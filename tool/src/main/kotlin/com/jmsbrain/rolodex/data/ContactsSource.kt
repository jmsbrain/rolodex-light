package com.jmsbrain.rolodex.data

/**
 * Where contacts live. Everything else in the tool only talks to this.
 *
 * Today: [LocalContactsSource] (a list inside the tool, filled by .vcf import).
 * Once LightOS allows contacts access: [PhoneContactsSource] (the phone's own,
 * synced contacts). See ENABLE_PHONE_CONTACTS.md.
 */
interface ContactsSource {
    /** True when edits reach the phone's contacts (and so DAVx5 etc.). */
    val usesPhoneContacts: Boolean

    suspend fun list(): List<Contact>
    suspend fun get(id: String): Contact?

    /** Saves and returns the stored contact (with its id). */
    suspend fun save(contact: Contact): Contact
    suspend fun delete(id: String)
}

/** The one place that decides which source the tool uses. */
object Contacts {
    lateinit var source: ContactsSource
        private set

    fun init(make: () -> ContactsSource) {
        if (!::source.isInitialized) source = make()
    }
}
