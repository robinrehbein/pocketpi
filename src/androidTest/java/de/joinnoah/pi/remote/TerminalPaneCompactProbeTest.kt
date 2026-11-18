package de.joinnoah.pi.remote

import android.util.Log
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Rule
import org.junit.Test

/**
 * Diagnostic only: logs the real measurements of the terminal pane at 320 dp and 280 dp under the
 * tag "CompactProbe" (semantics tree under "CompactProbeTree"). It asserts nothing about sizes.
 */
class TerminalPaneCompactProbeTest {
    @get:Rule val compose = createComposeRule()

    private val tag = "CompactProbe"

    private val tags = listOf(
        "terminalPane", "terminalHeaderPill", "terminalTitle", "terminalSubtitle", "terminalClose",
        "terminalBack", "terminalReloadPill", "terminalReload", "terminalShortcuts", "terminalMoreActions",
        "terminalMoreActionsBack", "terminalKeys", "terminalKey_escape", "terminalKey_enter", "terminalInput",
        "terminalInputSend", "terminalInsert", "terminalViewport", "terminalViewportBusy",
        "terminalViewportPlaceholder", "terminalNotice", "terminalEnded", "terminalUnavailable",
        "terminalControlBar", "terminalControlState", "terminalShortcutsSheet",
    )

    @Test fun probeCompactLayout() {
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
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(height.value)) {
                    TerminalPane(attachment, model, onClose = {}, onUnsentChanged = {})
                }
            }
        }
        compose.waitForIdle()
        snapshot(1, composeView)
        runCatching { compose.onNodeWithTag("terminalInput").performTextInput("first line\nsecond line\nthird line\nfourth line") }
            .onFailure { log("step typing failed: ${it.message}") }
        compose.waitForIdle()
        snapshot(2, composeView)
        runCatching {
            compose.onNodeWithTag("terminalShortcuts").performClick()
            compose.onNodeWithTag("terminalModifierKey_ctrl").performClick()
            compose.onNodeWithTag("terminalModifierKey_alt").performClick()
            compose.onNodeWithTag("terminalModifierKey_shift").performClick()
            compose.onNodeWithTag("terminalExtraKey_f12").performScrollTo()
            compose.onNodeWithTag("terminalHideShortcuts").performClick()
        }.onFailure { log("step shortcuts failed: ${it.message}") }
        compose.waitForIdle()
        snapshot(3, composeView)
        compose.runOnIdle { height.value = 280.dp }
        compose.waitForIdle()
        snapshot(4, composeView)
        runCatching {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().size
            log("4 rootCount=$roots")
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("CompactProbeTree") }.onFailure {
                for (index in 0 until roots) {
                    compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToLog("CompactProbeTree")
                }
            }
        }.onFailure { log("4 tree dump failed: ${it.message}") }
    }

    private fun log(line: String) {
        Log.i(tag, if (line.length > 2900) line.take(2900) + "...(truncated)" else line)
    }

    private inline fun item(moment: Int, name: String, block: () -> String) {
        val text = runCatching(block).getOrElse { "ERROR ${it::class.java.simpleName}: ${it.message}" }
        log("$moment $name: $text")
    }

    private fun snapshot(moment: Int, composeView: View) {
        val resources = composeView.resources
        val density = resources.displayMetrics.density
        fun dp(px: Number) = "%.1f".format(px.toFloat() / density)
        fun dpPx(px: Number) = "${dp(px)}dp/${px}px"
        item(moment, "displayMetrics") {
            val m = resources.displayMetrics
            "density=${m.density} widthPixels=${m.widthPixels} heightPixels=${m.heightPixels} " +
                "widthDp=${dp(m.widthPixels)} heightDp=${dp(m.heightPixels)} fontScale=${resources.configuration.fontScale}"
        }
        item(moment, "composeHost") {
            val loc = IntArray(2).also { composeView.getLocationOnScreen(it) }
            val root = composeView.rootView
            "width=${dpPx(composeView.width)} height=${dpPx(composeView.height)} " +
                "locOnScreen=(${dpPx(loc[0])},${dpPx(loc[1])}) rootViewHeight=${dpPx(root.height)} rootViewWidth=${dpPx(root.width)}"
        }
        item(moment, "insets") {
            val insets = ViewCompat.getRootWindowInsets(composeView) ?: return@item "null"
            fun fmt(i: Insets) = "top=${dpPx(i.top)} bottom=${dpPx(i.bottom)} left=${dpPx(i.left)} right=${dpPx(i.right)}"
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val safeDrawing = Insets.max(Insets.max(systemBars, cutout), ime)
            "statusBars[${fmt(statusBars)}] navigationBars[${fmt(navBars)}] ime[${fmt(ime)}] " +
                "imeVisible=${insets.isVisible(WindowInsetsCompat.Type.ime())} displayCutout[${fmt(cutout)}] " +
                "systemGestures[${fmt(gestures)}] safeDrawing[${fmt(safeDrawing)}]"
        }
        item(moment, "webView") {
            val web = findWebView(composeView) ?: return@item "absent"
            val loc = IntArray(2).also { web.getLocationOnScreen(it) }
            "width=${dpPx(web.width)} height=${dpPx(web.height)} locOnScreen=(${dpPx(loc[0])},${dpPx(loc[1])})"
        }
        for (name in tags) {
            item(moment, name) {
                val node = compose.onAllNodesWithTag(name, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                    ?: return@item "absent"
                val b = node.boundsInRoot
                val displayed = runCatching {
                    compose.onAllNodesWithTag(name, useUnmergedTree = true)[0].assertIsDisplayed()
                }.isSuccess
                "boundsInRoot=(l=${dpPx(b.left)}, t=${dpPx(b.top)}, r=${dpPx(b.right)}, b=${dpPx(b.bottom)}) " +
                    "size=(${dpPx(node.size.width)} x ${dpPx(node.size.height)}) isDisplayed=$displayed"
            }
        }
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
