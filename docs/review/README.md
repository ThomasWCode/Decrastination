# Repository review

- Review date: **10 October 2026**.
- Reviewed revision: **`d1eefaa9948ed7ccb9d814c3113a104fa5fc4bec`**; the initial working tree was clean. The review includes the actual local checkout and its tracked fixtures, not only a patch or selected modules.
- Deliverables: [Bugs and inconsistencies](BUGS-AND-INCONSISTENCIES.md) and [Independent improvements](IMPROVEMENTS.md).
- **33 bugs/weaknesses: P1 3 · P2 27 · P3 3.** **36 improvements: P1 8 · P2 28 · P3 0.** Priority scales are independent: release-impact severity for bugs; expected product benefit relative to effort for improvements.
- **439 existing tests passed in 42 suites; Android lint reported zero issues; the debug APK built.** Fourteen additional isolated probes exercised uncovered behaviors: twelve passed assertions describing the defect, and two failed desired-behavior assertions. Their results are explained below; they are not fourteen baseline test failures.
- Only `docs/review/` was used for review writes. No recommendation was implemented. Temporary probes, build outputs, caches, working notes and progress tracking are removed from the delivered folder; their reproducible source and commands are retained in this overview.

## Scope and method

- Two streams ran for every feature: existing behavior/defect review and an independent opportunity review. All **160 tracked text files** were read completely, including **90 production Kotlin files**, tests, scripts, fixtures, documentation, build configuration and wrapper launchers. Two tracked binary artifacts were inspected by role/configuration, not reverse-engineered. The exact production-file count is verified in the coverage table below.
- Source-to-merge-to-plan-to-enforcement and source-to-AI-to-plan paths were traced across modules. Persistence, settings/protection transactions, focus/reward accounting, calendar interpretation, learning, notifications, widget navigation, recovery and developer tooling were cross-checked with their callers and tests.
- Candidate findings were challenged against existing behavior and product decisions. Duplicate documentation drift was merged. The same health-visibility and accessibility recommendations across app/widget/countdown surfaces were consolidated. A proposed activity-reuse blocking defect was rejected because a material reachable failure was not established. Deliberately hidden informational mail, source read-only access and the committed shared debug key are recognized design decisions rather than automatically reported defects.
- Short excerpts and file/line references anchor every finding. Verified labels refer only to the stated executed boundary; device and live-service behavior is never inferred from JVM success. One settings lazy-state issue remains explicitly Suspected. Exploratory improvements state the needed measurement or provider-contract validation.
- This is systematic code and deterministic-check coverage of the complete relevant checkout, not a proof that no undiscovered defect exists. Live accounts, real model judgments and OEM accessibility behavior require separate evaluation.

## Application and workflow coverage

The app is a personal, sideloaded Android planner/blocker: Kotlin, Jetpack Compose/Glance, kotlinx.serialization JSON stores, WorkManager, Android accessibility/device-admin APIs, read-only source adapters and an Anthropic SDK client. It targets/compiles Android API 36, supports API 26+, and the checked build is version 1.6.0 (code 12). Gradle Kotlin DSL, a version catalog and GitHub Actions define the build; Python and PowerShell support source probing.

| Feature / entry point | Current flow examined | Independent improvement coverage |
|---|---|---|
| Application / AppGraph | Application construction, stores, observers, settings and instruction coordination, plan derivation | Consistent plan-input snapshot, coordinator boundaries, measured startup work |
| Teams / Power Planner / Gmail / Anki | Provider/HTTP/IMAP reads, source status, absence reconciliation, body backlog, card/deck derivation | Source-specific refresh, bounded conversation context, metadata reuse, explicit deck mappings, detail hydration evaluation |
| Planner / instructions | Capacity, deadline/margin order, dates, substeps, task dependencies, availability and seven instruction kinds | Decision explanations, provisional future workload, typed offline controls |
| AI enrichment / photo / reviews | Job eligibility, structured prompts/outputs, validation, fallback, alerts, serialized usage/cap accounting | Semantic evaluations, recipe provenance, per-job resources/queue priority, image preparation |
| Focus / accessibility service | Start/stop/reward journal, URL classification, browser state, PiP/split screen, overlays, delayed Teams offers | Browser coverage visibility, policy-boundary reevaluation, enforcement observation model, free-time countdown, reward history |
| Protection / settings | Arming, device admin/watchdog, settings delays, TOTP, guard heuristics, lifecycle and permissions | Readiness/self-test, credential reuse, clear requested/effective settings |
| Calendar / learning / stats | Event interpretation, capacity subtraction, assessment, calibration, daily/weekly review, retention | Question scope/recovery, forecast coverage, evidence confidence, reversible adaptation trials |
| Screens / widget / notifications | Today, Tasks, Stats, Setup, Settings, Calendar, Instructions, task opening, widget layouts/actions, notification routes | Direct task actions, confidence on every surface, search, accessible layouts, channels/privacy |
| Storage / backup / tooling | Atomic writes, unreadable fallback, secret storage, partial backup scope, parser fixtures, debug commands, CI | Recoverable store incidents, deterministic provider replay, dependency verification and maintenance |

Android entry points are declared in `app/src/main/AndroidManifest.xml`; `DecrastinationApp` builds `AppGraph`, activities expose user actions, `FocusService` observes windows, WorkManager workers drive background reads/enrichment/reviews, widget receivers expose plan actions, and protection receivers/watchdog handle restart and health checks. The debug command activity and account/device probe scripts were read; they were not invoked against user accounts or a device.

## Verification

### Environment and dependency installation

- This review installed **no packages or libraries** and changed no dependency declarations or lockfiles. The successful Gradle run was offline, using already cached artifacts and the installed Android SDK.
- The wrapper is Gradle **9.3.1** and AGP **9.0.1**. `JAVA_HOME` selected the installed Android Studio runtime, **OpenJDK 25.0.2**; compilation targets Java 17. The shell also has a JDK 17 installation, but this run must not be described as a JDK 17 execution or the Linux CI run.
- To set up a fresh machine, install Android Studio/Android SDK, an appropriate JDK (the repository CI uses Temurin 17), SDK platform **Android 36**, platform tools and the build tools required by AGP. Set `JAVA_HOME` and `ANDROID_HOME` for that installation and accept the SDK licenses. No local SDK path file was created by this review.
- Package/library installation is handled by the committed wrapper and `gradle/libs.versions.toml`: on a fresh machine run the check command below **without `--offline`** once to download the pinned dependencies, then use offline mode if the cache is complete. No npm/pip installation is needed for the Kotlin checks. The three Python scripts use Python's standard library; their live use additionally needs their documented account/device setup. Do not run account probes just to validate syntax.

### Executed commands and results

All shell commands ran from the repository root. An init script redirected every project build directory, the project cache and test temporary files into the review directory; its exact contents are preserved in the reproducibility appendix.

```powershell
.\gradlew.bat --offline --no-daemon --no-configuration-cache --no-build-cache --project-cache-dir docs/review/gradle-cache -I docs/review/review.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' testDebugUnitTest lintDebug assembleDebug --console=plain
```

- Result: **BUILD SUCCESSFUL in 3m 43s**, 53 executed tasks. Existing JUnit XML recorded **439 tests, 42 suites, 0 failures, 0 errors, 0 skips**. Lint XML contained **0 issues**. `assembleDebug` produced an APK under the isolated build directory. Kotlin/Java compilation passed; no separate repository type-checker is configured.
- Nonfatal environment/build notices: the installed SDK tooling encountered XML schema version 4 while one component supports version 3; `libandroidx.graphics.path.so` could not be stripped and was packaged intact. Neither prevented the build.
- Before the successful run, an initial init-script expression used unqualified `rootDir` and failed during configuration. After correction, the command above ran successfully. That harness error is not an app failure.

```powershell
.\gradlew.bat --offline --no-daemon --no-configuration-cache --no-build-cache --project-cache-dir docs/review/gradle-cache -I docs/review/review.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' -PreviewProbes testDebugUnitTest --tests '*ReviewProbe' --console=plain
```

- Result: **14 executed review probes, 12 passing defect-observation assertions, 2 failing desired-behavior assertions**, with no errors/skips. The overall Gradle result was failed because the two session regressions intentionally assert the desired result against unchanged code. The baseline 439-test run had already passed separately.
- An earlier source injection into `android.sourceSets.test.java` returned “no tests found.” AGP's built-in Kotlin requires `android.sourceSets.test.kotlin.directories`; the preserved init script uses that corrected interface. [Official built-in Kotlin migration guidance](https://developer.android.com/build/migrate-to-built-in-kotlin).
- Additional syntax/fixture checks: Python `ast.parse` on all three scripts passed without creating `__pycache__`; `System.Management.Automation.Language.Parser.ParseFile` parsed the PowerShell capture script without errors; ElementTree parsed 19 manifest/resource/fixture XML documents; all three JSON fixtures parsed. Two fixture XML files total 150 elements. These checks establish syntax/shape, not live-provider correctness.
- Source preservation was checked by SHA-256 and metadata comparison against the initial 3,938-file snapshot, not just by Git status. The final result is recorded under Preservation below.

| Review probe | Observed result | Finding |
|---|---|---|
| Source percentage with substeps | 60 minutes at 50% plans 30 minutes without steps, 60 with steps | [BUG-P1-001](BUGS-AND-INCONSISTENCIES.md#bug-p1-001) |
| Shrinking Anki counts plus timed work | 45 to 25 remaining cards yields 8 planned minutes instead of source remaining estimate 12 | [BUG-P1-002](BUGS-AND-INCONSISTENCIES.md#bug-p1-002) |
| Short daily limits | 60-minute work under recurring 20-minute day limits yields empty ordered plan, null next, no pressure | [BUG-P2-003](BUGS-AND-INCONSISTENCIES.md#bug-p2-003) |
| Mixed photo/timer calibration | 5 timed minutes plus photo-completed work is treated as complete timed evidence; multiplier becomes 0.7375 | [BUG-P2-016](BUGS-AND-INCONSISTENCIES.md#bug-p2-016) |
| Malformed backup | Negative work survives decode/merge and causes the statistics exception | [BUG-P2-002](BUGS-AND-INCONSISTENCIES.md#bug-p2-002) |
| Rejected email blocks | Synthetic invalid blocks plus Info classification hide both work and dropped-plan alert | [BUG-P2-004](BUGS-AND-INCONSISTENCIES.md#bug-p2-004) |
| Concurrent session starts | Expected 1 displaced-session record; actual 0 (desired-behavior assertion fails) | [BUG-P2-006](BUGS-AND-INCONSISTENCIES.md#bug-p2-006) |
| Same-title selected substep | Real planner selects later step; expected completion [false,true], actual [true,false] (assertion fails) | [BUG-P2-005](BUGS-AND-INCONSISTENCIES.md#bug-p2-005) |
| Missing Power Planner collection | Synthetic success body {} becomes empty source data and finishes prior task; official omission semantics not established | [BUG-P2-007](BUGS-AND-INCONSISTENCIES.md#bug-p2-007) |
| Missing Teams row identity | Skipped malformed identity becomes destructive absence reconciliation | [BUG-P2-007](BUGS-AND-INCONSISTENCIES.md#bug-p2-007) |
| Registration request | Actionable registration mail becomes hidden Event under fallback rules | [BUG-P1-003](BUGS-AND-INCONSISTENCIES.md#bug-p1-003) |
| Automated action request | No-reply imperative request becomes hidden Info | [BUG-P1-003](BUGS-AND-INCONSISTENCIES.md#bug-p1-003) |
| Anki DST rollover | London autumn 03:30/spring 04:30 disagree with local 04:00; spring next rollover is already past | [BUG-P2-018](BUGS-AND-INCONSISTENCIES.md#bug-p2-018) |
| Gmail encoded-byte cap | A markup-heavy 200KB prefix decodes empty yet is cached as whole and not queued again | [BUG-P2-022](BUGS-AND-INCONSISTENCIES.md#bug-p2-022) |

The photo probe tests the constructed completion record and learner; its Android producer mapping was read, not executed. Provider probes use synthetic data and pure adapters/merge paths. They do not establish server behavior, a live IMAP connection, actual AnkiDroid cursor semantics or device automation. No real photos, paid AI calls, mailbox mutations, invitation-token access, account probes, APK installation, release build, emulator/device UI checks, or OEM accessibility tests were performed.

## Dependency advisory lookup

- Parsed **123 unique resolved Maven coordinates** from the isolated debug lint dependency model and queried the [OSV batch API](https://osv.dev/docs/) on the review date. Only public package coordinates/versions were sent; repository contents and account data were not submitted.
- **20 distinct advisory matches across five coordinates** are version-range matches, not twenty established vulnerabilities in this app. All matches are retained below rather than selectively listing alarming examples.
- The app builds Anthropic **OkHttp** clients and uses kotlinx.serialization for its own JSON. The Apache classic/HTTP2 transport paths may be unused; this review did not prove their reachability. Jackson advisories variously depend on async/DataInput parsing, numeric constraints, Java records/naming, ignored/read-only/injected properties or object-ID behavior. Exact SDK parser/configuration reachability remains to be established before assigning exploit severity. No remote-compromise or credential-leak claim is inferred from a dependency match.
- The dependency finding recommends compatible patched-family upgrades and applicability triage; the separate build improvement covers keeping future graph changes/checksums/advisory decisions reviewable.

| Resolved coordinate | Advisory | Published issue summary |
|---|---|---|
| `com.fasterxml.jackson.core:jackson-core:2.18.2` | [GHSA-72hv-8253-57qq](https://nvd.nist.gov/vuln/detail/CVE-2026-18401) | jackson-core: Number Length Constraint Bypass in Async Parser Leads to Potential DoS Condition |
| `com.fasterxml.jackson.core:jackson-core:2.18.2` | [GHSA-7hhh-6rmp-j9qf](https://nvd.nist.gov/vuln/detail/CVE-2026-89425) | jackson-core: UTF8DataInputJsonParser._reportInvalidToken() missing maxErrorTokenLength limit -> unbounded StringBuilder growth (DoS) |
| `com.fasterxml.jackson.core:jackson-core:2.18.2` | [GHSA-p6pp-m3f8-5c89](https://nvd.nist.gov/vuln/detail/CVE-2026-89407) |  jackson-core: ReDoS: quadratic backtracking in NumberInput.PATTERN_FLOAT via looksLikeValidNumber() |
| `com.fasterxml.jackson.core:jackson-core:2.18.2` | [GHSA-r7wm-3cxj-wff9](https://nvd.nist.gov/vuln/detail/CVE-2026-68494) | jackson-core: Async parser maxNumberLength bypass via chunked digit accumulation (incomplete fix for GHSA-72hv-8253-57qq) |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-3pjw-73gf-8qr5](https://nvd.nist.gov/vuln/detail/CVE-2026-59888) | jackson-databind: @JsonIgnore on a Record property is bypassed with a PropertyNamingStrategy |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-5gvw-p9qm-jgwh](https://nvd.nist.gov/vuln/detail/CVE-2026-59889) | jackson-databind: @JsonView bypassed for @JsonUnwrapped container properties on deserialization |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-5jmj-h7xm-6q6v](https://nvd.nist.gov/vuln/detail/CVE-2026-54515) | jackson-databind has case-insensitive deserialization bypasses per-property @JsonIgnoreProperties |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-cxp5-3px4-pw24](https://nvd.nist.gov/vuln/detail/CVE-2026-91777) | jackson-databind quadratic forward-reference completion  |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-gx83-3vf8-gh7j](https://nvd.nist.gov/vuln/detail/CVE-2026-83557) | jackson-databind: Comparable missing from DefaultBaseTypeLimitingValidator's unsafe base types (incomplete PolymorphicTypeValidator denylist) |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-hgj6-7826-r7m5](https://nvd.nist.gov/vuln/detail/CVE-2026-54514) | jackson-databind: InetSocketAddress deserialization triggers eager DNS resolution (SSRF) |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-j3rv-43j4-c7qm](https://nvd.nist.gov/vuln/detail/CVE-2026-54512) | jackson-databind has a PolymorphicTypeValidator bypass via generic type parameters that allows arbitrary class instantiation |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-mhm7-754m-9p8w](https://github.com/advisories/GHSA-mhm7-754m-9p8w) | jackson-databind: `@JsonView` bypass for creator properties with `@JsonTypeInfo(include=As.EXTERNAL_PROPERTY)` |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-q4xh-88c3-wmh7](https://nvd.nist.gov/vuln/detail/CVE-2026-68497) | jackson-databind: Duration XMLGregorianCalendar Unbounded Number Parse DoS |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-rmj7-2vxq-3g9f](https://nvd.nist.gov/vuln/detail/CVE-2026-54513) | jackson-databind has an array subtype allowlist bypass in BasicPolymorphicTypeValidator (allowIfSubTypeIsArray) |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-vvgp-rfg2-7rr6](https://nvd.nist.gov/vuln/detail/CVE-2026-77310) | jackson-databind: Incomplete fix for CVE-2026-54514: eager DNS resolution (SSRF) still present in InetAddress deserialization |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-wjgm-6hv5-3cvf](https://nvd.nist.gov/vuln/detail/CVE-2026-19032) | jackson-databind: Path Deserialization Missing Scheme Allowlist for FileSystemProvider Resolution |
| `com.fasterxml.jackson.core:jackson-databind:2.18.2` | [GHSA-wv8q-qhhj-9h54](https://nvd.nist.gov/vuln/detail/CVE-2026-91776) | jackson-databind retains every unknown raw type ID  |
| `org.apache.httpcomponents.client5:httpclient5:5.3.1` | [GHSA-hjcp-jmpx-g3qm](https://nvd.nist.gov/vuln/detail/CVE-2026-64607) | Apache HttpComponents Client: Connection Leak on Content-Encoding Decode Error Leads to Pool Exhaustion DoS |
| `org.apache.httpcomponents.core5:httpcore5-h2:5.2.4` | [GHSA-v3jc-474w-2wm6](https://nvd.nist.gov/vuln/detail/CVE-2026-54428) | Apache HttpComponents Core: HPackDecoder Unlimited Header List Size Before SETTINGS ACK |
| `org.apache.httpcomponents.core5:httpcore5:5.2.4` | [GHSA-hf6x-8p5f-cgmf](https://nvd.nist.gov/vuln/detail/CVE-2026-54399) | Apache HttpComponents Core HTTP/1 header parsing can cause memory-exhaustion denial of service |

## Bugs and inconsistencies summary

**P1: 3 · P2: 27 · P3: 3 · Total: 33.**

| ID | Title | Priority | Status | Primary location |
|---|---|---|---|---|
| [BUG-P1-001](BUGS-AND-INCONSISTENCIES.md#bug-p1-001) | Source percentage progress is ignored once an item has steps | P1 | Verified | `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:464-481` |
| [BUG-P1-002](BUGS-AND-INCONSISTENCIES.md#bug-p1-002) | Anki count updates subtract completed timed work a second time | P1 | Verified | `app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:95-108` |
| [BUG-P1-003](BUGS-AND-INCONSISTENCIES.md#bug-p1-003) | The fallback email classifier silently hides actionable messages | P1 | Verified | `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/EmailRules.kt:51-76` |
| [BUG-P2-001](BUGS-AND-INCONSISTENCIES.md#bug-p2-001) | Backups omit unrecoverable local planning rules and task state | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt:10-28` |
| [BUG-P2-002](BUGS-AND-INCONSISTENCIES.md#bug-p2-002) | Backup decoding accepts semantic corruption that later crashes statistics | P2 | Verified | `app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt:49-51,94-102` |
| [BUG-P2-003](BUGS-AND-INCONSISTENCIES.md#bug-p2-003) | A task can disappear indefinitely when daily limits are shorter than its chunks | P2 | Verified | `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:338-341,369-387,483-491` |
| [BUG-P2-004](BUGS-AND-INCONSISTENCIES.md#bug-p2-004) | Rejected email blocks can hide both the task and its warning | P2 | Verified | `app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt:223-239` |
| [BUG-P2-005](BUGS-AND-INCONSISTENCIES.md#bug-p2-005) | Same-title substeps cannot be independently completed by a session | P2 | Verified | `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:214-217` |
| [BUG-P2-006](BUGS-AND-INCONSISTENCIES.md#bug-p2-006) | Concurrent starts can overwrite a focus session without ending it | P2 | Verified | `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:161-165` |
| [BUG-P2-007](BUGS-AND-INCONSISTENCIES.md#bug-p2-007) | Incomplete provider responses can be committed as successful absence | P2 | Verified | `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt:45-53,73-83,116-120` |
| [BUG-P2-008](BUGS-AND-INCONSISTENCIES.md#bug-p2-008) | Public fixture capture retains sensitive source data and overwrites the tracked copy | P2 | Confirmed by reading | `fixtures/teams_widget_state.json:1` |
| [BUG-P2-009](BUGS-AND-INCONSISTENCIES.md#bug-p2-009) | Resolved SDK dependencies include versions matched by published vulnerability advisories | P2 | Verified | `gradle/libs.versions.toml:17-17` |
| [BUG-P2-010](BUGS-AND-INCONSISTENCIES.md#bug-p2-010) | Automatic Teams sync can start after its interruption safeguards become false | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:633-648` |
| [BUG-P2-011](BUGS-AND-INCONSISTENCIES.md#bug-p2-011) | Recreating the camera caller silently deletes a valid captured photo | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:128-138` |
| [BUG-P2-012](BUGS-AND-INCONSISTENCIES.md#bug-p2-012) | Parent-code setup survives rotation while its secret does not | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:103-104` |
| [BUG-P2-013](BUGS-AND-INCONSISTENCIES.md#bug-p2-013) | Unsaved settings and contextual instructions disappear on activity recreation | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:65-70` |
| [BUG-P2-014](BUGS-AND-INCONSISTENCIES.md#bug-p2-014) | Saving a settings draft can overwrite unrelated changes made while it was open | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:65-69` |
| [BUG-P2-015](BUGS-AND-INCONSISTENCIES.md#bug-p2-015) | The monthly cap's worst-call bound does not cover every request's input | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:88-100` |
| [BUG-P2-016](BUGS-AND-INCONSISTENCIES.md#bug-p2-016) | Mixed photo and timer completions train effort from an incomplete duration | P2 | Verified | `app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt:59-74` |
| [BUG-P2-017](BUGS-AND-INCONSISTENCIES.md#bug-p2-017) | Anki homework schedules mix note counts with card limits | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt:39-43,89-95` |
| [BUG-P2-018](BUGS-AND-INCONSISTENCIES.md#bug-p2-018) | The Anki day switches at the wrong local time on DST dates | P2 | Verified | `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:74-75,163-165,271-273` |
| [BUG-P2-019](BUGS-AND-INCONSISTENCIES.md#bug-p2-019) | A slow Gmail initial load can time out forever without saving progress | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:213-220,233-239,262` |
| [BUG-P2-020](BUGS-AND-INCONSISTENCIES.md#bug-p2-020) | Repeated timeouts accumulate uncancellable provider reads | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:65-67,98-108` |
| [BUG-P2-021](BUGS-AND-INCONSISTENCIES.md#bug-p2-021) | Gmail connection setup lacks explicit socket cleanup on failure | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:196-207,243-254` |
| [BUG-P2-022](BUGS-AND-INCONSISTENCIES.md#bug-p2-022) | The Gmail byte cap can cache an incomplete message as fully read | P2 | Verified | `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:78-80,151-152,233-239,264-268` |
| [BUG-P2-023](BUGS-AND-INCONSISTENCIES.md#bug-p2-023) | External permission grants leave Setup and Calendar showing stale denied state | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt:68-80` |
| [BUG-P2-024](BUGS-AND-INCONSISTENCIES.md#bug-p2-024) | Session countdowns disagree with the clock that actually ends sessions | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt:64-65` |
| [BUG-P2-025](BUGS-AND-INCONSISTENCIES.md#bug-p2-025) | The Android enforcement layer has no automated transition coverage | P2 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:192-214` |
| [BUG-P2-026](BUGS-AND-INCONSISTENCIES.md#bug-p2-026) | The Teams capture script can destroy its good fixture after an adb failure | P2 | Confirmed by reading | `scripts/pull_teams_state.ps1:7-13,16-26` |
| [BUG-P2-027](BUGS-AND-INCONSISTENCIES.md#bug-p2-027) | A recycled invalid settings field can leave Save disabled with no visible error | P2 | Suspected | `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:69-76` |
| [BUG-P3-001](BUGS-AND-INCONSISTENCIES.md#bug-p3-001) | Fallback model answers are attributed to the requested model | P3 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:31-32,65-66,109-116` |
| [BUG-P3-002](BUGS-AND-INCONSISTENCIES.md#bug-p3-002) | Capacity advice promises fewer behind tasks after reducing hours | P3 | Confirmed by reading | `app/src/main/java/com/thomaswcode/decrastination/learn/Days.kt:121-130` |
| [BUG-P3-003](BUGS-AND-INCONSISTENCIES.md#bug-p3-003) | Current guides mix superseded protection and source contracts with implemented behavior | P3 | Confirmed by reading | `docs/data-sources.md:95,113-116,151,158,170,198-202` |

## Improvements summary

**P1: 8 · P2: 28 · P3: 0 · Total: 36.**

| ID | Title | Priority | Status | Primary location |
|---|---|---|---|---|
| [IMP-P1-001](IMPROVEMENTS.md#imp-p1-001) | Provide one task action surface for opening work, starting focus and checking completion | P1 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:72-76` |
| [IMP-P1-002](IMPROVEMENTS.md#imp-p1-002) | Return planning explanations and a visible unplanned workload | P1 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/core/Plan.kt:60-91` |
| [IMP-P1-003](IMPROVEMENTS.md#imp-p1-003) | Turn unreadable-state fallback into a recoverable, visible incident | P1 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt:18-26,51-57,60-79` |
| [IMP-P1-004](IMPROVEMENTS.md#imp-p1-004) | Add a semantic AI evaluation set for actual task decisions | P1 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt:45-73,220-297` |
| [IMP-P1-005](IMPROVEMENTS.md#imp-p1-005) | Make browser enforcement coverage visible and model unknown windows explicitly | P1 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:88-115` |
| [IMP-P1-006](IMPROVEMENTS.md#imp-p1-006) | Offer a protection-readiness check and explicit guard self-test before arming | P1 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:182-244` |
| [IMP-P1-007](IMPROVEMENTS.md#imp-p1-007) | Show plan confidence and source health in the app and every widget size | P1 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:164-167` |
| [IMP-P1-008](IMPROVEMENTS.md#imp-p1-008) | Reevaluate blocking at known policy boundaries and meaningful state changes | P1 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:599-629` |
| [IMP-P2-001](IMPROVEMENTS.md#imp-p2-001) | Offer deterministic controls for the seven supported instruction changes | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/core/Instructions.kt:59-81,124-127,167-225` |
| [IMP-P2-002](IMPROVEMENTS.md#imp-p2-002) | Schedule and coalesce refreshes around each source's actual dependencies | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:65,81-90` |
| [IMP-P2-003](IMPROVEMENTS.md#imp-p2-003) | Give email classification bounded conversation context, not only the latest body | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:93-105,129-143` |
| [IMP-P2-004](IMPROVEMENTS.md#imp-p2-004) | Forecast work that is not yet available or waits on another task | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:164-177` |
| [IMP-P2-005](IMPROVEMENTS.md#imp-p2-005) | Align calendar coverage with the forecast horizon | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt:42-44,94` |
| [IMP-P2-006](IMPROVEMENTS.md#imp-p2-006) | Make homework-to-Anki mappings explicit and reviewable | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:59-63,114-122,145,218-222` |
| [IMP-P2-007](IMPROVEMENTS.md#imp-p2-007) | Keep unresolved calendar questions visible and scope answers to the intended event | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt:52-56,74-82,97-110` |
| [IMP-P2-008](IMPROVEMENTS.md#imp-p2-008) | Adapt app layouts and the Teams countdown for large text and assistive navigation | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt:171-176` |
| [IMP-P2-009](IMPROVEMENTS.md#imp-p2-009) | Make settings easier to find and preview before saving | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:118-154` |
| [IMP-P2-010](IMPROVEMENTS.md#imp-p2-010) | Add task search and contextual filtering without changing the default list | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:60-80` |
| [IMP-P2-011](IMPROVEMENTS.md#imp-p2-011) | Give free-time use a lightweight, actionable countdown | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:264-324` |
| [IMP-P2-012](IMPROVEMENTS.md#imp-p2-012) | Preserve a working parent authenticator when rearming | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:116` |
| [IMP-P2-013](IMPROVEMENTS.md#imp-p2-013) | Explain rewards with a local transaction history | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/block/Credit.kt:13-30` |
| [IMP-P2-014](IMPROVEMENTS.md#imp-p2-014) | Add proportional image validation and review before paid photo checks | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/block/PhotoChecks.kt:25-55` |
| [IMP-P2-015](IMPROVEMENTS.md#imp-p2-015) | Let weekly adaptation propose an understandable trial before it changes routines | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt:102-107,121-123` |
| [IMP-P2-016](IMPROVEMENTS.md#imp-p2-016) | Show confidence and sample quality in learned estimates | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt:19-24,59-74,129-154` |
| [IMP-P2-017](IMPROVEMENTS.md#imp-p2-017) | Version enrichment recipes and expose their provenance | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt:14-40,142-168` |
| [IMP-P2-018](IMPROVEMENTS.md#imp-p2-018) | Preserve typed sync diagnostics and a small redacted history | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt:99-111,135-140` |
| [IMP-P2-019](IMPROVEMENTS.md#imp-p2-019) | Offer notification controls by purpose and sensitivity | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/notify/Channels.kt:22-32` |
| [IMP-P2-020](IMPROVEMENTS.md#imp-p2-020) | Reuse Gmail envelope metadata while still reconciling the complete inbox | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:211-217` |
| [IMP-P2-021](IMPROVEMENTS.md#imp-p2-021) | Preserve the chance to interpret email archived before its body was fetched | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:143-153` |
| [IMP-P2-022](IMPROVEMENTS.md#imp-p2-022) | Add offline end-to-end provider contract replays | P2 | Recommended | `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/ImapTest.kt:63-107` |
| [IMP-P2-023](IMPROVEMENTS.md#imp-p2-023) | Separate orchestration responsibilities and publish a consistent plan snapshot | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:93-107` |
| [IMP-P2-024](IMPROVEMENTS.md#imp-p2-024) | Coordinate Android enforcement through a small explicit event processor | P2 | Recommended | `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:54-139` |
| [IMP-P2-025](IMPROVEMENTS.md#imp-p2-025) | Make builds reproducible and automate focused dependency maintenance | P2 | Recommended | `gradle/wrapper/gradle-wrapper.properties:1-7` |
| [IMP-P2-026](IMPROVEMENTS.md#imp-p2-026) | Match AI resources and queue policy to each job | P2 | Exploratory | `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:34-51` |
| [IMP-P2-027](IMPROVEMENTS.md#imp-p2-027) | Hydrate full Power Planner instructions only when an item needs them | P2 | Exploratory | `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt:45-53,131-139` |
| [IMP-P2-028](IMPROVEMENTS.md#imp-p2-028) | Measure and reduce startup storage work before state grows | P2 | Exploratory | `app/src/main/java/com/thomaswcode/decrastination/DecrastinationApp.kt:10-17` |

## Systemic themes

- **Information can disappear at a boundary.** Missing provider structure becomes absence, an invalid AI plan can still yield a hidden classification, unplaceable work can vanish from Plan, and backup omits state that a source cannot recreate. Explicit completeness, rejection and recovery states matter more than adding more success-path parsing.
- **Progress needs provenance and identity.** Source percentages, remaining-card snapshots, timers, photos and duplicate step titles are distinct observations. Reusing one cumulative number or display title across those boundaries creates double counting and incorrect completion.
- **Android transitions remain under-tested.** Good pure-domain coverage does not exercise retained activity state, result callbacks, changing call/quiet conditions, window exposure or delayed enforcement actions. The device boundary remains the largest validation gap.
- **Explain the decision, its input quality and its limits.** Planning explanations, source freshness, browser coverage, calibrated evidence and pending calendar questions are independent opportunities to make a working system understandable.
- **Separate mandatory repairs from design experiments.** Validation and transactional fixes belong in the bug report. Model routing, longer forecast/detail hydration, recovery experience and architecture changes have separate acceptance measures and trade-offs in the improvement report.

## Coverage and exclusions

- Initial physical inventory: **3,938 files and 874 directories**, including the repository root, excluding internal `.git` metadata and review-created output. All entries are listed in the collapsible inventory below. Empty directories are included. `.git` is deliberately excluded as version-control metadata; it was not modified.
- Tracked inventory: **162 files: 160 text files read completely, two binary artifacts reviewed by role only.** There were no pre-existing untracked authored source files outside these tracked paths. Untracked/ignored files belonged to build/cache directories or generated personal snapshots/dumps.
- `app/debug.keystore` is an intentionally committed shared **debug** key used for the Teams widget's signature permission and sideload updates, as documented in the build and source guide. It is not presented as a newly discovered production-secret leak. `gradle/wrapper/gradle-wrapper.jar` is a vendor bootstrap binary; its configuration and launchers were read, but its bytecode was not audited.
- `.gradle/`, `.kotlin/`, `app/build/` and `build/` contain generated caches, compiled classes, downloaded/transformed resources, APK/report output or empty cache structure. They were inventoried/hashed, not treated as authored source or manually reviewed line by line. Dependency source/bytecode was not exhaustively audited; the resolved-coordinate advisory check is narrower.
- `private/` (23 files) and `dumps/` (one UI dump) are ignored, generated account/device/evaluation snapshots, including images. Their names and bytes were inventoried for preservation; their personal contents were excluded from the source audit. Tracked `fixtures/` files **were** read in full because they are repository inputs; their redaction weakness is reported without reproducing personal names or the embedded token.
- Empty `.claude/` and `.kotlin/` structure contains no authored instruction file. All supplied user constraints were honored; no extra source edits were inferred from recommendations.

### Tracked file ledger

Every text row below was read for both behavior and independent improvement opportunities. Module assignment only distributes coverage; cross-module paths were also followed. “Binary role only” means the stated exclusion, not source-content review.

| File | Coverage |
|---|---|
| `.gitattributes` | Read fully — application/UI/widget/build/docs and both review streams |
| `.github/workflows/ci.yml` | Read fully — application/UI/widget/build/docs and both review streams |
| `.gitignore` | Read fully — application/UI/widget/build/docs and both review streams |
| `PLAN.md` | Read fully — application/UI/widget/build/docs and both review streams |
| `README.md` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/build.gradle.kts` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/debug.keystore` | Binary role only; configuration and usage read |
| `app/lint.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/AndroidManifest.xml` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/DecrastinationApp.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/Blocklist.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/CountdownBanner.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/Credit.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/PhotoChecks.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/block/TeamsAutoSync.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/core/Instructions.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/core/Plan.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/core/Task.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/core/WallClock.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/data/DeviceClock.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/data/RuntimeState.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/data/SecretStore.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/data/Settings.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/data/TaskState.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/debug/CommandActivity.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/EnrichWorker.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/Enricher.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/InstructionReader.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/KeyProblem.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/ModelAlerts.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/ModelHold.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/PhotoChecker.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Assessment.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Briefing.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/CheckIns.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Daily.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Days.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/ReviewWorker.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Stats.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/net/Http.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/notify/Channels.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/notify/Notify.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionCheck.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/protect/SettingsChanges.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/protect/Totp.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/protect/Watchdog.kt` | Read fully — blocking/protection and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/TaskSource.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/EmailRules.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Imap.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Mime.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItems.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sources/teams/TeamsSource.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sync/SyncWorker.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt` | Read fully — sources/tooling and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/CalendarActivity.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/Components.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/Format.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/InstructionsActivity.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/InstructionsUi.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/OpenTaskActivity.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/SetupActivity.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/StatsScreen.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/TaskOpener.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/Theme.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/widget/NextWidget.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/java/com/thomaswcode/decrastination/widget/WidgetUpdater.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/drawable/ic_arrow_back.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/drawable/ic_expand_more.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/drawable/ic_focus.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/drawable/ic_more_vert.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/drawable/ic_refresh.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/mipmap-anydpi/ic_launcher.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/values/colors.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/values/strings.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/values/themes.xml` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/main/res/xml/backup_rules.xml` | Read fully — blocking/protection and both review streams |
| `app/src/main/res/xml/data_extraction_rules.xml` | Read fully — blocking/protection and both review streams |
| `app/src/main/res/xml/device_admin.xml` | Read fully — blocking/protection and both review streams |
| `app/src/main/res/xml/focus_service_config.xml` | Read fully — blocking/protection and both review streams |
| `app/src/main/res/xml/next_widget_info.xml` | Read fully — blocking/protection and both review streams |
| `app/src/main/res/xml/photo_paths.xml` | Read fully — blocking/protection and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/Fixtures.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/block/BlockingTest.kt` | Read fully — blocking/protection and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/block/FocusTest.kt` | Read fully — blocking/protection and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/core/EnrichmentTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/core/InstructionsTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/core/MergeTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/core/PlannerTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/data/BackupTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/data/StoresTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/enrich/EnrichTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/learn/DailyTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/learn/LearnTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/learn/StatsTest.kt` | Read fully — data/AI/planning and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/protect/ProtectTest.kt` | Read fully — blocking/protection and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/sources/anki/AnkiRulesTest.kt` | Read fully — sources/tooling and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/GmailTest.kt` | Read fully — sources/tooling and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/ImapTest.kt` | Read fully — sources/tooling and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/MimeTest.kt` | Read fully — sources/tooling and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItemsTest.kt` | Read fully — sources/tooling and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSourceTest.kt` | Read fully — sources/tooling and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/sources/teams/TeamsRowsTest.kt` | Read fully — sources/tooling and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/sync/SyncerTest.kt` | Read fully — sources/tooling and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/ui/FormatTest.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `app/src/test/java/com/thomaswcode/decrastination/widget/WidgetModelTest.kt` | Read fully — application/UI/widget/build/docs and both review streams |
| `build.gradle.kts` | Read fully — application/UI/widget/build/docs and both review streams |
| `docs/data-sources.md` | Read fully — sources/tooling and both review streams |
| `docs/needs-you.md` | Read fully — application/UI/widget/build/docs and both review streams |
| `docs/open-questions.md` | Read fully — application/UI/widget/build/docs and both review streams |
| `docs/phase0-findings.md` | Read fully — sources/tooling and both review streams |
| `docs/scheduler.md` | Read fully — blocking/protection and both review streams |
| `fixtures/README.md` | Read fully — sources/tooling and both review streams |
| `fixtures/anki_decks.json` | Read fully — sources/tooling and both review streams |
| `fixtures/home_page2_ui.xml` | Read fully — sources/tooling and both review streams |
| `fixtures/powerplanner_agenda.json` | Read fully — sources/tooling and both review streams |
| `fixtures/powerplanner_agenda_ui.xml` | Read fully — sources/tooling and both review streams |
| `fixtures/teams_widget_state.json` | Read fully — sources/tooling and both review streams |
| `gradle.properties` | Read fully — application/UI/widget/build/docs and both review streams |
| `gradle/libs.versions.toml` | Read fully — application/UI/widget/build/docs and both review streams |
| `gradle/wrapper/gradle-wrapper.jar` | Binary role only; configuration and usage read |
| `gradle/wrapper/gradle-wrapper.properties` | Read fully — application/UI/widget/build/docs and both review streams |
| `gradlew` | Read fully — application/UI/widget/build/docs and both review streams |
| `gradlew.bat` | Read fully — application/UI/widget/build/docs and both review streams |
| `scripts/gmail_probe.py` | Read fully — sources/tooling and both review streams |
| `scripts/load_credentials.py` | Read fully — sources/tooling and both review streams |
| `scripts/powerplanner_probe.py` | Read fully — sources/tooling and both review streams |
| `scripts/pull_teams_state.ps1` | Read fully — sources/tooling and both review streams |
| `settings.gradle.kts` | Read fully — application/UI/widget/build/docs and both review streams |

Production Kotlin files: **90**. Test Kotlin files: **24**. No `androidTest` source files are present. The two UI/widget test files, `FormatTest.kt` and `WidgetModelTest.kt`, are included in the complete-read ledger.

### Full initial physical inventory

<details>
<summary>Expand all 3,938 initial files and 874 directories (including generated/ignored entries)</summary>

Paths are relative to the repository. `[D]` is a directory and `[F]` a file. The inventory describes the pre-review tree, so the three newly delivered review documents are listed separately below. Classification and exclusions apply by the directories stated above.

```text
[D] .
[D] .claude
[D] .claude/worktrees
[F] .gitattributes
[D] .github
[D] .github/workflows
[F] .github/workflows/ci.yml
[F] .gitignore
[D] .gradle
[D] .gradle/9.3.1
[D] .gradle/9.3.1/checksums
[F] .gradle/9.3.1/checksums/checksums.lock
[F] .gradle/9.3.1/checksums/md5-checksums.bin
[F] .gradle/9.3.1/checksums/sha1-checksums.bin
[D] .gradle/9.3.1/executionHistory
[F] .gradle/9.3.1/executionHistory/executionHistory.bin
[F] .gradle/9.3.1/executionHistory/executionHistory.lock
[D] .gradle/9.3.1/expanded
[D] .gradle/9.3.1/fileChanges
[F] .gradle/9.3.1/fileChanges/last-build.bin
[D] .gradle/9.3.1/fileHashes
[F] .gradle/9.3.1/fileHashes/fileHashes.bin
[F] .gradle/9.3.1/fileHashes/fileHashes.lock
[F] .gradle/9.3.1/fileHashes/resourceHashesCache.bin
[F] .gradle/9.3.1/gc.properties
[D] .gradle/9.3.1/vcsMetadata
[D] .gradle/buildOutputCleanup
[F] .gradle/buildOutputCleanup/buildOutputCleanup.lock
[F] .gradle/buildOutputCleanup/cache.properties
[F] .gradle/buildOutputCleanup/outputFiles.bin
[D] .gradle/configuration-cache
[D] .gradle/configuration-cache/01bb01ca-404f-404b-9f5c-b06846e69197
[D] .gradle/configuration-cache/08910040-c4c9-413c-a47d-b96d578d4d31
[F] .gradle/configuration-cache/08910040-c4c9-413c-a47d-b96d578d4d31/.globals.work.bin
[F] .gradle/configuration-cache/08910040-c4c9-413c-a47d-b96d578d4d31/.strings.work.bin
[F] .gradle/configuration-cache/08910040-c4c9-413c-a47d-b96d578d4d31/_app.work.bin
[F] .gradle/configuration-cache/08910040-c4c9-413c-a47d-b96d578d4d31/buildfingerprint.bin
[F] .gradle/configuration-cache/08910040-c4c9-413c-a47d-b96d578d4d31/classloaderscopes.bin
[F] .gradle/configuration-cache/08910040-c4c9-413c-a47d-b96d578d4d31/entry.bin
[F] .gradle/configuration-cache/08910040-c4c9-413c-a47d-b96d578d4d31/projectfingerprint.bin
[F] .gradle/configuration-cache/08910040-c4c9-413c-a47d-b96d578d4d31/work.bin
[D] .gradle/configuration-cache/08da392e-907a-4926-8c0b-983be5f2f5ed
[D] .gradle/configuration-cache/090e3e89-c9a1-4031-b3f5-0f1e8c21401d
[D] .gradle/configuration-cache/0bdb7bc6-379e-492d-aeb7-a699b487bc9d
[D] .gradle/configuration-cache/0ca3dff5-aba8-4471-b913-3b50ac0eb0a1
[D] .gradle/configuration-cache/1418495d-f83d-4ade-9e3e-46df9861dcac
[D] .gradle/configuration-cache/170e87a7-449d-49b8-8ec3-c18d299674bd
[D] .gradle/configuration-cache/18df9a40-c7b9-46dd-940f-9bb96df8e2fb
[D] .gradle/configuration-cache/19719a41-b689-4942-bebc-968958aa8c05
[D] .gradle/configuration-cache/1a342f86-9f63-4ea0-8ac7-7701370a644e
[D] .gradle/configuration-cache/1b93b57d-0d35-4832-91c3-819a2517c14b
[D] .gradle/configuration-cache/1dba2bcb-6f90-4c92-921b-fee02dd4df11
[D] .gradle/configuration-cache/207c4930-56ed-44ff-877c-c11ebec86bab
[D] .gradle/configuration-cache/20a92cb4-49e1-40c6-90a5-9b2f9439ee4e
[D] .gradle/configuration-cache/21e626e5-f4a3-4b47-85ab-b380979d75da
[D] .gradle/configuration-cache/24e41770-6e8c-4570-b770-8c6c18a63c71
[D] .gradle/configuration-cache/2540cfdd-e5e3-4306-a82f-f72d02c2f165
[D] .gradle/configuration-cache/27886a19-4211-4d3d-9b36-a168f8ae87aa
[D] .gradle/configuration-cache/281a832e-aac7-4fbf-b7f6-c52447cd5c71
[D] .gradle/configuration-cache/2a27d309-69d4-44f3-830d-8e83a60db699
[D] .gradle/configuration-cache/2a6f9ece-54d6-4210-aa5f-ed0bae201010
[D] .gradle/configuration-cache/2cf5d415-0f07-4a93-a2bd-463e0ff4c421
[D] .gradle/configuration-cache/2d5d2c6b-a8fb-4d73-9e66-9254418700cf
[D] .gradle/configuration-cache/2fb7af4f-0bcf-4f8c-aad2-86ea9acae600
[D] .gradle/configuration-cache/2rbo227era7fnhmr7dnk5wmob
[F] .gradle/configuration-cache/2rbo227era7fnhmr7dnk5wmob/candidates.bin
[D] .gradle/configuration-cache/2ullftpwdlkwq2obsqc7x28d
[F] .gradle/configuration-cache/2ullftpwdlkwq2obsqc7x28d/candidates.bin
[D] .gradle/configuration-cache/32e2b45a-b955-4519-a492-8c5b9240f93e
[D] .gradle/configuration-cache/372df947-adc2-4a90-8ed5-711818300531
[D] .gradle/configuration-cache/372e7ca5-07ae-4a20-8ecb-fb04dea45a87
[D] .gradle/configuration-cache/39wat6nmz8yi4mad1cppfhuft
[F] .gradle/configuration-cache/39wat6nmz8yi4mad1cppfhuft/candidates.bin
[D] .gradle/configuration-cache/3a1qemkuic0zmeifbc1f94jeu
[F] .gradle/configuration-cache/3a1qemkuic0zmeifbc1f94jeu/candidates.bin
[D] .gradle/configuration-cache/3aa63343-1432-493c-99a3-d3fabff6e27b
[F] .gradle/configuration-cache/3aa63343-1432-493c-99a3-d3fabff6e27b/.globals.work.bin
[F] .gradle/configuration-cache/3aa63343-1432-493c-99a3-d3fabff6e27b/.strings.work.bin
[F] .gradle/configuration-cache/3aa63343-1432-493c-99a3-d3fabff6e27b/_app.work.bin
[F] .gradle/configuration-cache/3aa63343-1432-493c-99a3-d3fabff6e27b/buildfingerprint.bin
[F] .gradle/configuration-cache/3aa63343-1432-493c-99a3-d3fabff6e27b/classloaderscopes.bin
[F] .gradle/configuration-cache/3aa63343-1432-493c-99a3-d3fabff6e27b/entry.bin
[F] .gradle/configuration-cache/3aa63343-1432-493c-99a3-d3fabff6e27b/projectfingerprint.bin
[F] .gradle/configuration-cache/3aa63343-1432-493c-99a3-d3fabff6e27b/work.bin
[D] .gradle/configuration-cache/3c200f0c-cd71-4e17-9066-0e792c3f7e63
[D] .gradle/configuration-cache/3d0c8e4c-88f7-4bf2-a04c-64cd26adce3d
[D] .gradle/configuration-cache/3d31812e-de31-4c15-86c8-a63d74a3010f
[D] .gradle/configuration-cache/3uk1gdcx3wbh11kvetz4l0q4i
[F] .gradle/configuration-cache/3uk1gdcx3wbh11kvetz4l0q4i/candidates.bin
[D] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757
[F] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757/.globals.work.bin
[F] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757/.strings.work.bin
[F] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757/_app.work.bin
[F] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757/build.work.bin
[F] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757/buildfingerprint.bin
[F] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757/classloaderscopes.bin
[F] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757/entry.bin
[F] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757/projectfingerprint.bin
[F] .gradle/configuration-cache/41cd570b-5409-4874-b80c-f11bf6498757/work.bin
[D] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc
[F] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc/.globals.work.bin
[F] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc/.strings.work.bin
[F] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc/_app.work.bin
[F] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc/build.work.bin
[F] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc/buildfingerprint.bin
[F] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc/classloaderscopes.bin
[F] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc/entry.bin
[F] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc/projectfingerprint.bin
[F] .gradle/configuration-cache/430ff6af-9ef2-43ee-b632-0d7f51cc97dc/work.bin
[D] .gradle/configuration-cache/4342cfee-4ad7-4166-a551-48e930f1898d
[F] .gradle/configuration-cache/4342cfee-4ad7-4166-a551-48e930f1898d/.globals.work.bin
[F] .gradle/configuration-cache/4342cfee-4ad7-4166-a551-48e930f1898d/.strings.work.bin
[F] .gradle/configuration-cache/4342cfee-4ad7-4166-a551-48e930f1898d/_app.work.bin
[F] .gradle/configuration-cache/4342cfee-4ad7-4166-a551-48e930f1898d/buildfingerprint.bin
[F] .gradle/configuration-cache/4342cfee-4ad7-4166-a551-48e930f1898d/classloaderscopes.bin
[F] .gradle/configuration-cache/4342cfee-4ad7-4166-a551-48e930f1898d/entry.bin
[F] .gradle/configuration-cache/4342cfee-4ad7-4166-a551-48e930f1898d/projectfingerprint.bin
[F] .gradle/configuration-cache/4342cfee-4ad7-4166-a551-48e930f1898d/work.bin
[D] .gradle/configuration-cache/4857dc70-3913-4390-b023-9328fa099e46
[D] .gradle/configuration-cache/4995bf19-c9a1-43f5-93cd-e400453b5491
[F] .gradle/configuration-cache/4995bf19-c9a1-43f5-93cd-e400453b5491/.globals.work.bin
[F] .gradle/configuration-cache/4995bf19-c9a1-43f5-93cd-e400453b5491/.strings.work.bin
[F] .gradle/configuration-cache/4995bf19-c9a1-43f5-93cd-e400453b5491/_app.work.bin
[F] .gradle/configuration-cache/4995bf19-c9a1-43f5-93cd-e400453b5491/buildfingerprint.bin
[F] .gradle/configuration-cache/4995bf19-c9a1-43f5-93cd-e400453b5491/classloaderscopes.bin
[F] .gradle/configuration-cache/4995bf19-c9a1-43f5-93cd-e400453b5491/entry.bin
[F] .gradle/configuration-cache/4995bf19-c9a1-43f5-93cd-e400453b5491/projectfingerprint.bin
[F] .gradle/configuration-cache/4995bf19-c9a1-43f5-93cd-e400453b5491/work.bin
[D] .gradle/configuration-cache/4a93a1d3-7e16-4adb-b243-a527da65c826
[F] .gradle/configuration-cache/4a93a1d3-7e16-4adb-b243-a527da65c826/.globals.work.bin
[F] .gradle/configuration-cache/4a93a1d3-7e16-4adb-b243-a527da65c826/.strings.work.bin
[F] .gradle/configuration-cache/4a93a1d3-7e16-4adb-b243-a527da65c826/_app.work.bin
[F] .gradle/configuration-cache/4a93a1d3-7e16-4adb-b243-a527da65c826/buildfingerprint.bin
[F] .gradle/configuration-cache/4a93a1d3-7e16-4adb-b243-a527da65c826/classloaderscopes.bin
[F] .gradle/configuration-cache/4a93a1d3-7e16-4adb-b243-a527da65c826/entry.bin
[F] .gradle/configuration-cache/4a93a1d3-7e16-4adb-b243-a527da65c826/projectfingerprint.bin
[F] .gradle/configuration-cache/4a93a1d3-7e16-4adb-b243-a527da65c826/work.bin
[D] .gradle/configuration-cache/4af7a429-49c5-47cd-8db1-d3700c21bf9f
[F] .gradle/configuration-cache/4af7a429-49c5-47cd-8db1-d3700c21bf9f/.globals.work.bin
[F] .gradle/configuration-cache/4af7a429-49c5-47cd-8db1-d3700c21bf9f/.strings.work.bin
[F] .gradle/configuration-cache/4af7a429-49c5-47cd-8db1-d3700c21bf9f/_app.work.bin
[F] .gradle/configuration-cache/4af7a429-49c5-47cd-8db1-d3700c21bf9f/buildfingerprint.bin
[F] .gradle/configuration-cache/4af7a429-49c5-47cd-8db1-d3700c21bf9f/classloaderscopes.bin
[F] .gradle/configuration-cache/4af7a429-49c5-47cd-8db1-d3700c21bf9f/entry.bin
[F] .gradle/configuration-cache/4af7a429-49c5-47cd-8db1-d3700c21bf9f/projectfingerprint.bin
[F] .gradle/configuration-cache/4af7a429-49c5-47cd-8db1-d3700c21bf9f/work.bin
[D] .gradle/configuration-cache/4b1yct9gvqwzktq60ner5vb3f
[F] .gradle/configuration-cache/4b1yct9gvqwzktq60ner5vb3f/candidates.bin
[D] .gradle/configuration-cache/4c0e5ae6-529e-4bcf-bbc8-6b3edec8fd69
[D] .gradle/configuration-cache/4d2338ce-c13e-4871-b10f-30aba24ae8e7
[D] .gradle/configuration-cache/4de338af-6791-4122-a802-a147c5d0735d
[D] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524
[F] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524/.globals.work.bin
[F] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524/.strings.work.bin
[F] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524/_app.work.bin
[F] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524/build.work.bin
[F] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524/buildfingerprint.bin
[F] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524/classloaderscopes.bin
[F] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524/entry.bin
[F] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524/projectfingerprint.bin
[F] .gradle/configuration-cache/4f414da7-905e-430c-bbc9-7276e75ce524/work.bin
[D] .gradle/configuration-cache/4rauf13tyth2fmndlm8picy2t
[F] .gradle/configuration-cache/4rauf13tyth2fmndlm8picy2t/candidates.bin
[D] .gradle/configuration-cache/50a8x946nvjmnw7js342qdz1l
[F] .gradle/configuration-cache/50a8x946nvjmnw7js342qdz1l/candidates.bin
[D] .gradle/configuration-cache/5237a572-0ba6-4f78-94ef-093558aee068
[D] .gradle/configuration-cache/5394d851-a316-4b69-99b7-ba137f57a803
[D] .gradle/configuration-cache/568eccba-eed7-4be6-92e0-c27226d683ed
[D] .gradle/configuration-cache/571a0ed5-ab23-40d5-a6fc-09c0c574cf8e
[D] .gradle/configuration-cache/5bf09467-1bd4-4422-ad0b-5fcbe38bc5b1
[D] .gradle/configuration-cache/5f544724-ee41-49a7-9f1f-a01f2a0e1217
[F] .gradle/configuration-cache/5f544724-ee41-49a7-9f1f-a01f2a0e1217/.globals.work.bin
[F] .gradle/configuration-cache/5f544724-ee41-49a7-9f1f-a01f2a0e1217/.strings.work.bin
[F] .gradle/configuration-cache/5f544724-ee41-49a7-9f1f-a01f2a0e1217/_app.work.bin
[F] .gradle/configuration-cache/5f544724-ee41-49a7-9f1f-a01f2a0e1217/buildfingerprint.bin
[F] .gradle/configuration-cache/5f544724-ee41-49a7-9f1f-a01f2a0e1217/classloaderscopes.bin
[F] .gradle/configuration-cache/5f544724-ee41-49a7-9f1f-a01f2a0e1217/entry.bin
[F] .gradle/configuration-cache/5f544724-ee41-49a7-9f1f-a01f2a0e1217/projectfingerprint.bin
[F] .gradle/configuration-cache/5f544724-ee41-49a7-9f1f-a01f2a0e1217/work.bin
[D] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b
[F] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b/.globals.work.bin
[F] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b/.strings.work.bin
[F] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b/_app.work.bin
[F] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b/build.work.bin
[F] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b/buildfingerprint.bin
[F] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b/classloaderscopes.bin
[F] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b/entry.bin
[F] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b/projectfingerprint.bin
[F] .gradle/configuration-cache/62d29ba5-492b-4fe6-b7f4-78c7be6b786b/work.bin
[D] .gradle/configuration-cache/6312129e-2c78-47f4-821b-eeb8f61357cc
[D] .gradle/configuration-cache/631rlvosu4u68vjte532prv4m
[F] .gradle/configuration-cache/631rlvosu4u68vjte532prv4m/candidates.bin
[D] .gradle/configuration-cache/647fd1da-3168-4df5-8d9e-b21cc6b2a44e
[D] .gradle/configuration-cache/663a9994-15bd-4c19-9a4a-d3b8f147c788
[D] .gradle/configuration-cache/686c3803-ebb7-40c5-bd38-ef703c8d9b7b
[D] .gradle/configuration-cache/697c94fe-9f94-4fa7-9839-e1392e56d020
[D] .gradle/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a
[F] .gradle/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a/candidates.bin
[D] .gradle/configuration-cache/6bed0c1d-8328-4052-8db5-7e56c863ba92
[D] .gradle/configuration-cache/6cfdbc78-9dad-4cb9-8a65-849c7c6d20eb
[D] .gradle/configuration-cache/6d8e7c25-da14-4752-b6f7-c86627702b09
[D] .gradle/configuration-cache/6e9e89df-64d4-438e-8647-4f8af06863c7
[D] .gradle/configuration-cache/6lsik8qi5nodlnv0082notikd
[F] .gradle/configuration-cache/6lsik8qi5nodlnv0082notikd/candidates.bin
[D] .gradle/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy
[F] .gradle/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/candidates.bin
[D] .gradle/configuration-cache/71a3a5f7-ee14-474f-a634-415820cdf52e
[F] .gradle/configuration-cache/71a3a5f7-ee14-474f-a634-415820cdf52e/.globals.work.bin
[F] .gradle/configuration-cache/71a3a5f7-ee14-474f-a634-415820cdf52e/.strings.work.bin
[F] .gradle/configuration-cache/71a3a5f7-ee14-474f-a634-415820cdf52e/_app.work.bin
[F] .gradle/configuration-cache/71a3a5f7-ee14-474f-a634-415820cdf52e/buildfingerprint.bin
[F] .gradle/configuration-cache/71a3a5f7-ee14-474f-a634-415820cdf52e/classloaderscopes.bin
[F] .gradle/configuration-cache/71a3a5f7-ee14-474f-a634-415820cdf52e/entry.bin
[F] .gradle/configuration-cache/71a3a5f7-ee14-474f-a634-415820cdf52e/projectfingerprint.bin
[F] .gradle/configuration-cache/71a3a5f7-ee14-474f-a634-415820cdf52e/work.bin
[D] .gradle/configuration-cache/73d6ddae-1f08-490b-bc87-85cd9000f34b
[D] .gradle/configuration-cache/76b6fb2e-ff06-4ed1-910b-1db38fb14e05
[D] .gradle/configuration-cache/76df7867-e160-43fb-a5bb-f291d7b3f35a
[F] .gradle/configuration-cache/76df7867-e160-43fb-a5bb-f291d7b3f35a/.globals.work.bin
[F] .gradle/configuration-cache/76df7867-e160-43fb-a5bb-f291d7b3f35a/.strings.work.bin
[F] .gradle/configuration-cache/76df7867-e160-43fb-a5bb-f291d7b3f35a/_app.work.bin
[F] .gradle/configuration-cache/76df7867-e160-43fb-a5bb-f291d7b3f35a/buildfingerprint.bin
[F] .gradle/configuration-cache/76df7867-e160-43fb-a5bb-f291d7b3f35a/classloaderscopes.bin
[F] .gradle/configuration-cache/76df7867-e160-43fb-a5bb-f291d7b3f35a/entry.bin
[F] .gradle/configuration-cache/76df7867-e160-43fb-a5bb-f291d7b3f35a/projectfingerprint.bin
[F] .gradle/configuration-cache/76df7867-e160-43fb-a5bb-f291d7b3f35a/work.bin
[D] .gradle/configuration-cache/7850d844-4636-4a88-a471-0d469ba32145
[D] .gradle/configuration-cache/7a391b7a-bcc5-4c9c-b6e1-28f760e005ca
[D] .gradle/configuration-cache/7a3e7650-a5dd-4676-9214-08c2c73f4c93
[D] .gradle/configuration-cache/7bd17814-5bc0-48df-8264-c0ee903d4fc7
[D] .gradle/configuration-cache/7c0b97fb-3edd-4ec0-9fea-b964dd232c0d
[D] .gradle/configuration-cache/7ca2b87e-7c2c-49e0-8565-96a97114c519
[D] .gradle/configuration-cache/7e080ddc-d1b6-4610-87b6-b96040b8c002
[D] .gradle/configuration-cache/7e7ygw0a3mah8eeu57mu8hftv
[F] .gradle/configuration-cache/7e7ygw0a3mah8eeu57mu8hftv/candidates.bin
[D] .gradle/configuration-cache/7f67fac3-ee86-4da1-8f5a-3b9d168c1102
[D] .gradle/configuration-cache/81857e15-5868-46f9-ab4a-fb8ae3392823
[D] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb
[F] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb/.globals.work.bin
[F] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb/.strings.work.bin
[F] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb/_app.work.bin
[F] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb/build.work.bin
[F] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb/buildfingerprint.bin
[F] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb/classloaderscopes.bin
[F] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb/entry.bin
[F] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb/projectfingerprint.bin
[F] .gradle/configuration-cache/831717ac-f82b-4fcd-9fd4-d57ed83c35bb/work.bin
[D] .gradle/configuration-cache/83756ac0-5e2a-4fca-a9a6-d32286949a5e
[D] .gradle/configuration-cache/86131c7f-65e2-456a-aac9-f9dc5c0ffc62
[F] .gradle/configuration-cache/86131c7f-65e2-456a-aac9-f9dc5c0ffc62/.globals.work.bin
[F] .gradle/configuration-cache/86131c7f-65e2-456a-aac9-f9dc5c0ffc62/.strings.work.bin
[F] .gradle/configuration-cache/86131c7f-65e2-456a-aac9-f9dc5c0ffc62/_app.work.bin
[F] .gradle/configuration-cache/86131c7f-65e2-456a-aac9-f9dc5c0ffc62/buildfingerprint.bin
[F] .gradle/configuration-cache/86131c7f-65e2-456a-aac9-f9dc5c0ffc62/classloaderscopes.bin
[F] .gradle/configuration-cache/86131c7f-65e2-456a-aac9-f9dc5c0ffc62/entry.bin
[F] .gradle/configuration-cache/86131c7f-65e2-456a-aac9-f9dc5c0ffc62/projectfingerprint.bin
[F] .gradle/configuration-cache/86131c7f-65e2-456a-aac9-f9dc5c0ffc62/work.bin
[D] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a
[F] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a/.globals.work.bin
[F] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a/.strings.work.bin
[F] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a/_app.work.bin
[F] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a/build.work.bin
[F] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a/buildfingerprint.bin
[F] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a/classloaderscopes.bin
[F] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a/entry.bin
[F] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a/projectfingerprint.bin
[F] .gradle/configuration-cache/883e6bec-057b-423a-ba61-029efb25565a/work.bin
[D] .gradle/configuration-cache/8b40ded5-d95e-4825-8faa-1da50ed9d662
[D] .gradle/configuration-cache/8b790233-24b2-4cdc-9d12-2454b35c1758
[D] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b
[F] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b/.globals.work.bin
[F] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b/.strings.work.bin
[F] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b/_app.work.bin
[F] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b/build.work.bin
[F] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b/buildfingerprint.bin
[F] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b/classloaderscopes.bin
[F] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b/entry.bin
[F] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b/projectfingerprint.bin
[F] .gradle/configuration-cache/906dd04b-4e66-4887-934b-ef4e890bd54b/work.bin
[D] .gradle/configuration-cache/93ee2b40-e081-428c-985d-fcc4592333e6
[F] .gradle/configuration-cache/93ee2b40-e081-428c-985d-fcc4592333e6/.globals.work.bin
[F] .gradle/configuration-cache/93ee2b40-e081-428c-985d-fcc4592333e6/.strings.work.bin
[F] .gradle/configuration-cache/93ee2b40-e081-428c-985d-fcc4592333e6/_app.work.bin
[F] .gradle/configuration-cache/93ee2b40-e081-428c-985d-fcc4592333e6/buildfingerprint.bin
[F] .gradle/configuration-cache/93ee2b40-e081-428c-985d-fcc4592333e6/classloaderscopes.bin
[F] .gradle/configuration-cache/93ee2b40-e081-428c-985d-fcc4592333e6/entry.bin
[F] .gradle/configuration-cache/93ee2b40-e081-428c-985d-fcc4592333e6/projectfingerprint.bin
[F] .gradle/configuration-cache/93ee2b40-e081-428c-985d-fcc4592333e6/work.bin
[D] .gradle/configuration-cache/944df897-6897-4298-a8c8-a00845e85233
[D] .gradle/configuration-cache/986f23af-5834-445e-ab84-49ad3947da42
[D] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72
[F] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72/.globals.work.bin
[F] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72/.strings.work.bin
[F] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72/_app.work.bin
[F] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72/build.work.bin
[F] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72/buildfingerprint.bin
[F] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72/classloaderscopes.bin
[F] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72/entry.bin
[F] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72/projectfingerprint.bin
[F] .gradle/configuration-cache/99622191-0e34-410e-afe7-8482cd3c6b72/work.bin
[D] .gradle/configuration-cache/99838ba1-d0b3-47f4-a70f-097c937c7654
[F] .gradle/configuration-cache/99838ba1-d0b3-47f4-a70f-097c937c7654/.globals.work.bin
[F] .gradle/configuration-cache/99838ba1-d0b3-47f4-a70f-097c937c7654/.strings.work.bin
[F] .gradle/configuration-cache/99838ba1-d0b3-47f4-a70f-097c937c7654/_app.work.bin
[F] .gradle/configuration-cache/99838ba1-d0b3-47f4-a70f-097c937c7654/buildfingerprint.bin
[F] .gradle/configuration-cache/99838ba1-d0b3-47f4-a70f-097c937c7654/classloaderscopes.bin
[F] .gradle/configuration-cache/99838ba1-d0b3-47f4-a70f-097c937c7654/entry.bin
[F] .gradle/configuration-cache/99838ba1-d0b3-47f4-a70f-097c937c7654/projectfingerprint.bin
[F] .gradle/configuration-cache/99838ba1-d0b3-47f4-a70f-097c937c7654/work.bin
[D] .gradle/configuration-cache/9b27bf1c-a18c-4056-8cb3-6c035b673896
[D] .gradle/configuration-cache/9d625116-fa5e-4dc0-b67c-30b7c58ead6e
[D] .gradle/configuration-cache/9dd1aa9a-b848-48f1-89ea-0b53ffef30a4
[D] .gradle/configuration-cache/9uin68ty2687dlw23u831jqht
[F] .gradle/configuration-cache/9uin68ty2687dlw23u831jqht/candidates.bin
[D] .gradle/configuration-cache/a1f4db74-1e62-4deb-918f-29bb71cb8f58
[D] .gradle/configuration-cache/a350d82a-1c5b-4b77-b857-5f612217dc31
[F] .gradle/configuration-cache/a350d82a-1c5b-4b77-b857-5f612217dc31/.globals.work.bin
[F] .gradle/configuration-cache/a350d82a-1c5b-4b77-b857-5f612217dc31/.strings.work.bin
[F] .gradle/configuration-cache/a350d82a-1c5b-4b77-b857-5f612217dc31/_app.work.bin
[F] .gradle/configuration-cache/a350d82a-1c5b-4b77-b857-5f612217dc31/buildfingerprint.bin
[F] .gradle/configuration-cache/a350d82a-1c5b-4b77-b857-5f612217dc31/classloaderscopes.bin
[F] .gradle/configuration-cache/a350d82a-1c5b-4b77-b857-5f612217dc31/entry.bin
[F] .gradle/configuration-cache/a350d82a-1c5b-4b77-b857-5f612217dc31/projectfingerprint.bin
[F] .gradle/configuration-cache/a350d82a-1c5b-4b77-b857-5f612217dc31/work.bin
[D] .gradle/configuration-cache/a54c5218-96ba-4eda-97ce-42784b334fc5
[D] .gradle/configuration-cache/a6p4gjizj8qh8pc94batotgg
[F] .gradle/configuration-cache/a6p4gjizj8qh8pc94batotgg/candidates.bin
[D] .gradle/configuration-cache/a7527bc8-2fae-4add-81d8-028a1d6aee20
[D] .gradle/configuration-cache/abf60795-05cd-4989-b5ba-62e14bc3c55c
[D] .gradle/configuration-cache/ad6ec3e2-4833-407f-a11d-b865fd47622b
[D] .gradle/configuration-cache/addf9c93-c595-4575-9981-2d27d8ded240
[F] .gradle/configuration-cache/addf9c93-c595-4575-9981-2d27d8ded240/.globals.work.bin
[F] .gradle/configuration-cache/addf9c93-c595-4575-9981-2d27d8ded240/.strings.work.bin
[F] .gradle/configuration-cache/addf9c93-c595-4575-9981-2d27d8ded240/_app.work.bin
[F] .gradle/configuration-cache/addf9c93-c595-4575-9981-2d27d8ded240/buildfingerprint.bin
[F] .gradle/configuration-cache/addf9c93-c595-4575-9981-2d27d8ded240/classloaderscopes.bin
[F] .gradle/configuration-cache/addf9c93-c595-4575-9981-2d27d8ded240/entry.bin
[F] .gradle/configuration-cache/addf9c93-c595-4575-9981-2d27d8ded240/projectfingerprint.bin
[F] .gradle/configuration-cache/addf9c93-c595-4575-9981-2d27d8ded240/work.bin
[D] .gradle/configuration-cache/b305f218-768b-45cd-97a7-286788f73e76
[D] .gradle/configuration-cache/b30a6432-82f3-4e32-82db-f2dc575bfe1f
[D] .gradle/configuration-cache/bb0bbdcf-e02d-4069-87f7-f41978d00b07
[D] .gradle/configuration-cache/bb9436c6-7f4e-4faf-832b-d5ecb147460a
[D] .gradle/configuration-cache/bof6uceqtadfaclrt1xq9ku3g
[F] .gradle/configuration-cache/bof6uceqtadfaclrt1xq9ku3g/candidates.bin
[D] .gradle/configuration-cache/c370c10c-10f8-430a-865b-cfc2238ef62b
[F] .gradle/configuration-cache/c370c10c-10f8-430a-865b-cfc2238ef62b/.globals.work.bin
[F] .gradle/configuration-cache/c370c10c-10f8-430a-865b-cfc2238ef62b/.strings.work.bin
[F] .gradle/configuration-cache/c370c10c-10f8-430a-865b-cfc2238ef62b/_app.work.bin
[F] .gradle/configuration-cache/c370c10c-10f8-430a-865b-cfc2238ef62b/buildfingerprint.bin
[F] .gradle/configuration-cache/c370c10c-10f8-430a-865b-cfc2238ef62b/classloaderscopes.bin
[F] .gradle/configuration-cache/c370c10c-10f8-430a-865b-cfc2238ef62b/entry.bin
[F] .gradle/configuration-cache/c370c10c-10f8-430a-865b-cfc2238ef62b/projectfingerprint.bin
[F] .gradle/configuration-cache/c370c10c-10f8-430a-865b-cfc2238ef62b/work.bin
[D] .gradle/configuration-cache/c3cd34fb-073c-4f33-b3d7-795bcf19946a
[D] .gradle/configuration-cache/c7c2437d-7dc2-4d34-bac1-644453c4fcb1
[D] .gradle/configuration-cache/c7cadc79-763e-438d-8984-125c04be72dc
[D] .gradle/configuration-cache/c8c4716e-1a16-45b0-84af-7cbfcfb1a8d2
[D] .gradle/configuration-cache/cc0556b1-5f1e-4aea-9310-c23a455f2507
[D] .gradle/configuration-cache/cfcf94f2-6de7-42b5-874a-e0489971a6fb
[D] .gradle/configuration-cache/ckylic1x5xibs6zdgx6sxadb5
[F] .gradle/configuration-cache/ckylic1x5xibs6zdgx6sxadb5/candidates.bin
[F] .gradle/configuration-cache/configuration-cache.lock
[D] .gradle/configuration-cache/cxaekdylcei0oulnvpjr415zf
[F] .gradle/configuration-cache/cxaekdylcei0oulnvpjr415zf/candidates.bin
[D] .gradle/configuration-cache/d082819e-fefb-45b4-b86d-f996538f135d
[D] .gradle/configuration-cache/d0a241cf-443a-420e-b147-d4b443e03374
[D] .gradle/configuration-cache/d1a91e38-2c1f-4721-9a54-6d2ecede7529
[D] .gradle/configuration-cache/d59d9f86-95a5-47a4-b6f4-453dedd27d5e
[D] .gradle/configuration-cache/d61025c7-3157-4089-bb9f-879a91283dec
[D] .gradle/configuration-cache/d7ce01c3-dec4-4be0-b8bd-1830840850c9
[D] .gradle/configuration-cache/dab9cc49-81de-49cd-8506-e073d797807e
[D] .gradle/configuration-cache/dac17dea-95e6-4d79-8f4e-f5bcbd569636
[D] .gradle/configuration-cache/dnnd4xtulqr3p2389lgrgb9w
[F] .gradle/configuration-cache/dnnd4xtulqr3p2389lgrgb9w/candidates.bin
[D] .gradle/configuration-cache/dua1n31fh3my0049aggxh66u7
[F] .gradle/configuration-cache/dua1n31fh3my0049aggxh66u7/candidates.bin
[D] .gradle/configuration-cache/e16423d5-82dd-4867-89fd-9346af429308
[D] .gradle/configuration-cache/e2a6b3fb-827f-41a5-a442-6448a650cb3e
[D] .gradle/configuration-cache/e913e457-15f0-4854-9653-da4f84ec7e99
[D] .gradle/configuration-cache/e9ik552r7mq4e6x98tgsvqcws
[F] .gradle/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/candidates.bin
[D] .gradle/configuration-cache/eacde30d-93a3-429f-81f5-b1fb931fac33
[D] .gradle/configuration-cache/ebf47ca0-9d66-4bd9-ac7e-ded60ee94a9d
[F] .gradle/configuration-cache/ebf47ca0-9d66-4bd9-ac7e-ded60ee94a9d/.globals.work.bin
[F] .gradle/configuration-cache/ebf47ca0-9d66-4bd9-ac7e-ded60ee94a9d/.strings.work.bin
[F] .gradle/configuration-cache/ebf47ca0-9d66-4bd9-ac7e-ded60ee94a9d/_app.work.bin
[F] .gradle/configuration-cache/ebf47ca0-9d66-4bd9-ac7e-ded60ee94a9d/buildfingerprint.bin
[F] .gradle/configuration-cache/ebf47ca0-9d66-4bd9-ac7e-ded60ee94a9d/classloaderscopes.bin
[F] .gradle/configuration-cache/ebf47ca0-9d66-4bd9-ac7e-ded60ee94a9d/entry.bin
[F] .gradle/configuration-cache/ebf47ca0-9d66-4bd9-ac7e-ded60ee94a9d/projectfingerprint.bin
[F] .gradle/configuration-cache/ebf47ca0-9d66-4bd9-ac7e-ded60ee94a9d/work.bin
[D] .gradle/configuration-cache/ecb42084-25bf-4448-9843-96d2f6f3f299
[D] .gradle/configuration-cache/edb80801-3ed2-43e5-bd9a-7b32978704a6
[D] .gradle/configuration-cache/efc52dd0-c9fc-4415-8460-1752c45db71c
[D] .gradle/configuration-cache/egr6otipe4zalq8ex5iw8tybz
[F] .gradle/configuration-cache/egr6otipe4zalq8ex5iw8tybz/candidates.bin
[D] .gradle/configuration-cache/enfb4ls1qcrt2mh3i8r0vmjfy
[F] .gradle/configuration-cache/enfb4ls1qcrt2mh3i8r0vmjfy/candidates.bin
[D] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec
[F] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec/.globals.work.bin
[F] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec/.strings.work.bin
[F] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec/_app.work.bin
[F] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec/build.work.bin
[F] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec/buildfingerprint.bin
[F] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec/classloaderscopes.bin
[F] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec/entry.bin
[F] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec/projectfingerprint.bin
[F] .gradle/configuration-cache/f0503bec-7b19-4dab-a47a-4584913cd8ec/work.bin
[D] .gradle/configuration-cache/f54b98f7-dc36-45f0-ad2a-05e3a74ca782
[D] .gradle/configuration-cache/f57a34a4-2af3-4bef-b716-38a87d8b8543
[F] .gradle/configuration-cache/f57a34a4-2af3-4bef-b716-38a87d8b8543/.globals.work.bin
[F] .gradle/configuration-cache/f57a34a4-2af3-4bef-b716-38a87d8b8543/.strings.work.bin
[F] .gradle/configuration-cache/f57a34a4-2af3-4bef-b716-38a87d8b8543/_app.work.bin
[F] .gradle/configuration-cache/f57a34a4-2af3-4bef-b716-38a87d8b8543/buildfingerprint.bin
[F] .gradle/configuration-cache/f57a34a4-2af3-4bef-b716-38a87d8b8543/classloaderscopes.bin
[F] .gradle/configuration-cache/f57a34a4-2af3-4bef-b716-38a87d8b8543/entry.bin
[F] .gradle/configuration-cache/f57a34a4-2af3-4bef-b716-38a87d8b8543/projectfingerprint.bin
[F] .gradle/configuration-cache/f57a34a4-2af3-4bef-b716-38a87d8b8543/work.bin
[D] .gradle/configuration-cache/f88d95f6-7f85-41ae-8543-0788d32ecda7
[D] .gradle/configuration-cache/fc8bd9f3-bae5-4535-87d6-4be7c50e06b4
[D] .gradle/configuration-cache/fcbe515c-b5d4-446d-b0e7-c26077125228
[D] .gradle/configuration-cache/fd3933a8-e3e1-45ec-8dc6-8300c4d13c9e
[D] .gradle/configuration-cache/ff64304d-0fff-4e8b-86a2-e71d672135fb
[D] .gradle/configuration-cache/ff89eec5-59cc-4f5a-b473-4d9a898d6f63
[F] .gradle/configuration-cache/gc.properties
[D] .gradle/configuration-cache/l66zw88h57n5q4ewtati795x
[F] .gradle/configuration-cache/l66zw88h57n5q4ewtati795x/candidates.bin
[D] .gradle/configuration-cache/ph1c3xmsbz1aaw4qi8tkwkze
[F] .gradle/configuration-cache/ph1c3xmsbz1aaw4qi8tkwkze/candidates.bin
[D] .gradle/configuration-cache/xz1kgkw1vp4iusw1ixfms89l
[F] .gradle/configuration-cache/xz1kgkw1vp4iusw1ixfms89l/candidates.bin
[F] .gradle/file-system.probe
[D] .gradle/vcs-1
[F] .gradle/vcs-1/gc.properties
[D] .kotlin
[D] .kotlin/sessions
[D] app
[D] app/build
[F] app/build.gradle.kts
[D] app/build/generated
[D] app/build/generated/ap_generated_sources
[D] app/build/generated/ap_generated_sources/debug
[D] app/build/generated/ap_generated_sources/debug/out
[D] app/build/generated/res
[D] app/build/generated/res/pngs
[D] app/build/generated/res/pngs/debug
[D] app/build/generated/source
[D] app/build/generated/source/buildConfig
[D] app/build/generated/source/buildConfig/debug
[D] app/build/generated/source/buildConfig/debug/com
[D] app/build/generated/source/buildConfig/debug/com/thomaswcode
[D] app/build/generated/source/buildConfig/debug/com/thomaswcode/decrastination
[F] app/build/generated/source/buildConfig/debug/com/thomaswcode/decrastination/BuildConfig.java
[D] app/build/generated/updated_navigation_xml
[D] app/build/generated/updated_navigation_xml/debug
[D] app/build/intermediates
[D] app/build/intermediates/aar_metadata_check
[D] app/build/intermediates/aar_metadata_check/debug
[D] app/build/intermediates/aar_metadata_check/debug/checkDebugAarMetadata
[D] app/build/intermediates/android_res_source_set_path_map
[D] app/build/intermediates/android_res_source_set_path_map/debug
[D] app/build/intermediates/android_res_source_set_path_map/debug/mapDebugSourceSetPaths
[F] app/build/intermediates/android_res_source_set_path_map/debug/mapDebugSourceSetPaths/file-map.txt
[D] app/build/intermediates/android_test_lint_model
[D] app/build/intermediates/android_test_lint_model/debug
[D] app/build/intermediates/android_test_lint_model/debug/generateDebugAndroidTestLintModel
[F] app/build/intermediates/android_test_lint_model/debug/generateDebugAndroidTestLintModel/debug-artifact-dependencies.xml
[F] app/build/intermediates/android_test_lint_model/debug/generateDebugAndroidTestLintModel/debug-artifact-libraries.xml
[F] app/build/intermediates/android_test_lint_model/debug/generateDebugAndroidTestLintModel/debug.xml
[F] app/build/intermediates/android_test_lint_model/debug/generateDebugAndroidTestLintModel/module.xml
[D] app/build/intermediates/android_test_lint_partial_results
[D] app/build/intermediates/android_test_lint_partial_results/debug
[D] app/build/intermediates/android_test_lint_partial_results/debug/lintAnalyzeDebugAndroidTest
[D] app/build/intermediates/android_test_lint_partial_results/debug/lintAnalyzeDebugAndroidTest/out
[F] app/build/intermediates/android_test_lint_partial_results/debug/lintAnalyzeDebugAndroidTest/out/lint-issues.xml
[F] app/build/intermediates/android_test_lint_partial_results/debug/lintAnalyzeDebugAndroidTest/out/lint-partial.xml
[D] app/build/intermediates/annotation_processor_list
[D] app/build/intermediates/annotation_processor_list/debug
[D] app/build/intermediates/annotation_processor_list/debug/javaPreCompileDebug
[F] app/build/intermediates/annotation_processor_list/debug/javaPreCompileDebug/annotationProcessors.json
[D] app/build/intermediates/annotation_processor_list/debugUnitTest
[D] app/build/intermediates/annotation_processor_list/debugUnitTest/javaPreCompileDebugUnitTest
[F] app/build/intermediates/annotation_processor_list/debugUnitTest/javaPreCompileDebugUnitTest/annotationProcessors.json
[D] app/build/intermediates/apk_ide_redirect_file
[D] app/build/intermediates/apk_ide_redirect_file/debug
[D] app/build/intermediates/apk_ide_redirect_file/debug/createDebugApkListingFileRedirect
[F] app/build/intermediates/apk_ide_redirect_file/debug/createDebugApkListingFileRedirect/redirect.txt
[D] app/build/intermediates/app_metadata
[D] app/build/intermediates/app_metadata/debug
[D] app/build/intermediates/app_metadata/debug/writeDebugAppMetadata
[F] app/build/intermediates/app_metadata/debug/writeDebugAppMetadata/app-metadata.properties
[D] app/build/intermediates/assets
[D] app/build/intermediates/assets/debug
[D] app/build/intermediates/assets/debug/mergeDebugAssets
[D] app/build/intermediates/built_in_kotlinc
[D] app/build/intermediates/built_in_kotlinc/debug
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$1$2$emit$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$1$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$1$invokeSuspend$$inlined$map$1$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$1$invokeSuspend$$inlined$map$1$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$1$invokeSuspend$$inlined$map$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$10$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$10$invokeSuspend$$inlined$map$1$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$10$invokeSuspend$$inlined$map$1$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$10$invokeSuspend$$inlined$map$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$10.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$11$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$11$invokeSuspend$$inlined$map$1$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$11$invokeSuspend$$inlined$map$1$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$11$invokeSuspend$$inlined$map$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$11.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$12$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$12$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$12.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$13.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$14$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$16$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$16.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$17$1$emit$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$17$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$17.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$18$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$18$2$emit$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$18$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$18.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$4.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$5.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$6.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$7.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$8.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$9$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$9.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$addInstruction$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$addInstruction$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$applyDue$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$applyDueChanges$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$applyInstruction$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$applyInstructionWithCode$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$applyNow$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$cancelChange$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$changeSettings$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$deleteInstruction$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$deleteInstructionWithCode$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$enrichNow$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$enrichNow$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$importBackup$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$importBackup$6.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$instructions$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$layInstructions$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$log$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$modelFailed$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$modelWorked$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$readInstructions$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$reconcileAlerts$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$refreshAll$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$refuseCircle$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$refuseTakingBack$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$remove$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$requestTeamsSync$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$rereadInstruction$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$rereadInstruction$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$runtime$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$setApplied$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$settings$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$settleCompletions$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$settleCompletions$stopped$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$special$$inlined$CoroutineExceptionHandler$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$tasks$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$useParentCode$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$watchCalendar$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/AppGraph.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$onCreate$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Screen$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$1$1$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$2$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$4$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$5$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$6$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$6$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Screen$now$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$Screen$takePhoto$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockedActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Blocklist$Address.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Blocklist.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockPolicy$Input.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockPolicy$Reason.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockPolicy$Verdict$Allow.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockPolicy$Verdict$Block.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockPolicy$Verdict$Spend.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockPolicy$Verdict.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/BlockPolicy.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/ComposableSingletons$BlockedActivityKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/CountdownBanner$tick$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/CountdownBanner.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Credit$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Credit$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Credit.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$finish$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$finishPhoto$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$finishPhotos$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$finishSessions$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$onCompleted$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$photoChecked$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$rewardCompletions$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$rewardCompletions$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$spendSoon$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$startSession$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$stopSession$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$Target$App.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$Target$Browser.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$Target$Site.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$Target.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus$tickBlock$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Focus.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$block$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$disconnect$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$newScope$$inlined$CoroutineExceptionHandler$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$offerTeamsSync$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$offerTeamsSync$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$offerTeamsSync$3$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$onClick$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$screenReceiver$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$tick$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService$ticker$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusService.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusSession$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusSession$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/FocusSession.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/PhotoChecks$check$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/PhotoChecks$check$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/PhotoChecks$check$jpeg$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/PhotoChecks$check$jpeg$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/PhotoChecks$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/PhotoChecks.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/SessionReceiver$onReceive$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/SessionReceiver.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Sessions$end$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Sessions$start$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/Sessions.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/TeamsAutoSync$State$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/TeamsAutoSync$State$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/TeamsAutoSync$State.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/TeamsAutoSync$Trigger.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/block/TeamsAutoSync.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/About$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/About$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/About.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Busy.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Calibration$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Calibration$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Calibration.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Change$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Change$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Change.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/ChangeType$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/ChangeType.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Chunk.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/DayBucket.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Enrichment$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Enrichment$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Enrichment.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/EnrichmentKt$withEnrichment$1$place$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/EnrichmentKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Enrichments$Job.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Enrichments.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Fetched.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/FixedClock$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/FixedClock.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Instruction$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Instruction$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Instruction.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Instructions$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Instructions.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/InstructionsKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/InstructionState$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/InstructionState$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/InstructionState$special$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/InstructionState.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/InstructionStatus$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/InstructionStatus.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Kind$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Kind.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Merge$Result.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Merge.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Plan.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Planner$capacity$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Planner$Input.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Planner$Item.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Planner$Piece.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Planner$plan$$inlined$compareBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Planner$plan$4$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Planner$windows$Window.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Planner.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Source$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Source.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/SourceValues$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/SourceValues$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/SourceValues.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Status$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Status.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/SubStep$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/SubStep$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/SubStep.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/SystemWallClock.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/TaskItem$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/TaskItem$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/TaskItem.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/TaskOverrides$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/TaskOverrides.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Uptime$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Uptime$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/Uptime.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/WallClock$DefaultImpls.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/core/WallClock.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/ActivityLog$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/ActivityLog$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/ActivityLog.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/AssessLater$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/AssessLater$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/AssessLater.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backup$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backup$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backup.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$4.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$5.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$6.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$7.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Backups.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/BlockRecord$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/BlockRecord$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/BlockRecord.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/CompletionRecord$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/CompletionRecord$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/CompletionRecord.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/DailyRetry$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/DailyRetry$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/DailyRetry.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/DeviceClock.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/EndedSession$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/EndedSession$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/EndedSession.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/JsonStore$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/JsonStore$update$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/JsonStore$update$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/JsonStore.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/KeystoreCipher$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/KeystoreCipher.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/PhotoDone$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/PhotoDone$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/PhotoDone.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/ProtectionRecord$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/ProtectionRecord$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/ProtectionRecord.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/ProtectionState$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/ProtectionState$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/ProtectionState.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Rewarded$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Rewarded$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Rewarded.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/RuntimeState$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/RuntimeState$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/RuntimeState.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Secret.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SecretCipher.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SecretStore$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SecretStore$put$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SecretStore$put$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SecretStore.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SessionRecord$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SessionRecord$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SessionRecord.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Settings$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Settings$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Settings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SourceStatus$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SourceStatus$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/SourceStatus.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/TaskState$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/TaskState$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/TaskState.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/WeeklyReview$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/WeeklyReview$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/WeeklyReview.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Window$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Window$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/data/Window.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/debug
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/debug/CommandActivity$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/debug/CommandActivity$importCredentials$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/debug/CommandActivity$importCredentials$4.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/debug/CommandActivity$importCredentials$values$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/debug/CommandActivity$onCreate$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/debug/CommandActivity$run$$inlined$groupingBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/debug/CommandActivity$run$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/debug/CommandActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/DecrastinationApp.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/AiUsage$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/AiUsage$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/AiUsage.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Checked.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Estimate$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Estimate$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Estimate.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Split$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Split$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Split.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Step$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Step$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Step.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Triage$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Triage$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$Triage.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Answers.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricher$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricher$enrich$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricher$enrich$message$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricher.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeReviewer$Result.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeReviewer$review$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeReviewer$review$message$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeReviewer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Enricher$Result.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Enricher.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/EnrichWorker$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/EnrichWorker$doWork$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/EnrichWorker.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$Answer$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$Answer$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$Answer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$EntriesMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$Given$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$Given$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$Given.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$Reading$Changes.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$Reading$Unclear.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$Reading.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionAnswers.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionPrompts$describe$lambda$18$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionPrompts$describe$lambda$18$$inlined$sortedBy$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionPrompts$EntriesMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionPrompts.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionReader$read$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionReader$read$message$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionReader$Result.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/InstructionReader.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/KeyProblem$Companion$of$$inlined$filterIsInstance$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/KeyProblem$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/KeyProblem.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ModelAlerts.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ModelHold$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/ModelHold.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoChecker$check$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoChecker$check$message$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoChecker$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoChecker$Result.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoChecker$Verdict$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoChecker$Verdict$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoChecker$Verdict.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoChecker.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Pricing$Rates.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Pricing.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Prompts$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/Prompts.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/RuleEnricher$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/enrich/RuleEnricher.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/AssessActivity$Screen$save$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/AssessActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Assessment$record$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Assessment.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/AssessmentReceiver$onReceive$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/AssessmentReceiver.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Briefing$run$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Briefing.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CalendarEvent.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CalendarTime$refresh$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CalendarTime$refresh$events$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CalendarTime.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Calibrator$boxes$lambda$6$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Calibrator$Learned.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Calibrator$margins$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Calibrator$multipliers$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Calibrator.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CheckIn$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CheckIn$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CheckIn.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CheckInActivity$Form$1$1$6$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CheckInActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/CheckIns.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ComposableSingletons$AssessmentKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ComposableSingletons$CheckInsKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Daily.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/DailyReceiver$onReceive$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/DailyReceiver.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/DayChunk$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/DayChunk$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/DayChunk.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/DayRecord$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/DayRecord$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/DayRecord.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Days.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/EventAnswerReceiver$onReceive$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/EventAnswerReceiver.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/EventJudge$Judgement$Ask.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/EventJudge$Judgement$Busy.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/EventJudge$Judgement$Free.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/EventJudge$Judgement$Load.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/EventJudge$Judgement.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/EventJudge$Time.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/EventJudge.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Review$modelReview$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Review$Outcome.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Review$run$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Review$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Review.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewInput$Answer$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewInput$Answer$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewInput$Answer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewInput$Change$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewInput$Change$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewInput$Change.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewInput.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewWorker$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewWorker$doWork$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/ReviewWorker.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Stats$calibrationLines$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Stats$calibrationLines$$inlined$sortedBy$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Stats$calibrationLines$$inlined$sortedBy$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Stats$Day.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Stats$summary$$inlined$compareByDescending$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Stats$summary$$inlined$groupingBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Stats$summary$$inlined$thenBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Stats$Summary.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/learn/Stats.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/net
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/net/Http.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/net/HttpResponse.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/net/UrlConnectionHttp.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/notify
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/notify/Channels.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/notify/Notify.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/AdminReceiver.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/BootReceiver$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/BootReceiver$onReceive$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/BootReceiver.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/CodeLock$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/CodeLock$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/CodeLock$Result$Accepted.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/CodeLock$Result$Locked.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/CodeLock$Result$Reused.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/CodeLock$Result$Wrong.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/CodeLock$Result.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/CodeLock.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/GuardRules$Labels.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/GuardRules$Verdict$Back.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/GuardRules$Verdict$Leave.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/GuardRules$Verdict.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/GuardRules.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/PendingChange$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/PendingChange$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/PendingChange.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$applyWithCode$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$CodeDialog$1$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$CodeTarget$Change.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$CodeTarget$Unblock.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$CodeTarget.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$ParentCodeDialog$1$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$ParentCodeDialog$2$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$Screen$1$1$1$1$2$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$Screen$1$1$3$1$2$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$Screen$9$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$Step.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionCheck$Report.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionCheck.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChanges$Outcome.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChanges.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/Totp.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/Watchdog$check$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/Watchdog$restart$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/Watchdog.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/WatchdogReceiver$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/WatchdogReceiver$onReceive$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/WatchdogReceiver.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/WatchdogWorker$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/WatchdogWorker$doWork$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/protect/WatchdogWorker.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiDay$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiDay$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiDay.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiProvider$Opened.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiProvider.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiRules$homeworkDecks$lambda$47$$inlined$compareBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiRules.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiSource$read$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiSource.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/Deck$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/anki/Deck.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/EmailRules$Email.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/EmailRules$EntriesMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/EmailRules$special$$inlined$sortedByDescending$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/EmailRules$Triage.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/EmailRules.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/GmailSource$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/GmailSource$read$2$closer$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/GmailSource$read$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/GmailSource.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/GmailThreads$Body.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/GmailThreads$fetched$$inlined$groupingBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/GmailThreads$latest$$inlined$sortedByDescending$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/GmailThreads.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapClient$CommandFailed.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapClient$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapClient.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapLine.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapReader$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapReader.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapValue$Atom.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapValue$Items.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapValue$Nil.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapValue$Str.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapValue.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/InboxMessage.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/Mime$TextPart.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/gmail/Mime.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/AgendaResponse$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/AgendaResponse$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/AgendaResponse.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/ClassesResponse$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/ClassesResponse$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/ClassesResponse.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/LoginResponse$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/LoginResponse$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/LoginResponse.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$ApiError.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$Login.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItems$Due.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItems.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource$freshLogin$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource$read$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource$Semester.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PpClass$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PpClass$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PpClass.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PpItem$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PpItem$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PpItem.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PpSchedule$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PpSchedule$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PpSchedule.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/SemesterResponse$$serializer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/SemesterResponse$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/SemesterResponse.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/Timetable.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/WithError.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/ReadContext.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/SourceRead.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/SourceUnavailable.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/TaskSource.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/teams
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/teams/TeamsProvider.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/teams/TeamsRows.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/teams/TeamsSource$read$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sources/teams/TeamsSource.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/Syncer$failed$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/Syncer$readOne$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/Syncer$readOne$read$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/Syncer$readOne$reading$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/Syncer$special$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/Syncer$sync$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/Syncer.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/SyncReport.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/SyncWorker$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/SyncWorker$doWork$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/sync/SyncWorker.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$3$1$1$3$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$3$1$1$5$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$3$1$1$5$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$4.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$5.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$6.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity$Writing.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/CalendarActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComponentsKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$CalendarActivityKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$ComponentsKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsActivityKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$MainActivityKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$SetupScreenKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$StatsScreenKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$TasksScreenKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ComposableSingletons$TodayScreenKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/Credential.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/Format.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsActivity$onResume$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsActivity$Screen$$inlined$sortedByDescending$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsActivity$Screen$2$1$1$1$1$1$2$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsActivity$section$$inlined$itemsIndexed$default$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsActivity$section$$inlined$itemsIndexed$default$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsActivity$section$$inlined$itemsIndexed$default$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsActivity$section$3$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$3$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$5$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$6$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$7$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$4$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$6$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$ParentCodeDialog$1$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$sendInstruction$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/InstructionsUiKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/MainActivity$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/MainActivity$Main$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/MainActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/OpenTaskActivity$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/OpenTaskActivity$onCreate$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/OpenTaskActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$App.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$3$1$2$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$1$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$11$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$2$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$2$3$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$2$5$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$2$7$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$3$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$3$3$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$3$5$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$4$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$4$4$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$3$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$5$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$7$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$9$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$6$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$6$3$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$4.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$5.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$6.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$7.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$8.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity$launchableApps$$inlined$sortedBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivityKt$TimeField$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SettingsActivityKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SetupActivity.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$3$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$4$2$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$exporter$1$1$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$exporter$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$importer$1$1$1$1$bytes$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$importer$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$requestCalendar$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/SetupScreenKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$1$1$3$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$now$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/StatsScreenKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TaskOpener$open$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TaskOpener$open$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TaskOpener$openTeams$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TaskOpener$openTeams$result$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TaskOpener$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TaskOpener.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$compareBy$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$compareBy$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$4.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$5.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$6.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$7.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$8.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$9.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$sortedByDescending$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$tick$1$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TasksScreenKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/ThemeKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TileActions.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$1$1$5$3$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$1$1$7$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$lambda$41$$inlined$itemsIndexed$default$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$lambda$41$$inlined$itemsIndexed$default$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$lambda$41$$inlined$itemsIndexed$default$3.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$now$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$open$1$1$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/ui/TodayScreenKt.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/ComposableSingletons$NextWidgetKt.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/NextWidget$Content$lambda$14$lambda$13$lambda$12$$inlined$items$default$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/NextWidget$Content$lambda$14$lambda$13$lambda$12$$inlined$items$default$2.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/NextWidget$provideGlance$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/NextWidget$WhenMappings.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/NextWidget.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/NextWidgetReceiver$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/NextWidgetReceiver$onReceive$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/NextWidgetReceiver.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/RefreshAction.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetLayout$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetLayout$Parts$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetLayout$Parts.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetLayout$Pills.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetLayout.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetModel$Companion.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetModel$Line.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetModel.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetUpdater$update$1.class
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/thomaswcode/decrastination/widget/WidgetUpdater.class
[D] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/META-INF
[F] app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/META-INF/app.kotlin_module
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/BlocklistTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/BlockPolicyTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/CreditTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a block of an email can be ticked off by hand, an assignment's step can't$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a block ticked by hand after a session on it keeps that session's minutes$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a completion earns from the calibrated estimate, as the plan and its sessions do$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a completion is rewarded once, however often it's offered$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a completion rewarded after its day is over earns nothing then$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a completion rewarded after its task reopened uses the work it was done with$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a completion saved with the sync is rewarded later, if the app stopped first$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a completion that ends a running session says so$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a completion's estimate for learning leaves out the vocabulary its decks hold$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a completion's estimate is what was left when it was first seen$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a finished session marks its step done and earns a third of its minutes$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a finished session processed late is dated at its end, and its time isn't today's$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a photo check stopped part-way is finished at start-up, each part once$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a photo check's success applies once, to a piece still to do$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a photo of a piece of time counts against what's left of the estimate, once$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a photo's cap is the calibrated estimate, and its work is the day's$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a piece the photo check finds done is ticked off and earns its time$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a session ended twice at once is finished once$1$records$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a session ended twice at once is finished once$1$records$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a session ended twice at once is finished once$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a session ended with its task's completion is counted in the saved completion too$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a session finished on a day that's over earns nothing, and says so$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a session stopped early counts its minutes but earns nothing$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a session's ending saved before its due was given is given it at start-up, once$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a split task with no deadline of its own completes against its last block's$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a step longer than a session isn't ticked by one, its minutes count$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$a stop meant for one session doesn't end the one after it$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$an email whose blocks were ticked off by hand earns nothing when it completes$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$completions are handed on before they leave the queue, so a stop loses none$1$2$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$completions are handed on before they leave the queue, so a stop loses none$1$4.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$completions are handed on before they leave the queue, so a stop loses none$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$confirmed work earns time, reading emails doesn't$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$log$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$of two blocks of one name, the one ticked is the one ticked$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$only a piece the box cut goes to the box experiment$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$runtime$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$setting the date forward doesn't finish a session$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$settings$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$stopped after a session's minutes and record were saved, only its free time is left to give$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$tasks$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$vocabulary a deck held isn't rewarded again with its assignment$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$vocabulary a deck left unfinished is rewarded with its assignment, less its sessions$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$vocabulary of a deck dropped as its assignment closed is rewarded less the deck's sessions$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$what was just spent is off the credit at once, before it's saved$1$holder$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$what was just spent is off the credit at once, before it's saved$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$with nothing due soon, earned time is spent while a blocked app is in front$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest$work confirmed done during its session ends the session, its minutes counted once$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/FocusTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/block/TeamsAutoSyncTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/core
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/core/EnrichmentTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/core/InstructionsTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/core/MergeTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/core/PlannerTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/BackupTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/StoresTest$a store saves and loads its state$1$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/StoresTest$a store saves and loads its state$1$5.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/StoresTest$a store saves and loads its state$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/StoresTest$an unreadable file is set aside and the state starts afresh$store$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/StoresTest$Reverse.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/StoresTest$secrets are kept encrypted, and blank removes one$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/StoresTest$secrets that can't be decrypted read as none$broken$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/StoresTest$unknown keys from another version are ignored$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/data/StoresTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/AiUsageTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/AnswersTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricherTest$a fallback model's answer is priced at its own rates$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricherTest$a refusal is counted, and says nothing$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricherTest$an answer cut off by its token limit is not trusted$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricherTest$it asks Opus 5 point 5 at high effort for the schema's answer, with fallbacks on$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricherTest$the API's refusal of a key, or of the account, is read from the SDK's exception$1$failure$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricherTest$the API's refusal of a key, or of the account, is read from the SDK's exception$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/ClaudeEnricherTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/EnrichTestKt.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoCheckerTest$the photo goes as a JPEG image block with the piece, and the verdict is read$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/PhotoCheckerTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/PromptsTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/RuleEnricherTest$listed parts become steps sharing the estimate$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/RuleEnricherTest$the rules share the source's estimate, not a stale one of the model's$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/enrich/RuleEnricherTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/Fixtures.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/CalendarQuestionsTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/CalibratorTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/ClaudeReviewerTest$the review asks for the note and bounded changes, and reads them$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/ClaudeReviewerTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/DailyTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/DaysTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/EventJudgeTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/LearnTestKt.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/ReviewInputTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/learn/StatsTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/CodeLockTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/GuardRulesTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/ProtectionCheckTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a box length changed either way waits once armed$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a box length changed either way waits once armed$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a list change applies its additions now, and only its removals wait, keeping their wait$first$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a list change applies its additions now, and only its removals wait, keeping their wait$second$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a lower quota for undated work waits, a higher one doesn't$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a lower quota for undated work waits, a higher one doesn't$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a newer change to a field replaces the pending one$first$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a newer change to a field replaces the pending one$second$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a waiting change set back is cancelled, and one left as asked keeps its wait$again$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a waiting change set back is cancelled, and one left as asked keeps its wait$back$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a waiting change set back is cancelled, and one left as asked keeps its wait$first$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a waiting disarm set back is cancelled, and one left as asked keeps its wait$again$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a waiting disarm set back is cancelled, and one left as asked keeps its wait$back$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a waiting disarm set back is cancelled, and one left as asked keeps its wait$first$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a working window changed any way waits, and so does another textbook$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a working window changed any way waits, and so does another textbook$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$a working window changed any way waits, and so does another textbook$3.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$armed, loosening waits 24 hours and tightening applies at once$outcome$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$disarming waits, arming doesn't$armed$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$disarming waits, arming doesn't$disarm$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$fewer or later automatic Teams syncs wait once armed, more or sooner don't$waits$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$fewer or later Teams syncs, another textbook, and a key put to use wait once armed$waits$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$pending changes apply when their time comes, each only its own field$loose$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$pending changes apply when their time comes, each only its own field$other$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$setting the date forward doesn't hurry a loosening$loose$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$switching Claude on or raising its cap waits once armed, switching it off doesn't$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$switching Claude on or raising its cap waits once armed, switching it off doesn't$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$switching Claude on or raising its cap waits once armed, switching it off doesn't$3.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$switching Claude on or raising its cap waits once armed, switching it off doesn't$4.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$switching Claude on or raising its cap waits once armed, switching it off doesn't$5.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$switching Claude on or raising its cap waits once armed, switching it off doesn't$6.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest$unarmed, every change applies at once$outcome$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/SettingsChangesTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/protect/TotpTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/anki
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/anki/AnkiRulesTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/gmail
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/gmail/GmailTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapTest$Script.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/gmail/ImapTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/gmail/MimeTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItemsTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSourceTest$an expired session logs in again, once$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSourceTest$another account's login reads its own semester, not the cached one$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSourceTest$FakeApi.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSourceTest$Plain.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSourceTest$the session is reused, and the semester read once$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSourceTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/teams
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sources/teams/TeamsRowsTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$a read that blocks is abandoned at the timeout, not waited for$1$report$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$a read that blocks is abandoned at the timeout, not waited for$1$stuck$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$a read that blocks is abandoned at the timeout, not waited for$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$a source that hangs times out as a failure$1$slow$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$a source that hangs times out as a failure$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$a sync's completions are saved with the tasks' move to done, to be rewarded after$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$a sync's completions are saved with the tasks' move to done, to be rewarded after$1$teams$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$a sync's completions are saved with the tasks' move to done, to be rewarded after$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$after-every-sync listeners hear every sync, changed or not$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$after-every-sync listeners hear every sync, changed or not$1$syncer$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$after-every-sync listeners hear every sync, changed or not$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$an email that's just an email, archived, isn't work to reward$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$an email that's just an email, archived, isn't work to reward$1$gmail$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$an email that's just an email, archived, isn't work to reward$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$Anki is read last, and sees what the others just found$1$anki$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$Anki is read last, and sees what the others just found$1$teams$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$Anki is read last, and sees what the others just found$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$FakeSource.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$one source failing leaves the others' tasks merged and its own as they were$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$one source failing leaves the others' tasks merged and its own as they were$1$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$one source failing leaves the others' tasks merged and its own as they were$1$gmail$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$one source failing leaves the others' tasks merged and its own as they were$1$teams$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$one source failing leaves the others' tasks merged and its own as they were$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$only the asked-for sources are read, and listeners hear of changes$1$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$only the asked-for sources are read, and listeners hear of changes$1$gmail$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$only the asked-for sources are read, and listeners hear of changes$1$teams$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$only the asked-for sources are read, and listeners hear of changes$1.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$settings$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest$tasks$2.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/sync/SyncerTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/ui
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/ui/FormatTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/widget
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/widget/WidgetLayoutTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/widget/WidgetModelTest.class
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/com/thomaswcode/decrastination/widget/WidgetRedrawTest.class
[D] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/META-INF
[F] app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes/META-INF/app.kotlin_module
[D] app/build/intermediates/compatible_screen_manifest
[D] app/build/intermediates/compatible_screen_manifest/debug
[D] app/build/intermediates/compatible_screen_manifest/debug/createDebugCompatibleScreenManifests
[F] app/build/intermediates/compatible_screen_manifest/debug/createDebugCompatibleScreenManifests/output-metadata.json
[D] app/build/intermediates/compile_and_runtime_r_class_jar
[D] app/build/intermediates/compile_and_runtime_r_class_jar/debug
[D] app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources
[F] app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar
[D] app/build/intermediates/compile_app_classes_jar
[D] app/build/intermediates/compile_app_classes_jar/debug
[D] app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar
[F] app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar
[D] app/build/intermediates/compile_r_class_jar
[D] app/build/intermediates/compile_r_class_jar/debug
[D] app/build/intermediates/compile_r_class_jar/debug/generateDebugRFile
[F] app/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar
[D] app/build/intermediates/compile_symbol_list
[D] app/build/intermediates/compile_symbol_list/debug
[D] app/build/intermediates/compile_symbol_list/debug/generateDebugRFile
[F] app/build/intermediates/compile_symbol_list/debug/generateDebugRFile/R.txt
[D] app/build/intermediates/compiled_navigation_res
[D] app/build/intermediates/compiled_navigation_res/debug
[D] app/build/intermediates/compiled_navigation_res/debug/compileDebugNavigationResources
[D] app/build/intermediates/compressed_assets
[D] app/build/intermediates/compressed_assets/debug
[D] app/build/intermediates/compressed_assets/debug/compressDebugAssets
[D] app/build/intermediates/compressed_assets/debug/compressDebugAssets/out
[D] app/build/intermediates/data_binding_layout_info_type_merge
[D] app/build/intermediates/data_binding_layout_info_type_merge/debug
[D] app/build/intermediates/data_binding_layout_info_type_merge/debug/mergeDebugResources
[D] app/build/intermediates/data_binding_layout_info_type_merge/debug/mergeDebugResources/out
[D] app/build/intermediates/data_binding_layout_info_type_package
[D] app/build/intermediates/data_binding_layout_info_type_package/debug
[D] app/build/intermediates/data_binding_layout_info_type_package/debug/packageDebugResources
[D] app/build/intermediates/data_binding_layout_info_type_package/debug/packageDebugResources/out
[D] app/build/intermediates/default_proguard_files
[D] app/build/intermediates/default_proguard_files/global
[F] app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-9.0.1
[F] app/build/intermediates/default_proguard_files/global/proguard-android.txt-9.0.1
[D] app/build/intermediates/desugar_graph
[D] app/build/intermediates/desugar_graph/debug
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_0
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_0/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_1
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_1/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_2
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_2/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_3
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_3/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_4
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_4/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_5
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_5/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_6
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_6/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_7
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/dirs_bucket_7/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_0
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_0/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_1
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_1/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_2
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_2/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_3
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_3/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_4
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_4/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_5
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_5/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_6
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_6/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_7
[F] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/currentProject/jar_2e678dadbc22c2d1f12fa50ecea7684f11a7ebc941adc7239b7b1ad97c8b2019_bucket_7/graph.bin
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/externalLibs
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/mixedScopes
[D] app/build/intermediates/desugar_graph/debug/dexBuilderDebug/out/otherProjects
[D] app/build/intermediates/dex
[D] app/build/intermediates/dex/debug
[D] app/build/intermediates/dex/debug/mergeExtDexDebug
[F] app/build/intermediates/dex/debug/mergeExtDexDebug/classes.dex
[F] app/build/intermediates/dex/debug/mergeExtDexDebug/classes2.dex
[F] app/build/intermediates/dex/debug/mergeExtDexDebug/classes3.dex
[F] app/build/intermediates/dex/debug/mergeExtDexDebug/classes4.dex
[F] app/build/intermediates/dex/debug/mergeExtDexDebug/classes5.dex
[D] app/build/intermediates/dex/debug/mergeLibDexDebug
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/0
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/1
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/10
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/11
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/12
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/13
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/14
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/15
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/2
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/3
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/4
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/5
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/6
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/7
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/8
[D] app/build/intermediates/dex/debug/mergeLibDexDebug/9
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/0
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/0/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/1
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/1/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/10
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/11
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/11/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/12
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/12/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/13
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/13/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/14
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/15
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/15/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/2
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/2/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/3
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/3/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/4
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/4/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/5
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/5/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/6
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/6/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/7
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/8
[F] app/build/intermediates/dex/debug/mergeProjectDexDebug/8/classes.dex
[D] app/build/intermediates/dex/debug/mergeProjectDexDebug/9
[D] app/build/intermediates/dex_archive_input_jar_hashes
[D] app/build/intermediates/dex_archive_input_jar_hashes/debug
[D] app/build/intermediates/dex_archive_input_jar_hashes/debug/dexBuilderDebug
[F] app/build/intermediates/dex_archive_input_jar_hashes/debug/dexBuilderDebug/out
[D] app/build/intermediates/dex_number_of_buckets_file
[D] app/build/intermediates/dex_number_of_buckets_file/debug
[D] app/build/intermediates/dex_number_of_buckets_file/debug/dexBuilderDebug
[F] app/build/intermediates/dex_number_of_buckets_file/debug/dexBuilderDebug/out
[D] app/build/intermediates/duplicate_classes_check
[D] app/build/intermediates/duplicate_classes_check/debug
[D] app/build/intermediates/duplicate_classes_check/debug/checkDebugDuplicateClasses
[D] app/build/intermediates/external_file_lib_dex_archives
[D] app/build/intermediates/external_file_lib_dex_archives/debug
[D] app/build/intermediates/external_file_lib_dex_archives/debug/desugarDebugFileDependencies
[D] app/build/intermediates/external_libs_dex_archive
[D] app/build/intermediates/external_libs_dex_archive/debug
[D] app/build/intermediates/external_libs_dex_archive/debug/dexBuilderDebug
[D] app/build/intermediates/external_libs_dex_archive/debug/dexBuilderDebug/out
[D] app/build/intermediates/external_libs_dex_archive_with_artifact_transforms
[D] app/build/intermediates/external_libs_dex_archive_with_artifact_transforms/debug
[D] app/build/intermediates/external_libs_dex_archive_with_artifact_transforms/debug/dexBuilderDebug
[D] app/build/intermediates/external_libs_dex_archive_with_artifact_transforms/debug/dexBuilderDebug/out
[D] app/build/intermediates/global_synthetics_dex
[D] app/build/intermediates/global_synthetics_dex/debug
[D] app/build/intermediates/global_synthetics_dex/debug/mergeDebugGlobalSynthetics
[F] app/build/intermediates/global_synthetics_dex/debug/mergeDebugGlobalSynthetics/classes.dex
[D] app/build/intermediates/global_synthetics_external_lib
[D] app/build/intermediates/global_synthetics_external_lib/debug
[D] app/build/intermediates/global_synthetics_external_lib/debug/dexBuilderDebug
[D] app/build/intermediates/global_synthetics_external_lib/debug/dexBuilderDebug/out
[D] app/build/intermediates/global_synthetics_external_libs_artifact_transform
[D] app/build/intermediates/global_synthetics_external_libs_artifact_transform/debug
[D] app/build/intermediates/global_synthetics_external_libs_artifact_transform/debug/dexBuilderDebug
[D] app/build/intermediates/global_synthetics_external_libs_artifact_transform/debug/dexBuilderDebug/out
[D] app/build/intermediates/global_synthetics_file_lib
[D] app/build/intermediates/global_synthetics_file_lib/debug
[D] app/build/intermediates/global_synthetics_file_lib/debug/desugarDebugFileDependencies
[D] app/build/intermediates/global_synthetics_mixed_scope
[D] app/build/intermediates/global_synthetics_mixed_scope/debug
[D] app/build/intermediates/global_synthetics_mixed_scope/debug/dexBuilderDebug
[D] app/build/intermediates/global_synthetics_mixed_scope/debug/dexBuilderDebug/out
[D] app/build/intermediates/global_synthetics_project
[D] app/build/intermediates/global_synthetics_project/debug
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$0aaf80cefd50ac514d9dfe5e78552117bc968ee1a92ef426288742174361cd2e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$0aaf80cefd50ac514d9dfe5e78552117bc968ee1a92ef426288742174361cd2e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$0d3617f53787518114340122a6ab2e0e159cf8e13065ca861cbfa92ec19ce393$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$0d3617f53787518114340122a6ab2e0e159cf8e13065ca861cbfa92ec19ce393$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$0f3c8d08d0637cb5c4435e2792fac0f2987de2fa29d7acc8dac0ccacae2da47e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$1b90a1c3dbddd3aa7b49d5ab3021cdc4f79ce42181dbfe0910c582fdd83a3be0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$2b8cbfe447617d2bfff70f1608a5ac46e27e99bb2f802e5ed8f56f0fc6d39107$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$2fc0ac55b0ede10457976d1cdf47d714eb781bff2a771d35e4d40c4a060c2540$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$3318a363f1a0f2bdcf3fa513019697970804b37cc0f6793ebae6ea76993fc1c7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$3abbf2ab4b8de480b09bb525fa819c36df02a3ace30b2773a7bfe5d04d181000$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$407f293538903afda5e5ad02ec689dbef385aef5e1314253b2963337cd52a6eb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$526ee96b184bc79dc963648b46cb2a014d1901a9e39f91f32678f42a48823ae3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$5e12fae153b535e0cbe5d5e7194cf0f7fdd8fac5b5660733d1ccb822cd899bf8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$60a110865cc522c1d63be80b4ede71d3967e50222e8806ef4cc68e7af788eb75$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$6772f869fd46224ed05bf41085925b7b4124a910ea74409dcbb3ab411cb0f91b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$77ec6e3171a098df53e5ba1dd0a5cf8f4956f6a8b87c6ac85ba5b4275d34ef92$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$8291de09bfa2d317c8f780cf9d4be4d59ac44cbd160fb4d81ea7b0d74113ca7f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$8291de09bfa2d317c8f780cf9d4be4d59ac44cbd160fb4d81ea7b0d74113ca7f$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$835f16f9d6ff5feef9763c97ced8579e84afdaa93822d8d7dc4713693588f591$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$835f16f9d6ff5feef9763c97ced8579e84afdaa93822d8d7dc4713693588f591$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$a67bc2121db29a387321c9c31aa83f0ca2d598c0975b3b26d847d4cf41d3e78f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$af4146a962a8d8af63f95b821a594893893bcaaadc288098b20e6137b320b0fb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$b58e3cd622dc5ee4034dcba15231ad17a364e0b56abb580b9bd35c84c01e4a32$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$b58e3cd622dc5ee4034dcba15231ad17a364e0b56abb580b9bd35c84c01e4a32$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$b58e3cd622dc5ee4034dcba15231ad17a364e0b56abb580b9bd35c84c01e4a32$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$c88e31400c607831ac766debcb6deec449ceb274536c6806477e9ad1734d731d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$c88e31400c607831ac766debcb6deec449ceb274536c6806477e9ad1734d731d$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$c88e31400c607831ac766debcb6deec449ceb274536c6806477e9ad1734d731d$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$c88e31400c607831ac766debcb6deec449ceb274536c6806477e9ad1734d731d$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$d281ebd53fb0a580295f9a13bdd66b17781ad73e794bb8b57cbbf3f5cdc3d1f4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$e5c59fe5c8663df83b949e59d8218ebbec6fc7dcdfd4ea58dca873f57dc6708a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$e5c59fe5c8663df83b949e59d8218ebbec6fc7dcdfd4ea58dca873f57dc6708a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$e5c59fe5c8663df83b949e59d8218ebbec6fc7dcdfd4ea58dca873f57dc6708a$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$f135aea134be83b89f6555578b8d6827c14153b776cd7df574bc66ac6d882300$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$f2da5b64d32a6dbc6f287c39f920879aebe2ff388f19ae1b63407408ddd3a1bc$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$f2da5b64d32a6dbc6f287c39f920879aebe2ff388f19ae1b63407408ddd3a1bc$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$f2dbdb264dff96364261deed50b276c64b1d60a4beba085ad5dfea7349affc96$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$f2dbdb264dff96364261deed50b276c64b1d60a4beba085ad5dfea7349affc96$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$$InternalSyntheticLambda$2$f2dbdb264dff96364261deed50b276c64b1d60a4beba085ad5dfea7349affc96$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$1$2$$InternalSyntheticLambda$2$5cf8015e51cc0a311b1580b6cdbf82e874d5137fa31790f2f64857e98716560e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$1$2$$InternalSyntheticLambda$2$5cf8015e51cc0a311b1580b6cdbf82e874d5137fa31790f2f64857e98716560e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$refreshAll$1$$InternalSyntheticLambda$2$675ddfe7a0e1ad307dd647818370a11fa130c1c33af4c25bcddbc7c5878b652e$0.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$012a7edc6b7ff15fa9ec013c44b8d00b32482aa01d2c6a43b5ae1f0f384b092c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$012a7edc6b7ff15fa9ec013c44b8d00b32482aa01d2c6a43b5ae1f0f384b092c$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$012a7edc6b7ff15fa9ec013c44b8d00b32482aa01d2c6a43b5ae1f0f384b092c$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$012a7edc6b7ff15fa9ec013c44b8d00b32482aa01d2c6a43b5ae1f0f384b092c$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$012a7edc6b7ff15fa9ec013c44b8d00b32482aa01d2c6a43b5ae1f0f384b092c$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$012a7edc6b7ff15fa9ec013c44b8d00b32482aa01d2c6a43b5ae1f0f384b092c$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$012a7edc6b7ff15fa9ec013c44b8d00b32482aa01d2c6a43b5ae1f0f384b092c$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$12842356dadfb18c2dbff76661ab8b1b62a8e872780c7fb8cbb77e2680c7cc55$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$12842356dadfb18c2dbff76661ab8b1b62a8e872780c7fb8cbb77e2680c7cc55$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$12842356dadfb18c2dbff76661ab8b1b62a8e872780c7fb8cbb77e2680c7cc55$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$12842356dadfb18c2dbff76661ab8b1b62a8e872780c7fb8cbb77e2680c7cc55$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$12842356dadfb18c2dbff76661ab8b1b62a8e872780c7fb8cbb77e2680c7cc55$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$12842356dadfb18c2dbff76661ab8b1b62a8e872780c7fb8cbb77e2680c7cc55$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$12842356dadfb18c2dbff76661ab8b1b62a8e872780c7fb8cbb77e2680c7cc55$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$29b11de3927e0659b0b942412ba7c42920a04a19ec11c44c1be540bb0ddc351f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$29b11de3927e0659b0b942412ba7c42920a04a19ec11c44c1be540bb0ddc351f$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$29b11de3927e0659b0b942412ba7c42920a04a19ec11c44c1be540bb0ddc351f$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$29b11de3927e0659b0b942412ba7c42920a04a19ec11c44c1be540bb0ddc351f$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$43108d7697bba84c93079cb4dee5dcf786d3c726115f9950ef0991c1cb94b92e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$43108d7697bba84c93079cb4dee5dcf786d3c726115f9950ef0991c1cb94b92e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$43108d7697bba84c93079cb4dee5dcf786d3c726115f9950ef0991c1cb94b92e$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$45eca36bc046bb1662f7b683fa305f4884bb9a786d455de09aa226374ff00611$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$45eca36bc046bb1662f7b683fa305f4884bb9a786d455de09aa226374ff00611$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$45eca36bc046bb1662f7b683fa305f4884bb9a786d455de09aa226374ff00611$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$45eca36bc046bb1662f7b683fa305f4884bb9a786d455de09aa226374ff00611$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$54286cfd75b2ad9a0bc6fb89741a213bdee67aba5ef227a5f9ad7dd6649fd7fd$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$5a6a0d71730c62ed6b47fdffe683af4b0b477d4ec8ed8b23873d16a07ae597a7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$5a6a0d71730c62ed6b47fdffe683af4b0b477d4ec8ed8b23873d16a07ae597a7$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$5a6a0d71730c62ed6b47fdffe683af4b0b477d4ec8ed8b23873d16a07ae597a7$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$5a6a0d71730c62ed6b47fdffe683af4b0b477d4ec8ed8b23873d16a07ae597a7$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$5ce1782e38b823db2338ad748eccb6f95d791933bf703110d4cd213ed3530a63$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$5d62a49eb8e9e9408a6f7f0896d746ef548ffe8ea91a5475367979b1a8297e49$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$5d62a49eb8e9e9408a6f7f0896d746ef548ffe8ea91a5475367979b1a8297e49$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$82212297f89074777ec202294f2358976b66cd3abd35df99377def95e5e9a9ae$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$82212297f89074777ec202294f2358976b66cd3abd35df99377def95e5e9a9ae$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$82212297f89074777ec202294f2358976b66cd3abd35df99377def95e5e9a9ae$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$82212297f89074777ec202294f2358976b66cd3abd35df99377def95e5e9a9ae$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$9bbf0572bc853491e0f6bf417993c4bb8d9ed1e13c7ed3e27cf3dc514eef9b1a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$9bbf0572bc853491e0f6bf417993c4bb8d9ed1e13c7ed3e27cf3dc514eef9b1a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$9bbf0572bc853491e0f6bf417993c4bb8d9ed1e13c7ed3e27cf3dc514eef9b1a$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$9bbf0572bc853491e0f6bf417993c4bb8d9ed1e13c7ed3e27cf3dc514eef9b1a$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$9bbf0572bc853491e0f6bf417993c4bb8d9ed1e13c7ed3e27cf3dc514eef9b1a$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$9bbf0572bc853491e0f6bf417993c4bb8d9ed1e13c7ed3e27cf3dc514eef9b1a$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$9bbf0572bc853491e0f6bf417993c4bb8d9ed1e13c7ed3e27cf3dc514eef9b1a$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$a8c5c12dffb3c90153bb3b61bf6ee96fa11c84ce43d66f04a6b15c702a55f07e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$a8c5c12dffb3c90153bb3b61bf6ee96fa11c84ce43d66f04a6b15c702a55f07e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$a8c5c12dffb3c90153bb3b61bf6ee96fa11c84ce43d66f04a6b15c702a55f07e$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$a8c5c12dffb3c90153bb3b61bf6ee96fa11c84ce43d66f04a6b15c702a55f07e$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$a8c5c12dffb3c90153bb3b61bf6ee96fa11c84ce43d66f04a6b15c702a55f07e$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$a8c5c12dffb3c90153bb3b61bf6ee96fa11c84ce43d66f04a6b15c702a55f07e$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$b22815a5be0d6a79871ce0c5b62ead380df691fa75ede7a90478075fe66ab3c1$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$f43a812e05433a18be28217238ed8e2cc79a5a3eeb97bb71138372f24ac97afa$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$f43a812e05433a18be28217238ed8e2cc79a5a3eeb97bb71138372f24ac97afa$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$f43a812e05433a18be28217238ed8e2cc79a5a3eeb97bb71138372f24ac97afa$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$f43a812e05433a18be28217238ed8e2cc79a5a3eeb97bb71138372f24ac97afa$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$f43a812e05433a18be28217238ed8e2cc79a5a3eeb97bb71138372f24ac97afa$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$f43a812e05433a18be28217238ed8e2cc79a5a3eeb97bb71138372f24ac97afa$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$$InternalSyntheticLambda$2$f43a812e05433a18be28217238ed8e2cc79a5a3eeb97bb71138372f24ac97afa$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/ComposableSingletons$BlockedActivityKt$$InternalSyntheticLambda$2$facc4e1da32643d247fa310529cac7dcdc252ee2fa5dc5f6a7eef01cd8279f65$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/ComposableSingletons$BlockedActivityKt$$InternalSyntheticLambda$2$facc4e1da32643d247fa310529cac7dcdc252ee2fa5dc5f6a7eef01cd8279f65$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/ComposableSingletons$BlockedActivityKt$$InternalSyntheticLambda$2$facc4e1da32643d247fa310529cac7dcdc252ee2fa5dc5f6a7eef01cd8279f65$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/ComposableSingletons$BlockedActivityKt$$InternalSyntheticLambda$2$facc4e1da32643d247fa310529cac7dcdc252ee2fa5dc5f6a7eef01cd8279f65$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/ComposableSingletons$BlockedActivityKt$$InternalSyntheticLambda$2$facc4e1da32643d247fa310529cac7dcdc252ee2fa5dc5f6a7eef01cd8279f65$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/ComposableSingletons$BlockedActivityKt$$InternalSyntheticLambda$2$facc4e1da32643d247fa310529cac7dcdc252ee2fa5dc5f6a7eef01cd8279f65$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/CountdownBanner$$InternalSyntheticLambda$2$00b67162aedb5d70be342484a1fd9d9738c53e9c0818881b71327782f8b6ca32$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/CountdownBanner$$InternalSyntheticLambda$2$00b67162aedb5d70be342484a1fd9d9738c53e9c0818881b71327782f8b6ca32$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/CountdownBanner$$InternalSyntheticLambda$2$2eb392fa09267459c6ecf3e03078509e96f424b6ff42fdece42c00379878a514$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$1ae620d61791ddcf3d9197be42847dae0c75bf136eb9deaeafb92e68b73602a7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$20507b1a4ea8f1b2c6274028cd4e38270a27c447c7823b9510f8f0607933010d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$215f3f4b74e8197d215a5e55fff4aa972ee7194d812164cd8607498659db8399$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$2efe22432f83624e4f9ebac8258ff7eec5a4fc8cfeaf6ab513f5b58fb5e07b28$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$2efe22432f83624e4f9ebac8258ff7eec5a4fc8cfeaf6ab513f5b58fb5e07b28$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$2efe22432f83624e4f9ebac8258ff7eec5a4fc8cfeaf6ab513f5b58fb5e07b28$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$304d7006c414b1f612406a0e2d4c59e6f4dcbd6d0f908ffae0e8aae67f374360$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$304d7006c414b1f612406a0e2d4c59e6f4dcbd6d0f908ffae0e8aae67f374360$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$3baba6123cb147f7d2e650b42e1feb6a6fc5432592fac651b38689867c168902$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$3baba6123cb147f7d2e650b42e1feb6a6fc5432592fac651b38689867c168902$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$56ad910e4cabb2229ff942955a22481dd08cb713b37e5a63e89f2254deaa64d4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$6227544bb31c137fdb4e13bd86eda5713e1bd5b744573527997fc29b9e6e0e72$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$b689f3492b771d71926e5eb7339f44d4314394ac835477aa2cfd38ab2dbd5554$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$be6e029a05e05675cd6d8ab0b00a758453cef0303b9ae2a7ef37429ae6b5f188$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$be6e029a05e05675cd6d8ab0b00a758453cef0303b9ae2a7ef37429ae6b5f188$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$be6e029a05e05675cd6d8ab0b00a758453cef0303b9ae2a7ef37429ae6b5f188$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$be6e029a05e05675cd6d8ab0b00a758453cef0303b9ae2a7ef37429ae6b5f188$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$dc0e6e5b9d08647ef5b2cceb138ef5c7f5882e81ebf190c08577d3fc53260a63$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$e7c1748fcd86088ae2b8929b40e51451150c358219307114724d38010466709d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$e7c1748fcd86088ae2b8929b40e51451150c358219307114724d38010466709d$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$e7c1748fcd86088ae2b8929b40e51451150c358219307114724d38010466709d$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$eb06aa5a44b580a3e79a7ce9f6a975a8ed21b2caec96c8f92b59e82d79643b3d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$$InternalSyntheticLambda$2$feb2ef20cba4279c89b61e0519ba964b8712a219c9c9eeb1eedf63a7bede42df$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$$InternalSyntheticLambda$2$3762f98b1088f7db3122b7aa65515c4aabe455b8f222048824e10adc4cf259b5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$$InternalSyntheticLambda$2$55108d350d4cafcb40fdcfb71df9c57d6760594f0ba7f1bc63a96d8b15320742$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$$InternalSyntheticLambda$2$55108d350d4cafcb40fdcfb71df9c57d6760594f0ba7f1bc63a96d8b15320742$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$$InternalSyntheticLambda$2$72ced6d9ed4d23e198d1cee761b989ff5db5358f6b801a00144ce6b9ff1317a3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$$InternalSyntheticLambda$2$ac6ba7167c8341b8283d31041e84ac167c87f222330374cd5ad2ef2257b5b4ab$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$$InternalSyntheticLambda$2$ac6ba7167c8341b8283d31041e84ac167c87f222330374cd5ad2ef2257b5b4ab$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$$InternalSyntheticLambda$2$ac6ba7167c8341b8283d31041e84ac167c87f222330374cd5ad2ef2257b5b4ab$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$$InternalSyntheticLambda$2$b7926e501210295976141e92684b9eb895e533ae9fb2ab63e907ede50d4855c3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$offerTeamsSync$2$1$$InternalSyntheticLambda$2$49df63bad06bdef0970b61f45069e60daf0d2f728545be4f6b852eb78a03268a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$offerTeamsSync$3$1$$InternalSyntheticLambda$2$2645a2fab1946f799cf7004de3fa73d2bf0104403d49dd2e92e95cd607fb48e7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$rememberSite$1$$InternalSyntheticLambda$2$613ae64ea34899ffc8871813ed58c764acc1af5f1b930e2b1e62892dd7b2453c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$screenReceiver$1$$InternalSyntheticLambda$2$e0459414588ff0ab53d6e679c9b615d8241a86a76ce7ebf73a268f3aa509371c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/PhotoChecks$$InternalSyntheticLambda$2$b26f49ed18c98b800aa5931ac1177899adc76736e46a2abfae9b02b128160b74$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/PhotoChecks$$InternalSyntheticLambda$2$c60faa1ffd778d8e0ea5b3794f69b4eb2fc29af88f3f2139cbccb90120d22353$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/PhotoChecks$$InternalSyntheticLambda$2$c60faa1ffd778d8e0ea5b3794f69b4eb2fc29af88f3f2139cbccb90120d22353$1.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Calibration$$InternalSyntheticLambda$2$359db35c4b85318de184585e1a06b266a4f87cb39a852245d37ad3dd11185566$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Calibration$$InternalSyntheticLambda$2$359db35c4b85318de184585e1a06b266a4f87cb39a852245d37ad3dd11185566$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Calibration$$InternalSyntheticLambda$2$359db35c4b85318de184585e1a06b266a4f87cb39a852245d37ad3dd11185566$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Change$$InternalSyntheticLambda$2$6e9e071d5010d1bc3aa7a8e01f044a74f7e305771dce8f3f2558757b3ea1da38$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/ChangeType$$InternalSyntheticLambda$2$09ec3fab2d929c653253edb40910cd29fed692a85da21b9f45fe4d51f254bd79$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Enrichment$$InternalSyntheticLambda$2$df6ce85d9ec95a5e77600527b9c7ead0e0005b8b5b774412142d98444ff8383a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Enrichment$$InternalSyntheticLambda$2$df6ce85d9ec95a5e77600527b9c7ead0e0005b8b5b774412142d98444ff8383a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Enrichment$$InternalSyntheticLambda$2$df6ce85d9ec95a5e77600527b9c7ead0e0005b8b5b774412142d98444ff8383a$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/EnrichmentKt$$InternalSyntheticLambda$2$a67950eac827755282ce8f10ff1010a3477bc9aedad18e90a16d7bf08bfa65e6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Enrichments$$InternalSyntheticLambda$2$d34cf980c90b11fc4d9fbed10bf80c77b9191029af7147d6a4588d7a6c73d5b9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Instruction$$InternalSyntheticLambda$2$b3ba18270747e5eae77375e2385a74f7319b886f85e88d4e41dbf848b10d19ad$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Instruction$$InternalSyntheticLambda$2$b3ba18270747e5eae77375e2385a74f7319b886f85e88d4e41dbf848b10d19ad$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/InstructionState$$InternalSyntheticLambda$2$5d0f32b8fdbe21d2d3a36eff8e0e73c47d8da013b65f0a1e82611bb1389ad1d0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/InstructionStatus$$InternalSyntheticLambda$2$0af98aa62c95422fcad696e0080891ff434ba4a0e29f68431378614be919021e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Kind$$InternalSyntheticLambda$2$3b81f5869ee54403cd1c8977ef11d734218c0f0da93b6528adfb5a464660648d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Merge$$InternalSyntheticLambda$2$a8742cdc5d0e237d89b4762ee597f81215aa743eae45d3ffafd1d8882e38d248$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$02a201cfa44981cacabe0bc2c22d3d7306c838de28f3e0f54f17bd0a7f5693f9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$02a201cfa44981cacabe0bc2c22d3d7306c838de28f3e0f54f17bd0a7f5693f9$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$10.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$11.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$28eea8a5357433e8e48561cc547a6dbe85bf7f17b0575534d86c2515508842fa$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$317559cc26757f070ff6bb8fb2709ca89f1b8ce50d6a301ce1eb65d0b25715da$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$317559cc26757f070ff6bb8fb2709ca89f1b8ce50d6a301ce1eb65d0b25715da$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$33c9fd97deaad6de91212471a35350be9db79a884d276061c17b84c1f48b2827$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$33c9fd97deaad6de91212471a35350be9db79a884d276061c17b84c1f48b2827$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6b4b747bf036c8f374f5c0ddffd06c5e657b6efb670e53b51d94dc5e3bf2924e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6b4b747bf036c8f374f5c0ddffd06c5e657b6efb670e53b51d94dc5e3bf2924e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6de568e63fc764d67c8c2d85d2331f621b3948452fc5845060ddb10a9aedd4db$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6de568e63fc764d67c8c2d85d2331f621b3948452fc5845060ddb10a9aedd4db$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6de568e63fc764d67c8c2d85d2331f621b3948452fc5845060ddb10a9aedd4db$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6de568e63fc764d67c8c2d85d2331f621b3948452fc5845060ddb10a9aedd4db$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6de568e63fc764d67c8c2d85d2331f621b3948452fc5845060ddb10a9aedd4db$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6de568e63fc764d67c8c2d85d2331f621b3948452fc5845060ddb10a9aedd4db$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6de568e63fc764d67c8c2d85d2331f621b3948452fc5845060ddb10a9aedd4db$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$6de568e63fc764d67c8c2d85d2331f621b3948452fc5845060ddb10a9aedd4db$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$73269add5e0d7b57725a969538ca530b6a68fa8b32b61e599a074bd8aa661fa8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$73269add5e0d7b57725a969538ca530b6a68fa8b32b61e599a074bd8aa661fa8$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$73269add5e0d7b57725a969538ca530b6a68fa8b32b61e599a074bd8aa661fa8$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$73269add5e0d7b57725a969538ca530b6a68fa8b32b61e599a074bd8aa661fa8$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$7a94f7b18c87bdb179128b5bf914b5a79aca364e2ab8a53dc4009fa7853a1be6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$7a94f7b18c87bdb179128b5bf914b5a79aca364e2ab8a53dc4009fa7853a1be6$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$cc46ae22c015508e607af3429999b3d8979bd3b0937dbbcc87a0d8b7045dc650$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$cc46ae22c015508e607af3429999b3d8979bd3b0937dbbcc87a0d8b7045dc650$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$da3862efd10c627fbce3d383a25458f1a8ea95c5e27dd6b4fbebcfc306cde7b0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$$InternalSyntheticLambda$2$da3862efd10c627fbce3d383a25458f1a8ea95c5e27dd6b4fbebcfc306cde7b0$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Source$$InternalSyntheticLambda$2$dce3fdc909a0aad36323cfab668822797036f62fe23b58d57669446b17bcbb5b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/SourceValues$$InternalSyntheticLambda$2$be3a4c5e8126043c51f4ff82a75217b84c35a37cd0a25a7c945e8c0ff6059e91$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Status$$InternalSyntheticLambda$2$0e5485fb00d9b35a7d9b4022dbf195133c3c1f0768fe143b6f3b34fd2e9504ae$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/SubStep$$InternalSyntheticLambda$2$fe19147718d485e430b6dfff1c4b64d29f743ccdf384f588ccd03f86445d7bed$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem$$InternalSyntheticLambda$2$94b32a0daa89f4c8fa5f1aea73c5cda772ef8214e16f7ab8d16bcc1b80f07843$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem$$InternalSyntheticLambda$2$94b32a0daa89f4c8fa5f1aea73c5cda772ef8214e16f7ab8d16bcc1b80f07843$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem$$InternalSyntheticLambda$2$94b32a0daa89f4c8fa5f1aea73c5cda772ef8214e16f7ab8d16bcc1b80f07843$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem$$InternalSyntheticLambda$2$94b32a0daa89f4c8fa5f1aea73c5cda772ef8214e16f7ab8d16bcc1b80f07843$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem$$InternalSyntheticLambda$2$94b32a0daa89f4c8fa5f1aea73c5cda772ef8214e16f7ab8d16bcc1b80f07843$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem$$InternalSyntheticLambda$2$94b32a0daa89f4c8fa5f1aea73c5cda772ef8214e16f7ab8d16bcc1b80f07843$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem$$InternalSyntheticLambda$2$94b32a0daa89f4c8fa5f1aea73c5cda772ef8214e16f7ab8d16bcc1b80f07843$6.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog$$InternalSyntheticLambda$2$be06d29ed51480d8258a628ef00ba30a262ae564f224958045e046b01d066c5e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog$$InternalSyntheticLambda$2$be06d29ed51480d8258a628ef00ba30a262ae564f224958045e046b01d066c5e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog$$InternalSyntheticLambda$2$be06d29ed51480d8258a628ef00ba30a262ae564f224958045e046b01d066c5e$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog$$InternalSyntheticLambda$2$be06d29ed51480d8258a628ef00ba30a262ae564f224958045e046b01d066c5e$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog$$InternalSyntheticLambda$2$be06d29ed51480d8258a628ef00ba30a262ae564f224958045e046b01d066c5e$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog$$InternalSyntheticLambda$2$be06d29ed51480d8258a628ef00ba30a262ae564f224958045e046b01d066c5e$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog$$InternalSyntheticLambda$2$be06d29ed51480d8258a628ef00ba30a262ae564f224958045e046b01d066c5e$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backup$$InternalSyntheticLambda$2$78408016ce6f52a488986744206c355a7852bed90461481b25ee5a234bf0c688$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backups$$InternalSyntheticLambda$2$7c82b65ac5a50da16663d52ad252714f5fd74856364e0ce99a388dfeeaee2a0e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/CompletionRecord$$InternalSyntheticLambda$2$f9df913220ca375482d24e4e472508884960c99b8b1498c51e33b22a3e4c8cb9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/CompletionRecord$$InternalSyntheticLambda$2$f9df913220ca375482d24e4e472508884960c99b8b1498c51e33b22a3e4c8cb9$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/JsonStore$$InternalSyntheticLambda$2$2037c3b0d9049b950b8c1d2c146a89802c0584726c8be41c9f1af8c5d07afccb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/JsonStore$$InternalSyntheticLambda$2$ae888f10dfc44918411b02b6b1f04c00d5b6a7c8e7e90a932b484ad8eb9987df$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ProtectionRecord$$InternalSyntheticLambda$2$06383162430a2576156f2d7af44e01240ae6062ffba95ae3fc72b9adc8377788$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ProtectionState$$InternalSyntheticLambda$2$d3e27c423cf37b82f4e4185a4e6896c8d147cb509f79403c2d996c81f6ea1a66$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$InternalSyntheticLambda$2$46e81282b76f632a70a3bc7d5748aa290beb775a00cc70d1918665c5a90a9602$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SessionRecord$$InternalSyntheticLambda$2$7dfd9632be050a82436ddab95f7534a10a5aae31a9a078103f0d0375b1e114ea$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Settings$$InternalSyntheticLambda$2$8b43cf70952bb56e4a224412811de9e9153b5c82ec9ffe3fdcb4c44838f64079$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Settings$$InternalSyntheticLambda$2$8b43cf70952bb56e4a224412811de9e9153b5c82ec9ffe3fdcb4c44838f64079$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Settings$$InternalSyntheticLambda$2$8b43cf70952bb56e4a224412811de9e9153b5c82ec9ffe3fdcb4c44838f64079$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Settings$$InternalSyntheticLambda$2$8b43cf70952bb56e4a224412811de9e9153b5c82ec9ffe3fdcb4c44838f64079$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/TaskState$$InternalSyntheticLambda$2$416c81b2733372c37d33d3a292cc8f9d9749ddb9c55310f701a99c0d004a2abc$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/TaskState$$InternalSyntheticLambda$2$416c81b2733372c37d33d3a292cc8f9d9749ddb9c55310f701a99c0d004a2abc$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/TaskState$$InternalSyntheticLambda$2$416c81b2733372c37d33d3a292cc8f9d9749ddb9c55310f701a99c0d004a2abc$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/WeeklyReview$$InternalSyntheticLambda$2$8240c17c64c89f209e72590808a6ef4cccc65ae26dcec05f3489f6061306fc84$0.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$3045a90020f68ad63a4befbd8b8709c2456b149f28485a7a04cc9fabd100b682$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$7e208c797eeea88265b740d1003a4939b6873c6d6a715ac95b588a7d295031e6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$7e208c797eeea88265b740d1003a4939b6873c6d6a715ac95b588a7d295031e6$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$10.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$$InternalSyntheticLambda$2$dcf9f18666dc5700fda098677a8f57eba689ffbec8f0bc30f8e0f20dea8702bd$9.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/AiUsage$$InternalSyntheticLambda$2$e44d48772c28188f65818a8917a3404f0f8daa329b57907a0fbde8c81963b6fa$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$$InternalSyntheticLambda$2$0b14bba34a855a73b3795d1a8f31fa2afae5e7565ce5a3db125b38f2d2e745e1$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$$InternalSyntheticLambda$2$182d67a98a7fb7e27c39e84c82f7313208a5866d9a97ddd4048e5c788b9e57cc$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$$InternalSyntheticLambda$2$3b401a1244d217d89be4a862aaedbabbab4bd06c3a7a3c2fe06fb3d30b30bdfb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$$InternalSyntheticLambda$2$5723633b79e8a1869cc8b112d0687b12e46036a09ee3ffe551be5f51d82cf721$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Estimate$$InternalSyntheticLambda$2$d4c5ce4754207d07469780c5244066039bf8f7953d0535ef02ccfdbe9585b428$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Split$$InternalSyntheticLambda$2$576a89a4afb96e59b7098cbcfed387c3f784ab25a767b9a3fb26b175f7664c29$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Split$$InternalSyntheticLambda$2$576a89a4afb96e59b7098cbcfed387c3f784ab25a767b9a3fb26b175f7664c29$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Step$$InternalSyntheticLambda$2$b2384fca11cc0b84ec249430686f7a7eae61cc441327846f70951780dea02ae9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Triage$$InternalSyntheticLambda$2$a884c6b163fafa343b497867118877a535582df41837010bb90be8d435cc4732$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeEnricher$$InternalSyntheticLambda$2$8a3d63b1ec95291214affc432a8a14c49bb16972fc3efedf95579d64d60563af$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeReviewer$$InternalSyntheticLambda$2$15507b92a223bf08a5fcbec4285a88872df13f3ace3db67cc9669fe0d58a68cf$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$$InternalSyntheticLambda$2$6f492673d2d53646a990b9d7e99ce528659ff73a61b187cf789deff76caddca1$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Answer$$InternalSyntheticLambda$2$a33e6cde4c9027707c78dab752ce7b63b0c056c3f5d8a0ed1a0dc39c5b23f685$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/KeyProblem$$InternalSyntheticLambda$2$a7123cab5df30421a49ba2bca47664c7ee1813548318f502fb06d269d164baea$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/KeyProblem$Companion$$InternalSyntheticLambda$2$65c8c4e4d6d9df84a79915d84dcf04fc4aa0c52771e2221cb1da42d93d2732f4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/KeyProblem$Companion$$InternalSyntheticLambda$2$65c8c4e4d6d9df84a79915d84dcf04fc4aa0c52771e2221cb1da42d93d2732f4$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/KeyProblem$Companion$$InternalSyntheticLambda$2$65c8c4e4d6d9df84a79915d84dcf04fc4aa0c52771e2221cb1da42d93d2732f4$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/PhotoChecker$$InternalSyntheticLambda$2$1e121fef987bf9de342df83c9e44c72c84d73a00c3c93ae4ad8564bc85b265fb$0.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$1196a015a168cf9eeec00ecf2293c44506cf6e56fa0da5c9e6b642441c0acc60$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$1196a015a168cf9eeec00ecf2293c44506cf6e56fa0da5c9e6b642441c0acc60$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$1196a015a168cf9eeec00ecf2293c44506cf6e56fa0da5c9e6b642441c0acc60$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$1196a015a168cf9eeec00ecf2293c44506cf6e56fa0da5c9e6b642441c0acc60$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$24898931d568c96acab7e963bb2df486bde2b91b70ed797a3de377fe87aaf2bb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$24898931d568c96acab7e963bb2df486bde2b91b70ed797a3de377fe87aaf2bb$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$3e9d7a9b3e95f6707d5e21a052d3724e4962e5fedfd4e37bcf8374e00edb5a58$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$44d4710e9ecbfaeb0c641603ae01a3ea9366de7fc70bea22031ba4416754388d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$4ac34907f5ed58fe5bfa111dbfe0dadc76a74a0b5ec4b89be6cbebf17f5cf420$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$4ac34907f5ed58fe5bfa111dbfe0dadc76a74a0b5ec4b89be6cbebf17f5cf420$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$c05e859fc5b069f217a879f0745adff3eebc0763745cd3e6c24bec166f7110cd$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$fa2759d356c4af43bb98165acc9dee09160b4c5da1547bd1e554ce1017b5fb9b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$fa2759d356c4af43bb98165acc9dee09160b4c5da1547bd1e554ce1017b5fb9b$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$fa2759d356c4af43bb98165acc9dee09160b4c5da1547bd1e554ce1017b5fb9b$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$$InternalSyntheticLambda$2$fa2759d356c4af43bb98165acc9dee09160b4c5da1547bd1e554ce1017b5fb9b$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Assessment$$InternalSyntheticLambda$2$032fd6deccd3b1892dc5eb9f9263fdbd38d1707e4bf2924851bc0c4820b318cb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Assessment$$InternalSyntheticLambda$2$2f424ab43ab075ddf69ebe7b1189890d9f4411b1cdc687c01f4041f96da942ed$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Assessment$$InternalSyntheticLambda$2$5e2a4c69e2c03704c44fdda6950baec84222fc9554ca2d1cba70df0d05984abb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Assessment$$InternalSyntheticLambda$2$a0e0b8155cab89d7583ffa0017167586c20769dbdb8732ea193c5153ec6507e8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Briefing$$InternalSyntheticLambda$2$505e27c8ddd38a10e14dde2f1ddf8197c4937360fe8387e475d6413a603f9f48$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Briefing$$InternalSyntheticLambda$2$505e27c8ddd38a10e14dde2f1ddf8197c4937360fe8387e475d6413a603f9f48$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Briefing$$InternalSyntheticLambda$2$580017a0fabdd951313738f371379c0f42d9f920159480ca7f864c30d1d903eb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CalendarTime$$InternalSyntheticLambda$2$7efc413ccd5b63f5b4b06aa9f9ce86e26ec784f12f78ff6bcbb761f616bb7165$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CalendarTime$$InternalSyntheticLambda$2$7efc413ccd5b63f5b4b06aa9f9ce86e26ec784f12f78ff6bcbb761f616bb7165$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Calibrator$$InternalSyntheticLambda$2$b9a8b702ec233292d4a500086c1e4709135d0c13c2ef6ee0946faa4f9f65ddf3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Calibrator$$InternalSyntheticLambda$2$b9a8b702ec233292d4a500086c1e4709135d0c13c2ef6ee0946faa4f9f65ddf3$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$6d36df7a481777974e2099e9127eb12a29bd27f74d00d7f606c3863ee187ff9c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$79e70b82fd5996f0c388521f3fcf9aa98a3ae5bc7b5a1b4327b20fb44aee7b3f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$79e70b82fd5996f0c388521f3fcf9aa98a3ae5bc7b5a1b4327b20fb44aee7b3f$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$8886d1cf6a816f909826ea23a1a3c6747626f4fa74ff8d47065ee7df65a19624$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$8886d1cf6a816f909826ea23a1a3c6747626f4fa74ff8d47065ee7df65a19624$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$8886d1cf6a816f909826ea23a1a3c6747626f4fa74ff8d47065ee7df65a19624$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$8886d1cf6a816f909826ea23a1a3c6747626f4fa74ff8d47065ee7df65a19624$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$8886d1cf6a816f909826ea23a1a3c6747626f4fa74ff8d47065ee7df65a19624$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$8886d1cf6a816f909826ea23a1a3c6747626f4fa74ff8d47065ee7df65a19624$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$8886d1cf6a816f909826ea23a1a3c6747626f4fa74ff8d47065ee7df65a19624$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$b7420e152e276378d8c333201a51deff0552423b131a4e445ee4f2b9e0831552$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f482f92b540f39199ae3ab11d0bb4db042353e5907e4b1a3276470f131d26629$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f482f92b540f39199ae3ab11d0bb4db042353e5907e4b1a3276470f131d26629$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f482f92b540f39199ae3ab11d0bb4db042353e5907e4b1a3276470f131d26629$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f482f92b540f39199ae3ab11d0bb4db042353e5907e4b1a3276470f131d26629$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f482f92b540f39199ae3ab11d0bb4db042353e5907e4b1a3276470f131d26629$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f482f92b540f39199ae3ab11d0bb4db042353e5907e4b1a3276470f131d26629$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f482f92b540f39199ae3ab11d0bb4db042353e5907e4b1a3276470f131d26629$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f94f4b3d3fa37734afaf1d06e1124242a088c4f2951246289448c5e0c490392a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f94f4b3d3fa37734afaf1d06e1124242a088c4f2951246289448c5e0c490392a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f94f4b3d3fa37734afaf1d06e1124242a088c4f2951246289448c5e0c490392a$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f94f4b3d3fa37734afaf1d06e1124242a088c4f2951246289448c5e0c490392a$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f94f4b3d3fa37734afaf1d06e1124242a088c4f2951246289448c5e0c490392a$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f94f4b3d3fa37734afaf1d06e1124242a088c4f2951246289448c5e0c490392a$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$$InternalSyntheticLambda$2$f94f4b3d3fa37734afaf1d06e1124242a088c4f2951246289448c5e0c490392a$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$Form$1$1$6$1$1$$InternalSyntheticLambda$2$315fe259dbd0be4b1d19db7eb34931909c18c1d553ab9e4dd1be181f6bdf6f06$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$AssessmentKt$$InternalSyntheticLambda$2$2bf9e57bc5321ddda836ede23eeee1d4a107eb044122c7bcbb709da1cc62fbf9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$AssessmentKt$$InternalSyntheticLambda$2$2bf9e57bc5321ddda836ede23eeee1d4a107eb044122c7bcbb709da1cc62fbf9$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$AssessmentKt$$InternalSyntheticLambda$2$2bf9e57bc5321ddda836ede23eeee1d4a107eb044122c7bcbb709da1cc62fbf9$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$AssessmentKt$$InternalSyntheticLambda$2$2bf9e57bc5321ddda836ede23eeee1d4a107eb044122c7bcbb709da1cc62fbf9$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$CheckInsKt$$InternalSyntheticLambda$2$73d2e5dc31cac7f8f0dca99c9fa6c4c7cef1294f8a409394000859e9193aeae9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$CheckInsKt$$InternalSyntheticLambda$2$73d2e5dc31cac7f8f0dca99c9fa6c4c7cef1294f8a409394000859e9193aeae9$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$CheckInsKt$$InternalSyntheticLambda$2$73d2e5dc31cac7f8f0dca99c9fa6c4c7cef1294f8a409394000859e9193aeae9$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$CheckInsKt$$InternalSyntheticLambda$2$73d2e5dc31cac7f8f0dca99c9fa6c4c7cef1294f8a409394000859e9193aeae9$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$CheckInsKt$$InternalSyntheticLambda$2$73d2e5dc31cac7f8f0dca99c9fa6c4c7cef1294f8a409394000859e9193aeae9$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$CheckInsKt$$InternalSyntheticLambda$2$73d2e5dc31cac7f8f0dca99c9fa6c4c7cef1294f8a409394000859e9193aeae9$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$CheckInsKt$$InternalSyntheticLambda$2$73d2e5dc31cac7f8f0dca99c9fa6c4c7cef1294f8a409394000859e9193aeae9$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DailyReceiver$onReceive$1$$InternalSyntheticLambda$2$c4ef1081d5d84d8981140983cb7b92fa16a22287debc569857af9c63d4a0f7e5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DailyReceiver$onReceive$1$$InternalSyntheticLambda$2$c4ef1081d5d84d8981140983cb7b92fa16a22287debc569857af9c63d4a0f7e5$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DailyReceiver$onReceive$1$$InternalSyntheticLambda$2$c4ef1081d5d84d8981140983cb7b92fa16a22287debc569857af9c63d4a0f7e5$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DayRecord$$InternalSyntheticLambda$2$0d82b5538b776dcf8924e9fed5c07658e6e9014fbffb7353bab65c881d2be83c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventAnswerReceiver$onReceive$1$$InternalSyntheticLambda$2$d80c7f7cb8d5d13c132b37246af89469d1661d7e5c4c732644d33ab795cb57d1$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventJudge$$InternalSyntheticLambda$2$2670acf6ad70abb3731a56ca875d9dd6dd623d500fa44526ced614dbec9840ab$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventJudge$$InternalSyntheticLambda$2$2670acf6ad70abb3731a56ca875d9dd6dd623d500fa44526ced614dbec9840ab$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$$InternalSyntheticLambda$2$332b5313a4a317e640fc5f52bd6c02713c159c7b2125b9912f27a661320633e9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$$InternalSyntheticLambda$2$332b5313a4a317e640fc5f52bd6c02713c159c7b2125b9912f27a661320633e9$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$$InternalSyntheticLambda$2$332b5313a4a317e640fc5f52bd6c02713c159c7b2125b9912f27a661320633e9$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$$InternalSyntheticLambda$2$4716f5d9da103f6a26a04844cceaf55cf3905f00489e17a4c3fbf39c575f1e74$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$$InternalSyntheticLambda$2$4716f5d9da103f6a26a04844cceaf55cf3905f00489e17a4c3fbf39c575f1e74$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$$InternalSyntheticLambda$2$4716f5d9da103f6a26a04844cceaf55cf3905f00489e17a4c3fbf39c575f1e74$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$$InternalSyntheticLambda$2$76188aace4fbb0a677462c967165d8294bdfddd6ab27a677c8e3079d1e6a5cf8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$$InternalSyntheticLambda$2$76188aace4fbb0a677462c967165d8294bdfddd6ab27a677c8e3079d1e6a5cf8$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$$InternalSyntheticLambda$2$ebde6904e3d0f1bdd338f49bf00f58569994f722778c2f12864a45d032e68433$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$$InternalSyntheticLambda$2$ebde6904e3d0f1bdd338f49bf00f58569994f722778c2f12864a45d032e68433$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$$InternalSyntheticLambda$2$ebde6904e3d0f1bdd338f49bf00f58569994f722778c2f12864a45d032e68433$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$$InternalSyntheticLambda$2$ebde6904e3d0f1bdd338f49bf00f58569994f722778c2f12864a45d032e68433$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$$InternalSyntheticLambda$2$fd437fd14943e5bacdb1663a5cf99f15858bbca5d05f2ba0fe25413bf76907a5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$$InternalSyntheticLambda$2$fd437fd14943e5bacdb1663a5cf99f15858bbca5d05f2ba0fe25413bf76907a5$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$$InternalSyntheticLambda$2$fd437fd14943e5bacdb1663a5cf99f15858bbca5d05f2ba0fe25413bf76907a5$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$$InternalSyntheticLambda$2$fd437fd14943e5bacdb1663a5cf99f15858bbca5d05f2ba0fe25413bf76907a5$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$Answer$$InternalSyntheticLambda$2$0b83185bba38d237ef25b1e84293ee5dba58a6dd06f4a71f1c95c20b13843da0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$Answer$$InternalSyntheticLambda$2$0b83185bba38d237ef25b1e84293ee5dba58a6dd06f4a71f1c95c20b13843da0$1.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$10.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$11.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$12.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$13.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$14.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$15.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$16.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$17.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt$$InternalSyntheticLambda$2$4f26b985313f87b3642b6de28925765b2f1f8c9cf20fad9266d6820b54fd66f2$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$01af8db1ddc7014f7da7f8e48d08e092a4bfa5ac92379c863b16608c92b4b099$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$04f09b2e85ef22af102df34e7a879a0f51071fa2490870224fc586c8c75bce5b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$07019629984a8fc8f66500dc50711f1a066bc94d634060b148e553a65834501a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$0dda9adee83a9be30207e08fed59bb341bab511a6e1dd4b2c71a039008903c8b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$16bf6807a1fc1d22d4ae955f5d2c5e0b0d581eb14c91cbf770d7410aa374ad47$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$16bf6807a1fc1d22d4ae955f5d2c5e0b0d581eb14c91cbf770d7410aa374ad47$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$16bf6807a1fc1d22d4ae955f5d2c5e0b0d581eb14c91cbf770d7410aa374ad47$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$19eb86f9fab9eb9304301d337dffb9d1f16f115835fb19def9f1c1c5c0f57540$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$19eb86f9fab9eb9304301d337dffb9d1f16f115835fb19def9f1c1c5c0f57540$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$19eb86f9fab9eb9304301d337dffb9d1f16f115835fb19def9f1c1c5c0f57540$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$19eb86f9fab9eb9304301d337dffb9d1f16f115835fb19def9f1c1c5c0f57540$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1cf0c036f74e77ef506e4d43add2e5721d5438a6297adf511d76b54dc4803062$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1cf0c036f74e77ef506e4d43add2e5721d5438a6297adf511d76b54dc4803062$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1cf0c036f74e77ef506e4d43add2e5721d5438a6297adf511d76b54dc4803062$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1d8994d38e7064116111f1ad7876845dba7350887dcfb34a69eb9025c3e379db$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1dba00504e56abfc2dcdbf41524196792b48d22c5db117355c49787d50de3128$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1e17cd8c9d65f1555334bed4e0e11330cc6f742d99298a5d66a247c7de0a5e92$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1e17cd8c9d65f1555334bed4e0e11330cc6f742d99298a5d66a247c7de0a5e92$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1e17cd8c9d65f1555334bed4e0e11330cc6f742d99298a5d66a247c7de0a5e92$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1e17cd8c9d65f1555334bed4e0e11330cc6f742d99298a5d66a247c7de0a5e92$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$1e17cd8c9d65f1555334bed4e0e11330cc6f742d99298a5d66a247c7de0a5e92$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$247e137fedb559a030b1631e2ebdcbdd759ce5bfff7ef3d3c3b02594b7db9a88$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$247fcf028d6ec271e561a81b8296aa8bec803d8c2768a64901e6c7c596a7e164$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$247fcf028d6ec271e561a81b8296aa8bec803d8c2768a64901e6c7c596a7e164$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$247fcf028d6ec271e561a81b8296aa8bec803d8c2768a64901e6c7c596a7e164$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$247fcf028d6ec271e561a81b8296aa8bec803d8c2768a64901e6c7c596a7e164$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$247fcf028d6ec271e561a81b8296aa8bec803d8c2768a64901e6c7c596a7e164$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$247fcf028d6ec271e561a81b8296aa8bec803d8c2768a64901e6c7c596a7e164$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$2f95c6d104b64772f133f945536f3e3f1d42f1faa6e8365c80ad3fb57d1f95af$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$32e75ee1ad4f52c2ef99355f6858b89fb9ae5bb2e53433808178019eca5476b0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$34922b90c4a7a22432162162ed1b5921cd2e13e7d3c620c1267663e3fefa2948$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$3688437d0f893968ebd563d3e83ca41162047f3ec91b34b03520102c2e2ae600$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$3688437d0f893968ebd563d3e83ca41162047f3ec91b34b03520102c2e2ae600$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$3688437d0f893968ebd563d3e83ca41162047f3ec91b34b03520102c2e2ae600$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$3ddaa733c82322e67accb37afe377e275a120c2a66c322a3d4c932272c4d98ec$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$42bb0909819b8cb2e154e98ebc02430cecc994dad72dd64ce3899cf87ed9fa48$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$4b0260c448da68cdcbd3b0290fd1c40c244285c28b500fc0d84b0f7a42f8c743$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$4f0e734abff64edc1856b57e2d460802bd774f90dfa6b0924ec56ac31bfbaa66$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$5c07c63f88c88d62fc60177c52ba10813e8096f9307fcc18758e02f300927eb6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$5f59ba1e55b610d1ebc9fc460fba2f4c284476a0af60c1548138f31e2329a459$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$6077c0a7a8cbd419890ee8e4922aedf560cddd3445cde3370dff235ee45951bc$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$64f416c4c25eb34ea8f4a47e75286423b6962f450a1dcbf9d6f788ca33458541$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$64f416c4c25eb34ea8f4a47e75286423b6962f450a1dcbf9d6f788ca33458541$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$64f416c4c25eb34ea8f4a47e75286423b6962f450a1dcbf9d6f788ca33458541$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$64f416c4c25eb34ea8f4a47e75286423b6962f450a1dcbf9d6f788ca33458541$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$64f416c4c25eb34ea8f4a47e75286423b6962f450a1dcbf9d6f788ca33458541$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$64f416c4c25eb34ea8f4a47e75286423b6962f450a1dcbf9d6f788ca33458541$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$6a24549928ece1e81a87f031d91dddef78b9ba2afc66ee60ee9d09706d266ec9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$6de1d4211e6b042fcdf43514b2640c839c61915145f247de29785b00ad00593b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$6fed7803ac3a84d96f3dbcfa8bfcd954d6d11c632ec64937579c90de22ae3806$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$722cffdbd89dfb772a701aa8bb481180b76c4f542cfda383dd2bd2ef100c68f0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$7fc9c4b5806dfe4726801a1becf04bdaa503160053d5afd176e5b6ab90aad433$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$8a56aee7bfbde7805a79b277c3395df4186cd53ae7fde48fe79a8a29669b309d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$8b0aca5204fbee075a5ed50c311694b1f305951a7ad4ef30b84162f0dbd4868e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$8b983a62b70cba79d864b39cf61f84ef7b857e6a1fc5d55bc1c779803a508fb9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$8b983a62b70cba79d864b39cf61f84ef7b857e6a1fc5d55bc1c779803a508fb9$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$8b983a62b70cba79d864b39cf61f84ef7b857e6a1fc5d55bc1c779803a508fb9$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$8f180cd0761800c29bc7add3e9d9b6871ea81db2044c75ad9859771d102d4c69$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$90374402d77bdfb92948d0913963644fb7a9ad2042c1bc768c44b83ba9c1219c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$91069eda6b084fc6e4f29cc68ec4753bd33704072344894e880b0f469abd128b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$98c7a6a191307ff1055d4661ba8dc4c54cf451ac444effd9b4d0f60fcad13695$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$9b171e3e11cf5469085fe1d74e79abc9248f725fd61395d4760db559469b757f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$9b171e3e11cf5469085fe1d74e79abc9248f725fd61395d4760db559469b757f$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$9b171e3e11cf5469085fe1d74e79abc9248f725fd61395d4760db559469b757f$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$9b171e3e11cf5469085fe1d74e79abc9248f725fd61395d4760db559469b757f$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$9b171e3e11cf5469085fe1d74e79abc9248f725fd61395d4760db559469b757f$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$9c444daecccacfeac9ce6558561ae2b139594f4b862620880b1dca092fc266f5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a02cbf46239507052e2b05aaa699d30c27d3993e431bca9f3dd5d36451b6c57c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a1a93b4d803b877edfa97bd1906744f1327ff60ce5afcd195bde16b2f88e084d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$10.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$11.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$12.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$13.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$14.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$15.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a28cf887f44fbd9b6549532a64d99fe7b12beddab89064065e3b2bf2d0fe3fa0$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a32b10dfc2ad0f382f4c613296b12faa229585b562a1fe47a9a02daf22c293bb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$a607599cba2e71ae21d7fe53fd62f12accd5279292e4bea77bed2cd710739995$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$ab6fb5dabb16500977dfb103cbda3478535cf64b66e72e74e87df871fa98e402$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$ab6fb5dabb16500977dfb103cbda3478535cf64b66e72e74e87df871fa98e402$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$ab6fb5dabb16500977dfb103cbda3478535cf64b66e72e74e87df871fa98e402$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$b8c8a01b97d3fb59bae7fe98f44cc3c5302cb24154316fde1ca36f1c854808fc$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$bc7a33bcb06a847f48ff019e924d7b99161342693925fa2b811894799b550316$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$bc7a33bcb06a847f48ff019e924d7b99161342693925fa2b811894799b550316$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$bc7a33bcb06a847f48ff019e924d7b99161342693925fa2b811894799b550316$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$c17a8bc719cc28a2941b9a06e08eebb5940abd9395341d876b88f2f8b173869b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$c299f9289b506b2f82c8ed0c1aefcdc7b3f640c20495e6429f6fc6fa672e72a9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$c55df5e3b460364e750b888a26d04917e472fe41f064b3702d4aa9ecdb36e214$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$c660475bbdecad185df2c7ee85ebe395e2af9d708884426734ed2c64e76439be$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$c660475bbdecad185df2c7ee85ebe395e2af9d708884426734ed2c64e76439be$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$c67d5ba7985b2be97ced1b5d3202e7c6527169caf46c1a9bf36de457a77b7e64$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$c67d5ba7985b2be97ced1b5d3202e7c6527169caf46c1a9bf36de457a77b7e64$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$c67d5ba7985b2be97ced1b5d3202e7c6527169caf46c1a9bf36de457a77b7e64$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$c67d5ba7985b2be97ced1b5d3202e7c6527169caf46c1a9bf36de457a77b7e64$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$d2a4fc36872ad8003970f47a9134146b3b9143d1d0ddc760b0038829331ffaab$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$d3099b41d1d740c2572fbd359057ab8b4ac55a0e0a06d76d575b1b5e5a0674e2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$d3099b41d1d740c2572fbd359057ab8b4ac55a0e0a06d76d575b1b5e5a0674e2$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$d3099b41d1d740c2572fbd359057ab8b4ac55a0e0a06d76d575b1b5e5a0674e2$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$d3099b41d1d740c2572fbd359057ab8b4ac55a0e0a06d76d575b1b5e5a0674e2$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$d3099b41d1d740c2572fbd359057ab8b4ac55a0e0a06d76d575b1b5e5a0674e2$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$d3099b41d1d740c2572fbd359057ab8b4ac55a0e0a06d76d575b1b5e5a0674e2$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$d3293568e9e1451cc7fce075483b7107d84c5f8c14e6bd0bf948d687110ea74d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$e05bf586abcf514102eb0b8102dcd6e1c088675011fbbefadd884ac0bcf2e577$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$e35973de8296316f1d3b4320d95efca63f5726f771550988222a04fcb8ecdf4f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$e363ba5964d696b14277878480b61237545713429598437747a6876eb8e76ed6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$e6b2e612a0bb1b68fbee36f46af60ca23290b321dcd7c2277af7b48036b2a47b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$ec8f20725472fab6471b5e6216df5c170087388a9648bab51d105dd69a66c348$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$ee97b0f1459eb2033845a0a9011f6aae6ebd99748f75b586bddab499502b70e8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$f18e3aa7ad84d240e38dda4eb729d59e94ad7a7bf8d82f6e0591a8ac0042b384$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$$InternalSyntheticLambda$2$ff36cd294c109bcfea73605dd0f09142fdaa1c2b9730cebe92157b53fbe311d0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$ParentCodeDialog$1$1$1$1$$InternalSyntheticLambda$2$a7169796afc4467fb9ea0b7d9defc009e94d3e19c48b43f1645f550945ac3c16$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$Screen$1$1$1$1$2$1$1$1$$InternalSyntheticLambda$2$bb62f4fa6ba388931f6b706a7dbf60a7317bf5eb5813ef6aafd2ea651b4b6831$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$Screen$9$1$1$1$$InternalSyntheticLambda$2$71abeed86cebe4cfd51e28cc4e6db1330ffb40c8dc29c0d0489ed60aa92dcff3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionCheck$$InternalSyntheticLambda$2$8d2b85cca1132377466ccd74ca36c66db3c6e4a3052db649a550cead9501da01$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionCheck$$InternalSyntheticLambda$2$8d2b85cca1132377466ccd74ca36c66db3c6e4a3052db649a550cead9501da01$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$10.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$11.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$12.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$13.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$14.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$15.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$16.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$17.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$18.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$19.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$20.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$21.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$22.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$23.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$24.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$25.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$26.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$4c9c8e342e8780b3d4505e00d4fd8d5a67c7603221c708e35bb52fec2d57fa58$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$$InternalSyntheticLambda$2$7e3b12423176876ad3318ce9d988b5de2b739780a49a2c277b9a9f2fd74eed8b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/Watchdog$$InternalSyntheticLambda$2$b0326bff73178f3a131729d538565eff0a33ce5036ff99ea6413e7cce8e7b055$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/Watchdog$$InternalSyntheticLambda$2$b0326bff73178f3a131729d538565eff0a33ce5036ff99ea6413e7cce8e7b055$1.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiRules$$InternalSyntheticLambda$2$4dde652d7d99e4c4b044f88c322daa42643c1052b3693c51c9965ff2475af848$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiRules$$InternalSyntheticLambda$2$502b516fe704d6a7848d227b3b47e4e134e26aa7214e3f89a20158068b86eed0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiRules$$InternalSyntheticLambda$2$502b516fe704d6a7848d227b3b47e4e134e26aa7214e3f89a20158068b86eed0$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiRules$$InternalSyntheticLambda$2$c0663780e2dc3095fb1e5771b1335d69b60ed37fdc8c3355f6cf82eeb282c9da$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiRules$$InternalSyntheticLambda$2$c0663780e2dc3095fb1e5771b1335d69b60ed37fdc8c3355f6cf82eeb282c9da$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiRules$$InternalSyntheticLambda$2$c0663780e2dc3095fb1e5771b1335d69b60ed37fdc8c3355f6cf82eeb282c9da$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiRules$$InternalSyntheticLambda$2$c0663780e2dc3095fb1e5771b1335d69b60ed37fdc8c3355f6cf82eeb282c9da$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiSource$read$2$$InternalSyntheticLambda$2$e230483bf7b22c458545c70fe19804adaab9141f60a93656f26181668f309a12$0.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailThreads$$InternalSyntheticLambda$2$65114670dd8aded93b40c9dba164699369929eb6bf5aa0d315ee1074ecb47b4f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailThreads$$InternalSyntheticLambda$2$65114670dd8aded93b40c9dba164699369929eb6bf5aa0d315ee1074ecb47b4f$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/Mime$$InternalSyntheticLambda$2$1ce0ed2e95c1d3be8e29c580686fc500199a556602fd26c1ccbe586fd099aaf0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/Mime$$InternalSyntheticLambda$2$82ba2994450f27e32d598b23b1ef92a6ed076cdbfa03192737492c80eac58129$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/Mime$$InternalSyntheticLambda$2$9a5655cce387d36ee8ce221144b4ab6270c0b8472a3c815156ff1c829c3f6589$0.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/AgendaResponse$$InternalSyntheticLambda$2$3fd0638249e1a33fbaacf864753e7811ca17b23040050ef00b975043d6e31533$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/ClassesResponse$$InternalSyntheticLambda$2$3fefdc4c0615146c348509a8d5a214af1342c2edd774cc76f9c858c8b7f80bbf$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$$InternalSyntheticLambda$2$1fdb860286fc6f3c31c0240109ec2314c61195d30e1529e9cf54a2eb70b2b03c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$$InternalSyntheticLambda$2$283dbda5da5ba4edf35102190187623e163d5b813dae0c0a240ec9c356f81355$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$$InternalSyntheticLambda$2$3ace037a5550ffd5065bc5e9f77d5c1b7e317db78d68271ed920a5910952c935$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$$InternalSyntheticLambda$2$743e44e7074a89b221212085aee23dec7f28c4e2a3d8abf18694791a9075fa37$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$$InternalSyntheticLambda$2$e734846023193b2213415fdeec8874f31f9cb3551a66d25d8950149519e83da7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpClass$$InternalSyntheticLambda$2$fc9f1a3f51cd6ad3e4533c564760cbb60e5d6b5def16cfec4e1022aedb31db71$0.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$$InternalSyntheticLambda$2$84c567a7c1d359f8d6a56be3fc88e9aba67c3b1ced74dd2beeaa364b50932f6a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$$InternalSyntheticLambda$2$84c567a7c1d359f8d6a56be3fc88e9aba67c3b1ced74dd2beeaa364b50932f6a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$$InternalSyntheticLambda$2$a1e19dcd9da6e7cd7ead876689274babe7d7683305f78b67d200647649e8da3e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$$InternalSyntheticLambda$2$cb1135341c4204a9cd4fb745bf116deedad9719e7a1b26aaa4bcb6d63acb7fff$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$$InternalSyntheticLambda$2$eaf9e1517e14eb8dc24a50b5aa4c4d3c7b5db77e5ce8b6cbf68ef9674d3f9b4a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/SyncWorker$Companion$$InternalSyntheticLambda$2$78e9545b9ae706f15f1a50eac5c0d2e116dd43d0abb22d083795feec99f0caa5$0.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$0be206add64e6f8f55bfa088e136d0dd48fa883c8d5afe15804ac658427dff2c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$25e2d47e96d36e116aae12190675b5598a2f51745ea70d09843e101a3b766c09$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$25e2d47e96d36e116aae12190675b5598a2f51745ea70d09843e101a3b766c09$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$25e2d47e96d36e116aae12190675b5598a2f51745ea70d09843e101a3b766c09$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$2e63b9224a0dffff3d96e53b19f6edaa87e4247bf16b60afb835e392766f6ea2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$52fef64a1e714bd0a8ab1b620b390e5a2e2f01d47ca28c8acd78b9dbf6ab8134$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$52fef64a1e714bd0a8ab1b620b390e5a2e2f01d47ca28c8acd78b9dbf6ab8134$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$52fef64a1e714bd0a8ab1b620b390e5a2e2f01d47ca28c8acd78b9dbf6ab8134$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$52fef64a1e714bd0a8ab1b620b390e5a2e2f01d47ca28c8acd78b9dbf6ab8134$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$52fef64a1e714bd0a8ab1b620b390e5a2e2f01d47ca28c8acd78b9dbf6ab8134$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$7740c5c17851f36565f06739f526a1ab23c9351bc55ef2200df436808accc76b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$8bb2ae8af9f703e32100033c4f2ae13bdc5250fc0a392f6e8dd36b955cf6ad0f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$$InternalSyntheticLambda$2$8d5c6f90063a755488f1a7c517eac4492956146682a373807e26315ac3f6200e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$4919248e9065f332c4e0a597ad4fc956d979399f1605a549691842bcd171e439$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$69e2a7cd3b31193000832b4c99921406e6f5793366dc3318efc34223f3eff3f3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$6ff33a2f704d73b81d5557e6c62f8b3b3b425f9cd14e0727957789c637f8df07$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$6ff33a2f704d73b81d5557e6c62f8b3b3b425f9cd14e0727957789c637f8df07$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$6ff33a2f704d73b81d5557e6c62f8b3b3b425f9cd14e0727957789c637f8df07$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$8475f2a193c8f6e9ff8888b8a52df50f84a401bc455769fe9d168f542e5e4207$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$cf84ff5d8e50f47bafe0c9ae8d6bfa015792c8294405c2e12570cb6e03063fb3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$cf84ff5d8e50f47bafe0c9ae8d6bfa015792c8294405c2e12570cb6e03063fb3$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$da3d9f188563625b0d36f76f3930e3b0b69c5808679ca6582ac93ec227f76854$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$df9b29de9e2e7374ee98ac1451805263e127053d8883944ddd5f5c2dcee3660c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$eb7da5e30039bf5e1c4974b66f3c6828031434c70a5507f369f9165e8d62cf0b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$ef6a864f1a307163f909305092b85dd5c60d55323fe783c2866d84d838bb3aa2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt$$InternalSyntheticLambda$2$f89f7ec2081c998a877acbb2178d1ed91390966d103cdea738010848f6bd447a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$CalendarActivityKt$$InternalSyntheticLambda$2$a12235e083b830ed7229b39f363c06965540e57876f2716b1d3b947c1a667c4b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$CalendarActivityKt$$InternalSyntheticLambda$2$a12235e083b830ed7229b39f363c06965540e57876f2716b1d3b947c1a667c4b$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$CalendarActivityKt$$InternalSyntheticLambda$2$a12235e083b830ed7229b39f363c06965540e57876f2716b1d3b947c1a667c4b$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$CalendarActivityKt$$InternalSyntheticLambda$2$a12235e083b830ed7229b39f363c06965540e57876f2716b1d3b947c1a667c4b$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$ComponentsKt$$InternalSyntheticLambda$2$a1bf59e68e4d947fca4648d783de072845990523d193cf64625970ded7d36bfb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsActivityKt$$InternalSyntheticLambda$2$215badb90970279e04142752c9785c47a48805894f6cb88627611e94d1cd8985$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsActivityKt$$InternalSyntheticLambda$2$215badb90970279e04142752c9785c47a48805894f6cb88627611e94d1cd8985$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsActivityKt$$InternalSyntheticLambda$2$215badb90970279e04142752c9785c47a48805894f6cb88627611e94d1cd8985$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$10.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$11.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt$$InternalSyntheticLambda$2$e19f8b16b5ced4da5930237a2a88279534c7b8e384d77690cab1fa75875b1358$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$MainActivityKt$$InternalSyntheticLambda$2$b0183f78115076f4c7cb356b1821e625292c9e763f52355dbd1f0e2b7f9a2388$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$MainActivityKt$$InternalSyntheticLambda$2$b0183f78115076f4c7cb356b1821e625292c9e763f52355dbd1f0e2b7f9a2388$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$10.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$11.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$12.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$13.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt$$InternalSyntheticLambda$2$4ce5e52aef2c3bf254eb045959b6a3c87879e0beb8ed1d09027baea3984a2867$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SetupScreenKt$$InternalSyntheticLambda$2$8b104d83933621dfe60a2964b83a54556135ed8c72b418efcfa371a02b66464f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SetupScreenKt$$InternalSyntheticLambda$2$8b104d83933621dfe60a2964b83a54556135ed8c72b418efcfa371a02b66464f$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SetupScreenKt$$InternalSyntheticLambda$2$8b104d83933621dfe60a2964b83a54556135ed8c72b418efcfa371a02b66464f$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SetupScreenKt$$InternalSyntheticLambda$2$8b104d83933621dfe60a2964b83a54556135ed8c72b418efcfa371a02b66464f$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SetupScreenKt$$InternalSyntheticLambda$2$8b104d83933621dfe60a2964b83a54556135ed8c72b418efcfa371a02b66464f$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SetupScreenKt$$InternalSyntheticLambda$2$8b104d83933621dfe60a2964b83a54556135ed8c72b418efcfa371a02b66464f$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$StatsScreenKt$$InternalSyntheticLambda$2$8be45b6899989a7bc8988bc8a80953ee03ee8c73b280000918f4c3e9ea443fd5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$TasksScreenKt$$InternalSyntheticLambda$2$885fd87e8f74f3822fe197da6f379f2238d3247ac5b2ae7e249b57406b70aba8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$TasksScreenKt$$InternalSyntheticLambda$2$885fd87e8f74f3822fe197da6f379f2238d3247ac5b2ae7e249b57406b70aba8$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$TodayScreenKt$$InternalSyntheticLambda$2$bb8a637aefb9c8be26593608a1a67e62d539bd36958876918222045124d23ef7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$TodayScreenKt$$InternalSyntheticLambda$2$bb8a637aefb9c8be26593608a1a67e62d539bd36958876918222045124d23ef7$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$TodayScreenKt$$InternalSyntheticLambda$2$bb8a637aefb9c8be26593608a1a67e62d539bd36958876918222045124d23ef7$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$TodayScreenKt$$InternalSyntheticLambda$2$bb8a637aefb9c8be26593608a1a67e62d539bd36958876918222045124d23ef7$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$TodayScreenKt$$InternalSyntheticLambda$2$bb8a637aefb9c8be26593608a1a67e62d539bd36958876918222045124d23ef7$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$01ed1e020a4ff2c752c152ae1397564164db0fe48380ecbdeda0cbd17684a975$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$4448e6f56876e77fe027612a7e2dbff0acb7d6115024c4522f96563eafd6d2f2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$4d621ab62d3400d27fa4607988f6fa4518814eb303f49fe33f3c7aa91db4423c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$505c8795c40eaeaffd2d344dbbed74dc6de31be5438ebdb3a226814ae9cf051b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$505c8795c40eaeaffd2d344dbbed74dc6de31be5438ebdb3a226814ae9cf051b$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$505c8795c40eaeaffd2d344dbbed74dc6de31be5438ebdb3a226814ae9cf051b$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$505c8795c40eaeaffd2d344dbbed74dc6de31be5438ebdb3a226814ae9cf051b$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$60e4f48331ecc6e562fcc9033f9c5b637cb6b6942f9991f4484c5eb5efe353e3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$8d01152acf8daf8cf242f76f83de4edfaf9ce75549753a6ea54869483f29c3e6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$c66e00dd3c8af36dbe4a697c04d347acfe12caba77c7d493097f337d40d7e7d4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$c66e00dd3c8af36dbe4a697c04d347acfe12caba77c7d493097f337d40d7e7d4$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$facd037927075fd6800f4c770067d0b9c283a799795aa889f43bcf66e7d0b0ac$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$facd037927075fd6800f4c770067d0b9c283a799795aa889f43bcf66e7d0b0ac$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$$InternalSyntheticLambda$2$fb337e9bb984946d1ad3841368c1f5b06f3f371969a5c414040e101082a0e18b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$1c51c030a8d53814fc6d6b13ea166953454c5c7fde7710f62e2f06aba02e694e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3387e280dd9fd2ef7954d63b0bfd6126dce17aecd4e2d747e0e2fa9a6583ec92$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$37576d4916b7db7867f175139cfb77b5a8c2b93d7b516f0718b7d72267c28299$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$37576d4916b7db7867f175139cfb77b5a8c2b93d7b516f0718b7d72267c28299$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$37576d4916b7db7867f175139cfb77b5a8c2b93d7b516f0718b7d72267c28299$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$37576d4916b7db7867f175139cfb77b5a8c2b93d7b516f0718b7d72267c28299$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$10.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$11.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$12.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$3a8b1d4c977cdbb2e833e7c04f4015de6326d47645cab9ee8a8911303f582788$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$63806c0ff05958fe2fd415a5abd653225216e731867ae3997f93b44ca3cf455c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$977637082ec84e39bf4cbf3995b46746794b50ed703398b3bef4962b1d881aca$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$977637082ec84e39bf4cbf3995b46746794b50ed703398b3bef4962b1d881aca$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$977637082ec84e39bf4cbf3995b46746794b50ed703398b3bef4962b1d881aca$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$977637082ec84e39bf4cbf3995b46746794b50ed703398b3bef4962b1d881aca$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$$InternalSyntheticLambda$2$fa787d9b460b22322e4e85935204ce3d4c052ff67b2c0e78a878ef06ccb995de$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$05377ec89cb1d1bbc9c40869334e4b2bcb826a7d1cf7dece65c71e43d3e61f2f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$0b7d7374043e1942ed089e7a67ff5bc676af16e5e7eb3206f1724e782dcd02f0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$106ee9f0a0bc35d491628c5f97ca3a57061951638e3f2c5bfe075b0bfd84773c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$194bffd1a1a729eb7cf920b4a8bcb57766da05dd70c333d6421e53cc05b0f871$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$21252a44ef95e7778b5aa799513d5a0b946700ff092dc60149e5b8e793f7f657$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$47c0e45ccf43f450a66f4270c8ba76fe8755071c804b1b6eb185a7088d178875$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$47c0e45ccf43f450a66f4270c8ba76fe8755071c804b1b6eb185a7088d178875$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$70884bc7f793743807cd644431bf171dc330a82e7a3cd01dc8e02cbbeca9279e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$7eb1f5e2505972db50ab5d2c0c02b483655d710e357bec2cabaae4c850912430$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$7eb1f5e2505972db50ab5d2c0c02b483655d710e357bec2cabaae4c850912430$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$947e8660661e34220bc8640dcf2bff32f943177caa2435f3bc8580fd8f0de798$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$947e8660661e34220bc8640dcf2bff32f943177caa2435f3bc8580fd8f0de798$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$aef088125d05ab16328f3cb0c88463f324384e37a9ce7418c9998804b814af55$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$aef088125d05ab16328f3cb0c88463f324384e37a9ce7418c9998804b814af55$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$aef088125d05ab16328f3cb0c88463f324384e37a9ce7418c9998804b814af55$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$aef088125d05ab16328f3cb0c88463f324384e37a9ce7418c9998804b814af55$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$bbf017dfebc33f0f5ad8ab360ab1cc7d7f2d95ee0c22bf8c5e1c4f9708224894$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$daa113825fe729d92f67959089dec1d784e3470177e9db71939df973297a2429$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$daa113825fe729d92f67959089dec1d784e3470177e9db71939df973297a2429$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$daa113825fe729d92f67959089dec1d784e3470177e9db71939df973297a2429$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$daa113825fe729d92f67959089dec1d784e3470177e9db71939df973297a2429$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$daa113825fe729d92f67959089dec1d784e3470177e9db71939df973297a2429$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$e4577e2ecb6900811e07c2bcfa4546f9b98982df150e3d2cb56613fb06f1a6cb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$e4577e2ecb6900811e07c2bcfa4546f9b98982df150e3d2cb56613fb06f1a6cb$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$$InternalSyntheticLambda$2$e4577e2ecb6900811e07c2bcfa4546f9b98982df150e3d2cb56613fb06f1a6cb$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$14c50f89a2064f211425837664f49a4794ecc7a6b21a85d71d82c9dd306fc21d$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$18667933c396b86d28edf81d079407cda5fa1d065be099bacfb1fad9be8b24bb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$18667933c396b86d28edf81d079407cda5fa1d065be099bacfb1fad9be8b24bb$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$1ba3723aa627f5860a804727d8a654a1c9c6d789e65ce3c2f0af94e14ad3ee93$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$1ba3723aa627f5860a804727d8a654a1c9c6d789e65ce3c2f0af94e14ad3ee93$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$1ba3723aa627f5860a804727d8a654a1c9c6d789e65ce3c2f0af94e14ad3ee93$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$1d48cea0b659de9dadaa83c7f9efb8715af38d55c059a00d633f6eb1dd5d80ec$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$2275c2189bc6573e1cfc0cb2f7d4b1d79b93357141cdd5efacc2a5c0ca61eca7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$307708fddba2dcdb49a04223b265065577bf74f1c8e41f1a27af328ea69dc981$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$307708fddba2dcdb49a04223b265065577bf74f1c8e41f1a27af328ea69dc981$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$31b1f70621d378c87595d33323de71c961655a11801013faa2c8d547559b9c56$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$31b1f70621d378c87595d33323de71c961655a11801013faa2c8d547559b9c56$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$332f73dd189be33a846d82ba3acba23c4b7d34929b6617b51fa9583df31793a5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$332f73dd189be33a846d82ba3acba23c4b7d34929b6617b51fa9583df31793a5$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$332f73dd189be33a846d82ba3acba23c4b7d34929b6617b51fa9583df31793a5$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$36b90c7888b73a1182302af5d0f20c028f99b6e8496d42c1289455323e0b78b4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$4a392b0b900ab223f02f2c5ce393d7037b9687134961e4e9810adcca71ee5607$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$4a392b0b900ab223f02f2c5ce393d7037b9687134961e4e9810adcca71ee5607$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$4a392b0b900ab223f02f2c5ce393d7037b9687134961e4e9810adcca71ee5607$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$4a392b0b900ab223f02f2c5ce393d7037b9687134961e4e9810adcca71ee5607$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$4a392b0b900ab223f02f2c5ce393d7037b9687134961e4e9810adcca71ee5607$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$4ad142162619eb0e7714d5f4b7ff215047b0fad2e79bca072df2ebcbc9574132$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$540c36ab90411821a69ef2fb858f529c7b0a62d890930368020628737b02a2b8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$540c36ab90411821a69ef2fb858f529c7b0a62d890930368020628737b02a2b8$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$540c36ab90411821a69ef2fb858f529c7b0a62d890930368020628737b02a2b8$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5992fed95f38d1e21ec4f7b8dff1e2a101c9e651ca66ee3f7c2900ed7a42eabd$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5a19f96eb3a426b774bf540ec926671ff38e15483521bcf03e8a2ae144273b19$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5a19f96eb3a426b774bf540ec926671ff38e15483521bcf03e8a2ae144273b19$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5b51c6d7190ef2c24a3174ef65eb12cf25f99b3a6c72d862bb6a94ba5ea04b8b$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5ba036d7336eee3e1c1444508250c71967b2d7156498416417fdb3989d73d92e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5ba036d7336eee3e1c1444508250c71967b2d7156498416417fdb3989d73d92e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5c256c7316eef6291bb1851a03c9e50e03113640f35f4ce6ab1578a2536ae1ea$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$5c256c7316eef6291bb1851a03c9e50e03113640f35f4ce6ab1578a2536ae1ea$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$60e185942d01126164246a72922c93049376dd73248111f4533a65f98b6878b0$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$6905a0fbf1c4a4946081b2ece3a8b5e627ac997ce76735c1172c09a9f6afadd6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$6905a0fbf1c4a4946081b2ece3a8b5e627ac997ce76735c1172c09a9f6afadd6$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$6905a0fbf1c4a4946081b2ece3a8b5e627ac997ce76735c1172c09a9f6afadd6$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$6a3b174b8c26cc03b9514f9ea44db21f440954ddaa7263e2d1dc669943fd427f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$6c5c2cbbf01c254bc7430c5735cf66a795cbac45d833a308ca63bd988ca823eb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$6c5c2cbbf01c254bc7430c5735cf66a795cbac45d833a308ca63bd988ca823eb$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$6c5c2cbbf01c254bc7430c5735cf66a795cbac45d833a308ca63bd988ca823eb$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$790686646c745d6b23353dbf35c7ae4b153fa2dd69e57eb68c9becd14430c762$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$790686646c745d6b23353dbf35c7ae4b153fa2dd69e57eb68c9becd14430c762$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$790686646c745d6b23353dbf35c7ae4b153fa2dd69e57eb68c9becd14430c762$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$7b1b496022b2f8fc4f2bb07070d396c8625e01530f34ec196d88ffd5bad8680c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$7b1b496022b2f8fc4f2bb07070d396c8625e01530f34ec196d88ffd5bad8680c$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$7b1b496022b2f8fc4f2bb07070d396c8625e01530f34ec196d88ffd5bad8680c$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$7b1b496022b2f8fc4f2bb07070d396c8625e01530f34ec196d88ffd5bad8680c$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$7b1b496022b2f8fc4f2bb07070d396c8625e01530f34ec196d88ffd5bad8680c$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$810d3145f81304d78d66ebc143f1b25d4e0a79ce8cf76855cec325e6d94f0aa4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$810d3145f81304d78d66ebc143f1b25d4e0a79ce8cf76855cec325e6d94f0aa4$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$810d3145f81304d78d66ebc143f1b25d4e0a79ce8cf76855cec325e6d94f0aa4$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$85311ae0d62dd7bd45a0e139d8f6335b305d8c8b6734aaf191132a364a4851b1$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8555e18a768947f33d8137177986e637ab90e51efaa518706e91f00c364c794c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8ebbcefeb8a767b756f4959a06c713cd7815356dc86f409b732632d8b70f27f5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8ebbcefeb8a767b756f4959a06c713cd7815356dc86f409b732632d8b70f27f5$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8ebbcefeb8a767b756f4959a06c713cd7815356dc86f409b732632d8b70f27f5$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8ebbcefeb8a767b756f4959a06c713cd7815356dc86f409b732632d8b70f27f5$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8ebbcefeb8a767b756f4959a06c713cd7815356dc86f409b732632d8b70f27f5$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8ebbcefeb8a767b756f4959a06c713cd7815356dc86f409b732632d8b70f27f5$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8ebbcefeb8a767b756f4959a06c713cd7815356dc86f409b732632d8b70f27f5$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8ebbcefeb8a767b756f4959a06c713cd7815356dc86f409b732632d8b70f27f5$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$8ebbcefeb8a767b756f4959a06c713cd7815356dc86f409b732632d8b70f27f5$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$903ba0b66fd1b03c98abb385c247773b03203ffe9b3a7939750d6b60380bd19e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$903ba0b66fd1b03c98abb385c247773b03203ffe9b3a7939750d6b60380bd19e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$903ba0b66fd1b03c98abb385c247773b03203ffe9b3a7939750d6b60380bd19e$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$903ba0b66fd1b03c98abb385c247773b03203ffe9b3a7939750d6b60380bd19e$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$918be67ef35bf91e3c9d2c15a2fd5df2a19cc25bd5754a2a95fe1b78e3367f8f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$918be67ef35bf91e3c9d2c15a2fd5df2a19cc25bd5754a2a95fe1b78e3367f8f$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$918be67ef35bf91e3c9d2c15a2fd5df2a19cc25bd5754a2a95fe1b78e3367f8f$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1546e5d386a3a5cca1c93e3d0cfdd9b0422d462e742c0f84b5839aa08e5751c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b1e5267f12cf694db7f21500195a609fc6d902ccb820abedc0ed2b7ba851a350$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b59277972d98cf82013f34b930706af6e656e0aa2a3ca5372ba3cc9ce89695c1$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$b59277972d98cf82013f34b930706af6e656e0aa2a3ca5372ba3cc9ce89695c1$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$be053824617e0fd4a003629e8d79c585c932a45c7ab4a8a5788f64b6ef9521ea$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$be846ede5747094501181c3d24da675438a8231ccdf7946d6811b3065575ed8e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$be846ede5747094501181c3d24da675438a8231ccdf7946d6811b3065575ed8e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$be846ede5747094501181c3d24da675438a8231ccdf7946d6811b3065575ed8e$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$be846ede5747094501181c3d24da675438a8231ccdf7946d6811b3065575ed8e$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$c1ce4883dd3deb86c9012d3b761357ed7abd138d7810cdeee344131374abc546$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$c1ce4883dd3deb86c9012d3b761357ed7abd138d7810cdeee344131374abc546$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$c1ce4883dd3deb86c9012d3b761357ed7abd138d7810cdeee344131374abc546$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$c550ab2ea60a840c16bf4b558a117c0b6e365a6065f04634180602964136909b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$c718ec7fd793360e04281832dd71e210021f6a7703cb8e17dafa6b3e352aaa78$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d0d465145d3f50a444c787632aff520deb55b2abc171568101e1d10a9d82f775$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d0d465145d3f50a444c787632aff520deb55b2abc171568101e1d10a9d82f775$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d2e2193c66c149bd4a838daeaf4a1ae58444b926dd0c6354f1c0e1f82f0cdcef$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d2e2193c66c149bd4a838daeaf4a1ae58444b926dd0c6354f1c0e1f82f0cdcef$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d2e2193c66c149bd4a838daeaf4a1ae58444b926dd0c6354f1c0e1f82f0cdcef$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d2e2193c66c149bd4a838daeaf4a1ae58444b926dd0c6354f1c0e1f82f0cdcef$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d7debb1f953fd2aabfa69e41ab5a7aaae42fe1750f9c5c606908c28370e725fb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d7debb1f953fd2aabfa69e41ab5a7aaae42fe1750f9c5c606908c28370e725fb$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d7debb1f953fd2aabfa69e41ab5a7aaae42fe1750f9c5c606908c28370e725fb$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d7debb1f953fd2aabfa69e41ab5a7aaae42fe1750f9c5c606908c28370e725fb$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$d7debb1f953fd2aabfa69e41ab5a7aaae42fe1750f9c5c606908c28370e725fb$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$da2f7f0686612a33e00a25c9f300fab68ae55eccefbf9230394b560a4033695b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$da2f7f0686612a33e00a25c9f300fab68ae55eccefbf9230394b560a4033695b$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e2b074b9fae8286fc6b0452b6493c6a044ac5a0b5fab1f1da89c8904345a0563$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e3c7e2bcdbaa558b7e79bf0c2e38a164b5140d4c2850e69a89a7c9ad5cf1b117$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e47c082661d55509b24b74b5db17e6dcee11a93413ddfb85abb7a59016ffbbf4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e8431ad3d4d9531c83d8727a2ec0127adfd82190ecd664035986f27fb2e2e4d6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e8431ad3d4d9531c83d8727a2ec0127adfd82190ecd664035986f27fb2e2e4d6$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e8431ad3d4d9531c83d8727a2ec0127adfd82190ecd664035986f27fb2e2e4d6$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e8431ad3d4d9531c83d8727a2ec0127adfd82190ecd664035986f27fb2e2e4d6$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$e8431ad3d4d9531c83d8727a2ec0127adfd82190ecd664035986f27fb2e2e4d6$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$f19452011bd1ff644b492c2285d14fba3c265951222a3b976088dc90af1f6237$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$f19452011bd1ff644b492c2285d14fba3c265951222a3b976088dc90af1f6237$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$f6c07a537bf1ecc9c65ab0bff6808bb3fc6b039bb20c4e386dbc824f1eeb71b5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$f9af33541d0849af94d74f1564383f6f1c6ff823a0653d628c32e189c46f1e82$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$f9af33541d0849af94d74f1564383f6f1c6ff823a0653d628c32e189c46f1e82$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$fba9a702847803dcfa8c6fdd0c811db14b33fa65f040ab196a366696f553beb5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$fba9a702847803dcfa8c6fdd0c811db14b33fa65f040ab196a366696f553beb5$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$fba9a702847803dcfa8c6fdd0c811db14b33fa65f040ab196a366696f553beb5$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$fba9a702847803dcfa8c6fdd0c811db14b33fa65f040ab196a366696f553beb5$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$fd5b8c7d24d12d68f0ed22423409548a6e8cff11824b96d85ddde5700e271201$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$fd5b8c7d24d12d68f0ed22423409548a6e8cff11824b96d85ddde5700e271201$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$fd5b8c7d24d12d68f0ed22423409548a6e8cff11824b96d85ddde5700e271201$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$$InternalSyntheticLambda$2$fd5b8c7d24d12d68f0ed22423409548a6e8cff11824b96d85ddde5700e271201$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$2$1$2$1$1$$InternalSyntheticLambda$2$0edb9ac54d549a89f4f969d94bfd144de4575a662d29b579cc4b1808e5621bd2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$3$1$2$1$1$$InternalSyntheticLambda$2$1a80b091ad96a7ed431f2b162e73d83ed7153b5c36810f9e0a535014ba6facb2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$09f91b7e632a8b02faf33de0384f37c58ab57537d1072507969b761d97a7cc0c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$09f91b7e632a8b02faf33de0384f37c58ab57537d1072507969b761d97a7cc0c$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$0d694d5ef97d2ce25d17663e348258955687dcbe6d603f553b4bc7d80a7f0ae2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$0d694d5ef97d2ce25d17663e348258955687dcbe6d603f553b4bc7d80a7f0ae2$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$0d694d5ef97d2ce25d17663e348258955687dcbe6d603f553b4bc7d80a7f0ae2$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$122b1cf0d9bb7e9654800f78a169a49bee181f7a450080b9c73eebc1cdeaece7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$122b1cf0d9bb7e9654800f78a169a49bee181f7a450080b9c73eebc1cdeaece7$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$122b1cf0d9bb7e9654800f78a169a49bee181f7a450080b9c73eebc1cdeaece7$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$169b2b996146953a6c885b007a83feadb5f64d6a8c0e945ee8257ba54e18794a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$169b2b996146953a6c885b007a83feadb5f64d6a8c0e945ee8257ba54e18794a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$35c5b034fb74d56c411fb750df108f95b7a81b0771b4ed4d817ec069f6617c51$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$38a421a1c82e29bd30be594ece78b3c0f3a77b327e7050f086ab189eacf38229$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$3bca08686089df01e936b5035e3c5e379ead700939c7271e6fd8793fe8e81416$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$3bca08686089df01e936b5035e3c5e379ead700939c7271e6fd8793fe8e81416$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$3e1a1b036b1c149d98bd439a360fda7dfdb0b9c1c7ee5e1d9683c2fd5562e5c0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$8176112cd2235d65b90c01c4a6e9a8016503584bf74c22968596c394901cc398$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$f2193524fe6fa1bc95f6c868f05c4ce0448eafb6770a1b77fb368296654c3103$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$$InternalSyntheticLambda$2$f2193524fe6fa1bc95f6c868f05c4ce0448eafb6770a1b77fb368296654c3103$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupActivity$$InternalSyntheticLambda$2$07537e03da70df731ca68b42eb1cd2542dbead17e0b924b357f0f1a0a6e3daf8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupActivity$$InternalSyntheticLambda$2$07537e03da70df731ca68b42eb1cd2542dbead17e0b924b357f0f1a0a6e3daf8$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupActivity$$InternalSyntheticLambda$2$44d86d4ea8be47d49b0a33c7d36bda81eb32a78ae93833fcfe30c17c7616d872$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupActivity$$InternalSyntheticLambda$2$ed4d7ba8cf80964f812dd40f3636381888e04749be129c7acbdaa88a0019241d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$0ee8684fa30f4ac5ed456cff06089babd337f99481173e981dd12da327abcfa2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$1ea5e404caf2c52b256b1b8c5d05d9e7181912685ecadd31f5a63f26056ccef9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$22ee0d2ad8cf64dc5acf905242708dc4618b09f2cc1fd63ecc7cd05488f82590$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$294c627ef86fd7f81d827e65a3d872c09ba17550bb55cbf6e27836ce87f2c5c6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$294c627ef86fd7f81d827e65a3d872c09ba17550bb55cbf6e27836ce87f2c5c6$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$294c627ef86fd7f81d827e65a3d872c09ba17550bb55cbf6e27836ce87f2c5c6$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$294c627ef86fd7f81d827e65a3d872c09ba17550bb55cbf6e27836ce87f2c5c6$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$2f859c428eed5fbe34ca165acfb695c3c653dc2a89d683616c154ab364871196$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$3d4e9128759d22cb342507df788a6b9507595ec99d93f514bc854c8940ac0fb5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$3d4e9128759d22cb342507df788a6b9507595ec99d93f514bc854c8940ac0fb5$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$4acfc7ab7e8d83b305bbc63ca29f9543350c49bb1f30fcbfdc7e28ca6d37bb70$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$4acfc7ab7e8d83b305bbc63ca29f9543350c49bb1f30fcbfdc7e28ca6d37bb70$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$4acfc7ab7e8d83b305bbc63ca29f9543350c49bb1f30fcbfdc7e28ca6d37bb70$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$4acfc7ab7e8d83b305bbc63ca29f9543350c49bb1f30fcbfdc7e28ca6d37bb70$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$50c5de7a38029551a49e03e705fabb6bcca6fc8052ac376137184e95dcf92bad$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$52f667c792aa2509d0c5de39de05936478ab0c79c483fa42295e60d024ade4eb$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$5384866ece75e6a0106b821adcfc7a195f0c2793726960926031949790f530bc$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$562a7cb7a214bbd0dbe7234c888a299460c56e93fb625bf92d014176dcfdfb82$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$578e35a270e56b0f49909c8d47983ca849190b144ab32cda2a506b3a28b57374$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$5797f38113e43f92fe7fd5863b8712c38517fc0125d685bf0411c2cb0357900c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$5797f38113e43f92fe7fd5863b8712c38517fc0125d685bf0411c2cb0357900c$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$10.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$11.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$12.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$13.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$14.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$15.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$16.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$17.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$18.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$8.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$737f7caa49af6d052554a582ef60ea84a252c719d8cf1e602015f52ca71f5986$9.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$74d1f08a7b59454519403cbf7fc204940004db5f36c1afda90af1c4e8b695eb9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$74d1f08a7b59454519403cbf7fc204940004db5f36c1afda90af1c4e8b695eb9$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$74d1f08a7b59454519403cbf7fc204940004db5f36c1afda90af1c4e8b695eb9$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$74d1f08a7b59454519403cbf7fc204940004db5f36c1afda90af1c4e8b695eb9$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$75c6cca087571b5649d1ff372076ad5368e5566694bb26ecbf3544eb2aec8db8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$86059e29e44e5562fd67970b36ab769297d8b7365ce828f7aa762cd1d2e72e71$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$955eda4ceaa56b1b76f7a21f71e26baa569885c03801dcd8e480ca6307730056$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$98259e5a43b4989606fb85d3f232dda0ff305b3d345a20b2a38078c92a1cf9fc$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$b29de1b03e27df250f166acea4b09bff864993dc290e136bf9d28b0bba559aab$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$b6c7af1d12779b6a085e3a5eade7a2322863e56178045571d267f317252b5750$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$b6c7af1d12779b6a085e3a5eade7a2322863e56178045571d267f317252b5750$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$b6c7af1d12779b6a085e3a5eade7a2322863e56178045571d267f317252b5750$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$c35511a3dec52f36f196111767c5eecd0c29c167ac7d6a1339e89714c27f14d8$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$c35511a3dec52f36f196111767c5eecd0c29c167ac7d6a1339e89714c27f14d8$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$ca2b5020a93d3254bfab01a2c612b84dd3cf6bf9d12949d29673505b11c4553a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$ca2b5020a93d3254bfab01a2c612b84dd3cf6bf9d12949d29673505b11c4553a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$ca2b5020a93d3254bfab01a2c612b84dd3cf6bf9d12949d29673505b11c4553a$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$ca2b5020a93d3254bfab01a2c612b84dd3cf6bf9d12949d29673505b11c4553a$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$d5fd10df91de4c670a270365da6ffd3e6f615430ec6d5a3c4430ac23c9af1222$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$e55101e3956bdde41d52037ba7db45a8eaeda52bedb18e0c48dc091250e24240$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$e55101e3956bdde41d52037ba7db45a8eaeda52bedb18e0c48dc091250e24240$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$e55101e3956bdde41d52037ba7db45a8eaeda52bedb18e0c48dc091250e24240$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$e55101e3956bdde41d52037ba7db45a8eaeda52bedb18e0c48dc091250e24240$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$e5662faee3b947db22562874136149d2165ae23492f8d8cf4a3b1915739f5a28$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$e5662faee3b947db22562874136149d2165ae23492f8d8cf4a3b1915739f5a28$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$e5662faee3b947db22562874136149d2165ae23492f8d8cf4a3b1915739f5a28$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$e5662faee3b947db22562874136149d2165ae23492f8d8cf4a3b1915739f5a28$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$e5662faee3b947db22562874136149d2165ae23492f8d8cf4a3b1915739f5a28$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$f446b99c7eb1a9c2dd60afe5ecc2d4ae1ab66b7a3fd00758c88f0a6d7c24a65f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$f446b99c7eb1a9c2dd60afe5ecc2d4ae1ab66b7a3fd00758c88f0a6d7c24a65f$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$f446b99c7eb1a9c2dd60afe5ecc2d4ae1ab66b7a3fd00758c88f0a6d7c24a65f$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$f446b99c7eb1a9c2dd60afe5ecc2d4ae1ab66b7a3fd00758c88f0a6d7c24a65f$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$$InternalSyntheticLambda$2$f919c8719890b13f6c090e8b0e822f067907554f3b9d62909cf567e9475cb0d4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$3$1$1$$InternalSyntheticLambda$2$870e7aa56d70f121c481606be2b4731de1205bcdbc880d958bbeb001b4787936$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$3$1$1$$InternalSyntheticLambda$2$870e7aa56d70f121c481606be2b4731de1205bcdbc880d958bbeb001b4787936$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$01a27334afe93f536be8b1b6a2f1515d26c559f0a4d8e19762d694167c3aa867$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$12d5f8c2af812c59789a194da833e20f5795e688e2e43978fe431c7398fd5cb9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$12d5f8c2af812c59789a194da833e20f5795e688e2e43978fe431c7398fd5cb9$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$12d5f8c2af812c59789a194da833e20f5795e688e2e43978fe431c7398fd5cb9$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$12d5f8c2af812c59789a194da833e20f5795e688e2e43978fe431c7398fd5cb9$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$12d5f8c2af812c59789a194da833e20f5795e688e2e43978fe431c7398fd5cb9$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$32145d4edbaa77ee1bb86deb92031a06d5742f93f9b913e3261d8c1a9e987408$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$39fc0b4e2b979bbb9d4cbfe9a9de805ce3edf4737adbf261d2e2d8d723848663$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$492c3f93a633ad1f79ec4e5cd12b4139b2e741e9f9816728a960743f88c40b69$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$4b5febc9f190ee6c6b290070eadc11e363d2c32d1ade45475fede607fc18bd89$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$4b5febc9f190ee6c6b290070eadc11e363d2c32d1ade45475fede607fc18bd89$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$5019b77702a3305390f97dff2703b29efbaa82c72b54ac8c8fef488960cff54b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$5019b77702a3305390f97dff2703b29efbaa82c72b54ac8c8fef488960cff54b$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$5019b77702a3305390f97dff2703b29efbaa82c72b54ac8c8fef488960cff54b$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$5019b77702a3305390f97dff2703b29efbaa82c72b54ac8c8fef488960cff54b$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$5019b77702a3305390f97dff2703b29efbaa82c72b54ac8c8fef488960cff54b$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$6c379f694d269974e5b7e8a50327418fae60c071d9b09430061a6a82671fa1ae$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$7705868cebf81f5e2ea4ce9bcbc602d7a93541d965a6c568a62fbb3c61399de7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$7705868cebf81f5e2ea4ce9bcbc602d7a93541d965a6c568a62fbb3c61399de7$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$7bdca82f62103f477e8b495b57e5a13d0735bb6a3a648fbc906ca74a79a19764$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$7c22917d6fb3c70ef33e5e23548ef3f313fc335bfa44e7a00382f2b53fd879a3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$7c22917d6fb3c70ef33e5e23548ef3f313fc335bfa44e7a00382f2b53fd879a3$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$7c22917d6fb3c70ef33e5e23548ef3f313fc335bfa44e7a00382f2b53fd879a3$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$7c22917d6fb3c70ef33e5e23548ef3f313fc335bfa44e7a00382f2b53fd879a3$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$7c22917d6fb3c70ef33e5e23548ef3f313fc335bfa44e7a00382f2b53fd879a3$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$7c22917d6fb3c70ef33e5e23548ef3f313fc335bfa44e7a00382f2b53fd879a3$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$8bb6fd1bae784ed2ca76b0e3e6c4a93f054e920b18a70f08f9018487b5356c7a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$8bb6fd1bae784ed2ca76b0e3e6c4a93f054e920b18a70f08f9018487b5356c7a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$8bb6fd1bae784ed2ca76b0e3e6c4a93f054e920b18a70f08f9018487b5356c7a$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$8bb6fd1bae784ed2ca76b0e3e6c4a93f054e920b18a70f08f9018487b5356c7a$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$8bb6fd1bae784ed2ca76b0e3e6c4a93f054e920b18a70f08f9018487b5356c7a$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$ab5aa198af3974aaf4a3bf2bfaafdce52593b6166bd640d1a6e3a836de43cb75$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$d753b5184a5c77a874ec6ca0a7893eaaa478af473ce722d56f211300db81b01f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$da1ce24c59074768c752e50f9d49cf1a8d7c399552b60d37bf362ad49ab98d91$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$da3ffdefe4615d6dda463df5a74f9638874392f389064dbbd66666e5ae6c3366$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$e4a7cef03ef523e92e8fece50b02746b8dd5da7af66650ae86b3fa867cdae487$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$efa2fba3a3192008cf2e9f68fecc6fb99da2d343f51e39b489e2419700835712$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$efa2fba3a3192008cf2e9f68fecc6fb99da2d343f51e39b489e2419700835712$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$efa2fba3a3192008cf2e9f68fecc6fb99da2d343f51e39b489e2419700835712$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$efa2fba3a3192008cf2e9f68fecc6fb99da2d343f51e39b489e2419700835712$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$$InternalSyntheticLambda$2$efa2fba3a3192008cf2e9f68fecc6fb99da2d343f51e39b489e2419700835712$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$0aba377119b25d8c28bc4f230dfd48cb9f37be8b8818bce7b158e0693eac9dcf$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$0aba377119b25d8c28bc4f230dfd48cb9f37be8b8818bce7b158e0693eac9dcf$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$0aba377119b25d8c28bc4f230dfd48cb9f37be8b8818bce7b158e0693eac9dcf$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$0e172f9cade695a9600979c6b1614dabcf8bd995d9a992b230711d33270e04b9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$16c0d61feee77baa297f5c3a9e9223b6d0f5556573ba437f9bfa52abae362cff$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$16c0d61feee77baa297f5c3a9e9223b6d0f5556573ba437f9bfa52abae362cff$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$16c0d61feee77baa297f5c3a9e9223b6d0f5556573ba437f9bfa52abae362cff$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$16c0d61feee77baa297f5c3a9e9223b6d0f5556573ba437f9bfa52abae362cff$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$16c0d61feee77baa297f5c3a9e9223b6d0f5556573ba437f9bfa52abae362cff$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$16c0d61feee77baa297f5c3a9e9223b6d0f5556573ba437f9bfa52abae362cff$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$16c0d61feee77baa297f5c3a9e9223b6d0f5556573ba437f9bfa52abae362cff$6.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$16c0d61feee77baa297f5c3a9e9223b6d0f5556573ba437f9bfa52abae362cff$7.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$1d587997dff8512c36a6f97ebed724c58775360154d536073236e501fb87edbf$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$1d587997dff8512c36a6f97ebed724c58775360154d536073236e501fb87edbf$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$1d587997dff8512c36a6f97ebed724c58775360154d536073236e501fb87edbf$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$1d587997dff8512c36a6f97ebed724c58775360154d536073236e501fb87edbf$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$2a710d8511181a2dae5c37ba1e54c9b548a129df60cb15cad7179f9946fbfd9a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$2a710d8511181a2dae5c37ba1e54c9b548a129df60cb15cad7179f9946fbfd9a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$2a710d8511181a2dae5c37ba1e54c9b548a129df60cb15cad7179f9946fbfd9a$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$3307cb9ecad1d119ac8b06bc30f20a7e95dc1d2fdfe4e58811b4c49ebfedaa12$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$3307cb9ecad1d119ac8b06bc30f20a7e95dc1d2fdfe4e58811b4c49ebfedaa12$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$3307cb9ecad1d119ac8b06bc30f20a7e95dc1d2fdfe4e58811b4c49ebfedaa12$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$3307cb9ecad1d119ac8b06bc30f20a7e95dc1d2fdfe4e58811b4c49ebfedaa12$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$40a3dbad1193ab4711cd9cb7a4dc050baaba81811a2e80caa8095790dbf99cd4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$43b23c3eb4e1b7b06a6c37fb5f3982ac56d87358bad173489ccc34863b91dbd0$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$43b23c3eb4e1b7b06a6c37fb5f3982ac56d87358bad173489ccc34863b91dbd0$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$43b23c3eb4e1b7b06a6c37fb5f3982ac56d87358bad173489ccc34863b91dbd0$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$43b23c3eb4e1b7b06a6c37fb5f3982ac56d87358bad173489ccc34863b91dbd0$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$43b23c3eb4e1b7b06a6c37fb5f3982ac56d87358bad173489ccc34863b91dbd0$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$43b23c3eb4e1b7b06a6c37fb5f3982ac56d87358bad173489ccc34863b91dbd0$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$47196f9d56922fc9865f44a07cd6151b7dd48e2cef01a373804740b9eb5f5b2d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$47196f9d56922fc9865f44a07cd6151b7dd48e2cef01a373804740b9eb5f5b2d$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$47196f9d56922fc9865f44a07cd6151b7dd48e2cef01a373804740b9eb5f5b2d$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$47196f9d56922fc9865f44a07cd6151b7dd48e2cef01a373804740b9eb5f5b2d$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$47196f9d56922fc9865f44a07cd6151b7dd48e2cef01a373804740b9eb5f5b2d$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$47196f9d56922fc9865f44a07cd6151b7dd48e2cef01a373804740b9eb5f5b2d$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$48da9488798fd3f7de0a1821bebce55e03cffe75a81ef0022db7652449d8aa49$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$48da9488798fd3f7de0a1821bebce55e03cffe75a81ef0022db7652449d8aa49$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$48da9488798fd3f7de0a1821bebce55e03cffe75a81ef0022db7652449d8aa49$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$48da9488798fd3f7de0a1821bebce55e03cffe75a81ef0022db7652449d8aa49$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$48da9488798fd3f7de0a1821bebce55e03cffe75a81ef0022db7652449d8aa49$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$5ffb86b01c1544ee7a31d281176be522dd987d4b2ec50d1e58a9e76ab54ba4de$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$6898140557fa97e4017f9b9fab45bbe0951882392726d25825c1753acf37b80d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$6898140557fa97e4017f9b9fab45bbe0951882392726d25825c1753acf37b80d$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$6898140557fa97e4017f9b9fab45bbe0951882392726d25825c1753acf37b80d$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$68d5edf174a6317a48ec76442bf6c71a9072cb9a4fd6bb07ee07c95b392354fa$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$68d5edf174a6317a48ec76442bf6c71a9072cb9a4fd6bb07ee07c95b392354fa$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$6a8f4706d4acab2deb1521b805d7415d8b4ac25c9181a79ef174ad6e5764f2bc$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$6a8f4706d4acab2deb1521b805d7415d8b4ac25c9181a79ef174ad6e5764f2bc$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$6a8f4706d4acab2deb1521b805d7415d8b4ac25c9181a79ef174ad6e5764f2bc$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$6bdd078f21a5ad357217d60a000aa6a49400c7d1df04fd5aea71c1144cdbbdbf$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$7b5f4932adfd91ffada77bbd489d0fcb8fafcf8276b91c3ecf3963cae9a0d073$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$90ee865aef165822093e2e9889e25398e7f2a8d1b113a3cad4bd2297d269753c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$92791cc669610f0bb827acabe2fdc179740d45bcfadf814c2b5e483aa7ba6f20$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$96e31e5441a3ac9383d606af422eb9f32052fd7b9859e061f166bde630bcdf31$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$99ba274b9b13b69f66ff3554f93322eb53f7dada15f5cbaefea4eb48a528e1ae$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$99ba274b9b13b69f66ff3554f93322eb53f7dada15f5cbaefea4eb48a528e1ae$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$99ba274b9b13b69f66ff3554f93322eb53f7dada15f5cbaefea4eb48a528e1ae$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$99ba274b9b13b69f66ff3554f93322eb53f7dada15f5cbaefea4eb48a528e1ae$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$a4b608ceb6bab7ad33acdbd3b89541d250812dd768b43c5bb3aebe965688feb6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$a4b608ceb6bab7ad33acdbd3b89541d250812dd768b43c5bb3aebe965688feb6$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$a4b608ceb6bab7ad33acdbd3b89541d250812dd768b43c5bb3aebe965688feb6$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$d1baa607ca12107ff8be48854c1e6ed76c03a0b6ee50e19683709f6da49a1c46$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$d1baa607ca12107ff8be48854c1e6ed76c03a0b6ee50e19683709f6da49a1c46$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$d1baa607ca12107ff8be48854c1e6ed76c03a0b6ee50e19683709f6da49a1c46$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$d4ff52bffd610190b0051ed22c3ceba6f0fbd59f3627f0f421418d113d126257$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$d4ff52bffd610190b0051ed22c3ceba6f0fbd59f3627f0f421418d113d126257$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$d4ff52bffd610190b0051ed22c3ceba6f0fbd59f3627f0f421418d113d126257$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$d4ff52bffd610190b0051ed22c3ceba6f0fbd59f3627f0f421418d113d126257$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$da40152eecc16f645ccf7a6dda4b20b69aa589e0d4379c3afdccbc6f58a91992$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$e8b3b7dc3817878ac74a598e278b48f74cf7c541ff8fd270d6ad6c10d18647d3$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$f2dedff0c619c033f1dcb2275bb921481869d045062b17045c6c7f6ac3d2fc64$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$$InternalSyntheticLambda$2$f2dedff0c619c033f1dcb2275bb921481869d045062b17045c6c7f6ac3d2fc64$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ThemeKt$$InternalSyntheticLambda$2$a31b21788b5417e1edd3d886b40f5b33d7be1cad0ca80b31d94f857aab3acc84$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$0a391ec5131fc34818367a8d717d52d4c3f0b6587e296a623e6e284e2a48c323$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$0c7aad1ffbf5c2f070a748bb3de18022c650d0f804faeca09e0842383264d58e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$0c7aad1ffbf5c2f070a748bb3de18022c650d0f804faeca09e0842383264d58e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$242ef2c0f238704344b73b3b65e76127898541dd46b8dbb96d763f2dc5b7b905$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$3968198790a43921479832e50370446180cf75b17b2a6c4b7df3078959388c7a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$3968198790a43921479832e50370446180cf75b17b2a6c4b7df3078959388c7a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$3968198790a43921479832e50370446180cf75b17b2a6c4b7df3078959388c7a$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$3968198790a43921479832e50370446180cf75b17b2a6c4b7df3078959388c7a$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$3968198790a43921479832e50370446180cf75b17b2a6c4b7df3078959388c7a$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$3968198790a43921479832e50370446180cf75b17b2a6c4b7df3078959388c7a$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$3d7496964215bf1e03cf3ef573143a7b32ddbd151635401d04fcfd03975a4f10$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$3d7496964215bf1e03cf3ef573143a7b32ddbd151635401d04fcfd03975a4f10$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$4c6bc2c92bb2f00fb30985abeb92ae2bcee0443570d9982b0bf2d052720502fd$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$523c434d4d71ace81aa967a79e1512e71992b6d162736b1fae8d4681a06148a6$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$523c434d4d71ace81aa967a79e1512e71992b6d162736b1fae8d4681a06148a6$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$523c434d4d71ace81aa967a79e1512e71992b6d162736b1fae8d4681a06148a6$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$523c434d4d71ace81aa967a79e1512e71992b6d162736b1fae8d4681a06148a6$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$523c434d4d71ace81aa967a79e1512e71992b6d162736b1fae8d4681a06148a6$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$523c434d4d71ace81aa967a79e1512e71992b6d162736b1fae8d4681a06148a6$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$587d2bfdd52d0abb164e1b470cfd624a313058b5b269ff369c608f70ed54af63$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$587d2bfdd52d0abb164e1b470cfd624a313058b5b269ff369c608f70ed54af63$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$5ce1189859d2fafab52acab7219619681d774e01b825612b896eec51eb648712$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$5d3d0f2e3f84d9fac2d5a9623c73a1a0f0a56bafdfb2a0c5fa47c45ef5868fe4$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$5f3bb7787a946e8528310978d5a8ad8d089b689e4d1fed5827c7d74bcc019360$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$6b1179fae826ec2023edf208b6d048a4144a4fac900dd9998c6d00ebff1c3b3e$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$6b1179fae826ec2023edf208b6d048a4144a4fac900dd9998c6d00ebff1c3b3e$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$6b1179fae826ec2023edf208b6d048a4144a4fac900dd9998c6d00ebff1c3b3e$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$6b1179fae826ec2023edf208b6d048a4144a4fac900dd9998c6d00ebff1c3b3e$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$6e10d04ca663fe91744ce966996f97f901a58eafcc0bf1debb0670eaf78c7f63$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$76a1644d096fe3c907371ee73f7b18d85680279a5d93df600dcac3981619d7a9$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$7d37272780a5d3fbc973e899cd2950819e5e9418bf9028f0dbe85de6b70778ad$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$7d37272780a5d3fbc973e899cd2950819e5e9418bf9028f0dbe85de6b70778ad$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$7d37272780a5d3fbc973e899cd2950819e5e9418bf9028f0dbe85de6b70778ad$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$7d37272780a5d3fbc973e899cd2950819e5e9418bf9028f0dbe85de6b70778ad$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$7d37272780a5d3fbc973e899cd2950819e5e9418bf9028f0dbe85de6b70778ad$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$7d37272780a5d3fbc973e899cd2950819e5e9418bf9028f0dbe85de6b70778ad$5.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$817bf11069809379e6299f56bb1932fa3097923472fab9206389c29a712f52ce$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$bd99249d82912fe84b071bf3804ef3b7de796ca40c81a51443139b9c02e403ad$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$bdfb1cd69e44391e97195a696a2cde9eca93bddbd20d263085d1cd853c22b90c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$bdfb1cd69e44391e97195a696a2cde9eca93bddbd20d263085d1cd853c22b90c$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$c3a50714b36593ee7dfb03545d579ccce7229e5ec36dbfbb412ee8bd3c4cfb1c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$d2ffa2a151f77b8e59c024dcec6ed29665535260f6c49347375bc87bb4f7a3a2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$d5e626a8e4099b9608786c312778662bc7cbe30968cd9dc97073f2c7fbd2c36d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$dc256c2117cad8cf409dda11a6dd6f3117b6b33edf515460ea5778a5cd1efb0a$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$dc256c2117cad8cf409dda11a6dd6f3117b6b33edf515460ea5778a5cd1efb0a$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$dc256c2117cad8cf409dda11a6dd6f3117b6b33edf515460ea5778a5cd1efb0a$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$dc256c2117cad8cf409dda11a6dd6f3117b6b33edf515460ea5778a5cd1efb0a$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$dc256c2117cad8cf409dda11a6dd6f3117b6b33edf515460ea5778a5cd1efb0a$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$ddb69e00a7bcb3e3f43d2b8478b55b6a0119e56b39b06b61d83af4fcc771684d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$ddb69e00a7bcb3e3f43d2b8478b55b6a0119e56b39b06b61d83af4fcc771684d$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$e8888edb27414c2c207137f712213c23d6c4594cf96a55df90e5655dd48adbd7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$edfd9af992b579657fc3e41ec228e27e784fefb26f09c9afcdc37d059165879d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$f39009607ab1713319edb132d9c0a408f41a7538a486e49ac1f84b59ecf30468$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$f39009607ab1713319edb132d9c0a408f41a7538a486e49ac1f84b59ecf30468$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$f39009607ab1713319edb132d9c0a408f41a7538a486e49ac1f84b59ecf30468$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$f39009607ab1713319edb132d9c0a408f41a7538a486e49ac1f84b59ecf30468$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$f39009607ab1713319edb132d9c0a408f41a7538a486e49ac1f84b59ecf30468$4.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$$InternalSyntheticLambda$2$f424cc99c6ec1f4a28efb481443cf463a334189f3390d9f77fec4b342e45c998$0.globals
[D] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/ComposableSingletons$NextWidgetKt$$InternalSyntheticLambda$2$10775c5c89017ba11b83b5cf18d6df4128c2283da052cc7800c2ee778fcb4103$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$14d636add042e42658b078fef07172ef35760f37a6c5470682c037ce8bb5c13d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$14d636add042e42658b078fef07172ef35760f37a6c5470682c037ce8bb5c13d$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$151b8afb340c0a5380b579ee34e971ddcd74410999ff738f6fc68b10b480a68c$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$40a180dafc2056deabf6576fa78fdc6890566e24e814a512c7d3b839ff3b665d$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$5182c1ae9e63e1b415026102f8554804afe01f52c825c7db9cce19c104f4e535$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$5182c1ae9e63e1b415026102f8554804afe01f52c825c7db9cce19c104f4e535$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$5235b5cff9d4a3e194580753961e7e7eab9dd38a594e77b4ed5c488e02486c9b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$5235b5cff9d4a3e194580753961e7e7eab9dd38a594e77b4ed5c488e02486c9b$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$535024bcb4432071a4510b22acbd4f96e3c689f17da98922f25fc325b75f2830$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$5e1b238bd7c4343dc3da45a493be569b2819bca2addd5b0e52703899e5a6ef36$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$606922574c0183f597bc411d20b7859f2beb824a68c2e054afc76aecae2a98a7$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$606922574c0183f597bc411d20b7859f2beb824a68c2e054afc76aecae2a98a7$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$606922574c0183f597bc411d20b7859f2beb824a68c2e054afc76aecae2a98a7$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$67600195fd7767cd09c484dbf72ca4d384de0d0e6ebe6edfd5e019d1a6cd9225$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$7235679632cb026e9bcb1936305fe1c651ae845a8c5a8c0d12768371dbf584c5$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$7235679632cb026e9bcb1936305fe1c651ae845a8c5a8c0d12768371dbf584c5$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$7235679632cb026e9bcb1936305fe1c651ae845a8c5a8c0d12768371dbf584c5$2.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$7235679632cb026e9bcb1936305fe1c651ae845a8c5a8c0d12768371dbf584c5$3.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$9db64053baa057fddc005cf89c3338d678d1ae3049bd43699155c6693f0e1a50$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$af97a8b455914415e217878cf342cf427025370759cff8701782d9a0b6c16a0b$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$cbe85a332a95488d1e415b94bc88bbcb1113a1797400e22e3d4742b792b12cb2$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$cbe85a332a95488d1e415b94bc88bbcb1113a1797400e22e3d4742b792b12cb2$1.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$d4cded76616a5ccf2107377ae3a5ae1147e0b3b3330d000374246c57e0aa1278$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$e32b3546b7fec7af3cf24653079b6642d9f723d4c31d96c52f03f3d13a3ed89f$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$e764488e55c071182ec9522de58f3c5a638f3adaacd5744f9b67918865a18d95$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$f1c5862e3298ddf0db96fd131913cf48c8b50b59b43b89d7a25e6ccadcac8da1$0.globals
[F] app/build/intermediates/global_synthetics_project/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$$InternalSyntheticLambda$2$f1c5862e3298ddf0db96fd131913cf48c8b50b59b43b89d7a25e6ccadcac8da1$1.globals
[D] app/build/intermediates/global_synthetics_subproject
[D] app/build/intermediates/global_synthetics_subproject/debug
[D] app/build/intermediates/global_synthetics_subproject/debug/dexBuilderDebug
[D] app/build/intermediates/global_synthetics_subproject/debug/dexBuilderDebug/out
[D] app/build/intermediates/incremental
[D] app/build/intermediates/incremental/debug
[D] app/build/intermediates/incremental/debug-mergeJavaRes
[F] app/build/intermediates/incremental/debug-mergeJavaRes/merge-state
[D] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/+AJWBJf43cwlkptlH+V2Yw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/1gVniYccT_GIDxLcG0a2BQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/1mSkMUL0a1HBsSRkH9cuUA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/2cTnThD9DMqPRchvRlKiLw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/2PZVA7MjWPJeiROioPIVBw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/2uw5vqSoZWlF5Hkq5PzTYg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/2WrfXY55AOFTfiUPcFptxA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/3byG0EcqStnpwc_Tw26L2w==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/4a7JUC3PZobyTjwk_TVwow==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/4cTH0RTFD1WUdVPcxfW65Q==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/4JpSuAPvA2rOCHSeh4SW2A==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/4KFKNNCbOHB+s4MfJUMSiw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/51ddLY_f7EvebOWAMTD_ew==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/54g9F+FXVDHhli5eiTII+Q==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/6AKNIfEegxn5DXCCae8Nfg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/6vK5Z3Cq_76rxv_2EcwI5w==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/7ohlg6PFdi5z+6_xYCcsfA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/7WrQbUmeBS48V5+56KkBzg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/8362V+D3ECeGl_5enSfV+w==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/8FYRSfL2HZ_fzbTW035qLQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/8qdWYPBNFde_eftRz_srOA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/_fF_BX3ZljP3SNfQWfcnoQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/_voaNp+gGTHk_2LF4577ig==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/A+2ufsG6qg1ptFuPAqtIvQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/aaIQ3ay5CI4XnFc3HjSHiw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/AHYoZZ84MdaZll0gSKmdIA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Aljfas5B9hWFvgKVexkLaw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/AM5yNXKwrg54cxDhPWPqqg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/b+99HAt5a9fuxZi58E7y1A==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/BnIp_AdbINkRCQz6SVl8rw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/BsnIr2lsZPfvzqArKkUBLA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/bsoJvrDTu+EN8+dfaKi6jA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/bzM7Y_1EpmlITnCwvNbAdA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/CPJDFoKJ_Oc4S3rbW1Vehw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/DMxBszQZJHQe2YnuPWuYUQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/DrMSQeZfqIlWuT48w82kRw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/DvxFFFkhGu04ll2XDh5H7Q==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/eRNqX+ld+2MHumm0kyITow==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/FBWw3_3FW76EIQ6CKrMH_Q==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/FkNnnmVxHzKHgA0bCNJMvQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/fyebE2p5xcvsx06wjjBKVQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/gFh4TEuzWIEOt2tZM+R9VQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/GIwpLNVWjN7zi8tGG+Wa0g==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/gpLdjOjKSV39GFZuPBeGRQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/gqTgUktV36KQO07tCVGwVg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/GRQKxr_GDLxXbex6xVjruw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/GwLn7tNutJfAguoiidn7tg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/GyYE5SGr7b8pqJRvB4oarw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/h+sJIZy864d1owX1iSICcw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Hd+KZOOJ_RpfRR1LUvbMdA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/igrNsrYlwMhRbDAUQIz7JQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/ioMBBwonOG5QqbQUzhaugQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/jk4ZmtLyjiTHCpYBbljtaw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/JSQ1CZiUqMGCa1FLKMGfFA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/KdE+GujbuLPDq2OoD+VoeQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/kGHiN0NV18J4scoGPfzqNw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/kkr3rxi8ZMi2aWKCvsRFiQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/kmlR9EWHQJVQLMjzNrTzQQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Ku5aW7cthp7gA0JMe9AE4Q==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/LI0XY44Px15BBHqPLCYfCg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/luswXacBzl6gvEk1buAsYQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/m1NfEhO1aFA64iTtWcnBUA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/M4LbEgC58DnL_nxyHwiTSQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/M9Fn3Yu8or7v4B8L2fluYQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/mQbDWGRkVwqrh9Xsa4E4Ww==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/nrc68GF0uuUVLlMG6TpqRQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/o3xotc1a7JJzpx0D83AiTg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/oBB4fmZdv7WUaPA4hyZ8NQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/ohATiO9YzPk+F8i+wp2ahw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/oLPN2+KYThViQK36AlGMoA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/oY13QAgiAVt_e87BLhokKg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Oz9so9Ki+iJ0l83UdWYHdQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/p1SzKUkhGn6xzIpcywaNPg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/pdgLezvbaXgi9kCLbcO55Q==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Ph+GXbbNgJqPuXjSX4L9kw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Pj90wo34_1HReJ+zzNRQ3Q==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/pM4cuEEeeaJvAxVX+eMfBA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Po1bTlX5QXB4FJKAasBVtg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/prt2LigJUieLujm8ocE39Q==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/qAnmo8y2LNcvOxeqxCfyHQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/QrBp5y75bQZd4lZjJT26Sw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/qTQvwfpoy46Sh18THdKCtA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/QwbwoYJNIZpqR0FmaXE8Sg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/QY2kAVVGxWiFLQaNRU5IGA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/R+8MTagI3V3S51f7YxZFDQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/r13Z+UInneYjz+TX0hZMyA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/rDR9R5at7ND153olajhdoA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/rfR2pF+YGtmB7l1N9vKwjA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/RnkQe2+DXrPHwjeYZRUgrA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/RS76Kff1RAOG2fIIJ2RKFg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/SiB2cD43mtcgV_dYhOXeoA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/t4QE57P0uFKAb0d7qoMx4w==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/TAlxJYrZJBeV_PTjVNg2rw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/tOOrX4rF+dKBSV9rfyfTPw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Tt_biZ3n9H1dbuGrgs7uTA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/TuPL_9X+PfbulaJgPqPS7Q==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/UrgYm2w7kOeqYkLRFQLjUQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/UT3svxDm3CPZLtKS+8Gy5w==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Uw5k2mno2UYHiNlWco88kw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/UWdbPlFudLwtotCWKiWlXQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/UX7TOKXkDGyhhZDym+tdCQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/V3kdd4YmWMfgA7V+Wb7Cvg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/vBTkJ49ODGx4QyyAlp+wzg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/VEfyPc7iu5Gqm1GMbE9_hg==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/VmfgmD2FQN2izuJ1bw8CdA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/vrt1I0_3xAyf7dwNPRAu4w==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/vvATwJ6qhiOoABnkTOT1YA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/vWLg6IXuUmPZrADqs0fnEQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/WADCpKKiWtrgq39fIk9IVQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Wb3VjRUisMGE6C0kW+gRpA==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/wkJdDbPEuv2uEEoE4eJxpQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/WM5B0zRrim7l8edMM2IidQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/WPssxEU2xRmOPtWJlx0uQw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Xv+EBcjold2Ewaz30cUV4A==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/y7bKLdqDyUQyEKT8ZqSxOQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Yfh6EksYDpUuYYa4FPGEyw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/ykJH1KsbA6vyZnRqPwR34g==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Z13LZx+HFJuzZ9dt8ID8Xw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/Zcf_vReszpHGnleeEtXUjQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/zfDY8VyPr4rfI0JriVG_PQ==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/ZwcgeC23NklGresVUdfaYw==
[F] app/build/intermediates/incremental/debug-mergeJavaRes/zip-cache/zZnV4n5GGvmFFV0E_+CJJA==
[D] app/build/intermediates/incremental/debug/mergeDebugResources
[F] app/build/intermediates/incremental/debug/mergeDebugResources/compile-file-map.properties
[D] app/build/intermediates/incremental/debug/mergeDebugResources/merged.dir
[F] app/build/intermediates/incremental/debug/mergeDebugResources/merger.xml
[D] app/build/intermediates/incremental/debug/mergeDebugResources/stripped.dir
[D] app/build/intermediates/incremental/debug/packageDebugResources
[F] app/build/intermediates/incremental/debug/packageDebugResources/compile-file-map.properties
[D] app/build/intermediates/incremental/debug/packageDebugResources/merged.dir
[F] app/build/intermediates/incremental/debug/packageDebugResources/merger.xml
[D] app/build/intermediates/incremental/debug/packageDebugResources/stripped.dir
[D] app/build/intermediates/incremental/lintAnalyzeDebug
[F] app/build/intermediates/incremental/lintAnalyzeDebug/debug-artifact-dependencies.xml
[F] app/build/intermediates/incremental/lintAnalyzeDebug/debug-artifact-libraries.xml
[F] app/build/intermediates/incremental/lintAnalyzeDebug/debug.xml
[F] app/build/intermediates/incremental/lintAnalyzeDebug/module.xml
[D] app/build/intermediates/incremental/lintAnalyzeDebugAndroidTest
[F] app/build/intermediates/incremental/lintAnalyzeDebugAndroidTest/debug-artifact-dependencies.xml
[F] app/build/intermediates/incremental/lintAnalyzeDebugAndroidTest/debug-artifact-libraries.xml
[F] app/build/intermediates/incremental/lintAnalyzeDebugAndroidTest/debug.xml
[F] app/build/intermediates/incremental/lintAnalyzeDebugAndroidTest/module.xml
[D] app/build/intermediates/incremental/lintAnalyzeDebugUnitTest
[F] app/build/intermediates/incremental/lintAnalyzeDebugUnitTest/debug-artifact-dependencies.xml
[F] app/build/intermediates/incremental/lintAnalyzeDebugUnitTest/debug-artifact-libraries.xml
[F] app/build/intermediates/incremental/lintAnalyzeDebugUnitTest/debug.xml
[F] app/build/intermediates/incremental/lintAnalyzeDebugUnitTest/module.xml
[D] app/build/intermediates/incremental/mergeDebugAssets
[F] app/build/intermediates/incremental/mergeDebugAssets/merger.xml
[D] app/build/intermediates/incremental/mergeDebugJniLibFolders
[F] app/build/intermediates/incremental/mergeDebugJniLibFolders/merger.xml
[D] app/build/intermediates/incremental/packageDebug
[D] app/build/intermediates/incremental/packageDebug/tmp
[D] app/build/intermediates/incremental/packageDebug/tmp/debug
[F] app/build/intermediates/incremental/packageDebug/tmp/debug/dex-renamer-state.txt
[D] app/build/intermediates/incremental/packageDebug/tmp/debug/zip-cache
[F] app/build/intermediates/incremental/packageDebug/tmp/debug/zip-cache/androidResources
[F] app/build/intermediates/incremental/packageDebug/tmp/debug/zip-cache/javaResources0
[D] app/build/intermediates/java_res
[D] app/build/intermediates/java_res/debug
[D] app/build/intermediates/java_res/debug/processDebugJavaRes
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/block
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/core
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/data
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/debug
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/enrich
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/learn
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/net
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/notify
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/protect
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/sources
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/sources/anki
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/sources/gmail
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/sources/powerplanner
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/sources/teams
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/sync
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/ui
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/com/thomaswcode/decrastination/widget
[D] app/build/intermediates/java_res/debug/processDebugJavaRes/out/META-INF
[F] app/build/intermediates/java_res/debug/processDebugJavaRes/out/META-INF/app.kotlin_module
[D] app/build/intermediates/java_res/debugUnitTest
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/block
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/core
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/data
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/enrich
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/learn
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/protect
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/sources
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/sources/anki
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/sources/gmail
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/sources/powerplanner
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/sources/teams
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/sync
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/ui
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/com/thomaswcode/decrastination/widget
[D] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/META-INF
[F] app/build/intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out/META-INF/app.kotlin_module
[D] app/build/intermediates/javac
[D] app/build/intermediates/javac/debug
[D] app/build/intermediates/javac/debug/compileDebugJavaWithJavac
[D] app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
[D] app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/com
[D] app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/com/thomaswcode
[D] app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/com/thomaswcode/decrastination
[F] app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/com/thomaswcode/decrastination/BuildConfig.class
[D] app/build/intermediates/linked_resources_binary_format
[D] app/build/intermediates/linked_resources_binary_format/debug
[D] app/build/intermediates/linked_resources_binary_format/debug/processDebugResources
[F] app/build/intermediates/linked_resources_binary_format/debug/processDebugResources/linked-resources-binary-format-debug.ap_
[F] app/build/intermediates/linked_resources_binary_format/debug/processDebugResources/output-metadata.json
[D] app/build/intermediates/lint-cache
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/api-versions-50-36.0.bin
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/lint-cache-version.txt
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven-versions
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven-versions/repo.gradle.org
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven-versions/repo.gradle.org/maven-metadata.xml
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/activity
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/activity/group-index.xml
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/compose
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/compose/group-index.xml
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/core
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/core/group-index.xml
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/glance
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/glance/group-index.xml
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/lifecycle
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/lifecycle/group-index.xml
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/work
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/androidx/work/group-index.xml
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/com
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/com/android
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/com/android/application
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/com/android/application/group-index.xml
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/master-index.xml
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/org
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/org/jetbrains
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/org/jetbrains/kotlin
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/maven.google/org/jetbrains/kotlin/group-index.xml
[D] app/build/intermediates/lint-cache/lintAnalyzeDebug/sdk_index
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/sdk_index/snapshot.gz
[F] app/build/intermediates/lint-cache/lintAnalyzeDebug/typos-en.txt-2.bin
[D] app/build/intermediates/lint-cache/lintAnalyzeDebugAndroidTest
[F] app/build/intermediates/lint-cache/lintAnalyzeDebugAndroidTest/api-versions-50-36.0.bin
[F] app/build/intermediates/lint-cache/lintAnalyzeDebugAndroidTest/lint-cache-version.txt
[D] app/build/intermediates/lint-cache/lintAnalyzeDebugUnitTest
[F] app/build/intermediates/lint-cache/lintAnalyzeDebugUnitTest/api-versions-50-36.0.bin
[F] app/build/intermediates/lint-cache/lintAnalyzeDebugUnitTest/lint-cache-version.txt
[D] app/build/intermediates/lint-cache/lintReportDebug
[F] app/build/intermediates/lint-cache/lintReportDebug/lint-cache-version.txt
[D] app/build/intermediates/lint_intermediate_text_report
[D] app/build/intermediates/lint_intermediate_text_report/debug
[D] app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug
[F] app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt
[D] app/build/intermediates/lint_partial_results
[D] app/build/intermediates/lint_partial_results/debug
[D] app/build/intermediates/lint_partial_results/debug/lintAnalyzeDebug
[D] app/build/intermediates/lint_partial_results/debug/lintAnalyzeDebug/out
[F] app/build/intermediates/lint_partial_results/debug/lintAnalyzeDebug/out/lint-issues.xml
[F] app/build/intermediates/lint_partial_results/debug/lintAnalyzeDebug/out/lint-partial.xml
[F] app/build/intermediates/lint_partial_results/debug/lintAnalyzeDebug/out/lint-provisional.xml
[F] app/build/intermediates/lint_partial_results/debug/lintAnalyzeDebug/out/lint-resources.xml
[D] app/build/intermediates/lint_report_lint_model
[D] app/build/intermediates/lint_report_lint_model/debug
[D] app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel
[F] app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel/debug-artifact-dependencies.xml
[F] app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel/debug-artifact-libraries.xml
[F] app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel/debug.xml
[F] app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel/module.xml
[D] app/build/intermediates/lint_return_value
[D] app/build/intermediates/lint_return_value/debug
[D] app/build/intermediates/lint_return_value/debug/lintReportDebug
[F] app/build/intermediates/lint_return_value/debug/lintReportDebug/return-value-debug.txt
[D] app/build/intermediates/local_only_symbol_list
[D] app/build/intermediates/local_only_symbol_list/debug
[D] app/build/intermediates/local_only_symbol_list/debug/parseDebugLocalResources
[F] app/build/intermediates/local_only_symbol_list/debug/parseDebugLocalResources/R-def.txt
[D] app/build/intermediates/manifest_merge_blame_file
[D] app/build/intermediates/manifest_merge_blame_file/debug
[D] app/build/intermediates/manifest_merge_blame_file/debug/processDebugMainManifest
[F] app/build/intermediates/manifest_merge_blame_file/debug/processDebugMainManifest/manifest-merger-blame-debug-report.txt
[D] app/build/intermediates/merged_java_res
[D] app/build/intermediates/merged_java_res/debug
[D] app/build/intermediates/merged_java_res/debug/mergeDebugJavaResource
[F] app/build/intermediates/merged_java_res/debug/mergeDebugJavaResource/base.jar
[D] app/build/intermediates/merged_jni_libs
[D] app/build/intermediates/merged_jni_libs/debug
[D] app/build/intermediates/merged_jni_libs/debug/mergeDebugJniLibFolders
[D] app/build/intermediates/merged_jni_libs/debug/mergeDebugJniLibFolders/out
[D] app/build/intermediates/merged_manifest
[D] app/build/intermediates/merged_manifest/debug
[D] app/build/intermediates/merged_manifest/debug/processDebugMainManifest
[F] app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
[D] app/build/intermediates/merged_manifests
[D] app/build/intermediates/merged_manifests/debug
[D] app/build/intermediates/merged_manifests/debug/processDebugManifest
[F] app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
[F] app/build/intermediates/merged_manifests/debug/processDebugManifest/output-metadata.json
[D] app/build/intermediates/merged_native_libs
[D] app/build/intermediates/merged_native_libs/debug
[D] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs
[D] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out
[D] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib
[D] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a
[F] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libandroidx.graphics.path.so
[D] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/armeabi-v7a
[F] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/armeabi-v7a/libandroidx.graphics.path.so
[D] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/x86
[F] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/x86/libandroidx.graphics.path.so
[D] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/x86_64
[F] app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/x86_64/libandroidx.graphics.path.so
[D] app/build/intermediates/merged_res
[D] app/build/intermediates/merged_res/debug
[D] app/build/intermediates/merged_res/debug/mergeDebugResources
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/drawable_ic_arrow_back.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/drawable_ic_expand_more.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/drawable_ic_focus.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/drawable_ic_launcher_foreground.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/drawable_ic_more_vert.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/drawable_ic_refresh.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/mipmap-anydpi_ic_launcher.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-af_values-af.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-am_values-am.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ar_values-ar.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-as_values-as.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-az_values-az.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-b+sr+Latn_values-b+sr+Latn.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-be_values-be.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-bg_values-bg.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-bn_values-bn.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-bs_values-bs.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ca_values-ca.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-cs_values-cs.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-da_values-da.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-de_values-de.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-el_values-el.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-en-rAU_values-en-rAU.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-en-rCA_values-en-rCA.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-en-rGB_values-en-rGB.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-en-rIN_values-en-rIN.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-en-rXC_values-en-rXC.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-es-rUS_values-es-rUS.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-es_values-es.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-et_values-et.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-eu_values-eu.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-fa_values-fa.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-fi_values-fi.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-fr-rCA_values-fr-rCA.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-fr_values-fr.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-gl_values-gl.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-gu_values-gu.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-hi_values-hi.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-hr_values-hr.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-hu_values-hu.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-hy_values-hy.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-in_values-in.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-is_values-is.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-it_values-it.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-iw_values-iw.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ja_values-ja.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ka_values-ka.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-kk_values-kk.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-km_values-km.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-kn_values-kn.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ko_values-ko.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ky_values-ky.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-lo_values-lo.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-lt_values-lt.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-lv_values-lv.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-mk_values-mk.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ml_values-ml.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-mn_values-mn.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-mr_values-mr.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ms_values-ms.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-my_values-my.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-nb_values-nb.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ne_values-ne.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-night-v31_values-night-v31.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-night-v8_values-night-v8.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-nl_values-nl.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-or_values-or.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-pa_values-pa.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-pl_values-pl.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-pt-rBR_values-pt-rBR.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-pt-rPT_values-pt-rPT.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-pt_values-pt.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ro_values-ro.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ru_values-ru.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-si_values-si.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-sk_values-sk.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-sl_values-sl.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-sq_values-sq.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-sr_values-sr.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-sv_values-sv.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-sw_values-sw.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ta_values-ta.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-te_values-te.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-th_values-th.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-tl_values-tl.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-tr_values-tr.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-uk_values-uk.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-ur_values-ur.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-uz_values-uz.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-v23_values-v23.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-v29_values-v29.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-v30_values-v30.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-v31_values-v31.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-vi_values-vi.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-zh-rCN_values-zh-rCN.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-zh-rHK_values-zh-rHK.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-zh-rTW_values-zh-rTW.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values-zu_values-zu.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/values_values.arsc.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/xml_backup_rules.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/xml_data_extraction_rules.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/xml_device_admin.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/xml_focus_service_config.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/xml_next_widget_info.xml.flat
[F] app/build/intermediates/merged_res/debug/mergeDebugResources/xml_photo_paths.xml.flat
[D] app/build/intermediates/merged_res_blame_folder
[D] app/build/intermediates/merged_res_blame_folder/debug
[D] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources
[D] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out
[D] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/mergeDebugResources.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-af.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-am.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ar.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-as.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-az.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-b+sr+Latn.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-be.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-bg.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-bn.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-bs.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ca.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-cs.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-da.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-de.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-el.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-en-rAU.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-en-rCA.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-en-rGB.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-en-rIN.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-en-rXC.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-es-rUS.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-es.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-et.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-eu.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-fa.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-fi.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-fr-rCA.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-fr.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-gl.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-gu.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-hi.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-hr.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-hu.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-hy.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-in.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-is.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-it.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-iw.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ja.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ka.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-kk.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-km.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-kn.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ko.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ky.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-lo.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-lt.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-lv.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-mk.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ml.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-mn.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-mr.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ms.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-my.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-nb.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ne.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-night-v31.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-night-v8.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-nl.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-or.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-pa.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-pl.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-pt-rBR.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-pt-rPT.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-pt.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ro.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ru.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-si.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-sk.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-sl.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-sq.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-sr.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-sv.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-sw.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ta.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-te.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-th.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-tl.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-tr.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-uk.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-ur.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-uz.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-v23.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-v29.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-v30.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-v31.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-vi.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-zh-rCN.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-zh-rHK.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-zh-rTW.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values-zu.json
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/multi-v2/values.json
[D] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/single
[F] app/build/intermediates/merged_res_blame_folder/debug/mergeDebugResources/out/single/mergeDebugResources.json
[D] app/build/intermediates/merged_test_only_native_libs
[D] app/build/intermediates/merged_test_only_native_libs/debug
[D] app/build/intermediates/merged_test_only_native_libs/debug/mergeDebugNativeLibs
[D] app/build/intermediates/merged_test_only_native_libs/debug/mergeDebugNativeLibs/out
[D] app/build/intermediates/mixed_scope_dex_archive
[D] app/build/intermediates/mixed_scope_dex_archive/debug
[D] app/build/intermediates/mixed_scope_dex_archive/debug/dexBuilderDebug
[D] app/build/intermediates/mixed_scope_dex_archive/debug/dexBuilderDebug/out
[D] app/build/intermediates/navigation_json
[D] app/build/intermediates/navigation_json/debug
[D] app/build/intermediates/navigation_json/debug/extractDeepLinksDebug
[F] app/build/intermediates/navigation_json/debug/extractDeepLinksDebug/navigation.json
[D] app/build/intermediates/nested_resources_validation_report
[D] app/build/intermediates/nested_resources_validation_report/debug
[D] app/build/intermediates/nested_resources_validation_report/debug/generateDebugResources
[F] app/build/intermediates/nested_resources_validation_report/debug/generateDebugResources/nestedResourcesValidationReport.txt
[D] app/build/intermediates/packaged_manifests
[D] app/build/intermediates/packaged_manifests/debug
[D] app/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage
[F] app/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml
[F] app/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/output-metadata.json
[D] app/build/intermediates/packaged_res
[D] app/build/intermediates/packaged_res/debug
[D] app/build/intermediates/packaged_res/debug/packageDebugResources
[D] app/build/intermediates/packaged_res/debug/packageDebugResources/drawable
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/drawable/ic_arrow_back.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/drawable/ic_expand_more.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/drawable/ic_focus.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/drawable/ic_launcher_foreground.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/drawable/ic_more_vert.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/drawable/ic_refresh.xml
[D] app/build/intermediates/packaged_res/debug/packageDebugResources/mipmap-anydpi-v4
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/mipmap-anydpi-v4/ic_launcher.xml
[D] app/build/intermediates/packaged_res/debug/packageDebugResources/values
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/values/values.xml
[D] app/build/intermediates/packaged_res/debug/packageDebugResources/xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/xml/backup_rules.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/xml/data_extraction_rules.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/xml/device_admin.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/xml/focus_service_config.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/xml/next_widget_info.xml
[F] app/build/intermediates/packaged_res/debug/packageDebugResources/xml/photo_paths.xml
[D] app/build/intermediates/project_dex_archive
[D] app/build/intermediates/project_dex_archive/debug
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/62b23aad292ba05b62c7ae1a7fe4ee022ac4df7618ea7c433ab2611e017569fe_0.jar
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/62b23aad292ba05b62c7ae1a7fe4ee022ac4df7618ea7c433ab2611e017569fe_1.jar
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/62b23aad292ba05b62c7ae1a7fe4ee022ac4df7618ea7c433ab2611e017569fe_2.jar
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/62b23aad292ba05b62c7ae1a7fe4ee022ac4df7618ea7c433ab2611e017569fe_3.jar
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/62b23aad292ba05b62c7ae1a7fe4ee022ac4df7618ea7c433ab2611e017569fe_4.jar
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/62b23aad292ba05b62c7ae1a7fe4ee022ac4df7618ea7c433ab2611e017569fe_5.jar
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/62b23aad292ba05b62c7ae1a7fe4ee022ac4df7618ea7c433ab2611e017569fe_6.jar
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/62b23aad292ba05b62c7ae1a7fe4ee022ac4df7618ea7c433ab2611e017569fe_7.jar
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$1$2$emit$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$1$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$1$invokeSuspend$$inlined$map$1$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$1$invokeSuspend$$inlined$map$1$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$1$invokeSuspend$$inlined$map$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$10$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$10$invokeSuspend$$inlined$map$1$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$10$invokeSuspend$$inlined$map$1$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$10$invokeSuspend$$inlined$map$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$10.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$11$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$11$invokeSuspend$$inlined$map$1$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$11$invokeSuspend$$inlined$map$1$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$11$invokeSuspend$$inlined$map$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$11.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$12$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$12$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$12.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$13.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$14$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$16$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$16.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$17$1$emit$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$17$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$17.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$18$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$18$2$emit$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$18$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$18.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$4.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$5.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$6.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$7.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$8.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$9$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$9.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$addInstruction$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$addInstruction$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$applyDue$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$applyDueChanges$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$applyInstruction$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$applyInstructionWithCode$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$applyNow$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$cancelChange$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$changeSettings$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$deleteInstruction$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$deleteInstructionWithCode$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$enrichNow$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$enrichNow$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$importBackup$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$importBackup$6.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$instructions$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$layInstructions$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$log$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$modelFailed$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$modelWorked$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$readInstructions$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$reconcileAlerts$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$refreshAll$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$refuseCircle$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$refuseTakingBack$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$remove$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$requestTeamsSync$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$rereadInstruction$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$rereadInstruction$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$runtime$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$setApplied$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$settings$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$settleCompletions$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$settleCompletions$stopped$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$special$$inlined$CoroutineExceptionHandler$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$tasks$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$useParentCode$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$watchCalendar$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/AppGraph.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$onCreate$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Screen$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$1$1$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$2$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$4$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$5$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$1$6$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Screen$2$1$6$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Screen$now$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$Screen$takePhoto$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockedActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Blocklist$Address.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Blocklist.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockPolicy$Input.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockPolicy$Reason.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockPolicy$Verdict$Allow.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockPolicy$Verdict$Block.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockPolicy$Verdict$Spend.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockPolicy$Verdict.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/BlockPolicy.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/ComposableSingletons$BlockedActivityKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/CountdownBanner$tick$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/CountdownBanner.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Credit$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Credit$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Credit.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$finish$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$finishPhoto$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$finishPhotos$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$finishSessions$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$onCompleted$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$photoChecked$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$rewardCompletions$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$rewardCompletions$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$spendSoon$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$startSession$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$stopSession$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$Target$App.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$Target$Browser.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$Target$Site.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$Target.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus$tickBlock$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Focus.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$block$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$disconnect$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$newScope$$inlined$CoroutineExceptionHandler$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$offerTeamsSync$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$offerTeamsSync$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$offerTeamsSync$3$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$onClick$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$screenReceiver$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$tick$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService$ticker$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusService.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusSession$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusSession$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/FocusSession.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/PhotoChecks$check$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/PhotoChecks$check$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/PhotoChecks$check$jpeg$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/PhotoChecks$check$jpeg$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/PhotoChecks$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/PhotoChecks.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/SessionReceiver$onReceive$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/SessionReceiver.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Sessions$end$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Sessions$start$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/Sessions.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/TeamsAutoSync$State$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/TeamsAutoSync$State$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/TeamsAutoSync$State.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/TeamsAutoSync$Trigger.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/block/TeamsAutoSync.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/BuildConfig.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/About$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/About$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/About.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Busy.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Calibration$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Calibration$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Calibration.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Change$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Change$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Change.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/ChangeType$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/ChangeType.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Chunk.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/DayBucket.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Enrichment$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Enrichment$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Enrichment.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/EnrichmentKt$withEnrichment$1$place$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/EnrichmentKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Enrichments$Job.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Enrichments.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Fetched.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/FixedClock$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/FixedClock.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Instruction$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Instruction$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Instruction.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Instructions$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Instructions.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/InstructionsKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/InstructionState$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/InstructionState$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/InstructionState$special$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/InstructionState.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/InstructionStatus$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/InstructionStatus.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Kind$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Kind.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Merge$Result.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Merge.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Plan.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$capacity$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$Input.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$Item.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$Piece.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$plan$$inlined$compareBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$plan$4$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner$windows$Window.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Planner.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Source$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Source.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/SourceValues$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/SourceValues$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/SourceValues.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Status$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Status.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/SubStep$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/SubStep$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/SubStep.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/SystemWallClock.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskItem.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskOverrides$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/TaskOverrides.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Uptime$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Uptime$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/Uptime.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/WallClock$DefaultImpls.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/core/WallClock.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ActivityLog.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/AssessLater$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/AssessLater$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/AssessLater.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backup$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backup$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backup.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$4.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$5.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$6.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backups$mergeLog$$inlined$sortedBy$7.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Backups.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/BlockRecord$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/BlockRecord$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/BlockRecord.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/CompletionRecord$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/CompletionRecord$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/CompletionRecord.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/DailyRetry$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/DailyRetry$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/DailyRetry.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/DeviceClock.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/EndedSession$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/EndedSession$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/EndedSession.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/JsonStore$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/JsonStore$update$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/JsonStore$update$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/JsonStore.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/KeystoreCipher$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/KeystoreCipher.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/PhotoDone$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/PhotoDone$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/PhotoDone.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ProtectionRecord$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ProtectionRecord$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ProtectionRecord.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ProtectionState$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ProtectionState$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/ProtectionState.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Rewarded$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Rewarded$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Rewarded.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/RuntimeState.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Secret.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SecretCipher.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SecretStore$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SecretStore$put$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SecretStore$put$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SecretStore.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SessionRecord$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SessionRecord$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SessionRecord.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Settings$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Settings$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Settings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SourceStatus$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SourceStatus$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/SourceStatus.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/TaskState$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/TaskState$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/TaskState.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/WeeklyReview$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/WeeklyReview$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/WeeklyReview.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Window$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Window$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/data/Window.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$importCredentials$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$importCredentials$4.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$importCredentials$values$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$onCreate$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$run$$inlined$groupingBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity$run$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/debug/CommandActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/DecrastinationApp.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/AiUsage$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/AiUsage$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/AiUsage.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Checked.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Estimate$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Estimate$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Estimate.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Split$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Split$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Split.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Step$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Step$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Step.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Triage$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Triage$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$Triage.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Answers.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeEnricher$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeEnricher$enrich$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeEnricher$enrich$message$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeEnricher.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeReviewer$Result.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeReviewer$review$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeReviewer$review$message$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ClaudeReviewer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Enricher$Result.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Enricher.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/EnrichWorker$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/EnrichWorker$doWork$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/EnrichWorker.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Answer$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Answer$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Answer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$EntriesMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Given$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Given$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Given.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Reading$Changes.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Reading$Unclear.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$Reading.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionAnswers.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionPrompts$describe$lambda$18$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionPrompts$describe$lambda$18$$inlined$sortedBy$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionPrompts$EntriesMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionPrompts.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionReader$read$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionReader$read$message$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionReader$Result.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/InstructionReader.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/KeyProblem$Companion$of$$inlined$filterIsInstance$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/KeyProblem$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/KeyProblem.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ModelAlerts.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ModelHold$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/ModelHold.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/PhotoChecker$check$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/PhotoChecker$check$message$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/PhotoChecker$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/PhotoChecker$Result.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/PhotoChecker$Verdict$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/PhotoChecker$Verdict$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/PhotoChecker$Verdict.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/PhotoChecker.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Pricing$Rates.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Pricing.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Prompts$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/Prompts.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/RuleEnricher$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/enrich/RuleEnricher.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity$Screen$save$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Assessment$record$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Assessment.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessmentReceiver$onReceive$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/AssessmentReceiver.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Briefing$run$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Briefing.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CalendarEvent.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CalendarTime$refresh$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CalendarTime$refresh$events$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CalendarTime.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Calibrator$boxes$lambda$6$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Calibrator$Learned.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Calibrator$margins$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Calibrator$multipliers$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Calibrator.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckIn$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckIn$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckIn.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity$Form$1$1$6$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckInActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/CheckIns.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$AssessmentKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ComposableSingletons$CheckInsKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Daily.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DailyReceiver$onReceive$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DailyReceiver.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DayChunk$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DayChunk$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DayChunk.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DayRecord$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DayRecord$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/DayRecord.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Days.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventAnswerReceiver$onReceive$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventAnswerReceiver.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventJudge$Judgement$Ask.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventJudge$Judgement$Busy.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventJudge$Judgement$Free.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventJudge$Judgement$Load.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventJudge$Judgement.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventJudge$Time.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/EventJudge.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$modelReview$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$Outcome.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$run$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Review.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$Answer$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$Answer$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$Answer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$Change$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$Change$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput$Change.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewInput.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewWorker$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewWorker$doWork$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/ReviewWorker.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Stats$calibrationLines$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Stats$calibrationLines$$inlined$sortedBy$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Stats$calibrationLines$$inlined$sortedBy$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Stats$Day.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Stats$summary$$inlined$compareByDescending$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Stats$summary$$inlined$groupingBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Stats$summary$$inlined$thenBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Stats$Summary.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/learn/Stats.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/net
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/net/Http.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/net/HttpResponse.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/net/UrlConnectionHttp.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/notify
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/notify/Channels.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/notify/Notify.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/AdminReceiver.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/BootReceiver$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/BootReceiver$onReceive$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/BootReceiver.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/CodeLock$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/CodeLock$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/CodeLock$Result$Accepted.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/CodeLock$Result$Locked.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/CodeLock$Result$Reused.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/CodeLock$Result$Wrong.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/CodeLock$Result.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/CodeLock.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ComposableSingletons$ProtectionActivityKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/GuardRules$Labels.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/GuardRules$Verdict$Back.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/GuardRules$Verdict$Leave.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/GuardRules$Verdict.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/GuardRules.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/PendingChange$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/PendingChange$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/PendingChange.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$applyWithCode$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$CodeDialog$1$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$CodeTarget$Change.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$CodeTarget$Unblock.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$CodeTarget.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$ParentCodeDialog$1$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$ParentCodeDialog$2$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$Screen$1$1$1$1$2$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$Screen$1$1$3$1$2$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$Screen$9$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$Step.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionCheck$Report.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/ProtectionCheck.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges$Outcome.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/SettingsChanges.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/Totp.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/Watchdog$check$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/Watchdog$restart$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/Watchdog.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/WatchdogReceiver$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/WatchdogReceiver$onReceive$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/WatchdogReceiver.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/WatchdogWorker$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/WatchdogWorker$doWork$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/protect/WatchdogWorker.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiDay$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiDay$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiDay.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiProvider$Opened.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiProvider.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiRules$homeworkDecks$lambda$47$$inlined$compareBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiRules.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiSource$read$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/AnkiSource.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/Deck$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/anki/Deck.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/EmailRules$Email.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/EmailRules$EntriesMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/EmailRules$special$$inlined$sortedByDescending$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/EmailRules$Triage.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/EmailRules.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailSource$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailSource$read$2$closer$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailSource$read$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailSource.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailThreads$Body.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailThreads$fetched$$inlined$groupingBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailThreads$latest$$inlined$sortedByDescending$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/GmailThreads.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapClient$CommandFailed.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapClient$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapClient.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapLine.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapReader$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapReader.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapValue$Atom.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapValue$Items.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapValue$Nil.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapValue$Str.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/ImapValue.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/InboxMessage.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/Mime$TextPart.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/gmail/Mime.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/AgendaResponse$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/AgendaResponse$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/AgendaResponse.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/ClassesResponse$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/ClassesResponse$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/ClassesResponse.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/LoginResponse$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/LoginResponse$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/LoginResponse.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$ApiError.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi$Login.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItems$Due.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItems.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource$freshLogin$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource$read$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource$Semester.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpClass$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpClass$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpClass.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpItem$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpItem$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpItem.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpSchedule$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpSchedule$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/PpSchedule.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/SemesterResponse$$serializer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/SemesterResponse$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/SemesterResponse.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/Timetable.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/powerplanner/WithError.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/ReadContext.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/SourceRead.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/SourceUnavailable.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/TaskSource.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/teams
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/teams/TeamsProvider.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/teams/TeamsRows.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/teams/TeamsSource$read$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sources/teams/TeamsSource.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$failed$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$readOne$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$readOne$read$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$readOne$reading$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$special$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer$sync$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/Syncer.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/SyncReport.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/SyncWorker$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/SyncWorker$doWork$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/sync/SyncWorker.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$3$1$1$3$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$3$1$1$5$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$3$1$1$5$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$4.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$5.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$itemsIndexed$default$6.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Screen$lambda$26$lambda$25$lambda$24$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity$Writing.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/CalendarActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComponentsKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$CalendarActivityKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$ComponentsKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsActivityKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$InstructionsUiKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$MainActivityKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SettingsActivityKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$SetupScreenKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$StatsScreenKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$TasksScreenKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ComposableSingletons$TodayScreenKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/Credential.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/Format.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$onResume$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$Screen$$inlined$sortedByDescending$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$Screen$2$1$1$1$1$1$2$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$section$$inlined$itemsIndexed$default$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$section$$inlined$itemsIndexed$default$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$section$$inlined$itemsIndexed$default$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity$section$3$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$3$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$5$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$6$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$3$7$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$4$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$InstructionContent$6$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$ParentCodeDialog$1$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$sendInstruction$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/InstructionsUiKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity$Main$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/MainActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/OpenTaskActivity$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/OpenTaskActivity$onCreate$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/OpenTaskActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$App.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$3$1$2$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$1$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$11$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$2$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$2$3$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$2$5$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$2$7$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$3$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$3$3$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$3$5$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$4$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$4$4$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$3$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$5$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$7$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$5$9$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$6$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$4$1$1$6$3$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$4.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$5.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$6.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$7.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$Editor$lambda$108$lambda$107$lambda$106$$inlined$items$default$8.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity$launchableApps$$inlined$sortedBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt$TimeField$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SettingsActivityKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupActivity.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$3$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$4$2$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$exporter$1$1$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$exporter$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$importer$1$1$1$1$bytes$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$importer$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt$SetupScreen$requestCalendar$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/SetupScreenKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$1$1$3$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt$StatsScreen$now$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/StatsScreenKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TaskOpener$open$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TaskOpener$open$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TaskOpener$openTeams$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TaskOpener$openTeams$result$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TaskOpener$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TaskOpener.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$compareBy$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$compareBy$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$4.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$5.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$6.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$7.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$8.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$itemsIndexed$default$9.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$lambda$38$lambda$37$$inlined$sortedByDescending$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt$TasksScreen$tick$1$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TasksScreenKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/ThemeKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TileActions.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$1$1$5$3$1$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$1$1$7$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$$inlined$itemsIndexed$default$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$lambda$41$$inlined$itemsIndexed$default$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$lambda$41$$inlined$itemsIndexed$default$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$lambda$45$lambda$44$lambda$41$$inlined$itemsIndexed$default$3.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$now$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt$TodayScreen$open$1$1$2$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/ui/TodayScreenKt.dex
[D] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/ComposableSingletons$NextWidgetKt.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$Content$lambda$14$lambda$13$lambda$12$$inlined$items$default$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$Content$lambda$14$lambda$13$lambda$12$$inlined$items$default$2.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$provideGlance$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget$WhenMappings.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidget.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidgetReceiver$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidgetReceiver$onReceive$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/NextWidgetReceiver.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/RefreshAction.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetLayout$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetLayout$Parts$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetLayout$Parts.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetLayout$Pills.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetLayout.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetModel$Companion.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetModel$Line.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetModel.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetUpdater$update$1.dex
[F] app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/thomaswcode/decrastination/widget/WidgetUpdater.dex
[D] app/build/intermediates/runtime_app_classes_jar
[D] app/build/intermediates/runtime_app_classes_jar/debug
[D] app/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar
[F] app/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar
[D] app/build/intermediates/runtime_symbol_list
[D] app/build/intermediates/runtime_symbol_list/debug
[D] app/build/intermediates/runtime_symbol_list/debug/processDebugResources
[F] app/build/intermediates/runtime_symbol_list/debug/processDebugResources/R.txt
[D] app/build/intermediates/signing_config_versions
[D] app/build/intermediates/signing_config_versions/debug
[D] app/build/intermediates/signing_config_versions/debug/writeDebugSigningConfigVersions
[F] app/build/intermediates/signing_config_versions/debug/writeDebugSigningConfigVersions/signing-config-versions.json
[D] app/build/intermediates/stable_resource_ids_file
[D] app/build/intermediates/stable_resource_ids_file/debug
[D] app/build/intermediates/stable_resource_ids_file/debug/processDebugResources
[F] app/build/intermediates/stable_resource_ids_file/debug/processDebugResources/stableIds.txt
[D] app/build/intermediates/stripped_native_libs
[D] app/build/intermediates/stripped_native_libs/debug
[D] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols
[D] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out
[D] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib
[D] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a
[F] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libandroidx.graphics.path.so
[D] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/armeabi-v7a
[F] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/armeabi-v7a/libandroidx.graphics.path.so
[D] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/x86
[F] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/x86/libandroidx.graphics.path.so
[D] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/x86_64
[F] app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/x86_64/libandroidx.graphics.path.so
[D] app/build/intermediates/sub_project_dex_archive
[D] app/build/intermediates/sub_project_dex_archive/debug
[D] app/build/intermediates/sub_project_dex_archive/debug/dexBuilderDebug
[D] app/build/intermediates/sub_project_dex_archive/debug/dexBuilderDebug/out
[D] app/build/intermediates/symbol_list_with_package_name
[D] app/build/intermediates/symbol_list_with_package_name/debug
[D] app/build/intermediates/symbol_list_with_package_name/debug/generateDebugRFile
[F] app/build/intermediates/symbol_list_with_package_name/debug/generateDebugRFile/package-aware-r.txt
[D] app/build/intermediates/unit_test_lint_model
[D] app/build/intermediates/unit_test_lint_model/debug
[D] app/build/intermediates/unit_test_lint_model/debug/generateDebugUnitTestLintModel
[F] app/build/intermediates/unit_test_lint_model/debug/generateDebugUnitTestLintModel/debug-artifact-dependencies.xml
[F] app/build/intermediates/unit_test_lint_model/debug/generateDebugUnitTestLintModel/debug-artifact-libraries.xml
[F] app/build/intermediates/unit_test_lint_model/debug/generateDebugUnitTestLintModel/debug.xml
[F] app/build/intermediates/unit_test_lint_model/debug/generateDebugUnitTestLintModel/module.xml
[D] app/build/intermediates/unit_test_lint_partial_results
[D] app/build/intermediates/unit_test_lint_partial_results/debug
[D] app/build/intermediates/unit_test_lint_partial_results/debug/lintAnalyzeDebugUnitTest
[D] app/build/intermediates/unit_test_lint_partial_results/debug/lintAnalyzeDebugUnitTest/out
[F] app/build/intermediates/unit_test_lint_partial_results/debug/lintAnalyzeDebugUnitTest/out/lint-issues.xml
[F] app/build/intermediates/unit_test_lint_partial_results/debug/lintAnalyzeDebugUnitTest/out/lint-partial.xml
[D] app/build/intermediates/validate_signing_config
[D] app/build/intermediates/validate_signing_config/debug
[D] app/build/intermediates/validate_signing_config/debug/validateSigningDebug
[D] app/build/kotlin
[D] app/build/kotlin/compileDebugKotlin
[D] app/build/kotlin/compileDebugKotlin/cacheable
[D] app/build/kotlin/compileDebugKotlin/classpath-snapshot
[D] app/build/kotlin/compileDebugKotlin/local-state
[D] app/build/kotlin/compileDebugUnitTestKotlin
[D] app/build/kotlin/compileDebugUnitTestKotlin/cacheable
[D] app/build/kotlin/compileDebugUnitTestKotlin/classpath-snapshot
[D] app/build/kotlin/compileDebugUnitTestKotlin/local-state
[D] app/build/outputs
[D] app/build/outputs/apk
[D] app/build/outputs/apk/debug
[F] app/build/outputs/apk/debug/app-debug.apk
[F] app/build/outputs/apk/debug/output-metadata.json
[D] app/build/outputs/logs
[F] app/build/outputs/logs/manifest-merger-debug-report.txt
[D] app/build/reports
[F] app/build/reports/lint-results-debug.html
[F] app/build/reports/lint-results-debug.txt
[F] app/build/reports/lint-results-debug.xml
[D] app/build/reports/tests
[D] app/build/reports/tests/testDebugUnitTest
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.BlocklistTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.BlocklistTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.BlockPolicyTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.BlockPolicyTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.CreditTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.CreditTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.FocusTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.FocusTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.TeamsAutoSyncTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.block.TeamsAutoSyncTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.core.EnrichmentTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.core.EnrichmentTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.core.InstructionsTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.core.InstructionsTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.core.MergeTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.core.MergeTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.core.PlannerTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.core.PlannerTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.data.BackupTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.data.BackupTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.data.StoresTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.data.StoresTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.AiUsageTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.AiUsageTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.AnswersTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.AnswersTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.ClaudeEnricherTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.ClaudeEnricherTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.PhotoCheckerTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.PhotoCheckerTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.PromptsTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.PromptsTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.RuleEnricherTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.enrich.RuleEnricherTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.EvalDumpTest
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.EvalRulesTest
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.CalendarQuestionsTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.CalendarQuestionsTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.CalibratorTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.CalibratorTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.ClaudeReviewerTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.ClaudeReviewerTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.DailyTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.DailyTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.DaysTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.DaysTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.EventJudgeTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.EventJudgeTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.ReviewInputTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.ReviewInputTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.StatsTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.learn.StatsTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.PlanCheckTmp
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.probe.DeckCountsTest
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.probe.GuardRulesTest
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.CodeLockTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.CodeLockTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.GuardRulesTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.GuardRulesTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.ProtectionCheckTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.ProtectionCheckTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.SettingsChangesTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.SettingsChangesTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.TotpTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.protect.TotpTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.anki.AnkiRulesTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.anki.AnkiRulesTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.gmail.GmailTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.gmail.GmailTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.gmail.ImapTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.gmail.ImapTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.gmail.MimeTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.gmail.MimeTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerItemsTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerItemsTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerSourceTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerSourceTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.teams.TeamsRowsTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sources.teams.TeamsRowsTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sync.SyncerTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.sync.SyncerTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.ui.FormatTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.ui.FormatTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.widget.WidgetLayoutTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.widget.WidgetLayoutTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.widget.WidgetModelTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.widget.WidgetModelTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.widget.WidgetRedrawTest
[F] app/build/reports/tests/testDebugUnitTest/com.thomaswcode.decrastination.widget.WidgetRedrawTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/css
[F] app/build/reports/tests/testDebugUnitTest/css/base-style.css
[F] app/build/reports/tests/testDebugUnitTest/css/style.css
[F] app/build/reports/tests/testDebugUnitTest/index.html
[D] app/build/reports/tests/testDebugUnitTest/js
[F] app/build/reports/tests/testDebugUnitTest/js/report.js
[D] app/build/test-results
[D] app/build/test-results/testDebugUnitTest
[D] app/build/test-results/testDebugUnitTest/binary
[F] app/build/test-results/testDebugUnitTest/binary/output-events.bin
[F] app/build/test-results/testDebugUnitTest/binary/results-generic.bin
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.block.BlocklistTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.block.BlockPolicyTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.block.CreditTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.block.FocusTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.block.TeamsAutoSyncTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.core.EnrichmentTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.core.InstructionsTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.core.MergeTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.core.PlannerTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.data.BackupTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.data.StoresTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.enrich.AiUsageTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.enrich.AnswersTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.enrich.ClaudeEnricherTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.enrich.PhotoCheckerTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.enrich.PromptsTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.enrich.RuleEnricherTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.learn.CalendarQuestionsTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.learn.CalibratorTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.learn.ClaudeReviewerTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.learn.DailyTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.learn.DaysTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.learn.EventJudgeTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.learn.ReviewInputTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.learn.StatsTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.protect.CodeLockTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.protect.GuardRulesTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.protect.ProtectionCheckTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.protect.SettingsChangesTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.protect.TotpTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.sources.anki.AnkiRulesTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.sources.gmail.GmailTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.sources.gmail.ImapTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.sources.gmail.MimeTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerItemsTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerSourceTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.sources.teams.TeamsRowsTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.sync.SyncerTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.ui.FormatTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.widget.WidgetLayoutTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.widget.WidgetModelTest.xml
[F] app/build/test-results/testDebugUnitTest/TEST-com.thomaswcode.decrastination.widget.WidgetRedrawTest.xml
[D] app/build/tmp
[D] app/build/tmp/compileDebugJavaWithJavac
[D] app/build/tmp/compileDebugJavaWithJavac/compileTransaction
[D] app/build/tmp/compileDebugJavaWithJavac/compileTransaction/backup-dir
[D] app/build/tmp/compileDebugJavaWithJavac/compileTransaction/stash-dir
[F] app/build/tmp/compileDebugJavaWithJavac/previous-compilation-data.bin
[D] app/build/tmp/testDebugUnitTest
[F] app/debug.keystore
[F] app/lint.xml
[D] app/src
[D] app/src/main
[F] app/src/main/AndroidManifest.xml
[D] app/src/main/java
[D] app/src/main/java/com
[D] app/src/main/java/com/thomaswcode
[D] app/src/main/java/com/thomaswcode/decrastination
[F] app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt
[D] app/src/main/java/com/thomaswcode/decrastination/block
[F] app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt
[F] app/src/main/java/com/thomaswcode/decrastination/block/Blocklist.kt
[F] app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt
[F] app/src/main/java/com/thomaswcode/decrastination/block/CountdownBanner.kt
[F] app/src/main/java/com/thomaswcode/decrastination/block/Credit.kt
[F] app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt
[F] app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt
[F] app/src/main/java/com/thomaswcode/decrastination/block/PhotoChecks.kt
[F] app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt
[F] app/src/main/java/com/thomaswcode/decrastination/block/TeamsAutoSync.kt
[D] app/src/main/java/com/thomaswcode/decrastination/core
[F] app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt
[F] app/src/main/java/com/thomaswcode/decrastination/core/Instructions.kt
[F] app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt
[F] app/src/main/java/com/thomaswcode/decrastination/core/Plan.kt
[F] app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt
[F] app/src/main/java/com/thomaswcode/decrastination/core/Task.kt
[F] app/src/main/java/com/thomaswcode/decrastination/core/WallClock.kt
[D] app/src/main/java/com/thomaswcode/decrastination/data
[F] app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt
[F] app/src/main/java/com/thomaswcode/decrastination/data/DeviceClock.kt
[F] app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt
[F] app/src/main/java/com/thomaswcode/decrastination/data/RuntimeState.kt
[F] app/src/main/java/com/thomaswcode/decrastination/data/SecretStore.kt
[F] app/src/main/java/com/thomaswcode/decrastination/data/Settings.kt
[F] app/src/main/java/com/thomaswcode/decrastination/data/TaskState.kt
[D] app/src/main/java/com/thomaswcode/decrastination/debug
[F] app/src/main/java/com/thomaswcode/decrastination/debug/CommandActivity.kt
[F] app/src/main/java/com/thomaswcode/decrastination/DecrastinationApp.kt
[D] app/src/main/java/com/thomaswcode/decrastination/enrich
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/Enricher.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/EnrichWorker.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/InstructionReader.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/KeyProblem.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/ModelAlerts.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/ModelHold.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/PhotoChecker.kt
[F] app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt
[D] app/src/main/java/com/thomaswcode/decrastination/learn
[F] app/src/main/java/com/thomaswcode/decrastination/learn/Assessment.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/Briefing.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/CheckIns.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/Daily.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/Days.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/ReviewWorker.kt
[F] app/src/main/java/com/thomaswcode/decrastination/learn/Stats.kt
[D] app/src/main/java/com/thomaswcode/decrastination/net
[F] app/src/main/java/com/thomaswcode/decrastination/net/Http.kt
[D] app/src/main/java/com/thomaswcode/decrastination/notify
[F] app/src/main/java/com/thomaswcode/decrastination/notify/Channels.kt
[F] app/src/main/java/com/thomaswcode/decrastination/notify/Notify.kt
[D] app/src/main/java/com/thomaswcode/decrastination/protect
[F] app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt
[F] app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt
[F] app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionCheck.kt
[F] app/src/main/java/com/thomaswcode/decrastination/protect/SettingsChanges.kt
[F] app/src/main/java/com/thomaswcode/decrastination/protect/Totp.kt
[F] app/src/main/java/com/thomaswcode/decrastination/protect/Watchdog.kt
[D] app/src/main/java/com/thomaswcode/decrastination/sources
[D] app/src/main/java/com/thomaswcode/decrastination/sources/anki
[F] app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt
[F] app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt
[D] app/src/main/java/com/thomaswcode/decrastination/sources/gmail
[F] app/src/main/java/com/thomaswcode/decrastination/sources/gmail/EmailRules.kt
[F] app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt
[F] app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Imap.kt
[F] app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Mime.kt
[D] app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner
[F] app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt
[F] app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItems.kt
[F] app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource.kt
[F] app/src/main/java/com/thomaswcode/decrastination/sources/TaskSource.kt
[D] app/src/main/java/com/thomaswcode/decrastination/sources/teams
[F] app/src/main/java/com/thomaswcode/decrastination/sources/teams/TeamsSource.kt
[D] app/src/main/java/com/thomaswcode/decrastination/sync
[F] app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt
[F] app/src/main/java/com/thomaswcode/decrastination/sync/SyncWorker.kt
[D] app/src/main/java/com/thomaswcode/decrastination/ui
[F] app/src/main/java/com/thomaswcode/decrastination/ui/CalendarActivity.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/Components.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/Format.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/InstructionsActivity.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/InstructionsUi.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/OpenTaskActivity.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/SetupActivity.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/StatsScreen.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/TaskOpener.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/Theme.kt
[F] app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt
[D] app/src/main/java/com/thomaswcode/decrastination/widget
[F] app/src/main/java/com/thomaswcode/decrastination/widget/NextWidget.kt
[F] app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt
[F] app/src/main/java/com/thomaswcode/decrastination/widget/WidgetUpdater.kt
[D] app/src/main/res
[D] app/src/main/res/drawable
[F] app/src/main/res/drawable/ic_arrow_back.xml
[F] app/src/main/res/drawable/ic_expand_more.xml
[F] app/src/main/res/drawable/ic_focus.xml
[F] app/src/main/res/drawable/ic_launcher_foreground.xml
[F] app/src/main/res/drawable/ic_more_vert.xml
[F] app/src/main/res/drawable/ic_refresh.xml
[D] app/src/main/res/mipmap-anydpi
[F] app/src/main/res/mipmap-anydpi/ic_launcher.xml
[D] app/src/main/res/values
[F] app/src/main/res/values/colors.xml
[F] app/src/main/res/values/strings.xml
[F] app/src/main/res/values/themes.xml
[D] app/src/main/res/xml
[F] app/src/main/res/xml/backup_rules.xml
[F] app/src/main/res/xml/data_extraction_rules.xml
[F] app/src/main/res/xml/device_admin.xml
[F] app/src/main/res/xml/focus_service_config.xml
[F] app/src/main/res/xml/next_widget_info.xml
[F] app/src/main/res/xml/photo_paths.xml
[D] app/src/test
[D] app/src/test/java
[D] app/src/test/java/com
[D] app/src/test/java/com/thomaswcode
[D] app/src/test/java/com/thomaswcode/decrastination
[D] app/src/test/java/com/thomaswcode/decrastination/block
[F] app/src/test/java/com/thomaswcode/decrastination/block/BlockingTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/block/FocusTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/core
[F] app/src/test/java/com/thomaswcode/decrastination/core/EnrichmentTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/core/InstructionsTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/core/MergeTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/core/PlannerTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/data
[F] app/src/test/java/com/thomaswcode/decrastination/data/BackupTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/data/StoresTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/enrich
[F] app/src/test/java/com/thomaswcode/decrastination/enrich/EnrichTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/Fixtures.kt
[D] app/src/test/java/com/thomaswcode/decrastination/learn
[F] app/src/test/java/com/thomaswcode/decrastination/learn/DailyTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/learn/LearnTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/learn/StatsTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/protect
[F] app/src/test/java/com/thomaswcode/decrastination/protect/ProtectTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/sources
[D] app/src/test/java/com/thomaswcode/decrastination/sources/anki
[F] app/src/test/java/com/thomaswcode/decrastination/sources/anki/AnkiRulesTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/sources/gmail
[F] app/src/test/java/com/thomaswcode/decrastination/sources/gmail/GmailTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/sources/gmail/ImapTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/sources/gmail/MimeTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/sources/powerplanner
[F] app/src/test/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItemsTest.kt
[F] app/src/test/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSourceTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/sources/teams
[F] app/src/test/java/com/thomaswcode/decrastination/sources/teams/TeamsRowsTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/sync
[F] app/src/test/java/com/thomaswcode/decrastination/sync/SyncerTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/ui
[F] app/src/test/java/com/thomaswcode/decrastination/ui/FormatTest.kt
[D] app/src/test/java/com/thomaswcode/decrastination/widget
[F] app/src/test/java/com/thomaswcode/decrastination/widget/WidgetModelTest.kt
[D] build
[F] build.gradle.kts
[D] build/reports
[D] build/reports/configuration-cache
[D] build/reports/configuration-cache/2rbo227era7fnhmr7dnk5wmob
[D] build/reports/configuration-cache/2rbo227era7fnhmr7dnk5wmob/cr1d7dck0blf1vue9hlmxtppt
[F] build/reports/configuration-cache/2rbo227era7fnhmr7dnk5wmob/cr1d7dck0blf1vue9hlmxtppt/configuration-cache-report.html
[D] build/reports/configuration-cache/2ullftpwdlkwq2obsqc7x28d
[D] build/reports/configuration-cache/2ullftpwdlkwq2obsqc7x28d/9e5o1ff5h8ur52bycmaxed7d
[F] build/reports/configuration-cache/2ullftpwdlkwq2obsqc7x28d/9e5o1ff5h8ur52bycmaxed7d/configuration-cache-report.html
[D] build/reports/configuration-cache/39wat6nmz8yi4mad1cppfhuft
[D] build/reports/configuration-cache/39wat6nmz8yi4mad1cppfhuft/5cipau11vqj2mi7niatpk7xjj
[F] build/reports/configuration-cache/39wat6nmz8yi4mad1cppfhuft/5cipau11vqj2mi7niatpk7xjj/configuration-cache-report.html
[D] build/reports/configuration-cache/3a1qemkuic0zmeifbc1f94jeu
[D] build/reports/configuration-cache/3a1qemkuic0zmeifbc1f94jeu/sjo4cmc0lncttcgtia4bhn4w
[F] build/reports/configuration-cache/3a1qemkuic0zmeifbc1f94jeu/sjo4cmc0lncttcgtia4bhn4w/configuration-cache-report.html
[D] build/reports/configuration-cache/3uk1gdcx3wbh11kvetz4l0q4i
[D] build/reports/configuration-cache/3uk1gdcx3wbh11kvetz4l0q4i/1wmtc6oho5h8yeds82gtp7h4x
[F] build/reports/configuration-cache/3uk1gdcx3wbh11kvetz4l0q4i/1wmtc6oho5h8yeds82gtp7h4x/configuration-cache-report.html
[D] build/reports/configuration-cache/3uk1gdcx3wbh11kvetz4l0q4i/5k12v2etm531dreprfrdg0wxk
[F] build/reports/configuration-cache/3uk1gdcx3wbh11kvetz4l0q4i/5k12v2etm531dreprfrdg0wxk/configuration-cache-report.html
[D] build/reports/configuration-cache/3uk1gdcx3wbh11kvetz4l0q4i/cldoot6ybcs9jxzhdiv0366lz
[F] build/reports/configuration-cache/3uk1gdcx3wbh11kvetz4l0q4i/cldoot6ybcs9jxzhdiv0366lz/configuration-cache-report.html
[D] build/reports/configuration-cache/4b1yct9gvqwzktq60ner5vb3f
[D] build/reports/configuration-cache/4b1yct9gvqwzktq60ner5vb3f/7v4pbz4twpwuir66bfbqznocb
[F] build/reports/configuration-cache/4b1yct9gvqwzktq60ner5vb3f/7v4pbz4twpwuir66bfbqznocb/configuration-cache-report.html
[D] build/reports/configuration-cache/4b1yct9gvqwzktq60ner5vb3f/eqy8eamp30pq3iid3m63zx6tq
[F] build/reports/configuration-cache/4b1yct9gvqwzktq60ner5vb3f/eqy8eamp30pq3iid3m63zx6tq/configuration-cache-report.html
[D] build/reports/configuration-cache/4rauf13tyth2fmndlm8picy2t
[D] build/reports/configuration-cache/4rauf13tyth2fmndlm8picy2t/73p87bktwzj65hvno1a1pnzgr
[F] build/reports/configuration-cache/4rauf13tyth2fmndlm8picy2t/73p87bktwzj65hvno1a1pnzgr/configuration-cache-report.html
[D] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l
[D] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/19sbucifmfl0ek90x1p5buxw9
[F] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/19sbucifmfl0ek90x1p5buxw9/configuration-cache-report.html
[D] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/5lr1srudenkh70q9pu2pj1745
[F] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/5lr1srudenkh70q9pu2pj1745/configuration-cache-report.html
[D] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/5xkoeklrg99a4eryzmjstrf1p
[F] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/5xkoeklrg99a4eryzmjstrf1p/configuration-cache-report.html
[D] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/7b04v0mdlvnzsrh0fn93n9ln6
[F] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/7b04v0mdlvnzsrh0fn93n9ln6/configuration-cache-report.html
[D] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/a5jdi5pbk9zgd8russgwi31rb
[F] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/a5jdi5pbk9zgd8russgwi31rb/configuration-cache-report.html
[D] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/de6aww9x8k8yfw33plo2buhcw
[F] build/reports/configuration-cache/50a8x946nvjmnw7js342qdz1l/de6aww9x8k8yfw33plo2buhcw/configuration-cache-report.html
[D] build/reports/configuration-cache/631rlvosu4u68vjte532prv4m
[D] build/reports/configuration-cache/631rlvosu4u68vjte532prv4m/7ztn788h3z1irlitwqkzt5onz
[F] build/reports/configuration-cache/631rlvosu4u68vjte532prv4m/7ztn788h3z1irlitwqkzt5onz/configuration-cache-report.html
[D] build/reports/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a
[D] build/reports/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a/6dhv2gmkh7g6bnjpvr9i62rhc
[F] build/reports/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a/6dhv2gmkh7g6bnjpvr9i62rhc/configuration-cache-report.html
[D] build/reports/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a/7dp9kv6b1ngpjiynv8ric1i1v
[F] build/reports/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a/7dp9kv6b1ngpjiynv8ric1i1v/configuration-cache-report.html
[D] build/reports/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a/8qjv2bdibrmuta2y65pzbf234
[F] build/reports/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a/8qjv2bdibrmuta2y65pzbf234/configuration-cache-report.html
[D] build/reports/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a/aka9fpp1trs5lcz1rhqbkggwq
[F] build/reports/configuration-cache/6ahlk0gyeazcfkaxcoqp3dk7a/aka9fpp1trs5lcz1rhqbkggwq/configuration-cache-report.html
[D] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd
[D] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/3cx2f6c91vne09qrapfx6eh7x
[F] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/3cx2f6c91vne09qrapfx6eh7x/configuration-cache-report.html
[D] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/4ig3wcegqkfg5vgpzqfewgc8j
[F] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/4ig3wcegqkfg5vgpzqfewgc8j/configuration-cache-report.html
[D] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/4o6vipj99cjvchf7pgu0evqw8
[F] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/4o6vipj99cjvchf7pgu0evqw8/configuration-cache-report.html
[D] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/6o1jc3vfc285u2m9vvjufl870
[F] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/6o1jc3vfc285u2m9vvjufl870/configuration-cache-report.html
[D] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/a42fgsong0wq65q0aewzu0uiz
[F] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/a42fgsong0wq65q0aewzu0uiz/configuration-cache-report.html
[D] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/e8ura640sil9cexi1dol4vwl8
[F] build/reports/configuration-cache/6lsik8qi5nodlnv0082notikd/e8ura640sil9cexi1dol4vwl8/configuration-cache-report.html
[D] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy
[D] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/1aw0rlrjcjuvp1vq4au4rnsqa
[F] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/1aw0rlrjcjuvp1vq4au4rnsqa/configuration-cache-report.html
[D] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/1icbirqbv71y1kzom0bvim95a
[F] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/1icbirqbv71y1kzom0bvim95a/configuration-cache-report.html
[D] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/734cvj8hj9lks35lsm9ikeqf8
[F] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/734cvj8hj9lks35lsm9ikeqf8/configuration-cache-report.html
[D] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/9bd381h48f5ohrfxykte8n5lz
[F] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/9bd381h48f5ohrfxykte8n5lz/configuration-cache-report.html
[D] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/bd85r84jxkl6c4d1koszlira8
[F] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/bd85r84jxkl6c4d1koszlira8/configuration-cache-report.html
[D] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/dt7qnfcpyvsq6ed5ft5n9p8li
[F] build/reports/configuration-cache/6ofd5wg7hyn4xdx5pk6ahrbjy/dt7qnfcpyvsq6ed5ft5n9p8li/configuration-cache-report.html
[D] build/reports/configuration-cache/7e7ygw0a3mah8eeu57mu8hftv
[D] build/reports/configuration-cache/7e7ygw0a3mah8eeu57mu8hftv/ajs2j47a0ud628nn90dg63rpo
[F] build/reports/configuration-cache/7e7ygw0a3mah8eeu57mu8hftv/ajs2j47a0ud628nn90dg63rpo/configuration-cache-report.html
[D] build/reports/configuration-cache/9uin68ty2687dlw23u831jqht
[D] build/reports/configuration-cache/9uin68ty2687dlw23u831jqht/63ax3m7bt3zvnpahzgt0zv5e6
[F] build/reports/configuration-cache/9uin68ty2687dlw23u831jqht/63ax3m7bt3zvnpahzgt0zv5e6/configuration-cache-report.html
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/1dacz9zvcmarvdv2rb6fo22md
[F] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/1dacz9zvcmarvdv2rb6fo22md/configuration-cache-report.html
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/3fbi70ayfbbmi9fe1os5gatl
[F] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/3fbi70ayfbbmi9fe1os5gatl/configuration-cache-report.html
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/572uhzjmv04be18s955up7ddx
[F] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/572uhzjmv04be18s955up7ddx/configuration-cache-report.html
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/5g37dv8wj2fvph6qva12fpwiv
[F] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/5g37dv8wj2fvph6qva12fpwiv/configuration-cache-report.html
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/5h8oclz5jo9rs6kx0b85a9wwp
[F] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/5h8oclz5jo9rs6kx0b85a9wwp/configuration-cache-report.html
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/5uso4xn1vmdlprbcjag5i20qk
[F] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/5uso4xn1vmdlprbcjag5i20qk/configuration-cache-report.html
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/a6wzsgpjolfm2wealnk5gj95p
[F] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/a6wzsgpjolfm2wealnk5gj95p/configuration-cache-report.html
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/bp2b5pj4a2cjosndbav1svjjv
[F] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/bp2b5pj4a2cjosndbav1svjjv/configuration-cache-report.html
[D] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/d9xuucut7qu1j760wcdo9mnzt
[F] build/reports/configuration-cache/a6p4gjizj8qh8pc94batotgg/d9xuucut7qu1j760wcdo9mnzt/configuration-cache-report.html
[D] build/reports/configuration-cache/bof6uceqtadfaclrt1xq9ku3g
[D] build/reports/configuration-cache/bof6uceqtadfaclrt1xq9ku3g/6cxnqkst5qt2gfh25lo8ohtcn
[F] build/reports/configuration-cache/bof6uceqtadfaclrt1xq9ku3g/6cxnqkst5qt2gfh25lo8ohtcn/configuration-cache-report.html
[D] build/reports/configuration-cache/ckylic1x5xibs6zdgx6sxadb5
[D] build/reports/configuration-cache/ckylic1x5xibs6zdgx6sxadb5/5pxwfku7jx4kp0j0i339ex4td
[F] build/reports/configuration-cache/ckylic1x5xibs6zdgx6sxadb5/5pxwfku7jx4kp0j0i339ex4td/configuration-cache-report.html
[D] build/reports/configuration-cache/cxaekdylcei0oulnvpjr415zf
[D] build/reports/configuration-cache/cxaekdylcei0oulnvpjr415zf/4vs9i5hieetcgnkt98dnovl5r
[F] build/reports/configuration-cache/cxaekdylcei0oulnvpjr415zf/4vs9i5hieetcgnkt98dnovl5r/configuration-cache-report.html
[D] build/reports/configuration-cache/dnnd4xtulqr3p2389lgrgb9w
[D] build/reports/configuration-cache/dnnd4xtulqr3p2389lgrgb9w/3st4pa7g9zly19gegk2i8xgg4
[F] build/reports/configuration-cache/dnnd4xtulqr3p2389lgrgb9w/3st4pa7g9zly19gegk2i8xgg4/configuration-cache-report.html
[D] build/reports/configuration-cache/dua1n31fh3my0049aggxh66u7
[D] build/reports/configuration-cache/dua1n31fh3my0049aggxh66u7/bnaxqgqcndc3du2ctsh4qpb65
[F] build/reports/configuration-cache/dua1n31fh3my0049aggxh66u7/bnaxqgqcndc3du2ctsh4qpb65/configuration-cache-report.html
[D] build/reports/configuration-cache/dua1n31fh3my0049aggxh66u7/d0grlffzn2do4ygaulfseond7
[F] build/reports/configuration-cache/dua1n31fh3my0049aggxh66u7/d0grlffzn2do4ygaulfseond7/configuration-cache-report.html
[D] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws
[D] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/6it9yv2aew1svcpk3qpg3kbar
[F] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/6it9yv2aew1svcpk3qpg3kbar/configuration-cache-report.html
[D] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/6vqf1c5tquxbkv4twad59jyoi
[F] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/6vqf1c5tquxbkv4twad59jyoi/configuration-cache-report.html
[D] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/7wefrzvxr4r6ipf21kpt02673
[F] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/7wefrzvxr4r6ipf21kpt02673/configuration-cache-report.html
[D] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/dot29k30gbealqj38bmb68wlk
[F] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/dot29k30gbealqj38bmb68wlk/configuration-cache-report.html
[D] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/ez27dbir88mj9jg0t5amkm30t
[F] build/reports/configuration-cache/e9ik552r7mq4e6x98tgsvqcws/ez27dbir88mj9jg0t5amkm30t/configuration-cache-report.html
[D] build/reports/configuration-cache/egr6otipe4zalq8ex5iw8tybz
[D] build/reports/configuration-cache/egr6otipe4zalq8ex5iw8tybz/5pjnngjtjraefsgyh2e5qlepk
[F] build/reports/configuration-cache/egr6otipe4zalq8ex5iw8tybz/5pjnngjtjraefsgyh2e5qlepk/configuration-cache-report.html
[D] build/reports/configuration-cache/enfb4ls1qcrt2mh3i8r0vmjfy
[D] build/reports/configuration-cache/enfb4ls1qcrt2mh3i8r0vmjfy/599l7io08p5rvnd357lfky3mq
[F] build/reports/configuration-cache/enfb4ls1qcrt2mh3i8r0vmjfy/599l7io08p5rvnd357lfky3mq/configuration-cache-report.html
[D] build/reports/configuration-cache/l66zw88h57n5q4ewtati795x
[D] build/reports/configuration-cache/l66zw88h57n5q4ewtati795x/4ubtkerpct3hch2f008vkrvh4
[F] build/reports/configuration-cache/l66zw88h57n5q4ewtati795x/4ubtkerpct3hch2f008vkrvh4/configuration-cache-report.html
[D] build/reports/configuration-cache/ph1c3xmsbz1aaw4qi8tkwkze
[D] build/reports/configuration-cache/ph1c3xmsbz1aaw4qi8tkwkze/74ibmw0h78ipu025fnnvba9gk
[F] build/reports/configuration-cache/ph1c3xmsbz1aaw4qi8tkwkze/74ibmw0h78ipu025fnnvba9gk/configuration-cache-report.html
[D] build/reports/configuration-cache/xz1kgkw1vp4iusw1ixfms89l
[D] build/reports/configuration-cache/xz1kgkw1vp4iusw1ixfms89l/3r4skn0j7olahl68sj3p9c8fj
[F] build/reports/configuration-cache/xz1kgkw1vp4iusw1ixfms89l/3r4skn0j7olahl68sj3p9c8fj/configuration-cache-report.html
[D] build/reports/configuration-cache/xz1kgkw1vp4iusw1ixfms89l/4deyzkvmuf19828x3hxkkcy51
[F] build/reports/configuration-cache/xz1kgkw1vp4iusw1ixfms89l/4deyzkvmuf19828x3hxkkcy51/configuration-cache-report.html
[D] docs
[F] docs/data-sources.md
[F] docs/needs-you.md
[F] docs/open-questions.md
[F] docs/phase0-findings.md
[F] docs/scheduler.md
[D] dumps
[F] dumps/teams_failure-20261008-210646.xml
[D] fixtures
[F] fixtures/anki_decks.json
[F] fixtures/home_page2_ui.xml
[F] fixtures/powerplanner_agenda.json
[F] fixtures/powerplanner_agenda_ui.xml
[F] fixtures/README.md
[F] fixtures/teams_widget_state.json
[D] gradle
[F] gradle.properties
[F] gradle/libs.versions.toml
[D] gradle/wrapper
[F] gradle/wrapper/gradle-wrapper.jar
[F] gradle/wrapper/gradle-wrapper.properties
[F] gradlew
[F] gradlew.bat
[F] PLAN.md
[D] private
[F] private/ai-eval-answers.json
[F] private/ai-prompts-2026-10-09.json
[D] private/device
[F] private/device/log-after-restore.json
[F] private/device/log-before-restore.json
[F] private/device/protection-armed.png
[F] private/device/runtime-before-1.1.1.json
[F] private/device/runtime-before-claude.json
[F] private/device/screen-after-split.png
[F] private/device/settings-after-restore.json
[F] private/device/settings-before-1.1.1.json
[F] private/device/settings-before-restore.json
[F] private/device/tasks-after-1.1.1.json
[F] private/device/tasks-after-claude.json
[F] private/device/tasks-before-1.1.1.json
[F] private/device/tasks-now.json
[F] private/gmail_probe.json
[D] private/plan-check
[F] private/plan-check/result.txt
[F] private/plan-check/settings.json
[F] private/plan-check/tasks.json
[F] private/powerplanner_agenda.json
[F] private/q12-eval-2026-10-09.json
[F] private/tasks-2026-10-09.json
[F] private/tasks_snapshot.json
[F] README.md
[D] scripts
[F] scripts/gmail_probe.py
[F] scripts/load_credentials.py
[F] scripts/powerplanner_probe.py
[F] scripts/pull_teams_state.ps1
[F] settings.gradle.kts
```

</details>

## Preservation

- Final comparison checked **all 3,938 original files outside `docs/review/`**: **0 byte changes (SHA-256), 0 missing files, 0 new files, and 0 size/last-write-time changes**. The **874-directory** set is also unchanged. After temporary review-output cleanup, file membership and metadata were checked again.
- Git HEAD remains `d1eefaa9948ed7ccb9d814c3113a104fa5fc4bec`; there were no pre-existing local changes to preserve. The final Git difference is limited to the three new review documents. The comparison covers ignored generated/private files as well as tracked source, so unchanged Git status alone is not the evidence for preservation.

Final delivery consists only of:

- `docs/review/README.md`
- `docs/review/BUGS-AND-INCONSISTENCIES.md`
- `docs/review/IMPROVEMENTS.md`

No commits, staging, branch changes, resets, fixes, formatting, dependency updates or existing-test modifications were performed. Review-only experiments were placed under `docs/review/` and their reproducible contents follow. The temporary `PROGRESS.md` is removed after completion.

## Reproducing the isolated probes

The exact transient files are embedded here so the final directory needs only three documents. To rerun, save each block at its stated path under `docs/review/`, then run the two Gradle commands in Verification. The baseline command omits `-PreviewProbes`, so it excludes these extra sources. The probe command uses existing JUnit/Kotlin dependencies; no new library installation is needed. Reproduction creates temporary files and outputs under the review directory and does not require production/build-script edits. Keep the expected assertion convention in mind: a passing observation test confirms the current defect; the two failing session tests state desired behavior.

<details>
<summary>docs/review/review.init.gradle</summary>

Save as `docs/review/review.init.gradle`.

```groovy
gradle.beforeProject { p ->
    p.layout.buildDirectory.set(new File(p.rootDir, 'docs/review/check-output/' + (p.path == ':' ? 'root' : p.path.substring(1).replace(':', '/'))))
    if (p.hasProperty('reviewProbes')) {
        p.plugins.withId('com.android.application') {
            p.android.sourceSets.test.kotlin.directories.add(new File(p.rootDir, 'docs/review/probes').absolutePath)
        }
    }
    p.tasks.withType(Test).configureEach {
        def scratch = new File(p.rootDir, 'docs/review/test-tmp')
        scratch.mkdirs()
        systemProperty 'java.io.tmpdir', scratch.absolutePath
    }
}
```

</details>
<details>
<summary>docs/review/probes/BlockingReviewProbe.kt</summary>

Save as `docs/review/probes/BlockingReviewProbe.kt`.

```kotlin
package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class BlockingReviewProbe {
    private val dir = Files.createTempDirectory("review-focus").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-10T12:00"), Fixtures.LONDON)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState)
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings)
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState)
    private val log = JsonStore(File(dir, "log.json"), ActivityLog.serializer(), ::ActivityLog)
    private val focus = Focus(tasks, settings, runtime, log, clock) { state, s, now -> Planner.plan(Planner.Input(state.tasks, now, Fixtures.LONDON, s)) }

    private fun task(id: String, steps: List<SubStep> = emptyList()) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id,
        kind = Kind.Homework, sourceEffortMin = 90, subSteps = steps,
        firstSeenAt = clock.time, lastSeenAt = clock.time,
    )

    @Test
    fun `concurrent session starts preserve the displaced session record`() = runTest {
        try {
            tasks.update { it.copy(tasks = listOf(task("a"), task("b"))) }
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = async(Dispatchers.IO) {
                runtime.update {
                    held.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    it
                }
            }
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val first = async(start = CoroutineStart.UNDISPATCHED) { focus.startSession("teams:a", "a", null, 30) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { focus.startSession("teams:b", "b", null, 30) }
            release.countDown()
            holder.await()
            listOf(first, second).awaitAll()
            assertEquals(1, log.value.sessions.size, "Two successful starts must leave one running session and a record of the displaced one")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a session for the later same-title step completes that step`() = runTest {
        try {
            val assignment = task("a", listOf(SubStep("Practice", 30, ankiSections = listOf("2.2")), SubStep("Practice", 60)))
            val deck = TaskItem(
                id = "anki:deck:1", source = Source.Anki, sourceId = "deck:1", title = "Learn Anki deck 2.2",
                kind = Kind.Homework, derived = true, status = Status.Done,
                firstSeenAt = clock.time, lastSeenAt = clock.time,
                extra = mapOf("deckName" to "Textbook 1::2.2", "for" to "teams:a"),
            )
            tasks.update { it.copy(tasks = listOf(assignment, deck)) }
            val next = requireNotNull(focus.plan().next)
            assertEquals("teams:a", next.taskId)
            assertEquals(60, next.minutes)
            focus.startSession(next.taskId, next.label, next.step, next.minutes, next.box)
            clock.time += 60 * 60_000L
            focus.stopSession()
            assertEquals(listOf(false, true), tasks.value.tasks.first { it.id == "teams:a" }.subSteps.map { it.done })
        } finally {
            dir.deleteRecursively()
        }
    }
}
```

</details>
<details>
<summary>docs/review/probes/DataAiReviewProbe.kt</summary>

Save as `docs/review/probes/DataAiReviewProbe.kt`.

```kotlin
package com.thomaswcode.decrastination.review

import com.thomaswcode.decrastination.core.*
import com.thomaswcode.decrastination.data.*
import com.thomaswcode.decrastination.enrich.Answers
import com.thomaswcode.decrastination.enrich.ModelAlerts
import com.thomaswcode.decrastination.learn.Calibrator
import com.thomaswcode.decrastination.learn.Stats
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DataAiReviewProbe {
    private val zone = ZoneId.of("Europe/London")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-10-10T12:00")
    private fun task() = TaskItem("powerplanner:t", Source.PowerPlanner, "t", "Essay", kind = Kind.Homework,
        sourceEffortMin = 60, dueAt = at("2026-10-20T09:00"), firstSeenAt = now, lastSeenAt = now)

    @Test
    fun `source progress is ignored once an item has substeps`() {
        val plain = task().copy(sourceProgress = 0.5, firstProgress = 0.0)
        val split = plain.copy(subSteps = listOf(SubStep("Draft", 30), SubStep("Edit", 30)))
        fun planned(t: TaskItem) = Planner.plan(Planner.Input(listOf(t), now, zone, Settings())).ordered.sumOf { it.minutes }
        assertEquals(30, planned(plain))
        assertEquals(60, planned(split))
    }

    @Test
    fun `shrinking Anki source steps double deduct old timed minutes`() {
        fun deck(steps: List<SubStep>) = Fetched("deck:1", "Learn Anki deck 1.2", Kind.Homework,
            dueAt = at("2026-10-20T09:00"), sourceEffortMin = steps.sumOf { it.minutes },
            derived = true, subSteps = steps, stepsPerDay = 1)
        val initial = deck(listOf(SubStep("20 new cards", 9), SubStep("20 new cards", 9), SubStep("5 new cards", 3)))
        val stored = Merge.apply(emptyList(), Source.Anki, listOf(initial), now).tasks.single()
        val timed = stored.copy(workedMin = 9, subSteps = stored.subSteps.mapIndexed { i, step -> step.copy(done = i == 0) })
        val remaining = deck(listOf(SubStep("20 new cards", 9), SubStep("5 new cards", 3)))
        val synced = Merge.apply(listOf(timed), Source.Anki, listOf(remaining), now + 1).tasks.single()
        assertEquals(12, synced.subSteps.sumOf { it.minutes })
        assertEquals(9, synced.workedMin)
        val planned = Planner.plan(Planner.Input(listOf(synced), now + 1, zone, Settings()))
        assertEquals(8, planned.ordered.sumOf { it.minutes })
    }

    @Test
    fun `a completion with partly photographed work trains from its timed portion only`() {
        val record = CompletionRecord("teams:t", "Essay", Source.Teams, Kind.Homework,
            estimateMin = 40, workedMin = 5, firstSeenAt = now, doneAt = now + 1, byHand = false)
        assertEquals(0.7375, Calibrator.multipliers(listOf(record)).getValue("Homework|"), 1e-9)
    }

    @Test
    fun `backup admits negative work that crashes statistics after merge`() {
        val bad = Backup(exportedAt = now, settings = Settings(briefingWeekdayMin = -1), log = ActivityLog(
            sessions = listOf(SessionRecord("teams:t", Kind.Homework, label = "Essay", plannedMin = 30,
                workedMin = -1, startedAt = now, endedAt = now, completed = false))))
        val decoded = assertNotNull(Backups.decode(Backups.encode(bad)))
        assertEquals(-1, decoded.settings.briefingWeekdayMin)
        val merged = Backups.mergeLog(ActivityLog(), decoded.log, now)
        assertFailsWith<IllegalArgumentException> { Stats.summary(merged, now, zone) }
    }

    @Test
    fun `rejected email blocks can hide the whole task and its dropped warning`() {
        val email = task().copy(id = "gmail:t", source = Source.Gmail, sourceValues = SourceValues(Kind.Admin))
        val answer = """{"kind":"Info","actionableFrom":null,"deadline":null,"effortMin":60,"nextStep":"Apply","blocks":[{"title":"Apply","minutes":60,"from":"2026-11-20","due":"2026-10-30"}]}"""
        val enrichment = assertNotNull(Answers.parse(Enrichments.Job.Email, answer, email, "model", now, zone))
        val enriched = email.withEnrichment(enrichment)
        assertNotNull(enrichment.dropped)
        assertEquals(true, enriched.hidden)
        assertEquals(emptyList(), ModelAlerts.standing(listOf(enriched)))
        assertEquals(emptyList(), Planner.plan(Planner.Input(listOf(enriched), now, zone, Settings())).ordered)
    }

    @Test
    fun `a task disappears from a plan with enough total time but shorter daily limits`() {
        val today = Planner.date(now, zone)
        val limits = (0L..90L).associate { today.plusDays(it) to 20 }
        val plan = Planner.plan(Planner.Input(listOf(task()), now, zone, Settings(), dayCaps = limits))
        assertEquals(emptyList(), plan.ordered)
        assertEquals(false, plan.pressure)
        assertEquals(null, plan.next)
        assertEquals(true, plan.buckets.sumOf { it.capacityMin } >= 60)
    }
}
```

</details>
<details>
<summary>docs/review/probes/SourcesReviewProbe.kt</summary>

Save as `docs/review/probes/SourcesReviewProbe.kt`.

```kotlin
package com.thomaswcode.decrastination.review

import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.HttpResponse
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import com.thomaswcode.decrastination.sources.gmail.EmailRules
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sources.gmail.InboxMessage
import com.thomaswcode.decrastination.sources.gmail.Mime
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerApi
import com.thomaswcode.decrastination.sources.teams.TeamsRows
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourcesReviewProbe {
    private val zone = ZoneId.of("Europe/London")
    private val now = ZonedDateTime.parse("2026-10-10T12:00:00+01:00[Europe/London]").toInstant().toEpochMilli()

    @Test
    fun malformedPowerPlannerSuccessFinishesExistingTask() {
        var body = """{"Items":[{"Identifier":"task","Name":"Work","ItemType":5}]}"""
        val api = PowerPlannerApi(Http { _, _, _ -> HttpResponse(200, body) })
        val login = PowerPlannerApi.Login(1, "review", "dummy")
        val stored = Merge.apply(emptyList(), Source.PowerPlanner, listOf(com.thomaswcode.decrastination.core.Fetched("task", "Work", Kind.Homework)), now).tasks
        assertEquals(1, api.agenda(login, "semester", "2026-10-10T11:00:00Z").size)
        body = "{}"
        val absent = api.agenda(login, "semester", "2026-10-10T11:00:00Z")
        assertTrue(absent.isEmpty())
        val merged = Merge.apply(stored, Source.PowerPlanner, emptyList(), now + 1)
        assertEquals(Status.Done, merged.tasks.single().status)
        assertEquals(1, merged.completed.size)
    }

    @Test
    fun skippedTeamsIdentityCompletesExistingAssignment() {
        val valid = TeamsRows.fetched(mapOf("key" to "task", "title" to "Work"))!!
        val stored = Merge.apply(emptyList(), Source.Teams, listOf(valid), now).tasks
        val rows = listOf(mapOf("title" to "Work"))
        val merged = Merge.apply(stored, Source.Teams, rows.mapNotNull(TeamsRows::fetched), now + 1)
        assertEquals(Status.Done, merged.tasks.single().status)
    }

    @Test
    fun registrationRequestIsHiddenAsEventWithoutModel() {
        val message = InboxMessage(1, "message", "thread", now, "Registration required", "Admissions", "admissions@example.com", false)
        val tasks = GmailThreads.fetched(listOf(message), mapOf("message" to GmailThreads.Body("Please complete registration by 12 October 2026.")), now, zone)
        val stored = Merge.apply(emptyList(), Source.Gmail, tasks, now).tasks.single()
        assertEquals(Kind.Event, stored.kind)
        assertTrue(stored.hidden)
    }

    @Test
    fun automatedActionRequestIsHiddenAsInformation() {
        val triage = EmailRules.triage(EmailRules.Email("School", "noreply@example.com", "Action required", "Please return the consent form tomorrow.", false), now, zone)
        assertEquals(Kind.Info, triage.kind)
    }

    @Test
    fun ankiRolloverMovesAtDaylightSavingTransitions() {
        val autumn = ZonedDateTime.parse("2026-10-25T03:30:00Z[Europe/London]").toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 25), AnkiRules.ankiDay(autumn, zone))
        val spring = ZonedDateTime.parse("2026-03-29T04:30:00+01:00[Europe/London]").toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 3, 28), AnkiRules.ankiDay(spring, zone))
        assertTrue(AnkiRules.nextRollover(spring, zone) < spring)
    }

    @Test
    fun htmlCutAtByteLimitIsRememberedAsCompletelyRead() {
        val complete = "<style>" + " ".repeat(200_000) + "</style><p>Please submit the form by 12 October.</p>"
        val partial = complete.toByteArray().copyOf(200_000)
        val decoded = Mime.tidy(Mime.decode(partial, Mime.TextPart("1", "html", "7bit", "utf-8")), GmailThreads.MAX_BODY_CHARS)
        val cached = GmailThreads.withRead(emptyMap(), mapOf("message" to decoded))
        val message = InboxMessage(1, "message", "thread", now, "Form", null, "a@example.com", false)
        assertEquals("", cached.getValue("message").text)
        assertTrue(cached.getValue("message").whole)
        assertTrue(GmailThreads.toRead(listOf(message), cached, 60).isEmpty())
    }
}
```

</details>
