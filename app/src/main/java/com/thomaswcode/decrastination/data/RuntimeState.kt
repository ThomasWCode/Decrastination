package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.block.Credit
import com.thomaswcode.decrastination.block.FocusSession
import com.thomaswcode.decrastination.block.TeamsAutoSync
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.DayRecord
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
    /**
     * The blocked site each checked browser last showed in front (null: a page that isn't), kept
     * so a service started afresh can judge a page whose address bar is hidden (a video full
     * screen). A browser not in it hasn't been read.
     */
    val browserSites: Map<String, String?> = emptyMap(),
    val codeLock: CodeLock = CodeLock(),
    val teamsAuto: TeamsAutoSync.State = TeamsAutoSync.State(),
    val protection: ProtectionState = ProtectionState(),
    /** Testing from a PC (adb only): blocking hours apply until then, whatever the time. */
    val forceActiveUntil: Long? = null,
    /** Up to when the pending changes' waits have been counted, by the uptime clock. */
    val uptimeMark: Uptime? = null,
    /** What the model has cost this month. */
    val aiUsage: AiUsage = AiUsage(),
    /** What the app has learned about how long things take you (docs/scheduler.md §5). */
    val calibration: Calibration = Calibration(),
    /** Your answers about calendar events, by name: "free", "busy" or "load:<minutes>" (Q7). */
    val eventAnswers: Map<String, String> = emptyMap(),
    /** Events you've been asked about, by name, so each is asked once. */
    val eventsAsked: Set<String> = emptySet(),
    /** "How was it?" questions kept while notifications couldn't be seen, to ask once they can. */
    val assessLater: List<AssessLater> = emptyList(),
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
    /** Each day's plan as it stood in the morning, and how much of it got done. */
    val days: List<DayRecord> = emptyList(),
    val checkIns: List<CheckIn> = emptyList(),
    val reviews: List<WeeklyReview> = emptyList(),
) {
    fun trimmed(now: Long): ActivityLog {
        val since = now - KEEP_DAYS * 24 * 3_600_000L
        val sinceDay = java.time.Instant.ofEpochMilli(since).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        return ActivityLog(
            sessions = sessions.filter { it.startedAt >= since },
            completions = completions.filter { it.doneAt >= since },
            blocks = blocks.filter { it.at >= since },
            protection = protection.filter { it.at >= since },
            days = days.filter { it.date >= sinceDay },
            checkIns = checkIns.filter { it.at >= since },
            reviews = reviews.filter { it.at >= since },
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
    /** The box length its task was being cut into, for the box experiment; null for a step of its own. */
    val box: Int? = null,
    /** Work a photo check found done, not a timed session: counted as the day's work, not as a session. */
    val photo: Boolean = false,
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
    /** And the line you added, if any. */
    val note: String? = null,
)

/** The Sunday review: what changed, in a few lines, and who wrote it (the rules or the model). */
@Serializable
data class WeeklyReview(
    val at: Long,
    val lines: List<String>,
    val by: String,
    /** The week it reviewed (its Monday), so each week has one, whenever the check-in is moved to. */
    val week: String? = null,
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

/** A finished task's "how was it?", kept to ask once notifications can be seen. */
@Serializable
data class AssessLater(val taskId: String, val title: String, val doneAt: Long? = null)
