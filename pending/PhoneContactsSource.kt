@file:Suppress("DEPRECATION") // Im, SipAddress

package com.jmsbrain.rolodex.data

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's own contacts (synced by DAVx5 etc.). Not used yet: tools can't
 * get a ContentResolver or contacts permission. See ENABLE_PHONE_CONTACTS.md.
 * Ported from https://github.com/jmsbrain/Rolodex (ContactsRepository/ContactWriter).
 */
class PhoneContactsSource(private val resolver: ContentResolver) : ContactsSource {

    override val usesPhoneContacts = true

    // ---- Type tables ----

    private val phoneTypes = mapOf(
        "MOBILE" to Phone.TYPE_MOBILE, "HOME" to Phone.TYPE_HOME, "WORK" to Phone.TYPE_WORK,
        "OTHER" to Phone.TYPE_OTHER, "FAX" to Phone.TYPE_FAX_HOME, "WORK FAX" to Phone.TYPE_FAX_WORK,
        "PAGER" to Phone.TYPE_PAGER, "MAIN" to Phone.TYPE_MAIN,
    )
    private val emailTypes = mapOf("HOME" to Email.TYPE_HOME, "WORK" to Email.TYPE_WORK, "OTHER" to Email.TYPE_OTHER, "MOBILE" to Email.TYPE_MOBILE)
    private val postalTypes = mapOf("HOME" to StructuredPostal.TYPE_HOME, "WORK" to StructuredPostal.TYPE_WORK, "OTHER" to StructuredPostal.TYPE_OTHER)
    private val eventTypes = mapOf("BIRTHDAY" to Event.TYPE_BIRTHDAY, "ANNIVERSARY" to Event.TYPE_ANNIVERSARY, "OTHER" to Event.TYPE_OTHER)
    private val relationTypes = mapOf(
        "ASSISTANT" to Relation.TYPE_ASSISTANT, "BROTHER" to Relation.TYPE_BROTHER, "CHILD" to Relation.TYPE_CHILD,
        "DOMESTIC PARTNER" to Relation.TYPE_DOMESTIC_PARTNER, "FATHER" to Relation.TYPE_FATHER, "FRIEND" to Relation.TYPE_FRIEND,
        "MANAGER" to Relation.TYPE_MANAGER, "MOTHER" to Relation.TYPE_MOTHER, "PARENT" to Relation.TYPE_PARENT,
        "PARTNER" to Relation.TYPE_PARTNER, "REFERRED BY" to Relation.TYPE_REFERRED_BY, "RELATIVE" to Relation.TYPE_RELATIVE,
        "SISTER" to Relation.TYPE_SISTER, "SPOUSE" to Relation.TYPE_SPOUSE,
    )
    private val imProtocols = mapOf(
        "AIM" to Im.PROTOCOL_AIM, "MSN" to Im.PROTOCOL_MSN, "YAHOO" to Im.PROTOCOL_YAHOO, "SKYPE" to Im.PROTOCOL_SKYPE,
        "QQ" to Im.PROTOCOL_QQ, "GOOGLE TALK" to Im.PROTOCOL_GOOGLE_TALK, "ICQ" to Im.PROTOCOL_ICQ, "XMPP" to Im.PROTOCOL_JABBER,
    )

    private val mimes = mapOf(
        FieldKind.PHONE to Phone.CONTENT_ITEM_TYPE, FieldKind.EMAIL to Email.CONTENT_ITEM_TYPE,
        FieldKind.ADDRESS to StructuredPostal.CONTENT_ITEM_TYPE, FieldKind.NICKNAME to Nickname.CONTENT_ITEM_TYPE,
        FieldKind.RELATED to Relation.CONTENT_ITEM_TYPE, FieldKind.MESSAGING to Im.CONTENT_ITEM_TYPE,
        FieldKind.SIP to SipAddress.CONTENT_ITEM_TYPE, FieldKind.WEBSITE to Website.CONTENT_ITEM_TYPE,
        FieldKind.DATE to Event.CONTENT_ITEM_TYPE, FieldKind.NOTE to Note.CONTENT_ITEM_TYPE,
    )

    /** (type column, label column, custom type, label → type) per kind; null for kinds with no type. */
    private fun typing(kind: FieldKind): Typing? = when (kind) {
        FieldKind.PHONE -> Typing(Data.DATA2, Data.DATA3, 0, phoneTypes)
        FieldKind.EMAIL -> Typing(Data.DATA2, Data.DATA3, 0, emailTypes)
        FieldKind.ADDRESS -> Typing(Data.DATA2, Data.DATA3, 0, postalTypes)
        FieldKind.DATE -> Typing(Data.DATA2, Data.DATA3, 0, eventTypes)
        FieldKind.RELATED -> Typing(Data.DATA2, Data.DATA3, 0, relationTypes)
        FieldKind.MESSAGING -> Typing(Im.PROTOCOL, Im.CUSTOM_PROTOCOL, Im.PROTOCOL_CUSTOM, imProtocols)
        else -> null
    }

    private class Typing(val typeColumn: String, val labelColumn: String, val customType: Int, val types: Map<String, Int>)

    private fun labelFor(kind: FieldKind, c: Cursor): String {
        val t = typing(kind) ?: return kind.title
        val type = c.str(t.typeColumn)?.toIntOrNull() ?: t.customType
        val custom = c.str(t.labelColumn)
        return if (type == t.customType) custom?.uppercase(Locale.getDefault()) ?: "OTHER"
        else t.types.entries.firstOrNull { it.value == type }?.key ?: "OTHER"
    }

    // ---- Accounts ----

    private data class Account(val type: String?, val name: String?)

    /** Biggest account that syncs back to a server (e.g. DAVx5); new data goes here. */
    private val mainAccount: Account by lazy {
        val uploading = ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }.toSet()
        val counts = HashMap<Account, Int>()
        resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED} = 0", null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(0) ?: continue
                    if (type in uploading) counts.merge(Account(type, c.getString(1)), 1, Int::plus)
                }
            }
        counts.maxByOrNull { it.value }?.key ?: Account(null, null)
    }

    /** The copy of a merged contact to edit: the main account's if any. */
    private fun targetRawContact(contactId: Long): Long? {
        val raws = ArrayList<Pair<Long, Account>>()
        resolver.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0", arrayOf(contactId.toString()), null,
        )?.use { c -> while (c.moveToNext()) raws += c.getLong(0) to Account(c.getString(1), c.getString(2)) }
        return (raws.firstOrNull { it.second == mainAccount } ?: raws.firstOrNull { it.second.type == null } ?: raws.firstOrNull())?.first
    }

    // ---- Reading ----

    override suspend fun list(): List<Contact> = withContext(Dispatchers.IO) {
        val out = ArrayList<Contact>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, "${ContactsContract.Contacts.SORT_KEY_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { c ->
            // The list only needs names, so the display name goes in [Contact.first].
            while (c.moveToNext()) out += Contact(id = c.getLong(0).toString(), first = c.getString(1).orEmpty())
        }
        out
    }

    override suspend fun get(id: String): Contact? = withContext(Dispatchers.IO) {
        val contactId = id.toLongOrNull() ?: return@withContext null
        val target = targetRawContact(contactId) ?: return@withContext null
        var contact = Contact(id = id)
        var nameFromTarget = false
        var orgFromTarget = false
        val fields = ArrayList<ContactField>()
        val seen = HashMap<Pair<FieldKind, String>, Int>()

        resolver.query(
            Data.CONTENT_URI, null, "${Data.CONTACT_ID} = ?", arrayOf(id),
            "${Data.IS_SUPER_PRIMARY} DESC, ${Data.IS_PRIMARY} DESC, ${Data._ID} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Data._ID)
            val rawCol = c.getColumnIndexOrThrow(Data.RAW_CONTACT_ID)
            while (c.moveToNext()) {
                val rowId = c.getLong(idCol)
                val fromTarget = c.getLong(rawCol) == target
                when (val mime = c.str(Data.MIMETYPE)) {
                    // Prefer the target copy's rows so edits sync.
                    StructuredName.CONTENT_ITEM_TYPE -> if (!nameFromTarget) {
                        nameFromTarget = fromTarget
                        contact = contact.copy(
                            prefix = c.str(StructuredName.PREFIX).orEmpty(), first = c.str(StructuredName.GIVEN_NAME).orEmpty(),
                            middle = c.str(StructuredName.MIDDLE_NAME).orEmpty(), last = c.str(StructuredName.FAMILY_NAME).orEmpty(),
                            suffix = c.str(StructuredName.SUFFIX).orEmpty(),
                        )
                    }
                    Organization.CONTENT_ITEM_TYPE -> if (!orgFromTarget) {
                        orgFromTarget = fromTarget
                        contact = contact.copy(
                            company = c.str(Organization.COMPANY).orEmpty(), title = c.str(Organization.TITLE).orEmpty(),
                            department = c.str(Organization.DEPARTMENT).orEmpty(),
                        )
                    }
                    else -> {
                        val kind = mimes.entries.firstOrNull { it.value == mime }?.key ?: continue
                        val value = c.str(Data.DATA1) ?: continue
                        val key = when (kind) {
                            FieldKind.PHONE -> dialableNumber(value)
                            FieldKind.EMAIL -> value.lowercase(Locale.ROOT)
                            else -> value
                        }
                        val dup = seen[kind to key]
                        if (dup != null) {
                            fields[dup] = fields[dup].copy(rowIds = fields[dup].rowIds + rowId)
                        } else {
                            seen[kind to key] = fields.size
                            fields += ContactField(kind, labelFor(kind, c), value, listOf(rowId))
                        }
                    }
                }
            }
        }
        contact.copy(fields = fields)
    }

    // ---- Writing ----

    override suspend fun save(contact: Contact): Contact = withContext(Dispatchers.IO) {
        val original = if (contact.id.isBlank()) null else get(contact.id)
        val existingRaw = contact.id.toLongOrNull()?.let { targetRawContact(it) }
        val ops = ArrayList<ContentProviderOperation>()
        if (existingRaw == null) {
            ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, mainAccount.type)
                .withValue(RawContacts.ACCOUNT_NAME, mainAccount.name)
                .build()
        }

        fun insert(mime: String) = ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.MIMETYPE, mime).let {
            if (existingRaw != null) it.withValue(Data.RAW_CONTACT_ID, existingRaw) else it.withValueBackReference(Data.RAW_CONTACT_ID, 0)
        }
        fun update(rowId: Long) = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, rowId))
        fun delete(rowIds: List<Long>) = rowIds.forEach {
            ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, it)).build()
        }

        // Name
        val name = listOf(contact.prefix, contact.first, contact.middle, contact.last, contact.suffix).map { it.trim() }
        val oldName = original?.let { listOf(it.prefix, it.first, it.middle, it.last, it.suffix) }
        if (name != oldName) {
            val nameRow = existingRaw?.let { rowOf(it, StructuredName.CONTENT_ITEM_TYPE) }
            if (name.all { it.isEmpty() }) {
                if (nameRow != null) delete(listOf(nameRow))
            } else {
                ops += (if (nameRow != null) update(nameRow) else insert(StructuredName.CONTENT_ITEM_TYPE))
                    .withValue(StructuredName.PREFIX, name[0].ifEmpty { null })
                    .withValue(StructuredName.GIVEN_NAME, name[1].ifEmpty { null })
                    .withValue(StructuredName.MIDDLE_NAME, name[2].ifEmpty { null })
                    .withValue(StructuredName.FAMILY_NAME, name[3].ifEmpty { null })
                    .withValue(StructuredName.SUFFIX, name[4].ifEmpty { null })
                    .withValue(StructuredName.DISPLAY_NAME, name.filter { it.isNotEmpty() }.joinToString(" "))
                    .build()
            }
        }

        // Work
        val work = listOf(contact.company, contact.title, contact.department).map { it.trim() }
        if (work != original?.let { listOf(it.company, it.title, it.department) } && (original != null || work.any { it.isNotEmpty() })) {
            val orgRow = existingRaw?.let { rowOf(it, Organization.CONTENT_ITEM_TYPE) }
            ops += (orgRow?.let { update(it) } ?: insert(Organization.CONTENT_ITEM_TYPE).withValue(Organization.TYPE, Organization.TYPE_WORK))
                .withValue(Organization.COMPANY, work[0].ifEmpty { null })
                .withValue(Organization.TITLE, work[1].ifEmpty { null })
                .withValue(Organization.DEPARTMENT, work[2].ifEmpty { null })
                .build()
        }

        // Fields: matched by the rows they came from.
        val before = original?.fields.orEmpty()
        val keptRows = contact.fields.flatMap { it.rowIds }.toSet()
        before.filter { it.rowIds.none { id -> id in keptRows } }.forEach { delete(it.rowIds) }
        for (field in contact.fields) {
            val value = field.value.trim()
            val old = before.firstOrNull { it.rowIds.isNotEmpty() && it.rowIds == field.rowIds }
            when {
                field.rowIds.isEmpty() -> if (value.isNotEmpty()) ops += insert(mimes.getValue(field.kind)).withField(field, value, isNew = true).build()
                old == null -> Unit
                value.isEmpty() -> delete(field.rowIds)
                old.value != value || old.label != field.label ->
                    field.rowIds.forEach { ops += update(it).withField(field, value, isNew = false).build() }
            }
        }

        if (ops.isEmpty() || (existingRaw == null && ops.size == 1)) return@withContext contact
        val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = existingRaw ?: results[0].uri?.let { ContentUris.parseId(it) }
        val newId = rawId?.let { contactIdOf(it) }?.toString() ?: contact.id
        get(newId) ?: contact.copy(id = newId)
    }

    private fun ContentProviderOperation.Builder.withField(field: ContactField, value: String, isNew: Boolean) = apply {
        withValue(Data.DATA1, value)
        if (field.kind == FieldKind.ADDRESS) {
            // Android re-parses these from the new text.
            listOf(
                StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD, StructuredPostal.CITY,
                StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY,
            ).forEach { withValue(it, null) }
        }
        val t = typing(field.kind)
        if (t != null) {
            val type = t.types[field.label.uppercase(Locale.ROOT)]
            withValue(t.typeColumn, type ?: t.customType)
            withValue(t.labelColumn, if (type == null) field.label.lowercase().replaceFirstChar { it.titlecase() } else null)
            if (isNew && field.kind == FieldKind.MESSAGING) withValue(Im.TYPE, Im.TYPE_OTHER)
        } else if (isNew) {
            when (field.kind) {
                FieldKind.NICKNAME -> withValue(Nickname.TYPE, Nickname.TYPE_DEFAULT)
                FieldKind.SIP -> withValue(SipAddress.TYPE, SipAddress.TYPE_OTHER)
                FieldKind.WEBSITE -> withValue(Website.TYPE, Website.TYPE_OTHER)
                else -> Unit
            }
        }
    }

    private fun rowOf(rawId: Long, mime: String): Long? = resolver.query(
        Data.CONTENT_URI, arrayOf(Data._ID), "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
        arrayOf(rawId.toString(), mime), null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun contactIdOf(rawId: Long): Long? = resolver.query(
        ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.CONTACT_ID), null, null, null,
    )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    override suspend fun delete(id: String) {
        val contactId = id.toLongOrNull() ?: return
        withContext(Dispatchers.IO) {
            resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), null, null)
        }
    }

    private fun Cursor.str(column: String): String? {
        val i = getColumnIndex(column)
        if (i < 0 || isNull(i)) return null
        return getString(i)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
