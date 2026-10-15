package com.thomaswcode.decrastination.notify

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
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R

/**
 * A reply from Felix's notification answered (AI-24) as work of its own: the receiver that took it
 * is let go at once, and the app may go with it, so the answer, which is paid for, would be lost
 * half made.
 */
class FelixReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        AppGraph.get(applicationContext).answerReply(id, inputData.getString(KEY_WORDS).orEmpty(), inputData.getLong(KEY_SINCE, 0L))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        NotificationCompat.Builder(applicationContext, Channels.SYNC)
            .setSmallIcon(R.drawable.ic_focus)
            .setContentTitle("Answering your reply")
            .setOngoing(true)
            .build(),
    )

    companion object {
        private const val NAME = "felix-reply"
        private const val KEY_ID = "id"
        private const val KEY_WORDS = "words"
        private const val KEY_SINCE = "since"
        private const val NOTIFICATION_ID = 1004

        /** The most of the reply kept to show under his message while he answers: the chat has it whole. */
        private const val MAX_WORDS = 1_000

        /** The reply [words] to his message [id], sent at [since], answered as soon as it can be; one after another. */
        fun enqueue(context: Context, id: String, words: String, since: Long) {
            val request = OneTimeWorkRequestBuilder<FelixReplyWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_ID to id, KEY_WORDS to words.take(MAX_WORDS), KEY_SINCE to since))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
