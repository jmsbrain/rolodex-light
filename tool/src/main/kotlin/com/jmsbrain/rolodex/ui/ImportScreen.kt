package com.jmsbrain.rolodex.ui

import androidx.compose.foundation.layout.Box
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
import com.jmsbrain.rolodex.data.VCard
import com.jmsbrain.rolodex.sync.CardSync
import com.thelightphone.sdk.LightFileShare
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportViewModel(private val files: LightFileShare) : LightViewModel<String>() {
    val vcfFiles = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val reading = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<String>) {
        reading.value = false
        vcfFiles.value = files.list(Folders.IMPORT)
            .filter { it.isFile && (it.name.endsWith(".vcf", true) || it.name.endsWith(".vcard", true)) }
            .map { it.name }
            .sorted()
    }

    /** Reads [name]; [open] gets its contacts, [fail] what went wrong. One at a time, so a second tap can't open it twice. */
    fun read(name: String, open: (List<Contact>) -> Unit, fail: (String) -> Unit) {
        if (reading.value) return
        reading.value = true
        viewModelScope.launch {
            // Null = missing or unreadable, not just empty.
            val cards = withContext(Dispatchers.IO) {
                runCatching { files.read("${Folders.IMPORT}/$name") { VCard.parse(it.readText()) } }.getOrNull()
            }
            when {
                cards == null -> fail("Couldn't open $name.")
                cards.isEmpty() -> fail("No contacts found in $name.")
                else -> open(cards)
            }
        }
    }

    fun export() {
        viewModelScope.launch {
            val all = Contacts.source.list()
            withContext(Dispatchers.IO) {
                files.write("${Folders.EXPORT}/rolodex-contacts.vcf") { it.write(VCard.write(all)) }
            }
            message.value = "Saved ${all.size} contact(s). Download rolodex-contacts.vcf from Export contacts in Tool Manager."
        }
    }
}

/** Imports .vcf files uploaded through Tool Manager, and exports a backup. Returns the import result for the list. */
class ImportScreen(sealedActivity: SealedLightActivity) : LightScreen<String, ImportViewModel>(sealedActivity) {

    override val viewModelClass get() = ImportViewModel::class.java
    override fun createViewModel() = ImportViewModel(lightContext.fileShare)

    private fun open(name: String) = viewModel.read(
        name,
        open = { cards ->
            // One contact: check it in the editor. Several: a list.
            if (cards.size == 1) {
                navigateTo({ EditScreen(it, null, cards.single()) }) { id -> if (id.isNotEmpty()) goBack("Imported 1 contact from $name.") }
            } else {
                navigateTo({ ImportFileScreen(it, name, cards) }) { goBack(it) }
            }
        },
        fail = { goBack(it) },
    )

    @Composable
    override fun Content() {
        val vcfFiles by viewModel.vcfFiles.collectAsState()
        val message by viewModel.message.collectAsState()
        val reading by viewModel.reading.collectAsState()

        MessageOr(message, onClose = { viewModel.message.value = null }) {
            RolodexPage(
                title = "Import",
                left = backButton { goBack() },
                bottom = listOf(LightBarButton.Text(text = "EXPORT", onClick = viewModel::export)),
            ) {
                LightText(
                    "Upload .vcf files with Tool Manager (Rolodex → Import contacts), then tap a file to add its contacts.",
                    LightTextVariant.Paragraph,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
                if (reading) LightText("Reading…", LightTextVariant.Copy, lighten = true)
                else if (vcfFiles.isEmpty()) LightText("No files uploaded yet.", LightTextVariant.Copy, lighten = true)
                else vcfFiles.forEach { name ->
                    LightText(
                        text = name,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { open(name) }
                            .padding(vertical = 0.6f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

class ImportFileViewModel(cards: List<Contact>) : LightViewModel<String>() {
    /** Not added yet. One checked in the editor and saved leaves the list. */
    val left = MutableStateFlow(cards)
    val busy = MutableStateFlow(false)
    private var added = 0

    fun added(c: Contact) {
        left.value = left.value - c
        added++
    }

    /** What was added, for the list; null if nothing. */
    fun result(name: String, failed: Int = 0): String? = when {
        added == 0 && failed == 0 -> null
        failed == 0 -> if (added == 1) "Imported 1 contact from $name." else "Imported $added contacts from $name."
        else -> "Imported $added of ${added + failed} contacts from $name."
    }

    /** Saves the rest, then [done] gets the result. Once only, so a second tap can't import twice. */
    fun importAll(name: String, done: (String?) -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            val rest = left.value
            val saved = rest.count { c -> runCatching { Contacts.source.save(c.copy(id = "")) }.isSuccess }
            added += saved
            CardSync.syncSoon()
            done(result(name, rest.size - saved))
        }
    }
}

/** The contacts in one .vcf: IMPORT ALL, or tap one to check it first. Returns what was imported. */
class ImportFileScreen(sealedActivity: SealedLightActivity, private val name: String, private val cards: List<Contact>) :
    LightScreen<String, ImportFileViewModel>(sealedActivity) {

    override val viewModelClass get() = ImportFileViewModel::class.java
    override fun createViewModel() = ImportFileViewModel(cards)

    @Composable
    override fun Content() {
        val busy by viewModel.busy.collectAsState()
        val left by viewModel.left.collectAsState()

        RolodexPage(
            title = name,
            left = if (busy) null else backButton { goBack(viewModel.result(name)) },
            bottom = if (busy || left.isEmpty()) emptyList() else listOf(
                LightBarButton.Text(text = "IMPORT ALL", onClick = { viewModel.importAll(name) { goBack(it) } }),
            ),
        ) {
            if (busy) {
                LightText("Importing…", LightTextVariant.Copy, lighten = true)
                return@RolodexPage
            }
            LightText(
                if (left.isEmpty()) "All added." else "${left.size} contacts. Tap one to check it before adding it.",
                LightTextVariant.Paragraph,
                lighten = true,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            left.forEach { c ->
                LightText(
                    text = c.displayName,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { navigateTo({ EditScreen(it, null, c) }) { id -> if (id.isNotEmpty()) viewModel.added(c) } }
                        .padding(vertical = 0.6f.gridUnitsAsDp()),
                )
            }
            Box(Modifier.padding(bottom = 2f.gridUnitsAsDp()))
        }
    }
}
