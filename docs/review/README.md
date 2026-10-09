# Repository review

Review date: **9 October 2026**. Reviewed default branch: `main`, commit [`a89c7007d71b13bcf997cd700a70881fd3c2ac6d`](https://github.com/ThomasWCode/Decrastination/commit/a89c7007d71b13bcf997cd700a70881fd3c2ac6d). Review branch: `docs/repo-review-2026-10-09`.

This review documents **31 deduplicated findings: 6 P1, 20 P2, 5 P3**. It changes documentation only and implements no fixes.

## Scope and method

The inventory contains **149 tracked files and 55 tracked subdirectories** at the reviewed commit. All **143 non-generated, non-binary, non-vendored files were read in full**, including all application Kotlin, tests, scripts, manifests, build/CI configuration, resources, Markdown and JSON fixtures. Six generated/binary files are enumerated below with reasons; the keystore's identity and generated UI labels were also inspected. Untracked build products, downloaded dependencies and the provisioned SDK/JDK are outside the repository source inventory.

The review proceeded module by module with a root-level `PROGRESS.md` ledger, followed by cross-checks of callers, state transitions, tests, docs and configuration. The ledger was removed before delivery. Findings cover correctness, error handling, concurrency, input validation, authentication/authorization, secrets, data integrity, performance, resource handling, tests, API/naming consistency, dependencies/build and maintainability. Recommendations are proposals only. Duplicate manifestations of one cause are combined, including both persistence stores, incomplete source adapters, and hard-coded configuration explanations. Distinct acquisition, scheduling and accounting failures remain separate.

Suspected defects were exercised against the actual compiled application classes where feasible using temporary harnesses outside the checkout. Network reproductions used loopback servers and synthetic credentials; no real accounts, inboxes, model APIs or device settings were accessed. A second evidence pass checked important findings, source excerpts and limits. Published dependency-advisory matches were checked separately from application reachability.

**Status:** “Verified” means the stated failure or a clearly identified sub-path was reproduced/confirmed by a run; each entry names what was and was not run. “Confirmed by reading” means the code establishes the behavior but runtime/device validation remains unavailable. No unvalidated candidate is promoted to a demonstrated end-to-end device exploit. No finding currently uses “Suspected”.

**Priorities:** P1 is a likely-use incorrect result, data loss/corruption, vulnerability, crash or build/deploy failure to address before the next release. P2 requires particular conditions or has a workaround, or is a significant inconsistency/critical test gap. P3 is a lower-impact quality, accessibility, documentation or maintainability improvement. Higher choices at a boundary are explained in the finding.

## Repository and runtime map

- **Application:** one Kotlin Android module, `:app`, namespace/application ID `com.thomaswcode.decrastination`; Compose UI, Glance widget, WorkManager jobs; Java 17 source/target, min SDK 26, compile/target SDK 36.
- **Entry points:** `DecrastinationApp.onCreate` builds `AppGraph`; launcher `ui/MainActivity`; `block/FocusService` is system-bound accessibility; manifest receivers handle sessions, daily reminders, assessments, event answers, watchdog/boot and widgets. `debug.Command` is an exported activity alias requiring `android.permission.DUMP`; the actual debug activity is not exported. Manifest non-exported activities handle task opening, settings, protection and check-ins.
- **Modules:** core planning/merge/enrichment models; atomic JSON/encrypted secrets stores; source adapters for Teams provider, Anki provider, Power Planner HTTPS and Gmail IMAP; sync workers; focus/block/credit/session controls; delayed settings and parent TOTP protection; optional Claude enrichment/photo/review; calendar/learning/statistics; UI, notifications and widget.
- **Languages/formats:** Kotlin and Gradle Kotlin DSL; Python probes/credential loader; PowerShell capture script; XML Android resources and generated snapshots; JSON fixtures; TOML dependency catalog; YAML CI; Markdown docs; generated shell/batch wrapper launchers.
- **Build/dependency manifests:** `settings.gradle.kts`, root and app `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`. Gradle 9.3.1, AGP 9.0.1, Kotlin/Compose plugin 2.2.10. No dependency lockfile, Python dependency manifest, separate type-checker, ktlint or detekt configuration is tracked. Kotlin compilation supplies the configured type checking; Android lint is the configured linter.
- **CI:** `.github/workflows/ci.yml` uses JDK 17, compiles Python scripts, runs debug JVM tests and lint, assembles the debug APK, and uploads reports/APK. It has no instrumented/emulator test job. Debug signing uses the committed key; the documented distribution is sideloaded debug APKs. No deployment was performed.

## Commands run and results

Commands ran at the reviewed commit in `/workspace/Decrastination`, using provisioned JDK **17.0.20.1**, Android SDK 36 and Python **3.12.14**. Environment selection used `source /workspace/.cloud-setup/activate.sh`; this selects the toolchain and configured proxy/trust, not repository changes.

| Command/check | Result |
|---|---|
| `git ls-files`, `git ls-files`-derived parent directories; default branch lookup; `git fetch origin main`; `git rev-parse HEAD` | 149 tracked files, 55 subdirectories (plus the repository root); `main` at the SHA above. Clean starting tree. No `AGENTS.md` or `SECURITY.md` found. |
| `./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` | Passed; most tasks reused existing outputs, so a fresh run followed. |
| `./gradlew testDebugUnitTest lintDebug assembleDebug --rerun-tasks --no-build-cache --console=plain` | **Passed**, 53 tasks executed, 1m 52s. **386 tests in 41 suites; 0 failures, 0 errors, 0 skipped.** Android lint: **No issues found.** Debug APK built. Gradle reused its configuration cache only. |
| Fresh build diagnostic | `libandroidx.graphics.path.so` could not be stripped and was packaged as supplied. Build succeeded; no runtime failure inferred from this diagnostic. |
| `python3 -m py_compile scripts/*.py` | Passed. This is syntax/bytecode compilation, not live service or credential loading. |
| `./gradlew -I /workspace/work/repo-review/review.init.gradle :app:reviewClasspath --no-configuration-cache --console=plain` | Passed. An external init script captured the actual unit-test classpath and 121 unique debug runtime dependency coordinates; no project build file was changed. |
| POST `https://api.osv.dev/v1/querybatch` with those Maven names/versions; GET each matched advisory | 20 IDs on 5 dependency coordinates; none of the downloaded records withdrawn. See P2-019 for all IDs, patch floors and reachability limits. Build-tool/test-only dependencies and native binary internals were not exhaustively vulnerability-audited. |
| `keytool -list -keystore app/debug.keystore -storepass android -alias androiddebugkey`; `apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk` | Private-key entry readable using the repository's documented password; fresh APK certificate matched. No private key bytes exported. |
| `adb devices -l`; PowerShell executable lookup | No attached device/emulator; neither `pwsh` nor `powershell` installed. Device-only and Windows PowerShell execution limitations apply below. |

Temporary verification files were kept in `/workspace/work/repo-review/`, outside the checkout, and are not included in the PR. The following records give their entry commands or classpath command forms and observed results; findings provide concrete inputs/sequences for reproducing them without those temporary files.

| Targeted verification | Command / result |
|---|---|
| Stores, Anki accounting, backup validation | External Kotlin `CoreDataHarness.kt`, compiled with cached `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` and the actual test classpath, then `java -cp <harness>:<test-classpath> CoreDataHarnessKt`: passed all assertions. Both stores lost committed values on the forced cancellation interleaving; deck steps 9+3 became 5+3, quota 10 became 5; accepted `ankiDeadlineMin=-1` caused an Anki `DateTimeException`. |
| Session identity/concurrency | `python /workspace/work/repo-review/blocking-harness/run.py`: passed. Real answer parsing accepted duplicate titles; finishing the original session ticked another block. Old completion returned `stopped=true` while the replacement session was active. Framework alarm effects remain confirmed by reading. |
| UI state and tab sink | `python /workspace/work/repo-review/ui-widget/run_probes.py`: passed, including independent repeat. Unrelated cap edit reverted box 30 to 45; 2-day task with 1-day soft deadline said “Waiting a week”; pinned Material3 indicator threw for index 4/four tabs. Not a full Activity launch. |
| Enrichment/learning | `python3 /workspace/work/repo-review/enrich-learn/run-probe.py`: passed. Mixed photo/timer multiplier 0.85; delayed review shifted week and suppressed next Sunday; coaching appointment became free; unbounded prompt plus declared pricing exceeded reserve; actual SDK/loopback response dropped after cancellation. Token usage was simulated, with no real bill. |
| Source adapters | External Java harnesses `SourceChecks`, `LifecycleChecks`, `HtmlCapCheck`, compiled/run with the actual app/test classpath: reproduced empty malformed agenda, archive-before-body loss, empty-body caching, note/card mismatch and both London DST transitions. LifecycleChecks and HtmlCapCheck completed successfully; SourceChecks recorded its initial cases before the intentional timeout below. |
| HTML complexity | `timeout 30s java -cp <harness>:<test-classpath> SourceChecks`: initial scaling run reached timeout while attempting 200k input after smaller cases. Separate `timeout 95s java -cp <harness>:<test-classpath> HtmlCapCheck` completed a permitted 200,000-character input in **59,348 ms**. Exact host measurements and limits are in P1-005. |
| Gmail probe TLS | `python3 /workspace/work/repo-review/protect/probe_checks.py`: passed, invoking the existing probe with synthetic credentials against a loopback self-signed, wrong-host TLS certificate; server received LOGIN. |
| Guard locale | Provisioned JDK `javac` then `java ... GuardLocaleCheck`: English synthetic app-info returned Back; localized synthetic app-info/time screens returned Leave. The first unqualified `javac` attempt was unavailable on PATH; rerun with the provisioned JDK succeeded. No handset bypass executed. |

There is no separate configured type-check/lint task beyond Kotlin compilation and Android lint. The debug variant matches all documented/CI checks. Release installation, real integrations, paid calls and deployment were not attempted. No source/config/test/lockfile was edited to make checks pass.

## Findings summary

| ID | Title | Priority | Status | Primary location |
|---|---|---|---|---|
| [P1-001](P1.md#p1-001) | Committed private signing key defeats the signature trust boundary | P1 | Verified | [app/build.gradle.kts:24](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/build.gradle.kts#L24) |
| [P1-002](P1.md#p1-002) | Gmail probe sends the app password without authenticating the TLS server | P1 | Verified | [scripts/gmail_probe.py:117](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/scripts/gmail_probe.py#L117) |
| [P1-003](P1.md#p1-003) | Cancelling a store write leaves committed disk state and live state inconsistent | P1 | Verified | [data/JsonStore.kt:42](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt#L42) |
| [P1-004](P1.md#p1-004) | Incomplete source snapshots can falsely finish or discard tracked work | P1 | Verified | [sources/powerplanner/PowerPlannerApi.kt:45](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt#L45) |
| [P1-005](P1.md#p1-005) | HTML email stripping permits quadratic CPU exhaustion | P1 | Verified | [sources/gmail/Mime.kt:110](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Mime.kt#L110) |
| [P1-006](P1.md#p1-006) | Timed Anki work is deducted again after the source counts shrink | P1 | Verified | [core/Merge.kt:102](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt#L102) |
| [P2-001](P2.md#p2-001) | Changing the system language makes sensitive Settings pages unrecognizable to the guard | P2 | Verified | [protect/GuardRules.kt:41](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt#L41) |
| [P2-002](P2.md#p2-002) | An unchecked exported intent tab index crashes the main screen | P2 | Verified | [app/src/main/AndroidManifest.xml:61](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/AndroidManifest.xml#L61) |
| [P2-003](P2.md#p2-003) | Archiving an email before its deferred body fetch loses its follow-up work | P2 | Verified | [sources/gmail/GmailSource.kt:167](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L167) |
| [P2-004](P2.md#p2-004) | A transient missing Gmail body is cached permanently as empty text | P2 | Verified | [sources/gmail/GmailSource.kt:185](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L185) |
| [P2-005](P2.md#p2-005) | Finishing a session can complete another block with the same title | P2 | Verified | [block/Focus.kt:216](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt#L216) |
| [P2-006](P2.md#p2-006) | Source completion can cancel a replacement session's alarm and notification | P2 | Confirmed by reading | [AppGraph.kt:350](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt#L350) |
| [P2-007](P2.md#p2-007) | Service reconnection leaves deferred checks stuck for that instance | P2 | Confirmed by reading | [block/FocusService.kt:329](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt#L329) |
| [P2-008](P2.md#p2-008) | Settings saves overwrite concurrent changes to unrelated fields | P2 | Verified | [ui/SettingsActivity.kt:68](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L68) |
| [P2-009](P2.md#p2-009) | Backup import accepts out-of-range values that bypass settings validation | P2 | Verified | [data/Backup.kt:49](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt#L49) |
| [P2-010](P2.md#p2-010) | Cancelling a model call can discard its billable usage | P2 | Verified | [enrich/ClaudeReviewer.kt:44](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt#L44) |
| [P2-011](P2.md#p2-011) | Fixed request allowance does not bound the monthly model cost | P2 | Verified | [enrich/AiUsage.kt:29](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt#L29) |
| [P2-012](P2.md#p2-012) | Anki homework plans confuse note counts with card counts | P2 | Verified | [sources/anki/AnkiSource.kt:39](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt#L39) |
| [P2-013](P2.md#p2-013) | Anki's 04:00 day boundary shifts on daylight-saving transitions | P2 | Verified | [sources/anki/AnkiRules.kt:74](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt#L74) |
| [P2-014](P2.md#p2-014) | Photo-completed work is treated as fully timed when learning estimates | P2 | Verified | [block/Focus.kt:474](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt#L474) |
| [P2-015](P2.md#p2-015) | Delayed weekly jobs change their target week and suppress the next review | P2 | Verified | [learn/ReviewWorker.kt:48](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/ReviewWorker.kt#L48) |
| [P2-016](P2.md#p2-016) | Ambiguous transport words misclassify busy appointments as free time | P2 | Verified | [learn/EventJudge.kt:49](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt#L49) |
| [P2-017](P2.md#p2-017) | Scrolling an invalid Settings field off screen can leave Save disabled with no visible error | P2 | Confirmed by reading | [ui/SettingsActivity.kt:72](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L72) |
| [P2-018](P2.md#p2-018) | A failed Teams pull overwrites the last usable fixture before validation | P2 | Confirmed by reading | [scripts/pull_teams_state.ps1:7](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/scripts/pull_teams_state.ps1#L7) |
| [P2-019](P2.md#p2-019) | The shipped dependency graph retains five versions with published security advisories | P2 | Verified | [gradle/libs.versions.toml:17](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/gradle/libs.versions.toml#L17) |
| [P2-020](P2.md#p2-020) | Critical Android lifecycle and permission paths have no automated integration coverage | P2 | Confirmed by reading | [app/build.gradle.kts:58](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/build.gradle.kts#L58) |
| [P3-001](P3.md#p3-001) | Rotating during parent-code enrollment silently replaces the QR secret | P3 | Confirmed by reading | [protect/ProtectionActivity.kt:99](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt#L99) |
| [P3-002](P3.md#p3-002) | Settings switches do not expose their labels to accessibility services | P3 | Confirmed by reading | [ui/SettingsActivity.kt:252](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L252) |
| [P3-003](P3.md#p3-003) | Status explanations hard-code configurable deadlines and blocking hours | P3 | Verified | [widget/WidgetModel.kt:77](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt#L77) |
| [P3-004](P3.md#p3-004) | The accessibility disclosure promises no data leaves the phone, but reviews send block counts | P3 | Confirmed by reading | [app/src/main/res/values/strings.xml:12](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/res/values/strings.xml#L12) |
| [P3-005](P3.md#p3-005) | Current reference documents still present superseded contracts as active behavior | P3 | Confirmed by reading | [docs/data-sources.md:57](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/docs/data-sources.md#L57) |

Counts: **P1 6 · P2 20 · P3 5**. Complete locations, excerpts, triggers, effects, recommendations and effort estimates are in the linked priority files.

## Coverage and limitations

| Review group | Files fully read |
|---|---:|
| Core planning/merge and persistence, including tests | 18 |
| Blocking, sessions and policy, including tests | 12 |
| Protection, debug commands and PC scripts, including tests | 12 |
| Source adapters, network and sync, including tests | 22 |
| Enrichment, learning and calendar, including tests | 24 |
| UI, widget and notifications, including tests | 17 |
| Application integration, manifest/resources, build/CI, docs and fixtures | 38 |
| **Unique fully read files** | **143** |
| **Generated/binary files excluded from full source reading** | **6** |

Every eligible tracked file was read; a reviewed file without a finding is not asserted defect-free. Coverage is bounded to this commit. No Android device was available to exercise accessibility events, OEM Settings labels, alarms/notifications, Compose interactions, configuration changes, real ContentProviders or live permission grants. Source/client behavior was reviewed and tested with local inputs; the installed Teams companion, Anki provider implementation and live services were outside this repository. PowerShell was read but not executed. Dependency matching establishes affected versions, not every advisory's exploitability. Generated/binary exclusions follow.

| Skipped file | Reason / limited inspection |
|---|---|
| `app/debug.keystore` | Binary signing keystore; inspected entry type and certificate, not private key bytes. |
| `gradle/wrapper/gradle-wrapper.jar` | Vendored/generated Gradle wrapper binary. |
| `gradlew` | Generated Gradle Unix wrapper launcher. |
| `gradlew.bat` | Generated Gradle Windows wrapper launcher. |
| `fixtures/home_page2_ui.xml` | Generated Android UI hierarchy; parsed and inspected labels, structural boilerplate not line-reviewed. |
| `fixtures/powerplanner_agenda_ui.xml` | Generated Android UI hierarchy; parsed and inspected labels, superseded by reviewed JSON fixture. |

## Complete tracked-file inventory

“Reviewed” means fully read at the reviewed SHA. “Skipped” refers to the explicit reasons above. New review documents and the temporary progress ledger are not part of this baseline inventory.

| File | Coverage |
|---|---|
| `.gitattributes` | Reviewed |
| `.github/workflows/ci.yml` | Reviewed |
| `.gitignore` | Reviewed |
| `PLAN.md` | Reviewed |
| `README.md` | Reviewed |
| `app/build.gradle.kts` | Reviewed |
| `app/debug.keystore` | Skipped; see above |
| `app/lint.xml` | Reviewed |
| `app/src/main/AndroidManifest.xml` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/DecrastinationApp.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/Blocklist.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/CountdownBanner.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/Credit.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/PhotoChecks.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/block/TeamsAutoSync.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/core/Plan.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/core/Task.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/core/WallClock.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/data/DeviceClock.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/data/RuntimeState.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/data/SecretStore.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/data/Settings.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/data/TaskState.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/debug/CommandActivity.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/EnrichWorker.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/Enricher.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/ModelHold.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/PhotoChecker.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Assessment.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Briefing.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/CalendarTime.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/CheckIns.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Daily.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Days.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/ReviewWorker.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/learn/Stats.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/net/Http.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/notify/Channels.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/notify/Notify.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionCheck.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/protect/SettingsChanges.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/protect/Totp.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/protect/Watchdog.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/TaskSource.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/EmailRules.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Imap.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Mime.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItems.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSource.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sources/teams/TeamsSource.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sync/SyncWorker.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/sync/Syncer.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/Format.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/OpenTaskActivity.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/SetupScreen.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/StatsScreen.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/TaskOpener.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/Theme.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/ui/TodayScreen.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/widget/NextWidget.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt` | Reviewed |
| `app/src/main/java/com/thomaswcode/decrastination/widget/WidgetUpdater.kt` | Reviewed |
| `app/src/main/res/drawable/ic_focus.xml` | Reviewed |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Reviewed |
| `app/src/main/res/drawable/ic_refresh.xml` | Reviewed |
| `app/src/main/res/mipmap-anydpi/ic_launcher.xml` | Reviewed |
| `app/src/main/res/values/colors.xml` | Reviewed |
| `app/src/main/res/values/strings.xml` | Reviewed |
| `app/src/main/res/values/themes.xml` | Reviewed |
| `app/src/main/res/xml/backup_rules.xml` | Reviewed |
| `app/src/main/res/xml/data_extraction_rules.xml` | Reviewed |
| `app/src/main/res/xml/device_admin.xml` | Reviewed |
| `app/src/main/res/xml/focus_service_config.xml` | Reviewed |
| `app/src/main/res/xml/next_widget_info.xml` | Reviewed |
| `app/src/main/res/xml/photo_paths.xml` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/Fixtures.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/block/BlockingTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/block/FocusTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/core/EnrichmentTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/core/MergeTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/core/PlannerTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/data/BackupTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/data/StoresTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/enrich/EnrichTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/learn/DailyTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/learn/LearnTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/learn/StatsTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/protect/ProtectTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/sources/anki/AnkiRulesTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/GmailTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/ImapTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/sources/gmail/MimeTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerItemsTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerSourceTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/sources/teams/TeamsRowsTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/sync/SyncerTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/ui/FormatTest.kt` | Reviewed |
| `app/src/test/java/com/thomaswcode/decrastination/widget/WidgetModelTest.kt` | Reviewed |
| `build.gradle.kts` | Reviewed |
| `docs/data-sources.md` | Reviewed |
| `docs/needs-you.md` | Reviewed |
| `docs/open-questions.md` | Reviewed |
| `docs/phase0-findings.md` | Reviewed |
| `docs/scheduler.md` | Reviewed |
| `fixtures/README.md` | Reviewed |
| `fixtures/anki_decks.json` | Reviewed |
| `fixtures/home_page2_ui.xml` | Skipped; see above |
| `fixtures/powerplanner_agenda.json` | Reviewed |
| `fixtures/powerplanner_agenda_ui.xml` | Skipped; see above |
| `fixtures/teams_widget_state.json` | Reviewed |
| `gradle.properties` | Reviewed |
| `gradle/libs.versions.toml` | Reviewed |
| `gradle/wrapper/gradle-wrapper.jar` | Skipped; see above |
| `gradle/wrapper/gradle-wrapper.properties` | Reviewed |
| `gradlew` | Skipped; see above |
| `gradlew.bat` | Skipped; see above |
| `scripts/gmail_probe.py` | Reviewed |
| `scripts/load_credentials.py` | Reviewed |
| `scripts/powerplanner_probe.py` | Reviewed |
| `scripts/pull_teams_state.ps1` | Reviewed |
| `settings.gradle.kts` | Reviewed |

## Complete tracked-directory inventory

Git tracks files rather than empty directories. These are all parent directories represented by tracked files, plus the repository root.

```text
.
.github
.github/workflows
app
app/src
app/src/main
app/src/main/java
app/src/main/java/com
app/src/main/java/com/thomaswcode
app/src/main/java/com/thomaswcode/decrastination
app/src/main/java/com/thomaswcode/decrastination/block
app/src/main/java/com/thomaswcode/decrastination/core
app/src/main/java/com/thomaswcode/decrastination/data
app/src/main/java/com/thomaswcode/decrastination/debug
app/src/main/java/com/thomaswcode/decrastination/enrich
app/src/main/java/com/thomaswcode/decrastination/learn
app/src/main/java/com/thomaswcode/decrastination/net
app/src/main/java/com/thomaswcode/decrastination/notify
app/src/main/java/com/thomaswcode/decrastination/protect
app/src/main/java/com/thomaswcode/decrastination/sources
app/src/main/java/com/thomaswcode/decrastination/sources/anki
app/src/main/java/com/thomaswcode/decrastination/sources/gmail
app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner
app/src/main/java/com/thomaswcode/decrastination/sources/teams
app/src/main/java/com/thomaswcode/decrastination/sync
app/src/main/java/com/thomaswcode/decrastination/ui
app/src/main/java/com/thomaswcode/decrastination/widget
app/src/main/res
app/src/main/res/drawable
app/src/main/res/mipmap-anydpi
app/src/main/res/values
app/src/main/res/xml
app/src/test
app/src/test/java
app/src/test/java/com
app/src/test/java/com/thomaswcode
app/src/test/java/com/thomaswcode/decrastination
app/src/test/java/com/thomaswcode/decrastination/block
app/src/test/java/com/thomaswcode/decrastination/core
app/src/test/java/com/thomaswcode/decrastination/data
app/src/test/java/com/thomaswcode/decrastination/enrich
app/src/test/java/com/thomaswcode/decrastination/learn
app/src/test/java/com/thomaswcode/decrastination/protect
app/src/test/java/com/thomaswcode/decrastination/sources
app/src/test/java/com/thomaswcode/decrastination/sources/anki
app/src/test/java/com/thomaswcode/decrastination/sources/gmail
app/src/test/java/com/thomaswcode/decrastination/sources/powerplanner
app/src/test/java/com/thomaswcode/decrastination/sources/teams
app/src/test/java/com/thomaswcode/decrastination/sync
app/src/test/java/com/thomaswcode/decrastination/ui
app/src/test/java/com/thomaswcode/decrastination/widget
docs
fixtures
gradle
gradle/wrapper
scripts
```

## Systemic themes

- State crosses multiple representations without a durable shared identity or transaction: disk versus flow, source counts versus timed work, and a session's task block versus its title. Cancellation and interleaving expose these mismatches.
- Missing, deferred or malformed external data is sometimes collapsed into a successful empty value. Reconciliation then interprets absence as completed work rather than preserving an uncertain state.
- Tests are strong around pure rules but thin at Android lifecycle boundaries and combined workflows. Cross-module tests and a small component suite would catch several independently reproduced defects.
- Fixed explanatory text, historical contracts and fixed cost assumptions have outlived configurable behavior. Keep these tied to effective settings and enforced input bounds.
