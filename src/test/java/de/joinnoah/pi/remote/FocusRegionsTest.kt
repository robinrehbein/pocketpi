package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRegionsTest {
    @Test
    fun f6WalksTheRegionsOnScreenInOrderAndWraps() {
        val all = FocusRegion.entries
        assertEquals(FocusRegion.SESSIONS, nextFocusRegion(all, FocusRegion.PROJECTS, 1))
        assertEquals(FocusRegion.OVERLAY, nextFocusRegion(all, FocusRegion.INSPECTOR, 1))
        assertEquals(FocusRegion.PROJECTS, nextFocusRegion(all, FocusRegion.OVERLAY, 1))
        assertEquals(FocusRegion.OVERLAY, nextFocusRegion(all, FocusRegion.PROJECTS, -1))
        // Regions not on screen are skipped.
        val phone = listOf(FocusRegion.CHAT, FocusRegion.INSPECTOR)
        assertEquals(FocusRegion.CHAT, nextFocusRegion(phone, FocusRegion.INSPECTOR, 1))
        // From nowhere: the first going on, the last going back.
        assertEquals(FocusRegion.CHAT, nextFocusRegion(phone, null, 1))
        assertEquals(FocusRegion.INSPECTOR, nextFocusRegion(phone, null, -1))
        assertNull(nextFocusRegion(emptyList(), null, 1))
    }

    @Test
    fun cycleSkipsARegionThatCannotTakeFocus() {
        val regions = FocusRegions()
        val entered = mutableListOf<FocusRegion>()
        regions.register(FocusRegion.SESSIONS) { entered += FocusRegion.SESSIONS; true }
        regions.register(FocusRegion.CHAT) { entered += FocusRegion.CHAT; false }
        regions.register(FocusRegion.INSPECTOR) { entered += FocusRegion.INSPECTOR; true }
        val owner = Any()
        regions.focusChanged(FocusRegion.SESSIONS, owner, hasFocus = true)
        assertTrue(regions.cycle(1))
        assertEquals(listOf(FocusRegion.CHAT, FocusRegion.INSPECTOR), entered)
    }

    @Test
    fun anOverlayPaneInsideTheChatIsTheCurrentRegion() {
        val regions = FocusRegions()
        regions.focusChanged(FocusRegion.CHAT, "chat", hasFocus = true)
        regions.focusChanged(FocusRegion.OVERLAY, "pane", hasFocus = true)
        assertEquals(FocusRegion.OVERLAY, regions.current)
        regions.focusChanged(FocusRegion.OVERLAY, "pane", hasFocus = false)
        assertEquals(FocusRegion.CHAT, regions.current)
    }

    @Test
    fun anotherScreensRegionLeavesTheFocusedOneAlone() {
        val regions = FocusRegions()
        regions.focusChanged(FocusRegion.SESSIONS, "list", hasFocus = true)
        // A second sessions screen composing or leaving reports no focus for its own element.
        regions.focusChanged(FocusRegion.SESSIONS, "other list", hasFocus = false)
        assertEquals(FocusRegion.SESSIONS, regions.current)
        val focus = { true }
        regions.register(FocusRegion.SESSIONS, focus)
        regions.unregister(FocusRegion.SESSIONS) { true }
        assertEquals(setOf(FocusRegion.SESSIONS), regions.available)
        regions.unregister(FocusRegion.SESSIONS, focus)
        assertFalse(FocusRegion.SESSIONS in regions.available)
    }

    @Test
    fun anOlderRegistrationTakesOverWhenTheNewerOneLeaves() {
        val regions = FocusRegions()
        val entered = mutableListOf<String>()
        val older = { entered += "older"; true }
        val newer = { entered += "newer"; true }
        regions.register(FocusRegion.INSPECTOR, older)
        regions.register(FocusRegion.INSPECTOR, newer)
        assertTrue(regions.cycle(1))
        regions.unregister(FocusRegion.INSPECTOR, newer)
        assertEquals(setOf(FocusRegion.INSPECTOR), regions.available)
        assertTrue(regions.cycle(1))
        assertEquals(listOf("newer", "older"), entered)
        regions.unregister(FocusRegion.INSPECTOR, older)
        assertTrue(regions.available.isEmpty())
    }

    @Test
    fun aPaneOverTheChatIsARegionBesideTheSideInspector() {
        val regions = FocusRegions()
        val entered = mutableListOf<FocusRegion>()
        for (region in listOf(FocusRegion.SESSIONS, FocusRegion.INSPECTOR, FocusRegion.OVERLAY))
            regions.register(region) { entered += region; true }
        regions.focusChanged(FocusRegion.SESSIONS, "list", hasFocus = true)
        regions.cycle(1)
        regions.focusChanged(FocusRegion.SESSIONS, "list", hasFocus = false)
        regions.focusChanged(FocusRegion.INSPECTOR, "inspector", hasFocus = true)
        regions.cycle(1)
        assertEquals(listOf(FocusRegion.INSPECTOR, FocusRegion.OVERLAY), entered)
    }
}
