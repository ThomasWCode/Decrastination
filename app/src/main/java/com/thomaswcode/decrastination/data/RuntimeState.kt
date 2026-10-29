package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.block.TeamsAutoSync
import com.thomaswcode.decrastination.core.Duplicates
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.DayRecord
import com.thomaswcode.decrastination.learn.ReviewInput
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

/**
 * What's left of the app's state from moment to moment once each owner has its own file: the
 * notifier's counts and held list, the daily runs, the last backup, Teams' syncs, duplicates told apart,
 * the files found unreadable. Kept in `runtime.json`.
 */
@Serializable
data class RuntimeState(
    /** The routine notifications gone today, by date (LR-07): only today's kept. */
    val notifiedToday: Map<String, Int> = emptyMap(),
    /** Those held back by the ceiling or quiet hours (LR-07), to go at the next briefing. */
    val heldNotifications: List<HeldNotification> = emptyList(),
    /** When each of the daily alarms (briefing, check-in, review) last ran, so a late one isn't run twice. */
    val dailyRanAt: Map<String, Long> = emptyMap(),
    /** Daily alarms whose last run failed, to be tried again ([DailyRetry]). */
    val dailyRetries: Map<String, DailyRetry> = emptyMap(),
    val teamsAuto: TeamsAutoSync.State = TeamsAutoSync.State(),
    /** When the nightly backup was last written (AR-07): null before the first. */
    val lastBackupAt: Long? = null,
    /** Pairs of tasks you've said aren't the same work (DX-15), by [com.thomaswcode.decrastination.core.Duplicates.key]: not asked about again. */
    val notSame: List<String> = emptyList(),
    /** Saved files found unreadable at a start (IMP-P1-003), till you've seen them in Setup. */
    val unreadable: List<JsonStore.Unreadable> = emptyList(),
    /**
     * The copies set aside of those you've seen: one still found at a start (secrets left in place
     * for a keystore that may come back) isn't said again.
     */
    val unreadableSeen: List<String> = emptyList(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 3,
) {
    /** With [found] (this start's) on Setup's list: each once, and none you've seen. */
    fun withUnreadable(found: List<JsonStore.Unreadable>): RuntimeState {
        val new = found.filterNot { f -> unreadable.any { it.file == f.file && it.copy == f.copy } || (f.copy != null && f.copy in unreadableSeen) }
        return copy(unreadable = (unreadable + new).takeLast(MAX_UNREADABLE))
    }

    /** [seen] off Setup's list, and not said again while its copy is what's found. */
    fun seenUnreadable(seen: JsonStore.Unreadable): RuntimeState =
        copy(unreadable = unreadable - seen, unreadableSeen = (unreadableSeen + listOfNotNull(seen.copy)).distinct().takeLast(MAX_UNREADABLE_SEEN))

    /** Tasks [a] and [b] said not to be the same work (DX-15): not asked about again, the newest [MAX_NOT_SAME] pairs kept. */
    fun withNotSame(a: String, b: String): RuntimeState = copy(notSame = (notSame + Duplicates.key(a, b)).distinct().takeLast(MAX_NOT_SAME))

    companion object {
        /** Unreadable files Setup lists at most: the latest. */
        const val MAX_UNREADABLE = 10

        /** The most pairs said to be different kept: far more than there are. */
        const val MAX_NOT_SAME = 200

        /** Three copies kept of each of six files, with room. */
        const val MAX_UNREADABLE_SEEN = 24
    }
}

/**
 * What happened, for calibration, the weekly review and the stats (docs/spec/learning.md): slots
 * kept and missed, completions, blocks and protection lapses. Kept in `log.json`, trimmed to
 * [KEEP_DAYS] days.
 */
@Serializable
data class ActivityLog(
    /** Each slot's outcome (LR-09): the record the days, the calibration and the stats read. */
    val slots: List<SlotRecord> = emptyList(),
    val completions: List<CompletionRecord> = emptyList(),
    val blocks: List<BlockRecord> = emptyList(),
    val protection: List<ProtectionRecord> = emptyList(),
    /** Each day's plan as it stood in the morning, and how much of it got done. */
    val days: List<DayRecord> = emptyList(),
    val checkIns: List<CheckIn> = emptyList(),
    val reviews: List<WeeklyReview> = emptyList(),
    /** Each reviewed week's totals (PL-10), kept [WEEKS_KEPT] weeks, past the [KEEP_DAYS] the rest is kept: Stats' longer periods read them. */
    val weeks: List<WeekRollup> = emptyList(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 4,
) {
    fun trimmed(now: Long): ActivityLog {
        val since = now - KEEP_DAYS * 24 * 3_600_000L
        val sinceDay = Instant.ofEpochMilli(since).atZone(ZoneOffset.UTC).toLocalDate().toString()
        return ActivityLog(
            slots = slots.filter { it.at >= since },
            completions = completions.filter { it.doneAt >= since },
            blocks = blocks.filter { it.at >= since },
            protection = protection.filter { it.at >= since },
            days = days.filter { it.date >= sinceDay },
            checkIns = checkIns.filter { it.at >= since },
            reviews = reviews.filter { it.at >= since },
            weeks = weeks.sortedBy { it.week }.takeLast(WEEKS_KEPT),
            version = version,
        )
    }

    /** What's here that [copied] doesn't hold, record for record: what came after a reset's copy was made, which the reset keeps. */
    fun since(copied: ActivityLog): ActivityLog = ActivityLog(
        slots = slots - copied.slots.toSet(),
        completions = completions - copied.completions.toSet(),
        blocks = blocks - copied.blocks.toSet(),
        protection = protection - copied.protection.toSet(),
        days = days - copied.days.toSet(),
        checkIns = checkIns - copied.checkIns.toSet(),
        reviews = reviews - copied.reviews.toSet(),
        weeks = weeks - copied.weeks.toSet(),
        version = version,
    )

    companion object {
        const val KEEP_DAYS = 120L

        /** Two years of weeks. */
        const val WEEKS_KEPT = 104
    }
}

/** How a slot went (LR-09): kept in its time or after its grace, missed, or moved on purpose. */
@Serializable
enum class SlotOutcome {
    Kept,
    KeptLate,
    Missed,
    Moved,
    ;

    /** Done, in its time or after. */
    val kept: Boolean get() = this == Kept || this == KeptLate
}

/**
 * A slot's outcome (LR-09): which piece ([key], its task's), when it was laid ([start] to [end], in
 * a [window]), how it went, and what was worked: the planned minutes and any more you said. [earned]
 * when it earned its free time (a short piece on its tick, a long one with its photo, BL-08); [at]
 * when it was recorded. A piece ticked with no slot of its own is recorded at the tick.
 */
@Serializable
data class SlotRecord(
    val date: String,
    val key: String,
    val taskId: String,
    val kind: Kind,
    val className: String? = null,
    val label: String,
    val plannedMin: Int,
    val start: Long,
    val end: Long,
    val window: WindowKind,
    val outcome: SlotOutcome,
    val workedMin: Int,
    val photo: Boolean = false,
    val earned: Boolean = false,
    /**
     * What its keep put in the day's ledger of free time, its cap within the day's plan and its rate then: what taking
     * it back gives back (BL-08). Nothing on another day (yesterday's free time went at midnight); null for one kept
     * before this was kept.
     */
    val earnedMin: Double? = null,
    val at: Long,
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
    /** Minutes worked on it: its kept slots'. */
    val workedMin: Int,
    val dueAt: Long? = null,
    val firstSeenAt: Long,
    val doneAt: Long,
    /** Your answer to "how was it?": harder, as expected, easier. */
    val assessment: String? = null,
    /** And the line you added, if any. */
    val note: String? = null,
    /** Some of it ticked off by hand: its minutes aren't a measure of how long it took. */
    val byHand: Boolean = false,
    /** Some of it found done by a photo check: untimed, so its minutes aren't a measure of how long it took either. */
    val photo: Boolean = false,
    /**
     * Planned with your own minutes (an instruction's, or a task of your own): not the app's
     * estimate, so it teaches nothing about how far off the app's estimates are.
     */
    val yourEstimate: Boolean = false,
    /** [dueAt] was a real deadline, not a date the app or Claude set (PL-14): only these count as on time or late. Records from before were. */
    val hardDeadline: Boolean = true,
)

/** The Sunday review: what changed, in a few lines, and who wrote it (the rules or the model). */
@Serializable
data class WeeklyReview(
    val at: Long,
    val lines: List<String>,
    val by: String,
    /** The week it reviewed (its Monday), so each week has one, whenever the check-in is moved to. */
    val week: String? = null,
    /** Why the rules wrote it, not Claude: off, resting, at its cap, or no answer that could be used. */
    val why: String? = null,
    /** What's remembered before and after the week's tidy, when there was one (AI-21): for the Reviews page to show, and undo. */
    val memoryTidy: MemoryTidyRecord? = null,
    /** The changes it made to the settings (AI-18, LR-01), and each one's value before: next week's review reads them, and the Reviews page undoes them. */
    val changes: List<ReviewInput.Change> = emptyList(),
    val settingsBefore: Map<String, Int> = emptyMap(),
    /** The week's summary for home (NF-02), about the student in the third person (D-66): null till it's written. */
    val summary: String? = null,
)

/**
 * A week's totals (PL-10), written at its review: its kept slots' minutes, what was finished (and of
 * that with a real deadline, how much by it), the blocks, the days whose plan was all done, and the
 * slots kept and missed. [week] is its Monday.
 */
@Serializable
data class WeekRollup(
    val week: String,
    val minutes: Int,
    val finished: Int,
    val onTime: Int,
    val dated: Int = 0,
    val blocks: Int,
    val fullDays: Int,
    val slotsKept: Int,
    val slotsMissed: Int,
)

/** The facts remembered before a weekly tidy, and after it (AI-21). */
@Serializable
data class MemoryTidyRecord(val before: List<Fact>, val after: List<Fact>)

@Serializable
data class BlockRecord(val at: Long, val target: String, val reason: String)

@Serializable
data class ProtectionRecord(val at: Long, val problems: List<String>, val repaired: Boolean)

/** A notification held back (LR-07): where it was to go, what it said, and its tap and buttons ([taps]), to be made again with. */
@Serializable
data class HeldNotification(val channel: String, val id: Int, val tag: String? = null, val at: Long, val title: String, val text: String, val taps: List<HeldTap> = emptyList())

/**
 * A held notification's tap (no [label]) or a button of it: an activity of the app's to open, or
 * ([broadcast]) a receiver of its to send to, as [intent] (`Intent.toUri`) with [requestCode].
 */
@Serializable
data class HeldTap(val label: String? = null, val broadcast: Boolean = false, val requestCode: Int = 0, val intent: String, val reply: HeldReply? = null)

/** A button's reply box (Felix's, AI-24): its key, what it says, and the replies it offers; its intent mutable, to take the words. */
@Serializable
data class HeldReply(val key: String, val label: String, val choices: List<String> = emptyList())

/** A daily alarm's run that failed: tried again [at], this being try [tries] (`Daily.failed`). */
@Serializable
data class DailyRetry(val at: Long, val tries: Int)
