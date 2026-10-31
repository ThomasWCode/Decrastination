package com.thomaswcode.decrastination.data

import kotlinx.serialization.Serializable

/** What's only true of this phone: its device admin, the watchdog's finding, blocking forced from a PC. Kept in `device.json`, never backed up. */
@Serializable
data class DeviceState(
    /** Whether the device admin was last left for an armed phone, so a disarm the app didn't see through is finished at start. */
    val adminArmed: Boolean = false,
    val watchdog: WatchdogState = WatchdogState(),
    /** Testing from a PC (adb only): blocking hours apply until then, whatever the time. */
    val forceActiveUntil: Long? = null,
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 1,
)

/** The watchdog's last finding. */
@Serializable
data class WatchdogState(
    val problems: List<String> = emptyList(),
    /** Since when something has been wrong; null while all is well. */
    val offSince: Long? = null,
    val checkedAt: Long? = null,
    /** Since when the service has been switched on but not running (crashed). */
    val stoppedSince: Long? = null,
    /** When the watchdog last switched a stopped service off and on. */
    val restartedAt: Long? = null,
    /** Restarts in a row that it hasn't stayed up after; back to 0 once it's seen running. */
    val restartTries: Int = 0,
)
