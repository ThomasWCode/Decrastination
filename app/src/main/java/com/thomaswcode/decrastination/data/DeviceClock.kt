package com.thomaswcode.decrastination.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.thomaswcode.decrastination.core.SystemWallClock
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.core.WallClock
import java.time.ZoneId

/** The phone's clocks: the wall clock, and the uptime clock that setting the date can't move. */
class DeviceClock(context: Context) : WallClock {
    private val resolver = context.applicationContext.contentResolver

    override fun now(): Long = SystemWallClock.now()

    override fun zone(): ZoneId = SystemWallClock.zone()

    override fun uptime(): Uptime = Uptime(
        boot = runCatching { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1),
        elapsedMs = SystemClock.elapsedRealtime(),
    )
}
