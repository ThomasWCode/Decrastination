package com.thomaswcode.decrastination.calendar

import com.thomaswcode.decrastination.core.BusyBlock
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.enrich.MicroPlan
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.ZoneId

/**
 * What writing the plan's slots to your calendar changes (NF-07), by each event's URI: a slot not
 * there is inserted (one you deleted too: it isn't there); one of the app's whose time or words
 * differ is updated; one of the app's no longer a slot is deleted; and a piece's event you've moved
 * (its start no longer what the app wrote, nor what the plan has now) is a hand move, for the app to
 * take as your time, not to put back. A copy of one of its events that's lost the app's marks is
 * deleted ([isCopy]). Pure.
 */
object SlotCalendarDiff {
    const val URI_PREFIX = "decrastination://slot/"

    /** How many days of slots are written: today and the six after it. */
    const val DAYS = 7L

    /**
     * A slot as an event: its URI, its words, its times; [taskId] for a piece of work, null for a break outside; or a
     * busy time of yours ([busy], D-117), its time shown busy in your calendar, a slot's free.
     */
    data class Desired(val uri: String, val title: String, val description: String, val start: Long, val end: Long, val taskId: String?, val busy: Boolean = false)

    /**
     * One of the app's events, as the calendar has it now. Not [marked]: a copy of one that's lost the
     * app's marks (its URI and package), found by its words and a version the app wrote ([isCopy]), to delete.
     */
    data class Existing(val id: Long, val uri: String, val start: Long, val end: Long, val title: String, val description: String, val marked: Boolean = true)

    /** A break outside's words, as [desired] writes them. */
    const val OUTSIDE = "A break outside."

    /** A busy time's words, as [desired] writes them (D-117). */
    const val BUSY = "A busy time of yours: no work then."

    /**
     * Whether an event's [description] is in the words [desired] writes: a piece's, its source first
     * ("Teams. Due …"), a break outside's, or a busy time's. Only one of the signs of a copy ([isCopy]): an event of
     * yours could be worded so.
     */
    fun looksWritten(description: String): Boolean =
        description == OUTSIDE || description == BUSY || Source.entries.any { description.startsWith(it.label + ". ") }

    /** One version of an event as the app writes it to [calendarId], as a fingerprint of its title and times. */
    fun version(calendarId: Long, title: String, start: Long, end: Long): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest("$calendarId\n$title\n$start\n$end".toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest, 0, 8).long
    }

    /**
     * Whether an event of [calendarId] that has none of the app's marks is a copy of one of its own:
     * in its words ([looksWritten]), and under a title and at times the app wrote there ([versions],
     * as [version]). The calendar's sync, run while the app changed an event it was still
     * sending, brought it back as a new event, the app's marks left off, stale as its slot moved on;
     * such a copy is deleted, never left over the plan. Its words alone aren't enough: an event
     * of yours worded like one is never deleted.
     */
    fun isCopy(calendarId: Long, title: String, description: String, start: Long, end: Long, versions: Set<Long>): Boolean =
        looksWritten(description) && version(calendarId, title, start, end) in versions

    /**
     * Of [desired]'s versions, those an event of yours already has in [calendarId] ([unmarked]: none
     * of the app's marks, in its words), under a slot's title at its times before the app has ever
     * written that version (not one of [versions]), so it can't be a copy of one. Not remembered
     * ([remembered]) while it's there, so no write takes it for one: remembered,
     * the next write would have deleted it.
     */
    fun yours(calendarId: Long, desired: List<Desired>, unmarked: List<Existing>, versions: Set<Long>): Set<Long> {
        val wanted = desired.map { version(calendarId, it.title, it.start, it.end) }.toSet()
        return unmarked.filter { !it.marked && looksWritten(it.description) }.map { version(calendarId, it.title, it.start, it.end) }
            .filter { it in wanted && it !in versions }.toSet()
    }

    /** How many versions are remembered ([remembered]): some days of a plan's changes, as a copy comes back within minutes. */
    const val MAX_VERSIONS = 2_000

    /** [kept] with [written] remembered, each once, the latest last; past [MAX_VERSIONS], the oldest go. */
    fun remembered(kept: List<Long>, written: List<Long>): List<Long> {
        val fresh = written.distinct()
        return ((kept - fresh.toSet()) + fresh).takeLast(MAX_VERSIONS)
    }

    data class Update(val existing: Existing, val desired: Desired)

    /** A piece's event you moved: [existing] at your time, [desired] where the plan has it. */
    data class HandMove(val existing: Existing, val desired: Desired)

    /**
     * What a write changes; [restored], a busy time's event you deleted, inserted again among [inserts], and [reverted], one you
     * changed, its start left (stretched, shortened, renamed), put back among [updates]: each to be said (D-117).
     */
    data class Diff(val inserts: List<Desired>, val updates: List<Update>, val deletes: List<Existing>, val handMoves: List<HandMove>, val restored: List<Desired> = emptyList(), val reverted: List<Desired> = emptyList())

    /** Your moves as judged: those taken as your time, and those written back, with why. */
    data class Judged(val taken: List<HandMove>, val refused: List<Pair<HandMove, String>>)

    /** Why a second move of one task's pieces is written back. */
    const val ONE_AT_A_TIME = "one of its pieces moves at a time, and an earlier one was"

    /**
     * Your [moves], earliest first: each taken unless [refuses] gives why not, or one of its task's
     * was taken already, as a task's next piece has one time ([com.thomaswcode.decrastination.core.ChangeType.AtTime]):
     * that one is written back and said, not lost to the other.
     */
    fun judge(moves: List<HandMove>, refuses: (HandMove) -> String?): Judged {
        val taken = mutableListOf<HandMove>()
        val refused = mutableListOf<Pair<HandMove, String>>()
        val tasks = mutableSetOf<String?>()
        for (move in moves.sortedBy { it.existing.start }) {
            val why = refuses(move) ?: ONE_AT_A_TIME.takeIf { move.desired.taskId in tasks }
            if (why != null) {
                refused += move to why
            } else {
                taken += move
                tasks += move.desired.taskId
            }
        }
        return Judged(taken, refused)
    }

    /**
     * [starts] as a write left them, but each of [taken] at its start as last written ([before]): a
     * move counts as written only once its instruction is in, so one stopped short of it is found again.
     */
    fun pending(starts: Map<String, Long>, before: Map<String, Long>, taken: List<HandMove>): Map<String, Long> =
        starts + taken.mapNotNull { move -> before[move.desired.uri]?.let { move.desired.uri to it } }

    /**
     * [plan]'s slots, work and outside, from today for [DAYS] days, as events; and your [busy] times of those days (D-117),
     * each "Busy: <its name>", or "Busy" with none, by its day and times (`busy:1020-1110`).
     */
    fun desired(plan: Plan, zone: ZoneId, micro: Map<String, MicroPlan> = emptyMap(), busy: List<BusyBlock> = emptyList()): List<Desired> = (0 until DAYS).flatMap { offset ->
        val date = plan.today.plusDays(offset)
        val midnight = date.atStartOfDay()
        val blocks = busy.filter { it.date == date }.map { b ->
            Desired(
                uri = "$URI_PREFIX$date/${b.key}",
                title = b.title?.let { "Busy: $it" } ?: "Busy",
                description = BUSY,
                start = midnight.plusMinutes(b.startMin.toLong()).atZone(zone).toInstant().toEpochMilli(),
                end = midnight.plusMinutes(b.endMin.toLong()).atZone(zone).toInstant().toEpochMilli(),
                taskId = null,
                busy = true,
            )
        }
        blocks + plan.bucket(date)?.slots.orEmpty().map { slot ->
            val chunk = slot.chunk
            Desired(
                uri = "$URI_PREFIX$date/${slot.key}",
                title = chunk?.label ?: "Outside",
                // With its first step (AI-15), where one's written.
                description = chunk?.let { c -> "${c.source.label}. ${c.whyLine(plan.today, zone)}." + (micro[c.key]?.first?.takeIf { it.isNotEmpty() }?.let { " First: $it" } ?: "") } ?: OUTSIDE,
                start = slot.start,
                end = slot.end,
                taskId = chunk?.taskId,
            )
        }
    }

    /**
     * [desired] against the app's [existing] events, [written] holding each URI's start as the app last wrote it; [edited] whether
     * an event is in a version the app never wrote, so changed by hand.
     */
    fun diff(desired: List<Desired>, existing: List<Existing>, written: Map<String, Long>, edited: (Existing) -> Boolean = { false }): Diff {
        // Copies that have lost the app's marks: found by their words alone, and deleted.
        val copies = existing.filterNot { it.marked }
        val there = existing.filter { it.marked }.groupBy { it.uri }
        val wanted = desired.map { it.uri }.toSet()
        val inserts = mutableListOf<Desired>()
        val updates = mutableListOf<Update>()
        val moves = mutableListOf<HandMove>()
        val restored = mutableListOf<Desired>()
        val reverted = mutableListOf<Desired>()
        for (want in desired) {
            val event = there[want.uri]?.first()
            when {
                // Not there: inserted, one you deleted too; a busy time's, written before, said (D-117).
                event == null -> {
                    inserts += want
                    if (want.busy && want.uri in written) restored += want
                }
                // Moved by hand: not where it was written, nor where the plan has it now. A break outside's too, which the
                // writer puts back (D-116).
                written[want.uri]?.let { it != event.start } == true && event.start != want.start -> moves += HandMove(event, want)
                event.start != want.start || event.end != want.end || event.title != want.title || event.description != want.description -> {
                    updates += Update(event, want)
                    // A busy time's event changed by hand, its start left: put back as it was, and said (D-117).
                    if (want.busy && edited(event)) reverted += want
                }
            }
        }
        // The app's events no longer a slot, a second copy of one that is, and those that lost its marks.
        val deletes = there.filterKeys { it !in wanted }.values.flatten() + there.filterKeys { it in wanted }.values.flatMap { it.drop(1) } + copies
        return Diff(inserts, updates, deletes, moves, restored, reverted)
    }
}
