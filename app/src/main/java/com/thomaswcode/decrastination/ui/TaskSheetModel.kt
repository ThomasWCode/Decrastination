package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Instruction
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
     * the day it can start, nor past its real deadline's (UI-04's "due before then").
     */
    fun canGo(task: TaskItem, day: LocalDate, zone: ZoneId): Boolean =
        task.notBefore?.let { day >= Planner.date(it, zone) } != false && task.realDeadline?.let { day <= Planner.date(it, zone) } != false

    /** [p] as the sheet says it, from [today]: "Today 17:00–17:30, Q1: due today". */
    fun line(p: Planned, today: LocalDate, zone: ZoneId): String {
        val day = when (ChronoUnit.DAYS.between(today, p.day)) {
            0L -> "Today"
            1L -> "Tomorrow"
            in 2L..6L -> p.day.format(WEEKDAY)
            else -> p.day.format(DATE)
        }
        val time = if (p.start != null && p.end != null) " " + Format.span(p.start, p.end, zone) else ""
        val piece = p.chunk.step ?: if (p.chunk.parts > 1) "part ${p.chunk.part} of ${p.chunk.parts}" else null
        return listOfNotNull(day + time, piece).joinToString(", ") + ": " + p.why.replaceFirstChar(Char::lowercase)
    }

    /** The words of the instructions in force on [taskId], about it or changing it: the newest first. */
    fun instructions(applied: List<Instruction>, taskId: String): List<String> =
        applied.filter { i -> i.about.taskId == taskId || i.changes.any { it.taskId == taskId } }
            .sortedByDescending { it.appliedAt ?: it.at }
            .map { it.text }
}
