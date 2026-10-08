package com.thomaswcode.decrastination

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** The real snapshots in the repository's `fixtures/` (see its README). */
object Fixtures {
    val LONDON: ZoneId = ZoneId.of("Europe/London")

    fun file(name: String): File =
        listOf(File("../fixtures/$name"), File("fixtures/$name")).firstOrNull { it.exists() }
            ?: error("No fixture $name (run the tests from the repository or the app module)")

    fun text(name: String): String = file(name).readText()

    /** Epoch millis of a London local time, "2026-10-07T22:00". */
    fun at(local: String): Long = ZonedDateTime.of(java.time.LocalDateTime.parse(local), LONDON).toInstant().toEpochMilli()
}
