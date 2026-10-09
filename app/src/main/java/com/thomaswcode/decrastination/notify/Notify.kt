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

    // allowed() is the permission check.
    @SuppressLint("MissingPermission")
    fun post(context: Context, id: Int, notification: Notification) {
        if (allowed(context)) NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)
}
