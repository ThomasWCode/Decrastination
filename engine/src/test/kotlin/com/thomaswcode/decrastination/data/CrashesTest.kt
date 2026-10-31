package com.thomaswcode.decrastination.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The crash record (AR-06). */
class CrashesTest {
    @Test
    fun `each crash is written down with its stack, the last three kept, the newest shown`() {
        val dir = Files.createTempDirectory("crashes").toFile()
        try {
            assertNull(Crashes.last(dir))
            (1..4).forEach { i -> Crashes.record(dir, Thread.currentThread(), IllegalStateException("broke $i"), "3.0.0", at = 1_000L * i) }
            assertEquals(Crashes.KEEP, File(dir, "crashes").listFiles()!!.size)
            val last = Crashes.last(dir)!!
            assertTrue(last.startsWith("1970-01-01T00:00:04Z on ${Thread.currentThread().name}, version 3.0.0"), last)
            assertTrue("java.lang.IllegalStateException: broke 4" in last, last)
            assertTrue("CrashesTest" in last, last)
        } finally {
            dir.deleteRecursively()
        }
    }
}
