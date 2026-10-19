package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.protect.CodeLock
import com.thomaswcode.decrastination.protect.PendingChange
import kotlinx.serialization.Serializable

/** Protection's own state (docs/spec/blocking-and-protection.md): what waits, what's armed, what's overridden, the watchdog's finding. Kept in `protection.json`. */
@Serializable
data class ProtectionState(
    /** Loosening changes waiting their delay. */
    val pending: List<PendingChange> = emptyList(),
    /** A parent code unblocked everything until then. */
    val overrideUntil: Long? = null,
    /** Whether the device admin was last left for an armed phone, so a disarm the app didn't see through is finished at start. */
    val adminArmed: Boolean = false,
    val codeLock: CodeLock = CodeLock(),
    val protection: WatchdogState = WatchdogState(),
    /** Testing from a PC (adb only): blocking hours apply until then, whatever the time. */
    val forceActiveUntil: Long? = null,
    /** Up to when the pending changes' waits have been counted, by the uptime clock. */
    val uptimeMark: Uptime? = null,
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
