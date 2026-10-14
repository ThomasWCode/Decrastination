package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.TaskItem
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The Tasks tab's sections, by when each task is due (2.0.0, Q30), in the order they're shown. */
enum class DueGroup(val title: String) {
    Overdue("Overdue"),
    Today("Today"),
    Tomorrow("Tomorrow"),

    /** After tomorrow, up to Sunday. */
    ThisWeek("This week"),
    Later("Later"),
    NoDate("No due date"),
}

/** How the Tasks tab and the Plan group what they show. Pure, for the tests. */
object Groups {

    /**
     * [tasks] under their [DueGroup]s, each soonest due first (then by name), the sections in order
     * and none empty. A real deadline earlier today and not done is overdue; a date the app or
     * Claude set that has gone by was a plan, and the work is planned again, so it's today's (PL-14).
     * This week runs to Sunday.
     */
    fun byDue(tasks: List<TaskItem>, now: Long, zone: ZoneId): List<Pair<DueGroup, List<TaskItem>>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        fun group(task: TaskItem): DueGroup {
            val due = task.dueAt ?: return DueGroup.NoDate
            if (due < now) return if (task.hardDeadline) DueGroup.Overdue else DueGroup.Today
            val day = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
            return when {
                day == today -> DueGroup.Today
                day == today.plusDays(1) -> DueGroup.Tomorrow
                day <= sunday -> DueGroup.ThisWeek
                else -> DueGroup.Later
            }
        }
        val grouped = tasks.groupBy(::group)
        return DueGroup.entries.mapNotNull { g ->
            grouped[g]?.sortedWith(compareBy<TaskItem, Long?>(nullsLast()) { it.dueAt }.thenBy { it.title.lowercase() })?.let { g to it }
        }
    }

    /**
     * A day's pieces on the Plan as rows, one a task (Q31): each task where its first piece that day
     * comes, with all its pieces that day under it, in their order.
     */
    fun byTask(chunks: List<Chunk>): List<TaskRow> = chunks.groupBy { it.taskId }.map { (id, pieces) -> TaskRow(id, pieces) }
}

/** One task's pieces on one day of the Plan: a row that opens into them. */
data class TaskRow(val taskId: String, val pieces: List<Chunk>) {
    val first: Chunk get() = pieces.first()
    val minutes: Int get() = pieces.sumOf { it.minutes }

    /** Bad news if any of its pieces is: overdue, due today, or behind. */
    val urgent: Boolean get() = pieces.any { it.urgent }
}
