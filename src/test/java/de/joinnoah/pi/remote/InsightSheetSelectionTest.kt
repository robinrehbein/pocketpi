package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightSheetSelectionTest {
    @Test
    fun tasksAppearOnlyWhenNonemptyAndAvailable() {
        val task = SessionTask(1, "Task", TaskStatus.PENDING)
        for (freshness in TaskFreshness.entries) {
            assertFalse(taskInsightShown(SessionTasks(emptyList(), freshness)))
            assertEquals(
                freshness == TaskFreshness.LIVE || freshness == TaskFreshness.STALE,
                taskInsightShown(SessionTasks(listOf(task), freshness)),
            )
        }
    }

    @Test
    fun tasksDoNotChangeExistingChipOrder() {
        for (compact in listOf(false, true)) {
            for (attention in listOf(false, true)) {
                val original = insightChipOrder(compact, attention)
                val withTasks = insightChipOrder(compact, attention, includeTasks = true)
                assertEquals(original, withTasks.filterNot { it == InsightChip.TASKS })
                assertEquals(InsightChip.TASKS, withTasks.last())
                assertTrue(withTasks.containsAll(InsightChip.entries))
            }
        }
    }
}
