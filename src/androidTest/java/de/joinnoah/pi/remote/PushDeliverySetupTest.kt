package de.joinnoah.pi.remote

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PushDeliverySetupTest {
    @Test
    fun savesLiveFirebaseTokenForManualPushDelivery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("piRemoteLivePush") == "true",
        )

        instrumentation.uiAutomation
            .executeShellCommand(
                "pm grant ${instrumentation.targetContext.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            )
            .close()

        val app = instrumentation.targetContext.applicationContext as RemoteApplication
        assertTrue("Firebase must be configured for the live push fixture", app.pushConfigured)
        instrumentation.runOnMainSync { app.enablePush() }

        val token = Tasks.await(FirebaseMessaging.getInstance().token, 60, TimeUnit.SECONDS)
        instrumentation.targetContext.openFileOutput(
            "pocketpi-live-push-token.txt",
            android.content.Context.MODE_PRIVATE,
        ).bufferedWriter().use { it.write(token) }
    }
}
