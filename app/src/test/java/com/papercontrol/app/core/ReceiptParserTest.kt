package com.papercontrol.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Builds OCR rows the way a recognizer reports a receipt: label and price as separate lines. */
fun ocrOf(vararg rows: String, width: Int = 600): OcrResult {
    val lines = mutableListOf<OcrLine>()
    rows.forEachIndexed { i, row ->
        val y = 20f + i * 30f
        val parts = row.split("|")
        parts.forEachIndexed { j, p ->
            if (p.isBlank()) return@forEachIndexed
            val x = if (parts.size == 1) 40f else if (j == 0) 40f else width - 40f - p.length * 12f
            val words = mutableListOf<OcrLine>()
            var wx = x
            for (w in p.trim().split(' ')) {
                words += OcrLine(w, Box(wx, y, wx + w.length * 12f, y + 20f))
                wx += (w.length + 1) * 12f
            }
            lines += OcrLine(p.trim(), Box(x, y, x + p.trim().length * 12f, y + 20f), words)
        }
    }
    return OcrResult(lines, width, 40 + rows.size * 30)
}

class ReceiptParserTest {

    private val nordlit = ocrOf(
        "NORDLIT COFFEE",
        "214 Harbor St, Portland",
        "Tel 503 555 0142",
        "11/18/2026|09:42",
        "Order #2231|Table 6",
        "Flat white|4.50",
        "Oat cappuccino|5.25",
        "Almond croissant|4.75",
        "Sparkling water|3.00",
        "Subtotal|17.50",
        "Tax 8%|1.40",
        "TOTAL|18.90",
        "VISA **** 4821|18.90",
        "Auth 048812",
        "Thank you!",
        "2231 0048 8121 18",
    )

    @Test
    fun readsTheReceiptFromTheVideo() {
        val e = ReceiptParser.parse(nordlit, Locale.US)
        assertEquals(DocKind.RECEIPT, e.kind)
        assertEquals("Nordlit Coffee", e.merchant)
        assertEquals("214 Harbor St, Portland", e.address)
        assertEquals(LocalDate.of(2026, 11, 18), e.date)
        assertEquals(LocalTime.of(9, 42), e.time)
        assertEquals("2231", e.orderNumber)
        assertEquals("6", e.table)
        assertEquals(listOf("Flat white", "Oat cappuccino", "Almond croissant", "Sparkling water"), e.items.map { it.name })
        assertEquals(listOf(4.50, 5.25, 4.75, 3.00), e.items.map { it.amount })
        assertEquals(17.50, e.subtotal!!, 0.001)
        assertEquals(1.40, e.tax!!, 0.001)
        assertEquals("Tax 8%", e.taxLabel)
        assertEquals(18.90, e.total!!, 0.001)
        assertEquals("Visa", e.cardBrand)
        assertEquals("4821", e.cardLast4)
        assertEquals("048812", e.authCode)
        // Merchant, date, time and total are highlighted.
        assertEquals(4, e.highlights.size)
    }

    @Test
    fun readsAGermanReceipt() {
        val e = ReceiptParser.parse(
            ocrOf(
                "BÄCKEREI MÜLLER GMBH",
                "Hauptstr. 12, 10115 Berlin",
                "Datum: 03.02.2025|14:05",
                "2 x Brötchen|1,20",
                "Kaffee groß|3,10",
                "Zwischensumme|4,30",
                "MwSt 7%|0,30",
                "SUMME EUR|4,30",
                "girocard ****1234",
            ),
            Locale.GERMANY,
        )
        assertEquals("Bäckerei Müller GmbH", e.merchant)
        assertEquals(LocalDate.of(2025, 2, 3), e.date)
        assertEquals("EUR", e.currency)
        assertEquals(4.30, e.total!!, 0.001)
        assertEquals("MwSt 7%", e.taxLabel)
        assertEquals(2, e.items.first().quantity)
        assertEquals("Brötchen", e.items.first().name)
        assertEquals("Girocard", e.cardBrand)
    }

    @Test
    fun readsInvoiceNumbersAndPageMarkers() {
        val e = ReceiptParser.parse(
            ocrOf(
                "ACME Software GmbH",
                "Rechnung Nr. RE-2024-118",
                "Rechnungsdatum 12.04.2024",
                "Bitte zahlen Sie bis zum Fälligkeitsdatum. IBAN DE00 1234",
                "Seite 1 von 2",
            ),
        )
        assertEquals(DocKind.INVOICE, e.kind)
        assertEquals("RE-2024-118", e.invoiceNumber)
        assertEquals(1, e.pageNumber)
        assertEquals(2, e.pageCount)
        assertNull(e.total)
    }

    @Test
    fun amounts() {
        assertEquals(1234.56, ReceiptParser.parseAmount("1.234,56")!!, 1e-9)
        assertEquals(1234.56, ReceiptParser.parseAmount("1,234.56")!!, 1e-9)
        assertEquals(18.9, ReceiptParser.parseAmount("18.90")!!, 1e-9)
        assertEquals(-3.5, ReceiptParser.parseAmount("-3,50")!!, 1e-9)
    }

    @Test
    fun dates() {
        assertEquals(LocalDate.of(2026, 11, 18), ReceiptParser.parseDate("Nov 18, 2026", false)!!.first)
        assertEquals(LocalDate.of(2026, 3, 4), ReceiptParser.parseDate("4. März 2026", false)!!.first)
        assertEquals(LocalDate.of(2026, 4, 3), ReceiptParser.parseDate("04/03/2026", true)!!.first)
        assertEquals(LocalDate.of(2026, 3, 4), ReceiptParser.parseDate("04/03/2026", false)!!.first)
        assertEquals(LocalDate.of(2024, 1, 31), ReceiptParser.parseDate("2024-01-31", false)!!.first)
        assertNotNull(ReceiptParser.parseDate("31.12.24", false))
    }
}
