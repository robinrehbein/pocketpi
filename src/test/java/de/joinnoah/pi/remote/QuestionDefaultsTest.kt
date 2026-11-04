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

    private val questions =
        listOf(
            Wire.parse(
                """{"id":"stack","prompt":"Stack?","multiSelect":true,"required":true,"allowOther":true,"options":[{"value":"kt","label":"Kotlin"},{"value":"ts","label":"TypeScript"}],"defaults":["TypeScript","Rust"]}"""
            ),
            Wire.parse(
                """{"id":"name","prompt":"Name?","multiSelect":false,"required":false,"allowOther":true,"options":[{"value":"a","label":"Alpha"}],"defaults":["Custom name"]}"""
            ),
            Wire.parse("""{"id":"skip","prompt":"Skip?","multiSelect":false,"required":false,"allowOther":false,"options":[{"value":"x","label":"X"}],"defaults":[]}"""),
        )

    @Test
    fun defaultsEncodeOptionsWithIndexThenCustomDefaults() {
        val wire = QuestionnaireAnswers.defaults(questions).wire(questions)
        assertEquals(
            Wire.parse(
                """{"kind":"questionnaire","cancelled":false,"answers":[
                  {"id":"stack","skipped":false,"selections":[
                    {"value":"ts","label":"TypeScript","index":2,"wasCustom":false},
                    {"value":"Rust","label":"Rust","wasCustom":true}]},
                  {"id":"name","skipped":false,"selections":[{"value":"Custom name","label":"Custom name","wasCustom":true}]},
                  {"id":"skip","skipped":true,"selections":[]}]}"""
            ),
            wire,
        )
    }

    @Test
    fun phoneFormStateGivesTheSharedEncoding() {
        val defaults = QuestionnaireAnswers.defaults(questions)
        // The phone keeps custom defaults and typed text apart; untouched, it sends the defaults.
        val untouched = QuestionnaireAnswers.of(defaults.selected, defaults.custom, emptyMap())
        assertEquals(defaults.wire(questions), untouched.wire(questions))
        // Typed text follows the custom defaults; a blank or repeated answer is not sent twice.
        val typed = QuestionnaireAnswers.of(defaults.selected, defaults.custom, mapOf("stack" to "Go", "name" to " "))
        val answers = typed.wire(questions).array("answers")
        assertEquals(listOf("ts", "Rust", "Go"), answers[0].array("selections").map { it.text("value") })
        assertEquals(listOf("Custom name"), answers[1].array("selections").map { it.text("value") })
        assertTrue(answers[2].flag("skipped"))
        val repeated = QuestionnaireAnswers.of(defaults.selected, defaults.custom, mapOf("stack" to "Rust"))
        assertEquals(listOf("ts", "Rust"), repeated.wire(questions).array("answers")[0].array("selections").map { it.text("value") })
    }

    @Test
    fun requiredQuestionNeedsAnOptionOrOwnAnswer() {
        val empty = QuestionnaireAnswers()
        assertFalse(empty.valid(questions))
        assertTrue(runCatching { empty.wire(questions) }.isFailure)
        assertTrue(QuestionnaireAnswers(custom = mapOf("stack" to listOf("Zig"))).valid(questions))
        assertFalse(QuestionnaireAnswers(custom = mapOf("stack" to listOf(" "))).valid(questions))
    }
}
