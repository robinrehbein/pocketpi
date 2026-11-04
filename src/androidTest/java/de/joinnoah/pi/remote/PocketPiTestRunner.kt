package de.joinnoah.pi.remote

import android.app.Application
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
    private var startupArguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        startupArguments = arguments?.let(::Bundle)
        hideErrorDialogs = arguments?.getString("hideErrorDialogs") == "true"
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        val args = startupArguments
        try {
            if (args?.getString("productionEntryFixture") == "true") {
                ProductionEntryBootstrap.install(app, args)
            }
            super.callApplicationOnCreate(app)
            if (args?.getString("productionEntryFixture") == "true") ProductionEntryBootstrap.completed()
        } catch (failure: Throwable) {
            ProductionEntryBootstrap.close()
            throw failure
        } finally {
            DebugTransportFixture.clear()
        }
    }

    override fun onStart() {
        if (startupArguments?.getString("productionEntryFixture") == "true") ProductionEntryBootstrap.awaitStarted()
        if (hideErrorDialogs) runShell("settings put global hide_error_dialogs 1")
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        try { ProductionEntryBootstrap.close() } finally { super.finish(resultCode, results) }
    }

    private fun runShell(command: String) {
        // The command runs asynchronously; reading its output to the end waits for it to finish.
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }
}
