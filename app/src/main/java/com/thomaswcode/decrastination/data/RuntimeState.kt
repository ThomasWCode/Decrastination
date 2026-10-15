package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.block.Credit
import com.thomaswcode.decrastination.block.TeamsAutoSync
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.core.WindowKind
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
    /** Today's slot starts as the last plan laid them, and the slots missed (PL-13). */
    val slots: com.thomaswcode.decrastination.core.SlotState = com.thomaswcode.decrastination.core.SlotState(),
    /** Each slot event's start as the app last wrote it to the calendar, by its URI (NF-07): one since moved was moved by you. */
    val calendarWritten: Map<String, Long> = emptyMap(),
    /** The calendar those were written to, so a change of calendar takes them out of the old one. */
    val calendarWrittenTo: Long? = null,
    /** Calendars no longer written to whose events of the app's are still to be taken out (no permission, or a failure, the last time). */
    val calendarsToClear: List<Long> = emptyList(),
    /** Photo checks found done whose due (the task's step or minutes, the log, free time) isn't all given yet. */
    val photosDone: List<PhotoDone> = emptyList(),
    /** Slots being kept whose due (the task's step or minutes, the log, free time) isn't all given yet (LR-09). */
    val keeping: List<Keeping> = emptyList(),
    /** Felix's pace and what he's seen done (LR-03). */
    val felix: FelixState = FelixState(),
    /** The routine notifications gone today, by date (LR-07): only today's kept. */
    val notifiedToday: Map<String, Int> = emptyMap(),
    /** Those held back by the ceiling or quiet hours (LR-07), to go at the next briefing. */
    val heldNotifications: List<HeldNotification> = emptyList(),
    /** The decks' counts read as AnkiDroid came and went (NF-03): the last [com.thomaswcode.decrastination.learn.AnkiProgress.KEEP]. */
    val ankiReadings: List<com.thomaswcode.decrastination.learn.AnkiReading> = emptyList(),
    /** Since when AnkiDroid has been in front, read coming in; null when it isn't. */
    val ankiFrontSince: Long? = null,
    /** AnkiDroid time on deck tasks today not in a kept slot yet (NF-03): the visits before the one that finishes their cards. */
    val ankiWorked: AnkiWorked = AnkiWorked(),
    /** Tasks whose call the API wouldn't take as asked (AC-04), with the input it refused: sent again once that changes. */
    val enrichFailed: Map<String, String> = emptyMap(),
    /** Loosening changes waiting their delay. */
    val pending: List<PendingChange> = emptyList(),
    /** A parent code unblocked everything until then. */
    val overrideUntil: Long? = null,
    /** When each of the daily alarms (briefing, check-in, review) last ran, so a late one isn't run twice. */
    val dailyRanAt: Map<String, Long> = emptyMap(),
    /** Daily alarms whose last run failed, to be tried again ([DailyRetry]). */
    val dailyRetries: Map<String, DailyRetry> = emptyMap(),
    /** Whether the device admin was last left for an armed phone, so a disarm the app didn't see through is finished at start. */
    val adminArmed: Boolean = false,
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
    /**
     * Dropped-plan warnings shown: each task's id, and the content (its enrichment's input hash) of
     * the plan it was about. One not here (laid while notifications couldn't show it) is shown once
     * they can; a later plan dropped for changed content is another warning.
     */
    val droppedAlerted: Map<String, String> = emptyMap(),
    /** "How was it?" questions kept while notifications couldn't be seen, to ask once they can. */
    val assessLater: List<AssessLater> = emptyList(),
    /** Completions whose free time has been given, so each is given once (`Focus.onCompleted`); kept a fortnight. */
    val rewarded: List<Rewarded> = emptyList(),
    /** Saved files found unreadable at a start (IMP-P1-003), till you've seen them in Setup. */
    val unreadable: List<JsonStore.Unreadable> = emptyList(),
    /**
     * The copies set aside of those you've seen: one still found at a start (secrets left in place
     * for a keystore that may come back) isn't said again.
     */
    val unreadableSeen: List<String> = emptyList(),
) {
    /** With [found] (this start's) on Setup's list: each once, and none you've seen. */
    fun withUnreadable(found: List<JsonStore.Unreadable>): RuntimeState {
        val new = found.filterNot { f -> unreadable.any { it.file == f.file && it.copy == f.copy } || (f.copy != null && f.copy in unreadableSeen) }
        return copy(unreadable = (unreadable + new).takeLast(MAX_UNREADABLE))
    }

    /** [seen] off Setup's list, and not said again while its copy is what's found. */
    fun seenUnreadable(seen: JsonStore.Unreadable): RuntimeState =
        copy(unreadable = unreadable - seen, unreadableSeen = (unreadableSeen + listOfNotNull(seen.copy)).distinct().takeLast(MAX_UNREADABLE_SEEN))

    companion object {
        const val MAX_UNREADABLE = 10

        /** Three copies kept of each of six files, with room. */
        const val MAX_UNREADABLE_SEEN = 24
    }
}

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
 * What happened, for calibration, the weekly review and the stats (docs/scheduler.md §5): slots
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
    /** Felix's messages and the replies to them (AI-22, LR-03): the last [FELIX_KEEP_DAYS] days. */
    val felix: List<FelixRecord> = emptyList(),
) {
    fun trimmed(now: Long): ActivityLog {
        val since = now - KEEP_DAYS * 24 * 3_600_000L
        val sinceDay = java.time.Instant.ofEpochMilli(since).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        return ActivityLog(
            slots = slots.filter { it.at >= since },
            completions = completions.filter { it.doneAt >= since },
            blocks = blocks.filter { it.at >= since },
            protection = protection.filter { it.at >= since },
            days = days.filter { it.date >= sinceDay },
            checkIns = checkIns.filter { it.at >= since },
            reviews = reviews.filter { it.at >= since },
            felix = felix.filter { it.at >= now - FELIX_KEEP_DAYS * 24 * 3_600_000L },
        )
    }

    companion object {
        const val KEEP_DAYS = 120L

        /** His messages are many and matter for a few weeks: the review reads the last one's. */
        const val FELIX_KEEP_DAYS = 30L
    }
}

/**
 * A message of Felix's (AI-22, LR-03): when, why ([reason], a [com.thomaswcode.decrastination.enrich.FelixPrompts.Reason]),
 * what he said and the replies he offered, the piece ([key]) or task it was about, when he'd check
 * again, and the student's reply, when one came.
 */
@Serializable
data class FelixRecord(
    val id: String,
    val at: Long,
    val reason: String,
    val text: String,
    val replies: List<String> = emptyList(),
    val key: String? = null,
    val taskId: String? = null,
    val nextCheckAt: Long? = null,
    val replyText: String? = null,
    val repliedAt: Long? = null,
    /** The completions a Completions message asks about (LR-05): a reply of one of its three answers goes on each. */
    val asks: List<AssessLater> = emptyList(),
)

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
    /** Minutes worked on it: its kept slots' (focus sessions' before review v2). */
    val workedMin: Int,
    val dueAt: Long? = null,
    val firstSeenAt: Long,
    val doneAt: Long,
    /** Your answer to "how was it?": harder, as expected, easier (Phase 5). */
    val assessment: String? = null,
    /** And the line you added, if any. */
    val note: String? = null,
    /** Some of it ticked off by hand: its minutes aren't a measure of how long it took. */
    val byHand: Boolean = false,
    /** Some of it found done by a photo check: untimed, so its minutes aren't a measure of how long it took either. */
    val photo: Boolean = false,
    /**
     * Planned with your own minutes (an instruction's, or a task of your own): not the app's
     * estimate, so it teaches nothing about how far off the app's estimates are (2.0.0).
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
)

/** The facts remembered before a weekly tidy, and after it (AI-21). */
@Serializable
data class MemoryTidyRecord(val before: List<com.thomaswcode.decrastination.core.Fact>, val after: List<com.thomaswcode.decrastination.core.Fact>)

@Serializable
data class BlockRecord(val at: Long, val target: String, val reason: String)

@Serializable
data class ProtectionRecord(val at: Long, val problems: List<String>, val repaired: Boolean)

/** A completion that has had its free time: the task, and when it was confirmed done. */
@Serializable
data class Rewarded(val taskId: String, val doneAt: Long)

/** A piece a photo check found done: what it's owed, saved before any of it is given (`Focus.photoChecked`). */
@Serializable
data class PhotoDone(
    val id: String,
    val taskId: String,
    val step: String?,
    val minutes: Int,
    val at: Long,
    /** Its step's place in its task's list; null from before this was kept. */
    val stepIndex: Int? = null,
    /** The piece it was for ([com.thomaswcode.decrastination.core.Chunk.key], LR-09): its slot is kept by it. */
    val key: String? = null,
    /** When the photo was taken (the check began): a slot of its key kept before then is another's. */
    val takenAt: Long? = null,
)

/**
 * A slot being kept (LR-09), saved before anything changes, so a stop part-way is carried through at
 * start-up (`Focus.finishKeeps`): its [record]; its task's step ([stepIndex], still [step]), none for
 * a box of time, and the minutes worked on the task before ([workedBefore]), so the task's change
 * is made once; the free time it earns; the miss of it today it [replaces]; and the [photo] that
 * kept it, let go with it. [earnOnly]: a slot kept already, earning now (its photo passed): the
 * record it [replaces] marked, nothing done to the task.
 */
@Serializable
data class Keeping(
    val record: SlotRecord,
    val stepIndex: Int?,
    val step: String?,
    val workedBefore: Int,
    val earningMin: Double,
    val replaces: SlotRecord? = null,
    val photo: PhotoDone? = null,
    val earnOnly: Boolean = false,
)

/** Minutes in AnkiDroid on each deck task on [date] (NF-03), kept till the visit that finishes its cards. */
@Serializable
data class AnkiWorked(val date: String = "", val minutes: Map<String, Int> = emptyMap()) {
    /** As it stands on [date]: a new day starts empty. */
    fun on(date: String): AnkiWorked = if (this.date == date) this else AnkiWorked(date)

    fun plus(taskId: String, add: Int): AnkiWorked = copy(minutes = minutes + (taskId to (minutes[taskId] ?: 0) + add))

    fun without(taskId: String): AnkiWorked = copy(minutes = minutes - taskId)
}

/**
 * Felix between looks (LR-03): when he said he'd check again, when he last wrote, a message he wrote
 * ahead (AC-10), and when something was last seen done on each task (opened, studied in AnkiDroid).
 */
@Serializable
data class FelixState(
    val nextCheckAt: Long? = null,
    val lastSentAt: Long? = null,
    val draft: FelixDraft? = null,
    val activity: Map<String, Long> = emptyMap(),
    /** The model's questions about tasks waiting to be put (AI-10). */
    val questions: List<FelixQuestion> = emptyList(),
) {
    /** [taskIds] seen done on [at]: the last day's kept. */
    fun withActivity(taskIds: Collection<String>, at: Long): FelixState =
        copy(activity = (activity + taskIds.associateWith { at }).filterValues { it > at - 24 * 3_600_000L })

    /** [question] waiting about [taskId] (AI-10) in place of the one there was, where it stood (as it was, if the same); null, none. */
    fun withQuestion(taskId: String, question: String?, at: Long): FelixState {
        val was = questions.firstOrNull { it.taskId == taskId }
        val next = question?.let { q -> was?.takeIf { it.question == q } ?: FelixQuestion(taskId, q, at) }
        return copy(questions = if (was == null) questions + listOfNotNull(next) else questions.flatMap { if (it.taskId == taskId) listOfNotNull(next) else listOf(it) }.distinct())
    }
}

/** A question of the model's about [taskId]'s reading (AI-10), from [at]. */
@Serializable
data class FelixQuestion(val taskId: String, val question: String, val at: Long)

/** A message of Felix's written ahead (AC-10): sent if it still fits its reason and piece when the time comes. */
@Serializable
data class FelixDraft(val reason: String, val key: String?, val answer: com.thomaswcode.decrastination.enrich.FelixAnswer, val at: Long)

/** A notification held back (LR-07): where it was to go, what it said, and its tap and buttons ([taps]), to be made again with. */
@Serializable
data class HeldNotification(val channel: String, val id: Int, val tag: String? = null, val at: Long, val title: String, val text: String, val taps: List<HeldTap> = emptyList())

/**
 * A held notification's tap (no [label]) or a button of it: an activity of the app's to open, or
 * ([broadcast]) a receiver of its to send to, as [intent] (`Intent.toUri`) with [requestCode].
 */
@Serializable
data class HeldTap(val label: String? = null, val broadcast: Boolean = false, val requestCode: Int = 0, val intent: String)

/** A daily alarm's run that failed: tried again [at], this being try [tries] (`Daily.failed`). */
@Serializable
data class DailyRetry(val at: Long, val tries: Int)

/** A finished task's "how was it?", kept to ask once notifications can be seen. */
@Serializable
data class AssessLater(val taskId: String, val title: String, val doneAt: Long? = null)
