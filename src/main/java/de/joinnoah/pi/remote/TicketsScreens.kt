package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Placeholder until the ticket list is built; it only names the page. */
@Composable
internal fun TicketsScreen(key: RemoteNavKey.Tickets, model: TicketsViewModel, navigator: RemoteNavigator) {
    Text(stringResource(R.string.remote_tickets_title), style = MaterialTheme.typography.titleLarge)
}

/** Placeholder until the ticket detail is built; it only names the page. */
@Composable
internal fun TicketScreen(key: RemoteNavKey.Ticket, model: TicketsViewModel, navigator: RemoteNavigator) {
    Text(key.ref, style = MaterialTheme.typography.titleLarge)
}
