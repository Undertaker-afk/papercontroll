package com.papercontrol.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class BarcodesTest {
    @Test
    fun epcGiroCode() {
        val p = Barcodes.epc("BCD\n002\n1\nSCT\nBFSWDE33BER\nWikimedia Foerdergesellschaft\nDE33100205000001194700\nEUR123.45\n\n\nSpende fuer Wikipedia")!!
        assertEquals("DE33100205000001194700", p.iban)
        assertEquals(123.45, p.amount!!, 1e-9)
        assertEquals("EUR", p.currency)
        assertEquals("Wikimedia Foerdergesellschaft", p.name)
        assertEquals("Spende fuer Wikipedia", p.message)
        assertEquals("DE33 1002 0500 0001 1947 00", p.ibanPretty)
    }

    @Test
    fun swissQrBill() {
        val lines = MutableList(31) { "" }
        lines[0] = "SPC"; lines[1] = "0200"; lines[2] = "1"; lines[3] = "CH4431999123000889012"; lines[4] = "S"
        lines[5] = "Robert Schneider AG"; lines[18] = "1949.75"; lines[19] = "CHF"; lines[27] = "QRR"; lines[28] = "210000000003139471430009017"
        val p = Barcodes.swiss(lines.joinToString("\n"))!!
        assertEquals("CHF", p.currency)
        assertEquals(1949.75, p.amount!!, 1e-9)
        assertEquals("210000000003139471430009017", p.reference)
    }

    @Test
    fun asnAndSeparator() {
        assertEquals(PageCodes(asn = 123), Barcodes.interpret(listOf("ASN00123")))
        assertEquals(42, Barcodes.interpret(listOf("asn-42")).asn)
        assertTrue(Barcodes.interpret(listOf("PATCHT")).separator)
        assertFalse(Barcodes.validIban("DE33100205000001194701"))
    }
}

class DeadlinesTest {
    private val invoiceDate = LocalDate.of(2026, 3, 2)

    @Test
    fun paymentDueDates() {
        assertEquals(LocalDate.of(2026, 3, 16), Deadlines.find("Zahlbar bis zum 16.03.2026 ohne Abzug.", invoiceDate).single().date)
        assertEquals(LocalDate.of(2026, 3, 16), Deadlines.find("Bitte zahlen Sie innerhalb von 14 Tagen.", invoiceDate).single().date)
        assertEquals(LocalDate.of(2026, 4, 1), Deadlines.find("Payment terms: net 30 days", invoiceDate).single().date)
        assertEquals(LocalDate.of(2026, 3, 31), Deadlines.find("Due date: 2026-03-31", invoiceDate).single().date)
        assertEquals(LocalDate.of(2026, 3, 20), Deadlines.find("Date d'échéance : 20/03/2026", invoiceDate).single().date)
    }

    @Test
    fun warrantyAndCancellation() {
        val w = Deadlines.find("Waschmaschine XL. 2 Jahre Garantie ab Kaufdatum.", invoiceDate).single()
        assertEquals(DeadlineKind.WARRANTY, w.kind)
        assertEquals(LocalDate.of(2028, 3, 2), w.date)
        val c = Deadlines.find("Kündigung bis 30.09.2026 möglich, Vertragsende 31.12.2026", invoiceDate)
        assertEquals(LocalDate.of(2026, 9, 30), c.first { it.kind == DeadlineKind.CANCEL }.date)
        assertEquals(LocalDate.of(2026, 12, 31), c.first { it.kind == DeadlineKind.EXPIRY }.date)
        assertTrue(Deadlines.find("Nordlit Coffee. Thank you!", invoiceDate).isEmpty())
    }
}

class TemplatesTest {
    private val v = Templates.Values(title = "Nordlit Coffee", correspondent = "Nordlit", documentType = "Receipt", created = LocalDate.of(2026, 11, 18), amount = 18.9)

    @Test
    fun fillsAndDropsMissing() {
        assertEquals("Nordlit · 2026-11", Templates.apply("{correspondent} · {created_year}-{created_month}", v))
        assertEquals("Receipt/2026/Nordlit", Templates.apply("{document_type}/{created_year}/{correspondent}", v))
        assertEquals("Receipt/2026", Templates.apply("{document_type}/{created_year}/{asn}", v))
        assertEquals("Nordlit Coffee", Templates.apply("{asn} - {title}", v))
        assertEquals("Nordlit - Nordlit Coffee", Templates.apply("{correspondent} - {asn} - {title}", v))
        assertEquals("18.90", Templates.apply("{amount}", v))
    }
}

class QuerySyntaxTest {
    private val today = LocalDate.of(2026, 11, 23)

    @Test
    fun filters() {
        val q = QuerySyntax.parse("""coffee tag:Receipts -tag:inbox from:"Nordlit Coffee" created:2023..2024 amount:>50 is:due field:"Warranty until=2027"""", today)
        assertEquals("coffee", q.text)
        assertEquals(listOf("Receipts"), q.tags)
        assertEquals(listOf("inbox"), q.notTags)
        assertEquals("Nordlit Coffee", q.correspondent)
        assertEquals(LocalDate.of(2023, 1, 1), q.createdFrom)
        assertEquals(LocalDate.of(2024, 12, 31), q.createdTo)
        assertEquals(50.0, q.amountMin!!, 0.0)
        assertNull(q.amountMax)
        assertTrue("is:due" in q.flags)
        assertEquals(listOf("Warranty until" to "2027"), q.fields)
    }

    @Test
    fun dateForms() {
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31), QuerySyntax.dates("last-month", today))
        assertEquals(LocalDate.of(2024, 2, 1) to LocalDate.of(2024, 2, 29), QuerySyntax.dates("2024-2", today))
        assertEquals(LocalDate.of(2024, 1, 1) to null, QuerySyntax.dates(">2024-01-01", today))
        assertEquals(30, QuerySyntax.parse("added:30d", today).addedWithinDays)
    }
}

class SimilarityTest {
    @Test
    fun duplicates() {
        val grid = IntArray(72) { (it * 37) % 255 }
        val h = Similarity.dHash(grid)
        val noisy = Similarity.dHash(IntArray(72) { grid[it] + if (it == 5) 1 else 0 })
        assertTrue(Similarity.hamming(h, noisy) <= 2)
        val d = LocalDate.of(2026, 11, 18)
        val a = Similarity.Fingerprint(1, null, 18.90, d, "Nordlit Coffee", "x")
        assertNotNull(Similarity.duplicateReason(a, a.copy(id = 2, sender = "NORDLIT COFFEE")))
        assertNull(Similarity.duplicateReason(a, a.copy(id = 2, total = 4.50)))
    }

    @Test
    fun moreLikeThis() {
        val ranked = Similarity.rankByText(
            "Stadtwerke Stromrechnung Abschlag kWh",
            listOf(1L to "Kaffee Croissant Bon", 2L to "Stadtwerke Abschlag Strom kWh Zähler", 3L to "Versicherung Police"),
        )
        assertEquals(2L, ranked.first().first)
    }
}

class StatsTest {
    @Test
    fun monthlyAndCsv() {
        val items = listOf(
            Expense(LocalDate.of(2026, 11, 2), 18.9, "USD", "Receipt", "Nordlit"),
            Expense(LocalDate.of(2026, 11, 20), 4.3, "USD", "Receipt", "Bakery"),
            Expense(LocalDate.of(2026, 9, 1), 84.0, "USD", "Invoice", null),
        )
        val m = Stats.monthly(items, YearMonth.of(2026, 11), 3)
        assertEquals(listOf(84.0, 0.0, 23.2), m.map { Math.round(it.total * 100) / 100.0 })
        assertEquals("Invoice", Stats.by(items, { it.category }).first().first)
        assertEquals("a,\"b,c\",\"say \"\"hi\"\"\"\r\n", Csv.write(listOf("a", "b,c", "say \"hi\""), emptyList()))
    }
}

class LanguagesTest {
    @Test
    fun frenchSpanishItalianDutchReceipts() {
        val fr = ReceiptParser.parse(ocrOf("BOULANGERIE DUPONT", "Le 12/05/2026|08:15", "Croissant|1,20", "Total TTC|3,40", "TVA 5,5%|0,18", "Merci !"), Locale.FRANCE)
        assertEquals(3.40, fr.total!!, 1e-9)
        assertEquals("TVA 5,5%", fr.taxLabel)
        assertEquals(LocalDate.of(2026, 5, 12), fr.date)
        val es = ReceiptParser.parse(ocrOf("CAFETERIA SOL", "3 de marzo de 2026", "Café con leche|1,80", "IVA 10%|0,16", "TOTAL A PAGAR|1,80", "Gracias"), Locale.forLanguageTag("es-ES"))
        assertEquals(1.80, es.total!!, 1e-9)
        assertEquals(LocalDate.of(2026, 3, 3), es.date)
        val it = ReceiptParser.parse(ocrOf("BAR CENTRALE", "Totale|7,50", "Grazie"), Locale.ITALY)
        assertEquals(7.50, it.total!!, 1e-9)
        val nl = ReceiptParser.parse(ocrOf("BAKKERIJ JANSEN", "Totaal|5,95", "BTW 9%|0,49", "Bedankt"), Locale.forLanguageTag("nl-NL"))
        assertEquals(5.95, nl.total!!, 1e-9)
        assertEquals(DocKind.INVOICE, ReceiptParser.parse(ocrOf("ACME SARL", "Facture n° F-2026-77", "Échéance 30/06/2026")).kind)
        assertEquals("F-2026-77", ReceiptParser.parse(ocrOf("ACME SARL", "Facture n° F-2026-77")).invoiceNumber)
    }
}

class OrientationTest {
    private fun line(text: String, angle: Float) = OcrLine(text, Box(0f, 0f, 10f, 10f), emptyList(), angle)

    @Test
    fun uprightPageNeedsNoTurn() {
        assertEquals(0, OcrResult(listOf(line("Nordlit Coffee", 0f), line("Total 18.90", 1.5f)), 100, 100).uprightTurns())
    }

    @Test
    fun textReadingDownwardNeedsThreeClockwiseTurns() {
        // Lines run top to bottom (+90): turning the page a quarter counter-clockwise makes them level.
        assertEquals(3, OcrResult(listOf(line("Ortsverband Darmstadt", 89f), line("Regionalstelle", 91.5f), line("x", 0f)), 100, 100).uprightTurns())
    }

    @Test
    fun textReadingUpwardNeedsOneClockwiseTurn() {
        assertEquals(1, OcrResult(listOf(line("Ortsverband Darmstadt", -90f), line("Regionalstelle Viernheim", -88f)), 100, 100).uprightTurns())
    }

    @Test
    fun upsideDownNeedsAHalfTurn() {
        assertEquals(2, OcrResult(listOf(line("Rechnung Nr 2024", 180f), line("Summe 12,50", -179f)), 100, 100).uprightTurns())
    }

    @Test
    fun longLinesOutvoteStrayOnes() {
        assertEquals(0, OcrResult(listOf(line("A long ordinary line of text", 0f), line("another long line of text here", 1f), line("ab", 90f)), 100, 100).uprightTurns())
    }

    @Test
    fun headingIsTheTallestShortLineNearTheTop() {
        val ocr = OcrResult(
            listOf(
                OcrLine("Stadtwerke München", Box(10f, 10f, 200f, 50f)),
                OcrLine("Ihre Abrechnung für das Jahr 2025 mit allen Details und Hinweisen zur Zahlung", Box(10f, 80f, 400f, 96f)),
                OcrLine("Bitte beachten Sie die Hinweise auf der Rückseite dieses Schreibens genau", Box(10f, 110f, 400f, 126f)),
                OcrLine("Kundennummer 778812", Box(10f, 140f, 200f, 156f)),
            ),
            420, 600,
        )
        assertEquals("Stadtwerke München", Heading.pick(ocr)?.text)
        assertEquals("Stadtwerke München", Heading.title(ocr, DocKind.LETTER, null))
        assertTrue(!Heading.plausible("Pfungstadt, noch mal p Ober-Ramstadt Michelstadt Lampertheim Heppenheim | Groß-Umstadt"))
        assertTrue(Heading.plausible("Nordlit Coffee"))
    }
}
