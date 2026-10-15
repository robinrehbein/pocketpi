package de.joinnoah.pi.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
        CameraDenials.record(context(), denied = false)
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
        assertNull(startedSettings())
    }

    @Test
    fun aRefusalIsRemembered() {
        assertFalse(CameraDenials.deniedBefore(context()))
        CameraDenials.record(context(), denied = true)
        assertTrue(CameraDenials.deniedBefore(context()))
        CameraDenials.record(context(), denied = false)
        assertFalse(CameraDenials.deniedBefore(context()))
    }

    @Test
    fun theAnswerTellsADismissedPromptFromOneThatNoLongerShows() {
        assertEquals(CameraAnswer.GRANTED, cameraAnswer(granted = true, rationaleBefore = false, rationaleAfter = false, stoppedBefore = true))
        // A first refusal: Android explains next time and still asks.
        assertEquals(CameraAnswer.ASK_AGAIN, cameraAnswer(false, rationaleBefore = false, rationaleAfter = true, stoppedBefore = false))
        // Refused after the explanation: Android stops asking.
        assertEquals(CameraAnswer.STOPPED_ASKING, cameraAnswer(false, rationaleBefore = true, rationaleAfter = false, stoppedBefore = false))
        // Android 11+: Back on the first prompt is no answer, so the rationale stays off.
        assertEquals(CameraAnswer.ASK_AGAIN, cameraAnswer(false, rationaleBefore = false, rationaleAfter = false, stoppedBefore = false))
        // Once Android stopped asking, no prompt shows: only the app's settings help.
        assertEquals(CameraAnswer.NO_PROMPT, cameraAnswer(false, rationaleBefore = false, rationaleAfter = false, stoppedBefore = true))
    }

    private fun rationale(shown: Boolean) =
        shadowOf(compose.activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, shown)

    /** Taps Allow camera and answers Android's request with a refusal, the rationale then [rationaleAfter]. */
    private fun refuse(rationaleAfter: Boolean) {
        compose.onNodeWithTag("allowCamera").performClick()
        compose.waitForIdle()
        val request = checkNotNull(shadowOf(compose.activity).lastRequestedPermission)
        assertTrue(Manifest.permission.CAMERA in request.requestedPermissions)
        rationale(rationaleAfter)
        compose.runOnUiThread {
            @Suppress("DEPRECATION")
            compose.activity.onRequestPermissionsResult(
                request.requestCode,
                arrayOf(Manifest.permission.CAMERA),
                intArrayOf(PackageManager.PERMISSION_DENIED),
            )
        }
        compose.waitForIdle()
    }

    private fun message() = compose.onNodeWithTag("cameraMessage")

    /** The app-settings page among the activities started so far (Android's prompt is one of them). */
    private fun startedSettings(): android.content.Intent? =
        generateSequence { shadowOf(compose.activity).nextStartedActivity }
            .firstOrNull { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS }

    @Test
    fun aDismissedFirstPromptStillAsksAndroidNextTime() {
        rationale(false)
        render()
        refuse(rationaleAfter = false)
        message().assertTextEquals(compose.activity.getString(R.string.remote_camera_permission))
        assertFalse(CameraDenials.deniedBefore(context()))
        assertNull(startedSettings())
    }

    @Test
    fun aRefusalAfterTheExplanationPointsToTheSettings() {
        rationale(true)
        render()
        message().assertTextEquals(compose.activity.getString(R.string.remote_camera_permission))
        refuse(rationaleAfter = false)
        message().assertTextEquals(compose.activity.getString(R.string.remote_camera_settings))
        assertTrue(CameraDenials.deniedBefore(context()))
        // The refusal itself does not open the settings.
        assertNull(startedSettings())
    }

    @Test
    fun afterAPermanentDenialAllowCameraOpensTheAppSettingsWhenNoPromptShows() {
        CameraDenials.record(context(), denied = true)
        rationale(false)
        render()
        message().assertTextEquals(compose.activity.getString(R.string.remote_camera_settings))
        refuse(rationaleAfter = false)
        val started = checkNotNull(startedSettings())
        assertEquals("package:${compose.activity.packageName}", started.dataString)
        assertTrue(CameraDenials.deniedBefore(context()))
    }

    @Test
    fun askEveryTimeInTheSettingsBringsAndroidsPromptBack() {
        // Denied for good earlier; in the app's settings the user then chose "Ask every time".
        CameraDenials.record(context(), denied = true)
        rationale(false)
        render()
        // Allow camera asks Android, which shows its prompt again; this time the user refuses once.
        refuse(rationaleAfter = true)
        assertNull(startedSettings())
        message().assertTextEquals(compose.activity.getString(R.string.remote_camera_permission))
        assertFalse(CameraDenials.deniedBefore(context()))
    }
}
