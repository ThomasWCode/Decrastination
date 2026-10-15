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
import com.thomaswcode.decrastination.data.HeldNotification
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

    private fun taps(json: String?): List<HeldTap> = json?.let { runCatching { Json.decodeFromString(TAPS, it) }.getOrNull() }.orEmpty()

    private fun keep(builder: NotificationCompat.Builder, tap: HeldTap) {
        builder.extras.putString(EXTRA_TAPS, Json.encodeToString(TAPS, taps(builder.extras.getString(EXTRA_TAPS)) + tap))
    }

    /** [tap] made again: only ever one of the app's own, as it's read back from a file. */
    private fun pending(context: Context, tap: HeldTap): PendingIntent? {
        val intent = runCatching { Intent.parseUri(tap.intent, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        if (intent.component?.packageName != context.packageName) return null
        intent.selector = null
        return if (tap.broadcast) PendingIntent.getBroadcast(context, tap.requestCode, intent, FLAGS) else PendingIntent.getActivity(context, tap.requestCode, intent, FLAGS)
    }

    fun allowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

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
            val builder = NotificationCompat.Builder(context, h.channel)
                .setSmallIcon(com.thomaswcode.decrastination.R.drawable.ic_focus)
                .setContentTitle(h.title)
                .setContentText(h.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(h.text))
                .setContentIntent(h.taps.firstOrNull { it.label == null }?.let { pending(context, it) } ?: app)
                .setAutoCancel(true)
            h.taps.forEach { tap -> tap.label?.let { label -> pending(context, tap)?.let { builder.addAction(0, label, it) } } }
            if (h.tag != null) manager.notify(h.tag, h.id, builder.build()) else manager.notify(h.id, builder.build())
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
