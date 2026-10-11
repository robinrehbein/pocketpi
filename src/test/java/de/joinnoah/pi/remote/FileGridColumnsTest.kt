package de.joinnoah.pi.remote

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FileGridColumnsTest {
    @Test
    fun columnsGrowWithTheWidthWithinTwoAndSix() {
        val expected = mapOf(320 to 2, 411 to 2, 480 to 2, 600 to 3, 720 to 3, 800 to 4, 1100 to 5, 2000 to 6)
        for ((width, columns) in expected) assertEquals("$width dp", columns, fileGridColumns(width.dp))
    }
}
