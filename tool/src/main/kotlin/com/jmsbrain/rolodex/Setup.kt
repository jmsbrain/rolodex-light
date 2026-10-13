package com.jmsbrain.rolodex

import com.jmsbrain.rolodex.data.Contacts
import com.jmsbrain.rolodex.data.LocalContactsSource
import com.jmsbrain.rolodex.sync.SyncSettings
import java.io.File

/** Opens storage once per process. Screens and the background sync both call it. */
fun setUp(filesDir: File) {
    SyncSettings.init(filesDir)
    // Swap for PhoneContactsSource once LightOS allows it (ENABLE_PHONE_CONTACTS.md).
    Contacts.init { LocalContactsSource(filesDir) { SyncSettings.signedIn } }
}
