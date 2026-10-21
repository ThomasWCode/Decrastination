package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.GmailWriteStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.sources.gmail.GmailWriter
import com.thomaswcode.decrastination.sources.gmail.GmailWriter.Change
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A finished email's writes to Gmail through [GmailFinishes] (docs/plan_v4/10-emails-done.md §Tests): journaled on a
 * temporary store, made through a fake Gmail, the grace, the first finish's OK, Undo before and after, tries and giving up.
 */
class GmailFinishesTest {
    private val dir: File = Files.createTempDirectory("gmail-writes").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-21T17:00"))
    private val writes = JsonStore(File(dir, "gmail-writes.json"), GmailWriteStore.serializer(), ::GmailWriteStore)
    private var settings = Settings(hintsSeen = listOf(GmailWrites.OK_HINT))

    /** An email whose one box is kept: its work all done. */
    private val email = TaskItem(
        id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "Trip consent form", dueAt = null, kind = Kind.Admin,
        sourceEffortMin = 10, firstSeenAt = clock.time, lastSeenAt = clock.time, workedMin = 10,
    )
    private val tasks = mutableMapOf(email.id to email)

    /** What Android was asked: the worker's runs, Gmail reads, notifications said and taken away. */
    private val scheduled = mutableListOf<Long>()
    private val ensured = mutableListOf<Long>()
    private var synced = 0
    private val said = mutableListOf<String>()
    private val sorted = mutableListOf<String>()
    private val ports = object : GmailFinishes.Ports {
        override fun schedule(at: Long) { scheduled += at }
        override fun ensureScheduled(at: Long) { ensured += at }
        override fun syncGmail() { synced++ }
        override fun gaveUp(write: GmailWrite, words: String) { said += words }
        override fun sorted(write: GmailWrite) { sorted += write.threadId }
    }
    private var ids = 0
    private var gmailRead: Long? = null
    private val finishes = GmailFinishes(writes, { settings }, { tasks[it] }, { 1.0 }, clock, ports, readAt = { gmailRead }) { "w${++ids}" }

    /** Gmail, faked: the conversation's two messages (the first starred, both in the inbox), what was stored, and failures to make. */
    private inner class FakeGmail : GmailWriter {
        val messages = mutableListOf(GmailWriter.Message(11, "101", starred = true, inbox = true), GmailWriter.Message(12, "102", starred = false, inbox = true))
        val stored = mutableListOf<Pair<List<Long>, Change>>()
        var failing: Change? = null
        var offline = false

        override suspend fun <T> open(block: suspend (GmailWriter.Session) -> T): T {
            if (offline) throw UnknownHostException("imap.gmail.com")
            return block(object : GmailWriter.Session {
                override fun thread(threadId: String): List<Long> = messages.map { it.uid }
                override fun messages(uids: List<Long>): List<GmailWriter.Message> = messages.filter { it.uid in uids }
                override fun store(uids: List<Long>, change: Change): List<Long> {
                    if (change == failing) throw IOException("connection reset")
                    stored += uids to change
                    messages.replaceAll { m ->
                        if (m.uid !in uids) m else when (change) {
                            Change.Unstar -> m.copy(starred = false)
                            Change.Star -> m.copy(starred = true)
                            Change.Archive -> m.copy(inbox = false)
                            Change.Inbox -> m.copy(inbox = true)
                        }
                    }
                    return emptyList()
                }
            })
        }
    }

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun `a finish waits out its grace, then unstars what was starred and archives the conversation, and asks Gmail to read`() = runBlocking {
        val gmail = FakeGmail()
        assertEquals(GmailFinishes.Finish.Going, finishes.finish(email.id, explicit = false))
        assertEquals(listOf(clock.time + GmailWrites.GRACE_MS), scheduled)
        // In its grace: nothing written, the worker set again for its end.
        assertFalse(finishes.write(gmail))
        assertTrue(gmail.stored.isEmpty())
        clock.time += GmailWrites.GRACE_MS
        assertFalse(finishes.write(gmail))
        assertEquals(listOf(listOf(11L) to Change.Unstar, listOf(11L, 12L) to Change.Archive), gmail.stored)
        val receipt = finishes.value.single()
        assertEquals(listOf("101") to listOf("101", "102"), receipt.flagged to receipt.inbox)
        assertTrue(receipt.unstarred && receipt.archived && receipt.doneAt == clock.time)
        assertEquals(1, synced)
        // Still open, as its read hasn't come: a second finish writes nothing, till a read shows it starred again.
        assertEquals(GmailFinishes.Finish.Pending, finishes.finish(email.id, explicit = true))
        gmailRead = clock.time + 1
        clock.time += 2
        assertEquals(GmailFinishes.Finish.Going, finishes.finish(email.id, explicit = true))
    }

    @Test
    fun `a write journaled when a stop came before its worker was set is set going at the next start, one set or running left be`() = runBlocking {
        writes.update { it.copy(writes = listOf(GmailWrite("w9", email.id, email.title, "t1", clock.time, notBefore = clock.time + GmailWrites.GRACE_MS))) }
        finishes.rearm()
        assertEquals(listOf(clock.time + GmailWrites.GRACE_MS), ensured)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `his act's Undo takes back its own finish, and a keep taken back leaves one by your word`() = runBlocking {
        finishes.finish(email.id, explicit = true)
        val id = finishes.latestFinish(email.id)
        assertFalse(finishes.unfinish(email.id, byWork = true))
        assertFalse(finishes.unfinish(email.id, writeId = "another"))
        assertTrue(finishes.unfinish(email.id, writeId = id))
    }

    @Test
    fun `the switch off writes nothing, and work left is finished by your word only`() = runBlocking {
        settings = settings.copy(gmailFinishes = false)
        assertEquals(GmailFinishes.Finish.Off, finishes.finish(email.id, explicit = true))
        settings = settings.copy(gmailFinishes = true)
        tasks[email.id] = email.copy(workedMin = 0)
        assertEquals(GmailFinishes.Finish.No, finishes.finish(email.id, explicit = false))
        assertEquals(GmailFinishes.Finish.Going, finishes.finish(email.id, explicit = true))
        assertEquals(GmailFinishes.Finish.Pending, finishes.finish(email.id, explicit = true))
        // Not an email: nothing.
        tasks["teams:a"] = email.copy(id = "teams:a", source = Source.Teams)
        assertEquals(GmailFinishes.Finish.No, finishes.finish("teams:a", explicit = true))
        assertEquals(1, finishes.value.size)
    }

    @Test
    fun `the very first finish waits for your OK, Carry on sends it, Not this time drops it`() = runBlocking {
        settings = Settings()
        assertEquals(GmailFinishes.Finish.Awaiting, finishes.finish(email.id, explicit = true))
        assertTrue(scheduled.isEmpty())
        clock.time += 10 * GmailWrites.GRACE_MS
        assertFalse(finishes.write(FakeGmail()))
        assertNull(finishes.value.single().notBefore)
        finishes.refuse()
        assertTrue(finishes.value.isEmpty())
        // Asked again at the next finish; carried on, it goes after the grace from then.
        assertEquals(GmailFinishes.Finish.Awaiting, finishes.finish(email.id, explicit = true))
        finishes.confirm()
        assertEquals(clock.time + GmailWrites.GRACE_MS, finishes.value.single().notBefore)
        assertEquals(listOf(clock.time + GmailWrites.GRACE_MS), scheduled)
    }

    @Test
    fun `taken back within its grace, Gmail is never touched`() = runBlocking {
        val gmail = FakeGmail()
        finishes.finish(email.id, explicit = false)
        assertTrue(finishes.unfinish(email.id))
        clock.time += GmailWrites.GRACE_MS
        assertFalse(finishes.write(gmail))
        assertTrue(gmail.stored.isEmpty() && finishes.value.isEmpty() && synced == 0)
        assertFalse(finishes.unfinish(email.id))
    }

    @Test
    fun `taken back after its write, only what it took is put back`() = runBlocking {
        val gmail = FakeGmail()
        finishes.finish(email.id, explicit = false)
        clock.time += GmailWrites.GRACE_MS
        finishes.write(gmail)
        gmail.stored.clear()
        assertTrue(finishes.unfinish(email.id))
        assertEquals(clock.time, scheduled.last())
        assertFalse(finishes.write(gmail))
        // The one that was starred starred again; both back in the inbox; the other never starred.
        assertEquals(listOf(listOf(11L) to Change.Star, listOf(11L, 12L) to Change.Inbox), gmail.stored)
        assertEquals(listOf(true, false), gmail.messages.map { it.starred })
        assertEquals(GmailWrites.State.TakenBack, GmailWrites.state(finishes.value, email.id))
        assertEquals(2, synced)
        // Taken back already: nothing more.
        assertFalse(finishes.unfinish(email.id))
    }

    @Test
    fun `a try failing between its two commands goes on at the second, and five failures give it up, saying what succeeded`() = runBlocking {
        val gmail = FakeGmail()
        finishes.finish(email.id, explicit = false)
        clock.time += GmailWrites.GRACE_MS
        gmail.failing = Change.Archive
        assertTrue(finishes.write(gmail))
        val tried = finishes.value.single()
        assertTrue(tried.unstarred && !tried.archived && tried.tries == 1 && tried.doneAt == null)
        // Again, once its wait's over: the star isn't taken off twice.
        gmail.failing = null
        gmail.stored.clear()
        assertFalse(finishes.write(gmail))
        assertTrue(gmail.stored.isEmpty())
        clock.time += GmailWrites.backoff(1)
        assertFalse(finishes.write(gmail))
        assertEquals(listOf(listOf(11L, 12L) to Change.Archive), gmail.stored)
        assertTrue(finishes.value.single().archived)
        // Stopped mid-write (the worker cancelled): not a try that failed.
        tasks["gmail:t3"] = email.copy(id = "gmail:t3", sourceId = "t3", title = "Club email")
        finishes.finish("gmail:t3", explicit = true)
        clock.time += GmailWrites.GRACE_MS
        val stopping = object : GmailWriter {
            override suspend fun <T> open(block: suspend (GmailWriter.Session) -> T): T = throw kotlinx.coroutines.CancellationException("stopped")
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> { finishes.write(stopping) }
        assertEquals(0, finishes.value.single { it.taskId == "gmail:t3" }.tries)
        assertFalse(finishes.write(gmail))
        // Another email, offline each time: given up at the fifth, said, and its receipt kept till it's sorted.
        tasks["gmail:t2"] = email.copy(id = "gmail:t2", sourceId = "t2", title = "Club email")
        finishes.finish("gmail:t2", explicit = true)
        clock.time += GmailWrites.GRACE_MS
        gmail.offline = true
        repeat(GmailWrites.MAX_TRIES - 1) { assertTrue(finishes.write(gmail)); clock.time += GmailWrites.backoff(it + 1) }
        assertFalse(finishes.write(gmail))
        assertEquals(listOf("Couldn't unstar “Club email” in Gmail (no connection): it's still starred."), said)
        assertEquals(GmailWrites.State.GaveUp, GmailWrites.state(finishes.value, "gmail:t2"))
        finishes.sorted("gmail:t2")
        assertEquals(listOf("t2"), sorted)
        assertEquals(GmailWrites.State.None, GmailWrites.state(finishes.value, "gmail:t2"))
    }

    @Test
    fun `a failed try waits out its backoff, whatever else sets the worker going`() = runBlocking {
        val gmail = FakeGmail()
        finishes.finish(email.id, explicit = false)
        clock.time += GmailWrites.GRACE_MS
        gmail.offline = true
        assertTrue(finishes.write(gmail))
        val failedAt = clock.time
        assertEquals(failedAt + GmailWrites.backoff(1), scheduled.last())
        // Another email finished meanwhile: the worker's set for its grace, and that run leaves the failed one waiting.
        gmail.offline = false
        tasks["gmail:t2"] = email.copy(id = "gmail:t2", sourceId = "t2", title = "Club email")
        finishes.finish("gmail:t2", explicit = true)
        assertEquals(clock.time + GmailWrites.GRACE_MS, scheduled.last())
        clock.time += GmailWrites.GRACE_MS
        assertFalse(finishes.write(gmail))
        val waiting = finishes.value.single { it.taskId == email.id }
        assertTrue(waiting.tries == 1 && waiting.doneAt == null)
        assertEquals(failedAt + GmailWrites.backoff(1), scheduled.last())
        // Its wait over: it goes.
        clock.time = failedAt + GmailWrites.backoff(1)
        assertFalse(finishes.write(gmail))
        assertTrue(finishes.value.single { it.taskId == email.id }.archived)
    }

    @Test
    fun `a keep taken back leaves a finish whose work is still all done, and takes back one whose isn't`() = runBlocking {
        val steps = email.copy(subSteps = listOf(SubStep("Read it", 5, done = true), SubStep("Reply", 5, done = true)))
        tasks[email.id] = steps
        assertEquals(GmailFinishes.Finish.Going, finishes.finish(email.id, explicit = false))
        // A sitting that ticked nothing taken back: every step still ticked.
        tasks[email.id] = steps.copy(workedMin = 0)
        assertFalse(finishes.unworked(email.id))
        assertEquals(GmailWrites.State.Going, GmailWrites.state(finishes.value, email.id))
        // A step unticked: its work isn't all done, so the finish goes.
        tasks[email.id] = steps.copy(subSteps = steps.subSteps.mapIndexed { i, s -> if (i == 1) s.copy(done = false) else s })
        assertTrue(finishes.unworked(email.id))
        assertEquals(GmailWrites.State.None, GmailWrites.state(finishes.value, email.id))
    }

    @Test
    fun `a finish again after one given up takes its place, its notification taken away`() = runBlocking {
        val gmail = FakeGmail()
        finishes.finish(email.id, explicit = true)
        clock.time += GmailWrites.GRACE_MS
        gmail.offline = true
        repeat(GmailWrites.MAX_TRIES) { finishes.write(gmail); clock.time += GmailWrites.backoff(it + 1) }
        assertEquals(GmailWrites.State.GaveUp, GmailWrites.state(finishes.value, email.id))
        assertEquals(GmailFinishes.Finish.Going, finishes.finish(email.id, explicit = true))
        assertEquals(listOf("t1"), sorted)
        assertTrue(GmailWrites.givenUp(finishes.value).isEmpty())
        assertEquals(GmailWrites.State.Going, GmailWrites.state(finishes.value, email.id))
    }

    @Test
    fun `settled, a finish's receipt goes once a read that began after it finds the email starred again`() = runBlocking {
        finishes.finish(email.id, explicit = true)
        clock.time += GmailWrites.GRACE_MS
        finishes.write(FakeGmail())
        // The read after it closes the task: its receipt stays, for its Undo.
        tasks[email.id] = email.copy(status = Status.Done, doneAt = clock.time)
        gmailRead = clock.time + 1
        finishes.settle()
        assertEquals(GmailWrites.State.Made, GmailWrites.state(finishes.value, email.id))
        // Starred again, and a later read finds it open: a new round, the last one's receipt gone.
        tasks[email.id] = email
        gmailRead = clock.time + 2
        finishes.settle()
        assertEquals(GmailWrites.State.None, GmailWrites.state(finishes.value, email.id))
    }

    @Test
    fun `switched off, the first finish waiting for your OK is dropped, Carry on sends nothing, and one in its grace never goes`() = runBlocking {
        settings = Settings()
        assertEquals(GmailFinishes.Finish.Awaiting, finishes.finish(email.id, explicit = true))
        settings = settings.copy(gmailFinishes = false)
        finishes.confirm()
        assertTrue(finishes.value.isEmpty() && scheduled.isEmpty())
        settings = Settings(hintsSeen = listOf(GmailWrites.OK_HINT))
        finishes.finish(email.id, explicit = true)
        settings = settings.copy(gmailFinishes = false)
        val gmail = FakeGmail()
        clock.time += GmailWrites.GRACE_MS
        assertFalse(finishes.write(gmail))
        assertTrue(finishes.value.isEmpty() && gmail.stored.isEmpty())
    }
}
