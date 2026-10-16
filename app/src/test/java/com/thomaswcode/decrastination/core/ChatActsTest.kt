package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.data.Settings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Whether the chat's actions can still be undone (16 Oct). */
class ChatActsTest {
    private val now = Fixtures.at("2026-10-16T20:00")
    private val settings = Settings()

    private fun essay() = OwnTasks.create(OwnTask(title = "Essay", steps = listOf(OwnStep("Plan", 20), OwnStep("Write", 40))), "e", now)

    private fun finished() = essay().let { t -> t.copy(subSteps = t.subSteps.map { it.copy(done = true) }, status = Status.Done, doneAt = now) }

    @Test
    fun `a task opened again is undone only while it's as opening it left it`() {
        val done = finished()
        val act = Act("Open again: Essay", ChatAction(ChatAction.Type.Reopen, done.id), was = done)
        val reopened = OwnTasks.reopened(done)
        assertTrue(ChatActs.canUndo(act, listOf(reopened), settings, emptyList()))
        // Worked on since, or finished another way: it stands.
        assertFalse(ChatActs.canUndo(act, listOf(reopened.copy(workedMin = 10)), settings, emptyList()))
        assertFalse(ChatActs.canUndo(act, listOf(done), settings, emptyList()))
    }

    @Test
    fun `a task deleted is brought back only while no card that had added it is made again`() {
        val task = essay()
        val act = Act("Deleted: Essay", ChatAction(ChatAction.Type.Delete, task.id), was = task, cards = listOf("m|c"))
        val card = Proposal("c", task = OwnTasks.draftOf(task), instant = true)
        val messages = listOf(ChatMessage("m", Speaker.Claude, "Added it.", now, proposals = listOf(card)))
        assertTrue(ChatActs.canUndo(act, emptyList(), settings, messages))
        // Redo made it anew: bringing the old one back would make two.
        val redone = listOf(messages[0].copy(proposals = listOf(card.copy(state = ProposalState.Applied, made = "own:new"))))
        assertFalse(ChatActs.canUndo(act, emptyList(), settings, redone))
        // Back already.
        assertFalse(ChatActs.canUndo(act, listOf(task), settings, messages))
    }

    @Test
    fun `a tick that finished one of yours can be undone, and a setting while it's as it was set`() {
        val tick = Act("Ticked: Write, of Essay", ChatAction(ChatAction.Type.TickStep, "own:e", 1))
        assertTrue(ChatActs.canUndo(tick, listOf(finished()), settings, emptyList()))
        val box = Act("A piece's length (minutes): 30 → 40", ChatAction(ChatAction.Type.Setting, "", setting = "boxMin", value = "40"), before = "30", after = "40")
        assertTrue(ChatActs.canUndo(box, emptyList(), settings.copy(boxMin = 40), emptyList()))
        assertFalse(ChatActs.canUndo(box, emptyList(), settings.copy(boxMin = 45), emptyList()))
        assertFalse(ChatActs.canUndo(box.copy(undone = true), emptyList(), settings.copy(boxMin = 40), emptyList()))
    }
}
