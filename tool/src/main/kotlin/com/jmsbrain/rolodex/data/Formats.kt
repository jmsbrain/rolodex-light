package com.jmsbrain.rolodex.data

import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Digits, leading +, and * # , ; only. Hidden characters break LightOS dialing. */
fun dialableNumber(raw: String): String {
    val sb = StringBuilder()
    for (ch in raw) {
        val d = Character.digit(ch, 10)
        when {
            d >= 0 -> sb.append('0' + d)
            ch == '+' && sb.isEmpty() -> sb.append(ch)
            ch == '*' || ch == '#' || ch == ',' || ch == ';' -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** Stored date → "April 12, 1988" / "April 12". */
fun dateForDisplay(stored: String): String = try {
    if (stored.startsWith("--")) MonthDay.parse(stored).format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
    else LocalDate.parse(stored.take(10)).format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()))
} catch (e: Exception) {
    stored
}

/** Stored date → MM/dd/yyyy or MM/dd. */
fun dateForEditing(stored: String): String = try {
    if (stored.startsWith("--")) MonthDay.parse(stored).format(DateTimeFormatter.ofPattern("MM/dd"))
    else LocalDate.parse(stored.take(10)).format(DateTimeFormatter.ofPattern("MM/dd/yyyy"))
} catch (e: Exception) {
    stored
}

/** M/D/YYYY, M/D, yyyy-MM-dd or --MM-dd → stored form; null if invalid. */
fun dateForStorage(input: String): String? {
    val s = input.trim()
    Regex("""(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{4}))?""").matchEntire(s)?.let { m ->
        val month = m.groupValues[1].toInt()
        val day = m.groupValues[2].toInt()
        val year = m.groupValues[3]
        return try {
            if (year.isEmpty()) MonthDay.of(month, day).toString() else LocalDate.of(year.toInt(), month, day).toString()
        } catch (e: DateTimeException) {
            null
        }
    }
    return runCatching { LocalDate.parse(s).toString() }.getOrNull()
        ?: runCatching { MonthDay.parse(s).toString() }.getOrNull()
}

/** "Jane Q. Public" → ("Jane Q.", "Public"). */
fun splitName(full: String): Pair<String, String> {
    val words = full.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (words.size) {
        0 -> "" to ""
        1 -> words[0] to ""
        else -> words.dropLast(1).joinToString(" ") to words.last()
    }
}
