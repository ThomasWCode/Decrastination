package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.MemoryTidyRecord
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.MemoryTidy
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.Pricing
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.protect.Protection
import java.nio.file.Files
import java.time.LocalDate
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * What's learned from the log, through the Learning service on temporary stores (docs/plan_v4/architecture.md §The rules):
 * the week's review from a fixed log, its change and its tidy put back, the week's summary from
 * its input (Claude's API a stand-in), and the week's check-in and a completion's answer on record.
 * The pure rules are `LearnTest`'s, `StatsTest`'s and `SummaryTest`'s.
 */
class LearningTest {
    private val dir = Files.createTempDirectory("learning").toFile()

    /** A Sunday evening, after the check-in's time. */
    private val clock = FixedClock(Fixtures.at("2026-10-18T21:00"), LONDON)
    private val stores = Stores(dir, Reverse, { clock.now() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer().apply { start() }

    /** Claude's key, while there is one. */
    @Volatile private var key: String? = null
    private val gateway = ModelGateway(stores.settings, { key }, stores.model, stores.tasks, clock, NoAlerts, scope) {}.also { it.endpoint = server.url("").toString().trimEnd('/') }
    private val focus = Focus(stores.tasks, stores.settings, stores.session, stores.learning, stores.protection, stores.log, clock) { state, s, now -> Planner.plan(Planner.Input(state.tasks, now, LONDON, s)) }
    private val protection = Protection(stores.settings, stores.protection, stores.device, stores.secrets, clock, scope, adminActive = { false }) {}

    /** The review's lines put in the chat, and the weeks whose summary was offered. */
    private val told: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val offered: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val learning = Learning(
        stores.log, stores.learning, stores.settings, stores.memory, stores.chat, stores.instructions, clock, gateway, focus, protection,
        resets = { 0 },
        profile = { _, _ -> Profile.Block.NONE },
        // By the tasks alone: what the chat's acts hold and the sources' reads are Conversation's.
        forgetExpired = { now -> stores.memory.update { Memories.expired(it, stores.tasks.value.tasks.associateBy { t -> t.id }, now) } },
        reviewed = { told += it },
        summaryReady = { offered += it },
    )

    private val week = Daily.checkInWeek(clock.now(), LONDON, stores.settings.value.checkInMin)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        server.shutdown()
        dir.deleteRecursively()
    }

    /** A piece of homework done this week, as the log keeps it: [estimate] minutes planned, [worked] worked. */
    private fun done(id: String, estimate: Int, worked: Int, at: String) = CompletionRecord(
        taskId = "teams:$id", title = id, source = Source.Teams, kind = Kind.Homework, className = "12Ma",
        estimateMin = estimate, workedMin = worked, firstSeenAt = Fixtures.at("2026-10-12T09:00"), doneAt = Fixtures.at(at),
    )

    @Test
    fun `the week's review from a fixed log with Claude off learns the calibration afresh, keeps the rules' note and the week's totals, and tells Felix`() = runBlocking {
        stores.log.update { it.copy(completions = listOf(done("a", 30, 45, "2026-10-14T18:00"), done("b", 40, 60, "2026-10-15T18:00"), done("c", 20, 30, "2026-10-16T18:00"))) }
        val learned = Calibrator.learn(stores.log.value, stores.learning.value.calibration, today = LocalDate.parse("2026-10-18"), zone = LONDON, defaultMargin = stores.settings.value.marginDays)
        learning.review(ifDue = false)
        assertEquals(learned.calibration, stores.learning.value.calibration)
        val review = stores.log.value.reviews.single()
        assertEquals(week, review.week)
        assertEquals("rules", review.by)
        assertEquals("Claude was off", review.why)
        assertEquals(listOf(review.lines.first()), told)
        assertTrue(stores.log.value.weeks.any { it.week == week }, "the week's totals kept")
        // Had its review: the evening's, if due, makes no second.
        learning.review(ifDue = true)
        assertEquals(1, stores.log.value.reviews.size)
    }

    @Test
    fun `a review of the week so far learns nothing, and Sunday's still comes and learns (D-123)`() = runBlocking {
        stores.log.update { it.copy(completions = listOf(done("a", 30, 45, "2026-10-13T18:00"), done("b", 40, 60, "2026-10-14T18:00"), done("c", 20, 30, "2026-10-14T19:00"))) }
        val before = stores.learning.value.calibration
        clock.time = Fixtures.at("2026-10-15T17:00")
        learning.review(ifDue = true)
        assertEquals(before, stores.learning.value.calibration)
        assertEquals(listOf(week), stores.log.value.reviews.map { it.week })
        clock.time = Fixtures.at("2026-10-18T21:00")
        learning.review(ifDue = true)
        assertEquals(2, stores.log.value.reviews.size)
        assertTrue(stores.learning.value.calibration != before, "Sunday's learned")
    }

    @Test
    fun `a review's change is put back as a change of yours, and its tidy undone, while each is as the review left it`() = runBlocking {
        stores.settings.update { it.copy(softMinPerDay = 90) }
        val change = ReviewInput.Change("softMinPerDay", 90, "made up")
        val review = WeeklyReview(clock.now(), listOf("A made-up note."), by = "rules", changes = listOf(change), settingsBefore = mapOf("softMinPerDay" to 60))
        val unknown = review.copy(at = review.at + 1, settingsBefore = emptyMap())
        stores.log.update { it.copy(reviews = listOf(review, unknown)) }
        assertEquals("${ReviewInput.label("softMinPerDay")} is back to ${ReviewInput.valueText("softMinPerDay", 60)}.", learning.undoReviewChange(review.at, "softMinPerDay"))
        assertEquals(60, stores.settings.value.softMinPerDay)
        assertEquals("It's been changed since: left as it is.", learning.undoReviewChange(review.at, "softMinPerDay"))
        assertEquals("What it was before isn't known.", learning.undoReviewChange(unknown.at, "softMinPerDay"))
        assertEquals("That review didn't change it.", learning.undoReviewChange(review.at, "maxMinPerDay"))
        assertEquals("That review has gone.", learning.undoReviewChange(5L, "softMinPerDay"))
        // The tidy: put back while what's remembered is as it left it.
        val before = listOf(Fact("school", "Takes the bus", 1L), Fact("school", "Takes the bus at 8", 2L))
        val after = listOf(Fact("school", "Takes the bus at 8", 3L))
        stores.memory.update { it.copy(facts = after) }
        val tidied = review.copy(at = review.at + 2, memoryTidy = MemoryTidyRecord(before, after))
        stores.log.update { it.copy(reviews = it.reviews + tidied) }
        assertEquals("What's remembered is back as it was before the tidy.", learning.undoTidy(tidied.at))
        assertEquals(before, stores.memory.value.facts)
        assertEquals("What's remembered has changed since: left as it is.", learning.undoTidy(tidied.at))
        assertEquals("That review tidied nothing.", learning.undoTidy(review.at))
    }

    @Test
    fun `a check-in's moment can't reach past now and trim what the log keeps`() = runBlocking {
        val kept = done("a", 30, 45, "2026-10-14T18:00")
        stores.log.update { it.copy(completions = listOf(kept)) }
        assertTrue(learning.recordCheckIn(CheckIn(week, clock.now(), 4, "", "", "", ""), clock.now() + 3_650L * 86_400_000L))
        assertEquals(listOf(kept), stores.log.value.completions)
    }

    @Test
    fun `the tidy lets go of what's run out first, and isn't asked for while what it may rewrite has room`() = runBlocking {
        key = "test-key"
        stores.settings.update { it.copy(aiEnabled = true, aiKeyActive = true) }
        val day = 24 * 3_600_000L
        // A long fact past its until, beside plain ones past their share of the room only while it's there.
        val gone = Fact("cold", "x".repeat(3_996), clock.now() - 2 * day, until = clock.now() - day)
        val plain = (1..6).map { Fact("topic $it", "x".repeat(300), clock.now() - (7 - it) * day) }
        assertTrue(MemoryTidy.due(plain + gone))
        assertFalse(MemoryTidy.due(plain))
        stores.memory.update { it.copy(facts = plain + gone) }
        // An answer waits, so a call made would tidy.
        answers("""{"facts":[{"topic":"all","text":"short"}]}""")
        assertNull(learning.tidyMemory(clock.now()))
        assertEquals(plain, stores.memory.value.facts)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the week's summary is written from its input after its review and kept with it, Felix offering it, and none while Claude's off`() = runBlocking {
        stores.log.update { it.copy(completions = listOf(done("a", 30, 45, "2026-10-14T18:00")), reviews = listOf(WeeklyReview(clock.now(), listOf("A made-up note."), by = "rules", week = week))) }
        assertNull(learning.writeSummary(week))
        assertEquals(emptyList(), offered)
        key = "test-key"
        stores.settings.update { it.copy(aiEnabled = true, aiKeyActive = true) }
        answers("""{"summary":"A made-up week: the essay done."}""")
        assertEquals("A made-up week: the essay done.", learning.writeSummary(week))
        assertEquals("A made-up week: the essay done.", stores.log.value.reviews.single().summary)
        assertEquals(listOf(week), offered)
        // What it was asked: the week as Summary.input has it, from the log, your changes and the chat.
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val asked = sent.getValue("messages").jsonArray.single().jsonObject.getValue("content")
        val words = (asked as? JsonPrimitive)?.content ?: asked.jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        val input = Summary.input(stores.log.value, stores.instructions.value.applied, week, LONDON, stores.chat.value.messages)
        assertEquals(input.take(Pricing.MAX_INPUT_CHARS), words)
    }

    @Test
    fun `the week's check-in is kept once, from wherever it's asked, and a completion's answer on its completion`() = runBlocking {
        val checkIn = CheckIn(week, clock.now(), 4, "made-up", "", "", "")
        assertTrue(learning.recordCheckIn(checkIn))
        assertFalse(learning.recordCheckIn(checkIn.copy(feel = 2)))
        assertEquals(listOf(checkIn), stores.log.value.checkIns)
        val record = done("a", 30, 45, "2026-10-14T18:00")
        stores.log.update { it.copy(completions = listOf(record)) }
        learning.recordAssessment(record.taskId, record.doneAt, Calibrator.HARDER, note = " took longer ")
        val answered = stores.log.value.completions.single()
        assertEquals(Calibrator.HARDER, answered.assessment)
        assertEquals("took longer", answered.note)
    }

    /** Claude's next answer from the stand-in: a message whose text is [text]. */
    private fun answers(text: String) {
        val body = JsonObject(
            mapOf(
                "id" to JsonPrimitive("msg_test"),
                "type" to JsonPrimitive("message"),
                "role" to JsonPrimitive("assistant"),
                "model" to JsonPrimitive("claude-opus-5-5"),
                "content" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))),
                "stop_reason" to JsonPrimitive("end_turn"),
                "stop_sequence" to JsonNull,
                "usage" to JsonObject(mapOf("input_tokens" to JsonPrimitive(1_200), "output_tokens" to JsonPrimitive(80))),
            ),
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()))
    }

    private object NoAlerts : ModelGateway.Alerts {
        override fun keyProblem(problem: KeyProblem): Boolean = false
        override fun keyFixed() = Unit
        override fun dropped(task: TaskItem, why: String): Boolean = false
        override fun withdrawExcept(standing: Set<String>) = Unit
    }

    /** A cipher for the secrets that only reverses the bytes: no keystore on a PC. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
}
