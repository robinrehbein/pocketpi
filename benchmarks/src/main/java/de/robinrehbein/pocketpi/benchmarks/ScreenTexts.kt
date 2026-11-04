package de.robinrehbein.pocketpi.benchmarks

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
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
    return findObjects(By.scrollable(true)).firstNotNullOfOrNull { list ->
        list.scrollUntil(Direction.DOWN, Until.findObject(row))
    }
}

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
