package de.joinnoah.pi.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Notifications, the widget and shortcuts start MainActivity through its non-exported alias, which
 * Robolectric does not resolve like the system does. On a device the alias launch must reach the
 * running activity through onNewIntent, with the alias as the intent's component, instead of
 * stacking a second activity.
 */
class AliasLaunchEmulatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Suppress("DEPRECATION") // The flags overload needs API 33.
    @Test
    fun theAliasIsNotExportedAndTargetsMainActivity() {
        val info = context.packageManager.getActivityInfo(ComponentName(context.packageName, LaunchIntents.ALIAS), 0)
        assertFalse(info.exported)
        assertEquals(MainActivity::class.java.name, info.targetActivity)
    }

    @Test
    fun aNotificationTapReachesTheRunningActivityThroughOnNewIntent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var running: MainActivity
            lateinit var launchIntent: Intent
            scenario.onActivity {
                running = it
                launchIntent = it.intent
            }
            // A notification's tap target, sent as the system sends it.
            LaunchIntents.open(context, "route", "session").send()
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                var component: String? = null
                instrumentation.runOnMainSync { component = running.intent.component?.className }
                if (component == LaunchIntents.ALIAS) break
                SystemClock.sleep(100)
            }
            instrumentation.runOnMainSync {
                assertEquals(LaunchIntents.ALIAS, running.intent.component?.className)
                assertEquals("session", running.intent.getStringExtra(RemoteNotifications.EXTRA_TARGET))
                assertEquals(0, running.intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                assertSame(running, resumed.single())
                // ActivityScenario recognises its activity by the intent it launched it with, and
                // onNewIntent replaced that. Without the original back, close() never sees the
                // activity reach DESTROYED.
                running.intent = launchIntent
            }
        }
    }
}
