package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When a reminder comes, which are due, let go and kept (docs/plan_v4/07-notes-and-reminders.md §Tests). */
class RemindersTest {
    private val now = Fixtures.at("2026-10-20T17:00")
    private val min = 60_000L

    private fun task(id: String, due: String? = null, status: Status = Status.Open) = TaskItem(
        id = id, source = Source.Teams, sourceId = id.substringAfter(':'), title = id, dueAt = due?.let(Fixtures::at), kind = Kind.Homework,
        sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now, status = status,
    )

    private fun chunk(taskId: String) = Chunk(taskId, Source.Teams, Kind.Homework, taskId, null, 30, null, now, soft = false, overdue = false, dueToday = false, behind = false, part = 1, parts = 1, taskMinutes = 30, why = Why.Spread)

    private fun slot(taskId: String, start: String) = Slot(SlotKind.Work, chunk(taskId), Fixtures.at(start), Fixtures.at(start) + 30 * min, WindowKind.Evening, pinned = false)

    /** A plan whose days hold [slots], by their dates. */
    private fun plan(vararg slots: Slot) = Plan(
        now, LocalDate.parse("2026-10-20"),
        slots.groupBy { Instant.ofEpochMilli(it.start).atZone(LONDON).toLocalDate() }.map { (day, s) -> DayBucket(day, 120, s.mapNotNull { it.chunk }, s) },
        emptyList(),
    )

    private fun at(time: String) = Reminder("r", "call gran", now - 10 * min, By.Felix, due = Due(DueKind.At, Fixtures.at(time)))

    private fun slotStart(taskId: String, from: Long = now - 10 * min) = Reminder("s", "bring the form", now - 10 * min, By.Felix, taskId, Due(DueKind.SlotStart, from))

    private fun beforeDue(taskId: String, minutes: Int) = Reminder("b", "print it", now - 10 * min, By.Felix, taskId, Due(DueKind.BeforeDue, minutesBefore = minutes))

    @Test
    fun `an At comes at its time, or its snooze's, and isn't due before it`() {
        val r = at("2026-10-20T19:00")
        assertEquals(Fixtures.at("2026-10-20T19:00"), Reminders.next(listOf(r), plan(), emptyMap(), now))
        assertTrue(Reminders.due(listOf(r), plan(), emptyMap(), now).isEmpty())
        assertEquals(listOf(r to Fixtures.at("2026-10-20T19:00")), Reminders.due(listOf(r), plan(), emptyMap(), Fixtures.at("2026-10-20T19:00")))
        // Sent, it's done with till a snooze brings it back.
        val sent = r.copy(sentAt = Fixtures.at("2026-10-20T19:00"))
        assertFalse(Reminders.pending(sent))
        val snoozed = Reminders.snoozed(sent.copy(draft = FelixAnswer("oi"), draftedAt = now), Fixtures.at("2026-10-20T19:01"), 15)
        assertTrue(Reminders.pending(snoozed) && snoozed.draft == null && snoozed.draftedAt == null && snoozed.draftFor == null)
        assertEquals(Fixtures.at("2026-10-20T19:16"), Reminders.next(listOf(snoozed), plan(), emptyMap(), now))
        assertTrue(Reminders.due(listOf(snoozed), plan(), emptyMap(), Fixtures.at("2026-10-20T19:10")).isEmpty())
        // Moved and reworded only while it's never been sent.
        assertTrue(Reminders.unsent(r) && !Reminders.unsent(snoozed))
    }

    @Test
    fun `the app wakes for the start of his window to write one not yet written for, and for its moment`() {
        val r = at("2026-10-20T19:00")
        val ahead = 10 * min
        assertEquals(Fixtures.at("2026-10-20T18:50"), Reminders.nextWake(listOf(r), plan(), emptyMap(), now, ahead))
        // Written for already, or inside the window: its moment.
        assertEquals(Fixtures.at("2026-10-20T19:00"), Reminders.nextWake(listOf(r.copy(draft = FelixAnswer("oi"), draftedAt = Fixtures.at("2026-10-20T18:51"), draftFor = Fixtures.at("2026-10-20T19:00"))), plan(), emptyMap(), now, ahead))
        assertEquals(Fixtures.at("2026-10-20T19:00"), Reminders.nextWake(listOf(r), plan(), emptyMap(), Fixtures.at("2026-10-20T18:55"), ahead))
        assertNull(Reminders.nextWake(listOf(r.copy(sentAt = now)), plan(), emptyMap(), now, ahead))
        // Asked for and none came: no window to wake for, its moment only.
        assertEquals(Fixtures.at("2026-10-20T19:00"), Reminders.nextWake(listOf(r.copy(draftFor = Fixtures.at("2026-10-20T19:00"))), plan(), emptyMap(), now, ahead))
        // After a failed sweep, its moment gone by: not before the retry.
        assertEquals(Fixtures.at("2026-10-20T19:06"), Reminders.nextWake(listOf(r), plan(), emptyMap(), Fixtures.at("2026-10-20T19:01"), ahead, notBefore = Fixtures.at("2026-10-20T19:06")))
    }

    @Test
    fun `an At comes in quiet hours too, as an alarm would (Q10)`() {
        val night = at("2026-10-20T23:30")
        assertEquals(listOf(night to Fixtures.at("2026-10-20T23:30")), Reminders.due(listOf(night), plan(), emptyMap(), Fixtures.at("2026-10-20T23:30")))
    }

    @Test
    fun `a SlotStart comes at its task's next slot today, or tomorrow, and waits with none`() {
        val r = slotStart("teams:sheet")
        assertEquals(Fixtures.at("2026-10-20T18:00"), Reminders.next(listOf(r), plan(slot("teams:sheet", "2026-10-20T18:00"), slot("teams:other", "2026-10-20T17:30")), emptyMap(), now))
        assertEquals(Fixtures.at("2026-10-21T17:00"), Reminders.next(listOf(r), plan(slot("teams:sheet", "2026-10-21T17:00")), emptyMap(), now))
        assertNull(Reminders.next(listOf(r), plan(slot("teams:other", "2026-10-20T18:00")), emptyMap(), now))
        // A slot under way before it was set isn't its next: the one after is.
        val late = slotStart("teams:sheet", from = Fixtures.at("2026-10-20T17:05"))
        assertEquals(Fixtures.at("2026-10-21T17:00"), Reminders.next(listOf(late), plan(slot("teams:sheet", "2026-10-20T17:00"), slot("teams:sheet", "2026-10-21T17:00")), emptyMap(), now))
    }

    @Test
    fun `a BeforeDue follows its task's deadline as it moves`() {
        val r = beforeDue("teams:essay", 60)
        assertEquals(Fixtures.at("2026-10-22T08:00"), Reminders.next(listOf(r), plan(), mapOf("teams:essay" to task("teams:essay", "2026-10-22T09:00")), now))
        assertEquals(Fixtures.at("2026-10-23T08:00"), Reminders.next(listOf(r), plan(), mapOf("teams:essay" to task("teams:essay", "2026-10-23T09:00")), now))
    }

    @Test
    fun `one whose task closes before it comes is let go, an At never`() {
        val tasks = mapOf("teams:sheet" to task("teams:sheet", status = Status.Done), "teams:essay" to task("teams:essay"))
        val reminders = listOf(slotStart("teams:sheet"), beforeDue("teams:essay", 30), at("2026-10-20T19:00").copy(taskId = "teams:sheet"), beforeDue("teams:gone", 30).copy(id = "g"))
        // The sheet's done, the essay has no deadline, the last's task is gone, Teams read since; the At comes all the same.
        assertEquals(listOf("s", "b", "g"), Reminders.letGo(reminders, tasks, now) { now }.map { it.id })
        // Not listed only as Teams hasn't been read since it was set (a backup restored before the first sync): waited for.
        assertEquals(listOf("s", "b"), Reminders.letGo(reminders, tasks, now).map { it.id })
        assertEquals(listOf("s", "b"), Reminders.letGo(reminders, tasks, now) { now - 7 * 24 * 60 * min }.map { it.id })
        // Once sent, a snoozed one comes back at its snooze, whatever its task.
        assertTrue(Reminders.letGo(listOf(slotStart("teams:sheet").copy(sentAt = now - min, snoozedTo = now + 10 * min)), tasks, now).isEmpty())
    }

    @Test
    fun `one let go is back once what it waited on is, and one whose task went stays let go`() {
        val reminders = listOf(slotStart("teams:sheet"), beforeDue("teams:essay", 30), beforeDue("teams:gone", 30).copy(id = "g")).map { it.copy(letGoAt = now - min) }
        assertTrue(reminders.none(Reminders::pending))
        // As they were let go: the sheet done, the essay with no deadline, the last's task gone, Teams read since.
        val done = mapOf("teams:sheet" to task("teams:sheet", status = Status.Done), "teams:essay" to task("teams:essay"))
        assertTrue(Reminders.back(reminders, done, now) { now }.isEmpty())
        // The sheet open again (a tick undone), the essay given a due date; the gone one still not listed.
        val again = mapOf("teams:sheet" to task("teams:sheet"), "teams:essay" to task("teams:essay", due = "2026-10-22T09:00"))
        assertEquals(listOf("s", "b"), Reminders.back(reminders, again, now) { now }.map { it.id })
        // Only while there's room among those still to come: with 49, the first let go; with 50, none till one comes.
        val toCome = (1 until Reminders.MAX_PENDING).map { at("2026-10-20T19:00").copy(id = "p$it") }
        val first = reminders.map { if (it.id == "b") it.copy(letGoAt = now - 2 * min) else it }
        assertEquals(listOf("b"), Reminders.back(toCome + first, again, now) { now }.map { it.id })
        assertTrue(Reminders.back(toCome + at("2026-10-20T19:00").copy(id = "p50") + first, again, now) { now }.isEmpty())
    }

    @Test
    fun `a slot's reminder on a task with no slots of work for good is let go, a deadline's on it not`() {
        val event = task("teams:test", due = "2026-10-22T09:00").copy(kind = Kind.Event)
        val hidden = task("teams:sheet").copy(mine = Mine(notATask = SetBy(true, By.You)))
        val tasks = listOf(event, hidden).associateBy { it.id }
        val reminders = listOf(slotStart(event.id), slotStart(hidden.id).copy(id = "h"), beforeDue(event.id, 30))
        // The planner lays no work for an event, nor for one you said isn't a task: their slots never come.
        assertEquals(listOf("s", "h"), Reminders.letGo(reminders, tasks, now).map { it.id })
        assertTrue(Reminders.offPlan(event) && Reminders.offPlan(hidden) && !Reminders.offPlan(task("teams:essay")))
    }

    @Test
    fun `a draft is the moment's it was written for, and no other's, even within the window`() {
        val moment = Fixtures.at("2026-10-20T19:00")
        val r = at("2026-10-20T19:00").copy(draft = FelixAnswer("oi, call gran"), draftedAt = moment - 8 * min, draftFor = moment)
        assertTrue(Reminders.drafted(r, moment))
        // Its slot moved three minutes earlier, or to tomorrow: written for another moment.
        assertFalse(Reminders.drafted(r, moment - 3 * min))
        assertFalse(Reminders.drafted(r, moment + 24 * 60 * min))
        assertFalse(Reminders.drafted(r.undrafted(), moment))
        // Asked for and none came: not written, but asked for that moment, so not again.
        val none = r.copy(draft = null)
        assertFalse(Reminders.drafted(none, moment))
        assertTrue(Reminders.asked(none, moment) && !Reminders.asked(none, moment - 3 * min))
    }

    @Test
    fun `those sent, done or let go go after 30 days, a cancelled one with its act's Undo`() {
        val old = now - Reminders.KEEP_MS - min
        val sent = at("2026-10-20T19:00").copy(id = "sent", sentAt = old)
        val done = at("2026-10-20T19:00").copy(id = "done", sentAt = old, doneAt = now - min)
        val pending = at("2026-10-20T19:00").copy(id = "pending", at = old)
        val cancelled = at("2026-10-20T19:00").copy(id = "cancelled", cancelledAt = now - min)
        // Let go a month ago, or a minute ago: the latter kept, to come back if its task's to do again.
        val gone = slotStart("teams:sheet").copy(id = "gone", letGoAt = old)
        val letGo = slotStart("teams:sheet").copy(id = "let go", letGoAt = now - min)
        val all = listOf(sent, done, pending, cancelled, gone, letGo)
        val cancel = ChatMessage("m", Speaker.Felix, "Cancelled it.", now, acts = listOf(Act("Cancelled", ChatAction(ChatAction.Type.CancelReminder, "", value = "cancelled"))))
        assertEquals(listOf("done", "pending", "cancelled", "let go"), Reminders.prune(all, now, listOf(cancel)).map { it.id })
        // Its cancel undone (the reminder back), or its message gone from the window: nothing brings it back.
        assertEquals(listOf("done", "pending", "let go"), Reminders.prune(all, now, listOf(cancel.copy(acts = cancel.acts.map { it.copy(undone = true) }))).map { it.id })
        assertEquals(listOf("done", "pending", "let go"), Reminders.prune(all, now, emptyList()).map { it.id })
    }
}
