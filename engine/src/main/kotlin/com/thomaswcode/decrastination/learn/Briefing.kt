package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.Looks
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.Sync
import com.thomaswcode.decrastination.ui.Format

/**
 * The morning briefing, at 07:00 on a school day by the school calendar and 08:30 on any other (D-74): Felix's message on the
 * day's shape on a day with slots (LR-04), or the day's plan in his name in the app's words, in the
 * chat and his notification, made again with what waited overnight; a Teams sync offered at the next
 * unlock; and the day's plan recorded for the capacity check, with yesterday's marked done or not.
 */
class Briefing(
    private val planning: Planning,
    private val log: JsonStore<ActivityLog>,
    private val runtime: JsonStore<RuntimeState>,
    private val school: JsonStore<SchoolCalendar>,
    private val clock: WallClock,
    private val notices: Notices,
    private val sync: Sync,
    private val looks: Looks,
    private val conversation: Conversation,
) {
    /**
     * Today's record made again ([Days.replan]) after an answer about an event changed the plan.
     * A day the briefing hasn't recorded yet has none to change: it's recorded as it is then.
     */
    suspend fun replanToday() {
        val now = clock.now()
        val zone = clock.zone()
        val plan = planning.plan()
        val record = Days.record(plan, zone, SchoolDays.kindOf(plan.today, school.value))
        val ahead = record.chunks
        log.update { log ->
            val old = log.days.firstOrNull { it.date == plan.today.toString() && it.full == null } ?: return@update log
            log.copy(days = log.days.map { if (it === old) Days.replan(old, ahead, log, now, zone, record.schoolMin, record.kind) else it })
        }
    }

    suspend fun run() {
        // What the night held back (LR-07), first thing.
        runCatching { notices.postHeld() }.onFailure { Log.w(Log.TAG, "Posting what was held back failed", it) }
        // The calendar read first: woken by its alarm, the app has had no time to read it yet.
        sync.refreshCalendar()
        val now = clock.now()
        val zone = clock.zone()
        val plan = planning.plan()
        val today = Days.record(plan, zone, SchoolDays.kindOf(plan.today, school.value))
        log.update { log ->
            val finished = log.days.map { day -> if (day.full == null && day.date < today.date) Days.finish(day, log, zone) else day }
            log.copy(days = finished.filterNot { it.date == today.date } + today).trimmed(now)
        }
        runtime.update { it.copy(teamsAuto = it.teamsAuto.copy(morningUntil = now + MORNING_OFFER_MS)) }
        val (title, lines) = summary(plan)
        // Felix's sentence or two on the day in place of the list (LR-04); without it, the list
        // in his name, in the app's words: every morning is in the chat.
        val his = runCatching { looks.morning(now) }.onFailure { Log.w(Log.TAG, "Felix's morning message failed", it) }.getOrDefault(false)
        // Either way his notification's made again with it, and with what waited overnight for the
        // morning (LR-07), quiet hours that outlast the briefing or not ([Outbox.notice]).
        if (!his) conversation.morningList(title, lines)
    }

    companion object {
        /** How long after the briefing an unlock still brings the morning's Teams sync. */
        private const val MORNING_OFFER_MS = 90 * 60_000L

        /** The morning's list for [plan], its title and lines, where he has no words for it: pure, for the tests. */
        fun summary(plan: Plan): Pair<String, List<String>> {
            val today = plan.todayBucket?.chunks.orEmpty()
            if (today.isEmpty()) {
                val tomorrow = plan.tomorrowBucket?.chunks.orEmpty()
                return "nothing planned today" to listOfNotNull(tomorrow.takeIf { it.isNotEmpty() }?.let { "tomorrow: ${it.size} thing${if (it.size == 1) "" else "s"}, ${Format.minutes(it.sumOf { c -> c.minutes })}" })
            }
            val title = "today: ${today.size} thing${if (today.size == 1) "" else "s"}, ${Format.minutes(today.sumOf { it.minutes })}"
            val lines = today.take(6).map { chunk ->
                val flag = when {
                    chunk.overdue -> " (overdue)"
                    chunk.dueToday -> " (due today)"
                    chunk.behind -> " (behind)"
                    else -> ""
                }
                "${chunk.label}$flag · ${Format.minutes(chunk.minutes)}"
            } + listOfNotNull((today.size - 6).takeIf { it > 0 }?.let { "and $it more" })
            return title to lines
        }
    }
}
