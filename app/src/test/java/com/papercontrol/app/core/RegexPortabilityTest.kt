package com.papercontrol.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Desktop Java accepts regular expressions that Android's engine (ICU) rejects, and a bad one in
 * an object's setup crashes the screen that first touches it. The tests run on desktop Java, so
 * they cannot catch that by running; this reads the source and flags the usual culprit: a
 * literal "{" or "}" that is not a quantifier or a \p{..} property.
 */
class RegexPortabilityTest {
    private fun sources(): List<File> = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    /** Raw-string patterns: Regex("""...""") and Regex(""" ... """) literals. */
    private val raw = Regex("Regex\\(\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)

    private fun looseBraces(pattern: String): List<String> {
        var p = pattern
        p = p.replace(Regex("\\\\Q.*?\\\\E"), "")            // quoted text
        p = p.replace(Regex("\\\\[pP]\\{[A-Za-z_=]+\\}"), "")  // \p{L}
        p = p.replace(Regex("\\\\[{}]"), "")                  // escaped braces
        p = p.replace(Regex("\\{\\d+(,\\d*)?\\}"), "")        // quantifiers {2} {1,3} {2,}
        return Regex("[{}]").findAll(p).map { p.substring(maxOf(0, it.range.first - 12), minOf(p.length, it.range.last + 12)) }.toList()
    }

    @Test
    fun noRegexHasALooseBrace() {
        val bad = mutableListOf<String>()
        for (f in sources()) {
            val text = f.readText()
            for (m in raw.findAll(text)) {
                val pat = m.groupValues[1]
                // Kotlin templates inside raw strings are expanded, not part of the pattern.
                if (pat.contains("\${")) continue
                looseBraces(pat).forEach { bad += "${f.name}: …$it…" }
            }
        }
        assertTrue("loose braces in regular expressions (Android rejects these): $bad", bad.isEmpty())
    }

    @Test
    fun theCheckSeesTheBugItIsFor() {
        assertTrue(looseBraces("""\{([a-z_]+)}""").isNotEmpty())
        assertTrue(looseBraces("""\{([a-z_]+)\}""").isEmpty())
        assertTrue(looseBraces("""\d{1,3}[a-z]{2,}\p{L}+""").isEmpty())
    }

    @Test
    fun templatesWorkWithTheirPlaceholders() {
        val v = Templates.Values(title = "T", correspondent = "C")
        assertTrue(Templates.apply("{correspondent} - {title}", v) == "C - T")
    }
}
