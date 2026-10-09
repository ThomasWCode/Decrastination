# Improvements

**19 findings: 2 P2 and 17 P3.** The original two entries cover dependency maintenance and critical integration-test coverage. A focused second pass on 9 October 2026 adds **P3-006–P3-022** across UI, data collection and processing, AI workflow, calendar, blocking, protection and recovery. The [bugs and inconsistencies](BUGS_AND_INCONSISTENCIES.md) remain separate.

Reviewed application commit: `a89c7007d71b13bcf997cd700a70881fd3c2ac6d`; the application code is unchanged. See [scope, verification, coverage and full index](README.md). Original finding IDs, priorities, evidence and recommendations are retained. Findings are ordered by priority and expected practical impact.

For the 17 new proposals, **Confirmed by reading** establishes the current behavior or available data, not a measured improvement in usability, performance or model quality. They are P3 product and workflow opportunities, rather than release-blocking defects; implementation effort can still be substantial. Each includes concrete acceptance checks for future implementation. No proposal is implemented in this PR. The P2 dependency entry retains its explicit application-reachability limitation.

<a id="p2-019"></a>

## P2-019 — The shipped dependency graph retains five versions with published security advisories

- **ID:** P2-019
- **Title:** The shipped dependency graph retains five versions with published security advisories
- **Priority and category:** P2 — Dependency and build hygiene / security maintenance.
- **Status:** Verified (resolved versions and advisory matches); exploitability through this application is unproven.
- **Locations:**

[gradle/libs.versions.toml:17-18](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/gradle/libs.versions.toml#L17-L18):

```toml
anthropic = "2.34.0"
okhttp = "4.12.0"
```

[gradle/libs.versions.toml:37](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/gradle/libs.versions.toml#L37):

```toml
anthropic-java = { group = "com.anthropic", name = "anthropic-java", version.ref = "anthropic" }
```

[app/build.gradle.kts:78](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/build.gradle.kts#L78):

```kotlin
    implementation(libs.anthropic.java)
```

[app/build.gradle.kts:46-49](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/build.gradle.kts#L46-L49):

```kotlin
    packaging {
        resources {
            // The Anthropic SDK's Apache HTTP jars each carry these; nothing reads them at run time.
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:34-39](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt#L34-L39):

```kotlin
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt:24-29](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt#L24-L29):

```kotlin
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/PhotoChecker.kt:33-38](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/PhotoChecker.kt#L33-L38):

```kotlin
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()
```

- **Description:** The resolved debug runtime graph contains 121 dependency coordinates. Matching those exact versions against OSV on 9 October 2026 produced 20 advisory IDs across the five coordinates below, transitively supplied by the Anthropic SDK. These are confirmed dependency-version matches, deduplicated into one dependency-maintenance finding; they are not 20 verified application exploits. The application explicitly constructs the OkHttp client. Apache classes are present in the packaged graph, but an application path using the vulnerable Apache behavior was not established. Jackson advisories require particular parsers, target types, or mapper features; their presence alone does not prove those prerequisites in the SDK's use.

  | Resolved coordinate | Matched advisory IDs |
  |---|---|
  | `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-3pjw-73gf-8qr5](https://osv.dev/vulnerability/GHSA-3pjw-73gf-8qr5), [GHSA-5gvw-p9qm-jgwh](https://osv.dev/vulnerability/GHSA-5gvw-p9qm-jgwh), [GHSA-5jmj-h7xm-6q6v](https://osv.dev/vulnerability/GHSA-5jmj-h7xm-6q6v), [GHSA-cxp5-3px4-pw24](https://osv.dev/vulnerability/GHSA-cxp5-3px4-pw24), [GHSA-gx83-3vf8-gh7j](https://osv.dev/vulnerability/GHSA-gx83-3vf8-gh7j), [GHSA-hgj6-7826-r7m5](https://osv.dev/vulnerability/GHSA-hgj6-7826-r7m5), [GHSA-j3rv-43j4-c7qm](https://osv.dev/vulnerability/GHSA-j3rv-43j4-c7qm), [GHSA-mhm7-754m-9p8w](https://osv.dev/vulnerability/GHSA-mhm7-754m-9p8w), [GHSA-q4xh-88c3-wmh7](https://osv.dev/vulnerability/GHSA-q4xh-88c3-wmh7), [GHSA-rmj7-2vxq-3g9f](https://osv.dev/vulnerability/GHSA-rmj7-2vxq-3g9f), [GHSA-vvgp-rfg2-7rr6](https://osv.dev/vulnerability/GHSA-vvgp-rfg2-7rr6), [GHSA-wjgm-6hv5-3cvf](https://osv.dev/vulnerability/GHSA-wjgm-6hv5-3cvf), [GHSA-wv8q-qhhj-9h54](https://osv.dev/vulnerability/GHSA-wv8q-qhhj-9h54) |
  | `com.fasterxml.jackson.core:jackson-core:2.18.2` | [GHSA-72hv-8253-57qq](https://osv.dev/vulnerability/GHSA-72hv-8253-57qq), [GHSA-7hhh-6rmp-j9qf](https://osv.dev/vulnerability/GHSA-7hhh-6rmp-j9qf), [GHSA-p6pp-m3f8-5c89](https://osv.dev/vulnerability/GHSA-p6pp-m3f8-5c89), [GHSA-r7wm-3cxj-wff9](https://osv.dev/vulnerability/GHSA-r7wm-3cxj-wff9) |
  | `org.apache.httpcomponents.client5:httpclient5:5.3.1` | [GHSA-hjcp-jmpx-g3qm](https://osv.dev/vulnerability/GHSA-hjcp-jmpx-g3qm) |
  | `org.apache.httpcomponents.core5:httpcore5-h2:5.2.4` | [GHSA-v3jc-474w-2wm6](https://osv.dev/vulnerability/GHSA-v3jc-474w-2wm6) |
  | `org.apache.httpcomponents.core5:httpcore5:5.2.4` | [GHSA-hf6x-8p5f-cgmf](https://osv.dev/vulnerability/GHSA-hf6x-8p5f-cgmf) |

- **When it occurs:** Build the documented debug variant with the reviewed dependency catalog and resolve `debugRuntimeClasspath`; these versions enter that runtime graph. Confirming an application vulnerability requires an additional source-to-sink review or targeted reproduction demonstrating each advisory's input and configuration prerequisites. No such exploit was assumed from the scanner results.
- **Impact:** The distributed app carries dependencies for which parser limits, type/field restrictions, or HTTP resource handling have published fixes. This creates a concrete security-maintenance gap and avoidable exposure if an affected feature is reachable or enabled later. The evidence does not justify attributing arbitrary deserialization, SSRF, or Apache HTTP denial of service to the app today. P2 reflects verified affected dependencies with unresolved reachability rather than a proven normal-use compromise.
- **Recommended fix:** Prefer an Anthropic SDK update compatible with this Android/Kotlin toolchain that resolves the advisories; otherwise explicitly align/upgrade its transitive dependencies after compatibility testing. The saved advisories identify Jackson `2.18.11` as the newest required patch floor within the existing 2.18 line for these Jackson matches, HttpClient `5.6.3`, and HttpCore/HttpCore-H2 `5.4.3`; select an internally compatible set and check the current advisory database again before adoption. Remove unused Apache components only after proving the SDK does not need them. Add CI scanning of resolved runtime coordinates and retain reachability notes for any intentionally deferred match. Rerun existing AI serialization/client tests and Android build/lint after upgrading.
- **Effort:** M.

<a id="p2-020"></a>

## P2-020 — Critical Android lifecycle and permission paths have no automated integration coverage

- **ID:** P2-020
- **Title:** Critical Android lifecycle and permission paths have no automated integration coverage
- **Priority and category:** P2 — test coverage / regression risk on critical paths.
- **Status:** Confirmed by reading (complete tracked-file inventory, test sources, dependency declarations and CI; the existing JVM tests and Android lint/build were run successfully).
- **Locations:**

[app/build.gradle.kts:58-60](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/build.gradle.kts#L58-L60):

```kotlin
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
```

[app/build.gradle.kts:80-83](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/build.gradle.kts#L80-L83):

```kotlin
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
```

[.github/workflows/ci.yml:31-38](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/.github/workflows/ci.yml#L31-L38):

```yaml
      - name: Unit tests
        run: ./gradlew testDebugUnitTest --console=plain

      - name: Lint
        run: ./gradlew lintDebug --console=plain

      - name: Build debug APK
        run: ./gradlew assembleDebug --console=plain
```

- **Description:** The 386 existing tests validate substantial pure logic and mocked network behavior, but no automated suite drives an Activity recreation, service disconnect/reconnect, real alarm/notification identity, Android permission enforcement, or Compose state restoration. Android framework calls in local tests are configured to return default values. Building and linting Android test models is not execution of an Android test suite. These are core blocker, timer and protection behaviors, not peripheral screens.
- **When it occurs:** A change breaks lifecycle wiring or the relationship between framework state and the tested pure policy. CI can remain green because it runs only the local unit-test task, lint and APK assembly. The exported-tab, deferred service-callback and enrollment-state findings in this review illustrate the missing boundaries; their individual implementation root causes are documented separately.
- **Impact:** Release checks provide no repeatable evidence that the app still blocks, protects settings, ends sessions or restores its screens correctly on Android. Historical handset checks in the docs help establish past behavior but do not provide regression protection. P2 follows the requested priority for missing tests on critical paths.
- **Recommended fix:** Add a small emulator/instrumentation or suitable Robolectric suite for Activity intents/recreation, session alarm identity, service reconnect cleanup and permission denial. Add Compose tests for editor scrolling/state and labeled controls. Keep a separate, explicit Samsung handset matrix for OEM accessibility/Settings behavior that an emulator cannot prove, and run the automated suite in CI alongside the existing unit tests.
- **Effort:** L.

<a id="p3-006"></a>

## P3-006 — Bring source freshness and recovery into the Plan and compact widget

- **ID:** P3-006
- **Title:** Bring source freshness and recovery into the Plan and compact widget
- **Priority and category:** P3 — UI / data confidence and recovery
- **Status:** Confirmed by reading. Current status placement and widget size behavior are explicit in code and existing tests; the proposed presentation has not been validated on a device.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:95-99](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt#L95-L99) — the empty state depends only on the absence of a planned next chunk:

```kotlin
if (next == null) {
    Text("Nothing to do", style = MaterialTheme.typography.headlineSmall)
    Text("No work is planned. New tasks appear as the sources are read.", style = MaterialTheme.typography.bodyMedium)
    return@Column
}
```

[app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:69-75](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt#L69-L75) — useful per-source context already exists, on Tasks:

```kotlin
Text("${source.label} · $count open", style = MaterialTheme.typography.titleSmall)
val read = status.lastSuccessAt?.let { "Read ${Format.ago(it, now)}" } ?: "Not read yet"
val asOf = status.dataAsOf?.let { " · its data from ${Format.ago(it, now)}" }.orEmpty()
Text(read + asOf, style = MaterialTheme.typography.bodySmall)
status.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
status.error?.let {
    Text("Last read failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
```

[app/src/main/java/com/thomaswcode/decrastination/widget/NextWidget.kt:167-171](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/widget/NextWidget.kt#L167-L171) — compact layouts intentionally omit the warning line:

```kotlin
fun of(width: Float, height: Float, hasWarning: Boolean): WidgetLayout {
    val headlineLines = if (height >= 150) 2 else 1
    val then = height >= 76
    val summary = height >= 100
    val warning = hasWarning && height >= 120
```

- **Description:** The data needed to judge a plan's freshness is available, but it is most complete in Tasks and Setup. Plan shows planned work without the source-health context, and a short widget deliberately prioritizes the task over warning text. A user using only these primary surfaces has to visit another tab to distinguish a newly empty plan from one based on old or not-yet-read sources. This is an opportunity to make existing information easier to reach, not a claim that sync failures delete tasks or that empty-plan behavior violates the current contract.
- **When it occurs:** First launch before all sources have been read; a manual sync with one failed source; an old Teams snapshot; or use of a 2×1/short widget during a source error.
- **Impact:** A compact, actionable health indicator could improve confidence in an empty plan and shorten recovery from an integration failure. No effect on task accuracy or blocking policy is claimed without further testing.
- **Recommended fix:** Derive a shared read-only health model from `SourceStatus` plus coordinator/WorkManager run state that distinguishes **not read**, **reading**, **current**, **old snapshot**, and **last read failed; previous tasks retained**. Put a small status summary on Plan, especially its empty state, opening source-specific details with **Retry this source** and **Setup** links. After a manual sync, show a short partial-success summary tied to that run. For compact widgets, reserve an accessible status indicator that opens the same details without replacing the primary task action; retain protection problems as the highest-priority warning. Keep Teams' local read time separate from its upstream snapshot time, and preserve the deliberate Teams sync/countdown policy when offering recovery. Do not infer completion, remove tasks, weaken blocking or poll more frequently merely because the display says data is old.
- **Effort:** M
- **Acceptance checks:** Cover first-read, success, partial failure with retained tasks, old Teams data, recovery and protection-warning precedence. Check compact and tall widget layouts with large text and a screen reader; verify the source retry targets only the chosen source and that a Teams takeover request follows existing consent/countdown rules. Do not label a source current solely because a cached provider read succeeded.

Implementation connection: reuse source run state from P3-012; this entry concerns its presentation. P3-007 explains deliberate planning exclusions, a separate reason for open work to be absent from Plan.

<a id="p3-007"></a>

## P3-007 — Explain placement decisions and show work outside the current plan

- **ID:** P3-007
- **Title:** Explain placement decisions and show work outside the current plan
- **Priority and category:** P3 — Planning transparency / UI / data processing
- **Status:** Confirmed by reading. Existing planner tests also verify intentional deferral at the horizon; no usability benefit has been measured.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:226-227](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt#L226-L227):

```kotlin
// A block whose window opens past the plan's reach waits to be planned until it's within it.
val placeable = items.filter { item -> item.notBefore?.let { date(it, zone) <= horizon } ?: true }
```

[app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:314-322](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt#L314-L322):

```kotlin
item.chunks.indices.forEach { i ->
    val day = assigned[i] ?: return@forEach
    placed.getValue(day) += chunk(item, i, behind = behind[i] || day > dueBy)
}
note(item, assigned)
}

val buckets = days.map { DayBucket(it, capacity.getValue(it), placed.getValue(it).sortedWith(ORDER)) }
return Plan(input.now, today, buckets, events.sortedWith(compareBy(nullsLast()) { it.dueAt }))
```

[app/src/main/java/com/thomaswcode/decrastination/core/Plan.kt:54-60](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Plan.kt#L54-L60):

```kotlin
data class Plan(
    val now: Long,
    val today: LocalDate,
    val buckets: List<DayBucket>,
    /** Events (a call, an open day): reminders, not work to place. */
    val events: List<TaskItem>,
) {
```

[app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:95-98](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt#L95-L98):

```kotlin
if (next == null) {
    Text("Nothing to do", style = MaterialTheme.typography.headlineSmall)
    Text("No work is planned. New tasks appear as the sources are read.", style = MaterialTheme.typography.bodyMedium)
    return@Column
```

[app/src/test/java/com/thomaswcode/decrastination/core/PlannerTest.kt:435-440](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/test/java/com/thomaswcode/decrastination/core/PlannerTest.kt#L435-L440):

```kotlin
val emails = (1..61).map { task("email$it", null, effort = 15, kind = Kind.Admin, firstSeen = Fixtures.at("2026-10-08T17:00")) }
val plan = plan(emails, "2026-10-08T17:00")
val perDay = plan.buckets.map { b -> b.chunks.sumOf { it.minutes } }
assertTrue(perDay.all { it <= 60 }, "$perDay")
assertEquals(15, plan.buckets.size)
assertEquals(60, plan.ordered.size)
```

- **Description:** The planner deliberately limits the horizon and daily allowances, omits future windows outside that horizon, and leaves undated or per-day-limited pieces unassigned when they cannot fit within that horizon or their ordering window. Ordinary deadline work that fits nowhere is instead placed on its earliest day and marked behind; this proposal must explain that over-capacity placement too. Its output exposes placed buckets and events, plus basic chunk urgency flags, but no structured accounting for work excluded or deferred and no explanation of the estimate, calibrated margin, competing capacity and limits that led to a date. Tasks still retains the underlying work and shows task-level `availableFrom`, so this is an opportunity to explain existing behavior rather than an allocation fix.
- **When it occurs:** Open the Plan tab with the test's 61 undated 15-minute tasks: 60 chunks fit the 15-day horizon while one remains outside the plan. Another existing case at `PlannerTest.kt:230-236` has a first block opening on 16 January 2027 when planning on 8 October 2026; no chunk is returned. More routinely, a deadline several days away can produce a chunk today because of a margin or capacity constraint without the screen stating the cause.
- **Impact:** A user comparing Tasks with Plan must infer why work is absent, deferred, or already urgent. A reasoned breakdown could make the deterministic plan easier to trust and make schedule problems actionable. This is P3 because intentional scheduling constraints and source completion semantics should remain unchanged; the enhancement supplies explanations.
- **Recommended fix:** Extend the pure planner result with per-task/per-piece decisions: placed date, effective deadline and whether it is soft, applied margin, base and calibrated remaining effort, and a bounded reason enum for exclusions such as `notAvailableYet`, `outsideHorizon`, `dailyLimit`, `noCapacityWithinLimitedWindow` or `coveredByAnki`. Keep partially scheduled remainders explicitly rather than only returning assigned chunks. Add a Plan details sheet and a small "Not scheduled yet" section linking back to the task. Show the next eligible date where known and distinguish no open work from open work waiting for its window. Generate explanations from the same decisions used for allocation, not a second implementation that can drift. Preserve source-owned deadlines, source-verified completion, strict blocking and the existing horizon/daily limits.
- **Effort:** M
- **Acceptance checks:** Existing plans and block pressure are identical before/after adding diagnostics; the 61-task fixture reports 60 placed and one unplaced task; beyond-horizon windows have a reason and eligibility date; partial tasks account for every remaining piece without double counting; capacity explanations account for calendar overlap, elapsed time and day loads; unavailable work is never presented as complete.

<a id="p3-008"></a>

## P3-008 — Make task enrichment inspectable and correctable without losing source authority

- **ID:** P3-008
- **Title:** Make task enrichment inspectable and correctable without losing source authority
- **Priority and category:** P3 — AI workflow / task usability.
- **Status:** Confirmed by reading.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt:14-19](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt#L14-L19) — enrichment records a task hash, provider and timestamp:

```kotlin
data class Enrichment(
    /** What it was made from: a different hash means the task has changed since. */
    val inputHash: String,
    /** Who made it: the rules, or the model's id. */
    val by: String,
    val at: Long,
```

[app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:105-108](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt#L105-L108) — the expanded task gives a next action and broad attribution:

```kotlin
if (expanded) {
    // The enrichment's step only while it's of the email as it is: a new message's own rules' step otherwise.
    (Enrichments.current(task)?.nextStep ?: task.extra[GmailThreads.EXTRA_NEXT_STEP])?.let { Text("Next: $it", style = MaterialTheme.typography.bodySmall) }
    task.enrichment?.takeIf { it.by != RuleEnricher.BY }?.let { Text("Steps and estimate by Claude", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
```

[app/src/main/java/com/thomaswcode/decrastination/core/Task.kt:148](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Task.kt#L148) — the data model already supports a user estimate:

```kotlin
val effortMin: Int get() = userEffortMin ?: aiEffortMin ?: sourceEffortMin ?: kind.defaultEffortMin
```

- **Description:** The task view shows the effective estimate, steps, raw detail and a general model label, but has no explanation of which individual fields came from the source, rules or enrichment, no source-versus-inferred comparison, and no correction/reconsideration action. `userEffortMin` exists in the model but production searches find no UI setter. A user can read the source text but cannot inspect or correct the interpretation through this screen.
- **When it occurs:** Inspect an enriched task whose deadline, effort, availability or steps need checking against its instructions, or whose estimate no longer reflects the work. Open its expanded Tasks row: the available mutation is a Gmail step's existing Done action, not an interpretation editor.
- **Impact:** This limits the user's ability to understand and improve a plan that is based on an inference. The potential benefit is clearer explanations and a supported correction path; no rate of incorrect inferences or time saved was measured in this pass.
- **Recommended fix:** Add an interpretation panel showing source values, effective values, current enrichment time, and the reason each override was selected. Persist bounded evidence references for inferred dates or obligations (validated against the current input text, not unconstrained model reasoning). Start with the existing user estimate field and a rate-limited Reconsider action that shows the affected fields before applying a replacement. Preserve the existing source-completion authority and date precedence, including the earlier inferred test-date and Gmail interpretation rules; do not let corrections create completion credit. Freeze steps used by an active session, invalidate evidence when the input hash changes, and stage interpretation replacements and estimate corrections in an explicit pending-change type when they can reduce blocking while armed, applying only after the existing delay or parent authorization. The existing Settings-only change path does not already cover these task objects. Distinguish rules fallback from a usable model interpretation. This requires a small task-detail workflow and a durable correction policy rather than simply adding an editable number.
- **Effort:** L.
- **Acceptance checks:** A task with source, model and user estimates explains the winning value; a source refresh preserves a valid user estimate while invalidating stale evidence; correcting a task during a session cannot move its active step; reconsideration obeys model holds, cost gating and duplicate-request suppression; a reduced estimate cannot bypass armed protection or earn credit.

<a id="p3-009"></a>

## P3-009 — Add a calendar review inbox with explicit answer scope and duration

- **ID:** P3-009
- **Title:** Add a calendar review inbox with explicit answer scope and duration
- **Priority and category:** P3 — Calendar / planning data quality.
- **Status:** Confirmed by reading.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt:108-110](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt#L108-L110) — unanswered questions are offered only once per key:

```kotlin
/** Of the events the rules can't judge, those to ask about now: not over, within the week, not asked before. */
fun questions(candidates: List<CalendarEvent>, asked: Set<String>, now: Long): List<CalendarEvent> =
    candidates.filter { it.end > now && it.start < now + ASK_AHEAD_MS && EventJudge.key(it) !in asked }.distinctBy(EventJudge::key)
```

[app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt:127-131](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt#L127-L131) — the offered choices have one fixed intermediate duration:

```kotlin
val answers = listOf(
    "All of it" to EventJudge.store(EventJudge.Judgement.Busy),
    "A few hours" to EventJudge.store(EventJudge.Judgement.Load(EventJudge.FEW_HOURS_MIN)),
    "None" to EventJudge.store(EventJudge.Judgement.Free),
)
```

[app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt:52-56](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt#L52-L56) — answers deliberately apply across matching names:

```kotlin
/**
 * The key your answer is kept under: its name, so a weekly lesson is asked about once; one
 * with no name, its own event, so untitled events aren't all answered as one.
 */
fun key(event: CalendarEvent): String = event.title.trim().lowercase().ifEmpty { "#${event.id}" }
```

- **Description:** Calendar judgments currently use notification actions. An answer applies to every event with the same normalized title, and A few hours means 180 minutes. The Setup calendar row only grants permission or explains the feature; there is no event list for outstanding questions, inspecting saved judgments, changing an answer, choosing a precise duration, or choosing whether the answer applies once or repeatedly. Existing `EventJudgeTest` explicitly checks the same-name behavior, so that behavior is intentional, not itself classified as a defect here.
- **When it occurs:** A notification has been dismissed, an all-day event needs 90 rather than 180 minutes, or two same-named events have different study-time implications. The current UI does not provide these distinctions.
- **Impact:** The user cannot refine the calendar input to match these circumstances within the app. A review inbox would make the planner's capacity deductions inspectable and provide a persistent place to answer; the separate travel-keyword correctness finding remains separate.
- **Recommended fix:** Add an upcoming-events view listing source calendar, event dates, judgment origin, capacity deducted and unresolved questions. Offer a bounded custom number of minutes, edit/reset, and scope choices such as this occurrence, this series, or explicitly all matching titles. Store occurrence identity using provider calendar/event identifiers plus instance start; do not silently equate a recurring series with a title shared across calendars. Keep notifications as shortcuts into the same state. Recompute calendar capacity and use the existing `Briefing.replanToday`/`Days.replan` path to preserve already-recorded work; do not rerun the initial daily snapshot. Stage edits, resets and scope expansions in a new protected pending-change type when armed, with the existing delay or parent authorization before committing them. The current Settings-only pending mechanism does not cover `eventAnswers`. Either increasing or decreasing available capacity can move chunks out of today/tomorrow, so neither Free nor Busy is automatically a tightening; use a conservative protection policy for ambiguous changes. Migrate existing title answers as visible legacy rules rather than dropping them.
- **Effort:** L.
- **Acceptance checks:** A dismissed question remains answerable in-app; a 90-minute load affects only the selected day; an occurrence-only answer leaves another same-named event unchanged; a deliberate title rule retains today's behavior; editing or resetting recomputes capacity without erasing work already completed; multi-day and daylight-saving fixtures retain their existing date semantics.

<a id="p3-010"></a>

## P3-010 — Make prompt upgrades repeatable with semantic cases and revision tracking

- **ID:** P3-010
- **Title:** Make prompt upgrades repeatable with semantic cases and revision tracking
- **Priority and category:** P3 — AI workflow / quality evaluation.
- **Status:** Confirmed by reading.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt:148-150](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt#L148-L150) — freshness checks task input and rules/model state, not a prompt revision:

```kotlin
fun stale(task: TaskItem, modelOn: Boolean, rules: String, modelOff: Boolean = false): Boolean {
    val e = task.enrichment ?: return true
    return e.inputHash != inputHash(task) || (modelOn && e.by == rules) || (modelOff && e.by != rules)
```

[app/src/test/java/com/thomaswcode/decrastination/enrich/EnrichTest.kt:429-431](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/test/java/com/thomaswcode/decrastination/enrich/EnrichTest.kt#L429-L431) — client tests provide the expected answer through a mock response:

```kotlin
fun `it asks Opus 5 point 5 at high effort for the schema's answer, with fallbacks on`() = runBlocking {
    server.enqueue(reply("""{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-12T08:30","effortMin":10,"nextStep":"Sign the form"}"""))
    val result = enricher().enrich(email(), Enrichments.Job.Email, NOW)
```

[docs/data-sources.md:182-183](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/docs/data-sources.md#L182-L183) — the documentation records a separate historical semantic trial:

```text
- **Tried without the API**: the client against a stand-in server in the JVM tests (the request's model, effort, schema and fallback checked, and the answers read), and on the phone against a stand-in on the PC (`ai-check`): the SDK runs on Android, all three jobs. The prompts were tried on 18 real items by Opus through the subscription (`ai-prompts`, kept in the git-ignored `private/`).
- **What that showed** (8 emails, 8 assignments, 2 planner items: 9 good, 6 doubtful, 3 wrong), and what was changed:
```

- **Description:** The checked-in enrichment tests exercise rules, schemas, input construction, parser bounds and SDK request/response handling with supplied model answers. The documentation describes a historical real-item trial kept privately, but this is not a repeatable checked-in semantic evaluation set. Stored enrichment is tied to a task hash and provider string; changing the prompt without changing task text is not a distinct revision in its freshness decision. This makes prompt improvements and decisions about updating existing interpretations harder to evaluate and audit. The historical trial was not reproduced here and its counts are not current-model accuracy measurements.
- **When it occurs:** A prompt is revised to handle a new email/assignment pattern or the chosen model changes, while already-enriched tasks remain unchanged. Existing contract tests can still pass without showing whether the changed prompt preserves intended meaning on representative cases.
- **Impact:** Maintainers lack a compact repeatable comparison of semantic behavior and an explicit record of which prompt produced a saved interpretation. This is an opportunity to improve upgrade confidence; it does not establish a current production misclassification or justify reprocessing every task.
- **Recommended fix:** Introduce a prompt/schema revision recorded with each enrichment and a small curated set of synthetic or explicitly redacted cases. Give each case expected facts and allowed uncertainty: actionable obligations, date windows, source precedence, vocabulary linkage, and acceptable effort bounds rather than exact wording. Run saved-answer parsing and deterministic planner outcomes offline in normal checks. Provide a separate explicitly invoked, budget-limited model evaluation that compares revisions and records model, prompt revision, acceptance results and cost; do not send private fixtures or run paid calls in ordinary CI. After a reviewed improvement, queue only affected open tasks for optional reevaluation through the existing stale-input/session/cost gates, preserving valid prior interpretations until replacements are validated. Versioning must not create a paid reprocessing loop on every app update.
- **Effort:** M.
- **Acceptance checks:** The offline corpus runs without a key or network; a model-evaluation run cannot exceed its explicit budget; saved interpretations identify their prompt revision; bumping unrelated UI code schedules no model work; a targeted revision reevaluates an affected open task at most once while retaining a valid prior result if the new response is refused or unusable; source authority and active-session step identity remain intact.

<a id="p3-011"></a>

## P3-011 — Preview exactly which app and site rules will apply

- **ID:** P3-011
- **Title:** Preview exactly which app and site rules will apply
- **Priority and category:** P3 — Blocking configuration and explainability.
- **Status:** Confirmed by reading. The configuration and matching behavior are established below; the usability benefit is a proposal, not a measured result.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:162-170](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L162-L170) — site rules are edited as lines of text:

```kotlin
var text by remember(asked.blockedSites) { mutableStateOf(draft.blockedSites.joinToString("\n")) }
OutlinedTextField(
    value = text,
    onValueChange = { value ->
        text = value
        draft = draft.copy(blockedSites = value.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct())
    },
    label = { Text("One per line: a site, or a site and path") },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
```

[app/src/main/java/com/thomaswcode/decrastination/block/Blocklist.kt:82-87](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/Blocklist.kt#L82-L87) — matching is specific to host, subdomain, and path boundaries:

```kotlin
/** The blocked site [address] is on, or null. A site matches its subdomains, and a path its sub-paths. */
fun blockedSite(address: Address, sites: List<String>): String? = sites.firstOrNull { site ->
    val host = site.substringBefore('/').lowercase()
    val path = site.drop(host.length).lowercase()
    (address.host == host || address.host.endsWith(".$host")) && (path.isEmpty() || under(address.path.lowercase(), path))
}
```

[app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:115-118](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt#L115-L118) — site matching operates on a parsed address:

```kotlin
fun siteTarget(browser: String, addressBar: String?): Target.Site? {
    val address = addressBar?.let(Blocklist::address) ?: return null
    return Blocklist.blockedSite(address, settings.value.blockedSites)?.let { Target.Site(it, browser) }
}
```

- **Description:** Settings provides a site text field, browser switches, and an explanatory note, but no read-only way to try an address against the configured rules. Knowing that a browser is checked, blocked outright, or outside site coverage is a separate question from whether a site pattern matches. A preview would make these distinctions visible before a change is submitted. This is an enhancement to the present configuration UI, not a claim that the documented matcher is wrong.
- **When it occurs:** Configuring a path-limited service such as `bbc.co.uk/iplayer`, checking whether `www.bbc.co.uk/bitesize` stays available, or comparing the same URL in Chrome and a browser without readable-address support. The existing `BlocklistTest` cases in [app/src/test/java/com/thomaswcode/decrastination/block/BlockingTest.kt:15-36](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/test/java/com/thomaswcode/decrastination/block/BlockingTest.kt#L15-L36) demonstrate these distinctions.
- **Impact:** A user or parent could check intended coverage without repeatedly opening a distracting service. The expected benefit is clearer configuration and fewer accidental broad or ineffective rules; no reduction in such mistakes has been measured.
- **Recommended fix:** Add an app/browser selector and a local “Try an address” field. Reuse `Blocklist.address`, `blockedSite`, the current app/browser lists, and `BlockPolicy.decide` to show (1) matching rule or unmatched/invalid input, (2) browser coverage mode, and (3) whether today's current policy would block or spend earned time. Label the result a rule preview, since it cannot establish that an Android browser's address bar is actually readable. Allow separate previews of effective and requested settings. Keep this calculation read-only; applying removals must still use `AppGraph.changeSettings` and the existing parent-code/delay rules.
- **Effort:** M.
- **Acceptance checks:** Reuse the subdomain/path/look-alike examples above; show whole-browser blocking ahead of site matching; explicitly identify a browser outside site coverage; verify previews never modify settings, pending changes, credit, or browsing history. Actual browser-readability claims need device checks separately.

<a id="p3-012"></a>

## P3-012 — Coalesce sync requests and isolate independent source work

- **ID:** P3-012
- **Title:** Coalesce sync requests and isolate independent source work
- **Priority and category:** P3 — Data collection / responsiveness / scheduling
- **Status:** Confirmed by reading. Existing tests verify timeout isolation and Anki's read dependency; end-to-end latency improvement is proposed, not measured.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:81-88](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt#L81-L88):

```kotlin
suspend fun sync(only: Set<Source>? = null): SyncReport = lock.withLock {
    var report = SyncReport()
    for (source in sources) {
        if (only != null && source.source !in only) continue
        report += readOne(source)
    }
    if (report.completed.isNotEmpty() || report.reopened.isNotEmpty() || report.added.isNotEmpty() || report.missed.isNotEmpty()) {
        listeners.forEach { it(report) }
```

[app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:98-104](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt#L98-L104):

```kotlin
val reading = readers.async { source.read(context) }
val read = try {
    withTimeout(timeoutMs) { reading.await() }
} catch (e: TimeoutCancellationException) {
    reading.cancel()
    onFailure(source.source, e)
    return failed(source.source, startedAt, "No answer in ${timeoutMs / 1000} s")
```

[app/src/main/java/com/thomaswcode/decrastination/sync/SyncWorker.kt:69-74](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sync/SyncWorker.kt#L69-L74):

```kotlin
fun syncNow(context: Context, sources: Set<Source>? = null) {
    val request = OneTimeWorkRequestBuilder<SyncWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(workDataOf(KEY_SOURCES to sources?.joinToString(",") { it.name }))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
```

[app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:333-335](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt#L333-L335):

```kotlin
}.onFailure { Log.w(TAG, "Can't watch the Teams widget", it) }
scope.launch {
    teamsChanged.debounce(TEAMS_QUIET_MS).collect { syncer.sync(setOf(Source.Teams, Source.Anki)) }
```

[app/src/test/java/com/thomaswcode/decrastination/sync/SyncerTest.kt:89-95](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/test/java/com/thomaswcode/decrastination/sync/SyncerTest.kt#L89-L95):

```kotlin
fun `Anki is read last, and sees what the others just found`() = runTest {
    val anki = FakeSource(Source.Anki) { SourceRead(emptyList(), ankiDay = AnkiDay("2026-10-08", 12, "Textbook 1::1.2")) }
    val teams = FakeSource(Source.Teams) { items("vocab") }
    syncer(listOf(anki, teams)).sync()
    assertEquals(listOf("teams:vocab"), anki.seen.single().known.map { it.id })
    assertEquals(12L, tasks.value.ankiDay?.deckId)
}
```

- **Description:** Sync correctly preserves failed sources and serializes merges, but its lock covers the whole multi-source read and callbacks. Sources run sequentially with a default timeout of 90 seconds each. Separately, explicit WorkManager requests append to a chain while Teams provider changes call the same syncer directly. The app can make pending source sets explicit and combine duplicate work, so unrelated source refreshes need not wait behind a slow network read or cause another complete pass immediately afterward. The main Sync button already disables itself while its work chain is pending; the opportunity concerns the multiple scheduling paths and source dependencies, not a claim that repeated button taps are currently unguarded.
- **When it occurs:** A periodic or explicit all-source read overlaps a Teams provider notification or an Anki/settings/credential refresh, or a network source waits until timeout while a local source has new data. `Syncer.sync` currently waits for each prior source and for the global lock. Multiple `syncNow` calls from different callers append work even when their source sets overlap.
- **Impact:** Potentially faster visibility of local changes and less redundant network work. This is P3 because current reads have bounded waits and failure isolation; no deadlock or loss of source data is asserted.
- **Recommended fix:** Add one source-aware coordinator that maintains an in-flight set and a union of pending source requests. Coalesce identical requests already covered by a sufficiently recent/in-flight read, while retaining one follow-up when a source-change event arrives after that source's read started. Run independent Teams, Power Planner and Gmail reads with bounded concurrency, commit each successful snapshot as it finishes through serialized state updates, and read Anki after the relevant parent homework has merged. Do not wait for every independent read before publishing a local success; keep the Anki dependency barrier separate. Preserve full snapshot validation, exactly-once completion rewards and cancellation semantics. Distinguish local work from network work so offline network retry/backoff does not postpone local provider reads; keep a manual refresh capable of retrying immediately. Expose per-source running/queued/result state for UI feedback and measure latency before choosing concurrency limits.
- **Effort:** L
- **Acceptance checks:** Concurrent duplicate requests invoke each source only as needed, but a change during an in-flight read triggers one follow-up; a stalled remote source does not prevent a requested independent local read; Anki sees newly merged homework; partial failures keep old tasks; cancellations cannot commit stale answers; completion listeners and rewards remain exactly once; process death retains essential requested work; offline/reconnect behavior respects WorkManager's network lifetime requirements.

<a id="p3-013"></a>

## P3-013 — Connect task context and source handoff through one reusable detail view

- **ID:** P3-013
- **Title:** Connect task context and source handoff through one reusable detail view
- **Priority and category:** P3 — UI / task workflow
- **Status:** Confirmed by reading. The separate navigation paths below are implemented; the benefit of consolidating them is a proposal, not a measured usability result.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:56-60](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt#L56-L60) — a Plan row opens the source immediately:

```kotlin
val open = { chunk: Chunk ->
    tasks.tasks.firstOrNull { it.id == chunk.taskId }?.let { task ->
        scope.launch { TaskOpener.open(activity, task)?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() } }
    }
    Unit
```

[app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:105-109](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt#L105-L109) — explanatory detail instead lives in an expanded row on another tab:

```kotlin
if (expanded) {
    // The enrichment's step only while it's of the email as it is: a new message's own rules' step otherwise.
    (Enrichments.current(task)?.nextStep ?: task.extra[GmailThreads.EXTRA_NEXT_STEP])?.let { Text("Next: $it", style = MaterialTheme.typography.bodySmall) }
    task.enrichment?.takeIf { it.by != RuleEnricher.BY }?.let { Text("Steps and estimate by Claude", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    task.subSteps.forEachIndexed { index, step ->
```

[app/src/main/java/com/thomaswcode/decrastination/ui/TaskOpener.kt:64-67](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TaskOpener.kt#L64-L67) — Gmail handoff is an app launch with a transient explanatory message, not a conversation deep link:

```kotlin
private fun openGmail(context: Context, task: TaskItem): String {
    if (!launch(context, GMAIL)) return "Gmail isn't installed"
    val from = task.extra[GmailThreads.EXTRA_FROM]?.let { ", from $it" }.orEmpty()
    return "In Gmail: \"${task.title}\"$from"
```

- **Description:** Task context and task execution are split across surfaces. The Plan gives an immediate **Open**, whereas inspecting instructions, all blocks, availability and the model's next step requires finding the same task in Tasks. Gmail and Power Planner cannot reliably open the exact item through the implemented integrations; persistent identifying details would support that existing limitation without inventing a deep-link capability.
- **When it occurs:** A user sees a planned chunk and wants to inspect its instructions before switching apps, locates an email follow-up in Tasks and wants to open Gmail, or returns from a fallback launch after forgetting which subject or assignment to find.
- **Impact:** A shared detail view could reduce tab switching and repeated task lookup, and make source handoff recoverable after the toast disappears. This is an efficiency and clarity improvement; the current direct-open workflow remains useful.
- **Recommended fix:** Extract a task-detail component keyed by stable `TaskItem.id`, showing title/source, source instructions, ordered blocks with their dates, current next-step text, and persistent source-opening guidance. Add a secondary **Details** action to the Plan's next card and an **Open in [source]** action to expanded Tasks rows. A larger widget can offer a secondary detail action while preserving its existing primary tap-to-open behavior. Return to the same task detail after the external activity, retaining scroll/search context. Reuse `TaskOpener` for the actual launch; show its failure/fallback message in the detail view, with an explicit **Copy title** action if useful. Keep the existing Gmail-only manual block action and all source verification and earned-time rules; the new surface must not create a generic **Mark task done** action.
- **Effort:** M
- **Acceptance checks:** Open the same task from Plan, Tasks and a widget secondary action and verify identical up-to-date detail; handle a task disappearing during sync; exercise missing source apps and Teams fallback; verify Gmail subject/sender and Power Planner title remain available after returning; confirm direct widget opening and source-based completion behavior stay unchanged. Check a long assignment at large font size on a handset.

Implementation connection: use this detail view to present the independent planning explanation (P3-007) and interpretation controls (P3-008).

<a id="p3-014"></a>

## P3-014 — Show AI work and spending by purpose and outcome

- **ID:** P3-014
- **Title:** Show AI work and spending by purpose and outcome
- **Priority and category:** P3 — AI workflow / observability.
- **Status:** Confirmed by reading.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt:15-23](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt#L15-L23) — usage is one monthly aggregate:

```kotlin
val month: String = "",
val spentUsd: Double = 0.0,
val calls: Int = 0,
/** Calls the model declined (its safety classifiers), counted in [calls]. */
val refused: Int = 0,
/** Calls that failed (no network, a bad key); not counted in [calls]. */
val failed: Int = 0,
val lastError: String? = null,
val lastCallAt: Long? = null,
```

[app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:447-450](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt#L447-L450) — enrichment candidates and ordering exist only within the run:

```kotlin
val candidates = tasks.value.tasks
    .mapNotNull { task -> Enrichments.jobFor(task)?.let { task to it } }
    .filter { (task, _) -> Enrichments.stale(task, enricher != null, RuleEnricher.BY, modelOff = claudeKey() == null) }
    .sortedBy { (task, _) -> task.dueAt ?: Long.MAX_VALUE }
```

[app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:482-487](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt#L482-L487) — a paid response may yield a usable answer or rules fallback:

```kotlin
result?.let { r -> runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).record(r.costUsd, r.refused, at)) } }
when {
    result?.enrichment != null -> result.enrichment
    // Declined, or no answer it could read: the rules' say, under the model's name.
    result != null -> rules.enrich(task, job, now).enrichment?.copy(by = enricher?.by ?: RuleEnricher.BY)
    else -> null
```

- **Description:** Enrichment, weekly reviews and photo checks contribute to the same counters. Setup exposes enablement, a recent error and monthly spend, while Stats gives spend and call count. Neither surface explains how much each purpose consumed, how many tasks await enrichment, or whether a particular operation produced an accepted result, a refusal, an unusable answer, or rules fallback. The existing `AiUsageTest` verifies aggregate counts and holds, so this is a richer operational view rather than a replacement of those safeguards.
- **When it occurs:** Several tasks arrive together, the cap or one-hour failure hold is active, or the user wants to understand a month containing task enrichment, photo checks and reviews. A total cost alone cannot answer which feature used it or what work remains.
- **Impact:** Troubleshooting and choosing whether the optional model is useful require inference from task contents and global status. Per-purpose information could make budget choices and waiting states understandable; this pass did not establish any excess-spend rate or cost saving.
- **Recommended fix:** Add a bounded local operation record with purpose, task/input hash or review week, start/end, requested and actual response model, accepted/fallback/refused/failed outcome, cost from returned usage, and a non-sensitive reason code. Derive pending/running/held counts from the existing candidate selection and WorkManager state. Present a compact AI activity view with spend per purpose and an explicit capped/resting/off state. Do not retain prompts, raw responses, credentials or photos merely for telemetry, and do not automatically retry refusals or invalid responses. Keep all calls behind the existing single cost gate; limit a user-requested Retry to transient failures, with deduplication and the same hold/cap checks. Do not clear the paid-refusal/unusable-answer freshness marker just to retry; deliberate interpretation replacements belong in the preview and protected reconsideration flow proposed separately. This is complementary to the separate billing-correctness findings, not their fix.
- **Effort:** M.
- **Acceptance checks:** A mocked enrichment, review and photo check appear under distinct purposes and reconcile to the existing monthly total; fallback is distinguishable from accepted output; a changed or completed task leaves the queue; a cap or failure hold explains the queue without generating another call; retained records contain no task body, key or image bytes.

<a id="p3-015"></a>

## P3-015 — Explain learned estimates with sample support and experiment state

- **ID:** P3-015
- **Title:** Explain learned estimates with sample support and experiment state
- **Priority and category:** P3 — Learning / explainability.
- **Status:** Confirmed by reading.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt:47-51](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt#L47-L51) — learning returns current values and prose changes:

```kotlin
val multipliers = multipliers(log.completions)
val margins = margins(log.completions, defaultMargin)
val boxes = boxes(log, defaultBox, week)
val next = Calibration(multipliers = multipliers, marginDays = margins, boxMin = boxes)
return Learned(next, describe(previous, next, defaultMargin, defaultBox))
```

[app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt:144-152](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt#L144-L152) — the box selector has support and exploration information locally:

```kotlin
for ((kind, sessions) in log.sessions.filter { it.box != null && judged(it) }.groupBy { it.kind }) {
    val byBox = sessions.groupBy { it.box!! }
    fun mean(box: Int): Double = byBox[box]?.let { list -> list.count(::rewarded).toDouble() / list.size } ?: 0.5
    val best = candidates.maxWith(compareBy<Int>({ mean(it) }, { if (it == defaultBox) 1 else 0 }))
    // The seed scrambled first: small seeds a week apart would draw alike.
    val random = Random(java.util.Random(week * 31 + kind.ordinal).nextLong())
    // Settled on sessions at the boxes still tried: an old box of yours doesn't settle it.
    val counted = sessions.count { it.box in candidates }
    val choice = if (counted < BOX_SETTLED && random.nextDouble() < EXPLORE) candidates.filter { it != best }.random(random) else best
```

[app/src/main/java/com/thomaswcode/decrastination/learn/Stats.kt:103-109](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/Stats.kt#L103-L109) — the display receives values but not their support:

```kotlin
    "$what: takes %.1f× the estimate".format(Locale.UK, value)
}
val margins = calibration.marginDays.entries.sortedBy { it.key.ordinal }.map { (kind, days) ->
    "${kind.label}: finished $days day${if (days == 1) "" else "s"} before the deadline"
}
val boxes = calibration.boxMin.entries.sortedBy { it.key.ordinal }.map { (kind, minutes) -> "${kind.label}: cut into $minutes-minute pieces" }
return multipliers + margins + boxes
```

- **Description:** Stats shows learned multipliers, margins and box sizes, and weekly notes describe value changes. The learner does not return how many eligible observations supported a value, which observations were excluded, or whether a box was selected as an exploration trial. A value based on a small history and a value supported by many observations have the same display shape. The algorithm is already deterministic and bounded; this proposal adds evidence, not a claim that its current values are mathematically wrong.
- **When it occurs:** After an early completion first changes a multiplier, when a weekly review selects an exploratory box, or when a user wants to understand why a class's tasks now take more planned time.
- **Impact:** The user sees the resulting assumption without enough context to judge how established it is. Showing sample support could help distinguish a provisional learning choice from a repeatedly observed pattern. Predictive accuracy or completion-rate benefits have not been measured.
- **Recommended fix:** Return a deterministic support report alongside calibration: timed versus assessment-only completion counts, exclusions, date range, before/after values, and per-box judged/pending/rewarded counts with an exploration flag. Expose this under each learned value and in weekly review details. Label limited evidence directly using counts rather than inventing a confidence percentage. Keep replayed values, existing bounds and source-confirmed outcome rules unchanged initially; use the support report to evaluate any later minimum-sample or decay policy against retained logs before changing learning behavior. If a reset or rollback is added, make it a protected change rather than a shortcut to reduce work.
- **Effort:** M.
- **Acceptance checks:** Replaying the same log and week gives identical calibration and evidence; a session awaiting source completion is shown as pending; manually ticked work is excluded from timed support; an exploratory choice is labeled as such; a value falling back after records age out says so; the current deterministic calibration tests continue to pass unchanged.

<a id="p3-016"></a>

## P3-016 — Show effective and pending protection settings together

- **ID:** P3-016
- **Title:** Show effective and pending protection settings together
- **Priority and category:** P3 — Settings UX and protection transparency.
- **Status:** Confirmed by reading. The current pending-change representation is established; improved comprehension remains a proposed benefit.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:68-72](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L68-L72) — controls start from the requested state, including changes still waiting:

```kotlin
val saved by graph.settings.state.collectAsStateWithLifecycle()
val runtime by graph.runtime.state.collectAsStateWithLifecycle()
// What's been asked for, waiting changes included: setting one back cancels it.
val asked = SettingsChanges.requested(saved, runtime.pending)
var draft by remember { mutableStateOf(asked) }
```

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:208-215](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L208-L215) — the explanation is a separate list after the controls:

```kotlin
if (runtime.pending.isNotEmpty()) {
    item { Section("Waiting (protection is armed)") }
    items(runtime.pending, key = { it.id }) { change ->
        Note(
            "${change.description}: applies about ${Format.at(change.applyAt, graph.clock.now(), graph.clock.zone())}, and is shown above as if it had. " +
                "Set it back and save to cancel it; a parent code on the protection screen applies it now.",
        )
    }
```

[app/src/main/java/com/thomaswcode/decrastination/protect/SettingsChanges.kt:195-201](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/SettingsChanges.kt#L195-L201) — remaining delay is accumulated uptime:

```kotlin
fun applyDue(settings: Settings, pending: List<PendingChange>, now: Long, elapsedMs: Long): Outcome {
    val counted = pending.map { change ->
        val waited = change.waitedMs + elapsedMs.coerceAtLeast(0)
        change.copy(waitedMs = waited, applyAt = now + (change.waitMs - waited).coerceAtLeast(0))
    }
    val (due, waiting) = counted.partition { it.waitedMs >= it.waitMs }
    return Outcome(due.fold(settings, ::apply), waiting)
```

- **Description:** The app correctly keeps requested and effective settings separate, but the editor presents the requested value in its main control and explains the distinction farther down. A pending app removal therefore appears switched off in the editor while the effective blocker still covers it. The dedicated protection screen already offers Cancel and Parent code; these should be easier to discover from the affected setting. This proposal is separate from the concurrent-save defect P2-008.
- **When it occurs:** Protection is armed and a user removes an app/site, extends quiet hours, or requests another delayed change, then returns to Settings before its wait ends. A reboot or powered-off interval also makes a simple wall-clock estimate less useful than the remaining uptime.
- **Impact:** Expected reduction in uncertainty about what is active and why an apparently saved preference has not taken effect. Existing enforcement is deliberately retained; no security failure is alleged.
- **Recommended fix:** Add an inline “Active now → Requested” summary and pending badge to each affected field or list row. Show remaining powered-on time from `waitMs - waitedMs`, with the existing `applyAt` only as an estimate. Offer a clearly labeled Cancel request action and a link to the existing parent-code flow. Keep unsaved edits visually separate from submitted pending changes. Use a read-only view model derived from saved settings and `runtime.pending`, with changes still routed through the existing serialized methods.
- **Effort:** M.
- **Acceptance checks:** An armed app removal must show “blocked now; removal waiting” until applied; tightening must show immediately active; cancellation must remove only its own pending request; changing device time or rebooting must not shorten the enforced wait. Existing rule tests at [app/src/test/java/com/thomaswcode/decrastination/protect/ProtectTest.kt:110-165](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/test/java/com/thomaswcode/decrastination/protect/ProtectTest.kt#L110-L165) supply baseline examples.

<a id="p3-017"></a>

## P3-017 — Surface protection recovery history and the next recovery step

- **ID:** P3-017
- **Title:** Surface protection recovery history and the next recovery step
- **Priority and category:** P3 — Protection observability and recovery UX.
- **Status:** Confirmed by reading. Existing state and displayed summaries are confirmed; operational benefit requires later device validation.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/data/RuntimeState.kt:61-69](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/data/RuntimeState.kt#L61-L69) — recovery timing is already persisted:

```kotlin
/** Since when something has been wrong; null while all is well. */
val offSince: Long? = null,
val checkedAt: Long? = null,
/** Since when the service has been switched on but not running (crashed). */
val stoppedSince: Long? = null,
/** When the watchdog last switched a stopped service off and on. */
val restartedAt: Long? = null,
/** Restarts in a row that it hasn't stayed up after; back to 0 once it's seen running. */
val restartTries: Int = 0,
```

[app/src/main/java/com/thomaswcode/decrastination/protect/Watchdog.kt:202-208](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/Watchdog.kt#L202-L208) — observed problem transitions and repairs are retained locally:

```kotlin
// Each change, both ways, so the log shows how long each lapse lasted; and each repair, with
// what it put right, even when the check ends where the last one did.
if (repaired || problems != before.problems) {
    val recorded = if (repaired) found else problems
    graph.log.update { it.copy(protection = it.protection + ProtectionRecord(now, recorded, repaired)).trimmed(now) }
    Log.i(TAG, "Protection: ${recorded.ifEmpty { listOf("all well") }}${if (repaired) " (repaired)" else ""}")
}
```

[app/src/main/java/com/thomaswcode/decrastination/ui/StatsScreen.kt:65-70](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/StatsScreen.kt#L65-L70) — Stats collapses protection history to counts:

```kotlin
Line(
    when {
        stats.protectionProblems == 0 -> "Protection: no problems found."
        else -> "Protection: problems found ${stats.protectionProblems} time${if (stats.protectionProblems == 1) "" else "s"}, put right ${stats.protectionRepaired}."
    },
)
```

- **Description:** The protection screen shows the current checklist/problems and Stats shows totals, while the app stores when checks occurred, when a lapse started, and recovery attempts. A local diagnostic view could explain whether the app is waiting through the initial grace period, awaiting another automatic attempt, missing a permission, or observed running again. This does not replace the existing watchdog or resolve the separately documented reconnection/locale defects.
- **When it occurs:** Investigating an intermittent blocking outage after an app/OS update, reconnect, or permission change, particularly while an automatic restart is scheduled. The present next-restart rule is explicit in [app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionCheck.kt:103-107](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionCheck.kt#L103-L107) and is tested in [app/src/test/java/com/thomaswcode/decrastination/protect/ProtectTest.kt:394-409](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/test/java/com/thomaswcode/decrastination/protect/ProtectTest.kt#L394-L409).
- **Impact:** A user could distinguish “waiting for a scheduled recovery” from “permission needed” and see whether a recurring problem was observed again. Reduced diagnosis time is a proposed benefit, not a measured result or a claim of continuous protection between checks.
- **Recommended fix:** Extend the existing Protection screen with last-checked time, observed service state, next automatic recovery time (computed with the same `ProtectionCheck.restartAt` rule), and a bounded recent-events list from `log.protection`. Label intervals as observed rather than exact outage durations. Add a “Check now” action that refreshes the same watchdog/report path while respecting its existing retry pacing. If showing restart outcomes, record explicit attempt/result events instead of inferring success from an attempted restart. Keep details local and omit task content, browser addresses, keys, and authenticator data.
- **Effort:** M.
- **Acceptance checks:** Show unknown/never checked distinctly from healthy; accurately display grace, retry, permission-denied, recovery and no-recovery states; opening or refreshing the view must not disable protection or bypass cooldowns; time labels must remain honest when only periodic observations exist. Verify the resulting flow on the supported handset before claiming recovery reliability.

<a id="p3-018"></a>

## P3-018 — Preview backup contents and restore effects before applying them

- **ID:** P3-018
- **Title:** Preview backup contents and restore effects before applying them
- **Priority and category:** P3 — Recovery workflow / usability. This is an optional improvement for valid backups; invalid-value acceptance is separately covered by P2-009.
- **Status:** Confirmed by reading. Current import flow and merge rules were inspected; no file-picker interaction or proposed preview was exercised on Android.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt:88-96](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt#L88-L96):

```kotlin
val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
        restoreNote = runCatching {
            val bytes = withContext(Dispatchers.IO) {
                val input = requireNotNull(activity.contentResolver.openInputStream(uri)) { "no file to read" }
                input.use { Backups.read(it) }
            }
            if (bytes == null) "That file is too big to be a backup." else graph.importBackup(bytes.decodeToString())
```

[app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:389-394](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt#L389-L394):

```kotlin
val waiting = runtime.value.pending.size
val waits = if (waiting > 0) " $waiting change${if (waiting == 1) "" else "s"} that loosen blocking wait ${settings.value.loosenDelayHours} hours." else ""
if (armed) return "Settings restored.$waits Protection is armed, so the log and what the app learned were left as they are."
log.update { Backups.mergeLog(it, backup.log, clock.now()) }
// Your answers on this phone win over the backup's.
runtime.update { it.copy(calibration = backup.calibration, eventAnswers = backup.eventAnswers + it.eventAnswers) }
```

[app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt:18-26](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt#L18-L26):

```kotlin
val app: String = APP,
val format: Int = FORMAT,
val exportedAt: Long,
/** The app's version that wrote it, for reading it later. */
val versionName: String = "",
val settings: Settings,
val log: ActivityLog,
val calibration: Calibration = Calibration(),
val eventAnswers: Map<String, String> = emptyMap(),
```

- **Description:** Selecting a file starts the import immediately; the outcome text comes after mutation. The existing backup already includes export time and app version, and restore has useful but nontrivial semantics: settings are proposed through protection, log records are deduplicated, local calendar answers win, calibration is replaced only while unarmed, and same-month usage cannot decrease. A preview could make these effects visible before committing a valid older file. This does not allege that the documented merge behavior is incorrect.
- **When it occurs:** The user has multiple valid backups, restores a file made under different settings, or restores while protection is armed and only part of the backup can apply.
- **Impact:** The user currently has to inspect the JSON or apply the file to discover its detailed effect. A preview would support a deliberate restore choice; reduced restore mistakes have not been measured.
- **Recommended fix:** Split decoding/validation and a pure `RestorePreview` calculation from application. Show export date/version, changed settings with current/proposed values, immediately applicable versus delayed changes, unique log additions, and calibration/calendar sections that will be kept or replaced. Keep the selected decoded data in memory until explicit Apply or Cancel; recompute against current state at Apply so a stale preview cannot override newer changes. Offer an ordinary export of the current state before proceeding, using the existing file picker. Preserve the armed/key-state exclusions, monotonic usage merge and delayed-loosening path. Show actual additions, rather than backup totals, in the result.
- **Acceptance checks:** Preview and Cancel leave every store unchanged. Reimporting the same valid file shows zero new log records. Armed/unarmed previews match their actual permitted effects; a state change during preview prompts a refreshed comparison. Existing `BackupTest` cases for round-trip, deduplication, protection and spend remain green.
- **Effort:** M.

<a id="p3-019"></a>

## P3-019 — Add lightweight search and filters to the task inventory

- **ID:** P3-019
- **Title:** Add lightweight search and filters to the task inventory
- **Priority and category:** P3 — UI / task discovery
- **Status:** Confirmed by reading. Tasks currently renders every source group and every retained finished item with no search or filter controls; search usefulness at larger inventories is a proposed benefit.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:45-51](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt#L45-L51) — open items are grouped by source and sorted only by deadline:

```kotlin
LazyColumn(Modifier.fillMaxWidth()) {
    for (source in Source.entries) {
        val open = state.tasks.filter { it.source == source && it.isOpen }.sortedWith(compareBy(nullsLast()) { it.dueAt })
        item(key = "header-$source") { SourceHeader(source, state.status(source), open.size, now) }
        items(open, key = { it.id }) { TaskRow(it, now, zone, tick) }
    }
    val finished = state.tasks.filter { !it.isOpen }.sortedByDescending { it.doneAt ?: it.lastSeenAt }
```

[app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:99-104](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt#L99-L104) — future work and retained archived-email follow-ups are already recognizable in the data:

```kotlin
task.availableFrom?.takeIf { it > now }?.let {
    Text("Hidden from the plan until ${Format.at(it, now, zone)}", style = MaterialTheme.typography.bodySmall)
}
if (task.isOpen && task.extra[Merge.EXTRA_FOLLOW_UP] == "true") {
    Text("Archived in Gmail: kept for its blocks till they're done", style = MaterialTheme.typography.bodySmall)
}
```

- **Description:** The source-grouped inventory was intentionally built as a raw/debug view. It remains useful for inspection, but looking up one task, a subject, an archived follow-up or a future-dated block requires scrolling through unrelated source groups. The app already holds the title, detail, class, sender, status and availability fields needed for an in-memory query; no new collection service or database is needed for this improvement.
- **When it occurs:** Several sources have open tasks, the user knows an assignment title or sender but not its position, or the user wants to inspect work hidden from the current plan and archived emails retained for unfinished blocks.
- **Impact:** Search and explicit filters could make obligations easier to locate and clarify why a task exists outside today's plan. This does not claim that the current list is slow or unusable at the current personal inventory size.
- **Recommended fix:** Add a saved local search query over title, class, sender and detail, plus compact source and state chips (**Open**, **Future**, **Follow-ups**, **Finished/missed**). Preserve a clear **All tasks** reset and match count, and retain the current source grouping as the default. Include future sub-step windows (`SubStep.from`/`TaskItem.notBefore`) as well as task-level `availableFrom` in the Future filter. Keep source health visible even when a filter has no matches, distinguishing **No matching tasks** from an empty source. Derive filtered rows from existing immutable task state and preserve the query on rotation and while returning from task detail. These are presentation filters only: excluded rows must still participate in planning, blocking, sync and completion verification.
- **Effort:** S
- **Acceptance checks:** Exercise a mixed fixture with duplicate titles across sources, future blocks, an archived Gmail follow-up and completed/missed items; verify search matches class and sender as well as title; clear filters and recover the full inventory; rotate and return from a source app without losing the query; confirm filtering has no effect on `Plan.pressure` or task state.

<a id="p3-020"></a>

## P3-020 — Reuse unchanged Gmail envelope metadata between full inbox reconciliations

- **ID:** P3-020
- **Title:** Reuse unchanged Gmail envelope metadata between full inbox reconciliations
- **Priority and category:** P3 — Data collection / performance
- **Status:** Confirmed by reading. Repeated envelope work is established; battery, bandwidth and latency savings have not been benchmarked.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:157-165](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L157-L165):

```kotlin
val imap = ImapClient(socket.inputStream, socket.outputStream)
imap.greeting()
imap.login(address, password)
val count = imap.examine("INBOX")
val uids = if (count == 0) emptyList() else imap.uidSearch("ALL").sorted()
val messages = uids.chunked(FETCH_BATCH).flatMap { batch ->
    imap.uidFetch(batch, "UID INTERNALDATE X-GM-MSGID X-GM-THRID X-GM-LABELS ENVELOPE").mapNotNull(GmailThreads::message)
}
if (messages.size < uids.size) throw IOException("Gmail listed ${uids.size} messages but described ${messages.size}")
```

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:97-101](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L97-L101):

```kotlin
/** The text already stored for each conversation, by its newest message's id: read again only when that changes. */
fun knownBodies(known: List<TaskItem>): Map<String, String> =
    known.filter { it.source == Source.Gmail && EXTRA_TEXT_PENDING !in it.extra }
        .mapNotNull { task -> task.extra[EXTRA_MESSAGE_ID]?.let { it to task.detail } }
        .toMap()
```

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Imap.kt:220-224](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Imap.kt#L220-L224):

```kotlin
/** Opens [mailbox] read-only. Returns how many messages it holds. */
fun examine(mailbox: String): Int =
    run("EXAMINE ${quote(mailbox)}", "EXAMINE")
        .firstOrNull { it.values.getOrNull(1)?.string.equals("EXISTS", ignoreCase = true) }
        ?.values?.firstOrNull()?.string?.toIntOrNull() ?: 0
```

[app/src/main/java/com/thomaswcode/decrastination/sync/SyncWorker.kt:63-65](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sync/SyncWorker.kt#L63-L65):

```kotlin
fun schedule(context: Context) {
    val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
```

- **Description:** Gmail already caches bodies by the latest message ID, which is useful, but each successful read still searches the entire inbox and fetches every message's envelope and metadata in batches of 200. This repeats parsing and transfer for unchanged older messages on a nominal 15-minute schedule and on explicit syncs. A metadata cache could retain the complete-snapshot semantics while reducing unchanged envelope work. This recommendation does not replace the existing findings about incomplete bodies or malformed responses.
- **When it occurs:** Repeated syncs of a stable inbox, especially one with many retained conversations. With `N` inbox messages, the source currently requests `ceil(N / 200)` envelope batches on every nonempty read even if every body is cached and no task has changed.
- **Impact:** Potentially shorter Gmail reads and less network, parsing and allocation work, particularly on a large inbox. This is P3 because current full reconciliation is simple and correct in the ordinary case; no measured resource budget breach is claimed.
- **Recommended fix:** First instrument aggregate envelope count, bytes and elapsed time without logging message content, and benchmark representative inbox sizes. If material, expose mailbox `UIDVALIDITY` from `EXAMINE` and cache immutable envelope fields by account, mailbox, UID validity and UID. Continue obtaining an authoritative current UID set so archiving/snoozing/removal is detected. Fetch full metadata for unknown UIDs, refresh mutable labels using a lightweight fetch or a server-supported change mechanism, and build the full `SourceRead` from the validated current UID set plus cache. Invalidate on account or UID-validity changes and use periodic full reconciliation as a fallback. Never pass only changed messages into `Merge.apply`, which interprets missing tasks as completed. Bound cache retention and avoid persistent full bodies beyond the existing policy.
- **Effort:** M
- **Acceptance checks:** A second unchanged sync produces the same tasks with fewer envelope fetches; a new reply, archive, snooze/wake, expunge and account switch match a full-reconciliation reference; changed UID validity drops the cache; incomplete fetches preserve prior tasks rather than marking them done; labels that affect classification stay current; before/after benchmarks establish whether the added cache complexity is justified.

<a id="p3-021"></a>

## P3-021 — Distinguish optional features from required setup and failures

- **ID:** P3-021
- **Title:** Distinguish optional features from required setup and failures
- **Priority and category:** P3 — Onboarding / status communication.
- **Status:** Confirmed by reading. The checklist rendering and disabled-by-default model setting establish the current presentation; no usability study or handset interaction was run.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt:125-132](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt#L125-L132):

```kotlin
val usage = runtime.aiUsage.forMonth(AiUsage.monthOf(graph.clock.now(), graph.clock.zone()))
SetupItem(
    title = "Claude",
    done = settings.aiEnabled && settings.aiKeyActive && Secret.AnthropicApiKey in secrets && usage.lastError == null,
    detail = when {
        Secret.AnthropicApiKey !in secrets -> "No API key: the rules do what they can, and nothing is sent to Claude."
        !settings.aiKeyActive -> "Key saved; it waits like switching Claude on (Settings lists when it applies), so nothing is sent yet."
        !settings.aiEnabled -> "Key saved; switched off in Settings, so nothing is sent."
```

[app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt:139-143](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt#L139-L143):

```kotlin
SetupItem(
    title = "Calendar",
    done = calendarAllowed,
    detail = if (calendarAllowed) "Its events come off your free time: lessons take their slot, trains don't, and you're asked about all-day and long ones." else "Read only, so lessons and plans come off your free time.",
    action = if (calendarAllowed) null else "Allow" to { requestCalendar.launch(CalendarTime.PERMISSION) },
```

[app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt:276-283](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt#L276-L283):

```kotlin
Text(
    when (done) {
        true -> "✓"
        false -> "✗"
        null -> "•"
    },
    style = MaterialTheme.typography.titleLarge,
    color = if (done == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
```

- **Description:** The shared checklist has only done, not-done and neutral states. Deliberately disabled Claude and ungranted optional calendar access receive the same red cross as a failed required source read. The detailed text explains the distinctions, but the scan-level status does not. Claude being off is the documented default and rules remain usable; enabling paid calls is not a prerequisite for a working app.
- **When it occurs:** First launch, continued use with rules only, a deliberate choice to omit calendar access, or comparing a failed integration with an intentionally disabled feature on Setup.
- **Impact:** The checklist offers no quick distinction between actions needed for the chosen features and optional additions. Clearer states could make setup priorities easier to understand without encouraging unnecessary permissions or model activation; user confusion is a hypothesis, not a measured outcome.
- **Recommended fix:** Replace the nullable Boolean presentation with explicit states such as Ready, Needs action, Reading, Optional/off and Waiting for protected change. Group source connections, blocking/protection, optional features and maintenance separately. Give each state a text label and appropriate action, retain the rules-only explanation, and leave optional features off until explicitly enabled. Derive status from existing permission/source/settings data rather than a second persisted readiness flag. Link protection readiness to its own checks and source freshness to the Plan health summary instead of duplicating their logic.
- **Acceptance checks:** A rules-only installation with working sources shows Ready for those sources and Optional/off for Claude; a genuinely failed source still shows Needs action and its error. Enabling a protected optional feature shows Waiting until effective. TalkBack reads the state and feature name together; rotating or returning from permission settings refreshes the state.
- **Effort:** S.

<a id="p3-022"></a>

## P3-022 — Warn before earned free time expires

- **ID:** P3-022
- **Title:** Warn before earned free time expires
- **Priority and category:** P3 — Blocking UX and transition feedback.
- **Status:** Confirmed by reading. The current credit-spending and expiry path is established; the usefulness of a warning is a proposed product improvement.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:266-273](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt#L266-L273) — spending schedules the enforcement check:

```kotlin
private fun startSpending(target: Focus.Target) {
    if (spending?.first == target) return
    commitSpending()
    spending = target to SystemClock.elapsedRealtime()
    // Block again the moment it runs out.
    handler.removeCallbacks(creditCheck)
    handler.postDelayed(creditCheck, graph.focus.creditLeftMs().coerceIn(1_000L, SPEND_TICK_MS))
}
```

[app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:313-322](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt#L313-L322) — the expiry branch covers the blocked content:

```kotlin
if (left > 0 && verdict !is BlockPolicy.Verdict.Block) {
    // Saved as it goes: the service stopped (a crash, the process killed), at most one
    // check's worth is lost, not all of it since spending began.
    commitSpending()
    handler.postDelayed(creditCheck, left.coerceIn(1_000L, SPEND_TICK_MS))
} else {
    // Spent first, so the policy now says blocked: each is covered where it is (in front,
    // in the corner, beside another app).
    stopSpending()
    lookAtScreen()
```

[app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt:60-62](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt#L60-L62) — remaining credit is already visible on the home widget:

```kotlin
today.isEmpty() && tomorrow.isEmpty() -> runtime.credit.on(plan.today).leftMs.takeIf { it > 0 }
    ?.let { "Nothing due soon · ${Format.minutes((it / 60_000L).toInt())} of free time" }
    ?: "Nothing due today or tomorrow"
```

- **Description:** Earned-time balance is available on the widget, block screen, and Stats, but the spending loop does not notify the user shortly before reblocking. A user already inside a covered app must leave it to check that balance. A small advance warning could make the transition easier to anticipate without extending access.
- **When it occurs:** Nothing is due soon, credit is positive, and a covered app or site is being used long enough to consume the remaining balance. The proposal concerns earned-time expiry; newly arriving due-soon pressure must still block immediately under the current policy.
- **Impact:** Expected benefit is time to stop at a natural point before the block screen appears. It does not imply data loss in the current version, and no usability trial has been performed.
- **Recommended fix:** Emit an optional one-shot local notification when actual remaining credit crosses a modest threshold such as one minute. Compute its balance from the same monotonic elapsed-time accounting as `creditCheck`, deduplicate it for the current spending/credit period, and cancel it when spending stops or unconditional access begins. Use a dedicated low-importance notification channel; do not add a snooze, grace period, extra credit, or blocking override. An optional “Return to plan” action can open the existing app without changing state.
- **Effort:** M.
- **Acceptance checks:** Exercise threshold crossing, switching between covered apps, returning from screen-off, fresh credit, midnight rollover, and entry into quiet hours or a parent override. Warnings must not repeat every 30 seconds, spend credit twice, or delay `lookAtScreen`; disabling notifications must leave enforcement identical.
