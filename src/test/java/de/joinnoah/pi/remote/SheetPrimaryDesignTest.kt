package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1200dp")
class SheetPrimaryDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun partialTicketStartOpensExistingChat() {
        var tickets by mutableStateOf(TicketsState())
        val opened = mutableListOf<String>()
        var dismissed = 0
        compose.setContent { MaterialTheme {
            StartSheet("p", "DEV-1", tickets, true, {}, { opened += it }, { dismissed++ })
        } }
        compose.onNodeWithTag("ticketSheetConfirm").performClick()
        tickets = tickets.copy(start = TicketStart("id", TicketStartState.FAILED, TicketStartStep.PROMPT,
            false, true, "s", TicketStartError.SESSION_FAILED, "p", "DEV-1"))
        compose.onNodeWithTag("ticketStartOpenChat").assertIsDisplayed().performClick()
        assertEquals(listOf("s"), opened)
        assertEquals(1, dismissed)
    }
}
