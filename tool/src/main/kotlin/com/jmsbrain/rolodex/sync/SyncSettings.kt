package com.jmsbrain.rolodex.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An address book on the server, and whether this phone syncs it. */
@Serializable
data class Book(val url: String, val name: String, val readOnly: Boolean, val sync: Boolean, val ctag: String? = null)

/** The sign-in and sync status, in a file inside the tool. The password is encrypted with a key in Android's Keystore. */
object SyncSettings {
    const val DEFAULT_SERVER = "https://dav.mailbox.org/"

    @Serializable
    private data class Saved(
        val server: String? = null,
        val user: String? = null,
        val home: String? = null,
        val password: String? = null,
        val books: List<Book> = emptyList(),
        val newBook: String? = null,
        val lastSync: Long = 0,
        val lastError: String? = null,
        val notes: List<String> = emptyList(),
    )

    private var file: File? = null
    private var cache: Saved? = null
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun init(dir: File) {
        if (file == null) file = File(dir, "sync.json")
    }

    @Synchronized
    private fun read(): Saved = cache ?: (file?.takeIf { it.isFile }
        ?.let { f -> runCatching { json.decodeFromString<Saved>(f.readText()) }.getOrNull() }
        ?: Saved()).also { cache = it }

    @Synchronized
    private fun update(change: (Saved) -> Saved) {
        val s = change(read())
        cache = s
        val f = file ?: return
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(json.encodeToString(s))
        tmp.renameTo(f)
    }

    val user: String? get() = read().user
    val server: String? get() = read().server
    val home: String? get() = read().home
    val signedIn: Boolean get() = read().user != null

    fun save(server: String, user: String, home: String, password: String, books: List<Book>) {
        val pw = encrypt(password)
        update {
            Saved(
                server = server, user = user, home = home, password = pw, books = books,
                newBook = books.firstOrNull { it.sync && !it.readOnly }?.url,
            )
        }
    }

    fun setPassword(password: String) {
        val pw = encrypt(password)
        update { it.copy(password = pw) }
    }

    val password: String? get() = read().password?.let { runCatching { decrypt(it) }.getOrNull() }

    val books: List<Book> get() = read().books

    fun saveBooks(books: List<Book>) = update { it.copy(books = books) }

    /** Where contacts made on the phone go. */
    val newBook: String?
        get() {
            val s = read()
            val writable = s.books.filter { it.sync && !it.readOnly }
            return writable.firstOrNull { it.url == s.newBook }?.url ?: writable.firstOrNull()?.url
        }

    fun setNewBook(url: String?) = update { it.copy(newBook = url) }

    // ---- status ----

    val lastSync: Long? get() = read().lastSync.takeIf { it > 0 }
    val lastError: String? get() = read().lastError

    fun recordResult(error: String?) = update {
        if (error == null) it.copy(lastError = null, lastSync = System.currentTimeMillis()) else it.copy(lastError = error)
    }

    /** Values the server didn't keep, so they're only on this phone ("Name: value"). */
    val notes: List<String> get() = read().notes

    /** Replaces the notes for [name] (none, once everything reached the server). */
    fun setNotes(name: String, notes: List<String>) = update { s ->
        s.copy(notes = (s.notes.filterNot { it.startsWith("$name: ") } + notes).distinct())
    }

    fun clearNotes() = update { it.copy(notes = emptyList()) }

    /** Forgets the sign-in. */
    fun signOut() = update { Saved() }

    // ---- password ----

    private const val KEY_ALIAS = "rolodex-sync-password"
    private const val IV_LENGTH = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** IV + ciphertext, base64. */
    private fun encrypt(password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(cipher.iv.size + ct.size).put(cipher.iv).put(ct).array())
    }

    private fun decrypt(saved: String): String {
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(saved))
        val iv = ByteArray(IV_LENGTH).also { buf.get(it) }
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
