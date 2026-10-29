package com.thomaswcode.decrastination.ui

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The task form's saves past its own composition (U-02): how each went, read once, by the form made again after a turn too. */
class FormSavesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `a save under way is waited for, kept or not, and read once`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        FormSaves.start(scope, "form-kept") { release.await(); true }
        // The form made again after a turn asks while it's still under way.
        val waiting = async { FormSaves.result("form-kept") }
        release.complete(Unit)
        assertEquals(true, waiting.await())
        assertNull(FormSaves.result("form-kept"), "read once, then let go")
        FormSaves.start(scope, "form-refused") { false }
        assertEquals(false, FormSaves.result("form-refused"))
    }

    @Test
    fun `a save that throws comes back not kept, and none under way is null`() = runBlocking {
        FormSaves.start(scope, "form-throws") { throw IOException("a full disk") }
        assertEquals(false, FormSaves.result("form-throws"))
        assertNull(FormSaves.result("form-never-started"))
    }
}
