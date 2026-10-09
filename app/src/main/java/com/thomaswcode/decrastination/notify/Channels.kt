package com.thomaswcode.decrastination.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** The app's notification channels, created at start-up (creating one that exists changes nothing). */
object Channels {
    const val SYNC = "sync"
    const val SESSION = "session"
    const val PROTECTION = "protection"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(SYNC, "Reading your tasks", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown only on older Android versions, while a sync runs"
                },
                NotificationChannel(SESSION, "Focus sessions", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The session under way, and how it ended"
                },
                NotificationChannel(PROTECTION, "Protection", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When blocking is off: the focus service switched off, or protection weakened"
                },
            ),
        )
    }
}
