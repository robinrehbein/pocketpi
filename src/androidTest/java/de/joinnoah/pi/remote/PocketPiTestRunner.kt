package de.joinnoah.pi.remote

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.runner.AndroidJUnitRunner

/**
 * Hides system error dialogs on CI emulators before any test runs.
 *
 * On a loaded CI emulator, System UI can stop responding for a few seconds. The resulting
 * "isn't responding" dialog then sits above the app under test and takes every drag and back
 * press, so each later gesture test fails. With error dialogs hidden, the system restarts the
 * stalled process instead of asking, and input keeps reaching the app.
 *
 * The setting is global and outlives the run, so only CI opts in with the runner argument
 * `hideErrorDialogs=true`; local runs on a real phone leave it alone.
 */
class PocketPiTestRunner : AndroidJUnitRunner() {
    private var hideErrorDialogs = false

    override fun onCreate(arguments: Bundle?) {
        hideErrorDialogs = arguments?.getString("hideErrorDialogs") == "true"
        super.onCreate(arguments)
    }

    override fun onStart() {
        if (hideErrorDialogs) runShell("settings put global hide_error_dialogs 1")
        super.onStart()
    }

    private fun runShell(command: String) {
        // The command runs asynchronously; reading its output to the end waits for it to finish.
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }
}
