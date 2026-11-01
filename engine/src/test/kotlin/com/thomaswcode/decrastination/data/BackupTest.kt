package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.Due
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.FelixState
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.NoteScope
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.WeeklyMark
import com.thomaswcode.decrastination.core.WeeklyRule
import com.thomaswcode.decrastination.core.WeeklyRules
import com.thomaswcode.decrastination.core.Window
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.core.yours
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.enrich.AiUsage
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    private val now = Fixtures.at("2026-10-09T20:00")

    private fun completion(id: String, done: Long) = CompletionRecord(
        taskId = id,
        title = id,
        source = Source.Teams,
        kind = Kind.Homework,
        estimateMin = 40,
        workedMin = 10,
        firstSeenAt = done - 86_400_000L,
        doneAt = done,
    )

    @Test
    fun `a backup reads back as it was written`() {
        val backup = Backup(
            exportedAt = now,
            versionName = "1.0.0",
            settings = Settings(boxMin = 30, blockedSites = listOf("youtube.com")),
            log = ActivityLog(completions = listOf(completion("teams:a", now))),
            calibration = Calibration(multipliers = mapOf("Homework|" to 1.2)),
            eventAnswers = mapOf("Drum lesson" to "busy"),
        )
        assertEquals(backup, Backups.decode(Backups.encode(backup), emptyMap()))
    }

    @Test
    fun `a backup keeps the school calendar, and one from before has none`() {
        val school = SchoolCalendar(listOf(NoSchool("2026-10-26", "2026-10-30", "Half term")), listOf(HalfDay("2026-10-23", 750)))
        val backup = Backup(exportedAt = now, settings = Settings(), log = ActivityLog(), school = school)
        assertEquals(Backup.FORMAT, backup.format)
        // A backup from a later app's format is refused, rather than read without what it adds.
        assertNull(Backups.decode("""{"app":"Decrastination","format":${Backup.FORMAT + 1},"exportedAt":0,"settings":{},"log":{}}""", emptyMap()))
        assertEquals(school, Backups.decode(Backups.encode(backup), emptyMap())!!.school)
        // Format 2 (2.0.0) is still read, with no days off.
        val old = Backups.decode("""{"app":"Decrastination","format":2,"exportedAt":0,"settings":{},"log":{}}""", emptyMap())!!
        assertEquals(SchoolCalendar(), old.school)
        assertNull(Backups.problem(old))
        // One the app couldn't have kept is refused whole.
        val bad = backup.copy(school = SchoolCalendar(listOf(NoSchool("2026-10-30", "2026-10-26"))))
        assertNotNull(Backups.problem(bad))
    }

    @Test
    fun `what isn't one of this app's backups, or is from a newer one, isn't read`() {
        assertNull(Backups.decode("not json", emptyMap()))
        assertNull(Backups.decode("""{"app":"Something else","format":1,"exportedAt":0,"settings":{},"log":{}}""", emptyMap()))
        assertNull(Backups.decode("""{"app":"Decrastination","format":${Backup.FORMAT + 1},"exportedAt":0,"settings":{},"log":{}}""", emptyMap()))
        assertNotNull(Backups.decode("""{"app":"Decrastination","format":${Backup.FORMAT},"exportedAt":0,"settings":{},"log":{}}""", emptyMap()))
        // One from before 2.0.0 (format 1, no tasks of your own) is still read.
        assertNotNull(Backups.decode("""{"app":"Decrastination","format":1,"exportedAt":0,"settings":{},"log":{}}""", emptyMap()))
    }

    @Test
    fun `a file over the cap isn't read whole`() {
        assertEquals(3, Backups.read(ByteArrayInputStream(ByteArray(3)), cap = 4)?.size)
        assertNull(Backups.read(ByteArrayInputStream(ByteArray(5)), cap = 4))
    }

    @Test
    fun `restoring keeps this phone's protection and key, whatever the backup says`() {
        val here = Settings(armed = true, aiKeyActive = false)
        val backup = Backup(exportedAt = now, settings = Settings(armed = false, aiKeyActive = true, boxMin = 30), log = ActivityLog())
        val imported = Backups.importedSettings(here, backup)
        assertEquals(true, imported.armed)
        assertEquals(false, imported.aiKeyActive)
        assertEquals(30, imported.boxMin)
        // The calendars are this phone's: ids from another phone mean other calendars here.
        val calendars = Backups.importedSettings(here.copy(calendarWriteId = 7L, calendarsExcluded = listOf(3L)), backup.copy(settings = backup.settings.copy(calendarWriteId = 12L, calendarsExcluded = listOf(5L, 6L))))
        assertEquals(7L, calendars.calendarWriteId)
        assertEquals(listOf(3L), calendars.calendarsExcluded)
    }

    @Test
    fun `a restore takes in the month's spend on Claude, never lowering it`() {
        val month = "2026-10"
        val here = AiUsage(month = month, spentUsd = 3.0, calls = 10)
        val merged = Backups.mergeUsage(here, AiUsage(month = month, spentUsd = 40.0, calls = 90), month)
        assertEquals(40.0, merged.spentUsd)
        assertEquals(90, merged.calls)
        // A smaller one, or last month's, lowers nothing.
        assertEquals(here, Backups.mergeUsage(here, AiUsage(month = month, spentUsd = 1.0, calls = 2), month))
        assertEquals(here, Backups.mergeUsage(here, AiUsage(month = "2026-09", spentUsd = 99.0), month))
    }

    /** A slot of [key]'s kept, laid at [start]. */
    private fun slot(key: String, start: Long) = SlotRecord(
        date = Instant.ofEpochMilli(start).atZone(ZoneId.of("Europe/London")).toLocalDate().toString(), key = key, taskId = key.substringBefore('#'),
        kind = Kind.Homework, label = key, plannedMin = 30, start = start, end = start + 30 * 60_000L, window = WindowKind.Evening,
        outcome = SlotOutcome.Kept, workedMin = 30, at = start + 30 * 60_000L,
    )

    @Test
    fun `what's remembered is merged, this phone's own winning, and one past its bounds refused`() {
        val here = MemoryState(facts = listOf(Fact("football", "on Tuesdays", 5)))
        val theirs = MemoryState(
            facts = listOf(Fact("Football", "on Mondays", 1), Fact("sleep", "in bed by 22:30", 2)),
            rules = listOf(Rule("be blunter", 3)),
        )
        val merged = Backups.mergedMemory(here, theirs)
        assertEquals(listOf("sleep" to "in bed by 22:30", "football" to "on Tuesdays"), merged.facts.map { it.topic to it.text })
        assertEquals(listOf("be blunter"), merged.rules.map { it.text })
        assertNotNull(Backups.problem(backup().copy(memory = MemoryState(facts = listOf(Fact("", "x", 1))))))
        // Each within the bounds it's kept to, as all of it goes with every call.
        assertNotNull(Backups.problem(backup().copy(memory = MemoryState(rules = listOf(Rule("x".repeat(301), 1))))))
        assertNotNull(Backups.problem(backup().copy(memory = MemoryState(facts = listOf(Fact("t".repeat(61), "x", 1))))))
        assertEquals(
            null,
            Backups.problem(backup().copy(memory = MemoryState(facts = listOf(Fact("t".repeat(60), "x".repeat(300), 1)), rules = listOf(Rule("x".repeat(300), 1))))),
        )
        // Over the room together: the oldest go, wherever they came from.
        val big = "x".repeat(2_900)
        val local = MemoryState(facts = listOf(Fact("old", big, 1), Fact("mid", big, 5)))
        val newer = MemoryState(facts = listOf(Fact("new", big, 9)))
        assertEquals(listOf("mid", "new"), Backups.mergedMemory(local, newer).facts.map { it.topic })
    }

    @Test
    fun `merging a log adds what's missing and doubles nothing`() {
        val shared = completion("teams:a", now - 3_600_000L)
        val kept = slot("teams:a#-1#1", now - 3_600_000L)
        val here = ActivityLog(completions = listOf(shared), slots = listOf(kept), blocks = listOf(BlockRecord(now - 60_000L, "com.google.android.youtube", "DueSoon")))
        val backup = ActivityLog(
            completions = listOf(shared, completion("teams:b", now - 7_200_000L)),
            // The slot here and one this phone hasn't: the slots too (LR-09).
            slots = listOf(kept, slot("teams:b#-1#1", now - 7_200_000L)),
            blocks = listOf(BlockRecord(now - 60_000L, "com.google.android.youtube", "DueSoon")),
        )
        val merged = Backups.mergeLog(here, backup, now)
        assertEquals(listOf("teams:b", "teams:a"), merged.completions.map { it.taskId })
        assertEquals(listOf("teams:b#-1#1", "teams:a#-1#1"), merged.slots.map { it.key })
        // Two boxes of one task kept the same day share a key: told apart by when they were laid.
        val second = slot("teams:a#-1#1", now - 1_800_000L)
        assertEquals(2, Backups.mergeLog(here, ActivityLog(slots = listOf(kept, second)), now).slots.size)
        assertEquals(1, merged.blocks.size)
        // The weeks' rollups too (PL-10), this phone's own winning.
        val week = WeekRollup("2026-09-28", minutes = 120, finished = 3, onTime = 2, blocks = 1, fullDays = 2, slotsKept = 4, slotsMissed = 1)
        val weeks = Backups.mergeLog(ActivityLog(weeks = listOf(week)), ActivityLog(weeks = listOf(week.copy(minutes = 1), week.copy(week = "2026-09-21"))), now).weeks
        assertEquals(listOf("2026-09-21" to 120, "2026-09-28" to 120), weeks.map { it.week to it.minutes })
        // One the app couldn't have written is refused: not from a Monday, or a count below nothing.
        assertNull(Backups.problem(backup(ActivityLog(weeks = listOf(week)))))
        assertNotNull(Backups.problem(backup(ActivityLog(weeks = listOf(week.copy(week = "2026-09-29"))))))
        assertNotNull(Backups.problem(backup(ActivityLog(weeks = listOf(week.copy(week = "soon"))))))
        assertNotNull(Backups.problem(backup(ActivityLog(weeks = listOf(week.copy(minutes = -1))))))
    }

    private fun backup(log: ActivityLog = ActivityLog(), settings: Settings = Settings(), calibration: Calibration = Calibration(), answers: Map<String, String> = emptyMap()) =
        Backup(exportedAt = now, settings = settings, log = log, calibration = calibration, eventAnswers = answers)

    @Test
    fun `a backup holding what the app never writes is refused whole`() {
        assertNull(Backups.problem(backup(ActivityLog(completions = listOf(completion("teams:a", now))))))
        // Negative minutes, which the stats can't take; a box no screen allows; a multiplier past its bounds; a calendar answer of no kind.
        assertNotNull(Backups.problem(backup(ActivityLog(slots = listOf(slot("teams:a#-1#1", now - 3_600_000L).copy(workedMin = -1))))))
        assertNotNull(Backups.problem(backup(settings = Settings(boxMin = 5))))
        assertNotNull(Backups.problem(backup(settings = Settings(weekdayHours = Window(22 * 60, 16 * 60)))))
        assertNotNull(Backups.problem(backup(calibration = Calibration(multipliers = mapOf("Homework|" to 99.0)))))
        // A day's yield past what the review learns (PL-06), or evidence it couldn't have counted.
        assertNotNull(Backups.problem(backup(calibration = Calibration(yields = mapOf(DayKind.Weekend to -1.0)))))
        assertNotNull(Backups.problem(backup(calibration = Calibration(yields = mapOf(DayKind.Weekend to 0.6), yieldDays = mapOf(DayKind.Weekend to 2)))))
        assertNull(Backups.problem(backup(calibration = Calibration(yields = mapOf(DayKind.Weekend to 0.6), yieldDays = mapOf(DayKind.Weekend to 9)))))
        // A yield with no days behind it is one the review never learns.
        assertNotNull(Backups.problem(backup(calibration = Calibration(yields = mapOf(DayKind.Weekend to 0.5)))))
        // Evidence it couldn't have counted: a negative count, a kind it doesn't know, a class's own
        // figure on fewer than its three timed pieces. One from before 1.8.0 kept none, which is fine.
        assertNotNull(Backups.problem(backup(calibration = Calibration(timed = mapOf("Homework|" to -1), counted = true))))
        assertNotNull(Backups.problem(backup(calibration = Calibration(timed = mapOf("Chores|" to 2), counted = true))))
        assertNotNull(Backups.problem(backup(calibration = Calibration(multipliers = mapOf("Homework|12.1 Physics" to 1.2), timed = mapOf("Homework|12.1 Physics" to 1), counted = true))))
        assertNull(Backups.problem(backup(calibration = Calibration(multipliers = mapOf("Homework|12.1 Physics" to 1.2), timed = mapOf("Homework|12.1 Physics" to 3), counted = true))))
        assertNull(Backups.problem(backup(calibration = Calibration(multipliers = mapOf("Homework|12.1 Physics" to 1.2)))))
        assertNotNull(Backups.problem(backup(answers = mapOf("Trip" to "load:-5"))))
        assertNull(Backups.problem(backup(answers = mapOf("Trip" to "load:180", "Open Day" to "busy"))))
    }

    @Test
    fun `a backup with a day or a week that can't be read is refused whole, as Stats and the reviews read each`() {
        fun review(week: String?) = WeeklyReview(now, listOf("A made-up note."), by = "rules", week = week)
        assertTrue("a slot's day" in assertNotNull(Backups.problem(backup(ActivityLog(slots = listOf(slot("teams:a#-1#1", now - 3_600_000L).copy(date = "someday")))))))
        assertTrue("a review's week" in assertNotNull(Backups.problem(backup(ActivityLog(reviews = listOf(review("someday")))))))
        assertNull(Backups.problem(backup(ActivityLog(reviews = listOf(review("2026-10-12"), review(null))))))
    }

    @Test
    fun `a backup with an instruction the app couldn't have made is refused whole`() {
        fun with(vararg changes: Change) = backup().copy(instructions = listOf(Instruction("i", "i", at = now, state = InstructionStatus.Applied, changes = changes.toList(), appliedAt = now)))
        assertNull(Backups.problem(with(Change(ChangeType.BusyTime, weekday = 2, startMin = 16 * 60, endMin = 18 * 60))))
        assertNull(Backups.problem(with(Change(ChangeType.DueBy, taskId = "teams:a", time = now + 86_400_000L))))
        // A busy time past midnight, a weekday 8, a start ten years on, a wait on itself, an answer of no kind.
        assertNotNull(Backups.problem(with(Change(ChangeType.BusyTime, weekday = 2, startMin = 16 * 60, endMin = 25 * 60))))
        assertNotNull(Backups.problem(with(Change(ChangeType.DayLimit, weekday = 8, freeMin = 60))))
        assertNotNull(Backups.problem(with(Change(ChangeType.StartFrom, taskId = "teams:a", time = now + 3650 * 86_400_000L))))
        assertNotNull(Backups.problem(with(Change(ChangeType.After, taskId = "teams:a", afterTaskId = "teams:a"))))
        assertNotNull(Backups.problem(with(Change(ChangeType.EventTime, eventKey = "Trip", eventAnswer = "sometimes"))))
        // A day for only some tasks (D-115): a task, a date, and nothing else let in or one of the two.
        assertNull(Backups.problem(with(Change(ChangeType.DayOnly, taskId = "teams:a", date = "2026-10-31"))))
        assertNull(Backups.problem(with(Change(ChangeType.DayOnly, taskId = "teams:a", date = "2026-10-31", alsoAllow = InstructionRules.ALSO_DEADLINES))))
        assertNotNull(Backups.problem(with(Change(ChangeType.DayOnly, taskId = "teams:a", weekday = 6))))
        assertNotNull(Backups.problem(with(Change(ChangeType.DayOnly, date = "2026-10-31"))))
        assertNotNull(Backups.problem(with(Change(ChangeType.DayOnly, taskId = "teams:a", date = "2026-10-31", alsoAllow = "everything"))))
        // A day's breaks outside (D-116): each on a date; a move with its break and a time of the day, a NoOutside with its break or none, one added with its time.
        assertNull(Backups.problem(with(Change(ChangeType.OutsideAt, date = "2026-10-31", outsideMin = 780, startMin = 870))))
        assertNull(Backups.problem(with(Change(ChangeType.NoOutside, date = "2026-10-31"))))
        assertNull(Backups.problem(with(Change(ChangeType.NoOutside, date = "2026-10-31", outsideMin = 960))))
        assertNull(Backups.problem(with(Change(ChangeType.OutsideAdded, date = "2026-10-31", startMin = 1020))))
        assertNotNull(Backups.problem(with(Change(ChangeType.OutsideAt, weekday = 6, outsideMin = 780, startMin = 870))))
        assertNotNull(Backups.problem(with(Change(ChangeType.OutsideAt, date = "2026-10-31", startMin = 870))))
        assertNotNull(Backups.problem(with(Change(ChangeType.OutsideAt, date = "2026-10-31", outsideMin = 780, startMin = 25 * 60))))
        assertNotNull(Backups.problem(with(Change(ChangeType.NoOutside, date = "2026-10-31", outsideMin = -5))))
        assertNotNull(Backups.problem(with(Change(ChangeType.OutsideAdded, date = "2026-10-31"))))
        // A busy time's name (D-117): a word or two, within its room.
        assertNull(Backups.problem(with(Change(ChangeType.BusyTime, date = "2026-10-31", startMin = 1020, endMin = 1110, title = "Football"))))
        assertNotNull(Backups.problem(with(Change(ChangeType.BusyTime, date = "2026-10-31", startMin = 1020, endMin = 1110, title = "x".repeat(InstructionRules.MAX_BUSY_TITLE + 1)))))
    }

    @Test
    fun `an instruction's times are held to its own making, not the backup's, so one kept in use for years still restores`() {
        val day = 86_400_000L
        fun with(at: Long, change: Change) = backup().copy(instructions = listOf(Instruction("i", "i", at = at, state = InstructionStatus.Applied, changes = listOf(change), appliedAt = at)))
        // Made three years ago, its time then: every backup since holds it, and each restores.
        val old = now - 3 * 365 * day
        assertNull(Backups.problem(with(old, Change(ChangeType.AtTime, taskId = "own:a", time = old + day, date = "2023-10-19", startMin = 720))))
        assertNull(Backups.problem(with(old, Change(ChangeType.DueBy, taskId = "teams:a", time = old + 30 * day))))
        // A time far from its own making, or an instruction made after the backup: not one the app made.
        assertNotNull(Backups.problem(with(old, Change(ChangeType.DueBy, taskId = "teams:a", time = now))))
        assertNotNull(Backups.problem(with(now + 2 * day, Change(ChangeType.DueBy, taskId = "teams:a", time = now + 3 * day))))
    }

    @Test
    fun `a backup keeps your own tasks, and a restore adds those not here`() {
        val mine = OwnTaskRules.create(OwnTask(title = "UCAS statement", dueAt = now + 86_400_000L, minutes = 120), "u1", now)
        val read = Backups.decode(Backups.encode(backup().copy(ownTasks = listOf(mine))), emptyMap())!!
        assertEquals(listOf(mine), read.ownTasks)
        assertNull(Backups.problem(read))
        // One here already is the newer: it stays as it is.
        val here = mine.copy(title = "Personal statement")
        assertEquals(emptyList(), Backups.restoredOwnTasks(read.ownTasks, listOf(here)))
        assertEquals(listOf(mine), Backups.restoredOwnTasks(read.ownTasks, emptyList()))
        // One the form wouldn't take, or a source's task posing as yours, isn't restored.
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(title = " ")))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(source = Source.Teams)))))
        // Bookkeeping the app never writes: refused. What instructions laid over one isn't restored.
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.yours(minutes = -1)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(workedMin = -5)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(sourceProgress = 1.0)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(sourceValues = mine.sourceValues!!.copy(availableFrom = now + 86_400_000L))))))
        // What only a source gives: an Anki deck's cards a day (0 would divide by nothing), a derived count, extras.
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(stepsPerDay = 0)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(derived = true)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(extra = mapOf("from" to "x"))))))
        // A week of weekly work closed short is the one miss of yours there is (D-124); its week has to read.
        val week = WeeklyRules.make(WeeklyRule("g", "Guitar practice", 180, setAt = now), LocalDate.parse("2026-10-05"), now, ZoneId.of("UTC"))!!.copy(status = Status.Missed, doneAt = now)
        assertNull(Backups.problem(backup().copy(ownTasks = listOf(week))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(week.copy(weekly = week.weekly!!.copy(week = "soon"))))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(status = Status.Missed)))))
        // A week made from a rule as the rules came back, with nothing done, gives way to the backup's; one worked on here doesn't.
        val backed = WeeklyRules.make(WeeklyRule("g", "Guitar practice", 180, setAt = now), LocalDate.parse("2026-10-05"), now, ZoneId.of("UTC"))!!.copy(workedMin = 60)
        assertEquals(listOf(60), Backups.restoredOwnTasks(listOf(backed), listOf(backed.copy(workedMin = 0))).map { it.workedMin })
        assertEquals(emptyList(), Backups.restoredOwnTasks(listOf(backed), listOf(backed.copy(workedMin = 30))))
        // The weeks deleted go with it, from either side, and only Mondays.
        val skipped = WeeklyMark("g", "2026-10-12")
        assertEquals(listOf(skipped), Backups.decode(Backups.encode(backup().copy(weeksDeleted = listOf(skipped))), emptyMap())!!.weeksDeleted)
        val other = WeeklyMark("r", "2026-10-05")
        assertEquals(listOf(other, skipped), Backups.mergedWeeks(listOf(other), listOf(skipped, other)))
        assertNotNull(Backups.problem(backup().copy(weeksDeleted = listOf(WeeklyMark("g", "2026-10-13")))))
        // A rule in use is one the app could have made.
        fun rule(rule: WeeklyRule) = backup().copy(instructions = listOf(Instruction("i", "weekly", at = now, state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.Weekly, weekly = rule)), appliedAt = now)))
        assertNull(Backups.problem(rule(WeeklyRule("g", "Guitar practice", 180, listOf(2, 4), where = "home"))))
        assertNotNull(Backups.problem(rule(WeeklyRule("g", " ", 180))))
        assertNotNull(Backups.problem(rule(WeeklyRule("g", "Guitar practice", 0))))
        assertNotNull(Backups.problem(rule(WeeklyRule("g", "Guitar practice", 180, listOf(8)))))
        assertNotNull(Backups.problem(rule(WeeklyRule("g", "Guitar practice", 180, sessionMin = 1))))
        assertNotNull(Backups.problem(backup().copy(instructions = listOf(Instruction("i", "weekly", at = now, state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.Weekly)), appliedAt = now)))))
        // A backup of this version is one an older version refuses whole, not one it restores without them.
        assertTrue(Backup.FORMAT > 1)
        val laid = mine.yours(dueAt = now + 3 * 86_400_000L, notATask = true).let { it.copy(dueAt = it.mine.dueAt?.value) }
        val restored = Backups.restoredOwnTasks(listOf(laid), emptyList()).single()
        assertNull(restored.mine.dueAt?.value)
        assertEquals(mine.dueAt, restored.dueAt)
        assertEquals(false, restored.mine.notATask != null)
        // A backup from before 2.0.0 has none.
        assertEquals(emptyList(), Backups.decode(Backups.encode(backup()).replace("\"ownTasks\"", "\"ignored\""), emptyMap())!!.ownTasks)
    }

    @Test
    fun `a backup keeps the instructions in use`() {
        val applied = Instruction("i1", "I can't do anything on Saturday", at = now, state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.DayLimit, weekday = 6, freeMin = 0)), appliedAt = now)
        val read = Backups.decode(Backups.encode(backup().copy(instructions = listOf(applied))), emptyMap())!!
        assertEquals(listOf(applied), read.instructions)
        // One from before 1.7.0 has none.
        assertEquals(emptyList(), Backups.decode(Backups.encode(backup()).replace("\"instructions\"", "\"ignored\""), emptyMap())!!.instructions)
    }

    @Test
    fun `a backup's instructions come back as applying each here would`() {
        fun instruction(id: String, at: Long, vararg changes: Change) = Instruction(id, id, at = at, state = InstructionStatus.Applied, changes = changes.toList(), appliedAt = at)
        val saturday = instruction("saturday", now - 3_000, Change(ChangeType.DayLimit, weekday = 6, freeMin = 0))
        val dueFriday = instruction("due", now - 2_000, Change(ChangeType.DueBy, taskId = "teams:a", time = now + 86_400_000L))
        val bAfterA = instruction("b-after-a", now - 1_000, Change(ChangeType.After, taskId = "teams:b", afterTaskId = "teams:a"))
        // Here already: A waits for B.
        val aAfterB = instruction("a-after-b", now - 5_000, Change(ChangeType.After, taskId = "teams:a", afterTaskId = "teams:b"))
        val backed = listOf(bAfterA, dueFriday, saturday, aAfterB)
        // A due date too, armed or not: the work is yours to change.
        val restored = Backups.restoredInstructions(backed, here = listOf(aAfterB))
        assertEquals(listOf("saturday" to InstructionStatus.Applied, "due" to InstructionStatus.Applied, "b-after-a" to InstructionStatus.Unclear), restored.map { it.id to it.state })
        assertEquals(dueFriday.appliedAt, restored.single { it.id == "due" }.appliedAt)
        // Those not in use, as a reset's copy holds them, come back after them as they were, but one here already.
        val reading = Instruction("reading", "reading", at = now, state = InstructionStatus.Reading)
        val unclear = Instruction("unclear", "unclear", at = now, state = InstructionStatus.Unclear, note = "It can't be told which task")
        val all = Backups.restoredInstructions(backed + reading + unclear, here = listOf(aAfterB, unclear))
        assertEquals(listOf("saturday", "due", "b-after-a", "reading"), all.map { it.id })
        assertEquals(reading, all.last())
    }

    @Test
    fun `format 7's chat with Felix's state, its earlier messages and today's slots are kept, and a newer format is refused`() {
        // A message of his with the level he wrote it at (D-119, Q103), his note on how he felt, and your rating of it, kept with it.
        val firm = ChatMessage("m2", Speaker.Felix, "that's two today.", 6, kind = MessageKind.SlotEnd, mood = "Firm", feeling = "annoyed: two gone", rating = "Down", feedback = "too sharp", ratedAt = 7)
        val chat = ChatState(listOf(ChatMessage("m1", Speaker.You, "move the physics to Thursday", 5), firm), felix = FelixState(nextCheckAt = 42))
        val archive = ChatArchive(listOf(ChatMessage("m0", Speaker.You, "what's due?", 1), ChatMessage("m00", Speaker.Felix, "every slot today. really proud of you.", 2, kind = MessageKind.DayDone, mood = "Proud")))
        val backup = Backup(
            exportedAt = 1, settings = Settings(), log = ActivityLog(), chat = chat, archive = archive,
            slots = SlotState("2026-10-15", previous = mapOf("teams:a#0" to 7L)),
        )
        val back = Backups.decode(Backups.encode(backup), emptyMap())!!
        assertEquals(Backup.FORMAT, back.format)
        assertEquals(chat, back.chat)
        assertEquals(42L, back.chat.felix.nextCheckAt)
        assertEquals(archive, back.archive)
        // None in one from before.
        assertEquals(ChatState(), Backups.decode("""{"app":"Decrastination","format":5,"exportedAt":0,"settings":{},"log":{}}""", emptyMap())!!.chat)
        assertEquals(mapOf("teams:a#0" to 7L), back.slots.previous)
        assertEquals(null, Backups.decode(Backups.encode(backup).replace("\"format\": ${Backup.FORMAT}", "\"format\": ${Backup.FORMAT + 1}"), emptyMap()))
    }

    @Test
    fun `format 11 keeps the pairs that aren't the same work, one said on either side standing`() {
        val backup = Backup(exportedAt = 1, settings = Settings(), log = ActivityLog(), notSame = listOf("a|b", "c|d"))
        assertEquals(listOf("a|b", "c|d"), Backups.decode(Backups.encode(backup), emptyMap())!!.notSame)
        assertEquals(listOf("c|d", "a|b", "e|f"), Backups.mergedNotSame(listOf("a|b", "e|f"), listOf("c|d", "a|b")))
        // Past what's kept, this phone's stay: its own pair, early in the backup too, isn't the one that goes.
        val backed = listOf("mine|1") + List(TaskState.MAX_NOT_SAME) { "b$it|x" }
        val merged = Backups.mergedNotSame(listOf("mine|1"), backed)
        assertEquals(TaskState.MAX_NOT_SAME, merged.size)
        assertEquals("mine|1", merged.last())
        // None in one from before.
        assertEquals(emptyList(), Backups.decode("""{"app":"Decrastination","format":10,"exportedAt":0,"settings":{},"log":{}}""", emptyMap())!!.notSame)
        assertNull(Backups.problem(backup))
        assertNotNull(Backups.problem(backup.copy(notSame = listOf("just one"))))
        assertNotNull(Backups.problem(backup.copy(notSame = List(TaskState.MAX_NOT_SAME + 1) { "a$it|b" })))
    }

    @Test
    fun `format 9 keeps the receipts of writes to Gmail, restored by id beside this phone's, never a write still to make`() {
        val made = GmailWrite("w1", "gmail:t1", "Trip form", "t1", 3, notBefore = 4, flagged = listOf("101"), inbox = listOf("101"), unstarred = true, archived = true, doneAt = 5)
        val pending = GmailWrite("w2", "gmail:t2", "Club email", "t2", 6, notBefore = 7)
        val backup = Backup(exportedAt = 1, settings = Settings(), log = ActivityLog(), gmailWrites = listOf(made))
        val back = Backups.decode(Backups.encode(backup), emptyMap())!!
        assertEquals(11, Backup.FORMAT)
        assertEquals(listOf(made), back.gmailWrites)
        // None in one from before.
        assertEquals(emptyList(), Backups.decode("""{"app":"Decrastination","format":8,"exportedAt":0,"settings":{},"log":{}}""", emptyMap())!!.gmailWrites)
        // This phone's own stand; the backup's receipts it hasn't come back, never a pending one.
        val here = listOf(made.copy(title = "here"), pending)
        assertEquals(here, Backups.mergedWrites(here, listOf(made, pending.copy(id = "w3"))))
        // In the order they were made: a backup's older receipt never comes after this phone's newer for its task.
        assertEquals(listOf(made, pending), Backups.mergedWrites(listOf(pending), listOf(made)))
        val newer = made.copy(id = "w9", at = 50, notBefore = 51, doneAt = 52)
        assertEquals("w9", Backups.mergedWrites(listOf(newer), listOf(made)).last { it.taskId == "gmail:t1" }.id)
        // One by ids that aren't Gmail's, as no read gives them (a conversation's goes into an IMAP command): refused.
        val gmail = made.copy(threadId = "1789")
        assertNull(Backups.problem(backup.copy(gmailWrites = listOf(gmail))))
        assertNotNull(Backups.problem(backup.copy(gmailWrites = listOf(gmail.copy(threadId = "1789\r\nA1 EXPUNGE")))))
        assertNotNull(Backups.problem(backup.copy(gmailWrites = listOf(gmail.copy(flagged = listOf("x"))))))
    }

    @Test
    fun `format 8 keeps Felix's notes and reminders, restored by id beside this phone's, and refuses what the app never keeps`() {
        val note = Note("n1", "Use the second source", 3, By.Felix, taskId = "teams:essay")
        val standing = Note("n2", "On sheets, not in the book", 4, By.You, scope = NoteScope(subject = "Chemistry"))
        val reminder = Reminder("r1", "call gran", 5, By.Felix, due = Due(DueKind.At, 1_000))
        val felix = FelixStore(listOf(note, standing), listOf(reminder))
        val backup = Backup(exportedAt = 1, settings = Settings(), log = ActivityLog(), felixStore = felix)
        val back = Backups.decode(Backups.encode(backup), emptyMap())!!
        assertTrue(Backup.FORMAT >= 8)
        assertEquals(felix, back.felixStore)
        assertNull(Backups.problem(back))
        // None in one from before.
        assertEquals(FelixStore(), Backups.decode("""{"app":"Decrastination","format":7,"exportedAt":0,"settings":{},"log":{}}""", emptyMap())!!.felixStore)
        // This phone's own win; the backup's it hasn't come back.
        val here = FelixStore(listOf(note.copy(text = "Use the first source")), emptyList())
        assertEquals(FelixStore(listOf(here.notes.single(), standing), listOf(reminder)) to 0, Backups.mergedFelix(here, felix))
        // Within what the app keeps: still to come, 50; a task's notes, ten; standing, twenty; the rest left out and counted.
        val full = FelixStore(
            notes = (1..Notes.PER_TASK).map { Note("t$it", "n", 3, By.Felix, taskId = "teams:essay") } + (1..Notes.MAX_STANDING).map { Note("s$it", "n", 3, By.Felix, scope = NoteScope(subject = "Maths")) },
            reminders = (1..Reminders.MAX_PENDING).map { reminder.copy(id = "p$it") },
        )
        val (merged, left) = Backups.mergedFelix(full, felix.copy(reminders = felix.reminders + reminder.copy(id = "sent", sentAt = 9)))
        assertEquals(3, left)
        assertEquals(Reminders.MAX_PENDING, merged.reminders.count(Reminders::pending))
        assertTrue(merged.reminders.any { it.id == "sent" })
        assertNull(Backups.problem(backup.copy(felixStore = merged)))
        // Past 50 still to come by a snooze, as the app can keep them: not refused, and restored within 50, the rest left out.
        val over = full.copy(reminders = full.reminders + reminder.copy(id = "snoozed", sentAt = 9, snoozedTo = 2_000))
        assertNull(Backups.problem(backup.copy(felixStore = over)))
        assertEquals(1, Backups.mergedFelix(FelixStore(), over).second)
        // A note on a task and a scope, or neither, or on a blank task id; a reminder's due missing what its kind needs, or its task's
        // id blank; two of one id; words too long, or a scope's.
        for (bad in listOf(
            felix.copy(notes = listOf(note.copy(scope = NoteScope(subject = "Chemistry")))),
            felix.copy(notes = listOf(note.copy(taskId = null))),
            felix.copy(notes = listOf(note.copy(taskId = ""))),
            felix.copy(reminders = listOf(reminder.copy(taskId = " "))),
            felix.copy(reminders = listOf(reminder.copy(due = Due(DueKind.BeforeDue, minutesBefore = 30)))),
            felix.copy(reminders = listOf(reminder.copy(due = Due(DueKind.At)))),
            felix.copy(reminders = listOf(reminder, reminder.copy(text = "again"))),
            felix.copy(notes = listOf(note.copy(text = "x".repeat(Notes.MAX_TEXT + 1)))),
            felix.copy(notes = listOf(standing.copy(scope = NoteScope(titleContains = "x".repeat(Notes.MAX_SCOPE + 1))))),
        )) assertTrue(Backups.problem(backup.copy(felixStore = bad))?.contains("Felix's notes and reminders") == true, "$bad")
    }

    @Test
    fun `a backup past what Import reads leaves the chat's earliest messages out, as few as it takes`() {
        fun message(i: Int) = ChatMessage("m$i", Speaker.You, "x".repeat(1_000), i.toLong())
        val backup = Backup(exportedAt = 1, settings = Settings(), log = ActivityLog(), archive = ChatArchive((1..40).map(::message)))
        val whole = Backups.encode(backup).toByteArray().size
        // Room for all of it: nothing left out.
        assertEquals(0, Backups.fitted(backup, cap = whole).second)
        // Room for about half: the oldest left out, the newest kept, and it fits.
        val (text, left) = Backups.fitted(backup, cap = whole / 2)
        assertTrue(left in 1 until 40 && text.toByteArray().size <= whole / 2, "$left left out")
        val kept = Backups.decode(text, emptyMap())!!.archive.messages
        assertEquals((left + 1..40).map { "m$it" }, kept.map { it.id })
        // One fewer left out wouldn't fit.
        assertTrue(Backups.encode(backup.copy(archive = backup.archive.copy(messages = backup.archive.messages.drop(left - 1)))).toByteArray().size > whole / 2)
    }
}

