package com.thomaswcode.decrastination.sync

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.notify.Channels
import java.util.concurrent.TimeUnit

/**
 * Reads the sources: every 15 minutes, WorkManager's shortest period (PLAN.md §4), and at once
 * when asked ([syncNow]). No network constraint: Teams and Anki are read on the phone itself, and
 * a source that needs the network and hasn't got it just records the failure until the next run.
 *
 * Every sync not started from a screen in use goes through here. Since Android 15 an app with
 * nothing in the foreground loses its network a few seconds after leaving it, and its open
 * sockets are cut ("Software caused connection abort", seen on the phone on 8 Oct with Gmail's
 * IMAP); a running job keeps its network.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val only = inputData.getString(KEY_SOURCES)?.split(",")?.mapNotNull { name -> Source.entries.firstOrNull { it.name == name } }?.toSet()
        val report = graph.syncer.sync(only)
        Log.i(
            AppGraph.TAG,
            "Sync${only?.let { " of ${it.joinToString()}" }.orEmpty()}: ${report.added.size} added, ${report.completed.size} completed, " +
                "${report.reopened.size} reopened, ${report.missed.size} missed; failures ${report.failures}",
        )
        return Result.success()
    }

    /** Only asked for when an expedited job has to run as a foreground service: before Android 12. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Channels.SYNC)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle("Reading your tasks")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val PERIODIC = "sync-periodic"
        const val NOW = "sync-now"
        private const val KEY_SOURCES = "sources"
        private const val NOTIFICATION_ID = 1001

        /** Keeps the existing schedule if there is one, so starting the app doesn't reset its clock. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Reads [sources] (all, if null) as soon as possible, after any sync already asked for. */
        fun syncNow(context: Context, sources: Set<Source>? = null) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_SOURCES to sources?.joinToString(",") { it.name }))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
