package com.thomaswcode.decrastination.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** The app's notification channels, created at start-up (creating one that exists changes nothing). */
object Channels {
    const val SYNC = "sync"
    const val SESSION = "session"
    const val PROTECTION = "protection"
    const val DAILY = "daily"
    const val MODEL = "model"
    const val FREE_TIME = "free-time"
    const val FREE_TIME_ENDING = "free-time-ending"

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
                NotificationChannel(DAILY, "Plan and check-ins", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "The morning briefing, how finished work went, calendar questions, and the Sunday check-in"
                },
                NotificationChannel(MODEL, "Claude", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When Claude's plan for a task is dropped, or its API key stops working"
                },
                // Silent, while it's spent (IMP-P2-011); and once, loud enough to see, a minute before it runs out.
                NotificationChannel(FREE_TIME, "Free time", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Your earned free time counting down while a blocked app uses it"
                },
                NotificationChannel(FREE_TIME_ENDING, "Free time running out", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A minute before your free time runs out and the app is covered"
                },
            ),
        )
    }
}
