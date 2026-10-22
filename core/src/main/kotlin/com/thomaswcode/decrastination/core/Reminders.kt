package com.thomaswcode.decrastination.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * A reminder Felix keeps at your asking (docs/plan_v4/07-notes-and-reminders.md): your
 * [text], delivered as his message when it's [due], in his words for that moment written just before
 * ([draft], D-100), or yours as you set them where he can't write. Set from the chat ([by]);
 * [sentAt] once delivered, [snoozedTo] by a snooze (it comes again then), [doneAt] by its *Done*.
 * Cancelled ([cancelledAt]), it's never delivered and kept whole for the cancel's Undo; one whose task
 * closes before it comes is let go ([letGoAt]), said in the chat ([Reminders.letGo]), and back, said too,
 * once its task is to do again ([Reminders.back]).
 */
@Serializable
data class Reminder(
    val id: String,
    val text: String,
    val at: Long,
    val by: By,
    val taskId: String? = null,
    val due: Due,
    val sentAt: Long? = null,
    val snoozedTo: Long? = null,
    val doneAt: Long? = null,
    /** Cancelled from the chat: kept, never delivered, till its act can no longer be undone; Undo clears it. */
    val cancelledAt: Long? = null,
    /** Let go by the app, as what it waited on went ([Reminders.letGo]): never delivered while so, and back once that's back. */
    val letGoAt: Long? = null,
    /** His words for it, written ahead, when, and for the moment they were written for (or asked for, where none came): another moment's aren't sent. */
    val draft: FelixAnswer? = null,
    val draftedAt: Long? = null,
    val draftFor: Long? = null,
) {
    /** As it is, its words for before let go: moved, snoozed, sent or cancelled, it's another moment's. */
    fun undrafted(): Reminder = copy(draft = null, draftedAt = null, draftFor = null)
}

/**
 * When a reminder comes ([kind]): [at] a moment (At); at the start of its task's first work slot from
 * [at] on, the day it was asked for or the moment it was set (SlotStart); or [minutesBefore] its task's
 * deadline in force (BeforeDue), following the deadline as it moves.
 */
@Serializable
data class Due(val kind: DueKind, val at: Long? = null, val minutesBefore: Int? = null)

@Serializable
enum class DueKind { At, SlotStart, BeforeDue }

/** The reminders (docs/plan_v4/07-notes-and-reminders.md): when each comes, which are due, which are kept. Pure. */
object Reminders {
    /** A reminder's words, at most. */
    const val MAX_TEXT = 300

    /** The most reminders still to come at once. */
    const val MAX_PENDING = 50

    /** The most minutes before its task's deadline a BeforeDue comes: a week. */
    const val MAX_BEFORE_MIN = 7 * 24 * 60

    /** How long one sent or done is kept: its record, then it goes. */
    const val KEEP_MS = 30 * 24 * 3_600_000L

    /** How long after its time one comes before it's marked late (the phone off, or asleep through it). */
    const val LATE_MS = 5 * 60_000L

    /** Whether [r] is still to come: not done, cancelled nor let go, and not sent but for a snooze since. */
    fun pending(r: Reminder): Boolean =
        r.doneAt == null && r.cancelledAt == null && r.letGoAt == null && (r.sentAt == null || (r.snoozedTo != null && r.snoozedTo > r.sentAt))

    /** Whether [r] can still be moved or reworded: still to come, and never sent. */
    fun unsent(r: Reminder): Boolean = pending(r) && r.sentAt == null && r.snoozedTo == null

    /**
     * When [r] comes, by [plan] and the [tasks] as they are at [now]: its snooze; else an At's time; a
     * SlotStart's task's first work slot starting at or after its [Due.at]; a BeforeDue's task's deadline
     * in force, less its minutes. Null where that isn't known (no slot for its task yet, no deadline).
     */
    fun moment(r: Reminder, plan: Plan, tasks: Map<String, TaskItem>, now: Long): Long? {
        r.snoozedTo?.let { return it }
        return when (r.due.kind) {
            DueKind.At -> r.due.at
            DueKind.SlotStart -> plan.buckets.asSequence().flatMap { it.slots }
                .filter { it.kind == SlotKind.Work && it.chunk?.taskId == r.taskId && it.start >= (r.due.at ?: r.at) }
                .minOfOrNull { it.start }
            DueKind.BeforeDue -> r.taskId?.let(tasks::get)?.deadlineAt(now)?.minus((r.due.minutesBefore ?: 0) * 60_000L)
        }
    }

    /** The earliest moment any of [reminders] still to come is due ([moment]): what the alarm is set to; null, none. */
    fun next(reminders: List<Reminder>, plan: Plan, tasks: Map<String, TaskItem>, now: Long): Long? =
        reminders.filter(::pending).mapNotNull { moment(it, plan, tasks, now) }.minOrNull()

    /**
     * When the app is to wake for [reminders]: the earliest of each still to come's [moment] and, for one not
     * asked for its moment yet ([asked]), the start of the window [ahead] of it while that's still to come,
     * so it's written for with the phone asleep; not before [notBefore] (a while on, after a failed sweep). Null
     * with none to come.
     */
    fun nextWake(reminders: List<Reminder>, plan: Plan, tasks: Map<String, TaskItem>, now: Long, ahead: Long, notBefore: Long? = null): Long? =
        reminders.filter(::pending).flatMap { r ->
            val at = moment(r, plan, tasks, now) ?: return@flatMap emptyList()
            listOfNotNull(at, (at - ahead).takeIf { it > now && !asked(r, at) })
        }.minOrNull()?.let { at -> notBefore?.let { maxOf(at, it) } ?: at }

    /** Those of [reminders] still to come whose moment has come by [now], each with it, the earliest first. */
    fun due(reminders: List<Reminder>, plan: Plan, tasks: Map<String, TaskItem>, now: Long): List<Pair<Reminder, Long>> =
        reminders.filter(::pending).mapNotNull { r -> moment(r, plan, tasks, now)?.takeIf { it <= now }?.let { r to it } }.sortedBy { it.second }

    /**
     * Those of [reminders] to let go, never sent, as what they wait on is gone: a SlotStart's or a
     * BeforeDue's task closed, or not listed and gone ([TaskItem.goneSince], by its source's last read,
     * [readAt]: one not listed before its source is read again is waited for), a SlotStart's off the plan
     * ([offPlan]), or a BeforeDue's with no deadline. An At comes at its time whatever becomes of its task,
     * as an alarm would.
     */
    fun letGo(reminders: List<Reminder>, tasks: Map<String, TaskItem>, now: Long, readAt: (Source) -> Long? = { null }): List<Reminder> = reminders.filter { r ->
        unsent(r) && r.due.kind != DueKind.At && r.taskId.let { id ->
            val task = id?.let(tasks::get)
            when {
                id == null -> true
                task == null -> TaskItem.goneSince(id, r.at, readAt)
                else -> !task.isOpen || (r.due.kind == DueKind.SlotStart && offPlan(task)) || (r.due.kind == DueKind.BeforeDue && task.deadlineAt(now) == null)
            }
        }
    }

    /**
     * Those of [reminders] let go ([letGo]) that what they waited on has come back to while they're kept: their task
     * open and listed again, on the plan, with a deadline (a tick undone, a source's read that took it away a while), so
     * a let-go doesn't lose what you set. Each comes as it was set; as many as there's room for among those still to come
     * ([MAX_PENDING]), the first let go first, the rest staying let go till there's room (a restore's among them).
     */
    fun back(reminders: List<Reminder>, tasks: Map<String, TaskItem>, now: Long, readAt: (Source) -> Long? = { null }): List<Reminder> =
        reminders.filter { r -> r.letGoAt != null && r.copy(letGoAt = null).let { was -> unsent(was) && letGo(listOf(was), tasks, now, readAt).isEmpty() } }
            .sortedBy { it.letGoAt }.take((MAX_PENDING - reminders.count(::pending)).coerceAtLeast(0))

    /**
     * Whether [task] never has a work slot, so a SlotStart on it never comes: an event, or one you said isn't a
     * task or is the same as another ([TaskItem.hidden]), as the planner leaves them out of the work it lays.
     */
    fun offPlan(task: TaskItem): Boolean = task.kind == Kind.Event || task.hidden

    /** [r] snoozed [minutes] from [now]: it comes again then, its draft for before let go. */
    fun snoozed(r: Reminder, now: Long, minutes: Int): Reminder = r.copy(snoozedTo = now + minutes * 60_000L).undrafted()

    /**
     * Whether [r]'s draft is written for its [moment]: the moment it was written for ([Reminder.draftFor]), so one
     * whose slot or deadline has moved since, even within the window, is written for again.
     */
    fun drafted(r: Reminder, moment: Long): Boolean = r.draft != null && r.draftFor == moment

    /**
     * Whether his words for [r]'s [moment] have been asked for: written ([drafted]), or asked for and none came
     * (refused, or none that could be read), so that moment isn't paid for again and yours go.
     */
    fun asked(r: Reminder, moment: Long): Boolean = r.draftFor == moment

    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /**
     * When [r] comes, in words, [moment] its time ([Reminders.moment]) and [title] its task's: "Tue 20 Oct 19:00";
     * "at the start of “Vectors”'s next slot, Tue 20 Oct 17:00" (or "none planned yet"); "1 h before “Essay”
     * is due, Wed 21 Oct 08:00"; a snooze's, "snoozed to Tue 20 Oct 19:15".
     */
    fun whenLine(r: Reminder, moment: Long?, title: String?, zone: ZoneId): String {
        fun at(time: Long?) = time?.let { Instant.ofEpochMilli(it).atZone(zone).format(WHEN) }
        val task = "“${title ?: "its task"}”"
        if (r.snoozedTo != null) return "snoozed to ${at(r.snoozedTo)}"
        return when (r.due.kind) {
            DueKind.At -> at(r.due.at ?: moment) ?: "no time"
            DueKind.SlotStart -> "at the start of $task's next slot, ${at(moment) ?: "none planned yet"}"
            DueKind.BeforeDue -> "${minutes(r.due.minutesBefore ?: 0)} before $task is due, ${at(moment) ?: "no deadline"}"
        }
    }

    /** [n] minutes as a reminder's lead says them: "45 min", "1 h", "1 h 30 min", "2 days". */
    private fun minutes(n: Int): String = when {
        n >= 24 * 60 && n % (24 * 60) == 0 -> (n / (24 * 60)).let { "$it day${if (it == 1) "" else "s"}" }
        n >= 60 -> "${n / 60} h" + if (n % 60 > 0) " ${n % 60} min" else ""
        else -> "$n min"
    }

    /**
     * [reminders] kept (the nightly prune): one sent, done or let go more than [KEEP_MS] before [now] goes, as
     * does one cancelled once no message in the chat's window, [messages], holds its cancel's act not undone;
     * the rest stay.
     */
    fun prune(reminders: List<Reminder>, now: Long, messages: List<ChatMessage>): List<Reminder> {
        val held = ChatActs.held(messages)
        return reminders.filter { r ->
            when {
                r.cancelledAt != null -> r.id in held
                pending(r) -> true
                else -> (listOfNotNull(r.doneAt, r.sentAt, r.letGoAt).maxOrNull() ?: r.at) >= now - KEEP_MS
            }
        }
    }
}
