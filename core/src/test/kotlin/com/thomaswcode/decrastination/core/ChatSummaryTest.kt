package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What's folded under a message of his, and the label that says it (docs/spec/felix.md §The chat). */
class ChatSummaryTest {
    private val now = Fixtures.at("2026-10-18T18:00")

    private fun his(
        proposals: List<Proposal> = emptyList(),
        acts: List<Act> = emptyList(),
        memoryEdits: List<MemoryEdit> = emptyList(),
        searched: List<String> = emptyList(),
        note: String? = null,
    ) = ChatMessage("h", Speaker.Felix, "Done.", now, proposals = proposals, acts = acts, memoryEdits = memoryEdits, searched = searched, note = note)

    private val tick = ChatAction(ChatAction.Type.TickStep, "own:essay", stepIndex = 0)

    @Test
    fun `a message that's just talk has nothing to fold and no label`() {
        val talk = his()
        assertFalse(ChatSummary.folds(talk))
        assertTrue(ChatSummary.line(talk).isEmpty)
        assertEquals(0, ChatSummary.problems(talk))
    }

    @Test
    fun `changes count each thing he made that still stands, then what he remembered and searched`() {
        val message = his(
            proposals = listOf(
                Proposal("p1", changes = listOf(Change(ChangeType.Minutes, taskId = "a", minutes = 30), Change(ChangeType.DueBy, taskId = "b", date = "2026-10-20")), state = ProposalState.Applied, instant = true),
                Proposal("p2", task = OwnTask(title = "Dentist"), state = ProposalState.Applied, instant = true),
                // Undone by you: not counted.
                Proposal("p3", task = OwnTask(title = "Trip form"), state = ProposalState.Proposed, instant = true),
                // A task to show: never folded, nothing made.
                Proposal("p4", open = "teams:sheet", state = ProposalState.Applied, instant = true),
            ),
            acts = listOf(
                Act("Ticked “Plan” on “Essay”.", tick),
                Act("Set the piece length to 40 min.", ChatAction(ChatAction.Type.Setting, "", setting = "boxMin", value = "40")),
                Act("Ticked “Write” on “Essay”.", tick, undone = true),
                Act("Read in full: “Trip form”."),
            ),
            memoryEdits = listOf(MemoryEdit("Remembered: football is on Tuesdays")),
            searched = listOf("open day programme"),
        )
        assertTrue(ChatSummary.folds(message))
        assertEquals(ChatSummary.Line("Changes: 5 · Remembered · Searched", ""), ChatSummary.line(message))
        assertEquals(0, ChatSummary.problems(message))
    }

    @Test
    fun `what went wrong is said apart, in red, and turns the chevron red`() {
        val message = his(
            acts = listOf(
                Act(Act.NOT_MADE + "it's due before then.", problem = true),
                Act(Act.NOT_MADE + "no such task.", problem = true),
                Act(Act.NOT_LOOKED_UP + "searching the web is off.", problem = true),
            ),
            note = "Left out, as the app can't use it: a step with no minutes",
        )
        assertEquals(ChatSummary.Line("", "2 not made · not looked up · left out"), ChatSummary.line(message))
        assertEquals(4, ChatSummary.problems(message))
        assertTrue(ChatSummary.folds(message))
    }

    @Test
    fun `a card from before everything was made as it came, whose making failed, is a problem too`() {
        val failed = his(proposals = listOf(Proposal("p", changes = listOf(Change(ChangeType.Minutes, taskId = "a", minutes = 30)), note = "the task's gone")))
        assertEquals(1, ChatSummary.problems(failed))
        assertEquals(ChatSummary.Line("", "1 not made"), ChatSummary.line(failed))
        // Made as it came and not made: said by its line, counted once.
        val instant = his(
            proposals = listOf(Proposal("p", changes = listOf(Change(ChangeType.Minutes, taskId = "a", minutes = 30)), instant = true, note = "the task's gone")),
            acts = listOf(Act(Act.NOT_MADE + "the task's gone.", problem = true)),
        )
        assertEquals(1, ChatSummary.problems(instant))
    }

    @Test
    fun `only a task to show, or only a memory undone, folds what there is but says nothing`() {
        assertFalse(ChatSummary.folds(his(proposals = listOf(Proposal("p", open = "teams:sheet", state = ProposalState.Applied, instant = true)))))
        val forgotten = his(memoryEdits = listOf(MemoryEdit("Remembered: bus at 7:40", undone = true)))
        assertTrue(ChatSummary.folds(forgotten))
        assertTrue(ChatSummary.line(forgotten).isEmpty)
    }
}
