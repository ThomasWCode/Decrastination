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
 * 2. Sleep (22:30–07:00) is never blocked, and nor are school hours: before 16:45 on a weekday.
 * 3. Otherwise: while anything is planned for today, blocking is strict (since PL-01 spreads later
 *    work over its window, today's bucket alone, not tomorrow's too).
 * 4. With nothing planned today, earned free time is spent while a blocked app is in front; with none
 *    left, it's blocked until more is earned.
 */
object BlockPolicy {

    data class Input(
        val now: Long,
        val zone: ZoneId,
        val settings: Settings,
        /** Something is in today's or tomorrow's bucket. */
        val pressure: Boolean,
        val creditLeftMs: Long,
        val overrideUntil: Long? = null,
        /** Testing from a PC (adb only): act as if within blocking hours until then. */
        val forceActiveUntil: Long? = null,
    )

    enum class Reason(val headline: String) {
        DueSoon("Work is planned for today"),
        NoFreeTime("No free time left today"),
        Quiet("Quiet hours"),
        SchoolHours("School hours"),
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
            if (isSchoolHours(now, input.zone, input.settings)) return Verdict.Allow(Reason.SchoolHours)
        }
        return when {
            input.pressure -> Verdict.Block(Reason.DueSoon)
            input.creditLeftMs > 0 -> Verdict.Spend
            else -> Verdict.Block(Reason.NoFreeTime)
        }
    }

    fun isQuiet(now: Long, zone: ZoneId, settings: Settings): Boolean = minuteOfDay(now, zone) in settings.quietHours

    /** A weekday before blocking starts, outside sleep. */
    fun isSchoolHours(now: Long, zone: ZoneId, settings: Settings): Boolean {
        val day = Instant.ofEpochMilli(now).atZone(zone).dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false
        return !isQuiet(now, zone, settings) && minuteOfDay(now, zone) < settings.weekdayBlockFromMin
    }

    fun minuteOfDay(now: Long, zone: ZoneId): Int = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
}
