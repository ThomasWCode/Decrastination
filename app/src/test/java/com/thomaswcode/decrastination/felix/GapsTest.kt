package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.SourceValues
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The gaps closed in the chat (docs/plan_v4/06-what-felix-can-do.md §3, Stage 8b), through the Conversation on
 * temporary stores, his answers from a stand-in for Claude's API ([FelixHarness]): a piece kept as Day's Done
 * keeps it, and taken back (G1).
 */
class GapsTest {
    private val felix = FelixHarness("2026-10-20T17:00")
    private val conversation = felix.conversation
    private val stores = felix.stores
    private val now = felix.clock.now()

    @AfterTest
    fun close() = felix.close()

    /** A Teams worksheet due tomorrow morning, half an hour with no steps: planned this evening in one box. */
    private val sheet = TaskItem(
        id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Forces worksheet", dueAt = Fixtures.at("2026-10-21T09:00"),
        kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now,
    )

    private fun answer(reply: String, actions: List<String> = emptyList()) =
        """{"reply":"$reply","newTasks":[],"changes":[],"memories":[],"suggestedReplies":[],"actions":[${actions.joinToString(",")}],"research":"","details":[]}"""

    private fun action(type: String, taskId: String = "", value: String = "") = """{"type":"$type","taskId":"$taskId","stepIndex":-1,"setting":"","value":"$value"}"""

    /** His answer to [words], made, and the message it is. */
    private fun asked(words: String, answer: String): ChatMessage = runBlocking {
        felix.answers(answer)
        assertTrue(conversation.ask(words))
        felix.settle()
        stores.chat.value.messages.last { it.from == Speaker.Felix }
    }

    private fun canUndo(message: ChatMessage, i: Int, dayStart: Long? = null): Boolean {
        val m = stores.chat.value.messages.first { it.id == message.id }
        return ChatActs.canUndo(m.acts[i], stores.tasks.value.tasks, stores.settings.value, stores.chat.value.messages, kept = conversation.keptAt(), dayStart = dayStart)
    }

    @Test
    fun `a piece done with no step to tick is kept as Day's Done keeps it, and his Undo, or his unkeepPiece, takes it back`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val key = felix.planning.plan().todaySlots.mapNotNull { it.chunk }.single { it.taskId == sheet.id }.key
        // The context names it, to keep.
        val context = conversation.chatContext()
        assertEquals(sheet.id, context.keepable[key])
        assertTrue("key $key" in context.plan, context.plan)
        val kept = asked("I've done the worksheet", answer("Kept: nice one.", listOf(action("keepPiece", sheet.id, key))))
        assertEquals("Kept: ${sheet.title}", kept.acts.single().line)
        assertEquals(ChatAction(ChatAction.Type.KeepPiece, sheet.id, value = key), kept.acts.single().action)
        assertEquals(listOf(key), conversation.keptToday().map { it.key })
        // Kept today, the plan no longer shows it, so the context lists it as kept, to take back.
        assertTrue("Kept today (its key | the piece):\n- $key | " in conversation.chatContext().plan)
        assertTrue(canUndo(kept, 0))
        assertNull(conversation.undoAct(kept.id, 0))
        assertTrue(conversation.keptToday().isEmpty())
        assertFalse(canUndo(kept, 0))
        // Kept again from Day, then taken back by him.
        assertTrue(felix.focusKeep(key))
        val taken = asked("actually I haven't done it", answer("Taken back.", listOf(action("unkeepPiece", sheet.id, key))))
        assertEquals("Not kept after all: ${sheet.title}", taken.acts.single().line)
        assertTrue(conversation.keptToday().isEmpty())
        // Its Undo keeps it again.
        assertTrue(canUndo(taken, 0))
        assertNull(conversation.undoAct(taken.id, 0))
        assertEquals(listOf(key), conversation.keptToday().map { it.key })
    }

    @Test
    fun `a piece kept from Day while he answered isn't kept again by him, its next box having its key`() = runBlocking {
        val long = sheet.copy(sourceEffortMin = 120)
        stores.tasks.update { it.copy(tasks = listOf(long)) }
        val key = felix.planning.plan().todaySlots.mapNotNull { it.chunk }.first { it.taskId == long.id }.key
        felix.answers(answer("Kept: nice one.", listOf(action("keepPiece", long.id, key))), delayMs = 1_500)
        assertTrue(conversation.ask("I've done the first hour"))
        delay(500)
        // Kept from Day meanwhile: the next box has its key now.
        assertTrue(felix.focusKeep(key))
        val worked = stores.tasks.value.tasks.single().workedMin
        assertTrue(felix.planning.plan().ordered.any { it.key == key }, "the next box under the same key")
        felix.settle()
        val his = stores.chat.value.messages.last { it.from == Speaker.Felix }
        assertTrue(his.acts.none { it.action?.type == ChatAction.Type.KeepPiece }, his.acts.toString())
        assertEquals(worked, stores.tasks.value.tasks.single().workedMin)
    }

    @Test
    fun `his keep's Undo takes back the record it made, and isn't offered once the piece is kept again another way`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val key = felix.planning.plan().todaySlots.mapNotNull { it.chunk }.single { it.taskId == sheet.id }.key
        val kept = asked("I've done the worksheet", answer("Kept.", listOf(action("keepPiece", sheet.id, key))))
        assertTrue(canUndo(kept, 0))
        // Taken back from Day, then kept there again: his Undo would take back yours.
        assertTrue(felix.focusUnkeep(key, now))
        felix.clock.time = now + 60_000L
        assertTrue(felix.focusKeep(key))
        assertFalse(canUndo(kept, 0))
        assertEquals("It's changed since, so it can't be undone here", conversation.undoAct(kept.id, 0))
        assertEquals(listOf(key), conversation.keptToday().map { it.key })
    }

    @Test
    fun `a piece kept ahead of its day is among today's kept, with its Undo`() = runBlocking {
        val later = sheet.copy(id = "teams:later", sourceId = "later", notBefore = Fixtures.at("2026-10-21T00:00"), dueAt = Fixtures.at("2026-10-23T09:00"))
        stores.tasks.update { it.copy(tasks = listOf(later)) }
        val key = felix.planning.plan().ordered.first { it.taskId == later.id }.key
        assertTrue(felix.planning.plan().todaySlots.none { it.chunk?.key == key }, "planned after today")
        val kept = asked("I did the worksheet early", answer("Kept.", listOf(action("keepPiece", later.id, key))))
        assertEquals(listOf(key), conversation.keptToday().map { it.key })
        assertTrue(canUndo(kept, 0))
        assertNull(conversation.undoAct(kept.id, 0))
        assertTrue(conversation.keptToday().isEmpty())
    }

    @Test
    fun `his unkeep takes back the record he was shown, not one kept again from Day while he answered`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val key = felix.planning.plan().todaySlots.mapNotNull { it.chunk }.single { it.taskId == sheet.id }.key
        assertTrue(felix.focusKeep(key))
        felix.answers(answer("Taken back.", listOf(action("unkeepPiece", sheet.id, key))), delayMs = 1_500)
        assertTrue(conversation.ask("actually I haven't done it"))
        delay(500)
        // Taken back and kept again from Day meanwhile: yours now.
        felix.clock.time = now + 60_000L
        assertTrue(felix.focusUnkeep(key, now))
        assertTrue(felix.focusKeep(key))
        felix.settle()
        val his = stores.chat.value.messages.last { it.from == Speaker.Felix }
        assertTrue(his.acts.none { it.action?.type == ChatAction.Type.UnkeepPiece }, his.acts.toString())
        assertEquals(listOf(key), conversation.keptToday().map { it.key })
    }

    @Test
    fun `his keep that finished one of yours is taken back by its Undo, the task open again`() = runBlocking {
        val own = OwnTaskRules.create(OwnTask(title = "Poster"), "p", now)
        stores.tasks.update { it.copy(tasks = listOf(own)) }
        val key = felix.planning.plan().ordered.first { it.taskId == own.id }.key
        val kept = asked("I've done the poster", answer("Kept.", listOf(action("keepPiece", own.id, key))))
        // Its last piece finished it, as Day's Done does (the app's finish of your own, here by hand).
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Done, doneAt = now) }) }
        assertTrue(canUndo(kept, 0))
        assertNull(conversation.undoAct(kept.id, 0))
        val back = stores.tasks.value.tasks.single()
        assertTrue(back.isOpen && back.workedMin == 0, back.toString())
    }

    @Test
    fun `his unkeep of the piece that finished one of yours opens it again, and not one finished another way since`() = runBlocking {
        val own = OwnTaskRules.create(OwnTask(title = "Poster"), "p", now)
        stores.tasks.update { it.copy(tasks = listOf(own)) }
        val key = felix.planning.plan().ordered.first { it.taskId == own.id }.key
        // Kept from Day, its last piece finishing it (the app's finish of your own, here by hand).
        assertTrue(felix.focusKeep(key))
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Done, doneAt = now) }) }
        val taken = asked("actually I haven't finished the poster", answer("Taken back.", listOf(action("unkeepPiece", own.id, key))))
        val back = stores.tasks.value.tasks.single()
        assertTrue(back.isOpen && back.workedMin == 0, back.toString())
        assertTrue(stores.log.value.slots.none { it.key == key && it.outcome.kept })
        assertTrue(canUndo(taken, 0))
        // Kept again, then finished another way a while after: his unkeep leaves it finished, and the record kept.
        assertTrue(felix.focusKeep(key))
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Done, doneAt = now + 10 * 60_000L) }) }
        asked("no, I haven't", answer("Taken back.", listOf(action("unkeepPiece", own.id, key))))
        assertFalse(stores.tasks.value.tasks.single().isOpen)
        assertTrue(stores.log.value.slots.any { it.key == key && it.outcome.kept })
    }

    @Test
    fun `an unkeep's Undo keeps the piece again as it was kept, with its extra minutes and its photo`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val key = felix.planning.plan().todaySlots.mapNotNull { it.chunk }.single { it.taskId == sheet.id }.key
        assertTrue(felix.focusKeep(key, extraMin = 10, photo = true))
        val was = stores.log.value.slots.last { it.key == key && it.outcome.kept }
        val taken = asked("actually I haven't done it", answer("Taken back.", listOf(action("unkeepPiece", sheet.id, key))))
        assertTrue(stores.log.value.slots.none { it.key == key && it.outcome.kept })
        assertNull(conversation.undoAct(taken.id, 0))
        val again = stores.log.value.slots.last { it.key == key && it.outcome.kept }
        assertEquals(listOf(was.workedMin, if (was.photo) 1 else 0), listOf(again.workedMin, if (again.photo) 1 else 0))
        assertEquals(was.earned, again.earned)
    }

    @Test
    fun `an unkeep's Undo of a step's last sitting counts its step's other sitting once`() = runBlocking {
        val stepped = sheet.copy(sourceEffortMin = 120, subSteps = listOf(SubStep("Write it up", 120)))
        stores.tasks.update { it.copy(tasks = listOf(stepped)) }
        val pieces = felix.planning.plan().chunksOf(stepped.id)
        assertTrue(pieces.size >= 2 && pieces.any { it.key == "${stepped.id}#0" }, pieces.map { it.key }.toString())
        // The last sitting kept from Day, ten minutes more: the step's other sitting counted in with it.
        assertTrue(felix.focusKeep("${stepped.id}#0", extraMin = 10))
        val worked = stores.tasks.value.tasks.single().workedMin
        val was = stores.log.value.slots.last { it.key == "${stepped.id}#0" && it.outcome.kept }.workedMin
        val taken = asked("actually I haven't written it up", answer("Taken back.", listOf(action("unkeepPiece", stepped.id, "${stepped.id}#0"))))
        assertNull(conversation.undoAct(taken.id, 0))
        assertEquals(worked, stores.tasks.value.tasks.single().workedMin)
        assertEquals(was, stores.log.value.slots.last { it.key == "${stepped.id}#0" && it.outcome.kept }.workedMin)
    }

    @Test
    fun `an unkeep's Undo isn't done once its piece is laid otherwise, nor offered once its day is over`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val key = felix.planning.plan().todaySlots.mapNotNull { it.chunk }.single { it.taskId == sheet.id }.key
        assertTrue(felix.focusKeep(key))
        val taken = asked("actually I haven't done it", answer("Taken back.", listOf(action("unkeepPiece", sheet.id, key))))
        // Its task's minutes changed since: the piece under its key is another, not the one taken back.
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(sourceEffortMin = 20) }) }
        assertEquals("It's changed since, so it can't be undone here", conversation.undoAct(taken.id, 0))
        assertTrue(stores.log.value.slots.none { it.key == key && it.outcome.kept })
        // As it was again, it's there, till midnight: then its key names another day's piece.
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(sourceEffortMin = sheet.sourceEffortMin) }) }
        assertTrue(canUndo(taken, 0, dayStart = Fixtures.at("2026-10-20T00:00")))
        felix.clock.time = Fixtures.at("2026-10-21T00:05")
        assertFalse(canUndo(taken, 0, dayStart = Fixtures.at("2026-10-21T00:00")))
        assertEquals("It's changed since, so it can't be undone here", conversation.undoAct(taken.id, 0))
        assertTrue(stores.log.value.slots.none { it.key == key && it.outcome.kept })
    }

    @Test
    fun `kept today names each key's newest record, the one his unkeep takes back`() = runBlocking {
        val long = sheet.copy(sourceEffortMin = 120)
        stores.tasks.update { it.copy(tasks = listOf(long)) }
        val key = felix.planning.plan().todaySlots.mapNotNull { it.chunk }.first { it.taskId == long.id }.key
        assertTrue(felix.focusKeep(key))
        felix.clock.time = now + 60_000L
        assertTrue(felix.focusKeep(key))
        assertEquals(listOf(now + 60_000L), conversation.keptToday().map { it.at })
    }

    @Test
    fun `an unkeep of a box's newest is undone though an older box of its key is still kept`() = runBlocking {
        val long = sheet.copy(sourceEffortMin = 120)
        stores.tasks.update { it.copy(tasks = listOf(long)) }
        val key = felix.planning.plan().todaySlots.mapNotNull { it.chunk }.first { it.taskId == long.id }.key
        assertTrue(felix.focusKeep(key))
        // The next box has the key now: kept too, a minute on.
        felix.clock.time = now + 60_000L
        assertTrue(felix.focusKeep(key))
        felix.clock.time = now + 2 * 60_000L
        val taken = asked("actually I haven't done the second hour", answer("Taken back.", listOf(action("unkeepPiece", long.id, key))))
        assertEquals(1, stores.log.value.slots.count { it.key == key && it.outcome.kept })
        assertTrue(canUndo(taken, 0))
        assertNull(conversation.undoAct(taken.id, 0))
        assertEquals(2, stores.log.value.slots.count { it.key == key && it.outcome.kept })
    }

    /** His sync action (G2): [source] read, about [taskId]. */
    private fun sync(source: String, taskId: String = "") = """{"type":"sync","taskId":"$taskId","stepIndex":-1,"setting":"$source","value":""}"""

    @Test
    fun `asked to check a source, it's read now, said with no Undo, and his note says how the task he was asked about changed`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val his = asked("I've handed it in, check Teams", answer("Reading Teams.", listOf(sync("Teams", sheet.id))))
        assertEquals(listOf(setOf(Source.Teams)), felix.synced)
        assertEquals("Asked Teams to read", his.acts.single().line)
        assertNull(his.acts.single().action)
        // A read that changes nothing says nothing, and he keeps watching.
        conversation.afterSync()
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Note })
        // Teams' read finds it handed in: his note says so, once. (A quota missed, not done, says nothing: Merge's Missed.)
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Done, doneAt = now) }) }
        conversation.afterSync()
        conversation.afterSync()
        val note = stores.chat.value.messages.single { it.kind == MessageKind.Note }
        assertEquals("Teams says “Forces worksheet” is handed in.", note.text)
        assertTrue(note.byApp && note.about.taskId == sheet.id)
    }

    @Test
    fun `a change made here while the read was asked for isn't said as the source's, only what the source says`() = runBlocking {
        val given = sheet.copy(sourceValues = SourceValues(Kind.Homework, sheet.dueAt, null, sheet.title))
        stores.tasks.update { it.copy(tasks = listOf(given)) }
        asked("check Teams", answer("Reading Teams.", listOf(sync("Teams", given.id))))
        // A due date of yours laid over it meanwhile, and a name of yours: the source's own unchanged, nothing said.
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(dueAt = Fixtures.at("2026-10-22T09:00"), title = "My worksheet") }) }
        conversation.afterSync()
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Note })
        // Teams' own deadline moved: said.
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(sourceValues = it.sourceValues!!.copy(dueAt = Fixtures.at("2026-10-23T09:00"))) }) }
        conversation.afterSync()
        val note = stores.chat.value.messages.single { it.kind == MessageKind.Note }
        assertTrue(note.text.startsWith("Teams says “My worksheet” is due"), note.text)
    }

    @Test
    fun `a change first seen by a read after the watch is over says nothing`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        asked("check Teams", answer("Reading Teams.", listOf(sync("Teams", sheet.id))))
        // No read till past the quarter of an hour, which then finds it handed in.
        felix.clock.time = now + 20 * 60_000L
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Done, doneAt = felix.clock.time) }) }
        conversation.afterSync()
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Note })
    }

    @Test
    fun `a read of one source about another's task is made, but the task isn't watched`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val his = asked("check Gmail for the worksheet", answer("Reading Gmail.", listOf(sync("Gmail", sheet.id))))
        assertEquals(listOf(setOf(Source.Gmail)), felix.synced)
        assertTrue(his.note!!.contains("it's from Teams"), his.note)
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Done, doneAt = now) }) }
        conversation.afterSync()
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Note })
    }

    @Test
    fun `a read past the watch says nothing, and a source the app doesn't read is refused`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        asked("check Teams", answer("Reading Teams.", listOf(sync("Teams", sheet.id))))
        felix.clock.time = now + 20 * 60_000L
        conversation.afterSync()
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(dueAt = Fixtures.at("2026-10-23T09:00")) }) }
        conversation.afterSync()
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Note })
        val refused = asked("check Moodle", answer("Can't.", listOf(sync("Moodle"))))
        assertTrue(refused.acts.isEmpty() && refused.note!!.contains("Moodle"))
        assertEquals(1, felix.synced.size)
    }

    @Test
    fun `asked to open a task where it lives, it's opened at once with no card and said, and one of your own has its page here`() = runBlocking {
        val own = OwnTaskRules.create(OwnTask(title = "Poster"), "p", now)
        stores.tasks.update { it.copy(tasks = listOf(sheet, own)) }
        conversation.chatInFront = true
        val his = asked("open them in their apps", answer("Opening it in Teams.", listOf(action("open", sheet.id, "source"), action("open", own.id, "source"))))
        assertEquals(listOf(sheet.id), felix.ports.opened)
        assertEquals("Opened in Teams: Forces worksheet", his.acts.single().line)
        assertNull(his.acts.single().action)
        assertEquals(listOf(own.id), his.proposals.mapNotNull { it.open })
    }

    @Test
    fun `the chat left while a task opened where it lives, its card is there too`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        conversation.chatInFront = true
        felix.ports.whileOpening = { conversation.chatInFront = false }
        val his = asked("open it in Teams", answer("Opening it in Teams.", listOf(action("open", sheet.id, "source"))))
        assertEquals(listOf(sheet.id), felix.ports.opened)
        assertEquals(listOf(sheet.id), his.proposals.mapNotNull { it.open })
        assertTrue(his.acts.single().line.startsWith("Opened in Teams: Forces worksheet") && "its card's here" in his.acts.single().line, his.acts.toString())
    }

    @Test
    fun `with the chat not on screen, a task to open where it lives comes on a card instead, and says why`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        conversation.chatInFront = false
        val his = asked("open it in Teams", answer("Opening it in Teams.", listOf(action("open", sheet.id, "source"))))
        assertTrue(felix.ports.opened.isEmpty())
        assertEquals(listOf(sheet.id), his.proposals.mapNotNull { it.open })
        assertEquals("On its card, not opened in Teams: the chat wasn't on screen.", his.acts.single().line)
    }

    @Test
    fun `a piece the plan doesn't list, or one not kept today, isn't his to keep or take back`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val his = asked("done", answer("Kept.", listOf(action("keepPiece", sheet.id, "teams:sheet#9"), action("unkeepPiece", sheet.id, "teams:sheet#-1#1"))))
        assertTrue(his.acts.none { it.action != null })
        assertNotNull(his.note)
        assertTrue(conversation.keptToday().isEmpty())
    }
}
