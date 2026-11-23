package com.papercontrol.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** An axis-aligned box in image pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f

    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    companion object {
        fun unionOf(boxes: List<Box>): Box? = boxes.reduceOrNull { a, b -> a.union(b) }
    }
}

/** A piece of recognized text: one line as the recognizer reports it, or a word inside one. */
data class OcrLine(
    val text: String,
    val box: Box,
    val words: List<OcrLine> = emptyList(),
    /** Direction the text runs, in degrees clockwise from left-to-right (90 = reads downward). */
    val angle: Float = 0f,
    val confidence: Float = 1f,
)

/** Text recognized on one page image of [width] x [height] pixels. */
data class OcrResult(val lines: List<OcrLine>, val width: Int, val height: Int) {
    val text: String get() = rows().joinToString("\n") { it.text }

    /** Characters recognized, ignoring spaces: how much was read. */
    val chars: Int get() = lines.sumOf { l -> l.text.count { !it.isWhitespace() } }

    /**
     * How many clockwise quarter turns make the text upright (0 to 3). Decided by the lines'
     * directions, weighted by length, so a few odd lines do not decide it.
     */
    fun uprightTurns(): Int {
        val votes = IntArray(4)
        for (l in lines) {
            val n = l.text.count { it.isLetterOrDigit() }
            if (n < 2) continue
            val a = ((l.angle % 360f) + 360f) % 360f
            // Text reading at +90 needs a quarter turn back (counter-clockwise) = 3 clockwise turns.
            val quarter = ((a + 45f) / 90f).toInt() % 4
            votes[(4 - quarter) % 4] += n
        }
        val best = votes.indices.maxByOrNull { votes[it] } ?: 0
        return if (votes[best] == 0) 0 else best
    }

    /**
     * Lines that sit on the same visual row, left to right. Receipts print the label on the
     * left and the price on the right; recognizers often report them as separate lines.
     */
    fun rows(): List<OcrRow> {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.box.cy }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            if (row != null) {
                val h = row.map { it.box.height }.average().toFloat()
                val cy = row.map { it.box.cy }.average().toFloat()
                if (abs(line.box.cy - cy) < 0.55f * max(h, line.box.height)) {
                    row += line
                    continue
                }
            }
            rows += mutableListOf(line)
        }
        return rows.map { OcrRow(it.sortedBy { l -> l.box.left }) }
    }
}

/** One visual row of text, made of one or more lines from left to right. */
data class OcrRow(val parts: List<OcrLine>) {
    val text: String get() = parts.joinToString("  ") { it.text.trim() }
    val box: Box get() = Box.unionOf(parts.map { it.box })!!

    /** The box of the part (or word) whose text contains [needle]; falls back to the whole row. */
    fun boxOf(needle: String): Box {
        for (p in parts) {
            if (p.text.contains(needle)) {
                val words = p.words.filter { w ->
                    val t = w.text.trim()
                    t.isNotEmpty() && (t == needle || (t.length >= 2 && needle.contains(t)))
                }
                return Box.unionOf(words.map { it.box }) ?: p.box
            }
        }
        return box
    }
}
