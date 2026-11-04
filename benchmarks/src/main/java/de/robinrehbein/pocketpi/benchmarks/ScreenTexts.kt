package de.robinrehbein.pocketpi.benchmarks

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * The texts and content descriptions on screen, for a failure message: on CI the emulator is gone
 * by the time anyone reads the report.
 */
internal fun UiDevice.screenTexts(): String =
    try {
        findObjects(By.pkg(currentPackageName))
            .flatMap { listOfNotNull(it.text, it.contentDescription) }
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" | ", prefix = "Screen ($currentPackageName): ")
    } catch (error: RuntimeException) {
        "Screen unreadable: $error"
    }

/**
 * Taps the text [tap] until [expected] shows, at most three times. On the emulator a tap that lands
 * while the screen is still settling is sometimes lost; one lost tap must not fail the run.
 */
internal fun UiDevice.tapUntil(tap: String, expected: BySelector, what: String, timeoutMs: Long = 20_000) {
    repeat(3) {
        findObject(By.text(tap))?.click()
        if (wait(Until.hasObject(expected), timeoutMs)) return
    }
    error("$what. " + screenTexts())
}

/** The fixture project of src/benchmark/assets/connected-flow.json, as its Projects row is tagged. */
internal const val FIXTURE_PROJECT_ROW = "projectRow-project-pocketpi"

/**
 * The Projects page row of the project tagged [rowTag], scrolled into view if needed. The project's
 * name alone is no selector: the featured chats above the projects (Ready, Working) each repeat
 * their project's name as a label. Needs the tags exposed as resource IDs, as both activities do.
 */
private fun UiDevice.projectRow(rowTag: String): UiObject2? {
    val row = By.res(rowTag)
    wait(Until.findObject(row), 10_000)?.let { return it }
    // No swipe: a swipe that the list cannot take lands on a chat row as a tap and opens that chat.
    // The page list's own scroll action moves it without touching any row.
    repeat(PROJECT_ROW_SCROLLS) {
        if (!scrollPageForward()) return wait(Until.findObject(row), 10_000)
        waitForIdle()
        findObject(row)?.let { return it }
    }
    return findObject(row)
}

/** Scrolls the tallest scrollable of the active window one page forward; false when it cannot. */
private fun scrollPageForward(): Boolean {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
    val scrollables = mutableListOf<AccessibilityNodeInfo>()
    fun collect(node: AccessibilityNodeInfo) {
        if (node.isScrollable) scrollables += node
        for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
    }
    collect(root)
    val bounds = Rect()
    val list = scrollables.maxByOrNull { node -> node.getBoundsInScreen(bounds); bounds.height() } ?: return false
    return list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
}

private const val PROJECT_ROW_SCROLLS = 6

/**
 * Opens the project of [rowTag] from its Projects row and waits for its session list: the row has
 * gone and [expected] shows. [expected] alone could be a featured chat still on the Projects page.
 * Like [tapUntil], it taps at most three times.
 */
internal fun UiDevice.openProjectUntil(
    expected: BySelector,
    what: String,
    rowTag: String = FIXTURE_PROJECT_ROW,
    timeoutMs: Long = 20_000,
) {
    repeat(3) {
        projectRow(rowTag)?.click()
        if (wait(Until.gone(By.res(rowTag)), timeoutMs) && wait(Until.hasObject(expected), timeoutMs)) return
    }
    error("$what. " + screenTexts())
}
