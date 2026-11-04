package de.joinnoah.pi.remote

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdjacentUriHandlerTest {
    private val started = mutableListOf<Intent>()
    private var missing: ActivityNotFoundException? = null

    private val context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun startActivity(intent: Intent) {
                missing?.let { throw it }
                started += intent
            }
        }

    private fun open(adjacent: Boolean, uri: String = "https://example.com/page"): Intent {
        AdjacentUriHandler(context) { adjacent }.openUri(uri)
        return started.single()
    }

    private val adjacentFlags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK

    @Test
    fun splitScreenOrAWideWindowOnALargeScreenOpensLinksBeside() {
        assertTrue(opensLinksAdjacent(inMultiWindow = true, width = 360.dp, smallestWidth = 360.dp))
        assertTrue(opensLinksAdjacent(inMultiWindow = false, width = 720.dp, smallestWidth = 600.dp))
        assertTrue(opensLinksAdjacent(inMultiWindow = false, width = 1280.dp, smallestWidth = 800.dp))
        assertFalse(opensLinksAdjacent(inMultiWindow = false, width = 719.dp, smallestWidth = 800.dp))
        assertFalse(opensLinksAdjacent(inMultiWindow = false, width = 411.dp, smallestWidth = 411.dp))
    }

    @Test
    fun aPhoneInLandscapeIsWideButKeepsItsLinksAsBefore() {
        assertFalse(opensLinksAdjacent(inMultiWindow = false, width = 915.dp, smallestWidth = 411.dp))
        assertFalse(opensLinksAdjacent(inMultiWindow = false, width = 830.dp, smallestWidth = 384.dp))
        assertFalse(opensLinksAdjacent(inMultiWindow = false, width = 720.dp, smallestWidth = 599.dp))
    }

    @Test
    fun anAdjacentLinkStartsInANewTaskBesidePocketPi() {
        val intent = open(adjacent = true)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://example.com/page"), intent.data)
        assertEquals(adjacentFlags, intent.flags and adjacentFlags)
    }

    @Test
    fun aPhoneOpensAPlainViewIntentAsBefore() {
        val intent = open(adjacent = false)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://example.com/page"), intent.data)
        assertEquals(0, intent.flags)
    }

    @Test
    fun theDecisionIsMadeAtEachTap() {
        var wide = false
        val handler = AdjacentUriHandler(context) { wide }
        handler.openUri("https://example.com/a")
        wide = true
        handler.openUri("https://example.com/b")
        assertEquals(listOf(0, adjacentFlags), started.map { it.flags and adjacentFlags })
    }

    @Test
    fun aLinkNoAppOpensFailsLikeComposesOwnHandler() {
        for (adjacent in listOf(false, true)) {
            val error = ActivityNotFoundException("no browser").also { missing = it }
            try {
                AdjacentUriHandler(context) { adjacent }.openUri("https://example.com/page")
                fail("A link nothing opens must throw")
            } catch (thrown: IllegalArgumentException) {
                // Text links drop this silently; the privacy link shows its own message.
                assertSame(error, thrown.cause)
            }
        }
        assertTrue(started.isEmpty())
    }
}
