package com.thomaswcode.decrastination.enrich

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.MainActivity

/** Claude's warnings (docs/spec/readings.md): a plan of its dropped, and its key no longer working. */
object ModelAlerts {
    /**
     * Claude's key or account can't be used ([problem]): said once a stretch of it, till it's put
     * right in Setup. False if it couldn't be shown (notifications off), so it's tried again.
     */
    fun keyProblem(context: Context, problem: KeyProblem): Boolean {
        val notifier = AppGraph.get(context).notifier
        if (!notifier.shown(Channels.MODEL)) return false
        notifier.post(
            NoticeId.ModelKey,
            Notify.builder(context, Channels.MODEL)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("Claude has stopped working")
                .setContentText(problem.says)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${problem.says}. ${problem.fix}. The rules stand in meanwhile."))
                .let { Notify.opening(it, context, NoticeId.ModelKey.code(), tab(context, MainActivity.OPEN_SETUP)) }
                .setAutoCancel(true)
                .build(),
        )
        return true
    }

    /** The key works again, or a new one is in: the alert is out of date. */
    fun keyFixed(context: Context) = AppGraph.get(context).notifier.cancel(NoticeId.ModelKey)

    /** [task]'s plan from the model dropped, [why]: its estimate is planned whole, so it's worth a look. */
    fun dropped(context: Context, task: TaskItem, why: String): Boolean {
        val notifier = AppGraph.get(context).notifier
        if (!notifier.shown(Channels.MODEL)) return false
        notifier.post(
            NoticeId.ModelDropped,
            Notify.builder(context, Channels.MODEL)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("Claude's plan was dropped")
                .setContentText("${task.title}: $why")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${task.title}: $why. It's planned as one piece meanwhile; Tasks shows it."))
                .let { Notify.opening(it, context, NoticeId.ModelDropped.code(), tab(context, MainActivity.TAB_TASKS)) }
                .setAutoCancel(true)
                .build(),
            // One a task, told apart by its id as the tag.
            task.id,
        )
        return true
    }

    /** The open tasks with a dropped plan standing (their enrichment current), and why. */
    fun standing(tasks: List<TaskItem>): List<Pair<TaskItem, String>> =
        tasks.mapNotNull { task -> Enrichments.current(task)?.dropped?.takeIf { task.isOpen && !task.hidden }?.let { task to it } }

    /** Of [standing], those whose warning hasn't been shown for the plan as it is ([shown]: task id to content hash). */
    fun unshown(standing: List<Pair<TaskItem, String>>, shown: Map<String, String>): List<Pair<TaskItem, String>> =
        standing.filter { (task, _) -> shown[task.id] != task.enrichment?.inputHash }

    /**
     * Withdraws the warnings whose task no longer has a dropped plan: done or gone (a sync, which the
     * enrichment loop never sees again), or changed or planned since; showing, or held back for the morning
     * (LR-07). Those still standing stay.
     */
    fun withdrawExcept(context: Context, standing: Set<String>) {
        val notifier = AppGraph.get(context).notifier
        (notifier.showing(NoticeId.ModelDropped) + notifier.heldTags(NoticeId.ModelDropped)).distinct()
            .filter { it != null && it !in standing }.forEach { notifier.cancel(NoticeId.ModelDropped, it) }
    }

    private fun tab(context: Context, tab: Int): Intent =
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Claude's warnings as the gateway gives them ([ModelGateway.Alerts]): [ModelAlerts]' notifications. */
class AndroidModelAlerts(private val context: Context) : ModelGateway.Alerts {
    override fun keyProblem(problem: KeyProblem): Boolean = ModelAlerts.keyProblem(context, problem)

    override fun keyFixed() = ModelAlerts.keyFixed(context)

    override fun dropped(task: TaskItem, why: String): Boolean = ModelAlerts.dropped(context, task, why)

    override fun withdrawExcept(standing: Set<String>) = ModelAlerts.withdrawExcept(context, standing)
}
