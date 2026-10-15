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
import kotlinx.coroutines.launch

/**
 * Posting notifications, only when they're allowed (a runtime permission since Android 13); the
 * routine ones within their ceiling and outside quiet hours ([NotifyRules], LR-07), the rest held
 * for the next morning's briefing ([postHeld]).
 */
object Notify {
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

    /**
     * Whether [notification] is held back (LR-07): one of the routine channels' in quiet hours, or
     * past today's ceiling. Held, its words are kept for the next briefing; gone, it's counted.
     */
    private fun held(context: Context, id: Int, tag: String?, notification: Notification): Boolean {
        val channel = notification.channelId ?: return false
        if (channel !in NotifyRules.CEILINGED) return false
        val graph = com.thomaswcode.decrastination.AppGraph.get(context)
        val now = graph.clock.now()
        val date = graph.focus.today(now).toString()
        val quiet = com.thomaswcode.decrastination.block.BlockPolicy.isQuiet(now, graph.clock.zone(), graph.settings.value)
        val goes = NotifyRules.goesNow(channel, graph.runtime.value.notifiedToday[date] ?: 0, quiet)
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        graph.scope.launch {
            graph.runtime.update { s ->
                if (goes) s.copy(notifiedToday = NotifyRules.counted(s.notifiedToday, date))
                else s.copy(heldNotifications = (s.heldNotifications.filterNot { it.id == id && it.tag == tag } + HeldNotification(channel, id, tag, now, title, text)).takeLast(NotifyRules.MAX_HELD))
            }
        }
        return !goes
    }

    /** What was held back (LR-07), posted now (the morning's briefing), each opening the app, outside the ceiling; then let go. */
    @SuppressLint("MissingPermission")
    suspend fun postHeld(context: Context) {
        val graph = com.thomaswcode.decrastination.AppGraph.get(context)
        val held = graph.runtime.value.heldNotifications
        if (held.isEmpty()) return
        if (allowed(context)) {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, com.thomaswcode.decrastination.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            for (h in held) {
                val notification = NotificationCompat.Builder(context, h.channel)
                    .setSmallIcon(com.thomaswcode.decrastination.R.drawable.ic_focus)
                    .setContentTitle(h.title)
                    .setContentText(h.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(h.text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                val manager = NotificationManagerCompat.from(context)
                if (h.tag != null) manager.notify(h.tag, h.id, notification) else manager.notify(h.id, notification)
            }
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
