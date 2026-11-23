package com.papercontrol.app.core

import java.time.LocalDate
import java.time.YearMonth

/** One document as the expense overview sees it. */
data class Expense(val date: LocalDate, val amount: Double, val currency: String?, val category: String?, val sender: String?)

data class MonthTotal(val month: YearMonth, val total: Double, val count: Int)

object Stats {
    /** Totals per month for the [months] months up to and including [last], oldest first; empty months are zero. */
    fun monthly(items: List<Expense>, last: YearMonth, months: Int = 12): List<MonthTotal> {
        val byMonth = items.groupBy { YearMonth.from(it.date) }
        return (months - 1 downTo 0).map { back ->
            val m = last.minusMonths(back.toLong())
            val list = byMonth[m].orEmpty()
            MonthTotal(m, list.sumOf { it.amount }, list.size)
        }
    }

    /** Totals by a key (category or sender), largest first; null keys collected under [other]. */
    fun by(items: List<Expense>, key: (Expense) -> String?, other: String = "Other"): List<Pair<String, Double>> =
        items.groupBy { key(it) ?: other }.mapValues { (_, l) -> l.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** The currency most amounts are in, so mixed-currency archives show the main one. */
    fun mainCurrency(items: List<Expense>): String? = items.groupingBy { it.currency }.eachCount().maxByOrNull { it.value }?.key
}

object Csv {
    fun escape(v: String?): String {
        val s = v ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun write(header: List<String>, rows: List<List<String?>>): String =
        (listOf(header) + rows).joinToString("\r\n") { r -> r.joinToString(",") { escape(it) } } + "\r\n"
}
