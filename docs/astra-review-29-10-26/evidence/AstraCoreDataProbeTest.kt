package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.shareOut
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.AnkiRules
import com.thomaswcode.decrastination.core.Deck
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Source
import java.time.ZoneId
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.Settings
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder

class AstraCoreDataProbeTest {
    @Test
    fun `cancellation after disk write leaves stale state and a later update erases the saved change`() = runBlocking {
        val dir = Files.createTempDirectory("astra-store-cancel").toFile()
        try {
            val file = File(dir, "state.json")
            val base = MapSerializer(String.serializer(), Int.serializer())
            var cancel: Job? = null
            val serializer = object : KSerializer<Map<String, Int>> by base {
                override fun serialize(encoder: Encoder, value: Map<String, Int>) {
                    if (value["saved"] == 1) cancel?.cancel()
                    base.serialize(encoder, value)
                }
            }
            val store = JsonStore(file, serializer, { emptyMap<String, Int>() })
            val writer = launch(start = CoroutineStart.LAZY) { store.update { it + ("saved" to 1) } }
            cancel = writer
            writer.start()
            writer.join()
            assertTrue(file.readText().contains("saved"))
            assertEquals(emptyMap(), store.value)
            cancel = null
            store.update { it + ("other" to 2) }
            assertEquals(mapOf("other" to 2), JsonStore.json.decodeFromString(base, file.readText()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a valid total at least the number of steps is inflated`() {
        assertEquals(listOf(3, 1, 1), shareOut(3, listOf(100, 1, 1)))
        val task = OwnTaskRules.create(OwnTask("Practice", steps = listOf(OwnStep("Main", 100), OwnStep("Check", 1), OwnStep("Save", 1))), "probe", 1L)
        val changed = OwnTaskRules.changed(task, Change(ChangeType.Minutes, taskId = task.id, minutes = 3))
        assertEquals(5, changed.sourceEffortMin)
        assertEquals(5, changed.subSteps.sumOf { it.minutes })
    }

    @Test
    fun `a learned Anki rate changes the fresh step but merge keeps the old minutes`() {
        val now = Fixtures.at("2026-10-29T12:00")
        val assignment = OwnTaskRules.create(OwnTask("Learn vocabulary 1.2", dueAt = now + 7 * 86_400_000L), "assignment", now)
        val deck = Deck(1L, "Textbook 1::1.2", 0, 0, 20)
        fun fetched(rate: Double) = AnkiRules.homeworkDecks(listOf(deck), 1, listOf(assignment), { 20 }, now, ZoneId.of("Europe/London"), mapOf(1L to rate))
        val old = Merge.apply(emptyList(), Source.Anki, fetched(15.0), now).tasks
        val fresh = fetched(60.0)
        assertEquals(20, fresh.single().subSteps!!.single().minutes)
        val merged = Merge.apply(old, Source.Anki, fresh, now + 1).tasks.single()
        assertEquals(20, merged.sourceEffortMin)
        assertEquals(5, merged.subSteps.single().minutes)
    }

    @Test
    fun `forgetting a standing rule longer than sixty characters silently keeps it`() {
        val words = "Please keep reminders brief when I am studying and wait until I finish the current piece before asking again."
        val saved = Memories.edit(MemoryState(), listOf(Given(text = words, kind = "rule")), 1L).first
        assertEquals(words, saved.rules.single().text)
        val forgotten = Memories.edit(saved, listOf(Given(topic = words, text = "", kind = "rule")), 2L)
        assertEquals(words, forgotten.first.rules.single().text)
        assertTrue(forgotten.second.isEmpty())
    }

    @Test
    fun `valid retained instructions age into backups the importer refuses`() {
        val at = Fixtures.at("2026-10-29T12:00")
        val instruction = Instruction("old", "Due tomorrow", at = at, state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.DueBy, taskId = "teams:old", time = at + 86_400_000L)), appliedAt = at)
        val first = Backup(exportedAt = at, settings = Settings(), log = ActivityLog(), instructions = listOf(instruction))
        assertEquals(null, Backups.problem(first))
        assertTrue(Backups.problem(first.copy(exportedAt = at + 732 * 86_400_000L))!!.contains("an instruction"))
    }

    @Test
    fun `the migration rehearsal succeeds without inspecting corrupt monthly archive and Gmail journal`() {
        val root = Files.createTempDirectory("astra-rehearsal").toFile()
        val previous = System.getProperty("rehearseFrom")
        try {
            val files = File(root, "files").apply { mkdirs() }
            File(files, "tasks.json").writeText(JsonStore.json.encodeToString(TaskState.serializer(), TaskState()))
            File(files, "settings.json").writeText(JsonStore.json.encodeToString(Settings.serializer(), Settings()))
            File(files, "runtime.json").writeText(JsonStore.json.encodeToString(RuntimeState.serializer(), RuntimeState()))
            File(files, "log.json").writeText(JsonStore.json.encodeToString(ActivityLog.serializer(), ActivityLog()))
            File(files, "session.json").writeText(JsonStore.json.encodeToString(SessionState.serializer(), SessionState()))
            File(files, "protection.json").writeText(JsonStore.json.encodeToString(ProtectionState.serializer(), ProtectionState()))
            File(files, "model.json").writeText(JsonStore.json.encodeToString(ModelState.serializer(), ModelState()))
            File(files, "anki.json").writeText(JsonStore.json.encodeToString(AnkiState.serializer(), AnkiState()))
            File(files, "learning.json").writeText(JsonStore.json.encodeToString(LearningState.serializer(), LearningState()))
            File(files, "chat-archive-2026-10.json").writeText("{broken archive")
            File(files, "gmail-writes.json").writeText("{broken journal")
            System.setProperty("rehearseFrom", root.absolutePath)
            RehearsalTest().`the phone's stores read and migrated, what they hold counted before and after`()
            val report = File(root, "rehearsal.txt").readText()
            assertTrue("The same before and after" in report)
            assertTrue("gmail-writes.json" !in report)
            assertTrue("chat-archive-2026-10.json" !in report)
        } finally {
            if (previous == null) System.clearProperty("rehearseFrom") else System.setProperty("rehearseFrom", previous)
            root.deleteRecursively()
        }
    }
}



