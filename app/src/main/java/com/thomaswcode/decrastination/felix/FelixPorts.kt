package com.thomaswcode.decrastination.felix

import android.content.Context
import android.content.Intent
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.block.SlotAlarms
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.ReviewWorker
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.CheckInActivity
import com.thomaswcode.decrastination.ui.TaskOpener

/**
 * What Felix's service asks of Android (docs/plan_v4/02 §Ports), so it's tested without it: his one
 * notification, and the work and alarms that outlive what asked for them. [AndroidFelixPorts] on the
 * phone.
 */
interface FelixPorts {
    /** His one notification, as the outbox makes it from the chat ([FelixNotifications.show]). */
    fun show(notice: Outbox.Notice)

    /** His notification taken away ([FelixNotifications.cancel]). */
    fun cancel()

    /** His next look at [at], or none, by alarm ([SlotAlarms.armFelix]): the phone may be asleep through the service's ticks. */
    fun armLook(at: Long?)

    /** Your reminders whose moment has come, his words for them asked for and then sent, as work of its own ([ReminderWorker.enqueue]). */
    fun remindLater()

    /** The tasks read again now, as a job ([EnrichWorker.enqueue]). */
    fun readAgain()

    /** The reminders' alarms at their times, as the Settings screen sets them ([Daily.schedule]). */
    fun scheduleReminders()

    /** The week's review, unless the week has had it ([ReviewWorker.enqueue]). */
    fun review()

    /** The answer to a reply from his notification, as work of its own ([FelixReplyWorker.enqueue]). */
    fun answerLater()

    /** The reminder to open the week's check-in form, where he can't ask it himself. */
    fun remindForm()

    /** [task] opened where it lives, as its sheet's Open does ([TaskOpener.opened], G3): whether it was, and what to say. */
    suspend fun openSource(task: TaskItem): Pair<Boolean, String?>
}

/** [FelixPorts] on the phone. */
class AndroidFelixPorts(private val context: Context) : FelixPorts {
    override fun show(notice: Outbox.Notice) = FelixNotifications.show(context, notice)

    override fun cancel() = FelixNotifications.cancel(context)

    override fun armLook(at: Long?) = SlotAlarms.armFelix(context, at)

    override fun remindLater() = ReminderWorker.enqueue(context)

    override fun readAgain() = EnrichWorker.enqueue(context)

    override fun scheduleReminders() = Daily.schedule(context)

    override fun review() = ReviewWorker.enqueue(context, ifDue = true)

    override fun answerLater() = FelixReplyWorker.enqueue(context)

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
