package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.sources.powerplanner.PpClass
import com.thomaswcode.decrastination.sources.powerplanner.PpSchedule
import com.thomaswcode.decrastination.sources.powerplanner.Timetable
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.KSerializer

/**
 * A fixed state planned, as text, against `fixtures/snapshots/plan.txt` (docs/plan_v4, Stage 0): tasks
 * from every source, steps of each kind (the source's, a reading's, the rules', yours, Felix's, your
 * own task's), instructions in use with a pin, a school calendar, busy time and a learned pace, made
 * into a plan as the app makes one. Any move that changes a plan shows here, to the piece and the
 * minute. Written again only on purpose: `./gradlew testDebugUnitTest -PupdateSnapshots=true`.
 */
class PlanSnapshotTest {
    private val file = listOf(File("../fixtures"), File("fixtures")).firstOrNull { it.isDirectory }?.let { File(it, "snapshots/plan.txt") }
        ?: error("No fixtures (run the tests from the repository or the app module)")

    private fun at(local: String) = Fixtures.at(local)

    /** Monday, a school day, just before the evening's work starts. */
    private val now = at("2026-10-19T16:30")

    private val settings = Settings(classMap = mapOf("12X/Hi1" to "History", "12X/Ph1" to "Physics"))

    private val yourSteps = listOf(OwnStep("Questions 1 to 6", 20), OwnStep("Questions 7 to 12", 25, where = "home"))
    private val felixSteps = listOf(OwnStep("Read the brief", 15), OwnStep("Plan the poster", 30, where = "home"))

    private fun state(): TaskState {
        val seen = at("2026-10-12T17:00")
        fun read(task: TaskItem, by: String, steps: List<SubStep>, effort: Int, kind: Kind? = null, deadline: Long? = null, quoted: Boolean = false) =
            task.withEnrichment(Enrichment(Enrichments.inputHash(task), by, seen, kind = kind, deadline = deadline, deadlineQuoted = quoted, effortMin = effort, subSteps = steps, promptVersion = 7))
        val essay = read(
            TaskItem(id = "teams:essay", source = Source.Teams, sourceId = "essay", title = "History essay", detail = "1,200 words from two sources.", className = "12X/Hi1",
                dueAt = at("2026-10-23T09:00"), kind = Kind.Homework, sourceEffortMin = 120, firstSeenAt = seen, lastSeenAt = seen, sourceValues = SourceValues(Kind.Homework, at("2026-10-23T09:00"), null, "History essay")),
            "claude-opus-5-5", listOf(SubStep("Read the two sources", 30), SubStep("Plan the essay", 25), SubStep("Write the essay", 80, where = "home")), 135,
        )
        val sheet = TaskItem(id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Forces worksheet", className = "12X/Ph1", dueAt = at("2026-10-20T09:00"),
            kind = Kind.Homework, sourceEffortMin = 40, firstSeenAt = seen, lastSeenAt = seen, sourceValues = SourceValues(Kind.Homework, at("2026-10-20T09:00"), null, "Forces worksheet"))
        val poster = TaskItem(id = "powerplanner:poster", source = Source.PowerPlanner, sourceId = "poster", title = "Biology poster", className = "Biology", dueAt = at("2026-10-22T09:00"),
            kind = Kind.Homework, sourceEffortMin = 45, firstSeenAt = seen, lastSeenAt = seen, sourceValues = SourceValues(Kind.Homework, at("2026-10-22T09:00"), null, "Biology poster"))
        val deck = TaskItem(id = "anki:quota", source = Source.Anki, sourceId = "quota", title = "Anki: today's cards", kind = Kind.Revision, sourceEffortMin = 20, dueAt = at("2026-10-19T21:30"),
            subSteps = listOf(SubStep("Reviews", 10), SubStep("New cards", 10, ankiSections = listOf("2.3"))), stepsPerDay = 2, derived = true, firstSeenAt = seen, lastSeenAt = seen)
        val form = read(
            TaskItem(id = "gmail:form", source = Source.Gmail, sourceId = "form", title = "Trip consent form", detail = "Please return the form by Wednesday.", kind = Kind.Info, sourceEffortMin = 2,
                firstSeenAt = seen, lastSeenAt = seen, extra = mapOf("received" to at("2026-10-14T08:30").toString()), sourceValues = SourceValues(Kind.Info, null, null, "Trip consent form")),
            "rules", listOf(SubStep("Sign and return the form", 10)), 10, kind = Kind.Admin, deadline = at("2026-10-21T23:59"),
        )
        val newsletter = TaskItem(id = "gmail:news", source = Source.Gmail, sourceId = "news", title = "Newsletter", kind = Kind.Info, sourceEffortMin = 2, firstSeenAt = seen, lastSeenAt = seen)
        val sameSheet = TaskItem(id = "powerplanner:sheet", source = Source.PowerPlanner, sourceId = "sheet", title = "Forces worksheet", kind = Kind.Homework, sourceEffortMin = 40,
            dueAt = at("2026-10-20T09:00"), workedMin = 10, firstSeenAt = seen, lastSeenAt = seen)
        val overdue = TaskItem(id = "teams:late", source = Source.Teams, sourceId = "late", title = "Chemistry questions", className = "12X/Ch1", dueAt = at("2026-10-16T09:00"),
            kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = seen, lastSeenAt = seen)
        val later = TaskItem(id = "teams:later", source = Source.Teams, sourceId = "later", title = "Spring project", dueAt = at("2027-03-01T09:00"), availableFrom = at("2027-02-01T08:00"),
            kind = Kind.Homework, sourceEffortMin = 60, firstSeenAt = seen, lastSeenAt = seen)
        val done = TaskItem(id = "teams:done", source = Source.Teams, sourceId = "done", title = "Lab report", kind = Kind.Homework, sourceEffortMin = 50, status = Status.Done,
            firstSeenAt = seen, lastSeenAt = seen, doneAt = at("2026-10-15T20:00"))
        val revise = OwnTasks.create(OwnTask("Revise for the maths test", kind = Kind.Revision, dueAt = at("2026-10-22T09:00"), minutes = 60, steps = listOf(OwnStep("Chapter 3", 30, where = "home"), OwnStep("Chapter 4", 30, where = "home"))), "revise", seen)
        val undated = OwnTasks.create(OwnTask("Sort the folder", kind = Kind.Admin, minutes = 15), "folder", seen)
        val event = OwnTasks.create(OwnTask("Open evening", kind = Kind.Event, dueAt = at("2026-10-21T18:00"), minutes = 0), "open-evening", seen)
        val timetable = Timetable(
            "2026-09-07T00:00:00",
            listOf(PpClass("study", "Study Period", listOf(PpSchedule("2026-09-05T11:15:00", "2026-09-05T12:15:00", 2, 3), PpSchedule("2026-09-05T09:00:00", "2026-09-05T10:00:00", 3, 3))), PpClass("bio", "Biology", listOf(PpSchedule("2026-09-05T13:55:00", "2026-09-05T14:55:00", 1, 3)))),
        )
        return TaskState(tasks = listOf(essay, sheet, poster, deck, form, newsletter, sameSheet, overdue, later, done, revise, undated, event), timetable = timetable)
    }

    private fun instructions(): List<Instruction> {
        var n = 0
        fun applied(vararg changes: Change) = Instruction("i-${++n}", "made up", About(), at("2026-10-18T12:00") + n, InstructionStatus.Applied, changes.toList(), appliedAt = at("2026-10-18T12:00") + n)
        return listOf(
            applied(Change(ChangeType.Minutes, taskId = "teams:essay", minutes = 150, pacedFrom = 135, pacedHash = "paced")),
            // The pin: the essay's next piece at five tomorrow.
            applied(Change(ChangeType.AtTime, taskId = "teams:essay", date = "2026-10-20", startMin = 17 * 60, time = at("2026-10-20T17:00"))),
            applied(Change(ChangeType.Steps, taskId = "teams:sheet", steps = yourSteps)),
            // Felix's, from the chat: steps, all on Tuesday, due a day sooner.
            applied(Change(ChangeType.Steps, taskId = "powerplanner:poster", steps = felixSteps), Change(ChangeType.OnDay, taskId = "powerplanner:poster", date = "2026-10-20"), Change(ChangeType.DueBy, taskId = "powerplanner:poster", time = at("2026-10-21T17:00"))),
            applied(Change(ChangeType.After, taskId = "gmail:form", afterTaskId = "teams:essay")),
            applied(Change(ChangeType.NotATask, taskId = "gmail:news")),
            applied(Change(ChangeType.SameAs, taskId = "powerplanner:sheet", afterTaskId = "teams:sheet")),
            applied(Change(ChangeType.StartFrom, taskId = "own:revise", time = at("2026-10-20T00:00"))),
            applied(Change(ChangeType.BusyTime, weekday = 2, startMin = 20 * 60, endMin = 21 * 60)),
            applied(Change(ChangeType.DayLimit, date = "2026-10-21", freeMin = 60)),
        )
    }

    private val school = SchoolCalendar(listOf(NoSchool("2026-10-23", "2026-10-23", "Inset day"), NoSchool("2026-10-26", "2026-10-30", "Half term")), listOf(HalfDay("2026-10-22", 12 * 60 + 30)))

    private val calibration = Calibration(
        yields = mapOf(DayKind.Weekday to 0.8, DayKind.Weekend to 0.7), yieldDays = mapOf(DayKind.Weekday to 10, DayKind.Weekend to 8),
        multipliers = mapOf("Homework|" to 1.2, "Revision|" to 0.9), marginDays = mapOf(Kind.Homework to 1), timed = mapOf("Homework|" to 12, "Revision|" to 7), counted = true,
    )

    /** Football on Tuesday evening, and a day with an all-day event's few hours. */
    private val busy = listOf(Busy(at("2026-10-20T18:00"), at("2026-10-20T19:00")))
    private val dayLoads = mapOf(LocalDate.parse("2026-10-24") to 90)

    /** Today's kept slot (its minutes off today's limit), and a slot missed earlier today. */
    private val log = ActivityLog(
        slots = listOf(SlotRecord("2026-10-19", "anki:quota#0", "anki:quota", Kind.Revision, null, "Anki: today's cards: Reviews", 10, at("2026-10-19T12:20"), at("2026-10-19T12:30"), WindowKind.Lunch, SlotOutcome.Kept, 10, at = at("2026-10-19T12:30"))),
    )
    private val slots = SlotState("2026-10-19", previous = mapOf("teams:sheet#0" to at("2026-10-19T17:10")), missed = listOf(MissedSlot("teams:late#-1#1", "teams:late", at("2026-10-19T11:30"))))

    /** The plan as the app makes it ([Planning.plan]), from temporary stores holding the fixed state, and the calendar as last read. */
    private fun plan(): Plan {
        val dir = Files.createTempDirectory("snapshot").toFile()
        fun <T> store(name: String, serializer: KSerializer<T>, value: T) = JsonStore(File(dir, name), serializer, { value })
        val clock = FixedClock(now, LONDON)
        val settingsStore = store("settings.json", Settings.serializer(), settings)
        val runtimeStore = store("runtime.json", RuntimeState.serializer(), RuntimeState(calibration = calibration, slots = slots))
        val tasksStore = store("tasks.json", TaskState.serializer(), state())
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val planning = Planning(
            tasksStore, settingsStore, runtimeStore, store("log.json", ActivityLog.serializer(), log), store("instructions.json", InstructionState.serializer(), InstructionState(instructions())),
            store("school.json", SchoolCalendar.serializer(), school), clock, scope, ModelGateway(settingsStore, { null }, runtimeStore, tasksStore, clock, NoAlerts, scope) {},
            calendarTime = { EventJudge.Time(busy, dayLoads, emptyList()) },
            calendarChanges = emptyFlow<Unit>(),
            sweep = {},
            hooks = emptyList(),
            profile = { error("A plan asks nothing of the profile") },
            draftAhead = { _, _ -> },
            onFailure = { throw it },
        )
        return planning.plan()
    }

    /** No alerts: there's no key, and nothing's called. */
    private object NoAlerts : ModelGateway.Alerts {
        override fun keyProblem(problem: KeyProblem): Boolean = false
        override fun keyFixed() = Unit
        override fun dropped(task: TaskItem, why: String): Boolean = false
        override fun withdrawExcept(standing: Set<String>) = Unit
    }

    @Test
    fun `the fixed state plans as its snapshot`() {
        val text = describe(plan(), LONDON)
        if (System.getProperty("updateSnapshots") == "true") {
            file.parentFile.mkdirs()
            file.writeText(text)
            return
        }
        if (!file.exists()) fail("No snapshot at $file: run with -PupdateSnapshots=true and commit it")
        val was = file.readText().replace("\r\n", "\n")
        if (was != text) {
            val first = was.lines().zip(text.lines()).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(was.lines().size, text.lines().size) else it }
            fail("The plan differs from its snapshot from line ${first + 1}:\n- ${was.lines().getOrNull(first)}\n+ ${text.lines().getOrNull(first)}\nIf that's meant, run with -PupdateSnapshots=true and commit it.")
        }
    }

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.UK)
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

        private fun Long?.at(zone: ZoneId): String = this?.let { Instant.ofEpochMilli(it).atZone(zone).format(TIME) } ?: "-"

        private fun Long.clock(zone: ZoneId): String = Instant.ofEpochMilli(this).atZone(zone).format(CLOCK)

        private fun Chunk.line(zone: ZoneId): String = buildString {
            append(key).append(" \"").append(label).append("\" ").append(minutes).append(" min")
            append(", due ").append(dueAt.at(zone)).append(if (hard) " hard" else "").append(", deadline ").append(deadline.at(zone)).append(if (soft) " soft" else "")
            append(", why ").append(why).append(", part ").append(part).append('/').append(parts).append(" of ").append(taskMinutes).append(" min")
            append(", ").append(where)
            listOfNotNull("overdue".takeIf { overdue }, "dueToday".takeIf { dueToday }, "dueSoon".takeIf { dueSoon }, "behind".takeIf { behind }).takeIf { it.isNotEmpty() }?.let { append(" [").append(it.joinToString()).append(']') }
            rankedBy?.let { append(", ranked by ").append(it.at(zone)) }
            availableAt?.let { append(", from ").append(it.at(zone)) }
            box?.let { append(", box ").append(it) }
            stepIndex?.let { append(", step ").append(it) }
            sitting?.let { append(", sitting ").append(it.after).append(" \"").append(it.title).append('"') }
        }

        /** [plan] as text: each day's pieces, slots and pieces with no slot; then what isn't planned, what won't fit, the events, and what's next. */
        fun describe(plan: Plan, zone: ZoneId): String = buildString {
            appendLine("now ${plan.now.at(zone)}, today ${plan.today}")
            appendLine("next ${plan.next?.key}, then ${plan.then?.key}")
            for (bucket in plan.buckets) {
                appendLine("day ${bucket.date} ${bucket.date.dayOfWeek.name.take(3)}: ${bucket.plannedMin} of ${bucket.capacityMin} min")
                bucket.chunks.forEach { appendLine("  piece ${it.line(zone)}") }
                bucket.slots.forEach { appendLine("  slot ${it.kind} ${it.start.clock(zone)}-${it.end.clock(zone)} ${it.window} ${it.key}${if (it.pinned) " pinned" else ""}") }
                bucket.unslotted.forEach { appendLine("  unslotted ${it.key}") }
            }
            plan.unplanned.forEach { appendLine("unplanned ${it.taskId} \"${it.title}\" ${it.minutes} min in ${it.pieces}: ${it.reason}${it.at?.let { a -> " at ${a.at(zone)}" }.orEmpty()}${it.after?.let { a -> " after \"$a\"" }.orEmpty()}") }
            plan.wontFit.forEach { appendLine("won't fit ${it.taskId} \"${it.title}\": ${it.leftMin} min left, ${it.freeMin} free before ${it.deadline.at(zone)}") }
            plan.events.forEach { appendLine("event ${it.id} \"${it.title}\" at ${it.dueAt.at(zone)}") }
        }
    }
}
