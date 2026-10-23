package com.thomaswcode.decrastination.felix

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify

/**
 * A reply from Felix's notification answered (AI-24) as work of its own: the receiver that took it
 * is let go at once, and the app may go with it, so the answer, which is paid for, would be lost
 * half made. The reply is in the chat already, waiting for it; his answer, or why there's none yet,
 * is shown in his notification as it's kept ([Conversation.answerChat]). Your answer to his question
 * about a task is read the same way ([KEY_READ]): an instruction on it, read by Claude to judge whether
 * it answers it ([com.thomaswcode.decrastination.tasks.Instructions.readInstructions]).
 */
class FelixReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        // You're waiting on it, as on an answer: through a rest (AC-04).
        if (inputData.getBoolean(KEY_READ, false)) graph.instructions.readInstructions(interactive = true) else graph.conversation.answerChat()
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
        private const val KEY_READ = "read"

        /** The reply waiting in the chat answered, or with [read] your answer to his question read, as soon as it can be; one after another. */
        fun enqueue(context: Context, read: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<FelixReplyWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_READ to read))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
