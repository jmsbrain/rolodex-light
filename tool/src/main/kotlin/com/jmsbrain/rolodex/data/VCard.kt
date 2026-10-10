package com.jmsbrain.rolodex.data

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale

/** Reads vCard 2.1/3.0/4.0 text. Photos and unknown fields are skipped. */
object VCard {

    private class Property(val name: String, val params: Map<String, List<String>>, val rawValue: String) {
        val types: Set<String> = params["TYPE"].orEmpty().flatMap { it.split(',') }.map { it.uppercase(Locale.ROOT) }.toSet()
        val parts: List<String> get() = splitUnescaped(rawValue, ';').map(::unescape)
        val text: String get() = unescape(rawValue)
    }

    fun parse(content: String): List<Contact> {
        val cards = ArrayList<Contact>()
        var props: MutableList<Property>? = null
        for (line in logicalLines(content)) {
            val prop = parseLine(line) ?: continue
            when {
                prop.name == "BEGIN" && prop.rawValue.equals("VCARD", ignoreCase = true) -> props = ArrayList()
                prop.name == "END" && prop.rawValue.equals("VCARD", ignoreCase = true) -> {
                    props?.let { p -> toContact(p)?.let { cards += it } }
                    props = null
                }
                else -> props?.add(prop)
            }
        }
        return cards
    }

    /** Writes contacts as vCard 3.0. */
    fun write(contacts: List<Contact>): String = buildString {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
        fun line(s: String) = append(s).append("\r\n")
        for (c in contacts) {
            line("BEGIN:VCARD")
            line("VERSION:3.0")
            line("N:${esc(c.last)};${esc(c.first)};${esc(c.middle)};${esc(c.prefix)};${esc(c.suffix)}")
            line("FN:${esc(c.displayName)}")
            if (c.company.isNotBlank() || c.department.isNotBlank()) line("ORG:${esc(c.company)};${esc(c.department)}")
            if (c.title.isNotBlank()) line("TITLE:${esc(c.title)}")
            for (f in c.fields) {
                val v = esc(f.value)
                val type = f.label.replace(" ", "-")
                when (f.kind) {
                    FieldKind.PHONE -> line("TEL;TYPE=${if (f.label == "MOBILE") "CELL" else type}:$v")
                    FieldKind.EMAIL -> line("EMAIL;TYPE=$type:$v")
                    FieldKind.ADDRESS -> line("ADR;TYPE=$type:;;$v;;;;")
                    FieldKind.NICKNAME -> line("NICKNAME:$v")
                    FieldKind.RELATED -> line("RELATED;TYPE=${type.lowercase()};VALUE=text:$v")
                    FieldKind.MESSAGING -> line("IMPP;X-SERVICE-TYPE=${f.label.lowercase()}:${f.label.lowercase()}:${f.value}")
                    FieldKind.SIP -> line("IMPP:sip:${f.value}")
                    FieldKind.WEBSITE -> line("URL:$v")
                    FieldKind.DATE -> line("${if (f.label == "BIRTHDAY") "BDAY" else "ANNIVERSARY"}:${f.value}")
                    FieldKind.NOTE -> line("NOTE:$v")
                }
            }
            line("END:VCARD")
        }
    }

    // ---- Lines ----

    /** Joins folded lines and quoted-printable soft breaks. */
    private fun logicalLines(content: String): List<String> {
        val out = ArrayList<String>()
        for (line in content.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val last = out.lastOrNull()
            when {
                last != null && (line.startsWith(" ") || line.startsWith("\t")) -> out[out.size - 1] = last + line.substring(1)
                last != null && last.endsWith("=") && "QUOTED-PRINTABLE" in last.substringBefore(':').uppercase(Locale.ROOT) ->
                    out[out.size - 1] = last.dropLast(1) + line
                line.isNotBlank() -> out += line
            }
        }
        return out
    }

    private fun parseLine(line: String): Property? {
        val colon = indexOutsideQuotes(line, ':')
        if (colon < 0) return null
        var value = line.substring(colon + 1)
        val pieces = splitUnescaped(line.substring(0, colon), ';')
        // Drop "item1." style groups.
        val name = pieces[0].substringAfterLast('.').uppercase(Locale.ROOT)

        val params = HashMap<String, MutableList<String>>()
        for (p in pieces.drop(1)) {
            val eq = p.indexOf('=')
            if (eq < 0) {
                // vCard 2.1 bare params, e.g. TEL;CELL.
                val bare = p.uppercase(Locale.ROOT)
                val key = if (bare == "QUOTED-PRINTABLE" || bare == "BASE64" || bare == "B") "ENCODING" else "TYPE"
                params.getOrPut(key) { ArrayList() } += if (key == "ENCODING") bare else p
            } else {
                params.getOrPut(p.substring(0, eq).uppercase(Locale.ROOT)) { ArrayList() } += p.substring(eq + 1).trim('"')
            }
        }

        val encoding = params["ENCODING"]?.firstOrNull()?.uppercase(Locale.ROOT)
        if (encoding == "BASE64" || encoding == "B") return null  // Binary, e.g. photo.
        if (encoding == "QUOTED-PRINTABLE") {
            val charset = params["CHARSET"]?.firstOrNull()?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            value = decodeQuotedPrintable(value, charset)
        }
        return Property(name, params, value)
    }

    private fun indexOutsideQuotes(s: String, ch: Char): Int {
        var quoted = false
        s.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            if (c == ch && !quoted) return i
        }
        return -1
    }

    private fun splitUnescaped(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { sb.append(c).append(s[i + 1]); i++ }
                c == sep -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                sb.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().trim()
    }

    private fun decodeQuotedPrintable(s: String, charset: Charset): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val b = if (s[i] == '=' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (b != null) {
                bytes.write(b)
                i += 3
            } else {
                bytes.write(s[i].code and 0xFF)
                i++
            }
        }
        return String(bytes.toByteArray(), charset)
    }

    // ---- Contact ----

    private fun toContact(props: List<Property>): Contact? {
        var c = Contact()
        var fullName: String? = null
        val fields = ArrayList<ContactField>()
        fun add(kind: FieldKind, label: String, value: String) {
            if (value.isNotBlank()) fields += ContactField(kind, label, value.trim())
        }

        for (prop in props) {
            when (prop.name) {
                "FN" -> fullName = prop.text
                "N" -> {
                    val n = prop.parts + List(5) { "" }
                    c = c.copy(last = n[0], first = n[1], middle = n[2], prefix = n[3], suffix = n[4])
                }
                "ORG" -> {
                    val o = prop.parts + listOf("", "")
                    c = c.copy(company = o[0], department = o.drop(1).filter { it.isNotBlank() }.joinToString(", "))
                }
                "TITLE" -> c = c.copy(title = prop.text)
                "TEL" -> add(FieldKind.PHONE, phoneLabel(prop.types), prop.text.removePrefix("tel:"))
                "EMAIL" -> add(FieldKind.EMAIL, homeWorkLabel(prop.types, "HOME"), prop.text)
                "ADR" -> {
                    // PO box; extended; street; city; region; postcode; country
                    val a = prop.parts + List(7) { "" }
                    val cityLine = listOf(a[3], a[4], a[5]).filter { it.isNotBlank() }.joinToString(" ")
                    add(FieldKind.ADDRESS, homeWorkLabel(prop.types, "HOME"),
                        listOf(a[0], a[1], a[2], cityLine, a[6]).filter { it.isNotBlank() }.joinToString("\n"))
                }
                "URL" -> add(FieldKind.WEBSITE, "WEBSITE", prop.text)
                "NOTE" -> add(FieldKind.NOTE, "NOTE", prop.text)
                "NICKNAME" -> splitUnescaped(prop.rawValue, ',').map(::unescape).forEach { add(FieldKind.NICKNAME, "NICKNAME", it) }
                "BDAY" -> date(prop.text)?.let { add(FieldKind.DATE, "BIRTHDAY", it) }
                "ANNIVERSARY", "X-ANNIVERSARY" -> date(prop.text)?.let { add(FieldKind.DATE, "ANNIVERSARY", it) }
                "IMPP" -> {
                    val scheme = prop.text.substringBefore(':', "")
                    val service = prop.params["X-SERVICE-TYPE"]?.firstOrNull() ?: scheme
                    val value = if (scheme.isNotEmpty()) prop.text.substringAfter(':') else prop.text
                    if (service.equals("sip", ignoreCase = true)) add(FieldKind.SIP, "SIP", value)
                    else add(FieldKind.MESSAGING, messagingLabel(service), value)
                }
                "X-SIP" -> add(FieldKind.SIP, "SIP", prop.text.removePrefix("sip:"))
                "X-JABBER", "X-SKYPE", "X-SKYPE-USERNAME", "X-AIM", "X-MSN", "X-YAHOO", "X-QQ", "X-GOOGLE-TALK", "X-ICQ" ->
                    add(FieldKind.MESSAGING, messagingLabel(prop.name.removePrefix("X-")), prop.text)
                "RELATED" -> add(FieldKind.RELATED, prop.types.firstOrNull { it != "PREF" } ?: "RELATIVE", prop.text)
                // Android export: <mimetype>;data1;data2;data3
                "X-ANDROID-CUSTOM" -> {
                    val d = prop.parts + List(4) { "" }
                    when (d[0]) {
                        "vnd.android.cursor.item/nickname" -> add(FieldKind.NICKNAME, "NICKNAME", d[1])
                        "vnd.android.cursor.item/relation" -> add(FieldKind.RELATED, relationLabel(d[2].toIntOrNull(), d[3]), d[1])
                        "vnd.android.cursor.item/contact_event" -> date(d[1])?.let {
                            add(FieldKind.DATE, when (d[2]) { "3" -> "BIRTHDAY"; "1" -> "ANNIVERSARY"; else -> d[3].ifBlank { "OTHER" }.uppercase(Locale.ROOT) }, it)
                        }
                    }
                }
            }
        }

        if (listOf(c.first, c.middle, c.last).all { it.isBlank() } && !fullName.isNullOrBlank()) {
            val (first, last) = splitName(fullName)
            c = c.copy(first = first, last = last)
        }
        val result = c.copy(fields = fields)
        return result.takeIf { it != Contact() }
    }

    private fun phoneLabel(t: Set<String>) = when {
        "CELL" in t || "MOBILE" in t || "IPHONE" in t -> "MOBILE"
        "FAX" in t -> "FAX"
        "PAGER" in t -> "PAGER"
        "WORK" in t -> "WORK"
        "HOME" in t -> "HOME"
        "MAIN" in t -> "MAIN"
        "OTHER" in t -> "OTHER"
        else -> t.firstOrNull { it.startsWith("X-") }?.removePrefix("X-") ?: "MOBILE"
    }

    private fun homeWorkLabel(t: Set<String>, default: String) = when {
        "WORK" in t -> "WORK"
        "HOME" in t -> "HOME"
        "OTHER" in t -> "OTHER"
        else -> t.firstOrNull { it.startsWith("X-") }?.removePrefix("X-") ?: default
    }

    private fun messagingLabel(name: String) = when (name.lowercase(Locale.ROOT)) {
        "signal", "sgnl" -> "SIGNAL"
        "telegram", "tg" -> "TELEGRAM"
        "whatsapp" -> "WHATSAPP"
        "matrix" -> "MATRIX"
        "xmpp", "jabber" -> "XMPP"
        "skype", "skype-username" -> "SKYPE"
        else -> name.uppercase(Locale.ROOT).ifEmpty { "OTHER" }
    }

    private fun relationLabel(type: Int?, custom: String) = when (type) {
        1 -> "ASSISTANT"; 2 -> "BROTHER"; 3 -> "CHILD"; 4 -> "DOMESTIC PARTNER"; 5 -> "FATHER"; 6 -> "FRIEND"
        7 -> "MANAGER"; 8 -> "MOTHER"; 9 -> "PARENT"; 10 -> "PARTNER"; 11 -> "REFERRED BY"; 12 -> "RELATIVE"
        13 -> "SISTER"; 14 -> "SPOUSE"
        else -> custom.ifBlank { "RELATIVE" }.uppercase(Locale.ROOT)
    }

    /** vCard date → stored form; null if unreadable. */
    private fun date(raw: String): String? {
        val s = raw.trim().substringBefore('T')
        val iso = when {
            Regex("""\d{8}""").matches(s) -> "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
            Regex("""--\d{4}""").matches(s) -> "--${s.substring(2, 4)}-${s.substring(4, 6)}"
            else -> s
        }
        return dateForStorage(iso)
    }
}
