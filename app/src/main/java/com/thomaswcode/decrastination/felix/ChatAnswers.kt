package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.DayBucket
import com.thomaswcode.decrastination.core.Due
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.FelixSettings
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Lessons
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.NoteScope
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.shareOut
import com.thomaswcode.decrastination.data.FelixStore
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import com.thomaswcode.decrastination.enrich.ResearchPrompts
import com.thomaswcode.decrastination.enrich.filled
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Claude's answer in the chat, checked: its reply, the tasks and changes the app can make, and what it left out. */
object ChatAnswers {

    /**
     * The times given at once ([changes]' AtTime) that the plan, laid as [buckets], put elsewhere
     * (PL-04, D-114: a pin is kept to the minute but where one given later covers it, its piece
     * wouldn't be over by its deadline, or it's before its piece opens, when it goes to the first free
     * time after it), a line each, as the answer names the time asked for: "06:35 had no free time:
     * it's at 10:45." A minute or less apart is the time kept. Pure.
     */
    fun movedPins(changes: List<Change>, buckets: List<DayBucket>, zone: ZoneId): List<String> {
        val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone)
        return changes.filter { it.type == ChangeType.AtTime && it.taskId != null && it.time != null }.mapNotNull { c ->
            val asked = c.time!!
            val day = c.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: at(asked).toLocalDate()
            // A day past the plan's week: nothing to say of it.
            val bucket = buckets.firstOrNull { it.date == day } ?: return@mapNotNull null
            val slots = bucket.slots
                .filter { it.kind == SlotKind.Work && it.chunk?.taskId == c.taskId }
            val laid = slots.firstOrNull { it.pinned } ?: slots.minByOrNull { abs(it.start - asked) }
            when {
                laid == null -> "${at(asked).format(hm)} had no free time, nor any after it that day."
                abs(laid.start - asked) <= 60_000L -> null
                else -> "${at(asked).format(hm)} had no free time: it's at ${at(laid.start).format(hm)}."
            }
        }.distinct()
    }

    data class Reading(
        val reply: String,
        val tasks: List<OwnTask>,
        val changes: List<Change>,
        val leftOut: List<String>,
        val memories: List<Given> = emptyList(),
        val suggestedReplies: List<String> = emptyList(),
        /** Steps to tick and tasks to show (AI-14), each checked against what there is. */
        val actions: List<ChatAction> = emptyList(),
        /** What it asks to look up on the web first ([WebResearcher]): its answer is then only that it's looking. */
        val research: String? = null,
        /** The tasks whose whole text it asks to read first (D-93): its answer is then only that it's reading. */
        val details: List<String> = emptyList(),
        /** Reminders to set, and those listed to move or reword under their own ids, each checked. */
        val reminders: List<Reminder> = emptyList(),
        /** Notes to keep on tasks, each checked. */
        val notes: List<Note> = emptyList(),
        /** Whether it asks for the week's numbers first (G6): its answer is then only that it's looking. */
        val stats: Boolean = false,
        /** The calendar it asks for first (G7), as [ChatPrompts.calendarAsk] reads it: empty for the four weeks after the fortnight, else a month (yyyy-MM). */
        val calendar: String? = null,
        /** His messages still waiting that the words answered (D-99): those the context listed ([ChatPrompts.Context.waiting]). */
        val answered: List<String> = emptyList(),
    )

    /**
     * [reading]'s cards, each made as it comes, with its Undo (D-60, AI-24): its changes on one card, first;
     * then its new tasks, a card each; then a card for each task to show (AI-14), with its Open;
     * one opened where it lives (G3) has none.
     */
    fun cards(reading: Reading, tasks: Map<String, TaskItem>, zone: ZoneId, id: () -> String = { UUID.randomUUID().toString() }): List<Proposal> {
        val changes = reading.changes.takeIf { it.isNotEmpty() }?.let {
            Proposal(id(), changes = it, lines = it.map { c -> InstructionRules.describe(c, tasks, zone) }, instant = true)
        }
        val added = reading.tasks.map { Proposal(id(), task = it, instant = true) }
        val opens = reading.actions.filter { it.type == ChatAction.Type.Open && it.value != OPEN_SOURCE }.map { Proposal(id(), open = it.taskId) }
        return listOfNotNull(changes) + added + opens
    }

    @Serializable
    private data class Answer(
        val reply: String = "",
        val newTasks: List<NewTask> = emptyList(),
        val changes: List<InstructionAnswers.Given> = emptyList(),
        val memories: List<Given> = emptyList(),
        val suggestedReplies: List<String> = emptyList(),
        val actions: List<GivenAction> = emptyList(),
        val research: String = "",
        val details: List<String> = emptyList(),
        val answered: List<String> = emptyList(),
    )

    /**
     * A reminder as his answer gives it: from a change of type Remind (Q13: B), its words its
     * title, when it comes its eventKey, a listed reminder's id to move its afterTaskId, a BeforeDue's
     * minutes its minutes ([reminderOf]).
     */
    private data class GivenReminder(
        val id: String = "",
        val text: String = "",
        val date: String = "",
        val time: String = "",
        val taskId: String = "",
        val `when`: String = ChatPrompts.WHEN_AT,
        val minutesBefore: Int = -1,
    )

    /** A note as his answer gives it: from a change of type Note, its words its title; on a task, or for every task its scope matches (D-100), read from its eventKey ([noteOf]). */
    private data class GivenNote(val taskId: String = "", val text: String = "", val subject: String = "", val className: String = "", val source: String = "", val titleContains: String = "")

    /** [c], a Remind change, as a reminder. */
    private fun reminderOf(c: InstructionAnswers.Given) = GivenReminder(
        id = c.afterTaskId.orEmpty(), text = c.title, date = c.date.orEmpty(), time = c.time.orEmpty(), taskId = c.taskId.orEmpty(),
        `when` = c.eventKey?.trim()?.ifEmpty { null } ?: ChatPrompts.WHEN_AT, minutesBefore = c.minutes ?: -1,
    )

    /** [c], a Note change, as a note: its eventKey's scope as kind:value parts joined by ; (subject:, class:, source:, title:). */
    private fun noteOf(c: InstructionAnswers.Given): GivenNote {
        val parts = c.eventKey.orEmpty().split(';').mapNotNull { part -> part.split(':', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim().lowercase() to v.trim() } }.toMap()
        return GivenNote(c.taskId.orEmpty(), c.title, parts["subject"].orEmpty(), parts["class"].orEmpty(), parts["source"].orEmpty(), parts["title"].orEmpty())
    }

    /** The most reminders, and the most notes, one answer gives: a few at most is what's meant. */
    private const val MAX_REMINDERS = 10
    private const val MAX_NOTES = 10

    @Serializable
    private data class GivenAction(val type: String = "", val taskId: String = "", val stepIndex: Int = -1, val setting: String = "", val value: String = "")

    /** An open's value that opens the task where it lives (G3), not its page here. */
    const val OPEN_SOURCE = "source"

    /** The most actions one answer takes: a few at most is what's meant (ten: there are several kinds). */
    private const val MAX_ACTIONS = 10

    @Serializable
    private data class NewTask(
        val title: String = "",
        val kind: String = "Homework",
        val subject: String? = null,
        val dueDate: String? = null,
        val dueTime: String? = null,
        val startDate: String? = null,
        val minutes: Int = 0,
        val notes: String? = null,
        val steps: List<NewStep> = emptyList(),
    ) {
        /** As given, but an empty date or time is none: the schema has no nullable fields. */
        fun normalised(): NewTask = copy(dueDate = dueDate.filled(), dueTime = dueTime.filled(), startDate = startDate.filled(), steps = steps.map(NewStep::normalised))
    }

    @Serializable
    private data class NewStep(val title: String = "", val minutes: Int = 0, val startDate: String? = null, val dueDate: String? = null, val anyOrder: Boolean = false) {
        fun normalised(): NewStep = copy(startDate = startDate.filled(), dueDate = dueDate.filled())
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** More than this is a misreading, not a message. */
    const val MAX_TASKS = 10
    const val MAX_CHANGES = 40
    private const val DAY_MS = 24 * 3_600_000L

    /** The most memories one answer gives: a few at most is what's meant. */
    private const val MAX_MEMORIES = 5

    /**
     * [text] as Claude's answer, checked against what there is: each task as the form would take it
     * ([OwnTaskRules.problem]) with plausible dates, each change as an instruction's is; what can't be
     * used is left out and said ([Reading.leftOut]), the rest kept. Null if it can't be read at all.
     */
    fun parse(text: String, tasks: List<TaskItem>, events: List<CalendarEvent>, about: About, now: Long, zone: ZoneId, settings: Settings? = null, felix: FelixStore = FelixStore(), context: ChatPrompts.Context? = null): Reading? {
        val answer = runCatching { json.decodeFromString(Answer.serializer(), text) }.getOrNull() ?: return null
        val reply = answer.reply.trim().takeIf { it.isNotEmpty() } ?: return null
        val leftOut = mutableListOf<String>()
        val made = answer.newTasks.take(MAX_TASKS).map(NewTask::normalised).mapNotNull { n ->
            val task = task(n, now, zone)
            val problem = if (task == null) "a date in it doesn't read, or is out of range" else OwnTaskRules.problem(task)
            if (problem != null) leftOut += "the task “${n.title.trim().take(60)}” (${problem.replaceFirstChar(Char::lowercase)})"
            task.takeIf { problem == null }
        }
        if (answer.newTasks.size > MAX_TASKS) leftOut += "${answer.newTasks.size - MAX_TASKS} more tasks than the app takes at once"
        val byId = tasks.associateBy { it.id }
        val titles = events.associate { EventJudge.key(it) to it.title } + listOfNotNull(about.eventKey?.let { it to (about.eventTitle ?: it) })
        // His reminders and notes come as changes of their own types (Q13: B): taken out first, made their own way.
        val (kept, given) = answer.changes.take(MAX_CHANGES).map(InstructionAnswers.Given::normalised).partition { it.type == ChatPrompts.REMIND_TYPE || it.type == ChatPrompts.NOTE_TYPE }
        var changes = given.map { lessonOf(it, context, now, zone) ?: it }.mapNotNull { g ->
            // A due date at a class's next lesson it can't be set from (G8): refused, saying why, so he asks.
            if (g.type == ChangeType.DueBy.name && g.lesson.isNotBlank()) return@mapNotNull null.also { leftOut += "a change (${lessonProblem(g.lesson, context, zone)})" }
            InstructionAnswers.change(g, byId, titles, now, zone).also { if (it == null) leftOut += "a change (${InstructionAnswers.problem(g, byId, titles)})" }
        }
        if (answer.changes.size > MAX_CHANGES) leftOut += "${answer.changes.size - MAX_CHANGES} more changes than the app takes at once"
        // A split whose steps don't come to its task's minutes, its total not said to change (AI-19): left out.
        while (true) {
            val problem = InstructionAnswers.stepsProblem(changes, byId) ?: break
            val bad = changes.first { c -> c.type == ChangeType.Steps && InstructionAnswers.stepsProblem(listOf(c) + changes.filter { it.type == ChangeType.Minutes }, byId) != null }
            leftOut += "a change ($problem)"
            changes = changes - bad
        }
        if (InstructionRules.makesCircle(changes, emptyList())) {
            leftOut += "changes that would have tasks wait for each other in a circle"
            changes = emptyList()
        }
        val replies = answer.suggestedReplies.map { it.trim().take(FelixAnswers.MAX_REPLY) }.filter { it.isNotEmpty() }.distinct().take(FelixAnswers.MAX_REPLIES)
        // A step ticked or unticked, a listed task shown (AI-14); a task of yours done, open again or
        // deleted, a setting set: each only as the task, or the setting, can be.
        val actions = answer.actions.take(MAX_ACTIONS).mapNotNull { a -> action(a, byId, settings, felix, context, leftOut) }.distinct()
        // Tasks to read in full (D-93): listed and open, not the one the message is about (it came whole); and beside
        // them what else it asks for, each once: the week's numbers, and the calendar past the fortnight or a
        // month of it, the first asked for. Any other word is dropped.
        val asked = answer.details.map(String::trim)
        val details = asked.filter { id -> id != about.taskId && byId[id]?.let { it.isOpen && !it.hidden } == true }.distinct().take(ChatPrompts.MAX_READ)
        val stats = asked.any { it.equals(ChatPrompts.ASK_STATS, ignoreCase = true) }
        val calendar = asked.firstNotNullOfOrNull(ChatPrompts::calendarAsk)
        // Reminders and notes: each as the app can keep it, the rest said.
        // Those the answer cancels make room, as they're cancelled before its reminders are made.
        val cancelled = actions.filter { it.type == ChatAction.Type.CancelReminder }.mapNotNull { it.value }.toSet()
        // A deadline the answer sets is the one a reminder before it's due goes by, as its changes are made first.
        val dueBy = deadlines(changes)
        val reopened = actions.filter { it.type == ChatAction.Type.Reopen }.mapTo(HashSet()) { it.taskId }
        val reminders = reminders(kept.filter { it.type == ChatPrompts.REMIND_TYPE }.map(::reminderOf), byId, felix, now, zone, leftOut, cancelled, dueBy, reopened)
        // Those the answer deletes make room too.
        val deleted = actions.filter { it.type == ChatAction.Type.DeleteNote }.mapNotNull { it.value }.toSet()
        val notes = notes(kept.filter { it.type == ChatPrompts.NOTE_TYPE }.map(::noteOf), byId, felix, now, leftOut, deleted)
        // His messages the words answered: only those the context listed as waiting, each once; any other id dropped.
        val waiting = context?.waiting.orEmpty()
        val answered = answer.answered.map(String::trim).filter { it in waiting }.distinct()
        return Reading(reply.take(ChatPrompts.MAX_TEXT), made, changes, leftOut, answer.memories.take(MAX_MEMORIES), replies, actions, answer.research.trim().take(ResearchPrompts.MAX_QUESTION).takeIf { it.isNotEmpty() }, details, reminders, notes, stats, calendar, answered)
    }

    /**
     * [given] as reminders the app keeps, at most [MAX_REMINDERS], checked against the tasks there are
     * ([byId]) and [felix]'s reminders, at [now]: a new one with its words, at a time from now to a year
     * ahead (one gone by refused), at its task's next slot (a listed open task), or minutes before a
     * listed open task's deadline, within a week; one with a listed reminder's id, still to come and never
     * sent, moved or reworded in place (its words, or its time where none is given, kept), its draft let
     * go; no more still to come than [Reminders.MAX_PENDING], those the same answer cancels ([cancelled]) not
     * counted; before a deadline the same answer sets ([dueBy], by task id) by that one; on a task it opens
     * again ([reopened], its actions being made first) as on an open one. What can't be is said in [leftOut].
     */
    private fun reminders(given: List<GivenReminder>, byId: Map<String, TaskItem>, felix: FelixStore, now: Long, zone: ZoneId, leftOut: MutableList<String>, cancelled: Set<String> = emptySet(), dueBy: Map<String, Long> = emptyMap(), reopened: Set<String> = emptySet()): List<Reminder> {
        if (given.size > MAX_REMINDERS) leftOut += "${given.size - MAX_REMINDERS} more reminders than the app takes at once"
        var room = Reminders.MAX_PENDING - felix.reminders.count { Reminders.pending(it) && it.id !in cancelled }
        val seen = HashSet<String>()
        return given.take(MAX_REMINDERS).mapNotNull { g ->
            fun no(why: String) = null.also { leftOut += "a reminder ($why)" }
            val words = g.text.trim().take(Reminders.MAX_TEXT)
            val id = g.id.trim()
            val was = id.takeIf { it.isNotEmpty() }?.let { felix.reminders.firstOrNull { r -> r.id == it } }
            if (id.isNotEmpty() && (was == null || !Reminders.unsent(was))) return@mapNotNull no("one to move that isn't listed, or has come already")
            if (id.isNotEmpty() && !seen.add(id)) return@mapNotNull no("“${words.ifEmpty { was?.text.orEmpty() }.take(60)}” given twice")
            if (was == null && words.isEmpty()) return@mapNotNull no("one with no words")
            val taskId = g.taskId.trim().takeIf { it.isNotEmpty() }
            val task = taskId?.let(byId::get)
            if (taskId != null && task == null) return@mapNotNull no("“${words.take(60)}” on a task that isn't listed")
            val due = when (val kind = g.`when`.trim()) {
                ChatPrompts.WHEN_AT -> {
                    val time = g.time.trim().takeIf { it.isNotEmpty() }
                    if (time == null) {
                        // A move with no time keeps its time; a new one needs one.
                        was?.due ?: return@mapNotNull no("“${words.take(60)}” with no time")
                    } else {
                        val day = g.date.trim().ifEmpty { Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString() }
                        val at = at(day, time, LocalTime.MIDNIGHT, zone) ?: return@mapNotNull no("“${words.take(60)}” at a time that doesn't read")
                        if (at < now - GONE_BY_MS) return@mapNotNull no("“${words.take(60)}” at ${Instant.ofEpochMilli(at).atZone(zone).format(CLOCK)}, which has gone by")
                        if (at > now + YEAR_MS) return@mapNotNull no("“${words.take(60)}” more than a year ahead")
                        Due(DueKind.At, at)
                    }
                }
                ChatPrompts.WHEN_SLOT, ChatPrompts.WHEN_BEFORE -> {
                    // A move that names no task keeps the one it had.
                    val on = task ?: was?.taskId?.let(byId::get)
                        ?: return@mapNotNull no("“${words.take(60)}” at a task's ${if (kind == ChatPrompts.WHEN_SLOT) "slot" else "deadline"}, with no listed task")
                    if (!on.isOpen && on.id !in reopened) return@mapNotNull no("“${words.take(60)}” on “${on.title.take(60)}”, which is finished")
                    if (kind == ChatPrompts.WHEN_SLOT) {
                        // An event, or one off the plan as you said, never has a slot of work for it to come at.
                        if (Reminders.offPlan(on)) return@mapNotNull no("“${words.take(60)}” at a slot of “${on.title.take(60)}”, which never has one: it's ${if (on.kind == Kind.Event) "an event" else "off the plan"}")
                        // From the day it's asked for, or now.
                        val from = g.date.trim().takeIf { it.isNotEmpty() }?.let { at(it, null, LocalTime.MIDNIGHT, zone) ?: return@mapNotNull no("“${words.take(60)}” from a day that doesn't read") }
                        if (from != null && from > now + YEAR_MS) return@mapNotNull no("“${words.take(60)}” more than a year ahead")
                        Due(DueKind.SlotStart, maxOf(from ?: now, now))
                    } else {
                        val deadline = dueBy[on.id] ?: on.deadlineAt(now) ?: return@mapNotNull no("“${words.take(60)}” before “${on.title.take(60)}” is due, which has no due date")
                        val before = g.minutesBefore.takeIf { it in 0..Reminders.MAX_BEFORE_MIN } ?: return@mapNotNull no("“${words.take(60)}” before a task's due by more than a week, or by no time")
                        // Its moment from the deadline in force now: one gone by is refused, as an At's is.
                        val at = deadline - before * 60_000L
                        if (at < now - GONE_BY_MS) return@mapNotNull no("“${words.take(60)}” ${before} min before “${on.title.take(60)}” is due, which has gone by")
                        Due(DueKind.BeforeDue, minutesBefore = before)
                    }
                }
                else -> return@mapNotNull no("“${words.take(60)}” when the app doesn't know (${kind.take(20)})")
            }
            // An At keeps a task only as what it's about; a slot's or a deadline's is the task it waits on.
            val dueTask = taskId ?: was?.taskId
            if (was != null) {
                was.copy(text = words.ifEmpty { was.text }, due = due, taskId = dueTask).undrafted()
            } else {
                if (room <= 0) return@mapNotNull no("“${words.take(60)}”: ${Reminders.MAX_PENDING} are still to come already")
                room--
                Reminder(UUID.randomUUID().toString(), words, now, By.Felix, dueTask, due)
            }
        }
    }

    /**
     * [given] as notes the app keeps, at most [MAX_NOTES]: each with its words, on a listed task (open or
     * finished), no more on one than [Notes.PER_TASK] with [felix]'s; or standing (D-100), for every task
     * of a subject, a class, a source the app reads or with a word in its title, never on a task too, no more
     * than [Notes.MAX_STANDING], those the same answer deletes ([deleted]) not counted; what can't be is said in [leftOut].
     */
    private fun notes(given: List<GivenNote>, byId: Map<String, TaskItem>, felix: FelixStore, now: Long, leftOut: MutableList<String>, deleted: Set<String> = emptySet()): List<Note> {
        if (given.size > MAX_NOTES) leftOut += "${given.size - MAX_NOTES} more notes than the app takes at once"
        val added = HashMap<String, Int>()
        var standing = Notes.live(felix.notes).count { it.scope != null && it.id !in deleted }
        return given.take(MAX_NOTES).mapNotNull { g ->
            fun no(why: String) = null.also { leftOut += "a note ($why)" }
            val words = g.text.trim().take(Notes.MAX_TEXT)
            if (words.isEmpty()) return@mapNotNull no("one with no words")
            fun given(text: String) = text.trim().takeIf { it.isNotEmpty() }
            val source = given(g.source)?.let { name ->
                Source.entries.firstOrNull { it.name.equals(name.replace(" ", ""), ignoreCase = true) || it.label.equals(name, ignoreCase = true) }
                    ?: return@mapNotNull no("“${words.take(60)}” for a source the app doesn't read (${name.take(20)})")
            }
            if (listOf(g.subject, g.className, g.titleContains).any { it.trim().length > Notes.MAX_SCOPE }) return@mapNotNull no("“${words.take(60)}”, for tasks named in more than ${Notes.MAX_SCOPE} characters")
            val scope = NoteScope(given(g.subject), given(g.className), source, given(g.titleContains)).takeUnless { it.isEmpty }
            if (scope != null) {
                if (given(g.taskId) != null) return@mapNotNull no("“${words.take(60)}” on a task and for every task of a kind, both")
                if (standing >= Notes.MAX_STANDING) return@mapNotNull no("“${words.take(60)}”: ${Notes.MAX_STANDING} notes for every task of a kind are kept already")
                standing++
                return@mapNotNull Note(UUID.randomUUID().toString(), words, now, By.Felix, scope = scope)
            }
            val task = byId[g.taskId.trim()] ?: return@mapNotNull no("“${words.take(60)}” on a task that isn't listed")
            val count = Notes.on(task.id, felix.notes).count { it.id !in deleted } + (added[task.id] ?: 0)
            if (count >= Notes.PER_TASK) return@mapNotNull no("“${words.take(60)}”: “${task.title.take(60)}” has ${Notes.PER_TASK} already")
            added[task.id] = (added[task.id] ?: 0) + 1
            Note(UUID.randomUUID().toString(), words, now, By.Felix, taskId = task.id)
        }
    }

    /**
     * [g], a DueBy at a class's next lesson (G8), as one at that lesson's start, its own date and time not read;
     * null where it isn't one, or where the lesson can't be found ([lessonProblem] says why).
     */
    private fun lessonOf(g: InstructionAnswers.Given, context: ChatPrompts.Context?, now: Long, zone: ZoneId): InstructionAnswers.Given? {
        if (g.type != ChangeType.DueBy.name || g.lesson.isBlank()) return null
        val lessons = context?.lessons?.takeIf { !it.stale } ?: return null
        val at = Lessons.nextOf(g.lesson, lessons.groups, emptyMap()) ?: return null
        val start = Instant.ofEpochMilli(at.start).atZone(zone)
        return g.copy(date = start.toLocalDate().toString(), time = start.toLocalTime().format(CLOCK), lesson = "")
    }

    /** Why a due date at [lesson]'s next lesson can't be set (G8): no timetable read, one over a week old or of no known age, or no such class with a lesson ahead. */
    private fun lessonProblem(lesson: String, context: ChatPrompts.Context?, zone: ZoneId): String {
        val lessons = context?.lessons ?: return "a due date at the next lesson, with no timetable read: ask them the date"
        if (lessons.stale) {
            val read = lessons.readAt?.let { "read on " + Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) + ", over a week ago" } ?: "of no known age"
            return "a due date at the next lesson, from a timetable $read: ask them the date"
        }
        return "a due date at the next lesson of “${lesson.trim().take(40)}”, a class the lessons' list doesn't have, or with none ahead: ask them"
    }
    /** The deadlines [changes] set (DueBy), by task id: what a reminder before one goes by, made before their lay comes. */
    fun deadlines(changes: List<Change>): Map<String, Long> =
        changes.filter { it.type == ChangeType.DueBy && it.time != null }.mapNotNull { c -> c.taskId?.let { it to c.time!! } }.toMap()

    /** A reminder's time may be this far behind now (his reading of a minute), and no more. */
    private const val GONE_BY_MS = 60_000L
    private const val YEAR_MS = 366 * DAY_MS
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    /**
     * [a] as an action the app can take, checked against the tasks there are ([byId]), the [settings], [felix]'s
     * reminders and notes, and the pieces the [context] listed; null, and why in [leftOut], if it can't be.
     */
    private fun action(a: GivenAction, byId: Map<String, TaskItem>, settings: Settings?, felix: FelixStore, context: ChatPrompts.Context?, leftOut: MutableList<String>): ChatAction? {
        fun act(t: ChatAction.Type, id: String, step: Int? = null) = ChatAction(t, id, step)
        fun no(why: String) = null.also { leftOut += why }
        // A source read now (G2): one the app reads, by its name; about a listed task, or none.
        if (a.type == "sync") {
            val name = a.setting.trim()
            val source = Source.SYNCED.firstOrNull { it.name.equals(name.replace(" ", ""), ignoreCase = true) || it.label.equals(name, ignoreCase = true) }
                ?: return no("a source to read that the app doesn't (${name.take(20)})")
            val about = a.taskId.trim().takeIf { it.isNotEmpty() }?.let { id -> byId[id] ?: return no("a read about a task that isn't listed") }
            // Watched by a read of its own source only: another's can't say how it changed. The read's made all the same.
            if (about != null && about.source != source) leftOut += "watching “${about.title.take(40)}” with a read of ${source.label}: it's from ${about.source.label}"
            return ChatAction(ChatAction.Type.Sync, about?.takeIf { it.source == source }?.id.orEmpty(), setting = source.name)
        }
        // A piece kept as Day's Done keeps it, or taken back (G1): by its key, one the plan shown lists, or one kept today.
        if (a.type == "keepPiece" || a.type == "unkeepPiece") {
            val keep = a.type == "keepPiece"
            val key = a.value.trim()
            val of = (if (keep) context?.keepable else context?.kept)?.get(key)
                ?: return no(if (keep) "a piece to keep that the plan shown doesn't list" else "a piece to take back that isn't one kept today")
            // A finished task's piece isn't kept, nor taken back, but for one of yours (or one done by its tick) that the
            // piece may have finished: taken back, that opens it again (Conversation.act judges it was that piece).
            if (byId[of]?.let { it.isOpen || (!keep && (it.source == Source.Own || it.tickDone)) } != true) return no("a piece of a task that's finished")
            return ChatAction(if (keep) ChatAction.Type.KeepPiece else ChatAction.Type.UnkeepPiece, of, value = key)
        }
        // A reminder cancelled, still to come; a note deleted, still kept: by id, their task's taken from them.
        if (a.type == "cancelReminder") {
            val r = felix.reminders.firstOrNull { it.id == a.value.trim() }?.takeIf(Reminders::pending) ?: return no("a reminder to cancel that isn't one still to come")
            return ChatAction(ChatAction.Type.CancelReminder, r.taskId.orEmpty(), value = r.id)
        }
        if (a.type == "deleteNote") {
            val n = Notes.live(felix.notes).firstOrNull { it.id == a.value.trim() } ?: return no("a note to delete that isn't kept")
            return ChatAction(ChatAction.Type.DeleteNote, n.taskId.orEmpty(), value = n.id)
        }
        if (a.type == "setting") {
            val field = FelixSettings.field(a.setting) ?: return no("a setting the app doesn't let him change (${a.setting.trim().take(40)})")
            if (settings != null && FelixSettings.set(settings, field.name, a.value) == null) return no("“${field.label}” set to “${a.value.trim().take(40)}”, which it can't be")
            return ChatAction(ChatAction.Type.Setting, "", setting = field.name, value = a.value.trim())
        }
        val task = byId[a.taskId] ?: return no("an action on a task that isn't listed")
        val name = "“${task.title.take(60)}”"
        val own = task.source == Source.Own
        val step = task.subSteps.getOrNull(a.stepIndex)
        return when (a.type) {
            // Shown here, on a card; or where it lives (G3), at once: your own live here only.
            "open" -> if (a.value.trim() == OPEN_SOURCE && !own) ChatAction(ChatAction.Type.Open, task.id, value = OPEN_SOURCE) else act(ChatAction.Type.Open, task.id)
            "tickStep" -> if (task.isOpen && step?.done == false) act(ChatAction.Type.TickStep, task.id, a.stepIndex) else no("a step to tick that isn't one of $name's still to do")
            "untickStep" -> if (task.isOpen && step?.done == true) act(ChatAction.Type.UntickStep, task.id, a.stepIndex) else no("a step to untick that isn't one of $name's done")
            // Done by you: your own, or one done by its tick (NF-06), or a starred email, unstarred and archived in Gmail
            // (with the switch on); another is done when its source says.
            "done" -> when {
                !task.isOpen -> no("$name, finished already")
                own || task.tickDone -> act(ChatAction.Type.Done, task.id)
                task.source == Source.Gmail && settings?.gmailFinishes == true -> act(ChatAction.Type.Done, task.id)
                else -> no("$name is done when ${task.source.label} says so")
            }
            "reopen" -> if ((own || task.tickDone) && task.status == Status.Done) act(ChatAction.Type.Reopen, task.id) else no("$name to open again, which isn't one of their own finished")
            "delete" -> if (own) act(ChatAction.Type.Delete, task.id) else no("$name to delete, which is from ${task.source.label}: only a task of their own is deleted")
            else -> no("an action the app doesn't take")
        }
    }

    private fun task(n: NewTask, now: Long, zone: ZoneId): OwnTask? {
        val kind = OwnTaskRules.KINDS.firstOrNull { it.name == n.kind } ?: Kind.Homework
        val due = n.dueDate?.let { at(it, n.dueTime, if (kind == Kind.Event) LocalTime.of(9, 0) else LocalTime.of(23, 59), zone) ?: return null }
        val start = n.startDate?.let { at(it, null, LocalTime.MIDNIGHT, zone) ?: return null }
        val steps = n.steps.map { s ->
            OwnStep(
                title = s.title.trim(),
                minutes = s.minutes,
                from = s.startDate?.let { at(it, null, LocalTime.MIDNIGHT, zone) ?: return null },
                dueAt = s.dueDate?.let { at(it, null, LocalTime.of(23, 59), zone) ?: return null },
                anyOrder = s.anyOrder,
            )
        }
            .let { given ->
                // The task's minutes as Claude gave them, shared over its steps where theirs don't come to
                // the same: its total is what it said (and what the reply says), its steps the shape.
                val total = n.minutes.takeIf { it >= maxOf(1, given.size) && it in OwnTaskRules.MINUTES }
                if (given.isEmpty() || total == null || given.sumOf { it.minutes } == total || given.any { it.minutes <= 0 }) given
                else shareOut(total, given.map { it.minutes }).let { shares -> given.mapIndexed { i, s -> s.copy(minutes = shares[i]) } }
            }
        // Every date in it, its steps' too, within a month back and a year and a bit ahead.
        val dates = listOfNotNull(due, start) + steps.flatMap { listOfNotNull(it.from, it.dueAt) }
        if (dates.any { it !in (now - 31 * DAY_MS)..(now + 400 * DAY_MS) }) return null
        return OwnTask(
            title = n.title.trim(),
            notes = n.notes?.trim().orEmpty(),
            kind = kind,
            className = n.subject?.trim()?.takeIf { it.isNotEmpty() },
            dueAt = due,
            startFrom = start,
            minutes = n.minutes.coerceAtLeast(0),
            steps = steps,
        )
    }

    private fun at(date: String, time: String?, otherwise: LocalTime, zone: ZoneId): Long? {
        val day = runCatching { LocalDate.parse(date.trim().take(10)) }.getOrNull() ?: return null
        val clock = time?.let { t -> runCatching { LocalTime.parse(t.trim().take(5)) }.getOrNull() ?: return null } ?: otherwise
        return day.atTime(clock).atZone(zone).toInstant().toEpochMilli()
    }
}
