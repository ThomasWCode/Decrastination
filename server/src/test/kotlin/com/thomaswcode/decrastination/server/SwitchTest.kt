package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.SeedBatch
import com.thomaswcode.decrastination.sync.StoreText
import com.thomaswcode.decrastination.sync.SwitchSeed
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import com.thomaswcode.decrastination.tasks.TasksHarness
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** The switch to commands, the server's half (D-141): the phone's last whole seed, the mode set in its transaction. */
class SwitchTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("switch").toFile()
    private val db = Database(File(dir, "test.db").path)
    private val graph = ServerGraph(db, dir, Plain, "test")
    private val phone = TasksHarness()

    @AfterTest
    fun cleanUp() {
        // What going over set watching stopped first, or it would find its database closed.
        runBlocking { graph.stopForATest() }
        phone.close()
        db.close()
        dir.deleteRecursively()
    }

    /** What the phone holds, every store whole, as its final seed sends it. */
    private fun everything() = SeedBatch(
        "Europe/London",
        "test",
        phone.stores.mirrored.map { StoreText(it.name, it.version, it.text()) } + StoreText(CalendarRead.NAME, CalendarRead().version, Json.encodeToString(CalendarRead.serializer(), CalendarRead())),
    )

    private val serverTitles get() = graph.stores.tasks.value.tasks.map { it.title }.toSet()

    @Test
    fun `the final seed is taken whole and the server goes over in its transaction, taking commands at once, no restart`() = runBlocking {
        phone.commands.act(SaveOwnTask("s-essay", OwnTask(title = "Essay")))
        val over = assertIs<ServerGraph.Switched.Over>(graph.switchOver(SwitchSeed("switch-1", everything()), "phone"))
        assertEquals(ServerGraph.COMMANDS, graph.mode)
        assertEquals(ServerGraph.COMMANDS, db.meta("mode"), "on record, for the next start")
        assertEquals(setOf("Essay"), serverTitles)
        assertEquals(Protocol.hash(phone.stores.tasks.text()), over.answer.holds["tasks.json"], "what it holds, by hash, for the phone's record")
        assertEquals(db.currentRevision(), over.answer.revision)
        // A command straight after lands, and is written: the owners write now.
        val outcome = graph.applier.apply(CommandBatch(listOf(Command("c-1", System.currentTimeMillis(), SaveOwnTask("s-maths", OwnTask(title = "Maths"))))), "phone").single()
        assertEquals(Outcome.APPLIED, outcome.state)
        assertEquals(setOf("Essay", "Maths"), serverTitles)
        val row = db.transactionless { c -> c.prepareStatement("SELECT json FROM stores WHERE name = 'tasks.json'").use { q -> q.executeQuery().use { it.next(); it.getString(1) } } }
        assertTrue("Maths" in row, "the store's row is written, not left as the phone sent it")
    }

    @Test
    fun `asked again under the same id, in commands mode, it says the same answer and applies nothing`() = runBlocking {
        phone.commands.act(SaveOwnTask("s-essay", OwnTask(title = "Essay")))
        val first = assertIs<ServerGraph.Switched.Over>(graph.switchOver(SwitchSeed("switch-1", everything()), "phone")).answer
        graph.applier.apply(CommandBatch(listOf(Command("c-1", System.currentTimeMillis(), SaveOwnTask("s-maths", OwnTask(title = "Maths"))))), "phone")
        // The phone, its answer lost, sends the same seed again: what it held then, not the server's since.
        val again = assertIs<ServerGraph.Switched.Over>(graph.switchOver(SwitchSeed("switch-1", everything()), "phone")).answer
        assertEquals(first, again)
        assertEquals(setOf("Essay", "Maths"), serverTitles, "nothing of the seed's taken over what's come since")
    }

    @Test
    fun `another seed once it's over is turned away, and nothing of it is kept`() = runBlocking {
        graph.switchOver(SwitchSeed("switch-1", everything()), "phone")
        phone.commands.act(SaveOwnTask("s-late", OwnTask(title = "Late")))
        assertIs<ServerGraph.Switched.Refused>(graph.switchOver(SwitchSeed("switch-2", everything()), "phone"))
        assertEquals(emptySet(), serverTitles)
    }

    @Test
    fun `a seed that waited out the switch is turned away under its lock, and takes nothing over what's applied since`() = runBlocking {
        val older = everything()
        graph.switchOver(SwitchSeed("switch-1", everything()), "phone")
        graph.applier.apply(CommandBatch(listOf(Command("c-1", System.currentTimeMillis(), SaveOwnTask("s-maths", OwnTask(title = "Maths"))))), "phone")
        // Past the route's look before the switch, it reaches the lock after: the server's in commands mode by then.
        assertEquals(null, graph.seed(older, "phone"))
        assertEquals(setOf("Maths"), serverTitles)
    }

    @Test
    fun `a store this server can't read stops the switch, and the server goes on mirroring`() = runBlocking {
        val seed = everything().let { it.copy(stores = it.stores.map { s -> if (s.name == "tasks.json") s.copy(version = s.version + 1) else s }) }
        assertIs<ServerGraph.Switched.Refused>(graph.switchOver(SwitchSeed("switch-1", seed), "phone"))
        assertEquals("mirror", graph.mode)
        assertEquals(null, db.meta("mode"))
        assertEquals(0L, db.currentRevision(), "nothing kept")
    }

    @Test
    fun `what the phone ran today of the jobs at a set time isn't run again at the switch, and the rest run at once`() = runBlocking {
        // Its mornings at midnight: today's has gone by, so the phone's said it.
        phone.stores.settings.update { it.copy(briefingWeekdayMin = 0, briefingWeekendMin = 0) }
        graph.switchOver(SwitchSeed("switch-1", everything()), "phone")
        assertNotNull(db.meta("job:morning"), "today's morning is on record as run")
        assertEquals(null, db.meta("job:sources"), "what runs every quarter of an hour runs at its first turn")
    }

    @Test
    fun `a switch whose transaction fails leaves the server mirroring, with no answer kept to say again`() = runBlocking<Unit> {
        db.transactionless { c -> c.createStatement().use { it.execute("PRAGMA busy_timeout=20") } }
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").path}").use { lock ->
            lock.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            assertFailsWith<SQLException> { graph.switchOver(SwitchSeed("switch-1", everything()), "phone") }
            lock.createStatement().use { it.execute("ROLLBACK") }
        }
        assertEquals("mirror", graph.mode)
        assertEquals(null, db.meta("switch"))
        assertIs<ServerGraph.Switched.Over>(graph.switchOver(SwitchSeed("switch-1", everything()), "phone"), "and its retry goes through")
    }
}
