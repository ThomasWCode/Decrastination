package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.TaskItem

/**
 * What Felix's service asks of Android (docs/plan_v4/architecture.md §Ports), so it's tested without it: his one
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

    /** The reminders' alarms at their times, as the Settings screen sets them ([DailyAlarms.schedule]). */
    fun scheduleReminders()

    /** The week's review, unless the week has had it ([ReviewWorker.enqueue]). */
    fun review()

    /** The answer to a reply from his notification, as work of its own ([FelixReplyWorker.enqueue]). */
    fun answerLater()

    /** Your answer to his question from his notification, read by Claude as work of its own ([FelixReplyWorker.enqueue]). */
    fun readLater()

    /** The reminder to open the week's check-in form, where he can't ask it himself. */
    fun remindForm()

    /** [task] opened where it lives, as its sheet's Open does ([TaskOpener.opened], G3): whether it was, and what to say. */
    suspend fun openSource(task: TaskItem): Pair<Boolean, String?>
}
