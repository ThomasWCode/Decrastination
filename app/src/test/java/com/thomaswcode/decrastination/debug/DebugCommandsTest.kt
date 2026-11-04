package com.thomaswcode.decrastination.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The debug commands' own rules: a change made round the commands only while mirroring ([whileMirroring]), and what follows a `chat-ask` ([afterAsk]). */
class DebugCommandsTest {
    @Test
    fun `while the phone mirrors, the change is made`() = runBlocking<Unit> {
        var made = 0
        whileMirroring(sendsCommands = false, refused = "refused") { made++ }
        assertEquals(1, made)
    }

    @Test
    fun `once it sends commands the change isn't made, whether there's something to say or not`() = runBlocking<Unit> {
        var made = 0
        whileMirroring(sendsCommands = true, refused = "refused") { made++ }
        whileMirroring(sendsCommands = true, refused = null) { made++ }
        assertEquals(0, made)
    }

    @Test
    fun `a refusal names its command and what to use instead`() {
        val said = alone("forget-task", "own-delete")
        assertTrue(said.startsWith("forget-task: not while this phone keeps in step by commands") && said.endsWith(": own-delete"), said)
    }

    @Test
    fun `a chat-ask is answered here only while its message is the newest still waiting`() {
        assertEquals(AfterAsk.AnswerHere, afterAsk(sendsCommands = false, unanswered = "sent", id = "sent"))
        // Taken as an answer, it comes answered: an older message still waiting isn't this one's to have answered.
        assertEquals(AfterAsk.TakenAsAnswer, afterAsk(sendsCommands = false, unanswered = "older", id = "sent"))
        assertEquals(AfterAsk.TakenAsAnswer, afterAsk(sendsCommands = false, unanswered = null, id = "sent"))
    }

    @Test
    fun `once the phone sends commands, the server answers a chat-ask whatever's waiting`() {
        assertEquals(AfterAsk.ByServer, afterAsk(sendsCommands = true, unanswered = "sent", id = "sent"))
        assertEquals(AfterAsk.ByServer, afterAsk(sendsCommands = true, unanswered = "older", id = "sent"))
    }
}
