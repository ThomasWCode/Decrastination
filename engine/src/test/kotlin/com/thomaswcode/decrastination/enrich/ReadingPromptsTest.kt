package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.SourceValues
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReadingPromptsTest {
    @Test
    fun `the schema is written out as the JSON it's sent as`() {
        val schema = ReadingPrompts.schemaJson(Enrichments.Job.Email).jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(Json.parseToJsonElement(schema.toString()), schema)
    }

    @Test
    fun `the model is told what the source says is due, not an older enrichment's date`() {
        val task = assignment("Pages 3-5 of the booklet.").copy(
            dueAt = Fixtures.at("2026-10-12T08:30"),
            sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-14T08:30"), null),
        )
        val text = ReadingPrompts.describe(task, Enrichments.Job.Assignment, NOW, Fixtures.LONDON)
        assertTrue("Due: Wednesday 14 October 2026 08:30" in text, text)
    }

    @Test
    fun `every object in every schema is closed and asks for all its fields`() {
        fun check(schema: Map<*, *>) {
            if (schema["type"] == "object") {
                assertEquals(false, schema["additionalProperties"], "$schema")
                assertEquals((schema["properties"] as Map<*, *>).keys.toList(), schema["required"])
            }
            schema.values.forEach { value ->
                when (value) {
                    is Map<*, *> -> check(value)
                    is List<*> -> value.filterIsInstance<Map<*, *>>().forEach(::check)
                }
            }
        }
        Enrichments.Job.entries.forEach { check(ReadingPrompts.schema(it)) }
    }

    @Test
    fun `an email is described with when it came, who from, and its text`() {
        val text = ReadingPrompts.describe(email(), Enrichments.Job.Email, NOW, LONDON)
        assertTrue(text.startsWith("Today: Friday 9 October 2026, 17:00"), text)
        assertTrue("Received: Thursday 8 October 2026 16:30" in text, text)
        assertTrue("From: Mr Hughes <hughes@school.example>" in text, text)
        assertTrue("Subject: Berlin trip form" in text, text)
        assertTrue(text.endsWith("Please sign and return the trip form by Monday 12 October."), text)
        // A note to self says so, rather than giving your own address.
        val note = ReadingPrompts.describe(email().copy(extra = mapOf("sent" to "true", "from" to "Thomas <t@example.com>")), Enrichments.Job.Email, NOW, LONDON)
        assertTrue("From: the student (a note to self)" in note, note)
    }

    @Test
    fun `the email job is told it sees the whole conversation, quotes cut and attachments by name`() {
        val system = ReadingPrompts.system(Enrichments.Job.Email)
        // Starred, it's always something to do (AI-02).
        assertTrue("Every email you see was starred by the student: it is something to do." in system, system)
        assertTrue("Info" !in system, system)
        // What it sees, and what to do when it can't see what's asked (AI-04, DX-06).
        assertTrue("You see the conversation the starred message is in, oldest message first (where a long one's earlier messages had to be cut short or left out, it says so)" in system, system)
        assertTrue("The first message shown keeps what it quotes from earlier (cut short too, where it had to be); in the ones after it, quoted text and signatures are left out and marked in square brackets" in system, system)
        assertTrue("You don't see attachments, only their names (an attached email goes by its subject). Where what the email asks turns on an attachment or an earlier message you can't see, and its name doesn't tell you, don't guess what it asked: make nextStep say to read it first, naming it" in system, system)
        assertTrue("and effortMin 10." in system, system)
        assertTrue("from when the message saying them was sent" in system, system)
    }

    @Test
    fun `a conversation says it's all shown, and its attachments are named`() {
        val files = """[{"name":"consent-form.pdf","type":"application/pdf","size":112639},{"name":"itinerary.docx","type":"application/msword"}]"""
        val text = ReadingPrompts.describe(email().copy(extra = email().extra + mapOf(GmailThreads.EXTRA_MESSAGES to "3", GmailThreads.EXTRA_ATTACHMENTS to files)), Enrichments.Job.Email, NOW, LONDON)
        assertTrue("Messages in this conversation: 3, oldest first" in text, text)
        assertTrue("Attachments: consent-form.pdf (110 KB), itinerary.docx" in text, text)
        // One message, nothing attached: neither line.
        val single = ReadingPrompts.describe(email(), Enrichments.Job.Email, NOW, LONDON)
        assertTrue("Messages in this conversation" !in single && "Attachments" !in single, single)
    }

    @Test
    fun `an assignment gives its class and due time, no multiplier, and a long text says it's cut`() {
        val text = ReadingPrompts.describe(assignment("x".repeat(ReadingPrompts.MAX_TEXT + 10)), Enrichments.Job.Assignment, NOW, LONDON)
        assertTrue("Class: 12.8 German 2026-27" in text, text)
        assertTrue("Due: Wednesday 14 October 2026 08:30" in text, text)
        // The planner applies the learned multiplier itself; the model gives typical times.
        assertTrue("multiplier" !in text, text)
        assertTrue(text.endsWith("[The rest of this long text is left out.]"), text.takeLast(80))
    }
}
