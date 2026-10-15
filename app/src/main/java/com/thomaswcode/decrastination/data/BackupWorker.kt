package com.thomaswcode.decrastination.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thomaswcode.decrastination.AppGraph
import java.util.concurrent.TimeUnit

/** The nightly backup (AR-07): once a day, all the app keeps written to Documents/Decrastination ([AppGraph.backupNow]). */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppGraph.get(applicationContext).backupNow()
        return Result.success()
    }

    companion object {
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
