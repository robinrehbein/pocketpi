package de.joinnoah.pi.remote

import android.Manifest
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The scanner tells a missing permission from a camera that failed, and knows when Android stopped asking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QrScannerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun noDenialYet() {
        CameraDenials.record(context(), granted = true)
    }

    private fun render() {
        compose.setContent { MaterialTheme { QrScanner(onCode = {}, onClose = {}, onPaste = {}) } }
        compose.waitForIdle()
    }

    @Test
    fun theStateSeparatesPermissionFromCameraErrors() {
        assertEquals(CameraAccess.READY, cameraAccess(granted = true, cameraError = false, deniedBefore = false, rationale = false))
        // A camera that failed is no permission problem, whatever happened to the permission before.
        assertEquals(CameraAccess.ERROR, cameraAccess(granted = true, cameraError = true, deniedBefore = true, rationale = false))
        // Before the first request Android shows no rationale either: still ask.
        assertEquals(CameraAccess.ASK, cameraAccess(granted = false, cameraError = false, deniedBefore = false, rationale = false))
        // Denied once: Android still asks, and explains why.
        assertEquals(CameraAccess.ASK, cameraAccess(granted = false, cameraError = false, deniedBefore = true, rationale = true))
        // Denied for good: only the app's settings can grant it.
        assertEquals(CameraAccess.SETTINGS, cameraAccess(granted = false, cameraError = false, deniedBefore = true, rationale = false))
    }

    @Test
    fun aFirstRequestExplainsThePermissionAndAsksAndroid() {
        render()
        compose.onNodeWithTag("cameraMessage").assertTextEquals(compose.activity.getString(R.string.remote_camera_permission))
        compose.onNodeWithTag("allowCamera").performClick()
        compose.waitForIdle()
        val activity = shadowOf(compose.activity)
        assertTrue(Manifest.permission.CAMERA in activity.lastRequestedPermission.requestedPermissions)
        // Android's own prompt, not the app's settings.
        assertNotEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, activity.nextStartedActivity?.action)
    }

    @Test
    fun aRefusalIsRemembered() {
        assertFalse(CameraDenials.deniedBefore(context()))
        CameraDenials.record(context(), granted = false)
        assertTrue(CameraDenials.deniedBefore(context()))
        CameraDenials.record(context(), granted = true)
        assertFalse(CameraDenials.deniedBefore(context()))
    }

    @Test
    fun afterAPermanentDenialAllowCameraOpensTheAppSettings() {
        CameraDenials.record(context(), granted = false)
        render()
        compose.onNodeWithTag("cameraMessage").assertTextEquals(compose.activity.getString(R.string.remote_camera_settings))
        compose.onNodeWithTag("allowCamera").performClick()
        compose.waitForIdle()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${compose.activity.packageName}", started.dataString)
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
    }
}
