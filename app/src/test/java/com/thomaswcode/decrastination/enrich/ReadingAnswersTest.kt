package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.TaskPhoto
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.core.withEnrichment
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReadingAnswersTest {
    private fun parse(job: Enrichments.Job, text: String, task: TaskItem = email()) = ReadingAnswers.parse(job, text, task, "claude-opus-5-5", NOW, LONDON)

    /** The first time of asking (AI-01): nothing left out, the check failed its complaint. */
    private fun strict(job: Enrichments.Job, text: String, task: TaskItem = email()) = ReadingAnswers.read(job, text, task, "claude-opus-5-5", NOW, LONDON, salvage = false)

    @Test
    fun `a date that can't be read, one too far ahead, a kind that isn't one and an answer that isn't JSON are said, to be put right`() {
        fun triage(deadline: String, kind: String = "Admin") = """{"kind":"$kind","actionableFrom":null,"deadline":$deadline,"deadlineQuoted":true,"effortMin":10,"nextStep":"x","question":null,"reason":"","blocks":[]}"""
        assertEquals("Deadline \"Friday\" isn't a date (YYYY-MM-DD) or a local date and time (YYYY-MM-DDTHH:MM)", strict(Enrichments.Job.Email, triage("\"Friday\"")).complaint)
        assertEquals("Deadline 2028-10-16 is more than 400 days ahead, further than the app plans: give it only if the text says so", strict(Enrichments.Job.Email, triage("\"2028-10-16\"")).complaint)
        assertEquals("Kind is \"Info\": it must be Admin", strict(Enrichments.Job.Email, triage("null", kind = "Info")).complaint)
        assertTrue(strict(Enrichments.Job.Email, "{not json").complaint!!.startsWith("The answer isn't JSON the schema takes"))
        // An old email's long-gone date is no mistake: left out without a word.
        val old = strict(Enrichments.Job.Email, triage("\"2021-01-04\""))
        assertNull(old.complaint)
        assertNull(old.enrichment!!.deadline)
    }

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
    fun `a reading's summary of a long text is kept, none for a short one`() {
        // D-93: what Felix is told in place of a text too long for his room.
        val e = parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":null,"effortMin":10,"nextStep":"x","summary":"  Return the form by Friday; bring a packed lunch.  "}""")!!
        assertEquals("Return the form by Friday; bring a packed lunch.", e.summary)
        assertNull(parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":null,"effortMin":10,"nextStep":"x","summary":""}""")!!.summary)
        assertEquals(500, parse(Enrichments.Job.Effort, """{"effortMin":30,"blocks":[],"summary":"${"y".repeat(700)}"}""")!!.summary!!.length)
    }

    @Test
    fun `whether an email's deadline is its own words is read and kept (AI-12)`() {
        val quoted = parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-16","deadlineQuoted":true,"effortMin":10,"nextStep":"Hand in the form","blocks":[]}""")!!
        assertTrue(quoted.deadlineQuoted)
        assertEquals(true, email().withEnrichment(quoted).enrichment?.deadlineQuoted)
        // A day it only suggests: the date stands, as a plan.
        val suggested = parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-16","deadlineQuoted":false,"effortMin":10,"nextStep":"Reply","blocks":[]}""")!!
        assertEquals(Fixtures.at("2026-10-16T23:59"), suggested.deadline)
        assertFalse(suggested.deadlineQuoted)
        // Without the flag, or of a deadline dropped as out of range, nothing is quoted: never hard by accident.
        assertFalse(parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-16","effortMin":10,"nextStep":"x"}""")!!.deadlineQuoted)
        assertFalse(parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":"2031-01-01","deadlineQuoted":true,"effortMin":10,"nextStep":"x"}""")!!.deadlineQuoted)
        // Asked for, and told when it's true; the quick action's two weeks are gone.
        val system = ReadingPrompts.system(Enrichments.Job.Email)
        assertTrue("deadlineQuoted: true when deadline is a date the email itself gives" in system, system)
        assertFalse("within the next two weeks" in system, system)
        assertEquals("boolean", ReadingPrompts.schemaJson(Enrichments.Job.Email).jsonObject["properties"]!!.jsonObject["deadlineQuoted"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an assignment's steps say where they can be done (PL-15)`() {
        val e = parse(
            Enrichments.Job.Assignment,
            """{"subSteps":[{"title":"Read pages 40-44","minutes":20,"ankiSections":[],"from":null,"due":null,"where":"anywhere"},{"title":"Write the essay","minutes":40,"ankiSections":[],"from":null,"due":null,"where":"home"}],"effortMin":60,"ankiSections":[],"testDate":null}""",
            assignment("Read pages 40-44, then write the essay."),
        )!!
        assertEquals(listOf("anywhere", "home"), e.subSteps!!.map { it.where })
        assertTrue("\"home\" for a step that needs the laptop" in ReadingPrompts.system(Enrichments.Job.Assignment))
    }

    @Test
    fun `what's out of range is dropped rather than trusted`() {
        val e = parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":"1999-01-01","deadline":"2031-01-01T09:00","effortMin":5000,"nextStep":"  "}""")!!
        assertEquals(Kind.Admin, e.kind)
        assertNull(e.actionableFrom)
        assertNull(e.deadline)
        assertNull(e.effortMin)
        assertNull(e.nextStep)
        assertNull(parse(Enrichments.Job.Email, "not json"))
    }

    @Test
    fun `a starred email read as only to read, or as any kind but Admin, is no answer, an older Event a task`() {
        // Starred, it's always something to do (AI-02): an Info answer is unreadable, and the rules stand.
        assertNull(parse(Enrichments.Job.Email, """{"kind":"Info","actionableFrom":null,"deadline":null,"effortMin":2,"nextStep":"Read and archive","blocks":[]}"""))
        assertNull(parse(Enrichments.Job.Email, """{"kind":"Homework","actionableFrom":null,"deadline":null,"effortMin":20,"nextStep":"Do it","blocks":[]}"""))
        assertEquals(Kind.Admin, parse(Enrichments.Job.Email, """{"kind":"Event","actionableFrom":null,"deadline":"2026-10-17T10:00","effortMin":5,"nextStep":"Go to the open day","blocks":[]}""")!!.kind)
        assertEquals(listOf("Admin"), ReadingPrompts.schemaJson(Enrichments.Job.Email).jsonObject["properties"]!!.jsonObject["kind"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
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
    fun `a step longer than an hour is cut into even sittings, then Continue, the last Continue and finish`() {
        val e = parse(
            Enrichments.Job.Assignment,
            """{"subSteps":[{"title":"Write the essay","minutes":220,"ankiSections":[]},{"title":"Check it","minutes":20,"ankiSections":[]}],"effortMin":240,"ankiSections":[],"testDate":null}""",
            assignment("Essay"),
        )!!
        assertEquals(
            listOf("Write the essay" to 55, "Continue: Write the essay" to 55, "Continue: Write the essay" to 55, "Continue and finish: Write the essay" to 55, "Check it" to 20),
            e.subSteps!!.map { it.title to it.minutes },
        )
    }

    @Test
    fun `one piece of work is taken whole up to a task's most, then cut into sittings`() {
        val e = parse(Enrichments.Job.Assignment, """{"subSteps":[{"title":"Coursework draft","minutes":300,"ankiSections":[],"from":null,"due":null}],"effortMin":300,"ankiSections":[],"testDate":null}""", assignment("Write the draft."))!!
        assertNull(e.dropped)
        assertEquals(List(5) { 60 }, e.subSteps!!.map { it.minutes })
        assertEquals("Continue and finish: Coursework draft", e.subSteps!!.last().title)
    }

    @Test
    fun `steps past the most are folded before they're cut into sittings, so a fold is cut too`() {
        // Twelve given, the last two hours: all kept, it in two sittings.
        val short = (1..11).joinToString(",") { """{"title":"Question $it","minutes":10,"ankiSections":[],"from":null,"due":null}""" }
        val twelve = parse(Enrichments.Job.Assignment, """{"subSteps":[$short,{"title":"Write up","minutes":120,"ankiSections":[],"from":null,"due":null}],"effortMin":230,"ankiSections":[],"testDate":null}""", assignment("Do it."))!!
        assertEquals(13, twelve.subSteps!!.size)
        assertEquals(listOf("Write up" to 60, "Continue and finish: Write up" to 60), twelve.subSteps!!.takeLast(2).map { it.title to it.minutes })
        // Fourteen: the last three folded into one, and that cut too, no sitting over an hour.
        val fourteen = parse(Enrichments.Job.Assignment, """{"subSteps":[$short,{"title":"Plan","minutes":10,"ankiSections":[],"from":null,"due":null},{"title":"Draft","minutes":100,"ankiSections":[],"from":null,"due":null},{"title":"Check","minutes":10,"ankiSections":[],"from":null,"due":null}],"effortMin":230,"ankiSections":[],"testDate":null}""", assignment("Do it."))!!
        assertTrue(fourteen.subSteps!!.all { it.minutes <= ReadingAnswers.MAX_PART_MIN }, fourteen.subSteps.toString())
        assertEquals(230, fourteen.subSteps!!.sumOf { it.minutes })
        assertTrue(fourteen.subSteps!![11].title.startsWith("The rest: Plan"))
    }

    @Test
    fun `a step done before is kept whole, so all its work keeps its tick`() {
        // Read by older prompts as a two-hour step, done; read again, it's given as it was.
        val given = assignment("Revise for the test.").let { t -> t.withEnrichment(Enrichment(Enrichments.inputHash(t), "claude-opus-5-5", NOW, effortMin = 210, subSteps = listOf(SubStep("Revise", 120), SubStep("Practice paper", 90)), promptVersion = 4)) }
        val task = given.copy(subSteps = given.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s })
        val e = parse(Enrichments.Job.Assignment, """{"subSteps":[{"title":"Revise","minutes":120,"ankiSections":[],"from":null,"due":null},{"title":"Practice paper","minutes":90,"ankiSections":[],"from":null,"due":null}],"effortMin":210,"ankiSections":[],"testDate":null}""", task)!!
        assertEquals(listOf("Revise" to 120, "Practice paper" to 45, "Continue and finish: Practice paper" to 45), e.subSteps!!.map { it.title to it.minutes })
        assertEquals(listOf(true, false, false), task.withEnrichment(e).subSteps.map { it.done })
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
        val calendar = """{"kind":"Event","actionableFrom":null,"deadline":null,"effortMin":90,"nextStep":"Note the dates","blocks":[{"title":"Apply to STEM Potential","minutes":60,"from":"2026-10-30","due":"2026-11-20"},{"title":"Apply to RAL","minutes":30,"from":null,"due":"2027-01-15"}]}"""
        val e = parse(Enrichments.Job.Email, calendar)!!
        assertEquals(Kind.Admin, e.kind)
        assertEquals(90, e.effortMin)
        val blocks = e.subSteps!!
        assertEquals(Fixtures.at("2026-10-30T00:00"), blocks[0].from)
        assertEquals(Fixtures.at("2026-11-20T23:59"), blocks[0].dueAt)
        assertNull(blocks[1].from)
        // A block that opens after it's due: put back to the model the first time of asking (AI-01),
        // and asked again, kept with no dates of its own, saying so.
        val wrong = calendar.replace("2026-11-20", "2026-10-29")
        assertEquals("Block 1 ('Apply to STEM Potential') starts on 2026-10-30, after its own due time 2026-10-29T23:59", strict(Enrichments.Job.Email, wrong).complaint)
        val muddled = parse(Enrichments.Job.Email, wrong)!!
        assertEquals(listOf(null to null, null to Fixtures.at("2027-01-15T23:59")), muddled.subSteps!!.map { it.from to it.dueAt })
        assertEquals(Kind.Admin, muddled.kind)
        assertEquals(90, muddled.effortMin)
        assertNull(muddled.dropped)
        assertEquals(listOf("'Apply to STEM Potential': its start was after its own due time, so it's planned by the task's date"), muddled.adjusted)
    }

    @Test
    fun `blocks with a total out of range, or starting after their task is due, aren't trusted`() {
        // Past twenty hours, adding up or not.
        val blocks = (1..25).joinToString(",") { """{"title":"B$it","minutes":50,"from":null,"due":null}""" }
        val wild = parse(Enrichments.Job.Effort, """{"effortMin":1250,"blocks":[$blocks]}""")!!
        assertNull(wild.subSteps)
        assertNull(wild.effortMin)
        assertEquals("the blocks come to 20.8 hours, over the 20 hours one task can take", wild.dropped)
        // The email due on the 15th; a block opening on the 20th can't be done by then: put back,
        // then kept by the email's own date.
        val text = """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-15","effortMin":60,"nextStep":"Apply","blocks":[{"title":"Apply","minutes":60,"from":"2026-10-20","due":null}]}"""
        assertEquals("Block 1 ('Apply') starts on 2026-10-20, after the due time 2026-10-15T23:59", strict(Enrichments.Job.Email, text).complaint)
        val late = parse(Enrichments.Job.Email, text)!!
        assertEquals(listOf(null), late.subSteps!!.map { it.from })
        assertEquals(listOf("'Apply': its start was after the hand-in, so it's planned by the task's date"), late.adjusted)
    }

    @Test
    fun `blocks due before the email opens, or in an order their dates forbid, aren't trusted`() {
        val early = parse(
            Enrichments.Job.Email,
            """{"kind":"Admin","actionableFrom":"2026-12-01","deadline":"2026-12-31","effortMin":60,"nextStep":"Apply","blocks":[{"title":"Apply","minutes":60,"from":null,"due":"2026-11-15"}]}""",
        )!!
        assertNull(early.subSteps)
        assertEquals("block 1 ('Apply') is due 2026-11-15T23:59, before the email can be started on 2026-12-01", early.dropped)
        val backwards = parse(Enrichments.Job.Effort, """{"effortMin":60,"blocks":[{"title":"A","minutes":30,"from":"2026-10-20","due":null},{"title":"B","minutes":30,"from":null,"due":"2026-10-12"}]}""")!!
        assertNull(backwards.subSteps)
        assertEquals("block 1 ('A') can't start till 2026-10-20, after block 2 ('B') is due 2026-10-12T23:59", backwards.dropped)
    }

    @Test
    fun `blocks are counted as given, before a long one is cut into parts`() {
        fun given(count: Int) = (listOf("""{"title":"Long","minutes":61,"from":null,"due":null}""") + (2..count).map { """{"title":"B$it","minutes":10,"from":null,"due":null}""" }).joinToString(",")
        val total = 61 + (ReadingPrompts.MAX_BLOCKS - 1) * 10
        val e = parse(Enrichments.Job.Effort, """{"effortMin":$total,"blocks":[${given(ReadingPrompts.MAX_BLOCKS)}]}""")!!
        assertEquals(ReadingPrompts.MAX_BLOCKS + 1, e.subSteps!!.size)
        // One more than that isn't trusted.
        val over = parse(Enrichments.Job.Effort, """{"effortMin":${total + 10},"blocks":[${given(ReadingPrompts.MAX_BLOCKS + 1)}]}""")!!
        assertNull(over.subSteps)
        assertEquals("there are 31 blocks, over the 30 allowed", over.dropped)
    }

    @Test
    fun `an email holding months of applications can come to more than one task's ten hours`() {
        // Thirteen hours over five months, in order, as a calendar of work-experience deadlines came to.
        val months = listOf("2026-11", "2026-12", "2027-01", "2027-02", "2027-03")
        val blocks = (0 until 13).joinToString(",") { i -> """{"title":"Application ${i + 1}","minutes":60,"from":"${months[i * months.size / 13]}-01","due":"${months[i * months.size / 13]}-28"}""" }
        val e = parse(
            Enrichments.Job.Email,
            """{"kind":"Admin","actionableFrom":null,"deadline":"2027-03-31","effortMin":780,"nextStep":"Note the dates","blocks":[$blocks]}""",
        )!!
        assertEquals(13, e.subSteps!!.size)
        assertEquals(780, e.effortMin)
        // Without blocks, an email's own estimate past ten hours still isn't taken.
        assertNull(parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":null,"effortMin":780,"nextStep":"Do it","blocks":[]}""")!!.effortMin)
    }

    @Test
    fun `a dropped plan says why`() {
        fun block(title: String = "Apply", minutes: Int = 30, from: String? = null, due: String? = null) =
            """{"title":"$title","minutes":$minutes,"from":${from?.let { "\"$it\"" }},"due":${due?.let { "\"$it\"" }}}"""
        fun effort(vararg blocks: String) = parse(Enrichments.Job.Effort, """{"effortMin":${30 * blocks.size},"blocks":[${blocks.joinToString(",")}]}""")!!.dropped
        assertEquals("block 1 has no title", effort(block(title = " ")))
        assertEquals("block 1 ('Apply') has no minutes", parse(Enrichments.Job.Effort, """{"effortMin":30,"blocks":[${block(minutes = 0)}]}""")!!.dropped)
        assertEquals("block 1 ('Apply') is 700 minutes, over the 600 allowed", parse(Enrichments.Job.Effort, """{"effortMin":700,"blocks":[${block(minutes = 700)}]}""")!!.dropped)
        // An unreadable date, or one out of range, isn't left out: it fails the reading still.
        assertEquals("block 1 ('Apply') has a date that can't be read: 'soon'", effort(block(due = "soon")))
        assertEquals("block 1 ('Apply') has a date out of range: 2030-01-01T23:59", effort(block(due = "2030-01-01")))
        assertEquals("block 2 ('Apply') starts on 2026-10-20, after its own due time 2026-10-15T23:59", ReadingAnswers.read(Enrichments.Job.Effort, """{"effortMin":60,"blocks":[${block()},${block(from = "2026-10-20", due = "2026-10-15")}]}""", email(), "m", NOW, LONDON, salvage = false).enrichment!!.dropped)
        // An assignment's steps say the same of a step, and of a total past one task's.
        val split = { steps: String, total: Int -> parse(Enrichments.Job.Assignment, """{"subSteps":[$steps],"effortMin":$total,"ankiSections":[],"testDate":null}""", assignment("Do it"))!!.dropped }
        assertEquals("step 1 ('Essay') is 700 minutes, over the 600 allowed", split("""{"title":"Essay","minutes":700,"ankiSections":[],"from":null,"due":null}""", 700))
        assertEquals("the steps add up to 60 minutes, not the 120 effortMin gives", split("""{"title":"Essay","minutes":60,"ankiSections":[],"from":null,"due":null}""", 120))
        assertNull(split("""{"title":"Essay","minutes":60,"ankiSections":[],"from":null,"due":null}""", 60))
        // Said to the model, it starts with a capital.
        assertEquals("The steps add up to 60 minutes, not the 120 effortMin gives", strict(Enrichments.Job.Assignment, """{"subSteps":[{"title":"Essay","minutes":60,"ankiSections":[],"from":null,"due":null}],"effortMin":120,"ankiSections":[],"testDate":null}""", assignment("Do it")).complaint)
        assertNull(strict(Enrichments.Job.Effort, """{"effortMin":35,"blocks":[]}""").complaint)
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
        assertEquals("the blocks add up to 30 minutes, not the 200 effortMin gives", off.dropped)
        // Kept, or none given: nothing dropped.
        assertNull(e.dropped)
        assertNull(parse(Enrichments.Job.Effort, """{"effortMin":35,"blocks":[]}""")!!.dropped)
    }

    @Test
    fun `a long split folds its plain steps in their places`() {
        val first = (1..11).joinToString(",") { """{"title":"Part $it","minutes":10,"ankiSections":[],"from":null,"due":null}""" }
        val tail = """{"title":"Research","minutes":10,"ankiSections":[],"from":null,"due":null},{"title":"Notes","minutes":10,"ankiSections":[],"from":null,"due":null},{"title":"Submit draft","minutes":10,"ankiSections":[],"from":null,"due":"2026-10-12"},{"title":"Proofread","minutes":10,"ankiSections":[],"from":null,"due":null}"""
        val e = parse(Enrichments.Job.Assignment, """{"subSteps":[$first,$tail],"effortMin":150,"ankiSections":[],"testDate":null}""", assignment("Essay"))!!
        assertEquals(listOf("The rest: Research; Notes", "Submit draft", "Proofread"), e.subSteps!!.takeLast(3).map { it.title })
    }

    @Test
    fun `a reading quotes what gave it, and is asked with the steps as planned before`() {
        val e = parse(
            Enrichments.Job.Email,
            """{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-12","deadlineQuoted":true,"effortMin":10,"nextStep":"Sign the form","question":null,"reason":"“return the trip form by Monday 12 October”","blocks":[]}""",
        )!!
        assertEquals("return the trip form by Monday 12 October", e.reason)
        assertNull(parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":null,"effortMin":10,"nextStep":"Sign","reason":" ","blocks":[]}""")!!.reason)
        val asked = ReadingPrompts.describe(assignment("Essay"), Enrichments.Job.Assignment, NOW, LONDON, listOf(SubStep("Plan", 20), SubStep("Write", 60)))
        assertTrue("Steps as planned before (keep the exact title of any step whose work is unchanged; mark none as done):\n1. Plan (20 min)\n2. Write (60 min)" in asked, asked)
        assertFalse("planned before" in ReadingPrompts.describe(assignment("Essay"), Enrichments.Job.Assignment, NOW, LONDON))
        // One done is said to be, to be kept as it is: its tick goes only to a step of its name.
        val done = ReadingPrompts.describe(assignment("Essay"), Enrichments.Job.Assignment, NOW, LONDON, listOf(SubStep("Plan", 20, done = true), SubStep("Write", 60)))
        assertTrue("Steps as planned before (keep the exact title of any step whose work is unchanged; a step marked done is done: keep it as it is, its exact title and minutes (still counted in effortMin), and give only the work left in the others):\n1. Plan (20 min, done)\n2. Write (60 min)" in done, done)
        // The decks by topic, for an assignment (DX-10).
        assertTrue("Decks: 1.3 Holidays and travel; 1.4 School" in ReadingPrompts.describe(assignment("Vocab"), Enrichments.Job.Assignment, NOW, LONDON, decks = mapOf("1.4" to "School", "1.3" to "Holidays and travel")))
    }

    @Test
    fun `every worked example's answer is one the checks pass whole, and each is in its prompt`() {
        for (job in Enrichments.Job.entries) {
            for (example in Examples.of(job)) {
                val item = (if (job == Enrichments.Job.Email) email() else assignment("Example")).copy(dueAt = example.due?.let(Fixtures::at), sourceValues = null)
                val read = ReadingAnswers.read(job, example.answer, item, "claude-opus-5-5", NOW, LONDON, salvage = false)
                assertNotNull(read.enrichment, "$job: ${example.answer.take(60)}")
                assertNull(read.complaint, "$job: ${read.complaint}")
                assertTrue("Answer: ${example.answer}" in ReadingPrompts.system(job), "$job")
            }
        }
        assertTrue(ReadingPrompts.system(Enrichments.Job.Assignment).contains("    Title: Vocabulary and reading"))
    }

    @Test
    fun `a piece's first steps are read, asked once for its task as it is, and kept for today and tomorrow`() {
        val task = assignment("1. Read pages 40-44.\n2. Write the essay.").copy(dueAt = Fixtures.at("2026-10-09T23:59"))
        val chunk = Planner.plan(Planner.Input(listOf(task), NOW, LONDON, Settings(), windows = mapOf(LocalDate.parse("2026-10-09") to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))))
        val piece = chunk.todaySlots.first { it.kind == SlotKind.Work }.chunk!!
        val made = MicroPlans.parse("""{"first":"Open the essay title and read it aloud","next":["List three points","Write the first paragraph","An extra"],"doneWhen":"A paragraph a point"}""", piece, task, NOW)!!
        assertEquals(listOf("List three points", "Write the first paragraph"), made.next)
        assertEquals(listOf("First: Open the essay title and read it aloud", "Then: List three points; Write the first paragraph", "Done when: A paragraph a point"), MicroPlans.lines(made))
        val tasks = mapOf(task.id to task)
        // Each of today's pieces is due one.
        val keys = chunk.todaySlots.filter { it.kind == SlotKind.Work }.mapNotNull { it.chunk?.key }
        assertEquals(keys, MicroPlans.due(chunk, tasks, emptyMap()).map { it.first.key })
        // Written: not due again, till its task changes.
        assertEquals(keys - piece.key, MicroPlans.due(chunk, tasks, mapOf(piece.key to made)).map { it.first.key })
        assertEquals(keys, MicroPlans.due(chunk, mapOf(task.id to task.copy(detail = "Changed")), mapOf(piece.key to made)).map { it.first.key })
        // Declined or unread: kept empty, so not asked again.
        assertEquals(keys - piece.key, MicroPlans.due(chunk, tasks, mapOf(piece.key to MicroPlans.none(piece, task, NOW))).map { it.first.key })
        assertEquals(emptyList(), MicroPlans.lines(MicroPlans.none(piece, task, NOW)))
        // Others go.
        assertEquals(setOf(piece.key), MicroPlans.kept(chunk, mapOf(piece.key to made, "gone#1" to made)).keys)
        // Its task changed since: out of date, let go rather than shown.
        assertEquals(setOf(piece.key), MicroPlans.kept(chunk, mapOf(piece.key to made), tasks).keys)
        assertEquals(emptySet(), MicroPlans.kept(chunk, mapOf(piece.key to made), mapOf(task.id to task.copy(detail = "Changed"))).keys)
        val asked = MicroPlans.describe(piece, task)
        assertTrue("Task: ${task.title}" in asked && "Instructions:\n1. Read pages 40-44." in asked, asked)
        // The piece itself changed (its step renamed, its minutes): written for again.
        assertTrue(MicroPlans.hashOf(task, piece) != MicroPlans.hashOf(task, piece.copy(step = "Read pages 40-46")))
        assertTrue(MicroPlans.hashOf(task, piece) != MicroPlans.hashOf(task, piece.copy(minutes = piece.minutes + 10)))
        // The notes kept with its task (8a): told it, and a note added has it written for again; with none, as before.
        val noted = listOf("use the second source")
        assertTrue("Notes kept with it: use the second source" in MicroPlans.describe(piece, task, noted))
        assertTrue(MicroPlans.hashOf(task, piece) != MicroPlans.hashOf(task, piece, noted))
        assertEquals(MicroPlans.hashOf(task, piece), MicroPlans.hashOf(task, piece, emptyList()))
        assertEquals(keys, MicroPlans.due(chunk, tasks, mapOf(piece.key to made), mapOf(task.id to noted)).map { it.first.key })
        assertEquals(keys - piece.key, MicroPlans.due(chunk, tasks, mapOf(piece.key to MicroPlans.parse("""{"first":"Open it","next":[],"doneWhen":""}""", piece, task, NOW, noted)!!), mapOf(task.id to noted)).map { it.first.key })
    }

    @Test
    fun `a photo of the work is read with its task, and a photo of homework read into tasks`() {
        val task = assignment("Essay")
        val photographed = task.copy(photo = TaskPhoto("/x.jpg", "ab12cd34ef56ab12", NOW))
        // A task photographed is read again (AI-23).
        assertNotEquals(Enrichments.inputHash(task), Enrichments.inputHash(photographed))
        assertTrue("say in reason what you saw" in ReadingPrompts.photo(Enrichments.Job.Assignment))
        val read = PhotoTasks.parse(
            """{"tasks":[{"title":"Worksheet 5","subject":"Physics","dueDate":"2026-10-14","dueTime":"08:30","instructions":"Questions 1-6","minutes":45,"where":"anywhere","steps":[]},{"title":"","subject":"","dueDate":"","dueTime":"","instructions":"","minutes":0,"where":"home","steps":[]}],"question":"Is it due Wednesday or Thursday?"}""",
            NOW, LONDON,
        )!!
        assertEquals(listOf("Worksheet 5"), read.tasks.map { it.title })
        assertEquals(Fixtures.at("2026-10-14T08:30"), read.tasks.single().dueAt)
        assertEquals("Physics" to "Questions 1-6", read.tasks.single().className to read.tasks.single().notes)
        assertEquals(1, read.leftOut.size)
        assertEquals("Is it due Wednesday or Thursday?", read.question)
    }

    @Test
    fun `the limits the answers are checked against are said in the prompts and the schemas`() {
        assertEquals(
            "Limits the app enforces: at most 12 steps; a total of at most 10 hours; the steps must add up to effortMin exactly.",
            ReadingPrompts.limits(Enrichments.Job.Assignment),
        )
        assertTrue(ReadingPrompts.limits(Enrichments.Job.Assignment) in ReadingPrompts.system(Enrichments.Job.Assignment))
        assertTrue("at most 30 blocks, none longer than 600 minutes, 20 hours in all" in ReadingPrompts.system(Enrichments.Job.Email))
        val step = ReadingPrompts.schemaJson(Enrichments.Job.Assignment).jsonObject["properties"]!!.jsonObject["subSteps"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("All its work, however long (the app cuts one over 60 minutes into sittings itself); never over 600", step["minutes"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertTrue("never after the task's due date" in step["from"]!!.jsonObject["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a step can't start after the test that's its task's deadline`() {
        val text = """{"subSteps":[{"title":"Revise","minutes":60,"ankiSections":[],"from":"2026-10-13","due":null}],"effortMin":60,"ankiSections":[],"testDate":"2026-10-12"}"""
        // Put back the first time of asking; then kept, by the test's day.
        assertEquals("Step 1 ('Revise') starts on 2026-10-13, after the due time 2026-10-12T08:30", strict(Enrichments.Job.Assignment, text, assignment("Revise for the test")).complaint)
        val e = parse(Enrichments.Job.Assignment, text, assignment("Revise for the test"))!!
        assertEquals(listOf("Revise" to null), e.subSteps!!.map { it.title to it.from })
        assertEquals(listOf("'Revise': its start was after the hand-in, so it's planned by the task's date"), e.adjusted)
        assertTrue("No step may start after the assignment's due time" in ReadingPrompts.system(Enrichments.Job.Assignment))
        assertTrue("No block may start after the item's date" in ReadingPrompts.system(Enrichments.Job.Effort))
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
