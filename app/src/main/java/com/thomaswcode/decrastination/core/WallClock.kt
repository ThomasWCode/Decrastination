package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The phone's own clock since it started ([elapsedMs]), which setting the date can't move; [boot]
 * tells one start from the next.
 */
@Serializable
data class Uptime(val boot: Int, val elapsedMs: Long) {
    /** How long since [earlier] by this clock, or null where it can't say (across a restart). */
    fun since(earlier: Uptime?): Long? =
        if (earlier != null && earlier.boot == boot && elapsedMs >= earlier.elapsedMs) elapsedMs - earlier.elapsedMs else null
}

/** The time and the time zone, injected so the planner, the policy and the tests agree on "now". */
interface WallClock {
    fun now(): Long

    /** Read each time: the zone can change while the app runs. */
    fun zone(): ZoneId

    /**
     * The uptime clock, for waits that setting the date forward mustn't shorten (a loosening's 24
     * hours, a session's minutes). Null where there's none.
     */
    fun uptime(): Uptime? = null

    fun zoned(at: Long = now()): ZonedDateTime = Instant.ofEpochMilli(at).atZone(zone())
}

object SystemWallClock : WallClock {
    override fun now(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** A clock that says what it's told, for the tests. */
class FixedClock(var time: Long, private val zone: ZoneId = ZoneId.of("Europe/London"), var up: Uptime? = null) : WallClock {
    override fun now(): Long = time
    override fun zone(): ZoneId = zone
    override fun uptime(): Uptime? = up

    companion object {
        fun at(iso: String, zone: ZoneId = ZoneId.of("Europe/London")): FixedClock =
            FixedClock(ZonedDateTime.parse(iso).toInstant().toEpochMilli(), zone)
    }
}
