package de.joinnoah.pi.remote

import androidx.compose.runtime.State
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrailleSpinnerTest {
    private var durationScale = 1f
    private val motion = object : MotionDurationScale {
        override val scaleFactor: Float get() = durationScale
    }
    @get:Rule val compose = createComposeRule(effectContext = motion)

    @Test fun tickerUsesExactTenFrameSequenceAndWraps() {
        compose.mainClock.autoAdvance = false
        lateinit var frame: State<Int>
        compose.setContent { frame = rememberBrailleSpinnerFrame(true) }
        compose.mainClock.advanceTimeByFrame()
        val glyphs = StringBuilder()
        compose.runOnIdle { glyphs.append(brailleSpinnerGlyph(frame.value)) }
        repeat(9) {
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { glyphs.append(brailleSpinnerGlyph(frame.value)) }
        }
        assertEquals("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏", glyphs.toString())
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { assertEquals(0, frame.value) }
    }

    @Test fun disabledMotionKeepsFirstFrame() {
        durationScale = 0f
        compose.mainClock.autoAdvance = false
        lateinit var frame: State<Int>
        compose.setContent { frame = rememberBrailleSpinnerFrame(true) }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle { assertEquals(0, frame.value) }
    }

    @Test fun inactiveSpinnerDoesNotAdvance() {
        compose.mainClock.autoAdvance = false
        lateinit var frame: State<Int>
        compose.setContent { frame = rememberBrailleSpinnerFrame(false) }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle { assertEquals(0, frame.value) }
    }
}
