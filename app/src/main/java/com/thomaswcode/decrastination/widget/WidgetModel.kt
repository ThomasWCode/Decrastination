package com.thomaswcode.decrastination.widget

import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.Groups
import java.time.ZoneId

/**
 * What the widget shows, worked out from the plan: pure, so its wording is tested without a
 * home screen. The widget only lays it out (docs/scheduler.md, PLAN.md Phase 2: "Do:" line,
 * due/behind badge, minutes, "then:" line, ↻; tap opens the task).
 */
data class WidgetModel(
    /** "Do: Statics Prep", or "Nothing due" when the plan is empty: for a size with no "DO NOW" label. */
    val headline: String,
    /** "Statics Prep" alone, under a "DO NOW" label; null when nothing's planned. */
    val label: String?,
    val badge: String?,
    /** The badge is bad news: overdue, due today, behind. */
    val urgent: Boolean,
    val minutes: String?,
    val then: String?,
    /** "3 due today · 1 tomorrow". */
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
) {
    /** A row of the list: [urgent] as the headline's badge is (overdue, due today, behind). */
    data class Line(val text: String, val minutes: String, val urgent: Boolean, val taskId: String)

    companion object {
        /** Teams data older than this is worth a warning: the widget syncs only when asked. */
        const val TEAMS_STALE_MS = 24 * 3_600_000L
        private const val LIST_MAX = 30

        /**
         * [runtime] and [armed] add the blocker's side: protection trouble comes first among the
         * warnings, and free time is shown.
         */
        fun from(plan: Plan, state: TaskState, zone: ZoneId, runtime: RuntimeState = RuntimeState(), armed: Boolean = false, reward: String? = null): WidgetModel {
            val now = plan.now
            val next = plan.next
            val today = plan.todayBucket?.chunks.orEmpty()
            val tomorrow = plan.tomorrowBucket?.chunks.orEmpty()
            // After the next and the one after it, which have lines of their own; a task's pieces as one line (Q31).
            val rest = Groups.byTask(today.ifEmpty { tomorrow }.filterNot { it === next || it === plan.then })
            fun tasks(chunks: List<Chunk>) = chunks.map { it.taskId }.distinct().size
            return WidgetModel(
                headline = next?.let { "Do: ${it.label}" } ?: "Nothing due",
                reward = reward,
                label = next?.label,
                badge = next?.let { badge(it, now, zone) },
                urgent = next?.urgent == true,
                minutes = next?.let { Format.minutes(it.minutes) },
                then = plan.then?.let { "Then: ${it.label}" },
                summary = when {
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
            )
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
