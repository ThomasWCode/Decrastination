package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.core.Settings
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * When the app asks the Teams widget to sync on its own (Q21): on the first unlock after the
 * first-unlock time (16:45 by default), and every three hours (a setting), never while asleep
 * (22:30–07:00), a school day's hours as any other day's (D-105); and at an unlock in the hour and
 * a half after the morning briefing. Soon after Teams notifies you of assignments, too, within the
 * same hours (D-169). Any sync of Teams, yours or one of these, puts off every one of them but the
 * notice's for those three hours (D-169). Only while the phone is
 * unlocked and in use, which the focus service knows; every one is announced first by a banner
 * counting down 10 s, with Delay 5 and Skip today (D-11). Pure.
 */
object TeamsAutoSync {

    @Serializable
    data class State(
        /** When an automatic sync was last offered, whether it ran, was skipped or delayed. */
        val lastOfferedAt: Long? = null,
        /** "Delay 5" was tapped: offer again then. */
        val delayedUntil: Long? = null,
        /** The day the first-unlock sync was last offered. */
        val firstUnlockDay: String? = null,
        /** The morning briefing's: a sync is offered at an unlock before then (before school too). */
        val morningUntil: Long? = null,
        /** Offers in a row the widget didn't start (its sync service off, no answer). */
        val retries: Int = 0,
        /** The day "Skip today" was tapped: no automatic sync is offered till the next (D-11). */
        val skippedDay: String? = null,
    )

    enum class Trigger { FirstUnlock, Every3Hours, Delayed, Morning, Noticed }

    /** Minutes before an automatic sync the widget didn't start is offered again. */
    const val RETRY_MIN = 15

    /** Offers in a row that come to nothing before the next waits for its usual time. */
    const val RETRIES = 2

    fun due(
        now: Long,
        zone: ZoneId,
        settings: Settings,
        state: State,
        /** When the Teams widget last read Teams: a sync of any kind, or both its lists seen in full while you had Teams open. */
        teamsSyncedAt: Long?,
        /** The phone was unlocked just now. */
        unlocked: Boolean,
        /** When Teams last notified you of assignments, as the widget saw it, or null. */
        noticedAt: Long? = null,
    ): Trigger? {
        if (!settings.teamsAutoSync) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        // Skipped today: none till tomorrow, a delay or the morning's included (D-11).
        if (state.skippedDay == today) return null
        val every = settings.teamsSyncEveryMin * 60_000L
        // Any sync this recent, yours included, puts off the timed ones (D-169).
        val recent = teamsSyncedAt != null && now - teamsSyncedAt < every
        // Put off with "Delay 5": not before then, and then at once, though never at night.
        val delayed = state.delayedUntil
        if (delayed != null) return if (now >= delayed && !BlockPolicy.isQuiet(now, zone, settings)) Trigger.Delayed else null
        // The morning briefing's sync: at the next unlock in its hour and a half, school day or not.
        val morning = state.morningUntil
        if (unlocked && morning != null && now < morning && !recent && !BlockPolicy.isQuiet(now, zone, settings)) return Trigger.Morning
        if (!allowed(now, zone, settings)) return null
        // Teams notified you of assignments since it was last read, and nothing's been offered since: once a notice, so
        // a sync that fails isn't offered again and again (D-169).
        if (noticedAt != null && (teamsSyncedAt == null || noticedAt > teamsSyncedAt) && (state.lastOfferedAt == null || noticedAt > state.lastOfferedAt)) {
            return Trigger.Noticed
        }
        val minute = BlockPolicy.minuteOfDay(now, zone)
        if (unlocked && state.firstUnlockDay != today && minute >= settings.teamsFirstUnlockMin && !recent) return Trigger.FirstUnlock
        val notLately = state.lastOfferedAt == null || now - state.lastOfferedAt >= every
        return if (!recent && notLately) Trigger.Every3Hours else null
    }

    /**
     * Whether an offer made a moment ago still stands when its banner runs out: automatic syncs
     * still on, and not into quiet hours since.
     */
    fun stillWanted(now: Long, zone: ZoneId, settings: Settings): Boolean = settings.teamsAutoSync && !BlockPolicy.isQuiet(now, zone, settings)

    /** Within the hours automatic syncs may happen: outside quiet hours, a school day's as any other's (D-105). */
    fun allowed(now: Long, zone: ZoneId, settings: Settings): Boolean = !BlockPolicy.isQuiet(now, zone, settings)

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
            retries = 0,
        )
    }

    /**
     * The offer made at [now] came to nothing: the widget didn't start the sync (its sync service
     * off, no answer). As things were [before] it, so the day's first-unlock sync isn't used up,
     * and offered again in [RETRY_MIN] minutes, as a delayed one is. After [RETRIES] such offers in
     * a row, the last ([offered]) stands, and the next comes at its usual time.
     */
    fun retry(before: State, offered: State, now: Long): State =
        if (before.retries >= RETRIES) offered else before.copy(delayedUntil = now + RETRY_MIN * 60_000L, retries = before.retries + 1)

    fun delayed(state: State, now: Long, minutes: Int = 5): State = state.copy(delayedUntil = now + minutes * 60_000L)

    /**
     * "Skip today" tapped at [now]: no automatic sync offered again till the next day, counted as
     * offered (as [offered] is) so the day's first-unlock sync is used up, and any delay or
     * morning's offer dropped (D-11). A tap on Refresh Teams still syncs.
     */
    fun skipped(state: State, now: Long, zone: ZoneId, settings: Settings): State =
        offered(state, now, zone, settings).copy(skippedDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString())
}
