package com.thomaswcode.decrastination.felix

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.SlotAlarms
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify

/**
 * Your reminders whose moment has come and whose words are to be asked for afresh ([Looks.remindNow]), as work of its
 * own: the call can take longer than the alarm's receiver or a plan's hooks are given, and the app may go once
 * they're let go. Then they're sent, in his words or, where none came, yours, and the alarm set for the next.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val sent = runCatching { graph.looks.remindNow() }.onFailure { Log.w(AppGraph.TAG, "Sending a reminder in his words failed", it) }.isSuccess
        // Failed, it's tried again a while on, not at once: its moment's gone by, so its alarm would ring straight away.
        runCatching { graph.armReminder(after = if (sent) null else graph.clock.now() + SlotAlarms.REMINDER_RETRY_MS) }
            .onFailure { Log.w(AppGraph.TAG, "Setting the reminders' alarm failed", it) }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NoticeId.ReminderWork.id,
        Notify.builder(applicationContext, Channels.SYNC)
            .setContentTitle("Writing your reminder")
            .setOngoing(true)
            .build(),
    )

    companion object {
        private const val NAME = "felix-reminder"

        /** Your reminders' words asked for and sent as soon as they can be; one after another. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReminderWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
