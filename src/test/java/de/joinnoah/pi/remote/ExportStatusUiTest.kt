package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportStatusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sheetActionsWrapInsideBannerAtLargeFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var sharedUri: String? = null
        var dismissals = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp)) {
                        ExportStatus(
                            ExportState.Done("content://export"),
                            onShare = { sharedUri = it },
                            floating = false,
                            onDismiss = { dismissals++ },
                        )
                    }
                }
            }
        }

        val banner = compose.onNodeWithTag("exportStatus").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText(context.getString(R.string.remote_export_done)).fetchSemanticsNode().boundsInRoot
        val share = compose.onNodeWithText(context.getString(R.string.remote_export_share))
            .assertHasClickAction().assertHeightIsAtLeast(48.dp)
        val dismiss = compose.onNodeWithText(context.getString(R.string.remote_export_dismiss))
            .assertHasClickAction().assertHeightIsAtLeast(48.dp)
        val shareBounds = share.fetchSemanticsNode().boundsInRoot
        val dismissBounds = dismiss.fetchSemanticsNode().boundsInRoot
        for (item in listOf(label, shareBounds, dismissBounds)) {
            assertTrue("Item $item must be inside banner $banner", item.left >= banner.left && item.top >= banner.top &&
                item.right <= banner.right && item.bottom <= banner.bottom)
        }
        fun disjoint(a: Rect, b: Rect) =
            a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
        assertTrue("Label and share overlap", disjoint(label, shareBounds))
        assertTrue("Label and dismiss overlap", disjoint(label, dismissBounds))
        assertTrue("Actions overlap", disjoint(shareBounds, dismissBounds))
        assertTrue("Expected wrapping at narrow width", maxOf(label.top, shareBounds.top, dismissBounds.top) > minOf(label.top, shareBounds.top, dismissBounds.top))

        share.performClick()
        dismiss.performClick()
        compose.runOnIdle {
            assertEquals("content://export", sharedUri)
            assertEquals(1, dismissals)
        }
    }
}
