package com.thomaswcode.decrastination.felix

import android.content.Context
import android.content.Intent
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.block.SlotAlarms
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.ReviewWorker
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.CheckInActivity

/**
 * What Felix's service asks of Android (docs/plan_v4/02 §Ports), so it's tested without it: his
 * notification, and the work and alarms that outlive what asked for them. [AndroidFelixPorts] on the
 * phone.
 */
interface FelixPorts {
    /** His message [text], [id], as his notification ([FelixNotifications.post]): its [replies], [cards], and the reply just [sent] to it. */
    fun post(id: String, name: String, text: String, replies: List<String>, cards: List<Pair<String, Proposal>> = emptyList(), sent: String? = null, title: String? = null, channel: String = Channels.FELIX)

    /** His notification taken away ([FelixNotifications.cancel]). */
    fun cancel()

    /** His next look at [at], by alarm ([SlotAlarms.armFelix]): the phone may be asleep through the service's ticks. */
    fun armLook(at: Long)

    /** The tasks read again now, as a job ([EnrichWorker.enqueue]). */
    fun readAgain()

    /** The reminders' alarms at their times, as the Settings screen sets them ([Daily.schedule]). */
    fun scheduleReminders()

    /** The week's review, unless the week has had it ([ReviewWorker.enqueue]). */
    fun review()

    /** The answer to a reply from his notification, as work of its own ([FelixReplyWorker.enqueue]). */
    fun answerLater(id: String, asked: String, words: String)

    /** The reminder to open the week's check-in form, where he can't ask it himself. */
    fun remindForm()
}

/** [FelixPorts] on the phone. */
class AndroidFelixPorts(private val context: Context) : FelixPorts {
    override fun post(id: String, name: String, text: String, replies: List<String>, cards: List<Pair<String, Proposal>>, sent: String?, title: String?, channel: String) =
        FelixNotifications.post(context, id, name, text, replies, cards, sent, title, channel = channel)

    override fun cancel() = FelixNotifications.cancel(context)

    override fun armLook(at: Long) = SlotAlarms.armFelix(context, at)

    override fun readAgain() = EnrichWorker.enqueue(context)

    override fun scheduleReminders() = Daily.schedule(context)

    override fun review() = ReviewWorker.enqueue(context, ifDue = true)

    override fun answerLater(id: String, asked: String, words: String) = FelixReplyWorker.enqueue(context, id, asked, words)

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
