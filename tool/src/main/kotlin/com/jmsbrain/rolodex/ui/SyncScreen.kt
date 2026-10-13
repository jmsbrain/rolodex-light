package com.jmsbrain.rolodex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.jmsbrain.rolodex.data.Contacts
import com.jmsbrain.rolodex.data.LocalContactsSource
import com.jmsbrain.rolodex.sync.Book
import com.jmsbrain.rolodex.sync.CardDav
import com.jmsbrain.rolodex.sync.CardSync
import com.jmsbrain.rolodex.sync.DavError
import com.jmsbrain.rolodex.sync.SyncSchedule
import com.jmsbrain.rolodex.sync.SyncSettings
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncViewModel : LightViewModel<Unit>() {
    private val store get() = Contacts.source as? LocalContactsSource

    /** Bumped to re-read the saved settings. */
    val version = MutableStateFlow(0)
    val email = MutableStateFlow("")
    val password = MutableStateFlow("")
    val server = MutableStateFlow(SyncSettings.DEFAULT_SERVER)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    /** Contacts in the sync account, and contacts only on this phone. */
    val counts = MutableStateFlow<Pair<Int, Int>?>(null)

    init {
        viewModelScope.launch { CardSync.state.collect { if (!it.running) refresh() } }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = refresh()

    fun refresh() {
        version.value++
        viewModelScope.launch {
            val s = store ?: return@launch
            counts.value = s.syncEntries().count { it.meta.href != null } to s.localOnlyCount()
        }
    }

    fun signIn(onSignedIn: () -> Unit) {
        val user = email.value.trim()
        val pw = password.value
        val url = server.value.trim()
        if (user.isEmpty() || pw.isEmpty() || url.isEmpty()) {
            message.value = "Enter your email, password and server."
            return
        }
        busy.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dav = CardDav(user, pw)
                    val home = dav.findHome(url)
                    val found = dav.addressBooks(home)
                    if (found.isEmpty()) throw DavError("Signed in, but this account has no address books.")
                    // Start with one book: the main one if it's obvious.
                    val first = found.firstOrNull { !it.readOnly && it.name.equals("Contacts", ignoreCase = true) }
                        ?: found.firstOrNull { !it.readOnly } ?: found.first()
                    val books = found.map { Book(it.url, it.name, it.readOnly, sync = it.url == first.url) }
                    SyncSettings.save(url, user, home, pw, books)
                }
            }
            busy.value = false
            result.onSuccess {
                password.value = ""
                onSignedIn()
                CardSync.syncSoon()
                refresh()
            }.onFailure { message.value = it.message ?: "Couldn't sign in." }
        }
    }

    fun changePassword(pw: String) {
        if (pw.isEmpty()) return
        busy.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CardDav(SyncSettings.user!!, pw).addressBooks(SyncSettings.home!!) }
            }
            busy.value = false
            result.onSuccess {
                SyncSettings.setPassword(pw)
                CardSync.syncSoon()
                message.value = "Password changed."
            }.onFailure { message.value = it.message ?: "Couldn't check that password." }
        }
    }

    fun setSync(book: Book, on: Boolean) {
        SyncSettings.saveBooks(SyncSettings.books.map { if (it.url == book.url) it.copy(sync = on, ctag = null) else it })
        CardSync.syncSoon()
        refresh()
    }

    fun setNewBook(url: String) {
        SyncSettings.setNewBook(url)
        refresh()
    }

    fun syncNow() = CardSync.syncSoon()

    fun dismissNotes() {
        SyncSettings.clearNotes()
        refresh()
    }

    fun adoptLocalOnly() {
        viewModelScope.launch {
            store?.adoptLocalOnly()
            CardSync.syncSoon()
            refresh()
        }
    }

    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            store?.forgetSync()
            SyncSettings.signOut()
            onSignedOut()
            refresh()
        }
    }
}

/** Sign in to a CardDAV server, pick address books, see how syncing is going. */
class SyncScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, SyncViewModel>(sealedActivity) {

    override val viewModelClass get() = SyncViewModel::class.java
    override fun createViewModel() = SyncViewModel()

    private fun edit(title: String, value: String, set: (String) -> Unit) =
        navigateTo({ TextEditScreen(it, title, value, caps = false) }) { set(it) }

    private fun confirm(message: String, action: String, then: () -> Unit) =
        navigateTo({ ConfirmScreen(it, message, action) }) { yes -> if (yes) then() }

    @Composable
    override fun Content() {
        viewModel.version.collectAsState().value
        val busy by viewModel.busy.collectAsState()
        val message by viewModel.message.collectAsState()
        val signedIn = SyncSettings.signedIn

        Box {
            RolodexPage(
                title = "Sync",
                left = backButton { goBack() },
                bottom = when {
                    busy -> emptyList()
                    signedIn -> listOf(LightBarButton.Text(text = "SYNC NOW", onClick = viewModel::syncNow))
                    else -> listOf(LightBarButton.Text(text = "SIGN IN", onClick = { viewModel.signIn { SyncSchedule.start(lightContext) } }))
                },
            ) {
                if (signedIn) SignedIn() else SignInForm(busy)
                Box(Modifier.padding(bottom = 2f.gridUnitsAsDp()))
            }
            message?.let { LightFullscreenModal(message = it, onClose = { viewModel.message.value = null }) }
        }
    }

    @Composable
    private fun SignInForm(busy: Boolean) {
        val email by viewModel.email.collectAsState()
        val password by viewModel.password.collectAsState()
        val server by viewModel.server.collectAsState()

        Dim(
            "Sign in to your contacts (CardDAV) server, such as mailbox.org, and Rolodex keeps your contacts in step with it. " +
                "If you use two-factor sign-in, use an app password.",
        )
        TapField("EMAIL", email, "you@mailbox.org") { edit("Email", email) { viewModel.email.value = it.trim() } }
        TapField("PASSWORD", "•".repeat(password.length), "Password") { edit("Password", "") { viewModel.password.value = it } }
        TapField("SERVER", server, SyncSettings.DEFAULT_SERVER) { edit("Server", server) { viewModel.server.value = it.trim() } }
        if (busy) LightText("Signing in…", LightTextVariant.Paragraph, lighten = true, modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
        Heading("PRIVACY")
        Dim("Your password stays on this phone, encrypted, and is only sent to the server above. Contacts already in Rolodex stay on this phone until you choose to sync them.")
    }

    @Composable
    private fun SignedIn() {
        val state by CardSync.state.collectAsState()
        val counts by viewModel.counts.collectAsState()

        LightText(SyncSettings.user.orEmpty(), LightTextVariant.Paragraph)
        Dim(SyncSettings.server.orEmpty())

        Heading("STATUS")
        val last = SyncSettings.lastSync
        val error = SyncSettings.lastError
        when {
            state.running -> Dim("Syncing…")
            error != null -> LightText(error, LightTextVariant.Paragraph)
            last != null -> Dim("Synced ${ago(last).lowercase()}" + (counts?.let { " · ${it.first} contacts" } ?: ""))
            else -> Dim("Not synced yet.")
        }

        val notes = SyncSettings.notes
        if (notes.isNotEmpty()) {
            Heading("ONLY ON THIS PHONE")
            Dim(
                "The server didn't keep these, probably for lack of room: mailbox.org holds two mobile, two work and two home numbers. " +
                    "To get one onto the server, change its type, e.g. a third mobile to OTHER.",
            )
            notes.forEach { LightText(it, LightTextVariant.Paragraph, modifier = Modifier.padding(top = 0.3f.gridUnitsAsDp())) }
            TextAction("DISMISS", viewModel::dismissNotes)
        }

        Heading("ADDRESS BOOKS")
        val books = SyncSettings.books
        books.forEach { book ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .lightClickable {
                        if (book.sync) {
                            confirm(
                                "Stop syncing ${book.name}? Its contacts leave Rolodex but stay on the server. Changes not yet synced are lost.",
                                "STOP SYNCING",
                            ) { viewModel.setSync(book, false) }
                        } else {
                            viewModel.setSync(book, true)
                        }
                    }
                    .padding(vertical = 0.4f.gridUnitsAsDp()),
            ) {
                LightText((if (book.sync) "●  " else "○  ") + book.name, LightTextVariant.Paragraph)
                if (book.readOnly) LightText("READ ONLY", LightTextVariant.Superfine, lighten = true)
            }
        }
        Dim("Tap to turn syncing on or off.")

        val writable = books.filter { it.sync && !it.readOnly }
        if (writable.size > 1) {
            val newBook = SyncSettings.newBook
            TapField("NEW CONTACTS GO TO", writable.firstOrNull { it.url == newBook }?.name.orEmpty(), "Choose") {
                val names = writable.map { it.name }
                navigateTo({ ChoiceScreen(it, "New contacts go to", names, writable.firstOrNull { b -> b.url == newBook }?.name) }) { name ->
                    writable.firstOrNull { it.name == name }?.let { viewModel.setNewBook(it.url) }
                }
            }
        }

        val localOnly = counts?.second ?: 0
        val target = writable.firstOrNull { it.url == SyncSettings.newBook }
        if (localOnly > 0 && target != null) {
            Heading("NOT SYNCED")
            Dim(
                if (localOnly == 1) "1 contact is only on this phone (added or imported before you signed in)."
                else "$localOnly contacts are only on this phone (added or imported before you signed in).",
            )
            TextAction("ADD TO ${target.name.uppercase()}") {
                confirm(
                    "Add $localOnly contact(s) to ${target.name}? If they're already on the server, you'll have them twice.",
                    "ADD",
                ) { viewModel.adoptLocalOnly() }
            }
        }

        Heading("ACCOUNT")
        TextAction("CHANGE PASSWORD") { edit("New password", "") { viewModel.changePassword(it) } }
        TextAction("SIGN OUT") {
            confirm(
                "Sign out? Synced contacts are removed from Rolodex. They stay on the server. Changes not yet synced are lost.",
                "SIGN OUT",
            ) {
                viewModel.signOut { SyncSchedule.stop(lightContext) }
            }
        }
    }
}
