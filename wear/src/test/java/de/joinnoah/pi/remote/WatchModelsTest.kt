package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WatchModelsTest {
    private fun ready(status: String = "idle") = RemoteState(
        connected = true, status = status, draft = "Hello", session = Wire.objectOf("id" to "s", "origin" to "rpc"),
        capabilities = setOf(FOLLOW_UP_CAPABILITY, STEER_CAPABILITY),
    )
    @Test fun `idle sends but does not queue or steer`() {
        assertEquals(WatchSendActions(true, false, false, false), watchSendActions(ready()))
    }
    @Test fun `running session offers only supported busy actions`() {
        assertEquals(WatchSendActions(false, true, true, false), watchSendActions(ready("running")))
        assertFalse(watchSendActions(ready("running").copy(capabilities = emptySet())).followUp)
    }
    @Test fun `waiting offline sending and uncertain states cannot send`() {
        val base = ready()
        listOf(base.copy(status = "waiting"), base.copy(connected = false), base.copy(sending = true),
            base.copy(uncertain = true), base.copy(loading = true), base.copy(configurationChanging = true),
            base.copy(questions = listOf(Wire.objectOf("id" to "q"))), base.copy(draft = " ")).forEach {
            assertEquals(WatchSendActions(false, false, false, false), watchSendActions(it))
        }
    }
    @Test fun `commands and full journal cannot be queued`() {
        assertFalse(watchSendActions(ready("running").copy(draft = " /command")).followUp)
        assertFalse(watchSendActions(ready("running").copy(followUps = List(64) { PendingFollowUp("$it", "text") })).steer)
    }
    @Test fun `blocks preserve exact text including surrogate pairs`() {
        val text = "hello\n" + "🙂".repeat(200) + "\nend"
        val blocks = watchTextBlocks(text, 7)
        assertEquals(text, blocks.joinToString(""))
        assertTrue(blocks.all { it.length <= 7 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
    @Test fun `newline heavy text also has bounded block height`() {
        val text = "\n".repeat(101)
        val blocks = watchTextBlocks(text)
        assertEquals(text, blocks.joinToString(""))
        assertTrue(blocks.all { block -> block.count { it == '\n' } <= 3 })
    }
    @Test fun `editor limit never breaks an emoji surrogate pair`() {
        assertEquals("a", watchInputText("a🙂b", 2))
        assertEquals("a🙂", watchInputText("a🙂b", 3))
    }
    @Test fun `paging is bounded and clamps stale page positions`() {
        val values = (0 until 23).toList()
        assertEquals(3, watchPageCount(values.size))
        assertEquals((20 until 23).toList(), watchPage(values, 400))
        assertEquals((0 until 10).toList(), watchPage(values, -1))
        assertEquals(emptyList<Int>(), watchPage(emptyList<Int>(), 12))
    }
    @Test fun `file blocks retain source line numbers for wrapped lines`() {
        val blocks = watchFileBlocks("a".repeat(250) + "\nsecond\n")
        assertEquals(listOf(1, 1, 1, 2), blocks.map { it.line })
        assertEquals("a".repeat(250), blocks.filter { it.line == 1 }.joinToString("") { it.text })
    }
    private fun question(multi: Boolean = false, required: Boolean = true, defaults: List<String> = emptyList()) = Wire.objectOf(
        "id" to "q", "prompt" to "Pick", "multiSelect" to multi, "required" to required, "allowOther" to true,
        "defaults" to JsonArray(defaults.map(::JsonPrimitive)),
        "options" to JsonArray(listOf(Wire.objectOf("value" to "a", "label" to "Alpha"), Wire.objectOf("value" to "b", "label" to "Beta"))),
    )
    @Test fun `defaults and custom defaults survive questionnaire navigation`() {
        val q = question(true, defaults = listOf("Alpha", "Custom"))
        val answers = WatchQuestionAnswers.defaults(listOf(q))
        assertEquals(setOf("a"), answers.selected["q"])
        assertEquals(listOf("Custom"), answers.custom["q"])
        val wire = answers.wire(listOf(q)).array("answers").single().array("selections")
        assertEquals(listOf("a", "Custom"), wire.map { it.text("value") })
        assertEquals(1, wire.first().getValue("index").jsonPrimitive.int)
        assertTrue(wire.last().flag("wasCustom"))
    }
    @Test fun `single choice replaces custom while multi choice toggles`() {
        val q = question(defaults = listOf("Custom"))
        val single = WatchQuestionAnswers.defaults(listOf(q)).choose(q, "b")
        assertEquals(setOf("b"), single.selected["q"])
        assertTrue(single.custom["q"].isNullOrEmpty())
        val multi = question(true)
        val toggled = WatchQuestionAnswers.defaults(listOf(multi)).choose(multi, "a").choose(multi, "b").choose(multi, "a")
        assertEquals(setOf("b"), toggled.selected["q"])
    }
    @Test fun `typing another multi choice answer preserves existing custom defaults`() {
        val q = question(true, defaults = listOf("Alpha", "Custom"))
        val answers = WatchQuestionAnswers.defaults(listOf(q)).editOther(q, "New", "").editOther(q, "Newer", "New")
        assertEquals(setOf("a"), answers.selected["q"])
        assertEquals(listOf("Custom", "Newer"), answers.custom["q"])
    }
    @Test fun `required question blocks submission while optional skip has empty selections`() {
        val q = question()
        val answers = WatchQuestionAnswers.defaults(listOf(q))
        assertFalse(answers.valid(listOf(q)))
        assertTrue(runCatching { answers.wire(listOf(q)) }.isFailure)
        val optional = question(required = false)
        val skipped = answers.skip("q").wire(listOf(optional)).array("answers").single()
        assertTrue(skipped.flag("skipped"))
        assertTrue(skipped.array("selections").isEmpty())
    }
    @Test fun `watch answers encode exactly like the phone form, custom defaults included`() {
        val questions = listOf(
            question(true, defaults = listOf("Beta", "Custom")),
            JsonObject(question(required = false, defaults = listOf("Own words")) + ("id" to JsonPrimitive("r"))),
        )
        val watch = WatchQuestionAnswers.defaults(questions)
        val phoneDefaults = QuestionnaireAnswers.defaults(questions)
        // The phone keeps custom defaults apart from typed text; untouched, both send the same.
        val phone = QuestionnaireAnswers.of(phoneDefaults.selected, phoneDefaults.custom, emptyMap())
        assertEquals(phone.wire(questions), watch.wire(questions))
        val selections = watch.wire(questions).array("answers").map { answer -> answer.array("selections").map { it.text("value") } }
        assertEquals(listOf(listOf("b", "Custom"), listOf("Own words")), selections)
        // On a multiple choice a typed answer on either device follows the custom defaults.
        assertEquals(
            QuestionnaireAnswers.of(phoneDefaults.selected, phoneDefaults.custom, mapOf("q" to "Typed")).wire(questions),
            watch.editOther(questions[0], "Typed", "").wire(questions),
        )
        // On a single choice it replaces them, on both devices (QuestionnaireAnswers.other).
        val single = watch.editOther(questions[1], "More", "").wire(questions)
        assertEquals(phoneDefaults.other(questions[1], "More").wire(questions), single)
        assertEquals(listOf("More"), single.array("answers")[1].array("selections").map { it.text("value") })
    }
}
