package com.jmsbrain.rolodex.sync

import com.jmsbrain.rolodex.data.Contact
import com.jmsbrain.rolodex.data.ContactRows
import com.jmsbrain.rolodex.data.Contacts
import com.jmsbrain.rolodex.data.LocalContactsSource
import com.jmsbrain.rolodex.data.SyncEntry
import com.jmsbrain.rolodex.data.SyncFields
import com.jmsbrain.rolodex.data.SyncMeta
import com.jmsbrain.rolodex.data.SyncRow
import com.jmsbrain.rolodex.data.Tombstone
import com.jmsbrain.rolodex.data.VCardDoc
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SyncState(val running: Boolean = false, val finished: Int = 0)

/** Two-way sync between the server and the contacts in the sync account (see [SyncMeta]). */
object CardSync {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private const val FETCH_BATCH = 50
    private const val INSERT_BATCH = 40
    private const val AUTO_SYNC_MS = 10 * 60 * 1000L

    /** Runs a sync in the background (after an edit, or when the tool opens). */
    fun syncSoon() {
        if (!SyncSettings.signedIn) return
        scope.launch { sync() }
    }

    /** Syncs if the last one was a while ago. */
    fun syncIfStale() {
        val last = SyncSettings.lastSync ?: 0
        if (System.currentTimeMillis() - last > AUTO_SYNC_MS) syncSoon()
    }

    /** Runs [block] once no sync is running, e.g. signing out. */
    suspend fun <T> whenIdle(block: suspend () -> T): T = lock.withLock { block() }

    /** Returns the error, or null if it worked. Waits if a sync is already running. */
    suspend fun sync(): String? = lock.withLock {
        val store = Contacts.source as? LocalContactsSource
        if (!SyncSettings.signedIn || store == null) return@withLock null
        _state.update { it.copy(running = true) }
        var error: String? = null
        try {
            error = withContext(Dispatchers.IO) { Run(store).all() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DavError) {
            error = e.message
        } catch (e: Exception) {
            error = "Sync stopped: ${e.message ?: "unexpected error"}"
        } finally {
            if (SyncSettings.signedIn) SyncSettings.recordResult(error)
            _state.update { SyncState(running = false, finished = it.finished + 1) }
        }
        error
    }

    private class Run(private val store: LocalContactsSource) {
        private lateinit var dav: CardDav
        private val problems = ArrayList<String>()

        suspend fun all(): String? {
            val user = SyncSettings.user ?: return null
            val password = SyncSettings.password ?: return "Rolodex lost your password. Sign in again."
            val home = SyncSettings.home ?: return "Rolodex lost your sign-in. Sign in again."
            dav = CardDav(user, password)

            // Refresh the list of address books, keeping choices and sync state.
            val saved = SyncSettings.books.associateBy { it.url }
            val remote = dav.addressBooks(home)
            val books = remote.map { r ->
                val old = saved[r.url]
                Book(r.url, r.name, r.readOnly, old?.sync ?: false, old?.ctag)
            }
            val synced = books.filter { it.sync }
            val newBook = SyncSettings.newBook.takeIf { url -> synced.any { it.url == url && !it.readOnly } }
                ?: synced.firstOrNull { !it.readOnly }?.url

            // Contacts from books no longer synced (or gone) leave the phone.
            for (e in store.syncEntries()) {
                val href = e.meta.href ?: continue
                if (synced.none { inBook(href, it.url) }) store.deleteLocal(e.contact.id)
            }
            for (t in store.tombstones()) {
                if (synced.none { inBook(t.href, it.url) }) store.dropTombstone(t.href)
            }

            // A book turned off forgets its state, so turning it back on downloads it all again.
            val updated = books.map { if (it.sync) it else it.copy(ctag = null) }.toMutableList()
            for (book in synced) {
                val mine = store.syncEntries().filter { e -> e.meta.href?.let { inBook(it, book.url) } ?: (book.url == newBook) }
                val deleted = store.tombstones().filter { inBook(it.href, book.url) }
                // Read-only: changes can't go up, so deletions just come back.
                if (book.readOnly) deleted.forEach { store.dropTombstone(it.href) }
                val uploaded = if (book.readOnly) deleted.isNotEmpty() else upload(book, mine, deleted)
                val ctag = remote.first { it.url == book.url }.ctag
                // Unchanged on the server since last time: skip (unless the phone has none of it yet).
                if (!uploaded && ctag != null && ctag == book.ctag && mine.isNotEmpty()) continue
                download(book)
                val i = updated.indexOfFirst { it.url == book.url }
                updated[i] = updated[i].copy(ctag = ctag)
            }
            // A book switched on or off during this sync keeps its new setting; the next sync applies it.
            val latest = SyncSettings.books.associateBy { it.url }
            SyncSettings.saveBooks(updated.map { u -> latest[u.url]?.takeIf { it.sync != u.sync } ?: u })
            return problems.firstOrNull()?.let { if (problems.size > 1) "$it (and ${problems.size - 1} more)" else it }
        }

        // ---- phone → server ----

        /** Sends the phone's changes. Returns true if anything was sent. */
        private suspend fun upload(book: Book, mine: List<SyncEntry>, deleted: List<Tombstone>): Boolean {
            var sent = false
            for (t in deleted) {
                try {
                    try {
                        dav.delete(t.href, t.etag)
                    } catch (e: DavError) {
                        // 412: changed elsewhere since; the download brings it back.
                        if (e.status != 412) throw e
                    }
                    store.dropTombstone(t.href)
                    sent = true
                } catch (e: DavError) {
                    if (e.status == 401) throw e
                    problems += "Couldn't send a change: ${e.message}"
                }
            }
            for (e in mine) {
                try {
                    when {
                        e.meta.href == null -> {
                            create(book, e)
                            sent = true
                        }
                        e.meta.dirty -> if (update(e)) sent = true
                    }
                } catch (err: DavError) {
                    if (err.status == 401) throw err
                    problems += "Couldn't send a change: ${err.message}"
                }
            }
            return sent
        }

        private suspend fun create(book: Book, e: SyncEntry) {
            val rows = ContactRows.toRows(e.contact)
            var uid = UUID.randomUUID().toString()
            for (attempt in 0 until 3) {
                val url = book.url.trimEnd('/') + "/" + uid + ".vcf"
                val card = VCardDoc.fromRows(rows, uid)
                val etag = try {
                    dav.put(url, card.text(), null, create = true)
                } catch (err: DavError) {
                    if (err.status != 412 || attempt == 2) throw err
                    uid = UUID.randomUUID().toString()
                    continue
                }
                checkKept(e, rows, url, etag, card, SyncFields.mimes, emptyMap())
                return
            }
        }

        /** Merges with the server's copy and sends it. Returns true if anything was sent. */
        private suspend fun update(e: SyncEntry): Boolean {
            val href = e.meta.href!!
            val local = ContactRows.toRows(e.contact)
            val snapshot = e.meta.snapshot
            val base = e.meta.base?.let { VCardDoc.parseOne(it) }
            repeat(3) { attempt ->
                val server = dav.get(href)?.let { f -> VCardDoc.parseOne(f.text)?.let { f to it } }
                if (server == null) {
                    // Deleted elsewhere, but edited here: put it back.
                    val card = VCardDoc.fromRows(local, base?.uid ?: UUID.randomUUID().toString())
                    val etag = dav.put(href, card.text(), null, create = true)
                    checkKept(e, local, href, etag, card, SyncFields.mimes, emptyMap())
                    return true
                }
                val (file, doc) = server
                val merged = SyncMerge.merge(doc, base, local, snapshot)
                val text = merged.card.text()
                if (text == doc.text()) {
                    finish(e, local, href, file.etag, doc, merged.pullDown)
                    return false
                }
                try {
                    val etag = dav.put(href, text, file.etag, create = false)
                    checkKept(e, local, href, etag, merged.card, merged.replaced, merged.pullDown)
                    return true
                } catch (err: DavError) {
                    // Changed elsewhere in between: merge again.
                    if (err.status != 412 || attempt == 2) throw err
                }
            }
            return false
        }

        /**
         * Reads back what the server stored. Values it dropped (OX has room for only two mobile numbers, for example)
         * stay on the phone and are listed on the sync screen; kinds it kept in full take the server's version.
         */
        private suspend fun checkKept(
            e: SyncEntry,
            readRows: List<SyncRow>,
            href: String,
            etag: String?,
            sent: VCardDoc,
            sentKinds: Set<String>,
            pullDown: Map<String, List<SyncRow>>,
        ) {
            val file = dav.get(href)
            val stored = file?.let { VCardDoc.parseOne(it.text) }
            if (file == null || stored == null) {
                finish(e, readRows, href, etag, sent, pullDown)
                return
            }
            val lost = SyncMerge.lost(sent, stored, sentKinds)
            val lostKinds = lost.map { it.mime }.toSet()
            val storedRows = SyncFields.byMime(stored.rows())
            val sentRows = SyncFields.byMime(sent.rows())
            val take = sentKinds
                .filter { it !in lostKinds && !SyncFields.same(storedRows[it].orEmpty(), sentRows[it].orEmpty()) }
                .associateWith { storedRows[it].orEmpty() }
            finish(e, readRows, href, file.etag ?: etag, stored, pullDown + take)
            val name = stored.first("FN")?.text?.ifBlank { null } ?: sent.first("FN")?.text.orEmpty()
            SyncSettings.setNotes(name, lost.map { "$name: ${it["data1"] ?: it.values.values.first()}" })
        }

        // ---- server → phone ----

        private suspend fun download(book: Book) {
            val remote = dav.list(book.url)
            val locals = store.syncEntries()
                .filter { e -> e.meta.href?.let { inBook(it, book.url) } == true }
                .associateBy { key(it.meta.href!!) }
            val deleting = store.tombstones().map { key(it.href) }.toSet()
            val wanted = remote.filter { (url, etag) ->
                val k = key(url)
                val l = locals[k]
                if (l == null) k !in deleting else !l.meta.dirty && (etag == null || etag != l.meta.etag)
            }.keys.toList()

            val inserts = ArrayList<Pair<Contact, SyncMeta>>()
            for (chunk in wanted.chunked(FETCH_BATCH)) {
                val files = try {
                    dav.multiget(book.url, chunk)
                } catch (e: DavError) {
                    if (e.status == 401) throw e
                    chunk.mapNotNull { dav.get(it) }
                }
                for (f in files) {
                    val doc = VCardDoc.parseOne(f.text) ?: continue
                    if (doc.isGroup) continue
                    val l = locals[key(f.url)]
                    if (l == null) {
                        val c = ContactRows.fromRows(doc.rows())
                        inserts += c to SyncMeta(
                            href = f.url, etag = f.etag, base = doc.withoutBinary().text(),
                            snapshot = ContactRows.toRows(c), dirty = false,
                        )
                        if (inserts.size >= INSERT_BATCH) {
                            store.insertSynced(inserts.toList())
                            inserts.clear()
                        }
                    } else {
                        applyServer(l, f, doc)
                    }
                }
            }
            store.insertSynced(inserts)

            val remoteKeys = remote.keys.map(::key).toSet()
            for ((k, l) in locals) {
                if (k !in remoteKeys && !l.meta.dirty) store.deleteLocal(l.contact.id)
            }
        }

        private suspend fun applyServer(l: SyncEntry, f: CardFile, doc: VCardDoc) {
            val local = ContactRows.toRows(l.contact)
            val merged = SyncMerge.merge(doc, l.meta.base?.let { VCardDoc.parseOne(it) }, local, local)
            finish(l, local, l.meta.href!!, f.etag, doc, merged.pullDown)
        }

        /**
         * Records a finished sync: replaces the [pullDown] kinds with the server's rows, then stores the URL, ETag,
         * the card and a snapshot. If the contact was edited meanwhile, the edit is kept and uploads next time.
         */
        private suspend fun finish(
            e: SyncEntry,
            readRows: List<SyncRow>,
            href: String,
            etag: String?,
            card: VCardDoc,
            pullDown: Map<String, List<SyncRow>>,
        ) {
            val contact = if (pullDown.isEmpty()) {
                e.contact
            } else {
                ContactRows.fromRows(SyncFields.replaceKinds(readRows, pullDown), e.contact.id)
            }
            val meta = SyncMeta(href = href, etag = etag, base = card.withoutBinary().text(), snapshot = ContactRows.toRows(contact))
            store.finishSync(e.contact.id, e.meta.version, contact, meta, readRows, pullDown)
        }
    }

    // ---- helpers ----

    /** Compares URLs by decoded path, since servers encode hrefs differently. */
    private fun key(url: String): String = url.toHttpUrlOrNull()?.pathSegments?.joinToString("/") ?: url

    private fun inBook(href: String, book: String): Boolean {
        val b = key(book).trimEnd('/')
        val h = key(href)
        return h.startsWith("$b/")
    }
}
