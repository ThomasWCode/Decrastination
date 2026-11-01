package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Reset
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.sync.protocol.ResetData
import com.thomaswcode.decrastination.ui.BackupPage
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backup, Reset and Setup as the website draws them (docs/spec/website.md). */
class BackupViewTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val now = Fixtures.at("2026-10-14T12:00")

    private fun backup(leftOut: Int = 0, tooBig: Boolean = false, refused: String? = null, last: Long? = null, copies: List<String> = emptyList(), applied: List<Instruction> = emptyList(), writable: Boolean = true) =
        BackupViews.of(leftOut, tooBig, refused, last, copies, applied, now, writable)

    @Test
    fun `while the phone writes a backup can be had, and nothing else can be done`() {
        val view = backup(copies = listOf("reset-1.json"), writable = false)
        assertTrue(view.canDownload)
        assertTrue(view.resets.isEmpty())
        assertTrue(view.copies.isEmpty())
        assertFalse(view.canChange)
    }

    @Test
    fun `each kind can be reset, asking first in the phone's words`() {
        val view = backup(copies = listOf("reset-1.json"))
        assertEquals(Reset.entries.map { it.title }, view.resets.map { it.title })
        assertEquals(listOf("reset-1.json"), view.copies)
        val chat = view.resets.single { it.title == Reset.Chat.title }
        assertEquals("The chat's messages, Felix's messages and what he was waiting to say, and his notes and reminders", chat.takes)
        assertEquals(ResetData(Reset.Chat), chat.button.action)
        assertEquals("Reset the chat and Felix's history?", chat.button.confirm)
        assertEquals("Reset", chat.button.yes)
        assertEquals("Keep it", chat.button.no)
    }

    @Test
    fun `a reset that takes your changes names them`() {
        val change = Instruction("i", "Made-up change", at = now, state = InstructionStatus.Applied)
        val view = backup(applied = listOf(change))
        fun detail(what: Reset) = assertNotNull(view.resets.single { it.title == what.title }.button.detail)
        assertTrue("“Made-up change”" in detail(Reset.Instructions), detail(Reset.Instructions))
        assertTrue("“Made-up change”" in detail(Reset.Everything))
        assertFalse("Made-up change" in detail(Reset.Chat))
    }

    @Test
    fun `what's to know of a backup is said, and one that couldn't be restored isn't offered`() {
        assertNull(backup().fit)
        assertEquals(BackupPage.fit(5, false, null), backup(leftOut = 5).fit)
        assertEquals(BackupPage.fit(0, false, "a made-up reason"), backup(refused = "a made-up reason").fit)
        assertTrue(backup(refused = "a made-up reason").canDownload, "what Import refuses can still leave, as on the phone")
        assertFalse(backup(tooBig = true).canDownload)
    }

    @Test
    fun `the last nightly backup is said once there's been one`() {
        assertNull(backup().lastNightly)
        assertEquals("Last nightly backup: 2 h ago", backup(last = now - 2 * 3_600_000L).lastNightly)
    }

    private val read = SourceStatus(lastSuccessAt = now)

    private fun setup(
        tasks: TaskState = TaskState(),
        unreadable: List<JsonStore.Unreadable> = emptyList(),
        held: Set<String> = emptySet(),
        keyHeld: Boolean = false,
        settings: Settings = Settings(),
        writable: Boolean = true,
    ) = SetupViews.of(tasks, unreadable, { false }, settings, AiUsage(), held, keyHeld, now, zone, writable)

    private fun SetupView.source(title: String) = sources.single { it.title == title }

    @Test
    fun `the sources are the phone's four, each saying how it reads`() {
        val view = setup(TaskState(sources = mapOf(Source.Teams to read, Source.Anki to SourceStatus(error = "A made-up failure"))))
        assertEquals(listOf("Teams Assignments widget", "Power Planner login", "AnkiDroid access", "Gmail app password"), view.sources.map { it.title })
        assertEquals(SetupLine("Teams Assignments widget", true, "Reading it works."), view.source("Teams Assignments widget"))
        assertEquals(SetupLine("AnkiDroid access", false, "Last read failed: A made-up failure"), view.source("AnkiDroid access"))
    }

    @Test
    fun `a sign-in goes by what's held here, once the server reads them`() {
        val works = TaskState(sources = mapOf(Source.PowerPlanner to read, Source.Gmail to read))
        assertEquals(false, setup(works).source("Power Planner login").done)
        assertEquals(true, setup(works, held = setOf(Secret.PowerPlannerPassword.name)).source("Power Planner login").done)
        assertEquals(false, setup(works).source("Gmail app password").done)
        assertEquals(true, setup(works, held = setOf(Secret.GmailAppPassword.name)).source("Gmail app password").done)
    }

    @Test
    fun `while the phone writes, the sign-ins and the key are its own, so what it read is what's said`() {
        val works = TaskState(sources = mapOf(Source.PowerPlanner to read, Source.Gmail to read))
        val view = setup(works, settings = Settings(aiEnabled = true, aiKeyActive = true), writable = false)
        assertEquals(true, view.source("Power Planner login").done)
        assertEquals(true, view.source("Gmail app password").done)
        assertEquals(true, view.claude.done)
    }

    @Test
    fun `Claude goes by this box's key once the server asks it`() {
        val on = Settings(aiEnabled = true, aiKeyActive = true)
        assertEquals(false, setup(settings = on).claude.done)
        assertEquals(true, setup(settings = on, keyHeld = true).claude.done)
    }

    @Test
    fun `a key that's refused says what puts it right where it's held`() {
        val on = Settings(aiEnabled = true, aiKeyActive = true)
        fun said(problem: KeyProblem, writable: Boolean) = SetupViews.of(TaskState(), emptyList(), { false }, on, AiUsage(keyProblem = problem), emptySet(), true, now, zone, writable).claude.detail
        assertTrue("the box's .env" in said(KeyProblem.Rejected, writable = true))
        assertTrue(KeyProblem.Rejected.fix in said(KeyProblem.Rejected, writable = false), "while the phone asks Claude, its key is the phone's")
        assertTrue(KeyProblem.NoCredit.fix in said(KeyProblem.NoCredit, writable = true), "a new key wouldn't put that right")
    }

    @Test
    fun `a key on the box is in use whatever the phone's is`() {
        val waiting = Settings(aiEnabled = true, aiKeyActive = false)
        assertEquals(true, setup(settings = waiting, keyHeld = true).claude.done)
        assertTrue(setup(settings = waiting, keyHeld = true).claude.detail.startsWith("On: "), "no asking for a key the box has")
        assertEquals("Key saved, not in use yet: enter it again to use it.", setup(settings = waiting, writable = false).claude.detail)
        assertEquals(false, setup(settings = waiting, writable = false).claude.done)
    }

    @Test
    fun `what couldn't be read is said, the sign-ins' in the server's words`() {
        val lost = setup(unreadable = listOf(JsonStore.Unreadable("secrets.bin", null, false, now))).saved.single()
        assertEquals(false, lost.done)
        assertEquals("Found unreadable today 12:00", lost.title)
        assertTrue("MASTER_KEY" in lost.detail, lost.detail)
        assertFalse("Android" in lost.detail, lost.detail)
        val log = setup(unreadable = listOf(JsonStore.Unreadable("log.json", null, true, now))).saved.single()
        assertEquals(JsonStore.Unreadable("log.json", null, true, now).says, log.detail)
    }
}
