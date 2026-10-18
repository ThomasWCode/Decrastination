package com.thomaswcode.decrastination.felix

import android.content.Context
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
import com.thomaswcode.decrastination.notify.Notify

/**
 * A reply from Felix's notification answered (AI-24) as work of its own: the receiver that took it
 * is let go at once, and the app may go with it, so the answer, which is paid for, would be lost
 * half made. The reply is in the chat already, waiting for it.
 */
class FelixReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppGraph.get(applicationContext).conversation.answerReply()
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NoticeId.ReplyWork.id,
        Notify.builder(applicationContext, Channels.SYNC)
            .setContentTitle("Answering your reply")
            .setOngoing(true)
            .build(),
    )

    companion object {
        private const val NAME = "felix-reply"

        /** The reply waiting in the chat answered as soon as it can be; one after another. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FelixReplyWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
