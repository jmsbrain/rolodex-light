package com.jmsbrain.rolodex.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.jmsbrain.rolodex.data.Contact
import com.jmsbrain.rolodex.data.Contacts
import com.jmsbrain.rolodex.data.LocalContactsSource
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightText
import androidx.compose.foundation.layout.Box
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

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
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
        // Swap for PhoneContactsSource once LightOS allows it (ENABLE_PHONE_CONTACTS.md).
        Contacts.init { LocalContactsSource(lightContext.filesDir) }
    }

    override val viewModelClass get() = ContactListViewModel::class.java
    override fun createViewModel() = ContactListViewModel()

    @Composable
    override fun Content() {
        val contacts by viewModel.contacts.collectAsState()
        val query by viewModel.query.collectAsState()
        val q = query.trim()
        val shown = contacts.orEmpty().filter { q.isEmpty() || it.displayName.contains(q, ignoreCase = true) }
        val message by viewModel.message.collectAsState()

        val shownMessage = message
        // Instead of the page, not over it: the modal lets taps through.
        if (shownMessage != null) {
            LightFullscreenModal(message = shownMessage, onClose = { viewModel.message.value = null })
        } else {
            RolodexPage(
                title = "Rolodex",
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
                TapField("SEARCH", query, "Tap to search") {
                    navigateTo({ TextEditScreen(it, "Search", query) }) { viewModel.query.value = it }
                }
                Box(Modifier.padding(bottom = 1f.gridUnitsAsDp()))
                when {
                    contacts == null -> LightText("Loading…", LightTextVariant.Copy, lighten = true)
                    contacts.orEmpty().isEmpty() -> LightText(
                        if (Contacts.source.usesPhoneContacts) "No contacts on this phone."
                        else "No contacts yet. Tap ADD, or IMPORT a .vcf file.",
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
}
