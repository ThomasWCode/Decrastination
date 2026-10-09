package com.thomaswcode.decrastination.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** Posting notifications, only when they're allowed (a runtime permission since Android 13). */
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
        if (allowed(context)) NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)
}
