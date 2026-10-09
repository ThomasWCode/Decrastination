# Bugs and inconsistencies

29 findings covering incorrect behavior, security defects, and inconsistencies in interfaces or documentation: **6 P1, 18 P2, 5 P3**.

Reviewed commit: `a89c7007d71b13bcf997cd700a70881fd3c2ac6d`. See [review scope, verification and full index](README.md). Original finding IDs, priorities, evidence and recommendations are retained. Findings are ordered by priority and impact.

<a id="p1-001"></a>

## P1-001 — Committed private signing key defeats the signature trust boundary

- **ID:** P1-001
- **Title:** Committed private signing key defeats the signature trust boundary
- **Priority and category:** P1 — security / signing and authorization.
- **Status:** Verified (the committed keystore opens with the committed password, contains a private-key entry, and its certificate matches the freshly built debug APK; installation of a separate caller on the companion app was not attempted).
- **Locations:**

[app/build.gradle.kts:24-29](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/build.gradle.kts#L24-L29):

```kotlin
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
```

[app/src/main/AndroidManifest.xml:8-13](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/AndroidManifest.xml#L8-L13):

```xml
    <permission
        android:name="com.teamsassignments.widget.permission.READ_ASSIGNMENTS"
        android:description="@string/read_assignments_permission_description"
        android:label="@string/read_assignments_permission_label"
        android:protectionLevel="signature" />
    <uses-permission android:name="com.teamsassignments.widget.permission.READ_ASSIGNMENTS" />
```

[app/build.gradle.kts:33-35](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/build.gradle.kts#L33-L35):

```kotlin
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
```

[README.md:29-33](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/README.md#L29-L33):

````text
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew installDebug
```

Debug builds are signed with a copy of the widget's committed `app/debug.keystore`. Sharing its key is what grants this app the widget's signature-level permission to read the assignments, so don't change it.
````

[docs/data-sources.md:35-44](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/docs/data-sources.md#L35-L44):

```text

Manifest in the Teams widget: a `signature` permission `com.teamsassignments.widget.permission.READ_ASSIGNMENTS`, and `.provider.AssignmentsProvider` at authority `com.teamsassignments.widget.assignments`, exported, with `android:permission` set to it (queries; `call()` checks it in code, since Android checks no permission on calls).

| URI or call | Returns |
|---|---|
| `content://com.teamsassignments.widget.assignments/assignments` | One row per assignment not handed in, in the widget's order (due time, undated last, then title): `key, title, class_name, description, due_text, due_at, tab, detail_read_at, last_synced_at` |
| `content://com.teamsassignments.widget.assignments/state` | One row: `last_success_at, status` (`idle`/`running`/`failed`/`stopped`), `status_message, status_at, assignment_count, sync_service_enabled` |
| `call(root, "requestSync", null, null)` | Starts a sync as ↻ does. Result: `started`, and `reason` (`service_off`, `busy`) when not |
| `call(root, "open", key, null)` | Opens that assignment in Teams as a row tap does. One more `reason`: `unknown_key` |

```

`app/debug.keystore` is binary; its private-key entry and matching certificate were inspected (no text line/excerpt).

- **Description:** The same private key is both publicly distributed and treated as the identity that authorizes reading Teams assignments. A signature permission authenticates possession of that key, not the repository owner or a particular package name. Publishing it allows an unrelated installed APK to present the same signer. Calling it a debug key does not contain the issue: debug APKs are the documented installed distribution and CI artifact. The repository explicitly uses this key to cross the companion app's authorization boundary.
- **When it occurs:** A third-party APK requesting `READ_ASSIGNMENTS` is signed with the published key and installed alongside the documented companion app. Under the documented signature-permission contract, that caller qualifies as a trusted signer. The local checks were `keytool -list -keystore app/debug.keystore -storepass android -alias androiddebugkey` and `apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk`; both reported certificate SHA-256 `9A48B9F997E612B0249E75AF3944A4174C2BA0E9B2CD03680F0C39D7416C7BF6`, and keytool identified `PrivateKeyEntry`.
- **Impact:** The assignment permission no longer separates approved applications from arbitrary installed callers holding the public key. The documented provider exposes assignment titles, descriptions and class/deadline information plus sync/open calls. The key also cannot authenticate the provenance of APK updates; installing an update still requires the platform's normal user/install authorization. This is P1 because the shipped authorization credential is already public. The companion implementation is outside this repository and was not independently audited.
- **Recommended fix:** Coordinate a migration of both installed apps to a private signing identity held outside the repository, with restricted CI signing access. Plan backup/reinstallation or a supported signing-key migration for existing users; do not merely remove the keystore from the current tree, because Git history retains it. Review the companion provider's caller policy and rotate away from trusting the old certificate. Keep any public debug identity restricted to synthetic development installations with no personal assignment data.
- **Effort:** M.

<a id="p1-002"></a>

## P1-002 — Gmail probe sends the app password without authenticating the TLS server

- **ID:** P1-002
- **Title:** Gmail probe sends the app password without authenticating the TLS server
- **Priority and category:** P1 — Security / credential exposure.
- **Status:** Verified (offline end-to-end run of the existing probe against a local self-signed TLS server).
- **Locations:**

[scripts/gmail_probe.py:117-119](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/scripts/gmail_probe.py#L117-L119):

```python
    imap = imaplib.IMAP4_SSL(HOST, 993, timeout=30)
    try:
        imap.login(address, password)
```

- **Description:** `IMAP4_SSL` is called without an explicit validating `ssl_context`. The installed Python standard-library implementation supplies `ssl._create_stdlib_context()`, which has `verify_mode == ssl.CERT_NONE` and `check_hostname == False`. Consequently the TLS connection encrypts traffic but does not authenticate Gmail before `login` transmits the account's app password. The standard library's default was inspected directly, rather than inferred from the `SSL` name.
- **When it occurs:** Run the documented Gmail probe with an app password while an attacker can redirect or intercept its IMAP connection (for example through network or DNS interception). No optional `--save` flag is needed. The offline reproduction redirected only the connection destination to a loopback IMAP test server presenting a self-signed certificate for `untrusted.invalid`, then executed the existing `main()` with a synthetic address/password. It completed and the server received `LOGIN`. No real account or external service was contacted.
- **Impact:** An intercepting server can obtain the Gmail app password and access mail available to that credential. Certificate verification is a normal requirement for this account-setup diagnostic; this is P1 despite the affected path being a script rather than the Android app.
- **Recommended fix:** Import `ssl` and pass `ssl_context=ssl.create_default_context()` to `imaplib.IMAP4_SSL`. Keep hostname checking enabled. Add a local TLS regression check that rejects an untrusted/wrong-host certificate and succeeds only with a deliberately trusted test certificate for the correct hostname.
- **Effort:** S.

<a id="p1-003"></a>

## P1-003 — Cancelling a store write leaves committed disk state and live state inconsistent

- **ID:** P1-003
- **Title:** Cancelling a store write leaves committed disk state and live state inconsistent
- **Priority and category:** P1 — concurrency, data integrity. Saving and leaving a screen is a normal sequence; if cancellation lands during its I/O, the next unrelated update silently discards the committed change. This is prioritized as data loss rather than only a cosmetic missed refresh.
- **Status:** Verified (deterministically reproduced for both stores with external JVM harnesses).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt:42-48](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/data/JsonStore.kt#L42-L48):

```kotlin
    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val next = transform(_state.value)
        if (next != _state.value) {
            withContext(Dispatchers.IO) { write(next) }
            _state.value = next
        }
        next
```

[app/src/main/java/com/thomaswcode/decrastination/data/SecretStore.kt:70-74](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/data/SecretStore.kt#L70-L74):

```kotlin
        if (next != _values.value) {
            withContext(Dispatchers.IO) { write(next) }
            _values.value = next
            _present.value = next.keys
        }
```

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:90-94](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L90-L94):

```kotlin
                            scope.launch {
                                val wanted = draft
                                graph.changeSettings { wanted }
                                // The reminders' alarms, at their new times.
                                Daily.schedule(this@SettingsActivity)
```

- **Description:** `withContext(Dispatchers.IO)` guarantees prompt cancellation when returning to the caller's dispatcher. Cancellation can happen after `write()` atomically replaces the file but before the statements after `withContext` run. The mutex then unlocks while the live flow still contains the old value. Subsequent updates start from that old value and overwrite the successful disk write. Atomic rename prevents torn files, but does not make the disk-and-memory transaction atomic with respect to cancellation. The settings UI uses a composition-owned coroutine scope, so leaving that composition can cancel an active save.
- **When it occurs:** Begin a store update, suspend its serializer/cipher while executing on the I/O dispatcher, cancel the calling job, and let serialization/encryption and the rename finish. In the reproduction, `JsonStore` persisted `ankiTextbook=2` while `store.value.ankiTextbook` remained 1; an unrelated update to `boxMin` wrote 1 back to disk. `SecretStore` likewise persisted a synthetic Gmail address while reporting it absent, and a later write of an unrelated synthetic API key erased the address. The harness's latch controls the race; it does not alter either store's implementation. On device, the equivalent window is cancelling a save while filesystem/encryption work is in progress.
- **Impact:** Settings, task/runtime/log data, or credentials can appear saved after restart, remain invisible during the current process, and then be silently lost to another update. The stores underpin the app's bookkeeping, so the invariant affects more than settings. Both stores share the same root cause.
- **Recommended fix:** Make successful file replacement and publication of the matching in-memory state one cancellation-safe transaction while retaining the mutex. For example, perform the write and subsequent flow assignments within a `withContext(NonCancellable + Dispatchers.IO)` section (after any desired pre-commit cancellation check), or otherwise publish the successfully committed state before returning across the cancellable dispatcher boundary. Preserve write failures and cancellation semantics deliberately; do not publish a state whose file write failed. Add a deterministic cancellation regression test to both store implementations.
- **Effort:** S.

<a id="p1-004"></a>

## P1-004 — Incomplete source snapshots can falsely finish or discard tracked work

- **ID:** P1-004
- **Title:** Incomplete source snapshots can falsely finish or discard tracked work
- **Priority and category:** P1; data integrity / error handling. The malformed-response trigger is conditional, but the consequence is an irreversible-looking completion and potentially undeserved rewards; the higher priority is chosen because a failed read must not become a successful destructive reconciliation.
- **Status:** Verified for Power Planner; other affected adapters confirmed by reading.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt:45-53](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt#L45-L53):

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

[app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt:116-120](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/powerplanner/PowerPlannerApi.kt#L116-L120):

```kotlin
@Serializable
data class AgendaResponse(
    @SerialName("Items") val items: List<PpItem>? = null,
    @SerialName("Error") override val error: String? = null,
) : WithError
```

[app/src/main/java/com/thomaswcode/decrastination/sources/teams/TeamsSource.kt:73-74](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/teams/TeamsSource.kt#L73-L74):

```kotlin
    fun fetched(row: Map<String, Any?>): Fetched? {
        val key = row["key"] as? String ?: return null
```

[app/src/main/java/com/thomaswcode/decrastination/sources/teams/TeamsSource.kt:105-109](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/teams/TeamsSource.kt#L105-L109):

```kotlin
    override suspend fun read(context: ReadContext): SourceRead = withContext(Dispatchers.IO) {
        val rows = TeamsProvider.assignments(resolver)
        val state = TeamsProvider.state(resolver)
        SourceRead(
            items = rows.mapNotNull(TeamsRows::fetched),
```

[app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt:29-37](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt#L29-L37):

```kotlin
    fun decks(resolver: ContentResolver): List<Deck> =
        resolver.query(decksUri, arrayOf("deck_id", "deck_name", "deck_count"), null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val counts = parseCounts(cursor.getString(2)) ?: continue
                    add(Deck(cursor.getLong(0), cursor.getString(1), counts[0], counts[1], counts[2]))
                }
            }
        } ?: throw SourceUnavailable("AnkiDroid isn't answering (is it installed?)")
```

[app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:140-142](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt#L140-L142):

```kotlin
            when {
                old.status != Status.Open -> old.takeIf { now - (old.doneAt ?: old.lastSeenAt) < KEEP_FINISHED_MS }
                old.derived -> old.copy(status = Status.Missed).also { missed += it }
```

[app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:153](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt#L153):

```kotlin
                else -> old.copy(status = Status.Done, doneAt = now).also { completed += completion(old, it) }
```

- **Description:** The `TaskSource` contract requires a complete read or an exception because absence means completion. Power Planner accepts an HTTP 200 `{}` or `{"Items":null}` as an authoritative empty agenda. Teams silently drops rows without a key; Anki silently drops rows with unparseable counts. These adapter failures become task absence rather than source failure. The existing Teams unit test expressly tests skipping a missing key without checking the destructive merge consequence.
- **When it occurs:** Seed an open Power Planner task, stub `Http.post` to return `HttpResponse(200, "{}")`, call `PowerPlannerApi.agenda`, and reconcile the resulting empty list with `Merge.apply`. The offline production-class harness printed `malformed agenda size=0 task status=Done completed=1`. Other triggers are a Teams row with absent/non-string key or an Anki row with missing/malformed `deck_count`; their filtered lists omit previously tracked items. No claim is made that the live services currently return these malformed shapes.
- **Impact:** Power Planner/Teams work can disappear from the plan as completed, with completion-side effects through `Syncer` (including its unrewarded list); Anki homework can be marked missed or counts incorrectly reduced. Source health is recorded as successful, so the user receives no error explaining the lost work. A later authoritative read may reopen ordinary tasks, but cannot make the earlier false completion harmless.
- **Recommended fix:** Validate that the expected collection is present and every required row parses before returning `SourceRead`; throw a descriptive source error otherwise. Accept an explicit valid empty collection as an empty source, not missing data. Preserve the old snapshot on malformed rows, or introduce an explicit partial-read result that forbids absence-based completion. Add end-to-end adapter-to-merge tests for absent Items, invalid keys/counts, and explicit empty lists.
- **Effort:** M.

<a id="p1-005"></a>

## P1-005 — HTML email stripping permits quadratic CPU exhaustion

- **ID:** P1-005
- **Title:** HTML email stripping permits quadratic CPU exhaustion
- **Priority and category:** P1; security / performance / availability. This is an externally triggerable resource-exhaustion weakness in an input processed automatically during normal inbox synchronization.
- **Status:** Verified (production `Mime.htmlToText` run on synthetic offline input).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Mime.kt:110-112](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Mime.kt#L110-L112):

```kotlin
    private val DROPPED_BLOCKS = Regex("""<(style|script|head)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LINE_TAGS = Regex("""<\s*(br|/p|/div|/tr|/li|/h\d)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
```

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Mime.kt:119-120](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/Mime.kt#L119-L120):

```kotlin
    fun htmlToText(html: String): String {
        val text = html.replace(DROPPED_BLOCKS, " ").replace(UNCLOSED_BLOCK, " ").replace(LINE_TAGS, "\n").replace(TAG, " ")
```

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:185-191](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L185-L191):

```kotlin
    private fun text(imap: ImapClient, uid: Long): String {
        val structure = imap.uidFetch(listOf(uid), "UID BODYSTRUCTURE").firstOrNull()?.get("BODYSTRUCTURE") ?: return ""
        val part = Mime.textPart(structure) ?: return ""
        val limit = if (part.subtype == "html") MAX_HTML_BYTES else MAX_TEXT_BYTES
        val response = imap.uidFetch(listOf(uid), "UID BODY.PEEK[${part.section}]<0.$limit>").firstOrNull() ?: return ""
        val body = response.entries.firstOrNull { it.key.startsWith("BODY[") }?.value as? ImapValue.Str ?: return ""
        return Mime.tidy(Mime.decode(body.bytes, part), MAX_BODY_CHARS)
```

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:214-217](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L214-L217):

```kotlin
        const val MAX_BODIES = 60
        const val MAX_TEXT_BYTES = 32_000
        const val MAX_HTML_BYTES = 200_000
        const val MAX_BODY_CHARS = 4_000
```

- **Description:** For a string containing many `<` characters and no `>`, `TAG` scans the remaining suffix at every potential start. The resulting quadratic work occurs before the final 4,000-character truncation. The 200 KB fetch cap is large enough to consume substantial CPU. A sender controls the MIME HTML body; Gmail's IMAP raw-body fetch does not require HTML to be well formed. Syncer's coroutine timeout stops waiting but cannot interrupt this CPU work.
- **When it occurs:** Deliver an HTML-only message whose body contains 200,000 literal `<` characters (the offline reproduction calls `Mime.htmlToText("<".repeat(200_000))` directly). Against the compiled implementation on JVM 17, the full permitted body took **59,348 ms** and returned 200,000 characters. An earlier scaling run measured 10k/20k/40k/80k at 553/1,480/5,457/14,974 ms; it was terminated at a total 30 seconds during the 200k case. Several such messages can consume the 90-second source budget; the source supports up to 60 newly fetched bodies per sync. These are host measurements, not Android timings.
- **Impact:** Any sender who can put messages in the user's inbox can force prolonged CPU use and stale Gmail tasks. When the source exceeds its budget, no body-cache update is committed, so the same payloads are eligible again at the next sync. Other sources and the UI do not necessarily crash; the proven impact is severe parsing cost, with source timeouts depending on message count/device speed.
- **Recommended fix:** Replace repeated unanchored regex stripping with a parser or a single forward scan that bounds work by input length. Apply a parsing budget/cancellation check independently of the final display truncation. Add adversarial tests for a full-cap unterminated tag sequence and repeated unterminated style/head blocks, with a deterministic operation bound or a generous runtime ceiling.
- **Effort:** M.

<a id="p1-006"></a>

## P1-006 — Timed Anki work is deducted again after the source counts shrink

- **ID:** P1-006
- **Title:** Timed Anki work is deducted again after the source counts shrink
- **Priority and category:** P1 — correctness, data integrity of the schedule. This affects normal syncs after using the app's Anki focus timer and produces incorrect remaining work; the higher priority follows the requested definition for incorrect results in likely use.
- **Status:** Verified (external JVM reproduction using actual `AnkiRules`, `Merge`, and `Planner` classes).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:102-108](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt#L102-L108):

```kotlin
                sourceEffortMin = f.sourceEffortMin,
                sourceProgress = f.sourceProgress,
                firstProgress = old.firstProgress ?: old.sourceProgress,
                peakEffortMin = listOfNotNull(old.peakEffortMin, old.sourceEffortMin, f.sourceEffortMin).maxOrNull(),
                // The source's steps, unless they're the same as before: then the ones kept here,
                // with what a session ticked off, until the source's counts catch up (Anki's cards).
                subSteps = f.subSteps?.takeIf { new -> new.map { it.title } != old.subSteps.map { it.title } } ?: old.subSteps,
```

[app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:362-369](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt#L362-L369):

```kotlin
    fun remaining(task: TaskItem, multiplier: Double): Double {
        val whole = task.effortMin * multiplier
        val bySource = whole * (1 - task.sourceProgress.coerceIn(0.0, 1.0))
        // From the lower of the first-seen and current progress: a percentage corrected downward
        // brings its work back, rather than the first-seen one capping what's left.
        val baseline = minOf(task.firstProgress ?: task.sourceProgress, task.sourceProgress)
        val bySessions = whole * (1 - baseline.coerceIn(0.0, 1.0)) - task.workedMin - task.photoMin
        return minOf(bySource, bySessions)
```

[app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:386-392](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt#L386-L392):

```kotlin
            var spare = (task.workedMin + task.photoMin - task.subSteps.filter { it.done }.sumOf { if (it.byHand) it.timedMin.toDouble() else it.minutes * multiplier })
                .roundToInt().coerceAtLeast(0)
            return left.map { step ->
                val full = (step.minutes * multiplier).roundToInt().coerceAtLeast(1)
                val off = minOf(spare, (full - MIN_CHUNK).coerceAtLeast(0))
                spare -= off
                Piece(step.title, full - off, from = step.from, due = step.dueAt)
```

[app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:260-265](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt#L260-L265):

```kotlin
                sourceEffortMin = effortMin(0, left).coerceAtLeast(1),
                done = left == 0,
                derived = true,
                subSteps = newCardSteps(left, deck.new),
                stepsPerDay = 1,
                notBefore = if (waits) nextRollover(now, zone) else null,
```

- **Description:** Anki's fetched estimate and steps describe cards *remaining*, not a stable whole-task estimate. A sync replaces those values but retains `workedMin`. The planner then subtracts all recorded timed work from the already reduced estimate or newly shortened step list. For homework decks, replacing the steps also removes the completed step that previously absorbed its session minutes. The same consumed cards therefore lower the plan twice. The percentage-progress safeguard handles Power Planner's stable estimate but does not handle Anki's shrinking counts.
- **When it occurs:** Start with an Anki homework deck containing 45 unseen cards (steps of 9, 9, and 3 estimated minutes). Finish a nine-minute focus session on its first 20-card step, and sync after those 20 cards have been studied (`unseen=25`, today's new cards exhausted). The new source steps are 9 and 3 minutes, but the plan contains 5 and 3. Independently, a review quota with 150 reviews has a 20-minute estimate; after a ten-minute focus session and a sync reporting 75 reviews, its source estimate is 10 minutes but the planner produces the five-minute floor.
- **Impact:** Normal Anki use understates future work and shortens subsequent focus sessions despite the remaining cards. Homework decks lose four minutes in the small reproduction; larger recorded totals can drive multiple later steps to their minimum. The daily plan and the time offered to complete the quota no longer match the source's own remaining estimate.
- **Recommended fix:** Model count-derived tasks' source remaining work separately from the stable baseline used for timed-work accounting. Reconcile newly observed source progress with already recorded session work and subtract only session work not yet reflected in the source counts. Keep lifetime `workedMin` for rewards/history instead of resetting it blindly. Cover both quota and homework-deck flows with tests spanning focus completion, unchanged-count sync, partially reduced counts, and fully reduced counts.
- **Effort:** M.

<a id="p2-001"></a>

## P2-001 — Changing the system language makes sensitive Settings pages unrecognizable to the guard

- **ID:** P2-001
- **Title:** Changing the system language makes sensitive Settings pages unrecognizable to the guard
- **Priority and category:** P2 — Security / conditional protection bypass.
- **Status:** Verified (real compiled guard function with synthetic localized screen text; Android end-to-end flow was not available).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt:41-44](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt#L41-L44):

```kotlin
    private val APP_INFO_ACTIONS = setOf("Force stop", "Uninstall")
    private val CLEAR_DATA = setOf("Clear data", "Clear storage")
    private val RESET_PAGES = setOf("Reset all settings", "Reset accessibility settings")
    private const val UNINSTALL = "uninstall"
```

[app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt:55-58](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt#L55-L58):

```kotlin
                if (namesApp && trimmed.any { it in APP_INFO_ACTIONS }) return Verdict.Back("this app's App info")
                if (namesApp && trimmed.any { it in CLEAR_DATA }) return Verdict.Back("this app's storage")
                if (namesApp && trimmed.any { it.startsWith("Deactivate") } && trimmed.any { "device admin" in it.lowercase() }) {
                    return Verdict.Back("this app's device admin page")
```

[app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt:64-70](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt#L64-L70):

```kotlin
                if (trimmed.any { it == "Date and time" } && trimmed.any { it == "Use 24-hour format" || it == "Automatic date and time" }) {
                    return Verdict.Back("the date and time settings")
                }
                // The confirmation page, with its button, not the list of resets (titled "Reset") that
                // leads to it. One UI's button text is taken from its other reset pages: unverified.
                if (trimmed.any { it in RESET_PAGES } && trimmed.any { it == "Reset settings" }) {
                    return Verdict.Back("a settings reset")
```

[app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt:73-77](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/GuardRules.kt#L73-L77):

```kotlin
            DEVICE_CARE -> if (namesApp && trimmed.any { it == "Force stop" }) return Verdict.Back("this app in Device Care")
            else -> if (trimmed.any { labels.app in it } && trimmed.any { UNINSTALL in it.lowercase() }) {
                // One UI: "Uninstall this app?", the app's name, Cancel and Uninstall. The installer
                // also asks before installing or updating an app, and must still be able to.
                return Verdict.Back("an uninstall prompt for this app")
```

[app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:568-574](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt#L568-L574):

```kotlin
    private fun onClick(event: AccessibilityEvent) {
        if (!graph.settings.value.armed || event.packageName?.toString() != GuardRules.SETTINGS) return
        if (event.text.none { labels.service in it.toString() }) return
        if (screenTexts(GuardRules.SETTINGS).none { "shortcut" in it.lowercase() }) return
        Log.i(TAG, "Guard: the focus service was tapped on a shortcut page")
        performGlobalAction(GLOBAL_ACTION_BACK)
        scope.launch { Watchdog.check(this@FocusService, repair = true) }
```

- **Description:** Most protected Settings pages are identified by exact English action strings. Other Settings pages, including language selection, are allowed. The application's own label can remain `Decrastination` after Android's system controls are localized, but the guard will no longer recognize force-stop, storage clearing, admin deactivation, date/time, or uninstall controls. This is distinct from an Android update changing the screen structure: the same supported phone can change its language without an update.
- **When it occurs:** Protection is armed and the phone's system UI language changes from English. As a deterministic policy-level reproduction, `decide("com.android.settings", ["App info", "Decrastination", "Open", "Uninstall", "Force stop"], labels)` returned `Back`; changing the synthetic screen text to `["App-Info", "Decrastination", "Öffnen", "Deinstallieren", "Stopp erzwingen"]` returned `Leave`. A synthetic localized date/time page also returned `Leave`. These inputs demonstrate the locale dependence; the precise labels and full bypass sequence must additionally be confirmed on the target handset.
- **Impact:** Sensitive Settings pages lose their guard. If the platform permits the corresponding actions, the user can stop or disable protection, clear app state, or alter wall-clock based behavior through the unrecognized pages. The repository targets one English-language Samsung phone, so this is P2 for the specific changed-language condition rather than a claim that the configured English device fails today.
- **Recommended fix:** Recognize supported Settings screens using package plus stable view/resource identifiers and the app identity where available; isolate OEM/version-specific matchers and test both positive and negative captures. Where stable identifiers are unavailable, explicitly track supported locales and prevent or prominently flag an unsupported guard state when the system locale changes instead of silently presenting protection as fully active. Cover localized app-info, storage, admin, time, reset, and installer pages in tests.
- **Effort:** M.

<a id="p2-002"></a>

## P2-002 — An unchecked exported intent tab index crashes the main screen

- **ID:** P2-002
- **Title:** An unchecked exported intent tab index crashes the main screen
- **Priority and category:** P2 — input validation / crash.
- **Status:** Verified — passing index 4 to the actual pinned Material3 indicator with four tabs raises `ArrayIndexOutOfBoundsException`; the exported-intent-to-index path is confirmed by reading. Full Activity launch was not run.
- **Locations:**

[app/src/main/AndroidManifest.xml:61-64](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/AndroidManifest.xml#L61-L64):

```xml
        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
```

[app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt:50-53](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt#L50-L53):

```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) askedTab.value = intent.getIntExtra(EXTRA_TAB, 0)
```

[app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt:59-63](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt#L59-L63):

```kotlin
        var tab by rememberSaveable { mutableIntStateOf(intent.getIntExtra(EXTRA_TAB, 0)) }
        askedTab.value?.let { asked ->
            LaunchedEffect(asked) {
                tab = asked
                askedTab.value = null
```

[app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt:83-84](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/MainActivity.kt#L83-L84):

```kotlin
                PrimaryTabRow(selectedTabIndex = tab) {
                    TABS.forEachIndexed { i, name -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) }) }
```

- **Description:** Any app can explicitly launch the exported main activity and supply `tab`. Both the initial and singleTop intent paths accept any integer, while there are only four tabs. Material3's `TabIndicatorOffsetNode.measure` accesses `tabPositionsState.value[selectedTabIndex]` without range validation, so an invalid supplied index crashes layout. The screen's `when` fallback to Setup cannot protect the tab row.
- **When it occurs:** Launch `adb shell am start -n com.thomaswcode.decrastination/.ui.MainActivity --ei tab 4` (or any negative/out-of-range integer). The same explicit intent from another installed app requires no permission. The isolated actual-dependency probe verified index 4 against a four-entry list; run the command on a device to validate the Activity end to end.
- **Impact:** The foreground app process is terminated by an invalid external navigation argument. No data corruption or privilege gain was demonstrated. P2 because the trigger is a crafted or erroneous external intent, rather than normal in-app navigation.
- **Recommended fix:** Normalize all initial, restored and newly delivered tab values against `TABS.indices`, defaulting to Plan. Use one parser/helper on both intent paths and validate restored state before passing it to `PrimaryTabRow`. Add tests for `-1`, `4`, and `Int.MAX_VALUE`.
- **Effort:** S.

<a id="p2-003"></a>

## P2-003 — Archiving an email before its deferred body fetch loses its follow-up work

- **ID:** P2-003
- **Title:** Archiving an email before its deferred body fetch loses its follow-up work
- **Priority and category:** P2; data integrity / concurrency / missing critical-path coverage.
- **Status:** Verified (production Gmail conversion and merge).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:167-170](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L167-L170):

```kotlin
                var fetched = 0
                for (message in GmailThreads.latest(messages)) {
                    val body = known[message.messageId] ?: if (fetched < MAX_BODIES) text(imap, message.uid).also { fetched++ } else null
                    body?.let { bodies[message.messageId] = it }
```

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:90-91](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L90-L91):

```kotlin
                    if (from.isNotEmpty()) put(EXTRA_FROM, from)
                    if (body == null) put(EXTRA_TEXT_PENDING, "true")
```

[app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt:110-114](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Enrichment.kt#L110-L114):

```kotlin
    fun jobFor(task: TaskItem): Job? = when {
        !task.isOpen || task.derived -> null
        // Its text not read yet (more new emails than one read takes): asked about once it is.
        task.source == Source.Gmail && GmailThreads.EXTRA_TEXT_PENDING in task.extra -> null
        task.source == Source.Gmail -> Job.Email
```

[app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:147](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt#L147):

```kotlin
                old.source == Source.Gmail && Enrichments.jobFor(old) != null && unread(old) -> old
```

[app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt:151-153](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Merge.kt#L151-L153):

```kotlin
                old.source == Source.Gmail && old.subSteps.any { !it.done && (it.from != null || it.dueAt != null || old.dueAt != null) } ->
                    old.copy(extra = old.extra + (EXTRA_FOLLOW_UP to "true"))
                else -> old.copy(status = Status.Done, doneAt = now).also { completed += completion(old, it) }
```

- **Description:** Deferred messages have no body, no enrichment and no blocks, but `jobFor` returns null for them. The merge guard intended to retain archived unread emails consequently excludes precisely these unread emails. GmailSource only fetches bodies for current inbox messages, so merely retaining the task would also be insufficient to read it after archive.
- **When it occurs:** On the first sync with more than 60 unknown conversations, an older message receives `textPending=true`. Archive/snooze it before the next read. Reproduce offline with `GmailThreads.fetched(listOf(message), emptyMap(), now, zone)`, merge it into tasks, then merge an empty Gmail snapshot with `unread={true}`. Result: `enrichment job=null archived status=Done`. The expected pending-read retention never runs. The omitted body may contain future application deadlines/blocks of the kind the repository explicitly preserves after archive.
- **Impact:** Follow-up work in deferred email bodies is never extracted; the application reports the task done without ever reading its contents. A large first sync or a burst of mail is enough; no server failure is needed.
- **Recommended fix:** Represent pending body acquisition separately from enrichment eligibility and retain that state across inbox removal. Retrieve retained pending messages by stable Gmail message ID in an appropriate mailbox, with deletion handled explicitly, before applying the archive/completion rules. Add an integration test spanning the 60-body cap, archive, subsequent fetch and block retention.
- **Effort:** M.

<a id="p2-004"></a>

## P2-004 — A transient missing Gmail body is cached permanently as empty text

- **ID:** P2-004
- **Title:** A transient missing Gmail body is cached permanently as empty text
- **Priority and category:** P2; error handling / data integrity / concurrency.
- **Status:** Verified (scripted offline IMAP response through production `GmailSource.text`, Gmail conversion and cache).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:185-191](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L185-L191):

```kotlin
    private fun text(imap: ImapClient, uid: Long): String {
        val structure = imap.uidFetch(listOf(uid), "UID BODYSTRUCTURE").firstOrNull()?.get("BODYSTRUCTURE") ?: return ""
        val part = Mime.textPart(structure) ?: return ""
        val limit = if (part.subtype == "html") MAX_HTML_BYTES else MAX_TEXT_BYTES
        val response = imap.uidFetch(listOf(uid), "UID BODY.PEEK[${part.section}]<0.$limit>").firstOrNull() ?: return ""
        val body = response.entries.firstOrNull { it.key.startsWith("BODY[") }?.value as? ImapValue.Str ?: return ""
        return Mime.tidy(Mime.decode(body.bytes, part), MAX_BODY_CHARS)
```

[app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt:97-101](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/gmail/GmailSource.kt#L97-L101):

```kotlin
    /** The text already stored for each conversation, by its newest message's id: read again only when that changes. */
    fun knownBodies(known: List<TaskItem>): Map<String, String> =
        known.filter { it.source == Source.Gmail && EXTRA_TEXT_PENDING !in it.extra }
            .mapNotNull { task -> task.extra[EXTRA_MESSAGE_ID]?.let { it to task.detail } }
            .toMap()
```

- **Description:** Missing FETCH data is indistinguishable from a successfully read genuinely empty/non-text message. The empty string goes into the body map, so no `textPending` flag is set, and every later sync reuses it by message ID. The `.firstOrNull()` selection also does not select the requested UID/attributes before deciding the data is absent; an unsolicited FETCH preceding the requested response can lead to the same result.
- **When it occurs:** A message is archived/deleted between the envelope batch and its BODYSTRUCTURE fetch; IMAP permits an OK response with no FETCH for a UID no longer in the selected mailbox. In the offline harness, feed `d1 OK Success\r\n` to `ImapClient`, invoke production `text(imap, 1)`, then convert/cache the result. Observed `missing body result='' cached={m1=} pending=false`. If the same message returns to INBOX, its stable message ID selects that empty cached body, even though it is now fetchable. A successfully fetched empty text part should remain distinguishable from this failure.
- **Impact:** Task detail, dates and model/rule triage permanently omit readable content until a different message becomes latest or the state is otherwise reset. This is a separate acquisition failure from the intentional 60-body deferral in P2-003.
- **Recommended fix:** Return an explicit pending/missing result (or fail the source read) when requested UID/attributes are absent. Filter FETCH results by UID and requested section/attribute. Only cache empty text after an actual valid body/structure response establishes that there is no readable content. Test disappearing UIDs, interleaved unsolicited FETCH, return-to-inbox and a genuinely empty message.
- **Effort:** M.

<a id="p2-005"></a>

## P2-005 — Finishing a session can complete another block with the same title

- **ID:** P2-005
- **Title:** Finishing a session can complete another block with the same title
- **Priority and category:** P2 — correctness / data integrity / concurrency. Requires duplicate block titles and the original block being ticked while its session runs; marking an untouched block done can remove due work from the plan.
- **Status:** Verified (external JVM harness against actual project classes).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:216-221](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt#L216-L221):

```kotlin
                    val stepIndex = if (ended.completed && session.step != null && session.whole) t.subSteps.indexOfFirst { !it.done && it.title == session.step } else -1
                    val steps = if (stepIndex >= 0) t.subSteps.mapIndexed { i, s -> if (i == stepIndex) s.copy(done = true) else s } else t.subSteps
                    t.copy(
                        subSteps = steps,
                        workedMin = t.workedMin + ended.workedMin,
                        sessionsCounted = (t.sessionsCounted + session.startedAt).takeLast(MAX_COUNTED),
```

[app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt:92-96](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt#L92-L96):

```kotlin
data class FocusSession(
    val taskId: String,
    val label: String,
    /** The sub-step it works through, if the task has them; else the session adds to minutes worked. */
    val step: String?,
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt:280-287](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt#L280-L287):

```kotlin
        val read = given.map { step ->
            if (step.title.isBlank() || step.minutes !in 1..maxMinutes) return null
            val from = step.from?.let { date(it, zone, LocalTime.MIDNIGHT)?.takeIf { at -> plausible(at, now) } ?: return null }
            val due = step.due?.let { dateTime(it, zone)?.takeIf { at -> plausible(at, now) } ?: return null }
            if (from != null && ((due != null && from > due) || (dueBy != null && from > dueBy))) return null
            if (due != null && opens != null && due < opens) return null
            val sections = if (vocabulary && AnkiRules.vocabularyOnly(step.title)) sections(step.ankiSections) else emptyList()
            SubStep(step.title.trim().take(MAX_TITLE), step.minutes, ankiSections = sections, from = from, dueAt = due)
```

[app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt:118](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/TasksScreen.kt#L118):

```kotlin
                    if (task.source == Source.Gmail && task.isOpen && !step.done) TextButton(onClick = { tick(task, index, step.title) }) { Text("Done") }
```

- **Description:** A session remembers only the step title. On completion it looks up the first *currently unfinished* matching title, rather than the block it started on. The task screen correctly ticks by index, but this makes the session's original block disappear from the title lookup and moves completion to the next duplicate. The current tests cover duplicate titles for `tickBlock` only (`FocusTest.kt:516-521`).
- **When it occurs:** Parse an email enrichment with two accepted blocks `Apply` (30 min) and `Apply` (60 min), total 90 min. Start the first 30-minute planned chunk. While its timer is running, go to Tasks and tick the first block Done. Let the timer finish. The harness performed this through `Answers.parse`, `Focus.startSession`, `Focus.tickBlock`, and `Focus.stopSession`. Result: both blocks are done; the untouched 60-minute block was incorrectly ticked.
- **Impact:** The second obligation disappears from the remaining plan, and blocking pressure can lift even though that work has not been performed. The timed work is associated with the wrong block.
- **Recommended fix:** Give substeps stable identities and carry the chosen identity (and an applicable task generation) through `Chunk` and `FocusSession`. Update only that identity; if it is already complete, retain the legitimate session's timing record without completing a different block. Use the same identity in photo completion rather than title lookup. Add the duplicate-title/manual-tick-during-session regression case.
- **Effort:** M.

<a id="p2-006"></a>

## P2-006 — Source completion can cancel a replacement session's alarm and notification

- **ID:** P2-006
- **Title:** Source completion can cancel a replacement session's alarm and notification
- **Priority and category:** P2 — concurrency / lifecycle / timer reliability. Requires another session to start while an earlier source completion is being settled.
- **Status:** Confirmed by reading; the JVM harness reproduced the old-completion/new-session interleaving and stale `stopped=true` result, but Android alarm/notification cancellation itself was not run.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:350-355](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt#L350-L355):

```kotlin
    private suspend fun settleCompletions() {
        val stopped = focus.rewardCompletions { completed ->
            runCatching { Assessment.ask(app, completed) }.onFailure { Log.w(TAG, "Couldn't ask how the work went", it) }
        }
        if (stopped) Sessions.clear(app)
    }
```

[app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:387-393](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt#L387-L393):

```kotlin
    suspend fun rewardCompletions(then: suspend (List<TaskItem>) -> Unit = {}): Boolean {
        val waiting = tasks.value.unrewarded
        if (waiting.isEmpty()) return false
        val stopped = onCompleted(waiting)
        then(waiting)
        tasks.update { state -> state.copy(unrewarded = state.unrewarded.filterNot { t -> waiting.any { it.id == t.id && it.doneAt == t.doneAt } }) }
        return stopped
```

[app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt:116-120](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt#L116-L120):

```kotlin
    /** The session's notification and alarm gone: for a session ended without [end] (its task done). */
    fun clear(context: Context) {
        Notify.cancel(context, NOTIFICATION_ID)
        context.getSystemService(AlarmManager::class.java)?.cancel(endIntent(context))
    }
```

[app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt:146-150](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt#L146-L150):

```kotlin
    private fun endIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, SessionReceiver::class.java).setAction(ACTION_END),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
```

- **Description:** `stopped` describes the earlier session, but `Sessions.clear` cancels whichever session currently owns the shared notification and PendingIntent. Between these operations, `rewardCompletions` awaits assessment and a task-store write. A user can start the next task after the old session has cleared. The ordinary `Sessions.end` path already accounts for a replacement by restoring the current session after clearing (`Sessions.kt:63-68`); source-completion settlement does not.
- **When it occurs:** Start session A; a source sync reports task A done and enters `rewardCompletions`. Once it has stopped A, suspend its `then` callback (representing assessment work). Start session B. Resume the completion callback. The external JVM harness confirms `rewardCompletions` returns `true` while `focus.session` is B. In the real caller, the next operation is `Sessions.clear`, cancelling B's ongoing notification and end alarm.
- **Impact:** B remains stored as active, but the visible timer/Stop action and the alarm backstop disappear. If the accessibility service is unavailable, B is not settled and credited at its scheduled end until another recovery path runs. Otherwise its ticker eventually settles it.
- **Recommended fix:** Serialize session state and Android alarm/notification updates in one lifecycle coordinator. At minimum, after source-completion cleanup restore/schedule the currently active session, with synchronization so it cannot race another start; preferably use a stable session ID in alarm intents and conditional cancellation. Add a test interleaving source completion with a replacement start and assert B retains its alarm and notification.
- **Effort:** M.

<a id="p2-007"></a>

## P2-007 — Service reconnection leaves deferred checks stuck for that instance

- **ID:** P2-007
- **Title:** Service reconnection leaves deferred checks stuck for that instance
- **Priority and category:** P2 — lifecycle / blocking reliability / test coverage. Requires disconnection while a throttled URL or Settings check is pending, followed by reuse of the service instance.
- **Status:** Confirmed by reading. A device or Android lifecycle test is required to reproduce callback delivery; existing blocking tests cover only pure policy/state code.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:329-334](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt#L329-L334):

```kotlin
    private fun checkAddressLater(browser: String) {
        val wait = URL_CHECK_MS - (SystemClock.uptimeMillis() - lastUrlCheckAt)
        if (wait <= 0) return checkAddress(browser)
        if (urlCheckPending == null) handler.postDelayed({ urlCheckPending?.let(::checkAddress); urlCheckPending = null }, wait)
        urlCheckPending = browser
    }
```

[app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:552-560](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt#L552-L560):

```kotlin
    private fun lookLater(pkg: String, delayMs: Long) {
        if (lookPending == null) handler.postDelayed(deferredLook, delayMs)
        lookPending = pkg
    }

    private val deferredLook = Runnable {
        val pkg = lookPending ?: return@Runnable
        lookPending = null
        guard(pkg, firstLook = false)
```

[app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:688-695](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt#L688-L695):

```kotlin
    private fun disconnect(why: String) {
        if (!active) return
        active = false
        commitSpending()
        spending = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        banner.cancel()
```

[app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt:173-178](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/FocusService.kt#L173-L178):

```kotlin
        // Nothing known of what browsers show: while no service watched, any may have moved on,
        // to a blocked page or from one. One hiding its address is looked at afresh.
        lastSite.clear()
        pipSite.clear()
        lookedFor.clear()
        broughtForward.clear()
```

- **Description:** Removing a delayed Runnable also removes the only code that clears its pending sentinel. Both sentinels survive `disconnect` and `onServiceConnected`. Later events in the throttle/cooldown window see a non-null sentinel, so they do not post a new callback. The intended final check in a burst is silently lost. This is distinct from a service instance being destroyed: the class explicitly supports reconnecting a live instance (`FocusService.kt:69-72`).
- **When it occurs:** Queue a URL check within 400 ms of its previous check (or a guard check within its 250 ms interval / 1 s Back cooldown). Disconnect before delivery, then reconnect the same instance. Trigger another short burst of URL typing/content updates or an initially empty Settings page that fills during the throttle interval. No deferred callback is queued because the old pending flag remains set. A device test can inspect queued checks and verify final content is evaluated after the throttle expires.
- **Impact:** A newly blocked browser address can remain accessible until a later immediate event or the 30-second ticker. The guard does not have a periodic full Settings-screen scan, so a Settings page whose relevant text appeared only during the discarded final check can remain unguarded until another event. This weakens the intended prompt protection during the reconnect condition.
- **Recommended fix:** Clear `urlCheckPending` and `lookPending` whenever their callbacks are removed, and reset per-connection timing/retry state together. Add a reconnect regression test that cancels pending callbacks then confirms a subsequent burst schedules and executes a fresh final check.
- **Effort:** S.

<a id="p2-008"></a>

## P2-008 — Settings saves overwrite concurrent changes to unrelated fields

- **ID:** P2-008
- **Title:** Settings saves overwrite concurrent changes to unrelated fields
- **Priority and category:** P2 — concurrency / data integrity.
- **Status:** Verified — the actual `SettingsChanges.propose` used by `changeSettings` reproduces the overwrite with the editor's stale snapshot. Activity interaction was traced by reading.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:68-73](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L68-L73):

```kotlin
        val saved by graph.settings.state.collectAsStateWithLifecycle()
        val runtime by graph.runtime.state.collectAsStateWithLifecycle()
        // What's been asked for, waiting changes included: setting one back cancels it.
        val asked = SettingsChanges.requested(saved, runtime.pending)
        var draft by remember { mutableStateOf(asked) }
        var invalid by remember { mutableStateOf(emptySet<String>()) }
```

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:89-94](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L89-L94):

```kotlin
                        onClick = {
                            scope.launch {
                                val wanted = draft
                                graph.changeSettings { wanted }
                                // The reminders' alarms, at their new times.
                                Daily.schedule(this@SettingsActivity)
```

[app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:148-153](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt#L148-L153):

```kotlin
    suspend fun changeSettings(change: (Settings) -> Settings) = changing.withLock {
        // What's waiting is counted up to now first, so a new change's wait starts now.
        applyDue(force = true)
        val state = runtime.value
        val proposed = change(SettingsChanges.requested(settings.value, state.pending))
        val outcome = SettingsChanges.propose(settings.value, proposed, state.pending, clock.now()) { java.util.UUID.randomUUID().toString() }
```

[app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt:103-105](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt#L103-L105):

```kotlin
        val changes = answer.changes.filter(ReviewInput::allowed)
        // Laid over what's been asked for, so a change waiting elsewhere keeps its wait.
        if (changes.isNotEmpty()) graph.changeSettings { ReviewInput.apply(it, changes) }
```

- **Description:** The editor remembers a complete settings snapshot once. Incoming settings/pending state updates refresh `asked`, but not `draft`. Save ignores the latest settings supplied to its callback and returns the old complete snapshot. `SettingsChanges.propose` treats every difference as intentional, so unrelated concurrent changes are reverted or replaced by new pending changes. The mutex serializes writes but does not merge the stale editor snapshot.
- **When it occurs:** Open Settings with `boxMin=45`. While it remains open, a weekly model review changes `boxMin` to 30 (or another screen changes a setting). Change only the monthly cap to 201 and save. The pure probe using those exact settings returns `boxMin=45`, losing the concurrent 30-minute value. For armed settings, an unrelated pending change can similarly be cancelled by the stale snapshot.
- **Impact:** Settings or scheduled loosening requests are silently lost; the plan and blocking policy can revert despite the user saving an unrelated preference. P2 because a concurrent writer is required.
- **Recommended fix:** Retain the edit baseline and explicit dirty-field set. In `graph.changeSettings { latest -> ... }`, overlay only fields the user changed on `latest`; refresh untouched draft fields when `asked` changes. Detect conflicts for fields changed both locally and externally. Add a regression test for an external setting/pending update while the editor is open.
- **Effort:** M.

<a id="p2-009"></a>

## P2-009 — Backup import accepts out-of-range values that bypass settings validation

- **ID:** P2-009
- **Title:** Backup import accepts out-of-range values that bypass settings validation
- **Priority and category:** P2 — input validation, error handling, configuration integrity. A syntactically valid but edited or malformed backup is required; ordinary exports contain the valid values accepted by the UI.
- **Status:** Verified (accepted invalid backup plus actual Anki scheduling failure in an external JVM reproduction; complete Android import interaction was not executed).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt:49-51](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt#L49-L51):

```kotlin
    /** [text] as one of this app's backups, or null: not JSON, not this app's, or a newer format than this version reads. */
    fun decode(text: String): Backup? = runCatching { json.decodeFromString(Backup.serializer(), text) }.getOrNull()
        ?.takeIf { it.app == Backup.APP && it.format <= Backup.FORMAT }
```

[app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt:71-72](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/data/Backup.kt#L71-L72):

```kotlin
    fun importedSettings(asked: Settings, backup: Backup): Settings =
        backup.settings.copy(armed = asked.armed, aiKeyActive = asked.aiKeyActive)
```

[app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:380-383](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt#L380-L383):

```kotlin
    suspend fun importBackup(text: String): String {
        val backup = Backups.decode(text) ?: return "That isn't a Decrastination backup (or it's from a newer version)."
        val armed = settings.value.armed
        changeSettings { Backups.importedSettings(it, backup) }
```

[app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:178-181](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt#L178-L181):

```kotlin
        // A deadline before Anki's day turns over (01:00) is that night's, after midnight: the
        // next calendar date, still within the Anki day.
        val date = if (deadlineMin < ROLLOVER_HOUR * 60) day.plusDays(1) else day
        val dueAt = date.atTime(LocalTime.of(deadlineMin / 60, deadlineMin % 60)).atZone(zone).toInstant().toEpochMilli()
```

- **Description:** Backup decoding verifies only app identity and an upper bound on the format number. Kotlin serialization checks types, not the domain restrictions enforced by the Settings screen. `importedSettings` copies the other settings directly, and `changeSettings` applies its protection-delay policy without validating their ranges. Consequently invalid numbers and invalid working-hour windows can reach persisted settings. The same import path also accepts unbounded calibration values when unarmed.
- **When it occurs:** Decode/import the following small JSON backup while unarmed: `{"app":"Decrastination","format":1,"exportedAt":0,"settings":{"ankiDeadlineMin":-1},"log":{}}`. Decoding succeeds. On a subsequent Anki read with at least one due review, `AnkiRules.quota` throws `DateTimeException: Invalid value for MinuteOfHour (valid values 0 - 59): -1`. The production Settings time parser accepts only hours 0–23 and minutes 0–59, so this value cannot be saved through the ordinary time editor. Imported settings persist until corrected; this is not rejected as an invalid backup.
- **Impact:** A malformed backup can disable successful Anki synchronization and leave its previous tasks stale while the application reports a source failure. More broadly, backup input bypasses the documented bounds on scheduling and learned parameters. The verified effect is a sync failure, not a claimed full application crash.
- **Recommended fix:** Validate the complete backup before any settings/log/runtime writes. Reuse a central settings validator for both the editor and import: time-of-day values in 0..1439, ordered same-day study windows, valid quiet-hour windows, and the editor's numeric ranges. Validate calibration values against their supported finite ranges, and reject unsupported format values explicitly. Return a field-specific error without partially applying the backup. Add invalid-value import tests alongside the existing syntax/format and roundtrip tests.
- **Effort:** M.

<a id="p2-010"></a>

## P2-010 — Cancelling a model call can discard its billable usage

- **ID:** P2-010
- **Title:** Cancelling a model call can discard its billable usage
- **Priority and category:** P2 — concurrency/cancellation, accounting.
- **Status:** Verified (actual `ClaudeReviewer` against localhost MockWebServer; application accounting branches confirmed by reading).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt:44-45](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt#L44-L45):

```kotlin
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:57-58](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt#L57-L58):

```kotlin
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/PhotoChecker.kt:62-63](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/PhotoChecker.kt#L62-L63):

```kotlin
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
```

[app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt:97-100](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt#L97-L100):

```kotlin
            runCatching { current.review(input) }
                .onFailure { e -> graph.runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).failure(e.message ?: e.javaClass.simpleName, at)) } }
                .getOrThrow()
                .also { r -> graph.runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).record(r.costUsd, r.refused, at)) } }
```

[app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt:475-482](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/AppGraph.kt#L475-L482):

```kotlin
                val result = runCatching { enricher!!.enrich(task, job, now) }
                    .onFailure { error ->
                        Log.w(TAG, "The model's enrichment failed; the rules stand in", error)
                        runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).failure(error.message ?: error.javaClass.simpleName, at)) }
                        enricher = null
                    }
                    .getOrNull()
                result?.let { r -> runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).record(r.costUsd, r.refused, at)) } }
```

[app/src/main/java/com/thomaswcode/decrastination/block/PhotoChecks.kt:49-55](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/PhotoChecks.kt#L49-L55):

```kotlin
            runCatching { current.check(jpeg, piece.label) }
                .onFailure { e ->
                    Log.w(AppGraph.TAG, "The photo check failed", e)
                    graph.runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).failure(e.message ?: e.javaClass.simpleName, now)) }
                }
                .getOrElse { return "The check didn't go through (${it.message ?: "no connection"})." }
                .also { r -> graph.runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).record(r.costUsd, r.refused, now)) } }
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt:35](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt#L35):

```kotlin
    fun failure(error: String, at: Long): AiUsage = copy(failed = failed + 1, lastError = error, lastCallAt = at)
```

- **Description:** Synchronous HTTP requests run inside a cancellable `withContext(IO)` boundary. They can complete on the server after the caller is cancelled, but Kotlin then throws on returning to the cancelled context, discarding the response and its usage before pricing. The accounting is performed only after a normal result and there is no persistent in-flight charge. `runCatching` also catches cancellation as a model failure, but cannot recover the discarded usage. This differs from P2-011: even a correctly bounded small call is lost here.
- **When it occurs:** Start a model call; after the server receives it, cancel its coroutine before returning a successful response with usage. In the harness, a latch-controlled MockWebServer accepts the actual `ClaudeReviewer` request, its caller is cancelled, and the server then returns a valid response reporting 1,000 input/200 output tokens. The reviewer returns no result and throws `JobCancellationException`; no cost reaches its caller. WorkManager stopping a worker or a photo-check screen coroutine being cancelled provides reachable caller lifecycles. The harness verifies delivery/cancellation behavior with simulated usage, not a real charge.
- **Impact:** Successful service work can be missing from local spend/call counters, leaving the monthly cap unreliable. Repeated interrupted requests can spend beyond the locally reported allowance. A cancelled call can also lose its answer and be attempted again.
- **Recommended fix:** Persist a request reservation under the model-call lock before dispatch, reconcile actual usage within a bounded cancellation-safe accounting boundary once a response exists, and keep a conservative charge for dispatched requests whose outcome is unknown. Rethrow `CancellationException` rather than treating it as an ordinary network failure. If using cancellable HTTP, still account conservatively after dispatch because cancelling locally does not prove the service did no billable work. Add a response-after-cancellation regression with a mock server.
- **Effort:** M.

<a id="p2-011"></a>

## P2-011 — Fixed request allowance does not bound the monthly model cost

- **ID:** P2-011
- **Title:** Fixed request allowance does not bound the monthly model cost
- **Priority and category:** P2 — accounting, input bounds.
- **Status:** Verified (actual input builder and budget/pricing functions, using declared token-count scenarios; no claim that a live API produced those counts).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt:29-30](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/AiUsage.kt#L29-L30):

```kotlin
    /** Whether one more call stays under [capGbp] even at its dearest ([Pricing.WORST_CALL_USD]). */
    fun allows(capGbp: Int, usdToGbp: Double): Boolean = (spentUsd + Pricing.WORST_CALL_USD) * usdToGbp <= capGbp
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt:85-89](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeEnricher.kt#L85-L89):

```kotlin
    private val OPUS_5_5 = Rates(input = 4.0, output = 20.0, cacheRead = 0.20, cacheWrite = 5.0)
    private val OLDER_OPUS = Rates(input = 5.0, output = 25.0, cacheRead = 0.50, cacheWrite = 6.25)

    /** The most one call can cost: a long email in, and all of [ClaudeEnricher.MAX_TOKENS] out, at the dearer rates. */
    const val WORST_CALL_USD = 0.45
```

[app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt:94-96](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt#L94-L96):

```kotlin
        log.checkIns.filter { it.weekOf == week }.maxByOrNull { it.at }?.let { c ->
            appendLine()
            appendLine("Their Sunday answers: the week felt ${c.feel}/5. Avoided: ${c.avoided.ifBlank { "-" }}. In the way: ${c.inTheWay.ifBlank { "-" }}. Would change: ${c.change.ifBlank { "-" }}. Most energy: ${c.energy.ifBlank { "-" }}.")
```

[app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt:78-81](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt#L78-L81):

```kotlin
        log.completions.filter { inWeek(it.doneAt) }.ifEmpty { null }?.forEach { c ->
            val due = c.dueAt?.let { " due ${at(it)}," }.orEmpty()
            val answer = c.assessment?.let { " felt $it" }.orEmpty() + c.note?.let { " (\"$it\")" }.orEmpty()
            appendLine("- ${c.title} [${c.kind.label}${c.className?.let { ", $it" }.orEmpty()}]:$due done ${at(c.doneAt)}, estimate ${c.estimateMin} min, timed ${c.workedMin} min${if (c.byHand) " (some ticked off by hand)" else ""}.$answer")
```

[app/src/main/java/com/thomaswcode/decrastination/learn/CheckIns.kt:120](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/CheckIns.kt#L120):

```kotlin
                                    log.copy(checkIns = log.checkIns + CheckIn(week, now, feel, avoided.trim(), inTheWay.trim(), change.trim(), energy.trim())).trimmed(now)
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt:37-39](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt#L37-L39):

```kotlin
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .system(ReviewInput.SYSTEM)
            .addUserMessage(week)
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt:145-151](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/Prompts.kt#L145-L151):

```kotlin
        }
        val text = task.detail.trim()
        if (text.isNotEmpty()) {
            appendLine()
            appendLine(if (job == Enrichments.Job.Email) "Email:" else "Details:")
            if (text.length <= MAX_TEXT) append(text) else append(text.take(MAX_TEXT)).append("\n[The rest of this long text is left out.]")
        }
```

- **Description:** The gate allows any call when $0.45 remains, claiming that amount is a worst case. It limits output to 16,000 tokens but does not bound total input. At the code's own normal-model rates, 50,000 input plus 16,000 output tokens cost $0.52; the configured fallback rates exceed $0.45 with only 10,001 input tokens and a full output. No request-specific token check enforces the assumed maximum.
- **When it occurs:** Near the monthly cap, submit a large weekly log/check-in or other unbounded model input and receive a sufficiently long answer/thinking output. The harness passes 100,000 characters in one check-in field; `ReviewInput.describe` returns 100,292 characters intact. Independently using the code's pricing scenario of 50,000 input and 16,000 output, `AiUsage(spentUsd=200/0.79-0.46).allows(200,0.79)` is true, but recording that call yields £200.0474 spent. This verifies the missing bound and arithmetic, not an actual billed request or an exact tokenizer ratio.
- **Impact:** The configured maximum can be exceeded. The ordinary API key holder bears the excess, even though callers serialize the cap check. Oversized input can also be sent without any application limit.
- **Recommended fix:** Bound all input fields and the total request by a token budget, then compute a conservative per-request reservation from maximum output and every permitted model's prices (including system/schema/image input). Pass that amount into the budget gate. Add boundary tests for long weekly input and fallback pricing; revise the documented $0.45 guarantee until it is enforced.
- **Effort:** M.

<a id="p2-012"></a>

## P2-012 — Anki homework plans confuse note counts with card counts

- **ID:** P2-012
- **Title:** Anki homework plans confuse note counts with card counts
- **Priority and category:** P2; correctness / scheduling / API contract.
- **Status:** Verified for production planning with representative provider counts; note/card provider semantics are confirmed by repository code and `docs/data-sources.md:123`.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt:39-42](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiSource.kt#L39-L42):

```kotlin
    /** Notes in [deckName] with a card never studied, by Anki's own search; the provider answers null for none. */
    fun unseenNotes(resolver: ContentResolver, deckName: String): Int {
        val query = "deck:\"${deckName.replace("\"", "\\\"")}\" is:new"
        return resolver.query(notesUri, arrayOf("_id"), query, null, null)?.use { it.count } ?: 0
```

[app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:257-265](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt#L257-L265):

```kotlin
                detail = "$left cards never studied. For: " + linked.joinToString("; ") { it.title },
                className = first.className,
                dueAt = first.dueAt,
                sourceEffortMin = effortMin(0, left).coerceAtLeast(1),
                done = left == 0,
                derived = true,
                subSteps = newCardSteps(left, deck.new),
                stepsPerDay = 1,
                notBefore = if (waits) nextRollover(now, zone) else null,
```

[app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:280-284](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt#L280-L284):

```kotlin
    private fun newCardSteps(unseen: Int, today: Int): List<SubStep> {
        if (unseen <= 0) return emptyList()
        val first = minOf(unseen, if (today > 0) today else NEW_PER_DAY)
        val sizes = listOf(first) + (first until unseen step NEW_PER_DAY).map { minOf(NEW_PER_DAY, unseen - it) }
        return sizes.map { cards -> SubStep("$cards new cards", effortMin(0, cards).coerceAtLeast(1)) }
```

- **Description:** `/notes` returns notes having at least one new card. A note may generate multiple new cards (the documented vocabulary deck is learnt both ways), while `deck.new` and the 20/day limit count cards. The code treats the note count as a card count for effort, display and, critically, the number of separate daily steps. The documentation acknowledges low estimates, but a learned effort multiplier cannot add the missing days imposed by Anki's new-card limit.
- **When it occurs:** An open assignment names Textbook 1::1.2; its deck contains 40 entirely unseen notes generating two cards each, with 20 new cards available today. Call `homeworkDecks` with a `Deck(... new=20)` and `unseen={40}`. The production harness returned `40 cards never studied`, effort 17 minutes and exactly two steps of `20 new cards`. There are actually 80 new cards, requiring at least four 20-card days (and about 34 minutes at the code's 25 seconds/card).
- **Impact:** Multi-card vocabulary homework appears doable in too few days and consumes too little scheduled time; deadline pressure and blocking decisions are understated. Completion-by-zero can still be correct because zero matching notes means no unseen cards; that does not repair the planning error.
- **Recommended fix:** Maintain distinct note and card counts. Obtain exact per-note new-card counts through an appropriate card-capable provider/search surface, or expose a documented conservative cards-per-note/deck configuration and use it for remaining-card/day-step calculations. Do not claim an exact card count when only notes are known. Add mixed one-card/two-card/partially-studied-note fixtures that assert both total effort and minimum days.
- **Effort:** M.

<a id="p2-013"></a>

## P2-013 — Anki's 04:00 day boundary shifts on daylight-saving transitions

- **ID:** P2-013
- **Title:** Anki's 04:00 day boundary shifts on daylight-saving transitions
- **Priority and category:** P2; correctness / date handling.
- **Status:** Verified (production `AnkiRules.ankiDay` and `nextRollover`).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:74-75](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt#L74-L75):

```kotlin
    fun ankiDay(now: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(now).atZone(zone).minusHours(ROLLOVER_HOUR).toLocalDate()
```

[app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt:271-273](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/sources/anki/AnkiRules.kt#L271-L273):

```kotlin
    /** When Anki's next day starts: the next 04:00. */
    fun nextRollover(now: Long, zone: ZoneId): Long =
        ankiDay(now, zone).plusDays(1).atTime(ROLLOVER_HOUR.toInt(), 0).atZone(zone).toInstant().toEpochMilli()
```

- **Description:** `ZonedDateTime.minusHours(4)` subtracts four elapsed hours rather than comparing local wall time with 04:00. On a day with an offset transition, midnight may be three or five elapsed hours from 04:00. The resulting day can switch early or late, and `nextRollover` can return yesterday's boundary or tomorrow's instead of the next one.
- **When it occurs:** In `Europe/London`, call with `2026-10-25T03:30:00Z` (03:30 GMT, before rollover): production returns day `2026-10-25`, next rollover `2026-10-26T04:00Z`; correct values are day Oct 24 and next Oct 25 04:00. With `2026-03-29T03:30:00Z` (04:30 BST, after rollover), it returns day March 28 and next rollover March 29 04:00 BST, already in the past. Existing tests cover ordinary 03:59/04:00 but no offset transition.
- **Impact:** The daily quota can be reset a local hour too early/late and homework waiting for new cards can be hidden for an extra day or released before the intended next rollover. This is limited to the DST transition windows in affected time zones.
- **Recommended fix:** Compute the local date/time first, then use yesterday only if `localTime < 04:00`; resolve the next local 04:00 with the zone after choosing the date. Add both transition-day cases and assert that `nextRollover > now` and matches the intended local boundary.
- **Effort:** S.

<a id="p2-014"></a>

## P2-014 — Photo-completed work is treated as fully timed when learning estimates

- **ID:** P2-014
- **Title:** Photo-completed work is treated as fully timed when learning estimates
- **Priority and category:** P2 — correctness, data integrity.
- **Status:** Verified (actual `Focus.photoChecked`, `Focus.startSession/stopSession`, `Focus.onCompleted`, and `Calibrator.multipliers` in an offline JVM harness).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt:474-480](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/Focus.kt#L474-L480):

```kotlin
                        estimateMin = (task.effortMin * (1 - task.sourceProgress.coerceIn(0.0, 1.0)) - deckMinutes(task)).roundToInt().coerceAtLeast(1),
                        workedMin = worked(task),
                        // Its own deadline; one split into dated blocks with none of its own, its last block's.
                        dueAt = task.dueAt ?: task.subSteps.mapNotNull { it.dueAt }.maxOrNull(),
                        firstSeenAt = task.firstSeenAt,
                        doneAt = task.doneAt ?: now,
                        byHand = handMinutes(task) > 0,
```

[app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt:65-67](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/Calibrator.kt#L65-L67):

```kotlin
                // Timed, and all of it: with blocks ticked by hand, its minutes aren't how long it took.
                val timed = record.workedMin > 0 && !record.byHand
                if (timed) m = (1 - WEIGHT) * m + WEIGHT * (record.workedMin.toDouble() / record.estimateMin)
```

[app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt:80-81](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt#L80-L81):

```kotlin
            val answer = c.assessment?.let { " felt $it" }.orEmpty() + c.note?.let { " (\"$it\")" }.orEmpty()
            appendLine("- ${c.title} [${c.kind.label}${c.className?.let { ", $it" }.orEmpty()}]:$due done ${at(c.doneAt)}, estimate ${c.estimateMin} min, timed ${c.workedMin} min${if (c.byHand) " (some ticked off by hand)" else ""}.$answer")
```

- **Description:** The completion preserves only a hand-completion flag. A task partly completed through photos has an estimate for the whole task and timed minutes for only its timed portion, but passes the “timed, and all of it” predicate. Photo minutes deliberately remain separate in `TaskItem`; this omission nevertheless lets them distort timing calibration. The model's per-completion input also cannot identify the missing timing.
- **When it occurs:** Create a 60-minute homework task with two 30-minute steps. Photo-complete step A; run step B's 30-minute timer; let the source confirm completion. The harness records `estimateMin=60`, `workedMin=30`, `byHand=false`, then learns `Homework| = 0.85` from a starting multiplier of 1.0. The timed half actually matched its estimate. Existing tests cover photos and calibration separately, but not this combined path.
- **Impact:** Future work of the same kind/class is systematically underestimated and scheduled too late. Repeated mixed completions push the learned factor toward its 0.5 floor; the weekly model may also infer that the work was faster than it was.
- **Recommended fix:** Persist an explicit timing-completeness marker or photo contribution on `CompletionRecord`; exclude partially untimed completions from duration-ratio learning while retaining self-assessment nudges. Qualify their per-completion model input. Alternatively derive a correctly matched estimated denominator for only the timed portion, including calibration conversion. Add the mixed photo/timer completion regression above.
- **Effort:** M.

<a id="p2-015"></a>

## P2-015 — Delayed weekly jobs change their target week and suppress the next review

- **ID:** P2-015
- **Title:** Delayed weekly jobs change their target week and suppress the next review
- **Priority and category:** P2 — correctness, scheduling/data integrity.
- **Status:** Verified (actual week-selection and deduplication functions; WorkManager queue-data path confirmed by reading).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/learn/ReviewWorker.kt:48-52](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/ReviewWorker.kt#L48-L52):

```kotlin
            val online = AppGraph.get(context).claudeKey() != null
            val request = OneTimeWorkRequestBuilder<ReviewWorker>()
                .setInputData(workDataOf(KEY_IF_DUE to ifDue))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .apply { if (online) setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()) }
```

[app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt:34-36](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/Review.kt#L34-L36):

```kotlin
        val zone = graph.clock.zone()
        val week = Daily.checkInWeek(now, zone, graph.settings.value.checkInMin)
        if (ifDue && reviewed(graph.log.value.reviews, week, Daily.lastCheckIn(now, zone, graph.settings.value.checkInMin))) return
```

[app/src/main/java/com/thomaswcode/decrastination/learn/Daily.kt:146-149](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/Daily.kt#L146-L149):

```kotlin
    fun checkInWeek(now: Long, zone: ZoneId, minuteOfDay: Int): String {
        val last = lastCheckIn(now, zone, minuteOfDay)
        val next = nextSunday(now, zone, minuteOfDay)
        return weekOf(if (now - last <= next - now) last else next, zone)
```

- **Description:** A weekly request records only `ifDue`. It loses the week it was queued to review; when connectivity or background scheduling delays execution, the worker recalculates the nearest Sunday's week. The resulting record is marked as the next week's review, and deduplication suppresses that week's later complete review.
- **When it occurs:** With Claude enabled, queue the Sunday 11 October 2026 21:00 review in Europe/London while offline. Remain offline until Thursday 15 October 12:00. `Daily.checkInWeek` changes from `2026-10-05` to `2026-10-12`. The queued review now reads only the latter week's partial log; `Review.reviewed` returns true for the following Sunday 18 October and skips it. The threshold is the midpoint between Sunday check-in times, not a full week of delay. No device/network experiment was needed; the queue fields and actual pure functions establish this behavior.
- **Impact:** The intended week's notes/check-in are omitted, the following review is incomplete, and the scheduled complete review is suppressed. Bounded setting proposals can be based on the wrong period.
- **Recommended fix:** Persist the intended week and relevant scheduling timezone/check-in occurrence in the WorkRequest when enqueuing; pass that immutable week into `Review.run` and use it for the input and deduplication. Preserve explicit/manual review behavior separately. Test delayed jobs across the midpoint and multiple offline weeks.
- **Effort:** M.

<a id="p2-016"></a>

## P2-016 — Ambiguous transport words misclassify busy appointments as free time

- **ID:** P2-016
- **Title:** Ambiguous transport words misclassify busy appointments as free time
- **Priority and category:** P2 — correctness, input classification.
- **Status:** Verified (actual `EventJudge.time` in the offline harness).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt:49](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt#L49):

```kotlin
    private val TRAVEL = Regex("""\b(train|bus|coach|flight|plane|ferry|tube|metro)\b""", RegexOption.IGNORE_CASE)
```

[app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt:74-82](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/EventJudge.kt#L74-L82):

```kotlin
    fun judge(event: CalendarEvent, answers: Map<String, String>): Judgement {
        parse(answers[key(event)])?.let { return it }
        return when {
            // Marked free, a public holiday or a birthday, or travel you can work on.
            !event.busy || NOT_YOURS.containsMatchIn(event.calendar) || TRAVEL.containsMatchIn(event.title) -> Judgement.Free
            // All day, or most of one (an open day, a van hire booked 9 to 6): anything from nothing
            // to all of it. Ask.
            event.allDay || event.end - event.start >= LONG_MS -> Judgement.Ask
            else -> Judgement.Busy
```

- **Description:** A single whole-word keyword is treated as proof that an appointment is transport the student can work through. For example, “coach” also means a tutor or sports coach. A busy calendar appointment with that ordinary title becomes free time and is not returned as a question, so the app never obtains a corrective answer through the event-question path.
- **When it occurs:** Read a busy, one-hour event titled `Meeting with maths coach`, with no stored answer. `EventJudge.time` returns zero busy intervals, zero day loads, and zero questions. The same false positive occurs for other non-travel uses of the words. Existing tests cover “Train to Manchester” but no ambiguous non-travel title.
- **Impact:** The planner allocates study work inside real appointments, overstating capacity and potentially pushing work too late. Renaming the source event avoids the bug; it depends on the title, hence P2.
- **Recommended fix:** Preserve busy status for ambiguous matches or ask the user. Require sufficiently specific transport context before treating a busy appointment as workable time and offer a visible per-event override even for automatic classifications. Add both the coaching appointment and an actual transport case to the tests.
- **Effort:** S.

<a id="p2-017"></a>

## P2-017 — Scrolling an invalid Settings field off screen can leave Save disabled with no visible error

- **ID:** P2-017
- **Title:** Scrolling an invalid Settings field off screen can leave Save disabled with no visible error
- **Priority and category:** P2 — UI state / error handling.
- **Status:** Confirmed by reading — Compose lazy-item disposal and validation ownership are explicit; device scrolling reproduction remains to be run.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:72-79](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L72-L79):

```kotlin
        var draft by remember { mutableStateOf(asked) }
        var invalid by remember { mutableStateOf(emptySet<String>()) }
        var message by remember { mutableStateOf<String?>(null) }
        var apps by remember { mutableStateOf(emptyList<App>()) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps() } }
        fun valid(key: String, ok: Boolean) {
            invalid = if (ok) invalid - key else invalid + key
```

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:87-88](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L87-L88):

```kotlin
                    Button(
                        enabled = invalid.isEmpty() && draft != asked,
```

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:108-109](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L108-L109):

```kotlin
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
```

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:261-270](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L261-L270):

```kotlin
private fun <T> ParsedField(label: String, shown: String, key: String, valid: (String, Boolean) -> Unit, parse: (String) -> T?, onChange: (T) -> Unit, number: Boolean) {
    var text by remember(shown) { mutableStateOf(shown) }
    val ok = parse(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            val parsed = parse(value)
            valid(key, parsed != null)
            if (parsed != null) onChange(parsed)
```

- **Description:** Invalid raw text lives in a lazy item's ordinary `remember`, while the invalid-key set lives at the editor level. Scrolling far enough disposes the item and loses its raw text. Recreating it restores the last valid typed value from `draft`, so its error disappears, but nothing clears the editor's invalid key: `valid` only runs on another keystroke. Save remains disabled even when every displayed field is valid.
- **When it occurs:** Clear the Monthly cap field, tap a text field in the Hours section to move focus out of the Claude item, then scroll far down to the app list so the Claude lazy item is disposed and return to the cap. It is reconstructed from the previous valid cap, but the stale `aiMonthlyCapGbp` key remains in `invalid`. Make any other valid edit: Save is still disabled. Editing the affected cap again or reopening Settings is the workaround. The same path affects number, decimal, time and window fields.
- **Impact:** The user cannot save otherwise valid changes and loses the invalid input that would explain why. P2 because it requires invalid input followed by lazy-item disposal.
- **Recommended fix:** Hoist raw field text and validation together into editor state keyed by field ID (and preserve it across lazy-item disposal). Derive the invalid set from those raw values instead of maintaining two independent state stores. Add a UI regression that clears a field, scrolls it out of composition and back, then corrects and saves it.
- **Effort:** M.

<a id="p2-018"></a>

## P2-018 — A failed Teams pull overwrites the last usable fixture before validation

- **ID:** P2-018
- **Title:** A failed Teams pull overwrites the last usable fixture before validation
- **Priority and category:** P2 — Data integrity / script error handling.
- **Status:** Confirmed by reading (PowerShell is not installed in the review environment, so the script was not executed).
- **Locations:**

[scripts/pull_teams_state.ps1:7-13](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/scripts/pull_teams_state.ps1#L7-L13):

```powershell
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "fixtures\teams_widget_state.json"

adb shell run-as com.teamsassignments.widget cat files/widget_state.json | Out-File -Encoding utf8 $out
$state = Get-Content $out -Raw | ConvertFrom-Json
Write-Host ("Pulled {0} assignments, last successful sync {1}" -f $state.assignments.Count, ([DateTimeOffset]::FromUnixTimeMilliseconds($state.lastSuccessAt).ToLocalTime()))
```

- **Description:** The ADB command streams directly into the tracked fixture before its exit status or its JSON is checked. `Out-File` replaces the destination. In the normal Windows PowerShell native-command model, `$ErrorActionPreference = "Stop"` does not turn a nonzero `adb` exit into a terminating PowerShell error. Missing device, missing package/file, and `run-as` errors can therefore replace the previous fixture with an empty or diagnostic response; JSON parsing happens only afterwards. Even an invalid successful response has already overwritten the original when parsing fails.
- **When it occurs:** Execute the documented script with an unavailable/unauthorized device or a phone whose Teams app cannot be read with `run-as`, or have the read fail partway through. Reproduce safely on Windows using a temporary copy of the script/root and an `adb` stub that exits nonzero or emits malformed JSON; compare the destination bytes before and after. Do not perform that reproduction against the tracked fixture itself.
- **Impact:** The last useful fixture, including any uncommitted local capture, is lost or corrupted by an unsuccessful refresh. Git can restore the committed baseline but cannot recover an uncommitted capture. This also makes later fixture-based tests fail for a capture failure unrelated to the application under test.
- **Recommended fix:** Capture ADB output into a temporary file, check `$LASTEXITCODE`, parse and validate the expected state structure, and only then replace the fixture. Use `try/finally` to remove the temporary file. Check native exit codes for subsequent `adb pull`, `apksigner`, and `keytool` commands as well so failures cannot masquerade as a completed diagnostic.
- **Effort:** S.

<a id="p3-001"></a>

## P3-001 — Rotating during parent-code enrollment silently replaces the QR secret

- **ID:** P3-001
- **Title:** Rotating during parent-code enrollment silently replaces the QR secret
- **Priority and category:** P3 — UI state / maintainability.
- **Status:** Confirmed by reading (configuration-change UI behavior could not be exercised without Android).
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:99-100](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt#L99-L100):

```kotlin
        // Kept through the screen being recreated (turned): the wizard isn't left half-done unseen.
        var step by rememberSaveable { mutableStateOf(Step.None) }
```

[app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:302-308](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt#L302-L308):

```kotlin
    /** Shows a new secret as a QR code for your dad's authenticator; saved once a code from it checks out. */
    @Composable
    private fun ParentCodeDialog(graph: AppGraph, onDone: () -> Unit, onCancel: () -> Unit) {
        val secret = remember { Totp.newSecret() }
        val qr = remember(secret) { qrBitmap(Totp.uri(secret), 720) }
        var code by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
```

[app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt:329-335](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/protect/ProtectionActivity.kt#L329-L335):

```kotlin
                TextButton(enabled = code.length == Totp.DIGITS, onClick = {
                    val step = Totp.matchingStep(secret, code, graph.clock.now())
                    if (step == null) {
                        error = "That isn't the code. Check his app has the new entry."
                    } else {
                        scope.launch {
                            graph.secrets.put(Secret.TotpSecret, Totp.base32(secret))
```

- **Description:** The wizard stage is restored after Activity recreation, but the enrollment secret is only held by `remember`. Rotation disposes that composition and generates a different secret while returning directly to the same enrollment step. An authenticator already scanned from the preceding QR now contains the wrong key, although enrollment appears to have continued.
- **When it occurs:** Open the arming wizard, scan the parent QR into the parent's authenticator, rotate/recreate the phone Activity before confirming the code, then enter the code from the previously scanned entry. It will not validate except for an accidental six-digit collision; scanning the new QR is required.
- **Impact:** Interrupted or confusing setup and duplicate/stale authenticator entries. It does not reveal the stored parent secret or bypass an already armed configuration, hence P3.
- **Recommended fix:** Retain a single in-progress enrollment secret across configuration changes, for example in an Activity-scoped ViewModel. Clear it on explicit abandon or successful enrollment. After process death, intentionally restart enrollment with a clear message rather than restoring the stage while silently changing its secret. Avoid putting the secret into an unencrypted saved-state bundle merely to preserve it.
- **Effort:** S.

<a id="p3-002"></a>

## P3-002 — Settings switches do not expose their labels to accessibility services

- **ID:** P3-002
- **Title:** Settings switches do not expose their labels to accessibility services
- **Priority and category:** P3 — accessibility / UI consistency.
- **Status:** Confirmed by reading — the shared row has separate text and switch semantics; no TalkBack device run was performed.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:252-255](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L252-L255):

```kotlin
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
```

- **Description:** Every shared settings switch is an independently interactive accessibility node, but its label is a sibling `Text`, with neither merged semantics nor a content description. A screen-reader user focusing the switch receives its role/state without the setting or app it controls; activating the visible label also does nothing.
- **When it occurs:** Navigate Settings using TalkBack or switch access, especially through the repeated Blocked apps/browser switches. The helper is also used for Claude and automatic Teams sync. The preceding text can be read separately as a workaround.
- **Impact:** Identifying and changing the intended switch requires extra navigation and remembering a separate label, making a long blocklist unnecessarily error-prone.
- **Recommended fix:** Make the row `toggleable(value = checked, role = Role.Switch, onValueChange = onChange)` so descendants merge into one labelled control, and pass `onCheckedChange = null` to the visual switch. Verify the resulting semantics tree has one labelled toggle per row and test TalkBack traversal.
- **Effort:** S.

<a id="p3-003"></a>

## P3-003 — Status explanations hard-code configurable deadlines and blocking hours

- **ID:** P3-003
- **Title:** Status explanations hard-code configurable deadlines and blocking hours
- **Priority and category:** P3 — naming / display consistency.
- **Status:** Verified for the soft-deadline badge; the blocking-hours explanation is confirmed by reading.
- **Locations:**

[app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt:77-81](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/widget/WidgetModel.kt#L77-L81):

```kotlin
        fun badge(chunk: Chunk, now: Long, zone: ZoneId): String = when {
            !chunk.startable(now) -> "From " + Format.at(chunk.availableAt!!, now, zone).removePrefix("today ")
            chunk.overdue && chunk.soft -> "Waiting a week"
            chunk.overdue -> "Overdue"
            chunk.dueToday -> "Due " + Format.at(chunk.deadline, now, zone).removePrefix("today ")
```

[app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt:149](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/ui/SettingsActivity.kt#L149):

```kotlin
                    NumberField("Days given to undated work", draft.softDeadlineDays, 1..60, "softDeadlineDays", ::valid) { draft = draft.copy(softDeadlineDays = it) }
```

[app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt:149-154](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/core/Planner.kt#L149-L154):

```kotlin
            val soft = task.dueAt == null
            // Calendar days where you are, so a week is a week across the clocks changing.
            val deadline = task.dueAt
                ?: Instant.ofEpochMilli(task.firstSeenAt).atZone(zone).plusDays(input.settings.softDeadlineDays.toLong()).toInstant().toEpochMilli()
            windows(task, deadline, soft, pieces(task, input, held[task.id].orEmpty())) { start ->
                Instant.ofEpochMilli(start).atZone(zone).plusDays(input.settings.softDeadlineDays.toLong()).toInstant().toEpochMilli()
```

[app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt:255](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/BlockedActivity.kt#L255):

```kotlin
            Text("Nothing is blocked 22:30–07:00, or before 16:45 on school days.", style = MaterialTheme.typography.bodySmall)
```

[app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt:78-84](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/block/BlockPolicy.kt#L78-L84):

```kotlin
    fun isQuiet(now: Long, zone: ZoneId, settings: Settings): Boolean = minuteOfDay(now, zone) in settings.quietHours

    /** A weekday before blocking starts, outside sleep. */
    fun isSchoolHours(now: Long, zone: ZoneId, settings: Settings): Boolean {
        val day = Instant.ofEpochMilli(now).atZone(zone).dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false
        return !isQuiet(now, zone, settings) && minuteOfDay(now, zone) < settings.weekdayBlockFromMin
```

- **Description:** The badge converts every overdue undated task into the literal age `Waiting a week`. Soft deadlines are configurable between 1 and 60 days, and overdue tasks can also remain outstanding far longer than their initial deadline. The wording is therefore not derived from either the setting or actual task age. The shared badge is used in the widget and Plan screen. The block screen has the same root cause: its explanation always promises no blocking at 22:30–07:00 or before 16:45 on school days, although the policy reads effective settings. It also omits the active-focus-session exception.
- **When it occurs:** Set Days given to undated work to 1 and retain an undated task for two days. The task correctly becomes overdue, but its displayed badge says it has waited a week. Values greater than seven days and long-overdue tasks are also mislabelled. Separately, change effective quiet hours or weekday start, then expand “Why am I blocked?”: it still shows the defaults. A quiet-hours focus session also contradicts the absolute “Nothing is blocked” wording.
- **Impact:** Users receive misleading age information; the block explanation also misstates when apps become available. The underlying configured policy and deadlines are unaffected.
- **Recommended fix:** Use a configuration-independent label such as `Past planned date`, or carry actual first-seen/available date into the presentation model and format its elapsed duration. Pass effective Settings to the block explanation, format its current times, and explain the focus-session exception. Test non-default soft deadlines, blocking hours and long-overdue undated tasks.
- **Effort:** S.

<a id="p3-004"></a>

## P3-004 — The accessibility disclosure promises no data leaves the phone, but reviews send block counts

- **ID:** P3-004
- **Title:** The accessibility disclosure promises no data leaves the phone, but reviews send block counts
- **Priority and category:** P3 — privacy disclosure / user-facing consistency.
- **Status:** Confirmed by reading.
- **Locations:**

[app/src/main/res/values/strings.xml:12](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/res/values/strings.xml#L12):

```xml
    <string name="focus_service_description">Decrastination watches which app is in front. While homework or other work is due today or tomorrow, opening an app you\'ve chosen to block (YouTube, say) shows your next task instead. Once protection is armed, it also stops this setting being switched off from the phone.\n\nIt only notes which app is on screen, reads the address bar in Chrome and Brave, and reads Settings screens to recognise its own. Nothing leaves your phone.</string>
```

[app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt:92](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/learn/ReviewInput.kt#L92):

```kotlin
        appendLine("Times the blocker stopped them: ${log.blocks.count { inWeek(it.at) }}.")
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt:37-39](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt#L37-L39):

```kotlin
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .system(ReviewInput.SYSTEM)
            .addUserMessage(week)
```

[app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt:44](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/app/src/main/java/com/thomaswcode/decrastination/enrich/ClaudeReviewer.kt#L44):

```kotlin
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
```

- **Description:** The text shown when enabling accessibility makes an unconditional local-only claim. Once the user opts into Claude, the weekly prompt sends an aggregate derived from those blocker events. The code sends the weekly count, not the individual blocked package names, URLs or screen contents; those stronger disclosures are not alleged. Claude is off by default and requires user setup, which limits the severity.
- **When it occurs:** Enable Claude and an active API key, then run a weekly review after one or more blocking events. `ReviewInput.describe` includes the count in the request body sent by `ClaudeReviewer`.
- **Impact:** The accessibility permission explanation gives an inaccurate account of data handling after an optional feature is enabled. The disclosed aggregate is low sensitivity relative to the task information already used by the opted-in review, hence P3.
- **Recommended fix:** Describe accessibility observations as processed locally, and explicitly state that an optional Claude weekly review sends aggregate blocking counts along with its other documented inputs. Keep that qualification visible both in the accessibility explanation and the Claude opt-in screen.
- **Effort:** S.

<a id="p3-005"></a>

## P3-005 — Current reference documents still present superseded contracts as active behavior

- **ID:** P3-005
- **Title:** Current reference documents still present superseded contracts as active behavior
- **Priority and category:** P3 — documentation / API consistency and maintainability.
- **Status:** Confirmed by reading (cross-checked current code against all repository Markdown documents).
- **Locations:**

[docs/data-sources.md:57](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/docs/data-sources.md#L57):

```text
**Freshness.** Manual sync only (plus read-along while Teams is open). Decrastination calls `requestSync` from the block screen's **Refresh Teams** button and once in the morning routine, never silently in the background, because a sync takes over the screen. On 8 Oct both calls started but the widget's own sync and navigation then failed, because Teams now pages a Past due list of seven or more behind a "load more" placeholder; widget 0.3.1 (TeamsAssignmentsWidget #14) brings the placeholder into view, and its syncs work again (`docs/phase0-findings.md` §1).
```

[docs/data-sources.md:83](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/docs/data-sources.md#L83):

```text
**Credentials.** Username and password in EncryptedSharedPreferences; session token cached and refreshed on an `Error` response. The mobile apps' richer sync API (`data.powerplanner.net/api/Sync`) needs a device registration flow that lives in a closed NuGet (`PowerPlannerAppAuthLibrary`), so the web API is the practical choice.
```

[docs/scheduler.md:162-164](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/docs/scheduler.md#L162-L164):

```text
- The settings guard must not fire on another app that happens to contain the word "Decrastination" in its text (match on the Settings package *and* the app's own component or package name in the node tree).
- Override codes: a code for request A must not open request B; a code must stop working once the delay has elapsed on its own; the lockout after three wrong entries must survive a restart; a request raised while offline queues the email and still starts the 24-hour clock.
- The dedicated sender mailbox being unreachable (password revoked, no network) must degrade to "the request waits its 24 hours" with a visible notice, never to "the change applies".
```

[docs/data-sources.md:113-116](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/docs/data-sources.md#L113-L116):

```text
- *Daily quota task* (every day): "Anki: N reviews due + M new" with deadline 21:00. Effort ≈ reviews × 8 s + new × 25 s. Quota (your answer, 7 Oct): all due reviews plus 20 new cards from the lowest-numbered deck that still has new cards, plus any deck a German assignment names.
- *Homework deck task*: for each assignment with linked decks, "Learn deck 1.2 (k cards still new)", deadline = assignment due time (or the test date if the instructions name one), effort = new cards × 25 s + due × 8 s.

**Completion test.** For a deck task: `new + learn + review == 0` for that deck. For the daily quota: all review/learn counts zero and new cards introduced ≥ quota (new-introduced-today is derived as `min(quota, newAtStartOfDay − newNow)`).
```

[docs/data-sources.md:150-159](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/docs/data-sources.md#L150-L159):

```text
- From yourself → `Admin`, actionable now, effort 15 min unless the LLM says otherwise.
- Known noise senders (LinkedIn, marketing) → `Info`, suggested action "archive".
- Contains a date in the future and words like ticket/booking/open day → `Event` with `availableFrom = date − 1 day`.
- Everything else → `Admin`, actionable now, "read and decide".

**Alternative not taken: Gmail API (OAuth).** Cleaner scopes, proper message ids and deep links, labels; cost is a Google Cloud project, consent screen, and either 7-day token expiry in "Testing" or publishing unverified. You chose IMAP (Q2, 7 Oct).

**Sending, for the parent-held override only.** The app sends two kinds of email, both to `richard.white@lshtm.ac.uk`: a pending-change notice carrying a one-time code, and a "protection off for over an hour" alert. They go over SMTP (`smtp.gmail.com:465`, implicit TLS, `AUTH PLAIN`) from a **dedicated mailbox whose credentials your dad enters at setup**, not from your own account, because a code sent from your Gmail would be readable in your Sent folder. The same hand-rolled client approach works for SMTP (`EHLO`, `AUTH`, `MAIL FROM`, `RCPT TO`, `DATA`); messages are plain text with the request description, the code, and when the change would apply on its own. Design and the authenticator-app alternative: `docs/scheduler.md` §6.

## 5. The LLM enrichment
```

[docs/data-sources.md:197](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/docs/data-sources.md#L197):

```text
- `BlockedActivity`: `excludeFromRecents`, `launchMode="singleInstance"`, Back → `performGlobalAction(GLOBAL_ACTION_HOME)` via the service (the activity itself cannot). Shows `NextAction`, **Open**, **Check it's done**, **Refresh Teams**, **Why am I blocked?** (lists the pressure tasks), and the bypass control.
```

[PLAN.md:184](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/PLAN.md#L184):

```text
- **Device checks:** each source populates; the widget shows the right chunk after a sync; opening YouTube while Ch17 review is pending shows the block screen; a focus session blocks throughout and marks the chunk; handing in in Teams then **Check it's done** clears the task; with buckets empty, credit is spent only while YouTube is in front; the settings guard backs out of the app's own accessibility page and App info; uninstall is refused while the admin is active; a loosening change waits 24 h; the override email arrives at the parent address from the dedicated mailbox and nowhere in your own Gmail, its code applies only that change and only once, three wrong codes lock entry; the watchdog notices the service being turned off within 15 minutes and the alert email goes after an hour; everything survives a reboot and a day of One UI battery management.
```

[PLAN.md:188-191](https://github.com/ThomasWCode/Decrastination/blob/a89c7007d71b13bcf997cd700a70881fd3c2ac6d/PLAN.md#L188-L191):

```text
- **Teams data is only as fresh as the Teams widget's last sync** (manual, takes over the screen). The app requests a sync only at deliberate moments (block screen, morning). The widget's read-along observer catches hand-ins whenever Teams is open, which is when you hand in.
- **Power Planner's web API key is the rate-limited development key** from its open-source web app; 15-minute polling is modest; fallback is screen reading.
- **An app password grants full mailbox access**; encrypted on a phone you control; revocable.
- **Anti-tamper is friction, not security**: adb, safe mode, a factory reset or a new user profile defeat it. Layer 2 depends on Settings screen text, like the Teams scraper depends on Teams. The parent override is only as strong as the sender mailbox staying his; the setup makes him enter its password, and the app never displays it.
```

- **Description:** Historical proposals remain mixed with current operational contracts and the list of tests that "must cover" them. Implemented behavior uses automatic Teams syncs (`block/TeamsAutoSync.kt:40-75`), AES-GCM Android Keystore storage (`data/SecretStore.kt:107-136`), and TOTP codes (`protect/Totp.kt:36-47`, `ProtectionActivity.kt:350-402`), with no parent-email sender. A TOTP is deliberately not bound to one pending request, as the later as-built text correctly acknowledges. The obsolete statements are therefore not merely missing implementation details; they contradict the actual contract. Source locations here are relative to `app/src/main/java/com/thomaswcode/decrastination/`.
- **When it occurs:** Follow a reference section or implement a test directly from the listed edge cases without discovering a later correction in the same document or a separate historical decision. For example, an engineer following the current test list would require request-specific emailed codes that the current design deliberately does not provide.
- **Impact:** Misleading setup and validation expectations, incorrect security assumptions about override scope, and extra maintenance work resolving competing descriptions. This review does not treat clearly labeled historical Phase 0 observations as current defects.
- **Recommended fix:** Make each current reference describe one final contract. Move superseded proposals into a clearly marked historical section and link to the replacement. Align the edge-case checklist with TOTP, uptime-based delays, current storage and automatic sync behavior; retain the documented limitations of TOTP rather than promising request binding.
- **Effort:** S.
