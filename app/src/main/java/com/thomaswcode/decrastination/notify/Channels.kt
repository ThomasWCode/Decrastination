package com.thomaswcode.decrastination.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** The app's notification channels, created at start-up (creating one that exists changes nothing). */
object Channels {
    const val SYNC = "sync"
    /** The focus sessions' channel, gone with them (AR-09). */
    private const val SESSION = "session"
    const val PROTECTION = "protection"
    /** The one channel the next four were (before 1.8.0): taken over by them, then deleted once nothing's left on it. */
    private const val DAILY = "daily"

    // Each its own purpose (IMP-P2-019), so a routine one can be silenced and a question kept.
    /** The morning's list, now Felix's message, on his channel: deleted once nothing's left on it. */
    const val BRIEFING = "briefing"
    /** "How was it?" after each completion, now Felix's question (LR-05): deleted once nothing's left on it. */
    private const val HOW_IT_WENT = "how-it-went"
    /** A move of yours in the calendar put back; its questions were here before they were Felix's. */
    const val CALENDAR = "calendar-questions"
    const val WEEKLY = "weekly"
    const val MODEL = "model"
    const val FREE_TIME = "free-time"
    const val FREE_TIME_ENDING = "free-time-ending"
    /** A timer on a piece (docs/plan_timer): its countdown, silent; and its "time's up", heard. */
    const val TIMER = "timer"
    const val TIMER_OVER = "timer-over"
    /** Everything Felix says (AI-24, LR-03): his one notification, made from the chat. */
    const val FELIX = "felix"

    /** Your sources (D-106): a write to Gmail that couldn't be made; outside the day's ceiling. */
    const val SOURCES = "sources"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // The two of the four that were one still made start as you'd set it (silenced, say): Android keeps a channel's
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
                NotificationChannel(CALENDAR, "Calendar", was).apply {
                    description = "A move of yours in the calendar that couldn't be kept"
                }.asWas(),
                NotificationChannel(WEEKLY, "Weekly check-in", was).apply {
                    description = "The Sunday check-in's reminder, where Felix can't ask it"
                }.asWas(),
            ),
        )
        // The old one goes once nothing's left on it: deleting a channel takes its notifications with
        // it (a calendar question, a "How was it?"), and nothing would post them again. Till then
        // it's kept, with nothing new on it, and a later start deletes it.
        if (manager.getNotificationChannel(DAILY) != null && manager.activeNotifications.none { it.notification.channelId == DAILY }) {
            manager.deleteNotificationChannel(DAILY)
        }
        // Felix asks how it went (LR-05): its channel goes, as the old one did, once nothing's left on it.
        if (manager.getNotificationChannel(HOW_IT_WENT) != null && manager.activeNotifications.none { it.notification.channelId == HOW_IT_WENT }) {
            manager.deleteNotificationChannel(HOW_IT_WENT)
        }
        // The morning is Felix's message, on his channel: the briefing's goes likewise.
        if (manager.getNotificationChannel(BRIEFING) != null && manager.activeNotifications.none { it.notification.channelId == BRIEFING }) {
            manager.deleteNotificationChannel(BRIEFING)
        }
        // Sessions are gone (AR-09): their channel, and a session's notification left on it, with them.
        if (manager.getNotificationChannel(SESSION) != null) manager.deleteNotificationChannel(SESSION)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(SYNC, "Reading your tasks", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown only on older Android versions, while a sync runs"
                },
                NotificationChannel(PROTECTION, "Protection", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When blocking is off: the focus service switched off, or protection weakened"
                },
                NotificationChannel(FELIX, "Felix", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Everything he says: the morning, his messages about your work, his questions and answers"
                },
                NotificationChannel(MODEL, "Claude", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When Claude's plan for a task is dropped, or its API key stops working"
                },
                NotificationChannel(SOURCES, "Your sources", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When an email finished here couldn't be unstarred or archived in Gmail"
                },
                // Silent, while it's spent (IMP-P2-011); and once, loud enough to see, a minute before it runs out.
                NotificationChannel(FREE_TIME, "Free time", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Your earned free time counting down while a blocked app uses it"
                },
                NotificationChannel(FREE_TIME_ENDING, "Free time running out", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A minute before your free time runs out and the app is covered"
                },
                // Silent while it counts down; heard once at its zero, whatever the hour, as you started it.
                NotificationChannel(TIMER, "Timer", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A timer you started on a piece of work, counting down"
                },
                NotificationChannel(TIMER_OVER, "Timer over", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a timer you started reaches its end"
                },
            ),
        )
    }
}
