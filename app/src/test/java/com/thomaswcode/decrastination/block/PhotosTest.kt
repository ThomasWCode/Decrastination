package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Why
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelGateway
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The photos' service on temporary stores (docs/plan_v4, Stage 6d): a photo of a task's work kept
 * with it and read with it, the photos no task holds let go, and a photo check's early answers, its
 * pages let go whatever happens. Claude is off; the image's own handling is `PhotoTurnTest`'s.
 */
class PhotosTest {
    private val dir: File = Files.createTempDirectory("photos").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T16:00"), LONDON)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState)
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings)
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME))
    private val log = JsonStore(File(dir, "log.json"), ActivityLog.serializer(), ::ActivityLog, migrations = listOf(ChatMigration.LOG))
    private val chat = JsonStore(File(dir, "chat.json"), ChatState.serializer(), ::ChatState, migrations = listOf(ChatMigration.CHAT))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gateway = ModelGateway(settings, { null }, runtime, tasks, clock, NoAlerts, scope) {}
    private val focus = Focus(tasks, settings, runtime, log, clock) { state, s, now -> Planner.plan(Planner.Input(state.tasks, now, LONDON, s)) }
    private val folder = File(dir, "photos")

    /** The tasks read again with their photos. */
    private val read: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val photos = Photos(tasks, settings, chat, clock, scope, gateway, focus, folder, profile = { Profile.Block.NONE }) { read += it }

    private val essay = TaskItem(
        id = "teams:essay", source = Source.Teams, sourceId = "essay", title = "Essay", dueAt = Fixtures.at("2026-10-23T09:00"), kind = Kind.Homework,
        firstSeenAt = clock.time, lastSeenAt = clock.time,
    )

    @AfterTest
    fun cleanUp() {
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
    fun `the photos no task holds are let go`() = runBlocking {
        tasks.update { it.copy(tasks = listOf(essay)) }
        photos.attachPhoto(essay.id, "made-up photo bytes".toByteArray())
        val stray = File(folder, "gone.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        photos.sweepPhotos()
        assertFalse(stray.exists())
        assertTrue(File(task().photo!!.path).exists())
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
