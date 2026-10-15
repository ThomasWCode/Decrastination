package com.thomaswcode.decrastination.widget

import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.Groups
import java.time.ZoneId

/**
 * What the widget shows, worked out from the plan: pure, so its wording is tested without a
 * home screen. The widget only lays it out (UI-05: the slot under way or next with its time and a
 * tick; its badge and minutes; the "Then:" line; today's slots kept and the time left; ↻; a tap
 * opens the task).
 */
data class WidgetModel(
    /** "17:00–17:30 · Statics Prep", its slot's time first (UI-05); "Do: Statics Prep" with no slot; "Nothing due". */
    val headline: String,
    /** The piece's first step under it (AI-15's micro-plan, phase 7), where there's room; null without one. */
    val label: String?,
    val badge: String?,
    /** The badge is bad news: overdue, due today, behind. */
    val urgent: Boolean,
    val minutes: String?,
    val then: String?,
    /** "Today: 1 of 4 kept, 1 h 20 left" with slots today (UI-05); else "3 today · 1 tomorrow". */
    val summary: String,
    /** The rest of today's work (or tomorrow's, if today's is done), a line a task (Q31), for the largest size. */
    val list: List<Line>,
    /** How many more of the day's tasks there are than [list] holds: in the app. */
    val more: Int,
    /** What's wrong, worst first: a source that can't be read, Teams not synced for a day. */
    val warning: String?,
    /** What finishing today earns (BL-04): a line under the header. */
    val reward: String? = null,
    /** The task a tap opens, if any. */
    val taskId: String?,
    /** The piece the tick keeps (UI-05, LR-09), if any. */
    val key: String? = null,
    /** When the plan it shows was made: a tick keeps only what it showed (a box's next piece has its key). */
    val seenAt: Long = 0L,
) {
    /** A row of the list: [urgent] as the headline's badge is (overdue, due today, behind). */
    data class Line(val text: String, val minutes: String, val urgent: Boolean, val taskId: String)

    companion object {
        /** Teams data older than this is worth a warning: the widget syncs only when asked. */
        const val TEAMS_STALE_MS = 24 * 3_600_000L
        private const val LIST_MAX = 30

        /**
         * [runtime] and [armed] add the blocker's side: protection trouble comes first among the
         * warnings, and free time is shown. [slots]: today's on record, for the day's count.
         */
        fun from(
            plan: Plan,
            state: TaskState,
            zone: ZoneId,
            runtime: RuntimeState = RuntimeState(),
            armed: Boolean = false,
            reward: String? = null,
            slots: List<SlotRecord> = emptyList(),
        ): WidgetModel {
            val now = plan.now
            val next = plan.next
            // The slot that holds it, today's or tomorrow's, for its time (UI-05).
            val tomorrowSlots = plan.tomorrowBucket?.slots.orEmpty()
            val slot = next?.let { n -> (plan.todaySlots + tomorrowSlots).firstOrNull { it.kind == SlotKind.Work && it.chunk?.key == n.key } }
            val today = plan.todayBucket?.chunks.orEmpty()
            val tomorrow = plan.tomorrowBucket?.chunks.orEmpty()
            // After the next and the one after it, which have lines of their own; a task's pieces as one line (Q31).
            val rest = Groups.byTask(today.ifEmpty { tomorrow }.filterNot { it === next || it === plan.then })
            fun tasks(chunks: List<Chunk>) = chunks.map { it.taskId }.distinct().size
            return WidgetModel(
                headline = when {
                    next == null -> "Nothing due"
                    slot != null -> (if (tomorrowSlots.any { it === slot }) "Tomorrow " else "") + Format.span(slot.start, slot.end, zone) + " · " + next.label
                    else -> "Do: ${next.label}"
                },
                reward = reward,
                // A piece's first step comes with phase 7's micro-plans (AI-15).
                label = null,
                badge = next?.let { badge(it, now, zone) },
                urgent = next?.urgent == true,
                minutes = next?.let { Format.minutes(it.minutes) },
                then = plan.then?.let { "Then: ${it.label}" },
                summary = day(plan, slots) ?: when {
                    today.isEmpty() && tomorrow.isEmpty() -> runtime.credit.on(plan.today).leftMs.takeIf { it > 0 }
                        ?.let { "Nothing due soon · ${Format.minutes((it / 60_000L).toInt())} of free time" }
                        ?: "Nothing due today or tomorrow"
                    else -> listOfNotNull(
                        tasks(today).takeIf { it > 0 }?.let { "$it today" },
                        tasks(tomorrow).takeIf { it > 0 }?.let { "$it tomorrow" },
                    ).joinToString(" · ")
                },
                list = rest.take(LIST_MAX).map { row ->
                    val text = if (row.pieces.size == 1) row.first.label else "${row.first.title} · ${row.pieces.size} pieces"
                    Line(text, Format.minutes(row.minutes), row.urgent, row.taskId)
                },
                more = (rest.size - LIST_MAX).coerceAtLeast(0),
                warning = protection(runtime, armed) ?: warning(state, now),
                taskId = next?.taskId,
                key = next?.key,
                seenAt = now,
            )
        }

        /**
         * Today's slots as the header has them (UI-05): "Today: 1 of 4 kept, 1 h 20 left", the kept,
         * missed and still to come counted (one gone by but in its grace, still to tick, among them),
         * and the minutes of those still to come from now. Null with no slot today, kept, missed or
         * planned.
         */
        fun day(plan: Plan, slots: List<SlotRecord>): String? {
            val now = plan.now
            val ahead = plan.todaySlots.filter { it.kind == SlotKind.Work && it.chunk != null && it.end + com.thomaswcode.decrastination.core.Slotter.GRACE_MS > now }
            val kept = slots.count { it.outcome.kept }
            val total = kept + slots.count { it.outcome == SlotOutcome.Missed } + ahead.size
            if (total == 0) return null
            val left = ahead.sumOf { ((it.end - maxOf(it.start, now)).coerceAtLeast(0) / 60_000L).toInt() }
            return "Today: $kept of $total kept, " + if (left > 0) "${Format.minutes(left)} left" else "nothing left"
        }

        /** "From 04:00" (it can't be started yet), "Overdue", "Due 23:59" (today), "Behind", or when it's due. */
        fun badge(chunk: Chunk, now: Long, zone: ZoneId): String = when {
            !chunk.startable(now) -> "From " + Format.at(chunk.availableAt!!, now, zone).removePrefix("today ")
            chunk.overdue -> "Overdue"
            chunk.dueToday -> "Due " + Format.at(chunk.deadline, now, zone).removePrefix("today ")
            chunk.behind -> "Behind"
            chunk.dueAt == null -> "No deadline"
            else -> "Due " + Format.at(chunk.dueAt, now, zone)
        }

        /** The watchdog's finding, worst first: blocking off matters before anything else on the widget. */
        private fun protection(runtime: RuntimeState, armed: Boolean): String? =
            runtime.protection.problems.firstOrNull()?.let { if (armed) "PROTECTION OFF: $it" else it }

        private fun warning(state: TaskState, now: Long): String? {
            val failing = Source.SYNCED.firstOrNull { state.status(it).error != null }
            if (failing != null) return "Can't read ${failing.label}"
            return teamsWarning(state, now)
        }

        /** What's wrong with Teams' data, if anything: also the Plan's (IMP-P1-007). */
        fun teamsWarning(state: TaskState, now: Long): String? {
            // The Teams widget's own trouble (its sync service off, its last sync failed), which
            // also explains a ↻ that didn't sync Teams.
            state.status(Source.Teams).note?.let { return it }
            // Not read yet, or read but never synced by the Teams widget: nothing from Teams at all.
            val teamsAsOf = state.status(Source.Teams).dataAsOf ?: return "Teams hasn't synced yet"
            if (now - teamsAsOf > TEAMS_STALE_MS) return "Teams synced ${Format.ago(teamsAsOf, now)}"
            return null
        }
    }
}
