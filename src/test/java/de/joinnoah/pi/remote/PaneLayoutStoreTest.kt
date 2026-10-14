package de.joinnoah.pi.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaneLayoutStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun memosSurviveInTheirOwnFile() {
        val memos = mapOf("ON_DEMAND:280.0" to PaneMemo(list = 0.3f, inspector = 0.35f), "ON_DEMAND:240.0" to PaneMemo(listCollapsed = true))
        PaneLayoutStore(context).save(memos)
        assertEquals(memos, PaneLayoutStore(context).load())
        assertFalse(context.getSharedPreferences("settings", Context.MODE_PRIVATE).contains("memos"))
        // Nothing left to keep removes the entry.
        PaneLayoutStore(context).save(emptyMap())
        assertFalse(context.getSharedPreferences("pane_layout", Context.MODE_PRIVATE).contains("memos"))
        assertEquals(emptyMap<String, PaneMemo>(), PaneLayoutStore(context).load())
    }
}
