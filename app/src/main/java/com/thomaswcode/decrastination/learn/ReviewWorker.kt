package com.thomaswcode.decrastination.learn

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.notify.Channels

/**
 * The week's review as a job: with Claude on it waits on a model call, which can take longer than
 * an alarm's receiver is let run, and outlive the check-in screen that asked.
 */
class ReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Review.run(applicationContext, ifDue = inputData.getBoolean(KEY_IF_DUE, true))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Channels.SYNC)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle("Reviewing the week")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NAME = "review"
        private const val KEY_IF_DUE = "ifDue"
        private const val NOTIFICATION_ID = 1003

        /** The review now; [ifDue], only if none has run since Sunday's check-in. One after another. */
        fun enqueue(context: Context, ifDue: Boolean) {
            val request = OneTimeWorkRequestBuilder<ReviewWorker>()
                .setInputData(workDataOf(KEY_IF_DUE to ifDue))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
