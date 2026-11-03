package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h800dp")
class AcceptanceHierarchyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleAndReferenceRemainReadableAndActionsRemainSeparate() {
        val ticket = Ticket(
            "DEV-12", "A ticket to accept", "https://example.test", "Review", TicketStateGroup.STARTED,
            "task", TicketPriority.HIGH, true, TicketSection.ACCEPTANCE, false,
            setOf(TicketAction.ACCEPT), emptyList(), 0L, emptyList(),
        )
        var opened = 0
        var accepted = 0
        compose.setContent {
            MaterialTheme { AcceptanceRow(ticket, true, { opened++ }, { accepted++ }) }
        }
        compose.onNodeWithTag("acceptanceRow").assertIsDisplayed()
        compose.onNodeWithText(ticket.title).assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText(ticket.ref).assertIsDisplayed()
        val description = RuntimeEnvironment.getApplication().getString(R.string.remote_ticket_accept_ref, ticket.ref)
        compose.onNodeWithContentDescription(description).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, accepted) }
    }

    @Test fun unavailableAcceptDoesNotExposeAnAction() {
        val ticket = Ticket(
            "DEV-13", "Read only", "https://example.test", "Review", TicketStateGroup.STARTED,
            "task", TicketPriority.HIGH, true, TicketSection.ACCEPTANCE, false,
            emptySet(), emptyList(), 0L, emptyList(),
        )
        compose.setContent { MaterialTheme { AcceptanceRow(ticket, false, {}, {}) } }
        val description = RuntimeEnvironment.getApplication().getString(R.string.remote_ticket_accept_ref, ticket.ref)
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
    }
}
