package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.ModelState
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.StepOrderMigration
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.TasksMigration
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * The readings' service on temporary stores (docs/plan_v4), Claude's API a stand-in that
 * counts what it's sent: a reading due while Claude can't be asked is the rules', one read again
 * isn't sent then, and a task whose call the API refused as asked is read by the rules without
 * asking Claude again. The rules and the prompts themselves are `RuleEnricherTest`'s, `ReadingPromptsTest`'s and `ReadingAnswersTest`'s.
 */
class ReadingsTest {
    private val dir: File = Files.createTempDirectory("readings").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T16:00"), LONDON)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS))
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME))
    private val model = JsonStore(File(dir, "model.json"), ModelState.serializer(), ::ModelState)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer().apply { start() }

    /** Claude's key, while there is one. */
    @Volatile private var key: String? = null
    private val gateway = ModelGateway(settings, { key }, model, tasks, clock, NoAlerts, scope) {}.also { it.endpoint = server.url("").toString().trimEnd('/') }
    private val readings = Readings(tasks, model, clock, scope, gateway, profile = { Profile.Block.NONE }, queueQuestion = { _, _, _ -> }, readDecks = {})

    private val essay = TaskItem(
        id = "teams:essay", source = Source.Teams, sourceId = "essay", title = "Essay", dueAt = Fixtures.at("2026-10-23T09:00"), kind = Kind.Homework,
        detail = "Write about a made-up topic, two pages.", firstSeenAt = clock.time, lastSeenAt = clock.time,
    )

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun task() = tasks.value.tasks.single()

    @Test
    fun `while Claude can't be asked, a reading due is the rules', and one read again on asking isn't sent`() = runBlocking {
        tasks.update { it.copy(tasks = listOf(essay)) }
        assertFalse(readings.enrichNow())
        assertEquals(RuleEnricher.BY, task().enrichment?.by)
        assertEquals(Readings.ReadAgain.NotNow, readings.readAgain(essay.id))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a task whose call the API refused as asked is read by the rules, Claude not asked again`() = runBlocking {
        key = "test-key"
        settings.update { it.copy(aiEnabled = true, aiKeyActive = true) }
        tasks.update { it.copy(tasks = listOf(essay)) }
        model.update { it.copy(enrichFailed = mapOf(essay.id to Enrichments.inputHash(essay))) }
        // Claude could be asked: only the refusal keeps the call from being sent.
        assertTrue(gateway.claudeKey() != null && gateway.available(), "Claude can be asked")
        readings.enrichNow()
        assertEquals(RuleEnricher.BY, task().enrichment?.by)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `two runs at once read an unchanged task once, the second finding it read`() = runBlocking {
        key = "test-key"
        settings.update { it.copy(aiEnabled = true, aiKeyActive = true) }
        tasks.update { it.copy(tasks = listOf(essay)) }
        // Each answer held till a second request comes, or two seconds: two runs reading it at once would both be sent.
        val both = CountDownLatch(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                both.countDown()
                both.await(2, TimeUnit.SECONDS)
                return reading()
            }
        }
        listOf(async(Dispatchers.Default) { readings.enrichNow() }, async(Dispatchers.Default) { readings.enrichNow() }).awaitAll()
        assertEquals(1, server.requestCount)
        assertEquals(1, model.value.aiUsage.calls)
        assertEquals(ClaudeEnricher.MODEL, task().enrichment?.by)
    }

    /** Claude's reading of the essay, as the API sends it: one step, nothing to ask. */
    private fun reading(): MockResponse {
        val text = """{"subSteps":[{"title":"Write the essay","minutes":30,"ankiSections":[],"from":null,"due":null,"where":"anywhere","anyOrder":false}],"effortMin":30,"ankiSections":[],"testDate":null,"question":null,"reason":"","summary":""}"""
        val body = JsonObject(
            mapOf(
                "id" to JsonPrimitive("msg_test"), "type" to JsonPrimitive("message"), "role" to JsonPrimitive("assistant"), "model" to JsonPrimitive(ClaudeEnricher.MODEL),
                "content" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))),
                "stop_reason" to JsonPrimitive("end_turn"), "stop_sequence" to JsonNull,
                "usage" to JsonObject(mapOf("input_tokens" to JsonPrimitive(1_200), "output_tokens" to JsonPrimitive(80))),
            ),
        )
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }

    private object NoAlerts : ModelGateway.Alerts {
        override fun keyProblem(problem: KeyProblem): Boolean = false
        override fun keyFixed() = Unit
        override fun dropped(task: TaskItem, why: String): Boolean = false
        override fun withdrawExcept(standing: Set<String>) = Unit
    }
}
