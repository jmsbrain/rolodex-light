package com.jmsbrain.rolodex.sync

import com.jmsbrain.rolodex.data.Email
import com.jmsbrain.rolodex.data.Organization
import com.jmsbrain.rolodex.data.Phone
import com.jmsbrain.rolodex.data.StructuredName
import com.jmsbrain.rolodex.data.StructuredPostal
import com.jmsbrain.rolodex.data.SyncFields
import com.jmsbrain.rolodex.data.SyncRow
import com.jmsbrain.rolodex.data.VCardDoc
import com.jmsbrain.rolodex.data.dialableNumber
import java.util.Locale

/** The card to send, the kinds the phone should take from the server, and the kinds the phone changed. */
class Merged(val card: VCardDoc, val pullDown: Map<String, List<SyncRow>>, val replaced: Set<String>)

object SyncMerge {
    /**
     * Combines the server's card with the phone's rows, one kind (phones, emails…) at a time.
     * [local]: the phone's rows now. [snapshot]: the phone's rows right after the last sync, null if never synced.
     * [base]: the server's card at the last sync. A kind changed on the phone wins; otherwise the server's version is kept.
     */
    fun merge(server: VCardDoc, base: VCardDoc?, local: List<SyncRow>, snapshot: List<SyncRow>?): Merged {
        val l = SyncFields.byMime(local)
        val snap = snapshot?.let { SyncFields.byMime(it) }
        val s = SyncFields.byMime(server.rows())
        val b = base?.let { SyncFields.byMime(it.rows()) }
        val replace = LinkedHashMap<String, List<SyncRow>>()
        val pull = LinkedHashMap<String, List<SyncRow>>()
        for (mime in SyncFields.mimes) {
            val mine = l[mime].orEmpty()
            val localChanged = if (snap == null) mine.isNotEmpty() else !SyncFields.same(mine, snap[mime].orEmpty())
            when {
                localChanged -> replace[mime] = mine
                b == null || !SyncFields.same(s[mime].orEmpty(), b[mime].orEmpty()) -> pull[mime] = s[mime].orEmpty()
            }
        }
        return Merged(server.patched(replace), pull, replace.keys)
    }

    /**
     * Values in [sent] that the server didn't keep, for the [kinds] Rolodex sent.
     * Some servers (OX, used by mailbox.org) only have room for a fixed number of each, e.g. two mobile numbers.
     */
    fun lost(sent: VCardDoc, stored: VCardDoc, kinds: Set<String>): List<SyncRow> {
        val kept = stored.rows().map { key(it) }.toSet()
        return sent.rows().filter { it.mime in kinds && it.mime in CHECKED && key(it) !in kept }
    }

    /** Kinds that can hold several values. */
    private val CHECKED = SyncFields.mimes - setOf(StructuredName.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE)

    private fun key(r: SyncRow): String = r.mime + "|" + when (r.mime) {
        Phone.CONTENT_ITEM_TYPE -> dialableNumber(r[Phone.NUMBER].orEmpty())
        Email.CONTENT_ITEM_TYPE -> r[Email.ADDRESS].orEmpty().lowercase(Locale.ROOT)
        StructuredPostal.CONTENT_ITEM_TYPE -> listOf(
            StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD, StructuredPostal.CITY,
            StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY,
        ).joinToString("|") { r[it].orEmpty() }
        else -> r["data1"].orEmpty()
    }
}
