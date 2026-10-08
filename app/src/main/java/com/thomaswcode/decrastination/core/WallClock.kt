package com.thomaswcode.decrastination.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** The time and the time zone, injected so the planner, the policy and the tests agree on "now". */
interface WallClock {
    fun now(): Long

    /** Read each time: the zone can change while the app runs. */
    fun zone(): ZoneId

    fun zoned(at: Long = now()): ZonedDateTime = Instant.ofEpochMilli(at).atZone(zone())
}

object SystemWallClock : WallClock {
    override fun now(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** A clock that says what it's told, for the tests. */
class FixedClock(var time: Long, private val zone: ZoneId = ZoneId.of("Europe/London")) : WallClock {
    override fun now(): Long = time
    override fun zone(): ZoneId = zone

    companion object {
        fun at(iso: String, zone: ZoneId = ZoneId.of("Europe/London")): FixedClock =
            FixedClock(ZonedDateTime.parse(iso).toInstant().toEpochMilli(), zone)
    }
}
