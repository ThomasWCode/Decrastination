package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import com.thomaswcode.decrastination.views.TaskAsk
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** What the website asks of a task (docs/spec/website.md): made the commands the phone's sheet would make, by the phone's rules. */
class TaskAskTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("asks").toFile()
    private val db = Database(File(dir, "test.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
    private val graph = ServerGraph(db, dir, Plain, "test")
    private val own = "own:s"
    private val teams = "teams:t"

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun withTasks() {
        graph.applier.apply(CommandBatch(listOf(Command("own", System.currentTimeMillis(), SaveOwnTask("s", OwnTask(title = "Essay", minutes = 30))))), Client.WEBSITE)
        val now = System.currentTimeMillis()
        graph.stores.tasks.update { it.copy(tasks = it.tasks + TaskItem(teams, Source.Teams, "t", "Worksheet", kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now)) }
    }

    private fun task(id: String) = graph.stores.tasks.value.tasks.single { it.id == id }

    private fun day(plus: Long) = Instant.now().atZone(graph.clock.zone()).toLocalDate().plusDays(plus).toString()

    @Test
    fun `a due date on yours changes it, on a source's it's a change of yours`() = runBlocking<Unit> {
        withTasks()
        val at = System.currentTimeMillis() + 7 * 86_400_000L
        assertTrue(graph.ask(own, TaskAsk(TaskAsk.DUE, at = at)).done)
        assertEquals(at / 60_000, assertNotNull(task(own).dueAt) / 60_000)
        assertTrue(graph.stores.instructions.value.applied.isEmpty(), "yours is changed itself")
        assertTrue(graph.ask(teams, TaskAsk(TaskAsk.DUE, at = at)).done)
        assertEquals(1, graph.stores.instructions.value.applied.size)
        assertNotNull(task(teams).mine.dueAt?.value)
    }

    @Test
    fun `minutes are held to what the task can take`() = runBlocking<Unit> {
        withTasks()
        assertTrue(graph.ask(own, TaskAsk(TaskAsk.MINUTES, minutes = 45)).done)
        assertEquals(45, task(own).sourceEffortMin)
        val refused = graph.ask(own, TaskAsk(TaskAsk.MINUTES, minutes = 0))
        assertFalse(refused.done)
        assertEquals("Its minutes must be from 1 to 3600", refused.said)
    }

    @Test
    fun `a start is a day, and yours keeps what the form would take`() = runBlocking<Unit> {
        withTasks()
        assertTrue(graph.ask(own, TaskAsk(TaskAsk.START, date = day(2))).done)
        assertNotNull(task(own).notBefore)
        val late = System.currentTimeMillis() + 86_400_000L
        val refused = graph.ask(own, TaskAsk(TaskAsk.DUE, at = late))
        assertFalse(refused.done, "it can't be due before it can start")
        assertEquals("It can't start after it's due", refused.said)
    }

    @Test
    fun `what's asked of a task that's gone is said, and what isn't understood`() = runBlocking<Unit> {
        assertEquals("That task had gone", graph.ask("own:nothing", TaskAsk(TaskAsk.DUE, at = 1)).said)
        withTasks()
        assertEquals("That can't be asked", graph.ask(own, TaskAsk("something else")).said)
        assertEquals("That needs a date and a time", graph.ask(own, TaskAsk(TaskAsk.DUE)).said)
        assertEquals("That needs a date", graph.ask(own, TaskAsk(TaskAsk.START, date = "not a date")).said)
    }

    @Test
    fun `all of it moves to a day, said in the phone's words`() = runBlocking<Unit> {
        withTasks()
        val answer = graph.ask(own, TaskAsk(TaskAsk.TO_DAY, date = day(3)))
        assertTrue(answer.done, answer.said)
        assertTrue(assertNotNull(answer.said).startsWith("All of it on"))
        assertEquals(1, graph.stores.instructions.value.applied.size)
    }

    @Test
    fun `its next piece moves to a time, one that's gone refused`() = runBlocking<Unit> {
        withTasks()
        val gone = graph.ask(own, TaskAsk(TaskAsk.TO_TIME, date = day(-1), minute = 600))
        assertFalse(gone.done)
        assertEquals("That time's gone: pick a later one.", gone.said)
        assertEquals("That needs a time", graph.ask(own, TaskAsk(TaskAsk.TO_TIME, date = day(1), minute = 24 * 60)).said)
        val answer = graph.ask(own, TaskAsk(TaskAsk.TO_TIME, date = day(1), minute = 17 * 60))
        assertTrue(answer.done, answer.said)
        assertTrue(assertNotNull(answer.said).startsWith("At "))
    }

    @Test
    fun `Done is of the piece the page showed, and if that's changed, of none`() = runBlocking<Unit> {
        withTasks()
        val shown = assertNotNull(assertNotNull(graph.task(own)).actions.first { it.label == "Done" }.ask)
        val stale = graph.ask(own, shown.copy(key = "a piece done elsewhere since"))
        assertFalse(stale.done)
        assertEquals("That piece has changed since (done, or planned again).", stale.said)
        assertTrue(graph.stores.log.value.slots.isEmpty(), "and the next isn't kept in its place")
        assertFalse(graph.ask(own, TaskAsk(TaskAsk.DONE)).done, "nor one that doesn't say which")
    }

    @Test
    fun `a task with nothing planned has nothing to move or do`() = runBlocking<Unit> {
        withTasks()
        graph.stores.tasks.update { state -> state.copy(tasks = state.tasks.map { it.copy(status = com.thomaswcode.decrastination.core.Status.Done) }) }
        assertFalse(graph.ask(own, TaskAsk(TaskAsk.TO_DAY, date = day(1))).done)
        assertFalse(graph.ask(own, TaskAsk(TaskAsk.DONE)).done)
    }

    @Test
    fun `Done keeps its next piece, says what it earned, and can be taken back`() = runBlocking<Unit> {
        withTasks()
        val before = graph.task(own)
        val undo = assertNotNull(assertNotNull(before).actions.first { it.label == "Done" }.undo)
        val done = assertNotNull(before.actions.first { it.label == "Done" }.ask)
        val answer = graph.ask(own, done)
        assertTrue(answer.done, answer.said)
        assertTrue(assertNotNull(answer.said).startsWith("Done"))
        assertEquals(1, graph.stores.log.value.slots.size)
        val back = graph.applier.apply(CommandBatch(listOf(Command("undo", System.currentTimeMillis(), undo))), Client.WEBSITE).single()
        assertNull(back.why)
    }

    @Test
    fun `a photo's offered only with Claude's key on the box and tasks that can be saved`() = runBlocking<Unit> {
        assertNull(graph.work().photoSide, "no key")
        graph.stores.settings.update { it.copy(aiEnabled = true) }
        assertNull(graph.work().photoSide, "Claude on, and still no key")
        val keyed = ServerGraph(db, dir, Plain, "test", claudeKey = "a made-up key")
        assertNotNull(keyed.work().photoSide)
        keyed.stores.settings.update { it.copy(aiEnabled = false) }
        assertNull(keyed.work().photoSide, "Claude's off")
    }

    @Test
    fun `with no key, a photo says so and calls nothing`() = runBlocking<Unit> {
        val read = graph.readPhoto(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0))
        assertTrue(read.tasks.isEmpty())
        assertNotNull(read.why)
    }

    @Test
    fun `Work says whether anything can be changed`() = runBlocking<Unit> {
        withTasks()
        assertTrue(graph.work().canChange)
        graph.modeForATest("mirror")
        assertFalse(graph.work().canChange)
    }
}
