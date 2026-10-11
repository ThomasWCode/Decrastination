package com.thomaswcode.decrastination.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** The app's notification channels, created at start-up (creating one that exists changes nothing). */
object Channels {
    const val SYNC = "sync"
    const val SESSION = "session"
    const val PROTECTION = "protection"
    /** The one channel the next four were (before 1.8.0): taken over by them, then deleted once nothing's left on it. */
    private const val DAILY = "daily"

    // Each its own purpose (IMP-P2-019), so a routine one can be silenced and a question kept.
    const val BRIEFING = "briefing"
    const val HOW_IT_WENT = "how-it-went"
    const val CALENDAR = "calendar-questions"
    const val WEEKLY = "weekly"
    const val MODEL = "model"
    const val FREE_TIME = "free-time"
    const val FREE_TIME_ENDING = "free-time-ending"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // The four that were one start as you'd set it (silenced, say): Android keeps a channel's
        // settings once it's made, so this is their one chance.
        val old = manager.getNotificationChannel(DAILY)
        val was = old?.importance ?: NotificationManager.IMPORTANCE_DEFAULT
        // Its sound (or none), vibration, light, badge and lock-screen showing too: each is a setting
        // of its own, so a quiet one isn't made to ring or buzz.
        fun NotificationChannel.asWas() = apply {
            old ?: return@apply
            setSound(old.sound, old.audioAttributes)
            enableVibration(old.shouldVibrate())
            vibrationPattern = old.vibrationPattern
            enableLights(old.shouldShowLights())
            lightColor = old.lightColor
            setShowBadge(old.canShowBadge())
            lockscreenVisibility = old.lockscreenVisibility
        }
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(BRIEFING, "Morning briefing", was).apply {
                    description = "Today's plan, each morning"
                }.asWas(),
                NotificationChannel(HOW_IT_WENT, "How it went", was).apply {
                    description = "After finished homework or revision: harder, as expected or easier"
                }.asWas(),
                NotificationChannel(CALENDAR, "Calendar questions", was).apply {
                    description = "How much of its day an event the plan can't judge takes"
                }.asWas(),
                NotificationChannel(WEEKLY, "Weekly check-in and review", was).apply {
                    description = "The Sunday check-in, and the week's review"
                }.asWas(),
            ),
        )
        // The old one goes once nothing's left on it: deleting a channel takes its notifications with
        // it (a calendar question, a "How was it?"), and nothing would post them again. Till then
        // it's kept, with nothing new on it, and a later start deletes it.
        if (manager.getNotificationChannel(DAILY) != null && manager.activeNotifications.none { it.notification.channelId == DAILY }) {
            manager.deleteNotificationChannel(DAILY)
        }
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
