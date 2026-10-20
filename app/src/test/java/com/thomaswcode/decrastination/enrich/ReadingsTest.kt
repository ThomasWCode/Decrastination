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
import com.thomaswcode.decrastination.data.ModelState
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.TasksMigration
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer

/**
 * The readings' service on temporary stores (docs/plan_v4, Stage 6d), Claude's API a stand-in that
 * counts what it's sent: a reading due while Claude can't be asked is the rules', one read again
 * isn't sent then, and a task whose call the API refused as asked is read by the rules without
 * asking Claude again. The rules and the prompts themselves are `RuleEnricherTest`'s, `ReadingPromptsTest`'s and `ReadingAnswersTest`'s.
 */
class ReadingsTest {
    private val dir: File = Files.createTempDirectory("readings").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T16:00"), LONDON)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings)
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

    private object NoAlerts : ModelGateway.Alerts {
        override fun keyProblem(problem: KeyProblem): Boolean = false
        override fun keyFixed() = Unit
        override fun dropped(task: TaskItem, why: String): Boolean = false
        override fun withdrawExcept(standing: Set<String>) = Unit
    }
}
