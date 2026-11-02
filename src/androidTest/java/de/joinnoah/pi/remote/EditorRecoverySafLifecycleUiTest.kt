package de.joinnoah.pi.remote

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Opt-in: exercises the real system picker on a dedicated emulator. No document is created. */
class EditorRecoverySafLifecycleUiTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val saves = AtomicInteger()
    private val creations = CountDownLatch(2)
    private val entry = EditorRecoveryEntry(
        PagedJournalKey("local", "session", "lifecycle"),
        EditorRecoveryKind.DRAFT,
        "recovery fixture\n".repeat(8_192),
        1,
    )
    private val fixture = object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit lifecycle fixture required", args.getString("editorRecoveryLifecycleExpected") == "true")
            check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") || Build.HARDWARE in setOf("ranchu", "goldfish")) {
                "SAF lifecycle fixture may run only on an emulator"
            }
            saves.set(0)
            NavigationTestActivity.contentFactory = {
                creations.countDown()
                val content: @Composable () -> Unit = {
                    MaterialTheme { EditorRecoveryDialog({ EditorRecoveryLoad.Ready(listOf(entry)) }, onExportSaved = { saves.incrementAndGet() }) {} }
                }
                content
            }
        }
        override fun after() { NavigationTestActivity.contentFactory = { {} } }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @After fun closePickerIfStillOpen() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (device.currentPackageName.endsWith("documentsui")) device.pressBack()
    }

    @Test fun cancellingPickerReenablesExportWithoutReportingSuccess() {
        openPicker()
        cancelPicker()
        compose.onNodeWithTag("recoveryExport").assertIsEnabled()
        compose.onNodeWithTag("recoveryExportStatus").assertDoesNotExist()
        assertEquals(0, saves.get())
        // A second launch proves cancellation cleared the pending export.
        compose.onNodeWithTag("recoveryExport").performScrollTo().performClick()
        awaitPicker()
        cancelPicker()
        compose.onNodeWithTag("recoveryExport").assertIsEnabled()
        assertEquals(0, saves.get())
    }

    @Test fun recreationWhilePickerIsPendingReportsLostExportAndAllowsRetry() {
        val original = compose.activity
        openPicker()
        // Recreate the host before returning a result from the external picker.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { original.recreate() }
        assertTrue("Host activity was not recreated while picker was open", creations.await(10, TimeUnit.SECONDS))
        cancelPicker()
        val lost = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.editor_recovery_export_lost)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("recoveryExportStatus").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recoveryExportStatus").assertTextEquals(lost)
        assertEquals(0, saves.get())
        compose.onNodeWithTag("recoveryEntryDraft").performScrollTo().performClick()
        compose.onNodeWithTag("recoveryExport").performScrollTo().assertIsEnabled().performClick()
        awaitPicker()
        cancelPicker()
        assertEquals(0, saves.get())
    }

    private fun openPicker() {
        compose.onNodeWithTag("recoveryEntryDraft").performScrollTo().performClick()
        compose.onNodeWithTag("recoveryExport").performScrollTo().performClick()
        awaitPicker()
    }

    private fun awaitPicker() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue("System document picker did not appear", device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10_000) || device.currentPackageName.endsWith("documentsui"))
    }

    private fun cancelPicker() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        device.pressBack()
        val appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        compose.waitUntil(10_000) { device.currentPackageName == appPackage }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("recoveryEntryDraft").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("recoveryExport").fetchSemanticsNodes().isNotEmpty() }
    }
}
