package com.thomaswcode.decrastination.enrich

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.CallRecord
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.ModelState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.plusCall
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How a model call went ([ModelGateway.call]): held before it was sent, failed, or answered. */
sealed interface Called<out T> {
    data class Held(val hold: ModelHold) : Called<Nothing>
    data class Failed(val error: Throwable) : Called<Nothing>
    data class Answered<T>(val value: T) : Called<T>
}

/**
 * Every call to Claude (docs/plan_v4/architecture.md §`ModelGateway`): whether it can be asked
 * now ([hold]); each call made under the monthly cap and through its rests (AC-04, AC-05), counted,
 * put on record (AC-07) and, failed, classed, with the key's alert; and the dropped plans' alerts.
 * Its state is `model.json`'s `ModelState`.
 */
class ModelGateway(
    private val settings: JsonStore<Settings>,
    /** The API key kept in the secrets, or null. */
    private val savedKey: () -> String?,
    private val model: JsonStore<ModelState>,
    private val tasks: JsonStore<TaskState>,
    private val clock: WallClock,
    private val alerts: Alerts,
    private val scope: CoroutineScope,
    /** A failure's rest over after this long: what only the rules have read is asked again then. */
    private val retryAfter: (Long) -> Unit,
) {
    /** Claude's warnings, as notifications ([ModelAlerts] on the phone, a list in a test). */
    interface Alerts {
        /** [problem] with the key or the account said; false if it couldn't be shown. */
        fun keyProblem(problem: KeyProblem): Boolean

        fun keyFixed()

        /** [task]'s plan from the model dropped, [why], said; false if it couldn't be shown. */
        fun dropped(task: TaskItem, why: String): Boolean

        /** Withdraws the dropped plans' warnings but those of [standing] (task ids). */
        fun withdrawExcept(standing: Set<String>)
    }

    /**
     * Debug only (`ai-endpoint`): where the model's calls go instead of Anthropic, a stand-in on the
     * PC, so its failures and answers can be tried on the phone through the real paths without a
     * paid call. Never stored: a restart forgets it.
     */
    @Volatile
    var endpoint: String? = null

    /** The one client, and the key and endpoint it was made for: made again only when either changes. */
    private var made: Triple<String, String?, AnthropicClient>? = null

    /**
     * What [job]'s reader sends through, while Claude is on with its key: the one client for the key
     * and the endpoint, kept between calls (its connections with it), with [job]'s timeout
     * ([ModelEffort.timeout]). Null while Claude is off.
     */
    @Synchronized
    fun client(job: ModelEffort.Job): AnthropicClient? {
        val key = claudeKey() ?: return null
        // Read once: the client is made for the endpoint it's kept under.
        val at = endpoint
        val client = made?.takeIf { it.first == key && it.second == at }?.third ?: build(key, at).also {
            made = Triple(key, at, it)
            // Said loudly each time a client is made with one.
            if (at != null) Log.w(TAG, "Claude's calls go to $at, not Anthropic (debug ai-endpoint)")
        }
        return client.withOptions { it.timeout(ModelEffort.timeout(job)) }
    }

    /** Claude's API key, while Claude is switched on and the key is in use; else null. */
    fun claudeKey(): String? {
        val s = settings.value
        if (!s.aiEnabled || !s.aiKeyActive) return null
        return savedKey()?.takeIf { it.isNotBlank() }
    }

    /**
     * A failed model call, counted in [month] (or a newer one counted since): the model rests ([ModelHold.Resting]), and one showing
     * that the key or the account can't be used ([KeyProblem]) is alerted, once a stretch of it,
     * not at each hourly retry. The rules stand in meanwhile.
     */
    suspend fun failed(error: Throwable, month: String, at: Long, task: TaskItem? = null) {
        val problem = KeyProblem.of(error)
        // Rested by what failed (AC-04): busy, the server, a timeout, no network, a request it won't take.
        val kind = Failures.classify(error)
        var rest = 0L
        val after = model.update { state ->
            val usage = state.aiUsage.forCall(month)
            // Older than a call that's gone through since: no rest comes of it.
            rest = if (usage.stale(at)) 0L else usage.restAfter(kind, at, Failures.retryAfterMs(error))
            state.copy(
                aiUsage = usage.failure(error.message ?: error.javaClass.simpleName, at, problem, rest, kind),
                // A task the API won't take as asked isn't sent again till it changes.
                enrichFailed = if (kind == Failure.BadRequest && task != null) (state.enrichFailed + (task.id to Enrichments.inputHash(task))).entries.toList().takeLast(MAX_ENRICH_FAILED).associate { it.key to it.value } else state.enrichFailed,
            )
        }
        Log.w(TAG, "Claude's call failed ($kind): resting ${rest / 60_000} min")
        alertKeyProblem(after.aiUsage)
        // Once its rest is over, it's tried again on what's still the rules' (a key topped up or put
        // right meanwhile works then), rather than at whatever next changes.
        if (rest > 0) retryAfter(rest + RETRY_SLACK_MS)
    }

    /**
     * Shows the alerts not shown yet, a key problem and dropped plans, as notifications may have
     * come on since (allowed, or the channel switched back on): at the start, whenever the app comes
     * to the front (back from notification settings), and once the permission is granted.
     */
    fun reconcileAlerts() {
        scope.launch {
            runCatching {
                alertKeyProblem(model.value.aiUsage)
                droppedAlerts(tasks.value.tasks)
            }.onFailure { Log.w(TAG, "Showing waiting alerts failed", it) }
        }
    }

    /**
     * The dropped-plan warnings in step with [all]: those whose task no longer has one withdrawn
     * (done or gone, which the enrichment loop never sees again, or planned since), and any not
     * shown yet shown, once notifications can show it.
     */
    suspend fun droppedAlerts(all: List<TaskItem>) {
        val standing = DroppedAlerts.standing(all)
        alerts.withdrawExcept(standing.mapTo(HashSet()) { it.first.id })
        val shown = DroppedAlerts.unshown(standing, model.value.droppedAlerted).filter { (task, why) -> alerts.dropped(task, why) }
        model.update { state ->
            val kept = state.droppedAlerted.filterKeys { id -> standing.any { it.first.id == id } }
            state.copy(droppedAlerted = kept + shown.mapNotNull { (task, _) -> task.enrichment?.let { task.id to it.inputHash } })
        }
    }

    /**
     * [usage]'s key problem alerted, if it hasn't been yet and can be seen: one that couldn't
     * (notifications off) is tried again at the next failed call and when the app starts.
     */
    private suspend fun alertKeyProblem(usage: AiUsage) {
        val problem = usage.keyProblem?.takeIf { usage.keyAlertDue } ?: return
        if (alerts.keyProblem(problem)) model.update { it.copy(aiUsage = it.aiUsage.alerted(problem)) }
    }

    /** A new key put in: the last key's failure isn't this one's, so it's tried at once, not after the rest. */
    suspend fun newKey() {
        model.update { it.copy(aiUsage = it.aiUsage.newKey()) }
    }

    /**
     * A model call that went through, counted in [month] (or a newer one counted since): its cost,
     * and the key working again if it wasn't, its alert then gone; not when a failure since, of a
     * call sent after it, found the problem standing.
     */
    suspend fun worked(costUsd: Double, refused: Boolean, month: String, at: Long) {
        var mended = false
        model.update { state ->
            val before = state.aiUsage.forCall(month)
            val after = before.record(costUsd, refused, at)
            mended = before.keyProblem != null && after.keyProblem == null
            state.copy(aiUsage = after)
        }
        if (mended) alerts.keyFixed()
    }

    /**
     * What stops the model being asked now ([ModelHold]), or null: off, resting after a failed
     * call, or no room under the cap. Each call checks it under [calling] just before sending.
     */
    fun hold(interactive: Boolean = false): ModelHold? {
        val s = settings.value
        return ModelHold.of(claudeKey() != null, model.value.aiUsage, clock.now(), clock.zone(), s.aiMonthlyCapUsd, interactive)
    }

    /**
     * Held for each call to the model while its room is taken: the hold checked and the call counted
     * in flight, at its dearest, in one step ([call]), so calls made together can't pass the cap.
     */
    private val calling = Mutex()

    /** Calls sent and not back yet (AC-05): counted against the cap at their dearest. */
    private val inFlight = AtomicInteger()

    /**
     * A model call (AC-05): its room taken under [calling] for a moment (the model not held, an
     * [interactive] one through a rest; room under the cap beside the calls in flight), then [send]
     * with the lock let go, so the chat or a photo check doesn't wait behind an email's reading;
     * counted as it went, [cost] its price and whether it was declined, or its failure ([task], one
     * the API wouldn't take, isn't sent again till it changes).
     */
    suspend fun <T> call(interactive: Boolean, task: TaskItem? = null, record: ModelCalls.OnRecord<T>? = null, cost: (T) -> Pair<Double, Boolean>, send: suspend (at: Long) -> T): Called<T> {
        val held = calling.withLock {
            val hold = hold(interactive) ?: ModelHold.Capped.takeIf {
                !model.value.aiUsage.forMonth(AiUsage.monthOf(clock.now(), clock.zone())).allows(settings.value.aiMonthlyCapUsd, inFlight.get())
            }
            if (hold == null) inFlight.incrementAndGet()
            hold
        }
        if (held != null) return Called.Held(held)
        // In flight from here: whatever follows, the count is let go.
        return try {
            // Counted in the month it's made in, as the cap was checked for (or a newer one counted since).
            val at = clock.now()
            val month = AiUsage.monthOf(at, clock.zone())
            // Sent, it's paid for: it comes back and is counted, whatever cancels its caller meanwhile (a
            // job stopped), which goes on at its next suspension.
            withContext<Called<T>>(NonCancellable) {
                try {
                    val value = send(at)
                    val (usd, refused) = cost(value)
                    worked(usd, refused, month, at)
                    record?.let { r -> r.read(value).let { (usage, outcome) -> keepCall(r, at, usage, outcome, usd) } }
                    Called.Answered(value)
                } catch (e: Throwable) {
                    failed(e, month, at, task)
                    record?.let { r -> keepCall(r, at, ModelCalls.Usage.NONE, "failed:" + Failures.classify(e).name.lowercase(), 0.0) }
                    Called.Failed(e)
                }
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** [r]'s call, made at [at], put on record (AC-07): what it used, how it went, what it cost. */
    private suspend fun keepCall(r: ModelCalls.OnRecord<*>, at: Long, usage: ModelCalls.Usage, outcome: String, usd: Double) {
        val call = CallRecord(
            at = at, job = r.job.name, title = r.title?.trim()?.take(MAX_CALL_TITLE), effort = ModelEffort.of(r.job).toString().lowercase(),
            inTokens = usage.inTokens, outTokens = usage.outTokens, cacheRead = usage.cacheRead, latencyMs = clock.now() - at, stop = usage.stop, outcome = outcome, costUsd = usd,
        )
        model.update { it.copy(calls = it.calls.plusCall(call)) }
    }

    /**
     * Whether the model can be asked now: on with its key in use, not resting after a failed call,
     * and with room under this month's cap for another. When it can't, what only the rules have
     * seen isn't due for it, so it isn't redone every sync while the cap is used up.
     */
    fun available(): Boolean = hold() == null

    /** Why a call waits, in words, by what [hold]s it: an instruction's or a chat message's note. */
    fun because(hold: ModelHold): String = when (hold) {
        ModelHold.Off -> "Waiting for Claude, which is off (Settings)"
        ModelHold.Resting -> "Waiting for Claude, resting ${ModelHold.restLeftMin(model.value.aiUsage, clock.now())} min more after a failed call"
        ModelHold.Capped -> "Waiting for Claude: this month's cap is reached"
    }

    /** When a failed call is tried again (AC-04): after its rest, or at the next chance where it has none. */
    fun retryWords(): String = ModelHold.restLeftMin(model.value.aiUsage, clock.now()).let { if (it > 0) "it's tried again in $it min" else "it's tried again at the next chance" }

    companion object {
        /**
         * A client for [key], at [endpoint] (Anthropic's, with none): the one place one's built, the
         * gateway's own and a stand-in's for the plumbing check (`ai-check`) alike.
         */
        fun build(key: String, endpoint: String? = null): AnthropicClient =
            AnthropicOkHttpClient.builder().apiKey(key).apply { if (endpoint != null) baseUrl(endpoint) }.maxRetries(2).build()

        private const val TAG = "Decrastination"

        /** Past the model's hour of rest, so the retry finds it over. */
        private const val RETRY_SLACK_MS = 60_000L

        /** The most of what a call was about that its record keeps (AC-07). */
        private const val MAX_CALL_TITLE = 80

        /** The most tasks remembered as refused as asked (AC-04): far more than there are. */
        private const val MAX_ENRICH_FAILED = 200
    }
}
