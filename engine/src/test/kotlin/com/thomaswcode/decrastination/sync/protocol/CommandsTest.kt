package com.thomaswcode.decrastination.sync.protocol

import com.thomaswcode.decrastination.block.AnkiWatch
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskPhoto
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.Ids
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Replay
import com.thomaswcode.decrastination.core.ReplayClock
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.newStepId
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.Reset
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.FelixHarness
import com.thomaswcode.decrastination.felix.Looks
import com.thomaswcode.decrastination.learn.AnkiReading
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.DeckCount
import com.thomaswcode.decrastination.learn.Learning
import com.thomaswcode.decrastination.learn.ReviewInput
import com.thomaswcode.decrastination.protect.Protection
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sync.Syncer
import com.thomaswcode.decrastination.tasks.Instructions
import com.thomaswcode.decrastination.tasks.OwnTasks
import com.thomaswcode.decrastination.tasks.TasksHarness
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A screen's change as data (D-140): every action goes and comes back whole, and a command's time and ids are its own on either side. */
class CommandsTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val review = WeeklyReview(at = 1L, lines = listOf("a line"), by = "rules", week = "2026-W44")

    /** One of each action there is: a new one fails [everyActionIsHere] till it's added. */
    private val samples: List<Action> = listOf(
        SaveOwnTask("s", OwnTask(title = "t", steps = listOf(OwnStep("a", 10))), fromForm = true, wasIndexes = listOf(0)),
        FinishOwnTask("t"), ReopenOwnTask("t"), DeleteOwnTask("t"),
        AddOwnChange("i", "words", About(taskId = "t"), Change(ChangeType.OnDay, taskId = "t", date = "2026-11-02")),
        LaterToday("k"), TakeBackQuick("t", ChangeType.AtTime), ApplyInstruction("i"), DeleteInstruction("i"), RereadInstruction("i", "words"), NotSame("a", "b"),
        AddNoSchool(NoSchool("2026-11-02", "2026-11-03", "a break")), AddHalfDay(HalfDay("2026-11-02", 780)), DeleteNoSchool(NoSchool("2026-11-02", "2026-11-03")), DeleteHalfDay(HalfDay("2026-11-02", 780)),
        KeepSlot("k", 5L), UndoKeep("k", 5L), TickBlock("t", 1, "a step"), StartTimer("t", 1, "k"), StopTimer, NoteActivity(listOf("t")),
        SendMessage("hello", About(taskId = "t"), "m", "r", false), ReplyFromNotification("hello", "m"), WithdrawQuestion("m"), CancelAnswer, MarkAnswered(listOf("m")), UnmarkAnswered(Conversation.Marked(setOf("m"), 5L)),
        RateMessage("m", true, "good", "this one"), ReadChat(5L), ClearChat, ApplyProposal("m", "p", OwnTask(title = "t")), UndoProposal("m", "p"), DiscardProposal("m", "p", false), UndoAct("m", 1), UndoMemory("m", 1), EndCheckInFlow,
        Remember(listOf(Given(text = "a rule", kind = Given.RULE))), DeleteRule(Rule("a rule", 5L)), DeleteFact(Fact("topic", "a fact", 5L)), AddNote("t", "a note"), DeleteNote("n"),
        ChangeSettings(mapOf("boxMin" to JsonPrimitive(30))), ParentCodeSet(9L), UseParentCode("123456", 9L), ApplyChangeNow("c", "123456"), CancelChange("c"),
        RecordCheckIn(CheckIn("2026-W44", 5L, 3, "", "", "", ""), 5L), ForgetCalibration("k"), ResetData(Reset.Chat), UndoReviewChange(5L, "boxMin"), UndoTidy(5L),
        ObserveSources(listOf(SourceObserved(Source.Teams, 5L, SourceRead(listOf(Fetched("a", "an essay", Kind.Homework, subSteps = listOf(SubStep("plan", 10)))), dataAsOf = 4L, note = "a note")), SourceObserved(Source.Anki, 5L, failed = "no answer"), SourceObserved(Source.Teams, 6L))),
        NoteSource(Source.Teams, "a note"), ObserveCalendar(CalendarRead(answers = mapOf("an event" to "busy"))), AskAboutEvent("k", "an event", "Monday", 5L, listOf("busy", "free")), SettleEvent("k"),
        AnkiCame(5L, AnkiReading(5L, mapOf(1L to DeckCount(1, 2, 3)), entering = true)), AnkiWent(6L), AnkiReset, RecordBlock("an app", "a reason"), SpendFreeTime("2026-11-03", 30_000L), TimerOver,
        RecordProtection(listOf("a problem"), repaired = true), AttachPhoto("t", TaskPhoto("photos/t.jpg", "abcd", 5L)),
    )

    @Test
    fun `every action is here`() {
        val kinds = Action.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()
        val samples = samples.map { sample -> json.parseToJsonElement(json.encodeToString(Action.serializer(), sample)).let { (it as JsonObject).getValue("type").toString().trim('"') } }.toSet()
        assertEquals(kinds, samples)
    }

    @Test
    fun `every command goes and comes back whole`() {
        for (action in samples) {
            val command = Command("id", 5L, action)
            assertEquals(command, json.decodeFromString(Command.serializer(), json.encodeToString(Command.serializer(), command)), action.toString())
        }
    }

    @Test
    fun `a saved own task's steps keep which each was through the wire`() {
        val made = OwnTask(title = "t", steps = listOf(OwnStep("renamed", 10, wasIndex = 2), OwnStep("new", 5)), fromForm = true)
        val action = SaveOwnTask.of("s", made)
        val back = json.decodeFromString(Action.serializer(), json.encodeToString(Action.serializer(), action)) as SaveOwnTask
        assertTrue(back.fromForm)
        assertEquals(listOf(2, null), back.wasIndexes)
    }

    @Test
    fun `a command's ids are the same each time it's applied, and differ from another's`() {
        fun drawn(seed: String): List<String> {
            val replay = Replay(5L, seed)
            Replay.current.set(replay)
            try {
                return listOf(Ids.next(), Ids.next(), newStepId())
            } finally {
                Replay.current.remove()
            }
        }
        assertEquals(drawn("a"), drawn("a"))
        assertNotEquals(drawn("a"), drawn("b"))
        assertEquals(3, drawn("a").toSet().size)
    }

    @Test
    fun `outside a command, ids are random and the clock's the real one`() {
        assertNull(Replay.current.get())
        assertNotEquals(Ids.next(), Ids.next())
        assertEquals(7L, ReplayClock(FixedClock(7L)).now())
    }

    @Test
    fun `a command's moment and ids stay with it across threads`() = runBlocking {
        val clock = ReplayClock(FixedClock(7L))
        val first = withContext(Replay.current.asContextElement(Replay(5L, "a"))) {
            withContext(Dispatchers.IO) { clock.now() to Ids.next() }
        }
        val again = withContext(Replay.current.asContextElement(Replay(5L, "a"))) { withContext(Dispatchers.Default) { clock.now() to Ids.next() } }
        assertEquals(5L, first.first)
        assertEquals(first, again)
        assertEquals(7L, clock.now())
    }

    @Test
    fun `a reset says what it came to, and one that didn't happen is refused`() {
        val reset = ResetData(Reset.Chat)
        assertEquals("Chat: reset.", reset.said("Chat: reset."))
        assertNull(reset.refused("Chat: reset."))
        assertEquals("${BackupService.NOTHING_RESET}: made up", reset.refused("${BackupService.NOTHING_RESET}: made up"))
    }

    @Test
    fun `settings changes that overlap, each building on the last, lose none`() = runBlocking {
        val dir = Files.createTempDirectory("settings").toFile()
        try {
            val clock = FixedClock(1_000L)
            val stores = Stores(dir, Plain)
            val made = Protection(stores.settings, stores.protection, stores.device, stores.secrets, clock, this, adminActive = { false }) {}
            val owners = object : Owners {
                override val tasks = stores.tasks
                override val ownTasks: OwnTasks get() = error("not here")
                override val instructions: Instructions get() = error("not here")
                override val focus: Focus get() = error("not here")
                override val conversation: Conversation get() = error("not here")
                override val looks: Looks get() = error("not here")
                override val protection = made
                override val learning: Learning get() = error("not here")
                override val backups: BackupService get() = error("not here")
                override val sources: Syncer get() = error("not here")
                override val ankiWatch: AnkiWatch get() = error("not here")
                override val photos: Photos get() = error("not here")

                override suspend fun calendarSeen(read: CalendarRead) = error("not here")

                override suspend fun eventAsked(key: String) = error("not here")
            }
            val commands = Commands(owners, clock)
            (1..30).map { i -> async(Dispatchers.Default) { commands.changeSettings { it.copy(hintsSeen = it.hintsSeen + "hint $i") } } }.awaitAll()
            assertEquals(30, stores.settings.value.hintsSeen.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an event's question applied on the phone is asked in the chat, and put on record as asked there as it is`() = runBlocking {
        FelixHarness().use { h ->
            val recorded = mutableListOf<String>()
            val owners = object : Owners {
                override val tasks = h.stores.tasks
                override val ownTasks: OwnTasks get() = error("not here")
                override val instructions: Instructions get() = error("not here")
                override val focus: Focus get() = error("not here")
                override val conversation = h.conversation
                override val looks: Looks get() = error("not here")
                override val protection: Protection get() = error("not here")
                override val learning: Learning get() = error("not here")
                override val backups: BackupService get() = error("not here")
                override val sources: Syncer get() = error("not here")
                override val ankiWatch: AnkiWatch get() = error("not here")
                override val photos: Photos get() = error("not here")

                override suspend fun calendarSeen(read: CalendarRead) = error("not here")

                override suspend fun eventAsked(key: String) {
                    assertTrue(h.stores.chat.value.messages.any { it.about.eventKey == key }, "on record once its question is")
                    recorded += key
                }
            }
            Commands(owners, h.clock).act(AskAboutEvent("an event", "An event", "Monday", h.clock.now(), listOf("All of it", "None")))
            assertEquals(listOf("an event"), recorded)
        }
    }

    @Test
    fun `two sides applying one command end the same`() = runBlocking {
        fun harness() = TasksHarness()
        val phone = harness()
        val server = harness()
        try {
            val task = OwnTask(title = "Essay", steps = listOf(OwnStep("Plan", 20), OwnStep("Write", 40)))
            val command = Command("the-id", phone.clock.now() + 60_000, SaveOwnTask.of("e", task))
            // An hour apart: only the command's own moment can make the two the same.
            server.clock.time += 3_600_000
            for (side in listOf(phone, server)) side.commands.apply(command)
            assertEquals(phone.stores.tasks.text(), server.stores.tasks.text())
            assertEquals(command.at, phone.stores.tasks.value.tasks.single().firstSeenAt)
            assertTrue(phone.stores.tasks.value.tasks.single().subSteps.all { it.id.isNotEmpty() })
        } finally {
            phone.close()
            server.close()
        }
    }
}
