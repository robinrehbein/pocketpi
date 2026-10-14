package de.joinnoah.pi.remote

import android.app.UiModeManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * MainActivity handles rotation, resizing and dark mode itself: the same activity stays, so an
 * open artifact preview keeps its loaded WebView. Only a dark mode switch replaces the view, to
 * draw the page in the new colors.
 */
@SdkSuppress(minSdkVersion = 31)
class ConfigChangeEmulatorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Shows an HTML artifact over the app, themed like the app from the system's dark mode. */
    @Before
    fun openArtifact() {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = remoteColorScheme(isSystemInDarkTheme())) {
                    ArtifactViewer(ArtifactKind.Html, "page.html", "Page", "<!doctype html><p>Hello</p>", reload = null, onDismiss = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("artifactPreview").assertExists()
        compose.waitUntil(10_000) { webViews().size == 1 }
    }

    /** Every WebView in the app's windows; the viewer is a dialog, outside the activity's window. */
    private fun webViews(): List<WebView> {
        val found = mutableListOf<WebView>()
        fun visit(view: View) {
            if (view is WebView) found += view
            else if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { WindowInspector.getGlobalWindowViews().forEach(::visit) }
        return found
    }

    @Test
    fun rotationKeepsTheActivityAndTheLoadedPreview() {
        val activity = compose.activity
        val view = webViews().single()
        val landscape = activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val target = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        try {
            compose.runOnUiThread {
                activity.requestedOrientation =
                    if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.waitUntil(10_000) { activity.resources.configuration.orientation == target }
            compose.waitForIdle()
            assertFalse(activity.isDestroyed)
            assertSame(activity, compose.activity)
            assertSame(view, webViews().single())
        } finally {
            compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test
    fun darkModeKeepsTheActivityAndRedrawsThePreviewInTheNewColors() {
        val activity = compose.activity
        val view = webViews().single()
        fun night() = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val dark = night() != Configuration.UI_MODE_NIGHT_YES
        val uiModes = activity.getSystemService(UiModeManager::class.java)
        try {
            uiModes.setApplicationNightMode(if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
            compose.waitUntil(10_000) {
                night() == if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            compose.waitForIdle()
            compose.waitUntil(10_000) { webViews().let { it.size == 1 && it[0] !== view } }
            assertFalse(activity.isDestroyed)
            assertSame(activity, compose.activity)
            assertNotSame(view, webViews().single())
        } finally {
            // Follows the system again, as before the test.
            uiModes.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
        }
    }
}
