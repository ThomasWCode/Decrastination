package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Calibration
import java.time.LocalDate
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.core.Slotter
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MissedSlot
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Keeping
import com.thomaswcode.decrastination.data.PhotoDone
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.RuntimeState
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
    fun `confirmed work earns time, reading emails doesn't`() = runTest {
        val homework = task("hw", effort = 45).copy(status = Status.Done, doneAt = clock.time)
        val email = task("email", kind = Kind.Info, effort = 2).copy(status = Status.Done, doneAt = clock.time)
        focus.onCompleted(listOf(homework, email))
        assertEquals(15 * 60_000L, focus.creditLeftMs())
        assertEquals(listOf("teams:hw", "teams:email"), log.value.completions.map { it.taskId })
    }

    @Test
    fun `one of your own you say is done is logged but earns nothing`() = runTest {
        val own = task("mine", effort = 45).copy(id = "own:mine", source = Source.Own, sourceId = "mine", status = Status.Done, doneAt = clock.time)
        focus.onCompleted(listOf(own))
        assertEquals(0L, focus.creditLeftMs())
        val record = log.value.completions.single()
        assertEquals("own:mine", record.taskId)
        // Your minutes, not the app's estimate: nothing for the calibration to learn from.
        assertTrue(record.yourEstimate)
    }

    @Test
    fun `minutes you raise plan more time, but finishing earns no more than the app's estimate`() = runTest {
        val raised = task("hw", effort = 30).copy(userEffortMin = 300, status = Status.Done, doneAt = clock.time)
        focus.onCompleted(listOf(raised))
        // 30 minutes of the app's estimate, a third of it: not 30 minutes of credit for 300.
        assertEquals(10 * 60_000L, focus.creditLeftMs())
        // Lowered, it earns by yours.
        val lowered = task("other", effort = 30).copy(userEffortMin = 15, status = Status.Done, doneAt = clock.time)
        focus.onCompleted(listOf(lowered))
        assertEquals(15 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `with your minutes raised and a step ticked, each estimate takes off its own share`() = runTest {
        // Two 30-minute steps, raised to two hours, one ticked off by hand.
        val steps = listOf(SubStep("Q1-8", 30, done = true, byHand = true), SubStep("Q9-16", 30))
        val raised = task("hw", effort = 60, steps = steps).copy(userEffortMin = 120, status = Status.Done, doneAt = clock.time)
        focus.onCompleted(listOf(raised))
        // By the app's estimate, 30 of its 60 minutes were left: a third of them.
        assertEquals(10 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `a step of your own task is ticked off by hand, as an email's is`() = runTest {
        val own = task("mine", steps = listOf(SubStep("Draft", 30), SubStep("Redraft", 30))).copy(id = "own:mine", source = Source.Own, sourceId = "mine")
        tasks.update { it.copy(tasks = listOf(own, task("hw", steps = listOf(SubStep("Q1", 20))))) }
        assertTrue(focus.tickBlock("own:mine", 0, "Draft"))
        assertTrue(tasks.value.tasks.first { it.id == "own:mine" }.subSteps[0].byHand)
        // A Teams assignment's steps aren't yours to tick: its source says when it's done.
        assertFalse(focus.tickBlock("teams:hw", 0, "Q1"))
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
    fun `a completion's estimate is what was left when it was first seen`() = runTest {
        val halfDone = task("pp", effort = 100).copy(status = Status.Done, doneAt = clock.time, sourceProgress = 0.5)
        focus.onCompleted(listOf(halfDone))
        assertEquals(50, log.value.completions.single().estimateMin)
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
    fun `a completion's estimate for learning leaves out the vocabulary its decks hold`() = runTest {
        val homework = task("hw", effort = 60, steps = listOf(SubStep("Learn vocabulary 2.2", 30, ankiSections = listOf("2.2")), SubStep("Exercise 4", 30)))
        val deck = TaskItem(
            id = "anki:deck:1", source = Source.Anki, sourceId = "deck:1", title = "Learn Anki deck 2.2", kind = Kind.Homework, derived = true,
            firstSeenAt = clock.time, lastSeenAt = clock.time, extra = mapOf("deckName" to "Textbook 1::2.2", "for" to "teams:hw"), status = Status.Done,
        )
        tasks.update { it.copy(tasks = listOf(homework, deck)) }
        focus.onCompleted(listOf(homework.copy(status = Status.Done, doneAt = clock.time)))
        assertEquals(30, log.value.completions.single().estimateMin)
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
    fun `a completion earns from the calibrated estimate, as the plan and its kept slots do`() = runTest {
        // Learned to take half its estimate: a 100-minute task is 50 minutes of pieces, both kept.
        runtime.update { it.copy(calibration = Calibration(multipliers = mapOf("Homework|" to 0.5))) }
        tasks.update { it.copy(tasks = listOf(task("hw", effort = 100))) }
        repeat(2) { assertTrue(focus.keepSlot("teams:hw#-1#1")) }
        assertEquals(50, tasks.value.tasks.single().workedMin)
        val afterSlots = focus.creditLeftMs()
        focus.onCompleted(listOf(tasks.value.tasks.single().copy(status = Status.Done, doneAt = clock.time)))
        // The slots were all of it: the completion earns nothing more.
        assertEquals(afterSlots, focus.creditLeftMs())
        // With none kept, the completion earns for the 50 calibrated minutes: 50 / 3.
        tasks.update { it.copy(tasks = listOf(task("other", effort = 100))) }
        focus.onCompleted(listOf(task("other", effort = 100).copy(status = Status.Done, doneAt = clock.time)))
        assertEquals(afterSlots + 50 * 60_000L / 3, focus.creditLeftMs())
    }

    @Test
    fun `completions are handed on before they leave the queue, so a stop loses none`() = runTest {
        val homework = task("hw", effort = 45).copy(status = Status.Done, doneAt = clock.time)
        tasks.update { it.copy(tasks = listOf(homework), unrewarded = listOf(homework)) }
        // Stopped while asking how it went: still queued, so it's all done again next time.
        runCatching { focus.rewardCompletions { error("stopped") } }
        assertEquals(listOf("teams:hw"), tasks.value.unrewarded.map { it.id })
        val asked = mutableListOf<String>()
        focus.rewardCompletions { completed -> asked += completed.map { it.id } }
        assertEquals(listOf("teams:hw"), asked)
        assertEquals(emptyList(), tasks.value.unrewarded)
        // Its free time given once, for all that.
        assertEquals(15 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `a photo check stopped part-way is finished at start-up, once`() = runTest {
        // Saved, then the app stopped before its piece was kept.
        val done = PhotoDone("p1", "teams:hw", null, 30, clock.time, key = "teams:hw#-1#1")
        tasks.update { it.copy(tasks = listOf(task("hw", effort = 30))) }
        runtime.update { it.copy(photosDone = listOf(done)) }
        focus.finishPhotos()
        assertEquals(30, tasks.value.tasks.single().workedMin)
        assertTrue(log.value.slots.single().let { it.photo && it.earned })
        assertEquals(10 * 60_000L, focus.creditLeftMs())
        assertEquals(emptyList(), runtime.value.photosDone)
        // Stopped again after keeping it, before letting the photo go: finished again, nothing more.
        runtime.update { it.copy(photosDone = listOf(done)) }
        focus.finishPhotos()
        assertEquals(30, tasks.value.tasks.single().workedMin)
        assertEquals(1, log.value.slots.size)
        assertEquals(10 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `a plan dropped is worked out again, not taken from the minute's keeping`() {
        var made = 0
        val counting = Focus(tasks, settings, runtime, log, clock) { state, s, now -> made++; Planner.plan(Planner.Input(state.tasks, now, LONDON, s)) }
        counting.plan()
        counting.plan()
        assertEquals(1, made)
        counting.forgetPlan()
        counting.plan()
        assertEquals(2, made)
    }

    @Test
    fun `a completion rewarded after its task reopened uses the work it was done with`() = runTest {
        // Done after 30 minutes of sessions; the app stopped before the reward, and the next read reopened it.
        val done = task("hw", effort = 45).copy(status = Status.Done, doneAt = clock.time, workedMin = 20, photoMin = 10)
        tasks.update { it.copy(tasks = listOf(done.copy(status = Status.Open, doneAt = null, workedMin = 0, photoMin = 0)), unrewarded = listOf(done)) }
        focus.rewardCompletions()
        // 45 less the 20 timed and 10 photographed: 5 minutes of free time, not 15.
        assertEquals(5 * 60_000L, focus.creditLeftMs())
        assertEquals(20, log.value.completions.single().workedMin)
    }

    @Test
    fun `a block ticked by hand after work on it keeps that work's minutes`() = runTest {
        val email = task("e", kind = Kind.Admin, effort = 60).copy(id = "gmail:e", source = Source.Gmail, subSteps = listOf(SubStep("A", 30), SubStep("B", 30)), workedMin = 10)
        tasks.update { it.copy(tasks = listOf(email)) }
        assertTrue(focus.tickBlock("gmail:e", 0, "A"))
        assertEquals(10, tasks.value.tasks.single().subSteps.first().timedMin)
        // Completed: B's 30 minutes earn, not A's 20 untimed ones (nor its 10 timed ones twice).
        focus.onCompleted(listOf(tasks.value.tasks.single().copy(status = Status.Done, doneAt = clock.time)))
        assertEquals(10 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `of two blocks of one name, the one ticked is the one ticked`() = runTest {
        val email = task("e").copy(id = "gmail:e", source = Source.Gmail, subSteps = listOf(SubStep("Apply", 30), SubStep("Apply", 60)))
        tasks.update { it.copy(tasks = listOf(email)) }
        assertTrue(focus.tickBlock("gmail:e", 1, "Apply"))
        assertEquals(listOf(false, true), tasks.value.tasks.single().subSteps.map { it.done })
        assertEquals(60, tasks.value.tasks.single().handMin)
    }

    @Test
    fun `a block of an email can be ticked off by hand, an assignment's step can't`() = runTest {
        val email = task("e").copy(id = "gmail:e", source = Source.Gmail, subSteps = listOf(SubStep("Apply", 30)))
        tasks.update { it.copy(tasks = listOf(email, task("hw", steps = listOf(SubStep("Q1", 30))))) }
        assertTrue(focus.tickBlock("gmail:e", 0, "Apply"))
        assertFalse(focus.tickBlock("teams:hw", 0, "Q1"))
        // Twice, or by a title no longer there: nothing more.
        assertFalse(focus.tickBlock("gmail:e", 0, "Apply"))
        assertEquals(listOf(true), tasks.value.tasks.first { it.id == "gmail:e" }.subSteps.map { it.done })
        // No free time for a tick.
        assertEquals(0L, focus.creditLeftMs())
    }

    @Test
    fun `an email whose blocks were ticked off by hand earns nothing when it completes`() = runTest {
        val email = task("e", kind = Kind.Admin, effort = 30).copy(id = "gmail:e", source = Source.Gmail, subSteps = listOf(SubStep("Apply", 30)))
        tasks.update { it.copy(tasks = listOf(email)) }
        assertTrue(focus.tickBlock("gmail:e", 0, "Apply"))
        assertEquals(30, tasks.value.tasks.single().handMin)
        focus.onCompleted(listOf(tasks.value.tasks.single().copy(status = Status.Done, doneAt = clock.time)))
        assertEquals(0L, focus.creditLeftMs())
        assertTrue(log.value.completions.single().byHand)
    }

    @Test
    fun `a split task with no deadline of its own completes against its last block's`() = runTest {
        val due = clock.time + 3 * 86_400_000L
        val email = task("e", kind = Kind.Admin, due = null).copy(id = "gmail:e", source = Source.Gmail, subSteps = listOf(SubStep("Apply", 30, dueAt = due)))
        focus.onCompleted(listOf(email.copy(status = Status.Done, doneAt = clock.time)))
        assertEquals(due, log.value.completions.single().dueAt)
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

    @Test
    fun `a browser whose address can't be read is said till something covers it`() = runTest {
        val chrome = setOf("com.android.chrome")
        assertEquals(listOf("com.android.chrome"), focus.uncovered(chrome))
        // Blocked outright (Settings' fix): covered at once, though still on the checked list (armed,
        // its taking off waits as a loosening does).
        settings.update { it.copy(blockedBrowsers = it.blockedBrowsers + "com.android.chrome") }
        assertTrue(focus.isCheckedBrowser("com.android.chrome"))
        assertTrue(focus.uncovered(chrome).isEmpty())
    }

    @Test
    fun `a completion with work a photo check found done is marked so`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("hw", effort = 40))) }
        assertTrue(focus.photoChecked("teams:hw", null, 20, key = "teams:hw#-1#1"))
        focus.onCompleted(listOf(tasks.value.tasks.single().copy(status = Status.Done, doneAt = clock.time)))
        assertTrue(log.value.completions.single().photo)
    }

    // --- Slots kept, missed and moved (LR-09); what a kept slot earns (BL-08) ---

    private val monday = LocalDate.parse("2026-10-12")
    private val evening = mapOf(monday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))
    private val ownDone = mutableListOf<String>()
    private fun input(state: TaskState, s: Settings, now: Long) =
        Planner.Input(state.tasks, now, LONDON, s, windows = evening, previousSlots = runtime.value.slots.on(monday).previous)
    private val slotted = Focus(tasks, settings, runtime, log, clock, onOwnTaskDone = { ownDone += it }) { state, s, now -> Planner.plan(input(state, s, now)) }

    /** Monday 16:00 with [list] planned into the evening, its starts kept as the watcher keeps them: its work slots. */
    private suspend fun laid(vararg list: TaskItem): List<Slot> {
        clock.time = Fixtures.at("2026-10-12T16:00")
        tasks.update { it.copy(tasks = list.toList()) }
        val work = Planner.plan(input(tasks.value, settings.value, clock.time)).todaySlots.filter { it.kind == SlotKind.Work }
        runtime.update { it.copy(slots = SlotState(monday.toString(), previous = work.associate { s -> s.chunk!!.key to s.start })) }
        return work
    }

    private fun steps() = listOf(SubStep("Q1-8", 30), SubStep("Q9-16", 30))

    @Test
    fun `keeping a slot ticks its step, records it and adds its minutes`() = runTest {
        val first = laid(task("t", due = "2026-10-12T23:59", steps = steps(), effort = 60)).first()
        val key = first.chunk!!.key
        clock.time = first.end
        assertTrue(slotted.keepSlot(key, extraMin = 5))
        val t = tasks.value.tasks.single()
        assertEquals(listOf(true, false), t.subSteps.map { it.done })
        assertEquals(35, t.subSteps[0].timedMin)
        assertEquals(35, t.workedMin)
        val record = log.value.slots.single()
        assertEquals(SlotOutcome.Kept, record.outcome)
        assertEquals(first.start to first.end, record.start to record.end)
        assertEquals(30 to 35, record.plannedMin to record.workedMin)
        // Off the starts the plan keeps; kept again, nothing, as its step is done.
        assertFalse(key in runtime.value.slots.previous)
        assertFalse(slotted.keepSlot(key))
        assertEquals(1, log.value.slots.size)
    }

    @Test
    fun `keeping a piece leaves its task's next piece in its slot`() = runTest {
        val (first, second) = laid(task("t", due = "2026-10-12T23:59", steps = steps(), effort = 60))
        clock.time = first.end
        assertTrue(slotted.keepSlot(first.chunk!!.key))
        // Its key is its step's, which a piece kept before it doesn't renumber, so its start is kept.
        val next = Planner.plan(input(tasks.value, settings.value, clock.time)).todaySlots.single { it.kind == SlotKind.Work }
        assertEquals(second.chunk!!.key to second.start, next.chunk!!.key to next.start)
    }

    @Test
    fun `a missed slot is recorded once, and kept after it is kept late where it was laid`() = runTest {
        val first = laid(task("t", due = "2026-10-12T23:59", steps = steps(), effort = 60)).first()
        val key = first.chunk!!.key
        // Its end and grace go by: missed, as its alarm says, and said again changes nothing.
        clock.time = first.end + Slotter.GRACE_MS + 60_000L
        repeat(2) { slotted.missSlot(key, Focus.Laid(first.start, first.end, first.window)) }
        runtime.update { it.copy(slots = it.slots.on(monday).missed(key, first.chunk!!.taskId, clock.time)) }
        assertEquals(SlotOutcome.Missed, log.value.slots.single().outcome)
        // Ticked at 19:00 after all: the one slot it was, kept late, at its own time.
        clock.time = Fixtures.at("2026-10-12T19:00")
        assertTrue(slotted.keepSlot(key))
        val record = log.value.slots.single()
        assertEquals(SlotOutcome.KeptLate, record.outcome)
        assertEquals(first.start to 30, record.start to record.workedMin)
    }

    @Test
    fun `a slot can't be kept twice, by a second tap or two at once`() = runTest {
        val first = laid(task("box", due = "2026-10-12T23:59", effort = 60)).first()
        val key = first.chunk!!.key
        val seen = clock.time
        clock.time = first.end
        val both = listOf(async { slotted.keepSlot(key, seenAt = seen) }, async { slotted.keepSlot(key, seenAt = seen) }).awaitAll()
        assertEquals(1, both.count { it })
        assertEquals(30, tasks.value.tasks.single().workedMin)
        assertEquals(1, log.value.slots.size)
        // The next box, its key the same now, is kept by a tick seen after the first.
        assertTrue(slotted.keepSlot(key, seenAt = clock.time + 1))
        assertEquals(60, tasks.value.tasks.single().workedMin)
    }

    @Test
    fun `one of your own whose work a kept slot finishes is finished`() = runTest {
        val own = task("mine", due = "2026-10-12T23:59", effort = 30).copy(id = "own:mine", source = Source.Own)
        val first = laid(own).single()
        clock.time = first.end
        assertTrue(slotted.keepSlot(first.chunk!!.key))
        assertEquals(listOf("own:mine"), ownDone)
    }

    @Test
    fun `a short piece earns on its tick, a longer one when its photo passes, once`() = runTest {
        val slots = laid(task("short", due = "2026-10-12T23:59", effort = 15), task("long", due = "2026-10-12T23:59", effort = 30))
        val short = slots.single { it.chunk!!.taskId == "teams:short" }
        val long = slots.single { it.chunk!!.taskId == "teams:long" }
        clock.time = long.end
        // Under `photoFromMin` (20): a third of its 15 minutes on the tick.
        assertTrue(slotted.keepSlot(short.chunk!!.key))
        assertEquals(5 * 60_000L, slotted.creditLeftMs())
        // 30 minutes: nothing on the tick; ten when its photo passes, and only once.
        assertTrue(slotted.keepSlot(long.chunk!!.key))
        assertEquals(5 * 60_000L, slotted.creditLeftMs())
        assertFalse(log.value.slots.single { it.taskId == "teams:long" }.earned)
        assertTrue(slotted.photoChecked("teams:long", null, 30, key = long.chunk!!.key))
        assertEquals(15 * 60_000L, slotted.creditLeftMs())
        assertTrue(log.value.slots.single { it.taskId == "teams:long" }.let { it.earned && it.photo })
        assertFalse(slotted.photoChecked("teams:long", null, 30, key = long.chunk!!.key))
        assertEquals(15 * 60_000L, slotted.creditLeftMs())
    }

    @Test
    fun `a photo before the tick keeps the slot and earns`() = runTest {
        val long = laid(task("long", due = "2026-10-12T23:59", effort = 30)).single()
        clock.time = long.start + 10 * 60_000L
        assertTrue(slotted.photoChecked("teams:long", null, 30, key = long.chunk!!.key))
        val record = log.value.slots.single()
        assertTrue(record.photo && record.earned)
        assertEquals(10 * 60_000L, slotted.creditLeftMs())
        // A tick after it finds it kept: nothing more.
        assertFalse(slotted.keepSlot(long.chunk!!.key, seenAt = clock.time))
        assertEquals(10 * 60_000L, slotted.creditLeftMs())
    }

    @Test
    fun `slots earn no more than today's plan, beside what else earned`() = runTest {
        // Tuesday's piece ticked on Monday too: Monday's plan was the 15 minutes, so 5 earned from slots.
        val tuesday = monday.plusDays(1)
        val both = evening + (tuesday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))
        val ahead = Focus(tasks, settings, runtime, log, clock) { state, s, now ->
            Planner.plan(Planner.Input(state.tasks, now, LONDON, s, windows = both, previousSlots = runtime.value.slots.on(monday).previous))
        }
        laid(task("a", due = "2026-10-12T23:59", effort = 15), task("b", due = "2026-10-13T23:59", effort = 15).copy(notBefore = Fixtures.at("2026-10-13T00:00")))
        // Ten minutes earned already today by a completion, which stands beside the plan's share.
        runtime.update { it.copy(credit = it.credit.earn(monday, 10.0)) }
        clock.time = Fixtures.at("2026-10-12T18:00")
        assertTrue(ahead.keepSlot("teams:a#-1#1"))
        assertEquals(15 * 60_000L, ahead.creditLeftMs())
        // Handed in (Teams says so), so nothing of today's plan is left: tomorrow's piece earns nothing today.
        tasks.update { state -> state.copy(tasks = state.tasks.map { if (it.id == "teams:a") it.copy(status = Status.Done, doneAt = clock.time) else it }) }
        assertTrue(ahead.keepSlot("teams:b#-1#1"))
        assertEquals(15 * 60_000L, ahead.creditLeftMs())
    }

    // --- What blocks (BL-01, BL-11) ---

    @Test
    fun `tomorrow's work alone doesn't block, today's does`() = runTest {
        // Monday 20:00 with ten minutes of free time: work that can't start till tomorrow leaves today free.
        clock.time = Fixtures.at("2026-10-12T20:00")
        runtime.update { it.copy(credit = it.credit.earn(monday, 10.0)) }
        tasks.update { it.copy(tasks = listOf(task("later", due = "2026-10-14T23:59").copy(notBefore = Fixtures.at("2026-10-13T00:00")))) }
        assertEquals(BlockPolicy.Verdict.Spend, focus.verdict())
        tasks.update { it.copy(tasks = it.tasks + task("tonight", due = "2026-10-12T23:59")) }
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.TodayUndone), focus.verdict())
    }

    @Test
    fun `a slot missed today that went to tomorrow blocks till the hours end, and a move of yours lifts it`() = runTest {
        clock.time = Fixtures.at("2026-10-12T20:00")
        val missed = MissedSlot("teams:t#-1#1", "teams:t", clock.time - 3_600_000L, spilled = true)
        runtime.update { it.copy(credit = it.credit.earn(monday, 10.0), slots = SlotState(monday.toString(), missed = listOf(missed))) }
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.TodayUndone), focus.verdict())
        // The day's hours end at 22:00: free time again till the quiet hours.
        clock.time = Fixtures.at("2026-10-12T22:05")
        assertEquals(BlockPolicy.Verdict.Spend, focus.verdict())
        // Missed but still today's (not spilled): nothing more than today's plan says.
        clock.time = Fixtures.at("2026-10-12T20:00")
        runtime.update { it.copy(slots = SlotState(monday.toString(), missed = listOf(missed.copy(spilled = false)))) }
        assertEquals(BlockPolicy.Verdict.Spend, focus.verdict())
        // Moved on purpose (a time you gave its task): forgotten.
        runtime.update { it.copy(slots = SlotState(monday.toString(), missed = listOf(missed)).moved("teams:t")) }
        assertEquals(BlockPolicy.Verdict.Spend, focus.verdict())
    }

    // --- The reward where the block meets you (BL-04), and a day with nothing to do (BL-07) ---

    @Test
    fun `the reward line says what finishing today earns, short pieces on the tick and long ones with a photo`() = runTest {
        clock.time = Fixtures.at("2026-10-12T17:00")
        // Two short pieces today: a third of their minutes on their ticks.
        tasks.update { it.copy(tasks = listOf(task("a", due = "2026-10-12T23:59", effort = 15), task("b", due = "2026-10-12T23:59", effort = 15))) }
        assertEquals("Finish today's 2 slots (30 min) → 10 min free", focus.rewardLine(focus.plan()))
        // And a long one: its share with a photo.
        tasks.update { it.copy(tasks = it.tasks + task("statics", due = "2026-10-12T23:59", effort = 30)) }
        assertEquals("Finish today's 3 slots (1 h) → 10 min free, 10 min more with a photo of statics", focus.rewardLine(focus.plan()))
        tasks.update { it.copy(tasks = listOf(task("statics", due = "2026-10-12T23:59", effort = 30))) }
        assertEquals("Finish today's slot (30 min) → 10 min free with a photo of statics", focus.rewardLine(focus.plan()))
        // Nothing left today: what there is to spend; with none, nothing to say.
        tasks.update { it.copy(tasks = emptyList()) }
        assertNull(focus.rewardLine(focus.plan()))
        runtime.update { it.copy(credit = it.credit.earn(monday, 25.0)) }
        assertEquals("Today's plan is done. 25 min free.", focus.rewardLine(focus.plan()))
    }

    @Test
    fun `a day with nothing to do starts with its free time, once, and a day with work or done with it doesn't`() = runTest {
        clock.time = Fixtures.at("2026-10-12T09:00")
        repeat(2) { focus.grantAllowanceIfDue() }
        assertEquals(60 * 60_000L, focus.creditLeftMs())
        assertEquals("Nothing planned today: 1 h free.", focus.rewardLine(focus.plan()))
        // The next day, work planned: nothing given.
        clock.time = Fixtures.at("2026-10-13T09:00")
        tasks.update { it.copy(tasks = listOf(task("a", due = "2026-10-13T23:59"))) }
        focus.grantAllowanceIfDue()
        assertEquals(0L, focus.creditLeftMs())
        // A piece of it kept, then the whole handed in: the day emptied by its work earns no allowance.
        assertTrue(focus.keepSlot("teams:a#-1#1"))
        tasks.update { state -> state.copy(tasks = state.tasks.map { it.copy(status = Status.Done, doneAt = clock.time) }) }
        focus.grantAllowanceIfDue()
        assertEquals(0L, focus.creditLeftMs())
    }

    // --- Free time between today's slots (BL-10) ---

    @Test
    fun `after a slot kept, the gap till the next is free time, and it closes when the next begins or goes by undone`() = runTest {
        val slots = laid(task("t", due = "2026-10-12T23:59", steps = steps(), effort = 60))
        val (first, second) = slots
        // Ticked two minutes early, at 17:28: blocked till then, a gap after it till 17:35.
        clock.time = first.end - 2 * 60_000L
        assertEquals(null, slotted.gapUntil())
        assertTrue(slotted.keepSlot(first.chunk!!.key))
        runtime.update { it.copy(credit = it.credit.earn(monday, 10.0)) }
        assertEquals(second.start, slotted.gapUntil())
        assertEquals(BlockPolicy.Verdict.Spend, slotted.verdict())
        // Free time runs only till the next slot's start, however much there is.
        assertEquals(second.start - clock.time, slotted.spendableMs())
        // The next slot begun: blocked.
        clock.time = second.start
        assertEquals(null, slotted.gapUntil())
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.TodayUndone), slotted.verdict())
    }

    @Test
    fun `a short piece of a later day's ticked ahead earns nothing today`() = runTest {
        val tuesday = monday.plusDays(1)
        val both = evening + (tuesday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))
        val ahead = Focus(tasks, settings, runtime, log, clock) { state, s, now ->
            Planner.plan(Planner.Input(state.tasks, now, LONDON, s, windows = both, previousSlots = runtime.value.slots.on(monday).previous))
        }
        laid(task("later", due = "2026-10-13T23:59", effort = 15).copy(notBefore = Fixtures.at("2026-10-13T00:00")))
        clock.time = Fixtures.at("2026-10-12T18:00")
        assertTrue(ahead.keepSlot("teams:later#-1#1"))
        assertEquals(0L, ahead.creditLeftMs())
        assertFalse(log.value.slots.single().earned)
    }

    // --- Codex's second review of phase 4 ---

    @Test
    fun `a slot kept part-way before a stop is carried through at start-up, once`() = runTest {
        val (first, second) = laid(task("t", due = "2026-10-12T23:59", steps = listOf(SubStep("Q1", 15), SubStep("Q2", 15)), effort = 30))
        clock.time = second.end
        fun record(slot: Slot) = SlotRecord(
            date = "2026-10-12", key = slot.chunk!!.key, taskId = "teams:t", kind = Kind.Homework, label = slot.chunk!!.label, plannedMin = 15,
            start = slot.start, end = slot.end, window = slot.window, outcome = SlotOutcome.Kept, workedMin = 15, earned = true, at = clock.time,
        )
        // Stopped after its step was ticked, before its record and free time: those given, once.
        tasks.update { state -> state.copy(tasks = state.tasks.map { t -> t.copy(subSteps = t.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true, timedMin = 15) else s }, workedMin = 15) }) }
        runtime.update { it.copy(keeping = listOf(Keeping(record(first), 0, "Q1", workedBefore = 0, earningMin = 5.0))) }
        repeat(2) { slotted.finishKeeps() }
        assertEquals(15, tasks.value.tasks.single().workedMin)
        assertEquals(listOf(record(first)), log.value.slots)
        assertEquals(5 * 60_000L, slotted.creditLeftMs())
        assertEquals(emptyList(), runtime.value.keeping)
        assertFalse(first.chunk!!.key in runtime.value.slots.previous)
        // Stopped before anything changed, a photo's keep: all of it, the photo let go with it, and nothing more from the photo.
        val photo = PhotoDone("p", "teams:t", "Q2", 15, clock.time, 1, second.chunk!!.key)
        runtime.update { it.copy(photosDone = listOf(photo), keeping = listOf(Keeping(record(second).copy(photo = true), 1, "Q2", workedBefore = 15, earningMin = 5.0, photo = photo))) }
        slotted.finishKeeps()
        slotted.finishPhotos()
        assertEquals(listOf(true, true), tasks.value.tasks.single().subSteps.map { it.done })
        assertEquals(30, tasks.value.tasks.single().workedMin)
        assertEquals(2, log.value.slots.size)
        assertEquals(10 * 60_000L, slotted.creditLeftMs())
        assertEquals(emptyList(), runtime.value.photosDone)
    }

    @Test
    fun `a day with nothing to do waits for today's reads before its free time`() = runTest {
        clock.time = Fixtures.at("2026-10-12T09:00")
        // Teams last read yesterday (the app just started): today's work may not be in yet.
        tasks.update { it.copy(sources = mapOf(Source.Teams to SourceStatus(lastSuccessAt = Fixtures.at("2026-10-11T21:00")))) }
        focus.grantAllowanceIfDue()
        assertEquals(0L, focus.creditLeftMs())
        // Read this morning, nothing in it: the day has nothing to do.
        tasks.update { it.copy(sources = mapOf(Source.Teams to SourceStatus(lastSuccessAt = Fixtures.at("2026-10-12T08:55")))) }
        focus.grantAllowanceIfDue()
        assertEquals(60 * 60_000L, focus.creditLeftMs())
    }

    @Test
    fun `a piece moved and laid again later has that slot's miss recorded too, each once`() = runTest {
        val first = laid(task("t", due = "2026-10-12T23:59", effort = 15)).single()
        val key = first.chunk!!.key
        slotted.moveSlot(key, Focus.Laid(first.start, first.end, first.window))
        val later = Focus.Laid(Fixtures.at("2026-10-12T20:00"), Fixtures.at("2026-10-12T20:15"), WindowKind.Evening)
        clock.time = Fixtures.at("2026-10-12T20:30")
        repeat(2) { slotted.missSlot(key, later) }
        assertEquals(listOf(SlotOutcome.Moved to first.start, SlotOutcome.Missed to later.start), log.value.slots.map { it.outcome to it.start })
    }

    @Test
    fun `a slot missed today that went to tomorrow, done tonight, earns as today's`() = runTest {
        // Evenings all week; Wednesday's work laid on Monday at 17:00.
        val week = (0L..4L).associate { monday.plusDays(it) to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)) }
        val twoDays = Focus(tasks, settings, runtime, log, clock) { state, s, now ->
            Planner.plan(Planner.Input(state.tasks, now, LONDON, s, windows = week, previousSlots = runtime.value.slots.on(monday).previous))
        }
        clock.time = Fixtures.at("2026-10-12T16:00")
        tasks.update { it.copy(tasks = listOf(task("t", due = "2026-10-14T23:59", effort = 15))) }
        val first = twoDays.plan().todaySlots.single { it.kind == SlotKind.Work }
        val key = first.chunk!!.key
        runtime.update { it.copy(slots = SlotState(monday.toString(), previous = mapOf(key to first.start))) }
        clock.time = first.end + Slotter.GRACE_MS + 60_000L
        twoDays.missSlot(key, Focus.Laid(first.start, first.end, first.window))
        runtime.update { it.copy(slots = it.slots.on(monday).missed(key, "teams:t", clock.time)) }
        // Monday's hours over, it's tomorrow's in the plan, in a slot; done now, it's the slot missed, kept late, and earns.
        clock.time = Fixtures.at("2026-10-12T22:45")
        assertTrue(twoDays.plan().tomorrowBucket?.slots.orEmpty().any { it.chunk?.key == key })
        assertTrue(twoDays.keepSlot(key))
        val record = log.value.slots.single()
        assertEquals(Triple(SlotOutcome.KeptLate, "2026-10-12", true), Triple(record.outcome, record.date, record.earned))
        assertEquals(5 * 60_000L, twoDays.creditLeftMs())
    }

    @Test
    fun `a slot missed after one kept closes the gap, and its new time opens no other`() = runTest {
        val (first, second) = laid(task("t", due = "2026-10-12T23:59", steps = steps(), effort = 60))
        clock.time = first.end
        assertTrue(slotted.keepSlot(first.chunk!!.key))
        runtime.update { it.copy(credit = it.credit.earn(monday, 10.0)) }
        // The next goes by undone and is laid again later: today's work, blocked, not a gap till then.
        clock.time = second.end + Slotter.GRACE_MS + 60_000L
        slotted.missSlot(second.chunk!!.key, Focus.Laid(second.start, second.end, second.window))
        runtime.update { it.copy(slots = it.slots.on(monday).missed(second.chunk!!.key, "teams:t", clock.time)) }
        assertTrue(slotted.plan().todaySlots.filter { it.kind == SlotKind.Work }.all { it.start > clock.time })
        assertNull(slotted.gapUntil())
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.TodayUndone), slotted.verdict())
    }

    @Test
    fun `a task done again after reopening isn't marked photographed by its first time's photo`() = runTest {
        tasks.update { it.copy(tasks = listOf(task("hw", effort = 40))) }
        assertTrue(focus.photoChecked("teams:hw", null, 20, key = "teams:hw#-1#1"))
        focus.onCompleted(listOf(tasks.value.tasks.single().copy(status = Status.Done, doneAt = clock.time)))
        // Reopened, a piece of it ticked (timed), and done again: no photo this time round.
        clock.time += 3_600_000L
        tasks.update { state -> state.copy(tasks = state.tasks.map { it.copy(workedMin = 0) }) }
        assertTrue(focus.keepSlot("teams:hw#-1#1"))
        clock.time += 60_000L
        focus.onCompleted(listOf(tasks.value.tasks.single().copy(status = Status.Done, doneAt = clock.time)))
        assertEquals(listOf(true, false), log.value.completions.map { it.photo })
    }

    @Test
    fun `a longer piece no photo can check earns once its source says it's done, and the line says so`() = runTest {
        clock.time = Fixtures.at("2026-10-12T17:00")
        tasks.update { it.copy(tasks = listOf(task("revise", kind = Kind.Revision, due = "2026-10-12T23:59", effort = 30))) }
        assertEquals("Finish today's slot (30 min) → 10 min free once Teams says revise is done", focus.rewardLine(focus.plan()))
        assertTrue(focus.keepSlot("teams:revise#-1#1"))
        assertEquals(0L, focus.creditLeftMs())
        // Teams says it's done: the slot kept earns by that, once however often it's said.
        val done = tasks.value.tasks.single().copy(status = Status.Done, doneAt = clock.time)
        repeat(2) { focus.onCompleted(listOf(done)) }
        assertEquals(10 * 60_000L, focus.creditLeftMs())
        assertTrue(log.value.slots.single().earned)
        // AnkiDroid's cards counted are a deck piece's evidence: kept by them, it earns at once.
        tasks.update { it.copy(tasks = listOf(task("vocab", kind = Kind.Revision, due = "2026-10-12T23:59", effort = 30))) }
        assertTrue(focus.keepSlot("teams:vocab#-1#1", verified = true))
        assertEquals(20 * 60_000L, focus.creditLeftMs())
        // One of your own that isn't written work: nothing can check it, so the line promises nothing.
        tasks.update { it.copy(tasks = listOf(task("plan", kind = Kind.Admin, due = "2026-10-12T23:59", effort = 30).copy(id = "own:plan", source = Source.Own))) }
        assertEquals("Finish today's slot (30 min)", focus.rewardLine(focus.plan()))
    }
}
