package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Keep unfinished delivery visible outside the input; confirmed deliveries need no dismissal. */
@Composable
internal fun FollowUpStatus(state: RemoteState, onDismissFollowUp: (String) -> Unit) {
    val delivered = state.followUps.filter { it.status == "delivered" }.map { it.requestId }
    LaunchedEffect(delivered) {
        delivered.forEach(onDismissFollowUp)
    }
    val visible = state.followUps.filter { it.status != "delivered" }
            if (visible.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 112.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("followUpJournal"),
                ) {
                    visible.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    when (entry.status) {
                                        "delivered" -> R.string.remote_follow_up_delivered
                                        "uncertain" -> R.string.remote_follow_up_uncertain
                                        "cancelled" -> R.string.remote_follow_up_cancelled
                                        else -> if (entry.delivery == "steer") R.string.remote_steer_accepted
                                            else R.string.remote_follow_up_pending
                                    },
                                    entry.text.take(100),
                                ),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (entry.status !in setOf("pending", "accepted")) {
                                IconButton(
                                    onClick = { onDismissFollowUp(entry.requestId) },
                                    modifier = Modifier.testTag("dismissFollowUp-${entry.requestId}"),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        stringResource(R.string.remote_follow_up_dismiss, entry.text.take(100)),
                                    )
                                }
                            }
                        }
                    }
                }
            }

}
