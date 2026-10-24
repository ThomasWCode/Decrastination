package com.thomaswcode.decrastination.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thomaswcode.decrastination.AppGraph
import java.util.concurrent.TimeUnit

/** The nightly backup (AR-07): once a day, all the app keeps written to Documents/Decrastination ([BackupService.backupNow]). */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Too big to restore, it's said in Settings → Backup, and not tried again: it would be as big.
        if (!BackupFolder.available || AppGraph.get(applicationContext).backups.backupNow() != BackupService.Nightly.NotWritten) return Result.success()
        // Not written (the storage busy, a write failed): tried again after WorkManager's wait, a few
        // times, then at the next day's.
        return if (runAttemptCount < RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val RETRIES = 3

        /** Once a day, kept as it is when it's there already: set at every start, beside the sync. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "backup",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).build(),
            )
        }
    }
}
