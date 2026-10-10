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
import com.jmsbrain.rolodex.data.Contact
import com.jmsbrain.rolodex.data.ContactField
import com.jmsbrain.rolodex.data.Contacts
import com.jmsbrain.rolodex.data.FieldKind
import com.jmsbrain.rolodex.data.dateForDisplay
import com.jmsbrain.rolodex.data.dialableNumber
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.error
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ContactViewModel(private val id: String) : LightViewModel<Unit>() {
    val contact = MutableStateFlow<Contact?>(null)
    val loaded = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch {
            contact.value = Contacts.source.get(id)
            loaded.value = true
        }
    }

    /** Opens the LightOS phone screen with the number filled in. */
    fun dial(number: String) {
        viewModelScope.launch {
            val result = callRemoteServiceMethod(
                LightServiceMethod.OpenDialer,
                LightServiceMethod.OpenDialer.Request(phoneNumber = dialableNumber(number)),
            )
            if (result.error != null) message.value = "Couldn't open the phone screen."
        }
    }
}

class ContactScreen(sealedActivity: SealedLightActivity, private val id: String) :
    LightScreen<Unit, ContactViewModel>(sealedActivity) {

    override val viewModelClass get() = ContactViewModel::class.java
    override fun createViewModel() = ContactViewModel(id)

    @Composable
    override fun Content() {
        val contact by viewModel.contact.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val message by viewModel.message.collectAsState()
        val c = contact

        Box {
            RolodexPage(
                title = "",
                left = backButton { goBack() },
                right = c?.let {
                    LightBarButton.Text(text = "EDIT", onClick = {
                        // "" means it was deleted.
                        navigateTo({ EditScreen(it, c.id) }) { result -> if (result.isEmpty()) goBack() }
                    })
                },
            ) {
                when {
                    c != null -> ContactCard(c, onDial = viewModel::dial)
                    loaded -> LightText("This contact no longer exists.", LightTextVariant.Copy)
                    else -> LightText("Loading…", LightTextVariant.Copy, lighten = true)
                }
            }
            message?.let { LightFullscreenModal(message = it, onClose = { viewModel.message.value = null }) }
        }
    }
}

@Composable
private fun ContactCard(c: Contact, onDial: (String) -> Unit) {
    LightText(c.displayName, LightTextVariant.Heading)

    val name = listOf("PREFIX" to c.prefix, "MIDDLE" to c.middle, "SUFFIX" to c.suffix).filter { it.second.isNotBlank() }
    if (name.isNotEmpty()) {
        Section("FULL NAME") {
            (listOf("FIRST" to c.first) + name + ("LAST" to c.last)).filter { it.second.isNotBlank() }
                .forEach { (label, value) -> Entry(label, value) }
        }
    }
    FieldSection(c, FieldKind.PHONE) { f -> Entry(f.label, f.value, onTap = { onDial(f.value) }) }
    FieldSection(c, FieldKind.EMAIL)
    FieldSection(c, FieldKind.ADDRESS)
    val work = listOf("COMPANY" to c.company, "TITLE" to c.title, "DEPARTMENT" to c.department).filter { it.second.isNotBlank() }
    if (work.isNotEmpty()) Section("WORK") { work.forEach { (label, value) -> Entry(label, value) } }
    FieldSection(c, FieldKind.NICKNAME)
    FieldSection(c, FieldKind.RELATED)
    FieldSection(c, FieldKind.MESSAGING)
    FieldSection(c, FieldKind.SIP)
    FieldSection(c, FieldKind.WEBSITE)
    FieldSection(c, FieldKind.DATE) { f -> Entry(f.label, dateForDisplay(f.value)) }
    FieldSection(c, FieldKind.NOTE)
    Box(Modifier.padding(bottom = 2f.gridUnitsAsDp()))
}

@Composable
private fun FieldSection(
    c: Contact,
    kind: FieldKind,
    entry: @Composable (ContactField) -> Unit = { f -> Entry(f.label.takeIf { kind.labels.isNotEmpty() }, f.value) },
) {
    val fields = c.fieldsOf(kind)
    if (fields.isNotEmpty()) Section(kind.title) { fields.forEach { entry(it) } }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    LightText(title, LightTextVariant.Detail, lighten = true, modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()))
    content()
}

@Composable
private fun Entry(label: String?, value: String, onTap: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (onTap != null) it.lightClickable(onClick = onTap) else it }
            .padding(vertical = 0.4f.gridUnitsAsDp()),
    ) {
        if (!label.isNullOrBlank()) LightText(label, LightTextVariant.Fine, lighten = true)
        LightText(value, LightTextVariant.Paragraph, underline = onTap != null)
    }
}
