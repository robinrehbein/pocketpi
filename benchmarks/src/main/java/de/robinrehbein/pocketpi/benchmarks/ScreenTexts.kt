package de.robinrehbein.pocketpi.benchmarks

import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
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
 * A composed row can exist below the fold. Bring its full touch target away from the list edges
 * before clicking; finding its resource ID alone does not establish that it is safe to tap.
 */
private fun UiDevice.projectRow(rowTag: String, trace: MutableList<String>): UiObject2? {
    val minimumSize = 48 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    var searchDirection = Direction.DOWN
    var edgeReached = false
    if (!hasObject(By.res(rowTag))) wait(Until.hasObject(By.scrollable(true)), 10_000)
    repeat(6) {
        // Smoke tests disable implicit idle waits. Reacquire nodes after this bounded settle.
        waitForIdle(2_000)
        try {
            val list = findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
            val viewport = list?.visibleBounds ?: Rect(0, 0, displayWidth, displayHeight)
            if (viewport.isEmpty) return null
            val node = findObject(By.res(rowTag))
            val bounds = node?.visibleBounds
            trace += "viewport=$viewport row=$bounds edgeReached=$edgeReached"
            val margin = viewport.height() / 10
            val validTouchTarget = bounds != null && !bounds.isEmpty &&
                bounds.height() >= minimumSize && bounds.width() >= minimumSize && viewport.contains(bounds)
            val awayFromEdges = bounds != null &&
                bounds.top >= viewport.top + margin && bounds.bottom <= viewport.bottom - margin
            if (validTouchTarget && (awayFromEdges || (list != null && edgeReached))) return node
            // With no scroll container, retain the margin to stay clear of system bars.
            if (list == null) return null
            val direction =
                if (bounds == null || bounds.isEmpty) searchDirection
                else if (bounds.centerY() < viewport.centerY()) Direction.UP else Direction.DOWN
            // List pages draw under the floating header/footer. The default 10% gesture
            // margin can start a swipe on the footer rather than the scrollable content.
            list.setGestureMarginsPercentage(0.1f, 0.3f, 0.1f, 0.3f)
            val scrollResult = list.scroll(direction, 0.3f)
            trace += "scroll=$direction result=$scrollResult verticalGestureMargins=30%"
            if (scrollResult) {
                searchDirection = direction
                edgeReached = false
            } else {
                // The end may prevent centring a valid row. Settle and recheck fresh bounds on
                // the next pass before accepting it; a missing row may be above this position.
                edgeReached = true
                searchDirection = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
            }
        } catch (_: StaleObjectException) {
            // A layout change invalidated this pass; the next pass obtains fresh nodes.
        }
    }
    return null
}

/**
 * Opens a real Sessions screen, not a matching featured chat on Projects. A stray chat tap can
 * be recovered with one Back (the navigator keeps Sessions beneath Chat). Never Back on Projects,
 * click an offscreen row, or relaunch after leaving the app: those must remain visible failures.
 */
internal fun UiDevice.openProjectUntil(
    expected: BySelector,
    what: String,
    rowTag: String = FIXTURE_PROJECT_ROW,
    timeoutMs: Long = 20_000,
) {
    val sessions = By.res("sessionsProjectTitle")
    val composer = By.res("composerField")
    val trace = mutableListOf<String>()
    fun requireApp() {
        check(currentPackageName == "de.robinrehbein.pocketpi.benchmark") {
            "$what. Left the benchmark app. " + screenTexts()
        }
    }
    fun sessionsLoaded() = hasObject(sessions) && hasObject(expected) && !hasObject(composer)
    repeat(3) {
        waitForIdle(2_000)
        requireApp()
        if (sessionsLoaded()) return
        if (hasObject(composer)) {
            pressBack()
            if (wait(Until.hasObject(sessions), timeoutMs) &&
                wait(Until.hasObject(expected), timeoutMs) && sessionsLoaded()
            ) return
            requireApp()
        }
        // The list finder must never scroll a chat, a host picker, or the system launcher.
        if (!hasObject(By.res("projectsMacHeading"))) return@repeat
        try {
            projectRow(rowTag, trace)?.click()
        } catch (_: StaleObjectException) {
            return@repeat
        }
        if (wait(Until.hasObject(sessions), timeoutMs) &&
            wait(Until.hasObject(expected), timeoutMs) && sessionsLoaded()
        ) return
        requireApp()
    }
    error("$what. " + screenTexts() + " Navigation: " + trace.joinToString(" | "))
}
