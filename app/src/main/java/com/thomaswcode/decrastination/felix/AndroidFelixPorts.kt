package com.thomaswcode.decrastination.felix

import android.content.Context
import android.content.Intent
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.block.SlotAlarms
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.learn.DailyAlarms
import com.thomaswcode.decrastination.learn.ReviewWorker
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.CheckInActivity
import com.thomaswcode.decrastination.ui.TaskOpener

/** [FelixPorts] on the phone. */
class AndroidFelixPorts(private val context: Context) : FelixPorts {
    override fun show(notice: Outbox.Notice) = FelixNotifications.show(context, notice)

    override fun cancel() = FelixNotifications.cancel(context)

    override fun armLook(at: Long?) = SlotAlarms.armFelix(context, at)

    override fun remindLater() = ReminderWorker.enqueue(context)

    override fun readAgain() = EnrichWorker.enqueue(context)

    override fun scheduleReminders() = DailyAlarms.schedule(context)

    override fun review() = ReviewWorker.enqueue(context, ifDue = true)

    override fun answerLater() = FelixReplyWorker.enqueue(context)

    override fun readLater() = FelixReplyWorker.enqueue(context, read = true)

    override suspend fun openSource(task: TaskItem): Pair<Boolean, String?> = TaskOpener.opened(context, task)

    override fun remindForm() {
        val graph = AppGraph.get(context)
        graph.notifier.post(
            NoticeId.CheckIn,
            Notify.builder(context, Channels.WEEKLY)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("Sunday check-in")
                .setContentText("Five quick questions about the week, before its review.")
                .let { Notify.opening(it, context, NoticeId.CheckIn.code(), Intent(context, CheckInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .setAutoCancel(true)
                .build(),
        )
    }
}
