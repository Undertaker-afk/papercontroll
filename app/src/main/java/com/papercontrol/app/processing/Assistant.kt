package com.papercontrol.app.processing

import android.content.Context
import com.papercontrol.app.core.QuerySyntax
import com.papercontrol.app.core.Text
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.Filter
import com.papercontrol.app.data.Store
import com.papercontrol.app.ml.Llm
import com.papercontrol.app.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

data class Answer(val text: String, val sources: List<Document>, val usedAi: Boolean)

/**
 * "Ask your documents": finds the documents a question is about (by meaning, by words and
 * by the period it names), then lets the on-device model answer from them, citing each
 * document it used. Sums for "how much did I spend…" are computed exactly, not by the model.
 */
object Assistant {
    private val spendWords = Regex("(?i)\\b(spend|spent|spending|cost|paid|pay|total|sum|how much|ausgegeben|ausgaben|bezahlt|kosten|summe|wie viel|wieviel|gasté|dépensé|speso|uitgegeven)\\b")

    suspend fun ask(context: Context, question: String): Answer = withContext(Dispatchers.Default) {
        val period = period(question, LocalDate.now())
        val sources = LinkedHashMap<Long, Document>()

        // Meaning, then words.
        Search.byMeaning(context, question, 12)?.map { it.first }?.let { ids ->
            Store.documents(Filter(), 12, ids).sortedBy { ids.indexOf(it.id) }.forEach { sources[it.id] = it }
        }
        val words = Text.features(question).filter { it !in QUESTION_WORDS }
        for (w in words.take(6)) Store.documents(Filter(query = w), 6).forEach { sources.putIfAbsent(it.id, it) }

        // Everything in the period the question names (sums need all of them).
        val inPeriod = period?.let { (a, b) -> Store.documents(Filter(from = a, to = b), 200) }.orEmpty()
        val spend = spendWords.containsMatchIn(question)
        val subject = words.filter { it.length > 3 }
        val relevant = if (spend && period != null) {
            // Narrow to what the question is about ("coffee"), when that matches anything.
            val narrowed = inPeriod.filter { d -> subject.any { s -> Text.normalize(d.title + " " + d.content).contains(s) } || d.id in sources }
            narrowed.ifEmpty { inPeriod }
        } else inPeriod
        relevant.forEach { sources.putIfAbsent(it.id, it) }

        val labels = Store.allLabels()
        val list = sources.values.toList().take(40)
        val computed = if (spend) relevant.ifEmpty { list }.filter { it.amount != null }.let { docs ->
            if (docs.isEmpty()) null else docs.groupBy { it.currency }.entries.joinToString(" + ") { (cur, l) -> Fmt.money(l.sumOf { it.amount!! }, cur) } + " across ${docs.size} document${if (docs.size == 1) "" else "s"}"
        } else null

        if (!Llm.isReady(context)) {
            val text = when {
                list.isEmpty() -> "I found no documents about that."
                computed != null -> "Total: $computed. Turn on the AI model in Settings for full answers."
                else -> "These documents look relevant. Turn on the AI model in Settings to get answers in words."
            }
            return@withContext Answer(text, list.take(10), false)
        }

        val table = list.joinToString("\n") { d ->
            val parts = listOfNotNull(
                d.created?.toString(), d.correspondentId?.let { labels[it]?.name }, d.docTypeId?.let { labels[it]?.name },
                d.amount?.let { Fmt.money(it, d.currency) }, d.title,
            )
            "[#${d.id}] " + parts.joinToString(" | ") + "\n    " + d.content.replace(Regex("\\s+"), " ").take(if (list.size > 15) 160 else 500)
        }
        val prompt = buildString {
            appendLine("Today is ${LocalDate.now()}. Answer the question using only these documents from my archive.")
            appendLine("Cite every document you use as [#id]. If they do not contain the answer, say so. Be brief.")
            if (computed != null) appendLine("The exact total of the matching amounts is $computed; use this number.")
            appendLine()
            appendLine("Documents:")
            appendLine(table)
            appendLine()
            append("Question: ").append(question)
        }
        val reply = runCatching { Llm.answer(context, "You answer questions about a person's own documents: receipts, invoices, letters, contracts.", prompt) }
            .getOrElse { "The AI model could not answer: ${it.message}" }
        val cited = Regex("#(\\d+)").findAll(reply).mapNotNull { it.groupValues[1].toLongOrNull() }.toSet()
        Answer(reply.trim(), list.filter { it.id in cited }.ifEmpty { list.take(5) }, true)
    }

    /** The period a question names: "in March", "March 2026", "last month", "2025", "this year". */
    fun period(q: String, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val s = q.lowercase()
        Regex("\\b(last|this) (month|year)\\b|\\b(letzten|diesen) (monat|jahr)\\b").find(s)?.let { m ->
            val last = m.groupValues[1] == "last" || m.groupValues[3] == "letzten"
            val month = m.groupValues[2] == "month" || m.groupValues[4] == "monat"
            return QuerySyntax.dates((if (last) "last-" else "this-") + (if (month) "month" else "year"), today)?.let { (a, b) -> a!! to b!! }
        }
        val year = Regex("\\b(19|20)\\d{2}\\b").find(s)?.value?.toInt()
        for (m in Month.entries) {
            val names = listOf(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH).flatMap { listOf(m.getDisplayName(TextStyle.FULL, it).lowercase()) }
            if (names.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(s) }) {
                val y = year ?: if (m.value <= today.monthValue) today.year else today.year - 1
                val ym = YearMonth.of(y, m)
                return ym.atDay(1) to ym.atEndOfMonth()
            }
        }
        if (year != null) return LocalDate.of(year, 1, 1) to LocalDate.of(year, 12, 31)
        return null
    }

    private val QUESTION_WORDS = setOf(
        "what", "when", "where", "which", "how", "much", "many", "did", "does", "spend", "spent", "my", "was", "were", "last", "this", "month", "year",
        "was", "wann", "wie", "viel", "welche", "habe", "hab", "ich", "mein", "meine", "letzten", "monat", "jahr", "ausgegeben",
    ) + Month.entries.map { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() }
}
