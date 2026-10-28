package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.OutputConfig
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Slotter
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.felix.ChatPrompts
import com.thomaswcode.decrastination.felix.FelixAnswers
import com.thomaswcode.decrastination.felix.FelixCaller
import com.thomaswcode.decrastination.felix.FelixHarness
import com.thomaswcode.decrastination.felix.FelixPrompts
import com.thomaswcode.decrastination.felix.Mood
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
     * The chat's private set (`private/eval-chat`, never committed; docs/spec/readings.md §The evaluation set),
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

    /**
     * Felix's moods' private set (`private/eval-felix-mood/cases.json`, never committed, every case made up,
     * a case in the shape [MoodCase] reads), asked only with `-PrunEval=mood` and the key. A `felix` case is one message of
     * his, asked as [com.thomaswcode.decrastination.felix.Looks] asks it: [FelixPrompts.system] and [FelixPrompts.describe]
     * through [FelixCaller.say], today's plan laid by the [Planner] from the case's tasks and evening, its slots on record
     * and his recent messages as the app keeps them; his answer read by [FelixAnswers.parse], one cut to its room
     * ([FelixCaller.Result.cut]) or unread failing its case. A `chat` case is a message of yours, asked through the
     * Conversation on temporary stores holding the case alone ([FelixHarness]), as the chat's set asks. Beside each, the
     * level the rule gives ([Mood.of]). Each case asked `EVAL_TIMES` times (1 unless set), as his tone varies from one call
     * to the next; `EVAL_CASES` (ids, by commas) asks only those; `EVAL_MODEL` asks his messages of another model than the
     * app's (the chat's stay on the app's), its cost priced as the older Opus's; `EVAL_DRY` builds each and prints what he'd be told,
     * asking nothing. Each answer, with its length, its address words and its flags, written to `runs/<n>.json` and
     * `report-<n>.md`; with `EVAL_BEFORE` (a run's number), `report.md` too, that run's answers beside this one's. The
     * budget is the chat's set's soft one, and a case not asked is said in the report.
     */
    @Test
    fun `the mood set asked of Felix and scored`() = runBlocking {
        assumeTrue("Not asked for: -PrunEval=mood", System.getProperty("runEval") == "mood")
        val dry = !System.getenv("EVAL_DRY").isNullOrBlank()
        val key = System.getenv(KEY)
        assumeTrue("No $KEY in the environment", dry || !key.isNullOrBlank())
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "private/eval-felix-mood") }.firstOrNull { File(it, "cases.json").isFile }
        assumeTrue("No private/eval-felix-mood/cases.json", dir != null)
        val cases = Json.parseToJsonElement(File(dir, "cases.json").readText()).jsonObject.getValue("cases").jsonArray.map { MoodCase(it.jsonObject) }
        val budget = System.getenv("EVAL_BUDGET_USD")?.let { text -> text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: fail("EVAL_BUDGET_USD should be a number of dollars, not $text") } ?: 3.0
        val times = System.getenv("EVAL_TIMES")?.let { text -> text.toIntOrNull()?.takeIf { it in 1..5 } ?: fail("EVAL_TIMES should be 1 to 5, not $text") } ?: 1
        val only = System.getenv("EVAL_CASES")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        val runs = File(dir, "runs").apply { mkdirs() }
        val run = (runs.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val client = if (dry) null else ModelGateway.build(key!!)
        var spent = 0.0
        var dearest = 0.0
        val results = mutableListOf<JsonObject>()
        val notAsked = mutableListOf<String>()
        for (case in cases) {
            if (only != null && case.id !in only) continue
            for (attempt in 1..times) {
                if (!dry && spent + maxOf(0.5, dearest * 2) > budget) {
                    notAsked += "${case.id}#$attempt"
                    continue
                }
                val began = System.nanoTime()
                val asked = if (case.chat) askChat(case, key, dry) else askFelix(case, client, dry)
                if (dry) {
                    println("=== ${case.id} (${case.reason}), the rule: ${asked.level}\n${asked.prompt}\n")
                    break
                }
                val seconds = (System.nanoTime() - began) / 1e9
                spent += asked.cost
                dearest = maxOf(dearest, asked.cost)
                val words = addressWords(asked.text, case.nickname)
                val flags = flags(asked, words, case)
                println("${case.id}#$attempt: ${asked.level}, ${asked.chars} chars${if (asked.cut) " (cut)" else ""}, ${dollars(asked.cost)}, ${"%.1f".format(Locale.UK, seconds)} s: ${asked.text}" + flags.joinToString("") { " [$it]" })
                results += JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(case.id), "attempt" to JsonPrimitive(attempt), "kind" to JsonPrimitive(if (case.chat) "chat" else "felix"),
                        "reason" to JsonPrimitive(case.reason), "what" to JsonPrimitive(case.what), "expect" to JsonPrimitive(case.expect), "level" to JsonPrimitive(asked.level.name),
                        "text" to JsonPrimitive(asked.text), "chars" to JsonPrimitive(asked.chars), "cut" to JsonPrimitive(asked.cut), "read" to JsonPrimitive(asked.read),
                        "replies" to JsonArray(asked.replies.map(::JsonPrimitive)), "extra" to JsonArray(asked.extra.map(::JsonPrimitive)),
                        "addressWords" to JsonArray(words.map(::JsonPrimitive)), "flags" to JsonArray(flags.map(::JsonPrimitive)),
                        "costUsd" to JsonPrimitive(asked.cost), "seconds" to JsonPrimitive(seconds), "prompt" to JsonPrimitive(asked.prompt),
                    ),
                )
            }
        }
        if (dry) return@runBlocking
        File(runs, "$run.json").writeText(JsonArray(results).toString())
        File(dir, "report-$run.md").writeText(moodReport(run, results, notAsked, spent))
        System.getenv("EVAL_BEFORE")?.toIntOrNull()?.let { before ->
            val earlier = Json.parseToJsonElement(File(runs, "$before.json").readText()).jsonArray.map { it.jsonObject }
            File(dir, "report.md").writeText(moodSideBySide(before, earlier, run, results, notAsked))
        }
        println("Run $run: ${results.size} answers, ${dollars(spent)} in all${if (notAsked.isEmpty()) "" else "; not asked, past the budget: " + notAsked.joinToString()}: ${File(dir, "report-$run.md").path}")
    }

    /** One answer of the mood set's: his words, as the app would keep them, with what it rests on. */
    private class MoodAnswer(
        val level: Mood.Level,
        val prompt: String,
        val text: String = "",
        val chars: Int = 0,
        val cut: Boolean = false,
        val read: Boolean = true,
        val replies: List<String> = emptyList(),
        /** What else came with it: his memories and next check, the chat's acts and changes. */
        val extra: List<String> = emptyList(),
        val cost: Double = 0.0,
        /** His message before this one (the case's last with words), for two running with an address word. */
        val before: String? = null,
    )

    /** A `felix` case asked as Looks asks his messages, or, [dry], only built. */
    private suspend fun askFelix(case: MoodCase, client: AnthropicClient?, dry: Boolean): MoodAnswer {
        val settings = Settings()
        val reason = FelixPrompts.Reason.valueOf(case.reason)
        val today = case.today
        val plan = Planner.plan(
            Planner.Input(
                case.tasks.filter { it.isOpen }, case.planFrom, zone, settings, windows = mapOf(case.date to listOf(case.window)),
                workedTodayMin = today.filter { it.outcome.kept }.sumOf { it.workedMin }, missedSlots = today.filter { it.outcome == SlotOutcome.Missed }.mapTo(HashSet()) { it.key },
            ),
        ).copy(now = case.now)
        val slots = { plan.todaySlots.filter { it.chunk != null }.joinToString { "${it.chunk?.key} ${Instant.ofEpochMilli(it.start).atZone(zone).toLocalTime()}–${Instant.ofEpochMilli(it.end).atZone(zone).toLocalTime()}" } }
        // A slot begun: the one under way, which should be the case's task's.
        val slot = if (reason == FelixPrompts.Reason.SlotStart) plan.slotAt(case.now) ?: fail("${case.id}: no slot under way at ${case.nowText}; today's: ${slots()}") else null
        if (slot != null && case.task != null && slot.chunk?.taskId != case.task) fail("${case.id}: the slot under way is ${slot.chunk?.key}, not ${case.task}'s; today's: ${slots()}")
        val record = case.record?.let { k -> today.firstOrNull { it.key == k } ?: fail("${case.id}: no record $k") }
        val messages = case.recent(slot?.chunk?.key)
        val task = (slot?.chunk?.taskId ?: record?.taskId ?: case.task)?.let { id -> case.tasks.firstOrNull { it.id == id } }
        val feeling = Mood.of(reason, today, record, Mood.nudges(messages, slot?.chunk?.key ?: record?.key, case.now, zone), task, messages, case.now, zone)
        val free = case.freeMin.takeIf { reason == FelixPrompts.Reason.DayDone || reason == FelixPrompts.Reason.SlotKept }
        val user = FelixPrompts.describe(reason, plan, slot, task, today, messages, case.question, settings, case.now, zone,free, record, firstAction = case.firstAction, reminder = case.reminder, tasks = case.tasks, feeling = feeling)
        if (dry) return MoodAnswer(feeling.level, user)
        val profile = Profile.block(settings, case.tasks, null, SchoolCalendar(), case.memory, case.now, zone)
        val max = if (reason == FelixPrompts.Reason.Morning) FelixAnswers.MAX_MORNING else FelixAnswers.MAX_MESSAGE
        val result = FelixCaller(client!!, System.getenv("EVAL_MODEL") ?: ClaudeEnricher.MODEL).say(FelixPrompts.system(settings, Profile.yearGroup(case.now, zone, settings.yearGroupStart)), profile, user, max)
        val answer = result.answer
        val before = ChatHistory.his(messages).filter { it.text.isNotBlank() }.maxByOrNull { it.at }?.text
        return MoodAnswer(
            feeling.level, user, answer?.message.orEmpty(), result.messageChars, result.cut, read = answer != null && !result.refused, replies = answer?.replies.orEmpty(),
            extra = listOfNotNull(answer?.feeling?.takeIf { it.isNotEmpty() }?.let { "feeling: $it" }) + answer?.memories.orEmpty().map { "remembered: ${it.topic}: ${it.text}" } +
                listOfNotNull(answer?.nextCheckMin?.let { "next check $it min" }, result.model?.takeIf { System.getenv("EVAL_MODEL") != null }),
            cost = result.costUsd, before = before,
        )
    }

    /** A `chat` case asked through the Conversation on stores holding the case alone, or, [dry], only built. */
    private suspend fun askChat(case: MoodCase, key: String?, dry: Boolean): MoodAnswer = FelixHarness(start = case.nowText, key = key).use { h ->
        h.stores.tasks.update { it.copy(tasks = case.tasks) }
        h.stores.log.update { it.copy(slots = case.today) }
        h.stores.memory.update { case.memory }
        val messages = case.recent(null)
        h.stores.chat.update { it.copy(messages = messages) }
        // The chat's turn leans the way today's newest record does.
        val newest = case.today.filter { it.outcome != SlotOutcome.Moved }.maxByOrNull { it.at }
        val feeling = Mood.of(null, case.today, null, 0, case.tasks.firstOrNull { it.id == newest?.taskId }, messages, case.now, zone)
        val prompt = "They: “${case.message}”" + h.conversation.chatContext().plan.lines().filter { it.startsWith("How you feel") || it.startsWith("Today's slots") || (it.startsWith("- ") && "[past]" in it) }.joinToString("") { "\n$it" }
        if (dry) return@use MoodAnswer(feeling.level, prompt)
        val about = case.about?.let { id -> About(taskId = id, taskTitle = case.tasks.firstOrNull { it.id == id }?.title) } ?: About()
        h.conversation.ask(case.message!!, about)
        h.settle()
        val reply = h.stores.chat.value.messages.lastOrNull { it.from == Speaker.Felix }
        val made = reply?.proposals.orEmpty().filter { it.state == ProposalState.Applied }.flatMap { p -> p.lines }
        val before = ChatHistory.his(messages).filter { it.text.isNotBlank() }.maxByOrNull { it.at }?.text
        MoodAnswer(
            feeling.level, prompt, reply?.text.orEmpty(), reply?.text.orEmpty().length, read = reply != null && reply.text.isNotBlank(), replies = reply?.suggestedReplies.orEmpty(),
            extra = listOfNotNull(reply?.feeling?.takeIf { it.isNotEmpty() }?.let { "feeling: $it" }) + made.map { "made: $it" } + reply?.acts.orEmpty().map { "act: ${it.line}" } +
                reply?.memoryEdits.orEmpty().map { "memory: ${it.line}" },
            cost = h.stores.model.value.calls.sumOf { it.costUsd }, before = before,
        )
    }

    /** One case of the mood set as the app would hold it: its times "HH:mm" on its day, or whole. */
    private inner class MoodCase(private val o: JsonObject) {
        private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        private fun JsonObject.list(name: String): List<JsonObject> = this[name]?.jsonArray.orEmpty().map { it.jsonObject }

        val id = o.str("id")!!
        val chat = o.str("kind") == "chat"
        val what = o.str("what").orEmpty()
        val nowText = o.str("now")!!
        val now = Fixtures.at(nowText)
        val date: LocalDate = LocalDate.parse(nowText.substringBefore('T'))
        fun time(text: String): Long = if ('T' in text) Fixtures.at(text) else Fixtures.at("${date}T$text")
        private fun minute(text: String) = LocalTime.parse(text).let { it.hour * 60 + it.minute }
        val reason = o.str("reason") ?: "Chat"
        val expect = o.str("expect").orEmpty()
        val record = o.str("record")
        val task = o.str("task")
        val message = o.str("message")
        val about = o.str("about")
        val firstAction = o.str("firstAction")

        /** A Question's to put, in the app's words; a Reminder's words, as they set it. */
        val question = o.str("question")
        val reminder = o.str("reminder")
        val freeMin = o.int("freeMin") ?: 20
        val nickname = o.str("nickname")
        val planFrom = o.str("planFrom")?.let(::time) ?: now
        val window = o["window"]?.jsonArray?.map { minute(it.jsonPrimitive.content) }?.let { DayWindow(it[0], it[1], WindowKind.Evening) } ?: DayWindow(16 * 60 + 30, 21 * 60 + 30, WindowKind.Evening)
        val tasks: List<TaskItem> = o.list("tasks").map { t ->
            val id = t.str("id")!!
            val done = t.str("status") == "Done"
            // Finished as its last slot kept today was, or an hour ago.
            val keptAt = o.list("today").filter { it.str("key")!!.substringBefore('#') == id && it.str("outcome")!!.startsWith("Kept") }.maxOfOrNull { time(it.str("at") ?: it.str("end")!!) }
            TaskItem(
                id = id, source = Source.valueOf(t.str("source") ?: "Teams"), sourceId = id.substringAfter(':'), title = t.str("title")!!, detail = t.str("detail").orEmpty(),
                className = t.str("className"), dueAt = t.str("due")?.let(::time), kind = Kind.valueOf(t.str("kind") ?: "Homework"), sourceEffortMin = t.int("minutes"),
                workedMin = t.int("workedMin") ?: 0, photoMin = t.int("photoMin") ?: 0,
                subSteps = t.list("steps").map { s -> SubStep(s.str("title")!!, s.int("minutes")!!, done = s.str("done") == "true", byHand = s.str("byHand") == "true") },
                status = if (done) Status.Done else Status.Open, doneAt = if (done) keptAt ?: (now - 3_600_000L) else null, firstSeenAt = now - 5 * 86_400_000L, lastSeenAt = now,
            )
        }
        val today: List<SlotRecord> = o.list("today").map { r ->
            val key = r.str("key")!!
            val of = tasks.firstOrNull { it.id == key.substringBefore('#') }
            val start = time(r.str("start")!!)
            val end = time(r.str("end")!!)
            val outcome = SlotOutcome.valueOf(r.str("outcome")!!)
            val minutes = ((end - start) / 60_000L).toInt()
            SlotRecord(
                date = date.toString(), key = key, taskId = key.substringBefore('#'), kind = of?.kind ?: Kind.Homework, className = of?.className, label = r.str("label") ?: of?.title ?: key,
                plannedMin = minutes, start = start, end = end, window = WindowKind.Evening, outcome = outcome, workedMin = if (outcome.kept) minutes else 0, earned = outcome.kept,
                at = r.str("at")?.let(::time) ?: (end + if (outcome == SlotOutcome.Missed) Slotter.GRACE_MS else 0L),
            )
        }

        /** The chat as the case has it, a piece key "@slot" being the slot under way's ([slotKey]); yours answered, so only the case's message waits. */
        fun recent(slotKey: String?): List<ChatMessage> = o.list("recent").mapIndexed { i, m ->
            val at = time(m.str("at")!!)
            val from = Speaker.valueOf(m.str("from") ?: "Felix")
            val piece = m.str("pieceKey")?.let { if (it == "@slot") slotKey else it }
            val about = m.str("about") ?: piece?.substringBefore('#')
            ChatMessage(
                "case-$i", from, m.str("text").orEmpty(), at, About(taskId = about, taskTitle = tasks.firstOrNull { it.id == about }?.title),
                kind = MessageKind.valueOf(m.str("kind") ?: "Chat"), pieceKey = piece, answeredAt = if (from == Speaker.You) at else null, mood = m.str("mood").orEmpty(),
            )
        }

        val memory = MemoryState(
            facts = o.list("facts").map { Fact(it.str("topic")!!, it.str("text")!!, now - 86_400_000L, until = it.str("until")?.let(::time)) },
            rules = o.list("rules").map { Rule(it.str("text")!!, now - 2 * 86_400_000L) },
        )
    }

    /** The address words in [text]: "mate", "bro", "bud", "dude" and the case's own nickname. */
    private fun addressWords(text: String, nickname: String?): List<String> =
        Regex("""\b(mate|bro|bud|buddy|dude${nickname?.let { "|" + Regex.escape(it.lowercase(Locale.ROOT)) }.orEmpty()})\b""").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    /**
     * What's wrong with [asked], in words: unread or cut, an address word at strong, or two of his messages running with
     * one; a teacher's words, a counsellor's, or London slang (D-119); a line that sounds written or is the app's
     * own, a mood's example copied, or yesterday brought up in the morning (the owner's read of run 2).
     */
    private fun flags(asked: MoodAnswer, words: List<String>, case: MoodCase): List<String> {
        val text = asked.text.lowercase(Locale.ROOT)
        val flags = mutableListOf<String>()
        if (!asked.read) flags += "unread or empty"
        if (asked.cut) flags += "cut (${asked.chars} chars)"
        if (words.isNotEmpty() && asked.level == Mood.Level.Strong) flags += "address word at strong"
        if (words.isNotEmpty() && asked.before?.let { addressWords(it, case.nickname).isNotEmpty() } == true) flags += "address words two messages running"
        if (words.size > 1) flags += "${words.size} address words"
        TEACHER.filter { it in text }.forEach { flags += "teacher: $it" }
        COUNSELLOR.filter { it in text }.forEach { flags += "counsellor: $it" }
        Regex("""\b(proper|sorted|nah|alr|innit|bruv)\b""").findAll(text).forEach { flags += "slang: ${it.value}" }
        WRITTEN.filter { it in text }.forEach { flags += "written: $it" }
        Regex("""\b(\d+) of \1\b""").find(text)?.let { flags += "the app's words: ${it.value}" }
        fives(asked.text).intersect(COPIED).firstOrNull()?.let { flags += "copied: $it" }
        if (case.reason == "Morning" && "yesterday" in text) flags += "yesterday brought up"
        if (asked.text.any { Character.isSurrogate(it) || Character.getType(it) == Character.OTHER_SYMBOL.toInt() }) flags += "emoji"
        return flags
    }

    /** A run's answers as a report: each case, what it is, the rule's level and what he said. */
    private fun moodReport(run: Int, results: List<JsonObject>, notAsked: List<String>, spent: Double): String = buildString {
        fun JsonObject.s(name: String) = getValue(name).jsonPrimitive.content
        val withWords = results.count { it.getValue("addressWords").jsonArray.isNotEmpty() }
        appendLine("# Felix's moods: run $run")
        appendLine()
        appendLine("${results.size} answers, ${dollars(spent)}. Address words in $withWords of ${results.size} messages${if (results.isNotEmpty() && withWords * 3 > results.size) " (over a third: a fail)" else ""}.")
        if (notAsked.isNotEmpty()) appendLine("Not asked, past the budget: ${notAsked.joinToString()}.")
        shared(results).takeIf { it.isNotEmpty() }?.let { appendLine("Phrases in three cases or more: ${it.joinToString("; ")}.") }
        for ((id, answers) in results.groupBy { it.s("id") }) {
            val first = answers.first()
            appendLine()
            appendLine("## $id (${first.s("reason")}): the rule ${first.s("level")}, expected ${first.s("expect")}")
            appendLine()
            appendLine(first.s("what"))
            appendLine()
            for (a in answers) appendLine(answerLine(a))
        }
    }

    /** One answer as a line of a report: what he said, its length and its flags. */
    private fun answerLine(a: JsonObject): String {
        fun s(name: String) = a.getValue(name).jsonPrimitive.content
        val flags = a.getValue("flags").jsonArray.map { it.jsonPrimitive.content }
        val extra = a.getValue("extra").jsonArray.map { it.jsonPrimitive.content }
        return "- #${s("attempt")}: “${s("text")}” (${s("chars")} chars${if (s("cut") == "true") ", cut" else ""})" +
            flags.joinToString("") { " **[$it]**" } + extra.takeIf { it.isNotEmpty() }?.joinToString("; ", " _", "_").orEmpty()
    }

    /** Run [before]'s answers beside run [after]'s, a case at a time, the rule's level with each: the owner's read (D-119). */
    private fun moodSideBySide(before: Int, earlier: List<JsonObject>, after: Int, results: List<JsonObject>, notAsked: List<String>): String = buildString {
        fun JsonObject.s(name: String) = getValue(name).jsonPrimitive.content
        fun rate(list: List<JsonObject>) = "${list.count { it.getValue("addressWords").jsonArray.isNotEmpty() }} of ${list.size}"
        appendLine("# Felix's moods: run $before beside run $after")
        appendLine()
        appendLine("Address words: run $before ${rate(earlier)}, run $after ${rate(results)} (more than about one in three fails).")
        if (notAsked.isNotEmpty()) appendLine("Not asked, past the budget: ${notAsked.joinToString()}.")
        val flagged = results.filter { it.getValue("flags").jsonArray.isNotEmpty() }
        appendLine("Flagged in run $after: ${flagged.size} of ${results.size}" + flagged.joinToString("") { "\n- ${it.s("id")}#${it.s("attempt")}: " + it.getValue("flags").jsonArray.joinToString { f -> f.jsonPrimitive.content } })
        shared(results).takeIf { it.isNotEmpty() }?.let { appendLine("Phrases in three cases or more in run $after: ${it.joinToString("; ")}.") }
        val byBefore = earlier.groupBy { it.s("id") }
        for ((id, answers) in results.groupBy { it.s("id") }) {
            val first = answers.first()
            appendLine()
            appendLine("## $id (${first.s("reason")}): the rule ${first.s("level")}, expected ${first.s("expect")}")
            appendLine()
            appendLine(first.s("what"))
            appendLine()
            appendLine("Run $before:")
            byBefore[id].orEmpty().forEach { appendLine(answerLine(it)) }
            appendLine()
            appendLine("Run $after:")
            answers.forEach { appendLine(answerLine(it)) }
        }
    }

    /**
     * Phrases of four words or more that answers to three cases or more share, the longest kept and none with a number
     * (times and counts are the work's): a stock line forming, which no list of known ones catches (the owner's read of run 3).
     */
    private fun shared(results: List<JsonObject>): List<String> {
        val cases = HashMap<String, MutableSet<String>>()
        for (a in results) {
            val words = Regex("""[a-z0-9:']+""").findAll(a.getValue("text").jsonPrimitive.content.lowercase(Locale.ROOT)).map { it.value }.toList()
            for (n in 4..8) words.windowed(n).filter { w -> w.none { word -> word.any(Char::isDigit) } }.forEach { cases.getOrPut(it.joinToString(" ")) { HashSet() } += a.getValue("id").jsonPrimitive.content }
        }
        val common = cases.filterValues { it.size >= 3 }
        return common.filter { (p, ids) -> common.none { (q, others) -> q != p && p in q && others == ids } }.entries.sortedByDescending { it.value.size }.map { "“${it.key}” (${it.value.size})" }
    }

    private fun dollars(usd: Double) = "$" + String.format(Locale.UK, "%.3f", usd)

    internal companion object {
        const val KEY = "CLAUDE_DECRASTINATION_API_KEY"

        /** A teacher's words (D-119): never his. */
        val TEACHER = listOf("expected better", "disappointed in you", "make sure you", "remember to", "you need to", "i'm not angry")

        /** A counsellor's (D-119): sincerity gone wrong. */
        val COUNSELLOR = listOf("i hear you", "that's valid", "be kind to yourself", "it's okay to struggle", "your feelings", "making it hard", "getting in the way")

        /**
         * Lines that sound written, an AI's, or the app's own words read back (the owner's read of run 2), and pride said
         * outright, which doesn't sound sincere (of run 4): never his.
         */
        val WRITTEN = listOf("sting", "gets to me", "counting on", "had it in you", "to spare", "slot kept", "slots kept", "every slot", "proud of you", "off your plate", "done and dusted")

        /**
         * Five words running from a mood's example in his prompts, and in no other example of theirs: an answer with
         * them has copied it (the owner's read of run 2).
         */
        val COPIED: Set<String> by lazy {
            fun examples(prompt: String) = prompt.substringAfter("Examples (made up").lines().filter { it.trimStart().startsWith("- ") }
            fun quoted(lines: List<String>) = lines.flatMap { l -> Regex("\"([^\"]+)\"").findAll(l).map { it.groupValues[1] }.toList() }.flatMap(::fives).toSet()
            val (mood, rest) = (examples(FelixPrompts.system(Settings(), 12)) + examples(ChatPrompts.system(Settings()))).partition { "told you're" in it }
            quoted(mood) - quoted(rest)
        }

        /** [text]'s words five at a time, in lower case, without their stops and commas, and none with a number (times and counts are the work's). */
        fun fives(text: String): Set<String> = Regex("""[a-z0-9:']+""").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList().windowed(5)
            .filter { w -> w.none { word -> word.any(Char::isDigit) } }.mapTo(HashSet()) { it.joinToString(" ") }
    }
}
