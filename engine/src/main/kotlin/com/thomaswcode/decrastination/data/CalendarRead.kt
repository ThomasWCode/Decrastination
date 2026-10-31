package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * The phone's calendar as it last read it, told to the server (`calendar-read.json` there): the calendar is the phone's
 * to read, and the plan needs its busy time wherever it's made.
 */
@Serializable
data class CalendarRead(
    val events: List<CalendarEvent> = emptyList(),
    /** Your answers about events, as the phone keeps them. */
    val answers: Map<String, String> = emptyMap(),
    /** What was read, from when to when. */
    val from: Long = 0,
    val to: Long = 0,
    val version: Int = 1,
) {
    /** It judged at [now], as the phone judges it, your instructions about events over your answers. */
    fun time(applied: List<Instruction>, now: Long, zone: ZoneId): EventJudge.Time =
        EventJudge.time(events, answers + InstructionRules.eventAnswers(applied), zone, askUntil = now + CalendarTime.ASK_AHEAD_MS).copy(read = (from to to).takeIf { to > 0 })

    companion object {
        /** Its name among the server's stores. */
        const val NAME = "calendar-read.json"
    }
}
