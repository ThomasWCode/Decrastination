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
 * 1. A focus session you started blocks throughout, whatever the hour.
 * 2. A parent-approved unblock (the override) lets everything through until it ends.
 * 3. Sleep (22:30–07:00) is never blocked, and nor are school hours: before 16:45 on a weekday.
 * 4. Otherwise: while anything is due today or tomorrow, blocking is strict.
 * 5. With nothing due soon, earned free time is spent while a blocked app is in front; with none
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
        val session: FocusSession? = null,
        val overrideUntil: Long? = null,
        /** Testing from a PC (adb only): act as if within blocking hours until then. */
        val forceActiveUntil: Long? = null,
    )

    enum class Reason(val headline: String) {
        Session("You're in a focus session"),
        DueSoon("Work is due today or tomorrow"),
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
        if (input.session != null && now < input.session.endsAt) return Verdict.Block(Reason.Session)
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

/** A focus session on one chunk: blocked apps stay blocked until it ends (docs/scheduler.md §4). */
@Serializable
data class FocusSession(
    val taskId: String,
    val label: String,
    /** The sub-step it works through, if the task has them; else the session adds to minutes worked. */
    val step: String?,
    val minutes: Int,
    val startedAt: Long,
    /** The uptime clock at the start, so setting the date forward can't finish it early. */
    val startedUptime: Uptime? = null,
) {
    val endsAt: Long get() = startedAt + minutes * 60_000L

    /** How long it has run: by the uptime clock where it can say (the same start of the phone), else the wall clock. */
    fun ran(now: Long, uptime: Uptime?): Long = uptime?.since(startedUptime) ?: (now - startedAt)

    /**
     * Its time is up. By the uptime clock where it can say, so setting the date either way neither
     * ends it early nor holds it; across a restart, by the wall clock.
     */
    fun isDue(now: Long, uptime: Uptime?): Boolean = ran(now, uptime) >= minutes * 60_000L - FINISH_SLACK_MS

    companion object {
        /** A session ended by its alarm a moment before its minutes are up still counts as finished. */
        const val FINISH_SLACK_MS = 5_000L
    }
}
