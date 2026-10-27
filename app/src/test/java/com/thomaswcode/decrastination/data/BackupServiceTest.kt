package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Act
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest

/**
 * Backups and resets through the backup service (AR-07, LR-08), on phones whose stores are in temporary
 * folders: a full one, every store as the fixtures have it, and an empty one; a folder that keeps
 * a map; and what isn't the backup's own counted.
 */
class BackupServiceTest {
    private val clock = FixedClock(Fixtures.at("2026-10-18T12:00"), Fixtures.LONDON)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)

    /** A phone: its stores in a folder of its own, every one of the fixtures' newest shape in it when [full]. */
    private inner class Phone(full: Boolean, cap: Int = Backups.MAX_BYTES) {
        val dir: File = Files.createTempDirectory("phone").toFile().also { dir ->
            if (full) Fixtures.file("stores").listFiles { f -> f.isDirectory }.orEmpty().maxBy { it.name }.listFiles().orEmpty().forEach { it.copyTo(File(dir, it.name)) }
        }
        val stores = Stores(dir, Reverse, clock = { clock.time })
        val folder = Folder()
        var laid = 0
        var scheduled = 0
        var reread = 0
        var cancelled = 0
        val service = BackupService(
            stores, clock, scope, folder, File(dir, "backups"), "test",
            // Unarmed, as a change made here at once.
            changeSettings = { change -> stores.replace(stores.settings, change) },
            applying = Mutex(),
            layInstructions = { laid++ },
            layTaskOverrides = { laid++ },
            schedule = { scheduled++ },
            reread = { reread++ },
            cancelFelix = { cancelled++ },
            backupCap = cap,
        )

        /** Every JSON store's state, by file. */
        fun states(): Map<String, Any?> = mapOf(
            "tasks.json" to stores.tasks.value, "settings.json" to stores.settings.value, "runtime.json" to stores.runtime.value, "log.json" to stores.log.value,
            "session.json" to stores.session.value, "protection.json" to stores.protection.value, "model.json" to stores.model.value,
            "anki.json" to stores.anki.value, "learning.json" to stores.learning.value,
            "instructions.json" to stores.instructions.value, "chat.json" to stores.chat.value, "chat-archive.json" to stores.chatArchive.value, "school.json" to stores.school.value,
            "memory.json" to stores.memory.value, "felix.json" to stores.felix.value, "calendar.json" to stores.calendar.value,
        )
    }

    /** What's launched on the service's scope (the calendar read again after a restore): waited for. */
    private fun settle() = runBlocking { job.children.toList().joinAll() }

    @Test
    fun `a backup written and read back into an emptier phone brings back all it keeps`() = runTest {
        val full = Phone(full = true)
        val empty = Phone(full = false)
        val said = empty.service.importBackup(full.service.exportBackup())
        settle()
        assertTrue(said.startsWith("Restored the settings"), said)
        val was = full.stores
        val now = empty.stores
        assertEquals(was.instructions.value.applied, now.instructions.value.applied)
        assertEquals(was.tasks.value.tasks.filter { it.source == Source.Own }.map { it.id }, now.tasks.value.tasks.map { it.id })
        assertEquals(was.school.value, now.school.value)
        assertEquals(was.memory.value, now.memory.value)
        // The chat but the message of yours still waiting, which would be sent with words from then, and said so.
        assertEquals(was.chat.value.messages.dropLast(1), now.chat.value.messages)
        assertTrue("1 of yours that waited for an answer left out" in said, said)
        assertEquals(was.chat.value.felix, now.chat.value.felix)
        assertEquals(was.chatArchive.value, now.chatArchive.value)
        assertEquals(was.felix.value, now.felix.value)
        assertEquals(was.learning.value.calibration, now.learning.value.calibration)
        assertEquals(was.calendar.value.eventAnswers, now.calendar.value.eventAnswers)
        assertEquals(was.session.value.slots, now.session.value.slots)
        assertEquals(was.model.value.aiUsage.spentUsd, now.model.value.aiUsage.spentUsd, 1e-9)
        // Every record, in the order a merge keeps them: each list by its time.
        assertEquals(Backups.mergeLog(ActivityLog(), was.log.value, clock.time), now.log.value)
        // The settings, but this phone's protection, key and calendars.
        assertEquals(Backups.importedSettings(Settings(), Backups.decode(full.service.exportBackup(), full.stores.shapes)!!), now.settings.value)
        assertEquals(listOf(false, false), listOf(now.settings.value.armed, now.settings.value.aiKeyActive))
        assertTrue(empty.laid > 0 && empty.scheduled == 1 && empty.reread == 1)
        // Every store a backup keeps something of has it back; those it keeps nothing of, nothing.
        val states = empty.states()
        for (kept in now.all.filter { it.file in states }) {
            val default = Phone(full = false).states().getValue(kept.file)
            if (kept.backup != null) assertNotEquals(default, states.getValue(kept.file), "${kept.file}: the backup says it keeps ${kept.backup}") else assertEquals(default, states.getValue(kept.file), kept.file)
        }
    }

    @Test
    fun `a backup says each store's version, and reads back by them`() {
        val full = Phone(full = true)
        val backup = Backups.decode(full.service.exportBackup(), full.stores.shapes)!!
        assertEquals(full.stores.shapes.mapValues { it.value.version }, backup.versions)
        assertEquals(setOf("calendar.json", "chat.json", "chat-archive.json", "instructions.json", "log.json", "memory.json", "runtime.json", "session.json", "settings.json", "tasks.json"), backup.versions.filterValues { it > 1 }.keys)
    }

    @Test
    fun `a reset keeps a copy first, takes what it says, and its copy brings it back`() = runTest {
        val phone = Phone(full = true)
        val log = phone.stores.log.value
        val calibration = phone.stores.learning.value.calibration
        val said = phone.service.reset(Reset.Stats)
        val copy = phone.folder.files.keys.single { it.startsWith("reset-2026-10-18-") }
        assertTrue(said.endsWith("Documents/Decrastination/$copy."), said)
        assertEquals(ActivityLog(), phone.stores.log.value)
        assertEquals(Calibration(), phone.stores.learning.value.calibration)
        assertEquals(1, phone.service.resets.get())
        phone.service.importBackup(phone.folder.files.getValue(copy))
        settle()
        assertEquals(Backups.mergeLog(ActivityLog(), log, clock.time), phone.stores.log.value)
        assertEquals(calibration, phone.stores.learning.value.calibration)
    }

    @Test
    fun `a reset of your changes keeps every instruction in its copy, in use or not, and its copy brings each back as it was`() = runTest {
        val phone = Phone(full = true)
        val reading = Instruction("being-read", "skip the essay on Friday", at = clock.time, state = InstructionStatus.Reading)
        val unclear = Instruction("left-unclear", "the usual", at = clock.time, state = InstructionStatus.Unclear, note = "It can't be told which task")
        phone.stores.replace(phone.stores.instructions) { it.copy(instructions = it.instructions + reading + unclear) }
        val before = phone.stores.instructions.value.instructions
        assertTrue(before.any { it.state == InstructionStatus.Applied })
        phone.service.reset(Reset.Instructions)
        assertTrue(phone.stores.instructions.value.instructions.isEmpty())
        val copy = phone.folder.files.keys.single { it.startsWith("reset-") }
        val said = phone.service.importBackup(phone.folder.files.getValue(copy))
        settle()
        assertEquals(before.map { it.id }.toSet(), phone.stores.instructions.value.instructions.map { it.id }.toSet())
        assertEquals(reading, phone.stores.instructions.value.instructions.single { it.id == "being-read" })
        assertEquals(unclear, phone.stores.instructions.value.instructions.single { it.id == "left-unclear" })
        // Those of the fixtures' too: every one not in use, said.
        val notInUse = before.count { it.state != InstructionStatus.Applied }
        assertTrue(notInUse > 2 && "$notInUse not in use back as they were" in said, said)
        // The nightly backup keeps those in use alone, as before.
        assertTrue(Backups.decode(phone.service.exportBackup(), phone.stores.shapes)!!.instructions.all { it.state == InstructionStatus.Applied })
    }

    @Test
    fun `a reset whose copy would be too big to restore, even without the chat's earlier messages, takes nothing`() = runTest {
        val phone = Phone(full = true, cap = 2_000)
        val before = phone.states()
        assertEquals("Nothing was reset: a copy of it would be too big to restore.", phone.service.reset(Reset.Instructions))
        assertEquals(before, phone.states())
        assertTrue(phone.folder.files.isEmpty())
    }

    @Test
    fun `a reset whose copy Import would refuse takes nothing`() = runTest {
        val phone = Phone(full = true)
        // Read and waiting, its due date further ahead than a backup's instructions are let hold.
        val far = Instruction("far-off", "the trip's form", at = clock.time, state = InstructionStatus.Understood, changes = listOf(Change(ChangeType.DueBy, taskId = "own:far", time = clock.time + 500 * 86_400_000L)))
        phone.stores.replace(phone.stores.instructions) { it.copy(instructions = it.instructions + far) }
        val before = phone.states()
        val said = phone.service.reset(Reset.Instructions)
        assertTrue(said.startsWith("Nothing was reset: a copy of it couldn't be restored ("), said)
        assertEquals(before, phone.states())
        assertTrue(phone.folder.files.isEmpty())
    }

    @Test
    fun `a reset of the chat whose copy has no room for its earliest messages keeps them whole beside it`() = runTest {
        // Room for all but the archive's earliest messages: the rest of the copy fits, as it must to be read back.
        val small = Phone(full = true, cap = 106_000)
        val archive = ChatArchive((1..80).map { ChatMessage("a$it", Speaker.You, "x".repeat(1_000), it.toLong(), answeredAt = it.toLong()) })
        small.stores.replace(small.stores.chatArchive, archive)
        val said = small.service.reset(Reset.Chat)
        val kept = small.folder.files.keys.single { it.startsWith("chat-archive-") }
        assertTrue("whole in Documents/Decrastination/$kept" in said, said)
        assertEquals(archive, JsonStore.json.decodeFromString(ChatArchive.serializer(), small.folder.files.getValue(kept)))
        assertEquals(ChatArchive(), small.stores.chatArchive.value)
        // Not a reset's copy to restore from.
        assertTrue(small.service.resetCopies().none { it.startsWith("chat-archive-") })
    }

    @Test
    fun `each reset takes the copies kept of every store it clears whole, and of no other`() = runTest {
        for (area in Reset.entries) {
            val phone = Phone(full = true)
            // Each JSON store's copies: an older shape's, and one of a file that couldn't be read.
            val copies = phone.stores.all.map { it.file }.filter { it.endsWith(".json") }.associateWith { name ->
                listOf("$name.v1", "$name.unreadable-20261021-010000").map { File(phone.dir, it).apply { writeText("{}") } }
            }
            phone.service.reset(area)
            val said = phone.stores.all.filter { kept -> kept.clears.any { area == it || area == Reset.Everything } }.map { it.file }.toSet()
            for ((name, files) in copies) {
                if (name in said) assertTrue(files.none { it.exists() }, "$area: $name") else assertTrue(files.all { it.exists() }, "$area: $name")
            }
            // A memory reset clears only what the settings say about you: their copies stay, as they hold the rest.
            if (area == Reset.Memories || area == Reset.Everything) assertTrue(copies.getValue("settings.json").all { it.exists() }, "$area")
        }
    }

    @Test
    fun `a reset of the chat takes the copies its archive's months were kept as, with the months`() = runTest {
        val phone = Phone(full = true)
        phone.stores.replace(phone.stores.chatArchive, ChatArchive(listOf(ChatMessage("a1", Speaker.You, "hello", Fixtures.at("2026-08-03T10:00"), answeredAt = 1))))
        val copy = File(phone.dir, "chat-archive-2026-08.json.v4").apply { writeText("{\"messages\":[]}") }
        phone.service.reset(Reset.Chat)
        assertEquals(ChatArchive(), phone.stores.chatArchive.value)
        assertFalse(copy.exists())
    }

    @Test
    fun `restored into a chat that isn't empty, his messages it hasn't come back with your replies to them`() = runTest {
        val from = Phone(full = false)
        val nudge = ChatMessage("n1", Speaker.Felix, "Ten minutes in: starting?", 100, kind = MessageKind.SlotStart)
        val reply = ChatMessage("r1", Speaker.You, "yes", 110, replyTo = "n1", answeredAt = 120)
        val talk = ChatMessage("t1", Speaker.You, "what's due?", 130, answeredAt = 140)
        from.stores.replace(from.stores.chat, ChatState(listOf(nudge, reply, talk)))
        val to = Phone(full = false)
        val here = ChatMessage("h1", Speaker.You, "hello", 200, answeredAt = 210)
        to.stores.replace(to.stores.chat, ChatState(listOf(here)))
        val said = to.service.importBackup(from.service.exportBackup())
        settle()
        assertEquals(listOf("n1", "r1", "h1"), to.stores.chat.value.messages.map { it.id })
        assertTrue("1 message of Felix's into the chat" in said, said)
    }

    @Test
    fun `restored into a chat that isn't empty, his messages read there are read here, and what's unread stays so`() = runTest {
        val from = Phone(full = false)
        val read = ChatMessage("n1", Speaker.Felix, "Ten minutes in: starting?", 100, kind = MessageKind.SlotStart)
        val unread = ChatMessage("n2", Speaker.Felix, "Half an hour left.", 400, kind = MessageKind.SlotEnd)
        from.stores.replace(from.stores.chat, ChatState(listOf(read, unread), readAt = 300))
        val to = Phone(full = false)
        val here = ChatMessage("h1", Speaker.Felix, "Kept the slot: nice.", 220, kind = MessageKind.SlotKept)
        to.stores.replace(to.stores.chat, ChatState(listOf(here), readAt = 50))
        to.service.importBackup(from.service.exportBackup())
        settle()
        val chat = to.stores.chat.value
        assertEquals(listOf("n1", "h1", "n2"), chat.messages.map { it.id })
        // Read up to just before this phone's own unread: n1 read, as it was there; h1 and n2 not.
        assertEquals(219L, chat.readAt)
    }

    @Test
    fun `what's remembered is merged once what's run out on either side is let go of, so a stale one here doesn't win`() = runTest {
        val hour = 3_600_000L
        val from = Phone(full = false)
        from.stores.replace(from.stores.memory, MemoryState(facts = listOf(Fact("cold", "better now", clock.time - hour)), rules = listOf(Rule("be blunter", clock.time - hour))))
        val to = Phone(full = false)
        to.stores.replace(to.stores.memory, MemoryState(facts = listOf(Fact("cold", "feeling rough", clock.time - 2 * hour, until = clock.time - 1)), rules = listOf(Rule("Be blunter", clock.time - 2 * hour, until = clock.time - 1))))
        to.service.importBackup(from.service.exportBackup())
        settle()
        assertEquals(listOf("better now"), to.stores.memory.value.facts.map { it.text })
        assertEquals(listOf(Rule("be blunter", clock.time - hour)), to.stores.memory.value.rules)
    }

    @Test
    fun `a fact whose task the backup's chat can still bring back is kept, into an empty phone`() = runTest {
        val hour = 3_600_000L
        val from = Phone(full = false)
        val gone = TaskItem(id = "own:gone", source = Source.Own, sourceId = "gone", title = "A task since deleted", kind = Kind.Admin, firstSeenAt = clock.time - hour, lastSeenAt = clock.time - hour)
        // Its delete's act in the chat, not undone: its Undo brings the task back.
        val act = Act("Deleted “A task since deleted”.", action = ChatAction(ChatAction.Type.Delete, gone.id), was = gone)
        from.stores.replace(from.stores.chat, ChatState(listOf(ChatMessage("f1", Speaker.Felix, "Deleted it.", clock.time - hour, acts = listOf(act)))))
        from.stores.replace(from.stores.memory, MemoryState(facts = listOf(Fact("deadline", "the essay can be late", clock.time - hour, taskId = gone.id))))
        val to = Phone(full = false)
        to.service.importBackup(from.service.exportBackup())
        settle()
        assertEquals(listOf("deadline"), to.stores.memory.value.facts.map { it.topic })
    }

    @Test
    fun `where Documents can't take the copy, it goes in the app's own files, listed and restored from there`() = runTest {
        val phone = Phone(full = true)
        phone.folder.available = false
        val memory = phone.stores.memory.value
        val said = phone.service.reset(Reset.Memories)
        val name = phone.service.resetCopies().single()
        assertTrue(said.contains("the app's own files ($name)"), said)
        assertTrue(phone.stores.memory.value.facts.isEmpty() && phone.stores.settings.value.profileLines.isEmpty())
        phone.service.restoreResetCopy(name)
        assertEquals(memory, phone.stores.memory.value)
        assertEquals("That copy isn't there.", phone.service.restoreResetCopy("reset-elsewhere.json"))
    }

    @Test
    fun `each reset takes from the stores Stores says it does, and from no other`() = runTest {
        for (area in Reset.entries) {
            val phone = Phone(full = true)
            val before = phone.states()
            phone.service.reset(area)
            val after = phone.states()
            val changed = before.keys.filter { before[it] != after[it] }.toSet()
            val said = phone.stores.all.filter { kept -> kept.resets.any { area == it || area == Reset.Everything } }.map { it.file }.toSet()
            assertEquals(said, changed, "$area")
            if (area == Reset.Chat || area == Reset.Everything) assertEquals(1, phone.cancelled)
            if (area == Reset.Instructions || area == Reset.Everything) assertTrue(phone.laid > 0)
        }
    }

    @Test
    fun `the nightly backup writes the day's file, keeps the newest fortnight and a reset's copies, and notes when`() = runTest {
        val phone = Phone(full = true)
        (1..15).forEach { phone.folder.files["decrastination-2026-09-%02d.json".format(it)] = "{}" }
        phone.folder.files["reset-2026-09-01-120000.json"] = "{}"
        assertEquals(BackupService.Nightly.Written, phone.service.backupNow())
        val nightly = phone.folder.files.keys.filter { it.startsWith("decrastination-") }
        assertEquals(BackupFolder.KEEP, nightly.size)
        assertTrue("decrastination-2026-10-18.json" in nightly && "decrastination-2026-09-01.json" !in nightly && "decrastination-2026-09-02.json" !in nightly)
        assertTrue("reset-2026-09-01-120000.json" in phone.folder.files)
        assertEquals(clock.time, phone.stores.runtime.value.lastBackupAt)
        phone.folder.available = false
        assertEquals(BackupService.Nightly.NotWritten, phone.service.backupNow())
    }

    @Test
    fun `a nightly backup more than Import reads isn't written, and the older ones are kept`() = runTest {
        val phone = Phone(full = true, cap = 2_000)
        (1..15).forEach { phone.folder.files["decrastination-2026-09-%02d.json".format(it)] = "{}" }
        val before = phone.folder.files.toMap()
        val last = phone.stores.runtime.value.lastBackupAt
        assertTrue(phone.service.tooBig(phone.service.exportBackup()))
        assertEquals(BackupService.Nightly.TooBig, phone.service.backupNow())
        assertEquals(before, phone.folder.files)
        assertEquals(last, phone.stores.runtime.value.lastBackupAt)
    }

    @Test
    fun `a restore brings back none of yours that waited for an answer, or for its Try again, and says how many it left out`() = runTest {
        val from = Phone(full = false)
        val nudge = ChatMessage("n1", Speaker.Felix, "Ten minutes in: starting?", 100, kind = MessageKind.SlotStart)
        val first = ChatMessage("w1", Speaker.You, "move maths to tomorrow", 110, note = "Cancelled", stuck = true)
        val second = ChatMessage("w2", Speaker.You, "and remind me at six", 120, replyTo = "n1")
        from.stores.replace(from.stores.chat, ChatState(listOf(nudge, first, second)))
        val backup = from.service.exportBackup()
        // Into an empty chat: his message back, neither of yours, so nothing's sent as it opens, nor with your next words.
        val empty = Phone(full = false)
        val said = empty.service.importBackup(backup)
        settle()
        assertEquals(listOf("n1"), empty.stores.chat.value.messages.map { it.id })
        assertEquals(null, empty.stores.chat.value.unanswered)
        assertTrue("2 of yours that waited for an answer left out" in said, said)
        // Into one that isn't: his message back, not your reply to it still waiting.
        val other = Phone(full = false)
        other.stores.replace(other.stores.chat, ChatState(listOf(ChatMessage("h1", Speaker.You, "hello", 200, answeredAt = 210))))
        val told = other.service.importBackup(backup)
        settle()
        assertEquals(listOf("n1", "h1"), other.stores.chat.value.messages.map { it.id })
        assertEquals(null, other.stores.chat.value.unanswered)
        assertTrue("1 of yours that waited for an answer left out" in told, told)
    }

    /** A folder that keeps a map, by name. */
    private class Folder : BackupService.Folder {
        override var available = true
        val files = LinkedHashMap<String, String>()

        override fun write(name: String, text: String): Boolean {
            files[name] = text
            return true
        }

        override fun names(): List<String> = files.keys.toList()

        override fun delete(name: String): Boolean = files.remove(name) != null
    }

    /** A cipher for the secrets that only reverses the bytes: no keystore on a PC. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
}
