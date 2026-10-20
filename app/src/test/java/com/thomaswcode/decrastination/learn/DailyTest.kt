package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.block.TeamsAutoSync
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.DailyRetry
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.enrich.ClaudeReviewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class DailyTest {
    // The box the briefing's tests were written for (30 since PL-12).
    private val settings = Settings(boxMin = 45)

    @Test
    fun `the briefing is at 7 on school days and half past 8 at weekends`() {
        // Thursday evening: Friday 07:00.
        assertEquals(Fixtures.at("2026-10-09T07:00"), Daily.nextBriefing(Fixtures.at("2026-10-08T21:00"), LONDON, settings, SchoolCalendar()))
        // Friday after the briefing: Saturday 08:30.
        assertEquals(Fixtures.at("2026-10-10T08:30"), Daily.nextBriefing(Fixtures.at("2026-10-09T07:00"), LONDON, settings, SchoolCalendar()))
        // Sunday 09:00: Monday 07:00.
        assertEquals(Fixtures.at("2026-10-12T07:00"), Daily.nextBriefing(Fixtures.at("2026-10-11T09:00"), LONDON, settings, SchoolCalendar()))
    }

    @Test
    fun `a weekday with no school has the weekends' briefing, and a half day the school days'`() {
        // Friday a day off: Thursday evening's next briefing is Friday 08:30, as a weekend's.
        val dayOff = SchoolCalendar(noSchool = listOf(NoSchool("2026-10-09", "2026-10-09")))
        assertEquals(Fixtures.at("2026-10-09T08:30"), Daily.nextBriefing(Fixtures.at("2026-10-08T21:00"), LONDON, settings, dayOff))
        // A week off: every day of it at 08:30, and school again on the Monday after at 07:00.
        val week = SchoolCalendar(noSchool = listOf(NoSchool("2026-10-12", "2026-10-16")))
        assertEquals(Fixtures.at("2026-10-14T08:30"), Daily.nextBriefing(Fixtures.at("2026-10-13T09:00"), LONDON, settings, week))
        assertEquals(Fixtures.at("2026-10-19T07:00"), Daily.nextBriefing(Fixtures.at("2026-10-18T09:00"), LONDON, settings, week))
        // A half day is still a school day.
        val half = SchoolCalendar(halfDays = listOf(HalfDay("2026-10-09", 12 * 60)))
        assertEquals(Fixtures.at("2026-10-09T07:00"), Daily.nextBriefing(Fixtures.at("2026-10-08T21:00"), LONDON, settings, half))
    }

    @Test
    fun `a late check-in's review is found on the Monday after, not moved a week`() {
        // Check-in at 23:30, review at 01:00: at 00:30 on Monday the alarm is still tonight's.
        assertEquals(Fixtures.at("2026-10-12T01:00"), Daily.nextSunday(Fixtures.at("2026-10-12T00:30"), LONDON, 23 * 60 + 30 + Daily.REVIEW_AFTER_MIN))
    }

    @Test
    fun `a check-in's answers belong to the week of the nearer Sunday`() {
        // Just after a late Sunday check-in, and on Monday afternoon: the week that ended.
        assertEquals("2026-10-05", Daily.checkInWeek(Fixtures.at("2026-10-12T00:30"), LONDON, 23 * 60 + 30))
        assertEquals("2026-10-05", Daily.checkInWeek(Fixtures.at("2026-10-12T16:00"), LONDON, 19 * 60 + 30))
        // On Saturday: the week ending tomorrow.
        assertEquals("2026-10-05", Daily.checkInWeek(Fixtures.at("2026-10-10T12:00"), LONDON, 19 * 60 + 30))
        assertEquals("2026-10-12", Daily.checkInWeek(Fixtures.at("2026-10-17T12:00"), LONDON, 19 * 60 + 30))
    }

    @Test
    fun `a review after midnight still finds Sunday's check-in`() {
        // Check-in at 23:30: the review's alarm goes at 01:00 on Monday.
        assertEquals(Fixtures.at("2026-10-11T23:30"), Daily.lastCheckIn(Fixtures.at("2026-10-12T01:00"), LONDON, 23 * 60 + 30))
        // Before Sunday's, the Sunday before's.
        assertEquals(Fixtures.at("2026-10-04T19:30"), Daily.lastCheckIn(Fixtures.at("2026-10-11T19:00"), LONDON, 19 * 60 + 30))
    }

    @Test
    fun `the check-in is on Sunday evening, and the review an hour and a half after`() {
        assertEquals(Fixtures.at("2026-10-11T19:30"), Daily.nextSunday(Fixtures.at("2026-10-09T17:00"), LONDON, settings.checkInMin))
        assertEquals(Fixtures.at("2026-10-18T19:30"), Daily.nextSunday(Fixtures.at("2026-10-11T19:30"), LONDON, settings.checkInMin))
        assertEquals(Fixtures.at("2026-10-11T21:00"), Daily.nextSunday(Fixtures.at("2026-10-09T17:00"), LONDON, settings.checkInMin + Daily.REVIEW_AFTER_MIN))
        assertEquals("2026-10-05", Daily.weekOf(Fixtures.at("2026-10-11T19:30"), LONDON))
        // The day's check-in time, which a review must come after to count as the week's.
        assertEquals(Fixtures.at("2026-10-11T19:30"), Daily.checkInOn(Fixtures.at("2026-10-11T21:00"), LONDON, settings.checkInMin))
    }

    @Test
    fun `the briefing says what today holds, first things first`() {
        val now = Fixtures.at("2026-10-09T07:00")
        val task = TaskItem(id = "teams:a", source = Source.Teams, sourceId = "a", title = "Moments Prep", dueAt = Fixtures.at("2026-10-09T21:00"), kind = Kind.Homework, sourceEffortMin = 40, firstSeenAt = now, lastSeenAt = now)
        val (title, lines) = Briefing.summary(Planner.plan(Planner.Input(listOf(task), now, LONDON, settings)))
        assertEquals("Today: 1 thing, 40 min", title)
        assertEquals(listOf("Moments Prep (due today) · 40 min"), lines)
        assertEquals("Nothing planned today", Briefing.summary(Planner.plan(Planner.Input(emptyList(), now, LONDON, settings))).first)
    }

    @Test
    fun `the morning's Teams sync is offered at an unlock before school, once`() {
        val now = Fixtures.at("2026-10-09T07:20")
        val state = TeamsAutoSync.State(morningUntil = Fixtures.at("2026-10-09T08:30"))
        assertEquals(TeamsAutoSync.Trigger.Morning, TeamsAutoSync.due(now, LONDON, settings, state, teamsSyncedAt = null, unlocked = true))
        // Not without an unlock, and not once offered.
        assertEquals(null, TeamsAutoSync.due(now, LONDON, settings, state, teamsSyncedAt = null, unlocked = false))
        val offered = TeamsAutoSync.offered(state, now, LONDON, settings)
        assertEquals(null, TeamsAutoSync.due(now + 60_000L, LONDON, settings, offered, teamsSyncedAt = null, unlocked = true))
        // Delayed: not before its five minutes, then even though the morning's window has gone.
        val delayed = state.copy(delayedUntil = now + 5 * 60_000L)
        assertEquals(null, TeamsAutoSync.due(now + 60_000L, LONDON, settings, delayed, teamsSyncedAt = null, unlocked = true))
        assertEquals(TeamsAutoSync.Trigger.Delayed, TeamsAutoSync.due(Fixtures.at("2026-10-09T08:45"), LONDON, settings, delayed, teamsSyncedAt = null, unlocked = false))
    }

    @Test
    fun `a review is once a week, by the week, wherever the check-in is moved`() {
        val reviews = listOf(WeeklyReview(Fixtures.at("2026-10-11T21:00"), listOf("x"), "rules", week = "2026-10-05"))
        // The check-in moved to 23:00 after the review: the same week, reviewed.
        assertTrue(Review.reviewed(reviews, "2026-10-05", Fixtures.at("2026-10-11T23:00")))
        assertEquals(false, Review.reviewed(reviews, "2026-10-12", Fixtures.at("2026-10-18T19:30")))
    }
}

class ReviewInputTest {
    private val now = Fixtures.at("2026-10-11T21:00")

    @Test
    fun `the model sees the learned margins, not only the settings`() {
        val calibration = Calibration(marginDays = mapOf(Kind.Homework to 3))
        val text = ReviewInput.describe(ActivityLog(), calibration, Settings(), now, LONDON, week = "2026-10-05")
        assertTrue("Learned margins (days, used instead of marginDays): Homework 3" in text, text)
    }

    @Test
    fun `the model reads the reviewed week's own days, even reviewed on the Monday after`() {
        val log = ActivityLog(
            completions = listOf(
                CompletionRecord("teams:a", "Monday morning's", Source.Teams, Kind.Homework, null, 20, 20, null, 0, Fixtures.at("2026-10-05T08:00")),
                CompletionRecord("teams:b", "The next Monday's", Source.Teams, Kind.Homework, null, 20, 20, null, 0, Fixtures.at("2026-10-12T09:00")),
            ),
        )
        val text = ReviewInput.describe(log, Calibration(), Settings(), Fixtures.at("2026-10-12T10:00"), LONDON, week = "2026-10-05")
        assertTrue("Monday morning's" in text, text)
        assertTrue("The next Monday's" !in text, text)
    }

    @Test
    fun `a week of long notes and answers stays within a call's size, the answers kept`() {
        val long = "x".repeat(5_000)
        val log = ActivityLog(
            completions = (1..200).map { CompletionRecord("teams:$it", long, Source.Teams, Kind.Homework, long, 20, 20, null, 0, Fixtures.at("2026-10-06T08:00") + it * 60_000L, note = long) },
            checkIns = listOf(CheckIn("2026-10-05", Fixtures.at("2026-10-11T19:40"), 3, long, long, long, long)),
        )
        val text = ReviewInput.describe(log, Calibration(), Settings(), Fixtures.at("2026-10-12T10:00"), LONDON, week = "2026-10-05")
        assertTrue(text.length <= com.thomaswcode.decrastination.enrich.Pricing.MAX_INPUT_CHARS, "${text.length}")
        assertTrue("Their Sunday answers" in text)
        assertTrue("and 160 more before those" in text)
    }

    @Test
    fun `a daily alarm that failed is tried again, three times, then left to its next time`() {
        var retries = emptyMap<String, DailyRetry>()
        repeat(Daily.RETRIES) { retries = Daily.failed(retries, Daily.ACTION_BRIEFING, Fixtures.at("2026-10-12T07:00")) }
        assertEquals(DailyRetry(Fixtures.at("2026-10-12T07:10"), Daily.RETRIES), retries[Daily.ACTION_BRIEFING])
        assertEquals(emptyMap(), Daily.failed(retries, Daily.ACTION_BRIEFING, Fixtures.at("2026-10-12T07:30")))
    }

    @Test
    fun `the week's slots are told kept and missed, by hour and day`() {
        // Laid in the reviewed week: kept on Thursday at 17:00, missed on Friday at 19:00; Monday's is the next week's.
        fun slot(start: String, outcome: SlotOutcome) = SlotRecord(
            date = start.take(10), key = "teams:a#-1#1", taskId = "teams:a", kind = Kind.Homework, label = "a", plannedMin = 30,
            start = Fixtures.at(start), end = Fixtures.at(start) + 30 * 60_000L, window = WindowKind.Evening, outcome = outcome,
            workedMin = if (outcome.kept) 30 else 0, at = Fixtures.at(start),
        )
        val log = ActivityLog(slots = listOf(slot("2026-10-08T17:00", SlotOutcome.Kept), slot("2026-10-09T19:00", SlotOutcome.Missed), slot("2026-10-12T17:00", SlotOutcome.Kept)))
        val text = ReviewInput.describe(log, Calibration(), Settings(), Fixtures.at("2026-10-12T10:00"), LONDON, week = "2026-10-05")
        assertTrue("Slots: 1 kept of 2, 30 min worked; 0 kept late, 1 missed." in text, text)
        assertTrue("Kept by hour: 17:00 1/1, 19:00 0/1" in text, text)
        assertTrue("Kept by day: Thu 1/1, Fri 0/1" in text, text)
    }

    @Test
    fun `a briefing that ran today isn't run again when its time moves later`() {
        val ran = Fixtures.at("2026-10-12T07:00")
        val later = Settings(briefingWeekdayMin = 8 * 60)
        assertEquals(Fixtures.at("2026-10-13T08:00"), Daily.nextBriefing(Daily.from(ran, Fixtures.at("2026-10-12T07:30"), LONDON), LONDON, later, SchoolCalendar()))
        // Run yesterday, today's still to be delivered keeps its time.
        assertEquals(Fixtures.at("2026-10-12T07:00"), Daily.nextBriefing(Daily.from(Fixtures.at("2026-10-11T08:30"), Fixtures.at("2026-10-12T07:05"), LONDON), LONDON, Settings(), SchoolCalendar()))
    }

    @Test
    fun `the model reads the reviewed week's check-in, not last week's answered late`() {
        // Last week's, answered on Monday morning; none this week.
        val log = ActivityLog(checkIns = listOf(CheckIn("2026-09-28", Fixtures.at("2026-10-05T08:00"), 2, "Maths", "Tired", "-", "-")))
        val text = ReviewInput.describe(log, Calibration(), Settings(), now, LONDON, week = "2026-10-05")
        assertTrue("the week felt" !in text, text)
    }

    @Test
    fun `the model's changes are said as they stand, in place or waiting`() {
        val changes = listOf(ReviewInput.Change("boxMin", 30, "shorter pieces got finished"), ReviewInput.Change("marginDays", 0, "always early"))
        // Armed, the margin's lowering waits: the margin is still 1.
        val after = Settings(boxMin = 30, marginDays = 1)
        assertEquals(
            listOf("Changed: boxMin to 30 (shorter pieces got finished)", "Waiting 24 hours, as it loosens blocking: marginDays to 0 (always early)"),
            Review.changeLines(changes, after),
        )
    }

    @Test
    fun `the week is described with its work, sessions, answers and settings`() {
        val log = ActivityLog(
            completions = listOf(CompletionRecord("teams:a", "Moments Prep", Source.Teams, Kind.Homework, "12.1 Physics", 40, 55, Fixtures.at("2026-10-09T21:00"), 0, Fixtures.at("2026-10-09T20:00"), "harder", "the diagrams")),
            checkIns = listOf(CheckIn("2026-10-05", now - 60_000L, 3, "German", "Football", "Less on Fridays", "Before tea")),
        )
        val text = ReviewInput.describe(log, Calibration(), Settings(), now, LONDON, week = "2026-10-05")
        assertTrue("- Moments Prep [Homework, 12.1 Physics]: due Fri 9 Oct 21:00, done Fri 9 Oct 20:00, estimate 40 min, timed 55 min. felt harder (\"the diagrams\")" in text, text)
        assertTrue("the week felt 3/5" in text, text)
        assertTrue("Settings: boxMin 30, marginDays 1, softMinPerDay 60, workMinPerFreeMin 3, weekdayHoursStart 1005, weekdayHoursEnd 1320." in text, text)
        // Done by a date the app or Claude set: a plan, said to be one.
        val planned = log.copy(completions = log.completions.map { it.copy(hardDeadline = false) })
        assertTrue("Moments Prep [Homework, 12.1 Physics]: due Fri 9 Oct 21:00 (a plan, not a deadline), done Fri 9 Oct 20:00" in ReviewInput.describe(planned, Calibration(), Settings(), now, LONDON, week = "2026-10-05"))
    }

    @Test
    fun `only the settings the review may change move, and only within their bounds`() {
        val changes = listOf(
            ReviewInput.Change("softMinPerDay", 45, "less undated work got done"),
            ReviewInput.Change("marginDays", 9, "too far"),
            ReviewInput.Change("armed", 0, "not allowed"),
            // A piece's length is yours since 17 Oct: the review leaves it.
            ReviewInput.Change("boxMin", 25, "shorter pieces got finished"),
        )
        val applied = ReviewInput.apply(Settings(boxMin = 60), changes)
        assertEquals(45, applied.softMinPerDay)
        assertEquals(1, applied.marginDays)
        assertEquals(Settings(boxMin = 60).copy(softMinPerDay = 45), applied)
        // A school day's hours, within an hour of theirs (AI-18).
        val hours = ReviewInput.apply(Settings(), listOf(ReviewInput.Change("weekdayHoursStart", Settings().weekdayHours.startMin - 45, "earlier starts went well"), ReviewInput.Change("weekdayHoursEnd", Settings().weekdayHours.endMin + 90, "too far")))
        assertEquals(Settings().weekdayHours.copy(startMin = Settings().weekdayHours.startMin - 45), hours.weekdayHours)
        // Two ends each within reach that would meet or cross: neither moves.
        val short = Settings().copy(weekdayHours = com.thomaswcode.decrastination.core.Window(17 * 60, 18 * 60))
        assertEquals(short.weekdayHours, ReviewInput.apply(short, listOf(ReviewInput.Change("weekdayHoursStart", 17 * 60 + 50, "later"), ReviewInput.Change("weekdayHoursEnd", 17 * 60 + 10, "earlier"))).weekdayHours)
        assertEquals(com.thomaswcode.decrastination.core.Window(17 * 60 + 30, 18 * 60), ReviewInput.apply(short, listOf(ReviewInput.Change("weekdayHoursStart", 17 * 60 + 30, "later"))).weekdayHours)
        assertEquals(false, ReviewInput.allowed(ReviewInput.Change("weekdayHoursEnd", Settings().weekdayHours.endMin + 90, "x"), Settings()))
        assertEquals("weekdayHours", ReviewInput.fieldOf("weekdayHoursEnd"))
        // Put back as it was, whatever the bounds (LR-01), and said in words.
        assertEquals(90, ReviewInput.set(Settings(), "boxMin", 90).boxMin)
        assertEquals("A piece's length 45 min → 30 min (shorter pieces got finished)", ReviewInput.changeText(ReviewInput.Change("boxMin", 30, "shorter pieces got finished"), 45))
        assertEquals("A school day's start 16:45 → 16:00 (earlier starts went well)", ReviewInput.changeText(ReviewInput.Change("weekdayHoursStart", 960, "earlier starts went well"), 1005))
    }

    @Test
    fun `the review reads last week's review and its changes, the days, the windows, Felix and the subjects`() {
        val last = com.thomaswcode.decrastination.data.WeeklyReview(
            Fixtures.at("2026-10-04T19:00"), listOf("Shorter pieces this week."), by = "claude", week = "2026-09-28",
            changes = listOf(ReviewInput.Change("boxMin", 30, "shorter pieces got finished")), settingsBefore = mapOf("boxMin" to 45),
        )
        val slot = SlotRecord(
            date = "2026-10-06", key = "teams:a#0", taskId = "teams:a", kind = Kind.Homework, className = "12.1 Physics", label = "a", plannedMin = 30,
            start = Fixtures.at("2026-10-06T17:00"), end = Fixtures.at("2026-10-06T17:30"), window = com.thomaswcode.decrastination.core.WindowKind.Evening,
            outcome = SlotOutcome.Kept, workedMin = 30, earned = true, at = Fixtures.at("2026-10-06T17:25"),
        )
        // His message in the chat about the slot, and your reply to it.
        val felix = listOf(
            ChatMessage("f1", Speaker.Felix, "Ten minutes in: are you starting?", Fixtures.at("2026-10-06T17:10"), kind = MessageKind.SlotStart, pieceKey = "teams:a#0"),
            ChatMessage("y1", Speaker.You, "yes", Fixtures.at("2026-10-06T17:12"), replyTo = "f1", answeredAt = Fixtures.at("2026-10-06T17:12")),
        )
        val day = com.thomaswcode.decrastination.learn.DayRecord("2026-10-06", plannedMin = 60, chunks = emptyList(), doneMin = 30, full = false, kind = com.thomaswcode.decrastination.core.DayKind.Weekday)
        val log = ActivityLog(reviews = listOf(last), slots = listOf(slot), days = listOf(day))
        val text = ReviewInput.describe(log, Calibration(), Settings(boxMin = 30, classMap = mapOf("12.1 Physics" to "Physics")), Fixtures.at("2026-10-11T19:00"), LONDON, week = "2026-10-05", messages = felix)
        assertTrue("Last week's review said: Shorter pieces this week." in text, text)
        assertTrue("It changed: boxMin 45 to 30 (shorter pieces got finished), now 30" in text, text)
        assertTrue("By window: Evening 1 kept, 0 missed, 0 moved" in text, text)
        assertTrue("Pieces that earned free time: 1." in text, text)
        assertTrue("- Tue 6 Oct (Weekday evenings): planned 60 min, done 30 min, not all of it" in text, text)
        assertTrue("Felix: 1 messages, 1 replied to; of the 1 about a slot, 1 saw it kept after." in text, text)
        assertTrue("- Physics: 30 min in 1 pieces" in text, text)
    }
}

class ClaudeReviewerTest {
    @Test
    fun `the review asks for the note and bounded changes, and reads them`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val text = """{"note":["German took longer than planned."," "],"changes":[{"setting":"boxMin","value":30,"why":"shorter pieces got done"}]}"""
            val body = JsonObject(
                mapOf(
                    "id" to JsonPrimitive("msg"), "type" to JsonPrimitive("message"), "role" to JsonPrimitive("assistant"), "model" to JsonPrimitive("claude-opus-5-5"),
                    "content" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))),
                    "stop_reason" to JsonPrimitive("end_turn"), "stop_sequence" to JsonNull,
                    "usage" to JsonObject(mapOf("input_tokens" to JsonPrimitive(3_000), "output_tokens" to JsonPrimitive(500))),
                ),
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()))
            val result = ClaudeReviewer(com.thomaswcode.decrastination.enrich.ModelGateway.build("test-key", server.url("").toString().trimEnd('/'))).review("Finished this week: ...")
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("high", request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals("default", request["fallbacks"]!!.jsonPrimitive.content)
            assertEquals(listOf("German took longer than planned."), result.answer!!.note)
            assertEquals(30, result.answer!!.changes.single().value)
            assertEquals(0.022, result.costUsd, 1e-9)
        } finally {
            server.shutdown()
        }
    }
}
