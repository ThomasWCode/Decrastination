package com.thomaswcode.decrastination.data

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the nightly backups' folder lets go (AR-07, LR-08). */
class BackupFolderTest {
    @Test
    fun `the newest fourteen nightly backups are kept by the date in their names, a reset's copy never let go`() {
        val nights = (1..20).map { BackupFolder.nightlyName("2026-10-%02d".format(it)) }.shuffled(Random(7))
        val names = nights + "reset-2026-09-01.json" + "notes.txt"
        assertEquals((1..6).map { BackupFolder.nightlyName("2026-10-%02d".format(it)) }.toSet(), BackupFolder.toPrune(names).toSet())
        assertEquals(emptyList(), BackupFolder.toPrune(nights.take(14)))
    }
}
