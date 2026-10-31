package com.thomaswcode.decrastination

import android.app.Application
import com.thomaswcode.decrastination.data.BackupWorker
import com.thomaswcode.decrastination.data.Crashes
import com.thomaswcode.decrastination.learn.DailyAlarms
import com.thomaswcode.decrastination.notify.AndroidChannels
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.protect.WatchdogWorker
import com.thomaswcode.decrastination.sync.SyncWorker

class DecrastinationApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The engine's log, before anything writes to it.
        Log.sink = AndroidLog
        // A crash written down before the app goes (AR-06), then handed on as it would have been.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Crashes.record(filesDir, thread, error, runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?")
            previous?.uncaughtException(thread, error)
        }
        // The channels first: the graph may show a waiting alert as it starts.
        AndroidChannels.create(this)
        AppGraph.get(this)
        SyncWorker.schedule(this)
        WatchdogWorker.schedule(this)
        BackupWorker.schedule(this)
        DailyAlarms.schedule(this)
    }
}
