package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.DayBucket
import com.thomaswcode.decrastination.core.FelixSettings
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.shareOut
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
     * (PL-04: a pin whose time isn't free goes to the first free time after it), a line each, as
     * the answer names the time asked for: "06:35 had no free time: it's at 10:45." A minute or
     * less apart is the time kept. Pure.
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
            val laid = slots.firstOrNull { it.pinned } ?: slots.minByOrNull { kotlin.math.abs(it.start - asked) }
            when {
                laid == null -> "${at(asked).format(hm)} had no free time, nor any after it that day."
                kotlin.math.abs(laid.start - asked) <= 60_000L -> null
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
        /** What it asks to look up on the web first (16 Oct, [WebResearcher]): its answer is then only that it's looking. */
        val research: String? = null,
        /** The tasks whose whole text it asks to read first (D-93): its answer is then only that it's reading. */
        val details: List<String> = emptyList(),
    )

    /**
     * [reading]'s cards, each made as it comes, with its Undo (your call, 16 Oct: nothing he does waits
     * for a button; AI-24 had only changes to what exists made so): its changes on one card, first;
     * then its new tasks, a card each; then a card for each task to show (AI-14), with its Open.
     */
    fun cards(reading: Reading, tasks: Map<String, TaskItem>, zone: ZoneId, id: () -> String = { UUID.randomUUID().toString() }): List<Proposal> {
        val changes = reading.changes.takeIf { it.isNotEmpty() }?.let {
            Proposal(id(), changes = it, lines = it.map { c -> Instructions.describe(c, tasks, zone) }, instant = true)
        }
        val added = reading.tasks.map { Proposal(id(), task = it, instant = true) }
        val opens = reading.actions.filter { it.type == ChatAction.Type.Open }.map { Proposal(id(), open = it.taskId) }
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
    )

    @Serializable
    private data class GivenAction(val type: String = "", val taskId: String = "", val stepIndex: Int = -1, val setting: String = "", val value: String = "")

    /** The most actions one answer takes: a few at most is what's meant (ten since there are more kinds, 16 Oct). */
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
    private data class NewStep(val title: String = "", val minutes: Int = 0, val startDate: String? = null, val dueDate: String? = null) {
        fun normalised(): NewStep = copy(startDate = startDate.filled(), dueDate = dueDate.filled())
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** More than this is a misreading, not a message. */
    const val MAX_TASKS = 10
    const val MAX_CHANGES = 40
    private const val DAY_MS = 24 * 3_600_000L

    /**
     * [text] as Claude's answer, checked against what there is: each task as the form would take it
     * ([OwnTasks.problem]) with plausible dates, each change as an instruction's is; what can't be
     * used is left out and said ([Reading.leftOut]), the rest kept. Null if it can't be read at all.
     */
    /** The most memories one answer gives: a few at most is what's meant. */
    private const val MAX_MEMORIES = 5

    fun parse(text: String, tasks: List<TaskItem>, events: List<CalendarEvent>, about: About, now: Long, zone: ZoneId, settings: Settings? = null): Reading? {
        val answer = runCatching { json.decodeFromString(Answer.serializer(), text) }.getOrNull() ?: return null
        val reply = answer.reply.trim().takeIf { it.isNotEmpty() } ?: return null
        val leftOut = mutableListOf<String>()
        val made = answer.newTasks.take(MAX_TASKS).map(NewTask::normalised).mapNotNull { n ->
            val task = task(n, now, zone)
            val problem = if (task == null) "a date in it doesn't read, or is out of range" else OwnTasks.problem(task)
            if (problem != null) leftOut += "the task “${n.title.trim().take(60)}” (${problem.replaceFirstChar(Char::lowercase)})"
            task.takeIf { problem == null }
        }
        if (answer.newTasks.size > MAX_TASKS) leftOut += "${answer.newTasks.size - MAX_TASKS} more tasks than the app takes at once"
        val byId = tasks.associateBy { it.id }
        val titles = events.associate { EventJudge.key(it) to it.title } + listOfNotNull(about.eventKey?.let { it to (about.eventTitle ?: it) })
        var changes = answer.changes.take(MAX_CHANGES).map(InstructionAnswers.Given::normalised).mapNotNull { g ->
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
        if (Instructions.makesCircle(changes, emptyList())) {
            leftOut += "changes that would have tasks wait for each other in a circle"
            changes = emptyList()
        }
        val replies = answer.suggestedReplies.map { it.trim().take(FelixAnswers.MAX_REPLY) }.filter { it.isNotEmpty() }.distinct().take(FelixAnswers.MAX_REPLIES)
        // A step ticked or unticked, a listed task shown (AI-14); a task of yours done, open again or
        // deleted, a setting set (16 Oct): each only as the task, or the setting, can be.
        val actions = answer.actions.take(MAX_ACTIONS).mapNotNull { a -> action(a, byId, settings, leftOut) }.distinct()
        // Tasks to read in full (D-93): listed and open, not the one the message is about (it came whole).
        val details = answer.details.map(String::trim).filter { id -> id != about.taskId && byId[id]?.let { it.isOpen && !it.hidden } == true }.distinct().take(ChatPrompts.MAX_READ)
        return Reading(reply.take(ChatPrompts.MAX_TEXT), made, changes, leftOut, answer.memories.take(MAX_MEMORIES), replies, actions, answer.research.trim().take(ResearchPrompts.MAX_QUESTION).takeIf { it.isNotEmpty() }, details)
    }

    /** [a] as an action the app can take, checked against the tasks there are ([byId]) and the [settings]; null, and why in [leftOut], if it can't be. */
    private fun action(a: GivenAction, byId: Map<String, TaskItem>, settings: Settings?, leftOut: MutableList<String>): ChatAction? {
        fun act(t: ChatAction.Type, id: String, step: Int? = null) = ChatAction(t, id, step)
        fun no(why: String) = null.also { leftOut += why }
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
            "open" -> act(ChatAction.Type.Open, task.id)
            "tickStep" -> if (task.isOpen && step?.done == false) act(ChatAction.Type.TickStep, task.id, a.stepIndex) else no("a step to tick that isn't one of $name's still to do")
            "untickStep" -> if (task.isOpen && step?.done == true) act(ChatAction.Type.UntickStep, task.id, a.stepIndex) else no("a step to untick that isn't one of $name's done")
            // Done by you: your own, or one done by its tick (NF-06); another is done when its source says.
            "done" -> when {
                !task.isOpen -> no("$name, finished already")
                own || task.tickDone -> act(ChatAction.Type.Done, task.id)
                else -> no("$name is done when ${task.source.label} says so")
            }
            "reopen" -> if ((own || task.tickDone) && task.status == Status.Done) act(ChatAction.Type.Reopen, task.id) else no("$name to open again, which isn't one of their own finished")
            "delete" -> if (own) act(ChatAction.Type.Delete, task.id) else no("$name to delete, which is from ${task.source.label}: only a task of their own is deleted")
            else -> no("an action the app doesn't take")
        }
    }

    private fun task(n: NewTask, now: Long, zone: ZoneId): OwnTask? {
        val kind = OwnTasks.KINDS.firstOrNull { it.name == n.kind } ?: Kind.Homework
        val due = n.dueDate?.let { at(it, n.dueTime, if (kind == Kind.Event) LocalTime.of(9, 0) else LocalTime.of(23, 59), zone) ?: return null }
        val start = n.startDate?.let { at(it, null, LocalTime.MIDNIGHT, zone) ?: return null }
        val steps = n.steps.map { s ->
            OwnStep(
                title = s.title.trim(),
                minutes = s.minutes,
                from = s.startDate?.let { at(it, null, LocalTime.MIDNIGHT, zone) ?: return null },
                dueAt = s.dueDate?.let { at(it, null, LocalTime.of(23, 59), zone) ?: return null },
            )
        }
            .let { given ->
                // The task's minutes as Claude gave them, shared over its steps where theirs don't come to
                // the same: its total is what it said (and what the reply says), its steps the shape.
                val total = n.minutes.takeIf { it >= maxOf(1, given.size) && it in OwnTasks.MINUTES }
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
