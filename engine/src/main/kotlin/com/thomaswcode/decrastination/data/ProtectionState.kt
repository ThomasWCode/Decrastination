package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.protect.CodeLock
import com.thomaswcode.decrastination.protect.PendingChange
import kotlinx.serialization.Serializable

/** Protection's state wherever it's asked for (docs/spec/blocking-and-protection.md): what waits and what's overridden. Kept in `protection.json`. */
@Serializable
data class ProtectionState(
    /** Loosening changes waiting their delay. */
    val pending: List<PendingChange> = emptyList(),
    /** A parent code unblocked everything until then. */
    val overrideUntil: Long? = null,
    val codeLock: CodeLock = CodeLock(),
    /** Up to when the pending changes' waits have been counted, by the uptime clock. */
    val uptimeMark: Uptime? = null,
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 2,
)
