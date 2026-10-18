package com.thomaswcode.decrastination.block

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.MainActivity

/**
 * Earned free time being spent, said (IMP-P2-011): a silent notification counting it down while a
 * blocked app uses it, and one heads-up a minute before it runs out, so a video can be stopped
 * rather than covered mid-sentence. Both go with the spending; neither grants any time. **Back to
 * work** opens the plan, whose card starts the next piece.
 */
object FreeTimeNotice {
    /** The heads-up comes this long before the end. */
    const val WARN_MS = 60_000L

    /** [what] is spending free time, which runs out at [endsAt] (the wall clock's face of it). */
    fun show(context: Context, what: String, endsAt: Long) {
        AppGraph.get(context).notifier.post(
            NoticeId.FreeTime,
            Notify.builder(context, Channels.FREE_TIME)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("Free time: $what")
                .setContentText("Covered again when it runs out.")
                .setWhen(endsAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setSilent(true)
                // Shown again as its end moves: never a sound or a buzz for that.
                .setOnlyAlertOnce(true)
                .setContentIntent(backToWork(context, NoticeId.FreeTime))
                .addAction(0, "Back to work", backToWork(context, NoticeId.FreeTime))
                .build(),
        )
    }

    /** A minute left: said once, where it'll be seen. */
    fun warn(context: Context, what: String) {
        AppGraph.get(context).notifier.post(
            NoticeId.FreeTimeEnding,
            Notify.builder(context, Channels.FREE_TIME_ENDING)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("A minute of free time left")
                .setContentText("$what is covered again in a minute.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setTimeoutAfter(WARN_MS)
                .setAutoCancel(true)
                .setContentIntent(backToWork(context, NoticeId.FreeTimeEnding))
                .addAction(0, "Back to work", backToWork(context, NoticeId.FreeTimeEnding))
                .build(),
        )
    }

    /** Spending stopped: both go. */
    fun cancel(context: Context) {
        val notifier = AppGraph.get(context).notifier
        notifier.cancel(NoticeId.FreeTime)
        notifier.cancel(NoticeId.FreeTimeEnding)
    }

    /** The plan opened, as [notice]'s tap and its button. */
    private fun backToWork(context: Context, notice: NoticeId): PendingIntent = PendingIntent.getActivity(
        context,
        notice.code(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
