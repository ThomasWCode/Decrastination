# Bugs and inconsistencies

Review date: 10 October 2026. Revision: `d1eefaa9948ed7ccb9d814c3113a104fa5fc4bec`. [Scope, checks, coverage and summary tables](README.md).

These are review findings only; none of the recommended changes has been implemented. Evidence excerpts are no longer than ten lines each; `...` marks omitted code. Line references refer to the reviewed revision.

Verified means an executed check confirms the stated result, within its explicit boundary. It does not imply Android/device or live-service reproduction. Confirmed by reading is code-supported behavior; Suspected identifies the additional check needed. Priorities follow the requested release-impact definitions, and conditional recovery or lifecycle cases remain P2.

## P1

<a id="bug-p1-001"></a>

### BUG-P1-001 — Source percentage progress is ignored once an item has steps

- **ID:** `BUG-P1-001`

- **Priority and category:** P1; Planning correctness.
- **Status:** Verified; passing pure JVM probe `source progress is ignored once an item has substeps` reproduces the incorrect result.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:464-481`, `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:450-457`; `app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt:289-294`.
- **Excerpt** (`Planner.kt:474-480`, 7 lines):
  ```kotlin
  var spare = (task.workedMin + task.photoMin - task.subSteps.filter { it.done }.sumOf { if (it.byHand) it.timedMin.toDouble() else it.minutes * multiplier })
      .roundToInt().coerceAtLeast(0)
  return left.map { step ->
      val full = (step.minutes * multiplier).roundToInt().coerceAtLeast(1)
      val off = minOf(spare, (full - MIN_CHUNK).coerceAtLeast(0))
      spare -= off
      Piece(step.title, full - off, from = step.from, due = step.dueAt)
  ```
- **Current behaviour:** tasks without steps use `remaining`, which reconciles source progress with measured work. Tasks with steps skip that calculation and use only the step completion flags and timed/photo minutes.
- **Description:** Power Planner percentage updates reduce its remaining work until AI gives it blocks; the same percentage then has no effect. The branch also ignores `userEffortMin`, although no current editing UI for that field was established, so the release-impact claim concerns source percentage progress.
- **Trigger/opportunity:** use a 60-minute Power Planner item at 50% completion, with no locally timed work. It plans 30 minutes without steps and 60 minutes after two 30-minute AI steps are attached. Advancing source progress on an already split task likewise leaves the old work planned.
- **Impact/benefit:** normal use of Power Planner progress with AI blocks overstates work and blocking pressure, and rewards/planning can disagree about how much was already done.
- **Recommended change:** reconcile source-progress remaining budget with step state and timed work; allocate the nonduplicated remaining budget over unfinished steps without counting source progress and sessions twice. Decide how percentage completion maps to the ordered steps and make this visible where approximate.
- **Trade-offs:** percentages do not identify which individual step was completed; proportional reduction or asking the user has different usability costs. Preserve minimum finish/hand-in behaviour and dates.
- **Effort:** **M**. Acceptance: the example schedules 30 minutes; progress-only syncs, timed-only updates, and both together agree without double subtraction.

<a id="bug-p1-002"></a>

### BUG-P1-002 — Anki count updates subtract completed timed work a second time

- **ID:** `BUG-P1-002`

- **Priority and category:** P1; Planning/data integration.
- **Status:** Verified; passing pure JVM probe `shrinking Anki source steps double deduct old timed minutes` reproduces the incorrect result.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:95-108`; `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:474-480`; `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:248-265,280-284`; `app/src/test/java/com/thomaswcode/decrastination/core/MergeTest.kt:123-132`.
- **Excerpt** (`Merge.kt:106-108`, 3 lines):
  ```kotlin
  // The source's steps, unless they're the same as before: then the ones kept here,
  // with what a session ticked off, until the source's counts catch up (Anki's cards).
  subSteps = f.subSteps?.takeIf { new -> new.map { it.title } != old.subSteps.map { it.title } } ?: old.subSteps,
  ```
- **Current behaviour:** Anki regenerates steps for the cards still unseen. Merge replaces the old step list when its titles change, but retains cumulative `workedMin`. Planner assumes all accumulated minutes not consumed by currently done steps can reduce current unfinished steps.
- **Description:** a source's shrinking list already removed the cards studied during the timer; their timer minutes are then deducted from the next cards. The isolated merge test deliberately verifies the reset to all-false remaining steps, but does not plan the resulting state.
- **Trigger/opportunity:** initial 45 cards produce steps 9,9,3 minutes. Finish the first 9-minute session, then sync 25 remaining cards, producing 9,3-minute steps. `workedMin` remains 9, so Planner schedules 5+3=8 minutes instead of the remaining source estimate of 12.
- **Impact/benefit:** Anki homework sessions become shorter than the work represented by their new-card counts and pressure is understated as additional days are synced.
- **Recommended change:** track the source-count baseline that measured work belongs to, or keep a separate offset for work already reflected by regenerated steps. Preserve total timed history for rewards and calibration while ensuring only unreflected minutes reduce the current source step list.
- **Trade-offs:** simply clearing `workedMin` would damage learning, completion accounting and idempotent rewards; a baseline is needed. Counts can move upward as well as down.
- **Effort:** **M**. Acceptance: the 45→25 sequence schedules 12 minutes; repeated unchanged syncs and delayed source updates do not double count work.

<a id="bug-p1-003"></a>

### BUG-P1-003 — The fallback email classifier silently hides actionable messages

- **ID:** `BUG-P1-003`

- **Priority and category:** P1; correctness / task collection.
- **Status:** Verified by offline probes `registrationRequestIsHiddenAsEventWithoutModel` and `automatedActionRequestIsHiddenAsInformation` (both pass asserting the incorrect classification; [recorded probe results](README.md#verification)). No real mailbox was queried.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/EmailRules.kt:51-76`; `app/src/main/java/com/thomaswcode/decrastination/core/Task.kt:150-157`; `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/GmailTest.kt:36-49`.
- **Excerpt** (`EmailRules.kt:61-67`, seven lines):

```kotlin
if (EVENT_WORDS.containsMatchIn(text)) {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    futureDates(text, today).firstOrNull()?.let { day ->
        // Calendar days, not 24-hour steps: the clocks change in October and March.
        return Triage(
            kind = Kind.Event,
            effortMin = Kind.Event.defaultEffortMin,
```

- **Current behaviour:** Any message containing an event keyword such as `registration` and a future date becomes Event. Otherwise any no-reply sender, noise domain or unsubscribe text becomes Info. Both kinds become `justAnEmail`, hence `hidden`, and disappear from planning, blocking and the Tasks list.
- **Description:** Sender style and the presence of a registration date do not prove that no action is required. This changed from merely estimating an email to dropping it from the actionable surfaces. A working model can correct the result later, but the rules are the default and the fallback for unavailable AI.
- **Trigger/opportunity:** With AI unavailable, ingest subject `Registration required`, body `Please complete registration by 12 October 2026.`, from `admissions@example.com`, on 10 October. The result is hidden Event. A message `Please return the consent form tomorrow.` from `noreply@example.com` is Info. Neither example needs a network request.
- **Impact/benefit:** Actual registration and school-form work can disappear without an error or a means to inspect the discarded candidates in normal Tasks. P1 reflects likely real mail, not just malformed input.
- **Recommended change:** Treat broad heuristics as candidates with explicit uncertainty. Preserve Admin when imperative/action cues or deadlines request work; hide only confident informational cases, and expose a reviewable excluded-email list with an override. Add regressions combining event vocabulary or automated senders with required actions.
- **Trade-offs:** A conservative default produces more low-value tasks and needs a compact review surface; simple action-keyword rules still cannot replace classification validation.
- **Effort:** M.

## P2

<a id="bug-p2-001"></a>

### BUG-P2-001 — Backups omit unrecoverable local planning rules and task state

- **ID:** `BUG-P2-001`

- **Priority and category:** P2; Recovery/data-loss weakness.
- **Status:** Confirmed by reading. This is an acknowledged limited backup scope, not a claim that the app promises a complete device backup.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt:10-28`; `app/src/main/java/com/thomaswcode/decrastination/core/Instructions.kt:18-29,105-109`; `app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:147-153`; `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:449-483`.
- **Excerpt** (`Backup.kt:10-14`, 5 lines):
  ```kotlin
  /**
   * What a backup keeps (PLAN.md Phase 6): the settings, the activity log, what the app has learned,
   * and your answers about calendar events: what the sources can't give back. Never the passwords
   * or keys, which stay in this phone's encrypted store, nor the tasks, which the next sync reads
   * again.
  ```
- **Current behaviour:** export covers settings, log, calibration, event answers and spend. It intentionally omits instructions and tasks; the instructions exclusion is documented in `docs/needs-you.md`. Local-only task steps/progress and retained Gmail follow-ups cannot all be reconstructed by rereading sources, especially after the email leaves INBOX.
- **Description:** user-authored recurring limits, busy times, dependencies and task overrides exist only in local stores. Exporting the supported backup and restoring after device loss omits those decisions. Treating all tasks as reproducible source data also loses local progress and retained future email obligations that the source no longer returns.
- **Trigger/opportunity:** save a recurring instruction or task override, export, then import on an empty installation and reconnect sources. InstructionState is absent from the export/import signature. An archived email with a retained future block likewise is absent from both the backup task data and the fresh INBOX source result. These paths were traced statically; no device replacement or account mutation was performed.
- **Impact/benefit:** a phone replacement loses planning decisions and potentially obligations even when the user exported the available backup. The documented limitation and lack of effect during ordinary in-place use support P2 rather than treating this as unexpected routine corruption.
- **Recommended change:** add a versioned local-planning section keyed by stable task IDs, include InstructionState and retained follow-up task content, and reconcile against fresh sources on import. For armed devices, restore through the same policy/delay checks as interactive changes. List the included/excluded data in the export preview.
- **Trade-offs:** backups contain more sensitive task/email content and need explicit scope choices; source ID/account changes require conflict handling. Restoring local decisions must not silently loosen an armed configuration.
- **Effort:** **M**. Acceptance: a round-trip onto an empty state preserves applied recurring limits/busy times, task overrides, manual progress and archived future application blocks; credentials remain excluded.

<a id="bug-p2-002"></a>

### BUG-P2-002 — Backup decoding accepts semantic corruption that later crashes statistics

- **ID:** `BUG-P2-002`

- **Priority and category:** P2; Import validation/data integrity.
- **Status:** Verified; passing pure JVM probe `backup admits negative work that crashes statistics after merge` confirms decoding, merging and the statistics exception; Android document import was not exercised.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt:49-51,94-102`; `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:469-483`; `app/src/main/java/com/thomaswcode/decrastination/learn/Days.kt:105-109`; `app/src/main/java/com/thomaswcode/decrastination/learn/Stats.kt:59-61,85`.
- **Excerpt** (`Backup.kt:49-51`, 3 lines):
  ```kotlin
  /** [text] as one of this app's backups, or null: not JSON, not this app's, or a newer format than this version reads. */
  fun decode(text: String): Backup? = runCatching { json.decodeFromString(Backup.serializer(), text) }.getOrNull()
      ?.takeIf { it.app == Backup.APP && it.format <= Backup.FORMAT }
  ```
- **Current behaviour:** validation covers JSON shape, app name, upper format version and byte limit, then persists settings and (while unarmed) log/calibration without range/date checks.
- **Description:** valid JSON is not necessarily a valid application state. Negative measured minutes survive decoding and merging; `Days.minutesIn` calls `coerceIn(0, negative)` and throws. Other unchecked settings, dates and multipliers can also invalidate planning; no claim is made that every such value currently crashes.
- **Trigger/opportunity:** import a backup containing a recent SessionRecord with `workedMin=-1` while unarmed; open Stats. The pure probe decodes/merges this record and confirms the statistics call throws. It also confirms a negative briefing time is accepted.
- **Impact/benefit:** a damaged or manually edited backup can persist a crash condition. The ordinary backup encoder does not itself produce negative timed records, so this is P2 rather than a normal-use crash claim.
- **Recommended change:** validate a complete candidate backup before any store is changed: supported version range, minute/window bounds, finite bounded calibration, valid dates, nonnegative durations and coherent identifiers. Return specific validation errors; keep the current state intact on failure.
- **Trade-offs:** legacy backups may require explicit migration or repair choices; silently clamping corrupted history would distort learning.
- **Effort:** **M**. Acceptance: the malformed record is rejected before persistence; valid legacy/current backups still round-trip.

<a id="bug-p2-003"></a>

### BUG-P2-003 — A task can disappear indefinitely when daily limits are shorter than its chunks

- **ID:** `BUG-P2-003`

- **Priority and category:** P2; Planning/blocking integration.
- **Status:** Verified; passing pure JVM probe `a task disappears from a plan with enough total time but shorter daily limits` reproduces the empty-plan result; Android enforcement was not exercised.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:338-341,369-387,483-491`; `app/src/main/java/com/thomaswcode/decrastination/core/Plan.kt:74-86`.
- **Excerpt** (`Planner.kt:374-382`, 9 lines):
  ```kotlin
  var from = earliest
  // Not past a run of its task after it, where that's placed: what fits nowhere before
  // then goes unplanned, rather than out of order.
  val until = minOf(horizon, ceilingOf(item) ?: horizon)
  for (i in item.chunks.indices) {
      val day = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.firstOrNull { fits(it, i) } ?: break
      assigned[i] = day
      take(item, day, item.chunks[i].minutes)
      from = day
  ```
- **Current behaviour:** the default 45-minute box splits a 60-minute task into two 30-minute chunks. A recurring 20-minute day limit rejects both on every day; unassigned pieces are omitted from Plan.
- **Description:** the overall horizon has enough available minutes, yet no task is planned, and no unscheduled record is returned. Replanning each day cannot resolve a persistent smaller daily limit. This concerns subdividable time boxes; indivisible real-world steps need a distinct explicit exception.
- **Trigger/opportunity:** one 60-minute task due in ten days; set all days' instruction limits to 20 minutes. The probe observes `ordered=[]`, `next=null`, `pressure=false` despite total capacity exceeding 60 minutes.
- **Impact/benefit:** the main recommendation/widget has no next action, and plan-based blocking pressure disappears while work remains open.
- **Recommended change:** split generic boxes further to fit available capacity, respecting ordering and dates. For truly indivisible steps, return an explicit unscheduled/conflict item and expose it in the main planning flow; do not silently equate no placement with no work.
- **Trade-offs:** extra fragments need stable identity and sensible timer labels; do not automatically split tasks whose semantics require continuity.
- **Effort:** **M**. Acceptance: the example becomes three 20-minute pieces or a visible conflict; no daily limit is exceeded.

<a id="bug-p2-004"></a>

### BUG-P2-004 — Rejected email blocks can hide both the task and its warning

- **ID:** `BUG-P2-004`

- **Priority and category:** P2; AI fallback/data visibility.
- **Status:** Verified; passing pure JVM probe `rejected email blocks can hide the whole task and its dropped warning` verifies the parser-to-plan/alert outcome with synthetic model JSON. No live model response is claimed.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt:223-239`; `app/src/main/java/com/thomaswcode/decrastination/core/Task.kt:154-157`; `app/src/main/java/com/thomaswcode/decrastination/enrich/ModelAlerts.kt:64-66`; existing parser-only expectation `app/src/test/java/com/thomaswcode/decrastination/enrich/EnrichTest.kt:272-285`.
- **Excerpt** (`Prompts.kt:229-236`, 8 lines):
  ```kotlin
  val checked = trusted(steps(t.blocks, now, zone, vocabulary = false, maxMinutes = MAX_EFFORT, dueBy = deadline ?: sourceDue(task), opens = opens, noun = "block"), t.effortMin, t.blocks.size)
  val blocks = checked.steps
  base.copy(
      // Blocks of work make it something to do, whatever else it says.
      kind = if (blocks != null) Kind.Admin else Kind.entries.firstOrNull { it.name == t.kind && it in setOf(Kind.Admin, Kind.Event, Kind.Info) },
      actionableFrom = opens,
      deadline = deadline,
      effortMin = blocks?.sumOf { it.minutes } ?: total,
  ```
- **Current behaviour:** valid action blocks force Admin, even if the same answer says Info. If block validation rejects them, the raw Info/Event classification survives. Hidden emails are excluded from planning and from dropped-plan warnings.
- **Description:** contradictory or malformed AI output does not fall back to the remaining whole estimate visibly. It can erase actionable work from the plan and suppress the warning that the split was dropped. Existing tests validate the local parsing rule but do not cover this outcome through planning and alert selection.
- **Trigger/opportunity:** an Admin email receives `kind=Info`, total 60 and an Apply block whose from date is after its due date. The answer has nonnull `dropped`, but the resulting task is hidden, its plan is empty and `ModelAlerts.standing` returns no warning.
- **Impact/benefit:** specific malformed model answers silently remove obligations. No claim is made that a real provider returned this exact response during review.
- **Recommended change:** keep a conservative visible task/review-needed state when a model answer simultaneously declares action blocks and a non-task classification but fails block validation. At minimum make dropped-plan warnings independent of hidden classification when that classification came from the rejected answer.
- **Trade-offs:** conservative fallback can temporarily show a false-positive task; provide an explicit correction action rather than silently deciding it is informational.
- **Effort:** **M**. Acceptance: the reproduced response cannot silently remove the task and its diagnostic together.

<a id="bug-p2-005"></a>

### BUG-P2-005 — Same-title substeps cannot be independently completed by a session

- **ID:** `BUG-P2-005`

- **Priority and category:** P2; task identity / progress accounting.

- **Status:** Verified (deterministic planner-to-Focus JUnit probe executed in the isolated review run).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:214-217`, `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:329-338`, contrasted with index-aware `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:281-297`; `app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt:93-96`; `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:466-480`; `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:128-143`; `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:176-178`.
- **Excerpt:**

```kotlin
val stepIndex = if (ended.completed && session.step != null && session.whole) t.subSteps.indexOfFirst { !it.done && it.title == session.step } else -1
```

- **Current behaviour:** A session/photo identifies its step by title; finishing selects the first unfinished step of that title. Manual email ticking already uses index plus title to distinguish duplicates.
- **Description:** With two remaining steps titled Practice, starting a session for the second step supplies no stable identity. Completion ticks the first, even when the selected piece's estimate/deadline differs.
- **Trigger/opportunity:** A homework assignment has `Practice(30, ankiSections=[2.2])` then `Practice(60)` and a completed linked Textbook 1::2.2 deck. `Planner.pieces` excludes the first step because the deck holds it; the second 60-minute step is `plan.next`, which the block screen starts. Finishing nevertheless ticks the first unfinished title match. The supplied probe uses this actual planner selection rather than calling an unselectable hypothetical step.
- **Executed evidence:** `BlockingReviewProbe.a session for the later same-title step completes that step` passed its real-plan selection assertions, then failed completion state with expected **[false, true]**, actual **[true, false]**. This proves the normal selected chunk completed the wrong stored step. The baseline existing suite remained green.
- **Impact/benefit:** Incorrect substep completion/deadline pressure when duplicate titles are selectable or steps change during a session.
- **Recommended change:** Carry a stable step ID and task revision through Chunk, FocusSession and PhotoDone; handle stale/missing IDs without falling back to an unrelated matching title.
- **Trade-offs:** A migration must map existing title-only pending sessions conservatively and preserve their worked minutes.
- **Effort:** M.

<a id="bug-p2-006"></a>

### BUG-P2-006 — Concurrent starts can overwrite a focus session without ending it

- **ID:** `BUG-P2-006`

- **Priority and category:** P2; concurrency / session accounting.

- **Status:** Verified (deterministic JUnit probe executed in the isolated review run).
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:161-165`, `app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt:42-47`, `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:174-178`.
- **Excerpt:**

```kotlin
suspend fun startSession(taskId: String, label: String, step: String?, minutes: Int, box: Int? = null): FocusSession {
    stopSession()
    val session = FocusSession(taskId, label, step, minutes.coerceIn(1, MAX_SESSION_MIN), clock.now(), clock.uptime(), box, whole = minutes <= MAX_SESSION_MIN)
    runtime.update { it.copy(session = session) }
    return session
}
```

- **Current behaviour:** Each start separately stops the current session, then saves its replacement. `JsonStore` serializes individual writes, not this compound operation. The Start button launches a coroutine without a pending-start flag.
- **Description:** Two starts can both finish their initial stop before either has stored its new session. Both report success, but the last save silently replaces the other session without its stop record or accounting. Notification/alarm creation then proceeds independently for both returned sessions. This is separate from the correctly guarded concurrent-stop path.
- **Trigger/opportunity:** Queue two `startSession` calls while a runtime-store update is held; release the store. Both see no active session, then both save. Rapid repeated Start taps while persistence is slow provide a UI entry point. The review probe uses gates, not sleeps.
- **Executed evidence:** `BlockingReviewProbe.concurrent session starts preserve the displaced session record` failed with expected session-record count **1**, actual **0**. Both start calls returned; there was no record for the overwritten session. This is an intentionally failing regression assertion against existing code, not a failure in the baseline suite.
- **Impact/benefit:** An acknowledged session disappears, and timing/notification state can describe a different start. P2 because competing starts are a specific condition and immediate double taps typically lose little worked time.
- **Recommended change:** Serialize the entire start/stop transition with a session-level lock or an atomic runtime transition that journals any displaced session before publishing its replacement. Bind alarm and notification operations to the current session identity, and disable repeated Start while starting.
- **Trade-offs:** Avoid recursively acquiring the same mutex through `stopSession`; retain the existing crash-recovery journal rather than holding a lock across unnecessary I/O.
- **Effort:** M.

<a id="bug-p2-007"></a>

### BUG-P2-007 — Incomplete provider responses can be committed as successful absence

- **ID:** `BUG-P2-007`

- **Priority and category:** P2; data integrity / external contracts.
- **Status:** Verified for missing Power Planner `Items` and a Teams row without its identity by offline probes `malformedPowerPlannerSuccessFinishesExistingTask` and `skippedTeamsIdentityCompletesExistingAssignment` (both pass; [recorded probe results](README.md#verification)). The Anki malformed-count variant remains confirmed by reading. No live provider schema failure was observed.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt:45-53,73-83,116-120`; `app/src/main/java/com/thomaswcode/decrastination/sources/teams/TeamsSource.kt:73-75,105-112`; `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt:29-37`; `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:114-129`; `app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:137-153`.
- **Excerpt** (`PowerPlannerApi.kt:45-53`, nine lines):

```kotlin
fun agenda(login: Login, semesterId: String, nowIso: String): List<PpItem> =
    call(
        "GetAgenda",
        withLogin(login) {
            put("SemesterIdentifier", semesterId)
            put("CurrentTime", nowIso)
        },
        AgendaResponse.serializer(),
    ).items.orEmpty()
```

- **Current behaviour:** An HTTP 200 `{}` or `{"Items":null}` becomes an empty Power Planner agenda; Teams silently skips missing keys; Anki silently skips unparseable deck counts. Syncer labels these reads successful and Merge treats absent tasks as done or missed. The offline probes establish that behavior; they do not establish whether the official Power Planner API requires `Items` or permits null as an empty result.
- **Description:** The explicit `TaskSource` contract says failures must never look like empty lists. The adapters do not establish snapshot completeness before absence triggers completion. In a partial/schema-shifted response, skipped identities or unvalidated absent collections can therefore award completion credit and clear blocking pressure. The official API's null/omission semantics need confirmation before classifying every such response as invalid. P2 reflects the missing defensive boundary, not an observed live outage.
- **Trigger/opportunity:** Seed an open Power Planner item; have the fake API answer HTTP 200 `{}`; `agenda()` returns empty and an otherwise ordinary merge completes the stored task. A previously valid Teams row returned without `key` has the same effect. Existing Teams tests explicitly expect the row parser to skip missing keys, but do not verify safe merge behavior.
- **Impact/benefit:** Upstream contract errors become false task completion instead of retaining the last good state. Anki failures can remove/reclassify an entire deck's work.
- **Recommended change:** Establish each provider's success/completeness contract, including the official meaning of absent/null collections, and validate it before destructive absence reconciliation. Reject responses that violate that contract with a clear source error, or introduce an explicit non-authoritative partial snapshot mode which can update/add but never complete missing tasks. Keep a confirmed empty result distinct from an unvalidated one and add end-to-end adapter-plus-merge regressions.
- **Trade-offs:** Rejecting a whole malformed snapshot keeps valid changes stale until recovery; a partial mode is more complex and needs careful duplicate/missing-ID semantics.
- **Effort:** M.

<a id="bug-p2-008"></a>

### BUG-P2-008 — Public fixture capture retains sensitive source data and overwrites the tracked copy

- **ID:** `BUG-P2-008`

- **Priority and category:** P2; privacy / developer tooling.
- **Status:** Confirmed by reading. No invitation URL was opened; its current validity or permissions are unknown.
- **Locations:** `fixtures/teams_widget_state.json:1`; `fixtures/home_page2_ui.xml:1`; `fixtures/powerplanner_agenda_ui.xml:1`; `fixtures/powerplanner_agenda.json:2`; `scripts/pull_teams_state.ps1:9-12`; `fixtures/README.md:7-13`.
- **Excerpt** (`pull_teams_state.ps1:9-12`, four lines):

```powershell
$out = Join-Path $root "fixtures\teams_widget_state.json"

adb shell run-as com.teamsassignments.widget cat files/widget_state.json | Out-File -Encoding utf8 $out
$state = Get-Content $out -Raw | ConvertFrom-Json
```

- **Current behaviour:** Teams fixture data contains actual classroom/teacher names, detailed assignments and an account/group URL bearing an `authToken` query value. XML reference fixtures still contain the original personal task text, despite the corresponding Power Planner JSON deliberately renaming that text for a public repository. The capture script writes live provider data straight into the tracked fixture path.
- **Description:** Redaction is inconsistent across duplicate representations, and the refresh path can reintroduce identifying/token-like data without a review step. The literal token and personal names are deliberately not reproduced here. This is a confirmed exposure of the stored data, not a claim that the URL permits account takeover.
- **Trigger/opportunity:** Inspect the fixture paths locally, or run the current refresh script on a populated device; it replaces the public fixture before validating content. Do not open the embedded URL to test access without separate authorization.
- **Impact/benefit:** Sharing/publishing the repository shares school/personal context and potentially a reusable invitation credential; developers can unintentionally replace sanitized data with a live dump.
- **Recommended change:** Capture into the ignored private directory, validate, then explicitly produce a deterministic sanitized fixture. Replace names/contact details and credential-like query values consistently across JSON/XML/docs; assess revocation of the embedded value with its owner if it is still active. Preserve structural timing/identity relationships with synthetic equivalents for tests.
- **Trade-offs:** Redaction must preserve parser edge cases and referential consistency. Removing current content does not remove already published copies/history; a separate owner decision is required for any historical cleanup.
- **Effort:** M.

<a id="bug-p2-009"></a>

### BUG-P2-009 — Resolved SDK dependencies include versions matched by published vulnerability advisories

- **ID:** `BUG-P2-009`

- **Priority and category:** P2; Dependency hygiene / security maintenance.

- **Status:** Verified (resolved dependency model plus live OSV version lookup; exploitability not demonstrated).

- **Locations:** `gradle/libs.versions.toml:17-17`; `gradle/libs.versions.toml:37-37`; `app/build.gradle.kts:78-78`; `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:34-39`.

- **Excerpt** (`gradle/libs.versions.toml:17-17`):

```text
anthropic = "2.34.0"
```

- **Excerpt** (`gradle/libs.versions.toml:37-37`):

```text
anthropic-java = { group = "com.anthropic", name = "anthropic-java", version.ref = "anthropic" }
```

- **Excerpt** (`app/build.gradle.kts:78-78`):

```kotlin
    implementation(libs.anthropic.java)
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:34-39`):

```kotlin
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()
```

- **Current behaviour:** The pinned Anthropic SDK resolves Jackson core/databind 2.18.2 and Apache HttpClient 5.3.1 / HttpCore 5.2.4. The isolated debug dependency model was queried against OSV on 10 October 2026.

- **Description:** The lookup matched 20 distinct advisories across five coordinates (123 coordinates checked). Examples include Jackson async number-limit bypass, DataInput error growth, numeric-regex backtracking, and Apache HTTP header/connection resource exhaustion. These are confirmed version matches, not 20 demonstrated app vulnerabilities. App clients use AnthropicOkHttpClient, and application-owned parsers use kotlinx.serialization; many advisory prerequisites are not established here. The overview preserves all matches and the limitations.

- **Trigger/opportunity:** Build the current debug runtime and compare its dependency graph with the current OSV Maven advisory ranges. Exploitability would additionally require tracing the matching parser/client configuration and attacker-controlled input to an affected API.

- **Impact/benefit:** Known vulnerable library versions are shipped without an automated advisory gate. Priority reflects maintenance exposure, not an unsupported claim of remote compromise.

- **Recommended change:** Upgrade the SDK or apply aligned, compatible dependency constraints to patched library families, then run SDK mock-server tests, Android lint/build, and the advisory lookup again. Remove unused transports only after verifying SDK loading. Acceptance: no unresolved advisory matches without documented non-applicability and a review date.

- **Trade-offs:** Do not blindly force a major dependency upgrade or assume that absence of source-level imports proves transitive code is unreachable. Android/Kotlin compatibility and response parsing need regression checks.

- **Effort:** M

<a id="bug-p2-010"></a>

### BUG-P2-010 — Automatic Teams sync can start after its interruption safeguards become false

- **ID:** `BUG-P2-010`

- **Priority and category:** P2; background automation / interruptions.

- **Status:** Confirmed by reading; no phone call or Teams sync was performed.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:633-648`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:650-665`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:126-138`, `app/src/main/java/com/thomaswcode/decrastination/block/TeamsAutoSync.kt:52-61`.
- **Excerpt:**

```kotlin
if (power?.isInteractive != true || keyguard?.isKeyguardLocked == true) return
if (getSystemService(AudioManager::class.java)?.mode != AudioManager.MODE_NORMAL) return
...
onTimeout = {
    scope.launch {
        val before = graph.runtime.value.teamsAuto
        recordOffer()
        val why = graph.requestTeamsSync() ?: return@launch
```

- **Current behaviour:** Call mode, unlock state, quiet hours and current settings are checked when the ten-second banner is offered. Timeout then records the offer and starts the screen-taking sync. Only a screen-off broadcast cancels the banner during the countdown.
- **Description:** The launch does not revalidate the conditions that made the offer permissible. Starting or answering a call during those ten seconds, entering quiet hours, or disabling automatic sync while unarmed does not stop the pending timeout.
- **Trigger/opportunity:** Offer Teams sync shortly before quiet hours; let its ten seconds cross that boundary. Alternatively, start a call after the banner appears and let it expire.
- **Impact/benefit:** An automatic action intended to avoid sensitive moments can still take over the screen then. P2 because the invalidating transition must occur during a short countdown.
- **Recommended change:** Revalidate a single launch-eligibility predicate immediately before `recordOffer` and `requestTeamsSync`. Cancel or defer when it fails and distinguish cancellation from a failed external-widget start so retry accounting stays accurate.
- **Trade-offs:** A postponed offer needs a bounded retry policy to avoid repeatedly warning during a long call. Preserve explicit manual-sync behavior separately.
- **Effort:** M.

<a id="bug-p2-011"></a>

### BUG-P2-011 — Recreating the camera caller silently deletes a valid captured photo

- **ID:** `BUG-P2-011`

- **Priority and category:** P2; lifecycle / photo workflow.

- **Status:** Confirmed by reading and Android lifecycle contract; not run on a device.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:128-138`, `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:203-206`, `app/src/main/AndroidManifest.xml:81-86`.
- **Excerpt:**

```kotlin
var photoFor by remember { mutableStateOf<Chunk?>(null) }
val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
    val piece = photoFor
    if (!taken || piece == null || photoChecker == null) {
        photoFile.delete()
        return@rememberLauncherForActivityResult
    }
```

- **Current behaviour:** The launcher restores its callback, but the task/chunk being photographed exists only in `remember`. The output file has a fixed cache path.
- **Description:** After activity recreation, `photoFor` is null. A successful camera result is deliberately handled as a cancellation and its photo deleted without any explanation. Android specifically requires saving additional state needed to interpret activity results separately from the launcher; camera use is a documented recreation scenario. [Android activity-result state requirements](https://developer.android.com/training/basics/intents/result).
- **Trigger/opportunity:** Start Photo check, recreate the caller while the external camera is open (configuration change or process recreation), take the photo and return.
- **Impact/benefit:** The user's work capture is discarded and no check or reward happens; repeated attempts may fail on a memory-constrained phone.
- **Recommended change:** Persist a minimal pending-capture token containing task ID, stable chunk identity/revision and unique file path. Restore it before receiving the result, revalidate the task, and explain any expiry. Delete the file after a deliberate resolved outcome.
- **Trade-offs:** Do not save the bitmap or API secret in instance state; expire stale capture tokens and files so cancelled work does not linger.
- **Effort:** M.

<a id="bug-p2-012"></a>

### BUG-P2-012 — Parent-code setup survives rotation while its secret does not

- **ID:** `BUG-P2-012`

- **Priority and category:** P2; protection onboarding / lifecycle.

- **Status:** Confirmed by reading and Compose restoration contract; not run on a device.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:103-104`, `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:314-318`, `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:339-347`.
- **Excerpt:**

```kotlin
var step by rememberSaveable { mutableStateOf(Step.None) }
...
val secret = remember { Totp.newSecret() }
val qr = remember(secret) { qrBitmap(Totp.uri(secret), 720) }
```

- **Current behaviour:** The wizard step is restored but a fresh TOTP secret is created when the QR dialog composition is recreated.
- **Description:** A parent who scanned the QR before rotation now holds the previous secret. The dialog silently shows a new QR and rejects every code from the entry that was just enrolled. This is not clock skew or an authenticator error. [Compose's state-saving contract](https://developer.android.com/develop/ui/compose/state-saving).
- **Trigger/opportunity:** Scan the QR in the parent-code step, rotate/recreate ProtectionActivity, then enter the current code from the scanned authenticator entry.
- **Impact/benefit:** Set-up cannot complete with the already-scanned entry; duplicate authenticator entries and unnecessary troubleshooting result.
- **Recommended change:** Keep the provisional enrolment secret across configuration changes in a retained state holder; for process recreation either restore it through an appropriately protected provisional store or explicitly restart enrolment with an explanation. Promote it to the active secret only after verification and remove provisional state on cancellation.
- **Trade-offs:** Treat provisional secrets as credentials; a generic saved-state bundle is not the preferred durable secret store.
- **Effort:** M.

<a id="bug-p2-013"></a>

### BUG-P2-013 — Unsaved settings and contextual instructions disappear on activity recreation

- **ID:** `BUG-P2-013`

- **Priority and category:** P2; UI state / data loss.

- **Status:** Confirmed by reading.

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:65-70`; `app/src/main/java/com/thomaswcode/decrastination/ui/InstructionsUi.kt:54-63`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:65-70`):

```kotlin
        val saved by graph.settings.state.collectAsStateWithLifecycle()
        val runtime by graph.runtime.state.collectAsStateWithLifecycle()
        // What's been asked for, waiting changes included: setting one back cancels it.
        val asked = SettingsChanges.requested(saved, runtime.pending)
        var draft by remember { mutableStateOf(asked) }
        var invalid by remember { mutableStateOf(emptySet<String>()) }
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/InstructionsUi.kt:54-59`):

```kotlin
@Composable
fun InstructionDialog(what: String, initial: String = "", onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Instruction") },
```

- **Current behaviour:** Settings keeps the draft in remember; task/event/day instruction dialogs keep their text and their open state in remember. The main Instructions page correctly uses rememberSaveable for its new-instruction text.

- **Description:** A rotation or system recreation destroys edits that have not yet been saved/sent. This affects long natural-language instructions as well as multiple settings changes. The inconsistency is supported by the Compose state contract: [Compose state-saving contract](https://developer.android.com/develop/ui/compose/state-saving). It was not reproduced on a device.

- **Trigger/opportunity:** Edit several settings, or compose an instruction through a task or calendar event; rotate before Save/Send. The settings return to stored values and the contextual dialog closes.

- **Impact/benefit:** Lost user input and repeated work, especially when a long instruction is interrupted.

- **Recommended change:** Keep non-secret drafts and contextual target IDs in a saved-state-backed screen model. Restore validation and dirty state with the draft, and offer discard confirmation only when the user navigates away with unsaved changes. Acceptance: recreation preserves every edited field and target; explicit Cancel still clears the draft.

- **Trade-offs:** Do not put API passwords or TOTP secrets into general saved instance state; those require a separate lifecycle design. Keep saved bundles small.

- **Effort:** M

<a id="bug-p2-014"></a>

### BUG-P2-014 — Saving a settings draft can overwrite unrelated changes made while it was open

- **ID:** `BUG-P2-014`

- **Priority and category:** P2; Settings concurrency / consistency.

- **Status:** Confirmed by reading.

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:65-69`; `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:85-98`; `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:191-199`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:65-69`):

```kotlin
        val saved by graph.settings.state.collectAsStateWithLifecycle()
        val runtime by graph.runtime.state.collectAsStateWithLifecycle()
        // What's been asked for, waiting changes included: setting one back cancels it.
        val asked = SettingsChanges.requested(saved, runtime.pending)
        var draft by remember { mutableStateOf(asked) }
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:85-90`):

```kotlin
                        enabled = invalid.isEmpty() && draft != asked,
                        onClick = {
                            scope.launch {
                                val wanted = draft
                                graph.changeSettings { wanted }
                                // The reminders' alarms, at their new times.
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:191-196`):

```kotlin
     */
    suspend fun changeSettings(change: (Settings) -> Settings) = changing.withLock {
        // What's waiting is counted up to now first, so a new change's wait starts now.
        applyDue(force = true)
        val state = runtime.value
        val proposed = change(SettingsChanges.requested(settings.value, state.pending))
```

- **Current behaviour:** The editor snapshots all requested settings once, then passes that whole object through graph.changeSettings { wanted }. The graph provides the latest requested state to the lambda, but the editor ignores it.

- **Description:** Changes from another activity or background transition are lost or unintentionally resubmitted when an old draft is saved. A Settings screen left on the back stack can retain aiKeyActive=false while Setup activates a newly entered key; saving an unrelated edit then asks to turn that key off. Pending protection still gates loosening, but it does not resolve the lost-update problem.

- **Trigger/opportunity:** Open Settings, then change the API-key activation or another setting through a second screen/instance without destroying the editor; return and save a blocklist or hours edit. Compare untouched fields before/after.

- **Impact/benefit:** Unexpected settings reversions and extra delayed changes, with misleading Save confirmation.

- **Recommended change:** Track edited fields against a base revision and apply only those fields to the latest requested settings. Show a conflict when the same field changed elsewhere. Acceptance: external edits to untouched fields survive, pending changes are preserved, and same-field conflicts are explicit.

- **Trade-offs:** Simply replacing draft whenever a store emits would discard ongoing edits; the merge needs field-level dirty tracking.

- **Effort:** M

<a id="bug-p2-015"></a>

### BUG-P2-015 — The monthly cap's worst-call bound does not cover every request's input

- **ID:** `BUG-P2-015`

- **Priority and category:** P2; AI budget contract.
- **Status:** Confirmed by reading. No live-provider spending was attempted, and this does not assert current external prices.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:88-100`; `app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt:38-39`; `app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt:78-81,94-104`; `app/src/main/java/com/thomaswcode/decrastination/enrich/InstructionReader.kt:98-106,117-125`; `app/src/main/java/com/thomaswcode/decrastination/learn/CheckIns.kt:105-108,120`.
- **Excerpt** (`ClaudeEnricher.kt:94-100`, 7 lines):
  ```kotlin
  /**
   * The most one call can cost, at the dearer rates: the longest text the model reads in
   * ([Prompts.MAX_TEXT] characters at [TOKENS_PER_CHAR] tokens each, and [PROMPT_TOKENS] more),
   * and all of [ClaudeEnricher.MAX_TOKENS] out. $0.66.
   */
  val WORST_CALL_USD: Double =
      ((Prompts.MAX_TEXT.toLong() * TOKENS_PER_CHAR + PROMPT_TOKENS) * OLDER_OPUS.input + ClaudeEnricher.MAX_TOKENS * OLDER_OPUS.output) / 1_000_000.0
  ```
- **Current behaviour:** every model call uses a $0.66 coded upper reservation derived from a 16,000-character enrichment body plus overhead. Review input has no total input limit and incorporates every completion's title/note and unrestricted check-in text. Instruction input limits counts but not all event/title/key lengths.
- **Description:** inputs accepted through other workflows can exceed the inputs used to prove this bound. Therefore `allows` cannot guarantee that an admitted call stays under the optional monthly cap. Review input can also exceed a provider context limit and fail repeatedly.
- **Trigger/opportunity:** a large weekly record or pasted long check-in/assessment note when the cap has only slightly more than the fixed reserve remaining; the admitted call can have greater input cost under the code's own pricing model.
- **Impact/benefit:** the optional spending limit is not a strict bound; long requests waste latency and can fail. Default uncapped users are not affected by the cap guarantee itself.
- **Recommended change:** establish a total token budget per request type, summarize/limit history deterministically, and reserve using the actual bounded payload plus job-specific output/image budget. Show when detail was omitted.
- **Trade-offs:** token counting and summarization add complexity; conservative byte bounds may refuse affordable calls. Network retries/unknown billing outcomes still need an explicit policy.
- **Effort:** **M**. Acceptance: adversarially long accepted UI inputs produce bounded requests and cannot exceed the reserved maximum under the configured rates.

<a id="bug-p2-016"></a>

### BUG-P2-016 — Mixed photo and timer completions train effort from an incomplete duration

- **ID:** `BUG-P2-016`

- **Priority and category:** P2; Learning correctness.
- **Status:** Verified; passing pure JVM probe `a completion with partly photographed work trains from its timed portion only` verifies the learner's result, with Focus mapping separately confirmed by reading. No live photo or Android completion flow was run.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt:59-74`; `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:414-415,465-480`; `app/src/main/java/com/thomaswcode/decrastination/data/RuntimeState.kt:134-153`.
- **Excerpt** (`Calibrator.kt:65-73`, 9 lines):
  ```kotlin
  // Timed, and all of it: with blocks ticked by hand, its minutes aren't how long it took.
  val timed = record.workedMin > 0 && !record.byHand
  if (timed) m = (1 - WEIGHT) * m + WEIGHT * (record.workedMin.toDouble() / record.estimateMin)
  val nudge = if (timed) NUDGE_WITH_TIME else NUDGE_ALONE
  when (record.assessment) {
      HARDER -> m *= 1 + nudge
      EASIER -> m *= 1 - nudge
  }
  if (timed || record.assessment == HARDER || record.assessment == EASIER) result[key] = m.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
  ```
- **Current behaviour:** the completion record stores timed minutes and a by-hand flag, but no marker for photo-confirmed work. Any positive timed duration on a record not marked by hand is treated as complete duration.
- **Description:** excluding photo minutes from measured time is correct; treating the remaining partial timed minutes as the whole task duration is not. The analogous manual-tick case is deliberately excluded and tested.
- **Trigger/opportunity:** a task estimated at 40 minutes has 5 timed minutes and the remaining work accepted through a photo, then its source confirms completion. The record has estimate 40, worked 5, byHand false. The pure learner probe computes 0.7375 from an initial multiplier of 1.
- **Impact/benefit:** mixed-mode users progressively receive underestimated work durations and shorter sessions even though the app did not measure their actual completion time.
- **Recommended change:** record timing coverage/provenance on completions and exclude incomplete measurements from duration ratios. Continue accepting explicit harder/easier assessments without pretending a photo's estimated minutes were measured.
- **Trade-offs:** old completion records cannot establish coverage reliably; conservatively skip uncertain historic samples rather than manufacture actual durations.
- **Effort:** **M**. Acceptance: mixed-photo completions do not train the actual/estimate ratio; fully timed completions retain existing results.

<a id="bug-p2-017"></a>

### BUG-P2-017 — Anki homework schedules mix note counts with card limits

- **ID:** `BUG-P2-017`

- **Priority and category:** P2; scheduling / measurement units.
- **Status:** Confirmed by reading; Android provider cardinality is documented in `docs/data-sources.md:123`, not revalidated on a device.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt:39-43,89-95`; `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:248-265,280-284`; `docs/data-sources.md:123`.
- **Excerpt** (`AnkiSource.kt:39-43`, five lines):

```kotlin
/** Notes in [deckName] with a card never studied, by Anki's own search; the provider answers null for none. */
fun unseenNotes(resolver: ContentResolver, deckName: String): Int {
    val query = "deck:\"${deckName.replace("\"", "\\\"")}\" is:new"
    return resolver.query(notesUri, arrayOf("_id"), query, null, null)?.use { it.count } ?: 0
}
```

- **Current behaviour:** `unseenNotes` supplies note counts, `deck.new` supplies today's card count, and `newCardSteps` combines them as if both were cards. The description labels notes as `cards never studied`, effort uses seconds/card, and future steps are capped at 20 cards/day.
- **Description:** The documentation acknowledges a low estimate for two-card notes, but calibration cannot fix the unit mismatch in the number of daily steps. With 40 wholly new notes producing 80 cards, a 20-card daily cap needs four days; this code initially creates only two 20-card steps. Partial progress can further change the note/card ratio.
- **Trigger/opportunity:** A linked deck uses Basic-and-reversed or another multi-card note type. Supply unseen count 40 and today's new count 20 to `homeworkDecks`; inspect its two steps. The production notes provider supplies the former unit.
- **Impact/benefit:** Remaining days and minutes are systematically underrepresented for multi-card decks, including the documented both-ways vocabulary use. Completion detection at zero remains useful; the defect is estimated work and daily capacity.
- **Recommended change:** Track units explicitly. Obtain card cardinality where the provider supports it, or derive a bounded estimate from note templates/card ordinals with a documented uncertainty; use note units consistently if exact card counts cannot be read, and do not mix that count with a card-based daily cap. Validate both partially studied reverse cards and one-card notes.
- **Trade-offs:** Additional provider queries may cost time and may not expose exact scheduler state. An honest interval/uncertainty is preferable to an apparently precise card/day schedule.
- **Effort:** M.

<a id="bug-p2-018"></a>

### BUG-P2-018 — The Anki day switches at the wrong local time on DST dates

- **ID:** `BUG-P2-018`

- **Priority and category:** P2; time arithmetic.
- **Status:** Verified by the offline `ankiRolloverMovesAtDaylightSavingTransitions` probe (passes for both UK transitions and the past next-rollover value; [recorded probe results](README.md#verification)). No AnkiDroid device state was changed.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:74-75,163-165,271-273`; `app/src/test/java/com/thomaswcode/decrastination/sources/anki/AnkiRulesTest.kt:60-64`.
- **Excerpt** (`AnkiRules.kt:74-75`, two lines):

```kotlin
fun ankiDay(now: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(now).atZone(zone).minusHours(ROLLOVER_HOUR).toLocalDate()
```

- **Current behaviour:** Subtracting four elapsed hours from a zoned timestamp assigns its resulting date to the Anki day, while `nextRollover` constructs the next local 04:00.
- **Description:** Those operations disagree around a one-hour offset change. London 25 October 2026 at 03:30 is classified as the 25th although the configured local 04:00 rollover is still ahead. London 29 March at 04:30 is classified as the 28th, and `nextRollover` returns that morning's already-past 04:00.
- **Trigger/opportunity:** Call `ankiDay` at the two timestamps above with Europe/London. Existing tests cover normal midnight/04:00 boundaries only.
- **Impact/benefit:** The quota can roll early/late, and a homework deck can stop waiting an hour early or receive a past not-before time. This is limited to transition mornings.
- **Recommended change:** Compare the current local time against the local rollover time, subtract a calendar date when before it, and derive the next boundary consistently. Add spring/fall regressions and evaluate other supported zones.
- **Trade-offs:** If rollover becomes user-configurable to an ambiguous/nonexistent local time, define its gap/overlap resolution explicitly; the current 04:00 London boundary itself is unambiguous.
- **Effort:** S.

<a id="bug-p2-019"></a>

### BUG-P2-019 — A slow Gmail initial load can time out forever without saving progress

- **ID:** `BUG-P2-019`

- **Priority and category:** P2; reliability / synchronization.
- **Status:** Confirmed by reading; timing threshold is calculated from command count, not a live latency measurement.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:213-220,233-239,262`; `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:58,98-104,114-131`.
- **Excerpt** (`GmailSource.kt:213-220`, eight lines):

```kotlin
val messages = uids.chunked(FETCH_BATCH).flatMap { batch ->
    imap.uidFetch(batch, "UID INTERNALDATE X-GM-MSGID X-GM-THRID X-GM-LABELS ENVELOPE").mapNotNull(GmailThreads::message)
}
if (messages.size < uids.size) throw IOException("Gmail listed ${uids.size} messages but described ${messages.size}")
val read = GmailThreads.toRead(messages, known, MAX_BODIES).associate { it.messageId to text(imap, it.uid) }
val bodies = GmailThreads.withRead(known, read)
imap.logout()
SourceRead(GmailThreads.fetched(messages, bodies, context.now, context.zone))
```

- **Current behaviour:** Up to 60 uncached conversations are processed sequentially before returning any result. Each whose BODYSTRUCTURE identifies a readable MIME part requires two IMAP commands: the structure and then its text. A conversation with no readable part does not require the second command. The entire source result is discarded at 90 seconds and no completed body is cached beforehand.
- **Description:** A batch of 60 uncached readable MIME bodies requires at least 120 body-command round trips, plus connection, login, listing, envelope batches and logout. Around 0.75 seconds per command already exceeds the total source budget in that case. The next run chooses the same uncached messages and repeats the failed batch, rather than making bounded progress.
- **Trigger/opportunity:** Initial import/body-limit migration with at least 60 uncached conversations containing readable MIME bodies on a high-latency connection. A scripted transport delaying each body response would confirm the whole production reader; no such network/device run occurred in this review.
- **Impact/benefit:** Gmail can stay stale or empty indefinitely on a connection that successfully completes each individual command, with `No answer in 90 s` on every run.
- **Recommended change:** Bound body fetching by elapsed source budget and return a complete envelope snapshot with remaining bodies explicitly pending; persist successful body reads in an independent cache so cancellation does not discard them. Batch BODYSTRUCTURE reads and progressively retry the backlog. Never reconcile absence from an incomplete envelope listing.
- **Trade-offs:** Intermediate UI contains pending tasks and must avoid deriving final classification from absent text. Separate caches require identity/version handling.
- **Effort:** M.

<a id="bug-p2-020"></a>

### BUG-P2-020 — Repeated timeouts accumulate uncancellable provider reads

- **ID:** `BUG-P2-020`

- **Priority and category:** P2; resource lifecycle / concurrency.
- **Status:** Confirmed by reading; existing test verifies one abandoned blocking read only.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:65-67,98-108`; `app/src/main/java/com/thomaswcode/decrastination/sources/teams/TeamsSource.kt:43-49`; `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt:29-42`; `app/src/test/java/com/thomaswcode/decrastination/sync/SyncerTest.kt:66-79`.
- **Excerpt** (`Syncer.kt:98-104`, seven lines):

```kotlin
val reading = readers.async { source.read(context) }
val read = try {
    withTimeout(timeoutMs) { reading.await() }
} catch (e: TimeoutCancellationException) {
    reading.cancel()
    onFailure(source.source, e)
    return failed(source.source, startedAt, "No answer in ${timeoutMs / 1000} s")
```

- **Current behaviour:** Reads live in a separate SupervisorJob scope. Cancellation lets Syncer stop waiting, but cannot interrupt a ContentResolver call. The next sync starts another call to the same provider even while the earlier one remains blocked.
- **Description:** This correctly prevents one stalled read from blocking the first sync forever, but does not bound the accumulated stalled operations. Provider query overloads do not carry a CancellationSignal, and no in-flight registry suppresses repeated starts.
- **Trigger/opportunity:** A provider blocks indefinitely; multiple manual/periodic syncs reach it. The test's fake `CountDownLatch.await()` models one such call, but releases it after one attempt. Retaining it across several sync attempts demonstrates concurrent abandoned readers.
- **Impact/benefit:** Occupied IO threads/Binder calls accumulate and compete with persistence and other network jobs. Timed-out Power Planner calls can also finish later and update session/cache state outside the task merge.
- **Recommended change:** Bound in-flight operations per source; wire CancellationSignal/socket disconnection through capable transports, and explicitly report an earlier read still winding down instead of spawning another. Retry after completion or a controlled provider recovery policy.
- **Trade-offs:** A genuinely uninterruptible provider may stay unavailable until its process recovers, but resource growth should remain bounded. Do not block healthy sources behind that recovery.
- **Effort:** M.

<a id="bug-p2-021"></a>

### BUG-P2-021 — Gmail connection setup lacks explicit socket cleanup on failure

- **ID:** `BUG-P2-021`

- **Priority and category:** P2; resource cleanup.
- **Status:** Confirmed by reading; no real TLS or socket failure injected.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:196-207,243-254`.
- **Excerpt** (`GmailSource.kt:243-249`, seven lines):

```kotlin
private fun connect(): SSLSocket {
    val plain = Socket()
    plain.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
    val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, HOST, PORT, true) as SSLSocket
    socket.soTimeout = TIMEOUT_MS
    socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    socket.startHandshake()
```

- **Current behaviour:** The caller's `use` block and cancellation closer are installed only after `connect()` returns. The helper closes the TLS socket only for an explicit hostname-verifier false result.
- **Description:** If socket wrapping or the TLS handshake throws after TCP connection, this code does not explicitly release socket ownership. Some TLS implementations close their underlying socket on handshake failure, so an actual leak depends on the failing stage and implementation; this review confirms the missing cleanup path, not an observed descriptor leak.
- **Trigger/opportunity:** A TCP connection succeeds but the TLS handshake times out or throws a certificate/protocol exception, or wrapping the connected socket fails.
- **Impact/benefit:** Resources may remain open longer than the failed read on failure paths that do not self-close, especially across repeated bad/captive connections. The failure correctly reports a source error; the weakness is ownership/cleanup, not a TLS-verification bypass. A tracked socket-factory fault-injection test would establish the affected stages.
- **Recommended change:** Keep ownership in a try/finally until a successfully initialized SSLSocket is handed to the caller, closing either wrapper or underlying socket on every failure. Install cancellation before the connection/handshake stage where practical; inject the connection factory to test failure paths.
- **Trade-offs:** Take care not to close the successfully returned socket and do not accidentally weaken certificate/hostname checks.
- **Effort:** S.

<a id="bug-p2-022"></a>

### BUG-P2-022 — The Gmail byte cap can cache an incomplete message as fully read

- **ID:** `BUG-P2-022`

- **Priority and category:** P2; data quality / AI input integrity.
- **Status:** Verified by offline transformation/cache probe `htmlCutAtByteLimitIsRememberedAsCompletelyRead` (passes; [recorded probe results](README.md#verification)). This confirms the fetched-prefix decoding/cache behavior, not a live IMAP transaction.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:78-80,151-152,233-239,264-268`; `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Mime.kt:116-120`.
- **Excerpt** (`GmailSource.kt:234-239`, six lines):

```kotlin
val structure = imap.uidFetch(listOf(uid), "UID BODYSTRUCTURE").firstOrNull()?.get("BODYSTRUCTURE") ?: return null
val part = Mime.textPart(structure) ?: return ""
val response = imap.uidFetch(listOf(uid), "UID BODY.PEEK[${part.section}]<0.$MAX_PART_BYTES>").firstOrNull() ?: return null
val body = response.entries.firstOrNull { it.key.startsWith("BODY[") }?.value ?: return null
// NIL: a part with nothing in it.
return (body as? ImapValue.Str)?.let { Mime.tidy(Mime.decode(it.bytes, part), GmailThreads.MAX_BODY_CHARS) }.orEmpty()
```

- **Current behaviour:** At most 200,000 encoded bytes are fetched; only the resulting text is returned. `withRead` wraps every non-null text in `Body(..., readTo=20_000)`, whose `whole` becomes true even if almost no readable text was reached.
- **Description:** Encoded-byte truncation and readable-character truncation are different. An HTML message whose initial styling exceeds the byte cap can become empty text; it is still cached as read and never re-fetched while its message ID remains unchanged. No source flag tells the model/user that the useful text lies beyond the byte limit.
- **Trigger/opportunity:** A body begins with an open style block spanning the first 200KB and contains requested work afterward. Decode the fetched prefix; it becomes empty and is considered whole. Smaller compressed/readable portions can also mask omitted tail instructions.
- **Impact/benefit:** Work in long or markup-heavy mail can be permanently excluded from AI/rules without an explicit incomplete-data signal. The 20,000-character cap itself is deliberate; the bug is claiming character coverage that encoded bytes did not provide.
- **Recommended change:** Carry encoded part size/truncation metadata from BODYSTRUCTURE and fetch responses. Continue bounded incremental part reads until a readable-text budget or an explicit total-byte ceiling is reached; retain an incomplete marker at the ceiling and offer targeted retry/open-source handling. Do not equate short decoded text with complete input.
- **Trade-offs:** More bytes/round trips; limits are still needed for privacy and memory. Coordinate with [BUG-P2-019](BUGS-AND-INCONSISTENCIES.md#bug-p2-019)'s elapsed-time budget so larger text cannot starve synchronization.
- **Effort:** M.

<a id="bug-p2-023"></a>

### BUG-P2-023 — External permission grants leave Setup and Calendar showing stale denied state

- **ID:** `BUG-P2-023`

- **Priority and category:** P2; Permissions / lifecycle.

- **Status:** Confirmed by reading.

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt:68-80`; `app/src/main/java/com/thomaswcode/decrastination/ui/CalendarActivity.kt:64-80`; `app/src/main/java/com/thomaswcode/decrastination/ui/SetupActivity.kt:29-32`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt:68-73`):

```kotlin
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Credential?>(null) }
    var enteringKey by remember { mutableStateOf(false) }
    val requestAnki = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (granted) SyncWorker.syncNow(activity, setOf(Source.Anki))
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/CalendarActivity.kt:64-69`):

```kotlin
        LaunchedEffect(Unit) { CalendarTime.refresh(applicationContext) }
        val now = graph.clock.now()
        val zone = graph.clock.zone()
        val applied = instructions.applied
        val yours = Instructions.eventAnswers(applied)
        val answers = runtime.eventAnswers + yours
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SetupActivity.kt:29-32`):

```kotlin
    override fun onResume() {
        super.onResume()
        // Back from notification settings: alerts that couldn't be shown before can be now.
        AppGraph.get(this).reconcileAlerts()
```

- **Current behaviour:** Anki and calendar permission flags are cached by a refresh integer that changes only in in-app permission callbacks. Calendar caches its permission once. Returning to Setup only reconciles alerts.

- **Description:** Granting calendar or Anki permission in Android Settings does not invalidate the cached booleans when the existing activity resumes. A newly granted permission can keep its denied message or Allow button until that activity is recreated. Normal runtime-permission revocation generally terminates the process, so a stale granted-state claim is not made here. See the [Android permission lifecycle documentation](https://developer.android.com/training/permissions/requesting#one-time).

- **Trigger/opportunity:** Leave Setup or Calendar alive in its denied state, grant READ_CALENDAR or the Anki permission in system Settings, and return using Back/Recents without recreating the activity.

- **Impact/benefit:** Incorrect setup guidance and avoidable troubleshooting when a permission changes through the normal Android controls.

- **Recommended change:** Refresh permission state on lifecycle resume, and refresh/clear dependent calendar data according to the new grant. Offer a direct Settings route after permanent denial. Acceptance: external grant and revocation are reflected immediately on return without reopening the activity.

- **Trade-offs:** Observe lifecycle only while visible and avoid repeatedly launching permission dialogs.

- **Effort:** S

<a id="bug-p2-024"></a>

### BUG-P2-024 — Session countdowns disagree with the clock that actually ends sessions

- **ID:** `BUG-P2-024`

- **Priority and category:** P2; timekeeping / user feedback.

- **Status:** Confirmed by reading; uptime behavior already has unit coverage, UI mismatch not run.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt:64-65`; `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:158-162`, `app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt:103-108`, `app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt:108-115`, `app/src/test/java/com/thomaswcode/decrastination/block/FocusTest.kt:79-101`.
- **Excerpt:**

```kotlin
val left = ((session.endsAt - now) / 1000).coerceAtLeast(0)
...
fun ran(now: Long, uptime: Uptime?): Long = uptime?.since(startedUptime) ?: (now - startedAt)
fun isDue(now: Long, uptime: Uptime?): Boolean = ran(now, uptime) >= minutes * 60_000L - FINISH_SLACK_MS
```

- **Current behaviour:** The policy and alarm use elapsed time in the same boot, but the block-screen timer, widget countdown and notification use the original wall-clock end.
- **Description:** A wall-clock adjustment can show zero time remaining while the focus session correctly continues, or show a long wait for a nearly finished session. The repository explicitly tests adjustment resistance, so its display is inconsistent with supported behavior.
- **Trigger/opportunity:** Start a 30-minute session unarmed, advance wall time 30 minutes after two elapsed minutes. The tested session remains active, but the block screen computes zero remaining.
- **Impact/benefit:** Misleading countdowns make the blocker appear stuck or unreliable even while its accounting is correct.
- **Recommended change:** Expose `remainingMs(now, uptime)` from FocusSession and derive every timer from it. For Android's notification chronometer calculate the display deadline from current wall time plus elapsed-clock remaining time and refresh on clock changes.
- **Trade-offs:** Across reboot, preserve the explicit wall-clock fallback; do not confuse the five-second completion slack with a full countdown discrepancy.
- **Effort:** M.

<a id="bug-p2-025"></a>

### BUG-P2-025 — The Android enforcement layer has no automated transition coverage

- **ID:** `BUG-P2-025`

- **Priority and category:** P2; critical-path test coverage.

- **Status:** Confirmed by reading all repository tests and the complete test inventory; no Android instrumentation tests are present.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:192-214`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:378-420`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:427-483`, `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:606-668`; `app/src/test/java/com/thomaswcode/decrastination/block/BlockingTest.kt:13-222`; `app/src/test/java/com/thomaswcode/decrastination/block/FocusTest.kt:39-565`; `app/src/test/java/com/thomaswcode/decrastination/protect/ProtectTest.kt:319-455`.
- **Excerpt:**

```kotlin
AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
    closeBlockedPictureInPicture()
    coverBlockedSideWindows()
    frontPackage()?.let { onFront(it, firstLook = false) }
}
```

- **Current behaviour:** Pure URL, policy, credit, session and protection rules have useful unit tests. Window selection, per-browser/PiP remembered state, delayed handlers, activity reuse, connection reset and Teams countdown are concentrated in Android classes and not exercised by those tests.
- **Description:** Passing the large pure suite cannot detect whether a window is classified incorrectly, stale callbacks fire, unknown browser windows are released, or a reconnection loses a deferred action. Historical manual phone evidence does not regression-test today's changes.
- **Trigger/opportunity:** Change the service's event ordering, window precedence, delayed callbacks or target lifecycle; current tests can remain green even when blocking fails.
- **Impact/benefit:** The application's core enforcement path has a material regression-detection gap. This finding concerns absent coverage, not a claim that all these scenarios currently fail.
- **Recommended change:** Add deterministic fake-window/event-sequence tests around an extracted enforcement coordinator, then a small device/emulator smoke matrix for window-state API integration. Include strict-to-free-time transitions, browser address disappearance, PiP plus another browser window, split screen, lock/unlock, reconnection and delayed Teams launches.
- **Trade-offs:** Device tests alone are brittle and slower; pure mocks alone cannot verify OEM accessibility exposure. Use both at deliberately small scope.
- **Effort:** L.

<a id="bug-p2-026"></a>

### BUG-P2-026 — The Teams capture script can destroy its good fixture after an adb failure

- **ID:** `BUG-P2-026`

- **Priority and category:** P2; developer tooling / data preservation.
- **Status:** Confirmed by reading; not run against a device or tracked fixture.
- **Locations:** `scripts/pull_teams_state.ps1:7-13,16-26`.
- **Excerpt:** The four-line write/parse excerpt in [BUG-P2-008](BUGS-AND-INCONSISTENCIES.md#bug-p2-008) applies; `$ErrorActionPreference = "Stop"` is at line 7, while no `$LASTEXITCODE` checks appear after adb, apksigner or keytool invocations.
- **Current behaviour:** Output is redirected directly over the tracked file and parsed afterwards. Native-command exit statuses are not checked. The signature step prints both fingerprints and tells the human they must match, without comparing them or failing on mismatch.
- **Description:** A missing/unauthorized device or failed `run-as` can truncate or replace the existing fixture before JSON parsing fails. Later certificate command failures are not converted into a programmatic verdict. The script explicitly tells a human that the printed fingerprints must match, so it does not claim to have automatically verified equality. This is distinct from [BUG-P2-008](BUGS-AND-INCONSISTENCIES.md#bug-p2-008)'s confidentiality issue: even entirely synthetic/non-sensitive fixtures can be damaged.
- **Trigger/opportunity:** Run the script with `adb` returning nonzero/no valid JSON; redirect writes the destination before validation. Use a stand-in adb and a temporary fixture path for a future regression test, not the repository fixture.
- **Impact/benefit:** The refresh tool can lose a known-good test input. An automated caller cannot use its exit status as a certificate-verification result; the existing human comparison instruction must be followed.
- **Recommended change:** Capture to a temporary/private file, check each native process exit code, parse/validate required fields and then replace the destination atomically only after explicit sanitized export. Normalize and compare installed and repository certificate fingerprints; exit nonzero for unavailable tools, missing paths or mismatch.
- **Trade-offs:** Adds platform-specific failure handling; Windows PowerShell and PowerShell 7 native-error behavior must both be considered if both remain supported.
- **Effort:** S.

- **Local excerpt** (`scripts/pull_teams_state.ps1:9-12`):

```powershell
$out = Join-Path $root "fixtures\teams_widget_state.json"

adb shell run-as com.teamsassignments.widget cat files/widget_state.json | Out-File -Encoding utf8 $out
$state = Get-Content $out -Raw | ConvertFrom-Json
```

<a id="bug-p2-027"></a>

### BUG-P2-027 — A recycled invalid settings field can leave Save disabled with no visible error

- **ID:** `BUG-P2-027`

- **Priority and category:** P2; Form validation / lazy UI state.

- **Status:** Suspected (confirm by scrolling a malformed field out of composition on a device or Compose test).

- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:69-76`; `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:252-267`.

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:69-74`):

```kotlin
        var draft by remember { mutableStateOf(asked) }
        var invalid by remember { mutableStateOf(emptySet<String>()) }
        var message by remember { mutableStateOf<String?>(null) }
        var apps by remember { mutableStateOf(emptyList<App>()) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps() } }
```

- **Excerpt** (`app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:252-257`):

```kotlin
private fun <T> ParsedField(label: String, shown: String, key: String, valid: (String, Boolean) -> Unit, parse: (String) -> T?, onChange: (T) -> Unit, number: Boolean) {
    var text by remember(shown) { mutableStateOf(shown) }
    val ok = parse(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
```

- **Current behaviour:** Invalid keys are stored at editor level, but each raw field string is remembered only inside its lazy item. valid(key, ...) is called only after a keystroke.

- **Description:** When an invalid offscreen field leaves composition, its raw text is forgotten. Recreating the item displays the last valid draft value, while the parent invalid set still contains its key. The Save button remains disabled until the user edits that apparently valid field again. This is a state-lifetime mismatch; the exact lazy eviction threshold needs device verification.

- **Trigger/opportunity:** Type an invalid time or out-of-range number, scroll far enough into the installed-app list to dispose its item, then return. The old valid number can be visible while Save remains disabled.

- **Impact/benefit:** A form can become apparently impossible to save with no remaining visible explanation.

- **Recommended change:** Hoist raw field strings with the draft and derive the entire validation map from them rather than retaining independent error flags. Acceptance: scrolling/recreation preserves the invalid input and its message; correcting it always re-enables a valid dirty draft.

- **Trade-offs:** Do not auto-normalize every keystroke in a way that moves the cursor or prevents intermediate input.

- **Effort:** M

## P3

<a id="bug-p3-001"></a>

### BUG-P3-001 — Fallback model answers are attributed to the requested model

- **ID:** `BUG-P3-001`

- **Priority and category:** P3; AI provenance inconsistency.
- **Status:** Confirmed by reading.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:31-32,65-66,109-116`; `app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt:15-18`; analogous review attribution `app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt:107`.
- **Excerpt** (`ClaudeEnricher.kt:65-66`, 2 lines):
  ```kotlin
  val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
  return Enricher.Result(Answers.parse(job, text, task, by, now, zone), cost)
  ```
- **Current behaviour:** `by` is initialized from the requested model; cost uses the model actually returned by the API. The app deliberately enables server-side fallback.
- **Description:** an answer from a fallback is saved as if the requested model produced it, contrary to the Enrichment field's stated meaning. The existing fallback test checks price and estimate, but not this provenance.
- **Trigger/opportunity:** API returns a different model under the enabled fallback path.
- **Impact/benefit:** debugging, future model comparison and cache decisions cannot reliably identify the generating model. This is metadata correctness, not a claim of current user-facing functional failure.
- **Recommended change:** record requested and actual response model separately; use actual model for authorship and pricing, retaining requested model/job version for policy/cache decisions.
- **Trade-offs:** optional fields and migration are needed for historic records; previous actual models cannot be recovered from saved metadata.
- **Effort:** **S**. Acceptance: the mocked fallback response retains its response model identifier.

<a id="bug-p3-002"></a>

### BUG-P3-002 — Capacity advice promises fewer behind tasks after reducing hours

- **ID:** `BUG-P3-002`

- **Priority and category:** P3; Guidance/code inconsistency.
- **Status:** Confirmed by reading.
- **Locations:** `app/src/main/java/com/thomaswcode/decrastination/learn/Days.kt:121-130`; `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:338-363`.
- **Excerpt** (`Days.kt:129-130`, 2 lines):
  ```kotlin
  return "Only $full of the last ${judged.size} days' plans were done in full: the hours in Settings may be more than the evenings hold. " +
      "Planning with fewer would put less on each day, and less would show as behind."
  ```
- **Current behaviour:** after too few completed plans the review recommends reducing working hours and says less work would be behind. Planner marks dated pieces behind when insufficient capacity prevents fitting them before the deadline.
- **Description:** reducing ordinary working-hour capacity cannot justify the promised reduction in behind work; it can make more dated chunks fail to fit and accumulate in today's bucket. Reducing overoptimistic hours can improve honesty, but does not remove obligations.
- **Trigger/opportunity:** a backlog that already barely fits the available days, followed by accepting this advice and narrowing study hours.
- **Impact/benefit:** the guidance creates a false expectation about a core control and can increase apparent pressure instead of easing it.
- **Recommended change:** explain that realistic hours reveal infeasible work; accompany the advice with a preview of the impact and actionable scope/deadline changes rather than promising less lateness.
- **Trade-offs:** more candid overload feedback can feel discouraging; present manageable next steps alongside it.
- **Effort:** **S**. Acceptance: the advice no longer contradicts capacity reduction in deterministic planner examples.

<a id="bug-p3-003"></a>

### BUG-P3-003 — Current guides mix superseded protection and source contracts with implemented behavior

- **ID:** `BUG-P3-003`

- **Priority and category:** P3; documentation consistency.
- **Status:** Confirmed by reading; historical Phase 0 findings are appropriately dated and are not themselves a bug.
- **Locations:** `docs/data-sources.md:95,113-116,151,158,170,198-202`; `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt:23-26`; `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:38-44,248-265`; `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/EmailRules.kt:59`; `docs/scheduler.md:53-67,119-147,162-164`; `PLAN.md:30,91,147,208,215`; `app/src/main/java/com/thomaswcode/decrastination/protect/SettingsChanges.kt:61`; `app/src/main/java/com/thomaswcode/decrastination/protect/Totp.kt:104-115`; `app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt:64-74`.
- **Excerpt** (`docs/data-sources.md:151`, one line):

```text
- From yourself → `Admin`, actionable now, effort 15 min unless the LLM says otherwise.
```

- **Current behaviour:** The guide mixes older plans with later as-built amendments. It still describes a dedicated SMTP mailbox sending parent override codes, a dependency-merging Anki permission, a 21:00 quota and card-count completion formula, while current sections/code use different implementations; self-note effort is 10 minutes in code, 15 in the quoted rule.
- **Additional current-contract inconsistencies:** The scheduler says shorter quiet hours are a loosening change, although reducing an unconditional release tightens blocking. It requires request-bound override codes while current TOTP intentionally is not request-bound, and claims the guard automatically disables for incomplete setup although the guard checks armed state. PLAN retains email-code milestones and message-ID wording while current Gmail tasks are keyed by conversation/thread. These statements need explicit historical labels.

- **Additional excerpt** (`docs/scheduler.md`, statements at the locations above; omissions marked):

```text
- **Quiet hours** are the only unconditional release, and shortening them is a "loosening" change subject to the 24-hour delay (§6).
...
- Override codes: a code for request A must not open request B; a code must stop working once the delay has elapsed on its own; the lockout after three wrong entries must survive a restart; a request raised while offline queues the email and still starts the 24-hour clock.
```

- **Description:** Readers cannot reliably distinguish supported current behavior from abandoned design without cross-reading later paragraphs and code. The SMTP claim is especially misleading about credentials/data leaving the app.
- **Trigger/opportunity:** A maintainer uses the guide for setup, threat boundaries, collection behavior or acceptance criteria.
- **Impact/benefit:** Incorrect setup/maintenance assumptions and tests aimed at obsolete contracts. No runtime defect is attributed to the prose alone.
- **Recommended change:** Make one compact current contract authoritative per source/security flow; move older alternatives into dated historical sections and link current code/tests. Align the scheduler acceptance list with school hours, session precedence, monotonic waits and request-agnostic TOTP; keep dated Phase 0 and past milestones explicitly historical. Reconcile the exact contradictory statements while preserving useful Phase 0 evidence as history.
- **Trade-offs:** Historical reasoning remains valuable; do not erase it merely to shorten the guide. Documentation assertions should be kept close to the accepted runtime contract.
- **Effort:** S.
