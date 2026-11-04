package de.joinnoah.pi.remote

import androidx.compose.runtime.saveable.SaverScope
import de.joinnoah.pi.remote.InspectorTab.CHANGES
import de.joinnoah.pi.remote.InspectorTab.FILES
import de.joinnoah.pi.remote.InspectorTab.HISTORY
import de.joinnoah.pi.remote.InspectorTab.JOBS
import de.joinnoah.pi.remote.InspectorTab.TOOL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorTabsTest {
    @Test
    fun openingAddsATabOnceAndActivatesIt() {
        val tabs = InspectorTabs().open(CHANGES).open(FILES)
        assertEquals(listOf(CHANGES, FILES), tabs.open)
        assertEquals(FILES, tabs.active)
        // An open tab only becomes active again, in its place.
        val switched = tabs.open(CHANGES)
        assertEquals(listOf(CHANGES, FILES), switched.open)
        assertEquals(CHANGES, switched.active)
    }

    @Test
    fun closingTheActiveTabFallsBackToThePreviousOne() {
        val tabs = InspectorTabs().open(CHANGES).open(FILES).open(TOOL)
        val closed = tabs.close(TOOL)
        assertEquals(listOf(CHANGES, FILES), closed.open)
        assertEquals(FILES, closed.active)
        // The first tab has nothing before it: the one after it takes over.
        val first = tabs.open(CHANGES).close(CHANGES)
        assertEquals(FILES, first.active)
        // Closing another tab keeps the active one.
        assertEquals(TOOL, tabs.close(CHANGES).active)
        // A tab that is not open changes nothing.
        assertSame(tabs, tabs.close(JOBS))
    }

    @Test
    fun closingTheLastTabLeavesNoneActive() {
        val tabs = InspectorTabs().open(JOBS).close(JOBS)
        assertTrue(tabs.open.isEmpty())
        assertNull(tabs.active)
    }

    @Test
    fun aTabWhoseStateIsGoneIsDroppedNeverShownEmpty() {
        val tabs = InspectorTabs().open(CHANGES).open(FILES)
        // A reconnect cleared the files: Changes is shown again.
        val reconciled = tabs.reconcile(listOf(CHANGES))
        assertEquals(listOf(CHANGES), reconciled.open)
        assertEquals(CHANGES, reconciled.active)
        // Nothing left: no tab and no pane.
        assertEquals(InspectorTabs(), tabs.reconcile(emptyList()))
        // Unchanged state keeps the tabs as they are.
        assertEquals(tabs, tabs.reconcile(listOf(FILES, CHANGES)))
    }

    @Test
    fun aTabThatOpenedElsewhereJoinsAndShowsThePane() {
        val tabs = InspectorTabs(listOf(CHANGES), CHANGES, hidden = true)
        // A notification opened the jobs.
        val reconciled = tabs.reconcile(listOf(CHANGES, JOBS))
        assertEquals(listOf(CHANGES, JOBS), reconciled.open)
        assertEquals(JOBS, reconciled.active)
        assertFalse(reconciled.hidden)
    }

    @Test
    fun hidingKeepsTheTabsAndOpeningShowsThemAgain() {
        val hidden = InspectorTabs().open(CHANGES).open(HISTORY).copy(hidden = true)
        assertEquals(listOf(CHANGES, HISTORY), hidden.reconcile(listOf(CHANGES, HISTORY)).open)
        assertTrue(hidden.reconcile(listOf(CHANGES, HISTORY)).hidden)
        assertFalse(hidden.open(CHANGES).hidden)
    }

    @Test
    fun tabsSurviveRecreation() {
        val scope = SaverScope { true }
        val tabs = InspectorTabs().open(FILES).open(TOOL).open(FILES).copy(hidden = true)
        val saved = with(InspectorTabs.Saver) { scope.save(tabs) }!!
        assertEquals(tabs, InspectorTabs.Saver.restore(saved))
        val empty = with(InspectorTabs.Saver) { scope.save(InspectorTabs()) }!!
        assertEquals(InspectorTabs(), InspectorTabs.Saver.restore(empty))
    }
}
