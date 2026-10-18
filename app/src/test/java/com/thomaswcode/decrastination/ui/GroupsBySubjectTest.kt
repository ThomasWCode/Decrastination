package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Work view by subject (UI-10). */
class GroupsBySubjectTest {
    private val now = Fixtures.at("2026-10-12T17:00")

    private fun task(id: String, className: String?, due: String?) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, className = className, dueAt = due?.let(Fixtures::at), kind = Kind.Homework,
        firstSeenAt = now, lastSeenAt = now,
    )

    @Test
    fun `tasks by the subject their class is named as, else its code, those with none last, each soonest due first`() {
        val tasks = listOf(
            task("b", "12.9-PH1", "2026-10-15T09:00"),
            task("a", "12.9-PH1", "2026-10-13T09:00"),
            task("c", "12.1-MA2", null),
            task("d", null, "2026-10-14T09:00"),
            task("e", " ", null),
        )
        val groups = Groups.bySubject(tasks, mapOf("12.9-PH1" to "Physics"), now)
        assertEquals(listOf("12.1-MA2", "Physics", Groups.NO_SUBJECT), groups.map { it.first })
        assertEquals(listOf("teams:a", "teams:b"), groups[1].second.map { it.id })
        assertEquals(listOf("teams:d", "teams:e"), groups[2].second.map { it.id })
    }
}
