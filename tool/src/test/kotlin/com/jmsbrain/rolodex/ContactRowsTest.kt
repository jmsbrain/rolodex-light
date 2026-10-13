package com.jmsbrain.rolodex

import com.jmsbrain.rolodex.data.Contact
import com.jmsbrain.rolodex.data.ContactField
import com.jmsbrain.rolodex.data.ContactRows
import com.jmsbrain.rolodex.data.FieldKind
import com.jmsbrain.rolodex.data.LocalContactsSource
import com.jmsbrain.rolodex.data.Phone
import com.jmsbrain.rolodex.data.SyncFields
import com.jmsbrain.rolodex.data.SyncMeta
import com.jmsbrain.rolodex.data.VCard
import com.jmsbrain.rolodex.data.VCardDoc
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ContactRowsTest {

    private val card = """
        BEGIN:VCARD
        VERSION:3.0
        FN:Dr. Bob Smith
        N:Smith;Robert;;Dr.;
        X-PHONETIC-FIRST-NAME:Rob
        ORG:Acme;Sales
        ROLE:Lead
        TEL;TYPE=WORK,FAX:+1 555 0101
        item1.TEL:+1 555 0102
        item1.X-ABLabel:Lab
        ADR;TYPE=WORK:;Suite 4;1 Main St;Portland;OR;97201;USA
        IMPP;X-SERVICE-TYPE=Threema:x-threema:ABCD1234
        BDAY:19800101
        END:VCARD
    """.trimIndent().replace("\n", "\r\n")

    private fun rows() = VCardDoc.parseOne(card)!!.rows()

    @Test
    fun readsIntoContact() {
        val c = ContactRows.fromRows(rows())
        assertEquals("Dr. Bob Smith", c.displayName)
        assertEquals("Robert", c.first)
        assertEquals("Rob", c.phoneticFirst)
        assertEquals("Lead", c.role)
        assertEquals(listOf("WORK FAX", "Lab"), c.fieldsOf(FieldKind.PHONE).map { it.label })
        assertEquals("Suite 4\n1 Main St\nPortland, OR 97201\nUSA", c.fieldsOf(FieldKind.ADDRESS).single().value)
        assertEquals("THREEMA", c.fieldsOf(FieldKind.MESSAGING).single().label)
        assertEquals("1980-01-01", c.fieldsOf(FieldKind.DATE).single().value)
    }

    @Test
    fun unchangedContactGivesTheSameRows() {
        val c = ContactRows.fromRows(rows())
        assertTrue(SyncFields.same(rows(), ContactRows.toRows(c)))
        // And again after one more trip.
        val again = ContactRows.fromRows(ContactRows.toRows(c), c.id)
        assertEquals(c, again)
    }

    @Test
    fun editedFieldMakesANewRowOthersStay() {
        val c = ContactRows.fromRows(rows())
        val fields = c.fields.map { if (it.label == "Lab") it.copy(value = "+1 555 0199") else it }
        val out = ContactRows.toRows(c.copy(fields = fields)).filter { it.mime == Phone.CONTENT_ITEM_TYPE }
        assertEquals(listOf("+1 555 0101", "+1 555 0199"), out.map { it[Phone.NUMBER] })
        assertEquals("Lab", out[1][Phone.LABEL])
        assertEquals(Phone.TYPE_FAX_WORK.toString(), out[0][Phone.TYPE])
    }

    @Test
    fun editedNameReplacesFullName() {
        val c = ContactRows.fromRows(rows()).copy(first = "Bobby")
        assertEquals("Dr. Bobby Smith", c.displayName)
    }

    @Test
    fun fullNameOnlyIsKeptWhole() {
        val c = VCard.parse("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Acme Plumbing\r\nTEL:1\r\nEND:VCARD\r\n").single()
        assertEquals("Acme Plumbing", c.displayName)
        // Settles after one trip (the name gains a first-name column), then stays.
        val once = ContactRows.fromRows(ContactRows.toRows(c))
        assertEquals("Acme Plumbing", once.displayName)
        assertEquals(once, ContactRows.fromRows(ContactRows.toRows(once)))
    }

    @Test
    fun newFieldsBecomeRows() {
        val c = Contact(first = "Ann", fields = listOf(ContactField(FieldKind.PHONE, "WORK", "123"), ContactField(FieldKind.MESSAGING, "SIGNAL", "+1")))
        val text = VCard.write(listOf(c))
        assertTrue("TEL;TYPE=WORK,VOICE:123" in text)
        assertTrue("IMPP;X-SERVICE-TYPE=Signal:sgnl:+1" in text)
        assertEquals(c.fields.map { it.label to it.value }, VCard.parse(text).single().fields.map { it.label to it.value })
    }

    private fun tempDir(): File = Files.createTempDirectory("rolodex").toFile()

    @Test
    fun readsTheOldFileFormat() = runBlocking {
        val dir = tempDir()
        File(dir, "contacts.json").writeText("""[{"id":"a","first":"Ann","fields":[{"kind":"PHONE","label":"MOBILE","value":"1"}]}]""")
        val list = LocalContactsSource(dir).list()
        assertEquals("Ann", list.single().first)
    }

    @Test
    fun editDuringSyncIsKept() = runBlocking {
        val dir = tempDir()
        val store = LocalContactsSource(dir) { true }
        val saved = store.save(Contact(first = "Ann"))
        val entry = store.syncEntries().single()
        // Edited while the sync ran.
        store.save(saved.copy(last = "Lee"))
        store.finishSync(saved.id, entry.meta.version, saved, SyncMeta(href = "https://x/a.vcf", etag = "1"), ContactRows.toRows(saved))
        val after = store.syncEntries().single()
        assertEquals("Lee", after.contact.last)
        assertTrue(after.meta.dirty)
        assertEquals("https://x/a.vcf", after.meta.href)
    }

    @Test
    fun deletingASyncedContactLeavesATombstone() = runBlocking {
        val dir = tempDir()
        val store = LocalContactsSource(dir) { true }
        val saved = store.save(Contact(first = "Ann"))
        val entry = store.syncEntries().single()
        store.finishSync(saved.id, entry.meta.version, saved, SyncMeta(href = "https://x/a.vcf", etag = "1"), ContactRows.toRows(saved))
        assertFalse(store.syncEntries().single().meta.dirty)
        store.delete(saved.id)
        assertEquals("https://x/a.vcf", store.tombstones().single().href)
        assertTrue(store.list().isEmpty())
    }
}
