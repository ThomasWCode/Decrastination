package com.thomaswcode.decrastination.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.thomaswcode.decrastination.data.HeldNotification
import com.thomaswcode.decrastination.data.HeldReply
import com.thomaswcode.decrastination.data.HeldTap
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * How the app's notifications are made: in its look ([builder]), their tap and buttons kept with them
 * ([opening], [button], [replying]) so one held back for the next morning's briefing (LR-07) is made
 * again as it was ([rebuild]). [Notifier] posts them.
 */
object Notify {
    /** Where a notification's tap and buttons are kept, to make it again with should it be held back. */
    private const val EXTRA_TAPS = "com.thomaswcode.decrastination.taps"
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private val TAPS = ListSerializer(HeldTap.serializer())

    /**
     * [builder] opening [intent]'s activity (the app's) when tapped, kept with it too: held back
     * (LR-07), it's posted as it was made, not as a line opening the app.
     */
    fun opening(builder: NotificationCompat.Builder, context: Context, requestCode: Int, intent: Intent): NotificationCompat.Builder {
        keep(builder, HeldTap(label = null, broadcast = false, requestCode = requestCode, intent = intent.toUri(Intent.URI_INTENT_SCHEME)))
        return builder.setContentIntent(PendingIntent.getActivity(context, requestCode, intent, FLAGS))
    }

    /** [builder] with a button, [label], sending [intent] to a receiver of the app's; kept with it too, as [opening]'s tap is. */
    fun button(builder: NotificationCompat.Builder, context: Context, label: String, requestCode: Int, intent: Intent): NotificationCompat.Builder {
        keep(builder, HeldTap(label = label, broadcast = true, requestCode = requestCode, intent = intent.toUri(Intent.URI_INTENT_SCHEME)))
        return builder.addAction(0, label, PendingIntent.getBroadcast(context, requestCode, intent, FLAGS))
    }

    /**
     * [builder] with a button, [label], opening a reply box ([key], saying [hint], offering
     * [choices]) whose words go to [intent]'s receiver; kept with it too, as [opening]'s tap is.
     */
    fun replying(builder: NotificationCompat.Builder, context: Context, label: String, requestCode: Int, intent: Intent, key: String, hint: String, choices: List<String>): NotificationCompat.Builder {
        keep(builder, HeldTap(label = label, broadcast = true, requestCode = requestCode, intent = intent.toUri(Intent.URI_INTENT_SCHEME), reply = HeldReply(key, hint, choices)))
        return builder.addAction(replyAction(context, label, requestCode, intent, HeldReply(key, hint, choices)))
    }

    private fun replyAction(context: Context, label: String, requestCode: Int, intent: Intent, reply: HeldReply): NotificationCompat.Action {
        // Mutable, as the reply's words are put in it.
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val input = RemoteInput.Builder(reply.key).setLabel(reply.label).setChoices(reply.choices.toTypedArray()).build()
        return NotificationCompat.Action.Builder(com.thomaswcode.decrastination.R.drawable.ic_send, label, pending).addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    private fun taps(json: String?): List<HeldTap> = json?.let { runCatching { Json.decodeFromString(TAPS, it) }.getOrNull() }.orEmpty()

    /** The tap and buttons kept with [notification] ([opening], [button], [replying]). */
    fun taps(notification: Notification): List<HeldTap> = taps(notification.extras.getString(EXTRA_TAPS))

    private fun keep(builder: NotificationCompat.Builder, tap: HeldTap) {
        builder.extras.putString(EXTRA_TAPS, Json.encodeToString(TAPS, taps(builder.extras.getString(EXTRA_TAPS)) + tap))
    }

    /** [tap]'s intent read back: only ever one of the app's own, as it's read from a file. */
    private fun intentOf(context: Context, tap: HeldTap): Intent? {
        val intent = runCatching { Intent.parseUri(tap.intent, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        if (intent.component?.packageName != context.packageName) return null
        intent.selector = null
        return intent
    }

    /** [tap] made again. */
    private fun pending(context: Context, tap: HeldTap): PendingIntent? {
        val intent = intentOf(context, tap) ?: return null
        return if (tap.broadcast) PendingIntent.getBroadcast(context, tap.requestCode, intent, FLAGS) else PendingIntent.getActivity(context, tap.requestCode, intent, FLAGS)
    }

    /** A notification on [channel] in the app's look: its icon, and the app's blue ([com.thomaswcode.decrastination.ui.Colours.BLUE_ARGB]). */
    fun builder(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel).setSmallIcon(com.thomaswcode.decrastination.R.drawable.ic_focus).setColor(com.thomaswcode.decrastination.ui.Colours.BLUE_ARGB)

    /** [held], held back (LR-07), made again as it was: its tap and buttons with it, or with none kept, opening the app. */
    fun rebuild(context: Context, held: HeldNotification): Notification {
        val app = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.thomaswcode.decrastination.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            FLAGS,
        )
        val made = builder(context, held.channel)
            .setSmallIcon(com.thomaswcode.decrastination.R.drawable.ic_focus)
            .setContentTitle(held.title)
            .setContentText(held.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(held.text))
            .setContentIntent(held.taps.firstOrNull { it.label == null }?.let { pending(context, it) } ?: app)
            .setAutoCancel(true)
        held.taps.forEach { tap ->
            val label = tap.label ?: return@forEach
            val reply = tap.reply
            if (reply != null) intentOf(context, tap)?.let { made.addAction(replyAction(context, label, tap.requestCode, it, reply)) }
            else pending(context, tap)?.let { made.addAction(0, label, it) }
        }
        return made.build()
    }
}
