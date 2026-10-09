package com.thomaswcode.decrastination.enrich

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.notify.Channels
import java.util.concurrent.TimeUnit

/**
 * Enriches what's new or changed ([AppGraph.enrichNow]) as a job: the model's calls need the
 * network, which Android 15+ takes from an app seconds after it leaves the screen.
 */
class EnrichWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        // Queued for the rules alone (no connection asked for): the model isn't asked, even if
        // it's been switched on since; switching it on queues a job of its own that waits online.
        graph.enrichNow(if (inputData.getBoolean(KEY_MODEL, false)) graph.modelEnricher() else null)
        // Instructions waiting for Claude, as its rest after a failed call ends.
        if (inputData.getBoolean(KEY_MODEL, false)) graph.readInstructions()
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Channels.SYNC)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle("Reading your tasks")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NAME = "enrich"
        private const val RETRY = "enrich-retry"
        private const val KEY_MODEL = "model"
        private const val NOTIFICATION_ID = 1002

        /** After each sync, and when the model is switched on or given its key; one run after another. */
        fun enqueue(context: Context) {
            // With the model to ask, it waits for a connection: offline, a call would fail and rest
            // the model for an hour. Without it, the rules need none.
            val online = AppGraph.get(context).modelAvailable()
            val request = OneTimeWorkRequestBuilder<EnrichWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_MODEL to online))
                .apply { if (online) setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * The model tried again after [delayMs], the rest after a failed call: online, on what the
         * rules stand in for. A later failure moves it on.
         */
        fun retryAfter(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<EnrichWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MODEL to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
