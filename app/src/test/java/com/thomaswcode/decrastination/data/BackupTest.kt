package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.enrich.AiUsage
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun `what isn't one of this app's backups, or is from a newer one, isn't read`() {
        assertNull(Backups.decode("not json"))
        assertNull(Backups.decode("""{"app":"Something else","format":1,"exportedAt":0,"settings":{},"log":{}}"""))
        assertNull(Backups.decode("""{"app":"Decrastination","format":2,"exportedAt":0,"settings":{},"log":{}}"""))
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
        val unarmed = Backups.restoredInstructions(backed, here = listOf(aAfterB), armed = false)
        assertEquals(listOf("saturday" to InstructionStatus.Applied, "due" to InstructionStatus.Applied, "b-after-a" to InstructionStatus.Unclear), unarmed.map { it.id to it.state })
        // Armed, a due date waits for your dad's code; the rest are as unarmed.
        val armed = Backups.restoredInstructions(backed, here = listOf(aAfterB), armed = true)
        assertEquals(InstructionStatus.Understood, armed.single { it.id == "due" }.state)
        assertNull(armed.single { it.id == "due" }.appliedAt)
        assertEquals(InstructionStatus.Applied, armed.single { it.id == "saturday" }.state)
    }
}
