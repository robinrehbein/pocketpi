package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipMetadata
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
    fun aDroppedTextFileIsAnAttachmentAsOnTheChat() {
        assertEquals(
            Picked("content://a/notes.txt", photo = false),
            attachmentOf("content://a/notes.txt", "text/plain", textFiles = true),
        )
        assertNull(attachmentOf("https://example.com/notes.txt", "text/plain", textFiles = true))
    }

    @Test
    fun aClipItemWhoseTypeCannotBeReadIsStillTakenWhenItIsAContentUri() {
        val uri = Uri.parse("content://a/shot")
        assertEquals(
            Picked("content://a/shot", photo = false),
            attachmentOfItem(uri, typeOf = { throw SecurityException("no grant") }, textFiles = false),
        )
        assertEquals(
            Picked("content://a/shot", photo = false),
            attachmentOfItem(uri, typeOf = { null }, textFiles = false),
        )
        assertNull(attachmentOfItem(null, typeOf = { "image/png" }, textFiles = false))
    }

    /**
     * A hand-built transfer, as Compose hands the listener one for a keyboard commit, a paste or a
     * drop. The constructor is internal to Compose's foundation module, public in its bytecode.
     */
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private fun transfer(
        clip: ClipData,
        source: TransferableContent.Source = TransferableContent.Source.Keyboard,
    ) = TransferableContent(ClipEntry(clip), ClipMetadata(clip.description), source)

    private fun typeByExtension(uri: Uri): String? =
        when (uri.lastPathSegment?.substringAfterLast('.')) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "txt" -> "text/plain"
            "boom" -> throw SecurityException("no grant")
            else -> null
        }

    @Test
    fun aKeyboardImageIsTakenAndNothingIsLeftForTheField() {
        val taken = mutableListOf<List<Picked>>()
        val sticker = Uri.parse("content://keyboard/sticker.png")
        val rest = takeAttachments(
            transfer(ClipData(ClipDescription("sticker", arrayOf("image/png")), ClipData.Item(sticker))),
            ::typeByExtension,
            textFiles = false,
        ) { taken += it }
        assertNull(rest)
        assertEquals(listOf(listOf(Picked(sticker.toString(), photo = true))), taken)
    }

    @Test
    fun aMixedClipKeepsItsTextAndItemsThatAreNotAttachments() {
        val taken = mutableListOf<List<Picked>>()
        val a = Uri.parse("content://x/a.png")
        val b = Uri.parse("content://x/b.gif")
        val clip = ClipData.newPlainText("mixed", "see below")
        clip.addItem(ClipData.Item(a))
        clip.addItem(ClipData.Item(b))
        clip.addItem(ClipData.Item(Uri.parse("https://example.com/c.png")))
        clip.addItem(ClipData.Item(Uri.parse("content://x/notes.txt")))
        val rest = takeAttachments(transfer(clip), ::typeByExtension, textFiles = false) { taken += it }
        // Both images go in one batch; the text, the web link and the text file stay.
        assertEquals(listOf(listOf(Picked(a.toString(), photo = true), Picked(b.toString(), photo = false))), taken)
        val kept = checkNotNull(rest).clipEntry.clipData
        assertEquals(3, kept.itemCount)
        assertEquals("see below", kept.getItemAt(0).text.toString())
        assertEquals("https://example.com/c.png", kept.getItemAt(1).uri.toString())
        assertEquals("content://x/notes.txt", kept.getItemAt(2).uri.toString())
    }

    @Test
    fun anItemWhoseTypeCannotBeReadIsStillTaken() {
        val taken = mutableListOf<List<Picked>>()
        val odd = Uri.parse("content://x/odd.boom")
        val rest = takeAttachments(
            transfer(ClipData(ClipDescription("odd", arrayOf("*/*")), ClipData.Item(odd))),
            ::typeByExtension,
            textFiles = false,
        ) { taken += it }
        assertNull(rest)
        assertEquals(listOf(listOf(Picked(odd.toString(), photo = false))), taken)
    }

    @Test
    fun nothingTakenMeansNoBatch() {
        var batches = 0
        val rest = takeAttachments(transfer(ClipData.newPlainText("t", "just text")), ::typeByExtension, textFiles = false) { batches++ }
        assertEquals(0, batches)
        assertEquals("just text", checkNotNull(rest).clipEntry.clipData.getItemAt(0).text.toString())
    }

    @Test
    fun aTextFileIsAnAttachmentOnlyForADrop() {
        val taken = mutableListOf<List<Picked>>()
        val file = Uri.parse("content://x/log.txt")
        val clip = ClipData(ClipDescription("log", arrayOf("text/plain")), ClipData.Item(file))
        val dropped = takeAttachments(
            transfer(clip, TransferableContent.Source.DragAndDrop), ::typeByExtension, textFiles = true,
        ) { taken += it }
        assertNull(dropped)
        assertEquals(listOf(listOf(Picked(file.toString(), photo = false))), taken)
        taken.clear()
        val pasted = takeAttachments(transfer(clip, TransferableContent.Source.Clipboard), ::typeByExtension, textFiles = false) { taken += it }
        assertTrue(taken.isEmpty())
        assertEquals(file.toString(), checkNotNull(pasted).clipEntry.clipData.getItemAt(0).uri.toString())
    }

    @Test
    fun theListenerHandsAKeyboardCommitToTheBatchCallback() {
        val taken = mutableListOf<List<Picked>>()
        val listener = composerContentListener(context) { taken += it }
        // The resolver knows no such provider, so the clip's own type (image/png) decides: a photo.
        val sticker = Uri.parse("content://keyboard/sticker.png")
        val rest = listener.onReceive(
            transfer(ClipData(ClipDescription("sticker", arrayOf("image/png")), ClipData.Item(sticker))),
        )
        assertNull(rest)
        assertEquals(listOf(listOf(Picked(sticker.toString(), photo = true))), taken)
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

    /** Shows the composer; without [withAttachments] it takes no attachments, as where the composer has none. */
    private fun show(pastes: Pastes, withAttachments: Boolean = true): Harness {
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
                    onPasteAttachments =
                        if (!withAttachments) null
                        else { items, onDone ->
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
     * Guarded: the cache is private to androidx.core, and a library change must not fail every test.
     */
    @Before
    fun forgetFileProviderRoots() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null)
                .let { (it as MutableMap<*, *>).clear() }
        }
    }

    private fun pastedFiles() = File(context.cacheDir, "exports").listFiles().orEmpty().filter { it.name.startsWith("pasted-text-") }

    private fun exportedFile(uri: String): File =
        File(context.cacheDir, "exports").listFiles().orEmpty().single { it.name == Uri.parse(uri).lastPathSegment }

    /** The Compose view that serves the text input session, found in the activity's view tree. */
    private fun composeView(): View {
        fun find(view: View): View? =
            if (view.javaClass.simpleName == "AndroidComposeView") view
            else (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
        return checkNotNull(find(compose.activity.window.decorView)) { "No Compose view" }
    }

    /**
     * Commits an image the way a keyboard does (Gboard, Samsung Keyboard): through the input
     * connection of the focused field, not the clipboard. The field must be focused first.
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
        val pastes = Pastes()
        show(pastes)
        val sticker = Uri.parse("content://de.joinnoah.pi.test.images/sticker.png")
        assertTrue(commitImageFromKeyboard(sticker, "image/png"))
        assertEquals(listOf(listOf(Picked(sticker.toString(), photo = true))), pastes.batches)
        assertEquals("", shownText())
    }

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
        assertEquals(log, exportedFile(attached.uri).readText())
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
    fun aPasteOverTheDraftLimitIsStillOfferedAsAFile() {
        // Over the 128 KB draft limit: the field could not take it, so only the attachment is offered.
        val pastes = Pastes()
        show(pastes)
        val big = "x".repeat(200 * 1024)
        setClip(ClipData.newPlainText("big", big))
        pressPaste()
        assertEquals("", shownText())
        compose.onNodeWithTag("longPasteOffer").assertIsDisplayed()
        compose.onNodeWithTag("longPastePaste").assertDoesNotExist()

        compose.onNodeWithTag("longPasteAttach").performClick()
        compose.waitUntil(5_000) { pastes.batches.isNotEmpty() }
        assertEquals(big, exportedFile(pastes.batches.single().single().uri).readText())
        pastes.done.single().invoke()
    }

    @Test
    fun aNewerLongPasteReplacesTheHeldOne() {
        val pastes = Pastes()
        show(pastes)
        setClip(ClipData.newPlainText("first", "first log\n".repeat(300)))
        pressPaste()
        val second = "second log\n".repeat(300)
        setClip(ClipData.newPlainText("second", second))
        pressPaste()
        compose.onNodeWithTag("longPasteAttach").performClick()
        compose.waitUntil(5_000) { pastes.batches.isNotEmpty() }
        assertEquals(second, exportedFile(pastes.batches.single().single().uri).readText())
        pastes.done.single().invoke()
    }

    @Test
    fun aLongPasteWithoutAttachmentsGoesIntoTheFieldAsBefore() {
        val pastes = Pastes()
        show(pastes, withAttachments = false)
        val log = "a long line of text\n".repeat(300)
        setClip(ClipData.newPlainText("log", log))
        pressPaste()
        assertEquals(log, shownText())
        compose.onNodeWithTag("longPasteOffer").assertDoesNotExist()
        assertTrue(pastes.batches.isEmpty())
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

    @Test
    fun aMixedClipKeepsItsTextAndTakesItsImages() {
        val pastes = Pastes()
        show(pastes)
        val image = Uri.parse("content://de.joinnoah.pi.test.images/shot.png")
        val clip = ClipData.newPlainText("mixed", "See the screenshot: ")
        clip.addItem(ClipData.Item(image))
        setClip(clip)
        pressPaste()
        assertEquals(listOf(listOf(Picked(image.toString(), photo = false))), pastes.batches)
        assertEquals("See the screenshot: ", shownText())
    }

    @Test
    fun severalImagesOfOnePasteGoInOneBatch() {
        val pastes = Pastes()
        show(pastes)
        val first = Uri.parse("content://de.joinnoah.pi.test.images/a.png")
        val second = Uri.parse("content://de.joinnoah.pi.test.images/b.png")
        val clip = ClipData.newUri(context.contentResolver, "images", first)
        clip.addItem(ClipData.Item(second))
        setClip(clip)
        pressPaste()
        assertEquals(
            listOf(listOf(Picked(first.toString(), photo = false), Picked(second.toString(), photo = false))),
            pastes.batches,
        )
        assertEquals("", shownText())
    }
}
