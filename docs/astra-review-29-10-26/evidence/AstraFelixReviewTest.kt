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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AstraFelixReviewTest {
    @Test
    fun `a pending message omitted by the input budget must remain unanswered`() = runBlocking {
        FelixHarness().use { felix ->
            val now = felix.clock.now()
            val waiting = (0..5).map { index ->
                ChatMessage("pending-$index", Speaker.You, "PENDING_${index}_UNIQUE " + "x".repeat(1_880), now - 10_000 + index)
            }
            val nudge = ChatMessage("nudge", Speaker.Felix, "what happened to the worksheet?", now - 2_000, kind = MessageKind.SlotEnd)
            val newest = ChatMessage("latest", Speaker.You, "please answer what I sent", now - 1_000)
            felix.stores.chat.update { it.copy(messages = waiting + nudge + newest) }
            felix.answers(felix.chatAnswer("ok"))
            felix.conversation.answerChat()
            val request = felix.server.takeRequest().body.readUtf8()
            assertFalse("PENDING_0_UNIQUE" in request)
            val oldest = felix.stores.chat.value.messages.first { it.id == "pending-0" }
            assertNull(oldest.answeredAt, "An omitted message was marked answered, with note=${oldest.note}")
        }
    }

    @Test
    fun `undo of an old untick must not tick a replacement step at the same index`() = runBlocking {
        FelixHarness().use { felix ->
            val now = felix.clock.now()
            val task = OwnTaskRules.create(OwnTask(title = "Review exercise", steps = listOf(OwnStep("Replacement work", 20), OwnStep("Last work", 20))), "review", now)
            val act = Act("To do again: Original work, of Review exercise", ChatAction(ChatAction.Type.UntickStep, task.id, 0))
            val message = ChatMessage("old-untick", Speaker.Felix, "do that again", now - 1_000, acts = listOf(act))
            felix.stores.tasks.update { it.copy(tasks = listOf(task)) }
            felix.stores.chat.update { it.copy(messages = listOf(message)) }
            assertTrue(ChatActs.canUndo(act, listOf(task), felix.stores.settings.value, listOf(message)))
            felix.conversation.undoAct(message.id, 0)
            assertFalse(felix.stores.tasks.value.tasks.single().subSteps[0].done, "Undo ticked unrelated Replacement work")
        }
    }

    @Test
    fun `a returned tick must not apply to steps changed while the model answered`() = runBlocking {
        FelixHarness().use { felix ->
            val now = felix.clock.now()
            val task = OwnTaskRules.create(OwnTask(title = "Review exercise", steps = listOf(OwnStep("Original work", 20), OwnStep("Last work", 20))), "review", now)
            felix.stores.tasks.update { it.copy(tasks = listOf(task)) }
            felix.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    runBlocking {
                        val replacement = OwnTaskRules.edited(task, OwnTaskRules.draftOf(task).copy(steps = listOf(OwnStep("Replacement work", 20), OwnTaskRules.draftOf(task).steps[1]), fromForm = true))
                        felix.stores.tasks.update { it.copy(tasks = listOf(replacement)) }
                    }
                    return felix.answer(felix.chatAnswer("original work done", actions = """[{"type":"tickStep","taskId":"${task.id}","stepIndex":0,"setting":"","value":""}]"""))
                }
            }
            felix.conversation.ask("I finished Original work")
            felix.settle()
            assertFalse(felix.stores.tasks.value.tasks.single().subSteps[0].done, "Felix ticked Replacement work after seeing only Original work")
        }
    }
}
