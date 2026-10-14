package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The composer's paste and long-paste paths on a device. Ctrl+V and the paste menu run the field's
 * paste command, which the test drives through its semantics action. The keyboard's image commit is
 * made through the field's input connection, as the IME makes it; the real Gboard and Samsung
 * Keyboard commits still need a device check.
 */
class ComposerContentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    private class Batches {
        val batches = mutableListOf<List<Picked>>()
        val done = mutableListOf<() -> Unit>()
    }

    private class Harness {
        var state by mutableStateOf(RemoteState(connected = true, status = "idle", selection = RemoteSelection("host", "project", "chat")))
    }

    private fun show(batches: Batches): Harness {
        val harness = Harness()
        compose.setContent {
            MaterialTheme {
                ChatComposer(
                    state = harness.state,
                    onDraftFor = { selection, text ->
                        if (selection == harness.state.selection) harness.state = harness.state.copy(draft = text)
                    },
                    onSend = {},
                    onStop = {},
                    onRemoveQuote = {},
                    onPasteAttachments = { items, onDone ->
                        batches.batches += items
                        batches.done += onDone
                    },
                    voicePermissionGrantedOverride = false,
                )
            }
        }
        compose.waitForIdle()
        return harness
    }

    private fun field() = compose.onNodeWithTag("composerField")

    private fun shownText(): String =
        field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun paste() {
        field().performSemanticsAction(SemanticsActions.PasteText)
        compose.waitForIdle()
    }

    /** The Compose view that serves the text input session, found in the activity's view tree. */
    private fun composeView(): View {
        fun find(view: View): View? =
            if (view.javaClass.simpleName == "AndroidComposeView") view
            else (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
        return checkNotNull(find(compose.activity.window.decorView)) { "No Compose view" }
    }

    /**
     * Commits an image the way Gboard or Samsung Keyboard does: through the input connection of the
     * focused field. Here the test stands in for the IME; a real keyboard needs a device check.
     */
    private fun commitImageFromKeyboard(uri: Uri, mimeType: String): Boolean {
        field().performClick()
        compose.waitForIdle()
        val connection = checkNotNull(
            composeView().onCreateInputConnection(EditorInfo().apply { contentMimeTypes = arrayOf(mimeType) }),
        ) { "The field has no input session" }
        val committed = connection.commitContent(
            InputContentInfo(uri, ClipDescription("sticker", arrayOf(mimeType))),
            0,
            null,
        )
        compose.waitForIdle()
        return committed
    }

    @Test
    fun anImageCommittedByTheKeyboardLandsInTheAttachmentBatch() {
        val batches = Batches()
        show(batches)
        val sticker = Uri.parse("content://de.joinnoah.pi.test.fixtures/sticker.png")
        assertTrue(commitImageFromKeyboard(sticker, "image/png"))
        assertEquals(listOf(Picked(sticker.toString(), photo = false)), batches.batches.single())
        assertEquals("", shownText())
    }

    @Test
    fun plainTextPasteGoesIntoTheFieldUnchanged() {
        val batches = Batches()
        show(batches)
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "Please check the build."))
        paste()
        assertEquals("Please check the build.", shownText())
        assertTrue(batches.batches.isEmpty())
    }

    @Test
    fun anImagePasteAttachesItThroughTheReceiver() {
        val batches = Batches()
        show(batches)
        val shot = Uri.parse("content://de.joinnoah.pi.test.fixtures/shot.png")
        clipboard.setPrimaryClip(ClipData(ClipDescription("shot", arrayOf("image/png")), ClipData.Item(shot)))
        paste()
        assertEquals(listOf(Picked(shot.toString(), photo = true)), batches.batches.single())
        assertEquals("", shownText())
    }

    @Test
    fun aLongTypedTextIsOfferedAsATextFileAndAttachedOnRequest() {
        val batches = Batches()
        show(batches)
        val log = (1..300).joinToString("\n") { "2026-10-14 build line $it" }
        field().performTextInput(log)
        compose.waitForIdle()
        field().assertIsDisplayed()
        assertEquals("", shownText())
        compose.onNodeWithTag("longPasteOffer").assertIsDisplayed()

        compose.onNodeWithTag("longPasteAttach").performClick()
        compose.waitUntil(5_000) { batches.batches.isNotEmpty() }
        val attached = batches.batches.single().single()
        assertTrue(attached.uri.startsWith("content://") && attached.uri.endsWith(".txt"))
        batches.done.single().invoke()
    }

    @Test
    fun aLongPasteDeclinedGoesIntoTheFieldAsText() {
        val batches = Batches()
        show(batches)
        val log = "a long line of text\n".repeat(300)
        clipboard.setPrimaryClip(ClipData.newPlainText("log", log))
        paste()
        compose.onNodeWithTag("longPasteOffer").assertIsDisplayed()
        compose.onNodeWithTag("longPastePaste").performClick()
        compose.waitForIdle()
        assertEquals(log, shownText())
        assertTrue(batches.batches.isEmpty())
    }
}
