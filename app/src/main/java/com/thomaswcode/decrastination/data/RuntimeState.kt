package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.block.Credit
import com.thomaswcode.decrastination.block.FocusSession
import com.thomaswcode.decrastination.block.TeamsAutoSync
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.protect.CodeLock
import com.thomaswcode.decrastination.protect.PendingChange
import kotlinx.serialization.Serializable

/** What the blocker and its protection keep from moment to moment. Kept in `runtime.json`. */
@Serializable
data class RuntimeState(
    val credit: Credit = Credit(),
    val session: FocusSession? = null,
    /** Sessions ended whose due (the task's minutes and step, the log, free time) isn't all given yet. */
    val finishing: List<EndedSession> = emptyList(),
    /** Loosening changes waiting their delay. */
    val pending: List<PendingChange> = emptyList(),
    /** A parent code unblocked everything until then. */
    val overrideUntil: Long? = null,
    /** Whether the device admin was last left for an armed phone, so a disarm the app didn't see through is finished at start. */
    val adminArmed: Boolean = false,
    val codeLock: CodeLock = CodeLock(),
    val teamsAuto: TeamsAutoSync.State = TeamsAutoSync.State(),
    val protection: ProtectionState = ProtectionState(),
    /** Testing from a PC (adb only): blocking hours apply until then, whatever the time. */
    val forceActiveUntil: Long? = null,
    /** Up to when the pending changes' waits have been counted, by the uptime clock. */
    val uptimeMark: Uptime? = null,
    /** Completions whose free time has been given, so each is given once (`Focus.onCompleted`); kept a fortnight. */
    val rewarded: List<Rewarded> = emptyList(),
)

/** The watchdog's last finding. */
@Serializable
data class ProtectionState(
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

/**
 * What happened, for calibration, the weekly review and the stats (docs/scheduler.md §5): focus
 * sessions, completions, blocks and protection lapses. Kept in `log.json`, trimmed to
 * [KEEP_DAYS] days.
 */
@Serializable
data class ActivityLog(
    val sessions: List<SessionRecord> = emptyList(),
    val completions: List<CompletionRecord> = emptyList(),
    val blocks: List<BlockRecord> = emptyList(),
    val protection: List<ProtectionRecord> = emptyList(),
) {
    fun trimmed(now: Long): ActivityLog {
        val since = now - KEEP_DAYS * 24 * 3_600_000L
        return ActivityLog(
            sessions.filter { it.startedAt >= since },
            completions.filter { it.doneAt >= since },
            blocks.filter { it.at >= since },
            protection.filter { it.at >= since },
        )
    }

    companion object {
        const val KEEP_DAYS = 120L
    }
}

@Serializable
data class SessionRecord(
    val taskId: String,
    val kind: Kind,
    val className: String? = null,
    val label: String,
    val plannedMin: Int,
    val workedMin: Int,
    val startedAt: Long,
    val endedAt: Long,
    /** Ran its full length. */
    val completed: Boolean,
)

@Serializable
data class CompletionRecord(
    val taskId: String,
    val title: String,
    val source: Source,
    val kind: Kind,
    val className: String? = null,
    /** The estimate it was planned with, before calibration. */
    val estimateMin: Int,
    /** Minutes of focus sessions on it. */
    val workedMin: Int,
    val dueAt: Long? = null,
    val firstSeenAt: Long,
    val doneAt: Long,
    /** Your answer to "how was it?": harder, as expected, easier (Phase 5). */
    val assessment: String? = null,
)

@Serializable
data class BlockRecord(val at: Long, val target: String, val reason: String)

@Serializable
data class ProtectionRecord(val at: Long, val problems: List<String>, val repaired: Boolean)

/** A completion that has had its free time: the task, and when it was confirmed done. */
@Serializable
data class Rewarded(val taskId: String, val doneAt: Long)

/** A focus session as it ended: what it's owed, saved with its ending (`Focus.stopSession`). */
@Serializable
data class EndedSession(val session: FocusSession, val workedMin: Int, val completed: Boolean, val endedAt: Long)
