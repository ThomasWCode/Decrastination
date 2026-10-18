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
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId

/**
 * Felix's look (LR-03) from an alarm, as work of its own: the receiver the alarm woke is let go at
 * once, and the app may go with it, taking a message half asked and his next alarm with it.
 */
class FelixWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { AppGraph.get(applicationContext).looks.check() }.onFailure { Log.w(AppGraph.TAG, "Felix's look failed", it) }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NoticeId.FelixWork.id,
        com.thomaswcode.decrastination.notify.Notify.builder(applicationContext, Channels.SYNC)
            .setContentTitle("Looking at the plan")
            .setOngoing(true)
            .build(),
    )

    companion object {
        private const val NAME = "felix-look"

        /** A look, after any under way: a moment there may be something to say. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FelixWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
