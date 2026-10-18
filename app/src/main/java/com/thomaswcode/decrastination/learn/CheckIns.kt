package com.thomaswcode.decrastination.learn

import android.content.Context
import android.content.Intent
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.CheckInActivity

/** The Sunday check-in's reminder (docs/spec/learning.md, item 6). */
object CheckIns {
    /**
     * The week's check-in begun (LR-02): Felix asks its first question in his notification, the rest
     * as each is answered ([AppGraph.checkInReply]); the form, This week, takes them all at once.
     * The reminder to open the form, where his notifications can't be shown.
     */
    suspend fun remind(context: Context) {
        val graph = AppGraph.get(context)
        if (graph.askCheckIn()) return
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
