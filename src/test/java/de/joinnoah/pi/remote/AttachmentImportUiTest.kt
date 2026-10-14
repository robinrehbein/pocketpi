package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A drop on the chat and a paste into the composer reach the attachment import as one batch, and
 * text stays text. The drop goes through Compose's own drag listener with a platform DragEvent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AttachmentImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Types the fixture URIs by their extension, as a real provider would. */
    class FixtureProvider : ContentProvider() {
        override fun onCreate() = true

        override fun getType(uri: Uri): String? =
            when (uri.lastPathSegment?.substringAfterLast('.')) {
                "png" -> "image/png"
                "pdf" -> "application/pdf"
                "log" -> "text/plain"
                else -> null
            }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private val repository = NavigationFakeRepository().apply { holdBatches = true }
    private val settings = FakeSettingsRepository()
    private val chat = RemoteSelection("host", "project", "s1")
    private val shot = Uri.parse("content://fixture/shot.png")
    private val pdf = Uri.parse("content://fixture/spec.pdf")

    init {
        val session = buildJsonObject {
            put("id", "s1")
            put("title", "First session")
            put("status", "idle")
            put("origin", "rpc")
        }
        val project = buildJsonObject {
            put("id", "project")
            put("name", "noah-monorepo")
        }
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
                projects = listOf(project),
                project = project,
                sessions = listOf(session),
                session = session,
                messages = listOf(Wire.objectOf("id" to "u", "role" to "user", "text" to "Look at this", "parts" to JsonArray(emptyList()))),
                capabilities = setOf(ATTACHMENTS_CAPABILITY),
            )
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = chat)
            chat
        }
    }

    private fun render() {
        Robolectric.setupContentProvider(FixtureProvider::class.java, "fixture")
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    keyboard = Configuration.KEYBOARD_QWERTY
                    hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    RemoteNavigation(repository, settings, false, {}, RemoteNotification("host", "s1", 1))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").assertExists()
    }

    private fun shownText(): String =
        compose.onNodeWithTag("composerField").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun composeView(view: View = compose.activity.window.decorView): View? {
        if (view.javaClass.simpleName == "AndroidComposeView") return view
        if (view is ViewGroup) for (i in 0 until view.childCount) composeView(view.getChildAt(i))?.let { return it }
        return null
    }

    /** Compose's drag listener on its root view, which the platform calls for every drag event. */
    private fun dragListener(view: View): View.OnDragListener {
        val info = View::class.java.getDeclaredMethod("getListenerInfo").apply { isAccessible = true }.invoke(view)
        return info.javaClass.getDeclaredField("mOnDragListener").apply { isAccessible = true }.get(info) as View.OnDragListener
    }

    /** Grants as the platform hands them to a drop target; counts the releases. */
    private class Grants {
        var taken = 0
        var released = 0
        val binder: Any =
            Class.forName("com.android.internal.view.IDragAndDropPermissions").let { type ->
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                    when (method.name) {
                        "take", "takeTransient" -> taken++
                        "release" -> released++
                        "asBinder" -> return@newProxyInstance Binder()
                    }
                    null
                }
            }
    }

    private fun dragEvent(action: Int, x: Float, y: Float, clip: ClipData?, description: ClipDescription, grants: Grants? = null): DragEvent {
        val obtain =
            DragEvent::class.java.declaredMethods.single { it.name == "obtain" && it.parameterCount == 12 }
        return obtain.invoke(
            null, action, x, y, 0f, 0f, 0, null, description, clip, null, grants?.binder, action == DragEvent.ACTION_DROP,
        ) as DragEvent
    }

    /** Drags [clip] from another app onto the middle of the chat and drops it there. */
    private fun drop(clip: ClipData, grants: Grants? = null, beforeDrop: () -> Unit = {}) {
        val view = checkNotNull(composeView())
        val listener = dragListener(view)
        val x = view.width / 2f
        val y = view.height / 2f
        val description = clip.description
        compose.runOnIdle {
            assertTrue(listener.onDrag(view, dragEvent(DragEvent.ACTION_DRAG_STARTED, x, y, null, description)))
            listener.onDrag(view, dragEvent(DragEvent.ACTION_DRAG_ENTERED, x, y, null, description))
            listener.onDrag(view, dragEvent(DragEvent.ACTION_DRAG_LOCATION, x, y, null, description))
        }
        compose.waitForIdle()
        beforeDrop()
        compose.runOnIdle {
            assertTrue(listener.onDrag(view, dragEvent(DragEvent.ACTION_DROP, x, y, clip, description, grants)))
            listener.onDrag(view, dragEvent(DragEvent.ACTION_DRAG_ENDED, x, y, null, description))
        }
        compose.waitForIdle()
    }

    private fun clip(vararg uris: Uri, types: Array<String>): ClipData =
        ClipData(ClipDescription("drop", types), ClipData.Item(uris.first())).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }

    @Test
    fun droppedImageAndPdfImportAsOneBatchAndKeepTheirGrantsUntilDone() {
        render()
        val grants = Grants()
        drop(clip(shot, pdf, types = arrayOf("image/png", "application/pdf")), grants) {
            compose.onNodeWithTag("dropScrim").assertExists()
        }
        compose.onNodeWithTag("dropScrim").assertDoesNotExist()
        val (selection, items, onDone) = repository.batches.single()
        assertEquals(chat, selection)
        assertEquals(listOf(Picked(shot.toString(), photo = true), Picked(pdf.toString(), photo = false)), items)
        assertTrue(repository.imports.isEmpty())
        // The import has not read the URIs yet: the grants stay.
        assertEquals(1, grants.taken)
        assertEquals(0, grants.released)
        compose.runOnIdle { onDone() }
        assertEquals(1, grants.released)
    }

    @Test
    fun droppedTextGoesIntoTheDraft() {
        render()
        drop(ClipData.newPlainText("note", "Stack trace from the log"))
        compose.waitUntil { shownText() == "Stack trace from the log" }
        assertTrue(repository.batches.isEmpty())
    }

    @Test
    fun ctrlVWithAnImageAttachesItAndPlainTextIsPasted() {
        render()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val field = compose.onNodeWithTag("composerField")
        field.requestFocus()
        clipboard.setPrimaryClip(ClipData(ClipDescription("shot", arrayOf("image/png")), ClipData.Item(shot)))
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        compose.waitForIdle()
        assertEquals(listOf(Picked(shot.toString(), photo = true)), repository.batches.single().second)
        assertEquals("", shownText())

        // Text stays the field's: the key reaches it. (Robolectric's field types the letter where
        // a device's pastes, so the test checks that the field got the key, not the pasted text.)
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "hello"))
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        compose.waitForIdle()
        assertTrue(shownText().isNotEmpty())
        assertEquals(1, repository.batches.size)

        // A text file copied as a URI is the field's to paste, too.
        val before = shownText()
        clipboard.setPrimaryClip(
            ClipData(ClipDescription("log", arrayOf("text/plain")), ClipData.Item(Uri.parse("content://fixture/build.log")))
        )
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        compose.waitForIdle()
        assertTrue(shownText() != before)
        assertEquals(1, repository.batches.size)
    }

    @Test
    fun pasteImageInTheMenuAttachesTheClipboardImage() {
        render()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "hello"))
        val add = compose.activity.getString(R.string.remote_add_attachment)
        compose.onNodeWithContentDescription(add).performClick()
        compose.onNodeWithTag("pasteAttachment").performClick()
        compose.waitForIdle()
        assertTrue(repository.batches.isEmpty())

        clipboard.setPrimaryClip(ClipData(ClipDescription("shot", arrayOf("image/png")), ClipData.Item(shot)))
        compose.onNodeWithContentDescription(add).performClick()
        compose.onNodeWithTag("pasteAttachment").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Picked(shot.toString(), photo = true)), repository.batches.single().second)
    }

    @Test
    fun dragSourcesBuildTextForAPathAndAUriForAnArtifact() {
        val path = pathTransfer("src/main/App.kt")
        assertEquals(ClipDescription.MIMETYPE_TEXT_PLAIN, path.clipData.description.getMimeType(0))
        assertEquals("src/main/App.kt", path.clipData.getItemAt(0).text.toString())
        assertEquals(View.DRAG_FLAG_GLOBAL, path.flags)
        assertFalse(path.flags and View.DRAG_FLAG_GLOBAL_URI_READ != 0)

        val context = compose.activity
        val artifact = runBlocking { dragArtifact(context, "site/report page.html", "<html>report</html>") }
        val uri = artifact.clipData.getItemAt(0).uri
        assertEquals("${context.packageName}.exports", uri.authority)
        assertEquals("text/html", artifact.clipData.description.getMimeType(0))
        assertEquals(View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ, artifact.flags)
        val file = File(File(context.cacheDir, DragStorage.DIRECTORY), "report-page.html")
        assertEquals("<html>report</html>", file.readText())
    }
}
