package com.thomaswcode.decrastination.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** A debug command's change made round the commands, only while this phone mirrors ([whileMirroring]). */
class WhileMirroringTest {
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
}
