package com.thomaswcode.decrastination.widget

import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.ui.Format
import java.time.ZoneId

/**
 * What the widget shows, worked out from the plan: pure, so its wording is tested without a
 * home screen. The widget only lays it out (docs/scheduler.md, PLAN.md Phase 2: "Do:" line,
 * due/behind badge, minutes, "then:" line, ↻; tap opens the task).
 */
data class WidgetModel(
    /** "Do: Statics Prep", or "Nothing due" when the plan is empty. */
    val headline: String,
    val badge: String?,
    /** The badge is bad news: overdue, due today, behind. */
    val urgent: Boolean,
    val minutes: String?,
    val then: String?,
    /** "3 due today · 1 tomorrow". */
    val summary: String,
    /** The rest of today's work (or tomorrow's, if today's is done), for the largest size. */
    val list: List<Line>,
    /** What's wrong, worst first: a source that can't be read, Teams not synced for a day. */
    val warning: String?,
    /** The task a tap opens, if any. */
    val taskId: String?,
) {
    data class Line(val text: String, val minutes: String, val urgent: Boolean, val taskId: String)

    companion object {
        /** Teams data older than this is worth a warning: the widget syncs only when asked. */
        const val TEAMS_STALE_MS = 24 * 3_600_000L
        private const val LIST_MAX = 30

        fun from(plan: Plan, state: TaskState, zone: ZoneId): WidgetModel {
            val now = plan.now
            val next = plan.next
            val today = plan.todayBucket?.chunks.orEmpty()
            val tomorrow = plan.tomorrowBucket?.chunks.orEmpty()
            val shown = today.ifEmpty { tomorrow }
            return WidgetModel(
                headline = next?.let { "Do: ${it.label}" } ?: "Nothing due",
                badge = next?.let { badge(it, now, zone) },
                urgent = next != null && (next.overdue || next.dueToday || next.behind),
                minutes = next?.let { Format.minutes(it.minutes) },
                then = plan.then?.let { "Then: ${it.label}" },
                summary = when {
                    today.isEmpty() && tomorrow.isEmpty() -> "Nothing due today or tomorrow"
                    else -> listOfNotNull(
                        today.size.takeIf { it > 0 }?.let { "$it today" },
                        tomorrow.size.takeIf { it > 0 }?.let { "$it tomorrow" },
                    ).joinToString(" · ")
                },
                // After the next and the one after it, which have lines of their own.
                list = shown.filterNot { it === next || it === plan.then }.take(LIST_MAX)
                    .map { Line(it.label, Format.minutes(it.minutes), it.overdue || it.behind, it.taskId) },
                warning = warning(state, now),
                taskId = next?.taskId,
            )
        }

        /** "Overdue", "Due 23:59" (today), "Behind", or when it's due. */
        fun badge(chunk: Chunk, now: Long, zone: ZoneId): String = when {
            chunk.overdue && chunk.soft -> "Waiting a week"
            chunk.overdue -> "Overdue"
            chunk.dueToday -> "Due " + Format.at(chunk.deadline, now, zone).removePrefix("today ")
            chunk.behind -> "Behind"
            chunk.dueAt == null -> "No deadline"
            else -> "Due " + Format.at(chunk.dueAt, now, zone)
        }

        private fun warning(state: TaskState, now: Long): String? {
            val failing = Source.entries.firstOrNull { state.status(it).error != null }
            if (failing != null) return "Can't read ${failing.label}"
            val teamsAsOf = state.status(Source.Teams).dataAsOf
            if (teamsAsOf != null && now - teamsAsOf > TEAMS_STALE_MS) return "Teams synced ${Format.ago(teamsAsOf, now)}"
            return null
        }
    }
}
