package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The real pane must keep a useful terminal when the available height is reduced by the IME or split screen. */
class TerminalPaneCompactUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactPaneKeepsFourTerminalRowsAndInputControlsVisible() {
        val drafts = object : DraftStorage {
            override fun load(): Map<DraftKey, StoredDraft> = emptyMap()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }
        val model = ChatViewModel(
            UiRemoteRepository(drafts), RemoteNavKey.Chat("host", "project", "session"), UiSettingsRepository(),
        )
        val attachment = TerminalView("session", attaching = false, generation = 1, keysEnabled = true)
        lateinit var composeView: View
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    TerminalPane(attachment, model, onClose = {}, onUnsentChanged = {})
                }
            }
        }

        compose.runOnIdle {
            val webView = findWebView(composeView)
            assertNotNull("The actual terminal renderer must be present", webView)
            val heightDp = checkNotNull(webView).height / webView.resources.displayMetrics.density
            assertTrue("A 320 dp pane must leave at least 96 dp (approximately four rows) for the terminal; got $heightDp dp", heightDp >= 96f)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.remote_terminal_input)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.remote_terminal_send)).assertIsDisplayed()
        compose.onNodeWithText("Esc").assertIsDisplayed()
    }

    @Test fun shortcutDialogAndResizePreserveDraftAndSelectedModifiers() {
        val drafts = object : DraftStorage {
            override fun load(): Map<DraftKey, StoredDraft> = emptyMap()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }
        val model = ChatViewModel(
            UiRemoteRepository(drafts), RemoteNavKey.Chat("host", "project", "session"), UiSettingsRepository(),
        )
        val attachment = TerminalView("session", attaching = false, generation = 1, keysEnabled = true)
        val height = mutableStateOf(320.dp)
        lateinit var composeView: View
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = context.getString(R.string.remote_terminal_input)
        val shortcuts = context.getString(R.string.remote_terminal_shortcuts)
        val hide = context.getString(R.string.remote_terminal_hide_shortcuts)
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(height.value)) {
                    TerminalPane(attachment, model, onClose = {}, onUnsentChanged = {})
                }
            }
        }
        compose.onNodeWithText(input).performTextInput("first line\nsecond line\nthird line\nfourth line")
        captureEvidence("collapsed")
        compose.onNodeWithText(shortcuts).performClick()
        compose.onNode(hasText("Ctrl") and hasClickAction()).performClick().assertIsSelected()
        compose.onNode(hasText("Alt") and hasClickAction()).performClick().assertIsSelected()
        compose.onNode(hasText("Shift") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithText("F12").performScrollTo().assertIsDisplayed()
        captureEvidence("expanded")
        compose.onNodeWithText(hide).performClick()
        compose.onNodeWithText("Ctrl+Alt+Shift").assertIsDisplayed()
        compose.onNodeWithText(input).assertTextContains("first line\nsecond line\nthird line\nfourth line")
        compose.runOnIdle { height.value = 280.dp }
        compose.runOnIdle {
            val webView = checkNotNull(findWebView(composeView))
            val heightDp = webView.height / webView.resources.displayMetrics.density
            assertTrue("A 280 dp pane with draft and modifiers must leave 96 dp for the terminal; got $heightDp dp", heightDp >= 96f)
        }
        compose.onNodeWithText(context.getString(R.string.remote_terminal_send)).assertIsDisplayed()
        compose.onNodeWithText("Esc").assertIsDisplayed()
        compose.onNodeWithText("Ctrl+Alt+Shift").assertIsDisplayed()
        compose.onNodeWithText(input).assertTextContains("first line\nsecond line\nthird line\nfourth line")
        captureEvidence("narrow")
        compose.runOnIdle { height.value = 500.dp }
        compose.onNodeWithText(shortcuts).performClick()
        compose.onNode(hasText("Ctrl") and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("Alt") and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("Shift") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText(hide).performClick()
        compose.onNodeWithText(input).assertTextContains("first line\nsecond line\nthird line\nfourth line")
    }

    @Test fun compactPaneProtectsRendererWithMultilineDraftModifiersAndPendingInput() {
        val drafts = object : DraftStorage {
            override fun load(): Map<DraftKey, StoredDraft> = emptyMap()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }
        val model = ChatViewModel(
            UiRemoteRepository(drafts), RemoteNavKey.Chat("host", "project", "session"), UiSettingsRepository(),
        )
        val attachment = TerminalView("session", attaching = false, generation = 1, keysEnabled = true, inputPending = true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = context.getString(R.string.remote_terminal_input)
        lateinit var composeView: View
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    TerminalPane(attachment, model, onClose = {}, onUnsentChanged = {})
                }
            }
        }
        compose.onNodeWithText(input).performTextInput("first line\nsecond line\nthird line\nfourth line")
        compose.onNodeWithText(context.getString(R.string.remote_terminal_shortcuts)).performClick()
        compose.onNode(hasText("Ctrl") and hasClickAction()).performClick()
        compose.onNode(hasText("Alt") and hasClickAction()).performClick()
        compose.onNode(hasText("Shift") and hasClickAction()).performClick()
        compose.onNodeWithText(context.getString(R.string.remote_terminal_hide_shortcuts)).performClick()
        compose.onNodeWithText("Ctrl+Alt+Shift").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.remote_terminal_sending)).assertIsDisplayed()
        compose.onNodeWithText(input).assertTextContains("first line\nsecond line\nthird line\nfourth line")
        compose.runOnIdle {
            val webView = checkNotNull(findWebView(composeView))
            val heightDp = webView.height / webView.resources.displayMetrics.density
            assertTrue("A 320 dp pane with a draft, modifiers and pending input must leave 96 dp for the terminal; got $heightDp dp", heightDp >= 96f)
        }
        compose.onNodeWithText(context.getString(R.string.remote_terminal_send)).assertIsDisplayed()
        compose.onNodeWithText("Esc").assertIsDisplayed()
    }

    @Test fun capabilityLossHidesInactiveModifierBadgeAndPreservesDraft() {
        val drafts = object : DraftStorage {
            override fun load(): Map<DraftKey, StoredDraft> = emptyMap()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }
        val model = ChatViewModel(
            UiRemoteRepository(drafts), RemoteNavKey.Chat("host", "project", "session"), UiSettingsRepository(),
        )
        val attachment = mutableStateOf(TerminalView("session", attaching = false, generation = 1, keysEnabled = true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = context.getString(R.string.remote_terminal_input)
        val shortcuts = context.getString(R.string.remote_terminal_shortcuts)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    TerminalPane(attachment.value, model, onClose = {}, onUnsentChanged = {})
                }
            }
        }
        compose.onNodeWithText(input).performTextInput("retained draft")
        compose.onNodeWithText(shortcuts).performClick()
        compose.onNode(hasText("Ctrl") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.remote_terminal_hide_shortcuts)).performClick()
        compose.onNodeWithText("Ctrl").assertIsDisplayed()
        compose.runOnIdle { attachment.value = attachment.value.copy(keysEnabled = false) }
        compose.onNodeWithText(shortcuts).assertDoesNotExist()
        compose.onNodeWithText("Ctrl").assertDoesNotExist()
        compose.onNodeWithText(input).assertTextContains("retained draft")
        compose.onNodeWithText("Esc").assertIsDisplayed()
    }

    @Test fun legacyHostDoesNotExposeStructuredShortcuts() {
        val drafts = object : DraftStorage {
            override fun load(): Map<DraftKey, StoredDraft> = emptyMap()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }
        val model = ChatViewModel(
            UiRemoteRepository(drafts), RemoteNavKey.Chat("host", "project", "session"), UiSettingsRepository(),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    TerminalPane(TerminalView("session", attaching = false, generation = 1), model, {}, {})
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.remote_terminal_shortcuts)).assertDoesNotExist()
        compose.onNodeWithText("Esc").assertIsDisplayed()
    }

    private fun captureEvidence(state: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = InstrumentationRegistry.getArguments().getString("terminalCompactEvidence") ?: return
        require(label.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "Invalid evidence label" }
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(instrumentation.targetContext.noBackupFilesDir, "terminal-compact-$label-$state.png")
        file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
