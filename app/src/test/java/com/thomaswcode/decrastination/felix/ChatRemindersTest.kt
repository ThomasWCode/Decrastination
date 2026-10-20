package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.Due
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.FelixAnswer
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.NoteScope
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.FelixStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat's reminders and notes (Stage 8a): read from his answer and checked, and told to him in the context. */
class ChatRemindersTest {
    // Tuesday 20 Oct 2026, 17:00.
    private val now = Fixtures.at("2026-10-20T17:00")
    private val min = 60_000L

    private fun task(id: String, title: String, due: String? = null, status: Status = Status.Open, className: String? = null, steps: List<SubStep> = emptyList()) = TaskItem(
        id = id, source = Source.Teams, sourceId = id.substringAfter(':'), title = title, dueAt = due?.let(Fixtures::at), kind = Kind.Homework,
        sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now, status = status, className = className, subSteps = steps,
    )

    private val sheet = task("teams:sheet", "Forces worksheet", "2026-10-22T09:00", className = "12X/Ph1", steps = listOf(SubStep("Questions 1 to 4", 15)))
    private val essay = task("teams:essay", "Causes essay")
    private val done = task("teams:done", "Practical write-up", status = Status.Done)
    private val tasks = listOf(sheet, essay, done)

    /** A Remind change (Q13: B), as his answer gives one: its words the title, when it comes the eventKey, a listed one's id afterTaskId. */
    private fun reminder(id: String = "", text: String = "call gran", date: String = "", time: String = "", taskId: String = "", whenIs: String = "at", minutesBefore: Int = -1) =
        """{"type":"Remind","taskId":"$taskId","afterTaskId":"$id","eventKey":"$whenIs","eventTime":"","minutes":$minutesBefore,"date":"$date","endDate":"","weekday":"","time":"$time","endTime":"","title":"$text","steps":[],"paced":false}"""

    /** A Note change, its words the title, a standing one's scope its eventKey. */
    private fun note(taskId: String, text: String = "use the second source", scope: String = "") =
        """{"type":"Note","taskId":"$taskId","afterTaskId":"","eventKey":"$scope","eventTime":"","minutes":-1,"date":"","endDate":"","weekday":"","time":"","endTime":"","title":"$text","steps":[],"paced":false}"""

    private fun answer(reminders: List<String> = emptyList(), notes: List<String> = emptyList(), actions: String = "[]") =
        """{"reply":"Done.","newTasks":[],"changes":[${(reminders + notes).joinToString(",")}],"memories":[],"suggestedReplies":[],"actions":$actions,"research":"","details":[]}"""

    private fun parse(json: String, felix: FelixStore = FelixStore()) = ChatAnswers.parse(json, tasks, emptyList(), About(), now, LONDON, felix = felix)!!

    private val listed = Reminder("r1", "call gran", now - 60 * min, By.Felix, due = Due(DueKind.At, Fixtures.at("2026-10-20T19:00")), draft = FelixAnswer("oi"), draftedAt = now)

    @Test
    fun `a reminder is read at a time, at a task's next slot, or before its due, in their words`() {
        val reading = parse(answer(listOf(
            reminder(text = "call gran", date = "2026-10-21", time = "19:00"),
            reminder(text = "stretch", time = "17:03"),
            reminder(text = "bring the form", taskId = sheet.id, whenIs = "slotStart"),
            reminder(text = "print it", taskId = sheet.id, whenIs = "beforeDue", minutesBefore = 60),
        )))
        assertEquals(emptyList(), reading.leftOut)
        val (gran, stretch, form, print) = reading.reminders
        assertEquals(Due(DueKind.At, Fixtures.at("2026-10-21T19:00")), gran.due)
        // No date: today.
        assertEquals(Due(DueKind.At, Fixtures.at("2026-10-20T17:03")), stretch.due)
        assertEquals(Due(DueKind.SlotStart, now), form.due)
        assertEquals(sheet.id, form.taskId)
        assertEquals(Due(DueKind.BeforeDue, minutesBefore = 60), print.due)
        assertTrue(reading.reminders.all { it.by == By.Felix && it.at == now && it.sentAt == null } && reading.reminders.map { it.id }.toSet().size == 4)
        assertEquals("call gran", gran.text)
    }

    @Test
    fun `one gone by, with no time or no words, on a task not listed or finished, or before a task with no due date, is refused and said`() {
        val reading = parse(answer(listOf(
            reminder(text = "too late", time = "16:00"),
            reminder(text = "no time"),
            reminder(text = ""),
            reminder(text = "on nothing", taskId = "teams:gone", whenIs = "slotStart"),
            reminder(text = "no task", whenIs = "slotStart"),
            reminder(text = "on done", taskId = done.id, whenIs = "slotStart"),
            reminder(text = "no deadline", taskId = essay.id, whenIs = "beforeDue", minutesBefore = 30),
            reminder(text = "too long before", taskId = sheet.id, whenIs = "beforeDue", minutesBefore = 8 * 24 * 60),
            reminder(text = "next year", date = "2027-10-21", time = "19:00"),
            // Due at 09:00 on Thursday, a week before is gone by.
            reminder(text = "a week before", taskId = sheet.id, whenIs = "beforeDue", minutesBefore = 7 * 24 * 60),
        )))
        assertTrue(reading.reminders.isEmpty())
        assertEquals(10, reading.leftOut.size, reading.leftOut.joinToString("\n"))
        assertTrue(reading.leftOut.last().contains("which has gone by"))
        // A slot's from a day past the year ahead never comes in reach: refused as an At's would be.
        assertTrue(parse(answer(listOf(reminder(text = "in 2099", date = "2099-10-21", taskId = sheet.id, whenIs = "slotStart")))).leftOut.single().contains("more than a year ahead"))
        assertTrue(reading.leftOut.first().contains("16:00, which has gone by"))
        // A minute behind, as his reading of now may be, is now.
        assertEquals(1, parse(answer(listOf(reminder(time = "16:59")))).reminders.size)
    }

    @Test
    fun `a listed reminder's id moves or rewords it in place, its draft let go, and an unknown or sent one's refused`() {
        val felix = FelixStore(reminders = listOf(listed, listed.copy(id = "r2", sentAt = now - min)))
        val moved = parse(answer(listOf(reminder(id = "r1", text = "", time = "20:00"))), felix).reminders.single()
        assertEquals("r1", moved.id)
        assertEquals("call gran", moved.text)
        assertEquals(Due(DueKind.At, Fixtures.at("2026-10-20T20:00")), moved.due)
        assertNull(moved.draft)
        // Reworded, its time kept.
        val reworded = parse(answer(listOf(reminder(id = "r1", text = "ring gran"))), felix).reminders.single()
        assertEquals(listed.due to "ring gran", reworded.due to reworded.text)
        // To the start of the sheet's next slot.
        assertEquals(Due(DueKind.SlotStart, now) to sheet.id, parse(answer(listOf(reminder(id = "r1", taskId = sheet.id, whenIs = "slotStart"))), felix).reminders.single().let { it.due to it.taskId })
        val refused = parse(answer(listOf(reminder(id = "r2", time = "20:00"), reminder(id = "nope", time = "20:00"))), felix)
        assertTrue(refused.reminders.isEmpty() && refused.leftOut.size == 2)
    }

    @Test
    fun `no more than ten an answer, nor past fifty still to come`() {
        val many = parse(answer((1..12).map { reminder(text = "r$it", time = "18:00") }))
        assertEquals(10, many.reminders.size)
        assertTrue(many.leftOut.single().startsWith("2 more reminders"))
        val full = FelixStore(reminders = (1..Reminders.MAX_PENDING - 1).map { listed.copy(id = "p$it") })
        val past = parse(answer(listOf(reminder(text = "one", time = "18:00"), reminder(text = "two", time = "18:00"))), full)
        assertEquals(listOf("one"), past.reminders.map { it.text })
        assertTrue(past.leftOut.single().contains("still to come already"))
        // One the same answer cancels makes room for its replacement.
        val fifty = FelixStore(reminders = (1..Reminders.MAX_PENDING).map { listed.copy(id = "p$it") })
        val cancel = """[{"type":"cancelReminder","taskId":"","stepIndex":-1,"setting":"","value":"p1"}]"""
        val replaced = parse(answer(listOf(reminder(text = "instead", time = "18:00")), actions = cancel), fifty)
        assertEquals(listOf("instead"), replaced.reminders.map { it.text })
        assertTrue(replaced.leftOut.isEmpty(), replaced.leftOut.toString())
    }

    @Test
    fun `a note is read on a listed task, finished or not, and one on a task not listed, with no words, or past ten a task is refused`() {
        val reading = parse(answer(notes = listOf(note(sheet.id), note(done.id, "marked: 18/20"), note("teams:gone"), note(sheet.id, ""))))
        assertEquals(listOf(sheet.id, done.id), reading.notes.map { it.taskId })
        assertTrue(reading.notes.all { it.by == By.Felix && it.at == now && it.scope == null })
        assertEquals(2, reading.leftOut.size)
        val full = FelixStore(notes = (1..9).map { Note("n$it", "note $it", now, By.You, taskId = sheet.id) })
        val past = parse(answer(notes = listOf(note(sheet.id, "tenth"), note(sheet.id, "eleventh"))), full)
        assertEquals(listOf("tenth"), past.notes.map { it.text })
        assertTrue(past.leftOut.single().contains("has 10 already"))
    }

    private fun standing(text: String, subject: String = "", className: String = "", source: String = "", titleContains: String = "", taskId: String = "") =
        note(taskId, text, listOfNotNull("subject:$subject".takeIf { subject.isNotEmpty() }, "class:$className".takeIf { className.isNotEmpty() }, "source:$source".takeIf { source.isNotEmpty() }, "title:$titleContains".takeIf { titleContains.isNotEmpty() }).joinToString(";"))

    @Test
    fun `a standing note is for every task of a subject, a class, a source or a word in its title, never a task too, and the source one the app reads`() {
        val reading = parse(answer(notes = listOf(
            standing("on sheets", subject = "Chemistry"),
            standing("show the working", className = "12X/Ph1", source = "Power Planner"),
            standing("read it twice", titleContains = "essay"),
            standing("both", subject = "Chemistry", taskId = sheet.id),
            standing("no such source", source = "Moodle"),
        )))
        assertEquals(
            listOf(NoteScope(subject = "Chemistry"), NoteScope(className = "12X/Ph1", source = Source.PowerPlanner), NoteScope(titleContains = "essay")),
            reading.notes.map { it.scope },
        )
        assertTrue(reading.notes.all { it.taskId == null })
        assertEquals(2, reading.leftOut.size, reading.leftOut.joinToString("\n"))
        // No more than twenty kept.
        val full = FelixStore(notes = (1..Notes.MAX_STANDING).map { Note("s$it", "standing $it", now, By.Felix, scope = NoteScope(subject = "Physics")) })
        assertTrue(parse(answer(notes = listOf(standing("one more", subject = "Physics"))), full).let { it.notes.isEmpty() && it.leftOut.single().contains("kept already") })
    }

    @Test
    fun `cancelReminder names a reminder still to come and deleteNote a note kept, and nothing else`() {
        val felix = FelixStore(
            notes = listOf(Note("n1", "use the second source", now, By.Felix, taskId = essay.id), Note("n2", "gone", now, By.Felix, taskId = essay.id, deletedAt = now)),
            reminders = listOf(listed, listed.copy(id = "r2", cancelledAt = now)),
        )
        val actions = """[{"type":"cancelReminder","taskId":"","stepIndex":-1,"setting":"","value":"r1"},{"type":"cancelReminder","taskId":"","stepIndex":-1,"setting":"","value":"r2"},{"type":"deleteNote","taskId":"","stepIndex":-1,"setting":"","value":"n1"},{"type":"deleteNote","taskId":"","stepIndex":-1,"setting":"","value":"n2"}]"""
        val reading = parse(answer(actions = actions), felix)
        assertEquals(listOf(ChatAction(ChatAction.Type.CancelReminder, "", value = "r1"), ChatAction(ChatAction.Type.DeleteNote, essay.id, value = "n1")), reading.actions)
        assertEquals(2, reading.leftOut.size)
    }

    @Test
    fun `many notes keep to their room, and twenty standing ones go once, so what comes after them stays`() {
        val many = (1..80).map { task("teams:t$it", "Task $it", "2026-10-22T09:00", className = "12X/Ph1") }
        val notes = many.flatMap { t -> (1..10).map { Note("${t.id}-$it", "x".repeat(Notes.MAX_TEXT), now, By.Felix, taskId = t.id) } } +
            (1..Notes.MAX_STANDING).map { Note("s$it", "y".repeat(Notes.MAX_TEXT), now, By.You, scope = NoteScope(subject = "Physics")) }
        val context = ChatPrompts.context(many, emptyList(), emptyList(), now, LONDON, classMap = mapOf("12X/Ph1" to "Physics"), notes = notes, reminders = listOf(listed), moments = mapOf("r1" to Fixtures.at("2026-10-20T19:00")))
        // The notes in about their room and the standing ones' once, not 80 tasks' worth of each.
        assertTrue(context.length < 30_000, "${context.length}")
        assertTrue("more notes of it, on its sheet" in context)
        assertEquals(Notes.MAX_STANDING, context.lines().count { it.startsWith("- s") && it.endsWith("y".repeat(Notes.MAX_TEXT)) })
        assertTrue("- r1 | Tue 20 Oct 19:00 | call gran | -" in context.lines())
    }

    @Test
    fun `the context lists each task's notes under its steps, its own and the standing ones it matches, and the reminders still to come after the settings`() {
        val notes = listOf(
            Note("n1", "use the second source", now, By.Felix, taskId = sheet.id),
            Note("n2", "on sheets, not in the book", now, By.You, scope = NoteScope(subject = "Physics")),
            Note("n3", "deleted", now, By.Felix, taskId = sheet.id, deletedAt = now),
            Note("n4", "marked, the write-up's back", now, By.You, taskId = done.id),
        )
        val reminders = listOf(
            listed,
            Reminder("r2", "bring the form", now, By.Felix, sheet.id, Due(DueKind.SlotStart, now)),
            listed.copy(id = "r3", sentAt = now - min),
        )
        val context = ChatPrompts.context(tasks, emptyList(), emptyList(), now, LONDON, classMap = mapOf("12X/Ph1" to "Physics"), notes = notes, reminders = reminders, moments = mapOf("r1" to Fixtures.at("2026-10-20T19:00")))
        val lines = context.lines()
        val step = lines.indexOfFirst { it.startsWith("    step 0: Questions 1 to 4") }
        assertEquals("    note n1 (yours): use the second source", lines[step + 1])
        assertEquals("    standing notes for it: n2", lines[step + 2])
        // The standing ones once, after the tasks.
        assertEquals(1, lines.count { "on sheets, not in the book" in it })
        assertTrue("- n2 | theirs | every Physics task | on sheets, not in the book" in lines)
        assertTrue("deleted" !in context)
        // A finished task's, by id, in a list of their own.
        val closed = lines.indexOf("Notes on finished tasks (id | whose | its task | their words):")
        assertTrue(closed > lines.indexOf("Standing notes, for every task they match (id | whose | the tasks they're for | their words):"))
        assertEquals("- n4 | theirs | Practical write-up | marked, the write-up's back", lines[closed + 1])
        val header = lines.indexOf("Reminders set, still to come (id | when | their words | its task):")
        assertTrue(header > lines.indexOfFirst { it.startsWith("Calendar events") })
        assertEquals(
            listOf("- r1 | Tue 20 Oct 19:00 | call gran | -", "- r2 | at the start of “Forces worksheet”'s next slot, none planned yet | bring the form | teams:sheet"),
            lines.subList(header + 1, header + 3),
        )
        // Sent, it's not to come.
        assertTrue("r3" !in context)
        assertEquals("(none)", ChatPrompts.context(tasks, emptyList(), emptyList(), now, LONDON).lines().let { it[it.indexOf("Reminders set, still to come (id | when | their words | its task):") + 1] })
    }
}
