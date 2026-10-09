package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.block.TeamsAutoSync
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.enrich.ClaudeReviewer
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyTest {
    private val settings = Settings()

    @Test
    fun `the briefing is at 7 on school days and half past 8 at weekends`() {
        // Thursday evening: Friday 07:00.
        assertEquals(Fixtures.at("2026-10-09T07:00"), Daily.nextBriefing(Fixtures.at("2026-10-08T21:00"), LONDON, settings))
        // Friday after the briefing: Saturday 08:30.
        assertEquals(Fixtures.at("2026-10-10T08:30"), Daily.nextBriefing(Fixtures.at("2026-10-09T07:00"), LONDON, settings))
        // Sunday 09:00: Monday 07:00.
        assertEquals(Fixtures.at("2026-10-12T07:00"), Daily.nextBriefing(Fixtures.at("2026-10-11T09:00"), LONDON, settings))
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
        val task = TaskItem(id = "teams:a", source = Source.Teams, sourceId = "a", title = "Statics Prep", dueAt = Fixtures.at("2026-10-09T21:00"), kind = Kind.Homework, sourceEffortMin = 40, firstSeenAt = now, lastSeenAt = now)
        val (title, lines) = Briefing.summary(Planner.plan(Planner.Input(listOf(task), now, LONDON, settings)))
        assertEquals("Today: 1 thing, 40 min", title)
        assertEquals(listOf("Statics Prep (due today) · 40 min"), lines)
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
    }
}

class ReviewInputTest {
    private val now = Fixtures.at("2026-10-11T21:00")

    @Test
    fun `the model sees the learned margins and boxes, not only the settings`() {
        val calibration = Calibration(marginDays = mapOf(Kind.Homework to 3), boxMin = mapOf(Kind.Homework to 25))
        val text = ReviewInput.describe(ActivityLog(), calibration, Settings(), now, LONDON)
        assertTrue("Learned margins (days, used instead of marginDays): Homework 3" in text, text)
        assertTrue("Learned box lengths (minutes, used instead of boxMin): Homework 25" in text, text)
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
            completions = listOf(CompletionRecord("teams:a", "Statics Prep", Source.Teams, Kind.Homework, "12.1 Physics", 40, 55, Fixtures.at("2026-10-09T21:00"), 0, Fixtures.at("2026-10-09T20:00"), "harder", "the diagrams")),
            checkIns = listOf(CheckIn("2026-10-05", now - 60_000L, 3, "German", "Football", "Less on Fridays", "Before tea")),
        )
        val text = ReviewInput.describe(log, Calibration(), Settings(), now, LONDON)
        assertTrue("- Statics Prep [Homework, 12.1 Physics]: due Fri 9 Oct 21:00, done Fri 9 Oct 20:00, estimate 40 min, timed 55 min. felt harder (\"the diagrams\")" in text, text)
        assertTrue("the week felt 3/5" in text, text)
        assertTrue("Settings: boxMin 45, marginDays 1, softMinPerDay 60, workMinPerFreeMin 3." in text, text)
    }

    @Test
    fun `only the settings the review may change move, and only within their bounds`() {
        val changes = listOf(
            ReviewInput.Change("boxMin", 30, "shorter pieces got finished"),
            ReviewInput.Change("marginDays", 9, "too far"),
            ReviewInput.Change("armed", 0, "not allowed"),
        )
        val applied = ReviewInput.apply(Settings(), changes)
        assertEquals(30, applied.boxMin)
        assertEquals(1, applied.marginDays)
        assertEquals(Settings().copy(boxMin = 30), applied)
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
            val result = ClaudeReviewer("test-key", endpoint = server.url("").toString().trimEnd('/')).review("Finished this week: ...")
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
