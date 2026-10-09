package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.data.Settings
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * When the app asks the Teams widget to sync on its own (Q21, decided 8 Oct): on the first unlock
 * after 16:45, and every three hours, never while asleep (22:30–07:00) and, on weekdays, not
 * before 16:45. Only while the phone is unlocked and in use, which the focus service knows; every
 * one is announced first by a banner counting down 10 s, with Cancel and Delay 5 min. Pure.
 */
object TeamsAutoSync {

    @Serializable
    data class State(
        /** When an automatic sync was last offered, whether it ran, was cancelled or delayed. */
        val lastOfferedAt: Long? = null,
        /** "Delay 5 min" was tapped: offer again then. */
        val delayedUntil: Long? = null,
        /** The day the first-unlock sync was last offered. */
        val firstUnlockDay: String? = null,
        /** The morning briefing's: a sync is offered at an unlock before then (before school too). */
        val morningUntil: Long? = null,
    )

    enum class Trigger { FirstUnlock, Every3Hours, Delayed, Morning }

    /** A sync of Teams this recent makes an automatic one pointless. */
    private const val RECENT_MS = 30 * 60_000L

    fun due(
        now: Long,
        zone: ZoneId,
        settings: Settings,
        state: State,
        /** When the Teams widget last read Teams successfully. */
        teamsSyncedAt: Long?,
        /** The phone was unlocked just now. */
        unlocked: Boolean,
    ): Trigger? {
        if (!settings.teamsAutoSync) return null
        val recent = teamsSyncedAt != null && now - teamsSyncedAt < RECENT_MS
        // Put off with "Delay 5 min": not before then, and then even outside the usual hours (a
        // morning offer's, before school), though never at night.
        val delayed = state.delayedUntil
        if (delayed != null) return if (now >= delayed && !BlockPolicy.isQuiet(now, zone, settings)) Trigger.Delayed else null
        // The morning briefing's sync: at the next unlock in its hour and a half, school day or not.
        val morning = state.morningUntil
        if (unlocked && morning != null && now < morning && !recent && !BlockPolicy.isQuiet(now, zone, settings)) return Trigger.Morning
        if (!allowed(now, zone, settings)) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val minute = BlockPolicy.minuteOfDay(now, zone)
        if (unlocked && state.firstUnlockDay != today && minute >= settings.teamsFirstUnlockMin && !recent) return Trigger.FirstUnlock
        val every = settings.teamsSyncEveryMin * 60_000L
        val stale = teamsSyncedAt == null || now - teamsSyncedAt >= every
        val notLately = state.lastOfferedAt == null || now - state.lastOfferedAt >= every
        return if (stale && notLately) Trigger.Every3Hours else null
    }

    /** Within the hours automatic syncs may happen. */
    fun allowed(now: Long, zone: ZoneId, settings: Settings): Boolean {
        if (BlockPolicy.isQuiet(now, zone, settings)) return false
        val day = Instant.ofEpochMilli(now).atZone(zone).dayOfWeek
        val weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
        return weekend || BlockPolicy.minuteOfDay(now, zone) >= settings.weekdayBlockFromMin
    }

    /**
     * What offering one at [now] does to the state, whatever the answer. One offered after the
     * first-unlock time counts as that day's first-unlock sync too: Teams has just been read.
     */
    fun offered(state: State, now: Long, zone: ZoneId, settings: Settings): State {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val afterFirstUnlockTime = BlockPolicy.minuteOfDay(now, zone) >= settings.teamsFirstUnlockMin
        return state.copy(
            lastOfferedAt = now,
            delayedUntil = null,
            firstUnlockDay = if (afterFirstUnlockTime) today else state.firstUnlockDay,
            morningUntil = null,
        )
    }

    fun delayed(state: State, now: Long, minutes: Int = 5): State = state.copy(delayedUntil = now + minutes * 60_000L)
}
