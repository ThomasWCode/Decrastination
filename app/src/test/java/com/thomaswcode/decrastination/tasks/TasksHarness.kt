package com.thomaswcode.decrastination.tasks

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.plan.Planning
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Your instructions' and your own tasks' services on temporary stores (docs/plan_v4), as
 * AppGraph makes them, with the real focus and plan; Claude is off (no key), or, with [claude], answered
 * by a stand-in for Claude's API ([answers]); and what Android does (the calendar read again, the widget,
 * a finish's completions) and what's handed to Felix are counted.
 */
class TasksHarness(start: String = "2026-10-19T16:00", private val claude: Boolean = false) : AutoCloseable {
    private val dir = Files.createTempDirectory("tasks").toFile()
    val clock = FixedClock(Fixtures.at(start), LONDON)
    val stores = Stores(dir, Reverse, { clock.now() })
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Default)
    /** The stand-in for Claude's API, started only with [claude]. */
    val server = MockWebServer().apply { if (claude) start() }
    private val gateway = ModelGateway(stores.settings, { "test-key".takeIf { claude } }, stores.model, stores.tasks, clock, NoAlerts, scope) {}
        .also { if (claude) it.endpoint = server.url("").toString().trimEnd('/') }
    val focus: Focus = Focus(stores.tasks, stores.settings, stores.session, stores.learning, stores.protection, stores.log, clock) { state, s, now -> planning.plan(state, s, now) }
    val planning: Planning = Planning(
        stores.tasks, stores.settings, stores.session, stores.learning, stores.model, stores.log, stores.instructions, stores.school, clock, scope, gateway,
        calendarTime = { NO_CALENDAR },
        calendarChanges = emptyFlow<Unit>(),
        sweep = {},
        hooks = emptyList(),
        profile = { Profile.Block.NONE },
        draftAhead = { _, _ -> },
        onFailure = { throw it },
    )

    /** How often the calendar was read again, the widget drawn, and a finish's completions settled. */
    val rereads = AtomicInteger()
    val redraws = AtomicInteger()
    val settled = AtomicInteger()

    /** Your replies to his questions, read, as they're handed to Felix: each's words, whether it answers his question, and whether a change of it was made. */
    val answersRead: MutableList<Triple<String, Boolean, Boolean>> = Collections.synchronizedList(mutableListOf())

    val instructions = Instructions(
        stores.instructions, stores.tasks, stores.settings, stores.session, stores.school, stores.chat, clock, scope, gateway, focus, planning,
        calendarTime = { NO_CALENDAR },
        profile = { Profile.Block.NONE },
        reread = { rereads.incrementAndGet() },
        redraw = { redraws.incrementAndGet() },
        answerRead = { instruction, answers, made -> answersRead += Triple(instruction.text, answers, made) },
        question = { QUESTION },
    )

    init {
        // Claude on, with its key in use: the instructions' readings go to the stand-in.
        if (claude) runBlocking { stores.settings.update { it.copy(aiEnabled = true, aiKeyActive = true) } }
    }

    /** Claude's next answer from the stand-in: a message whose text is [text], the JSON a reader parses, sent [delayMs] late. */
    fun answers(text: String, delayMs: Long = 0) {
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
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()).setHeadersDelay(delayMs, TimeUnit.MILLISECONDS))
    }
    val ownTasks = OwnTasks(stores.tasks, stores.instructions, stores.chat, clock, scope) { settled.incrementAndGet() }

    /** A source's task, made up: Teams homework due on [due], taking [minutes]. */
    fun teams(id: String, due: Long? = Fixtures.at("2026-10-23T09:00"), minutes: Int = 60) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = due, kind = Kind.Homework,
        sourceEffortMin = minutes, firstSeenAt = clock.time, lastSeenAt = clock.time,
    )

    /** [condition], once the background work it waits on has done it (5 s at most). */
    fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "never: $what" }
            Thread.sleep(10)
        }
    }

    override fun close() {
        job.cancel()
        if (claude) server.shutdown()
        dir.deleteRecursively()
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

    companion object {
        /** No calendar: nothing busy, no day's load, no event. */
        private val NO_CALENDAR = EventJudge.Time(emptyList(), emptyMap(), emptyList())

        /** His question, as he put it, that each reply of yours answers here. */
        const val QUESTION = "Is it questions 1 to 6, or all of them?"
    }
}
