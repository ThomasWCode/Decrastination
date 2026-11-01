package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.Due
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.Backups
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.HttpResponse
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/** What the server does by itself (docs/spec/server.md §Jobs), and what it holds that a restore has to carry. */
class ServerJobsTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("jobs").toFile()
    private val db = Database(File(dir, "test.db").path)
    private val clock = FixedClock(1_000_000L)

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun jobs(vararg jobs: ServerJob, running: Boolean = true) = ServerJobs(db, clock, { running }, jobs.toList())

    /** [block] with another connection holding the write lock: writes here are told the database is busy, at once. */
    private inline fun <T> whileLocked(block: () -> T): T {
        db.transactionless { c -> c.createStatement().use { it.execute("PRAGMA busy_timeout=20") } }
        return DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").path}").use { lock ->
            lock.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            block().also { lock.createStatement().use { it.execute("ROLLBACK") } }
        }
    }

    @Test
    fun `the heartbeat goes only from a box that can still write, as one that can't still answers its health check`() {
        val beats = mutableListOf<String>()
        val graph = ServerGraph(db, dir, Plain, "test", heartbeatUrl = "https://beat.example/made-up", http = Http { url, _, _ -> beats += url; HttpResponse(200, "") })
        graph.heartbeat()
        assertEquals(listOf("https://beat.example/made-up"), beats)
        whileLocked { assertFailsWith<SQLException> { graph.heartbeat() } }
        assertEquals(1, beats.size, "so the check notices")
        graph.heartbeat()
        assertEquals(2, beats.size, "and it's back as that passes")
    }

    @Test
    fun `the minute's job is that heartbeat`() = runBlocking {
        db.setMeta("mode", ServerGraph.COMMANDS)
        val url = "https://beat.example/made-up"
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val graph = ServerGraph(db, dir, Plain, "test", heartbeatUrl = url, http = Http { to, _, _ -> asked += to; HttpResponse(200, "") })
        graph.jobs.tick()
        assertEquals(1, asked.count { it == url })
        db.transaction { c -> c.createStatement().use { it.execute("DELETE FROM meta WHERE key = 'job:heartbeat'") } }
        whileLocked { graph.jobs.tick() }
        assertEquals(1, asked.count { it == url })
    }

    @Test
    fun `in mirror mode nothing runs`() = runBlocking {
        var ran = 0
        jobs(ServerJob.every("a", 60_000) { ran++ }, running = false).tick()
        assertEquals(0, ran)
    }

    @Test
    fun `a job runs when it's due, and not again till its time comes round`() = runBlocking {
        var ran = 0
        val jobs = jobs(ServerJob.every("a", 60_000) { ran++ })
        jobs.tick()
        jobs.tick()
        assertEquals(1, ran)
        clock.time += 60_000
        jobs.tick()
        assertEquals(2, ran)
    }

    @Test
    fun `a restart neither repeats a job nor misses one`() = runBlocking {
        var ran = 0
        jobs(ServerJob.every("a", 60_000) { ran++ }).tick()
        jobs(ServerJob.every("a", 60_000) { ran++ }).tick()
        assertEquals(1, ran)
    }

    @Test
    fun `one that fails stops neither the others nor itself, and waits its turn`() = runBlocking {
        var tries = 0
        var after = 0
        val jobs = jobs(ServerJob.every("bad", 60_000) { tries++; error("no") }, ServerJob.every("good", 600_000) { after++ })
        jobs.tick()
        assertEquals(1, after, "the next still ran")
        jobs.tick()
        assertEquals(1, tries, "not round again at once")
        clock.time += 60_000
        jobs.tick()
        assertEquals(2, tries, "failed, it's tried again at its next turn")
        assertEquals(1, after, "and what worked isn't")
    }

    @Test
    fun `a job at a set time that fails waits its retry, not the next minute`() = runBlocking {
        var tries = 0
        val jobs = jobs(ServerJob("bad", due = { last, _ -> last == null }) { tries++; error("no") })
        jobs.tick()
        clock.time += 60_000
        jobs.tick()
        assertEquals(1, tries)
        clock.time += ServerJob.RETRY_MS
        jobs.tick()
        assertEquals(2, tries)
    }

    @Test
    fun `a job's checkpoint isn't lost with a command that's rolled back`() = runBlocking {
        var ran = 0
        val jobs = jobs(ServerJob.every("a", 60_000) { ran++ })
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            runCatching {
                db.command {
                    entered.complete(Unit)
                    letGo.await()
                    error("rolled back")
                }
            }
        }
        entered.await()
        val turn = async(Dispatchers.IO) { jobs.tick() }
        withContext(Dispatchers.IO) { Thread.sleep(150) }
        letGo.complete(Unit)
        assertTrue(command.await().isFailure)
        turn.await()
        jobs.tick()
        assertEquals(1, ran, "it isn't run again")
    }

    @Serializable
    private data class Seen(val by: List<String> = emptyList())

    private fun store(backend: SqliteBackend) = JsonStore(backend, "a.json", Seen.serializer(), ::Seen)

    @Test(timeout = STUCK_MS)
    fun `a store's write from outside a command waits for nothing, so the two can't wait for each other`() = runBlocking {
        val store = store(SqliteBackend(db))
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            db.command {
                store.update { it.copy(by = it.by + "command") }
                entered.complete(Unit)
                letGo.await()
                store.update { it.copy(by = it.by + "command again") }
            }
        }
        entered.await()
        store.update { it.copy(by = it.by + "job") }
        letGo.complete(Unit)
        command.await()
        val made = listOf("command", "job", "command again")
        assertEquals(made, store.value.by)
        store.reload()
        assertEquals(made, store.value.by, "the job's write didn't go over the command's, which takes it in")
    }

    @Test(timeout = STUCK_MS)
    fun `what's held back for a command that's rolled back is kept, in its order`() = runBlocking {
        val store = store(SqliteBackend(db))
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            runCatching {
                db.command {
                    entered.complete(Unit)
                    letGo.await()
                    store.update { it.copy(by = it.by + "command") }
                    error("rolled back")
                }
            }
        }
        entered.await()
        store.update { it.copy(by = it.by + "one") }
        store.update { it.copy(by = it.by + "two") }
        assertEquals(null, db.read { c -> c.prepareStatement("SELECT json FROM stores WHERE name = 'a.json'").use { q -> q.executeQuery().use { r -> if (r.next()) r.getString(1) else null } } }, "not yet")
        letGo.complete(Unit)
        assertTrue(command.await().isFailure)
        store.reload()
        assertEquals(listOf("one", "two"), store.value.by)
    }

    @Test(timeout = STUCK_MS)
    fun `a credential sealed from outside a command waits for nothing either, and goes in as it closes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            db.command {
                entered.complete(Unit)
                letGo.await()
            }
        }
        entered.await()
        db.setCredentials("a", byteArrayOf(1), 0)
        assertEquals(null, db.credentials("a"), "not yet")
        letGo.complete(Unit)
        command.await()
        assertContentEquals(byteArrayOf(1), db.credentials("a"))
    }

    @Test
    fun `a loosening's wait is counted by the server's clock, across a restart, and a phone's mark is another clock's`() {
        val mark = requireNotNull(ServerClock(db).uptime())
        Thread.sleep(5)
        val later = requireNotNull(ServerClock(db).uptime())
        assertTrue(requireNotNull(later.since(mark)) > 0)
        assertEquals(null, later.since(Uptime(3, 1_000)))
        assertEquals(null, later.since(Uptime(Uptime.UNKNOWN_BOOT, 1_000)))
    }

    @Test
    fun `an own task whose steps are all done is finished, once the server applies commands`() = runBlocking {
        db.setMeta("mode", ServerGraph.COMMANDS)
        val graph = ServerGraph(db, dir, Plain, "test")
        val scope = CoroutineScope(Dispatchers.Default)
        graph.start(scope)
        val id = "own:s-a"
        graph.applier.apply(CommandBatch(listOf(Command("one", clock.now(), SaveOwnTask("s-a", OwnTask(title = "A", steps = listOf(OwnStep("B", 30))))))), "website")
        assertTrue(graph.stores.tasks.value.tasks.single { it.id == id }.isOpen)
        graph.stores.tasks.update { state -> state.copy(tasks = state.tasks.map { task -> task.copy(subSteps = task.subSteps.map { it.copy(done = true) }) }) }
        withTimeout(5_000) { graph.stores.tasks.state.first { state -> state.tasks.single { it.id == id }.status == Status.Done } }
        scope.cancel()
    }

    @Test
    fun `the plan is made and its day kept, once the server applies commands`() = runBlocking {
        db.setMeta("mode", ServerGraph.COMMANDS)
        val graph = ServerGraph(db, dir, Plain, "test")
        val scope = CoroutineScope(Dispatchers.Default)
        graph.start(scope)
        withTimeout(10_000) { graph.stores.session.state.first { it.slots.date.isNotEmpty() } }
        scope.cancel()
    }

    @Test
    fun `a piece gone by is marked before a new plan lays it again`() = runBlocking {
        db.setMeta("mode", ServerGraph.COMMANDS)
        val graph = ServerGraph(db, dir, Plain, "test")
        graph.applier.apply(CommandBatch(listOf(Command("one", clock.now(), SaveOwnTask("s-a", OwnTask(title = "A", minutes = 30))))), "website")
        val piece = graph.planning.plan().ordered.first()
        val now = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(now).atZone(graph.clock.zone()).toLocalDate()
        graph.stores.session.update { it.copy(slots = SlotState(today.toString(), mapOf(piece.key to now - 3 * 3_600_000L))) }
        graph.planning.watcher.start()
        withTimeout(10_000) { graph.stores.log.state.first { log -> log.slots.any { it.key == piece.key } } }
        Unit
    }

    @Test
    fun `a morning or a Sunday gone by is no job to catch up on, a first run being today's`() {
        val graph = ServerGraph(db, dir, Plain, "test")
        val zone = graph.clock.zone()
        val sunday = LocalDate.of(2026, 11, 1)
        fun at(day: LocalDate, minute: Int) = day.atStartOfDay(zone).toInstant().toEpochMilli() + minute * 60_000L
        val morning = graph.stores.settings.value.briefingWeekendMin
        assertFalse(graph.morningDue(null, at(sunday, morning - 60)), "before today's time, yesterday's isn't due")
        assertTrue(graph.morningDue(null, at(sunday, morning + 1)), "today's is")
        assertFalse(graph.morningDue(at(sunday, morning + 1), at(sunday, morning + 2)), "once it's run, not again")
        val friday = sunday.minusDays(2)
        assertFalse(graph.morningDue(at(friday, morning + 1), at(sunday, morning - 60)), "a morning missed isn't caught up on ahead of today's")
        assertTrue(graph.morningDue(at(friday, morning + 1), at(sunday, morning + 1)), "today's comes at its time")
        assertFalse(graph.morningDue(at(sunday, morning - 60), at(sunday, morning + 60)), "a run today stands for today, its time moved later since")
        val monday = sunday.plusDays(1)
        val checkIn = graph.stores.settings.value.checkInMin
        assertTrue(graph.sundayDue(null, at(sunday, checkIn + 1), checkIn))
        assertFalse(graph.sundayDue(null, at(monday, 120), checkIn), "Monday's first run says nothing of Sunday")
    }

    @Test
    fun `a morning set for midnight still comes`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        graph.stores.settings.update { it.copy(briefingWeekendMin = 0) }
        val sunday = LocalDate.of(2026, 11, 1).atStartOfDay(graph.clock.zone()).toInstant().toEpochMilli()
        assertTrue(graph.morningDue(null, sunday + 60_000))
    }

    @Test
    fun `a new Power Planner login takes the old one's session with it, and another credential doesn't`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        val secrets = graph.stores.secrets
        secrets.put(mapOf(Secret.PowerPlannerSession to "session", Secret.PowerPlannerAccountId to "1"))
        graph.setCredentials(mapOf("GmailAppPassword" to "secret"))
        assertEquals("session", secrets[Secret.PowerPlannerSession])
        graph.setCredentials(mapOf("PowerPlannerPassword" to ""))
        assertEquals(null, secrets[Secret.PowerPlannerSession])
        assertEquals(null, secrets[Secret.PowerPlannerAccountId])
    }

    @Test
    fun `the nightly file holds the calendar answers the phone sent`() = runBlocking {
        var written = ""
        val folder = object : BackupService.Folder {
            override val available = true

            override fun write(name: String, text: String): Boolean {
                written = text
                return true
            }

            override fun names() = emptyList<String>()

            override fun delete(name: String) = false
        }
        val graph = ServerGraph(db, dir, Plain, "test", nightly = folder)
        graph.calendarRead.update { it.copy(answers = mapOf("an event" to "free")) }
        graph.nightly()
        assertEquals(mapOf("an event" to "free"), assertNotNull(Backups.decode(written, graph.stores.shapes)).eventAnswers)
    }

    @Test(timeout = STUCK_MS)
    fun `the nightly file waits out a command, and holds nothing of one that fails`() = runBlocking {
        db.setMeta("mode", ServerGraph.COMMANDS)
        var written = ""
        val folder = object : BackupService.Folder {
            override val available = true

            override fun write(name: String, text: String): Boolean {
                written = text
                return true
            }

            override fun names() = emptyList<String>()

            override fun delete(name: String) = false
        }
        val graph = ServerGraph(db, dir, Plain, "test", nightly = folder)
        val now = System.currentTimeMillis()
        val half = TaskItem("own:half", Source.Own, "half", "Half made", kind = Kind.Homework, firstSeenAt = now, lastSeenAt = now)
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            db.commandOr({ graph.stores.tasks.reload() }, { "dropped" }) {
                graph.stores.tasks.update { it.copy(tasks = it.tasks + half) }
                entered.complete(Unit)
                letGo.await()
                error("no room")
            }
        }
        entered.await()
        val night = async(Dispatchers.IO) { graph.nightly() }
        withContext(Dispatchers.IO) { Thread.sleep(150) }
        assertEquals("", written, "not while it's open")
        letGo.complete(Unit)
        command.await()
        night.await()
        assertTrue(assertNotNull(Backups.decode(written, graph.stores.shapes)).ownTasks.none { it.title == "Half made" })
    }

    @Test
    fun `in mirror mode nothing watches the stores`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        val scope = CoroutineScope(Dispatchers.Default)
        graph.start(scope)
        graph.stores.tasks.update { it.copy(tasks = listOf(TaskItem("own:a", Source.Own, "a", "A", kind = Kind.Homework, subSteps = listOf(SubStep("B", 30, done = true)), firstSeenAt = 0, lastSeenAt = 0))) }
        delay(200)
        assertTrue(graph.stores.tasks.value.tasks.single().isOpen)
        scope.cancel()
    }

    @Test(timeout = STUCK_MS)
    fun `a job's write made over a command's doesn't bring it back when the command fails`() = runBlocking {
        val store = store(SqliteBackend(db))
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            db.commandOr({ store.reload() }, { "dropped" }) {
                store.update { it.copy(by = it.by + "command") }
                entered.complete(Unit)
                letGo.await()
                error("no room")
            }
        }
        entered.await()
        store.update { it.copy(by = it.by + "job") }
        store.update { it.copy(by = it.by + "job again") }
        letGo.complete(Unit)
        assertEquals("dropped", command.await())
        assertEquals(listOf("job", "job again"), store.value.by, "in memory")
        store.reload()
        assertEquals(listOf("job", "job again"), store.value.by, "and committed, each once")
    }

    @Test(timeout = STUCK_MS)
    fun `a write that comes while a failed command's put right goes in once, nothing of the command with it`() = runBlocking {
        val store = store(SqliteBackend(db))
        val answer = db.commandOr(
            { store.reload() },
            {
                // From outside the command, as a job's is: a scope of its own carries none of its context.
                CoroutineScope(Dispatchers.IO).async { store.update { it.copy(by = it.by + "job") } }.await()
                "dropped"
            },
        ) {
            store.update { it.copy(by = it.by + "command") }
            error("no room")
        }
        assertEquals("dropped", answer)
        assertEquals(listOf("job"), store.value.by)
        store.reload()
        assertEquals(listOf("job"), store.value.by)
    }

    @Test(timeout = STUCK_MS)
    fun `a write that comes as the stores are read again isn't lost, nor does it bring the command back`() = runBlocking {
        val store = store(SqliteBackend(db))
        var first = true
        val answer = db.commandOr(
            {
                // On memory the command's still in, and held; then read again, which takes it from memory, leaving only what's held.
                if (first) {
                    first = false
                    CoroutineScope(Dispatchers.IO).async { store.update { it.copy(by = it.by + "job") } }.await()
                }
                store.reload()
            },
            { "dropped" },
        ) {
            store.update { it.copy(by = it.by + "command") }
            error("no room")
        }
        assertEquals("dropped", answer)
        assertEquals(listOf("job"), store.value.by)
        store.reload()
        assertEquals(listOf("job"), store.value.by)
    }

    @Test(timeout = STUCK_MS)
    fun `a recovery that itself fails writes nothing of the command, and leaves memory as what's committed`() = runBlocking {
        val store = store(SqliteBackend(db))
        val outside = CoroutineScope(Dispatchers.IO)
        val failed = assertFailsWith<IllegalStateException> {
            db.commandOr(
                { store.reload() },
                { error("no room for the record") },
            ) {
                store.update { it.copy(by = it.by + "command") }
                outside.async { store.update { it.copy(by = it.by + "job") } }.await()
                error("no room")
            }
        }
        assertEquals("no room for the record", failed.message)
        assertEquals(emptyList(), store.value.by, "in memory")
        store.reload()
        assertEquals(emptyList(), store.value.by, "and committed")
        db.setMeta("after", "the database still takes writes")
        assertEquals("the database still takes writes", db.meta("after"))
    }

    @Test(timeout = STUCK_MS)
    fun `stores that can't be read again let go of what's held, as it can't be told from the command's`() = runBlocking {
        val store = store(SqliteBackend(db))
        val outside = CoroutineScope(Dispatchers.IO)
        var first = true
        assertFailsWith<IllegalStateException> {
            db.commandOr(
                {
                    if (first) {
                        first = false
                        outside.async { store.update { it.copy(by = it.by + "job") } }.await()
                        error("can't be read")
                    }
                    store.reload()
                },
                { "dropped" },
            ) {
                store.update { it.copy(by = it.by + "command") }
                error("no room")
            }
        }
        store.reload()
        assertEquals(emptyList(), store.value.by, "nothing of the command, by way of the job's write")
    }

    @Test(timeout = STUCK_MS)
    fun `a held write that can't be made again is let go, and the rest aren't`() = runBlocking {
        val store = store(SqliteBackend(db))
        val outside = CoroutineScope(Dispatchers.IO)
        val answer = db.commandOr({ store.reload() }, { "dropped" }) {
            store.update { it.copy(by = it.by + "command") }
            // Made on the command's change, so it can't be made on what's committed.
            outside.async { store.update { seen -> check("command" in seen.by); seen.copy(by = seen.by + "bad") } }.await()
            outside.async { store.update { it.copy(by = it.by + "good") } }.await()
            error("no room")
        }
        assertEquals("dropped", answer)
        assertTrue("good" in store.value.by)
        assertFalse("command" in store.value.by)
    }

    @Test
    fun `a failure beside the morning's briefing isn't its, and only its own runs it again`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        var briefed = 0
        val failing: suspend () -> Unit = { error("no") }
        graph.morning(failing, failing, { briefed++ }, failing)
        assertEquals(1, briefed, "they don't stop it")
        assertFailsWith<IllegalStateException> { graph.morning({}, {}, { error("no") }, {}) }
        Unit
    }

    @Test
    fun `a mode set while it runs waits for a restart, as everything that hangs on it does`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        db.setMeta("mode", ServerGraph.COMMANDS)
        assertEquals("mirror", graph.mode)
        assertFalse(graph.backend.writable)
        assertEquals(ServerGraph.COMMANDS, ServerGraph(db, dir, Plain, "test").mode)
    }

    @Test
    fun `a nightly file not written is a failure, to be tried again, not a night gone without`() = runBlocking {
        val folder = object : BackupService.Folder {
            override val available = true

            override fun write(name: String, text: String) = false

            override fun names() = emptyList<String>()

            override fun delete(name: String) = false
        }
        assertFailsWith<IllegalStateException> { ServerGraph(db, dir, Plain, "test", nightly = folder).nightly() }
        Unit
    }

    @Test
    fun `a reminder left to be asked for afresh is asked for, as the phone's worker would`() = runBlocking {
        db.setMeta("mode", ServerGraph.COMMANDS)
        val graph = ServerGraph(db, dir, Plain, "test")
        val now = System.currentTimeMillis()
        graph.stores.felix.update { it.copy(reminders = listOf(Reminder("r", "words", now - 600_000, By.Felix, due = Due(DueKind.At, now - 1_000)))) }
        graph.owners.ports.remindLater()
        withTimeout(5_000) { graph.stores.chat.state.first { chat -> chat.messages.any { it.reminderId == "r" } } }
        Unit
    }

    @Test
    fun `a write from outside a command waits for it, and isn't lost when it's rolled back`() = runBlocking {
        db.setMeta("probe", "before")
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            runCatching {
                db.command {
                    db.transaction { c -> c.createStatement().use { it.execute("INSERT INTO meta(key, value) VALUES('command', 'x')") } }
                    entered.complete(Unit)
                    letGo.await()
                    error("rolled back")
                }
            }
        }
        entered.await()
        val job = async(Dispatchers.IO) { db.transaction { c -> c.createStatement().use { it.execute("INSERT INTO meta(key, value) VALUES('job', 'y')") } } }
        withContext(Dispatchers.IO) { Thread.sleep(150) }
        assertFalse(job.isCompleted, "it waits while the command's open")
        letGo.complete(Unit)
        assertTrue(command.await().isFailure)
        job.await()
        assertEquals("y", db.meta("job"), "kept, though the command went")
        assertEquals(null, db.meta("command"))
    }

    @Test
    fun `credentials are in the database, sealed, and a rebuilt box gets them back from it`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        graph.setCredentials(mapOf("GmailAddress" to "someone@example.org", "AnthropicApiKey" to "not settable from the website"))
        assertEquals(setOf("GmailAddress"), graph.credentials())
        val sealed = assertNotNull(db.credentials("secrets.bin"))
        val file = File(dir, "secrets.bin")
        assertContentEquals(file.readBytes(), sealed)
        assertTrue(file.delete(), "the box is rebuilt: only the database came back")
        val rebuilt = ServerGraph(db, dir, Plain, "test")
        assertEquals("someone@example.org", rebuilt.stores.secrets[Secret.GmailAddress])
    }

    @Test
    fun `a blank credential is taken away`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        graph.setCredentials(mapOf("GmailAppPassword" to "secret"))
        graph.setCredentials(mapOf("GmailAppPassword" to ""))
        assertEquals(emptySet(), graph.credentials())
    }

    private companion object {
        /** For what could hang: a coroutine's timeout can't reach a thread stopped on a lock, and JUnit's stops waiting for it. */
        const val STUCK_MS = 15_000L
    }
}
