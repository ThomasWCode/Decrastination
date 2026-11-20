package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatPhoto
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.TaskPhoto
import com.thomaswcode.decrastination.core.Why
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.ChatPhotoMigration
import com.thomaswcode.decrastination.data.CheckNote
import com.thomaswcode.decrastination.data.CheckNotesMigration
import com.thomaswcode.decrastination.data.PhotoDoorsMigration
import com.thomaswcode.decrastination.data.CreditMigration
import com.thomaswcode.decrastination.data.OnlyMigration
import com.thomaswcode.decrastination.data.MoodMigration
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.SpeechMigration
import com.thomaswcode.decrastination.data.StepActionMigration
import com.thomaswcode.decrastination.data.OutsideMigration
import com.thomaswcode.decrastination.data.TimerMigration
import com.thomaswcode.decrastination.data.MemoryMigration
import com.thomaswcode.decrastination.data.FelixStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.NotSameMigration
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.LearningState
import com.thomaswcode.decrastination.data.ModelState
import com.thomaswcode.decrastination.data.ProtectionSplit
import com.thomaswcode.decrastination.data.ProtectionState
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SessionState
import com.thomaswcode.decrastination.data.StepOrderMigration
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.WeeklyMigration
import com.thomaswcode.decrastination.data.ZoomMigration
import com.thomaswcode.decrastination.data.TasksMigration
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.PhotoChecker
import com.thomaswcode.decrastination.enrich.PhotoTasks
import com.thomaswcode.decrastination.enrich.Profile
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * The photos' service on temporary stores (docs/plan_v4/architecture.md §The rules): a photo of a task's work kept
 * with it and read with it, the photos no task holds let go, and a photo check's early answers, its
 * pages let go whatever happens. Claude is off; the image's own handling is `PhotoTurnTest`'s.
 */
class PhotosTest {
    private val dir: File = Files.createTempDirectory("photos").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T16:00"), LONDON)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS, WeeklyMigration.TASKS, NotSameMigration.TASKS))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS, ZoomMigration.SETTINGS, SpeechMigration.SETTINGS))
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME, NotSameMigration.RUNTIME))
    private val learning = JsonStore(File(dir, "learning.json"), LearningState.serializer(), ::LearningState)
    private val model = JsonStore(File(dir, "model.json"), ModelState.serializer(), ::ModelState)
    private val protection = JsonStore(File(dir, "protection.json"), ProtectionState.serializer(), ::ProtectionState, migrations = listOf(ProtectionSplit.PROTECTION))
    private val session = JsonStore(File(dir, "session.json"), SessionState.serializer(), ::SessionState, migrations = listOf(CreditMigration.SESSION, TimerMigration.SESSION, CheckNotesMigration.SESSION, PhotoDoorsMigration.SESSION))
    private val log = JsonStore(File(dir, "log.json"), ActivityLog.serializer(), ::ActivityLog, migrations = listOf(ChatMigration.LOG, MemoryMigration.LOG, CreditMigration.LOG))
    private val chat = JsonStore(File(dir, "chat.json"), ChatState.serializer(), ::ChatState, migrations = listOf(ChatMigration.CHAT, TasksMigration.CHAT, FelixStore.CHAT, FelixStore.CHAT_PIECES, MemoryMigration.CHAT, StepOrderMigration.CHAT, OnlyMigration.CHAT, OutsideMigration.CHAT, MoodMigration.CHAT, SpeechMigration.CHAT, StepActionMigration.CHAT, WeeklyMigration.CHAT, ChatPhotoMigration.CHAT))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gateway = ModelGateway(settings, { null }, model, tasks, clock, NoAlerts, scope) {}
    private val focus = Focus(tasks, settings, session, learning, protection, log, clock) { state, s, now -> Planner.plan(Planner.Input(state.tasks, now, LONDON, s)) }
    private val folder = File(dir, "photos")

    /** The tasks read again with their photos. */
    private val read: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val photos = Photos(tasks, settings, chat, clock, scope, gateway, focus, folder, shrink = { null }, profile = { Profile.Block.NONE }) { read += it }

    /** A stand-in for Claude's API, and the photos' service that asks it: Claude on, for the checks that are answered. */
    private val server = MockWebServer().apply { start() }
    private val claude = ModelGateway(settings, { "test-key" }, model, tasks, clock, NoAlerts, scope) {}.also { it.endpoint = server.url("").toString().trimEnd('/') }
    private val answered = Photos(tasks, settings, chat, clock, scope, claude, focus, folder, shrink = { null }, profile = { Profile.Block.NONE }) { read += it }

    /** Each of [texts] the stand-in's next answer, in turn, as the API sends one. */
    private fun answers(vararg texts: String) = texts.forEach { text ->
        val body = JsonObject(
            mapOf(
                "id" to JsonPrimitive("msg"), "type" to JsonPrimitive("message"), "role" to JsonPrimitive("assistant"), "model" to JsonPrimitive("claude-opus-5-5"),
                "content" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))),
                "stop_reason" to JsonPrimitive("end_turn"), "stop_sequence" to JsonNull,
                "usage" to JsonObject(mapOf("input_tokens" to JsonPrimitive(3_000), "output_tokens" to JsonPrimitive(100))),
            ),
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()))
    }

    /** A check's answer: [done], by how sure, what the pages [covered] and whether that's [fresh]. */
    private fun verdict(done: Boolean, covered: String, fresh: Boolean = true, reason: String = "Every question is answered.") =
        """{"done":$done,"confidence":0.9,"reason":"$reason","seen":"Pages of written work","covered":"$covered","fresh":$fresh}"""

    private val essay = TaskItem(
        id = "teams:essay", source = Source.Teams, sourceId = "essay", title = "Essay", dueAt = Fixtures.at("2026-10-23T09:00"), kind = Kind.Homework,
        firstSeenAt = clock.time, lastSeenAt = clock.time,
    )

    @AfterTest
    fun cleanUp() {
        server.shutdown()
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun task() = tasks.value.tasks.single()

    @Test
    fun `a photo of the work is kept with its task, its fingerprint on it, and the task read again with it`() = runBlocking {
        tasks.update { it.copy(tasks = listOf(essay)) }
        val jpeg = "made-up photo bytes".toByteArray()
        photos.attachPhoto(essay.id, jpeg)
        val kept = task().photo!!
        assertTrue(File(kept.path).readBytes().contentEquals(jpeg))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(jpeg).take(8).joinToString("") { "%02x".format(it) }, kept.hash)
        assertEquals(clock.now(), kept.at)
        until("read again with it") { read == listOf(essay.id) }
        // Another in its place: one photo a task.
        photos.attachPhoto(essay.id, "another made-up photo".toByteArray())
        assertEquals(1, folder.listFiles()!!.size)
    }

    @Test
    fun `a photo of homework is read by the server where it reads them, and here where it doesn't`() = runBlocking {
        val sent = mutableListOf<ByteArray>()
        val reading = PhotoTasks.Reading(emptyList(), listOf("a worksheet"))
        val byServer = Photos(tasks, settings, chat, clock, scope, gateway, focus, folder, shrink = { null }, profile = { Profile.Block.NONE }, readByServer = { sent += it; reading to null }) { read += it }
        val jpeg = "made-up photo bytes".toByteArray()
        assertEquals(reading to null, byServer.readPhotoTasks(jpeg))
        assertTrue(sent.single().contentEquals(jpeg))
        // Not the server's to read (mirroring): Claude's asked here, and it's off.
        assertEquals(null to "Claude is off: add it with New task.", photos.readPhotoTasks(jpeg))
    }

    @Test
    fun `a photo check begun as the switch holds the checks waits for it, and is then the server's, nothing checked here`() = runBlocking {
        var server = false
        val switching = Photos(
            tasks, settings, chat, clock, scope, gateway, focus, folder, shrink = { it.readBytes() }, profile = { Profile.Block.NONE },
            checkByServer = { _, _, _, _, _ -> "Done: every question answered".takeIf { server } },
        ) { read += it }
        val held = CompletableDeferred<Unit>()
        val quiet = launch(start = CoroutineStart.UNDISPATCHED) { switching.quiet { held.await(); server = true } }
        val checked = async { switching.check(pages("page-1.jpg"), piece(Kind.Homework), takenAt = 5L) }
        // As far as it gets while the switch holds the checks: not made here, nor done.
        delay(500)
        assertFalse(checked.isCompleted, "it waits for the switch")
        held.complete(Unit)
        quiet.join()
        assertEquals("Done: every question answered", checked.await())
    }

    @Test
    fun `a photo check's pages are the server's to check where it checks them, sent as taken, and theirs go whatever happens`() = runBlocking {
        val asked = mutableListOf<Triple<List<String>, String, Long>>()
        val byServer = Photos(
            tasks, settings, chat, clock, scope, gateway, focus, folder, shrink = { it.readBytes() }, profile = { Profile.Block.NONE },
            checkByServer = { jpegs, piece, takenAt, _, _ -> asked += Triple(jpegs.map { it.decodeToString() }, piece.key, takenAt); "Done: every question answered" },
        ) { read += it }
        val two = pages("page-1.jpg", "page-2.jpg")
        assertEquals("Done: every question answered", byServer.check(two, piece(Kind.Homework), takenAt = 5L))
        assertEquals(Triple(listOf("not an image", "not an image"), piece(Kind.Homework).key, 5L), asked.single())
        assertTrue(two.none { it.exists() })
        // What isn't written homework isn't sent.
        assertEquals("Only written homework can be checked from a photo.", byServer.check(pages("revision.jpg"), piece(Kind.Revision)))
        assertEquals(1, asked.size)
        // The server's own check, of pages as they came: what isn't written homework isn't checked there either; Claude off says so.
        assertEquals("Only written homework can be checked from a photo.", photos.checkJpegs(listOf(byteArrayOf(1)), piece(Kind.Revision), 5L))
        assertEquals("Claude is off: the timer or the source will have to do.", photos.checkJpegs(listOf(byteArrayOf(1)), piece(Kind.Homework), 5L))
    }

    @Test
    fun `on the server a task's photo is kept by its hash, and swept by it`() = runBlocking {
        val byHash = Photos(tasks, settings, chat, clock, scope, gateway, focus, folder, shrink = { null }, profile = { Profile.Block.NONE }, fileOf = { File(folder, "${it.hash}.jpg") }) { read += it }
        folder.mkdirs()
        val held = File(folder, "a1b2c3d4e5f60718.jpg").apply { writeBytes(byteArrayOf(1)) }
        val stray = File(folder, "f0e1d2c3b4a59687.jpg").apply { writeBytes(byteArrayOf(2)) }
        tasks.update { it.copy(tasks = listOf(essay.copy(photo = TaskPhoto("/the/phone's/path/teams_essay.jpg", "a1b2c3d4e5f60718", 1L)))) }
        // One come since the time given is kept, held or not, as its command's to come; one before it, held by none, goes.
        val fresh = File(folder, "0a1b2c3d4e5f6a7b.jpg").apply { writeBytes(byteArrayOf(3)) }
        stray.setLastModified(fresh.lastModified() - 2 * 86_400_000L)
        byHash.sweepPhotos(keptBefore = fresh.lastModified() - 86_400_000L)
        assertTrue(held.exists(), "held by its hash, whatever the phone's path")
        assertTrue(fresh.exists(), "come within the day, its command still to come")
        assertFalse(stray.exists())
        // As the phone sweeps: none kept for coming since.
        byHash.sweepPhotos()
        assertFalse(fresh.exists())
    }

    @Test
    fun `the photos no task holds are let go`() = runBlocking {
        tasks.update { it.copy(tasks = listOf(essay)) }
        photos.attachPhoto(essay.id, "made-up photo bytes".toByteArray())
        val stray = File(folder, "gone.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        photos.sweepPhotos()
        assertFalse(stray.exists())
        assertTrue(File(task().photo!!.path).exists())
    }

    @Test
    fun `a check's note is kept after a not yet and a pass, none where Claude couldn't say, and the next check of the task is told them`() = runBlocking {
        settings.update { it.copy(aiEnabled = true, aiKeyActive = true) }
        tasks.update { it.copy(tasks = listOf(essay)) }
        // The pass isn't fresh, but nothing that passed was shown before it, so that's no bar.
        answers(verdict(false, "Q1-8 worked", reason = "Q9-12 aren't shown."), "not an answer", verdict(true, "Q9-12 worked", fresh = false))
        val page = listOf(byteArrayOf(1, 2, 3))
        assertEquals("Not yet: Q9-12 aren't shown. What these showed is remembered: photograph the rest.", answered.checkJpegs(page, piece(Kind.Homework), clock.time))
        assertEquals("Claude couldn't say. Try a clearer photo.", answered.checkJpegs(page, piece(Kind.Homework), clock.time))
        val passed = answered.checkJpegs(page, piece(Kind.Homework), clock.time)
        assertTrue(passed.startsWith("Done"), passed)
        // The pass relied on the first's note, so that's counted with it: Q1-8 can't be shown again as new.
        assertEquals(listOf("Q1-8 worked" to true, "Q9-12 worked" to true), focus.notesOf(essay.id).map { it.covered to it.passed })
        // The first was told nothing earlier; the last was told the first's, as not passed.
        val first = server.takeRequest().body.readUtf8()
        server.takeRequest()
        val last = server.takeRequest().body.readUtf8()
        assertFalse("Earlier checks of this task" in first)
        assertTrue("not passed: Q1-8 worked" in last, last)
    }

    @Test
    fun `a second sitting showing only what a passed check showed is refused, kept as not passed, and one showing the rest passes`() = runBlocking {
        settings.update { it.copy(aiEnabled = true, aiKeyActive = true) }
        tasks.update { it.copy(tasks = listOf(essay)) }
        focus.noteCheck(CheckNote(essay.id, "teams:essay#-1#1", "Essay, part 1 of 2", "Pages 1 to 4, written out", passed = true, at = clock.time - 3_600_000))
        answers(verdict(true, "Pages 1 to 4, written out", fresh = false), verdict(true, "Pages 5 and 6", fresh = true))
        val pages = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        assertEquals("Not yet: these show only work an earlier check counted already. Photograph this sitting's work.", answered.checkJpegs(pages, piece(Kind.Homework), clock.time))
        assertEquals(false, focus.notesOf(essay.id).last().passed)
        val passed = answered.checkJpegs(pages, piece(Kind.Homework), clock.time)
        assertTrue(passed.startsWith("Done"), passed)
        // The pass relied on every earlier note, the refused one's too: all counted now.
        assertEquals(listOf(true, true, true), focus.notesOf(essay.id).map { it.passed })
        assertTrue("counted in a check that passed (earned already): Pages 1 to 4, written out" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `a check of the plan's next part keeps that part, though the part before was ticked with no photo and its key is the same`() = runBlocking {
        settings.update { it.copy(aiEnabled = true, aiKeyActive = true) }
        tasks.update { it.copy(tasks = listOf(essay.copy(sourceEffortMin = 120))) }
        fun plan() = Planner.plan(Planner.Input(tasks.value.tasks, clock.time, LONDON, settings.value))
        val first = plan().chunksOf(essay.id).first()
        assertTrue(focus.keepSlot(first.key))
        // An hour on, the next part's photo, checked as the plan now numbers it ("part 1 of 3"), under the key the part ticked has.
        clock.time += 3_600_000
        val next = plan().chunksOf(essay.id).first()
        assertEquals(first.key, next.key)
        answers(verdict(true, "Pages 1 to 6, written out"))
        val said = answered.checkJpegs(listOf(byteArrayOf(1, 2, 3)), next, clock.time)
        assertTrue(said.startsWith("Done:"), said)
        // The next part kept and earning; the part ticked before left as it was.
        assertEquals(listOf(first.label to false, next.label to true), log.value.slots.filter { it.taskId == essay.id && it.outcome.kept }.map { it.label to it.earned })
    }

    @Test
    fun `a check's pages its box left kept stay with the task, are sent again after a later check's new ones, and are swept only when nothing names them`() = runBlocking {
        settings.update { it.copy(aiEnabled = true, aiKeyActive = true) }
        tasks.update { it.copy(tasks = listOf(essay)) }
        answers(verdict(false, "Q1-4", reason = "Q5-8 aren't shown."), verdict(true, "Q5-8"))
        val first = listOf(byteArrayOf(1, 1), byteArrayOf(2, 2))
        answered.checkJpegs(first, piece(Kind.Homework), clock.time, keep = true)
        val kept = focus.keptPages(essay.id)
        assertEquals(first.map(Photos::hashOf), kept.map { it.hash })
        assertTrue(kept.all { answered.pageFile(it.hash).isFile })
        // Ticked, the next check's page isn't kept; the kept ones go with it, after it, named so.
        answered.checkJpegs(listOf(byteArrayOf(3, 3)), piece(Kind.Homework), clock.time)
        server.takeRequest()
        val second = server.takeRequest().body.readUtf8()
        assertTrue("Kept page 1, from an earlier check:" in second && "Kept page 2, from an earlier check:" in second, second)
        assertTrue(second.indexOf("Page 1:") < second.indexOf("Kept page 1"))
        assertEquals(2, focus.keptPages(essay.id).size)
        // A page nothing names goes at a sweep, the kept ones stay.
        val stray = answered.pageFile("0000000000000000").apply { writeBytes(byteArrayOf(1)) }
        answered.sweepPhotos()
        assertFalse(stray.exists())
        assertTrue(kept.all { answered.pageFile(it.hash).isFile })
    }

    @Test
    fun `a chat photo's file is kept by its hash, and let go once its message is done with it`() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9)
        val hash = Photos.hashOf(jpeg)
        assertFalse(photos.keepChatPhoto("a1b2c3d4e5f60718", jpeg), "not the photo its hash names")
        assertTrue(photos.keepChatPhoto(hash, jpeg))
        assertTrue(photos.chatPhoto(hash)!!.contentEquals(jpeg))
        val kept = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 8).also { photos.keepChatPhoto(Photos.hashOf(it), it) }
        val keptHash = Photos.hashOf(kept)
        val waiting = ChatMessage("y1", Speaker.You, "", clock.time, photos = listOf(ChatPhoto(hash), ChatPhoto(keptHash, kept = true)))
        chat.update { it.copy(messages = listOf(waiting)) }
        // Its message waiting: both kept, however long ago they came.
        photos.sweepPhotos()
        assertTrue(photos.chatPhotoFile(hash).isFile && photos.chatPhotoFile(keptHash).isFile)
        // Answered: the ticked one goes at once, grace or none; the kept one stays its fortnight.
        chat.update { it.copy(messages = listOf(waiting.copy(answeredAt = clock.time))) }
        photos.sweepPhotos(keptBefore = clock.time - 86_400_000L)
        assertFalse(photos.chatPhotoFile(hash).exists())
        assertTrue(photos.chatPhotoFile(keptHash).isFile)
        clock.time += ChatPhoto.KEPT_MS
        photos.sweepPhotos(keptBefore = clock.time - 86_400_000L)
        assertFalse(photos.chatPhotoFile(keptHash).exists())
        // One come ahead of its message waits the grace; once named, gone with its message at once (taken back, the chat cleared).
        val ahead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7).also { photos.keepChatPhoto(Photos.hashOf(it), it) }
        val aheadHash = Photos.hashOf(ahead)
        photos.sweepPhotos(keptBefore = clock.time - 86_400_000L)
        assertTrue(photos.chatPhotoFile(aheadHash).isFile)
        chat.update { it.copy(messages = listOf(ChatMessage("y2", Speaker.You, "", clock.time, photos = listOf(ChatPhoto(aheadHash, kept = true))))) }
        photos.sweepPhotos(keptBefore = clock.time - 86_400_000L)
        chat.update { it.copy(messages = emptyList()) }
        photos.sweepPhotos(keptBefore = clock.time - 86_400_000L)
        assertFalse(photos.chatPhotoFile(aheadHash).exists())
    }

    @Test
    fun `a chat photo kept with a task is a copy of its own, read again with it`() = runBlocking {
        tasks.update { it.copy(tasks = listOf(essay)) }
        // The photo it had, its file still there once another's kept in its place, for the Undo that brings it back.
        val before = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 5)
        photos.attachPhoto(essay.id, before)
        val had = task().photo!!
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 6)
        val hash = Photos.hashOf(jpeg)
        photos.keepChatPhoto(hash, jpeg)
        val photo = photos.keepFromChat(essay.id, hash, jpeg)
        assertTrue(File(had.path).readBytes().contentEquals(before))
        assertEquals(hash, task().photo?.hash)
        assertEquals(photo, task().photo)
        photos.chatPhotoFile(hash).delete()
        assertTrue(File(photo.path).readBytes().contentEquals(jpeg), "its own copy stays as the chat's goes")
        while (essay.id !in read) delay(10)
    }

    @Test
    fun `a kept slot's piece keeps its part's share from its key and label, and a named step its place`() {
        val box = SlotRecord(date = "2026-10-19", key = "teams:essay#-1#2", taskId = essay.id, kind = Kind.Homework, label = "Essay: part 2 of 3", plannedMin = 40, start = 1, end = 2, window = WindowKind.Evening, outcome = SlotOutcome.Kept, workedMin = 40, at = 3)
        val piece = Photos.pieceOf(box, essay)!!
        assertEquals(2 to 3, piece.part to piece.parts)
        assertEquals(null, piece.stepIndex)
        assertTrue("It's part 2 of 3: with the parts before it, about two thirds" in PhotoChecker.context(piece, essay, emptyList(), LONDON))
        val step = Photos.pieceOf(box.copy(key = "teams:essay#1", label = "Essay: Plan it"), essay)!!
        assertEquals(1 to 1, step.stepIndex to step.parts)
        assertEquals(null, Photos.pieceOf(box, null))
        // The task's work in all: its own estimate where it's more than the slot, else the slot's minutes a part.
        assertEquals(150, Photos.pieceOf(box, essay.copy(sourceEffortMin = 150, aiEffortMin = null))!!.taskMinutes)
        assertEquals(120, Photos.pieceOf(box, essay.copy(sourceEffortMin = 30, aiEffortMin = null))!!.taskMinutes)
    }

    @Test
    fun `a photo check of a piece that isn't written homework, or of pages that can't be read, says so, and its pages go`() = runBlocking {
        val revision = pages("revision-1.jpg")
        assertEquals("Only written homework can be checked from a photo.", photos.check(revision, piece(Kind.Revision)))
        assertTrue(revision.none { it.exists() })
        val unreadable = pages("page-1.jpg", "page-2.jpg")
        assertEquals("A page couldn't be read: take the photos again.", photos.check(unreadable, piece(Kind.Homework)))
        assertTrue(unreadable.none { it.exists() })
    }

    /** Photos of a piece's pages, made up: not images at all. */
    private fun pages(vararg names: String) = names.map { File(dir, it).apply { writeText("not an image") } }

    private fun piece(kind: Kind) = Chunk(
        taskId = essay.id, source = Source.Teams, kind = kind, title = "Essay", step = null, minutes = 30, dueAt = essay.dueAt,
        deadline = essay.dueAt!!, soft = false, overdue = false, dueToday = false, behind = false, part = 1, parts = 1, taskMinutes = 30, why = Why.Spread,
    )

    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "never: $what" }
            Thread.sleep(10)
        }
    }

    private object NoAlerts : ModelGateway.Alerts {
        override fun keyProblem(problem: KeyProblem): Boolean = false
        override fun keyFixed() = Unit
        override fun dropped(task: TaskItem, why: String): Boolean = false
        override fun withdrawExcept(standing: Set<String>) = Unit
    }
}
