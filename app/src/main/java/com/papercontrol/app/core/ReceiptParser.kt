package com.papercontrol.app.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

enum class DocKind { RECEIPT, INVOICE, LETTER, OTHER }

data class LineItem(val name: String, val amount: Double, val quantity: Int = 1)

/** Fields read from a receipt, invoice or letter. Every field is optional. */
data class Extraction(
    val kind: DocKind = DocKind.OTHER,
    val merchant: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val orderNumber: String? = null,
    val table: String? = null,
    val invoiceNumber: String? = null,
    val items: List<LineItem> = emptyList(),
    val subtotal: Double? = null,
    val taxLabel: String? = null,
    val tax: Double? = null,
    val total: Double? = null,
    val currency: String? = null,
    val cardBrand: String? = null,
    val cardLast4: String? = null,
    val authCode: String? = null,
    val pageNumber: Int? = null,
    val pageCount: Int? = null,
    /** Where the most important fields sit on the page, for the scan highlight animation. */
    val highlights: List<Box> = emptyList(),
    /** Bank transfer details from a payment QR code. */
    val payment: Payment? = null,
) {
    /** A short title in the style "Nordlit Coffee" or "Invoice 2024-118 · ACME GmbH". */
    fun suggestedTitle(): String? = when {
        kind == DocKind.INVOICE && invoiceNumber != null && merchant != null -> "Invoice $invoiceNumber · $merchant"
        kind == DocKind.INVOICE && merchant != null -> "Invoice · $merchant"
        merchant != null -> merchant
        else -> null
    }
}

/**
 * Reads receipt and invoice fields from recognized text. Works on the visual rows of the
 * page, so labels on the left and amounts on the right are read together. English and
 * German keywords are understood; amounts may use either decimal separator.
 */
object ReceiptParser {

    private val amountAtEnd = Regex("""(?:([$€£]|EUR|USD|GBP|CHF)\s?)?(-?\d{1,3}(?:[.,' ]\d{3})*[.,]\d{2}|-?\d+[.,]\d{2})\s*(?:([$€£]|EUR|USD|GBP|CHF)|[A-Z])?\s*$""")
    private val timeRe = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?\s?([AaPp]\.?[Mm]\.?)?""")
    private val numericDate = Regex("""\b(\d{1,4})([./-])(\d{1,2})\2(\d{2,4})\b""")
    private val monthNames: Map<String, Month> = buildMap {
        for (m in Month.entries) {
            for (loc in listOf(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, Locale.ITALIAN, Locale.forLanguageTag("es"), Locale.forLanguageTag("nl"), Locale.forLanguageTag("pt"))) {
                put(m.getDisplayName(TextStyle.FULL, loc).lowercase(), m)
                put(m.getDisplayName(TextStyle.SHORT, loc).lowercase().trimEnd('.'), m)
            }
        }
        put("mrz", Month.MARCH); put("sept", Month.SEPTEMBER); put("fevrier", Month.FEBRUARY); put("aout", Month.AUGUST); put("decembre", Month.DECEMBER)
    }
    private val textDate1 = Regex("""\b(\d{1,2})\.?\s+(?:de\s+)?([\p{L}]{3,10})\.?\s+(?:de\s+)?(\d{4})\b""") // 18 Nov 2026, 18. März 2026
    private val textDate2 = Regex("""\b([A-Za-z]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})\b""") // Nov 18, 2026

    private val totalWords = Regex("""(?i)\b(grand\s*total|total(?:\s*ttc)?|amount\s*due|balance\s*due|summe|gesamt(?:betrag|summe)?|zu\s*zahlen|endbetrag|rechnungsbetrag|betrag|montant|net\s*à\s*payer|à\s*payer|importe|total\s*a\s*pagar|totale|da\s*pagare|totaal|te\s*betalen|bedrag|summa)\b""")
    private val notTotalWords = Regex("""(?i)(sub\s*-?\s*total|sous-total|subtotale|subtotaal|zwischensumme|total\s*(tax|vat|items|savings|discount|ht|tva|iva)|netto|base\s*imponible|imponibile)""")
    private val subtotalWords = Regex("""(?i)\b(sub\s*-?\s*total|sous-total|subtotale|subtotaal|total\s*ht|zwischensumme|netto(?:betrag)?|net\s*amount|base\s*imponible|imponibile)\b""")
    private val taxWords = Regex("""(?i)\b(tax|vat|mwst|ust|gst|hst|steuer|tva|iva|btw|moms|mva)\b\.?\s*(\d{1,2}(?:[.,]\d+)?\s?%)?""")
    private val nonItemWords = Regex("""(?i)\b(total|summe|gesamt|tax|vat|mwst|ust|change|rückgeld|cash|bar|visa|mastercard|amex|maestro|girocard|card|karte|tip|trinkgeld|auth|balance|zu zahlen|gegeben|subtotal|zwischensumme|netto|brutto|ec)\b""")
    private val cardRe = Regex("""(?i)\b(visa|mastercard|master\s*card|amex|american express|maestro|girocard|discover|ec[- ]?karte|debit|credit)\b.*?(?:[*xX•·.]{2,}\s*)(\d{4})""")
    private val authRe = Regex("""(?i)\b(?:auth(?:orization|orisation)?|genehmigung|approval)(?:\s*(?:code|no|nr|#))?\s*[:#.]?\s*([A-Z0-9]{4,10})\b""")
    private val orderRe = Regex("""(?i)\b(?:order|bestellung|bon|beleg|receipt|ticket|transaction|trans|commande|pedido|ordine|bestelling)\s*(?:no\.?|nr\.?|number|#)?\s*[:#]?\s*#?\s*([A-Z0-9-]{2,})""")
    private val invoiceRe = Regex("""(?i)\b(?:invoice|rechnung|facture|factura|fattura|factuur)\s*(?:no\.?|nr\.?|n[°º]\.?|number|nummer|núm\.?|num\.?|#)?\s*[:#.]?\s*([A-Z0-9][A-Z0-9/_-]{2,})""")
    private val tableRe = Regex("""(?i)\b(?:table|tisch)\s*#?\s*(\d{1,3})\b""")
    private val phoneRe = Regex("""(?i)\b(?:tel|phone|fon|telefon)\.?\s*[:.]?\s*([+\d][\d /().-]{5,})""")
    private val pageRe = Regex("""(?i)\b(?:page|seite|blatt|pg\.?|s\.|página|pagina|pag\.)\s*(\d{1,3})\s*(?:of|von|/|aus|sur|de|di|van)\s*(\d{1,3})\b""")
    private val pageShortRe = Regex("""^\s*-?\s*(\d{1,2})\s*/\s*(\d{1,2})\s*-?\s*$""")
    private val invoiceWords = Regex("""(?i)\b(invoice|rechnung|bill to|rechnungsempfänger|due date|fällig|iban|ust-?id|vat id|zahlungsziel|facture|factura|fattura|factuur|échéance|vencimiento|scadenza|vervaldatum|n° tva|partita iva|nif|cif)\b""")
    private val receiptWords = Regex("""(?i)\b(thank you|vielen dank|danke|cashier|kasse|table|tisch|receipt|bon|beleg|change|rückgeld|tip|trinkgeld|merci|ticket|caisse|gracias|caja|grazie|cassa|scontrino|bedankt|kassa|bonnetje|obrigado)\b""")

    fun parse(ocr: OcrResult, defaultLocale: Locale = Locale.getDefault()): Extraction {
        val rows = ocr.rows()
        if (rows.isEmpty()) return Extraction()
        val texts = rows.map { it.text }
        val all = texts.joinToString("\n")
        val highlights = mutableListOf<Box>()

        val currency = detectCurrency(all, defaultLocale)
        val monthFirst = currency == "USD" || (currency == null && defaultLocale.country == "US")

        // Date and time: the first readable ones.
        var date: LocalDate? = null
        var time: LocalTime? = null
        var dateRow = -1
        for ((i, row) in rows.withIndex()) {
            if (date == null) {
                parseDate(row.text, monthFirst)?.let { (d, raw) ->
                    date = d; dateRow = i; highlights += row.boxOf(raw)
                }
            }
            if (time == null) {
                timeRe.find(row.text)?.let { m ->
                    parseTime(m)?.let { t -> time = t; highlights += row.boxOf(m.value.trim()) }
                }
            }
        }

        // Merchant: the first short, name-like line near the top; failing that, the page's heading.
        var merchant: String? = null
        var merchantRow = -1
        val headerLimit = if (dateRow > 0) dateRow else minOf(rows.size, 8)
        for (i in 0 until minOf(headerLimit, 8)) {
            val t = rows[i].text.trim()
            val letters = t.count { it.isLetter() }
            if (letters < 3 || letters < t.length / 2) continue
            if (phoneRe.containsMatchIn(t) || invoiceWords.containsMatchIn(t) && t.length < 12) continue
            if (Regex("""(?i)^(www\.|http)""").containsMatchIn(t)) continue
            if (!Heading.plausible(t)) continue
            merchant = prettyName(t)
            merchantRow = i
            highlights.add(0, rows[i].box)
            break
        }
        if (merchant == null) {
            Heading.pick(ocr)?.let { h ->
                merchant = prettyName(h.text.trim())
                highlights.add(0, h.box)
            }
        }
        val address = if (merchantRow >= 0) {
            rows.drop(merchantRow + 1).take(2).map { it.text.trim() }
                .firstOrNull { r -> r.any { it.isDigit() } && r.count { it.isLetter() } >= 4 && !phoneRe.containsMatchIn(r) && parseDate(r, monthFirst) == null }
                ?.let { prettyName(it) }
        } else null
        val phone = texts.firstNotNullOfOrNull { phoneRe.find(it)?.groupValues?.get(1)?.trim() }

        // Amount rows.
        var total: Double? = null
        var totalRow = -1
        var subtotal: Double? = null
        var subtotalRow = -1
        var tax: Double? = null
        var taxLabel: String? = null
        var firstTotalsRow = Int.MAX_VALUE
        for ((i, row) in rows.withIndex()) {
            val t = row.text
            val amount = amountAtEnd.find(t)?.let { parseAmount(it.groupValues[2]) } ?: continue
            when {
                subtotalWords.containsMatchIn(t) -> {
                    subtotal = amount; subtotalRow = i; firstTotalsRow = minOf(firstTotalsRow, i)
                }
                totalWords.containsMatchIn(t) && !notTotalWords.containsMatchIn(t) -> {
                    // The largest labelled total wins; ties go to the first one (card lines repeat it).
                    if (total == null || amount > total!!) {
                        total = amount; totalRow = i
                    }
                    firstTotalsRow = minOf(firstTotalsRow, i)
                }
                taxWords.containsMatchIn(t) && !totalWords.containsMatchIn(t) -> {
                    val m = taxWords.find(t)!!
                    tax = amount
                    taxLabel = buildString {
                        append(
                            when (m.groupValues[1].lowercase()) {
                                "vat" -> "VAT"; "gst" -> "GST"; "hst" -> "HST"
                                "mwst" -> "MwSt"; "ust" -> "USt"; "steuer" -> "Steuer"
                                "tva" -> "TVA"; "iva" -> "IVA"; "btw" -> "BTW"; "moms" -> "Moms"; "mva" -> "MVA"
                                else -> "Tax"
                            }
                        )
                        m.groupValues[2].takeIf { it.isNotBlank() }?.let { append(' ').append(it.replace(" ", "")) }
                    }
                    firstTotalsRow = minOf(firstTotalsRow, i)
                }
            }
        }
        if (totalRow >= 0) {
            val row = rows[totalRow]
            val raw = amountAtEnd.find(row.text)!!.groupValues[2]
            highlights += row.boxOf(raw)
        }

        // Line items: rows above the totals that end in an amount and have a name.
        val items = mutableListOf<LineItem>()
        val itemsStart = maxOf(dateRow, merchantRow) + 1
        val itemsEnd = if (firstTotalsRow == Int.MAX_VALUE) rows.size else firstTotalsRow
        for (i in itemsStart until itemsEnd) {
            val t = rows[i].text
            val m = amountAtEnd.find(t) ?: continue
            val amount = parseAmount(m.groupValues[2]) ?: continue
            var name = t.substring(0, m.range.first).trim().trimEnd('.', ':', '-', '*', ' ')
            if (name.count { it.isLetter() } < 2 || nonItemWords.containsMatchIn(name)) continue
            var qty = 1
            Regex("""^(\d{1,2})\s*[xX×]\s+""").find(name)?.let { q -> qty = q.groupValues[1].toInt(); name = name.substring(q.range.last + 1) }
            Regex("""\s+(\d{1,2})\s*[xX×]\s*[\d.,]+$""").find(name)?.let { q -> qty = q.groupValues[1].toInt(); name = name.substring(0, q.range.first) }
            items += LineItem(prettyItem(name), amount, qty)
        }

        val card = texts.firstNotNullOfOrNull { cardRe.find(it) }
        val auth = texts.firstNotNullOfOrNull { authRe.find(it)?.groupValues?.get(1) }
        val order = texts.firstNotNullOfOrNull { t -> orderRe.find(t)?.groupValues?.get(1)?.takeIf { v -> v.any { it.isDigit() } } }
        val invoice = texts.firstNotNullOfOrNull { t -> invoiceRe.find(t)?.groupValues?.get(1)?.takeIf { v -> v.any { it.isDigit() } } }
        val table = texts.firstNotNullOfOrNull { tableRe.find(it)?.groupValues?.get(1) }
        val page = texts.firstNotNullOfOrNull { pageRe.find(it) } ?: texts.takeLast(3).plus(texts.take(2)).firstNotNullOfOrNull { pageShortRe.find(it) }

        val kind = when {
            invoiceWords.findAll(all).count() >= 2 || invoice != null -> DocKind.INVOICE
            total != null && (items.isNotEmpty() || receiptWords.containsMatchIn(all) || card != null) -> DocKind.RECEIPT
            total != null -> DocKind.RECEIPT
            all.length > 200 -> DocKind.LETTER
            else -> DocKind.OTHER
        }

        return Extraction(
            kind = kind,
            merchant = merchant,
            address = address,
            phone = phone,
            date = date,
            time = time,
            orderNumber = order,
            table = table,
            invoiceNumber = invoice,
            items = items,
            subtotal = subtotal,
            taxLabel = taxLabel,
            tax = tax,
            total = total,
            currency = currency,
            cardBrand = card?.groupValues?.get(1)?.let { brand(it) },
            cardLast4 = card?.groupValues?.get(2),
            authCode = auth,
            pageNumber = page?.groupValues?.get(1)?.toIntOrNull(),
            pageCount = page?.groupValues?.get(2)?.toIntOrNull(),
            highlights = highlights.filter { b -> b.width <= ocr.width * 0.9f && b.height <= ocr.height * 0.1f && b.width * b.height <= ocr.width * ocr.height * 0.12f },
        )
    }

    fun detectCurrency(text: String, locale: Locale): String? = when {
        text.contains('€') || Regex("""\bEUR\b""").containsMatchIn(text) -> "EUR"
        text.contains('£') || Regex("""\bGBP\b""").containsMatchIn(text) -> "GBP"
        Regex("""\bCHF\b""").containsMatchIn(text) -> "CHF"
        text.contains('$') || Regex("""\bUSD\b""").containsMatchIn(text) -> "USD"
        Regex("""(?i)\b(mwst|ust|summe|gesamt|zwischensumme|tva|ttc|iva|btw|totale|importe|totaal)\b""").containsMatchIn(text) -> "EUR"
        Regex("""(?i)\b(tax|tip)\b""").containsMatchIn(text) && locale.country in setOf("US", "") -> "USD"
        else -> null
    }

    /** "1.234,56", "1,234.56", "18.90", "18,90" → number. */
    fun parseAmount(raw: String): Double? {
        val s = raw.replace(" ", "").replace("'", "")
        val neg = s.startsWith("-")
        val body = s.removePrefix("-")
        val lastSep = body.indexOfLast { it == '.' || it == ',' }
        if (lastSep < 0) return body.toDoubleOrNull()
        val int = body.substring(0, lastSep).replace(".", "").replace(",", "")
        val frac = body.substring(lastSep + 1)
        val v = "$int.$frac".toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    /** The first date in [text] and the exact text it was read from. */
    fun parseDate(text: String, monthFirst: Boolean): Pair<LocalDate, String>? {
        numericDate.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val sep = m.groupValues[2]
            val b = m.groupValues[3].toInt()
            var y = m.groupValues[4].toInt()
            val d: LocalDate? = if (m.groupValues[1].length == 4) {
                safeDate(a, b, y) // yyyy-mm-dd
            } else {
                if (y < 100) y += 2000
                when {
                    a > 12 -> safeDate(y, b, a)
                    b > 12 -> safeDate(y, a, b)
                    sep == "." -> safeDate(y, b, a)
                    monthFirst -> safeDate(y, a, b)
                    else -> safeDate(y, b, a)
                }
            }
            if (d != null && d.year in 1970..2100) return d to m.value
        }
        textDate1.find(text)?.let { m ->
            val month = monthNames[m.groupValues[2].lowercase()]
            if (month != null) safeDate(m.groupValues[3].toInt(), month.value, m.groupValues[1].toInt())?.let { return it to m.value }
        }
        textDate2.find(text)?.let { m ->
            val month = monthNames[m.groupValues[1].lowercase()]
            if (month != null) safeDate(m.groupValues[3].toInt(), month.value, m.groupValues[2].toInt())?.let { return it to m.value }
        }
        return null
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (_: Exception) { null }

    private fun parseTime(m: MatchResult): LocalTime? {
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val ampm = m.groupValues[3].lowercase().replace(".", "")
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        return try { LocalTime.of(h, min) } catch (_: Exception) { null }
    }

    private fun brand(raw: String): String = when (raw.lowercase().replace(" ", "")) {
        "visa" -> "Visa"
        "mastercard" -> "Mastercard"
        "amex", "americanexpress" -> "Amex"
        "maestro" -> "Maestro"
        "girocard", "ec-karte", "eckarte", "ec karte" -> "Girocard"
        "discover" -> "Discover"
        else -> raw.replaceFirstChar { it.uppercase() }
    }

    /** "NORDLIT COFFEE" → "Nordlit Coffee"; mixed case is kept. */
    fun prettyName(raw: String): String {
        val t = raw.trim().replace(Regex("""\s{2,}"""), " ")
        val letters = t.filter { it.isLetter() }
        if (letters.isEmpty() || letters.any { it.isLowerCase() }) return t
        return t.lowercase().split(' ').joinToString(" ") { w ->
            if (w.length <= 3 && w in setOf("st", "rd", "ave", "and", "of", "the", "und", "der", "die", "das")) w.replaceFirstChar { it.uppercase() }
            else w.replaceFirstChar { it.uppercase() }
        }.replace(Regex("""\b(Gmbh|Ag|Kg|Llc|Inc|Ltd)\b""")) { mr ->
            when (mr.value) { "Gmbh" -> "GmbH"; "Ag" -> "AG"; "Kg" -> "KG"; "Llc" -> "LLC"; "Inc" -> "Inc"; else -> "Ltd" }
        }
    }

    private fun prettyItem(raw: String): String {
        val t = prettyName(raw)
        // Receipts print items in capitals; sentence case reads better than title case.
        return if (raw.filter { it.isLetter() }.all { it.isUpperCase() }) t.lowercase().replaceFirstChar { it.uppercase() } else t
    }
}
