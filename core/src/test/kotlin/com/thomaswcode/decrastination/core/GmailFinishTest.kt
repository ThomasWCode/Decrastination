package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a starred email's work is done here, so its keep or tick finishes it (docs/spec/sources.md §Gmail). */
class GmailFinishTest {
    private val now = Fixtures.at("2026-10-21T17:00")

    private fun email(minutes: Int = 20, worked: Int = 0, steps: List<SubStep> = emptyList(), status: Status = Status.Open, source: Source = Source.Gmail) = TaskItem(
        id = "gmail:1", source = source, sourceId = "1", title = "Trip consent form", dueAt = null, kind = Kind.Admin,
        sourceEffortMin = minutes, firstSeenAt = now, lastSeenAt = now, status = status, workedMin = worked, subSteps = steps,
    )

    private fun step(done: Boolean) = SubStep("Sign it", 10, done = done, origin = StepOrigin.Reading)

    @Test
    fun `its steps all ticked, or with none its minutes all worked, finish it`() {
        assertTrue(GmailFinish.due(email(steps = listOf(step(true), step(true))), 1.0))
        assertFalse(GmailFinish.due(email(steps = listOf(step(true), step(false))), 1.0))
        assertTrue(GmailFinish.due(email(minutes = 20, worked = 20), 1.0))
        // Its boxes kept at the pace learned: what's left rounds to none, as the plan rounds it.
        assertTrue(GmailFinish.due(email(minutes = 20, worked = 23), 1.15))
        assertFalse(GmailFinish.due(email(minutes = 20, worked = 20), 1.15))
        assertFalse(GmailFinish.due(email(minutes = 20, worked = 5), 1.0))
    }

    @Test
    fun `only an open email that isn't hidden is finished by its work`() {
        assertFalse(GmailFinish.due(email(worked = 20, status = Status.Done), 1.0))
        assertFalse(GmailFinish.due(email(worked = 20, source = Source.Teams), 1.0))
        assertFalse(GmailFinish.due(email(worked = 20).copy(mine = Mine(notATask = SetBy(true, By.You))), 1.0))
        // Finished by your word (Felix's done, the sheet's Finish): any open email, its work done or not.
        assertTrue(GmailFinish.finishable(email()))
        assertFalse(GmailFinish.finishable(email(status = Status.Done)))
        assertFalse(GmailFinish.finishable(email(source = Source.Own)))
    }
}
