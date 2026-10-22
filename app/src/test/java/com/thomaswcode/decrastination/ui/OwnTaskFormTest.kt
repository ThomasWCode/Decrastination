package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import kotlin.test.Test
import kotlin.test.assertEquals

/** The form's steps as its rows have them (UI-15). */
class OwnTaskFormTest {
    private val draft = Fixtures.at("2026-11-08T23:59")
    private val task = OwnTask(
        title = "Personal statement", kind = Kind.Admin, minutes = 480,
        steps = listOf(OwnStep("First draft", 240, dueAt = draft, id = "a1"), OwnStep("Ask for a reference", 240, id = "b2", anyOrder = true)),
    )

    @Test
    fun `a task Felix proposed keeps its steps' own dates and marks through the form`() {
        val steps = stepsOf(rowsOf(task, editing = false), task)
        assertEquals(listOf(null to draft, null to null), steps.map { it.from to it.dueAt })
        assertEquals(listOf(false, true), steps.map { it.anyOrder })
        // Not one of yours yet: no ids, each made as it's added.
        assertEquals(listOf("", ""), steps.map { it.id })
    }

    @Test
    fun `a task of yours edited keeps its steps' ids, dates and marks, and a step added has none`() {
        val steps = stepsOf(rowsOf(task, editing = true) + StepRow("Proofread", "30"), task)
        assertEquals(listOf("a1", "b2", ""), steps.map { it.id })
        assertEquals(listOf(draft, null, null), steps.map { it.dueAt })
        assertEquals(listOf(false, true, false), steps.map { it.anyOrder })
    }
}
