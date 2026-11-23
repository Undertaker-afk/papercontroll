package com.papercontrol.app.core

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/** Duplicate detection and "more like this". */
object Similarity {

    /**
     * 64-bit difference hash of a page: survives re-scans, small shifts and exposure changes,
     * changes completely for a different page. [lum] is a 9 x 8 luminance grid.
     */
    fun dHash(lum: IntArray): Long {
        require(lum.size == 72)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (lum[y * 9 + x] > lum[y * 9 + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    data class Fingerprint(
        val id: Long,
        val hash: Long?,
        val total: Double?,
        val date: LocalDate?,
        val sender: String?,
        val text: String,
    )

    /** Why [candidate] looks like a copy of [existing], or null when it does not. */
    fun duplicateReason(candidate: Fingerprint, existing: Fingerprint): String? {
        val h1 = candidate.hash
        val h2 = existing.hash
        if (h1 != null && h2 != null && hamming(h1, h2) <= 6) return "Same picture"
        val sameTotal = candidate.total != null && existing.total != null && abs(candidate.total - existing.total) < 0.005
        val sameDate = candidate.date != null && candidate.date == existing.date
        val sameSender = candidate.sender != null && existing.sender != null &&
            Text.partialRatio(Text.normalize(candidate.sender), Text.normalize(existing.sender)) >= 88
        if (sameTotal && sameDate && (sameSender || existing.sender == null || candidate.sender == null)) return "Same amount and date"
        if (candidate.text.length > 200 && existing.text.length > 200 && textOverlap(candidate.text, existing.text) > 0.9) return "Same text"
        return null
    }

    /** Share of distinct words both texts have, out of the smaller set. */
    fun textOverlap(a: String, b: String): Double {
        val wa = Text.features(a).toSet()
        val wb = Text.features(b).toSet()
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return (wa intersect wb).size.toDouble() / minOf(wa.size, wb.size)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0f || nb == 0f) 0f else d / (sqrt(na) * sqrt(nb))
    }

    /**
     * Ranks [others] by word similarity to [doc] (TF-IDF cosine). Used for "more like this"
     * when no embedding model is installed.
     */
    fun rankByText(doc: String, others: List<Pair<Long, String>>, limit: Int = 10): List<Pair<Long, Double>> {
        val docs = others.map { it.first to Text.features(it.second).groupingBy { w -> w }.eachCount() }
        val target = Text.features(doc).groupingBy { it }.eachCount()
        val df = HashMap<String, Int>()
        for ((_, f) in docs) for (w in f.keys) df[w] = (df[w] ?: 0) + 1
        val n = docs.size + 1.0
        fun vec(f: Map<String, Int>): Map<String, Double> {
            val v = f.mapValues { (w, c) -> (1 + kotlin.math.ln(c.toDouble())) * kotlin.math.ln(n / (1 + (df[w] ?: 0))) }
            val norm = sqrt(v.values.sumOf { it * it })
            return if (norm == 0.0) v else v.mapValues { it.value / norm }
        }
        val t = vec(target)
        return docs.map { (id, f) ->
            val v = vec(f)
            id to t.entries.sumOf { (k, x) -> x * (v[k] ?: 0.0) }
        }.filter { it.second > 0.05 }.sortedByDescending { it.second }.take(limit)
    }
}
