package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.Settings
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * Whether a blocked app (or site) may be used now (docs/scheduler.md §4, with the hours decided
 * on 8 Oct). Pure: everything it depends on comes in through [Input].
 *
 * 1. A parent-approved unblock (the override) lets everything through until it ends.
 * 2. Sleep (22:30–07:00) is never blocked, and nor is the time before blocking starts: 16:45 on a
 *    school day, 10:00 on a weekend or a day off (BL-02).
 * 3. Otherwise: while today's work isn't done, blocking is strict (BL-01: today's pieces still to
 *    do, or a slot missed today that went to tomorrow, BL-11).
 * 4. With nothing left today, earned free time is spent while a blocked app is in front; with none
 *    left, it's blocked until more is earned.
 */
object BlockPolicy {

    data class Input(
        val now: Long,
        val zone: ZoneId,
        val settings: Settings,
        /** Today's work isn't done (BL-01): pieces still to do today, or a slot missed today that went to tomorrow (BL-11). */
        val pressure: Boolean,
        val creditLeftMs: Long,
        val overrideUntil: Long? = null,
        /** Testing from a PC (adb only): act as if within blocking hours until then. */
        val forceActiveUntil: Long? = null,
        /** A school day (BL-02): blocking starts at `weekdayBlockFromMin`; on a weekend or a day off, at `weekendBlockFromMin`. */
        val schoolDay: Boolean = true,
    )

    enum class Reason(val headline: String) {
        TodayUndone("Today's work isn't done"),
        NoFreeTime("No free time left today"),
        Quiet("Quiet hours"),
        BeforeBlock("Before blocking starts"),
        Override("Unblocked by a parent code"),
        FreeTime("Using earned free time"),
    }

    sealed interface Verdict {
        val reason: Reason

        data class Block(override val reason: Reason) : Verdict

        /** Allowed, and free time isn't being spent. */
        data class Allow(override val reason: Reason) : Verdict

        /** Allowed, spending earned free time while it's in front. */
        data object Spend : Verdict {
            override val reason = Reason.FreeTime
        }
    }

    fun decide(input: Input): Verdict {
        val now = input.now
        if (input.overrideUntil != null && now < input.overrideUntil) return Verdict.Allow(Reason.Override)
        val forced = input.forceActiveUntil != null && now < input.forceActiveUntil
        if (!forced) {
            if (isQuiet(now, input.zone, input.settings)) return Verdict.Allow(Reason.Quiet)
            if (isBeforeBlockStart(now, input.zone, input.settings, input.schoolDay)) return Verdict.Allow(Reason.BeforeBlock)
        }
        return when {
            input.pressure -> Verdict.Block(Reason.TodayUndone)
            input.creditLeftMs > 0 -> Verdict.Spend
            else -> Verdict.Block(Reason.NoFreeTime)
        }
    }

    fun isQuiet(now: Long, zone: ZoneId, settings: Settings): Boolean = minuteOfDay(now, zone) in settings.quietHours

    /**
     * Before blocking starts, outside sleep (BL-02): on a [schoolDay] at `weekdayBlockFromMin` (the
     * phone at school stays free, BL-09), on a weekend or a day off at `weekendBlockFromMin`.
     */
    fun isBeforeBlockStart(now: Long, zone: ZoneId, settings: Settings, schoolDay: Boolean): Boolean =
        !isQuiet(now, zone, settings) && minuteOfDay(now, zone) < blockFromMin(settings, schoolDay)

    /** When blocking starts on a day of its kind. */
    fun blockFromMin(settings: Settings, schoolDay: Boolean): Int = if (schoolDay) settings.weekdayBlockFromMin else settings.weekendBlockFromMin

    fun minuteOfDay(now: Long, zone: ZoneId): Int = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
}
