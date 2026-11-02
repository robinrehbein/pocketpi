package de.joinnoah.pi.remote

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Exercises injected stream failures, not a real document provider. */
class EditorRecoveryIoUiTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val registry = ControlledRegistry()
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }
    private val creations = CountDownLatch(2)
    private val saves = AtomicInteger()
    private var dialogVisible by mutableStateOf(true)
    private val entry = EditorRecoveryEntry(
        PagedJournalKey("host", "session", "question"), EditorRecoveryKind.DRAFT, "retained content", 1,
    )
    private lateinit var output: OutputStream
    private val uri = Uri.parse("content://test/document")
    private val fixture = object : ExternalResource() {
        override fun before() {
            NavigationTestActivity.contentFactory = {
                creations.countDown()
                val content: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        MaterialTheme {
                            if (dialogVisible) EditorRecoveryDialog(
                                load = { EditorRecoveryLoad.Ready(listOf(entry)) },
                                onExportSaved = { saves.incrementAndGet() },
                                openExportOutput = { output },
                                onDismiss = { dialogVisible = false },
                            )
                        }
                    }
                }
                content
            }
        }
        override fun after() { NavigationTestActivity.contentFactory = { {} } }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun writeFailureReportsFailureAndRetainsRecoveryText() {
        output = object : OutputStream() {
            override fun write(b: Int) { throw IOException("write failed") }
            override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("write failed") }
        }
        startExport()
        assertFailedAndRetained()
    }

    @Test fun closeFailureReportsFailureAndRetainsRecoveryText() {
        output = object : ByteArrayOutputStream() {
            override fun close() { throw IOException("close failed") }
        }
        startExport()
        assertFailedAndRetained()
    }

    @Test fun recreationDuringWriteKeepsExportFencedUntilActualCompletion() {
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        output = object : OutputStream() {
            private fun awaitRelease() {
                enteredWrite.countDown()
                check(releaseWrite.await(30, TimeUnit.SECONDS)) { "Timed out waiting to release write" }
            }
            override fun write(b: Int) {
                awaitRelease()
                bytes.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                awaitRelease()
                bytes.write(b, off, len)
            }
        }
        try {
            startExport()
            compose.waitUntil(10_000) { enteredWrite.count == 0L }
            val original = compose.activity
            InstrumentationRegistry.getInstrumentation().runOnMainSync { original.recreate() }
            assertTrue("Host activity was not recreated", creations.await(10, TimeUnit.SECONDS))
            compose.onNodeWithTag("recoveryEntryDraft").performClick()
            compose.onNodeWithTag("recoveryExport").performScrollTo().assertIsNotEnabled()
            assertEquals(0, saves.get())
            val savedText = contextString(R.string.editor_recovery_export_saved)
            compose.onAllNodesWithText(savedText).assertCountEquals(0)
            releaseWrite.countDown()
            awaitStatus(savedText)
            compose.onNodeWithTag("recoveryExport").performScrollTo().assertIsEnabled()
            assertEquals(1, saves.get())
            assertEquals("retained content", bytes.toString("UTF-8"))
        } finally {
            releaseWrite.countDown()
        }
    }

    @Test fun reopeningDialogDuringWriteKeepsExportFencedUntilActualCompletion() {
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        output = object : OutputStream() {
            private fun awaitRelease() {
                enteredWrite.countDown()
                check(releaseWrite.await(30, TimeUnit.SECONDS)) { "Timed out waiting to release write" }
            }
            override fun write(b: Int) { awaitRelease(); bytes.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { awaitRelease(); bytes.write(b, off, len) }
        }
        try {
            startExport()
            compose.waitUntil(10_000) { enteredWrite.count == 0L }
            compose.onNodeWithText(contextString(R.string.editor_recovery_close)).performClick()
            compose.runOnIdle { dialogVisible = true }
            compose.onNodeWithTag("recoveryEntryDraft").performClick()
            compose.onNodeWithTag("recoveryExport").performScrollTo().assertIsNotEnabled()
            val savedText = contextString(R.string.editor_recovery_export_saved)
            compose.onAllNodesWithText(savedText).assertCountEquals(0)
            assertEquals(0, saves.get())
            releaseWrite.countDown()
            awaitStatus(savedText)
            compose.onNodeWithTag("recoveryExport").performScrollTo().assertIsEnabled()
            assertEquals(1, saves.get())
            assertEquals("retained content", bytes.toString("UTF-8"))
        } finally {
            releaseWrite.countDown()
        }
    }

    private fun startExport() {
        compose.onNodeWithTag("recoveryEntryDraft").performClick()
        compose.onNodeWithTag("recoveryExport").performScrollTo().performClick()
        compose.runOnIdle { registry.dispatchResult(requireNotNull(registry.requestCode), uri) }
    }

    private fun assertFailedAndRetained() {
        awaitStatus(contextString(R.string.editor_recovery_export_failed))
        compose.onNodeWithTag("recoveryPreview").assertTextEquals("retained content")
        compose.onNodeWithTag("recoveryExport").performScrollTo().assertIsEnabled()
        assertEquals(0, saves.get())
    }

    private fun awaitStatus(expected: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recoveryExportStatus").assertTextEquals(expected)
    }

    private fun contextString(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private class ControlledRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
        }
    }
}
