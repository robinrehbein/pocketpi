package de.joinnoah.pi.remote

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Session notifications are conversations with pi, grouped per Mac under a summary of their own,
 * and a completion offers a reply that the upgrade in place keeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val complete = PushPayload("r", "s", "e", PushEvent.COMPLETE)
    private val question = PushPayload("r", "s", "e2", PushEvent.QUESTION)

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        RemoteNotifications.createChannel(context)
    }

    private fun active(tag: String, id: Int): StatusBarNotification? =
        manager.activeNotifications.find { it.tag == tag && it.id == id }

    private fun session(routeId: String = "r", sessionId: String = "s"): Notification =
        checkNotNull(active(RemoteNotifications.tag(routeId, sessionId), 1)).notification

    private fun summary(routeId: String = "r"): Notification? =
        active(RemoteNotifications.summaryTag(routeId), RemoteNotifications.SUMMARY_ID)?.notification

    private fun style(notification: Notification) =
        checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))

    private fun replyAction(notification: Notification) =
        notification.actions.orEmpty().find { action -> action.remoteInputs?.isNotEmpty() == true }

    @Test
    fun aCompletionIsAConversationWithPiTiedToTheSessionShortcut() {
        RemoteNotifications.postComplete(context, complete)
        val notification = session()
        assertEquals("pi:r", notification.group)
        assertEquals(shortcutId("r", "s"), notification.shortcutId)
        assertEquals(shortcutId("r", "s"), notification.locusId?.id)
        val style = style(notification)
        assertEquals("pi has finished", style.conversationTitle)
        val message = style.messages.single()
        assertEquals("pi", message.person?.name)
        assertTrue(message.person!!.isBot)
        assertEquals("Open the session to see its current state.", message.text)
        // The lock screen sees no conversation.
        assertEquals("pi has finished", notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification.publicVersion))
    }

    @Test
    fun theNamedCompletionKeepsItsGroupConversationAndReply() {
        RemoteNotifications.postComplete(context, complete)
        assertNotNull(replyAction(session()))
        RemoteNotifications.postComplete(context, complete, SessionLabel("Fix the login bug", "pocketpi", "All tests pass."))
        val notification = session()
        assertEquals("pi:r", notification.group)
        assertEquals(shortcutId("r", "s"), notification.shortcutId)
        val style = style(notification)
        assertEquals("Fix the login bug finished", style.conversationTitle)
        assertEquals("pocketpi\nAll tests pass.", style.messages.single().text)
        val reply = checkNotNull(replyAction(notification))
        assertEquals("Reply", reply.title)
        assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
        assertTrue(reply.isAuthenticationRequired)
        val intent = shadowOf(reply.actionIntent).savedIntent
        assertEquals(RemoteNotifications.ACTION_REPLY, intent.action)
        assertEquals("r", intent.getStringExtra(RemoteNotifications.EXTRA_ROUTE))
        assertEquals("s", intent.getStringExtra(RemoteNotifications.EXTRA_TARGET))
        assertEquals(NotificationActionReceiver::class.java.name, intent.component?.className)
        assertTrue(shadowOf(reply.actionIntent).flags and android.app.PendingIntent.FLAG_MUTABLE != 0)
    }

    @Test
    @Config(sdk = [30])
    fun belowAndroid12ACompletionOffersNoReply() {
        RemoteNotifications.postComplete(context, complete)
        assertNull(replyAction(session()))
    }

    @Test
    fun theUpgradedQuestionKeepsTheGroupAndIsAConversation() {
        RemoteNotifications.postGenericQuestion(context, question)
        assertEquals("pi:r", session().group)
        RemoteNotifications.postQuestion(
            context,
            question,
            QuestionNotification("q", "confirm", "Delete the branch?", "It has no remote.", listOf(NotificationAction.Open)),
            alert = false,
        )
        val notification = session()
        assertEquals("pi:r", notification.group)
        assertEquals(shortcutId("r", "s"), notification.shortcutId)
        val style = style(notification)
        assertEquals("Delete the branch?", style.conversationTitle)
        assertEquals("It has no remote.", style.messages.single().text)
        // A question is answered, not replied to as a new prompt.
        assertTrue(notification.actions.orEmpty().none { RemoteNotifications.ACTION_REPLY == shadowOf(it.actionIntent).savedIntent.action })
    }

    @Test
    fun aPlanTooLongForTheNotificationOffersReviewInTheApp() {
        val plan = Wire.objectOf("id" to "q", "kind" to "plan", "title" to "Plan", "plan" to "p".repeat(500))
        RemoteNotifications.postQuestion(context, question, questionNotification(plan, true)!!, alert = false)
        val action = session().actions.single()
        assertEquals("Review in app", action.title)
        assertEquals(LaunchIntents.ALIAS, shadowOf(action.actionIntent).savedIntent.component?.className)
        val short = Wire.objectOf("id" to "q", "kind" to "plan", "title" to "Plan", "plan" to "1. Do it")
        RemoteNotifications.postQuestion(context, question, questionNotification(short, true)!!, alert = false)
        assertEquals(listOf("Approve and execute", "Open"), session().actions.map { it.title.toString() })
    }

    @Test
    fun eachMacGetsASilentSummaryUnderItsOwnTagAndId() {
        RemoteNotifications.rememberHost(PairedHost("r", "https://relay.example", "d", "secret", "Robin's MacBook"))
        RemoteNotifications.postComplete(context, complete)
        RemoteNotifications.postComplete(context, PushPayload("r2", "s", "e3", PushEvent.COMPLETE))
        val first = checkNotNull(summary("r"))
        assertTrue(first.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals("pi:r", first.group)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, first.groupAlertBehavior)
        assertEquals("Robin's MacBook", first.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        // The lock screen does not see the Mac's name.
        assertNull(first.publicVersion.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val second = checkNotNull(summary("r2"))
        assertEquals("pi:r2", second.group)
        assertEquals("pi:r2", session("r2").group)
        // The summary never times out while it has a child that does not.
        assertEquals(0L, first.timeoutAfter)
    }

    @Test
    fun cancellingTheLastSessionRemovesTheSummaryButNotBefore() {
        RemoteNotifications.postComplete(context, complete)
        RemoteNotifications.postAttention(context, PushPayload("r", "s2", "e4", PushEvent.JOB_DONE))
        RemoteNotifications.cancel(context, "r", "s")
        assertNotNull(summary())
        RemoteNotifications.cancel(context, "r", "s2")
        assertNull(summary())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun unpairingRemovesTheSummaryWithTheMacsNotifications() {
        RemoteNotifications.postComplete(context, complete)
        RemoteNotifications.postComplete(context, PushPayload("r2", "s", "e3", PushEvent.COMPLETE))
        RemoteNotifications.cancelRoute(context, "r")
        assertNull(summary("r"))
        assertNull(active(RemoteNotifications.tag("r", "s"), 1))
        assertNotNull(summary("r2"))
        assertNotNull(active(RemoteNotifications.tag("r2", "s"), 1))
    }

    @Test
    fun dismissingTheLastNotificationTakesTheSummaryAlong() {
        RemoteNotifications.postComplete(context, complete)
        RemoteNotifications.postAttention(context, PushPayload("r", "s", "e4", PushEvent.JOB_DONE))
        val delete = shadowOf(session().deleteIntent).savedIntent
        assertEquals(RemoteNotifications.ACTION_DISMISSED, delete.action)
        // The user swipes the completion away; the job notice still shows.
        manager.cancel(RemoteNotifications.tag("r", "s"), 1)
        NotificationActionReceiver().onReceive(context, delete)
        assertNotNull(summary())
        val jobDelete = shadowOf(active(RemoteNotifications.tag("r", "s"), 2)!!.notification.deleteIntent).savedIntent
        // A delete intent may arrive while the list still holds the dismissed notification.
        NotificationActionReceiver().onReceive(context, jobDelete)
        assertNull(summary())
    }

    @Test
    fun aForgedDismissalIsIgnored() {
        RemoteNotifications.postComplete(context, complete)
        NotificationActionReceiver().onReceive(
            context,
            Intent(RemoteNotifications.ACTION_DISMISSED)
                .putExtra(RemoteNotifications.EXTRA_ROUTE, "r:x")
                .putExtra(RemoteNotifications.EXTRA_TARGET, "s")
                .putExtra(RemoteNotifications.EXTRA_NOTIFICATION_ID, 1),
        )
        assertNotNull(summary())
    }

    @Test
    fun aLoneTransientNotificationTakesTheSummaryAlongWhenItTimesOut() {
        RemoteNotifications.postOutcome(context, "r", "s", AnswerOutcome.SENT)
        assertEquals(8_000L, session().timeoutAfter)
        assertEquals(8_000L, summary()!!.timeoutAfter)
        RemoteNotifications.postComplete(context, PushPayload("r", "s2", "e5", PushEvent.COMPLETE))
        assertEquals(0L, summary()!!.timeoutAfter)
    }

    @Test
    fun aReplyShowsInTheConversationWhileSendingAndAfter() {
        RemoteNotifications.postComplete(context, complete, SessionLabel("Fix the login bug", null, "Done."))
        val previous = RemoteNotifications.conversation(context, "r", "s")
        RemoteNotifications.postReply(context, "r", "s", previous, "Now push it", sending = true)
        var notification = session()
        assertEquals("Sending…", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertEquals(listOf("Done.", "Now push it"), style(notification).messages.map { it.text.toString() })
        assertNull(style(notification).messages.last().person)
        assertTrue(notification.actions.isNullOrEmpty())
        assertEquals("pi:r", notification.group)
        assertEquals(shortcutId("r", "s"), notification.shortcutId)
        RemoteNotifications.postReplyOutcome(context, "r", "s", ReplyOutcome.SENT, previous, "Now push it")
        notification = session()
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertEquals("Fix the login bug finished", style(notification).conversationTitle)
        assertEquals(listOf("Done.", "Now push it"), style(notification).messages.map { it.text.toString() })
    }

    @Test
    fun anUncertainReplyOnlyOpensTheSessionAndAFailedOneCanBeSentAgain() {
        RemoteNotifications.postReplyOutcome(context, "r", "s", ReplyOutcome.UNCERTAIN, null, "Now push it")
        var notification = session()
        assertEquals(
            "Reply may not have been delivered",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals(listOf("Open"), notification.actions.map { it.title.toString() })
        assertNull(replyAction(notification))
        assertEquals("Now push it", notification.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)!!.single())
        RemoteNotifications.postReplyOutcome(context, "r", "s", ReplyOutcome.FAILED, null, "Now push it")
        notification = session()
        assertEquals("Reply not sent", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotNull(replyAction(notification))
        assertEquals("pi:r", notification.group)
    }

    @Test
    fun anEmptyReplyIsExplainedAndCanBeWrittenAgain() {
        RemoteNotifications.postEmptyCompletionReply(context, "r", "s")
        val notification = session()
        assertEquals(
            "The reply was empty and was not sent.",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertNotNull(replyAction(notification))
        assertTrue(notification.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY).isNullOrEmpty())
    }
}
