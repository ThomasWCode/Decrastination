package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.DayBreaks
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The string, if it's filled in: an unused field is empty, as the answers' schemas have no nullable fields. */
internal fun String?.filled(): String? = this?.takeIf { it.isNotBlank() }

/** Claude's reading of an instruction, checked: changes the app can make, or why there are none. */
object InstructionAnswers {

    sealed interface Reading {
        /** For your reply to Felix's question ([com.thomaswcode.decrastination.core.Instruction.answer]): whether it answers it; true for any other. */
        val answers: Boolean

        data class Changes(val changes: List<Change>, override val answers: Boolean = true) : Reading

        /** Nothing to apply: what Claude found unclear, or why its answer couldn't be used. */
        data class Unclear(val why: String, override val answers: Boolean = true) : Reading
    }

    @Serializable
    private data class Answer(val understood: Boolean, val question: String? = null, val changes: List<Given> = emptyList(), val answersQuestion: Boolean = true)

    /** One change as Claude gives it, before it's checked ([change]). */
    @Serializable
    internal data class Given(
        val type: String,
        val taskId: String? = null,
        val afterTaskId: String? = null,
        val eventKey: String? = null,
        val eventTime: String? = null,
        val minutes: Int? = null,
        val date: String? = null,
        val endDate: String? = null,
        val weekday: String? = null,
        val time: String? = null,
        val endTime: String? = null,
        val title: String = "",
        val steps: List<GivenStep> = emptyList(),
        /** The chat's only ([ChatPrompts]): a Minutes total that's the app's estimate at the student's pace. */
        val paced: Boolean = false,
        /** The chat's only (G8): a DueBy at this class's next lesson, its date and time the app's to set. */
        val lesson: String = "",
        /** A DayOnly's: what else its day lets in (D-115), empty for nothing. */
        val alsoAllow: String = "",
        /** An OutsideAt's or a NoOutside's break, by its own time (HH:MM): empty, for a NoOutside, all of the day's (D-116). */
        val outsideTime: String = "",
    ) {
        /** As given, but an empty string or a minutes of -1 is none: the schema has no nullable fields ([InstructionPrompts.CHANGE_FIELDS]). */
        fun normalised(): Given = copy(
            taskId = taskId.filled(), afterTaskId = afterTaskId.filled(), eventKey = eventKey.filled(), eventTime = eventTime.filled(),
            minutes = minutes?.takeIf { it >= 0 }, date = date.filled(), endDate = endDate.filled(), weekday = weekday.filled(),
            time = time.filled(), endTime = endTime.filled(),
        )
    }

    /** A step of a [ChangeType.Steps] change, its dates empty for none. */
    @Serializable
    internal data class GivenStep(val title: String = "", val minutes: Int = 0, val startDate: String = "", val dueDate: String = "", val where: String = "anywhere", val anyOrder: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true }

    /** More changes than this is a misreading, not an instruction. */
    const val MAX_CHANGES = 40
    private const val DAY_MS = 24 * 3_600_000L

    /**
     * [text] as Claude's reading, checked against what there is: the [tasks] it names must be
     * listed, the [events] too, and every date and time must read and be plausible. One change that
     * can't be made and none are: you're told why, and can write it again.
     */
    fun parse(text: String, tasks: List<TaskItem>, events: List<CalendarEvent>, about: About, now: Long, zone: ZoneId, breaksOn: (LocalDate) -> DayBreaks = { DayBreaks.NONE }): Reading {
        val answer = runCatching { json.decodeFromString(Answer.serializer(), text) }.getOrNull()
            ?: return Reading.Unclear("Claude's answer couldn't be read")
        if (!answer.understood || answer.changes.isEmpty()) {
            return Reading.Unclear(answer.question?.trim()?.takeIf { it.isNotEmpty() }?.take(300) ?: "Claude couldn't turn it into a change the app can make", answer.answersQuestion)
        }
        if (answer.changes.size > MAX_CHANGES) return Reading.Unclear("Claude read it as ${answer.changes.size} changes, more than the $MAX_CHANGES the app takes", answer.answersQuestion)
        val byId = tasks.associateBy { it.id }
        // The event it's about counts even if it's past the fortnight read.
        val titles = events.associate { EventJudge.key(it) to it.title } + listOfNotNull(about.eventKey?.let { it to (about.eventTitle ?: it) })
        // A day's breaks as the changes before each leave them (D-116), so one can use time another frees.
        val breaks = Breaks(breaksOn)
        val changes = answer.changes.map(Given::normalised).map { g ->
            change(g, byId, titles, now, zone, breaks)?.also(breaks::take) ?: return Reading.Unclear("Claude's reading couldn't be used: ${problem(g, byId, titles, now, zone, breaks)}", answer.answersQuestion)
        }
        stepsProblem(changes, byId)?.let { return Reading.Unclear("Claude's reading couldn't be used: $it", answer.answersQuestion) }
        // Tasks waiting on each other in a circle within this one reading: none would ever be planned.
        if (InstructionRules.makesCircle(changes, emptyList())) return Reading.Unclear("Claude's reading would have tasks wait for each other in a circle", answer.answersQuestion)
        return Reading.Changes(changes, answer.answersQuestion)
    }

    /**
     * [g] as a change the app can make, checked against the [tasks] and [events] there are, and for a day's breaks outside
     * against those it has ([breaksOn]); null if it can't be.
     */
    internal fun change(g: Given, tasks: Map<String, TaskItem>, events: Map<String, String>, now: Long, zone: ZoneId, breaksOn: (LocalDate) -> DayBreaks = { DayBreaks.NONE }): Change? {
        val type = ChangeType.entries.firstOrNull { it.name == g.type } ?: return null
        val task = g.taskId?.takeIf { it in tasks }
        return when (type) {
            ChangeType.NotATask -> task?.let { Change(type, taskId = it) }
            ChangeType.StartFrom -> task?.let { id -> at(g.date, g.time, LocalTime.MIDNIGHT, zone)?.takeIf { plausible(it, now) }?.let { Change(type, taskId = id, time = it) } }
            ChangeType.DueBy -> task?.let { id -> at(g.date, g.time, LocalTime.of(23, 59), zone)?.takeIf { plausible(it, now) }?.let { Change(type, taskId = id, time = it) } }
            // At least a minute for each of its steps, as yours are shared out over them.
            // A paced total keeps the app's estimate it came from, and the task's content, as they are now.
            ChangeType.Minutes -> task?.let { id ->
                val t = tasks.getValue(id)
                g.minutes?.takeIf { it in OwnTaskRules.minutesFor(t) }?.let {
                    Change(type, taskId = id, minutes = it, pacedFrom = t.appEffortMin.takeIf { g.paced }, pacedHash = Enrichments.inputHash(t).takeIf { g.paced })
                }
            }
            ChangeType.After -> task?.let { id -> g.afterTaskId?.takeIf { it in tasks && it != id }?.let { Change(type, taskId = id, afterTaskId = it) } }
            ChangeType.EventTime -> g.eventKey?.takeIf { it in events }?.let { key ->
                val answer = when (g.eventTime) {
                    "free" -> "free"
                    "busy" -> "busy"
                    "hours" -> g.minutes?.takeIf { it in 1..24 * 60 }?.let { "load:$it" }
                    else -> null
                } ?: return null
                Change(type, eventKey = key, eventTitle = events[key], eventAnswer = answer)
            }
            ChangeType.DayLimit -> {
                val minutes = g.minutes?.takeIf { it in 0..24 * 60 } ?: return null
                day(g, now)?.let { (date, weekday) -> Change(type, date = date, weekday = weekday, freeMin = minutes) }
            }
            ChangeType.BusyTime -> {
                val start = minute(g.time) ?: return null
                val end = minute(g.endTime)?.takeIf { it > start } ?: return null
                day(g, now)?.let { (date, weekday) -> Change(type, date = date, weekday = weekday, startMin = start, endMin = end) }
            }
            // A stretch of dates, not a weekday: the school calendar holds dates (PL-16).
            ChangeType.NoSchool -> {
                val first = day(g.copy(weekday = null), now)?.first ?: return null
                val last = g.endDate?.let { day(g.copy(date = it, weekday = null), now)?.first ?: return null } ?: first
                if (last < first) return null
                Change(type, date = first, endDate = last)
            }
            ChangeType.HalfDay -> {
                val date = day(g.copy(weekday = null), now)?.first ?: return null
                val end = minute(g.endTime) ?: return null
                Change(type, date = date, endMin = end)
            }
            // A day or a time for a task (PL-04): a date, not a weekday.
            ChangeType.OnDay -> task?.let { id -> day(g.copy(weekday = null), now)?.first?.let { Change(type, taskId = id, date = it) } }
            ChangeType.AtTime -> task?.let { id ->
                val date = day(g.copy(weekday = null), now)?.first ?: return null
                val start = minute(g.time) ?: return null
                at(date, g.time, LocalTime.MIDNIGHT, zone)?.takeIf { plausible(it, now) }?.let { Change(type, taskId = id, date = date, startMin = start, time = it) }
            }
            // A day for only some tasks (D-115): a date, not a weekday, as a day kept free of the rest once its tasks are done
            // would be every such day; what else it lets in where that's one of the two, else nothing.
            ChangeType.DayOnly -> task?.let { id ->
                val date = day(g.copy(weekday = null), now)?.first ?: return null
                Change(type, taskId = id, date = date, alsoAllow = g.alsoAllow.trim().takeIf { it == InstructionRules.ALSO_ANKI || it == InstructionRules.ALSO_DEADLINES })
            }
            // A day's breaks outside (D-116): on a date, never a weekday; one the day has, by the time it's named by (one of its
            // own taken off, moved to put it back), not begun (D-113); a time today not gone, the break over by midnight, on its
            // day; a break moved or added over none of the day's others, nor added at a time one is named by.
            ChangeType.OutsideAt -> {
                val date = day(g.copy(weekday = null), now)?.first ?: return null
                val own = minute(g.outsideTime) ?: return null
                val start = minute(g.time)?.takeIf { !gone(date, it, now, zone) } ?: return null
                val breaks = breaksOn(LocalDate.parse(date))
                if ((own !in breaks.at && own !in breaks.reserved) || own in breaks.begun || start + breaks.length > DAY_MIN || breaks.over(start, own)) return null
                Change(type, date = date, outsideMin = own, startMin = start)
            }
            ChangeType.NoOutside -> {
                val date = day(g.copy(weekday = null), now)?.first ?: return null
                val own = g.outsideTime.filled()?.let { minute(it) ?: return null } ?: -1
                val breaks = breaksOn(LocalDate.parse(date))
                if (own >= 0 && (own !in breaks.at || own in breaks.begun)) return null
                Change(type, date = date, outsideMin = own)
            }
            ChangeType.OutsideAdded -> {
                val date = day(g.copy(weekday = null), now)?.first ?: return null
                val start = minute(g.time)?.takeIf { !gone(date, it, now, zone) } ?: return null
                val breaks = breaksOn(LocalDate.parse(date))
                if (start in breaks.at || start in breaks.reserved || start + breaks.length > DAY_MIN || breaks.over(start)) return null
                Change(type, date = date, startMin = start)
            }
            // The same work as another listed task (DX-15), which is kept.
            ChangeType.SameAs -> task?.let { id -> g.afterTaskId?.takeIf { it in tasks && it != id }?.let { Change(type, taskId = id, afterTaskId = it) } }
            // A task's name in the app: not one derived from Anki's counts, whose name is their count.
            ChangeType.Rename -> task?.takeIf { !tasks.getValue(it).derived }?.let { id -> g.title.trim().takeIf { it.isNotEmpty() }?.let { Change(type, taskId = id, title = it.take(OwnTaskRules.MAX_TITLE)) } }
            // Any task's steps but Anki's, which are its cards, each with
            // its minutes and its dates readable (AI-19).
            ChangeType.Steps -> task?.takeIf { !tasks.getValue(it).derived && tasks.getValue(it).source != Source.Anki }?.let { id ->
                // A step's dates: readable, in range, its start not after its end, and neither after the task's day.
                val dueDay = tasks.getValue(id).dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                fun dayOf(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
                val steps = g.steps.takeIf { it.isNotEmpty() && it.size <= OwnTaskRules.MAX_STEPS }?.map { s ->
                    if (s.title.isBlank() || s.minutes < 1) return null
                    val from = s.startDate.takeIf { it.isNotBlank() }?.let { at(it, null, LocalTime.MIDNIGHT, zone)?.takeIf { t -> plausible(t, now) } ?: return null }
                    val due = s.dueDate.takeIf { it.isNotBlank() }?.let { at(it, null, LocalTime.of(23, 59), zone)?.takeIf { t -> plausible(t, now) } ?: return null }
                    if (from != null && due != null && from > due) return null
                    if (dueDay != null && listOfNotNull(from, due).any { dayOf(it) > dueDay }) return null
                    OwnStep(
                        title = s.title.trim().take(OwnTaskRules.MAX_TITLE),
                        minutes = s.minutes,
                        from = from,
                        dueAt = due,
                        where = s.where.takeIf { it == "home" || it == "anywhere" },
                        anyOrder = s.anyOrder,
                    )
                } ?: return null
                Change(type, taskId = id, steps = steps)
            }
        }
    }

    /**
     * Why a split among [changes] can't be taken (AI-19): its steps don't add up to its task's
     * minutes, and no Minutes change among them gives that new total. Null when each can.
     */
    internal fun stepsProblem(changes: List<Change>, tasks: Map<String, TaskItem>): String? {
        for (c in changes.filter { it.type == ChangeType.Steps }) {
            val task = tasks[c.taskId] ?: continue
            val sum = c.steps.orEmpty().sumOf { it.minutes }
            // Your own task's minutes as the form has them; a source's task's, the estimate in force.
            val total = if (task.source == Source.Own) {
                OwnTaskRules.draftOf(task).let { draft -> if (draft.steps.isEmpty()) draft.minutes else draft.steps.sumOf { it.minutes } }
            } else {
                task.effortMin
            }
            if (sum == total || changes.any { it.type == ChangeType.Minutes && it.taskId == c.taskId && it.minutes == sum }) continue
            return "the steps for “${task.title.take(60)}” add up to $sum minutes, not its $total, and nothing says its total changes"
        }
        return null
    }

    /** Why [g] couldn't be used, in words. */
    internal fun problem(g: Given, tasks: Map<String, TaskItem>, events: Map<String, String>, now: Long = 0, zone: ZoneId = ZoneId.of("UTC"), breaksOn: (LocalDate) -> DayBreaks = { DayBreaks.NONE }): String = when {
        g.type in setOf("OutsideAt", "NoOutside", "OutsideAdded") -> breakProblem(g, now, zone, breaksOn)
        ChangeType.entries.none { it.name == g.type } -> "a change of a kind the app doesn't make"
        g.type in setOf("NotATask", "StartFrom", "DueBy", "Minutes", "After", "OnDay", "AtTime", "Steps", "SameAs", "Rename", "DayOnly") && g.taskId !in tasks -> "it names a task that isn't listed"
        g.type == "DayOnly" && g.date == null -> "a day only for some tasks takes a date"
        g.type == "SameAs" -> "the task it's the same as isn't listed"
        g.type in setOf("Steps", "Rename") && tasks[g.taskId]?.let { it.derived || (g.type == "Steps" && it.source == Source.Anki) } == true -> "an Anki task's steps and name come from its cards"
        g.type == "Rename" -> "the new name is missing"
        g.type == "Steps" -> "a step has no name or minutes, or a date that doesn't read, is out of range or falls after the task's"
        g.type == "After" -> "it waits on a task that isn't listed"
        g.type == "EventTime" && g.eventKey !in events -> "it names an event that isn't in the calendar"
        else -> "a date, time or number in it is out of range"
    }

    /** Why a change to a day's breaks outside ([g]) couldn't be used, in words (D-116). */
    private fun breakProblem(g: Given, now: Long, zone: ZoneId, breaksOn: (LocalDate) -> DayBreaks): String {
        val date = day(g.copy(weekday = null), now)?.first ?: return "a day's breaks outside are changed on a date, never a weekday"
        val breaks = breaksOn(LocalDate.parse(date))
        val own = minute(g.outsideTime)
        val start = minute(g.time)
        return when {
            g.type != "OutsideAdded" && g.outsideTime.isNotBlank() && own == null -> "the break's time doesn't read"
            g.type != "OutsideAdded" && own != null && own !in breaks.at && !(g.type == "OutsideAt" && own in breaks.reserved) -> "there's no ${g.outsideTime.trim().take(5)} break outside that day"
            g.type != "OutsideAdded" && own != null && own in breaks.begun -> "that break outside has begun, and is kept as it is"
            g.type == "OutsideAt" && g.outsideTime.isBlank() -> "it doesn't say which break outside"
            g.type != "NoOutside" && start == null -> "the time doesn't read"
            g.type != "NoOutside" && start != null && gone(date, start, now, zone) -> "that time's gone"
            g.type != "NoOutside" && start != null && start + breaks.length > DAY_MIN -> "a break outside is over by midnight, on its day"
            g.type != "NoOutside" && start != null && breaks.over(start, own ?: -1) -> "it would be over another break outside"
            g.type == "OutsideAdded" && start != null && (start in breaks.at || start in breaks.reserved) -> "a break outside of that day's is named by that time: move or put back that one instead"
            else -> "a date, time or number in it is out of range"
        }
    }

    /** A day's minutes: a break outside is over by its end. */
    private const val DAY_MIN = 24 * 60

    /**
     * [breaksOn] as the changes kept so far leave each day's breaks (D-116, [take] each as it's kept), so one change of an answer can
     * use time another frees: a break taken off, and another moved there.
     */
    internal class Breaks(private val breaksOn: (LocalDate) -> DayBreaks) : (LocalDate) -> DayBreaks {
        private val days = HashMap<LocalDate, DayBreaks>()

        override fun invoke(day: LocalDate): DayBreaks = days.getOrPut(day) { breaksOn(day) }

        /** [change], kept: its day's breaks as it leaves them. */
        fun take(change: Change) {
            val day = change.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
            days[day] = invoke(day).with(change)
        }
    }

    /** Whether [minute] of [date] (YYYY-MM-DD) has gone by at [now]: the day's clock time, as the planner lays it, on a day the clocks change too. */
    private fun gone(date: String, minute: Int, now: Long, zone: ZoneId): Boolean =
        LocalDate.parse(date).atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli() < now

    /** A day's date (YYYY-MM-DD) or weekday (1–7), one of the two. */
    private fun day(g: Given, now: Long): Pair<String?, Int?>? {
        g.date?.let { text ->
            val date = runCatching { LocalDate.parse(text.trim().take(10)) }.getOrNull() ?: return null
            // A day long past, or more than a year off, is a misreading.
            val today = LocalDate.ofEpochDay(now / DAY_MS)
            if (date < today.minusDays(1) || date > today.plusDays(400)) return null
            return date.toString() to null
        }
        val weekday = DayOfWeek.entries.firstOrNull { it.name.equals(g.weekday?.trim(), ignoreCase = true) } ?: return null
        return null to weekday.value
    }

    private fun minute(text: String?): Int? = text?.trim()?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }?.let { it.hour * 60 + it.minute }

    private fun at(date: String?, time: String?, otherwise: LocalTime, zone: ZoneId): Long? {
        val day = date?.let { runCatching { LocalDate.parse(it.trim().take(10)) }.getOrNull() } ?: return null
        val clock = time?.let { t -> runCatching { LocalTime.parse(t.trim().take(5)) }.getOrNull() ?: return null } ?: otherwise
        return day.atTime(clock).atZone(zone).toInstant().toEpochMilli()
    }

    /** Within a month before now and a year and a bit after. */
    private fun plausible(time: Long, now: Long): Boolean = time in (now - 31 * DAY_MS)..(now + 400 * DAY_MS)
}
