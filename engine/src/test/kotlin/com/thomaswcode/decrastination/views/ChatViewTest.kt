package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Act
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.CheckInDraft
import com.thomaswcode.decrastination.core.FelixState
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WebSource
import com.thomaswcode.decrastination.data.FelixStore
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.sync.protocol.ApplyInstruction
import com.thomaswcode.decrastination.sync.protocol.ApplyProposal
import com.thomaswcode.decrastination.sync.protocol.CancelAnswer
import com.thomaswcode.decrastination.sync.protocol.ClearChat
import com.thomaswcode.decrastination.sync.protocol.DeleteInstruction
import com.thomaswcode.decrastination.sync.protocol.DiscardProposal
import com.thomaswcode.decrastination.sync.protocol.MarkAnswered
import com.thomaswcode.decrastination.sync.protocol.SendMessage
import com.thomaswcode.decrastination.sync.protocol.UndoProposal
import com.thomaswcode.decrastination.sync.protocol.WithdrawQuestion
import com.thomaswcode.decrastination.ui.ChatRows
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat as the website draws it (docs/spec/website.md): what's shown, and what can be done, as the phone's chat has them. */
class ChatViewTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    private fun his(id: String, at: Long = now - hour, text: String = "A word of his", kind: MessageKind = MessageKind.Chat) = ChatMessage(id, Speaker.Felix, text, at, kind = kind)

    private fun yours(id: String, at: Long = now - hour, text: String = "A word of yours") = ChatMessage(id, Speaker.You, text, at, answeredAt = at + 1)

    private fun task(id: String = "own:s", title: String = "Essay") = TaskItem(id, Source.Own, "s", title, kind = Kind.Homework, firstSeenAt = now, lastSeenAt = now)

    private fun view(
        chat: ChatState,
        archive: List<ChatMessage> = emptyList(),
        earlier: Int = 0,
        older: Boolean = false,
        quotes: List<ChatMessage> = emptyList(),
        tasks: List<TaskItem> = emptyList(),
        instructions: InstructionState = InstructionState(),
        answering: Long? = null,
        claudeOn: Boolean = true,
        writable: Boolean = true,
    ): ChatView = ChatViews.of(
        chat, archive, earlier, older, quotes, TaskState(tasks = tasks), Settings(felixName = "Fe"), MemoryState(), FelixStore(), instructions,
        emptyMap(), answering, claudeOn, today, now, zone, writable,
    )

    private fun view(vararg messages: ChatMessage, readAt: Long = 0) = view(ChatState(messages.toList(), readAt))

    @Test
    fun `each day's first message says its day, and his silences aren't drawn`() {
        val rows = view(his("a", now - 30 * hour), his("silent", now - 3 * hour, text = ""), yours("b", now - 2 * hour), his("c", now - hour)).rows
        assertEquals(listOf("a", "b", "c"), rows.map { it.id })
        assertEquals(listOf("Yesterday", "Today", null), rows.map { it.day })
        assertEquals("11:00", rows[2].time)
    }

    @Test
    fun `his words in two texts are two bubbles, and an answer in the chat stays whole`() {
        assertEquals(listOf("One", "Two"), view(his("a", text = "One\nTwo", kind = MessageKind.Morning)).rows.single().texts)
        assertEquals(listOf("One\nTwo"), view(his("a", text = "One\nTwo")).rows.single().texts)
    }

    @Test
    fun `a reply says what it answers only where that isn't the message above it`() {
        val first = his("a", text = "First line\nSecond line")
        val other = yours("b")
        val reply = yours("c").copy(replyTo = "a")
        val straight = yours("d").copy(replyTo = "c")
        val rows = view(first, other, reply, straight).rows.associateBy { it.id }
        assertEquals(ChatQuote("a", "Fe", "First line"), rows["c"]?.quote)
        assertNull(rows["d"]?.quote, "that's the one above it")
    }

    @Test
    fun `a reply's quote can come from further back than the pages read`() {
        val reply = yours("r").copy(replyTo = "gone")
        val far = his("gone", now - 400 * hour, text = "Long ago")
        assertEquals("Long ago", view(ChatState(listOf(reply)), quotes = listOf(far)).rows.single().quote?.line)
    }

    @Test
    fun `what it reaches back over the archive is shown, marked, and with nothing to do`() {
        val archive = (1..3).map { his("old$it", now - (500L - it) * hour, kind = MessageKind.Morning).copy(suggestedReplies = listOf("Yes")) }
        val shown = view(ChatState(listOf(yours("new"))), archive = archive, earlier = 2)
        assertEquals(listOf("old2", "old3", "new"), shown.rows.map { it.id })
        assertEquals(listOf(true, true, false), shown.rows.map { it.archived })
        assertTrue(shown.more, "one more past them")
        assertEquals(2, shown.earlier)
        assertTrue(shown.rows.first().chips.isEmpty())
        assertNull(shown.rows.first().mark)
        assertFalse(view(ChatState(listOf(yours("new"))), archive = archive, earlier = 3).more)
    }

    @Test
    fun `the archive offers more before any of it is read`() {
        assertTrue(view(ChatState(listOf(yours("new"))), older = true).more)
        assertFalse(view(ChatState(listOf(yours("new")))).more)
    }

    @Test
    fun `what's unread is his, with words, since the read mark, and not what's answered or replied to`() {
        val read = his("read", now - 5 * hour)
        val first = his("first", now - 3 * hour)
        val answered = his("answered", now - 2 * hour).copy(answeredAt = now)
        val replied = his("replied", now - 2 * hour)
        val silent = his("silent", now - hour, text = "")
        val last = his("last", now - hour)
        val shown = view(read, first, answered, replied, yours("y", now - 90 * 60_000L).copy(replyTo = "replied"), silent, last, readAt = now - 4 * hour)
        assertEquals("first", shown.firstUnread)
        assertEquals(2, shown.unread)
        assertEquals(now - hour, shown.newestAt)
    }

    @Test
    fun `yours waiting says why, with Try again and Take back, or he's typing`() {
        val waiting = ChatMessage("y", Speaker.You, "Well?", now - hour, note = "Claude's call failed")
        val row = view(waiting).rows.single()
        assertEquals("Claude's call failed", row.note)
        assertEquals(WithdrawQuestion("y"), row.takeBack)
        assertTrue(row.tryAgain)
        assertFalse(view(waiting).typing)
        val asked = view(ChatState(listOf(waiting.copy(note = null))), answering = now - 1_000)
        assertTrue(asked.typing)
        assertEquals(now - 1_000, asked.sentAt)
        assertEquals(CancelAnswer, asked.cancel)
        assertNull(asked.rows.single().takeBack)
        assertNull(view(ChatState(listOf(waiting.copy(note = null)))).cancel, "with no call running there's nothing to stop waiting for")
        assertNull(view(ChatState(listOf(waiting.copy(note = null))), answering = now - 1_000, writable = false).cancel, "nor one to stop from here while the phone's asking")
    }

    @Test
    fun `yours answered says nothing of why it waited`() {
        val answered = ChatMessage("y", Speaker.You, "Well?", now - hour, note = "Claude's call failed", answeredAt = now)
        val row = view(answered).rows.single()
        assertNull(row.note)
        assertNull(row.takeBack)
        assertFalse(row.tryAgain)
    }

    @Test
    fun `a question of his waits, is offered to the box, and can be marked answered`() {
        val question = his("q", kind = MessageKind.Question).copy(about = About(taskId = "t"), suggestedReplies = listOf("Yes", "No"))
        val shown = view(question)
        val row = shown.rows.single()
        assertTrue(row.waiting)
        assertEquals(MarkAnswered(listOf("q")), row.mark)
        assertEquals(MarkAnswered(listOf("q")), shown.markAll)
        assertEquals("q", shown.offer?.id)
        assertNull(shown.quiet)
        assertEquals(listOf("Yes", "No"), shown.chips.map { it.words })
    }

    @Test
    fun `a chip's id is the same till your reply is heard, so a second press is the one message`() {
        val question = his("q", kind = MessageKind.Question).copy(about = About(taskId = "t"), suggestedReplies = listOf("Yes"))
        assertEquals(SendMessage("Yes", id = "q-chip-0-0", replyTo = "q"), view(question).chips.single().send)
        assertEquals(view(question).chips, view(question).chips)
    }

    @Test
    fun `a chip pressed again after your reply is heard is a message of its own`() {
        val nudge = his("n", kind = MessageKind.SlotStart).copy(suggestedReplies = listOf("On it"))
        val reply = ChatMessage("n-chip-0-0", Speaker.You, "On it", now, replyTo = "n", answeredAt = now)
        val chip = view(nudge, reply).rows.first().chips.single()
        assertEquals(SendMessage("On it", id = "n-chip-0-1", replyTo = "n"), chip.send)
    }

    @Test
    fun `a check-in's question under way can't be marked answered, as the check-in waits on it`() {
        val question = his("q", kind = MessageKind.CheckIn)
        val shown = view(ChatState(listOf(question), felix = FelixState(checkInDraft = CheckInDraft("2026-W42"))))
        assertTrue(shown.rows.single().waiting)
        assertNull(shown.rows.single().mark)
        assertNull(shown.markAll)
    }

    @Test
    fun `a nudge just above the box takes your words with no strip`() {
        val nudge = his("n", kind = MessageKind.SlotStart).copy(suggestedReplies = listOf("On it"))
        val shown = view(nudge)
        assertEquals("n", shown.quiet)
        assertNull(shown.offer)
        assertEquals(listOf("On it"), shown.chips.map { it.words })
    }

    @Test
    fun `one of his you choose to reply to has its own chips`() {
        val message = his("m").copy(suggestedReplies = listOf("Sure"))
        val answered = his("a").copy(suggestedReplies = listOf("Sure"), answeredAt = now)
        val rows = view(message, answered).rows
        assertEquals(listOf("Sure"), rows[0].chips.map { it.words })
        assertTrue(rows[1].chips.isEmpty())
    }

    @Test
    fun `mirroring, it's all shown and there's nothing to press`() {
        val made = Proposal("p", task = OwnTask(title = "Essay"), state = ProposalState.Applied, made = "own:s", instant = true)
        val question = his("q", kind = MessageKind.Question).copy(about = About(taskId = "t"), suggestedReplies = listOf("Yes"), proposals = listOf(made))
        val waiting = ChatMessage("y", Speaker.You, "Well?", now - hour, note = "Why")
        val instruction = Instruction("i", "Words", About(), now, InstructionStatus.Applied, changes = listOf(Change(ChangeType.Minutes, taskId = "t", minutes = 9)))
        val shown = view(ChatState(listOf(question, waiting)), tasks = listOf(task()), instructions = InstructionState(listOf(instruction)), writable = false)
        assertFalse(shown.canChange)
        assertTrue(shown.rows.size == 2)
        assertNull(shown.markAll)
        assertNull(shown.clear)
        assertNull(shown.form)
        assertNull(shown.offer)
        assertTrue(shown.chips.isEmpty())
        assertTrue(shown.rows.all { it.mark == null && it.takeBack == null && !it.tryAgain && !it.ratable && it.chips.isEmpty() })
        assertTrue(shown.rows[0].cards.single().buttons.isEmpty())
        assertEquals("Added", shown.rows[0].cards.single().state)
        assertTrue(shown.inUse.single().buttons.isEmpty())
    }

    @Test
    fun `Claude off is said, and an empty chat says what it's for`() {
        assertNotNull(view(ChatState(), claudeOn = false).claudeOff)
        assertNull(view(ChatState()).claudeOff)
        val empty = view(ChatState())
        assertEquals(ChatRows.intro("Fe"), empty.intro)
        assertEquals(ChatRows.EXAMPLES, empty.examples)
        assertNull(view(yours("a")).intro)
        assertTrue(view(yours("a")).examples.isEmpty())
    }

    @Test
    fun `a chat with messages can be cleared, asking first`() {
        assertNull(view(ChatState()).clear)
        val clear = assertNotNull(view(yours("a")).clear)
        assertEquals(ClearChat, clear.action)
        assertEquals("Clear the chat?", clear.confirm)
    }

    @Test
    fun `only a page a browser can safely follow is a link`() {
        val message = his("m").copy(
            sources = listOf(WebSource("A page", "https://example.com/a"), WebSource("", "http://example.org/b"), WebSource("Odd", "javascript:alert(1)"), WebSource("Odder", "::not a url::")),
            searched = listOf("one", "two"),
        )
        val row = view(message).rows.single()
        assertEquals(
            listOf(ChatSourceLine("A page", "https://example.com/a"), ChatSourceLine("example.org", "http://example.org/b"), ChatSourceLine("Odd"), ChatSourceLine("Odder")),
            row.sources,
        )
        assertEquals("Searched the web for “one”, “two”", row.searched)
    }

    @Test
    fun `what he did is in a line, and red where it went wrong, folded with what's under it`() {
        val act = Act("A line of what he did")
        val failed = Act(Act.NOT_MADE + " a step", problem = true)
        val row = view(his("m").copy(acts = listOf(act, failed))).rows.single()
        assertTrue(row.folds)
        assertEquals(listOf("A line of what he did", Act.NOT_MADE + " a step"), row.lines.map { it.line })
        assertEquals("1 not made", row.wrong)
        assertTrue(row.problem)
        val plain = view(his("plain")).rows.single()
        assertFalse(plain.folds)
        assertFalse(plain.problem)
    }

    private fun card(proposal: Proposal, tasks: List<TaskItem> = emptyList(), writable: Boolean = true) = view(ChatState(listOf(his("m").copy(proposals = listOf(proposal)))), tasks = tasks, writable = writable).rows.single().cards.single()

    private val added = OwnTask(title = "Essay", minutes = 30, steps = listOf(OwnStep("Plan", 30)))

    @Test
    fun `a task he added says so, with its Undo and its Change`() {
        val shown = card(Proposal("p", task = added, state = ProposalState.Applied, made = "own:s", instant = true), listOf(task()))
        assertEquals("New task of yours", shown.heading)
        assertEquals("Added", shown.state)
        assertEquals(listOf("Undo", "Change"), shown.buttons.map { it.label })
        assertEquals(UndoProposal("m", "p"), shown.buttons[0].action)
        assertEquals("s", shown.buttons[1].edit?.sourceId)
        assertNull(shown.buttons[1].action, "the form saves it under its own id")
    }

    @Test
    fun `a card with nothing left to take back has no Undo`() {
        val change = Change(ChangeType.Minutes, taskId = "own:s", minutes = 90)
        assertTrue(card(Proposal("p", changes = listOf(change), state = ProposalState.Applied, instant = true), listOf(task())).buttons.isEmpty())
    }

    @Test
    fun `a task that's since gone can't be changed from its card`() {
        val shown = card(Proposal("p", task = added, state = ProposalState.Applied, made = "own:s", instant = true))
        assertEquals(listOf("Undo"), shown.buttons.map { it.label })
        assertEquals("Essay", shown.title)
    }

    @Test
    fun `undone, it can be made again, or changed first`() {
        val shown = card(Proposal("p", task = added, instant = true))
        assertEquals("Undone", shown.state)
        assertEquals(listOf("Redo", "Change first"), shown.buttons.map { it.label })
        assertEquals(ApplyProposal("m", "p"), shown.buttons[1].action)
        assertEquals(added, shown.buttons[1].edit?.task)
    }

    @Test
    fun `one not made says why, and offers Try again`() {
        val shown = card(Proposal("p", task = added, instant = true, note = "It couldn't be added"))
        assertEquals("It couldn't be added", shown.note)
        assertNull(shown.state)
        assertEquals(listOf("Try again", "Change first"), shown.buttons.map { it.label })
    }

    @Test
    fun `one from before everything was made as it came still waits to be added`() {
        val shown = card(Proposal("p", task = added))
        assertEquals(listOf("Add", "Change first", "Set aside"), shown.buttons.map { it.label })
        assertTrue(shown.buttons[0].primary)
        assertEquals(DiscardProposal("m", "p"), shown.buttons[2].action)
    }

    @Test
    fun `one set aside can be brought back`() {
        val shown = card(Proposal("p", task = added, state = ProposalState.Discarded))
        assertTrue(shown.aside)
        assertEquals("Set aside", shown.state)
        assertEquals(DiscardProposal("m", "p", discarded = false), shown.buttons.single().action)
    }

    @Test
    fun `a card of changes has them in the words they were proposed in, and its own word when applied`() {
        val change = Change(ChangeType.Minutes, taskId = "own:s", minutes = 90)
        val shown = card(Proposal("p", changes = listOf(change), lines = listOf("90 minutes on the essay"), state = ProposalState.Applied, made = "i"), listOf(task()))
        assertEquals("Changes", shown.heading)
        assertEquals(listOf("90 minutes on the essay"), shown.lines)
        assertEquals("Applied", shown.state)
        assertEquals("Done", card(Proposal("p", changes = listOf(change), state = ProposalState.Applied, instant = true), listOf(task())).state)
        assertEquals(listOf("Apply", "Set aside"), card(Proposal("p", changes = listOf(change)), listOf(task())).buttons.map { it.label })
    }

    @Test
    fun `an archived card says what became of it, with nothing to do`() {
        fun archived(proposal: Proposal) = view(ChatState(emptyList()), archive = listOf(his("m").copy(proposals = listOf(proposal))), earlier = 1).rows.single().cards.single()
        val instant = archived(Proposal("p", task = added, instant = true, note = "Why"))
        assertEquals("Not made", instant.state)
        assertTrue(instant.buttons.isEmpty())
        assertEquals("Not added", archived(Proposal("p", task = added)).state)
    }

    @Test
    fun `a task he shows you is outside the fold, and opens while it's there`() {
        val message = his("m").copy(proposals = listOf(Proposal("p", open = "own:s")))
        val there = view(ChatState(listOf(message)), tasks = listOf(task())).rows.single()
        assertEquals("Essay", there.shown.single().title)
        assertEquals("own:s", there.shown.single().open)
        assertTrue(there.cards.isEmpty())
        val gone = view(ChatState(listOf(message))).rows.single().shown.single()
        assertEquals("A task no longer listed", gone.title)
        assertNull(gone.open)
    }

    @Test
    fun `your changes are those to check, newest first, then those in use, each with what's to do`() {
        val change = Change(ChangeType.Minutes, taskId = "own:s", minutes = 90)
        val reading = Instruction("r", "Reading", About(), now - 3 * hour, InstructionStatus.Reading)
        val failed = Instruction("f", "Failed", About(), now - 2 * hour, InstructionStatus.Reading, note = "Claude is off")
        val unclear = Instruction("c", "Unclear", About(), now - hour, InstructionStatus.Unclear)
        val understood = Instruction("u", "Understood", About(), now - 4 * hour, InstructionStatus.Understood, changes = listOf(change))
        val applied = Instruction("a", "Applied", About(taskId = "own:s", taskTitle = "Essay"), now - 5 * hour, InstructionStatus.Applied, changes = listOf(change), note = "It couldn't be taken back")
        val shown = view(ChatState(), tasks = listOf(task()), instructions = InstructionState(listOf(reading, failed, unclear, understood, applied)))
        assertEquals(listOf("c", "f", "r", "u"), shown.toCheck.map { it.id })
        val byId = shown.toCheck.associateBy { it.id }
        assertTrue(assertNotNull(byId["r"]).reading)
        assertEquals("Claude is reading it…", byId["r"]?.says)
        assertEquals("Claude is off", byId["f"]?.says)
        assertFalse(assertNotNull(byId["f"]).reading)
        assertEquals("Claude couldn't turn it into a change", byId["c"]?.says)
        assertTrue(assertNotNull(byId["c"]).rewrite)
        assertEquals(listOf("Apply", "Discard"), byId["u"]?.buttons?.map { it.label })
        assertEquals(ApplyInstruction("u"), byId["u"]?.buttons?.first()?.action)
        val inUse = shown.inUse.single()
        assertEquals("About the task “Essay”", inUse.about)
        assertEquals("It couldn't be taken back", inUse.note)
        assertEquals(DeleteInstruction("a"), inUse.buttons.single().action)
    }

    @Test
    fun `a task's status doesn't stop its card saying what it is`() {
        val done = task().copy(status = Status.Done)
        val shown = card(Proposal("p", task = added, state = ProposalState.Applied, made = "own:s", instant = true), listOf(done))
        assertEquals("New task of yours", shown.heading)
    }
}
