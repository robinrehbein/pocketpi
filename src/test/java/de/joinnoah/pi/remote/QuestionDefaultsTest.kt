package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class QuestionDefaultsTest {
    @Test
    fun defaultsResolveValuesLabelsCustomTextAndDuplicates() {
        val question =
            Wire.parse(
                """{"id":"q","options":[{"value":"yes","label":"Yes"},{"value":"no","label":"No"}],"defaults":["Yes","yes","No","custom"],"allowOther":true}"""
            )
        val defaults = questionDefaults(question)
        assertEquals(setOf("yes", "no"), defaults.first)
        assertEquals(listOf("custom"), defaults.second)
    }

    @Test
    fun labelValueCollisionUsesFirstMatchingOptionLikeTheTerminal() {
        val question =
            Wire.parse(
                """{"id":"q","options":[{"value":"first","label":"match"},{"value":"match","label":"second"}],"defaults":["match"],"allowOther":true}"""
            )
        assertEquals(setOf("first"), questionDefaults(question).first)
    }
}
