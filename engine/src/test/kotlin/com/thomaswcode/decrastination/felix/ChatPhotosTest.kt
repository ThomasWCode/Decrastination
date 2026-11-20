package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatPhoto
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.enrich.Pricing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Photos in the chat (6.8.0), as the chat's pure rules have them: a photo counts as words and is said as one, how long its file's
 * needed, the pictures a turn sends and what's said of the rest, and his answer's lines of them, the ones he asks to see and a photo
 * kept with a task.
 */
class ChatPhotosTest {
    private val now = Fixtures.at("2026-10-12T20:00")
    private val a = "1a2b3c4d5e6f7a8b"
    private val b = "9f8e7d6c5b4a3f2e"

    private fun you(id: String, text: String, at: Long = now, photos: List<ChatPhoto> = emptyList(), answeredAt: Long? = null) = ChatMessage(id, Speaker.You, text, at, answeredAt = answeredAt, photos = photos)

    @Test
    fun `a photo counts as words, and is said as a photo with his line of it`() {
        val alone = you("y", "", photos = listOf(ChatPhoto(a, note = "a worksheet"), ChatPhoto(b)))
        assertTrue(alone.says)
        assertEquals("[photo: a worksheet] [photo]", alone.said)
        assertEquals("[photo] and this", you("z", "and this", photos = listOf(ChatPhoto(b))).said)
        assertFalse(you("e", " ").says)
    }

    @Test
    fun `a ticked photo's file is needed till its message is answered, a kept one's a fortnight, neither's past it`() {
        val sent = you("y", "", photos = listOf(ChatPhoto(a)))
        assertTrue(ChatPhoto(a).live(sent, now + 1))
        assertFalse(ChatPhoto(a).live(sent.copy(answeredAt = now + 1), now + 2))
        assertTrue(ChatPhoto(a, kept = true).live(sent.copy(answeredAt = now + 1), now + ChatPhoto.KEPT_MS - 1))
        assertFalse(ChatPhoto(a, kept = true).live(sent, now + ChatPhoto.KEPT_MS))
        assertFalse(ChatPhoto(a).live(sent, now + ChatPhoto.KEPT_MS), "one whose message is never answered goes too")
    }

    @Test
    fun `the newest turn sends its photos as pictures, each named, each taking its room from the text`() {
        val asked = you("y", "What's this?", photos = listOf(ChatPhoto(a), ChatPhoto(b)))
        val sent = ChatPrompts.sent(listOf(asked), "CONTEXT", emptyMap(), LONDON, live = setOf(a, b))
        assertEquals(listOf(a, b), sent.pictures)
        val words = sent.turns.single().second
        assertTrue("[Photo 1, sent with this turn]\n[Photo 2, sent with this turn]\nWhat's this?" in words, words)
        assertEquals("Photo 2, id $b:", ChatPrompts.pictureName(1, b))
        // One whose file's gone isn't sent, and is said by its id.
        val one = ChatPrompts.sent(listOf(asked), "", emptyMap(), LONDON, live = setOf(a))
        assertEquals(listOf(a), one.pictures)
        assertTrue("[photo $b]" in one.turns.single().second)
        // Each takes its room: a budget whose quarter holds one sends one, its text the room the picture leaves.
        val budget = 4 * Pricing.PICTURE_CHARS + 100
        val small = ChatPrompts.sent(listOf(asked), "C".repeat(10_000), emptyMap(), LONDON, budget = budget, live = setOf(a, b))
        assertEquals(listOf(a), small.pictures)
        assertTrue(small.turns.single().second.length <= budget - Pricing.PICTURE_CHARS)
        assertTrue("[photo $b, not sent for room]" in small.turns.single().second)
    }

    @Test
    fun `a kept photo goes again with a message within ten minutes of it, after that by his line, and when he asks to see it`() {
        val first = you("y1", "Here", photos = listOf(ChatPhoto(a, kept = true, note = "a worksheet")), answeredAt = now + 1_000)
        val reply = ChatMessage("h1", Speaker.Felix, "Got it.", now + 1_000)
        val soon = you("y2", "Which is hardest?", at = now + ChatPrompts.PHOTO_WITHIN_MS)
        val again = ChatPrompts.sent(listOf(first, reply, soon), "", emptyMap(), LONDON, live = setOf(a))
        assertEquals(listOf(a), again.pictures)
        assertTrue("[Photo 1, sent with this turn]\nHere" in again.turns.first().second)
        val later = soon.copy(at = now + ChatPrompts.PHOTO_WITHIN_MS + 1)
        val told = ChatPrompts.sent(listOf(first, reply, later), "", emptyMap(), LONDON, live = setOf(a))
        assertEquals(emptyList(), told.pictures)
        assertTrue("[photo $a, still kept: a worksheet]\nHere" in told.turns.first().second, told.turns.first().second)
        assertEquals(listOf(a), ChatPrompts.sent(listOf(first, reply, later), "", emptyMap(), LONDON, live = setOf(a), seen = listOf(a)).pictures)
        // Its file gone: his line of it alone.
        assertTrue("[photo $a: a worksheet]\nHere" in ChatPrompts.sent(listOf(first, reply, later), "", emptyMap(), LONDON).turns.first().second)
    }

    @Test
    fun `a photo he asks to see goes before one kept within ten minutes, which he didn't ask for`() {
        val old = you("y0", "Older", at = now - 3_600_000L, photos = listOf(ChatPhoto(b, kept = true)), answeredAt = now - 3_500_000L)
        val first = you("y1", "Here", photos = listOf(ChatPhoto(a, kept = true)), answeredAt = now + 1_000)
        val reply = ChatMessage("h1", Speaker.Felix, "Got it.", now + 1_000)
        val soon = you("y2", "And the older one?", at = now + 60_000L)
        // Room for one picture: the one asked for.
        val budget = 4 * Pricing.PICTURE_CHARS + 100
        assertEquals(listOf(b), ChatPrompts.sent(listOf(old, first, reply, soon), "", emptyMap(), LONDON, budget = budget, live = setOf(a, b), seen = listOf(b)).pictures)
    }

    @Test
    fun `his lines of the photos, those he asks to see and a photo kept with a task are read as the app can use them`() {
        val bridge = TaskItem(id = "teams:b", source = Source.Teams, sourceId = "b", title = "Bridge", dueAt = now + 86_400_000L, kind = Kind.Homework, firstSeenAt = now, lastSeenAt = now)
        val context = ChatPrompts.Context("", "", photos = setOf(a))
        val keep = """{"type":"keepPhoto","taskId":"teams:b","stepIndex":-1,"setting":"","value":"${a.uppercase()}"},{"type":"keepPhoto","taskId":"teams:b","stepIndex":-1,"setting":"","value":"$b"}"""
        val json = """{"reply":"Kept.","newTasks":[],"changes":[],"memories":[],"suggestedReplies":[],"actions":[$keep],"research":"","details":["photo:$a","photo:$b","photo:nonsense"],"answered":[],"photos":"Photo 1, id $a: a worksheet \n\nthe second\nPhoto 4: the fourth"}"""
        val reading = assertNotNull(ChatAnswers.parse(json, listOf(bridge), emptyList(), About(), now, LONDON, context = context))
        // A blank line isn't a photo's; one naming its photo is that one's.
        assertEquals(listOf("a worksheet", "the second", "", "the fourth"), reading.photoNotes)
        assertEquals(listOf(a), reading.see, "only one still here")
        assertEquals(listOf(ChatAction(ChatAction.Type.KeepPhoto, "teams:b", value = a)), reading.actions)
        assertTrue(reading.leftOut.any { "a photo to keep that isn't one of theirs still here" in it }, reading.leftOut.toString())
        // Not with a finished task, nor one Claude doesn't read.
        val done = bridge.copy(status = Status.Done)
        assertTrue(assertNotNull(ChatAnswers.parse(json, listOf(done), emptyList(), About(), now, LONDON, context = context)).actions.isEmpty())
        assertEquals(a, ChatPrompts.photoAsk(" Photo: ${a.uppercase()} "))
        assertNull(ChatPrompts.photoAsk("photo:12"))
        assertNull(ChatPrompts.photoAsk("calendar"))
    }

    @Test
    fun `photos handed to the check carry their task, a step of its own and the student's word, only where there are photos`() {
        val sheet = TaskItem(
            id = "teams:s", source = Source.Teams, sourceId = "s", title = "Sheet", dueAt = now + 86_400_000L, kind = Kind.Homework, firstSeenAt = now, lastSeenAt = now,
            subSteps = listOf(SubStep("Q1-5", 20), SubStep("Q6-10", 20)),
        )
        fun reading(action: String, photos: Set<String> = setOf(a)) = assertNotNull(
            ChatAnswers.parse(
                """{"reply":"Checking.","newTasks":[],"changes":[],"memories":[],"suggestedReplies":[],"actions":[$action],"research":"","details":[],"answered":[],"photos":""}""",
                listOf(sheet), emptyList(), About(), now, LONDON,
                context = ChatPrompts.Context("", "", keepable = mapOf("teams:s#1" to "teams:s", "teams:o#-1#2" to "teams:o"), kept = mapOf("teams:s#0" to "teams:s"), photos = photos),
            ),
        )
        // The step with its title, so it's found as it is when the check's made.
        assertEquals(listOf(ChatAction(ChatAction.Type.CheckPhotos, "teams:s", 1, value = "only the odd ones were set", stepTitle = "Q6-10")), reading("""{"type":"checkPhotos","taskId":"teams:s","stepIndex":1,"setting":"","value":" only the odd ones were set "}""").actions)
        assertEquals(listOf(ChatAction(ChatAction.Type.CheckPhotos, "teams:s", null, value = "")), reading("""{"type":"checkPhotos","taskId":"teams:s","stepIndex":-1,"setting":"","value":""}""").actions)
        assertTrue(reading("""{"type":"checkPhotos","taskId":"teams:s","stepIndex":5,"setting":"","value":""}""").actions.isEmpty(), "a step it hasn't")
        assertTrue(reading("""{"type":"checkPhotos","taskId":"teams:s","stepIndex":-1,"setting":"","value":""}""", photos = emptySet()).actions.isEmpty(), "no photos here")
        // The plan's piece by its key where he names one, of the task's and listed; never another task's, one not listed, nor one kept today, found without.
        assertEquals(listOf(ChatAction(ChatAction.Type.CheckPhotos, "teams:s", 1, setting = "teams:s#1", value = "", stepTitle = "Q6-10")), reading("""{"type":"checkPhotos","taskId":"teams:s","stepIndex":1,"setting":" teams:s#1 ","value":""}""").actions)
        assertTrue(reading("""{"type":"checkPhotos","taskId":"teams:s","stepIndex":-1,"setting":"teams:s#0","value":""}""").actions.isEmpty(), "a piece kept today")
        assertTrue(reading("""{"type":"checkPhotos","taskId":"teams:s","stepIndex":-1,"setting":"teams:o#-1#2","value":""}""").actions.isEmpty(), "another task's piece")
        assertTrue(reading("""{"type":"checkPhotos","taskId":"teams:s","stepIndex":-1,"setting":"teams:s#7","value":""}""").actions.isEmpty(), "a piece not listed")
        // One check a piece, the first: the same step twice, or the task's next beside a step of it, pays once; each step its own.
        fun check(step: Int, said: String = "") = """{"type":"checkPhotos","taskId":"teams:s","stepIndex":$step,"setting":"","value":"$said"}"""
        assertEquals(listOf("first"), reading(check(1, "first") + "," + check(1, "again")).actions.map { it.value })
        assertEquals(listOf(1), reading(check(-1) + "," + check(1)).actions.map { it.stepIndex })
        assertEquals(listOf(0, 1), reading(check(0) + "," + check(1)).actions.map { it.stepIndex })
    }

    @Test
    fun `a photo he asks to see takes a round, once`() {
        val reading = ChatAnswers.Reading("Looking", emptyList(), emptyList(), emptyList(), see = listOf(a))
        assertEquals(listOf(a), ChatRounds.next(reading, searched = false, searches = true, read = emptyList())?.see)
        assertNull(ChatRounds.next(reading, searched = false, searches = true, read = emptyList(), seen = listOf(a)))
        assertTrue(ChatRounds.asking(reading, false, true, emptyList(), false, emptyList()))
        assertFalse(ChatRounds.asking(reading, false, true, emptyList(), false, emptyList(), seen = listOf(a)))
    }
}
