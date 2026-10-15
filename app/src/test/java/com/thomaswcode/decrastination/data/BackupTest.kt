package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.enrich.AiUsage
import java.io.ByteArrayInputStream
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
        assertEquals(backup, Backups.decode(Backups.encode(backup)))
    }

    @Test
    fun `a backup keeps the school calendar, and one from before has none`() {
        val school = SchoolCalendar(listOf(NoSchool("2026-10-26", "2026-10-30", "Half term")), listOf(HalfDay("2026-10-23", 750)))
        val backup = Backup(exportedAt = now, settings = Settings(), log = ActivityLog(), school = school)
        assertEquals(3, backup.format)
        assertEquals(school, Backups.decode(Backups.encode(backup))!!.school)
        // Format 2 (2.0.0) is still read, with no days off.
        val old = Backups.decode("""{"app":"Decrastination","format":2,"exportedAt":0,"settings":{},"log":{}}""")!!
        assertEquals(SchoolCalendar(), old.school)
        assertNull(Backups.problem(old))
        // One the app couldn't have kept is refused whole.
        val bad = backup.copy(school = SchoolCalendar(listOf(NoSchool("2026-10-30", "2026-10-26"))))
        assertNotNull(Backups.problem(bad))
    }

    @Test
    fun `what isn't one of this app's backups, or is from a newer one, isn't read`() {
        assertNull(Backups.decode("not json"))
        assertNull(Backups.decode("""{"app":"Something else","format":1,"exportedAt":0,"settings":{},"log":{}}"""))
        assertNull(Backups.decode("""{"app":"Decrastination","format":${Backup.FORMAT + 1},"exportedAt":0,"settings":{},"log":{}}"""))
        assertNotNull(Backups.decode("""{"app":"Decrastination","format":${Backup.FORMAT},"exportedAt":0,"settings":{},"log":{}}"""))
        // One from before 2.0.0 (format 1, no tasks of your own) is still read.
        assertNotNull(Backups.decode("""{"app":"Decrastination","format":1,"exportedAt":0,"settings":{},"log":{}}"""))
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

    @Test
    fun `merging a log adds what's missing and doubles nothing`() {
        val shared = completion("teams:a", now - 3_600_000L)
        val here = ActivityLog(completions = listOf(shared), blocks = listOf(BlockRecord(now - 60_000L, "com.google.android.youtube", "DueSoon")))
        val backup = ActivityLog(
            completions = listOf(shared, completion("teams:b", now - 7_200_000L)),
            blocks = listOf(BlockRecord(now - 60_000L, "com.google.android.youtube", "DueSoon")),
        )
        val merged = Backups.mergeLog(here, backup, now)
        assertEquals(listOf("teams:b", "teams:a"), merged.completions.map { it.taskId })
        assertEquals(1, merged.blocks.size)
    }

    private fun backup(log: ActivityLog = ActivityLog(), settings: Settings = Settings(), calibration: Calibration = Calibration(), answers: Map<String, String> = emptyMap()) =
        Backup(exportedAt = now, settings = settings, log = log, calibration = calibration, eventAnswers = answers)

    @Test
    fun `a backup holding what the app never writes is refused whole`() {
        assertNull(Backups.problem(backup(ActivityLog(completions = listOf(completion("teams:a", now))))))
        // Negative minutes, which the stats can't take; a box no screen allows; a multiplier past its bounds; a calendar answer of no kind.
        val session = SessionRecord("teams:a", Kind.Homework, label = "a", plannedMin = 30, workedMin = -1, startedAt = now - 3_600_000L, endedAt = now, completed = false)
        assertNotNull(Backups.problem(backup(ActivityLog(sessions = listOf(session)))))
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
    }

    @Test
    fun `a backup keeps your own tasks, and a restore adds those not here`() {
        val mine = OwnTasks.create(OwnTask(title = "UCAS statement", dueAt = now + 86_400_000L, minutes = 120), "u1", now)
        val read = Backups.decode(Backups.encode(backup().copy(ownTasks = listOf(mine))))!!
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
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(userEffortMin = -1)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(workedMin = -5)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(sourceProgress = 1.0)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(sourceValues = mine.sourceValues!!.copy(availableFrom = now + 86_400_000L))))))
        // What only a source gives: an Anki deck's cards a day (0 would divide by nothing), a derived count, extras.
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(stepsPerDay = 0)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(derived = true)))))
        assertNotNull(Backups.problem(backup().copy(ownTasks = listOf(mine.copy(extra = mapOf("from" to "x"))))))
        // A backup of this version is one an older version refuses whole, not one it restores without them.
        assertTrue(Backup.FORMAT > 1)
        val laid = mine.copy(userDueAt = now + 3 * 86_400_000L, userNotATask = true).let { it.copy(dueAt = it.userDueAt) }
        val restored = Backups.restoredOwnTasks(listOf(laid), emptyList()).single()
        assertNull(restored.userDueAt)
        assertEquals(mine.dueAt, restored.dueAt)
        assertEquals(false, restored.userNotATask)
        // A backup from before 2.0.0 has none.
        assertEquals(emptyList(), Backups.decode(Backups.encode(backup()).replace("\"ownTasks\"", "\"ignored\""))!!.ownTasks)
    }

    @Test
    fun `a backup keeps the instructions in use`() {
        val applied = Instruction("i1", "I can't do anything on Saturday", at = now, state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.DayLimit, weekday = 6, freeMin = 0)), appliedAt = now)
        val read = Backups.decode(Backups.encode(backup().copy(instructions = listOf(applied))))!!
        assertEquals(listOf(applied), read.instructions)
        // One from before 1.7.0 has none.
        assertEquals(emptyList(), Backups.decode(Backups.encode(backup()).replace("\"instructions\"", "\"ignored\""))!!.instructions)
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
        // A due date too, armed or not: the work is yours to change (12 Oct).
        val restored = Backups.restoredInstructions(backed, here = listOf(aAfterB))
        assertEquals(listOf("saturday" to InstructionStatus.Applied, "due" to InstructionStatus.Applied, "b-after-a" to InstructionStatus.Unclear), restored.map { it.id to it.state })
        assertEquals(dueFriday.appliedAt, restored.single { it.id == "due" }.appliedAt)
    }
}
