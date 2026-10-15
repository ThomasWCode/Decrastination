package com.thomaswcode.decrastination.calendar

import com.thomaswcode.decrastination.core.Plan
import java.time.ZoneId

/**
 * What writing the plan's slots to your calendar changes (NF-07), by each event's URI: a slot not
 * there is inserted (one you deleted too: it isn't there); one of the app's whose time or words
 * differ is updated; one of the app's no longer a slot is deleted; and a piece's event you've moved
 * (its start no longer what the app wrote, nor what the plan has now) is a hand move, for the app to
 * take as your time, not to put back. Pure.
 */
object SlotCalendarDiff {
    const val URI_PREFIX = "decrastination://slot/"

    /** How many days of slots are written: today and the six after it. */
    const val DAYS = 7L

    /** A slot as an event: its URI, its words, its times; [taskId] for a piece of work, null for a break outside. */
    data class Desired(val uri: String, val title: String, val description: String, val start: Long, val end: Long, val taskId: String?)

    /** One of the app's events, as the calendar has it now. */
    data class Existing(val id: Long, val uri: String, val start: Long, val end: Long, val title: String, val description: String)

    data class Update(val existing: Existing, val desired: Desired)

    /** A piece's event you moved: [existing] at your time, [desired] where the plan has it. */
    data class HandMove(val existing: Existing, val desired: Desired)

    data class Diff(val inserts: List<Desired>, val updates: List<Update>, val deletes: List<Existing>, val handMoves: List<HandMove>)

    /** [plan]'s slots, work and outside, from today for [DAYS] days, as events. */
    fun desired(plan: Plan, zone: ZoneId): List<Desired> = (0 until DAYS).flatMap { offset ->
        val date = plan.today.plusDays(offset)
        plan.bucket(date)?.slots.orEmpty().map { slot ->
            val chunk = slot.chunk
            Desired(
                uri = "$URI_PREFIX$date/${slot.key}",
                title = chunk?.label ?: "Outside",
                description = chunk?.let { "${it.source.label}. ${it.whyLine(plan.today, zone)}." } ?: "A break outside.",
                start = slot.start,
                end = slot.end,
                taskId = chunk?.taskId,
            )
        }
    }

    /** [desired] against the app's [existing] events, [written] holding each URI's start as the app last wrote it. */
    fun diff(desired: List<Desired>, existing: List<Existing>, written: Map<String, Long>): Diff {
        val there = existing.groupBy { it.uri }
        val wanted = desired.map { it.uri }.toSet()
        val inserts = mutableListOf<Desired>()
        val updates = mutableListOf<Update>()
        val moves = mutableListOf<HandMove>()
        for (want in desired) {
            val event = there[want.uri]?.first()
            when {
                event == null -> inserts += want
                // Moved by hand: not where it was written, nor where the plan has it now.
                want.taskId != null && written[want.uri]?.let { it != event.start } == true && event.start != want.start -> moves += HandMove(event, want)
                event.start != want.start || event.end != want.end || event.title != want.title || event.description != want.description -> updates += Update(event, want)
            }
        }
        // The app's events no longer a slot, and a second copy of one that is.
        val deletes = existing.filter { it.uri !in wanted } + there.filterKeys { it in wanted }.values.flatMap { it.drop(1) }
        return Diff(inserts, updates, deletes, moves)
    }
}
