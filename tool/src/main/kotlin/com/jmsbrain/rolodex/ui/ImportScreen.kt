package com.jmsbrain.rolodex.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.jmsbrain.rolodex.Folders
import com.jmsbrain.rolodex.data.Contacts
import com.jmsbrain.rolodex.data.VCard
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

class ImportViewModel(private val files: LightFileShare) : LightViewModel<String>() {
    val vcfFiles = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<String>) {
        vcfFiles.value = files.list(Folders.IMPORT)
            .filter { it.isFile && (it.name.endsWith(".vcf", true) || it.name.endsWith(".vcard", true)) }
            .map { it.name }
            .sorted()
    }

    /** Imports one file, then [done] gets the result. One at a time, so a second tap can't import twice. */
    fun import(name: String, done: (String) -> Unit) {
        if (importing.value) return
        importing.value = true
        viewModelScope.launch {
            // Null = missing or unreadable, not just empty.
            val cards = withContext(Dispatchers.IO) {
                runCatching { files.read("${Folders.IMPORT}/$name") { VCard.parse(it.readText()) } }.getOrNull()
            }
            cards?.forEach { Contacts.source.save(it) }
            done(when {
                cards == null -> "Couldn't open $name."
                cards.isEmpty() -> "No contacts found in $name."
                else -> "Imported ${cards.size} contact(s) from $name."
            })
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

/** Imports .vcf files uploaded through Tool Manager, and exports a backup. Returns the import result. */
class ImportScreen(sealedActivity: SealedLightActivity) : LightScreen<String, ImportViewModel>(sealedActivity) {

    override val viewModelClass get() = ImportViewModel::class.java
    override fun createViewModel() = ImportViewModel(lightContext.fileShare)

    @Composable
    override fun Content() {
        val vcfFiles by viewModel.vcfFiles.collectAsState()
        val message by viewModel.message.collectAsState()
        val importing by viewModel.importing.collectAsState()

        val shownMessage = message
        // Instead of the page, not over it: the modal lets taps through.
        if (shownMessage != null) {
            LightFullscreenModal(message = shownMessage, onClose = { viewModel.message.value = null })
        } else {
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
                if (importing) LightText("Importing…", LightTextVariant.Copy, lighten = true)
                else if (vcfFiles.isEmpty()) LightText("No files uploaded yet.", LightTextVariant.Copy, lighten = true)
                else vcfFiles.forEach { name ->
                    LightText(
                        text = name,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.import(name) { goBack(it) } }
                            .padding(vertical = 0.6f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
