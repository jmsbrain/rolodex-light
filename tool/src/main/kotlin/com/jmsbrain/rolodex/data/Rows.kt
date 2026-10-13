package com.jmsbrain.rolodex.data

import java.util.Locale
import kotlinx.serialization.Serializable

// Contact data as rows: a kind plus data1, data2… columns. Same keys and type numbers as Android's
// contacts store, so the vCard and sync code match plain-Android Rolodex, and
// pending/PhoneContactsSource can map them one to one.

object StructuredName {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/name"
    const val DISPLAY_NAME = "data1"
    const val GIVEN_NAME = "data2"
    const val FAMILY_NAME = "data3"
    const val PREFIX = "data4"
    const val MIDDLE_NAME = "data5"
    const val SUFFIX = "data6"
    const val PHONETIC_GIVEN_NAME = "data7"
    const val PHONETIC_MIDDLE_NAME = "data8"
    const val PHONETIC_FAMILY_NAME = "data9"
}

object Organization {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/organization"
    const val COMPANY = "data1"
    const val TITLE = "data4"
    const val DEPARTMENT = "data5"
    const val JOB_DESCRIPTION = "data6"
}

object Phone {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/phone_v2"
    const val NUMBER = "data1"
    const val TYPE = "data2"
    const val LABEL = "data3"
    const val TYPE_CUSTOM = 0
    const val TYPE_HOME = 1
    const val TYPE_MOBILE = 2
    const val TYPE_WORK = 3
    const val TYPE_FAX_WORK = 4
    const val TYPE_FAX_HOME = 5
    const val TYPE_PAGER = 6
    const val TYPE_OTHER = 7
    const val TYPE_CALLBACK = 8
    const val TYPE_CAR = 9
    const val TYPE_COMPANY_MAIN = 10
    const val TYPE_ISDN = 11
    const val TYPE_MAIN = 12
    const val TYPE_OTHER_FAX = 13
    const val TYPE_RADIO = 14
    const val TYPE_TELEX = 15
    const val TYPE_TTY_TDD = 16
    const val TYPE_WORK_MOBILE = 17
    const val TYPE_WORK_PAGER = 18
    const val TYPE_ASSISTANT = 19
    const val TYPE_MMS = 20
}

object Email {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/email_v2"
    const val ADDRESS = "data1"
    const val TYPE = "data2"
    const val LABEL = "data3"
    const val TYPE_HOME = 1
    const val TYPE_WORK = 2
    const val TYPE_OTHER = 3
    const val TYPE_MOBILE = 4
}

object StructuredPostal {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/postal-address_v2"
    const val FORMATTED_ADDRESS = "data1"
    const val TYPE = "data2"
    const val LABEL = "data3"
    const val STREET = "data4"
    const val POBOX = "data5"
    const val NEIGHBORHOOD = "data6"
    const val CITY = "data7"
    const val REGION = "data8"
    const val POSTCODE = "data9"
    const val COUNTRY = "data10"
    const val TYPE_HOME = 1
    const val TYPE_WORK = 2
    const val TYPE_OTHER = 3
}

object Nickname {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/nickname"
    const val NAME = "data1"
}

object Website {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/website"
    const val URL = "data1"
}

object Event {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contact_event"
    const val START_DATE = "data1"
    const val TYPE = "data2"
    const val LABEL = "data3"
    const val TYPE_CUSTOM = 0
    const val TYPE_ANNIVERSARY = 1
    const val TYPE_OTHER = 2
    const val TYPE_BIRTHDAY = 3
}

object Im {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/im"
    const val DATA = "data1"
    const val PROTOCOL = "data5"
    const val CUSTOM_PROTOCOL = "data6"
    const val PROTOCOL_CUSTOM = -1
    const val PROTOCOL_AIM = 0
    const val PROTOCOL_MSN = 1
    const val PROTOCOL_YAHOO = 2
    const val PROTOCOL_SKYPE = 3
    const val PROTOCOL_QQ = 4
    const val PROTOCOL_GOOGLE_TALK = 5
    const val PROTOCOL_ICQ = 6
    const val PROTOCOL_JABBER = 7
}

object SipAddress {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/sip_address"
    const val SIP_ADDRESS = "data1"
}

object Relation {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/relation"
    const val NAME = "data1"
    const val TYPE = "data2"
    const val LABEL = "data3"
    const val TYPE_CUSTOM = 0
    const val TYPE_ASSISTANT = 1
    const val TYPE_BROTHER = 2
    const val TYPE_CHILD = 3
    const val TYPE_DOMESTIC_PARTNER = 4
    const val TYPE_FATHER = 5
    const val TYPE_FRIEND = 6
    const val TYPE_MANAGER = 7
    const val TYPE_MOTHER = 8
    const val TYPE_PARENT = 9
    const val TYPE_PARTNER = 10
    const val TYPE_REFERRED_BY = 11
    const val TYPE_RELATIVE = 12
    const val TYPE_SISTER = 13
    const val TYPE_SPOUSE = 14
}

object Note {
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/note"
    const val NOTE = "data1"
}

/** One contact data row: column → value. Blank values are left out so rows compare cleanly. */
@Serializable
data class SyncRow(val mime: String, val values: Map<String, String>) {
    operator fun get(column: String): String? = values[column]

    val sortKey: String get() = mime + values.toSortedMap()

    /** Drops labels that only matter for custom types. */
    fun normalized(): SyncRow {
        val v = values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }.toMutableMap()
        when (mime) {
            Im.CONTENT_ITEM_TYPE -> if (v[Im.PROTOCOL] != Im.PROTOCOL_CUSTOM.toString()) v.remove(Im.CUSTOM_PROTOCOL)
            Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE,
            Event.CONTENT_ITEM_TYPE, Relation.CONTENT_ITEM_TYPE,
            -> if (v["data2"] != "0") v.remove("data3")
        }
        return SyncRow(mime, v)
    }

    companion object {
        fun of(mime: String, vararg pairs: Pair<String, Any?>) = SyncRow(
            mime,
            pairs.mapNotNull { (k, v) -> v?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap(),
        ).normalized()
    }
}

/** What Rolodex syncs, per kind: the columns read and written. */
object SyncFields {
    val columns: Map<String, List<String>> = linkedMapOf(
        StructuredName.CONTENT_ITEM_TYPE to listOf(
            StructuredName.DISPLAY_NAME, StructuredName.PREFIX, StructuredName.GIVEN_NAME, StructuredName.MIDDLE_NAME,
            StructuredName.FAMILY_NAME, StructuredName.SUFFIX, StructuredName.PHONETIC_GIVEN_NAME,
            StructuredName.PHONETIC_MIDDLE_NAME, StructuredName.PHONETIC_FAMILY_NAME,
        ),
        Organization.CONTENT_ITEM_TYPE to listOf(Organization.COMPANY, Organization.TITLE, Organization.DEPARTMENT, Organization.JOB_DESCRIPTION),
        Phone.CONTENT_ITEM_TYPE to listOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL),
        Email.CONTENT_ITEM_TYPE to listOf(Email.ADDRESS, Email.TYPE, Email.LABEL),
        StructuredPostal.CONTENT_ITEM_TYPE to listOf(
            StructuredPostal.FORMATTED_ADDRESS, StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD,
            StructuredPostal.CITY, StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY,
            StructuredPostal.TYPE, StructuredPostal.LABEL,
        ),
        Nickname.CONTENT_ITEM_TYPE to listOf(Nickname.NAME),
        Website.CONTENT_ITEM_TYPE to listOf(Website.URL),
        Event.CONTENT_ITEM_TYPE to listOf(Event.START_DATE, Event.TYPE, Event.LABEL),
        Im.CONTENT_ITEM_TYPE to listOf(Im.DATA, Im.PROTOCOL, Im.CUSTOM_PROTOCOL),
        SipAddress.CONTENT_ITEM_TYPE to listOf(SipAddress.SIP_ADDRESS),
        Relation.CONTENT_ITEM_TYPE to listOf(Relation.NAME, Relation.TYPE, Relation.LABEL),
        Note.CONTENT_ITEM_TYPE to listOf(Note.NOTE),
    )
    val mimes: Set<String> get() = columns.keys

    /** Same rows, in any order. */
    fun same(a: List<SyncRow>, b: List<SyncRow>): Boolean =
        a.size == b.size && a.map { it.sortKey }.sorted() == b.map { it.sortKey }.sorted()

    fun byMime(rows: List<SyncRow>): Map<String, List<SyncRow>> = rows.groupBy { it.mime }

    /** [rows] with the kinds in [replace] swapped for its rows. */
    fun replaceKinds(rows: List<SyncRow>, replace: Map<String, List<SyncRow>>): List<SyncRow> =
        (rows.filter { it.mime !in replace.keys } + replace.values.flatten()).sortedBy { mimes.indexOf(it.mime) }
}

/** [Contact] ↔ rows. Rows read back into a contact turn into the same rows again. */
object ContactRows {

    private val PHONE_NAMES = mapOf(
        Phone.TYPE_HOME to "HOME", Phone.TYPE_MOBILE to "MOBILE", Phone.TYPE_WORK to "WORK", Phone.TYPE_FAX_WORK to "WORK FAX",
        Phone.TYPE_FAX_HOME to "HOME FAX", Phone.TYPE_PAGER to "PAGER", Phone.TYPE_OTHER to "OTHER", Phone.TYPE_CALLBACK to "CALLBACK",
        Phone.TYPE_CAR to "CAR", Phone.TYPE_COMPANY_MAIN to "COMPANY MAIN", Phone.TYPE_ISDN to "ISDN", Phone.TYPE_MAIN to "MAIN",
        Phone.TYPE_OTHER_FAX to "FAX", Phone.TYPE_RADIO to "RADIO", Phone.TYPE_TELEX to "TELEX", Phone.TYPE_TTY_TDD to "TTY/TDD",
        Phone.TYPE_WORK_MOBILE to "WORK MOBILE", Phone.TYPE_WORK_PAGER to "WORK PAGER", Phone.TYPE_ASSISTANT to "ASSISTANT",
        Phone.TYPE_MMS to "MMS",
    )
    private val EMAIL_NAMES = mapOf(Email.TYPE_HOME to "HOME", Email.TYPE_WORK to "WORK", Email.TYPE_OTHER to "OTHER", Email.TYPE_MOBILE to "MOBILE")
    private val POSTAL_NAMES = mapOf(StructuredPostal.TYPE_HOME to "HOME", StructuredPostal.TYPE_WORK to "WORK", StructuredPostal.TYPE_OTHER to "OTHER")
    private val EVENT_NAMES = mapOf(Event.TYPE_BIRTHDAY to "BIRTHDAY", Event.TYPE_ANNIVERSARY to "ANNIVERSARY", Event.TYPE_OTHER to "OTHER")
    private val RELATION_NAMES = mapOf(
        Relation.TYPE_ASSISTANT to "ASSISTANT", Relation.TYPE_BROTHER to "BROTHER", Relation.TYPE_CHILD to "CHILD",
        Relation.TYPE_DOMESTIC_PARTNER to "DOMESTIC PARTNER", Relation.TYPE_FATHER to "FATHER", Relation.TYPE_FRIEND to "FRIEND",
        Relation.TYPE_MANAGER to "MANAGER", Relation.TYPE_MOTHER to "MOTHER", Relation.TYPE_PARENT to "PARENT",
        Relation.TYPE_PARTNER to "PARTNER", Relation.TYPE_REFERRED_BY to "REFERRED BY", Relation.TYPE_RELATIVE to "RELATIVE",
        Relation.TYPE_SISTER to "SISTER", Relation.TYPE_SPOUSE to "SPOUSE",
    )
    private val IM_NAMES = mapOf(
        Im.PROTOCOL_AIM to "AIM", Im.PROTOCOL_MSN to "MSN", Im.PROTOCOL_YAHOO to "YAHOO", Im.PROTOCOL_SKYPE to "SKYPE",
        Im.PROTOCOL_QQ to "QQ", Im.PROTOCOL_GOOGLE_TALK to "GOOGLE TALK", Im.PROTOCOL_ICQ to "ICQ", Im.PROTOCOL_JABBER to "XMPP",
    )

    fun mimeOf(kind: FieldKind): String = when (kind) {
        FieldKind.PHONE -> Phone.CONTENT_ITEM_TYPE
        FieldKind.EMAIL -> Email.CONTENT_ITEM_TYPE
        FieldKind.ADDRESS -> StructuredPostal.CONTENT_ITEM_TYPE
        FieldKind.NICKNAME -> Nickname.CONTENT_ITEM_TYPE
        FieldKind.RELATED -> Relation.CONTENT_ITEM_TYPE
        FieldKind.MESSAGING -> Im.CONTENT_ITEM_TYPE
        FieldKind.SIP -> SipAddress.CONTENT_ITEM_TYPE
        FieldKind.WEBSITE -> Website.CONTENT_ITEM_TYPE
        FieldKind.DATE -> Event.CONTENT_ITEM_TYPE
        FieldKind.NOTE -> Note.CONTENT_ITEM_TYPE
    }

    // ---- name ----

    private fun Contact.nameParts() = listOf(prefix, first, middle, last, suffix).map { it.trim() }

    /** Name parts of a synced name row. A full name alone (a company, an email address) counts as the first name. */
    private fun rowNameParts(r: Map<String, String>): List<String> {
        val parts = listOf(StructuredName.PREFIX, StructuredName.GIVEN_NAME, StructuredName.MIDDLE_NAME, StructuredName.FAMILY_NAME, StructuredName.SUFFIX)
            .map { r[it].orEmpty() }
        return if (parts.all { it.isEmpty() }) listOf("", r[StructuredName.DISPLAY_NAME].orEmpty(), "", "", "") else parts
    }

    /** The server's full name, while the name parts haven't been edited. */
    fun syncedDisplayName(c: Contact): String? {
        val fn = c.nameRow[StructuredName.DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: return null
        return fn.takeIf { rowNameParts(c.nameRow) == c.nameParts() }
    }

    // ---- contact → rows ----

    fun toRows(c: Contact): List<SyncRow> {
        val out = ArrayList<SyncRow>()
        val p = c.nameParts()
        val name = SyncRow.of(
            StructuredName.CONTENT_ITEM_TYPE,
            StructuredName.DISPLAY_NAME to (syncedDisplayName(c) ?: p.filter { it.isNotEmpty() }.joinToString(" ")),
            StructuredName.PREFIX to p[0], StructuredName.GIVEN_NAME to p[1], StructuredName.MIDDLE_NAME to p[2],
            StructuredName.FAMILY_NAME to p[3], StructuredName.SUFFIX to p[4],
            StructuredName.PHONETIC_GIVEN_NAME to c.phoneticFirst, StructuredName.PHONETIC_MIDDLE_NAME to c.phoneticMiddle,
            StructuredName.PHONETIC_FAMILY_NAME to c.phoneticLast,
        )
        if (name.values.isNotEmpty()) out += name
        val org = SyncRow.of(
            Organization.CONTENT_ITEM_TYPE,
            Organization.COMPANY to c.company, Organization.TITLE to c.title,
            Organization.DEPARTMENT to c.department, Organization.JOB_DESCRIPTION to c.role,
        )
        if (org.values.isNotEmpty()) out += org
        c.fields.forEach { f -> rowOf(f)?.let { out += it } }
        return out
    }

    fun rowOf(f: ContactField): SyncRow? {
        val mime = mimeOf(f.kind)
        if (f.row.isNotEmpty()) {
            val kept = SyncRow(mime, f.row)
            val back = fieldOf(kept)
            if (back != null && back.value == f.value && (f.kind.labels.isEmpty() || back.label == f.label)) return kept
        }
        val v = f.value.trim()
        if (v.isEmpty()) return null
        return when (f.kind) {
            FieldKind.PHONE -> typed(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, v, PHONE_NAMES, f.label)
            FieldKind.EMAIL -> typed(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, v, EMAIL_NAMES, f.label)
            FieldKind.ADDRESS -> typed(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, v, POSTAL_NAMES, f.label)
            FieldKind.RELATED -> typed(Relation.CONTENT_ITEM_TYPE, Relation.NAME, v, RELATION_NAMES, f.label)
            FieldKind.DATE -> typed(Event.CONTENT_ITEM_TYPE, Event.START_DATE, v, EVENT_NAMES, f.label)
            FieldKind.MESSAGING -> {
                val (protocol, custom) = imProtocol(f.label)
                SyncRow.of(Im.CONTENT_ITEM_TYPE, Im.DATA to v, Im.PROTOCOL to protocol, Im.CUSTOM_PROTOCOL to custom)
            }
            FieldKind.NICKNAME -> SyncRow.of(Nickname.CONTENT_ITEM_TYPE, Nickname.NAME to v)
            FieldKind.SIP -> SyncRow.of(SipAddress.CONTENT_ITEM_TYPE, SipAddress.SIP_ADDRESS to v)
            FieldKind.WEBSITE -> SyncRow.of(Website.CONTENT_ITEM_TYPE, Website.URL to v)
            FieldKind.NOTE -> SyncRow.of(Note.CONTENT_ITEM_TYPE, Note.NOTE to v)
        }
    }

    private fun typed(mime: String, valueColumn: String, value: String, names: Map<Int, String>, label: String): SyncRow {
        val l = label.trim()
        val type = names.entries.firstOrNull { it.value.equals(l, ignoreCase = true) }?.key
            ?: if (mime == Phone.CONTENT_ITEM_TYPE && l.equals("OTHER FAX", ignoreCase = true)) Phone.TYPE_OTHER_FAX else null
        return if (type != null) SyncRow.of(mime, valueColumn to value, "data2" to type)
        else SyncRow.of(mime, valueColumn to value, "data2" to 0, "data3" to l.ifEmpty { "Other" })
    }

    /** Messaging label → protocol, plus the service name for custom ones. */
    fun imProtocol(label: String): Pair<Int, String?> = when (label.trim().lowercase(Locale.ROOT)) {
        "signal", "sgnl" -> Im.PROTOCOL_CUSTOM to "Signal"
        "telegram", "tg" -> Im.PROTOCOL_CUSTOM to "Telegram"
        "whatsapp" -> Im.PROTOCOL_CUSTOM to "WhatsApp"
        "matrix" -> Im.PROTOCOL_CUSTOM to "Matrix"
        "xmpp", "jabber" -> Im.PROTOCOL_JABBER to null
        "skype", "skype-username" -> Im.PROTOCOL_SKYPE to null
        "aim" -> Im.PROTOCOL_AIM to null
        "msn", "msnim" -> Im.PROTOCOL_MSN to null
        "yahoo", "ymsgr" -> Im.PROTOCOL_YAHOO to null
        "qq" -> Im.PROTOCOL_QQ to null
        "google talk", "gtalk", "google-talk", "googletalk" -> Im.PROTOCOL_GOOGLE_TALK to null
        "icq" -> Im.PROTOCOL_ICQ to null
        else -> Im.PROTOCOL_CUSTOM to label.trim().replaceFirstChar { it.uppercase() }.ifEmpty { "Other" }
    }

    // ---- rows → contact ----

    fun fromRows(rows: List<SyncRow>, id: String = ""): Contact {
        val name = rows.firstOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE }
        val org = rows.firstOrNull { it.mime == Organization.CONTENT_ITEM_TYPE }
        val parts = name?.let { rowNameParts(it.values) } ?: List(5) { "" }
        return Contact(
            id = id,
            prefix = parts[0], first = parts[1], middle = parts[2], last = parts[3], suffix = parts[4],
            phoneticFirst = name?.get(StructuredName.PHONETIC_GIVEN_NAME).orEmpty(),
            phoneticMiddle = name?.get(StructuredName.PHONETIC_MIDDLE_NAME).orEmpty(),
            phoneticLast = name?.get(StructuredName.PHONETIC_FAMILY_NAME).orEmpty(),
            company = org?.get(Organization.COMPANY).orEmpty(),
            title = org?.get(Organization.TITLE).orEmpty(),
            department = org?.get(Organization.DEPARTMENT).orEmpty(),
            role = org?.get(Organization.JOB_DESCRIPTION).orEmpty(),
            fields = rows.mapNotNull(::fieldOf),
            nameRow = name?.values.orEmpty(),
        )
    }

    fun fieldOf(r: SyncRow): ContactField? {
        fun named(names: Map<Int, String>, default: String): String = when (val t = r["data2"]?.toIntOrNull()) {
            null -> r["data3"] ?: default
            0 -> r["data3"] ?: "OTHER"
            else -> names[t] ?: r["data3"] ?: "OTHER"
        }
        fun field(kind: FieldKind, value: String?, label: String = kind.defaultLabel): ContactField? =
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { ContactField(kind, label, it, row = r.values) }

        return when (r.mime) {
            Phone.CONTENT_ITEM_TYPE -> field(FieldKind.PHONE, r[Phone.NUMBER], named(PHONE_NAMES, "MOBILE"))
            Email.CONTENT_ITEM_TYPE -> field(FieldKind.EMAIL, r[Email.ADDRESS], named(EMAIL_NAMES, "HOME"))
            StructuredPostal.CONTENT_ITEM_TYPE -> field(FieldKind.ADDRESS, addressText(r), named(POSTAL_NAMES, "HOME"))
            Nickname.CONTENT_ITEM_TYPE -> field(FieldKind.NICKNAME, r[Nickname.NAME])
            Relation.CONTENT_ITEM_TYPE -> field(FieldKind.RELATED, r[Relation.NAME], named(RELATION_NAMES, "RELATIVE"))
            Im.CONTENT_ITEM_TYPE -> {
                val protocol = r[Im.PROTOCOL]?.toIntOrNull() ?: Im.PROTOCOL_CUSTOM
                val label = IM_NAMES[protocol] ?: r[Im.CUSTOM_PROTOCOL]?.uppercase(Locale.ROOT) ?: "OTHER"
                field(FieldKind.MESSAGING, r[Im.DATA], label)
            }
            SipAddress.CONTENT_ITEM_TYPE -> field(FieldKind.SIP, r[SipAddress.SIP_ADDRESS])
            Website.CONTENT_ITEM_TYPE -> field(FieldKind.WEBSITE, r[Website.URL])
            Event.CONTENT_ITEM_TYPE -> field(FieldKind.DATE, r[Event.START_DATE], named(EVENT_NAMES, "OTHER"))
            Note.CONTENT_ITEM_TYPE -> field(FieldKind.NOTE, r[Note.NOTE])
            else -> null
        }
    }

    /** "PO box / extended / street / City, Region Postcode / Country", or the text as typed. */
    private fun addressText(r: SyncRow): String? {
        val city = r[StructuredPostal.CITY]
        val regionZip = listOfNotNull(r[StructuredPostal.REGION], r[StructuredPostal.POSTCODE]).joinToString(" ").ifEmpty { null }
        val parts = listOfNotNull(
            r[StructuredPostal.POBOX], r[StructuredPostal.NEIGHBORHOOD], r[StructuredPostal.STREET],
            listOfNotNull(city, regionZip).joinToString(", ").ifEmpty { null },
            r[StructuredPostal.COUNTRY],
        )
        return parts.joinToString("\n").ifEmpty { null } ?: r[StructuredPostal.FORMATTED_ADDRESS]
    }
}
