package com.thomaswcode.decrastination.felix

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.AskActivity

/**
 * Felix's messages as notifications (AI-24): his words; a reply box, his quick replies its choices
 * (shown as chips); and a button for each of up to two cards (a task to add, changes to apply).
 * One at a time: a newer one takes its place. Tapped, the chat opens, where the thread goes on.
 */
object FelixNotifications {
    /** A button each, after the tap's code in [NoticeId.FelixMessage]'s range: `NoticeIdTest` holds them to it. */
    internal const val MAX_CARDS = 2
    private const val MAX_CARD_TITLE = 24

    const val KEY_REPLY = "reply"
    const val ACTION_REPLY = "com.thomaswcode.decrastination.FELIX_REPLY"
    const val ACTION_CARD = "com.thomaswcode.decrastination.FELIX_CARD"

    /** The message replied to: a record of his ([com.thomaswcode.decrastination.data.FelixRecord]), or an answer of his in the chat. */
    const val EXTRA_ID = "id"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_CARD = "card"

    /**
     * [text], his message [id], from [name], with [replies] to choose from and [cards] (each with the
     * chat message it's on) to act on. [sent]: the reply just sent to it, shown under it while he
     * answers, without sounding again. [title] in his name's place, and [lines] under it (the
     * morning's list, LR-04), on [channel] (the morning's: the briefing's, LR-07).
     */
    fun post(
        context: Context,
        id: String,
        name: String,
        text: String,
        replies: List<String>,
        cards: List<Pair<String, Proposal>> = emptyList(),
        sent: String? = null,
        title: String? = null,
        lines: List<String> = emptyList(),
        channel: String = Channels.FELIX,
    ) {
        val builder = Notify.builder(context, channel)
            .setSmallIcon(R.drawable.ic_focus)
            .setContentTitle(title ?: name)
            .setContentText(text)
            .setStyle(
                if (lines.isEmpty()) NotificationCompat.BigTextStyle().bigText(text)
                else NotificationCompat.InboxStyle().setBigContentTitle(title ?: name).setSummaryText(text).also { style -> lines.forEach(style::addLine) },
            )
            .let { Notify.opening(it, context, NoticeId.FelixMessage.code(), AskActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .setAutoCancel(true)
            .setOnlyAlertOnce(sent != null)
            // Kept with it, as its buttons are, should the morning's be held back (LR-07).
            .let { Notify.replying(it, context, "Reply", NoticeId.FelixMessage.code(), Intent(context, FelixReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_ID, id), KEY_REPLY, "Reply to $name", replies) }
        sent?.let { builder.setRemoteInputHistory(arrayOf<CharSequence>(it)) }
        cards.take(MAX_CARDS).forEachIndexed { i, (messageId, card) ->
            val label = card.task?.let { "Add “${it.title.take(MAX_CARD_TITLE)}”" } ?: if (card.changes.size == 1) "Apply the change" else "Apply the changes"
            Notify.button(builder, context, label, NoticeId.FelixMessage.code(1 + i), Intent(context, FelixReplyReceiver::class.java).setAction(ACTION_CARD).putExtra(EXTRA_MESSAGE, messageId).putExtra(EXTRA_CARD, card.id))
        }
        AppGraph.get(context).notifier.post(NoticeId.FelixMessage, builder.build())
    }

    fun cancel(context: Context) = AppGraph.get(context).notifier.cancel(NoticeId.FelixMessage)
}
