package com.jmsbrain.rolodex

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec

/** Folders shown in LightOS's Tool Manager (the browser page for moving files). */
object Folders {
    const val IMPORT = "import"
    const val EXPORT = "export"
}

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override fun getToolManagerManifest() = ClientToolManifest(
        title = "Rolodex Light",
        roots = listOf(
            ClientLeafNode(
                FileBrowserSpec(
                    label = "Import contacts",
                    path = Folders.IMPORT,
                    headerText = "Upload .vcf files here, then open Rolodex Light and tap IMPORT.",
                ),
            ),
            ClientLeafNode(
                FileBrowserSpec(
                    label = "Export contacts",
                    path = Folders.EXPORT,
                    headerText = "Tap EXPORT in Rolodex to save all contacts here as a .vcf file.",
                ),
            ),
        ),
    )
}
