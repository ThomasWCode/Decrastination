package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.ChatSummary
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WebSource
import com.thomaswcode.decrastination.data.FelixStore
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.felix.ChatPrompts
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.FelixAnswers
import com.thomaswcode.decrastination.felix.Outbox
import com.thomaswcode.decrastination.felix.Replies
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.ApplyInstruction
import com.thomaswcode.decrastination.sync.protocol.ApplyProposal
import com.thomaswcode.decrastination.sync.protocol.CancelAnswer
import com.thomaswcode.decrastination.sync.protocol.ClearChat
import com.thomaswcode.decrastination.sync.protocol.DeleteInstruction
import com.thomaswcode.decrastination.sync.protocol.DiscardProposal
import com.thomaswcode.decrastination.sync.protocol.MarkAnswered
import com.thomaswcode.decrastination.sync.protocol.SendMessage
import com.thomaswcode.decrastination.sync.protocol.UndoAct
import com.thomaswcode.decrastination.sync.protocol.UndoMemory
import com.thomaswcode.decrastination.sync.protocol.UndoProposal
import com.thomaswcode.decrastination.sync.protocol.WithdrawQuestion
import com.thomaswcode.decrastination.ui.ChatRows
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.aboutLine
import com.thomaswcode.decrastination.ui.stepLine
import com.thomaswcode.decrastination.ui.taskLine
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * The chat as the website draws it (docs/spec/website.md): the phone's rows, words and rules, each thing to do ready to send. The window's
 * messages, under [earlier] of the archive's, with [more] past them. A visit keeps the [firstUnread] it's first told, as this one
 * moves as the chat's read, and reads up to [newestAt] on leaving, so nothing written since the page was drawn is read.
 */
@Serializable
data class ChatView(
    val name: String,
    val rows: List<ChatRow>,
    val earlier: Int,
    /** How many more one scroll back reads. */
    val page: Int = ChatRows.PAGE,
    val more: Boolean,
    val firstUnread: String? = null,
    /** How many of his wait unread. */
    val unread: Int = 0,
    val newestAt: Long? = null,
    /** He's answering: your message waits with nothing to say why. Sent to him at [sentAt], with [cancel] to stop waiting once that's [cancelAfterMs] ago. */
    val typing: Boolean = false,
    val sentAt: Long? = null,
    val cancel: Action? = null,
    /** Why what you write only waits, in red over the chat. */
    val claudeOff: String? = null,
    /** An empty chat's words. */
    val intro: String? = null,
    val examples: List<String> = emptyList(),
    /** His replies to tap, unless you've chosen a message of his with its own ([ChatRow.chips]). */
    val chips: List<ChatChip> = emptyList(),
    /** The message of his the box is offered as a reply to, shown over it, turned down with its ✕. */
    val offer: ChatQuote? = null,
    /** The nudge just above the box: your words go as its reply, with no strip and none of its subject. */
    val quiet: String? = null,
    val markAll: Action? = null,
    val clear: ChatButton? = null,
    /** What's in the chat's Your changes: those waiting on you or on Claude, then those in use. */
    val toCheck: List<ChangeRow> = emptyList(),
    val inUse: List<ChangeRow> = emptyList(),
    /** Whether anything can be changed here: the server applies commands. */
    val canChange: Boolean = false,
    /** What the form takes, for a card's task changed first. */
    val form: OwnTaskFormView? = null,
    val maxText: Int = ChatPrompts.MAX_TEXT,
    val maxFeedback: Int = Conversation.MAX_FEEDBACK,
    val readAfterMs: Long = ChatRows.READ_AFTER_MS,
    val undoMs: Long = ChatRows.UNDO_BAR_MS,
    val cancelAfterMs: Long = ChatRows.CANCEL_AFTER_S * 1_000,
)

/** What's said wherever you are on the website (W-9): how many of his wait unread. */
@Serializable
data class NoticesView(val unread: Int)

/** Who wrote a message replied to, and its first line. */
@Serializable
data class ChatQuote(val id: String, val who: String, val line: String)

/** A reply of his to tap: its id fixed, so a second press is the one message. */
@Serializable
data class ChatChip(val words: String, val send: Action)

@Serializable
data class ChatSourceLine(val title: String, val url: String? = null)

/** What he did, a line, with its Undo while it can be taken back; struck through once it's [undone]. */
@Serializable
data class ChatDid(val line: String, val undone: Boolean = false, val undo: Action? = null)

/** A button: what it sends, or the form it opens first ([edit]), what's filled in then going in [action]'s `edited`. */
@Serializable
data class ChatButton(
    val label: String,
    val action: Action? = null,
    val edit: OwnTaskEdit? = null,
    val primary: Boolean = false,
    val confirm: String? = null,
    val detail: String? = null,
    /** What the two answers to [confirm] are called, where they aren't the button's own and Cancel. */
    val yes: String? = null,
    val no: String? = null,
)

/** A card: a task he shows you ([open] its id), one he added, or what he changed; what became of it ([state]), and what's left to do. */
@Serializable
data class ChatCard(
    val id: String,
    val heading: String,
    val title: String? = null,
    val line: String? = null,
    val steps: List<String> = emptyList(),
    val notes: String? = null,
    val lines: List<String> = emptyList(),
    val note: String? = null,
    val open: String? = null,
    val state: String? = null,
    val aside: Boolean = false,
    val buttons: List<ChatButton> = emptyList(),
)

/** One message, its [day]'s line over it where it's that day's first. [texts]: a bubble each, his two or three, or the one. */
@Serializable
data class ChatRow(
    val id: String,
    val yours: Boolean,
    val at: Long,
    val time: String,
    val day: String? = null,
    val texts: List<String>,
    val about: String? = null,
    val quote: ChatQuote? = null,
    val archived: Boolean = false,
    /** Waiting for your answer, shown so; with what marks it answered, where it can be. */
    val waiting: Boolean = false,
    val mark: Action? = null,
    /** Yours: why it waits, with what takes it back; and whether it can be asked for again. */
    val note: String? = null,
    val takeBack: Action? = null,
    val tryAgain: Boolean = false,
    /** His replies to tap, where you've chosen this one to reply to. */
    val chips: List<ChatChip> = emptyList(),
    val ratable: Boolean = false,
    val rating: String = "",
    val feedback: String = "",
    val preferred: String = "",
    val feeling: String = "",
    val unsent: List<String> = emptyList(),
    /** What he did in a line, and what went wrong, in red; whether there's more folded under it, and something wrong in that. */
    val did: String = "",
    val wrong: String = "",
    val folds: Boolean = false,
    val problem: Boolean = false,
    val searched: String? = null,
    val sources: List<ChatSourceLine> = emptyList(),
    /** His message's own button, in the app's words: the week's review, or its summary to send, both on the Reviews page. */
    val opens: String? = null,
    /** A task he shows you, never folded; then the cards folded with the rest. */
    val shown: List<ChatCard> = emptyList(),
    val cards: List<ChatCard> = emptyList(),
    val lines: List<ChatDid> = emptyList(),
    val said: String? = null,
)

/** One of your changes, in the chat's Your changes: what it's about, your words, Claude's reading, and what's to do. */
@Serializable
data class ChangeRow(
    val id: String,
    val about: String,
    val words: String,
    val says: String? = null,
    val reading: Boolean = false,
    val problem: Boolean = false,
    val heading: String? = null,
    val lines: List<String> = emptyList(),
    val note: String? = null,
    val rewrite: Boolean = false,
    val buttons: List<ChatButton> = emptyList(),
)

object ChatViews {
    private val CLEAR_DETAIL = "Its messages go, the earlier ones too. What you added or applied from it stays: your tasks, and your changes (taken back under Your changes)."

    /** The chat at [now], with [earlier] of the [archive]'s newest over its window (it holds [older] past them, not read yet), [quotes] those read for a reply further back. */
    fun of(
        chat: ChatState,
        archive: List<ChatMessage>,
        earlier: Int,
        older: Boolean,
        quotes: List<ChatMessage>,
        tasks: TaskState,
        settings: Settings,
        memory: MemoryState,
        felix: FelixStore,
        instructions: InstructionState,
        kept: Map<String, Long>,
        answering: Long?,
        claudeOn: Boolean,
        today: LocalDate,
        now: Long,
        zone: ZoneId,
        writable: Boolean,
    ): ChatView {
        val name = settings.felixName
        val byId = tasks.tasks.associateBy { it.id }
        val checkIn = chat.felix.checkInDraft != null
        val awaiting = Replies.awaitingIds(chat.messages, checkIn, now, zone)
        val markable = Replies.markable(chat.messages, checkIn, awaiting)
        val turn = Replies.turn(chat.messages, checkIn, now, zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val calendarDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        fun quote(message: ChatMessage) = ChatQuote(message.id, if (message.from == Speaker.Felix) name else "You", message.text.trim().lineSequence().first())

        // An id fixed by what's been said so far: a press made twice before the page has heard is the one message, one made after it another.
        fun chips(message: ChatMessage): List<ChatChip> {
            val replies = chat.messages.count { it.from == Speaker.You && it.replyTo == message.id }
            return message.suggestedReplies.mapIndexed { index, reply -> ChatChip(reply, SendMessage(reply, id = "${message.id}-chip-$index-$replies", replyTo = message.id)) }
        }

        fun lines(message: ChatMessage, live: Boolean): List<ChatDid> =
            message.memoryEdits.mapIndexed { index, edit -> ChatDid(edit.line, edit.undone, UndoMemory(message.id, index).takeIf { live && !edit.undone && Memories.undo(memory, edit) != null }) } +
                message.acts.mapIndexed { index, act ->
                    val can = live && !act.undone && ChatActs.canUndo(act, tasks.tasks, settings, chat.messages, felix.notes, felix.reminders, kept, messageId = message.id, now = now, dayStart = dayStart, readAt = { source -> tasks.status(source).lastSuccessAt })
                    ChatDid(act.line, act.undone, UndoAct(message.id, index).takeIf { can })
                }

        fun row(item: ChatRows.Item.Message, day: String?): ChatRow {
            val message = item.message
            val live = writable && !item.archived
            val common = ChatRow(message.id, message.from == Speaker.You, message.at, Format.time(message.at, zone), day, listOf(message.text), archived = item.archived, quote = item.quote?.let(::quote))
            if (message.from == Speaker.You) {
                val why = message.note.takeIf { message.answeredAt == null }
                return common.copy(
                    about = aboutLine(message.about, now, zone).takeIf { message.about != About() },
                    note = why,
                    takeBack = WithdrawQuestion(message.id).takeIf { live && why != null },
                    tryAgain = live && why != null,
                )
            }
            val line = ChatSummary.line(message)
            return common.copy(
                texts = FelixAnswers.texts(message),
                waiting = message.id in awaiting,
                mark = MarkAnswered(listOf(message.id)).takeIf { live && message.id in markable },
                chips = if (live && message.answeredAt == null) chips(message) else emptyList(),
                ratable = writable,
                rating = message.rating,
                feedback = message.feedback,
                preferred = message.preferred,
                feeling = message.feeling,
                unsent = message.unsent,
                did = line.did,
                wrong = line.wrong,
                folds = ChatSummary.folds(message),
                problem = ChatSummary.problems(message) > 0,
                searched = message.searched.takeIf { it.isNotEmpty() }?.let { searched -> "Searched the web for " + searched.joinToString(", ") { "“$it”" } },
                sources = message.sources.map(::source),
                shown = message.proposals.filter { it.open != null }.map { card(message, it, byId, live, now, zone) },
                cards = message.proposals.filter { it.open == null }.map { card(message, it, byId, live, now, zone) },
                lines = lines(message, live),
                said = message.note,
                opens = message.opens?.let { if (it.startsWith(Conversation.OPENS_SUMMARY)) "Send it" else "Open the review" },
            )
        }

        val items = ChatRows.items(chat.messages, archive, earlier, firstUnread = null, zone, older, quotes)
        var heading: String? = null
        val rows = buildList {
            items.forEach { item ->
                when (item) {
                    is ChatRows.Item.Day -> heading = ChatRows.dayLine(item.date, calendarDay)
                    is ChatRows.Item.Message -> {
                        add(row(item, heading))
                        heading = null
                    }
                    else -> Unit
                }
            }
        }
        val unread = Outbox.unreadOf(chat)
        val waiting = chat.unanswered
        val shown = chat.messages.any { it.from == Speaker.You || it.text.isNotBlank() }
        val changes = instructions.instructions.filter { it.state != InstructionStatus.Applied }.sortedByDescending { it.at }
        return ChatView(
            name = name,
            rows = rows,
            earlier = earlier,
            more = items.firstOrNull() == ChatRows.Item.Earlier,
            firstUnread = unread.firstOrNull()?.id,
            unread = unread.size,
            newestAt = chat.messages.maxOfOrNull { it.at },
            typing = waiting != null && waiting.note == null,
            sentAt = answering,
            cancel = CancelAnswer.takeIf { writable && answering != null },
            claudeOff = "Claude is off, or has no key: switch it on in Settings, where its key goes too. What you write here waits for it; meanwhile, New task in Work adds one with the form.".takeUnless { claudeOn },
            intro = ChatRows.intro(name).takeUnless { shown },
            examples = if (shown) emptyList() else ChatRows.EXAMPLES,
            chips = if (writable) turn.last?.let(::chips).orEmpty() else emptyList(),
            offer = turn.pending?.takeIf { writable && turn.quiet == null }?.let(::quote),
            quiet = turn.quiet?.id?.takeIf { writable },
            markAll = MarkAnswered(markable.toList()).takeIf { writable && markable.isNotEmpty() },
            clear = ChatButton("Clear the chat", ClearChat, confirm = "Clear the chat?", detail = CLEAR_DETAIL).takeIf { writable && chat.messages.isNotEmpty() },
            toCheck = changes.map { change(it, byId, zone, now, writable) },
            inUse = instructions.applied.reversed().map { change(it, byId, zone, now, writable) },
            canChange = writable,
            form = WorkViews.FORM.takeIf { writable },
        )
    }

    /** A page of his sources, a link only where it's one a browser can safely follow: what's in it came from the web. */
    private fun source(source: WebSource): ChatSourceLine {
        val uri = runCatching { URI(source.url) }.getOrNull()
        val web = uri?.scheme?.lowercase() in WEB
        return ChatSourceLine(source.title.ifBlank { uri?.host ?: source.url }, source.url.takeIf { web })
    }

    private val WEB = setOf("http", "https")

    private fun card(message: ChatMessage, proposal: Proposal, tasks: Map<String, TaskItem>, live: Boolean, now: Long, zone: ZoneId): ChatCard {
        val open = proposal.open
        if (open != null) return ChatCard(proposal.id, "Task", tasks[open]?.title ?: "A task no longer listed", open = open.takeIf { it in tasks })
        val applied = proposal.state == ProposalState.Applied
        val discarded = proposal.state == ProposalState.Discarded
        // Added, the task as it is now (changed since with Change, or on its page); else as he gave it.
        val mine = proposal.made?.takeIf { applied }?.let { tasks[it] }?.takeIf { it.source == Source.Own }
        val task = mine?.let(OwnTaskRules::draftOf) ?: proposal.task
        val words = when {
            applied -> if (proposal.task != null) "Added" else if (proposal.instant) "Done" else "Applied"
            discarded -> "Set aside"
            proposal.instant -> if (proposal.note == null) "Undone" else if (live) null else "Not made"
            else -> if (live) null else "Not added"
        }
        val apply = ApplyProposal(message.id, proposal.id)
        val first = proposal.task?.let { ChatButton("Change first", apply, edit = OwnTaskEdit(proposal.id, it)) }
        val buttons = when {
            !live -> emptyList()
            applied -> listOfNotNull(
                ChatButton("Undo", UndoProposal(message.id, proposal.id)).takeIf { proposal.made != null || proposal.before.isNotEmpty() || proposal.schoolBefore != null },
                // A task it added is yours to change, in the form, from here too.
                mine?.takeIf { proposal.task != null }?.let { ChatButton("Change", edit = OwnTaskEdit(it.sourceId, OwnTaskRules.draftOf(it))) },
            )
            discarded -> listOf(ChatButton("Bring back", DiscardProposal(message.id, proposal.id, discarded = false)))
            // Made as it came, and since undone, or not made (its note says why): made again here.
            proposal.instant -> listOfNotNull(ChatButton(if (proposal.note == null) "Redo" else "Try again", apply), first)
            // From before everything was made as it came (D-60): still to add or apply.
            else -> listOfNotNull(ChatButton(if (task != null) "Add" else "Apply", apply, primary = true), first, ChatButton("Set aside", DiscardProposal(message.id, proposal.id)))
        }
        if (task == null) {
            // In the words it was proposed in: made, the task's estimate is the new one.
            val lines = proposal.lines.takeIf { it.size == proposal.changes.size } ?: proposal.changes.map { InstructionRules.describe(it, tasks, zone) }
            return ChatCard(proposal.id, "Changes", lines = lines, note = proposal.note, state = words, aside = discarded, buttons = buttons)
        }
        return ChatCard(
            proposal.id,
            if (task.kind == Kind.Event) "New event of yours" else "New task of yours",
            task.title,
            taskLine(task, now, zone),
            task.steps.map { stepLine(it.title, it.minutes, it.from, it.dueAt, it.anyOrder, now, zone) },
            task.notes.takeIf { it.isNotBlank() },
            note = proposal.note,
            state = words,
            aside = discarded,
            buttons = buttons,
        )
    }

    private fun change(instruction: Instruction, tasks: Map<String, TaskItem>, zone: ZoneId, now: Long, writable: Boolean): ChangeRow {
        val row = ChangeRow(instruction.id, aboutLine(instruction.about, now, zone), instruction.text)
        val discard = ChatButton("Discard", DeleteInstruction(instruction.id))
        val lines = instruction.changes.map { InstructionRules.describe(it, tasks, zone) }
        val made = when (instruction.state) {
            InstructionStatus.Reading -> row.copy(says = instruction.note ?: "Claude is reading it…", reading = instruction.note == null, buttons = listOf(discard))
            InstructionStatus.Unclear -> row.copy(says = instruction.note ?: "Claude couldn't turn it into a change", problem = true, rewrite = true, buttons = listOf(discard))
            InstructionStatus.Understood -> row.copy(heading = "Claude understood:", lines = lines, buttons = listOf(ChatButton("Apply", ApplyInstruction(instruction.id), primary = true), discard))
            // Why taking it back was refused, where it was.
            InstructionStatus.Applied -> row.copy(heading = "In use:", lines = lines, note = instruction.note, buttons = listOf(ChatButton("Take back", DeleteInstruction(instruction.id))))
        }
        return if (writable) made else made.copy(rewrite = false, buttons = emptyList())
    }
}
