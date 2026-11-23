package com.papercontrol.app.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One photographed page before it belongs to a document. */
data class PageInput(
    /** Caller's handle for the page, returned unchanged in the groups. */
    val key: Int,
    val ocr: OcrResult,
    /** When the photo was taken (ms since epoch), 0 when unknown. */
    val capturedAt: Long = 0,
    /** Average paper color (r, g, b in 0..1), null when unknown. */
    val tone: FloatArray? = null,
    val extraction: Extraction = ReceiptParser.parse(ocr),
)

/** Pages that belong to one document, in reading order, and why they were put together. */
data class PageGroup(val keys: List<Int>, val confidence: Double, val reasons: List<String>)

/**
 * Paper mapping: finds which photos show pages (or pieces of a long receipt) of the same
 * document, puts them together and in order.
 *
 * Every pair of pages gets a score from the evidence a person would use: shared invoice or
 * customer numbers, "page 2 of 3" markers, text that continues from the bottom of one photo
 * to the top of the next, the same sender and date, similar wording, photos taken right
 * after each other, and the same paper. Conflicting evidence (different dates, two
 * different totals, two different invoice numbers) pushes pages apart. Pages are then
 * joined bottom-up while the evidence holds.
 */
object PaperMapper {
    const val JOIN_THRESHOLD = 0.42

    private val idRe = Regex("""\b[A-Z0-9][A-Z0-9/_.-]{3,}[A-Z0-9]\b""", RegexOption.IGNORE_CASE)
    private val dateLike = Regex("""^\d{1,4}[./-]\d{1,2}[./-]\d{1,4}$""")
    private val amountLike = Regex("""^-?\d+[.,]\d{2}$""")
    private val timeLike = Regex("""^\d{1,2}[:.]\d{2}$""")

    internal class Page(val input: PageInput, val order: Int) {
        val ex = input.extraction
        val rows = input.ocr.rows().map { Text.normalize(it.text) }.filter { it.length >= 4 }
        val ids: Set<String> = idRe.findAll(input.ocr.text).map { it.value.uppercase() }
            .filter { v -> v.count(Char::isDigit) >= 4 && !dateLike.matches(v) && !amountLike.matches(v) && !timeLike.matches(v) }
            .toSet()
        val features: Map<String, Int> = Text.features(input.ocr.text).groupingBy { it }.eachCount()
        val hasHeader = ex.merchant != null && rows.isNotEmpty() && Text.normalize(ex.merchant).let { m -> rows.take(3).any { it.contains(m) } }
        val hasTotal = ex.total != null
    }

    data class PairScore(val score: Double, val reasons: List<String>, val continues: Boolean = false)

    fun group(inputs: List<PageInput>): List<PageGroup> {
        if (inputs.isEmpty()) return emptyList()
        val pages = inputs.sortedWith(compareBy({ it.capturedAt }, { it.key })).mapIndexed { i, p -> Page(p, i) }
        val n = pages.size
        val idf = idf(pages)
        val vectors = pages.map { tfidf(it.features, idf) }

        val score = Array(n) { DoubleArray(n) }
        val reasons = HashMap<Pair<Int, Int>, List<String>>()
        val follows = HashSet<Pair<Int, Int>>() // (a, b): b continues a
        for (i in 0 until n) for (j in i + 1 until n) {
            val s = pairScore(pages[i], pages[j], cosine(vectors[i], vectors[j]))
            score[i][j] = s.score; score[j][i] = s.score
            reasons[i to j] = s.reasons
            if (s.continues) follows += i to j
            val back = continuation(pages[j], pages[i])
            if (back) follows += j to i
        }

        // Average-linkage clustering with a shortcut for one very strong link.
        val clusters = (0 until n).map { mutableListOf(it) }.toMutableList()
        while (clusters.size > 1) {
            var best = Double.NEGATIVE_INFINITY
            var bi = -1
            var bj = -1
            for (a in clusters.indices) for (b in a + 1 until clusters.size) {
                val l = link(clusters[a], clusters[b], score)
                if (l > best) { best = l; bi = a; bj = b }
            }
            if (bi < 0 || best < JOIN_THRESHOLD) break
            clusters[bi].addAll(clusters[bj])
            clusters.removeAt(bj)
        }

        return clusters.map { c ->
            val ordered = orderPages(c.map { pages[it] }, follows)
            val why = LinkedHashSet<String>()
            var minLink = 1.0
            for (a in c) for (b in c) if (a < b) {
                reasons[a to b]?.let { why += it }
                minLink = min(minLink, score[a][b])
            }
            val confidence = if (c.size == 1) 1.0 else ((c.sumOf { a -> c.filter { it != a }.sumOf { b -> score[a][b] } } / (c.size * (c.size - 1))).coerceIn(0.0, 1.0))
            PageGroup(ordered.map { it.input.key }, confidence, why.toList().take(4))
        }.sortedBy { g -> pages.first { it.input.key == g.keys.first() }.order }
    }

    /**
     * Neighbouring groups that were kept apart but not by much: worth a second opinion
     * (the chat model). Returns pairs of indices into [groups].
     */
    fun undecided(inputs: List<PageInput>, groups: List<PageGroup>, low: Double = 0.1): List<Pair<Int, Int>> {
        val byKey = inputs.associateBy { it.key }
        val pages = inputs.sortedWith(compareBy({ it.capturedAt }, { it.key })).mapIndexed { i, p -> Page(p, i) }
        val idf = idf(pages)
        val pageOf = pages.associateBy { it.input.key }
        val out = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until groups.size - 1) {
            val a = pageOf[groups[i].keys.last()] ?: continue
            val b = pageOf[groups[i + 1].keys.first()] ?: continue
            if (byKey[a.input.key] == null || byKey[b.input.key] == null) continue
            val s = pairScore(a, b, cosine(tfidf(a.features, idf), tfidf(b.features, idf))).score
            if (s >= low && s < JOIN_THRESHOLD) out += i to i + 1
        }
        return out
    }

    private fun link(a: List<Int>, b: List<Int>, s: Array<DoubleArray>): Double {
        var sum = 0.0
        var mx = Double.NEGATIVE_INFINITY
        var mn = Double.POSITIVE_INFINITY
        for (i in a) for (j in b) {
            val v = s[i][j].coerceIn(-1.0, 1.2)
            sum += v; mx = max(mx, v); mn = min(mn, v)
        }
        val avg = sum / (a.size * b.size)
        // A long document's first and last page may share little; one strong link without
        // any conflict is enough to keep it together.
        return if (mx >= 0.7 && mn > -0.25) max(avg, mx * 0.75) else avg
    }

    internal fun pairScore(a: Page, b: Page, cos: Double): PairScore {
        var s = 0.0
        val why = mutableListOf<String>()
        val ea = a.ex
        val eb = b.ex

        val shared = a.ids intersect b.ids
        if (shared.isNotEmpty()) {
            s += min(0.75, 0.55 + 0.1 * (shared.size - 1))
            why += "Same number ${shared.maxBy { it.length }}"
        }
        fun conflict(x: String?, y: String?) = x != null && y != null && x.uppercase() != y.uppercase()
        if (conflict(ea.invoiceNumber, eb.invoiceNumber)) s -= 0.7
        if (conflict(ea.orderNumber, eb.orderNumber)) s -= 0.4

        if (ea.date != null && eb.date != null) {
            if (ea.date == eb.date) { s += 0.2; why += "Same date" } else s -= 0.45
        }

        if (ea.pageCount != null && eb.pageCount != null) {
            when {
                ea.pageCount != eb.pageCount -> s -= 0.35
                ea.pageNumber != eb.pageNumber -> { s += 0.4; why += "Page ${ea.pageNumber}/${ea.pageCount} and ${eb.pageNumber}/${eb.pageCount}" }
                else -> s -= 0.4
            }
        } else if ((ea.pageNumber ?: 0) > 1 || (eb.pageNumber ?: 0) > 1) {
            s += 0.05
        }

        if (ea.merchant != null && eb.merchant != null) {
            val r = Text.partialRatio(Text.normalize(ea.merchant), Text.normalize(eb.merchant))
            if (r >= 85) { s += 0.2; why += "Same sender ${ea.merchant}" } else s -= 0.15
        }

        if (ea.total != null && eb.total != null && abs(ea.total - eb.total) > 0.005 && a.hasHeader && b.hasHeader) s -= 0.5

        s += 0.5 * cos
        if (cos > 0.35) why += "Similar wording"

        val continues = continuation(a, b)
        if (continues) { s += 0.6; why += "Text continues from one photo to the next" }

        // A page with the letterhead but no total, then one with the total but no letterhead.
        val headThenTail = (a.hasHeader && !a.hasTotal && b.hasTotal && !b.hasHeader) || (b.hasHeader && !b.hasTotal && a.hasTotal && !a.hasHeader)
        if (headThenTail && abs(a.order - b.order) == 1) { s += 0.25; why += "Start and end of one document" }

        if (a.input.capturedAt > 0 && b.input.capturedAt > 0) {
            val dt = abs(a.input.capturedAt - b.input.capturedAt)
            when {
                dt <= 90_000 -> { s += 0.15; why += "Taken right after each other" }
                dt <= 600_000 -> s += 0.05
                dt > 86_400_000 -> s -= 0.1
            }
        }

        val ta = a.input.tone
        val tb = b.input.tone
        if (ta != null && tb != null) {
            val d = sqrt((0 until 3).sumOf { ((ta[it] - tb[it]) * (ta[it] - tb[it])).toDouble() })
            if (d < 0.06) s += 0.05 else if (d > 0.2) { s -= 0.15 }
        }
        return PairScore(s, why, continues)
    }

    /** True when the last rows of [a] reappear as the first rows of [b] (overlapping photos of one long receipt). */
    internal fun continuation(a: Page, b: Page): Boolean {
        if (a.rows.size < 3 || b.rows.size < 3) return false
        val tail = a.rows.takeLast(min(8, a.rows.size))
        val head = b.rows.take(min(8, b.rows.size))
        var hits = 0
        for (t in tail) if (t.length >= 6 && head.any { h -> h.length >= 6 && Text.partialRatio(t, h) >= 88 && Text.partialRatio(h, t) >= 88 }) hits++
        return hits >= 2
    }

    private fun orderPages(pages: List<Page>, follows: Set<Pair<Int, Int>>): List<Page> {
        if (pages.size == 1) return pages
        fun rank(p: Page): Double {
            p.ex.pageNumber?.let { return it * 1000.0 }
            var r = p.order * 10.0
            if (p.hasHeader && !p.hasTotal) r -= 5000
            if (p.hasTotal && !p.hasHeader) r += 5000
            return r
        }
        val list = pages.sortedBy { rank(it) }.toMutableList()
        // Respect "continues" links: a few bubble passes are enough for short documents.
        repeat(list.size) {
            var changed = false
            for (i in 0 until list.size - 1) {
                for (j in i + 1 until list.size) {
                    if ((list[j].order to list[i].order) in follows && list[i].ex.pageNumber == null) {
                        val x = list.removeAt(j)
                        list.add(i, x)
                        changed = true
                    }
                }
            }
            if (!changed) return list
        }
        return list
    }

    private fun idf(pages: List<Page>): Map<String, Double> {
        val df = HashMap<String, Int>()
        for (p in pages) for (w in p.features.keys) df[w] = (df[w] ?: 0) + 1
        val n = pages.size.toDouble()
        return df.mapValues { ln((n + 1) / (it.value + 0.5)) }
    }

    private fun tfidf(f: Map<String, Int>, idf: Map<String, Double>): Map<String, Double> {
        val v = f.mapValues { (w, c) -> (1 + ln(c.toDouble())) * (idf[w] ?: 0.0) }
        val norm = sqrt(v.values.sumOf { it * it })
        return if (norm == 0.0) v else v.mapValues { it.value / norm }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        val (s, l) = if (a.size < b.size) a to b else b to a
        var d = 0.0
        for ((k, v) in s) d += v * (l[k] ?: 0.0)
        return d
    }
}
