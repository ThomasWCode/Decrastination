package com.thomaswcode.decrastination.felix

import android.app.Notification
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.FelixState
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.ChatArchiveStore
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.FelixStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.OnlyMigration
import com.thomaswcode.decrastination.data.MoodMigration
import com.thomaswcode.decrastination.data.SpeechMigration
import com.thomaswcode.decrastination.data.OutsideMigration
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.MemoryMigration
import com.thomaswcode.decrastination.data.StepOrderMigration
import com.thomaswcode.decrastination.data.TasksMigration
import com.thomaswcode.decrastination.data.ZoomMigration
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notices
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

/**
 * Felix's outbox (docs/spec/felix.md §Notifications and replies): every message in the chat before his notification
 * is made from it; his one notification rebuilt from the chat's unread, not added to; nothing while the
 * chat's on screen or his channel's off; the kinds that wait for the morning left out in quiet hours;
 * the live window moved to the archive, never an unread message, nothing doubled or lost.
 */
class OutboxTest {
    private val dir: File = Files.createTempDirectory("outbox").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T18:00"), Fixtures.LONDON)
    private val chat = JsonStore(File(dir, "chat.json"), ChatState.serializer(), ::ChatState, migrations = listOf(ChatMigration.CHAT, TasksMigration.CHAT, FelixStore.CHAT, FelixStore.CHAT_PIECES, MemoryMigration.CHAT, StepOrderMigration.CHAT, OnlyMigration.CHAT, OutsideMigration.CHAT, MoodMigration.CHAT, SpeechMigration.CHAT))
    private val archive = ChatArchiveStore(dir)
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS, ZoomMigration.SETTINGS, SpeechMigration.SETTINGS))
    private var onScreen = false
    private var shown = true
    /** What his notification showed as each was made, with the chat as it was then. */
    private val made = mutableListOf<Pair<Outbox.Notice, ChatState>>()
    private val ports = object : FelixHarness.Ports() {
        override fun show(notice: Outbox.Notice) {
            made += notice to chat.value
            super.show(notice)
        }
    }
    private val outbox = Outbox(chat, archive, settings, Channel(), ports, { onScreen }, clock)

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun `a question answered as the window's move writes the archive moves as it now is, not as it was copied`() = runBlocking {
        val now = clock.now()
        val question = ChatMessage("q", Speaker.Felix, "Which exercise is due?", now - Outbox.WINDOW_MS - 60_000L, About(taskId = "teams:sheet"), kind = MessageKind.Question)
        val newer = (1..Outbox.WINDOW).map { ChatMessage("new-$it", Speaker.You, "a message", now - it, answeredAt = now) }
        chat.update { ChatState(listOf(question) + newer, readAt = now) }
        // The archive's writer held, so the move has copied the question and waits to write it.
        val writing = ChatArchiveStore::class.java.getDeclaredField("writing").apply { isAccessible = true }.get(archive) as Mutex
        writing.lock()
        val move = launch(start = CoroutineStart.UNDISPATCHED) { outbox.moveWindow(now) }
        try {
            // Answered meanwhile, as a reply keeps it: in the live window, as it still is there.
            chat.update { s -> s.copy(messages = s.messages.map { if (it.id == question.id) it.copy(answeredAt = now) else it }) }
            outbox.answeredInArchive(question.id, now)
        } finally {
            writing.unlock()
        }
        move.join()
        // Changed since it was copied, it stays, answered, to move as it is at the next move.
        assertEquals(now, chat.value.messages.single { it.id == question.id }.answeredAt)
        outbox.moveWindow(now)
        assertTrue(chat.value.messages.none { it.id == question.id })
        assertEquals(now, archive.find(question.id)?.answeredAt)
    }

    private inner class Channel : Notices {
        override fun post(id: NoticeId, notification: Notification, tag: String?) = Unit
        override fun cancel(id: NoticeId, tag: String?) = Unit
        override fun shown(channel: String) = shown
        override suspend fun postHeld() = Unit
    }

    private fun his(id: String, at: Long = clock.now(), kind: MessageKind = MessageKind.SlotStart, text: String = "message $id") = ChatMessage(id, Speaker.Felix, text, at, kind = kind)

    @Test
    fun `a message is in the chat before his notification is made from it, and it sounds for it`() = runBlocking {
        outbox.send(his("a"))
        val (notice, then) = made.single()
        assertEquals(listOf("a"), then.messages.map { it.id })
        assertEquals(listOf("a"), notice.lines.map { it.id })
        assertTrue(notice.alert)
        assertEquals("a" to "a", notice.replyTo to notice.openAt)
    }

    @Test
    fun `his notification is made again from the chat, holding every unread one, not added to`() = runBlocking {
        outbox.send(his("a", clock.now() - 60_000L))
        outbox.send(his("b"))
        val notice = ports.showing!!
        assertEquals(listOf("a", "b"), notice.lines.map { it.id })
        // A reply answers the newest; a tap opens the chat at the first.
        assertEquals("b" to "a", notice.replyTo to notice.openAt)
        // Read up to the first: only the second's left.
        chat.update { it.copy(readAt = clock.now() - 30_000L) }
        outbox.notify(clock.now())
        assertEquals(listOf("b"), ports.showing!!.lines.map { it.id })
        assertFalse(ports.showing!!.alert)
        // All read: taken away.
        chat.update { it.copy(readAt = clock.now()) }
        outbox.notify(clock.now())
        assertNull(ports.showing)
    }

    @Test
    fun `his notification keeps to a room that fits above its Reply, the newest first, each cut at a sentence`() = runBlocking {
        val long = "This sentence runs to about sixty characters, give or take. ".repeat(4).trim()
        for (i in 1..4) outbox.send(his("m$i", clock.now() - (5 - i) * 60_000L, text = "$long Number $i."))
        val notice = ports.showing!!
        // The newest whole, as it fits; the one before it ended at a whole sentence; the oldest left to the chat.
        assertEquals(listOf("m3", "m4"), notice.lines.map { it.id })
        assertEquals("$long Number 4.", notice.lines.last().text)
        assertTrue(notice.lines.first().text.endsWith("take.") && notice.lines.sumOf { it.text.length } <= Outbox.NOTICE_ROOM)
        // A reply still answers the newest, and a tap opens the chat at the first unread, kept out or not.
        assertEquals("m4" to "m1", notice.replyTo to notice.openAt)
    }

    @Test
    fun `a reply of yours to one of his still unread stays in the window with it, so it stays seen`() {
        val old = clock.now() - Outbox.WINDOW_MS - 60_000L
        val his = his("a", old)
        val reply = ChatMessage("y", Speaker.You, "on it", old + 1, replyTo = "a")
        val newer = (1..Outbox.WINDOW).map { his("n$it", clock.now() - it) }
        val state = ChatState(listOf(his, reply) + newer, readAt = old - 1)
        // His still unread, so it stays; your reply to it stays with it.
        assertTrue(Outbox.past(state, clock.now()).none { it.id == "a" || it.id == "y" })
        assertEquals(setOf("a"), Outbox.repliedTo(state))
    }

    @Test
    fun `a tap opens the chat past a message you've replied to from his notification`() = runBlocking {
        outbox.send(his("a", clock.now() - 120_000L))
        chat.update { it.copy(messages = it.messages + ChatMessage("y", Speaker.You, "on it", clock.now() - 60_000L, replyTo = "a")) }
        outbox.notify(clock.now())
        // Still shown, with your reply under it, while he answers; nothing else of his: the tap opens at it.
        assertEquals(listOf("a", "y") to "a", ports.showing!!.lines.map { it.id } to ports.showing!!.openAt)
        outbox.send(his("b"))
        assertEquals("b", ports.showing!!.openAt)
    }

    @Test
    fun `nothing's posted while the chat's on screen, nor with his channel off, and his silences never`() = runBlocking {
        onScreen = true
        outbox.send(his("a"))
        assertNull(ports.showing)
        assertEquals(listOf("a"), chat.value.messages.map { it.id })
        onScreen = false
        shown = false
        outbox.send(his("b"))
        assertNull(ports.showing)
        shown = true
        outbox.send(his("silent", text = ""))
        // Made again: the two unread with words, not sounding for a silence.
        assertEquals(listOf("a", "b"), ports.showing!!.lines.map { it.id })
        assertFalse(ports.showing!!.alert)
    }

    @Test
    fun `the review, the summary and a calendar question wait for the morning in quiet hours, in the chat at once`() = runBlocking {
        clock.time = Fixtures.at("2026-10-18T23:30")
        outbox.send(his("review", kind = MessageKind.Review))
        outbox.send(his("event", kind = MessageKind.EventQuestion))
        assertEquals(listOf("review", "event"), chat.value.messages.map { it.id })
        assertNull(ports.showing)
        // His looks don't come in quiet hours, but a note of the app's does, and shows.
        outbox.send(his("note", kind = MessageKind.Note))
        assertEquals(listOf("note"), ports.showing!!.lines.map { it.id })
        // The morning's briefing makes it again: they're in it.
        clock.time = Fixtures.at("2026-10-19T07:30")
        outbox.notify(clock.now())
        assertEquals(listOf("review", "event", "note"), ports.showing!!.lines.map { it.id })
    }

    @Test
    fun `what waited for the morning shows from the morning's message on, quiet hours that outlast it or not`() = runBlocking {
        clock.time = Fixtures.at("2026-10-18T23:30")
        outbox.send(his("review", kind = MessageKind.Review))
        assertNull(ports.showing)
        // Quiet hours to 08:00 here, the briefing at 07:00: its message is the morning all the same.
        settings.update { it.copy(quietHours = it.quietHours.copy(endMin = 8 * 60)) }
        clock.time = Fixtures.at("2026-10-19T07:00")
        outbox.notify(clock.now())
        assertNull(ports.showing)
        outbox.send(his("morning", kind = MessageKind.Morning))
        assertEquals(listOf("review", "morning"), ports.showing!!.lines.map { it.id })
        // Made again before quiet hours end: what waited stays in it, and one of a kind that waits,
        // written after the morning's message, waits for the next.
        clock.time = Fixtures.at("2026-10-19T07:20")
        outbox.send(his("event", kind = MessageKind.EventQuestion))
        assertEquals(listOf("review", "morning"), ports.showing!!.lines.map { it.id })
    }

    @Test
    fun `a look of his in two texts is two lines of his notification, its replies the last's (D-120)`() = runBlocking {
        outbox.send(his("s").copy(text = "physics went by\nwhat happened"))
        assertEquals(listOf("s#0" to "physics went by", "s" to "what happened"), ports.showing!!.lines.map { it.id to it.text })
        assertEquals("s", ports.showing!!.replyTo)
    }

    @Test
    fun `your reply shows under his message, a question answered leaves it, and an answer's gist goes with his words`() = runBlocking {
        outbox.send(his("q", kind = MessageKind.Question))
        outbox.send(his("s"))
        chat.update { s -> s.copy(messages = s.messages + ChatMessage("y", Speaker.You, "on it", clock.now(), replyTo = "s")) }
        outbox.notify(clock.now())
        assertEquals(listOf("q" to false, "s" to false, "y" to true), ports.showing!!.lines.map { it.id to it.yours })
        chat.update { s -> s.copy(messages = s.messages.map { if (it.id == "q") it.copy(answeredAt = clock.now()) else it }) }
        outbox.notify(clock.now())
        assertEquals(listOf("s", "y"), ports.showing!!.lines.map { it.id })
    }

    @Test
    fun `the window keeps the newest two hundred and all of a fortnight, moving the rest to the archive, never an unread one`() = runBlocking {
        val now = clock.now()
        val old = now - Outbox.WINDOW_MS - 3_600_000L
        // His, the oldest of all and still unread; 210 old ones of yours; then 5 recent.
        val unread = his("u", old - 1)
        val olds = (0 until 210).map { ChatMessage("o$it", Speaker.You, "old $it", old + it, answeredAt = old + it) }
        val recent = (0 until 5).map { ChatMessage("r$it", Speaker.You, "recent $it", now - 1_000L + it, answeredAt = now) }
        chat.update { ChatState(listOf(unread) + olds + recent, readAt = old - 2) }
        outbox.moveWindow(now)
        // Past the newest 200 and older than a fortnight, and read: the oldest 15 of yours; his unread stays.
        assertEquals((0 until 15).map { "o$it" }, archive.value.messages.map { it.id })
        assertEquals(listOf("u") + (15 until 210).map { "o$it" } + recent.map { it.id }, chat.value.messages.map { it.id })
        // Moved again: nothing doubled, nothing lost.
        outbox.moveWindow(now)
        assertEquals(15, archive.value.messages.size)
        assertEquals(1 + 195 + 5, chat.value.messages.size)
        // A stop between the archive's update and the window's trim leaves a copy in both: dropped by id at the next move.
        val stray = olds[20].copy(at = old)
        archive.update { it.copy(messages = it.messages + stray) }
        chat.update { it.copy(messages = listOf(stray) + it.messages.filterNot { m -> m.id == stray.id }) }
        outbox.moveWindow(now)
        assertEquals(1, archive.value.messages.count { it.id == stray.id })
    }

    @Test
    fun `clearing the chat empties its archive too, and keeps his state`() = runBlocking {
        chat.update { ChatState(listOf(his("a")), felix = FelixState(nextCheckAt = 42)) }
        archive.update { ChatArchive(listOf(his("z", 1))) }
        // Copies kept of the window: an older shape's, and one that couldn't be read; and one of another store's.
        val copies = listOf("chat.json.v5", "chat.json.unreadable-20261021-010000").map { File(dir, it).apply { writeText("{}") } }
        val other = File(dir, "felix.json.v2").apply { writeText("{}") }
        outbox.clear()
        assertTrue(chat.value.messages.isEmpty() && archive.value.messages.isEmpty())
        assertEquals(42L, chat.value.felix.nextCheckAt)
        assertNull(ports.showing)
        // What's cleared isn't kept in a copy (D-94); another store's stays.
        assertTrue(copies.none { it.exists() })
        assertTrue(other.exists())
    }
}
