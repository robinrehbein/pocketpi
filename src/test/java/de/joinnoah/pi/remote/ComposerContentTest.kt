package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The composer's keyboard images, rich pastes and long pastes offered as a text file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val selection = RemoteSelection("host", "project", "chat")

    // ---- Pure rules -----------------------------------------------------------------------

    @Test
    fun theInsertedTextIsTheMiddleThatChanged() {
        assertEquals("X", insertedText("ab", "aXb"))
        assertEquals("a", insertedText("aa", "aaa"))
        assertEquals("", insertedText("abc", "ac"))
        assertEquals("new", insertedText("old", "new"))
    }

    @Test
    fun aPasteIsLongOverEightKilobytesOrTwoHundredLines() {
        assertFalse(isLongPaste("x".repeat(LONG_PASTE_CHARS)))
        assertTrue(isLongPaste("x".repeat(LONG_PASTE_CHARS + 1)))
        assertFalse(isLongPaste("\n".repeat(LONG_PASTE_LINES - 1)))
        assertTrue(isLongPaste("\n".repeat(LONG_PASTE_LINES)))
        assertFalse(isLongPaste("A short sentence.\nAnd a second line."))
    }

    @Test
    fun onlyImagesAndFilesAreTakenFromAPaste() {
        assertEquals(Picked("content://a/b.png", photo = true), attachmentOf("content://a/b.png", "image/png"))
        assertEquals(Picked("content://a/b.gif", photo = false), attachmentOf("content://a/b.gif", "image/gif"))
        assertEquals(Picked("content://a/unknown", photo = false), attachmentOf("content://a/unknown", null))
        // Text stays with the field, and so does anything that is not a content URI.
        assertNull(attachmentOf("content://a/notes.txt", "text/plain"))
        assertNull(attachmentOf("https://example.com/image.png", "image/png"))
        assertNull(attachmentOf(null, "image/png"))
    }

    @Test
    fun aPastedTextIsWrittenAsATextFileInTheExportFolder() {
        val file = writePastedText(context, "line 1\nline 2")
        try {
            assertEquals("exports", file.parentFile?.name)
            assertTrue(file.name.startsWith("pasted-text-") && file.name.endsWith(".txt"))
            assertEquals("line 1\nline 2", file.readText())
        } finally {
            file.delete()
        }
    }

    // ---- The composer field ---------------------------------------------------------------

    private class Pastes {
        val batches = mutableListOf<List<Picked>>()
        val done = mutableListOf<() -> Unit>()
    }

    private class Harness {
        var state by mutableStateOf(RemoteState(connected = true, status = "idle", selection = RemoteSelection("host", "project", "chat")))
    }

    private fun show(pastes: Pastes): Harness {
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
                        pastes.batches += items
                        pastes.done += onDone
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

    private fun setClip(clip: ClipData) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /**
     * Pastes the clipboard as Ctrl+V does on a device: the field's paste command, which hands images
     * and files to its content receiver. Robolectric's key events do not run that command.
     */
    private fun pressPaste() {
        field().performSemanticsAction(SemanticsActions.PasteText)
        compose.waitForIdle()
    }

    /**
     * FileProvider keeps one root per authority for the whole JVM, so a test that runs after another
     * one, with its own cache folder, would resolve paths against the wrong folder. Drop it per test.
     */
    @Before
    fun forgetFileProviderRoots() {
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null)
            .let { (it as MutableMap<*, *>).clear() }
    }

    private fun pastedFiles() = File(context.cacheDir, "exports").listFiles().orEmpty().filter { it.name.startsWith("pasted-text-") }

    @Test
    fun aLongTypedOrPastedTextIsHeldBackAndAttachedAsAFileOnRequest() {
        val pastes = Pastes()
        show(pastes)
        val log = (1..300).joinToString("\n") { "2026-10-14 log line $it" }
        field().performTextInput(log)
        compose.waitForIdle()
        // The field keeps what it had, and the offer says what it does.
        assertEquals("", shownText())
        compose.onNodeWithTag("longPasteOffer").assertIsDisplayed()

        compose.onNodeWithTag("longPasteAttach").performClick()
        compose.waitUntil(5_000) { pastes.batches.isNotEmpty() }
        val attached = pastes.batches.single().single()
        assertTrue(attached.uri.startsWith("content://") && attached.uri.endsWith(".txt"))
        assertFalse(attached.photo)
        assertEquals(log, File(context.cacheDir, "exports").listFiles().orEmpty()
            .single { it.name == Uri.parse(attached.uri).lastPathSegment }.readText())
        // The file goes once the import has read it.
        pastes.done.single().invoke()
        assertTrue(pastedFiles().isEmpty())
        compose.onNodeWithTag("longPasteOffer").assertDoesNotExist()
    }

    @Test
    fun aDeclinedLongPasteGoesIntoTheFieldAsText() {
        val pastes = Pastes()
        show(pastes)
        val log = "a long line of text\n".repeat(300)
        setClip(ClipData.newPlainText("log", log))
        pressPaste()
        compose.onNodeWithTag("longPasteOffer").assertIsDisplayed()
        assertEquals("", shownText())

        compose.onNodeWithTag("longPastePaste").performClick()
        compose.waitForIdle()
        assertEquals(log, shownText())
        assertTrue(pastes.batches.isEmpty())
        compose.onNodeWithTag("longPasteOffer").assertDoesNotExist()
    }

    @Test
    fun aShortTextPasteGoesIntoTheFieldUnchanged() {
        val pastes = Pastes()
        show(pastes)
        setClip(ClipData.newPlainText("note", "Please check the build."))
        pressPaste()
        assertEquals("Please check the build.", shownText())
        assertTrue(pastes.batches.isEmpty())
        compose.onNodeWithTag("longPasteOffer").assertDoesNotExist()
    }

    @Test
    fun ctrlVWithAnImageAttachesItThroughTheReceiver() {
        val pastes = Pastes()
        show(pastes)
        val image = Uri.parse("content://de.joinnoah.pi.test.images/photo.png")
        setClip(ClipData.newUri(context.contentResolver, "image", image))
        pressPaste()
        assertEquals(listOf(listOf(Picked(image.toString(), photo = false))), pastes.batches)
        assertEquals("", shownText())
    }
}
