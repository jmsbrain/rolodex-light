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
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportViewModel(private val files: LightFileShare) : LightViewModel<Unit>() {
    val vcfFiles = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        vcfFiles.value = files.list(Folders.IMPORT)
            .filter { it.isFile && (it.name.endsWith(".vcf", true) || it.name.endsWith(".vcard", true)) }
            .map { it.name }
            .sorted()
    }

    /** Reads [name]; [open] gets its contacts. */
    fun read(name: String, open: (List<Contact>) -> Unit) {
        viewModelScope.launch {
            // Null = missing or unreadable, not just empty.
            val cards = withContext(Dispatchers.IO) {
                runCatching { files.read("${Folders.IMPORT}/$name") { VCard.parse(it.readText()) } }.getOrNull()
            }
            when {
                cards == null -> message.value = "Couldn't open $name."
                cards.isEmpty() -> message.value = "No contacts found in $name."
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

/** Imports .vcf files uploaded through Tool Manager, and exports a backup. */
class ImportScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ImportViewModel>(sealedActivity) {

    override val viewModelClass get() = ImportViewModel::class.java
    override fun createViewModel() = ImportViewModel(lightContext.fileShare)

    private fun open(name: String) = viewModel.read(name) { cards ->
        // One contact: check it in the editor. Several: a list.
        if (cards.size == 1) navigateTo({ EditScreen(it, null, cards.single()) })
        else navigateTo({ ImportFileScreen(it, name, cards) })
    }

    @Composable
    override fun Content() {
        val vcfFiles by viewModel.vcfFiles.collectAsState()
        val message by viewModel.message.collectAsState()

        Box {
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
                if (vcfFiles.isEmpty()) LightText("No files uploaded yet.", LightTextVariant.Copy, lighten = true)
                vcfFiles.forEach { name ->
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
            message?.let { LightFullscreenModal(message = it, onClose = { viewModel.message.value = null }) }
        }
    }
}

class ImportFileViewModel(private val cards: List<Contact>) : LightViewModel<Unit>() {
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun importAll() {
        busy.value = true
        viewModelScope.launch {
            val saved = cards.count { c -> runCatching { Contacts.source.save(c.copy(id = "")) }.isSuccess }
            CardSync.syncSoon()
            busy.value = false
            message.value = if (saved == cards.size) "Imported $saved contacts." else "Imported $saved of ${cards.size} contacts."
        }
    }
}

/** The contacts in one .vcf: IMPORT ALL, or tap one to check it first. */
class ImportFileScreen(sealedActivity: SealedLightActivity, private val name: String, private val cards: List<Contact>) :
    LightScreen<Unit, ImportFileViewModel>(sealedActivity) {

    override val viewModelClass get() = ImportFileViewModel::class.java
    override fun createViewModel() = ImportFileViewModel(cards)

    @Composable
    override fun Content() {
        val message by viewModel.message.collectAsState()
        val busy by viewModel.busy.collectAsState()

        Box {
            RolodexPage(
                title = name,
                left = backButton { goBack() },
                bottom = if (busy) emptyList() else listOf(LightBarButton.Text(text = "IMPORT ALL", onClick = viewModel::importAll)),
            ) {
                LightText(
                    "${cards.size} contacts. Tap one to check it before adding it.",
                    LightTextVariant.Paragraph,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
                cards.forEach { c ->
                    LightText(
                        text = c.displayName,
                        variant = LightTextVariant.Copy,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { navigateTo({ EditScreen(it, null, c) }) }
                            .padding(vertical = 0.6f.gridUnitsAsDp()),
                    )
                }
            }
            message?.let {
                LightFullscreenModal(message = it, onClose = {
                    viewModel.message.value = null
                    goBack()
                })
            }
        }
    }
}
