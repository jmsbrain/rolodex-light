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
import com.jmsbrain.rolodex.Folders
import com.jmsbrain.rolodex.data.Contact
import com.jmsbrain.rolodex.data.Contacts
import com.jmsbrain.rolodex.data.FieldKind
import com.jmsbrain.rolodex.data.VCard
import com.jmsbrain.rolodex.data.dateForDisplay
import com.jmsbrain.rolodex.data.dialableNumber
import com.jmsbrain.rolodex.sync.CardSync
import com.thelightphone.sdk.LightFileShare
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
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What tapping an entry does. Long-press always copies. */
private enum class Tap { Dial, Copy, None }

/** A labelled value on a card. */
private class Entry(val label: String?, val value: String, val tap: Tap = Tap.None, val underline: Boolean = false)

private class Section(val title: String, val entries: List<Entry>)

class ContactViewModel(private val id: String, private val files: LightFileShare) : LightViewModel<Unit>() {
    val contact = MutableStateFlow<Contact?>(null)
    val loaded = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    init {
        // A sync may change what's shown.
        viewModelScope.launch { CardSync.state.collect { if (!it.running && loaded.value) reload() } }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = reload()

    private fun reload() {
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

    /** Saves the contact as a .vcf in Tool Manager's export folder. */
    fun saveVcf(c: Contact) {
        viewModelScope.launch {
            val name = c.displayName.replace(Regex("""[^\p{L}\p{N} ._-]"""), "").trim().ifEmpty { "contact" } + ".vcf"
            val ok = withContext(Dispatchers.IO) {
                runCatching { files.write("${Folders.EXPORT}/$name") { it.write(VCard.write(listOf(c))) } }.isSuccess
            }
            message.value = if (ok) "Saved $name. Download it from Export contacts in Tool Manager." else "Couldn't save the contact file."
        }
    }
}

class ContactScreen(sealedActivity: SealedLightActivity, private val id: String) :
    LightScreen<Unit, ContactViewModel>(sealedActivity) {

    override val viewModelClass get() = ContactViewModel::class.java
    override fun createViewModel() = ContactViewModel(id, lightContext.fileShare)

    @Composable
    override fun Content() {
        val contact by viewModel.contact.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val message by viewModel.message.collectAsState()
        val copy = rememberCopy()
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
                bottom = if (c != null) listOf(LightBarButton.Text(text = "SHARE", onClick = { share(c, copy) })) else emptyList(),
            ) {
                when {
                    c != null -> ContactCard(c, onDial = viewModel::dial, copy = copy)
                    loaded -> LightText("This contact no longer exists.", LightTextVariant.Copy)
                    else -> LightText("Loading…", LightTextVariant.Copy, lighten = true)
                }
            }
            message?.let { LightFullscreenModal(message = it, onClose = { viewModel.message.value = null }) }
        }
    }

    private fun share(c: Contact, copy: (String) -> Unit) {
        val asText = "COPY AS TEXT"
        val asFile = "SAVE AS CONTACT FILE (.VCF)"
        navigateTo({ ChoiceScreen(it, "Share", listOf(asText, asFile), null) }) { choice ->
            when (choice) {
                asText -> copy(shareText(c))
                asFile -> viewModel.saveVcf(c)
            }
        }
    }
}

@Composable
private fun ContactCard(c: Contact, onDial: (String) -> Unit, copy: (String) -> Unit) {
    LightText(
        c.displayName,
        LightTextVariant.Heading,
        modifier = Modifier.tapAndHold(onTap = null) { copy(c.displayName) },
    )
    val sections = sections(c)
    if (sections.isEmpty()) {
        LightText(
            "Nothing else saved for this contact.",
            LightTextVariant.Paragraph,
            lighten = true,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
    }
    sections.forEach { section ->
        Heading(section.title)
        section.entries.forEach { e ->
            val onTap: (() -> Unit)? = when (e.tap) {
                Tap.Dial -> { { onDial(e.value) } }
                Tap.Copy -> { { copy(e.value) } }
                Tap.None -> null
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .tapAndHold(onTap) { copy(e.value) }
                    .padding(vertical = 0.4f.gridUnitsAsDp()),
            ) {
                if (!e.label.isNullOrBlank()) LightText(e.label, LightTextVariant.Superfine, lighten = true)
                LightText(e.value, LightTextVariant.Paragraph, underline = e.underline)
            }
        }
    }
    Box(Modifier.padding(bottom = 2f.gridUnitsAsDp()))
}

/** The card's sections, in the editor's order. Copies of the same number or email show once. */
private fun sections(c: Contact): List<Section> {
    fun label(s: String) = s.uppercase(Locale.getDefault())
    fun fields(kind: FieldKind, tap: Tap = Tap.None, underline: Boolean = false, dedupe: (String) -> String = { it }): Section? {
        val seen = HashSet<String>()
        val entries = c.fieldsOf(kind).filter { seen.add(dedupe(it.value)) }.map { f ->
            val value = if (kind == FieldKind.DATE) dateForDisplay(f.value) else f.value
            Entry(label(f.label).takeIf { kind.labels.isNotEmpty() }, value, tap, underline)
        }
        return entries.takeIf { it.isNotEmpty() }?.let { Section(kind.title, it) }
    }

    // FULL NAME only when it adds something.
    val name = listOf("PREFIX" to c.prefix, "MIDDLE" to c.middle, "SUFFIX" to c.suffix, "PHONETIC" to c.phonetic).filter { it.second.isNotBlank() }
    val fullName = if (name.isEmpty()) null else Section(
        "FULL NAME",
        listOf("PREFIX" to c.prefix, "FIRST" to c.first, "MIDDLE" to c.middle, "LAST" to c.last, "SUFFIX" to c.suffix, "PHONETIC" to c.phonetic)
            .filter { it.second.isNotBlank() }
            .map { (l, v) -> Entry(l, v) },
    )
    val work = listOf("COMPANY" to c.company, "TITLE" to c.title, "DEPARTMENT" to c.department, "ROLE" to c.role)
        .filter { it.second.isNotBlank() }
        .map { (l, v) -> Entry(l, v) }

    return listOfNotNull(
        fullName,
        fields(FieldKind.PHONE, Tap.Dial) { dialableNumber(it) },
        fields(FieldKind.EMAIL, Tap.Copy) { it.lowercase(Locale.ROOT) },
        fields(FieldKind.ADDRESS, Tap.Copy),
        work.takeIf { it.isNotEmpty() }?.let { Section("WORK", it) },
        fields(FieldKind.NICKNAME),
        fields(FieldKind.RELATED),
        fields(FieldKind.MESSAGING),
        fields(FieldKind.SIP),
        fields(FieldKind.WEBSITE, Tap.Copy, underline = true),
        fields(FieldKind.DATE),
        fields(FieldKind.NOTE),
    )
}

/** Plain text for sharing: the name, then "Mobile: …" and so on. Leaves out notes. */
private fun shareText(c: Contact): String = buildString {
    append(c.displayName)
    for (section in sections(c)) {
        if (section.title == "FULL NAME" || section.title == "NOTES") continue
        for (e in section.entries) {
            val label = e.label?.takeIf { it.isNotBlank() } ?: section.title
            append('\n')
            append(label.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) })
            append(": ")
            append(e.value.replace("\n", ", "))
        }
    }
}
