package com.papercontrol.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    private data class M(
        override val match: String,
        override val algorithm: MatchAlgorithm,
        override val caseInsensitive: Boolean = true,
        override val id: Long = 1,
        override val name: String = "x",
    ) : Matchable

    private val doc = "Stadtwerke München – Ihre Stromrechnung für März. Kundennummer 778812."

    @Test
    fun ruleAlgorithms() {
        // Whole words only, like Paperless-ngx: "strom" does not match "Stromrechnung".
        assertFalse(Matcher.matches(M("strom gas", MatchAlgorithm.ANY), doc))
        assertTrue(Matcher.matches(M("stromrechnung gas", MatchAlgorithm.ANY), doc))
        assertFalse(Matcher.matches(M("stromrechnung gas", MatchAlgorithm.ALL), doc))
        assertTrue(Matcher.matches(M("stadtwerke munchen", MatchAlgorithm.ALL), doc))
        assertTrue(Matcher.matches(M("\"Stadtwerke München\"", MatchAlgorithm.ANY), doc))
        assertTrue(Matcher.matches(M("Stadtwerke München", MatchAlgorithm.LITERAL), doc))
        assertFalse(Matcher.matches(M("stadtwerk", MatchAlgorithm.LITERAL), doc))
        assertTrue(Matcher.matches(M("Kundennummer \\d{6}", MatchAlgorithm.REGEX), doc))
        assertTrue(Matcher.matches(M("Stadwerke", MatchAlgorithm.FUZZY), doc))
        assertFalse(Matcher.matches(M("Telekom", MatchAlgorithm.FUZZY), doc))
        assertFalse(Matcher.matches(M("strom", MatchAlgorithm.NONE), doc))
        assertFalse(Matcher.matches(M("STROM", MatchAlgorithm.ANY, caseInsensitive = false), doc))
    }

    @Test
    fun classifierLearnsFromSortedDocuments() {
        val examples = listOf(
            "Stromrechnung Stadtwerke Verbrauch kWh Abschlag" to 1L,
            "Stadtwerke Abschlag Strom Zählerstand kWh" to 1L,
            "Gasrechnung Stadtwerke Verbrauch Abschlag" to 1L,
            "Kaffee Croissant Bon Danke Barzahlung" to 2L,
            "Coffee croissant receipt thank you visa" to 2L,
            "Espresso Kuchen Bon Kasse Danke" to 2L,
        )
        val c = Classifier.train(examples)
        assertEquals(1L, c.predict("Ihre Abschlag Rechnung Strom kWh Stadtwerke"))
        assertEquals(2L, c.predict("Croissant und Kaffee, danke für Ihren Besuch"))
        assertNull(Classifier.train(examples.filter { it.second == 1L }).predict("anything"))
    }

    @Test
    fun fuzzyRatio() {
        assertEquals(100, Text.partialRatio("nordlit", "nordlit coffee"))
        assertTrue(Text.partialRatio("nordlt cofee", "nordlit coffee portland") >= 85)
    }
}
