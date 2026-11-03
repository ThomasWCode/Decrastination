package com.thomaswcode.decrastination.sync

import android.content.Context
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
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import java.util.concurrent.TimeUnit

/**
 * Sends the stores to the server ([Replica]): as work of its own, as Android 15 cuts an app's network seconds after it leaves
 * the screen. Within a minute of a change, so a run of changes is one send ([soon]), at once as the app comes to the front
 * ([now]), and at once when the server says it's changed ([told]); the 15-minute sync sends too.
 */
class ReplicaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val replica = AppGraph.get(applicationContext).replica
        val told = inputData.getLong(KEY_REVISION, NOT_TOLD)
        if (told == NOT_TOLD) replica.upload() else replica.told(told)
        return Result.success()
    }

    /** Only asked for when an expedited job has to run as a foreground service: before Android 12. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = Notify.builder(applicationContext, Channels.SYNC)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle("Sending to the website")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NoticeId.Sync.id, notification)
    }

    companion object {
        private const val SOON = "replica-soon"
        private const val NOW = "replica-now"
        private const val TOLD = "replica-told"
        private const val KEY_REVISION = "revision"
        private const val NOT_TOLD = -1L
        private const val COALESCE_S = 60L

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Within a minute: one already waiting takes this change with it. */
        fun soon(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReplicaWorker>().setConstraints(online).setInitialDelay(COALESCE_S, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
        }

        /** As soon as it can go. */
        fun now(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReplicaWorker>().setConstraints(online).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * The server's said it's changed, at [revision] (W-18): as soon as it can go, and after any exchange on its way, which may
         * have pulled before the change, so it isn't kept to that one. Expedited, as a high-priority message lets a job start at once.
         */
        fun told(context: Context, revision: Long) {
            val request = OneTimeWorkRequestBuilder<ReplicaWorker>().setConstraints(online).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_REVISION to revision)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(TOLD, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
