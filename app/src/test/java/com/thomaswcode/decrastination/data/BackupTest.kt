package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Calibration
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
}
