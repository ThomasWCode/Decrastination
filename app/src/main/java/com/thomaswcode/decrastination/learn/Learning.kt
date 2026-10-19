package com.thomaswcode.decrastination.learn

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SettingsLimits
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.MemoryTidyRecord
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.ClaudeReviewer
import com.thomaswcode.decrastination.enrich.MemoryTidier
import com.thomaswcode.decrastination.enrich.MemoryTidy
import com.thomaswcode.decrastination.enrich.ModelCalls
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.enrich.SummaryWriter
import com.thomaswcode.decrastination.protect.Protection
import java.time.LocalDate

/**
 * What the app learns from the log (docs/spec/learning.md; docs/plan_v4, Stage 6e), moved out of
 * `Review`, `AppGraph` and `Assessment`: the week's review ([review]: the calibration learned afresh,
 * the capacity checked, Claude's note and bounded changes, what's remembered tidied); a figure
 * forgotten ([forgetCalibration]); a review's change or tidy put back ([undoReviewChange],
 * [undoTidy]); the week's summary for home ([writeSummary]); and the week's check-in and a
 * completion's answer on record ([recordCheckIn], [recordAssessment]), whoever asked them. Built
 * like [Focus]: its stores, the clock, the gateway, the focus, protection
 * and small callbacks; the pure parts are [Review]'s, [Calibrator]'s, [Stats]' and [Summary]'s.
 */
class Learning(
    private val log: JsonStore<ActivityLog>,
    private val runtime: JsonStore<RuntimeState>,
    private val settings: JsonStore<Settings>,
    private val memory: JsonStore<MemoryState>,
    /** The chat, read for what the week's review and summary are told of it. */
    private val chat: JsonStore<ChatState>,
    /** Your instructions, read for the changes in use the summary tells of. */
    private val instructions: JsonStore<InstructionState>,
    private val clock: WallClock,
    private val gateway: ModelGateway,
    private val focus: Focus,
    /** Settings changed: the review's at once, armed or not; a change of its put back as one of yours. */
    private val protection: Protection,
    /** How many resets of the record there have been ([com.thomaswcode.decrastination.data.BackupService.resets]): one while a review's out lets what it makes go (LR-08). */
    private val resets: () -> Int,
    /** Who the student is at a time, with what's remembered, as each call sends it (AI-05). */
    private val profile: (now: Long, remembered: MemoryState) -> Profile.Block,
    /** The review's first line in the chat, in Felix's name ([com.thomaswcode.decrastination.felix.Conversation.reviewed]). */
    private val reviewed: suspend (String) -> Unit,
    /** His offer to send the week's summary ([com.thomaswcode.decrastination.felix.Conversation.summaryReady]). */
    private val summaryReady: suspend (String) -> Unit,
) {
    /**
     * The weekly review (docs/spec/learning.md, item 7), unless [ifDue] and the week has had it: on
     * Sunday evening, after the check-in (or without it), the calibration is learned afresh from the
     * log, the capacity checked, and, once Claude is on, the week goes to the model for a note and
     * bounded changes, which apply at once, armed or not. Until then the note is the rules': what
     * changed, in words. Then what's remembered is tidied, once it's past 70 % of its room (AI-21).
     */
    suspend fun review(ifDue: Boolean) {
        val now = clock.now()
        // Once a week: the check-in runs it at once, and the evening's alarm finds it done since
        // Sunday's check-in time (a review at any other time doesn't count). The latest Sunday at or
        // before now, so an alarm after midnight still finds Sunday's.
        val zone = clock.zone()
        val week = Daily.checkInWeek(now, zone, settings.value.checkInMin)
        if (ifDue && Review.reviewed(log.value.reviews, week, Daily.lastCheckIn(now, zone, settings.value.checkInMin))) return
        // A reset of the record while this is out (LR-08) lets what it makes go.
        val generation = resets()
        val learned = Calibrator.learn(
            log.value,
            runtime.value.calibration,
            today = focus.today(now),
            zone = zone,
            defaultMargin = settings.value.marginDays,
        )
        runtime.update { it.copy(calibration = learned.calibration) }
        val findings = learned.changes + listOfNotNull(Days.capacityAdvice(log.value.days, focus.today(now)))
        val model = runCatching { modelReview(now, week, generation) }
            .onFailure { Log.w(AppGraph.TAG, "The model's weekly review failed; the rules' stands", it) }
            .getOrElse { Outcome(why = "Claude's call failed") }
        val tidy = runCatching { tidyMemory(now) }
            .onFailure { Log.w(AppGraph.TAG, "Tidying what's remembered failed; it's left as it was", it) }
            .getOrNull()
        // The rules' findings always stand: the model's note comes first, and they follow; then the tidy's line.
        val review = (
            model.review?.let { it.copy(lines = it.lines + findings) }
                ?: WeeklyReview(now, findings.ifEmpty { listOf("Nothing to change this week: the estimates held.") }, by = "rules", why = model.why)
            ).let { it.copy(week = week, lines = it.lines + listOfNotNull(tidy?.let(Review::tidyLine)), memoryTidy = tidy) }
        // The week's totals (PL-10), kept past its days; those of the weeks before it the log still
        // holds whole made again with them, so what came in after their reviews is in them.
        val rollup = Stats.rollup(log.value, LocalDate.parse(week), zone)
        var kept = false
        log.update { l ->
            if (resets() != generation) l
            else l.copy(reviews = l.reviews + review, weeks = Stats.refreshedWeeks(l, now, zone).filterNot { w -> w.week == week } + rollup).trimmed(now).also { kept = true }
        }
        if (!kept) {
            Log.i(AppGraph.TAG, "The record was reset while the week's review was out: it isn't kept")
            return
        }
        // Its note's first line in the chat, in Felix's name, with the button that opens the review.
        reviewed(review.lines.first())
        // The week's summary for home (NF-02), after its review: its own call, its failure the review's no matter.
        runCatching { writeSummary(week) }.onFailure { Log.w(AppGraph.TAG, "The week's summary failed", it) }
    }

    /** The model's review, or why there's none (the rules' stands then, saying so). */
    private class Outcome(val review: WeeklyReview? = null, val why: String? = null)

    /**
     * The model's review, while Claude is on and under its cap: a note and bounded changes, the
     * changes applied through [com.thomaswcode.decrastination.protect.Protection.applyReviewChanges]. Otherwise why it couldn't be had.
     */
    private suspend fun modelReview(now: Long, week: String, generation: Int): Outcome {
        val reviewer = gateway.client(ModelEffort.Job.Review)?.let { ClaudeReviewer(it, profile = profile(clock.now(), memory.value)) } ?: return Outcome(why = because(ModelHold.Off))
        val zone = clock.zone()
        val input = ReviewInput.describe(log.value, runtime.value.calibration, settings.value, now, zone, week, chat.value.messages)
        // Its room taken with every other model call's, then sent (AC-05). A failure is recorded
        // (Setup shows it; the model rests), then the rules' review stands.
        val current = gateway.client(ModelEffort.Job.Review)?.let { ClaudeReviewer(it, profile = profile(clock.now(), memory.value)) } ?: return Outcome(why = because(ModelHold.Off))
        val result = when (val called = gateway.call(interactive = false, record = ModelCalls.OnRecord(ModelEffort.Job.Review, "the week") { r: ClaudeReviewer.Result -> r.usage to ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: ClaudeReviewer.Result -> r.costUsd to r.refused }) { current.review(input) }) {
            is Called.Held -> return Outcome(why = because(called.hold))
            is Called.Failed -> throw called.error
            is Called.Answered -> called.value
        }
        val answer = result.answer ?: return Outcome(why = if (result.refused) "Claude declined to review it" else "Claude's answer couldn't be used")
        val asked = settings.value
        val changes = answer.changes.filter { ReviewInput.allowed(it, asked) }
        // What each was, kept with the review (AI-18, LR-01): next week's reads it, and its page undoes it.
        val before = changes.mapNotNull { c -> ReviewInput.valueOf(asked, c.setting)?.let { c.setting to it } }.toMap()
        // Not after a reset of the record that came while Claude was asked (LR-08): the review's let go.
        if (resets() != generation) return Outcome(why = "the record was reset while Claude was asked")
        // At once, armed or not (your call, 11 Oct); laid over what's been asked for, so a change
        // waiting for another setting keeps its wait.
        if (changes.isNotEmpty()) protection.applyReviewChanges(changes)
        val note = answer.note.ifEmpty { listOf("No note this week.") }
        return Outcome(WeeklyReview(now, note + Review.changeLines(changes, settings.value), by = result.model ?: reviewer.model, changes = changes, settingsBefore = before))
    }

    /**
     * What's remembered, tidied by the model once its facts are past [MemoryTidy.DUE_SHARE] of their
     * room (AI-21), or when [force]d (a PC's `memory-tidy`): its own call, after the review's. The
     * before and after, or null: not due, the model held or failed (recorded as any call is), an
     * answer that isn't a tidy, or the facts changed while it was out.
     */
    suspend fun tidyMemory(now: Long, force: Boolean = false): MemoryTidyRecord? {
        val before = memory.value.facts
        if (before.isEmpty() || !(force || MemoryTidy.due(before))) return null
        // The profile without the facts, which it's given to tidy.
        val tidier = gateway.client(ModelEffort.Job.Tidy)?.let { MemoryTidier(it, profile = profile(clock.now(), memory.value.copy(facts = emptyList()))) } ?: return null
        val called = gateway.call(interactive = false, record = ModelCalls.OnRecord(ModelEffort.Job.Tidy, "what's remembered") { r: MemoryTidier.Result -> r.usage to ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: MemoryTidier.Result -> r.costUsd to r.refused }) { at -> tidier.tidy(before, at, clock.zone()) }
        val answer = (called as? Called.Answered)?.value?.answer ?: return null
        val after = MemoryTidy.after(answer, before, now) ?: return null
        var applied = false
        memory.update { state -> MemoryTidy.applied(state, before, after)?.also { applied = true } ?: state }
        return if (applied) MemoryTidyRecord(before, after) else null
    }

    /** Why the model didn't review the week, in words. */
    private fun because(hold: ModelHold): String = when (hold) {
        ModelHold.Off -> "Claude was off"
        ModelHold.Resting -> "Claude was resting after a failed call"
        ModelHold.Capped -> "Claude's monthly cap was reached"
    }

    /**
     * What's been learned about [key] (`Kind|class`) forgotten (LR-06): its figure goes now, the
     * kind's standing for it, and its work so far teaches nothing at the reviews after.
     */
    suspend fun forgetCalibration(key: String) {
        val now = clock.now()
        runtime.update { s -> s.calibration.let { c -> s.copy(calibration = c.copy(multipliers = c.multipliers - key, timed = c.timed - key, forgotten = c.forgotten + (key to now))) } }
    }

    /**
     * [change], one of [review]'s, put back (LR-01): its setting to its value before, as a change of
     * yours (once armed, one that loosens waits as yours do), while it's still what the review left.
     * What happened, in words.
     */
    suspend fun undoReviewChange(review: WeeklyReview, change: ReviewInput.Change): String {
        val before = review.settingsBefore[change.setting] ?: return "What it was before isn't known."
        if (ReviewInput.valueOf(settings.value, change.setting) != change.value) return "It's been changed since: left as it is."
        // One end of a school day's hours back alone can meet the other: then it can't be.
        if (!SettingsLimits.window(ReviewInput.set(settings.value, change.setting, before).weekdayHours, overnight = false)) return "It can't go back on its own: the day's hours would end before they start. Change them in Settings."
        protection.changeSettings { ReviewInput.set(it, change.setting, before) }
        val now = ReviewInput.valueOf(settings.value, change.setting)
        val label = ReviewInput.label(change.setting)
        return if (now == before) "$label is back to ${ReviewInput.valueText(change.setting, before)}." else "$label back to ${ReviewInput.valueText(change.setting, before)} waits, as a change that loosens does once armed."
    }

    /** [review]'s tidy of what's remembered undone (LR-01): its facts as they were, while they're still as it left them. What happened, in words. */
    suspend fun undoTidy(review: WeeklyReview): String {
        val tidy = review.memoryTidy ?: return "That review tidied nothing."
        var done = false
        memory.update { state -> if (state.facts == tidy.after) state.copy(facts = tidy.before).also { done = true } else state }
        return if (done) "What's remembered is back as it was before the tidy." else "What's remembered has changed since: left as it is."
    }

    /**
     * The [week]'s summary for home (NF-02), written by the model after its review and kept with it;
     * then Felix's offer to send it, in the chat with the button that opens it in an email. Nothing
     * while Claude can't be asked, or the week has no review. The summary, or null.
     */
    suspend fun writeSummary(week: String): String? {
        val client = gateway.client(ModelEffort.Job.Summary) ?: return null
        if (log.value.reviews.none { it.week == week }) return null
        val zone = clock.zone()
        val input = Summary.input(log.value, instructions.value.applied, week, zone, chat.value.messages)
        val writer = SummaryWriter(client)
        val called = gateway.call(
            interactive = false,
            record = ModelCalls.OnRecord(ModelEffort.Job.Summary, "the week's summary") { r: SummaryWriter.Result -> r.usage to ModelCalls.outcome(r.refused, r.text == null) },
            cost = { r: SummaryWriter.Result -> r.costUsd to r.refused },
        ) { at -> writer.write(profile(at, memory.value).text, input) }
        val text = (called as? Called.Answered)?.value?.text ?: return null
        log.update { l -> l.copy(reviews = l.reviews.map { if (it.week == week) it.copy(summary = text) else it }) }
        // His offer in the chat, held from his notification in quiet hours till the morning (LR-07).
        summaryReady(week)
        return text
    }

    /**
     * [checkIn], the week's (from Felix's five questions in the chat, or the check-in's form), kept once a
     * week: one for a week that has its check-in already (a second tap, another screen) is let go. Whether
     * it was kept.
     */
    suspend fun recordCheckIn(checkIn: CheckIn, now: Long = clock.now()): Boolean {
        var added = false
        log.update { l -> withCheckIn(l, checkIn, now)?.also { added = true } ?: l }
        return added
    }

    /** Records [answer] (and [note]) on the completion of [taskId] done at [doneAt] (0: its latest, for older questions). */
    suspend fun recordAssessment(taskId: String, doneAt: Long, answer: String, note: String? = null) {
        log.update { Assessment.answered(it, taskId, doneAt, answer, note) }
    }

    companion object {
        /** [log] with [checkIn] kept, trimmed at [now]; or null where its week has its check-in already. Pure. */
        fun withCheckIn(log: ActivityLog, checkIn: CheckIn, now: Long): ActivityLog? =
            if (log.checkIns.any { it.weekOf == checkIn.weekOf }) null else log.copy(checkIns = log.checkIns + checkIn).trimmed(now)
    }
}
