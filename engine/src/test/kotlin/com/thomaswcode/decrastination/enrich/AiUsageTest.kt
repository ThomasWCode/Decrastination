package com.thomaswcode.decrastination.enrich

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.yours
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiUsageTest {
    @Test
    fun `the model is held while off, for an hour after a failed call, and at its cap`() {
        val now = Fixtures.at("2026-10-10T12:00")
        val month = AiUsage.monthOf(now, LONDON)
        assertEquals(ModelHold.Off, ModelHold.of(false, AiUsage(month = month), now, LONDON, 200))
        val failed = AiUsage(month = month).failure("no connection", now - 10 * 60_000L)
        assertEquals(ModelHold.Resting, ModelHold.of(true, failed, now, LONDON, 200))
        assertNull(ModelHold.of(true, failed, now + ModelHold.REST_MS, LONDON, 200))
        assertEquals(ModelHold.Capped, ModelHold.of(true, AiUsage(month = month, spentUsd = 1000.0), now, LONDON, 200))
        assertNull(ModelHold.of(true, AiUsage(month = month), now, LONDON, 200))
    }

    @Test
    fun `a new month starts at nothing, and a call is refused that could pass the cap`() {
        val usage = AiUsage(month = "2026-09", spentUsd = 250.0)
        assertEquals(0.0, usage.forMonth("2026-10").spentUsd)
        assertTrue(AiUsage("2026-10", spentUsd = 10.0).allows(capUsd = 200))
        // $199.50 spent leaves no room under $200 for a call at its dearest.
        assertEquals(false, AiUsage("2026-10", spentUsd = 199.50).allows(capUsd = 200))
        // No cap, as by default: the prepaid account's credit is the limit.
        assertTrue(AiUsage("2026-10", spentUsd = 5_000.0).allows(capUsd = null))
        assertNull(ModelHold.of(true, AiUsage(month = "2026-10", spentUsd = 5_000.0), Fixtures.at("2026-10-10T12:00"), LONDON, null))
    }

    @Test
    fun `a call's dearest case covers the longest text any call sends`() {
        // Every character three tokens, the instructions' worth more, and all the output, at the
        // dearer rates of a fallback's older Opus. Every call's input is cut to the most it covers,
        // and an email's text is well within it.
        // The profile sent with every call counted too, at its most.
        // A photo check's three pictures among the rest (AI-17).
        val longest = Pricing.costUsd("claude-opus-4-8", input = 3L * (Pricing.MAX_INPUT_CHARS + Profile.MAX_BLOCK_CHARS) + 3 * 3_300 + 1_000, output = ClaudeEnricher.MAX_TOKENS)
        assertTrue(longest <= Pricing.WORST_CALL_USD, "$longest > ${Pricing.WORST_CALL_USD}")
        // And with its instructions written to the hour's cache, dearer than sent as input.
        val written = Pricing.costUsd("claude-opus-4-8", input = 3L * (Pricing.MAX_INPUT_CHARS + Profile.MAX_BLOCK_CHARS) + 3 * 3_300, output = ClaudeEnricher.MAX_TOKENS, cacheWriteHour = 1_000)
        assertTrue(written <= Pricing.WORST_CALL_USD, "$written > ${Pricing.WORST_CALL_USD}")
        assertEquals(1.35, Pricing.WORST_CALL_USD, 1e-9)
        assertTrue(ReadingPrompts.MAX_TEXT + 1_000 < Pricing.MAX_INPUT_CHARS)
    }

    @Test
    fun `a key problem is alerted once a stretch, once it can be seen, and ends with a call that works or a new key`() {
        val first = AiUsage("2026-10").failure("401", NOW, KeyProblem.Rejected)
        assertTrue(first.keyAlertDue)
        assertEquals(NOW, first.keyProblemSince)
        // Not shown (notifications off): the hourly retry finds it still due.
        val retry = first.failure("401", NOW + 3_600_000, KeyProblem.Rejected)
        assertTrue(retry.keyAlertDue)
        assertEquals(NOW, retry.keyProblemSince)
        // Shown: not again while it stands, nor for no network meanwhile.
        val shown = retry.alerted(KeyProblem.Rejected)
        assertFalse(shown.failure("401", NOW + 7_200_000, KeyProblem.Rejected).keyAlertDue)
        val offline = shown.failure("timeout", NOW + 7_200_000)
        assertEquals(KeyProblem.Rejected, offline.keyProblem)
        assertFalse(offline.keyAlertDue)
        // Another problem is another stretch, and another alert; one shown for the last doesn't count.
        val broke = offline.failure("400", NOW + 10_800_000, KeyProblem.NoCredit)
        assertTrue(broke.keyAlertDue)
        assertTrue(broke.alerted(KeyProblem.Rejected).keyAlertDue)
        // Over at a call that works, or with a new key; carried into a new month till then, as shown.
        assertNull(broke.record(0.01, refused = false, at = NOW + 14_400_000).keyProblem)
        assertNull(broke.newKey().keyProblem)
        assertNull(broke.newKey().lastError)
        val told = broke.alerted(KeyProblem.NoCredit)
        assertEquals(KeyProblem.NoCredit, told.forMonth("2026-11").keyProblem)
        assertFalse(told.forMonth("2026-11").keyAlertDue)
    }

    @Test
    fun `a dropped plan's warning is due till shown for the plan as it is`() {
        val task = TaskItem(id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "Calendar", detail = "x", kind = Kind.Admin, firstSeenAt = NOW, lastSeenAt = NOW)
        val dropped = task.copy(enrichment = Enrichment(inputHash = Enrichments.inputHash(task), by = "model", at = NOW, dropped = "31 blocks, past the 30 the app takes"))
        val standing = DroppedAlerts.standing(listOf(dropped))
        assertEquals(listOf("gmail:t1" to "31 blocks, past the 30 the app takes"), standing.map { it.first.id to it.second })
        // Not shown yet (notifications off when it was laid): due.
        assertEquals(1, DroppedAlerts.unshown(standing, emptyMap()).size)
        // Shown for this content: not again.
        assertEquals(0, DroppedAlerts.unshown(standing, mapOf("gmail:t1" to Enrichments.inputHash(task))).size)
        // Shown for an older content: this plan is another warning.
        assertEquals(1, DroppedAlerts.unshown(standing, mapOf("gmail:t1" to "older")).size)
        // Done, or its plan kept since, or said not to be a task, not shown anywhere: nothing standing.
        assertEquals(0, DroppedAlerts.standing(listOf(dropped.copy(status = Status.Done))).size)
        assertEquals(0, DroppedAlerts.standing(listOf(dropped.yours(notATask = true))).size)
        assertEquals(0, DroppedAlerts.standing(listOf(dropped.copy(enrichment = dropped.enrichment!!.copy(dropped = null)))).size)
    }

    @Test
    fun `a key or credit problem is told from any other failure`() {
        assertEquals(KeyProblem.Rejected, KeyProblem.of(401, "authentication_error", "invalid x-api-key"))
        assertEquals(KeyProblem.NotAllowed, KeyProblem.of(403, "permission_error", "not allowed"))
        assertEquals(KeyProblem.NoCredit, KeyProblem.of(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API."))
        assertEquals(KeyProblem.NoCredit, KeyProblem.of(402, "billing_error", null))
        assertNull(KeyProblem.of(400, "invalid_request_error", "max_tokens: too large"))
        assertNull(KeyProblem.of(529, "overloaded_error", "Overloaded"))
        assertNull(KeyProblem.of(IOException("no network")))
    }

    @Test
    fun `calls, refusals and failures are counted`() {
        val usage = AiUsage("2026-10").record(0.01, refused = false, at = NOW).record(0.02, refused = true, at = NOW).failure("timeout", NOW)
        assertEquals(2, usage.calls)
        assertEquals(1, usage.refused)
        assertEquals(1, usage.failed)
        assertEquals(0.03, usage.spentUsd, 1e-9)
        assertEquals("timeout", usage.lastError)
    }

    @Test
    fun `work over an hour is cut into even sittings by time, never by what's in it`() {
        val step = SubStep("Write the essay", 100)
        assertEquals(listOf("Write the essay" to 50, "Continue and finish: Write the essay" to 50), ReadingAnswers.fitParts(step).map { it.title to it.minutes })
        assertEquals(
            listOf("Write the essay" to 50, "Continue: Write the essay" to 50, "Continue and finish: Write the essay" to 50),
            ReadingAnswers.fitParts(step.copy(minutes = 150)).map { it.title to it.minutes },
        )
        // An hour or less is one sitting.
        assertEquals(listOf(step.copy(minutes = 60)), ReadingAnswers.fitParts(step.copy(minutes = 60)))
        assertEquals(listOf(31, 30), ReadingAnswers.fitParts(step.copy(minutes = 61)).map { it.minutes })
        // Free of the steps before it (D-109), its first part alone is: the rest follow it.
        assertEquals(listOf(true, false, false), ReadingAnswers.fitParts(step.copy(minutes = 150, anyOrder = true)).map { it.anyOrder })
    }

    @Test
    fun `prices are Opus 5 point 5's, or the dearer older Opus's a fallback may use`() {
        assertEquals(0.0108, Pricing.costUsd("claude-opus-5-5", input = 1_200, output = 300), 1e-9)
        // A web search (16 Oct) is a cent beside its tokens.
        assertEquals(0.0308, Pricing.costUsd("claude-opus-5-5", input = 1_200, output = 300, searches = 2), 1e-9)
        assertEquals(0.0135, Pricing.costUsd("claude-opus-4-8", input = 1_200, output = 300), 1e-9)
    }

    @Test
    fun `a write to the hour's cache is charged at the hour's rate, the five minutes' at theirs`() {
        // The price list (20 Oct): Opus 5.5 writes at $5 a million for five minutes and $8 for an hour, the older Opus at $6.25 and $10.
        assertEquals(0.005, Pricing.costUsd("claude-opus-5-5", input = 0, output = 0, cacheWrite = 1_000), 1e-12)
        assertEquals(0.008, Pricing.costUsd("claude-opus-5-5", input = 0, output = 0, cacheWriteHour = 1_000), 1e-12)
        assertEquals(0.00625, Pricing.costUsd("claude-opus-4-8", input = 0, output = 0, cacheWrite = 1_000), 1e-12)
        assertEquals(0.010, Pricing.costUsd("claude-opus-4-8", input = 0, output = 0, cacheWriteHour = 1_000), 1e-12)
        // An answer's writes as it breaks them down: the system prompt's for the hour, a web search's for five minutes.
        // 1 200 in, 300 out and 5 000 read: $0.0118; 4 000 written for the hour, $0.032; 500 for five minutes, $0.0025.
        val both = """"cache_creation":{"ephemeral_5m_input_tokens":500,"ephemeral_1h_input_tokens":4000}"""
        assertEquals(0.0463, Pricing.costUsd(answer(written = 4_500, breakdown = both)), 1e-9)
        // With no breakdown, every write is the hour's, as every system prompt is marked so (AC-01).
        assertEquals(0.0118 + 0.036, Pricing.costUsd(answer(written = 4_500)), 1e-9)
        // Nothing written, nothing charged for it.
        assertEquals(0.0118, Pricing.costUsd(answer(written = 0)), 1e-9)
    }

    /** An answer of Opus 5.5's: 1 200 tokens in, 300 out, 5 000 read from the cache and [written] written to it, as [breakdown] says. */
    private fun answer(written: Long, breakdown: String? = null): Message {
        val usage = """"usage":{"input_tokens":1200,"output_tokens":300,"cache_read_input_tokens":5000,"cache_creation_input_tokens":$written${breakdown?.let { ",$it" }.orEmpty()}}"""
        val json = """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":"end_turn","stop_sequence":null,$usage}"""
        return jsonMapper().readValue(json, Message::class.java)
    }
}
