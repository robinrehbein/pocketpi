package de.robinrehbein.pocketpi.benchmarks

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
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
