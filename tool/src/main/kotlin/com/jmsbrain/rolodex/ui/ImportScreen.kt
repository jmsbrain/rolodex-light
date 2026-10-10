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

class ImportViewModel(private val files: LightFileShare) : LightViewModel<Unit>() {
    val vcfFiles = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        vcfFiles.value = files.list(Folders.IMPORT)
            .filter { it.isFile && (it.name.endsWith(".vcf", true) || it.name.endsWith(".vcard", true)) }
            .map { it.name }
            .sorted()
    }

    fun import(name: String) {
        viewModelScope.launch {
            val cards = withContext(Dispatchers.IO) {
                files.read("${Folders.IMPORT}/$name") { VCard.parse(it.readText()) }.orEmpty()
            }
            cards.forEach { Contacts.source.save(it) }
            message.value = if (cards.isEmpty()) "No contacts found in $name." else "Imported ${cards.size} contact(s) from $name."
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
                            .lightClickable { viewModel.import(name) }
                            .padding(vertical = 0.6f.gridUnitsAsDp()),
                    )
                }
            }
            message?.let { LightFullscreenModal(message = it, onClose = { viewModel.message.value = null }) }
        }
    }
}
