package com.papercontrol.app.core

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** How a tag, correspondent, document type or folder decides whether it fits a document. Same set as Paperless-ngx. */
enum class MatchAlgorithm(val label: String, val help: String) {
    NONE("None", "Never assigned automatically."),
    ANY("Any word", "The document contains any of these words."),
    ALL("All words", "The document contains all of these words."),
    LITERAL("Exact", "The document contains this exact text."),
    REGEX("Regular expression", "The document matches this regular expression."),
    FUZZY("Fuzzy word", "The document contains text close to this, typos allowed."),
    AUTO("Auto (AI)", "Learned from the documents you have already sorted.");
}

/** Anything a document can be sorted by: tag, correspondent, document type or folder. */
interface Matchable {
    val id: Long
    val name: String
    val match: String
    val algorithm: MatchAlgorithm
    val caseInsensitive: Boolean
}

object Text {
    private val diacritics = Regex("\\p{Mn}+")
    private val wordRe = Regex("[\\p{L}\\p{N}]+")

    /** Lower case, no accents, single spaces: the form all matching works on. */
    fun normalize(s: String, caseInsensitive: Boolean = true): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(diacritics, "")
        return (if (caseInsensitive) n.lowercase() else n).replace(Regex("\\s+"), " ").trim()
    }

    fun words(s: String): List<String> = wordRe.findAll(s).map { it.value }.toList()

    /** Words useful for learning and comparing: at least 3 letters, no pure numbers, no stop words. */
    fun features(s: String): List<String> =
        words(normalize(s)).filter { it.length >= 3 && it.any(Char::isLetter) && it !in STOP }

    /** Paperless-style word list: whitespace separated, "quoted phrases" stay together. */
    fun splitMatch(match: String): List<String> =
        Regex("\"([^\"]+)\"|(\\S+)").findAll(match).map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim() }
            .filter { it.isNotEmpty() }.toList()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Best similarity (0..100) of [needle] against any same-length window of words in [hay]. */
    fun partialRatio(needle: String, hay: String): Int {
        if (needle.isEmpty() || hay.isEmpty()) return 0
        if (hay.contains(needle)) return 100
        val nWords = needle.split(' ').size
        val hw = hay.split(' ')
        var best = 0
        for (size in max(1, nWords - 1)..(nWords + 1)) {
            for (i in 0..max(0, hw.size - size)) {
                val window = hw.subList(i, min(hw.size, i + size)).joinToString(" ")
                val d = levenshtein(needle, window)
                val r = (100 * (1.0 - d.toDouble() / max(needle.length, window.length))).toInt()
                if (r > best) best = r
            }
        }
        return best
    }

    private val STOP = setOf(
        "the", "and", "for", "you", "your", "with", "this", "that", "from", "are", "was", "will", "have", "has", "not", "but", "all", "our",
        "der", "die", "das", "und", "ist", "mit", "von", "den", "dem", "des", "ein", "eine", "einer", "für", "auf", "sie", "wir", "ihr", "ihre", "bei", "aus", "nicht", "sich", "auch", "nach", "wie", "als", "oder", "zum", "zur",
    )
}

/** The rule-based Paperless-ngx matching algorithms. AUTO is answered by [Classifier]. */
object Matcher {
    fun matches(m: Matchable, content: String): Boolean {
        if (m.match.isBlank() && m.algorithm != MatchAlgorithm.AUTO) return false
        val ci = m.caseInsensitive
        val text = Text.normalize(content, ci)
        val pattern = if (m.algorithm == MatchAlgorithm.REGEX) m.match else Text.normalize(m.match, ci)
        return when (m.algorithm) {
            MatchAlgorithm.NONE, MatchAlgorithm.AUTO -> false
            MatchAlgorithm.ANY -> Text.splitMatch(pattern).any { containsWord(text, it) }
            MatchAlgorithm.ALL -> Text.splitMatch(pattern).let { w -> w.isNotEmpty() && w.all { containsWord(text, it) } }
            MatchAlgorithm.LITERAL -> containsWord(text, pattern)
            MatchAlgorithm.REGEX -> try {
                Regex(pattern, if (ci) setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE) else setOf(RegexOption.MULTILINE)).containsMatchIn(content)
            } catch (_: Exception) {
                false
            }
            MatchAlgorithm.FUZZY -> Text.partialRatio(pattern, text) >= 90
        }
    }

    private fun containsWord(text: String, word: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)
}

/**
 * The "Auto" matching algorithm: a small multinomial naive Bayes model trained on the
 * documents the user has already sorted, like the classifier Paperless-ngx trains.
 * Single-label targets (correspondent, type, folder) pick the most likely class; tags are
 * decided one by one.
 */
class Classifier private constructor(
    private val classes: Map<Long, ClassStats>,
    private val vocabSize: Int,
    private val totalDocs: Int,
) {
    private class ClassStats(val docs: Int, val counts: Map<String, Int>, val total: Int)

    /** Log-likelihood of the document under each class, as probabilities that sum to 1. */
    fun probabilities(content: String): Map<Long, Double> {
        if (classes.isEmpty()) return emptyMap()
        val f = Text.features(content).groupingBy { it }.eachCount()
        val scores = classes.mapValues { (_, c) ->
            var s = ln(c.docs.toDouble() / totalDocs)
            for ((w, n) in f) s += n * ln((c.counts[w] ?: 0) + 1.0) - n * ln(c.total + vocabSize.toDouble())
            s
        }
        val mx = scores.values.max()
        val e = scores.mapValues { exp(it.value - mx) }
        val sum = e.values.sum()
        return e.mapValues { it.value / sum }
    }

    /** The class that fits best, if it is clearly ahead. */
    fun predict(content: String, minProbability: Double = 0.6): Long? {
        if (classes.size < 2) return null
        val p = probabilities(content)
        val best = p.maxByOrNull { it.value } ?: return null
        return best.key.takeIf { best.value >= minProbability }
    }

    companion object {
        const val NONE = -1L

        /** [examples]: document text and the class it was sorted into ([NONE] for "nothing"). */
        fun train(examples: List<Pair<String, Long>>): Classifier {
            val byClass = examples.groupBy({ it.second }, { it.first })
            val vocab = HashSet<String>()
            val stats = byClass.mapValues { (_, docs) ->
                val counts = HashMap<String, Int>()
                for (d in docs) for (w in Text.features(d)) {
                    counts[w] = (counts[w] ?: 0) + 1
                    vocab += w
                }
                ClassStats(docs.size, counts, counts.values.sum())
            }
            return Classifier(stats, max(1, vocab.size), max(1, examples.size))
        }

        /** One yes/no model per tag: decides whether [tagId] belongs on a document. */
        fun trainBinary(examples: List<Pair<String, Boolean>>): Classifier =
            train(examples.map { it.first to if (it.second) 1L else 0L })
    }
}
