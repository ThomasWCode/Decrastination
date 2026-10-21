package com.thomaswcode.decrastination.felix

import android.app.Notification
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.learn.Learning
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.sync.GmailFinishes
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Felix's service on temporary stores (docs/plan_v4, Stage 4), for the flows' tests: the real stores,
 * gateway, focus, plan and outbox, and his calls through the real readers, answered by a stand-in for
 * Claude's API ([server]). Android is a list of what it was asked ([ports]); the other owners
 * ([owners]: your own tasks, your instructions and the calendar's answers) do, simply, what AppGraph's
 * functions do. For the chat's eval ([com.thomaswcode.decrastination.enrich.EvalRunTest]): the stores
 * [seed]ed with a copy of the phone's before they're read, the calendar as [calendar] judges it, and with
 * a [key] his calls go to Claude's API itself, paid for.
 */
class FelixHarness(
    start: String = "2026-10-19T16:30",
    seed: (File) -> Unit = {},
    private val key: String? = null,
    private val calendar: (Stores) -> EventJudge.Time = { NO_CALENDAR },
) : AutoCloseable {
    private val dir = Files.createTempDirectory("felix").toFile().also(seed)
    val clock = FixedClock(Fixtures.at(start), LONDON)
    val stores = Stores(dir, Reverse, { clock.now() })
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    val server = MockWebServer().apply { start() }
    private val gateway = ModelGateway(stores.settings, { key ?: "test-key" }, stores.model, stores.tasks, clock, NoAlerts, scope) {}
        .also { if (key == null) it.endpoint = server.url("").toString().trimEnd('/') }
    val ports = Ports()
    val owners = Owners()

    /** Whether the chat is on screen, as its screen says so. */
    @Volatile var chatOnScreen = false

    /** His channel shown, as Android says. */
    @Volatile var shown = true
    private val focus: Focus = Focus(stores.tasks, stores.settings, stores.session, stores.learning, stores.protection, stores.log, clock) { state, s, now -> planning.plan(state, s, now) }
    val planning: Planning = Planning(
        stores.tasks, stores.settings, stores.session, stores.learning, stores.model, stores.log, stores.instructions, stores.school, clock, scope, gateway,
        calendarTime = { calendar(stores) },
        calendarChanges = emptyFlow<Unit>(),
        sweep = {},
        hooks = emptyList(),
        profile = { conversation.profile(it) },
        draftAhead = { _, _ -> },
        onFailure = { throw it },
    )
    private val notices = Shown()
    val outbox = Outbox(stores.chat, stores.chatArchive, stores.settings, notices, ports, { chatOnScreen }, clock)
    val conversation: Conversation = Conversation(
        stores.tasks, stores.settings, stores.runtime, stores.learning, stores.log, stores.instructions, stores.chat, stores.school, stores.memory, stores.felix,
        clock, scope, gateway, notices, focus, planning, ports, outbox, owners,
        calendarTime = { calendar(stores) },
        answered = { message, words -> looks.answered(message, words) },
        draftReminders = { now -> looks.draftReminders(now) },
        requestSync = { synced += it },
        requestTeamsSync = { teamsSyncs++; teamsRefusal },
    )

    /** How often the Teams widget was asked to sync (Q33: B), and why it didn't start, as the test says. */
    var teamsSyncs = 0
    var teamsRefusal: String? = null

    /** The sources his `sync` asked to read (G2), in order. */
    val synced: MutableList<Set<Source>> = Collections.synchronizedList(mutableListOf())
    val looks: Looks = Looks(
        stores.tasks, stores.settings, stores.model, stores.log, stores.chat, stores.felix, clock, scope, gateway, focus, planning, ports, outbox,
        calendarTime = { calendar(stores) },
        profile = { conversation.profile(it) },
        remember = { conversation.remember(it) },
        addInstruction = { text, about, answer -> owners.instruction(text, about, answer) },
        assess = { taskId, doneAt, answer -> assessed += Triple(taskId, doneAt, answer) },
    )

    /** [key]'s piece kept as Day's Done keeps it ([Focus.keepSlot]), with [extraMin] more and a [photo]. */
    suspend fun focusKeep(key: String, extraMin: Int = 0, photo: Boolean = false): Boolean = focus.keepSlot(key, extraMin, photo)

    /** [key]'s last piece kept since [since] taken back, as Day's Undo does ([Focus.unkeepSlot]). */
    suspend fun focusUnkeep(key: String, since: Long): Boolean = focus.unkeepSlot(key, since)

    /** How work went, as it was put on record ([Looks.answered]): the task, when it was done, the answer. */
    val assessed: MutableList<Triple<String, Long, String>> = Collections.synchronizedList(mutableListOf())

    init {
        // Claude on, with its key in use: his calls go to the stand-in.
        runBlocking { stores.settings.update { it.copy(aiEnabled = true, aiKeyActive = true) } }
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

    /** The chat's answer [reply], with the tasks and changes it offers (the schema's JSON for them), what it names [answered], the actions it takes ([actions]), and nothing else. */
    fun chatAnswer(reply: String, newTasks: String = "[]", changes: String = "[]", answered: String = "[]", actions: String = "[]"): String =
        """{"reply":"$reply","newTasks":$newTasks,"changes":$changes,"memories":[],"suggestedReplies":[],"actions":$actions,"research":"","details":[],"answered":$answered}"""

    /** What the service started in the background (an answer asked for as a message is kept), finished. */
    fun settle() = runBlocking {
        while (job.children.any { it.isActive }) job.children.toList().joinAll()
    }

    override fun close() {
        settle()
        server.shutdown()
        dir.deleteRecursively()
    }

    /** What Android was asked to do: his notification made and taken away, his looks set, the work asked for. */
    open class Ports : FelixPorts {
        /** His notification as each was made: the last is what's showing, unless taken away since ([showing]). */
        val shown: MutableList<Outbox.Notice> = Collections.synchronizedList(mutableListOf())
        val looks: MutableList<Long?> = Collections.synchronizedList(mutableListOf())
        @Volatile var cancelled = 0
        @Volatile var showing: Outbox.Notice? = null
        @Volatile var readAgain = 0
        @Volatile var schedules = 0
        @Volatile var reviews = 0
        @Volatile var forms = 0
        @Volatile var later = 0

        override fun show(notice: Outbox.Notice) {
            shown += notice
            showing = notice
        }

        override fun cancel() {
            cancelled++
            showing = null
        }

        override fun armLook(at: Long?) {
            looks += at
        }

        override fun readAgain() {
            readAgain++
        }

        override fun scheduleReminders() {
            schedules++
        }

        override fun review() {
            reviews++
        }

        override fun answerLater() {
            later++
        }

        override fun remindForm() {
            forms++
        }

        /** The tasks opened where they live (G3), in order: each opened. */
        val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** Done as each opens, as if you'd done it meanwhile (the chat left). */
        var whileOpening: () -> Unit = {}

        override suspend fun openSource(task: TaskItem): Pair<Boolean, String?> {
            whileOpening()
            opened += task.id
            return true to null
        }
    }

    /** Your own tasks and your instructions, as AppGraph keeps them: made, applied and taken back on the stores, the checks left out. */
    inner class Owners : Conversation.Owners {
        override suspend fun saveOwnTask(sourceId: String, task: OwnTask): Boolean {
            if (OwnTaskRules.problem(task) != null) return false
            val made = OwnTaskRules.create(task, sourceId, clock.now())
            stores.tasks.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == made.id } + made) }
            return true
        }

        override suspend fun finishOwnTask(taskId: String) = Unit

        override suspend fun reopenOwnTask(taskId: String) = Unit

        override suspend fun deleteOwnTask(taskId: String) {
            stores.tasks.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == taskId && it.source == Source.Own }) }
        }

        override suspend fun applyInstruction(id: String) {
            stores.instructions.update { s ->
                s.copy(instructions = s.instructions.map { if (it.id == id && it.state == InstructionStatus.Understood) it.copy(state = InstructionStatus.Applied, appliedAt = clock.now()) else it })
            }
        }

        override suspend fun deleteInstruction(id: String) {
            stores.instructions.update { s -> s.copy(instructions = s.instructions.filterNot { it.id == id }) }
        }

        override suspend fun layTaskOverrides() = Unit

        override suspend fun changeSettings(change: (Settings) -> Settings) {
            stores.settings.update(change)
        }

        /** The calendar's answers kept (the event, the answer as it's kept), as `CalendarWriter.answer` would keep them. */
        val events: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

        override suspend fun answerEvent(key: String, answer: String) {
            events += key to answer
            conversation.settleEvent(key)
        }

        /** The week's check-in on record once a week, by the rule `Learning.recordCheckIn` keeps it by. */
        override suspend fun recordCheckIn(checkIn: CheckIn, now: Long): Boolean {
            var added = false
            stores.log.update { l -> Learning.withCheckIn(l, checkIn, now)?.also { added = true } ?: l }
            return added
        }

        /** The starred emails finished by his done (Stage 11), each coming to [gmailFinish]. */
        val gmailFinished = mutableListOf<String>()
        var gmailFinish = GmailFinishes.Finish.Going

        override suspend fun finishGmail(taskId: String): GmailFinishes.Finish = gmailFinish.also { gmailFinished += taskId }

        /** The finishes taken back. */
        val gmailUnfinished = mutableListOf<String>()

        override suspend fun unfinishGmail(taskId: String, writeId: String?): Boolean = taskId in gmailFinished && writeId == "w-$taskId" && gmailUnfinished.add(taskId)

        override fun gmailWriteOf(taskId: String): String? = "w-$taskId".takeIf { taskId in gmailFinished }

        /** Your answer to his question, kept to be read, as `Instructions.addInstruction` keeps one. */
        suspend fun instruction(text: String, about: About, answer: Boolean): String {
            val id = UUID.randomUUID().toString()
            stores.instructions.update { it.copy(instructions = it.instructions + Instruction(id, text, about, clock.now(), answer = answer)) }
            return id
        }
    }

    /** His channel shown while [shown] says, and nothing posted through it here: his notification is the ports'. */
    private inner class Shown : Notices {
        override fun post(id: NoticeId, notification: Notification, tag: String?) = Unit
        override fun cancel(id: NoticeId, tag: String?) = Unit
        override fun shown(channel: String): Boolean = shown
        override suspend fun postHeld() = Unit
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

    private companion object {
        /** No calendar: nothing busy, no day's load, no event. */
        val NO_CALENDAR = EventJudge.Time(emptyList(), emptyMap(), emptyList())
    }
}
