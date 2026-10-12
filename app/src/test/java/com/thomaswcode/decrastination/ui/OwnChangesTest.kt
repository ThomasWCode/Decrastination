package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class OwnChangesTest {
    private fun task(dueAt: Long?) = TaskItem(
        id = "teams:t",
        source = Source.Teams,
        sourceId = "t",
        title = "Statics Prep",
        dueAt = dueAt,
        kind = Kind.Homework,
        firstSeenAt = Fixtures.at("2026-10-07T12:00"),
        lastSeenAt = Fixtures.at("2026-10-07T12:00"),
    )

    @Test
    fun `a due date picked keeps the time of day it's due now`() {
        val (change, text) = ownChange(task(Fixtures.at("2026-10-12T08:30")), OwnChange.DueOn, LocalDate.parse("2026-10-16"), LONDON)
        assertEquals(ChangeType.DueBy, change.type)
        assertEquals(Fixtures.at("2026-10-16T08:30"), change.time)
        assertEquals("Due Fri 16 Oct 08:30 (set in Tasks)", text)
    }

    @Test
    fun `a due date picked for a task with none is at the day's end`() {
        val (change, _) = ownChange(task(null), OwnChange.DueOn, LocalDate.parse("2026-10-16"), LONDON)
        assertEquals(Fixtures.at("2026-10-16T23:59"), change.time)
    }

    @Test
    fun `a start picked is the day's beginning, across the clocks changing`() {
        // 25 Oct 2026: the clocks go back in London.
        val (change, text) = ownChange(task(null), OwnChange.StartFrom, LocalDate.parse("2026-10-26"), LONDON)
        assertEquals(ChangeType.StartFrom, change.type)
        assertEquals(Fixtures.at("2026-10-26T00:00"), change.time)
        assertEquals("Can't be started before Mon 26 Oct (set in Tasks)", text)
    }

    @Test
    fun `not a task is the change an instruction makes`() {
        val (change, _) = ownChange(task(null), OwnChange.NotATask, LocalDate.parse("2026-10-16"), LONDON)
        assertEquals(ChangeType.NotATask, change.type)
        assertEquals("teams:t", change.taskId)
    }
}
