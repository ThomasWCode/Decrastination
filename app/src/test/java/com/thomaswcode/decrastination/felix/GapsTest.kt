package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    private fun canUndo(message: ChatMessage, i: Int): Boolean {
        val m = stores.chat.value.messages.first { it.id == message.id }
        return ChatActs.canUndo(m.acts[i], stores.tasks.value.tasks, stores.settings.value, stores.chat.value.messages, kept = conversation.keptToday().mapTo(HashSet()) { it.key })
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
        // Teams' read finds it handed in: his note says so, once.
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Done, doneAt = now) }) }
        conversation.afterSync()
        conversation.afterSync()
        val note = stores.chat.value.messages.single { it.kind == MessageKind.Note }
        assertEquals("Teams says “Forces worksheet” is handed in.", note.text)
        assertTrue(note.byApp && note.about.taskId == sheet.id)
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
    fun `a piece the plan doesn't list, or one not kept today, isn't his to keep or take back`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val his = asked("done", answer("Kept.", listOf(action("keepPiece", sheet.id, "teams:sheet#9"), action("unkeepPiece", sheet.id, "teams:sheet#-1#1"))))
        assertTrue(his.acts.none { it.action != null })
        assertNotNull(his.note)
        assertTrue(conversation.keptToday().isEmpty())
    }
}
