package com.thomaswcode.decrastination.felix

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.AskActivity

/**
 * Felix's one notification (docs/plan_v4/05-the-chat.md §3), as the outbox makes it from the chat
 * ([Outbox.Notice]): his unread messages and your replies to them, in Android's messaging style, from
 * him by his name; a reply box answering the newest, his replies its choices (shown as chips). Tapped,
 * the chat opens at the first unread.
 */
object FelixNotifications {
    const val KEY_REPLY = "reply"
    const val ACTION_REPLY = "com.thomaswcode.decrastination.FELIX_REPLY"

    /** The message of his a reply answers. */
    const val EXTRA_ID = "id"

    /** [notice] posted, in place of the one showing; silent unless it's for a new message of his. */
    fun show(context: Context, notice: Outbox.Notice) {
        val him = Person.Builder().setName(notice.name).setKey("felix").setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher)).build()
        val you = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(you)
        // Yours as the user's own (no person), so they show as sent.
        notice.lines.forEach { style.addMessage(it.text, it.at, if (it.yours) null else him) }
        val builder = Notify.builder(context, Channels.FELIX)
            .setStyle(style)
            .setWhen(notice.lines.last().at)
            .setShowWhen(true)
            .let { Notify.opening(it, context, NoticeId.Felix.code(), AskActivity.intent(context, openAt = notice.openAt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .setAutoCancel(true)
            .setOnlyAlertOnce(!notice.alert)
            .setSilent(!notice.alert)
            .let { Notify.replying(it, context, "Reply", NoticeId.Felix.code(1), Intent(context, FelixReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_ID, notice.replyTo), KEY_REPLY, "Reply to ${notice.name}", notice.replies) }
        AppGraph.get(context).notifier.post(NoticeId.Felix, builder.build())
    }

    fun cancel(context: Context) = AppGraph.get(context).notifier.cancel(NoticeId.Felix)
}
