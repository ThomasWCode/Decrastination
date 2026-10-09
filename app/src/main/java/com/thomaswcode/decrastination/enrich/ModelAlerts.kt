package com.thomaswcode.decrastination.enrich

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.MainActivity

/** Claude's warnings (docs/data-sources.md §5): a plan of its dropped, and its key no longer working. */
object ModelAlerts {
    /** One a task, told apart by its id as the tag. */
    private const val DROPPED_ID = 5001
    private const val KEY_ID = 5002

    /** Claude's key or account can't be used ([problem]): said once a stretch of it, till it's put right in Setup. */
    fun keyProblem(context: Context, problem: KeyProblem) {
        Notify.post(
            context,
            KEY_ID,
            NotificationCompat.Builder(context, Channels.MODEL)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("Claude has stopped working")
                .setContentText(problem.says)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${problem.says}. ${problem.fix}. The rules stand in meanwhile."))
                .setContentIntent(open(context, KEY_ID, MainActivity.TAB_SETUP))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The key works again, or a new one is in: the alert is out of date. */
    fun keyFixed(context: Context) = Notify.cancel(context, KEY_ID)

    /** [task]'s plan from the model dropped, [why]: its estimate is planned whole, so it's worth a look. */
    fun dropped(context: Context, task: TaskItem, why: String) {
        Notify.post(
            context,
            task.id,
            DROPPED_ID,
            NotificationCompat.Builder(context, Channels.MODEL)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("Claude's plan was dropped")
                .setContentText("${task.title}: $why")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${task.title}: $why. It's planned as one piece meanwhile; Tasks shows it."))
                .setContentIntent(open(context, DROPPED_ID, MainActivity.TAB_TASKS))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A plan for [taskId] kept since: the warning about the last one is out of date. */
    fun planKept(context: Context, taskId: String) = Notify.cancel(context, taskId, DROPPED_ID)

    private fun open(context: Context, id: Int, tab: Int): PendingIntent = PendingIntent.getActivity(
        context,
        id,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
