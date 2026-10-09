# Improvements

2 findings covering dependency maintenance and automated integration-test coverage: **2 P2**. These improvements address preventive maintenance and regression protection; the dependency entry retains its explicit limitation that application exploitability has not been established.

Reviewed commit: `a89c7007d71b13bcf997cd700a70881fd3c2ac6d`. See [review scope, verification and full index](README.md). Original finding IDs, priorities, evidence and recommendations are retained. Findings are ordered by priority and impact.

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
