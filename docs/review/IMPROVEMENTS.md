# Independent improvements

Review date: 10 October 2026. Revision: `d1eefaa9948ed7ccb9d814c3113a104fa5fc4bec`. [Scope, checks, coverage and summary tables](README.md).

These are review findings only; none of the recommended changes has been implemented. Evidence excerpts are no longer than ten lines each; `...` marks omitted code. Line references refer to the reviewed revision.

Priorities reflect expected product benefit and effort independently of defect priorities. Recommended proposals are supported by the present code; Exploratory proposals require the specified measurement or contract evaluation. Acceptance criteria are targets, not measured improvements. Related defects are cross-referenced rather than repeated.

## P1

<a id="imp-p1-001"></a>

### IMP-P1-001 — Provide one task action surface for opening work, starting focus and checking completion

- **ID:** `IMP-P1-001`

- **Priority and category:** P1; Core workflow / UI.

- **Status:** Recommended (supported by code analysis).

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:72-76`; `app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:183-196`; `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:174-184`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:72-76`):

```kotlin
    val open = { chunk: Chunk ->
        tasks.tasks.firstOrNull { it.id == chunk.taskId }?.let { task ->
            scope.launch { TaskOpener.open(activity, task)?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() } }
        }
        Unit
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:183-188`):

```kotlin
                task.subSteps.forEachIndexed { index, step ->
                    // A block's own dates, where it has them; an email's can be ticked off here.
                    val dates = listOfNotNull(step.from?.let { "from " + Format.at(it, now, zone) }, step.dueAt?.let { Format.due(it, now, zone) }).joinToString(", ")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "• ${step.title} (${Format.minutes(step.minutes)}${if (dates.isEmpty()) "" else "; $dates"})${if (step.done) " ✓" else ""}",
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:174-179`):

```kotlin
                        if (session == null) {
                            // Not before it's available (an Anki deck's next cards at 04:00).
                            FilledTonalButton(enabled = next.startable(plan.now) && !checking, onClick = {
                                scope.launch { Sessions.start(this@BlockedActivity, next.taskId, next.label, next.step, next.minutes, next.box) }
                            }) { Text("Start ${Format.minutes(minOf(next.minutes, Focus.MAX_SESSION_MIN))}") }
                        } else {
```

- **Current behaviour:** Plan tiles open the source; Tasks expands details and email Done/instruction actions. Normal focus Start/Stop and Check it is done controls live on the block screen.

- **Description:** A user who proactively opens the app cannot start the same measured workflow directly from the plan or task detail. Opening a distracting app to obtain those controls is an avoidable detour.

- **Trigger/opportunity:** Beginning the recommended homework from Plan, investigating a task from Tasks, or resuming work after opening the app voluntarily.

- **Impact/benefit:** Fewer steps to start useful work and more complete timed-work data. Measure taps from launcher to a started focus session and the fraction of voluntarily started tasks with useful observations; no improvement percentage is assumed.

- **Recommended change:** Add a shared task/chunk detail action sheet with Open source, Start/Stop focus, Check source and applicable photo/instruction controls. Carry the same current chunk identity and protection rules used by the block screen. Acceptance: every startable plan chunk is actionable without first triggering blocking; repeated taps are idempotent.

- **Trade-offs:** Keep manual completion limited to the existing supported cases; more entry points increase lifecycle/concurrency testing requirements.

- **Effort:** M

<a id="imp-p1-002"></a>

### IMP-P1-002 — Return planning explanations and a visible unplanned workload

- **ID:** `IMP-P1-002`

- **Priority and category:** P1; Planning UX/blocking transparency.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/core/Plan.kt:60-91`; `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:297,316-318,385-387`; `app/src/main/java/com/thomaswcode/decrastination/learn/Briefing.kt:27-43`.
- **Excerpt** (`Planner.kt:385-388`, 4 lines):
  ```kotlin
  item.chunks.indices.forEach { i ->
      val day = assigned[i] ?: return@forEach
      placed.getValue(day) += chunk(item, i, behind = behind[i] || day > dueBy)
  }
  ```
- **Current behaviour:** Plan exposes placed buckets and events. It carries urgency flags but not why a piece landed there or what could not be placed. The briefing counts only today's pieces.
- **Description:** make the backward scheduling policy inspectable: source deadline, margin, calendar deductions, daily card limit, dependency, capacity conflict and horizon deferral should explain the recommendation.
- **Impact/benefit:** every open obligation has either a placement or a visible reason it is waiting/unplanned; tapping a chunk explains why it is today and what changes would move it. Capacity totals reconcile with busy events and instruction limits.
- **Recommended change:** return typed reasons for valid placements, intentional waits and horizon deferrals alongside buckets; display a concise explanation from Today/Tasks. Consume the unscheduled/conflict result introduced by [BUG-P2-003](BUGS-AND-INCONSISTENCIES.md#bug-p2-003) rather than implementing another omission fix. Cross-reference [BUG-P2-003](BUGS-AND-INCONSISTENCIES.md#bug-p2-003) for the defect fix: this enhancement also covers intentionally deferred horizon work and valid dependency waits.
- **Trade-offs:** avoid overwhelming the main screen; progressively reveal details. An explanation model must remain synchronized with planner branches and should be asserted alongside output tests.
- **Effort:** **M**.

- **Trigger/opportunity:** Inspect a next-task recommendation, an overloaded deadline or work intentionally held by a dependency.

<a id="imp-p1-003"></a>

### IMP-P1-003 — Turn unreadable-state fallback into a recoverable, visible incident

- **ID:** `IMP-P1-003`

- **Priority and category:** P1; Persistence/recovery.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt:18-26,51-57,60-79`; `app/src/main/java/com/thomaswcode/decrastination/data/SecretStore.kt:79-86`; `app/src/test/java/com/thomaswcode/decrastination/data/StoresTest.kt:23-29,56-64`.
- **Excerpt** (`JsonStore.kt:51-57`, 7 lines):
  ```kotlin
  private fun load(): T {
      if (!file.exists()) return default()
      val loaded = runCatching { json.decodeFromString(serializer, file.readText()) }
      loaded.exceptionOrNull()?.let {
          runCatching { file.copyTo(File(file.path + ".unreadable"), overwrite = true) }
      }
      return onLoad(loaded.getOrNull() ?: default())
  ```
- **Current behaviour:** malformed state is copied to a single `.unreadable` file and default state is exposed; secret load failures become an empty map. The store does not expose whether fallback occurred or whether preserving the bad file succeeded.
- **Description:** keep startup resilient while giving the user a direct way to recover their instructions/history/settings, instead of requiring manual app-private-file investigation.
- **Impact/benefit:** injected corruption starts the app with an explicit recovery notice, preserves the original bytes under a unique identifier, and can restore the last validated snapshot. A second incident cannot overwrite the only evidence from the first. Keystore failure is distinguishable from no credentials set without exposing secrets.
- **Recommended change:** return load diagnostics with state, keep a bounded versioned last-good snapshot and migration metadata, and provide export/restore/continue choices. Use a small import/recovery journal to make multi-store restoration restartable. [BUG-P2-002](BUGS-AND-INCONSISTENCIES.md#bug-p2-002) separately covers rejecting semantically invalid backups.
- **Trade-offs:** snapshots consume storage and may retain sensitive data longer; apply bounded retention and existing private-storage/secret protections. Do not silently restore stale protection state.
- **Effort:** **M**.

- **Trigger/opportunity:** Start the app after a JSON/keystore read failure, or recover from repeated corrupt-state incidents.

<a id="imp-p1-004"></a>

### IMP-P1-004 — Add a semantic AI evaluation set for actual task decisions

- **ID:** `IMP-P1-004`

- **Priority and category:** P1; AI reliability/evaluation.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt:45-73,220-297`; `app/src/test/java/com/thomaswcode/decrastination/enrich/EnrichTest.kt:163-410,548-572`; `app/src/test/java/com/thomaswcode/decrastination/core/InstructionsTest.kt:319-381`.
- **Excerpt** (`EnrichTest.kt:548-551`, 4 lines):
  ```kotlin
  fun `it asks Opus 5 point 5 at high effort for the schema's answer, with fallbacks on`() = runBlocking {
      server.enqueue(reply("""{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-12T08:30","effortMin":10,"nextStep":"Sign the form"}"""))
      val result = enricher().enrich(email(), Enrichments.Job.Email, NOW)
      val request = server.takeRequest()
  ```
- **Current behaviour:** deterministic tests thoroughly validate parsing, schema, mocked requests and planner rules. They cannot establish whether a real model recognizes genuine obligations, quoted instructions, relative dates or the correct extent of work.
- **Description:** evaluate the model's decision quality independently of the transport/parser and alongside deterministic fallbacks.
- **Impact/benefit:** a consented/redacted corpus measures missed actionable emails, false-positive tasks, wrong deadlines, invalid plans, estimate error and cost/latency by job. Model/prompt changes must not increase missed obligations on the chosen acceptance set; numerical thresholds should be chosen after establishing a baseline, not invented here.
- **Recommended change:** construct labelled examples from supported workflows, including forwarded mail, multiple deadlines, adversarial source instructions, vague availability and mixed vocabulary work. Save response fixtures, run deterministic downstream evaluation offline, and perform live generation only under an explicit development budget.
- **Trade-offs:** labelling takes time and personal content needs redaction. One dataset can overfit; maintain held-out examples and report uncertainty.
- **Effort:** **M**.

- **Trigger/opportunity:** Change a model, prompt, schema or interpretation rule that can hide obligations or move deadlines.

<a id="imp-p1-005"></a>

### IMP-P1-005 — Make browser enforcement coverage visible and model unknown windows explicitly

- **ID:** `IMP-P1-005`

- **Priority and category:** P1; blocking effectiveness / diagnostics.

- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:88-115`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:344-368`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:445-461`, `app/src/main/java/com/thomaswcode/decrastination/block/Blocklist.kt:61-63`.
- **Excerpt:**

```kotlin
val site = if (text != null) graph.focus.siteTarget(browser, text) else remembered(browser)
if (text != null) rememberSite(browser, site)
if (site != null) act(site) else stopSpendingUnlessAside()
```

- **Current behaviour:** Chrome/Brave use one fixed address-bar resource ID. An unreadable browser is backed out once and ultimately allowed as unknown, by deliberate design. Known allowed, never observed and temporarily unreadable states live in nullable maps keyed by package.
- **Description:** The humane fail-open choice need not be invisible. A browser update or new window can reduce coverage while the protection UI still reports that the service is on. Package-wide remembered state also cannot express separate provenance for each normal browser window.
- **Trigger/opportunity:** A supported browser hides or changes its URL bar, or multiple windows coexist.
- **Impact/benefit:** Users can see which browsers are actually protected, choose an explicit unknown-browser policy and diagnose coverage failures without treating service connection as proof of site detection. Success can be measured as correct known/unknown classification in recorded window fixtures and fewer unintended Back actions.
- **Recommended change:** Introduce explicit KnownAllowed / KnownBlocked / Unknown observations, timestamp and window identity, plus per-browser adapters with tested IDs. Show a concise degraded-coverage status and offer either permissive unknown behavior or blocking the browser outright, through existing delayed settings changes.
- **Trade-offs:** Accessibility cannot guarantee a browser URL is exposed. Do not promise universal browser control or collect full browsing history; retain only the minimum host/classification evidence needed.
- **Effort:** M. High priority because this directly improves the core blocker with a small bounded supported-browser surface.

<a id="imp-p1-006"></a>

### IMP-P1-006 — Offer a protection-readiness check and explicit guard self-test before arming

- **ID:** `IMP-P1-006`

- **Priority and category:** P1; protection reliability / onboarding.

- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:182-244`, `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:285-296`, `app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt:41-74`, `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionCheck.kt:77-85`.
- **Excerpt:**

```kotlin
TextButton(onClick = {
    step = Step.None
    scope.launch {
        graph.changeSettings { it.copy(armed = true) }
        Watchdog.check(this@ProtectionActivity, repair = true)
    }
}) { Text("Arm") }
```

- **Current behaviour:** The wizard asks for admin and optional parent code, then arms. Service connection, self-repair permission, notification reachability and textual Settings compatibility are displayed separately; arming is not conditioned on them.
- **Description:** The status checklist can become an active, comprehensible setup workflow rather than relying on the user to infer which missing components make “Armed” incomplete. English text and One UI page heuristics deserve an explicit live confidence check after OS changes.
- **Trigger/opportunity:** First arming, a phone OS update, or returning after protection permissions changed.
- **Impact/benefit:** Users understand actual protection and retain a tested way back before enabling the guard. Measure successful self-test coverage and the proportion of armed setups with unresolved critical prerequisites.
- **Recommended change:** Summarize blocking, repair, uninstall deterrence and alert capabilities individually before confirmation; provide guided guard tests with expected result and recovery instructions. Permit deliberately partial protection only with accurate labels; do not claim an unknown shortcut was verified.
- **Trade-offs:** Android/OEM behavior limits what can be conclusively tested; do not turn setup into an irreversible trap or pretend a heuristic is tamper-proof.
- **Effort:** M. High priority because readiness is central to the app's primary purpose and the required facts already exist.

<a id="imp-p1-007"></a>

### IMP-P1-007 — Show plan confidence and source health in the app and every widget size

- **ID:** `IMP-P1-007`

- **Priority and category:** P1; Planning UX / feedback.

- **Status:** Recommended (supported by code analysis).

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:164-167`; `app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt:100-103`; `app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt:96-105`; `app/src/main/java/com/thomaswcode/decrastination/widget/NextWidget.kt:339-351`; `app/src/main/java/com/thomaswcode/decrastination/widget/NextWidget.kt:237-247`; `app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt:76-77`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:164-167`):

```kotlin
            if (next == null) {
                Text("Nothing to do", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("No work is planned. New tasks appear as the sources are read.", style = MaterialTheme.typography.bodyMedium)
                return@Column
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt:100-103`):

```kotlin
        // What Setup would show in red, as a dot on the menu: a source failing, Claude's key, protection.
        val attention = Source.entries.any { tasks.status(it).error != null } ||
            (runtime.aiUsage.keyProblem != null && settings.aiEnabled && settings.aiKeyActive) ||
            runtime.protection.problems.isNotEmpty()
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt:96-101`):

```kotlin
        private fun warning(state: TaskState, now: Long): String? {
            val failing = Source.entries.firstOrNull { state.status(it).error != null }
            if (failing != null) return "Can't read ${failing.label}"
            // The Teams widget's own trouble (its sync service off, its last sync failed), which
            // also explains a ↻ that didn't sync Teams.
            state.status(Source.Teams).note?.let { return it }
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/widget/NextWidget.kt:339-344`):

```kotlin
            // Each in turn, as long as it fits; what isn't there takes no room.
            var used = PADDING + t(HEADLINE) + pillsHigh
            fun fits(extra: Float) = (height >= used + extra).also { if (it) used += extra }
            val pills = when {
                sideBySide(SIDES) -> Pills.Row
                fits(t(PILLS)) -> Pills.Stacked
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/widget/NextWidget.kt:237-242`):

```kotlin
    /** What's wrong, set apart in the error colours, first; a tap opens the app, whose ⋮ menu leads to the fix. */
    @Composable
    private fun Warning(text: String, app: Action) {
        Box(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(
                text,
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt:76-77`):

```kotlin
                warning = protection(runtime, armed) ?: warning(state, now),
                taskId = next?.taskId,
```

- **Current behaviour:** An empty plan says Nothing to do; source/protection trouble is mainly a menu badge, Tasks details or widget warning. The main refresh button reads sources while the widget also requests a Teams sync. Compact widget layout spends available height on Then and DO NOW before the warning. The smallest supported layout intentionally hides the warning entirely, as unit tests assert.

- **Description:** The primary decision surface does not distinguish an actually clear workload from incomplete, stale or never-read inputs. Refresh actions with the same icon also produce different levels of Teams freshness. A widget used as the main task surface can show a reassuring next task while protection or a source is failing, with no compact indication of the problem.

- **Trigger/opportunity:** First launch, an offline afternoon, a failed provider, or deciding whether a stale Teams assignment has been handed in. A 2x1 or other short widget during a permission, protection, or source failure.

- **Impact/benefit:** Users can judge whether the recommendation is current and reach the recovery action directly. Acceptance can count cases where each known failure/stale condition has a visible explanation and an effective action. At least one visible and accessible health cue survives every supported widget size. Test all existing WidgetLayout sizes with warning=true and confirm a direct path to the relevant setup issue.

- **Recommended change:** Add a compact plan health summary: last successful source reads, source-data age where available, pending enrichment, and whether the plan is provisional. Make Refresh source data versus Request Teams sync explicit, with the screen-takeover explanation on the latter. Preserve last-known work during failures. Reserve a small status icon/stripe and accessible description for the highest-severity warning; substitute it for secondary wording when there is no space. Route the cue to Setup or the specific failing source and keep task opening on the main card.

- **Trade-offs:** Avoid alarm fatigue and do not imply that a recent provider read guarantees a recent external Teams sync. This improves visibility without duplicating malformed-snapshot fixes. Do not cram full error messages into tiny layouts; the full message belongs in the app. Preserve the primary next action where possible.

- **Effort:** M

<a id="imp-p1-008"></a>

### IMP-P1-008 — Reevaluate blocking at known policy boundaries and meaningful state changes

- **ID:** `IMP-P1-008`

- **Priority and category:** P1; responsiveness / efficiency.

- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:599-629`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:705-718`, `app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt:62-75`, `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:54-67`.
- **Excerpt:**

```kotlin
tick()
if (active) handler.postDelayed(this, TICK_MS)
...
private const val TICK_MS = 30_000L
```

- **Current behaviour:** Events handle app/window changes; a fixed 30-second tick catches policy changes while an app stays visible. The tick also checks sessions, pending settings, watchdog and Teams offers.
- **Description:** Blocking-hour boundaries, override expiry, new pressure and setting changes are knowable without repeatedly polling all windows. The current polling is a reasonable backstop but also determines response latency when no window event arrives.
- **Trigger/opportunity:** 16:45 while already watching a blocked app; task sync adds due work; a pending removal becomes effective; a session or override ends.
- **Impact/benefit:** Lower and more predictable policy-transition latency while reducing repeated window traversal during idle stable periods. Measure decision-to-cover latency and window reads before/after; no performance claim is asserted here.
- **Recommended change:** Subscribe to relevant task/settings/runtime changes, compute the next time boundary, and coalesce reevaluations onto the service thread. Keep a slower heartbeat as recovery and continue elapsed-time checks while spending credit.
- **Trade-offs:** Avoid feedback loops when spending updates runtime and preserve the same-boot monotonic session clock. Android scheduling still needs a backstop.
- **Effort:** M.

## P2

<a id="imp-p2-001"></a>

### IMP-P2-001 — Offer deterministic controls for the seven supported instruction changes

- **ID:** `IMP-P2-001`

- **Priority and category:** P2; Offline UX/AI boundaries.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/core/Instructions.kt:59-81,124-127,167-225`; `app/src/main/java/com/thomaswcode/decrastination/enrich/InstructionReader.kt:47-61,195-220`.
- **Excerpt** (`Instructions.kt:74-80`, 7 lines):
  ```kotlin
  EventTime,

  /** At most [Change.freeMin] minutes of work on [Change.date], or every [Change.weekday]. */
  DayLimit,

  /** No work from [Change.startMin] to [Change.endMin] on [Change.date], or every [Change.weekday]. */
  BusyTime,
  ```
- **Current behaviour:** instruction text is interpreted into a small, well-defined set of Change values. Natural language is convenient, but straightforward edits inherit model availability, latency, interpretation ambiguity and the cap/rest policy.
- **Description:** retain natural-language entry as an accelerator while allowing a user to directly set a start date, dependency, task visibility, due date, event load or recurring availability.
- **Impact/benefit:** every supported change can be previewed/applied with AI disabled and no network request; natural-language and form paths yield equivalent validated Change objects and enforce the same parent-code/cycle rules.
- **Recommended change:** build typed editors backed by shared validators and `Instructions.describe`; include date/time pickers and task selectors. Use natural language to prefill the same reviewable controls.
- **Trade-offs:** seven controls can crowd the UI; context-specific menus should show only applicable ones. All paths must preserve protection checks.
- **Effort:** **M**.

- **Trigger/opportunity:** Set a known date, dependency or recurring availability when AI is off, capped, resting or unnecessary.

<a id="imp-p2-002"></a>

### IMP-P2-002 — Schedule and coalesce refreshes around each source's actual dependencies

- **ID:** `IMP-P2-002`

- **Priority and category:** P2; responsiveness / background execution.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:65,81-90`; `app/src/main/java/com/thomaswcode/decrastination/sync/SyncWorker.kt:34-43,63-75`; `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:110-114`.
- **Excerpt** (`SyncWorker.kt:69-75`, seven lines):

```kotlin
fun syncNow(context: Context, sources: Set<Source>? = null) {
    val request = OneTimeWorkRequestBuilder<SyncWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(workDataOf(KEY_SOURCES to sources?.joinToString(",") { it.name }))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
}
```

- **Current behaviour:** Every source shares a serial loop and a 15-minute periodic job; one-time requests append to a queue. Offline remote sources attempt network calls anyway, source failures return worker success, and local Anki waits until Teams, Power Planner and Gmail have been attempted.
- **Description:** Independent sources can progress independently while preserving the actual dependency that Anki homework needs merged assignments. Repeated UI/provider refresh requests can be coalesced instead of replaying identical full reads.
- **Trigger/opportunity:** Intermittent networking, repeated Refresh taps, bursts of provider observer events, or a fast Anki completion while Gmail is slow.
- **Impact/benefit:** Quicker local completion detection and fewer redundant remote reads. Measure time to each source's committed update and requests per user action rather than promising a fixed speed-up.
- **Recommended change:** Maintain a pending source set, merge/coalesce requests, run a bounded independent fetch wave, then derive Anki from the committed assignment snapshot. Apply network constraints/retry only to remote work, retain local work offline, and classify transient versus setup failures for bounded backoff.
- **Trade-offs:** More orchestration complexity and snapshot ordering requirements; avoid introducing a race where Anki derives from half-merged assignments. Do not enqueue unbounded automatic retries.
- **Effort:** M.

<a id="imp-p2-003"></a>

### IMP-P2-003 — Give email classification bounded conversation context, not only the latest body

- **ID:** `IMP-P2-003`

- **Priority and category:** P2; data processing / AI workflow.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:93-105,129-143`; `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/GmailTest.kt:108-123`.
- **Excerpt** (`GmailSource.kt:93-97`, five lines):

```kotlin
/** Each conversation's newest message, newest conversation first. */
fun latest(messages: List<InboxMessage>): List<InboxMessage> =
    messages.groupBy { it.threadId }
        .map { (_, thread) -> thread.maxWith(compareBy({ it.receivedAt }, { it.uid })) }
        .sortedByDescending { it.receivedAt }
```

- **Current behaviour:** One conversation becomes one task, but its content is only the newest inbox message. Tests intentionally reduce a conversation to a self-sent `Sounds good` response.
- **Description:** A short acknowledgment or changed subject often omits the original requested work. Retaining a bounded selection of preceding messages or a provenance-aware summary would make classification and amendments more faithful while preserving one task per conversation.
- **Trigger/opportunity:** A parent/teacher sends a detailed request, followed by short replies, clarification or a date change.
- **Impact/benefit:** Better preservation of actual obligations and less unnecessary re-planning after courtesy replies. Compare labeled multi-message conversation cases with the current latest-message baseline.
- **Recommended change:** Store message-level identities/timestamps and a small bounded context window, remove quoted duplicate text, and separate original requests from later amendments/acknowledgments. Show which message supports each extracted action. Do not assume all prior requests remain active after a cancellation reply.
- **Trade-offs:** More sensitive text is retained/read, more tokens may be used, and cancellation/supersession handling needs explicit evaluation. Make the amount of context transparent and bounded.
- **Effort:** M.

<a id="imp-p2-004"></a>

### IMP-P2-004 — Forecast work that is not yet available or waits on another task

- **ID:** `IMP-P2-004`

- **Priority and category:** P2; Planning effectiveness.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:164-177`; `app/src/main/java/com/thomaswcode/decrastination/core/Instructions.kt:134-144`; `app/src/main/java/com/thomaswcode/decrastination/core/Task.kt:79-81,169`.
- **Excerpt** (`Planner.kt:166-169`, 4 lines):
  ```kotlin
  fun heldBack(task: TaskItem) = task.hidden || Instructions.waiting(task, byId)
  val (events, work) = input.tasks
      .filter { it.isOpen && it.isAvailable(input.now) && !heldBack(it) && linked(it).let { a -> a.isEmpty() || !a.all(::heldBack) } }
      .partition { it.kind == Kind.Event }
  ```
- **Current behaviour:** Tasks gated by the effective `Task.availableFrom` property (source availability or enrichment actionable-from date) and dependency-held tasks are entirely omitted until the gate opens, while explicit userFrom dates are forecast through notBefore. A substantial upcoming application can therefore be absent from capacity forecasts.
- **Description:** distinguish visible future reservations from actions that may start now, using the existing future-start machinery and dependency graph.
- **Impact/benefit:** a task opening next Monday appears in next week's forecast today and warns if its available window cannot hold the work; it cannot be started or create premature enforcement merely because it is visible. A dependent task is shown with its predecessor and a provisional earliest date.
- **Recommended change:** forecast availableFrom work from its opening; compute dependency-aware provisional windows or explicitly reserve uncertain effort. Keep hidden/not-a-task semantics separate from not-yet-startable work.
- **Trade-offs:** a predecessor's actual completion time is uncertain; mark dependent forecasts provisional and do not turn predicted dates into false source facts.
- **Effort:** **L**.

- **Trigger/opportunity:** Plan ahead for an application opening later or a task whose predecessor is unfinished.

<a id="imp-p2-005"></a>

### IMP-P2-005 — Align calendar coverage with the forecast horizon

- **ID:** `IMP-P2-005`

- **Priority and category:** P2; Calendar/planning integration.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt:42-44,94`; `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:65-67,187-197,500-519`.
- **Excerpt** (`CalendarTime.kt:42-44`, 3 lines):
  ```kotlin
  private const val DAY_MS = 24 * 3_600_000L
  private const val LOOK_AHEAD_MS = 14 * DAY_MS
  private const val ASK_AHEAD_MS = 7 * DAY_MS
  ```
- **Current behaviour:** the calendar provides roughly a fortnight while task planning can span 90 days. Later buckets use configured hours without already-known future calendar commitments, then move work as those commitments enter the read window.
- **Description:** distinguish calendar-confirmed forecast capacity from provisional capacity, and query enough known events for the actual planning horizon.
- **Impact/benefit:** a known busy week six weeks ahead affects a six-week assignment forecast now; the UI shows calendar coverage/freshness and the amount of provisional time. No-event queries do not silently imply permission/read success.
- **Recommended change:** derive the required range from open-task deadlines within the existing 90-day ceiling, cache event instances by range, and incrementally extend/refresh coverage. Continue asking interpretation questions near their relevant time while showing uncertain events in forecasts.
- **Trade-offs:** more instances increase provider I/O and memory; bound range/counts and benchmark locally. Future calendars change, so forecasts remain provisional even with data present.
- **Effort:** **M**.

- **Trigger/opportunity:** Forecast an assignment more than fourteen days ahead while the device calendar already contains future commitments.

<a id="imp-p2-006"></a>

### IMP-P2-006 — Make homework-to-Anki mappings explicit and reviewable

- **ID:** `IMP-P2-006`

- **Priority and category:** P2; data collection / user control.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:59-63,114-122,145,218-222`; `docs/data-sources.md:110`.
- **Excerpt** (`AnkiRules.kt:218-222`, five lines):

```kotlin
for (task in assignments) {
    if (!task.isOpen || task.source == Source.Anki) continue
    for (section in sectionsOf(task)) {
        val deck = byName[deckName(textbook, section)] ?: continue
        wanted.getOrPut(deck) { mutableListOf() } += task
```

- **Current behaviour:** Textbook/section names are a strict string pattern; the current textbook supplies the book number, and the enrichment supplies sections only when the regex finds none. Topic-only descriptions cannot be mapped.
- **Description:** A small user-confirmed subject/topic-to-deck catalog would support existing real instructions that name a topic instead of a section, renamed decks and ambiguous textbook numbers without giving the AI unconstrained authority over arbitrary deck IDs.
- **Trigger/opportunity:** Homework says a topic such as a family unit, a teacher uses a different numbering scheme, or the user renames/reorganizes decks.
- **Impact/benefit:** Fewer unlinked vocabulary tasks and clearer explanation of why a deck is being scheduled; measure mapping coverage and correction frequency on existing assignment examples.
- **Recommended change:** Key mappings by stable deck IDs, show source assignment plus match reason/confidence, allow correction, and propose candidate matches from a local deck/topic catalog. Persist confirmed mappings and explicitly flag unmatched sections rather than silently skipping them.
- **Trade-offs:** Additional setup and stale mappings after deck changes; preserve deterministic overrides and avoid silently reusing a topic mapping across unrelated subjects.
- **Effort:** M.

<a id="imp-p2-007"></a>

### IMP-P2-007 — Keep unresolved calendar questions visible and scope answers to the intended event

- **ID:** `IMP-P2-007`

- **Priority and category:** P2; Calendar UX/data quality.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt:52-56,74-82,97-110`; `app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt:110-114,121-123,140-157`; `app/src/main/java/com/thomaswcode/decrastination/data/RuntimeState.kt:47-50`.
- **Excerpt** (`CalendarTime.kt:110-114`, 5 lines):
  ```kotlin
  val quiet = BlockPolicy.isQuiet(now, graph.clock.zone(), graph.settings.value)
  val toAsk = if (quiet || !Notify.shown(context, Channels.DAILY)) emptyList() else questions(time.toAsk, graph.runtime.value.eventsAsked, now)
  if (toAsk.isNotEmpty()) {
      toAsk.forEach { ask(context, it, graph.clock.zone()) }
      graph.runtime.update { it.copy(eventsAsked = it.eventsAsked + toAsk.map(EventJudge::key)) }
  ```
- **Current behaviour:** a name is marked asked when its notification is posted, and answers apply to all events with that lowercased title. Unanswered Ask events consume no capacity. A dismissed notification can leave the uncertainty unresolved for every later occurrence.
- **Description:** show unresolved choices in Calendar/Today and distinguish one occurrence, a recurring series and every event of a given name; use existing event/calendar information before collecting more data.
- **Impact/benefit:** dismissing a question does not lose the unresolved item; the forecast labels unknown time; the user can answer one occurrence without changing unrelated same-name events and can edit/reset stored answers offline.
- **Recommended change:** persist question state separately from delivery state, add an inbox/list with due dates and gentle controlled re-prompting, and use provider event/calendar/series identity with an explicit name-wide rule option.
- **Trade-offs:** additional identity/migration complexity and potential notification fatigue; avoid repeatedly asking after explicit dismissal/snooze preferences.
- **Effort:** **M**.

- **Trigger/opportunity:** Dismiss a calendar interpretation notification, revisit the same title, or answer only one event occurrence.

<a id="imp-p2-008"></a>

### IMP-P2-008 — Adapt app layouts and the Teams countdown for large text and assistive navigation

- **ID:** `IMP-P2-008`

- **Priority and category:** P2; Accessibility / responsive UI.

- **Status:** Recommended (supported by code analysis).

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:171-176`; `app/src/main/java/com/thomaswcode/decrastination/ui/StatsScreen.kt:163-185`; `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:242-247`; `app/src/main/java/com/thomaswcode/decrastination/ui/Components.kt:113-128`; `app/src/main/java/com/thomaswcode/decrastination/block/CountdownBanner.kt:87-95`; `app/src/main/java/com/thomaswcode/decrastination/block/CountdownBanner.kt:105-139`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:171-176`):

```kotlin
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val badge = WidgetModel.badge(next, plan.now, zone)
                if (next.urgent) WarningPill(badge) else Pill(badge)
                Pill(Format.minutes(next.minutes))
                Text(next.source.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/StatsScreen.kt:163-168`):

```kotlin
private fun TileRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight()) { left() }
        Box(Modifier.weight(1f).fillMaxHeight()) { right() }
    }
}
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:242-247`):

```kotlin
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/Components.kt:113-118`):

```kotlin
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 28.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/block/CountdownBanner.kt:87-92`):

```kotlin
    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/block/CountdownBanner.kt:105-110`):

```kotlin
        label = TextView(service).apply {
            setTextColor(onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(4), 0, dp(8), 0)
            maxLines = 1
            isSingleLine = true
```

- **Current behaviour:** The app uses fixed side-by-side stats tiles, one-line values, a non-wrapping next-task badge row and separate text/switch nodes. Folding headings expose a Fold/Unfold icon but no explicit expanded-state semantics. The Teams countdown uses one horizontal row, a single-line message and clickable TextViews, with a fixed ten-second response window.

- **Description:** The widget already accounts for font scale, while the app layouts do not make equivalent width/font decisions. This is an opportunity to improve usable reading and navigation; no measured clipping or TalkBack failure is claimed. The countdown is a particularly important instance because its actions precede a screen takeover.

- **Trigger/opportunity:** Large Android font/display settings, split-screen widths, long source labels or screen-reader/switch-access use.

- **Impact/benefit:** Readable values and reliably named, operable controls. Acceptance: 1.0/1.3/2.0 font scales and narrow widths retain all values/actions, and accessibility traversal names each switch and expanded group once. Cancel and Delay remain fully visible and operable at 200% text scale and with TalkBack.

- **Recommended change:** Use adaptive single-column stats when space is insufficient, FlowRow for badges, and adequate text wrapping. Associate switch labels through row toggle semantics; expose headings and expanded/collapsed state, keeping decorative icons out of duplicate announcements. For the Teams banner, use accessible buttons, usable-inset width bounds and a second row when needed; offer a longer warning interval and announce the initial warning without reading every tick.

- **Trade-offs:** Screen-reader behavior needs real accessibility checks as well as semantic tests; a wholesale visual redesign is unnecessary. A larger countdown overlay is more intrusive; preserve touch access outside it.

- **Effort:** M

<a id="imp-p2-009"></a>

### IMP-P2-009 — Make settings easier to find and preview before saving

- **ID:** `IMP-P2-009`

- **Priority and category:** P2; Settings UX.

- **Status:** Recommended (supported by code analysis).

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:118-154`; `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:191-210`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:118-123`):

```kotlin
                item { Section("Hours") }
                item {
                    WindowField("School days: work", draft.weekdayHours, "weekdayHours", ::valid) { draft = draft.copy(weekdayHours = it) }
                    WindowField("Weekends: work", draft.weekendHours, "weekendHours", ::valid) { draft = draft.copy(weekendHours = it) }
                    WindowField("Quiet hours (nothing blocked)", draft.quietHours, "quietHours", ::valid, overnight = true) { draft = draft.copy(quietHours = it) }
                    TimeField("School days: blocking from", draft.weekdayBlockFromMin, "weekdayBlockFromMin", ::valid) { draft = draft.copy(weekdayBlockFromMin = it) }
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:191-196`):

```kotlin
                item { Section("Blocked apps") }
                // Browsers have their own switches above.
                val shownBrowsers = browsers(draft).toSet()
                // Known ones not installed keep a row once switched off, so they can be switched back.
                val listed = (Blocklist.APPS + asked.blockedApps + draft.blockedApps).distinct()
                    .filter { pkg -> apps.none { it.packageName == pkg } && pkg !in shownBrowsers }
```

- **Current behaviour:** All groups and every installed application appear in one long lazy list; times are typed as text, and pending changes are explained at the bottom.

- **Description:** Common settings and a particular app can be hard to find, while a protection delay is not explained beside the control being edited.

- **Trigger/opportunity:** Changing one blocked app, checking the current versus requested hours, or correcting a validation error far above the Save button.

- **Impact/benefit:** Reduce scrolling and invalid input while keeping protection consequences understandable. Measure time/taps to locate an app and correctly identify when a change takes effect.

- **Recommended change:** Add group navigation/search, app-name filtering, validated time pickers with text entry as an option, and an effective/current/requested diff before Save. Put pending state and cancellation beside each affected field. Acceptance: any app can be found without scanning the entire list, and every delayed field exposes its effective value and wait.

- **Trade-offs:** Preserve current wording unless clarity requires a change; field-level draft preservation and concurrency fixes are separate bugs in this report set.

- **Effort:** M

<a id="imp-p2-010"></a>

### IMP-P2-010 — Add task search and contextual filtering without changing the default list

- **ID:** `IMP-P2-010`

- **Priority and category:** P2; Task discovery / navigation.

- **Status:** Recommended (supported by code analysis).

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:60-80`; `app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:119-157`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:60-65`):

```kotlin
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        for (source in Source.entries) {
            // Emails that are just emails aren't tasks: not listed (your call, 9 Oct).
            // You've said some aren't tasks: they're under their own heading below.
            val open = state.tasks.filter { it.source == source && it.isOpen && !it.hidden }.sortedWith(compareBy(nullsLast()) { it.dueAt })
            val expanded = source.name !in folded
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:119-124`):

```kotlin
private fun TaskTile(task: TaskItem, index: Int, count: Int, now: Long, zone: java.time.ZoneId, actions: TileActions) {
    val tick = actions.tick
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val overdue = task.isOpen && task.dueAt?.let { it < now } == true
    val hiddenUntil = task.availableFrom?.takeIf { it > now }
    val followUp = task.isOpen && task.extra[Merge.EXTRA_FOLLOW_UP] == "true"
```

- **Current behaviour:** Tasks are grouped by source and sorted by due date; each task expands inline with its own expansion state. Finished work and user-hidden work have separate folded groups.

- **Description:** There is no search across titles, class, sender or source detail, and no direct filter for a dated follow-up, dropped AI plan, or a specific class.

- **Trigger/opportunity:** Finding a task mentioned in a notification or checking one archived email with outstanding dated blocks.

- **Impact/benefit:** Faster retrieval with existing task data and fewer source-to-source scans. Acceptance: fixtures can be found by title/class/sender and active filters are clearly resettable.

- **Recommended change:** Add local search, chips for source/class/deadline/follow-up/warning, and a stable detail route from notifications. Keep current source-grouped default and preserve expansion/scroll state when opening a task.

- **Trade-offs:** Do not surface AI-hidden informational mail in the default list against the documented preference; any optional classification-audit view needs an explicit product decision.

- **Effort:** M

<a id="imp-p2-011"></a>

### IMP-P2-011 — Give free-time use a lightweight, actionable countdown

- **ID:** `IMP-P2-011`

- **Priority and category:** P2; blocking UX / behavior support.

- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:264-324`, `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:213-218`, `app/src/main/java/com/thomaswcode/decrastination/block/Credit.kt:13-30`.
- **Excerpt:**

```kotlin
val left = graph.focus.creditLeftMs() - (SystemClock.elapsedRealtime() - since)
...
stopSpending()
lookAtScreen()
```

- **Current behaviour:** The ledger spends time correctly in the foreground or a side window and covers the target at exhaustion. The remaining-time explanation is shown once the block screen appears.
- **Description:** A working spending mechanism can still be improved by making the last minute predictable, so the user can stop a clip or save context before the abrupt cover.
- **Trigger/opportunity:** Credit is being spent on a blocked app/site, especially in PiP or split screen.
- **Impact/benefit:** Fewer surprise interruptions and clearer connection between completed work and earned time. Evaluate opt-in warning usefulness and dismissal frequency, not presumed behavioral effects.
- **Recommended change:** Provide an optional unobtrusive countdown/status and one warning near exhaustion, with “Return to work” opening the actual next task. Use the same authoritative remaining-time calculation and never grant extra credit by dismissing it.
- **Trade-offs:** Overlay clutter and accessibility conflict are real risks; default to a small notification or infrequent warning, not another constantly visible screen element.
- **Effort:** M.

<a id="imp-p2-012"></a>

### IMP-P2-012 — Preserve a working parent authenticator when rearming

- **ID:** `IMP-P2-012`

- **Priority and category:** P2; parent-code UX / credential lifecycle.

- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:116`, `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:265-283`, `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:314-355`.
- **Excerpt:**

```kotlin
if (Watchdog.isAdminActive(this)) {
    step = Step.ParentCode
}
...
graph.secrets.put(Secret.TotpSecret, Totp.base32(secret))
...
TextButton(onClick = { scope.launch { graph.secrets.put(Secret.TotpSecret, null); onDone() } }) { Text("Skip: no parent code") }
```

- **Current behaviour:** Rearming always enters a new-secret enrollment dialog, even if `hasParentCode` is true. The choices replace the secret after a fresh scan or delete it with Skip.
- **Description:** Temporary disarming need not require reenrolling a parent's authenticator or silently making the existing entry obsolete. A working existing credential is valuable state.
- **Trigger/opportunity:** Rearm after maintenance or an intentionally approved change.
- **Impact/benefit:** Fewer setup steps and obsolete authenticator entries while keeping parent involvement explicit.
- **Recommended change:** If a secret exists, show “Keep current parent authenticator” with a code verification, plus explicit Rotate and Remove paths. Require the existing parent authorization or the established delayed-disarm policy for credential changes when appropriate.
- **Trade-offs:** Recovery from a genuinely lost authenticator still needs a clearly documented route; retaining credentials must not bypass the chosen parent-consent model.
- **Effort:** M.

<a id="imp-p2-013"></a>

### IMP-P2-013 — Explain rewards with a local transaction history

- **ID:** `IMP-P2-013`

- **Priority and category:** P2; trust / data observability.

- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/Credit.kt:13-30`, `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:249-259`, `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:350-376`, `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:426-459`, `app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt:69-76`.
- **Excerpt:**

```kotlin
data class Credit(val day: String = "", val earnedMs: Long = 0, val spentMs: Long = 0) {
...
credit = if (earns) state.credit.earn(today, Credit.forSession(session.minutes, ratio)) else state.credit.on(today),
```

- **Current behaviour:** Credit stores daily totals. Rich session/completion records exist elsewhere, but the exact ratio, amount credited, denial reason and spend interval are not stored as a reward transaction.
- **Description:** A user cannot reliably reconstruct why an early stop earned nothing, why confirmed work earned only a remainder, or how midnight affected a late reward. Totals alone make future reward-policy experiments difficult to audit.
- **Trigger/opportunity:** The user opens “Why am I blocked?” or disputes a free-time balance after mixed timer/photo/source completion.
- **Impact/benefit:** Explain every balance change and allow accounting invariants to be checked against a compact log. No new sensitive browsing content is needed.
- **Recommended change:** Add bounded immutable credit transactions with event ID, amount, source work record, applied ratio and exclusion reason. Show a daily “Earned / Used / Expired” breakdown linked to its task, while aggregating spending without URL paths.
- **Trade-offs:** Retention/storage cost and migration complexity; avoid retaining browsing history simply to explain time totals.
- **Effort:** M.

<a id="imp-p2-014"></a>

### IMP-P2-014 — Add proportional image validation and review before paid photo checks

- **ID:** `IMP-P2-014`

- **Priority and category:** P2; photo workflow / AI efficiency.

- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/PhotoChecks.kt:25-55`, `app/src/main/java/com/thomaswcode/decrastination/block/PhotoChecks.kt:65-77`, `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:133-149`.
- **Excerpt:**

```kotlin
val bitmap = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
val scale = PhotoChecker.MAX_SIDE.toDouble() / maxOf(bitmap.width, bitmap.height)
val sized = if (scale < 1) bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) else bitmap
```

- **Current behaviour:** A captured image is decoded, shrunk and JPEG encoded immediately, then the model is asked; feedback requests a clearer image only after a paid/failed check. No preview/crop/orientation or quality feedback appears in the caller.
- **Description:** Better input preparation can improve a feature that already works while avoiding preventable calls. This recommendation does not claim a measured model-accuracy gain or assume every camera emits rotated pixels.
- **Trigger/opportunity:** Photograph handwriting under poor lighting or with unrelated surrounding pages.
- **Impact/benefit:** A clearer, task-specific submission and fewer unusable images sent. Evaluate retake rate, rejected-image rate, latency and cost per successful accepted piece.
- **Recommended change:** Offer a concise preview with selected task/step, rotate/crop/retake controls, and cheap checks for unreadable dimensions or gross blur before transmission. Respect EXIF orientation when decoding and dispose of source/derived image resources after the check. Keep raw photos local and ephemeral as today.
- **Trade-offs:** Extra UI must remain optional for a good capture; blur thresholds need evaluation and should not reject readable handwriting automatically.
- **Effort:** M.

<a id="imp-p2-015"></a>

### IMP-P2-015 — Let weekly adaptation propose an understandable trial before it changes routines

- **ID:** `IMP-P2-015`

- **Priority and category:** P2; Learning UX/user control.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt:102-107,121-123`; `app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt:26-39`; `app/src/main/java/com/thomaswcode/decrastination/learn/CheckIns.kt:132-137`.
- **Excerpt** (`Review.kt:102-107`, 6 lines):
  ```kotlin
  val answer = result.answer ?: return Outcome(why = if (result.refused) "Claude declined to review it" else "Claude's answer couldn't be used")
  val changes = answer.changes.filter(ReviewInput::allowed)
  // Laid over what's been asked for, so a change waiting elsewhere keeps its wait.
  if (changes.isNotEmpty()) graph.changeSettings { ReviewInput.apply(it, changes) }
  val note = answer.note.ifEmpty { listOf("No note this week.") }
  return Outcome(WeeklyReview(now, note + changeLines(changes, graph.settings.value), by = reviewer.model))
  ```
- **Current behaviour:** allowed model changes immediately enter the settings mutation/delay path; a review later states what changed. Bounds are enforced, but the user sees no before/after workload preview or trial evaluation.
- **Description:** preserve the useful automatic review while giving intentional control over experiments such as reward ratio and session length.
- **Impact/benefit:** each proposal shows old/new value, evidence, expected plan effect and whether protection delays it; the user can keep, decline or time-limit it. A subsequent review compares the stated outcome to observed results and can suggest reverting through the same protection path.
- **Recommended change:** persist review proposals separately, add an opt-in automation policy, and apply accepted/trusted experiments with start/end markers. Avoid changing multiple coupled variables without a way to attribute results.
- **Trade-offs:** additional taps can reduce follow-through; offer a concise default and optional automation. Observational data does not prove causality.
- **Effort:** **M**.

- **Trigger/opportunity:** Run the weekly review when it recommends changing reward ratio, session length or available hours.

<a id="imp-p2-016"></a>

### IMP-P2-016 — Show confidence and sample quality in learned estimates

- **ID:** `IMP-P2-016`

- **Priority and category:** P2; Learning/data quality.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt:19-24,59-74,129-154`; `app/src/main/java/com/thomaswcode/decrastination/core/Plan.kt:99-108`; `app/src/main/java/com/thomaswcode/decrastination/learn/Stats.kt:96-109`.
- **Excerpt** (`Calibrator.kt:63-68`, 6 lines):
  ```kotlin
  for (key in listOf(Calibration.key(record.kind, record.className), Calibration.key(record.kind, null)).distinct()) {
      var m = result[key] ?: 1.0
      // Timed, and all of it: with blocks ticked by hand, its minutes aren't how long it took.
      val timed = record.workedMin > 0 && !record.byHand
      if (timed) m = (1 - WEIGHT) * m + WEIGHT * (record.workedMin.toDouble() / record.estimateMin)
      val nudge = if (timed) NUDGE_WITH_TIME else NUDGE_ALONE
  ```
- **Current behaviour:** one accepted duration can establish a class multiplier; an exponentially weighted value is shown without sample count, recency or uncertainty. Box selection settles after a total count even when evidence for individual alternatives is sparse.
- **Description:** make automatic calibration proportional to its evidence and allow users to understand or reset learning when their habits/course change.
- **Impact/benefit:** Stats shows accepted sample count, excluded reasons and recent error range per class; sparse classes shrink toward their kind baseline; a single extreme sample cannot dominate a new class. Replay is deterministic, including reset/epoch markers.
- **Recommended change:** retain sufficient sample-quality summaries, weight by recency/coverage, require minimum evidence for large changes, and report held-out prediction error. [BUG-P2-016](BUGS-AND-INCONSISTENCIES.md#bug-p2-016) separately addresses the concrete mixed-photo contamination.
- **Trade-offs:** conservative learning adapts more slowly; richer statistics can imply false precision. Use simple interpretable ranges and documented sample inclusion rules.
- **Effort:** **M**.

- **Trigger/opportunity:** A new class has only a few observations, an outlier completion arrives, or study habits change.

<a id="imp-p2-017"></a>

### IMP-P2-017 — Version enrichment recipes and expose their provenance

- **ID:** `IMP-P2-017`

- **Priority and category:** P2; AI reproducibility/cache design.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt:14-40,142-168`; `app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt:135-159`.
- **Excerpt** (`Enrichment.kt:166-168`, 3 lines):
  ```kotlin
  fun stale(task: TaskItem, modelOn: Boolean, rules: String, modelOff: Boolean = false): Boolean {
      val e = task.enrichment ?: return true
      return e.inputHash != inputHash(task) || (modelOn && e.by == rules) || (modelOff && e.by != rules)
  ```
- **Current behaviour:** staleness follows task content and rules/model enablement. It does not encode prompt/schema/parser version or relevant user context. Historic decisions do not reveal which recipe produced them.
- **Description:** make changes to interpretation reproducible, selectively refresh affected tasks after recipe changes, and show why a classification or date was inferred.
- **Impact/benefit:** an unchanged task reuses an unchanged recipe; changing its job's recipe invalidates only relevant entries; the source text/date and recipe version behind a visible deadline can be inspected. [BUG-P3-001](BUGS-AND-INCONSISTENCIES.md#bug-p3-001) separately fixes inaccurate actual-model authorship.
- **Recommended change:** add optional recipe/prompt/schema version fields, context fingerprint and compact evidence/rationale, and include them in freshness rules. Use selective migrations, explicit refresh and a queue budget so an app upgrade does not unexpectedly reread every item at once.
- **Trade-offs:** refreshes cost time/money and can move existing plans. Preserved completed-step identity needs care when a new recipe restructures work.
- **Effort:** **M**.

- **Trigger/opportunity:** Upgrade the app with a changed prompt/parser while existing task text remains unchanged.

<a id="imp-p2-018"></a>

### IMP-P2-018 — Preserve typed sync diagnostics and a small redacted history

- **ID:** `IMP-P2-018`

- **Priority and category:** P2; observability / recovery experience.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:99-111,135-140`; `app/src/main/java/com/thomaswcode/decrastination/data/TaskState.kt:29-40`; `app/src/main/java/com/thomaswcode/decrastination/debug/CommandActivity.kt:301-310`.
- **Excerpt** (`Syncer.kt:135-140`, six lines):

```kotlin
private suspend fun failed(source: Source, at: Long, why: String): SyncReport {
    tasks.update { current ->
        val previous = current.status(source)
        current.copy(sources = current.sources + (source to previous.copy(lastAttemptAt = at, error = why)))
    }
    return SyncReport(failures = mapOf(source to why))
```

- **Current behaviour:** Persistent source status stores one freeform exception message, last attempted/success times and a note. Full failures and task titles are available mainly through adb logging; a later success overwrites the error.
- **Description:** The app cannot explain whether a stale source is missing permission, offline, authentication-rejected, schema-invalid, timed out, or waiting for bodies without parsing prose/logcat. It also loses the recent history needed to diagnose intermittent failures.
- **Trigger/opportunity:** A user sees outdated tasks after intermittent provider failure or a maintainer needs a reproducible report without accessing their inbox.
- **Impact/benefit:** More direct repair guidance and shareable diagnostics with less personal task text. Measure resolution steps and ensure exported reports contain no credentials or source content.
- **Recommended change:** Introduce bounded typed outcomes with duration, item counts, pending-body count, completeness flags and a correlation ID; keep a small redacted ring buffer. Map each outcome to a concrete setup/retry/open-provider action, while retaining a sanitized technical detail field for diagnosis.
- **Trade-offs:** Define retention and redaction carefully, and keep diagnostics cheap enough not to cause additional JSON write churn. Avoid collecting mailbox content merely to improve logging.
- **Effort:** M.

<a id="imp-p2-019"></a>

### IMP-P2-019 — Offer notification controls by purpose and sensitivity

- **ID:** `IMP-P2-019`

- **Priority and category:** P2; notifications / user experience / privacy.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/notify/Channels.kt:22-32`; `app/src/main/java/com/thomaswcode/decrastination/notify/Notify.kt:20-27,31-44`.
- **Excerpt** (`Channels.kt:28-32`, five lines):

```kotlin
NotificationChannel(DAILY, "Plan and check-ins", NotificationManager.IMPORTANCE_DEFAULT).apply {
    description = "The morning briefing, how finished work went, calendar questions, and the Sunday check-in"
},
NotificationChannel(MODEL, "Claude", NotificationManager.IMPORTANCE_DEFAULT).apply {
    description = "When Claude's plan for a task is dropped, or its API key stops working"
```

- **Current behaviour:** Multiple notification jobs share broad channels. Turning off the DAILY channel suppresses the briefing, completion assessments, calendar questions and weekly check-in together. `Notify.shown` can distinguish channel suppression but provides no user-level delivery preference itself.
- **Description:** Separate purposes let the user keep actionable calendar questions while silencing routine briefings, and protect task/email details on the lock screen without hiding protection failures.
- **Trigger/opportunity:** A user wants fewer interruptions, sensitive school/mail titles kept private, or a scheduled digest rather than repeated questions.
- **Impact/benefit:** Better control over interruptions and sensitive content while retaining important requests. Evaluate channel usability and undelivered-question counts; no claim is made that current notifications bypass Android permissions.
- **Recommended change:** Split action-required prompts from routine summaries, add a concise-content/lock-screen preference applied consistently by the notification builders, and connect actionable prompts to the unresolved-question workflow in [IMP-P2-007](IMPROVEMENTS.md#imp-p2-007). That separate proposal owns persistence and answer scope. Keep protection notifications separately configurable as they already are.
- **Trade-offs:** More channels can overwhelm settings; Android channel properties persist, so migration must be deliberate and cannot silently override prior user choices.
- **Effort:** M.

<a id="imp-p2-020"></a>

### IMP-P2-020 — Reuse Gmail envelope metadata while still reconciling the complete inbox

- **ID:** `IMP-P2-020`

- **Priority and category:** P2; performance / battery efficiency.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:211-217`; `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Imap.kt:220-239`.
- **Excerpt** (`GmailSource.kt:211-215`, five lines):

```kotlin
val count = imap.examine("INBOX")
val uids = if (count == 0) emptyList() else imap.uidSearch("ALL").sorted()
val messages = uids.chunked(FETCH_BATCH).flatMap { batch ->
    imap.uidFetch(batch, "UID INTERNALDATE X-GM-MSGID X-GM-THRID X-GM-LABELS ENVELOPE").mapNotNull(GmailThreads::message)
}
```

- **Current behaviour:** Every 15-minute refresh fetches all inbox envelopes/labels even when every message is unchanged; only body text is cached.
- **Description:** Keep the full UID membership search that safely identifies removals, but cache stable subject/from/internal-date/thread metadata and fetch it only for new IDs. Mutable labels can be refreshed separately as needed.
- **Trigger/opportunity:** An inbox grows beyond the current small fixture-shaped workload, or stays mostly unchanged between reads.
- **Impact/benefit:** Fewer transferred bytes, parser allocations and network requests per ordinary sync. Validate with command counts/bytes for unchanged versus newly arrived mail, not an assumed battery percentage.
- **Recommended change:** Persist UIDVALIDITY and a per-account/mailbox metadata cache, invalidate correctly when it changes, remove absent membership only after a complete search, and use bounded batches for new metadata. Avoid a SINCE-only approach that would lose older membership and falsely complete tasks.
- **Trade-offs:** More persistent state and mailbox identity rules; mutable label handling cannot blindly reuse old values. This is separate from [BUG-P2-019](BUGS-AND-INCONSISTENCIES.md#bug-p2-019)'s bounded-progress correctness fix.
- **Effort:** M.

<a id="imp-p2-021"></a>

### IMP-P2-021 — Preserve the chance to interpret email archived before its body was fetched

- **ID:** `IMP-P2-021`

- **Priority and category:** P2; Data collection completeness.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:143-153`; `app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt:129-132`; `app/src/test/java/com/thomaswcode/decrastination/core/MergeTest.kt:169-174`.
- **Excerpt** (`MergeTest.kt:169-174`, 6 lines):
  ```kotlin
  @Test
  fun `an email archived before its text was read as far as reads go isn't kept for its reading`() {
      // Stored before how far it was read was kept: half an email can't be asked about.
      val cut = Merge.apply(emptyList(), Source.Gmail, listOf(fetched("t6")), t0).tasks.map { it.copy(extra = it.extra - GmailThreads.EXTRA_TEXT_READ_TO) }
      assertEquals(Status.Done, Merge.apply(cut, Source.Gmail, emptyList(), later, unread = { true }).tasks.single().status)
  }
  ```
- **Current behaviour:** postfetch enrichment can keep an archived email for future blocks, but an email whose body is not fully fetched is deliberately considered done when it leaves the inbox. This is an explicit existing rule, not an accidental test regression.
- **Description:** an initial large inbox/read backlog can lose the opportunity to discover a future obligation if the user archives quickly.
- **Impact/benefit:** an observed message archived while awaiting its body can still be fetched read-only and produce future follow-ups; an actually deleted/unavailable message reaches a visible terminal state without endless retries.
- **Recommended change:** retain a bounded queue of observed thread/message identifiers requiring completion, hydrate those separately from current INBOX enumeration where provider access permits, and run the existing follow-up decision only after hydration or an explicit terminal outcome.
- **Trade-offs:** this reads some archived mail and expands retention; disclose the narrowly bounded queue and provide deletion/forget controls. Provider UID changes need stable identity handling.
- **Effort:** **M**.

- **Trigger/opportunity:** Archive an observed email before a backlog or first sync has fetched its body.

<a id="imp-p2-022"></a>

### IMP-P2-022 — Add offline end-to-end provider contract replays

- **ID:** `IMP-P2-022`

- **Priority and category:** P2; testing architecture / developer tooling.
- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/ImapTest.kt:63-107`; `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/GmailTest.kt:108-207`; `app/src/test/java/com/thomaswcode/decrastination/sources/teams/TeamsRowsTest.kt:18-29`; `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:188,243-254`; `scripts/gmail_probe.py:68-104`; `scripts/powerplanner_probe.py:39-52`.
- **Excerpt** (`ImapTest.kt:63-67`, five lines):

```kotlin
/** A scripted server: what it answers, and what the client sent. */
private class Script(answers: String) {
    val sent = ByteArrayOutputStream()
    val client = ImapClient(ByteArrayInputStream(answers.replace("\n", "\r\n").toByteArray()), sent)
    val commands: List<String> get() = sent.toString().trimEnd().split("\r\n")
```

- **Current behaviour:** Pure parsers have useful canned tests and Power Planner has a fake HTTP transport. GmailSource constructs its own TLS socket; provider tests create expected maps rather than exercising real cursor adapters. Probe scripts validate live accounts but are not repeatable offline workflow tests.
- **Description:** A reusable source-to-merge replay harness could validate ordering, cancellation, partial success, authentication errors, body backlog and idempotent task reconciliation together, instead of relying on parser success plus manually recorded phone experiments.
- **Trigger/opportunity:** A source protocol/provider version changes or a parser fix affects whether tasks are marked done, missed, hidden or enriched.
- **Impact/benefit:** Deterministic regression protection for core data-loss boundaries without requiring private accounts or a connected phone. Count protected failure scenarios, not simply added test methods.
- **Recommended change:** Inject the IMAP connection/session factory and provider gateways; capture only sanitized transport shapes; replay full multi-sync sequences through Syncer/Merge. Include partial listing, disappearing messages, malformed fields, repeated timeout, account switch and schema change fixtures. Keep device smoke checks as a separate small acceptance list.
- **Trade-offs:** Transport abstractions must remain small and avoid reproducing the implementation in fake expectations; a replay still cannot certify Android permission/lifecycle behavior.
- **Effort:** M.

<a id="imp-p2-023"></a>

### IMP-P2-023 — Separate orchestration responsibilities and publish a consistent plan snapshot

- **ID:** `IMP-P2-023`

- **Priority and category:** P2; Architecture / consistency / performance.

- **Status:** Recommended (supported by code analysis).

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:93-107`; `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:136-166`; `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:336-375`; `app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:56-69`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:93-98`):

```kotlin
    val clock: WallClock = DeviceClock(app)

    /** Work that outlives the screen or receiver that started it; a failure is logged, not fatal. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> Log.e(TAG, "Background work failed", error) })

    val tasks = JsonStore(File(app.filesDir, "tasks.json"), TaskState.serializer(), ::TaskState)
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:136-141`):

```kotlin

    fun plan(state: TaskState = tasks.value, settings: Settings = this.settings.value, now: Long = clock.now()): Plan {
        val zone = clock.zone()
        // Your instructions' days and times, over as far as the plan can reach.
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val applied = instructions.value.applied
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:336-341`):

```kotlin
    init {
        // Disarmed (once the wait is over, or at once by a parent's code): the device admin goes
        // too, so uninstalling is allowed again, as the protection screen says. Checked at start as
        // well as on each change, so a disarm the app stopped before seeing through is finished,
        // and so is an arming left part-way (the admin given, then the app stopped mid-wizard).
        scope.launch {
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:56-61`):

```kotlin
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
    // The plan moves with the clock as well as the data: deadlines pass, the evening runs out.
    val now by produceState(graph.clock.now()) {
        while (true) {
```

- **Current behaviour:** AppGraph combines store construction, settings transactions, provider observers, enrichment, instruction application, backup and UI refresh coordination. Views call graph.plan while it also reads instructions, calibration, calendar and the activity log outside the task/settings parameters.

- **Description:** The composition root is 911 lines and the effective plan has more inputs than each caller memoizes. This makes cross-store consistency, stale derivations and deterministic orchestration tests harder to reason about.

- **Trigger/opportunity:** Adding a data source, making instructions recoverable, changing calibration, or testing a workflow involving several stores.

- **Impact/benefit:** A single traceable input revision per plan and easier tests for orchestration without Android. Compare planner invocation counts and revision consistency before claiming a performance gain.

- **Recommended change:** Extract settings, instruction, enrichment and recovery coordinators behind the current public operations; retain AppGraph for wiring. Build an immutable PlanInputs snapshot with explicit clock/zone, task, settings, instruction, calendar, calibration and worked-today revisions, then share a derived plan state among UI/widget/blocking consumers.

- **Trade-offs:** Avoid a large rewrite or introducing a dependency-injection framework solely for style. Migration must preserve current journals, mutex order, immediate blocking decisions and time-bound invalidation.

- **Effort:** L

<a id="imp-p2-024"></a>

### IMP-P2-024 — Coordinate Android enforcement through a small explicit event processor

- **ID:** `IMP-P2-024`

- **Priority and category:** P2; architecture / maintainability.

- **Status:** Recommended (supported by code analysis).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:54-139`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:192-243`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:501-509`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:606-673`, `app/src/main/java/com/thomaswcode/decrastination/protect/Watchdog.kt:155-201`.
- **Excerpt:**

```kotlin
closeBlockedPictureInPicture()
coverBlockedSideWindows()
frontPackage()?.let { onFront(it, firstLook = false) }
```

- **Current behaviour:** One 750-line service manages browser memories, PiP promotion, side-window handling, credit, guard debounce, countdowns, sessions, settings and watchdog scheduling. Each path can reread windows and current policy.
- **Description:** The implementation works across many features but mixes observations and side effects. One event snapshot could yield one planned action, making precedence and event coalescing easier to understand. This is a design opportunity beyond the specific missing tests in [BUG-P2-025](BUGS-AND-INCONSISTENCIES.md#bug-p2-025).
- **Trigger/opportunity:** Add another browser adapter or window mode, tune responsiveness, or integrate new policy signals.
- **Impact/benefit:** Reduce duplicate accessibility traversal and make rules explicit for the combination of active, PiP and side windows. Measure duplicate reads/actions per event and maintain action equivalence before changing behavior.
- **Recommended change:** Build a bounded `ScreenObservation` from Android once, pass it plus policy state to a pure coordinator, then execute ordered actions through an Android adapter. Give delayed actions a connection/session token so stale work can be invalidated without scattered mutable flags.
- **Trade-offs:** A large rewrite is unnecessary and risky; extract one path at a time, keeping the currently tested pure Focus/policy layer.
- **Effort:** L.

<a id="imp-p2-025"></a>

### IMP-P2-025 — Make builds reproducible and automate focused dependency maintenance

- **ID:** `IMP-P2-025`

- **Priority and category:** P2; Build / maintenance.

- **Status:** Recommended (supported by code analysis).

- **Locations:** `gradle/wrapper/gradle-wrapper.properties:1-7`; `settings.gradle.kts:15-21`; `.github/workflows/ci.yml:24-45`.

- **Excerpt** (`gradle/wrapper/gradle-wrapper.properties:1-6`):

```text
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
```

- **Excerpt** (`settings.gradle.kts:15-20`):

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
```

- **Excerpt** (`.github/workflows/ci.yml:24-29`):

```text
          java-version: 17

      - uses: gradle/actions/setup-gradle@v6

      - name: Check the probe scripts compile
        run: python3 -m py_compile scripts/*.py
```

- **Current behaviour:** Direct versions are centralized and CI runs tests, lint and APK assembly. The wrapper URL has no distribution checksum; there is no committed resolved dependency lock/verification metadata or automated advisory step.

- **Description:** A version catalog does not capture the complete resolved graph or verify downloaded bytes. The successful local build and live advisory matches provide a concrete reason to make dependency changes reviewable.

- **Trigger/opportunity:** Reproducing a build on a new PC, updating the Anthropic SDK, or investigating newly published advisories.

- **Impact/benefit:** Reviewable graph changes and earlier detection of maintenance exposure. Acceptance: a clean supported build resolves the approved graph, rejects unexpected artifact changes and reports actionable advisory deltas.

- **Recommended change:** Add the official Gradle distribution SHA-256, dependency verification/locking appropriate to AGP, and a focused advisory job that records applicability decisions. Pin CI actions to reviewed revisions with an update mechanism. Keep builds on the documented Android-compatible dependency family.

- **Trade-offs:** Locks and checksums need a deliberate update workflow; upstream advisories require triage instead of failing on every unexploitable transitive match. This is a process enhancement; the current advisory matches are the separate dependency finding.

- **Effort:** M

<a id="imp-p2-026"></a>

### IMP-P2-026 — Match AI resources and queue policy to each job

- **ID:** `IMP-P2-026`

- **Priority and category:** P2; AI performance/cost/architecture.
- **Status:** Exploratory (requires evaluation or validation).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:34-51`; `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt:24-40`; `app/src/main/java/com/thomaswcode/decrastination/enrich/PhotoChecker.kt:33-58`; `app/src/main/java/com/thomaswcode/decrastination/enrich/InstructionReader.kt:263-277`; `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:504-516,754`.
- **Excerpt** (`ClaudeReviewer.kt:35-40`, 6 lines):
  ```kotlin
  val params = MessageCreateParams.builder()
      .model(model)
      .maxTokens(ClaudeEnricher.MAX_TOKENS)
      .system(ReviewInput.SYSTEM)
      .addUserMessage(week)
      .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).format(JsonOutputFormat.builder().schema(schema).build()).build())
  ```
- **Current behaviour:** all jobs request the same model/high effort/16k output allowance, use separate client construction, and share serialized model-call access. A short interactive instruction or photo can wait behind background work.
- **Description:** retain deterministic planning and validation while evaluating lighter resources for straightforward triage/estimates, stronger reasoning only for ambiguous jobs, and interactive-priority scheduling.
- **Impact/benefit:** compare median/tail interactive wait, cost per accepted decision and semantic evaluation results before/after; adopt a route only when the evaluation set preserves obligation/date accuracy. Active budget accounting remains serialized and correct.
- **Recommended change:** introduce a shared transport/client lifecycle keyed to credential/endpoint, typed job envelopes, per-job input/output limits and queue priority. Route uncertain results to a stronger model or explicit review after evaluating alternatives. [BUG-P2-015](BUGS-AND-INCONSISTENCIES.md#bug-p2-015) separately covers the mandatory cap-bound fix.
- **Trade-offs:** cheaper/lower-effort routes can reduce accuracy and retries may erase savings; concurrent requests complicate reservations. Do not promise measured savings before the experiment.
- **Effort:** **L**.

- **Trigger/opportunity:** An interactive instruction or photo request waits while background enrichment/review occupies model access.

<a id="imp-p2-027"></a>

### IMP-P2-027 — Hydrate full Power Planner instructions only when an item needs them

- **ID:** `IMP-P2-027`

- **Priority and category:** P2; data collection / AI quality.
- **Status:** Exploratory (requires endpoint-contract and content-coverage validation).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt:45-53,131-139`; `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItems.kt:48`; `docs/data-sources.md:75`.
- **Excerpt** (`PowerPlannerApi.kt:131-135`, five lines):

```kotlin
data class PpItem(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Name") val name: String = "",
    @SerialName("ShortDetails") val details: String? = null,
    @SerialName("Date") val date: String? = null,
```

- **Current behaviour:** All items use the agenda's `ShortDetails` as their entire task detail; no full-detail endpoint is called.
- **Description:** The repository documents `/GetHomework` and `/GetExam` returning full `Details`, but that behavior and the practical difference from agenda `ShortDetails` were not independently verified here. A selective second stage could provide richer instructions for new/changed/high-priority items if that difference is confirmed. This is an exploratory enhancement, not a claim that the two current empty-details fixture items were truncated.
- **Trigger/opportunity:** An assignment contains long instructions, attachment descriptions or a checklist that the agenda summary omits.
- **Impact/benefit:** More grounded task splits, estimates and vocabulary links, with fewer invented steps. Measure omitted-instruction recovery and model-plan correctness on a sanitized corpus before claiming a numeric gain.
- **Recommended change:** First compare sanitized agenda and detail responses for representative items and confirm endpoint contracts, access and change signals. If material extra instructions are available, add hydration keyed by source ID and a validated modification/content signal, cache full responses, keep agenda reconciliation separate from hydration, and indicate pending/missing detail. Prioritize due work and retain a read-only endpoint policy.
- **Trade-offs:** Additional requests against the shared development key, cache invalidation and provider schema maintenance. The exact remote payload/limits must be verified before implementation.
- **Effort:** M.

<a id="imp-p2-028"></a>

### IMP-P2-028 — Measure and reduce startup storage work before state grows

- **ID:** `IMP-P2-028`

- **Priority and category:** P2; Startup / resource efficiency.

- **Status:** Exploratory (requires startup measurement with representative retained data).

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/DecrastinationApp.kt:10-17`; `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:100-111`; `app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt:28-38`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/DecrastinationApp.kt:10-15`):

```kotlin
    override fun onCreate() {
        super.onCreate()
        // The channels first: the graph may show a waiting alert as it starts.
        Channels.create(this)
        AppGraph.get(this)
        SyncWorker.schedule(this)
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:100-105`):

```kotlin
    val secrets = SecretStore(File(app.filesDir, "secrets.bin"), KeystoreCipher())
    val runtime = JsonStore(File(app.filesDir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState)
    val log = JsonStore(File(app.filesDir, "log.json"), ActivityLog.serializer(), ::ActivityLog)

    /** Your instructions about tasks, events and days, and what Claude read them as ([Instructions]). */
    val instructions = JsonStore(File(app.filesDir, "instructions.json"), InstructionState.serializer(), ::InstructionState)
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt:28-33`):

```kotlin
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
    /** Tidies a state as loaded: drops what has expired, fixes what a crash left half done. */
    private val onLoad: (T) -> T = { it },
```

- **Current behaviour:** Application startup constructs all JsonStore instances and the encrypted secret store before scheduling work; stores load their complete JSON state. AppGraph then starts multiple reconciliation collectors.

- **Description:** Startup reads up to 120 days of activity history plus retained tasks and instructions. Synchronous construction couples launching a UI, receiver or worker to reading every store. No startup benchmark was run, so this is a measurement-led recommendation rather than a proven jank defect.

- **Trigger/opportunity:** Cold launch after months of activity, boot receivers, or low-storage/slow-device operation.

- **Impact/benefit:** An explicit cold-start and storage-size budget, with less unnecessary loading if measurements identify it. Track launch time, bytes decoded and planner work using representative synthetic histories.

- **Recommended change:** Add lightweight local timing around loading and first usable state, then move nonessential history hydration to IO or use segmented history where evidence warrants. Keep a clear loading/error state; initialize current blocking policy before declaring the app ready.

- **Trade-offs:** Async initialization must not create a window where the blocker incorrectly treats uninitialized tasks as an empty workload. Small datasets may not justify segmentation.

- **Effort:** M

## P3

No separate substantive findings at this priority; no minor items were added solely to fill this section.
