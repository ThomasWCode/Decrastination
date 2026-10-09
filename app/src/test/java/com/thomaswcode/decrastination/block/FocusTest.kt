package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.EndedSession
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SessionRecord
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class FocusTest {
    private val dir: File = Files.createTempDirectory("focus").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-10T12:00"), LONDON)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState)
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings)
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState)
    private val log = JsonStore(File(dir, "log.json"), ActivityLog.serializer(), ::ActivityLog)
    private val focus = Focus(tasks, settings, runtime, log, clock) { state, s, now -> Planner.plan(Planner.Input(state.tasks, now, LONDON, s)) }

    private fun task(id: String, kind: Kind = Kind.Homework, due: String? = "2026-10-20T09:00", steps: List<SubStep> = emptyList(), effort: Int = 45) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = due?.let(Fixtures::at), kind = kind,
        sourceEffortMin = effort, subSteps = steps, firstSeenAt = clock.time, lastSeenAt = clock.time,
    )

    @Test
    fun `a finished session marks its step done and earns a third of its minutes`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("t", steps = listOf(SubStep("Q1-8", 30), SubStep("Q9-16", 30))))) }
        focus.startSession("teams:t", "t: Q1-8", "Q1-8", 30)
        assertIs<BlockPolicy.Verdict.Block>(focus.verdict())
        clock.time += 30 * 60_000L
        val record = focus.stopSession()!!
        assertTrue(record.completed)
        assertEquals(listOf(true, false), tasks.value.tasks.single().subSteps.map { it.done })
        assertEquals(10 * 60_000L, focus.creditLeftMs())
        assertNull(runtime.value.session)
        assertEquals(1, log.value.sessions.size)
    }

    @Test
    fun `a session ended twice at once is finished once`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("t"))) }
        focus.startSession("teams:t", "t", null, 30)
        clock.time += 30 * 60_000L
        val records = listOf(async { focus.stopSession() }, async { focus.stopSession() }).awaitAll()
        assertEquals(1, records.count { it != null })
        assertEquals(30, tasks.value.tasks.single().workedMin)
        assertEquals(1, log.value.sessions.size)
        assertEquals(10 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `a session's end is the uptime clock's, the date set either way`() {
        val session = FocusSession("teams:t", "t", null, 30, startedAt = clock.time, startedUptime = Uptime(1, 0))
        // Set forward half an hour after two minutes: not due.
        assertEquals(false, session.isDue(clock.time + 30 * 60_000L, Uptime(1, 2 * 60_000L)))
        // Set back an hour after the half hour: due all the same.
        assertTrue(session.isDue(clock.time - 60 * 60_000L, Uptime(1, 30 * 60_000L)))
        // Across a restart, the wall clock says.
        assertTrue(session.isDue(clock.time + 30 * 60_000L, Uptime(2, 1_000L)))
    }

    @Test
    fun `setting the date forward doesn't finish a session`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("t"))) }
        clock.up = Uptime(1, 1_000_000L)
        focus.startSession("teams:t", "t", null, 30)
        // The wall clock jumps the half hour; the phone has been on for two minutes.
        clock.time += 30 * 60_000L
        clock.up = Uptime(1, 1_000_000L + 2 * 60_000L)
        val record = focus.stopSession()!!
        assertEquals(false, record.completed)
        assertEquals(2, record.workedMin)
        assertEquals(0L, focus.creditLeftMs())
    }

    @Test
    fun `work confirmed done during its session ends the session, its minutes counted once`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("t", effort = 40))) }
        focus.startSession("teams:t", "t", null, 30)
        clock.time += 10 * 60_000L
        focus.onCompleted(listOf(tasks.value.tasks.single().copy(status = Status.Done)))
        assertNull(runtime.value.session)
        assertEquals(10, tasks.value.tasks.single().workedMin)
        // The 30 minutes left of the estimate, a minute for three: ten, and no session's credit.
        assertEquals(10 * 60_000L, focus.creditLeftMs())
        // And the completion's record counts the session's minutes.
        assertEquals(10, log.value.completions.single().workedMin)
    }

    @Test
    fun `a session stopped early counts its minutes but earns nothing`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("t"))) }
        focus.startSession("teams:t", "t", null, 45)
        clock.time += 20 * 60_000L
        val record = focus.stopSession()!!
        assertEquals(false, record.completed)
        assertEquals(20, tasks.value.tasks.single().workedMin)
        assertEquals(0L, focus.creditLeftMs())
    }

    @Test
    fun `confirmed work earns time, reading emails doesn't`() = runTest {
        val homework = task("hw", effort = 45).copy(status = Status.Done, doneAt = clock.time)
        val email = task("email", kind = Kind.Info, effort = 2).copy(status = Status.Done, doneAt = clock.time)
        focus.onCompleted(listOf(homework, email))
        assertEquals(15 * 60_000L, focus.creditLeftMs())
        assertEquals(listOf("teams:hw", "teams:email"), log.value.completions.map { it.taskId })
    }

    @Test
    fun `with nothing due soon, earned time is spent while a blocked app is in front`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("later", due = "2026-10-30T09:00"))) }
        focus.onCompleted(listOf(task("hw", effort = 30).copy(status = Status.Done, doneAt = clock.time)))
        assertEquals(BlockPolicy.Verdict.Spend, focus.verdict())
        focus.spend(10 * 60_000L)
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.NoFreeTime), focus.verdict())
    }

    @Test
    fun `what was just spent is off the credit at once, before it's saved`() = runTest {
        focus.onCompleted(listOf(task("hw", effort = 30).copy(status = Status.Done, doneAt = clock.time)))
        // The store held mid-write, so the save waits.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread { runBlocking { runtime.update { entered.countDown(); release.await(); it } } }
        entered.await()
        val saving = focus.spendSoon(4 * 60_000L, CoroutineScope(Dispatchers.Default))
        // Not saved yet, and already counted.
        assertEquals(10 * 60_000L, runtime.value.credit.on(focus.today()).leftMs)
        assertEquals(6 * 60_000L, focus.creditLeftMs())
        release.countDown()
        saving?.join()
        holder.join()
        // Saved, and counted once.
        assertEquals(6 * 60_000L, runtime.value.credit.on(focus.today()).leftMs)
        assertEquals(6 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `a completion is rewarded once, however often it's offered`() = runTest {
        val homework = task("hw", effort = 45).copy(status = Status.Done, doneAt = clock.time)
        focus.onCompleted(listOf(homework))
        focus.onCompleted(listOf(homework))
        assertEquals(15 * 60_000L, focus.creditLeftMs())
        assertEquals(1, log.value.completions.size)
    }

    @Test
    fun `a completion saved with the sync is rewarded later, if the app stopped first`() = runTest {
        val homework = task("hw", effort = 45).copy(status = Status.Done, doneAt = clock.time)
        // The sync saved it; the app stopped before the reward.
        tasks.update { it.copy(tasks = listOf(homework), unrewarded = listOf(homework)) }
        focus.rewardCompletions()
        assertEquals(15 * 60_000L, focus.creditLeftMs())
        assertEquals(listOf("teams:hw"), log.value.completions.map { it.taskId })
        assertEquals(emptyList(), tasks.value.unrewarded)
        // Stopped after the free time but before the record and the clearing: neither is doubled.
        tasks.update { it.copy(unrewarded = listOf(homework)) }
        log.update { it.copy(completions = emptyList()) }
        focus.rewardCompletions()
        assertEquals(15 * 60_000L, focus.creditLeftMs())
        assertEquals(1, log.value.completions.size)
    }

    @Test
    fun `a completion rewarded after its day is over earns nothing then`() = runTest {
        val homework = task("hw", effort = 45).copy(status = Status.Done, doneAt = clock.time)
        tasks.update { it.copy(tasks = listOf(homework), unrewarded = listOf(homework)) }
        // The app stopped before the reward, and started again the next day.
        clock.time += 24 * 3_600_000L
        focus.rewardCompletions()
        assertEquals(0L, focus.creditLeftMs())
        assertEquals(listOf("teams:hw"), log.value.completions.map { it.taskId })
        assertEquals(emptyList(), tasks.value.unrewarded)
    }

    @Test
    fun `a session's ending saved before its due was given is given it at start-up, once`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("hw", effort = 90))) }
        val session = FocusSession("teams:hw", "hw", null, 30, startedAt = clock.time - 30 * 60_000L)
        // Stopped part-way: the session ended and saved, nothing yet given.
        runtime.update { it.copy(finishing = listOf(EndedSession(session, 30, completed = true, endedAt = clock.time))) }
        focus.finishSessions()
        assertEquals(30, tasks.value.tasks.single().workedMin)
        assertEquals(1, log.value.sessions.size)
        assertEquals(10 * 60_000L, focus.creditLeftMs())
        assertEquals(emptyList(), runtime.value.finishing)
        // Run again: nothing more.
        focus.finishSessions()
        assertEquals(30, tasks.value.tasks.single().workedMin)
        assertEquals(1, log.value.sessions.size)
        assertEquals(10 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `stopped after a session's minutes and record were saved, only its free time is left to give`() = runTest {
        val session = FocusSession("teams:hw", "hw", null, 30, startedAt = clock.time - 30 * 60_000L)
        tasks.update { it.copy(tasks = listOf(task("hw", effort = 90).copy(workedMin = 30, sessionsCounted = listOf(session.startedAt)))) }
        log.update {
            it.copy(
                sessions = listOf(
                    SessionRecord(
                        taskId = "teams:hw",
                        kind = Kind.Homework,
                        label = "hw",
                        plannedMin = 30,
                        workedMin = 30,
                        startedAt = session.startedAt,
                        endedAt = clock.time,
                        completed = true,
                    ),
                ),
            )
        }
        runtime.update { it.copy(finishing = listOf(EndedSession(session, 30, completed = true, endedAt = clock.time))) }
        focus.finishSessions()
        assertEquals(30, tasks.value.tasks.single().workedMin)
        assertEquals(1, log.value.sessions.size)
        assertEquals(10 * 60_000L, focus.creditLeftMs())
        assertEquals(emptyList(), runtime.value.finishing)
    }

    @Test
    fun `a completion that ends a running session says so`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("hw", effort = 45))) }
        focus.startSession("teams:hw", "hw", null, 30)
        val done = tasks.value.tasks.single().copy(status = Status.Done, doneAt = clock.time)
        assertTrue(focus.onCompleted(listOf(done)))
        assertNull(focus.session)
        assertEquals(false, focus.onCompleted(listOf(task("other", effort = 10).copy(status = Status.Done, doneAt = clock.time))))
    }

    @Test
    fun `vocabulary a deck left unfinished is rewarded with its assignment, less its sessions`() = runTest {
        val homework = task("hw", effort = 60, steps = listOf(SubStep("Learn vocabulary 2.2", 30, ankiSections = listOf("2.2")), SubStep("Exercise 4", 30)))
        // The deck still has cards: 9 minutes of sessions on it so far.
        val deck = TaskItem(
            id = "anki:deck:1", source = Source.Anki, sourceId = "deck:1", title = "Learn Anki deck 2.2", kind = Kind.Homework, derived = true,
            firstSeenAt = clock.time, lastSeenAt = clock.time, extra = mapOf("deckName" to "Textbook 1::2.2", "for" to "teams:hw"), workedMin = 9,
        )
        tasks.update { it.copy(tasks = listOf(homework, deck)) }
        focus.onCompleted(listOf(homework.copy(status = Status.Done, doneAt = clock.time)))
        // 60 less the deck's 9 minutes: 17 minutes of free time.
        assertEquals(17 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `vocabulary of a deck dropped as its assignment closed is rewarded less the deck's sessions`() = runTest {
        val homework = task("hw", effort = 60, steps = listOf(SubStep("Learn vocabulary 2.2", 30, ankiSections = listOf("2.2")), SubStep("Exercise 4", 30)))
        // The same sync that closed the assignment dropped its unfinished deck: 9 minutes of sessions on it.
        val deck = TaskItem(
            id = "anki:deck:1", source = Source.Anki, sourceId = "deck:1", title = "Learn Anki deck 2.2", kind = Kind.Homework, derived = true,
            firstSeenAt = clock.time, lastSeenAt = clock.time, extra = mapOf("deckName" to "Textbook 1::2.2", "for" to "teams:hw"), workedMin = 9,
            status = Status.Missed,
        )
        tasks.update { it.copy(tasks = listOf(homework, deck)) }
        focus.onCompleted(listOf(homework.copy(status = Status.Done, doneAt = clock.time)))
        // 60 less the deck's 9 minutes: 17 minutes of free time, not 20.
        assertEquals(17 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `vocabulary a deck held isn't rewarded again with its assignment`() = runTest {
        val homework = task("hw", effort = 60, steps = listOf(SubStep("Learn vocabulary 2.2", 30, ankiSections = listOf("2.2")), SubStep("Exercise 4", 30)))
        val deck = TaskItem(
            id = "anki:deck:1", source = Source.Anki, sourceId = "deck:1", title = "Learn Anki deck 2.2", kind = Kind.Homework, derived = true,
            firstSeenAt = clock.time, lastSeenAt = clock.time, extra = mapOf("deckName" to "Textbook 1::2.2", "for" to "teams:hw"), status = Status.Done,
        )
        tasks.update { it.copy(tasks = listOf(homework, deck)) }
        focus.onCompleted(listOf(homework.copy(status = Status.Done, doneAt = clock.time)))
        // 30 minutes of the 60 were the finished deck's: 10 minutes of free time, not 20.
        assertEquals(10 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `a finished session processed late is dated at its end, and its time isn't today's`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("hw", effort = 90))) }
        val session = focus.startSession("teams:hw", "hw", null, 30)
        // The phone was off at its alarm, and it's processed the next morning.
        clock.time += 14 * 3_600_000L
        focus.stopSession()
        assertEquals(session.endsAt, log.value.sessions.single().endedAt)
        assertEquals(0L, focus.creditLeftMs())
    }

    @Test
    fun `a session finished on a day that's over earns nothing, and says so`() = runTest {
        val yesterday = clock.time - 24 * 3_600_000L
        assertTrue(focus.earnsNow(true, clock.time))
        assertFalse(focus.earnsNow(true, yesterday))
        assertFalse(focus.earnsNow(false, clock.time))
    }

    @Test
    fun `a step longer than a session isn't ticked by one, its minutes count`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("t", effort = 240, steps = listOf(SubStep("Essay", 240))))) }
        focus.startSession("teams:t", "t: Essay", "Essay", 240)
        assertEquals(Focus.MAX_SESSION_MIN, focus.session!!.minutes)
        clock.time += Focus.MAX_SESSION_MIN * 60_000L
        focus.stopSession()
        val t = tasks.value.tasks.single()
        assertEquals(listOf(false), t.subSteps.map { it.done })
        assertEquals(Focus.MAX_SESSION_MIN, t.workedMin)
    }

    @Test
    fun `a completion rewarded after its task reopened uses the work it was done with`() = runTest {
        // Done after 30 minutes of sessions; the app stopped before the reward, and the next read reopened it.
        val done = task("hw", effort = 45).copy(status = Status.Done, doneAt = clock.time, workedMin = 30)
        tasks.update { it.copy(tasks = listOf(done.copy(status = Status.Open, doneAt = null, workedMin = 0)), unrewarded = listOf(done)) }
        focus.rewardCompletions()
        // 45 less the 30 worked: 5 minutes of free time, not 15.
        assertEquals(5 * 60_000L, focus.creditLeftMs())
        assertEquals(30, log.value.completions.single().workedMin)
    }

    @Test
    fun `a session ended with its task's completion is counted in the saved completion too`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("hw", effort = 45))) }
        focus.startSession("teams:hw", "hw", null, 30)
        clock.time += 30 * 60_000L
        val done = tasks.value.tasks.single().copy(status = Status.Done, doneAt = clock.time)
        tasks.update { it.copy(tasks = listOf(done), unrewarded = listOf(done)) }
        // The session ended (the reward's first step), then the app stopped, and the next read reopened the task.
        focus.stopSession()
        tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Open, doneAt = null, workedMin = 0) }) }
        val sessionCredit = focus.creditLeftMs()
        focus.rewardCompletions()
        // 45 less the session's 30: 5 minutes more, not 15.
        assertEquals(sessionCredit + 5 * 60_000L, focus.creditLeftMs())
        assertEquals(30, log.value.completions.single().workedMin)
    }

    @Test
    fun `a stop meant for one session doesn't end the one after it`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("a"), task("b"))) }
        val first = focus.startSession("teams:a", "a", null, 30)
        focus.startSession("teams:b", "b", null, 30)
        assertNull(focus.stopSession(first))
        assertEquals("teams:b", focus.session?.taskId)
    }

    @Test
    fun `what counts as blocked follows the settings`() {
        assertEquals(Focus.Target.App("com.google.android.youtube"), focus.target("com.google.android.youtube"))
        assertEquals(Focus.Target.Browser("org.mozilla.firefox"), focus.target("org.mozilla.firefox"))
        assertNull(focus.target("com.whatsapp"))
        assertTrue(focus.isCheckedBrowser("com.android.chrome"))
        assertEquals(Focus.Target.Site("youtube.com", "com.android.chrome"), focus.siteTarget("com.android.chrome", "m.youtube.com/shorts/x"))
        assertNull(focus.siteTarget("com.android.chrome", "bbc.co.uk/bitesize"))
    }
}
