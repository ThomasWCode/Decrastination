package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.Act
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Speaker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chat's turns as they're sent and acted on, through [FelixHarness]: what the input's budget leaves out stays
 * waiting (F-02), and a step's action lands on the step he named, or on none (F-01), from the review of 4.9.1.
 */
class ChatTurnsTest {
    @Test
    fun `a message of yours the input's budget leaves out stays waiting, said, while those that went are answered`(): Unit = runBlocking {
        FelixHarness().use { felix ->
            val now = felix.clock.now()
            // Six long messages waiting, his between them and your newest, so they're a turn apart and can't all go.
            val waiting = (0..5).map { i -> ChatMessage("pending-$i", Speaker.You, "PENDING_${i}_MARK " + "x".repeat(1_880), now - 10_000 + i) }
            val nudge = ChatMessage("nudge", Speaker.Felix, "what happened to the worksheet?", now - 2_000, kind = MessageKind.SlotEnd)
            val newest = ChatMessage("latest", Speaker.You, "please answer what I sent", now - 1_000)
            felix.stores.chat.update { it.copy(messages = waiting + nudge + newest) }
            felix.answers(felix.chatAnswer("ok"))
            felix.conversation.answerChat()
            val request = felix.server.takeRequest().body.readUtf8()
            assertFalse("PENDING_0_MARK" in request)
            val messages = felix.stores.chat.value.messages.associateBy { it.id }
            assertNull(messages.getValue("pending-0").answeredAt)
            assertTrue(messages.getValue("pending-0").stuck && messages.getValue("pending-0").note != null)
            assertNotNull(messages.getValue("latest").answeredAt)
        }
    }

    @Test
    fun `a tick he answers with lands on the step he was shown, not on one that took its place meanwhile, and says so`(): Unit = runBlocking {
        FelixHarness().use { felix ->
            val now = felix.clock.now()
            val task = OwnTaskRules.create(OwnTask(title = "Review exercise", steps = listOf(OwnStep("Original work", 20), OwnStep("Last work", 20))), "review", now)
            felix.stores.tasks.update { it.copy(tasks = listOf(task)) }
            // The first step replaced on its form while he answers.
            felix.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    runBlocking {
                        val draft = OwnTaskRules.draftOf(task)
                        felix.stores.tasks.update { it.copy(tasks = listOf(OwnTaskRules.edited(task, draft.copy(steps = listOf(OwnStep("Replacement work", 20), draft.steps[1]), fromForm = true)))) }
                    }
                    return felix.answer(felix.chatAnswer("original work done", actions = """[{"type":"tickStep","taskId":"${task.id}","stepIndex":0,"setting":"","value":""}]"""))
                }
            }
            felix.conversation.ask("I finished Original work")
            felix.settle()
            assertFalse(felix.stores.tasks.value.tasks.single().subSteps[0].done)
            val acts = felix.stores.chat.value.messages.last { it.from == Speaker.Felix }.acts
            assertTrue(acts.any { it.problem && "Original work" in it.line }, acts.toString())
        }
    }

    @Test
    fun `a step that moved is ticked where it is now`(): Unit = runBlocking {
        FelixHarness().use { felix ->
            val now = felix.clock.now()
            val task = OwnTaskRules.create(OwnTask(title = "Review exercise", steps = listOf(OwnStep("First", 20), OwnStep("Second", 20))), "review", now)
            felix.stores.tasks.update { it.copy(tasks = listOf(task)) }
            // A step put before it while he answers: his "Second" is now third.
            felix.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    runBlocking {
                        val draft = OwnTaskRules.draftOf(task)
                        felix.stores.tasks.update { it.copy(tasks = listOf(OwnTaskRules.edited(task, draft.copy(steps = listOf(draft.steps[0], OwnStep("Inserted", 20), draft.steps[1]), fromForm = true)))) }
                    }
                    return felix.answer(felix.chatAnswer("second done", actions = """[{"type":"tickStep","taskId":"${task.id}","stepIndex":1,"setting":"","value":""}]"""))
                }
            }
            felix.conversation.ask("I finished the second")
            felix.settle()
            assertEquals(listOf(false, false, true), felix.stores.tasks.value.tasks.single().subSteps.map { it.done })
        }
    }

    @Test
    fun `an old untick's Undo doesn't tick a step that took its place`(): Unit = runBlocking {
        FelixHarness().use { felix ->
            val now = felix.clock.now()
            val task = OwnTaskRules.create(OwnTask(title = "Review exercise", steps = listOf(OwnStep("Replacement work", 20), OwnStep("Last work", 20))), "review", now)
            // From before steps were named: its place alone, and its line naming the step that was there.
            val act = Act("To do again: Original work, of Review exercise", ChatAction(ChatAction.Type.UntickStep, task.id, 0))
            val message = ChatMessage("old-untick", Speaker.Felix, "do that again", now - 1_000, acts = listOf(act))
            felix.stores.tasks.update { it.copy(tasks = listOf(task)) }
            felix.stores.chat.update { it.copy(messages = listOf(message)) }
            assertFalse(ChatActs.canUndo(act, listOf(task), felix.stores.settings.value, listOf(message)))
            felix.conversation.undoAct(message.id, 0)
            assertFalse(felix.stores.tasks.value.tasks.single().subSteps[0].done)
        }
    }
}
