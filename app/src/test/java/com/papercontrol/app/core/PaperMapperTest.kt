package com.papercontrol.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperMapperTest {
    private val t0 = 1_700_000_000_000L

    private fun page(key: Int, minute: Int, vararg rows: String) =
        PageInput(key, ocrOf(*rows), capturedAt = t0 + minute * 60_000L)

    @Test
    fun groupsAMultiPageInvoiceAndSeparatesAReceipt() {
        val pages = listOf(
            // Shot in a mixed order: page 2 of the invoice first.
            page(
                10, 0,
                "Positionen Fortsetzung",
                "Wartung Server Q2|1.200,00",
                "Lizenz Office|480,00",
                "Rechnungsnummer RE-2024-118",
                "Seite 2 von 2",
            ),
            page(
                11, 1,
                "NORDLIT COFFEE",
                "214 Harbor St, Portland",
                "11/18/2026|09:42",
                "Flat white|4.50",
                "TOTAL|18.90",
                "Thank you!",
            ),
            page(
                12, 2,
                "ACME Software GmbH",
                "Rechnung",
                "Rechnungsnummer RE-2024-118",
                "Kundennummer 778812",
                "Datum 12.04.2024",
                "Seite 1 von 2",
            ),
        )
        val groups = PaperMapper.group(pages)
        assertEquals(2, groups.size)
        val invoice = groups.first { 10 in it.keys }
        assertEquals(listOf(12, 10), invoice.keys)
        assertTrue(invoice.reasons.any { it.contains("RE-2024-118") })
        assertEquals(listOf(11), groups.first { 11 in it.keys }.keys)
    }

    @Test
    fun joinsOverlappingPhotosOfALongReceipt() {
        val top = page(
            1, 0,
            "NORDLIT COFFEE",
            "214 Harbor St, Portland",
            "11/18/2026|09:42",
            "Flat white|4.50",
            "Oat cappuccino|5.25",
            "Almond croissant|4.75",
            "Sparkling water|3.00",
        )
        val bottom = page(
            2, 0,
            "Almond croissant|4.75",
            "Sparkling water|3.00",
            "Subtotal|17.50",
            "Tax 8%|1.40",
            "TOTAL|18.90",
            "VISA **** 4821|18.90",
            "Thank you!",
        )
        val other = page(
            3, 30,
            "Stadtwerke München",
            "Stromrechnung März 2026",
            "Kundennummer 778812",
            "Abschlag|84,00",
        )
        val groups = PaperMapper.group(listOf(bottom.copy(capturedAt = t0 + 20_000), top, other))
        assertEquals(2, groups.size)
        assertEquals(listOf(1, 2), groups.first { 1 in it.keys }.keys)
    }

    @Test
    fun keepsTwoReceiptsFromTheSameShopApart() {
        val a = page(1, 0, "NORDLIT COFFEE", "11/18/2026|09:42", "Flat white|4.50", "TOTAL|4.50")
        val b = page(2, 1, "NORDLIT COFFEE", "11/19/2026|08:15", "Oat cappuccino|5.25", "TOTAL|5.25")
        assertEquals(2, PaperMapper.group(listOf(a, b)).size)
    }
}
