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
data class OcrLine(val text: String, val box: Box, val words: List<OcrLine> = emptyList())

/** Text recognized on one page image of [width] x [height] pixels. */
data class OcrResult(val lines: List<OcrLine>, val width: Int, val height: Int) {
    val text: String get() = rows().joinToString("\n") { it.text }

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
