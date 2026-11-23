package com.papercontrol.app.core

/** Picks a page's heading: what a person would call the document. */
object Heading {
    private val notAName = Regex("""(?i)^(www\.|https?://|tel|fon|phone|fax|e-?mail|iban|bic|page|seite)\b|\d{2}[./-]\d{2}[./-]\d{2,4}|@""")

    /** Short, mostly letters, not a date, phone number, address line or web link. */
    fun plausible(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 3..48) return false
        if (t.split(Regex("\\s+")).size > 7) return false
        val nonSpace = t.count { !it.isWhitespace() }
        if (t.count { it.isLetter() } < nonSpace * 0.6) return false
        if (notAName.containsMatchIn(t)) return false
        if (t.count { it.isDigit() } > 5) return false
        return true
    }

    /** The tallest plausible line in the top part of the page, if it stands out; otherwise the first plausible line. */
    fun pick(ocr: OcrResult): OcrLine? {
        val lines = ocr.lines.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return null
        val heights = lines.map { it.box.height }.sorted()
        val median = heights[heights.size / 2]
        val top = lines.filter { it.box.cy < ocr.height * 0.4f && plausible(it.text) }
        if (top.isEmpty()) return null
        val tallest = top.maxByOrNull { it.box.height * (1f - 0.3f * it.box.cy / ocr.height) }!!
        return if (tallest.box.height >= 1.3f * median) tallest else top.minByOrNull { it.box.cy }
    }

    /** A title for a document with no clear sender: its heading, or the first words of the text. */
    fun title(ocr: OcrResult, kind: DocKind, date: java.time.LocalDate?): String {
        pick(ocr)?.let { return ReceiptParser.prettyName(it.text.trim()) }
        val first = ocr.rows().map { it.text.trim() }.firstOrNull { l -> l.count { it.isLetter() } >= 6 }
        if (first != null) {
            val words = first.split(Regex("\\s+"))
            return words.take(6).joinToString(" ") + if (words.size > 6) "…" else ""
        }
        val k = when (kind) { DocKind.RECEIPT -> "Receipt"; DocKind.INVOICE -> "Invoice"; DocKind.LETTER -> "Letter"; DocKind.OTHER -> "Document" }
        return if (date != null) "$k $date" else k
    }
}
