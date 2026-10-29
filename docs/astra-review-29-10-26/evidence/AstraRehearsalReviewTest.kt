package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AstraRehearsalReviewTest {
    @Test
    fun `the release rehearsal must reject unreadable monthly chat and Gmail journal files`() {
        val root = Files.createTempDirectory("astra-rehearsal").toFile()
        val before = System.getProperty("rehearseFrom")
        try {
            val files = File(root, "files").apply { mkdirs() }
            Fixtures.file("stores/2026-10-28-4.8.0").listFiles()!!.forEach { it.copyTo(File(files, it.name)) }
            File(files, "chat-archive.json").delete()
            File(files, "chat-archive-2026-08.json").writeText("{not readable monthly chat")
            File(files, "gmail-writes.json").writeText("{not readable write journal")
            System.setProperty("rehearseFrom", root.absolutePath)
            assertFailsWith<AssertionError> {
                RehearsalTest().`the phone's stores read and migrated, what they hold counted before and after`()
            }
        } finally {
            if (before == null) System.clearProperty("rehearseFrom") else System.setProperty("rehearseFrom", before)
            root.deleteRecursively()
        }
    }
}
