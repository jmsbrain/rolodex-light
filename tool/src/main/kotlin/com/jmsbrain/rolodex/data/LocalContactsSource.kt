package com.jmsbrain.rolodex.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Sync state of one contact. [href] is its file on the server (null until uploaded), [base] the server's card at
 * the last sync, [snapshot] the contact's rows right after it. Comparing against those tells which side changed what.
 */
@Serializable
data class SyncMeta(
    val href: String? = null,
    val etag: String? = null,
    val base: String? = null,
    val snapshot: List<SyncRow>? = null,
    val dirty: Boolean = true,
    val version: Long = 0,
)

/** A synced contact deleted on the phone, still to delete on the server. */
@Serializable
data class Tombstone(val href: String, val etag: String?)

class SyncEntry(val contact: Contact, val meta: SyncMeta)

/**
 * Contacts kept in a JSON file inside the tool. Contacts with a [SyncMeta] belong to the sync account;
 * the rest are only on this phone. [syncNewContacts]: whether contacts made now go to the account.
 */
class LocalContactsSource(dir: File, private val syncNewContacts: () -> Boolean = { false }) : ContactsSource {

    override val usesPhoneContacts = false

    @Serializable
    private data class Store(
        val contacts: List<Contact> = emptyList(),
        val sync: Map<String, SyncMeta> = emptyMap(),
        val deleted: List<Tombstone> = emptyList(),
    )

    private val file = File(dir, "contacts.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var cache: Store? = null

    private suspend fun load(): Store = cache ?: withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext Store()
        val text = runCatching { file.readText() }.getOrDefault("")
        runCatching { json.decodeFromString<Store>(text) }.getOrNull()
            // Before 0.2.0 the file was just the list.
            ?: runCatching { Store(contacts = json.decodeFromString<List<Contact>>(text)) }.getOrDefault(Store())
    }.also { cache = it }

    private suspend fun write(store: Store) {
        cache = store
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(store))
            tmp.renameTo(file)
        }
    }

    override suspend fun list() = lock.withLock { load().contacts }.sortedBy { it.displayName.lowercase() }

    override suspend fun get(id: String) = lock.withLock { load().contacts.firstOrNull { it.id == id } }

    override suspend fun save(contact: Contact): Contact = lock.withLock {
        val s = load()
        val isNew = contact.id.isBlank() || s.contacts.none { it.id == contact.id }
        val saved = if (contact.id.isBlank()) contact.copy(id = UUID.randomUUID().toString()) else contact
        val meta = s.sync[saved.id]
        val sync = when {
            meta != null -> s.sync + (saved.id to meta.copy(dirty = true, version = meta.version + 1))
            isNew && syncNewContacts() -> s.sync + (saved.id to SyncMeta())
            else -> s.sync
        }
        write(s.copy(contacts = s.contacts.filterNot { it.id == saved.id } + saved, sync = sync))
        saved
    }

    override suspend fun delete(id: String) = lock.withLock {
        val s = load()
        val meta = s.sync[id]
        val tomb = meta?.href?.let { Tombstone(it, meta.etag) }
        write(s.copy(contacts = s.contacts.filterNot { it.id == id }, sync = s.sync - id, deleted = s.deleted + listOfNotNull(tomb)))
    }

    // ---- for sync ----

    suspend fun syncEntries(): List<SyncEntry> = lock.withLock {
        val s = load()
        s.contacts.mapNotNull { c -> s.sync[c.id]?.let { SyncEntry(c, it) } }
    }

    suspend fun tombstones(): List<Tombstone> = lock.withLock { load().deleted }

    suspend fun dropTombstone(href: String) = lock.withLock {
        val s = load()
        write(s.copy(deleted = s.deleted.filterNot { it.href == href }))
    }

    /** Adds contacts downloaded from the server. */
    suspend fun insertSynced(items: List<Pair<Contact, SyncMeta>>) = lock.withLock {
        if (items.isEmpty()) return@withLock
        val s = load()
        val added = items.map { (c, m) -> c.copy(id = UUID.randomUUID().toString()) to m }
        write(s.copy(contacts = s.contacts + added.map { it.first }, sync = s.sync + added.associate { (c, m) -> c.id to m }))
    }

    /**
     * Records a finished sync of [id]: [contact] and [meta] replace what's stored. If the contact was edited since it
     * was read ([readVersion]), the edit stays and uploads next time. Of the server's changes ([pullDown]), it takes
     * only kinds the edit didn't touch, so none are missed.
     */
    suspend fun finishSync(
        id: String,
        readVersion: Long,
        contact: Contact,
        meta: SyncMeta,
        readRows: List<SyncRow>,
        pullDown: Map<String, List<SyncRow>> = emptyMap(),
    ) = lock.withLock {
        val s = load()
        val now = s.contacts.firstOrNull { it.id == id }
        if (now == null) {
            // Deleted during the sync: delete the server's copy next time too.
            val href = meta.href
            if (href != null && s.deleted.none { it.href == href }) write(s.copy(deleted = s.deleted + Tombstone(href, meta.etag)))
            return@withLock
        }
        val current = s.sync[id] ?: return@withLock
        if (current.version == readVersion) {
            write(s.copy(
                contacts = s.contacts.map { if (it.id == id) contact.copy(id = id) else it },
                sync = s.sync + (id to meta.copy(dirty = false, version = current.version)),
            ))
        } else {
            val nowRows = ContactRows.toRows(now)
            val mine = SyncFields.byMime(nowRows)
            val read = SyncFields.byMime(readRows)
            val take = pullDown.filterKeys { SyncFields.same(mine[it].orEmpty(), read[it].orEmpty()) }
            val kept = if (take.isEmpty()) now else ContactRows.fromRows(SyncFields.replaceKinds(nowRows, take), id)
            write(s.copy(
                contacts = s.contacts.map { if (it.id == id) kept else it },
                sync = s.sync + (id to meta.copy(snapshot = SyncFields.replaceKinds(readRows, take), dirty = true, version = current.version)),
            ))
        }
    }

    /** Removes a contact from the phone only. */
    suspend fun deleteLocal(id: String) = lock.withLock {
        val s = load()
        write(s.copy(contacts = s.contacts.filterNot { it.id == id }, sync = s.sync - id))
    }

    /** Contacts that only live on this phone. */
    suspend fun localOnlyCount(): Int = lock.withLock { load().let { s -> s.contacts.count { it.id !in s.sync } } }

    /** Moves every phone-only contact into the sync account; they upload on the next sync. */
    suspend fun adoptLocalOnly() = lock.withLock {
        val s = load()
        write(s.copy(sync = s.sync + s.contacts.filter { it.id !in s.sync }.associate { it.id to SyncMeta() }))
    }

    /** Signed out: the account's contacts leave the phone. Phone-only contacts stay. */
    suspend fun forgetSync() = lock.withLock {
        val s = load()
        write(Store(contacts = s.contacts.filter { it.id !in s.sync }))
    }
}
