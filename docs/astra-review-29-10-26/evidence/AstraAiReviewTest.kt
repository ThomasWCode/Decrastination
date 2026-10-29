package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.withEnrichment
import com.thomaswcode.decrastination.enrich.PhotoTasks
import com.thomaswcode.decrastination.sources.gmail.Conversation
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

class AstraAiReviewTest {
    @Test
    fun `an overdue starred email must keep its deadline through the next sync`() {
        val before = Fixtures.at("2026-10-28T18:00")
        val after = Fixtures.at("2026-10-29T08:00")
        val email = Conversation("123", listOf(7), 1, "Return the form", "456", before, "Example", "office@example.com", false)
        val bodies = mapOf(email.version to GmailThreads.Body("Please return the form by 28 October 2026."))
        val initial = Merge.apply(emptyList(), Source.Gmail, GmailThreads.fetched(listOf(email), bodies, before, Fixtures.LONDON), before).tasks
        val deadline = assertNotNull(initial.single().dueAt)
        val read = initial.single().withEnrichment(Enrichment(Enrichments.inputHash(initial.single()), "claude-opus-5-5", before, deadline = deadline, deadlineQuoted = true, effortMin = 30, subSteps = listOf(SubStep("Fill the form", 15), SubStep("Send the form", 15))))
        assertTrue(read.hardAt(before))
        val synced = Merge.apply(listOf(read), Source.Gmail, GmailThreads.fetched(listOf(email), bodies, after, Fixtures.LONDON), after).tasks.single()
        assertEquals(deadline, synced.dueAt)
        assertEquals(2, synced.subSteps.size)
    }

    @Test
    fun `a tick returned after steps change must not tick another step`() = runBlocking {
        FelixHarness().use { felix ->
            val task = TaskItem("teams:review", Source.Teams, "review", "Worksheet", kind = Kind.Homework, firstSeenAt = felix.clock.now(), lastSeenAt = felix.clock.now(), subSteps = listOf(SubStep("Read chapter", 15), SubStep("Write essay", 30)))
            felix.stores.tasks.update { it.copy(tasks = listOf(task)) }
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            felix.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return felix.answer(felix.chatAnswer("The reading is ticked.", actions = """[{"type":"tickStep","taskId":"teams:review","stepIndex":0}]"""))
                }
            }
            try {
                assertTrue(felix.conversation.ask("I finished reading the chapter"))
                assertTrue(arrived.await(10, TimeUnit.SECONDS))
                felix.stores.tasks.update { it.copy(tasks = listOf(task.copy(subSteps = task.subSteps.reversed()))) }
            } finally {
                release.countDown()
            }
            felix.settle()
            val essay = felix.stores.tasks.value.tasks.single().subSteps.first { it.title == "Write essay" }
            assertFalse(essay.done, "A response about Read chapter must not complete Write essay")
        }
    }

    @Test
    fun `a photo reading must disclose tasks beyond its six task limit`() {
        val tasks = (1..7).joinToString(",") { """{"title":"Task $it","minutes":15}""" }
        val read = assertNotNull(PhotoTasks.parse("""{"tasks":[$tasks]}""", Fixtures.at("2026-10-29T08:00"), Fixtures.LONDON))
        assertEquals(6, read.tasks.size)
        assertTrue(read.leftOut.isNotEmpty(), "The seventh task must be reported as left out")
    }

    @Test
    fun `a slot start nudge must not be sent after its piece was kept during the call`() = runBlocking {
        FelixHarness().use { felix ->
            val now = felix.clock.now()
            val task = TaskItem("teams:review", Source.Teams, "review", "Worksheet", kind = Kind.Homework, dueAt = Fixtures.at("2026-10-20T08:30"), sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now)
            felix.stores.tasks.update { it.copy(tasks = listOf(task)) }
            val slot = felix.planning.plan().todaySlots.first { it.kind == SlotKind.Work }
            val key = slot.chunk!!.key
            felix.stores.session.update { it.copy(slots = SlotState("2026-10-19", previous = mapOf(key to slot.start))) }
            felix.clock.time = slot.start + (felix.stores.settings.value.felixGraceMin + 1) * 60_000L
            felix.stores.chat.update { it.copy(messages = listOf(ChatMessage("n1", Speaker.Felix, "worksheet's on. starting?", felix.clock.time - 60_000L, About(taskId = task.id), kind = MessageKind.SlotStart, pieceKey = key))) }
            felix.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    runBlocking { check(felix.focusKeep(key)) }
                    return felix.answer("""{"texts":["that's two nudges now. start it."],"send":1,"replies":[],"nextCheckMin":10,"memories":[]}""")
                }
            }
            felix.looks.check()
            assertTrue(felix.stores.log.value.slots.any { it.key == key && it.outcome.kept })
            assertEquals(1, felix.stores.chat.value.messages.count { it.kind == MessageKind.SlotStart }, "The completed piece must not receive another start nudge")
        }
    }
}
