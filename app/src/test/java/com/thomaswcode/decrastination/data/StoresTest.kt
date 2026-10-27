package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.PpClass
import com.thomaswcode.decrastination.core.PpSchedule
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Timetable
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStoreException
import java.time.Instant
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoresTest {
    private val dir: File = Files.createTempDirectory("stores").toFile()

    @Test
    fun `a store saves and loads its state`() = runTest {
        val file = File(dir, "settings.json")
        JsonStore(file, Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS)).update { it.copy(ankiTextbook = 2) }
        assertEquals(2, JsonStore(file, Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS)).value.ankiTextbook)
        assertFalse(File(dir, "settings.json.tmp").exists())
    }

    @Test
    fun `a store's copies are let go, deleted or, where that fails, emptied, and another file is left`() = runTest {
        val store = JsonStore(File(dir, "felix.json"), FelixStore.serializer(), ::FelixStore)
        val copies = listOf("felix.json.v1", "felix.json.unreadable-20261021-010000").map { File(dir, it).apply { writeText("{\"notes\":[]}") } }
        val others = listOf("felix.json.tmp", "tasks.json.v2").map { File(dir, it).apply { writeText("{}") } }
        // The folder closed to deletes, where the system allows it (not Windows, where a folder's read-only mark doesn't): each copy emptied instead.
        dir.setWritable(false)
        try {
            store.letGoOfCopies()
        } finally {
            dir.setWritable(true)
        }
        assertTrue(copies.all { !it.exists() || it.length() == 0L })
        assertTrue(others.all { it.exists() && it.length() > 0 })
        store.letGoOfCopies()
        assertTrue(copies.none { it.exists() })
    }

    @Test
    fun `the tasks keep the timetable they were last read with`() = runTest {
        val file = File(dir, "tasks.json")
        val timetable = Timetable("2026-08-24T00:00:00", listOf(PpClass("c", "Study Period", listOf(PpSchedule("2026-09-05T08:50:00", "2026-09-05T09:45:00", dayOfWeek = 2)))))
        JsonStore(file, TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS)).update { it.copy(timetable = timetable) }
        assertEquals(timetable, JsonStore(file, TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS)).value.timetable)
    }

    @Test
    fun `an unreadable file is set aside and the state starts afresh`() = runTest {
        val file = File(dir, "tasks.json").apply { writeText("{not json") }
        val store = JsonStore(file, TaskState.serializer(), ::TaskState, clock = { AT }, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS))
        assertEquals(TaskState(), store.value)
        val problem = store.problem!!
        assertEquals("tasks.json.unreadable-20261011-083000", problem.copy)
        assertEquals("{not json", File(dir, problem.copy!!).readText())
        assertFalse(problem.kept)
        // Not replaced till it's on record: a start cut short before then finds it again, with the same copy.
        assertEquals(problem.copy, JsonStore(file, TaskState.serializer(), ::TaskState, clock = { AT + 1000 }, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS)).problem?.copy)
        // On record: what it started from is the file now, and the next start reads it, with nothing to say.
        store.keepRecovered()
        assertNull(JsonStore(file, TaskState.serializer(), ::TaskState, clock = { AT + 2000 }, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS)).problem)
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("tasks.json.unreadable-") }!!.size)
        // A file that reads is no problem.
        assertNull(JsonStore(File(dir, "other.json").apply { writeText("{}") }, TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS)).problem)
    }

    @Test
    fun `of a file that doesn't read whole, each field that reads on its own is kept`() = runTest {
        // An app update that can no longer read one field (here, a list that isn't one) loses only it:
        // protection stays armed, the textbook stays 3.
        val file = File(dir, "settings.json").apply { writeText("""{"armed": true, "ankiTextbook": 3, "blockedApps": "not a list"}""") }
        val store = JsonStore(file, Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS), clock = { AT })
        assertTrue(store.value.armed)
        assertEquals(3, store.value.ankiTextbook)
        assertEquals(Settings().blockedApps, store.value.blockedApps)
        assertTrue(store.problem!!.kept)
        // Kept, and written back once on record: still armed at the next start, and nothing more to say.
        store.keepRecovered()
        val again = JsonStore(file, Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS), clock = { AT + 1000 })
        assertTrue(again.value.armed)
        assertNull(again.problem)
    }

    @Test
    fun `a file that can't be read at all starts from what's safer, where that's given`() {
        val file = File(dir, "settings.json").apply { writeText("garbage") }
        val store = JsonStore(file, Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS), unreadable = { Settings(armed = true) }, clock = { AT })
        assertTrue(store.value.armed)
        // A file that's simply not there yet is the default, as ever.
        assertFalse(JsonStore(File(dir, "new.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS), unreadable = { Settings(armed = true) }).value.armed)
    }

    @Test
    fun `a second unreadable file doesn't overwrite the first, and only the newest few are kept`() {
        val file = File(dir, "log.json")
        for (i in 0 until 5) {
            file.writeText("bad $i")
            JsonStore(file, ActivityLog.serializer(), ::ActivityLog, clock = { AT + i * 1000L }, migrations = listOf(ChatMigration.LOG, MemoryMigration.LOG, CreditMigration.LOG))
        }
        val copies = dir.listFiles { f -> f.name.startsWith("log.json.unreadable-") }.orEmpty().map { it.name }.sorted()
        assertEquals(JsonStore.COPIES_KEPT, copies.size)
        assertEquals("bad 4", File(dir, copies.last()).readText())
    }

    @Test
    fun `a copy just made is kept, whatever the clock said of the others`() {
        val file = File(dir, "log.json").apply { writeText("bad now") }
        // Three from a clock that was ahead: by name, each newer than this one.
        listOf("20270101-000000", "20270102-000000", "20270103-000000").forEach { File(dir, "log.json.unreadable-$it").writeText("bad then") }
        val store = JsonStore(file, ActivityLog.serializer(), ::ActivityLog, clock = { AT }, migrations = listOf(ChatMigration.LOG, MemoryMigration.LOG, CreditMigration.LOG))
        assertEquals("bad now", File(dir, store.problem!!.copy!!).readText())
        assertEquals(JsonStore.COPIES_KEPT, dir.listFiles { f -> f.name.startsWith("log.json.unreadable-") }!!.size)
    }

    @Test
    fun `unknown keys from another version are ignored`() {
        val file = File(dir, "settings.json").apply { writeText("""{"ankiTextbook": 3, "fromTheFuture": true}""") }
        assertEquals(3, JsonStore(file, Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS)).value.ankiTextbook)
    }

    @Test
    fun `the state and log a build with focus sessions wrote load whole, their sessions ignored`() {
        // As 2.0.0 wrote them (AR-09): a session under way, one ending, and the sessions' history.
        val runtime = File(dir, "runtime.json").apply {
            writeText(
                """{"credit": {"day": "2026-10-15", "earnedMs": 600000}, "overrideUntil": 5,
                   "session": {"taskId": "teams:t", "label": "t", "step": null, "minutes": 30, "startedAt": 1},
                   "finishing": [{"session": {"taskId": "teams:t", "label": "t", "step": null, "minutes": 30, "startedAt": 1}, "workedMin": 30, "completed": true, "endedAt": 2}]}""",
            )
        }
        // Read as at a start, through the moves: the credit is the session's now, the override protection's.
        val stores = Stores(dir, Reverse)
        assertNull(stores.runtime.problem)
        assertEquals(600_000L to 5L, stores.session.value.credit.earnedMs to stores.protection.value.overrideUntil)
        val log = File(dir, "log.json").apply {
            writeText(
                """{"sessions": [{"taskId": "teams:t", "kind": "Homework", "label": "t", "plannedMin": 30, "workedMin": 30, "startedAt": 1, "endedAt": 2, "completed": true}],
                   "blocks": [{"at": 3, "target": "com.google.android.youtube", "reason": "Session"}]}""",
            )
        }
        val history = JsonStore(log, ActivityLog.serializer(), ::ActivityLog, migrations = listOf(ChatMigration.LOG, MemoryMigration.LOG, CreditMigration.LOG))
        assertNull(history.problem)
        assertEquals(listOf("Session"), history.value.blocks.map { it.reason })
    }

    /** Stands in for the Keystore: reverses the bytes, so the file is at least not the plain JSON. */
    @Test
    fun `every store Stores makes says what a backup keeps of it and which resets change it`() {
        val stores = Stores(dir, Reverse)
        // Each JsonStore and the secrets, by their files: none made and left out of the declarations.
        val made = Stores::class.java.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            when (val store = field.get(stores)) {
                is JsonStore<*> -> store.name
                is SecretStore -> "secrets.bin"
                is ChatArchiveStore -> store.name
                else -> null
            }
        }.toSet()
        assertEquals(made, stores.all.map { it.file }.toSet())
        assertEquals(stores.all.size, stores.all.map { it.file }.toSet().size)
        // Every JSON store's shape is known to a backup, at the version its state says, each with a migration from every one before.
        assertEquals(made - "secrets.bin", stores.shapes.keys)
        assertEquals(mapOf("calendar.json" to 2, "chat.json" to 8, "chat-archive.json" to 7, "instructions.json" to 4, "log.json" to 4, "memory.json" to 2, "runtime.json" to 3, "session.json" to 3, "settings.json" to 2, "tasks.json" to 3), stores.shapes.filterValues { it.version > 1 }.mapValues { it.value.version })
        assertTrue(stores.shapes.values.all { Migration.complete(it.version, it.migrations) })
        // And each is said in words when it can't be read, not by its file's name.
        for (file in made) assertTrue(!JsonStore.Unreadable(file, null, kept = false, at = 0).says.startsWith(file), file)
    }

    @Test
    fun `two tasks said not to be the same work are kept once a pair, the newest so many`() {
        val said = RuntimeState().withNotSame("teams:a", "gmail:b").withNotSame("gmail:b", "teams:a")
        assertEquals(1, said.notSame.size)
        val many = (1..RuntimeState.MAX_NOT_SAME + 5).fold(said) { s, n -> s.withNotSame("own:$n", "teams:a") }
        assertEquals(RuntimeState.MAX_NOT_SAME, many.notSame.size)
        assertFalse(said.notSame.single() in many.notSame)
    }

    @Test
    fun `a file found unreadable is said with its copy only while the copy's there, which a reset takes`() = runTest {
        File(dir, "memory.json").writeText("{not json")
        val stores = Stores(dir, Reverse)
        val found = stores.problems.single()
        assertTrue("kept as ${found.copy}" in found.says(stores::holds))
        stores.letGoOfCopies(Reset.Memories)
        assertFalse(stores.holds(found.copy!!))
        assertEquals(found.copy(copy = null).says, found.says(stores::holds))
        assertFalse("kept as" in found.says(stores::holds))
    }

    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }

    @Test
    fun `a replace refused for a moment is tried again, and one refused every time says so`() {
        val file = File(dir, "moved.json").apply { writeText("before") }
        var refusals = 2
        JsonStore.moveOver(File(dir, "moved.json.tmp").apply { writeText("after") }, file) { from, to ->
            if (refusals-- > 0) throw AccessDeniedException(to.toString())
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
        assertEquals("after", file.readText())
        assertFalse(File(dir, "moved.json.tmp").exists())
        // Refused every time: the last refusal thrown, after more than one try, the file as it was.
        var tries = 0
        assertFailsWith<AccessDeniedException> {
            JsonStore.moveOver(File(dir, "moved.json.tmp").apply { writeText("later") }, file) { _, to ->
                tries++
                throw AccessDeniedException(to.toString())
            }
        }
        assertTrue(tries > 1)
        assertEquals("after", file.readText())
    }

    @Test
    fun `the moves between stores are the chat's, the tasks' and the runtime's split, and a backup's parts are moved too`() {
        // A move runs on the stores' files at start; a backup from before it holds the fields where they were.
        // Backups.upgraded makes the chat's move on a backup's parts (ChatMigrationTest restores one from
        // before it), and the tasks' (its instructions marked Felix's from the chat it carries: the steps it
        // ties together are a source's task's, which a backup doesn't hold; TasksMigrationTest); the runtime's
        // split moves a backup's parts by where Backups.PARTS puts them (RuntimeSplitTest); another move
        // must be taught to it as well, and this test changed with it (D-94). The archive's split into months
        // (4.0.4) moves no field: a backup holds the archive whole, as before.
        assertEquals(listOf(ChatMigration.MOVE, ChatArchiveStore.SPLIT, TasksMigration.MOVE, RuntimeSplit.MOVE), Stores.MOVES, "A new move between stores: apply it to a backup's parts in Backups.upgraded too")
    }

    @Test
    fun `secrets are kept encrypted, and blank removes one`() = runTest {
        val file = File(dir, "secrets.bin")
        val store = SecretStore(file, Reverse)
        store.put(mapOf(Secret.GmailAddress to "me@example.com", Secret.GmailAppPassword to "abcd efgh"))
        assertFalse("abcd efgh" in file.readText())
        val again = SecretStore(file, Reverse)
        assertEquals("abcd efgh", again[Secret.GmailAppPassword])
        assertEquals(setOf(Secret.GmailAddress, Secret.GmailAppPassword), again.present.value)
        again.put(Secret.GmailAppPassword, " ")
        assertNull(SecretStore(file, Reverse)[Secret.GmailAppPassword])
    }

    @Test
    fun `secrets that can't be decrypted read as none`() {
        val file = File(dir, "secrets.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val broken = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(sealed: ByteArray): ByteArray = throw AEADBadTagException()
        }
        val store = SecretStore(file, broken, { AT }, retryWaitMs = 0)
        assertTrue(store.present.value.isEmpty())
        // Told apart from none saved, and a copy set aside before a save can overwrite it.
        assertEquals("secrets.bin.unreadable-20261011-083000", store.problem!!.copy)
        assertTrue(File(dir, "secrets.bin.unreadable-20261011-083000").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        // Left where it is, for a keystore that comes back; found again at the next start, with the
        // same copy, not another.
        assertTrue(file.exists())
        val again = SecretStore(file, broken, { AT + 1000 }, retryWaitMs = 0).problem!!
        assertEquals(store.problem!!.copy, again.copy)
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("secrets.bin.unreadable-") }!!.size)
        // Said till it's on record, once, and not after you've seen it.
        val listed = RuntimeState().withUnreadable(listOf(store.problem!!)).withUnreadable(listOf(again))
        assertEquals(1, listed.unreadable.size)
        val seen = listed.seenUnreadable(listed.unreadable.single())
        assertTrue(seen.withUnreadable(listOf(again)).unreadable.isEmpty())
        assertNull(SecretStore(File(dir, "none.bin"), broken).problem)
    }

    @Test
    fun `a keystore that fails for a moment loses nothing`() = runTest {
        val file = File(dir, "secrets.bin")
        SecretStore(file, Reverse).put(Secret.GmailAppPassword, "abcd efgh")
        // Fails once, then works: read on the second try, nothing set aside, nothing said.
        var calls = 0
        val flaky = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = Reverse.encrypt(plain)
            override fun decrypt(sealed: ByteArray): ByteArray = if (calls++ == 0) throw KeyStoreException("busy") else Reverse.decrypt(sealed)
        }
        val store = SecretStore(file, flaky, retryWaitMs = 0)
        assertEquals("abcd efgh", store[Secret.GmailAppPassword])
        assertNull(store.problem)
        // Failing at every try that start, then back: the file was left, so the next start reads it all.
        val down = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = Reverse.encrypt(plain)
            override fun decrypt(sealed: ByteArray): ByteArray = throw KeyStoreException("down")
        }
        assertTrue(SecretStore(file, down, retryWaitMs = 0).present.value.isEmpty())
        assertEquals("abcd efgh", SecretStore(file, Reverse)[Secret.GmailAppPassword])
    }

    @Test
    fun `a file that can't be read at all, rather than decoded, isn't written over`() {
        // A directory where the file should be: reading it fails, as an I/O error would.
        val file = File(dir, "runtime.json").apply { mkdirs() }
        val store = JsonStore(file, Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS), clock = { AT })
        assertEquals(Settings(), store.value)
        assertTrue(file.isDirectory)
    }

    private companion object {
        /** 11 Oct 2026, 08:30 UTC. */
        val AT = Instant.parse("2026-10-11T08:30:00Z").toEpochMilli()
    }
}
