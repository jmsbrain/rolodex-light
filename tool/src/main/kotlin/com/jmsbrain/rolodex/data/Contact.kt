package com.jmsbrain.rolodex.data

import kotlinx.serialization.Serializable

/** Repeatable contact fields, in card/editor order. [labels] feed the type picker. */
enum class FieldKind(val title: String, val labels: List<String>, val placeholder: String) {
    PHONE("PHONE", listOf("MOBILE", "HOME", "WORK", "OTHER"), "Number"),
    EMAIL("EMAIL", listOf("HOME", "WORK", "OTHER"), "Email"),
    ADDRESS("ADDRESS", listOf("HOME", "WORK", "OTHER"), "Address"),
    NICKNAME("NICKNAME", emptyList(), "Nickname"),
    RELATED(
        "RELATED",
        listOf(
            "SPOUSE", "PARTNER", "CHILD", "PARENT", "MOTHER", "FATHER",
            "BROTHER", "SISTER", "RELATIVE", "FRIEND", "ASSISTANT", "MANAGER",
        ),
        "Name",
    ),
    MESSAGING("MESSAGING", listOf("SIGNAL", "TELEGRAM", "WHATSAPP", "MATRIX", "XMPP", "SKYPE"), "Number or username"),
    SIP("SIP", emptyList(), "SIP address"),
    WEBSITE("WEBSITE", emptyList(), "Website"),
    DATE("DATES", listOf("BIRTHDAY", "ANNIVERSARY", "OTHER"), "MM/DD/YYYY or MM/DD"),
    NOTE("NOTES", emptyList(), "Note");

    val defaultLabel: String get() = labels.firstOrNull() ?: title
}

/**
 * One value. [label] is a word like "MOBILE" (or a custom label).
 * [rowIds] is only used by [PhoneContactsSource] to find the rows it came from.
 * [row] is the synced row it came from, sent back as is while value and label are unchanged.
 */
@Serializable
data class ContactField(
    val kind: FieldKind,
    val label: String,
    val value: String,
    val rowIds: List<Long> = emptyList(),
    val row: Map<String, String> = emptyMap(),
)

/**
 * A contact. Blank [id] means not saved yet. Dates are stored as yyyy-MM-dd or --MM-dd.
 * [nameRow] keeps the server's full name (FN) while the name parts are unchanged.
 */
@Serializable
data class Contact(
    val id: String = "",
    val prefix: String = "",
    val first: String = "",
    val middle: String = "",
    val last: String = "",
    val suffix: String = "",
    val phoneticFirst: String = "",
    val phoneticMiddle: String = "",
    val phoneticLast: String = "",
    val company: String = "",
    val title: String = "",
    val department: String = "",
    val role: String = "",
    val fields: List<ContactField> = emptyList(),
    val nameRow: Map<String, String> = emptyMap(),
) {
    val displayName: String
        get() = ContactRows.syncedDisplayName(this)
            ?: listOf(prefix, first, middle, last, suffix).filter { it.isNotBlank() }.joinToString(" ")
                .ifEmpty { company }
                .ifEmpty { fields.firstOrNull { it.kind == FieldKind.PHONE || it.kind == FieldKind.EMAIL }?.value.orEmpty() }
                .ifEmpty { "(no name)" }

    val phonetic: String get() = listOf(phoneticFirst, phoneticMiddle, phoneticLast).filter { it.isNotBlank() }.joinToString(" ")

    fun fieldsOf(kind: FieldKind) = fields.filter { it.kind == kind }
}
