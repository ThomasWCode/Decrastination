package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Lessons
import com.thomaswcode.decrastination.core.PpClass
import com.thomaswcode.decrastination.core.PpSchedule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Timetable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The next lessons in the chat (G8, docs/spec/felix.md §What he's told): each task's class, the lessons' line, and a due date at the next lesson. */
class ChatLessonsTest {
    // Tuesday 20 Oct 2026, 09:00.
    private val now = Fixtures.at("2026-10-20T09:00")
    private val classMap = mapOf("12X/Ch1" to "Chemistry", "Chem" to "Chemistry", "12X/Ma1" to "Maths")

    private val sheet = TaskItem(
        id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Rates worksheet", className = "12X/Ch1", dueAt = Fixtures.at("2026-10-23T09:00"),
        kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now,
    )
    private val own = TaskItem(id = "own:o1", source = Source.Own, sourceId = "o1", title = "Revise vectors", className = "Maths", kind = Kind.Revision, sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now)
    private val bare = TaskItem(id = "gmail:m1", source = Source.Gmail, sourceId = "m1", title = "Trip form", kind = Kind.Admin, sourceEffortMin = 10, firstSeenAt = now, lastSeenAt = now)
    private val tasks = listOf(sheet, own, bare)

    private val timetable = Timetable(
        "2026-09-07T00:00:00",
        listOf(
            PpClass("c1", "Chem", listOf(PpSchedule("2026-09-05T10:00:00", "2026-09-05T11:00:00", 3), PpSchedule("2026-09-05T14:00:00", "2026-09-05T15:00:00", 5))),
            PpClass("h1", "Hist", listOf(PpSchedule("2026-09-05T09:00:00", "2026-09-05T10:00:00", 4))),
        ),
    )

    private fun timetabled(readAt: Long? = now - 60_000L) = Lessons.Timetabled(Lessons.groups(Lessons.next(timetable, now, LONDON, SchoolCalendar()), classMap), readAt, Lessons.stale(readAt, now))

    private fun context(lessons: Lessons.Timetabled?) = ChatPrompts.context(tasks, emptyList(), emptyList(), now, LONDON, classMap, lessons = lessons)

    @Test
    fun `each task's line says its class, and the subject the class map gives it`() {
        val lines = context(null).lines()
        assertTrue(lines.any { it.startsWith("- teams:sheet | Rates worksheet | Teams | 12X/Ch1, Chemistry | Homework |") }, lines.joinToString("\n"))
        // Your own's class is its subject; with none, a dash.
        assertTrue(lines.any { it.startsWith("- own:o1 | Revise vectors | Yours | Maths | Revision |") })
        assertTrue(lines.any { it.startsWith("- gmail:m1 | Trip form | Gmail | - | Admin |") })
    }

    @Test
    fun `the next lessons follow the calendar's events, a class tied to no code said so, dated where the timetable's stale, and none with no timetable`() {
        val lines = context(timetabled()).lines()
        val header = lines.indexOf("Next lessons (class: its subject, and its names | its next three):")
        assertTrue(header > lines.indexOfFirst { it.startsWith("Calendar events") })
        assertEquals("- Chemistry (Chem, 12X/Ch1) | Wed 21 Oct 10:00–11:00; Fri 23 Oct 14:00–15:00; Wed 28 Oct 10:00–11:00", lines[header + 1])
        assertEquals("- Hist (no subject given, so tied to no class code) | Thu 22 Oct 09:00–10:00; Thu 29 Oct 09:00–10:00; Thu 5 Nov 09:00–10:00", lines[header + 2])
        assertTrue("Next lessons, from the timetable read on Saturday 10 October 2026, over a week ago (class" in context(timetabled(readAt = Fixtures.at("2026-10-10T12:00"))))
        // Read at no known time (its source's last read lost): of no known age, so stale.
        assertTrue("Next lessons, from a timetable of no known age (class" in context(timetabled(readAt = null)))
        assertTrue("Next lessons" !in context(null))
    }

    private fun dueBy(lesson: String, date: String = "", time: String = "") =
        """{"reply":"Due then.","newTasks":[],"changes":[{"type":"DueBy","taskId":"${sheet.id}","afterTaskId":"","eventKey":"","eventTime":"","minutes":-1,"date":"$date","endDate":"","weekday":"","time":"$time","endTime":"","title":"","steps":[],"paced":false,"lesson":"$lesson"}],"memories":[],"suggestedReplies":[],"actions":[],"research":"","details":[]}"""

    private fun parse(json: String, lessons: Lessons.Timetabled?) =
        ChatAnswers.parse(json, tasks, emptyList(), About(), now, LONDON, context = ChatPrompts.Context("", "", lessons = lessons))!!

    @Test
    fun `a due date at a class's next lesson is set to its start, its own date and time not read`() {
        val reading = parse(dueBy("Chemistry", date = "2026-11-30", time = "08:00"), timetabled())
        val change = reading.changes.single()
        assertEquals(ChangeType.DueBy, change.type)
        assertEquals(Fixtures.at("2026-10-21T10:00"), change.time)
        assertEquals(emptyList(), reading.leftOut)
        // By one of its names too: the Teams code its subject ties to the class.
        assertEquals(Fixtures.at("2026-10-21T10:00"), parse(dueBy("12X/Ch1"), timetabled()).changes.single().time)
    }

    @Test
    fun `a class it can't find, a stale timetable or none refuses it, saying why, so he asks`() {
        val unknown = parse(dueBy("Physics"), timetabled())
        assertTrue(unknown.changes.isEmpty() && unknown.leftOut.single().contains("the next lesson of “Physics”"))
        val stale = parse(dueBy("Chemistry"), timetabled(readAt = Fixtures.at("2026-10-10T12:00")))
        assertTrue(stale.changes.isEmpty() && stale.leftOut.single().contains("read on Sat 10 Oct, over a week ago"), stale.leftOut.toString())
        val undated = parse(dueBy("Chemistry"), timetabled(readAt = null))
        assertTrue(undated.changes.isEmpty() && undated.leftOut.single().contains("from a timetable of no known age"), undated.leftOut.toString())
        val none = parse(dueBy("Chemistry"), null)
        assertTrue(none.changes.isEmpty() && none.leftOut.single().contains("no timetable read"))
        // A date given as any date is, with no lesson, is set as given.
        assertEquals(Fixtures.at("2026-10-22T09:00"), parse(dueBy("", date = "2026-10-22", time = "09:00"), null).changes.single().time)
    }
}
