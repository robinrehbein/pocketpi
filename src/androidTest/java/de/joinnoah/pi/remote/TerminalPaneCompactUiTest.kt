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
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
        compose.onNodeWithTag("terminalInput").assertIsDisplayed()
        compose.onNodeWithTag("terminalInputSend").assertIsDisplayed()
        compose.onNodeWithTag("terminalKey_escape").assertIsDisplayed()
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
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(height.value)) {
                    TerminalPane(attachment, model, onClose = {}, onUnsentChanged = {})
                }
            }
        }
        compose.onNodeWithTag("terminalInput").performTextInput("first line\nsecond line\nthird line\nfourth line")
        captureEvidence("collapsed")
        compose.onNodeWithTag("terminalShortcuts").performClick()
        compose.onNodeWithTag("terminalModifierKey_ctrl").performClick().assertIsOn()
        compose.onNodeWithTag("terminalModifierKey_alt").performClick().assertIsOn()
        compose.onNodeWithTag("terminalModifierKey_shift").performClick().assertIsOn()
        compose.onNodeWithTag("terminalExtraKey_f12").performScrollTo().assertIsDisplayed()
        captureEvidence("expanded")
        compose.onNodeWithTag("terminalHideShortcuts").performClick()
        compose.onNodeWithTag("terminalSubtitle").assertTextEquals(allModifiers(context))
        compose.onNodeWithTag("terminalInput").assertTextContains("first line\nsecond line\nthird line\nfourth line")
        compose.runOnIdle { height.value = 280.dp }
        compose.runOnIdle {
            val webView = checkNotNull(findWebView(composeView))
            val heightDp = webView.height / webView.resources.displayMetrics.density
            assertTrue("A 280 dp pane with draft and modifiers must leave 96 dp for the terminal; got $heightDp dp", heightDp >= 96f)
        }
        compose.onNodeWithTag("terminalInputSend").assertIsDisplayed()
        compose.onNodeWithTag("terminalKey_escape").assertIsDisplayed()
        compose.onNodeWithTag("terminalSubtitle").assertTextEquals(allModifiers(context))
        compose.onNodeWithTag("terminalInput").assertTextContains("first line\nsecond line\nthird line\nfourth line")
        captureEvidence("narrow")
        compose.runOnIdle { height.value = 500.dp }
        compose.onNodeWithTag("terminalShortcuts").performClick()
        compose.onNodeWithTag("terminalModifierKey_ctrl").assertIsOn()
        compose.onNodeWithTag("terminalModifierKey_alt").assertIsOn()
        compose.onNodeWithTag("terminalModifierKey_shift").assertIsOn()
        compose.onNodeWithTag("terminalHideShortcuts").performClick()
        compose.onNodeWithTag("terminalInput").assertTextContains("first line\nsecond line\nthird line\nfourth line")
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
        lateinit var composeView: View
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    TerminalPane(attachment, model, onClose = {}, onUnsentChanged = {})
                }
            }
        }
        compose.onNodeWithTag("terminalInput").performTextInput("first line\nsecond line\nthird line\nfourth line")
        compose.onNodeWithTag("terminalShortcuts").performClick()
        compose.onNodeWithTag("terminalModifierKey_ctrl").performClick()
        compose.onNodeWithTag("terminalModifierKey_alt").performClick()
        compose.onNodeWithTag("terminalModifierKey_shift").performClick()
        compose.onNodeWithTag("terminalHideShortcuts").performClick()
        compose.onNodeWithTag("terminalSubtitle").assertTextEquals(allModifiers(context))
        compose.onNodeWithTag("terminalSending").assertIsDisplayed()
        compose.onNodeWithTag("terminalInput").assertTextContains("first line\nsecond line\nthird line\nfourth line")
        compose.runOnIdle {
            val webView = checkNotNull(findWebView(composeView))
            val heightDp = webView.height / webView.resources.displayMetrics.density
            assertTrue("A 320 dp pane with a draft, modifiers and pending input must leave 96 dp for the terminal; got $heightDp dp", heightDp >= 96f)
        }
        compose.onNodeWithTag("terminalInputSend").assertIsDisplayed()
        compose.onNodeWithTag("terminalKey_escape").assertIsDisplayed()
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
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    TerminalPane(attachment.value, model, onClose = {}, onUnsentChanged = {})
                }
            }
        }
        compose.onNodeWithTag("terminalInput").performTextInput("retained draft")
        compose.onNodeWithTag("terminalShortcuts").performClick()
        compose.onNodeWithTag("terminalModifierKey_ctrl").performClick().assertIsOn()
        compose.onNodeWithTag("terminalHideShortcuts").performClick()
        compose.onNodeWithTag("terminalSubtitle").assertTextEquals(context.getString(R.string.remote_view_key_ctrl))
        compose.runOnIdle { attachment.value = attachment.value.copy(keysEnabled = false) }
        compose.onNodeWithTag("terminalShortcuts").assertDoesNotExist()
        compose.onNodeWithTag("terminalSubtitle").assertDoesNotExist()
        compose.onNodeWithTag("terminalInput").assertTextContains("retained draft")
        compose.onNodeWithTag("terminalKey_escape").assertIsDisplayed()
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
        compose.onNodeWithTag("terminalShortcuts").assertDoesNotExist()
        compose.onNodeWithTag("terminalKey_escape").assertIsDisplayed()
    }

    private fun allModifiers(context: Context) = listOf(R.string.remote_view_key_ctrl, R.string.remote_view_key_alt, R.string.remote_view_key_shift)
        .joinToString("+") { context.getString(it) }

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
