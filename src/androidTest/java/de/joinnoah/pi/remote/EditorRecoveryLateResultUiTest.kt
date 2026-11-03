package de.joinnoah.pi.remote

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class EditorRecoveryLateResultUiTest {
    @get:Rule val compose = createComposeRule()

    private class ControlledRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null
        var launches = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            launches++
        }
    }

    @Test fun missingProviderReportsFailureWithoutSavedCallbackOrLosingEntry() {
        val registry = ControlledRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val entry = EditorRecoveryEntry(
            PagedJournalKey("host", "session", "question"),
            EditorRecoveryKind.DRAFT,
            "retained content",
            1,
        )
        val saved = mutableListOf<Uri>()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    EditorRecoveryDialog(
                        load = { EditorRecoveryLoad.Ready(listOf(entry)) },
                        onExportSaved = { saved.add(it) },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("recoveryEntryDraft").performClick()
        compose.onNodeWithTag("recoveryExport").performScrollTo().performClick()
        compose.runOnIdle {
            registry.dispatchResult(requireNotNull(registry.requestCode), Uri.parse("content://nonexistent-pocketpi-test-provider/document"))
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("recoveryExportStatus").fetchSemanticsNodes().isNotEmpty()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("recoveryExportStatus")
            .assertTextEquals(context.getString(R.string.editor_recovery_export_failed))
        compose.onNodeWithTag("recoveryPreview").assertTextEquals("retained content")
        compose.onNodeWithTag("recoveryExport").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(emptyList<Uri>(), saved) }
    }

    @Test fun restoredPendingRequestBlocksNewExportUntilOldResultIsConsumed() {
        val registry = ControlledRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val key = PagedJournalKey("host", "session", "question")
        val first = EditorRecoveryEntry(key, EditorRecoveryKind.DRAFT, "first", 1)
        val second = EditorRecoveryEntry(key, EditorRecoveryKind.UNCERTAIN_ATTEMPT, "second", 2, "attempt")
        val saved = mutableListOf<Uri>()
        val destination = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "late-recovery-result-${System.nanoTime()}")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    EditorRecoveryDialog(
                        load = { EditorRecoveryLoad.Ready(listOf(first, second)) },
                        onExportSaved = { saved.add(it) },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("recoveryEntryDraft").performClick()
        compose.onNodeWithTag("recoveryExport").performScrollTo().performClick()
        assertEquals(1, registry.launches)
        assertNotNull(registry.requestCode)

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("recoveryEntryUncertain").performClick()
        // Until the old result is consumed, it must not be able to take a new entry.
        compose.onNodeWithTag("recoveryExport").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            registry.dispatchResult(requireNotNull(registry.requestCode), Uri.fromFile(destination))
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("recoveryExportStatus")
            .assertTextEquals(context.getString(R.string.editor_recovery_export_lost))
        compose.onNodeWithTag("recoveryExport").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, registry.launches)
            assertEquals(emptyList<Uri>(), saved)
            assertFalse(destination.exists())
        }
    }
}
