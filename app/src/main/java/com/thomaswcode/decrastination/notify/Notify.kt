package com.thomaswcode.decrastination.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.thomaswcode.decrastination.data.HeldNotification
import com.thomaswcode.decrastination.data.HeldReply
import com.thomaswcode.decrastination.data.HeldTap
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Posting notifications, only when they're allowed (a runtime permission since Android 13); the
 * routine ones within their ceiling and outside quiet hours ([NotifyRules], LR-07), the rest held
 * for the next morning's briefing ([postHeld]), made again with their tap and buttons ([opening],
 * [button]).
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

    fun allowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** A notification on [channel] in the app's look (UI-12): its icon, and the logo's pink. */
    fun builder(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel).setSmallIcon(com.thomaswcode.decrastination.R.drawable.ic_focus).setColor(com.thomaswcode.decrastination.ui.Colours.PINK_ARGB)

    /** Whether [channel]'s notifications reach the screen: allowed, and the channel not switched off. */
    fun shown(context: Context, channel: String): Boolean {
        if (!allowed(context)) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        // Not made yet: it's made switched on, at its first use.
        val made = manager.getNotificationChannelCompat(channel) ?: return true
        return made.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    // allowed() is the permission check.
    @SuppressLint("MissingPermission")
    fun post(context: Context, id: Int, notification: Notification) {
        if (allowed(context) && !held(context, id, null, notification)) NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** Held while a routine one's ceiling is decided and counted, so two at once don't both take the last place. */
    private val counting = Any()

    /** Today's count as this run has it: ahead of the file, which is written after. */
    private var count: Pair<String, Int>? = null

    /**
     * Whether [notification] is held back (LR-07): one of the routine channels' in quiet hours, or
     * past today's ceiling, decided and counted in one step. Held, its words, tap and buttons are
     * kept for the next briefing; gone, it's counted. One on a channel you've switched off isn't
     * shown, so it's neither.
     */
    private fun held(context: Context, id: Int, tag: String?, notification: Notification): Boolean {
        val channel = notification.channelId ?: return false
        if (channel !in NotifyRules.CEILINGED || !shown(context, channel)) return false
        val graph = com.thomaswcode.decrastination.AppGraph.get(context)
        val now = graph.clock.now()
        val date = graph.focus.today(now).toString()
        val quiet = com.thomaswcode.decrastination.block.BlockPolicy.isQuiet(now, graph.clock.zone(), graph.settings.value)
        val goes: Boolean
        val sent: Int
        synchronized(counting) {
            val before = count?.takeIf { it.first == date }?.second ?: (graph.runtime.value.notifiedToday[date] ?: 0)
            goes = NotifyRules.goesNow(channel, before, quiet)
            sent = if (goes) before + 1 else before
            count = date to sent
        }
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        graph.scope.launch {
            graph.runtime.update { s ->
                if (goes) s.copy(notifiedToday = mapOf(date to maxOf(sent, s.notifiedToday[date] ?: 0)))
                else s.copy(heldNotifications = (s.heldNotifications.filterNot { it.id == id && it.tag == tag } + HeldNotification(channel, id, tag, now, title, text, taps(extras.getString(EXTRA_TAPS)))).takeLast(NotifyRules.MAX_HELD))
            }
        }
        return !goes
    }

    /**
     * What was held back (LR-07), posted now (the morning's briefing) as it was made, its tap and
     * buttons with it (one with none kept opens the app), outside the ceiling; then let go. Not while
     * notifications aren't allowed: then kept for the next briefing, not lost.
     */
    @SuppressLint("MissingPermission")
    suspend fun postHeld(context: Context) {
        val graph = com.thomaswcode.decrastination.AppGraph.get(context)
        val held = graph.runtime.value.heldNotifications
        if (held.isEmpty() || !allowed(context)) return
        val app = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.thomaswcode.decrastination.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            FLAGS,
        )
        val manager = NotificationManagerCompat.from(context)
        for (h in held) {
            val made = builder(context, h.channel)
                .setSmallIcon(com.thomaswcode.decrastination.R.drawable.ic_focus)
                .setContentTitle(h.title)
                .setContentText(h.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(h.text))
                .setContentIntent(h.taps.firstOrNull { it.label == null }?.let { pending(context, it) } ?: app)
                .setAutoCancel(true)
            h.taps.forEach { tap ->
                val label = tap.label ?: return@forEach
                val reply = tap.reply
                if (reply != null) intentOf(context, tap)?.let { made.addAction(replyAction(context, label, tap.requestCode, it, reply)) }
                else pending(context, tap)?.let { made.addAction(0, label, it) }
            }
            if (h.tag != null) manager.notify(h.tag, h.id, made.build()) else manager.notify(h.id, made.build())
        }
        graph.runtime.update { s -> s.copy(heldNotifications = s.heldNotifications - held.toSet()) }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** Posts one of many of a kind, told apart by [tag] (a key), so no two share a slot. */
    @SuppressLint("MissingPermission")
    fun post(context: Context, tag: String, id: Int, notification: Notification) {
        if (allowed(context) && !held(context, id, tag, notification)) NotificationManagerCompat.from(context).notify(tag, id, notification)
    }

    fun cancel(context: Context, tag: String, id: Int) = NotificationManagerCompat.from(context).cancel(tag, id)
}
