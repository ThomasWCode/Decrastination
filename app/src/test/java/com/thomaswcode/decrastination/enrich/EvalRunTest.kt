package com.thomaswcode.decrastination.enrich

import com.anthropic.models.messages.OutputConfig
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.data.Settings
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * A scored eval run through the API (AR-02, docs/eval.md): each case of the private set
 * ([EvalSetTest], `private/eval/cases.json`) asked of Claude as the app asks it, through the real
 * [ClaudeEnricher] with the key, its answer written to `private/eval/answers/<id>-<run>.json` (the
 * run numbered on from the last), then the lot scored as [EvalSetTest.score] does. A paid call a
 * case, so it runs only when asked, `-PrunEval=true`, with the key in the environment and the set
 * on disk: never in CI, nor in a plain test run here. It stops once what it's spent reaches
 * `EVAL_BUDGET_USD` (2 dollars unless set); `-Peffort=low|medium|high|max` sets how hard the model
 * thinks, each job's own otherwise.
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
        val budget = System.getenv("EVAL_BUDGET_USD")?.toDoubleOrNull() ?: 2.0
        val effort = System.getProperty("effort").orEmpty().ifBlank { null }?.let { OutputConfig.Effort.of(it.lowercase(Locale.ROOT)) }
        val run = (answers.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.substringAfterLast('-').toIntOrNull() }.maxOrNull() ?: 0) + 1
        val jobs = Enrichments.Job.entries.map(ModelEffort::jobOf)
        if (effort != null) jobs.forEach { ModelEffort.overrides[it] = effort }
        var spent = 0.0
        try {
            for (case in cases) {
                if (spent >= budget) {
                    println("Stopped at ${dollars(spent)}, the budget ${dollars(budget)}: ${case.id} and the cases after it not asked")
                    break
                }
                val now = set.nowOf(case)
                // The profile as the set's prompts have it: the settings' defaults, this task alone.
                val profile = Profile.Block(
                    Profile.yearGroup(now, zone, Settings().yearGroupStart),
                    Profile.block(Settings(), listOf(case.task), null, SchoolCalendar(), MemoryState(), now, zone),
                )
                val result = ClaudeEnricher(key!!, zone, profile = { profile }).enrich(case.task, case.job, now)
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

    private fun dollars(usd: Double) = "$" + String.format(Locale.UK, "%.3f", usd)

    private companion object {
        const val KEY = "CLAUDE_DECRASTINATION_API_KEY"
    }
}
