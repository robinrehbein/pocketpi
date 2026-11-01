package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the same bounded outer viewport and unbounded scroll child as QuestionComposer. */
class PagedEditorLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun invokeRetainedClick(action: () -> Boolean) {
        try {
            action()
        } catch (error: IllegalStateException) {
            assertEquals("Cannot read CompositionLocal because the Modifier node is not currently attached.", error.message)
            assertTrue(error.stackTrace.any { it.className == "androidx.compose.foundation.AbstractClickableNode" && it.methodName == "playClickSound" })
        }
    }

    @Test fun megabytePagedEditorBoundsWindowsAndSubmitsTheExactValue() {
        val value = "A".repeat(2_048) + "😀".repeat(261_120) + "Z".repeat(2_048)
        val question = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        var submitted: String? = null
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    QuestionCard(question, enabled = true, pagedEditor = PagedEditorView(text = value),
                        onSubmitPagedEditor = { submitted = it }) { }
                }
            }
        }
        val field = compose.onNodeWithTag("questionEditorField").performScrollTo().assertIsDisplayed()
        val rendered = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        assertNotNull(rendered)
        assertTrue("Visible window must be bounded", requireNotNull(rendered).length <= 2_048)
        assertTrue(rendered.startsWith("AAAA"))
        compose.onNodeWithTag("pagedEditorLast").performScrollTo().assertIsEnabled().performClick()
        val last = requireNotNull(compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
        assertTrue(last.length <= 2_048 && last.endsWith("ZZZZ"))
        compose.onNodeWithTag("pagedEditorSection").performScrollTo().performTextReplacement("3")
        compose.onNodeWithTag("pagedEditorJump").performClick()
        val middle = requireNotNull(compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
        assertTrue(middle.length <= 2_048 && middle.startsWith("😀"))
        compose.onNodeWithTag("pagedEditorFirst").performClick()
        val send = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.remote_send)
        compose.onNodeWithText(send).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(value, submitted) }
    }

    @Test fun largePasteIsKeptExactlyAndRewindowed() {
        val value = "draft"
        val pasted = "é\\\n".repeat(1_500)
        val question = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        var edited: String? = null
        var submitted: String? = null
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                QuestionCard(question, enabled = true, pagedEditor = PagedEditorView(text = value),
                    onEditPagedEditor = { edited = it }, onSubmitPagedEditor = { submitted = it }) { }
            }
        } }
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement(pasted)
        compose.runOnIdle { assertEquals(pasted, edited) }
        val displayed = requireNotNull(compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
        assertTrue("Paste must rewindow without truncating the draft", displayed.length <= 2_048)
        val send = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.remote_send)
        compose.onNodeWithText(send).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(pasted, submitted) }
    }

    @Test fun equalReplacementQuestionDoesNotInheritWindowDraft() {
        val first = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        val replacement = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        val question = mutableStateOf(first, referentialEqualityPolicy())
        compose.setContent { MaterialTheme {
            QuestionCard(question.value, enabled = true, editorScope = 1, pagedEditor = PagedEditorView(text = "original")) { }
        } }
        compose.onNodeWithTag("questionEditorField").performTextReplacement("old edit")
        compose.runOnIdle { question.value = replacement }
        compose.onNodeWithTag("questionEditorField").assertTextEquals("original")
    }

    @Test fun editInMiddleAndNavigatePreservesOtherWindows() {
        val value = "a".repeat(2_048) + "b".repeat(2_048) + "c".repeat(2_048)
        val expected = "a".repeat(2_048) + "middle" + "c".repeat(2_048)
        val question = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        var submitted: String? = null
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                QuestionCard(question, enabled = true, pagedEditor = PagedEditorView(text = value), onSubmitPagedEditor = { submitted = it }) { }
            }
        } }
        compose.onNodeWithTag("pagedEditorNext").performScrollTo().performClick()
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement("middle")
        compose.onNodeWithTag("pagedEditorLast").performScrollTo().performClick()
        val last = requireNotNull(compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
        assertTrue(last.endsWith("cccc"))
        val send = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.remote_send)
        compose.onNodeWithText(send).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(expected, submitted) }
    }

    @Test fun authorityGenerationChangeDoesNotInheritWindowDraft() {
        val question = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        val generation = mutableStateOf(1)
        compose.setContent { MaterialTheme {
            QuestionCard(question, enabled = true, editorScope = generation.value, pagedEditor = PagedEditorView(text = "original")) { }
        } }
        compose.onNodeWithTag("questionEditorField").performTextReplacement("old edit")
        compose.runOnIdle { generation.value = 2 }
        compose.onNodeWithTag("questionEditorField").assertTextEquals("original")
    }

    @Test fun staleFieldAndSubmitCallbacksCannotActOnReplacementOrGeneration() {
        val first = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        val replacement = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        val question = mutableStateOf(first, referentialEqualityPolicy())
        val generation = mutableStateOf(1)
        val edits = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        val answers = mutableListOf<kotlinx.serialization.json.JsonObject>()
        compose.setContent { MaterialTheme {
            QuestionCard(question.value, enabled = true, editorScope = generation.value, pagedEditor = PagedEditorView(text = "original"),
                onEditPagedEditor = { edits += it }, onSubmitPagedEditor = { submitted += it }) { answers += it }
        } }
        fun oldActions(): Pair<(AnnotatedString) -> Boolean, () -> Boolean> {
            val set = requireNotNull(compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config.getOrNull(SemanticsActions.SetText)?.action)
            val send = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.remote_send)
            val click = requireNotNull(compose.onNodeWithText(send).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action)
            return set to click
        }
        val (oldSet, oldSend) = oldActions()
        val cancel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.remote_cancel)
        val oldCancel = requireNotNull(compose.onNodeWithText(cancel).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle { question.value = replacement }
        compose.waitForIdle()
        compose.runOnIdle { oldSet(AnnotatedString("stale replacement")); invokeRetainedClick(oldSend); invokeRetainedClick(oldCancel) }
        compose.onNodeWithTag("questionEditorField").assertTextEquals("original")
        compose.runOnIdle { assertTrue(edits.isEmpty() && submitted.isEmpty() && answers.isEmpty()) }
        val (generationSet, generationSend) = oldActions()
        compose.runOnIdle { generation.value = 2 }
        compose.waitForIdle()
        compose.runOnIdle { generationSet(AnnotatedString("stale generation")); invokeRetainedClick(generationSend) }
        compose.onNodeWithTag("questionEditorField").assertTextEquals("original")
        compose.runOnIdle { assertTrue(edits.isEmpty() && submitted.isEmpty()) }
    }

    @Test fun jumpRetainsRequestedSectionWhenBoundarySnapsOverSurrogate() {
        val value = "a".repeat(2_047) + "😀" + "b".repeat(2_048)
        val question = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                QuestionCard(question, enabled = true, pagedEditor = PagedEditorView(text = value)) { }
            }
        } }
        compose.onNodeWithTag("pagedEditorSection").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("pagedEditorJump").performClick()
        val section = requireNotNull(compose.onNodeWithTag("pagedEditorSection").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
        assertEquals("2", section)
    }

    @Test fun staleLoadCallbackCannotActAfterAuthorityGenerationChanges() {
        val question = Wire.objectOf("id" to "editor", "kind" to "editor", "editorUploadGrantId" to "grant")
        val generation = mutableStateOf(1)
        var loads = 0
        compose.setContent { MaterialTheme {
            QuestionCard(question, enabled = true, editorScope = generation.value, pagedEditor = PagedEditorView(), onLoadPagedEditor = { loads++ }) { }
        } }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.remote_paged_editor_load)
        val oldLoad = requireNotNull(compose.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle { generation.value = 2 }
        compose.waitForIdle()
        compose.runOnIdle { invokeRetainedClick(oldLoad); assertEquals(0, loads) }
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { assertEquals(1, loads) }
    }
}
