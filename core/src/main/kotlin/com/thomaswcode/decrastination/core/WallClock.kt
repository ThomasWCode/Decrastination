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
    /**
     * How long since [earlier] by this clock, or null where it can't say: across a restart, or
     * where restarts can't be told apart (the restart count unread, [UNKNOWN_BOOT]).
     */
    fun since(earlier: Uptime?): Long? =
        if (earlier != null && boot != UNKNOWN_BOOT && earlier.boot == boot && elapsedMs >= earlier.elapsedMs) elapsedMs - earlier.elapsedMs else null

    /**
     * At least how long since [earlier]: [since] where it can say. Where restarts can't be told
     * apart, the clock's rise since then, which a restart in between can only have made smaller.
     */
    fun atLeastSince(earlier: Uptime?): Long? = since(earlier)
        ?: earlier?.takeIf { boot == UNKNOWN_BOOT && it.boot == UNKNOWN_BOOT && elapsedMs >= it.elapsedMs }?.let { elapsedMs - it.elapsedMs }

    companion object {
        /** The restart count couldn't be read. */
        const val UNKNOWN_BOOT = -1
    }
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
