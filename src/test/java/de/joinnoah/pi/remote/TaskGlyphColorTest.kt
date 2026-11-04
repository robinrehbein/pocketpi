package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskGlyphColorTest {
    @Test
    fun taskStatesUseTheirSemanticColors() {
        val neutral = Color.Gray
        val active = Color.Magenta
        val success = Color.Green
        assertEquals(neutral, taskGlyphColor(TaskStatus.PENDING, neutral, active, success))
        assertEquals(active, taskGlyphColor(TaskStatus.IN_PROGRESS, neutral, active, success))
        assertEquals(success, taskGlyphColor(TaskStatus.COMPLETED, neutral, active, success))
    }
}
