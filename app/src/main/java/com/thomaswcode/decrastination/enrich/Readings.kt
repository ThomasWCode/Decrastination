package com.thomaswcode.decrastination.enrich

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.AnkiRules
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.withEnrichment
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The tasks' readings (docs/spec/readings.md; docs/plan_v4, Stage 6d), moved out of `AppGraph`: what's
 * new or changed read, by Claude while it can be asked and by the rules otherwise ([enrichNow]); one
 * read again on asking ([readAgain]); and the dropped plans' warnings kept in step with the tasks
 * ([start]). Built like [com.thomaswcode.decrastination.block.Focus]: its stores, the clock, the
 * gateway and small callbacks.
 */
@OptIn(FlowPreview::class)
class Readings(
    private val tasks: JsonStore<TaskState>,
    private val runtime: JsonStore<RuntimeState>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val gateway: ModelGateway,
    /** Who the student is, as each call sends it (AI-05). */
    private val profile: () -> Profile.Block,
    /** A question a reading couldn't settle (AI-10), for Felix to put ([com.thomaswcode.decrastination.felix.Looks.queueQuestion]). */
    private val queueQuestion: suspend (taskId: String, question: String?, now: Long) -> Unit,
    /** Anki read again now, its decks' sections or deadline changed by a reading ([com.thomaswcode.decrastination.sync.SyncWorker.syncNow]). */
    private val readDecks: () -> Unit,
) {
    /** The rules' enrichment: always there, and the fallback for the model. */
    private val rules = RuleEnricher()

    /**
     * Enriches every task that's new or changed since it was last enriched, and, while the model is
     * on, those only the rules have seen; soonest due first. The model does it while it's on and
     * the month's spend leaves room under the cap, at most [MAX_MODEL_CALLS] a run; the rules do
     * the rest, and stand in for a call that fails (the model is then left alone for the run) or
     * that the model declines (recorded as the model's, so it isn't asked again). With [force], [only]
     * is read again by the model whatever it was read by (AC-06), its reading kept unless the model
     * gives one; whether it did. [byModel] false, the rules alone, whatever's on.
     */
    suspend fun enrichNow(byModel: Boolean = true, only: String? = null, force: Boolean = false): Boolean {
        // Not while it rests after a failed call (no network, a bad key), nor with no room under the
        // cap: then what only the rules have seen isn't due for it.
        var enricher: Enricher? = gateway.client(ModelEffort.Job.Email).takeIf { byModel }
            ?.let { ClaudeEnricher(it, clock.zone(), profile = { profile() }, decks = { tasks.value.deckTopics }) }?.takeIf { gateway.available() }
        var calls = 0
        var decksChanged = false
        // A task whose call the API wouldn't take as asked (AC-04): the rules' till it changes.
        fun refused(task: TaskItem) = runtime.value.enrichFailed[task.id] == Enrichments.inputHash(task)
        fun due(task: TaskItem) = (force && task.id == only) || Enrichments.stale(task, enricher != null && !refused(task), RuleEnricher.BY, ReadingPrompts.VERSION, modelOff = gateway.claudeKey() == null)
        // Whether [only]'s reading laid was the model's (for [force]).
        var readByModel = false
        val candidates = tasks.value.tasks
            // [only]: that task alone (the debug `enrich-task`), so nothing else is sent anywhere.
            .filter { only == null || it.id == only }
            .mapNotNull { task -> Enrichments.jobFor(task, GmailThreads::textRead)?.let { task to it } }
            .filter { (task, _) -> due(task) }
            // Never read first, then the oldest prompts' readings (AC-06), soonest due first.
            .sortedWith(compareBy(Enrichments.NEED) { it.first })
        for ((snapshot, _) in candidates) {
            // Read afresh: a sync since the run began may have closed or changed it.
            val task = tasks.value.tasks.firstOrNull { it.id == snapshot.id } ?: continue
            val job = Enrichments.jobFor(task, GmailThreads::textRead) ?: continue
            if (!due(task)) continue
            // Whether the model gave this one's reading, not the rules standing in.
            var fromModel = false
            // Stale only by the prompts' version (AC-06): its reading stays unless the model reads it now.
            val onlyVersion = Enrichments.staleOnlyByVersion(task, RuleEnricher.BY, ReadingPrompts.VERSION)
            val now = clock.now()
            // Switched off (or its key removed) while this runs: nothing more is sent.
            if (gateway.claudeKey() == null) enricher = null
            val model = enricher?.takeIf { calls < MAX_MODEL_CALLS && !refused(task) }
            var enrichment = if (model != null) {
                // Held now (switched off, resting after a failed call, no room under the cap): the
                // rules do the rest of the run.
                when (val called = gateway.call(interactive = false, task = task, record = ModelCalls.OnRecord(ModelEffort.jobOf(job), task.title) { r: Enricher.Result -> r.usage to ModelCalls.outcome(r) }, cost = { r: Enricher.Result -> r.costUsd to r.refused }) { model.enrich(task, job, now) }) {
                    is Called.Held -> {
                        enricher = null
                        null
                    }
                    is Called.Failed -> {
                        calls++
                        Log.w(AppGraph.TAG, "The model's enrichment failed; the rules stand in", called.error)
                        // Only a request it won't take leaves the others to be asked.
                        if (Failures.classify(called.error) != Failure.BadRequest) enricher = null
                        null
                    }
                    // Declined, or no answer it could read: the rules' say, under the model's name.
                    is Called.Answered -> {
                        calls++
                        val first = called.value
                        // A check its answer failed (AI-01): put back once, as a call of its own (counted
                        // under the cap, held by a rest); the first's reading stands if that doesn't come back.
                        val again = first.repair?.takeIf { calls < MAX_MODEL_CALLS }?.let { repair ->
                            when (val second = gateway.call(interactive = false, task = task, record = ModelCalls.OnRecord(ModelEffort.jobOf(job), task.title) { r: Enricher.Result -> r.usage to ModelCalls.outcome(r) }, cost = { r: Enricher.Result -> r.costUsd to r.refused }) { model.repair(task, job, now, repair) }) {
                                is Called.Answered -> second.value.also { calls++ }
                                is Called.Failed -> {
                                    calls++
                                    if (Failures.classify(second.error) != Failure.BadRequest) enricher = null
                                    null
                                }
                                is Called.Held -> {
                                    enricher = null
                                    null
                                }
                            }
                        }
                        // The rules' under the model's name is the model's answer by these prompts (AC-06), not asked for again till they change.
                        (again?.enrichment ?: first.enrichment)?.also { fromModel = true }
                            ?: when {
                                force -> null
                                // Declined by these prompts: the reading there kept, as theirs now, so it isn't asked for again till they change.
                                onlyVersion -> task.enrichment?.copy(promptVersion = ReadingPrompts.VERSION)
                                else -> rules.enrich(task, job, now).enrichment?.copy(by = model.by, promptVersion = ReadingPrompts.VERSION)
                            }
                    }
                }
            } else {
                null
            }
            // Read again on asking, or past this run's calls with a reading only older prompts made: the
            // reading there stands unless the model gave one.
            if (enrichment == null && (force || onlyVersion)) continue
            if (enrichment == null) enrichment = rules.enrich(task, job, now).enrichment
            val made = enrichment ?: continue
            // Laid only over the task as it was read (changed during the call, the next run does it).
            var laid = false
            tasks.update { state ->
                state.copy(
                    tasks = state.tasks.map {
                        if (it.id == task.id && Enrichments.inputHash(it) == made.inputHash) it.withEnrichment(made).also { laid = true } else it
                    },
                )
            }
            if (!laid) continue
            if (fromModel && task.id == only) readByModel = true
            // A question it couldn't settle from the text (AI-10): Felix puts it. One waiting from an
            // older reading goes, asked again as this one has it, or settled by it.
            queueQuestion(task.id, made.question, now)
            // A plan of the model's dropped (out of range, not adding up) is said once it's laid,
            // by droppedAlerts, as the tasks change.
            // Its deck tasks take their sections and due date from it.
            val after = task.withEnrichment(made)
            val topics = tasks.value.deckTopics
            val sections = AnkiRules.sectionsOf(after, topics)
            if (sections != AnkiRules.sectionsOf(task, topics) || (sections.isNotEmpty() && after.dueAt != task.dueAt)) decksChanged = true
        }
        // Sections the deck pattern missed, or a deadline moved: their deck tasks come from reading Anki again, now.
        if (decksChanged) readDecks()
        return readByModel
    }

    /** How a read again on asking went (AC-06). */
    enum class ReadAgain { Read, NotNow, NoReading }

    /**
     * [taskId] read again by Claude as it is now (AC-06, the sheet's *Read again with Claude*), by
     * these prompts whatever it was read by, its steps shown it to keep by name (AI-08). The reading
     * there stands till the new one comes: [ReadAgain.NoReading] where none did (the call failed or
     * was held, Claude declined, the task changed meanwhile); [ReadAgain.NotNow], with nothing sent,
     * while Claude can't be asked (off, resting, at the cap).
     */
    suspend fun readAgain(taskId: String): ReadAgain = withContext(NonCancellable) {
        if (gateway.claudeKey() == null || !gateway.available()) return@withContext ReadAgain.NotNow
        if (enrichNow(only = taskId, force = true)) ReadAgain.Read else ReadAgain.NoReading
    }

    /** The dropped plans' warnings kept in step with the tasks, from the app's start. */
    fun start() {
        // Dropped plans' warnings kept in step with the tasks, from the start.
        scope.launch {
            tasks.state.debounce(DROPPED_DEBOUNCE_MS).collect { state ->
                runCatching { gateway.droppedAlerts(state.tasks) }.onFailure { Log.w(AppGraph.TAG, "Dropped-plan warnings failed", it) }
            }
        }
    }

    companion object {
        /** The most model calls one enrichment run makes: the rest wait for the next. */
        const val MAX_MODEL_CALLS = 20

        /** How long the tasks are quiet before the dropped plans' warnings are kept in step: a second, as the widget waits. */
        private const val DROPPED_DEBOUNCE_MS = 1_000L
    }
}
