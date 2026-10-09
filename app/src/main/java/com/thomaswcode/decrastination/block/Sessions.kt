package com.thomaswcode.decrastination.block

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Focus sessions from Android's side: starting and stopping them through [Focus], the ongoing
 * notification that counts one down (with Stop), and its end. The focus service ends a session
 * on time while it runs; an alarm is the backstop when it isn't.
 */
object Sessions {
    private const val NOTIFICATION_ID = 2001
    private const val DONE_ID = 2002
    const val ACTION_END = "com.thomaswcode.decrastination.action.SESSION_END"
    const val ACTION_STOP = "com.thomaswcode.decrastination.action.SESSION_STOP"

    suspend fun start(context: Context, taskId: String, label: String, step: String?, minutes: Int) {
        val session = AppGraph.get(context).focus.startSession(taskId, label, step, minutes)
        showOngoing(context, session)
        scheduleEnd(context, session)
    }

    /** Ends the session if it's due (or now, with [early]); says how it went. Safe to call twice. */
    suspend fun end(context: Context, early: Boolean) {
        val focus = AppGraph.get(context).focus
        val session = focus.session ?: return clear(context)
        if (!early && AppGraph.get(context).clock.now() < session.endsAt) return
        val record = focus.stopSession() ?: return clear(context)
        clear(context)
        val text = if (record.completed) {
            val earned = Credit.forSession(record.plannedMin, AppGraph.get(context).settings.value.workMinPerFreeMin).toInt()
            "${record.label}: ${record.workedMin} min done, $earned min of free time earned"
        } else {
            "${record.label}: stopped after ${record.workedMin} min"
        }
        notify(
            context,
            DONE_ID,
            NotificationCompat.Builder(context, Channels.SESSION)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle(if (record.completed) "Session done" else "Session stopped")
                .setContentText(text)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun showOngoing(context: Context, session: FocusSession) {
        val stop = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, SessionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(
            context,
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, Channels.SESSION)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("Focus: ${session.label}")
                .setContentText("${session.minutes} min. Blocked apps stay blocked until it ends.")
                .setWhen(session.endsAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setContentIntent(openApp(context))
                .addAction(0, "Stop", stop)
                .build(),
        )
    }

    private fun clear(context: Context) {
        Notify.cancel(context, NOTIFICATION_ID)
        context.getSystemService(AlarmManager::class.java)?.cancel(endIntent(context))
    }

    /** The backstop: an inexact alarm a little after the end, should the focus service not be running. */
    private fun scheduleEnd(context: Context, session: FocusSession) {
        context.getSystemService(AlarmManager::class.java)
            ?.setWindow(AlarmManager.RTC_WAKEUP, session.endsAt, 60_000L, endIntent(context))
    }

    private fun endIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, SessionReceiver::class.java).setAction(ACTION_END),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(context: Context, id: Int, notification: android.app.Notification) = Notify.post(context, id, notification)
}

/** The session's alarm and its notification's Stop. */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Sessions.end(context, early = intent.action == Sessions.ACTION_STOP)
            } finally {
                pending.finish()
            }
        }
    }
}
