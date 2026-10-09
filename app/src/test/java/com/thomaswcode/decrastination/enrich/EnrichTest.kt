package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.SourceValues
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import kotlinx.coroutines.runBlocking
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NOW = Fixtures.at("2026-10-09T17:00")

private fun email(body: String = "Please sign and return the trip form by Monday 12 October.") = TaskItem(
    id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "Berlin trip form", detail = body, kind = Kind.Admin,
    sourceEffortMin = 15, firstSeenAt = NOW, lastSeenAt = NOW,
    extra = mapOf("from" to "Mr Hughes <hughes@school.example>", "received" to Fixtures.at("2026-10-08T16:30").toString()),
)

private fun assignment(detail: String) = TaskItem(
    id = "teams:a1", source = Source.Teams, sourceId = "a1", title = "Gefahren in den sozialen Netzwerken", detail = detail,
    className = "12.1 German 2026-27", dueAt = Fixtures.at("2026-10-14T08:30"), kind = Kind.Homework, firstSeenAt = NOW, lastSeenAt = NOW,
)

class RuleEnricherTest {
    @Test
    fun `listed parts become steps sharing the estimate`() = runBlocking {
        val task = assignment("Hausaufgaben\n1. Complete the reading task: past paper ( June 2022 )\n2. Mark it and highlight your mistakes")
        val steps = RuleEnricher().enrich(task, Enrichments.Job.Assignment, NOW).enrichment!!.subSteps!!
        assertEquals(listOf("Complete the reading task: past paper ( June 2022 )", "Mark it and highlight your mistakes"), steps.map { it.title })
        assertEquals(listOf(20, 20), steps.map { it.minutes })
    }

    @Test
    fun `the rules share the source's estimate, not a stale one of the model's`() = runBlocking {
        val task = assignment("1. Complete the reading task\n2. Mark it").copy(sourceEffortMin = 40, aiEffortMin = 120)
        val steps = RuleEnricher().enrich(task, Enrichments.Job.Assignment, NOW).enrichment!!.subSteps!!
        assertEquals(listOf(20, 20), steps.map { it.minutes })
    }

    @Test
    fun `only vocabulary and nothing else counts as vocabulary-only`() {
        assertTrue(AnkiRules.vocabularyOnly("Learn vocabulary p46-47/ 2.2/2.3 ( vocabulary test ! )"))
        assertTrue(AnkiRules.vocabularyOnly("Revise vocabulary 3.1 on Anki"))
        assertFalse(AnkiRules.vocabularyOnly("Learn vocabulary 2.2 and complete exercises 3-5"))
        // Work it doesn't know is still work.
        assertFalse(AnkiRules.vocabularyOnly("Learn vocabulary 2.2 and revise the grammar"))
        assertFalse(AnkiRules.vocabularyOnly("Complete exercise 4"))
    }

    @Test
    fun `a part that mixes vocabulary with other work isn't tagged, so it stays planned`() {
        val steps = RuleEnricher.steps("1. Learn vocabulary 2.2 and complete exercises 3-5\n2. Translation of the text", 40)!!
        assertEquals(listOf(emptyList<String>(), emptyList()), steps.map { it.ankiSections })
    }

    @Test
    fun `a part that's learning numbered vocabulary is a step tagged with its sections`() {
        val steps = RuleEnricher.steps("1. Learn vocabulary p46-47/ 2.2/2.3 ( vocabulary test ! )\n2. Complete the reading task", 40)!!
        assertEquals(listOf(listOf("2.2", "2.3"), emptyList()), steps.map { it.ankiSections })
    }

    @Test
    fun `dashes and a number without its space count, a section number doesn't`() {
        assertEquals(2, RuleEnricher.steps("- Learn vocabulary - verschiedene Familienformen\n- Translation - from the booklet", 40)!!.size)
        assertEquals(2, RuleEnricher.steps("1.Past paper: print it out\n2.Mark it", 40)!!.size)
        // "1.2 Familie" is a section, and one item is no list.
        assertNull(RuleEnricher.steps("1.2 Familie und Ehe - both ways.\nPage 15 - exercises 4 and 5.", 40))
        assertNull(RuleEnricher.steps("1. Do page 7", 40))
    }

    @Test
    fun `a long item is shortened, and every step gets five minutes at least`() {
        val steps = RuleEnricher.steps("1. " + "a".repeat(100) + "\n2. b\n3. c\n4. d\n5. e\n6. f\n7. g\n8. h\n9. i\n10. j", 30)!!
        assertEquals(60, steps.first().title.length)
        assertTrue(steps.first().title.endsWith("…"))
        assertTrue(steps.all { it.minutes == 5 })
    }
}

class PromptsTest {
    @Test
    fun `the schema is written out as the JSON it's sent as`() {
        val schema = Prompts.schemaJson(Enrichments.Job.Email).jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(Json.parseToJsonElement(schema.toString()), schema)
    }

    @Test
    fun `the model is told what the source says is due, not an older enrichment's date`() {
        val task = assignment("Page 7-11 of booklet.").copy(
            dueAt = Fixtures.at("2026-10-12T08:30"),
            sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-14T08:30"), null),
        )
        val text = Prompts.describe(task, Enrichments.Job.Assignment, NOW, Fixtures.LONDON)
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
        Enrichments.Job.entries.forEach { check(Prompts.schema(it)) }
    }

    @Test
    fun `an email is described with when it came, who from, and its text`() {
        val text = Prompts.describe(email(), Enrichments.Job.Email, NOW, LONDON)
        assertTrue(text.startsWith("Today: Friday 9 October 2026, 17:00"), text)
        assertTrue("Received: Thursday 8 October 2026 16:30" in text, text)
        assertTrue("From: Mr Hughes <hughes@school.example>" in text, text)
        assertTrue("Subject: Berlin trip form" in text, text)
        assertTrue(text.endsWith("Please sign and return the trip form by Monday 12 October."), text)
        // A note to self says so, rather than giving your own address.
        val note = Prompts.describe(email().copy(extra = mapOf("sent" to "true", "from" to "Thomas <t@example.com>")), Enrichments.Job.Email, NOW, LONDON)
        assertTrue("From: the student (a note to self)" in note, note)
    }

    @Test
    fun `an assignment gives its class and due time, no multiplier, and a long text says it's cut`() {
        val text = Prompts.describe(assignment("x".repeat(Prompts.MAX_TEXT + 10)), Enrichments.Job.Assignment, NOW, LONDON)
        assertTrue("Class: 12.1 German 2026-27" in text, text)
        assertTrue("Due: Wednesday 14 October 2026 08:30" in text, text)
        // The planner applies the learned multiplier itself; the model gives typical times.
        assertTrue("multiplier" !in text, text)
        assertTrue(text.endsWith("[The rest of this long text is left out.]"), text.takeLast(80))
    }
}

class AnswersTest {
    private fun parse(job: Enrichments.Job, text: String, task: TaskItem = email()) = Answers.parse(job, text, task, "claude-opus-5-5", NOW, LONDON)

    @Test
    fun `an email's triage is read, its dates in local time`() {
        val e = parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-12T08:30","effortMin":10,"nextStep":"Sign the form and give it to Mr Hughes"}""")!!
        assertEquals(Kind.Admin, e.kind)
        assertEquals(Fixtures.at("2026-10-12T08:30"), e.deadline)
        assertEquals(10, e.effortMin)
        assertEquals("Sign the form and give it to Mr Hughes", e.nextStep)
        assertEquals(Enrichments.inputHash(email()), e.inputHash)
        // A date alone means the end of that day.
        assertEquals(Fixtures.at("2026-10-12T23:59"), parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-12","effortMin":10,"nextStep":"x"}""")!!.deadline)
    }

    @Test
    fun `what's out of range is dropped rather than trusted`() {
        val e = parse(Enrichments.Job.Email, """{"kind":"Homework","actionableFrom":"1999-01-01","deadline":"2031-01-01T09:00","effortMin":5000,"nextStep":"  "}""")!!
        assertNull(e.kind)
        assertNull(e.actionableFrom)
        assertNull(e.deadline)
        assertNull(e.effortMin)
        assertNull(e.nextStep)
        assertNull(parse(Enrichments.Job.Email, "not json"))
    }

    @Test
    fun `an assignment's steps, sections and test date`() {
        val task = assignment("1. Learn vocabulary 2.2/2.3")
        val e = parse(
            Enrichments.Job.Assignment,
            """{"subSteps":[{"title":"Learn vocabulary 2.2","minutes":20},{"title":"","minutes":10},{"title":"Learn vocabulary 2.3","minutes":999}],"effortMin":40,"ankiSections":["2.2"," 2.3","12","2.2"],"testDate":"2026-10-12"}""",
            task,
        )!!
        // A blank step and one of 999 minutes: the split isn't trusted, the estimate stands.
        assertNull(e.subSteps)
        assertEquals(40, e.effortMin)
        // Nor its sections: the total planned whole holds the vocabulary.
        assertEquals(emptyList(), e.ankiSections)
        // A step's own sections are kept on it, and counted among the assignment's.
        val tagged = parse(
            Enrichments.Job.Assignment,
            """{"subSteps":[{"title":"Learn vocabulary 3.1","minutes":20,"ankiSections":["3.1","x"]},{"title":"Exercise 4","minutes":25,"ankiSections":[]}],"effortMin":45,"ankiSections":[],"testDate":null}""",
            task,
        )!!
        assertEquals(listOf(listOf("3.1"), emptyList()), tagged.subSteps!!.map { it.ankiSections })
        assertEquals(listOf("3.1"), tagged.ankiSections)
        // Tagged, but asking for other work too: kept untagged, so it's planned whole.
        val mixed = parse(
            Enrichments.Job.Assignment,
            """{"subSteps":[{"title":"Learn vocabulary 3.1 and answer questions 1-4","minutes":30,"ankiSections":["3.1"]}],"effortMin":30,"ankiSections":["3.1"],"testDate":null}""",
            task,
        )!!
        assertEquals(listOf(emptyList<String>()), mixed.subSteps!!.map { it.ankiSections })
        // More steps than the planner takes: the rest become one, none of the work lost.
        val many = (1..14).joinToString(",") { """{"title":"Question $it","minutes":10,"ankiSections":[]}""" }
        val long = parse(Enrichments.Job.Assignment, """{"subSteps":[$many],"effortMin":140,"ankiSections":[],"testDate":null}""", task)!!
        assertEquals(12, long.subSteps!!.size)
        // Steps that don't add up to the total: the total is planned, not ten minutes of it.
        val short = parse(Enrichments.Job.Assignment, """{"subSteps":[{"title":"Read the text","minutes":10,"ankiSections":[]}],"effortMin":120,"ankiSections":[],"testDate":null}""", task)!!
        assertNull(short.subSteps)
        assertEquals(120, short.effortMin)
        assertEquals(140, long.subSteps!!.sumOf { it.minutes })
        assertTrue(long.subSteps!!.last().title.startsWith("The rest: Question 12"))
        // A test's day: done before school that morning.
        assertEquals(Fixtures.at("2026-10-12T08:30"), e.testDate)
        assertEquals(40, e.effortMin)
    }

    @Test
    fun `a split adding up past the most one task can be isn't trusted`() {
        val steps = (1..3).joinToString(",") { """{"title":"Part $it","minutes":240,"ankiSections":[]}""" }
        val e = parse(Enrichments.Job.Assignment, """{"subSteps":[$steps],"effortMin":720,"ankiSections":[],"testDate":null}""", assignment("Essay"))!!
        assertNull(e.subSteps)
        assertNull(e.effortMin)
    }

    @Test
    fun `a step longer than a focus session is cut into even parts that each fit one`() {
        val e = parse(
            Enrichments.Job.Assignment,
            """{"subSteps":[{"title":"Write the essay","minutes":220,"ankiSections":[]},{"title":"Check it","minutes":20,"ankiSections":[]}],"effortMin":240,"ankiSections":[],"testDate":null}""",
            assignment("Essay"),
        )!!
        assertEquals(listOf("Write the essay (1 of 2)" to 110, "Write the essay (2 of 2)" to 110, "Check it" to 20), e.subSteps!!.map { it.title to it.minutes })
    }

    @Test
    fun `vocabulary past the step limit keeps its own step and sections, the rest folds`() {
        val questions = (1..12).joinToString(",") { """{"title":"Question $it","minutes":10,"ankiSections":[]}""" }
        val words = """{"title":"Learn vocabulary 2.3","minutes":20,"ankiSections":["2.3"]}"""
        val e = parse(
            Enrichments.Job.Assignment,
            """{"subSteps":[$questions,$words,{"title":"Question 13","minutes":10,"ankiSections":[]}],"effortMin":150,"ankiSections":["2.3"],"testDate":null}""",
            assignment("Questions 1-13, vocabulary 2.3"),
        )!!
        val steps = e.subSteps!!
        // Its own step, with its section, so the deck that holds it still does; nothing moved.
        assertEquals(listOf("2.3"), steps.single { it.title == "Learn vocabulary 2.3" }.ankiSections)
        assertEquals(listOf("Question 12", "Learn vocabulary 2.3", "Question 13"), steps.takeLast(3).map { it.title })
        assertEquals(150, steps.sumOf { it.minutes })
    }

    @Test
    fun `an assignment that's all vocabulary is one step its decks hold, planned while none does`() {
        val task = assignment("Learn vocabulary 1.2")
        val e = parse(Enrichments.Job.Assignment, """{"subSteps":[],"effortMin":5,"ankiSections":["1.2"],"testDate":null}""", task)!!
        assertEquals(listOf(Triple("Learn vocabulary 1.2", task.effortMin, listOf("1.2"))), e.subSteps!!.map { Triple(it.title, it.minutes, it.ankiSections) })
        assertEquals(listOf("1.2"), e.ankiSections)
        assertEquals(task.effortMin, e.effortMin)
    }

    @Test
    fun `an email can come back in dated blocks, and is then something to do`() {
        val calendar = """{"kind":"Info","actionableFrom":null,"deadline":null,"effortMin":90,"nextStep":"Note the dates","blocks":[{"title":"Apply to STEM Potential","minutes":60,"from":"2026-10-30","due":"2026-11-20"},{"title":"Apply to RAL","minutes":30,"from":null,"due":"2027-01-15"}]}"""
        val e = parse(Enrichments.Job.Email, calendar)!!
        assertEquals(Kind.Admin, e.kind)
        assertEquals(90, e.effortMin)
        val blocks = e.subSteps!!
        assertEquals(Fixtures.at("2026-10-30T00:00"), blocks[0].from)
        assertEquals(Fixtures.at("2026-11-20T23:59"), blocks[0].dueAt)
        assertNull(blocks[1].from)
        // A block that opens after it's due isn't to be trusted: the triage stands without them.
        val muddled = parse(Enrichments.Job.Email, calendar.replace("2026-11-20", "2026-10-29"))!!
        assertNull(muddled.subSteps)
        assertEquals(Kind.Info, muddled.kind)
    }

    @Test
    fun `blocks with a total out of range, or starting after their task is due, aren't trusted`() {
        val wild = parse(Enrichments.Job.Effort, """{"effortMin":999,"blocks":[{"title":"A","minutes":30,"from":null,"due":null}]}""")!!
        assertNull(wild.subSteps)
        assertNull(wild.effortMin)
        // The email due on the 15th; a block opening on the 20th can't be done by then.
        val late = parse(
            Enrichments.Job.Email,
            """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-15","effortMin":60,"nextStep":"Apply","blocks":[{"title":"Apply","minutes":60,"from":"2026-10-20","due":null}]}""",
        )!!
        assertNull(late.subSteps)
    }

    @Test
    fun `a planner item can come back in blocks too`() {
        val e = parse(Enrichments.Job.Effort, """{"effortMin":120,"blocks":[{"title":"Past paper 1","minutes":60,"from":null,"due":null},{"title":"Mark it and go over mistakes","minutes":60,"from":null,"due":null}]}""")!!
        assertEquals(listOf("Past paper 1", "Mark it and go over mistakes"), e.subSteps!!.map { it.title })
        assertEquals(120, e.effortMin)
        assertNull(parse(Enrichments.Job.Effort, """{"effortMin":35,"blocks":[]}""")!!.subSteps)
        // Blocks that don't add up to the total aren't trusted: the total is planned whole.
        val off = parse(Enrichments.Job.Effort, """{"effortMin":200,"blocks":[{"title":"A","minutes":30,"from":null,"due":null}]}""")!!
        assertNull(off.subSteps)
        assertEquals(200, off.effortMin)
    }

    @Test
    fun `a long split folds its plain steps in their places`() {
        val first = (1..11).joinToString(",") { """{"title":"Part $it","minutes":10,"ankiSections":[],"from":null,"due":null}""" }
        val tail = """{"title":"Research","minutes":10,"ankiSections":[],"from":null,"due":null},{"title":"Notes","minutes":10,"ankiSections":[],"from":null,"due":null},{"title":"Submit draft","minutes":10,"ankiSections":[],"from":null,"due":"2026-10-12"},{"title":"Proofread","minutes":10,"ankiSections":[],"from":null,"due":null}"""
        val e = parse(Enrichments.Job.Assignment, """{"subSteps":[$first,$tail],"effortMin":150,"ankiSections":[],"testDate":null}""", assignment("Essay"))!!
        assertEquals(listOf("The rest: Research; Notes", "Submit draft", "Proofread"), e.subSteps!!.takeLast(3).map { it.title })
    }

    @Test
    fun `a step can't start after the test that's its task's deadline`() {
        val e = parse(
            Enrichments.Job.Assignment,
            """{"subSteps":[{"title":"Revise","minutes":60,"ankiSections":[],"from":"2026-10-13","due":null}],"effortMin":60,"ankiSections":[],"testDate":"2026-10-12"}""",
            assignment("Revise for the test"),
        )!!
        assertNull(e.subSteps)
    }

    @Test
    fun `an assignment's step can have dates of its own`() {
        val e = parse(
            Enrichments.Job.Assignment,
            """{"subSteps":[{"title":"Draft","minutes":60,"ankiSections":[],"from":null,"due":"2026-10-12"},{"title":"Final version","minutes":40,"ankiSections":[],"from":"2026-10-13","due":null}],"effortMin":100,"ankiSections":[],"testDate":null}""",
            assignment("Essay"),
        )!!
        assertEquals(Fixtures.at("2026-10-12T23:59"), e.subSteps!![0].dueAt)
        assertEquals(Fixtures.at("2026-10-13T00:00"), e.subSteps!![1].from)
    }

    @Test
    fun `an estimate alone`() {
        assertEquals(35, parse(Enrichments.Job.Effort, """{"effortMin":35}""")!!.effortMin)
    }
}

class AiUsageTest {
    @Test
    fun `the model is held while off, for an hour after a failed call, and at its cap`() {
        val now = Fixtures.at("2026-10-10T12:00")
        val month = AiUsage.monthOf(now, LONDON)
        assertEquals(ModelHold.Off, ModelHold.of(false, AiUsage(month = month), now, LONDON, 200, 0.79))
        val failed = AiUsage(month = month).failure("no connection", now - 10 * 60_000L)
        assertEquals(ModelHold.Resting, ModelHold.of(true, failed, now, LONDON, 200, 0.79))
        assertNull(ModelHold.of(true, failed, now + ModelHold.REST_MS, LONDON, 200, 0.79))
        assertEquals(ModelHold.Capped, ModelHold.of(true, AiUsage(month = month, spentUsd = 1000.0), now, LONDON, 200, 0.79))
        assertNull(ModelHold.of(true, AiUsage(month = month), now, LONDON, 200, 0.79))
    }

    @Test
    fun `a new month starts at nothing, and a call is refused that could pass the cap`() {
        val usage = AiUsage(month = "2026-09", spentUsd = 250.0)
        assertEquals(0.0, usage.forMonth("2026-10").spentUsd)
        assertTrue(AiUsage("2026-10", spentUsd = 10.0).allows(capGbp = 200, usdToGbp = 0.79))
        // £200 at 0.79 is $253.16: $252.80 spent leaves no room for a call at its dearest.
        assertEquals(false, AiUsage("2026-10", spentUsd = 252.80).allows(capGbp = 200, usdToGbp = 0.79))
    }

    @Test
    fun `calls, refusals and failures are counted`() {
        val usage = AiUsage("2026-10").record(0.01, refused = false, at = NOW).record(0.02, refused = true, at = NOW).failure("timeout", NOW)
        assertEquals(2, usage.calls)
        assertEquals(1, usage.refused)
        assertEquals(1, usage.failed)
        assertEquals(0.03, usage.spentUsd, 1e-9)
        assertEquals("timeout", usage.lastError)
    }

    @Test
    fun `prices are Opus 5 point 5's, or the dearer older Opus's a fallback may use`() {
        assertEquals(0.0108, Pricing.costUsd("claude-opus-5-5", input = 1_200, output = 300), 1e-9)
        assertEquals(0.0135, Pricing.costUsd("claude-opus-4-8", input = 1_200, output = 300), 1e-9)
    }
}

/** The client against a stand-in for the API: what it sends, and what it makes of the answers. */
class ClaudeEnricherTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun stop() = server.shutdown()

    private fun reply(text: String?, stop: String = "end_turn", model: String = "claude-opus-5-5"): MockResponse {
        val content = if (text == null) JsonArray(emptyList()) else JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)))))
        val body = JsonObject(
            mapOf(
                "id" to JsonPrimitive("msg_test"),
                "type" to JsonPrimitive("message"),
                "role" to JsonPrimitive("assistant"),
                "model" to JsonPrimitive(model),
                "content" to content,
                "stop_reason" to JsonPrimitive(stop),
                "stop_sequence" to kotlinx.serialization.json.JsonNull,
                "usage" to JsonObject(mapOf("input_tokens" to JsonPrimitive(1_200), "output_tokens" to JsonPrimitive(300))),
            ),
        )
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }

    private fun enricher() = ClaudeEnricher("test-key", LONDON, endpoint = server.url("").toString().trimEnd('/'))

    @Test
    fun `it asks Opus 5 point 5 at high effort for the schema's answer, with fallbacks on`() = runBlocking {
        server.enqueue(reply("""{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-12T08:30","effortMin":10,"nextStep":"Sign the form"}"""))
        val result = enricher().enrich(email(), Enrichments.Job.Email, NOW)
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("test-key", request.getHeader("x-api-key"))
        assertTrue(ClaudeEnricher.FALLBACK_BETA in request.getHeader("anthropic-beta").orEmpty(), request.headers.toString())
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(16_000, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        val config = body["output_config"]!!.jsonObject
        assertEquals("high", config["effort"]!!.jsonPrimitive.content)
        val format = config["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("kind", "actionableFrom", "deadline", "effortMin", "nextStep", "blocks"), format["schema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("triage one email" in body["system"].toString(), body["system"].toString().take(120))
        assertTrue("Subject: Berlin trip form" in body["messages"].toString())
        // No thinking setting: Opus 5.5 always thinks, and rejects one that turns it off.
        assertNull(body["thinking"])

        val e = result.enrichment!!
        assertEquals("claude-opus-5-5", e.by)
        assertEquals(Fixtures.at("2026-10-12T08:30"), e.deadline)
        assertEquals(0.0108, result.costUsd, 1e-9)
    }

    @Test
    fun `a refusal is counted, and says nothing`() = runBlocking {
        server.enqueue(reply(null, stop = "refusal"))
        val result = enricher().enrich(email(), Enrichments.Job.Email, NOW)
        assertTrue(result.refused)
        assertNull(result.enrichment)
        assertEquals(0.0108, result.costUsd, 1e-9)
    }

    @Test
    fun `an answer cut off by its token limit is not trusted`() = runBlocking {
        server.enqueue(reply("""{"subSteps":[{"title":"Pa""", stop = "max_tokens"))
        assertNull(enricher().enrich(assignment("1. a\n2. b"), Enrichments.Job.Assignment, NOW).enrichment)
    }

    @Test
    fun `a fallback model's answer is priced at its own rates`() = runBlocking {
        server.enqueue(reply("""{"effortMin":30}""", model = "claude-opus-4-8"))
        val item = assignment("Revise").copy(source = Source.PowerPlanner, id = "powerplanner:p")
        val result = enricher().enrich(item, Enrichments.Job.Effort, NOW)
        assertEquals(30, result.enrichment!!.effortMin)
        assertEquals(0.0135, result.costUsd, 1e-9)
    }
}

class PhotoCheckerTest {
    @Test
    fun `the photo goes as a JPEG image block with the piece, and the verdict is read`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val text = """{"done":true,"confidence":1.4,"reason":"All eight answers are written out."}"""
            val body = JsonObject(
                mapOf(
                    "id" to JsonPrimitive("msg"), "type" to JsonPrimitive("message"), "role" to JsonPrimitive("assistant"), "model" to JsonPrimitive("claude-opus-5-5"),
                    "content" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))),
                    "stop_reason" to JsonPrimitive("end_turn"), "stop_sequence" to kotlinx.serialization.json.JsonNull,
                    "usage" to JsonObject(mapOf("input_tokens" to JsonPrimitive(1_600), "output_tokens" to JsonPrimitive(100))),
                ),
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()))
            val result = PhotoChecker("test-key", endpoint = server.url("").toString().trimEnd('/')).check(byteArrayOf(1, 2, 3), "Chapter 17 review, part 2 of 3")
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val content = request["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            val image = content.first().jsonObject
            assertEquals("image", image["type"]!!.jsonPrimitive.content)
            assertEquals("image/jpeg", image["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
            assertEquals("AQID", image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            assertTrue("Chapter 17 review, part 2 of 3" in content[1].toString())
            assertEquals(true, result.verdict!!.done)
            // A confidence past 1 is read as 1.
            assertEquals(1.0, result.verdict!!.confidence)
        } finally {
            server.shutdown()
        }
    }
}
