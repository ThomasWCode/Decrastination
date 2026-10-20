package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.TaskItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What a task's sheet says beyond its fields (UI-09): its pieces in the plan and why, and the instructions on it. Pure. */
object TaskSheetModel {
    /** A piece of the task's as planned: its day, its slot's times where it has one, and why it's there (PL-07). */
    data class Planned(val chunk: Chunk, val day: LocalDate, val start: Long?, val end: Long?, val why: String)

    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** [taskId]'s pieces in [plan], soonest first, each with its slot's times where it has one, and why. */
    fun planned(plan: Plan, taskId: String, zone: ZoneId): List<Planned> = plan.buckets.flatMap { bucket ->
        bucket.chunks.filter { it.taskId == taskId }.map { chunk ->
            val slot = bucket.slots.firstOrNull { it.chunk?.key == chunk.key }
            Planned(chunk, bucket.date, slot?.start, slot?.end, chunk.whyLine(plan.today, zone))
        }
    }

    /** The piece to tick, photograph or move next: the task's first in [plan]. */
    fun next(plan: Plan, taskId: String): Chunk? = plan.chunksOf(taskId).firstOrNull()

    /**
     * Whether [task]'s work can go to [day], as the plan keeps a day you give (`keptDay`): not before
     * the day it can start, nor past its real deadline's while that's still to come at [now] (UI-04's
     * "due before then"); overdue, any day (D-30).
     */
    fun canGo(task: TaskItem, day: LocalDate, now: Long, zone: ZoneId): Boolean =
        task.notBefore?.let { day >= Planner.date(it, zone) } != false && task.realDeadline?.takeIf { it > now }?.let { day <= Planner.date(it, zone) } != false

    /** [p] as the sheet says it, from [today]: "Today 17:00–17:30, Q1: due today". */
    fun line(p: Planned, today: LocalDate, zone: ZoneId): String {
        val day = when (ChronoUnit.DAYS.between(today, p.day)) {
            0L -> "Today"
            1L -> "Tomorrow"
            in 2L..6L -> p.day.format(WEEKDAY)
            else -> p.day.format(DATE)
        }
        val time = if (p.start != null && p.end != null) " " + Format.span(p.start, p.end, zone) else ""
        val piece = p.chunk.shown ?: if (p.chunk.parts > 1) "part ${p.chunk.part} of ${p.chunk.parts}" else null
        return listOfNotNull(day + time, piece).joinToString(", ") + ": " + p.why.replaceFirstChar(Char::lowercase)
    }

    /** A note's words, at most, as the sheet's box takes them (8a). */
    const val MAX_NOTE = Notes.MAX_TEXT

    /** One of a task's notes as its sheet lists it (8a): its words, who wrote it and when, and the tasks a standing one is for. */
    data class Noted(val id: String, val line: String)

    /**
     * [task]'s notes (8a), its own then the standing ones it matches ([Notes.forTask]), each a line: "Felix,
     * today 17:00: use the second source"; "Note for every Chemistry task: on sheets (yours, Mon)". [felix] his name.
     */
    fun notes(task: TaskItem, notes: List<Note>, classMap: Map<String, String>, felix: String, now: Long, zone: ZoneId): List<Noted> =
        Notes.forTask(task, notes, classMap).map { n ->
            val who = if (n.by == By.Felix) felix else "You"
            val at = Format.at(n.at, now, zone)
            Noted(n.id, n.scope?.let { "Note for ${Notes.scopeLine(it)}: ${n.text} (${if (n.by == By.Felix) felix else "yours"}, $at)" } ?: "$who, $at: ${n.text}")
        }

    /** The words of the instructions in force on [taskId], about it or changing it: the newest first. */
    fun instructions(applied: List<Instruction>, taskId: String): List<String> =
        applied.filter { i -> i.about.taskId == taskId || i.changes.any { it.taskId == taskId } }
            .sortedByDescending { it.appliedAt ?: it.at }
            .map { it.text }
}
