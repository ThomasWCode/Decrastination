package com.thomaswcode.decrastination

import android.app.Application
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.protect.WatchdogWorker
import com.thomaswcode.decrastination.sync.SyncWorker

class DecrastinationApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.get(this)
        Channels.create(this)
        SyncWorker.schedule(this)
        WatchdogWorker.schedule(this)
        Daily.schedule(this)
    }
}
