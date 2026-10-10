package com.jmsbrain.rolodex.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Contacts kept in a JSON file inside the tool. Nothing leaves the phone. */
class LocalContactsSource(dir: File) : ContactsSource {

    override val usesPhoneContacts = false

    private val file = File(dir, "contacts.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var cache: List<Contact>? = null

    private suspend fun all(): List<Contact> = cache ?: withContext(Dispatchers.IO) {
        if (file.isFile) runCatching { json.decodeFromString<List<Contact>>(file.readText()) }.getOrDefault(emptyList())
        else emptyList()
    }.also { cache = it }

    private suspend fun write(list: List<Contact>) {
        cache = list
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(list))
            tmp.renameTo(file)
        }
    }

    override suspend fun list() = lock.withLock { all() }.sortedBy { it.displayName.lowercase() }

    override suspend fun get(id: String) = lock.withLock { all() }.firstOrNull { it.id == id }

    override suspend fun save(contact: Contact): Contact = lock.withLock {
        val saved = if (contact.id.isBlank()) contact.copy(id = UUID.randomUUID().toString()) else contact
        val list = all().filterNot { it.id == saved.id } + saved
        write(list)
        saved
    }

    override suspend fun delete(id: String) = lock.withLock {
        write(all().filterNot { it.id == id })
    }
}
