package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.core.ClientOptions
import com.anthropic.core.JsonValue
import com.anthropic.core.http.Headers
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.UnauthorizedException
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.NotSameMigration
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.ModelState
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.StepOrderMigration
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.SpeechMigration
import com.thomaswcode.decrastination.data.WeeklyMigration
import com.thomaswcode.decrastination.data.ZoomMigration
import com.thomaswcode.decrastination.data.TasksMigration
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/** Every call to Claude through the gateway (AC-04, AC-05, AC-07), on temporary stores, with a send of its own and alerts that keep a list. */
class ModelGatewayTest {
    private val dir: File = Files.createTempDirectory("gateway").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-14T18:00"), Fixtures.LONDON)
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), { Settings(aiEnabled = true, aiKeyActive = true) }, migrations = listOf(SettingsMigration.SETTINGS, ZoomMigration.SETTINGS, SpeechMigration.SETTINGS))
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME, NotSameMigration.RUNTIME))
    private val model = JsonStore(File(dir, "model.json"), ModelState.serializer(), ::ModelState)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS, WeeklyMigration.TASKS, NotSameMigration.TASKS))
    private var key: String? = "test-key"
    private val alerts = Alerts()
    private val retries = mutableListOf<Long>()
    private val job = SupervisorJob()
    private val gateway = ModelGateway(settings, { key }, model, tasks, clock, alerts, CoroutineScope(job + Dispatchers.Default)) { retries += it }

    private val chat = ModelCalls.OnRecord<String>(ModelEffort.Job.Chat, "A question") { ModelCalls.Usage(1_200, 80, 900, "end_turn") to "used" }

    private suspend fun ask(interactive: Boolean = false, task: TaskItem? = null, send: suspend (Long) -> String = { "an answer" }) =
        gateway.call(interactive, task, chat, cost = { 0.05 to false }, send = send)

    private val keyRejected = UnauthorizedException.builder().headers(Headers.builder().build())
        .body(JsonValue.from(mapOf("type" to "error", "error" to mapOf("type" to "authentication_error", "message" to "invalid x-api-key")))).build()

    @Test
    fun `a key's in use as the settings say, unless the gateway's told it's another's`() = runTest {
        settings.update { it.copy(aiKeyActive = false) }
        assertNull(gateway.claudeKey())
        val boxes = ModelGateway(settings, { key }, model, tasks, clock, alerts, CoroutineScope(job + Dispatchers.Default), keyInUse = { true }) {}
        assertEquals(key, boxes.claudeKey())
        settings.update { it.copy(aiEnabled = false) }
        assertNull(boxes.claudeKey(), "off is off, whoever's key it is")
    }

    @Test
    fun `off, nothing is sent and it says so`() = runTest {
        settings.update { it.copy(aiEnabled = false) }
        var sent = false
        assertEquals(Called.Held(ModelHold.Off), ask { sent = true; "" })
        assertFalse(sent)
        assertNull(gateway.claudeKey())
        assertNull(gateway.client(ModelEffort.Job.Chat))
    }

    @Test
    fun `a call that goes through is counted, its cost kept and put on record`() = runTest {
        val called = ask()
        assertEquals(Called.Answered("an answer"), called)
        assertEquals(0.05, model.value.aiUsage.spentUsd, 1e-9)
        assertEquals(1, model.value.aiUsage.calls)
        val record = model.value.calls.single()
        assertEquals(listOf("Chat", "A question", "medium", "used"), listOf(record.job, record.title, record.effort, record.outcome))
        assertEquals(listOf(1_200L, 80L, 900L), listOf(record.inTokens, record.outTokens, record.cacheRead))
        assertEquals(0.05, record.costUsd, 1e-9)
    }

    @Test
    fun `a failure rests the model by its kind, a background call held through it, one someone waits for asked`() = runTest {
        // No network: no rest, the next try waits for one.
        assertIs<Called.Failed>(ask { throw UnknownHostException("no network") })
        assertNull(model.value.aiUsage.restUntil)
        assertEquals("Network", model.value.aiUsage.lastFailureClass)
        // A failure of no known kind: a quarter of an hour's rest, and the readings asked again after it.
        assertIs<Called.Failed>(ask { throw IllegalStateException("something else") })
        assertEquals(clock.time + 15 * 60_000L, model.value.aiUsage.restUntil)
        assertEquals(listOf(15 * 60_000L + 60_000L), retries)
        assertEquals("failed:unknown", model.value.calls.last().outcome)
        assertEquals(ModelHold.Resting, gateway.hold(interactive = false))
        assertNull(gateway.hold(interactive = true))
        assertIs<Called.Held>(ask(interactive = false))
        assertEquals(Called.Answered("an answer"), ask(interactive = true))
        // It went through: the rest is over.
        assertNull(model.value.aiUsage.restUntil)
        assertTrue(gateway.available())
    }

    @Test
    fun `a key that's rejected is alerted once, holds even what someone waits for, and its alert goes once a call works`() = runTest {
        assertIs<Called.Failed>(ask { throw keyRejected })
        settle()
        assertEquals(KeyProblem.Rejected, model.value.aiUsage.keyProblem)
        assertEquals("Key", model.value.aiUsage.lastFailureClass)
        assertEquals(listOf(KeyProblem.Rejected), alerts.keyProblems)
        // The key is what failed: not even a call someone waits for goes through its rest.
        assertEquals(ModelHold.Resting, gateway.hold(interactive = true))
        // Again, after its rest: said once a stretch, not at each retry.
        clock.time += 2 * 60 * 60_000L
        assertIs<Called.Failed>(ask { throw keyRejected })
        assertEquals(listOf(KeyProblem.Rejected), alerts.keyProblems)
        clock.time += 2 * 60 * 60_000L
        assertEquals(Called.Answered("an answer"), ask())
        assertNull(model.value.aiUsage.keyProblem)
        assertEquals(1, alerts.fixed)
    }

    @Test
    fun `a new key put in isn't held by the last key's failure`() = runTest {
        assertIs<Called.Failed>(ask { throw keyRejected })
        settle()
        assertEquals(ModelHold.Resting, gateway.hold(interactive = true))
        gateway.newKey()
        assertNull(model.value.aiUsage.keyProblem)
        assertNull(gateway.hold(interactive = true))
    }

    @Test
    fun `a key problem that couldn't be shown is shown when the alerts are put right`() = runTest {
        alerts.showing = false
        assertIs<Called.Failed>(ask { throw keyRejected })
        assertTrue(alerts.keyProblems.isEmpty() && model.value.aiUsage.keyAlertDue)
        alerts.showing = true
        gateway.reconcileAlerts()
        settle()
        assertEquals(listOf(KeyProblem.Rejected), alerts.keyProblems)
        assertFalse(model.value.aiUsage.keyAlertDue)
    }

    @Test
    fun `the cap holds a call that could pass it, the calls in flight counted at their dearest`() = runTest {
        settings.update { it.copy(aiMonthlyCapUsd = 2) }
        model.update { it.copy(aiUsage = it.aiUsage.forMonth(AiUsage.monthOf(clock.time, clock.zone())).copy(spentUsd = 2.0 - Pricing.WORST_CALL_USD + 0.01)) }
        assertEquals(ModelHold.Capped, gateway.hold())
        assertEquals(Called.Held(ModelHold.Capped), ask(interactive = true))
        model.update { it.copy(aiUsage = it.aiUsage.copy(spentUsd = 0.0)) }
        // One in flight counts at its dearest: a second beside it would pass $2, so it waits; alone, it goes.
        var second: Called<String>? = null
        assertEquals(Called.Answered("an answer"), ask { second = ask(); "an answer" })
        assertEquals(Called.Held(ModelHold.Capped), second)
        assertEquals(Called.Answered("an answer"), ask())
    }

    @Test
    fun `a request the API won't take isn't sent again for that task till it changes`() = runTest {
        val task = TaskItem(id = "teams:a", source = Source.Teams, sourceId = "a", title = "A task", kind = Kind.Homework, firstSeenAt = clock.time, lastSeenAt = clock.time)
        val refused = BadRequestException.builder().headers(Headers.builder().build()).body(JsonValue.from(mapOf("type" to "error", "error" to mapOf("type" to "invalid_request_error", "message" to "no")))).build()
        assertIs<Called.Failed>(ask(task = task) { throw refused })
        assertEquals(mapOf("teams:a" to Enrichments.inputHash(task)), model.value.enrichFailed)
        assertEquals("BadRequest", model.value.aiUsage.lastFailureClass)
    }

    @Test
    fun `one client for every call of a key and an endpoint, with each job's timeout, and another when either changes`() {
        val chat = assertNotNull(gateway.client(ModelEffort.Job.Chat))
        val felix = assertNotNull(gateway.client(ModelEffort.Job.Felix))
        assertSame(chat.underlying(), felix.underlying(), "one client, its connections shared")
        assertEquals(Duration.ofMinutes(3), chat.options().timeout.request())
        assertEquals(Duration.ofMinutes(2), felix.options().timeout.request())
        assertEquals(Duration.ofMinutes(5), gateway.client(ModelEffort.Job.Review)!!.options().timeout.request())
        key = "another-key"
        val after = assertNotNull(gateway.client(ModelEffort.Job.Chat))
        assertNotSame(chat.underlying(), after.underlying())
        gateway.endpoint = "http://127.0.0.1:8089"
        val stand = assertNotNull(gateway.client(ModelEffort.Job.Chat))
        assertNotSame(after.underlying(), stand.underlying())
        assertEquals("http://127.0.0.1:8089", stand.options().baseUrl())
        assertSame(stand.underlying(), gateway.client(ModelEffort.Job.Summary)!!.underlying())
    }

    @Test
    fun `every job has its timeout`() {
        for (job in ModelEffort.Job.entries) assertTrue(ModelEffort.timeout(job) >= Duration.ofMinutes(2), job.name)
    }

    @Test
    fun `a dropped plan is said once for its content, and withdrawn once it goes`() = runTest {
        val read = TaskItem(id = "teams:b", source = Source.Teams, sourceId = "b", title = "A task", kind = Kind.Homework, firstSeenAt = clock.time, lastSeenAt = clock.time)
        val dropped = read.copy(enrichment = Enrichment(Enrichments.inputHash(read), "claude-opus-5-5", clock.time, dropped = "its steps didn't add up"))
        gateway.droppedAlerts(listOf(dropped))
        assertEquals(listOf("teams:b"), alerts.dropped)
        assertEquals(mapOf("teams:b" to Enrichments.inputHash(read)), model.value.droppedAlerted)
        gateway.droppedAlerts(listOf(dropped))
        assertEquals(listOf("teams:b"), alerts.dropped)
        gateway.droppedAlerts(listOf(dropped.copy(status = Status.Done)))
        assertEquals(emptySet(), alerts.standing)
        assertTrue(model.value.droppedAlerted.isEmpty())
    }

    /** The HTTP client under [this] (the SDK wraps it afresh for each job's options, its retries around it): its connections. */
    private fun AnthropicClient.underlying(): Any =
        ClientOptions::class.java.getDeclaredField("originalHttpClient").apply { isAccessible = true }.get(options())

    /** The reconciling and the stores' writes run on the gateway's scope: waited for. */
    private fun settle() = runBlocking { job.children.toList().joinAll() }

    private class Alerts : ModelGateway.Alerts {
        var showing = true
        val keyProblems = mutableListOf<KeyProblem>()
        var fixed = 0
        val dropped = mutableListOf<String>()
        var standing: Set<String>? = null

        override fun keyProblem(problem: KeyProblem): Boolean = showing.also { if (it) keyProblems += problem }
        override fun keyFixed() {
            fixed++
        }
        override fun dropped(task: TaskItem, why: String): Boolean = showing.also { if (it) dropped += task.id }
        override fun withdrawExcept(standing: Set<String>) {
            this.standing = standing
        }
    }
}
