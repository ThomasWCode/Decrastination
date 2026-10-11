package com.thomaswcode.decrastination.block

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.MainActivity

/**
 * Earned free time being spent, said (IMP-P2-011): a silent notification counting it down while a
 * blocked app uses it, and one heads-up a minute before it runs out, so a video can be stopped
 * rather than covered mid-sentence. Both go with the spending; neither grants any time. **Back to
 * work** opens the plan, whose card starts the next piece.
 */
object FreeTimeNotice {
    private const val ID = 2003
    private const val ENDING_ID = 2004

    /** The heads-up comes this long before the end. */
    const val WARN_MS = 60_000L

    /** [what] is spending free time, which runs out at [endsAt] (the wall clock's face of it). */
    fun show(context: Context, what: String, endsAt: Long) {
        Notify.post(
            context,
            ID,
            NotificationCompat.Builder(context, Channels.FREE_TIME)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("Free time: $what")
                .setContentText("Covered again when it runs out.")
                .setWhen(endsAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(backToWork(context))
                .addAction(0, "Back to work", backToWork(context))
                .build(),
        )
    }

    /** A minute left: said once, where it'll be seen. */
    fun warn(context: Context, what: String) {
        Notify.post(
            context,
            ENDING_ID,
            NotificationCompat.Builder(context, Channels.FREE_TIME_ENDING)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("A minute of free time left")
                .setContentText("$what is covered again in a minute.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setTimeoutAfter(WARN_MS)
                .setAutoCancel(true)
                .setContentIntent(backToWork(context))
                .addAction(0, "Back to work", backToWork(context))
                .build(),
        )
    }

    /** Spending stopped: both go. */
    fun cancel(context: Context) {
        Notify.cancel(context, ID)
        Notify.cancel(context, ENDING_ID)
    }

    private fun backToWork(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        ID,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
