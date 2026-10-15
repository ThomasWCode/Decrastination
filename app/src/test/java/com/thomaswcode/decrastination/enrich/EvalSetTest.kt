package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.withEnrichment
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test

/**
 * The private evaluation set (IMP-P1-004, docs/eval.md): a dozen of your real emails and
 * assignments, each with the right answer, in `private/eval/` (never committed). Run only where
 * that folder is, so on this PC; skipped everywhere else, CI included.
 *
 * 1. `dump` writes each case's request as the app makes it ([Prompts.system], [Prompts.describe],
 *    [Prompts.schemaJson]) to `private/eval/prompts/<id>.txt`, email addresses redacted.
 * 2. Opus, through Claude Code, answers each into `private/eval/answers/<id>-<run>.json`: no API
 *    spend.
 * 3. `score` puts every answer through the app's own checks ([Answers.parse]) and the planner, as
 *    the app would, and writes `private/eval/report.md`: each case's kind, deadline, minutes,
 *    must-mention items, blocks and sections against its labels.
 *
 * [EvalRunTest] does 1 and 2 through the API instead, a paid call a case, then 3.
 */
class EvalSetTest {
    internal val dir = listOf(File("../private/eval"), File("private/eval")).firstOrNull { File(it, "cases.json").exists() }
    private val zone: ZoneId = ZoneId.of("Europe/London")

    /** One case: the task as stored, when it's asked about, and what a right answer has. */
    @Serializable
    data class Case(val id: String, val job: Enrichments.Job, val now: String, val task: TaskItem, val expect: Expect, val note: String = "")

    /**
     * What a right answer has; null where it doesn't matter. [deadline] "none" for no deadline;
     * [deadlineQuoted] whether it's the email's own words (AI-12).
     * [mentions]: what some step, block or the next step must each hold, case aside; parts joined
     * by `+` must all be in the same one ("Imperial+Work Experience": not met by a STEM Potential block).
     */
    @Serializable
    data class Expect(
        val kind: String? = null,
        val deadline: String? = null,
        val deadlineQuoted: Boolean? = null,
        val effortMin: List<Int>? = null,
        val mentions: List<String> = emptyList(),
        val blocksAtLeast: Int? = null,
        val ankiSections: List<String>? = null,
        val testDate: String? = null,
    )

    private fun allCases(): List<Case> {
        assumeTrue("No private/eval/cases.json: nothing to evaluate here", dir != null)
        return JsonStore.json.decodeFromString(ListSerializer(Case.serializer()), File(dir, "cases.json").readText())
    }

    /** The cases a right answer can pass: one labelled Info can't, since starred emails are never only to read (AI-02). */
    internal fun cases(): List<Case> = allCases().filterNot(::retired)

    private fun retired(case: Case) = case.expect.kind == "Info"

    internal fun nowOf(case: Case): Long = LocalDateTime.parse(case.now).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun dump() {
        val cases = cases()
        val out = File(dir!!, "prompts").apply { mkdirs() }
        for (case in cases) {
            val user = Prompts.describe(case.task, case.job, nowOf(case), zone).take(Pricing.MAX_INPUT_CHARS)
            File(out, "${case.id}.txt").writeText(
                buildString {
                    appendLine("=== SYSTEM ===")
                    appendLine(Prompts.system(case.job))
                    appendLine()
                    // The second system block every call sends (AI-05), here from the settings' defaults.
                    appendLine("=== PROFILE ===")
                    appendLine(Profile.block(com.thomaswcode.decrastination.data.Settings(), listOf(case.task), null, com.thomaswcode.decrastination.core.SchoolCalendar(), com.thomaswcode.decrastination.core.MemoryState(), nowOf(case), zone))
                    appendLine()
                    appendLine("=== USER ===")
                    appendLine(redact(user))
                    appendLine()
                    appendLine("=== ANSWER: one JSON object matching this schema, nothing else ===")
                    appendLine(Prompts.schemaJson(case.job).toString())
                },
            )
        }
    }

    @Test
    fun score() {
        val cases = cases()
        val retired = allCases().filter(::retired).map { it.id }.toSet()
        // A retired case's answers from earlier rounds aren't scored, nor counted as no case's.
        val answers = File(dir!!, "answers").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            .filterNot { it.nameWithoutExtension.substringBeforeLast('-') in retired }
        assumeTrue("No answers in private/eval/answers yet", answers.isNotEmpty())
        val byId = cases.associateBy { it.id }
        val rows = mutableListOf<String>()
        var passed = 0
        fun failed(name: String, why: String) {
            rows += "| $name | **fail** | **$why** |"
        }
        // Named <case>-<run>.json: the case is everything before the last '-', matched whole.
        for ((run, files) in answers.groupBy { it.nameWithoutExtension.substringAfterLast('-') }.toSortedMap()) {
            val answered = mutableSetOf<String>()
            for (file in files) {
                val case = byId[file.nameWithoutExtension.substringBeforeLast('-')]
                if (case == null) {
                    failed(file.nameWithoutExtension, "no such case")
                    continue
                }
                answered += case.id
                val now = nowOf(case)
                val text = file.readText().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val enrichment = Answers.parse(case.job, text, case.task, "eval", now, zone)
                val checks = if (enrichment == null) listOf("unreadable" to false) else check(case, case.task.withEnrichment(enrichment), enrichment, now, text)
                val ok = checks.all { it.second }
                if (ok) passed++
                rows += "| ${file.nameWithoutExtension} | ${if (ok) "pass" else "**fail**"} | " + checks.joinToString("; ") { (what, good) -> if (good) what else "**$what**" } + " |"
            }
            // Every case of the jobs a round answers (a round can be one job's, where only its prompt
            // changed): one with no answer fails, rather than leaving a smaller round that passes.
            val jobs = answered.mapNotNull { byId[it]?.job }.toSet()
            cases.filter { it.id !in answered && it.job in jobs }.forEach { failed("${it.id}-$run", "no answer") }
        }
        File(dir!!, "report.md").writeText(
            buildString {
                appendLine("# Evaluation report")
                appendLine()
                appendLine("$passed of ${rows.size} answers pass every check.")
                appendLine()
                if (retired.isNotEmpty()) {
                    appendLine("Retired: ${retired.sorted().joinToString(", ")} (Info isn't an answer since starred-only reading, AI-02).")
                    appendLine()
                }
                appendLine("| Answer | Result | Checks (failed in bold) |")
                appendLine("|---|---|---|")
                rows.forEach(::appendLine)
            },
        )
    }

    /** Each label of [case]'s against what the app made of the answer ([made]: the task as enriched). */
    private fun check(case: Case, made: TaskItem, e: com.thomaswcode.decrastination.core.Enrichment, now: Long, answer: String): List<Pair<String, Boolean>> = buildList {
        val expect = case.expect
        fun day(time: Long?) = time?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: "none"
        expect.kind?.let { add("kind ${e.kind?.name ?: "-"} (want $it)" to (e.kind?.name == it)) }
        expect.deadline?.let { add("deadline ${day(e.deadline)} (want $it)" to (day(e.deadline) == it)) }
        expect.deadlineQuoted?.let { add("quoted ${e.deadlineQuoted} (want $it)" to (e.deadlineQuoted == it)) }
        expect.testDate?.let { add("test ${day(e.testDate)} (want $it)" to (day(e.testDate) == it)) }
        expect.effortMin?.let { (lo, hi) -> add("${made.effortMin} min (want $lo–$hi)" to (made.effortMin in lo..hi)) }
        // The steps given, kept or dropped: what the model saw is checked apart from whether the app kept it.
        val titles = (listOfNotNull(e.nextStep) + (e.subSteps?.map { it.title } ?: given(answer))).map { it.lowercase() }
        expect.mentions.forEach { m -> add("mentions $m" to titles.any { title -> m.lowercase().split('+').all { it.trim() in title } }) }
        expect.blocksAtLeast?.let { add("${e.subSteps?.size ?: 0} blocks (want ≥$it)" to ((e.subSteps?.size ?: 0) >= it)) }
        expect.ankiSections?.let { add("sections ${e.ankiSections} (want $it)" to (e.ankiSections.toSet() == it.toSet())) }
        // Where each step can be done (PL-15), as read: shown, not judged (no labels yet; rounds before it have none).
        if (case.job == Enrichments.Job.Assignment) e.subSteps?.let { steps -> add("where: " + steps.joinToString(", ") { "${it.title.take(24)} ${it.where ?: "-"}" } to true) }
        add(("plan kept" + (e.dropped?.let { ": dropped, $it" } ?: "")) to (e.dropped == null))
        // As the app would plan it: something placed or said, nothing lost.
        val plan = Planner.plan(Planner.Input(listOf(made), now, zone, Settings()))
        val placed = plan.ordered.size + plan.unplanned.size
        if (made.kind.name != "Info" && made.kind.name != "Event") add("planned ($placed pieces)" to (placed > 0))
    }

    /** The titles of the blocks or steps [answer] gives, whether or not the app kept them. */
    private fun given(answer: String): List<String> = runCatching {
        val root = JsonStore.json.parseToJsonElement(answer).jsonObject
        listOf("blocks", "subSteps").flatMap { key -> root[key]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content } }
    }.getOrDefault(emptyList())

    /** Email addresses out of what's handed to Opus here (names stay): the app itself sends them. */
    private fun redact(text: String): String = text.replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "<address>")
}
