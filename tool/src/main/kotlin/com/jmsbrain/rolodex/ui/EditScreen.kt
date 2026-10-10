package com.jmsbrain.rolodex.ui

import androidx.compose.foundation.layout.Box
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
import com.jmsbrain.rolodex.data.ContactField
import com.jmsbrain.rolodex.data.Contacts
import com.jmsbrain.rolodex.data.FieldKind
import com.jmsbrain.rolodex.data.dateForEditing
import com.jmsbrain.rolodex.data.dateForStorage
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Result of the editor: the saved contact's id, or "" if it was deleted. */
class EditViewModel(private val id: String?) : LightViewModel<String>() {
    /** Being edited. Dates are in MM/DD/YYYY form here. */
    val draft = MutableStateFlow<Contact?>(if (id == null) Contact(fields = listOf(ContactField(FieldKind.PHONE, "MOBILE", ""))) else null)
    private var original: Contact? = draft.value
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<String>) {
        if (id == null || draft.value != null) return
        viewModelScope.launch {
            val loaded = Contacts.source.get(id)?.let { c ->
                c.copy(fields = c.fields.map { if (it.kind == FieldKind.DATE) it.copy(value = dateForEditing(it.value)) else it })
            }
            original = loaded
            draft.value = loaded
        }
    }

    val changed get() = draft.value != original

    fun update(change: (Contact) -> Contact) {
        draft.value = draft.value?.let(change)
    }

    fun setField(index: Int, field: ContactField) = update { c -> c.copy(fields = c.fields.toMutableList().also { it[index] = field }) }
    fun removeField(index: Int) = update { c -> c.copy(fields = c.fields.toMutableList().also { it.removeAt(index) }) }
    fun addField(kind: FieldKind) = update { c -> c.copy(fields = c.fields + ContactField(kind, kind.defaultLabel, "")) }

    fun save(done: (String) -> Unit) {
        val d = draft.value ?: return
        val bad = d.fields.firstOrNull { it.kind == FieldKind.DATE && it.value.isNotBlank() && dateForStorage(it.value) == null }
        if (bad != null) {
            message.value = "Can't read the date \"${bad.value}\". Use MM/DD/YYYY, or MM/DD without a year."
            return
        }
        val toSave = d.copy(
            fields = d.fields.filter { it.value.isNotBlank() }
                .map { if (it.kind == FieldKind.DATE) it.copy(value = dateForStorage(it.value)!!) else it.copy(value = it.value.trim()) },
        )
        if (toSave.id.isBlank() && toSave == Contact()) return done("")
        busy.value = true
        viewModelScope.launch {
            runCatching { Contacts.source.save(toSave) }
                .onSuccess { done(it.id) }
                .onFailure { message.value = "Couldn't save this contact." }
            busy.value = false
        }
    }

    fun delete(done: () -> Unit) {
        val contactId = id ?: return
        viewModelScope.launch {
            runCatching { Contacts.source.delete(contactId) }
                .onSuccess { done() }
                .onFailure { message.value = "Couldn't delete this contact." }
        }
    }
}

class EditScreen(sealedActivity: SealedLightActivity, private val id: String?) :
    LightScreen<String, EditViewModel>(sealedActivity) {

    override val viewModelClass get() = EditViewModel::class.java
    override fun createViewModel() = EditViewModel(id)

    private fun cancel() {
        if (!viewModel.changed) return goBack(null)
        navigateTo({ ConfirmScreen(it, "Discard your changes?", "DISCARD") }) { discard -> if (discard) goBack(null) }
    }

    private fun editText(title: String, value: String, caps: Boolean = true, singleLine: Boolean = true, set: (String) -> Unit) =
        navigateTo({ TextEditScreen(it, title, value, caps, singleLine) }) { set(it) }

    @Composable
    override fun Content() {
        val draft by viewModel.draft.collectAsState()
        val message by viewModel.message.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val d = draft

        Box {
            RolodexPage(
                title = if (id == null) "New contact" else "Edit",
                left = LightBarButton.Text(text = "CANCEL", onClick = ::cancel),
                right = if (d != null && !busy) LightBarButton.Text(text = "SAVE", onClick = { viewModel.save { goBack(it) } }) else null,
                bottom = if (id != null) listOf(
                    LightBarButton.Text(text = "DELETE CONTACT", onClick = {
                        navigateTo({ ConfirmScreen(it, "Delete this contact? This can't be undone.", "DELETE") }) { yes ->
                            if (yes) viewModel.delete { goBack("") }
                        }
                    }),
                ) else emptyList(),
            ) {
                if (d == null) {
                    LightText("Loading…", LightTextVariant.Copy, lighten = true)
                    return@RolodexPage
                }
                Heading("NAME")
                Field("Prefix", d.prefix, "Dr., Rev.") { editText("Prefix", d.prefix) { v -> viewModel.update { it.copy(prefix = v) } } }
                Field("First", d.first, "First name") { editText("First name", d.first) { v -> viewModel.update { it.copy(first = v) } } }
                Field("Middle", d.middle, "Middle name") { editText("Middle name", d.middle) { v -> viewModel.update { it.copy(middle = v) } } }
                Field("Last", d.last, "Last name") { editText("Last name", d.last) { v -> viewModel.update { it.copy(last = v) } } }
                Field("Suffix", d.suffix, "Jr., PhD") { editText("Suffix", d.suffix) { v -> viewModel.update { it.copy(suffix = v) } } }

                Items(d, FieldKind.PHONE)
                Items(d, FieldKind.EMAIL)
                Items(d, FieldKind.ADDRESS)

                Heading("WORK")
                Field("Company", d.company, "Company") { editText("Company", d.company) { v -> viewModel.update { it.copy(company = v) } } }
                Field("Title", d.title, "Title") { editText("Title", d.title) { v -> viewModel.update { it.copy(title = v) } } }
                Field("Department", d.department, "Department") { editText("Department", d.department) { v -> viewModel.update { it.copy(department = v) } } }

                listOf(FieldKind.NICKNAME, FieldKind.RELATED, FieldKind.MESSAGING, FieldKind.SIP, FieldKind.WEBSITE, FieldKind.DATE, FieldKind.NOTE)
                    .forEach { Items(d, it) }
                Box(Modifier.padding(bottom = 2f.gridUnitsAsDp()))
            }
            message?.let { LightFullscreenModal(message = it, onClose = { viewModel.message.value = null }) }
        }
    }

    @Composable
    private fun Heading(title: String) {
        LightText(title, LightTextVariant.Detail, lighten = true, modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()))
    }

    @Composable
    private fun Field(label: String, value: String, placeholder: String, onClick: () -> Unit) =
        TapField(label, value, placeholder, onClick)

    /** One repeatable section: each value with its type and REMOVE, then ADD. */
    @Composable
    private fun Items(d: Contact, kind: FieldKind) {
        Heading(kind.title)
        d.fields.forEachIndexed { index, field ->
            if (field.kind != kind) return@forEachIndexed
            Row(Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp()), verticalAlignment = Alignment.CenterVertically) {
                if (kind.labels.isNotEmpty()) {
                    LightText(
                        text = field.label,
                        variant = LightTextVariant.Detail,
                        underline = true,
                        modifier = Modifier.lightClickable {
                            navigateTo({ ChoiceScreen(it, kind.title, kind.labels, field.label) }) { label ->
                                viewModel.setField(index, field.copy(label = label))
                            }
                        },
                    )
                }
                Box(Modifier.weight(1f))
                LightText(
                    text = "REMOVE",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.lightClickable { viewModel.removeField(index) },
                )
            }
            LightText(
                text = field.value.ifBlank { kind.placeholder },
                variant = LightTextVariant.Paragraph,
                lighten = field.value.isBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable {
                        editText(
                            kind.placeholder,
                            field.value,
                            caps = kind in setOf(FieldKind.ADDRESS, FieldKind.NICKNAME, FieldKind.RELATED, FieldKind.NOTE),
                            singleLine = kind != FieldKind.ADDRESS && kind != FieldKind.NOTE,
                        ) {
                            viewModel.setField(index, field.copy(value = it))
                        }
                    }
                    .padding(vertical = 0.4f.gridUnitsAsDp()),
            )
        }
        LightText(
            text = "+ ADD " + when (kind) {
                FieldKind.RELATED -> "PERSON"
                FieldKind.MESSAGING -> "HANDLE"
                else -> kind.name
            },
            variant = LightTextVariant.Fine,
            modifier = Modifier
                .lightClickable { viewModel.addField(kind) }
                .padding(vertical = 0.5f.gridUnitsAsDp()),
        )
    }
}
