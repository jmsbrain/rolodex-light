package com.jmsbrain.rolodex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.jmsbrain.rolodex.data.Contact
import com.jmsbrain.rolodex.data.Contacts
import com.jmsbrain.rolodex.setUp
import com.jmsbrain.rolodex.sync.CardSync
import com.jmsbrain.rolodex.sync.SyncSchedule
import com.jmsbrain.rolodex.sync.SyncSettings
import com.jmsbrain.rolodex.sync.SyncState
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ContactListViewModel : LightViewModel<Unit>() {
    private val all = MutableStateFlow<List<Contact>?>(null)
    val contacts: StateFlow<List<Contact>?> = all
    val query = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)

    /** Bumped on show, so the sync line re-reads its settings. */
    val shown = MutableStateFlow(0)

    init {
        // A finished sync may have changed contacts.
        viewModelScope.launch { CardSync.state.collect { if (!it.running) reload() } }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        shown.value++
        reload()
        CardSync.syncIfStale()
    }

    private fun reload() {
        viewModelScope.launch { all.value = Contacts.source.list() }
    }

    override fun onBackPressed(): Boolean {
        if (query.value.isEmpty()) return false
        query.value = ""
        return true
    }
}

@InitialScreen
class ContactListScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ContactListViewModel>(sealedActivity) {

    init {
        setUp(lightContext.filesDir)
        if (SyncSettings.signedIn) SyncSchedule.start(lightContext)
    }

    override val viewModelClass get() = ContactListViewModel::class.java
    override fun createViewModel() = ContactListViewModel()

    @Composable
    override fun Content() {
        val message by viewModel.message.collectAsState()
        MessageOr(message, onClose = { viewModel.message.value = null }) { Page() }
    }

    @Composable
    private fun Page() {
        val contacts by viewModel.contacts.collectAsState()
        val query by viewModel.query.collectAsState()
        val syncState by CardSync.state.collectAsState()
        viewModel.shown.collectAsState().value
        val q = query.trim()
        val shown = contacts.orEmpty().filter { q.isEmpty() || it.displayName.contains(q, ignoreCase = true) }

        RolodexPage(
            title = "Rolodex Light",
            bottom = buildList {
                add(LightBarButton.Text(text = "ADD", onClick = {
                    navigateTo({ EditScreen(it, null) }) { id -> if (id.isNotEmpty()) navigateTo({ ContactScreen(it, id) }) }
                }))
                if (!Contacts.source.usesPhoneContacts) {
                    add(LightBarButton.Text(text = "IMPORT", onClick = {
                        navigateTo({ ImportScreen(it) }) { viewModel.message.value = it }
                    }))
                }
            },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    TapField("SEARCH", query, "Tap to search") {
                        navigateTo({ TextEditScreen(it, "Search", query) }) { viewModel.query.value = it }
                    }
                }
                if (query.isNotEmpty()) {
                    LightText(
                        text = "CLEAR",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier
                            .lightClickable { viewModel.query.value = "" }
                            .padding(start = 1f.gridUnitsAsDp(), top = 0.5f.gridUnitsAsDp(), bottom = 0.3f.gridUnitsAsDp()),
                    )
                }
            }
            SyncLine(syncState) { navigateTo({ SyncScreen(it) }) }
            Box(Modifier.padding(bottom = 0.5f.gridUnitsAsDp()))
            when {
                contacts == null -> LightText("Loading…", LightTextVariant.Copy, lighten = true)
                contacts.orEmpty().isEmpty() -> LightText(
                    if (Contacts.source.usesPhoneContacts) "No contacts on this phone."
                    else "No contacts yet. Tap ADD, IMPORT a .vcf file, or tap the sync line to sign in to your contacts server.",
                    LightTextVariant.Copy,
                    lighten = true,
                )
                shown.isEmpty() -> LightText("No matches.", LightTextVariant.Copy, lighten = true)
                else -> shown.forEach { contact ->
                    LightText(
                        text = contact.displayName,
                        variant = LightTextVariant.Copy,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { navigateTo({ ContactScreen(it, contact.id) }) }
                            .padding(vertical = 0.6f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

/** Under the search box: how syncing is going. Tapping it opens the sync screen. */
@Composable
private fun SyncLine(state: SyncState, onOpen: () -> Unit) {
    val (text, attention) = when {
        !SyncSettings.signedIn -> "SYNC: OFF" to false
        state.running -> "SYNCING…" to false
        SyncSettings.lastError != null -> "SYNC PROBLEM: TAP FOR DETAILS" to true
        SyncSettings.notes.isNotEmpty() -> "SOME DETAILS ONLY ON THIS PHONE: TAP" to true
        else -> (SyncSettings.lastSync?.let { "SYNCED ${ago(it)}" } ?: "SYNC: ON") to false
    }
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = !attention,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
            .padding(top = 0.6f.gridUnitsAsDp(), bottom = 0.4f.gridUnitsAsDp()),
    )
}
