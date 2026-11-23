package com.papercontrol.app.core

import java.time.LocalDate
import java.time.YearMonth

/**
 * The search box language. Free words search titles and text. Filters:
 *
 *  tag:receipts  -tag:inbox  from:"Nordlit Coffee"  type:invoice  folder:receipts  asn:12
 *  created:2024  created:2024-03  created:2023..2024  created:>2024-01-01  created:this-month
 *  added:7d  amount:>50  amount:10..20  is:inbox  is:due  is:duplicate  has:payment  has:notes
 *  field:Project=Garden  field:"Warranty until=2027"
 */
data class RawQuery(
    val text: String = "",
    val tags: List<String> = emptyList(),
    val notTags: List<String> = emptyList(),
    val correspondent: String? = null,
    val type: String? = null,
    val folder: String? = null,
    val asn: Int? = null,
    val createdFrom: LocalDate? = null,
    val createdTo: LocalDate? = null,
    val addedWithinDays: Int? = null,
    val amountMin: Double? = null,
    val amountMax: Double? = null,
    val flags: Set<String> = emptySet(),
    val fields: List<Pair<String, String>> = emptyList(),
)

object QuerySyntax {
    private val token = Regex("""(-?)(\w+):(?:"([^"]*)"|(\S+))|"([^"]*)"|(\S+)""")

    fun parse(query: String, today: LocalDate = LocalDate.now()): RawQuery {
        var q = RawQuery()
        val words = mutableListOf<String>()
        for (m in token.findAll(query)) {
            val neg = m.groupValues[1] == "-"
            val key = m.groupValues[2].lowercase()
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
            if (key.isEmpty()) {
                words += m.groupValues[5].ifEmpty { m.groupValues[6] }
                continue
            }
            q = when (key) {
                "tag" -> if (neg) q.copy(notTags = q.notTags + value) else q.copy(tags = q.tags + value)
                "correspondent", "from" -> q.copy(correspondent = value)
                "type" -> q.copy(type = value)
                "folder", "path" -> q.copy(folder = value)
                "asn" -> q.copy(asn = value.toIntOrNull() ?: -1)
                "created", "date", "year" -> dates(value, today)?.let { (a, b) -> q.copy(createdFrom = a, createdTo = b) } ?: q
                "added" -> q.copy(addedWithinDays = Regex("""(\d+)\s*([dwmy]?)""").matchEntire(value)?.let { r ->
                    val n = r.groupValues[1].toInt()
                    when (r.groupValues[2]) { "w" -> n * 7; "m" -> n * 30; "y" -> n * 365; else -> n }
                })
                "amount", "total" -> amounts(value)?.let { (a, b) -> q.copy(amountMin = a, amountMax = b) } ?: q
                "is", "has" -> q.copy(flags = q.flags + "$key:${value.lowercase()}")
                "field" -> value.split('=', limit = 2).let { p -> if (p.size == 2) q.copy(fields = q.fields + (p[0].trim() to p[1].trim())) else q }
                else -> { words += m.value; q }
            }
        }
        return q.copy(text = words.joinToString(" "))
    }

    /** A single date, month, year, a range "a..b", or ">a" / "<a", or a named period. */
    fun dates(v: String, today: LocalDate): Pair<LocalDate?, LocalDate?>? {
        val s = v.trim().lowercase()
        when (s) {
            "today" -> return today to today
            "this-week", "week" -> return today.minusDays(today.dayOfWeek.value - 1L) to today
            "this-month", "month" -> return today.withDayOfMonth(1) to today
            "last-month" -> YearMonth.from(today).minusMonths(1).let { return it.atDay(1) to it.atEndOfMonth() }
            "this-year", "year" -> return today.withDayOfYear(1) to today
            "last-year" -> return LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31)
        }
        if (s.startsWith(">")) return start(s.drop(1).trimStart('='))?.let { it to null }
        if (s.startsWith("<")) return end(s.drop(1).trimStart('='))?.let { null to it }
        if (s.contains("..")) {
            val (a, b) = s.split("..", limit = 2)
            val from = if (a.isBlank()) null else start(a) ?: return null
            val to = if (b.isBlank()) null else end(b) ?: return null
            return from to to
        }
        val a = start(s) ?: return null
        return a to end(s)
    }

    private fun start(s: String): LocalDate? = runCatching {
        when {
            Regex("""\d{4}""").matches(s) -> LocalDate.of(s.toInt(), 1, 1)
            Regex("""\d{4}-\d{1,2}""").matches(s) -> YearMonth.parse(pad(s)).atDay(1)
            else -> LocalDate.parse(s)
        }
    }.getOrNull()

    private fun end(s: String): LocalDate? = runCatching {
        when {
            Regex("""\d{4}""").matches(s) -> LocalDate.of(s.toInt(), 12, 31)
            Regex("""\d{4}-\d{1,2}""").matches(s) -> YearMonth.parse(pad(s)).atEndOfMonth()
            else -> LocalDate.parse(s)
        }
    }.getOrNull()

    private fun pad(s: String) = s.split('-').let { "${it[0]}-${it[1].padStart(2, '0')}" }

    fun amounts(v: String): Pair<Double?, Double?>? {
        val s = v.replace(',', '.').trim()
        fun n(x: String) = x.trim().toDoubleOrNull()
        return when {
            s.startsWith(">") -> n(s.drop(1).trimStart('='))?.let { it to null }
            s.startsWith("<") -> n(s.drop(1).trimStart('='))?.let { null to it }
            s.contains("..") -> s.split("..", limit = 2).let { (a, b) -> (if (a.isBlank()) null else n(a)) to (if (b.isBlank()) null else n(b)) }
            else -> n(s)?.let { it - 0.005 to it + 0.005 }
        }
    }
}
