package com.thomaswcode.decrastination.enrich

import com.anthropic.models.messages.OutputConfig
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.felix.ChatPrompts
import com.thomaswcode.decrastination.felix.FelixHarness
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.fail

/**
 * A scored eval run through the API (AR-02, docs/development.md): each case of the private set
 * ([EvalSetTest], `private/eval/cases.json`) asked of Claude as the app asks it, through the real
 * [ClaudeEnricher] with the key, its answer written to `private/eval/answers/<id>-<run>.json` (the
 * run numbered on from the last), then the lot scored as [EvalSetTest.score] does. A paid call a
 * case, so it runs only when asked, `-PrunEval=true`, with the key in the environment and the set
 * on disk: never in CI, nor in a plain test run here. It stops before a call whose dearest case
 * ([Pricing.WORST_CALL_USD]) could take it past `EVAL_BUDGET_USD` (2 dollars unless set);
 * `-Peffort=low|medium|high|max` sets how hard the model thinks, each job's own otherwise.
 */
class EvalRunTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")

    @Test
    fun `the set asked of Claude and scored`() = runBlocking {
        assumeTrue("Not asked for: -PrunEval=true", System.getProperty("runEval") == "true")
        val key = System.getenv(KEY)
        assumeTrue("No $KEY in the environment", !key.isNullOrBlank())
        val set = EvalSetTest()
        val cases = set.cases()
        val answers = File(set.dir!!, "answers").apply { mkdirs() }
        val budget = System.getenv("EVAL_BUDGET_USD")?.let { text -> text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: fail("EVAL_BUDGET_USD should be a number of dollars, not $text") } ?: 2.0
        val effort = System.getProperty("effort").orEmpty().ifBlank { null }?.let { OutputConfig.Effort.of(it.lowercase(Locale.ROOT)) }
        val run = (answers.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.substringAfterLast('-').toIntOrNull() }.maxOrNull() ?: 0) + 1
        val jobs = Enrichments.Job.entries.map(ModelEffort::jobOf)
        if (effort != null) jobs.forEach { ModelEffort.overrides[it] = effort }
        var spent = 0.0
        try {
            for (case in cases) {
                // The next call's dearest case must fit what's left (AC-05's worst call), so the budget is never passed.
                if (spent + Pricing.WORST_CALL_USD > budget) {
                    println("Stopped at ${dollars(spent)}: the next call could cost up to ${dollars(Pricing.WORST_CALL_USD)}, past the budget of ${dollars(budget)}; ${case.id} and the cases after it not asked")
                    break
                }
                val now = set.nowOf(case)
                // The profile as the set's prompts have it: the settings' defaults, this task alone.
                val profile = Profile.Block(
                    Profile.yearGroup(now, zone, Settings().yearGroupStart),
                    Profile.block(Settings(), listOf(case.task), null, SchoolCalendar(), MemoryState(), now, zone),
                )
                val result = ClaudeEnricher(ModelGateway.build(key!!), zone, profile = { profile }).enrich(case.task, case.job, now)
                spent += result.costUsd
                result.answer?.let { File(answers, "${case.id}-$run.json").writeText(it) }
                val how = when {
                    result.refused -> "refused"
                    result.answer == null -> "no answer (cut off)"
                    result.repair != null -> "answered, a check failed: ${result.repair?.complaint}"
                    else -> "answered"
                }
                println("${case.id} (${case.job}): $how, ${dollars(result.costUsd)}")
            }
        } finally {
            if (effort != null) jobs.forEach { ModelEffort.overrides.remove(it) }
        }
        println("Run $run: ${dollars(spent)} in all")
        set.score()
        println("Scored: ${File(set.dir, "report.md").path}")
    }

    /**
     * The chat's private set (`private/eval-chat`, never committed; docs/plan_v4/15-sent-or-asked-for.md §The eval),
     * asked only with `-PrunEval=chat` and the key: each case a message of yours to Felix on a copy of the phone's
     * stores (`state/files/`, the chat and the record of calls left out, so each case starts a chat of its own) and
     * calendar (`state/events.json`, as `CalendarTime.read` gives it), at the set's `now`, asked through the
     * Conversation as the app asks it, rounds included ([FelixHarness] on Claude's API), each paid for. Its answer
     * is scored by the case's labels, each a check: `reads`, what its rounds brought (a task's id, `stats`,
     * `calendar` or `calendar:yyyy-MM`, said under his answer); `says`, groups of words of which the reply holds one
     * each; `makes`, the change types made (a new task as `task:<kind>`), a group meaning one of them; `noRound`,
     * answered in one call; or `nothing`, just talk, which his answer makes nothing of. A case marked `twice` (the text cut's) is asked twice; `EVAL_CASES` (ids, by commas)
     * asks only those. Each case's score, rounds, tokens, cost and seconds printed and written to `runs/<n>.json`;
     * the run stops before a case once what's left of `EVAL_BUDGET_USD` (3 dollars unless set) is under twice the
     * dearest case so far, half a dollar at least: a soft budget, which a run can pass by a case's cost (a case is about
     * $0.07 to $0.16 a call, where holding each to the worst call's $1.35 would stop a $3 run before it starts).
     */
    @Test
    fun `the chat's set asked of Felix and scored`() = runBlocking {
        assumeTrue("Not asked for: -PrunEval=chat", System.getProperty("runEval") == "chat")
        val key = System.getenv(KEY)
        assumeTrue("No $KEY in the environment", !key.isNullOrBlank())
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "private/eval-chat") }.firstOrNull { File(it, "cases-8c.json").isFile }
        assumeTrue("No private/eval-chat/cases-8c.json", dir != null)
        val set = Json.parseToJsonElement(File(dir, "cases-8c.json").readText()).jsonObject
        val now = set.getValue("now").jsonPrimitive.content
        val events = Json.parseToJsonElement(File(dir, "state/events.json").readText()).jsonArray.map { e ->
            val o = e.jsonObject
            fun text(name: String) = o[name]?.jsonPrimitive?.contentOrNull.orEmpty()
            CalendarEvent(o.getValue("id").jsonPrimitive.long, text("title"), o.getValue("start").jsonPrimitive.long, o.getValue("end").jsonPrimitive.long, o.getValue("allDay").jsonPrimitive.boolean, o["busy"]?.jsonPrimitive?.booleanOrNull ?: true, text("calendar"), text("location"), text("description"))
        }
        val state = File(dir, "state/files")
        val budget = System.getenv("EVAL_BUDGET_USD")?.let { text -> text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: fail("EVAL_BUDGET_USD should be a number of dollars, not $text") } ?: 3.0
        val only = System.getenv("EVAL_CASES")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        // -Peffort=low|medium|high|max for the chat's calls and its searches, as the readings' set takes it.
        val effort = System.getProperty("effort").orEmpty().ifBlank { null }?.let { OutputConfig.Effort.of(it.lowercase(Locale.ROOT)) }
        val jobs = listOf(ModelEffort.Job.Chat, ModelEffort.Job.Research)
        if (effort != null) jobs.forEach { ModelEffort.overrides[it] = effort }
        val runs = File(dir, "runs").apply { mkdirs() }
        val run = (runs.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.maxOrNull() ?: 0) + 1
        var spent = 0.0
        var dearest = 0.0
        val results = mutableListOf<JsonObject>()
        try {
            cases@ for (case in set.getValue("cases").jsonArray.map { it.jsonObject }) {
                val id = case.getValue("id").jsonPrimitive.content
                if (only != null && id !in only) continue
                val twice = case["twice"]?.jsonPrimitive?.booleanOrNull == true
                for (attempt in 1..(if (twice) 2 else 1)) {
                    if (spent + maxOf(0.5, dearest * 2) > budget) {
                        println("Stopped at ${dollars(spent)}, before $id: what's left of ${dollars(budget)} may not cover another case")
                        break@cases
                    }
                    // A copy of the phone's stores, but the chat and the record of calls: a chat of its own, its calls alone on record.
                    val seed = { into: File -> state.listFiles().orEmpty().filter { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith("chat") && f.name != "model.json" }.forEach { it.copyTo(File(into, it.name)) } }
                    FelixHarness(start = now, seed = seed, key = key, calendar = { stores ->
                        val answers = stores.calendar.value.eventAnswers + InstructionRules.eventAnswers(stores.instructions.value.applied)
                        EventJudge.time(events, answers, zone, askUntil = Fixtures.at(now) + CalendarTime.ASK_AHEAD_MS).copy(read = (Fixtures.at(now) - CalendarTime.DAY_MS) to CalendarTime.readUntil(Fixtures.at(now), zone))
                    }).use { h ->
                        val about = case["about"]?.jsonPrimitive?.contentOrNull?.let { taskId -> About(taskId = taskId, taskTitle = h.stores.tasks.value.tasks.firstOrNull { it.id == taskId }?.title) } ?: About()
                        val began = System.nanoTime()
                        h.conversation.ask(case.getValue("text").jsonPrimitive.content, about)
                        h.settle()
                        val seconds = (System.nanoTime() - began) / 1e9
                        val calls = h.stores.model.value.calls
                        val cost = calls.sumOf { it.costUsd }
                        spent += cost
                        dearest = maxOf(dearest, cost)
                        val reply = h.stores.chat.value.messages.lastOrNull { it.from == Speaker.Felix }
                        val words = reply?.text.orEmpty()
                        val acts = reply?.acts.orEmpty().map { it.line }
                        val made = reply?.proposals.orEmpty().filter { it.state == ProposalState.Applied }.flatMap { p -> p.changes.map { it.type.name } + listOfNotNull(p.task?.let { "task:" + it.kind.name }) }
                        val titles = h.stores.tasks.value.tasks.associate { it.id to it.title }
                        val checks = mutableListOf<Pair<String, Boolean>>()
                        case["reads"]?.jsonArray?.forEach { r ->
                            val what = r.jsonPrimitive.content
                            val read = when {
                                what == "stats" -> acts.any { "week's numbers" in it }
                                // The very span: the month and its year, or the four weeks after the fortnight.
                                what == "calendar" || what.startsWith("calendar:") -> "Read: the calendar for " + ChatPrompts.calendarWhat(what.substringAfter(':', ""), Fixtures.at(now), zone) + "." in acts
                                // The very task, by its whole title as the act lists them.
                                else -> acts.any { it.startsWith("Read in full: ") && titles[what]?.let { t -> "“$t”" in it.removePrefix("Read in full: ").removeSuffix(".").split("; ") } == true }
                            }
                            checks += "reads $what" to read
                        }
                        case["says"]?.jsonArray?.forEach { group ->
                            val any = group.jsonArray.map { it.jsonPrimitive.content }
                            checks += "says ${any.joinToString("|")}" to any.any { words.contains(it, ignoreCase = true) }
                        }
                        case["makes"]?.jsonArray?.forEach { t ->
                            val any = (t as? JsonArray)?.map { it.jsonPrimitive.content } ?: listOf(t.jsonPrimitive.content)
                            checks += "makes ${any.joinToString("|")}" to any.any { it in made }
                        }
                        // A message that's just talk: a reply and nothing else, no change, nothing remembered, no action.
                        if (case["nothing"]?.jsonPrimitive?.booleanOrNull == true) checks += "nothing but a reply" to (made.isEmpty() && acts.isEmpty() && reply?.memoryEdits.isNullOrEmpty())
                        val chatCalls = calls.count { it.job == ModelEffort.Job.Chat.name }
                        if (case["noRound"]?.jsonPrimitive?.booleanOrNull == true) checks += "no round" to (chatCalls == 1)
                        val score = checks.count { it.second }.toDouble() / checks.size.coerceAtLeast(1)
                        println(
                            "$id#$attempt: score ${"%.2f".format(Locale.UK, score)} (${checks.joinToString { (c, ok) -> (if (ok) "+" else "-") + c }}), rounds ${chatCalls - 1}, " +
                                "in ${calls.sumOf { it.inTokens }} (cache ${calls.sumOf { it.cacheRead }}), out ${calls.sumOf { it.outTokens }}, ${dollars(cost)}, ${"%.1f".format(Locale.UK, seconds)} s",
                        )
                        results += JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(id), "attempt" to JsonPrimitive(attempt), "cut" to JsonPrimitive(twice), "score" to JsonPrimitive(score),
                                "checks" to JsonObject(checks.associate { (c, ok) -> c to JsonPrimitive(ok) }),
                                "rounds" to JsonPrimitive(chatCalls - 1), "calls" to JsonPrimitive(calls.size),
                                "inTokens" to JsonPrimitive(calls.sumOf { it.inTokens }), "cacheRead" to JsonPrimitive(calls.sumOf { it.cacheRead }), "outTokens" to JsonPrimitive(calls.sumOf { it.outTokens }),
                                "costUsd" to JsonPrimitive(cost), "seconds" to JsonPrimitive(seconds),
                                "reply" to JsonPrimitive(words), "acts" to JsonArray(acts.map(::JsonPrimitive)), "made" to JsonArray(made.map(::JsonPrimitive)),
                                "note" to JsonPrimitive(reply?.note.orEmpty()),
                            ),
                        )
                    }
                }
            }
        } finally {
            // Let go whatever happened to a case, so no later call in this process runs at the run's effort.
            if (effort != null) jobs.forEach { ModelEffort.overrides.remove(it) }
        }
        File(runs, "$run.json").writeText(JsonArray(results).toString())
        println("Run $run: ${results.size} answers, ${dollars(spent)} in all; mean score ${"%.3f".format(Locale.UK, results.map { it.getValue("score").jsonPrimitive.content.toDouble() }.average())}: ${File(runs, "$run.json").path}")
    }

    private fun dollars(usd: Double) = "$" + String.format(Locale.UK, "%.3f", usd)

    internal companion object {
        const val KEY = "CLAUDE_DECRASTINATION_API_KEY"
    }
}
