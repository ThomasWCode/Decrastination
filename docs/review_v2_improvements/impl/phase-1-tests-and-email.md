# Phase 1: property tests, starred-only Gmail, the Email job without Info

Items: AR-04, DX-03, DX-07, AI-02, DX-06, DX-01, AI-04. No dependency on other phases. Everything here is testable on the JVM.

---

## AR-04 Property tests for the planner's invariants

**Change.** A seeded random test over `Planner.plan` that asserts the planner's rules hold for any input, so phases 2–4 change the planner under a net.

**Files.** New `app/src/test/java/com/thomaswcode/decrastination/core/PlannerPropertyTest.kt`.

**Steps.**

1. A generator, `Gen(seed: Long)`, wrapping `kotlin.random.Random(seed)`, that builds a `Planner.Input` from:
   - `now`: `Fixtures.at("2026-10-14T17:00")` plus a random offset of 0–13 days and 0–23 hours.
   - 1–40 `TaskItem`s via a local `task(...)` helper modelled on `PlannerTest.task` (copy it; don't import a private helper): random `Source` from `Teams, PowerPlanner, Gmail, Own`; `Kind` from `Homework, Revision, Admin`; `dueAt` null (20 %) or `now + 1..30 days` at 08:30 or 23:59; `sourceEffortMin` 5–300; 0–6 `SubStep`s whose minutes sum to the effort (use `shareOut`); `stepsPerDay` null or 1–3 (Anki-like); `notBefore` null or `now + 0..5 days`; `firstSeenAt = now - 0..10 days`; `availableFrom` null or future.
   - `Settings()` with `boxMin` from `{25, 30, 45, 60}`, `marginDays` 0–2, random hours windows (weekday 16:00–22:30, weekend 08:00–23:00, each start < end).
   - 0–5 `Busy` intervals inside the next 14 days, 30–180 min; 0–2 `dayCaps` (random date → 0..300); 0–2 `hardBusy`; `dayLoads` 0–1 entries.
2. `@Test fun invariants()` runs `ITERATIONS` seeds (`System.getenv("PLANNER_PROPERTY_ITERATIONS")?.toInt() ?: 300`), and for each builds the input, calls `Planner.plan`, and checks every invariant below with `assertTrue("seed=$seed: <message>", …)`. One failing seed fails the test with its seed printed; add a `@Test fun seed_N()` reproducing it when you fix one.
3. Invariants (write each as a private `fun check…(plan: Plan, input: Planner.Input)`):
   - **Capacity.** For every bucket, `plannedMin` of chunks with `!behind && !overdue && !dueToday` ≤ `bucket.capacityMin`. (Overdue and due-today work may exceed capacity by design; behind chunks are flagged.)
   - **Deadlines.** For every chunk of a dated task (`chunk.soft == false`), the bucket's date ≤ `Planner.date(chunk.deadline, zone)` or the chunk is `behind` or `overdue`.
   - **Windowed steps.** A chunk with `availableAt != null` is on a date ≥ `date(availableAt)`.
   - **Per-day limit.** A task with `stepsPerDay = n` has at most n chunks in any bucket.
   - **Order.** Within a task, chunks' `part` numbers are non-decreasing by bucket date.
   - **Completeness.** Every open, available, non-hidden, non-waiting task's remaining pieces appear either as chunks or in `plan.unplanned` (sum of minutes matches `pieces` logic: compare the set of task ids, not minutes, to stay robust).
   - **Determinism.** `Planner.plan(input) == Planner.plan(input)` (data class equality on `Plan`).
   - **Stability (phase 2 adds the real rule).** For now: adding one minute to `now` moves no chunk whose bucket is after today.
4. Hold the seed list used in CI small (300 iterations run in well under a minute); the env var lets a developer run 10,000.

**Tests.** This item is the test. Also add `PlannerPropertyTest` to the CI's default run (it is, by being under `app/src/test`).

**Done when.** The test passes on `main` as it is today (if it finds a real bug, fix the planner minimally and record it in `PROGRESS.md`), runs in under 60 s at 300 iterations, and prints the seed on failure.

---

## DX-03 Read only starred emails; every starred email is a task

**Change.** `GmailSource` reads the Starred mailbox instead of the inbox; a thread is a task while any message of it is starred; unstarring finishes it. A seven-day grace keeps existing unstarred email tasks open with a visible line.

**Files.** `sources/gmail/Imap.kt`, `sources/gmail/GmailSource.kt`, `sources/TaskSource.kt` (no change needed; see step 4), `data/Settings.kt`, `protect/SettingsChanges.kt`, `ui/TasksScreen.kt`, `ui/SetupScreen.kt`, `docs/data-sources.md` §4.

**Steps.**

1. `ImapClient.list(): List<Mailbox>` where `data class Mailbox(val name: String, val attributes: Set<String>)`: runs `LIST "" "*"` through the existing `run(command, "LIST")`, parses each untagged line `* LIST (<attrs>) "<delim>" <name>`; attributes are the atoms in the first list (keep their backslash, lower-cased); the name is the last value's string (quoted or literal). Unit test in `ImapTest` with a captured Gmail `LIST` response (write it from RFC 3501 §7.2.2 and Gmail's known output: `* LIST (\HasNoChildren \Flagged) "/" "[Gmail]/Starred"`).
2. `GmailSource.read`: after `imap.login`, `val starred = imap.list().firstOrNull { "\\flagged" in it.attributes }?.name ?: imap.list().firstOrNull { it.name.endsWith("/Starred", ignoreCase = true) }?.name ?: throw SourceUnavailable("Gmail has no Starred folder")`. Call `list()` once, not twice. Then `imap.examine(starred)` instead of `examine("INBOX")`. The rest of the read (UID SEARCH ALL, envelope fetch in batches, texts) is unchanged: the folder is small.
3. Finishing: nothing to change in `Merge.apply`: a thread absent from the read is finished as an archived one is today. Verify with the existing `MergeTest` and add one test in `GmailTest`: a thread listed in the last read and absent from this one comes back `completed`.
4. Grace for the switch. Add `Settings.gmailStarredSince: Long? = null` (LOOSER `NEVER`, LABELS "Gmail reads starred mail since", not shown in the Settings screen). In `GmailSource.read`, after the starred list is known: `val since = context.settings.gmailStarredSince`; if null, the source can't write settings (it's pure), so return `SourceRead(items, note = NOTE_STARRED_FIRST)` and let `AppGraph` set the setting: in the `syncer.addListener` block, when `tasks.status(Source.Gmail).note == NOTE_STARRED_FIRST && settings.value.gmailStarredSince == null`, `changeSettings { it.copy(gmailStarredSince = clock.now()) }`. While `since == null || now < since + GRACE_MS` (7 days): for every known open Gmail task (`context.known.filter { it.source == Source.Gmail && it.isOpen }`) whose `sourceId` isn't among the starred threads, re-emit a `Fetched` built from the task's stored fields (`sourceId`, `title`, `kind`, `detail`, `dueAt`, `availableFrom`, `sourceEffortMin`, `extra + (EXTRA_UNSTARRED to "true")`). After the grace they're not re-emitted and the merge finishes them.
5. `GmailThreads.EXTRA_UNSTARRED = "unstarred"`. In `TasksScreen`, a folded section "Not starred: star them in Gmail to keep them" listing open Gmail tasks with that extra (above "Not tasks, as you said"). After phase 6 this moves into the Work view's foot.
6. `SetupScreen`'s Gmail section text: "The app reads your starred emails only. Star an email in Gmail to make it a task; unstar it when it's done." Keep the app-password instructions.
7. `docs/data-sources.md` §4: replace the inbox description with the Starred-folder one; note the `\Flagged` special-use lookup and the fallback name.

**Tests.** `ImapTest`: `list` parsing. `GmailTest`: `fetched` of a starred-only read; grace re-emits an unstarred known task within 7 days and not after; `toRead`/`knownBodies` unchanged. `MergeTest`: unchanged behaviour covers finishing.

**Done when.** A sync against a Gmail account lists only starred threads (check with `scripts/gmail_probe.py` extended with `--starred`, or by hand); unstarring a thread finishes its task at the next sync; the grace section shows and then empties.

---

## DX-07 The sync after starred-only

**Change.** For threads already known, fetch flags and labels only; envelopes and texts only for new UIDs; keep `UIDVALIDITY`.

**Files.** `sources/gmail/Imap.kt`, `sources/gmail/GmailSource.kt`.

**Steps.**

1. `ImapClient.examine` returns `Examined(exists: Int, uidValidity: Long?)`: parse the untagged `* OK [UIDVALIDITY n]` line in the EXAMINE response (`ImapLine.text` holds the bracketed code; parse `UIDVALIDITY (\d+)`).
2. `GmailThreads` gains `const val EXTRA_UID = "uid"` and `const val EXTRA_UID_VALIDITY = "uidValidity"`, stored in `Fetched.extra` by `fetched`. `knownUids(known: List<TaskItem>, validity: Long?): Map<Long, TaskItem>` returns the known tasks' UIDs when their stored validity equals the mailbox's; otherwise empty (a changed validity means a full read).
3. In `read`: `val uids = imap.uidSearch("ALL")`; split into `known = uids.filter { it in knownUids }` and `new = uids - known`. For `known`, nothing further is needed (the Starred folder listing already says the thread is still starred); for `new`, the existing envelope fetch. Build `messages` from the known tasks' stored envelope fields (title, from, received, messageId, threadId, sent) plus the new messages' envelopes. The `messages.size < uids.size` check stays, counting both.
4. Keep `MAX_BODIES`, the time budget and `toRead` as they are.

**Tests.** `GmailTest`: a read with two known UIDs and one new fetches one envelope (assert on the fake `ImapClient`'s commands if the test has one; else test `knownUids` and the split as pure functions).

**Done when.** A sync with no new starred mail sends no envelope FETCH (log the IMAP commands at debug level and check once by hand).

---

## AI-02 The Email job after starred-only: no Info, always a next step

**Change.** Remove `Info` from the Email job's answers and prompt; a starred email is always something to do.

**Files.** `enrich/Prompts.kt`, `sources/gmail/EmailRules.kt`, `core/Task.kt` (no change: `Kind.Info` stays for old data), `app/src/test/.../enrich/EvalSetTest.kt`, `docs/data-sources.md` §5.

**Steps.**

1. `Prompts.schema(Email)`: `"kind" to mapOf("type" to "string", "enum" to listOf("Admin", "Event"))`.
2. `Prompts.system(Email)`: replace the first paragraph's last sentence and the `kind` bullet with:
   ```
   Every email you see was starred by the student: it is something to do. An email the student sent themselves is a note of something they mean to do.

   Decide, from the email:
   - kind: "Admin" if it asks the student to do something (reply, sign, pay, fill in a form, bring or prepare something), or if it's information they starred to come back to (then the task is to read it and decide, and nextStep says what to decide); "Event" if it's about something happening at a set time that needs nothing beyond turning up (a trip, a call, an open evening).
   ```
   Change the `effortMin` bullet's "about 2 for Info" to "at least 5 for something only to read and decide".
3. `Answers.parse`, Email branch: `kind` accepts `Admin` and `Event` only; anything else returns null (unreadable, so the rules stand).
4. `EmailRules.triage`: where it returns `Kind.Info`, return `Kind.Admin` with `effortMin = 5` and `nextStep = "Read it and decide"`. Keep the self-note rule (Admin, 10 min).
5. `EvalSetTest.cases()`: filter out cases whose `expect.kind == "Info"` with a note in the report ("retired: Info isn't an answer since starred-only reading"); don't delete the private files.
6. `docs/data-sources.md` §5: the Email job's kinds.

**Tests.** `EnrichTest`: an Email answer with `kind: "Info"` is unreadable; the rules' triage of a newsletter is Admin 5 min. Existing tests expecting Info change accordingly.

**Done when.** No code path makes a new Gmail task of kind Info; old Info tasks still load and still count as `justAnEmail` (hidden) until they finish.

---

## DX-06 Strip quoted replies and signatures before the model sees them

**Change.** Cut quoted earlier messages and signatures from an email's text, with a marker saying what was cut.

**Files.** `sources/gmail/Mime.kt`, `sources/gmail/GmailSource.kt`.

**Steps.**

1. `Mime.cutQuoted(text: String, html: Boolean): Cut` where `data class Cut(val text: String, val cutChars: Int)`.
   - HTML (apply before `htmlToText`'s tag stripping): remove every `<blockquote …>…</blockquote>` subtree and every `<div class="gmail_quote"…>…</div>` subtree, matching the closing tag by depth (count nested same-named opening tags). Implement with a small scanner over the string, not regex alone, so nesting is right.
   - Plain text (apply after HTML flattening and to plain parts): cut from the first line matching, trimmed, `^On .{3,200} wrote:$`, `^-----Original Message-----$`, `^From: .+$` when followed within 3 lines by `^Sent: ` or `^To: `, or `^-- $` (signature delimiter). Don't cut at `^---------- Forwarded message ---------$`: a forward's content is below it; cut only at the second quote marker after it.
   - Return the kept text and the number of characters cut.
2. `GmailThreads.CUT_QUOTED = "[Earlier messages in this thread were left out: %d characters.]"`. In `GmailSource.text()`, after decoding and before `take(MAX_BODY_CHARS)`, apply `cutQuoted`; append the marker line when `cutChars > 0`. The stored `detail` is the cut text with the marker (the full text isn't stored; "Open in Gmail" has it).
3. `Prompts.describe` needs no change: the marker is in the text. Add to `Prompts.system(Email)`: "A line in square brackets at the end saying earlier messages were left out means the email is a reply or a forward whose history you can't see."

**Tests.** `MimeTest`: nested blockquotes removed whole; `gmail_quote` div removed; plain "On … wrote:" cut; signature cut; a forward keeps its forwarded content and cuts the second-level quote; `cutChars` counts.

**Done when.** The eval dump (`EvalSetTest.dump`) of a reply case shows the marker and no quoted thread.

---

## DX-01 Attachment names and types

**Change.** Record each message's attachments (name, type, size) from the BODYSTRUCTURE already fetched; show them on the sheet; tell the model.

**Files.** `sources/gmail/Mime.kt`, `sources/gmail/GmailSource.kt`, `enrich/Prompts.kt`, `ui/TaskSheet.kt`.

**Steps.**

1. `Mime.Attachment(val name: String, val type: String, val size: Long?)` and `fun attachments(structure: ImapValue): List<Attachment>`: walk as `collect` does; a leaf part is an attachment when its disposition is `attachment`, or when its type isn't `text` and it has a filename (`filename` in the disposition's parameter list at index 9, or `name` in the body parameters at index 2). Skip `inline` images without a filename. `type` is `"$type/$subtype"` lower-cased.
2. `GmailSource.text()` returns `Pair<String?, List<Mime.Attachment>>` (rename to `textAndAttachments`); the structure is fetched once already. `GmailThreads.Body` gains `attachments: List<String>` (each `name|type|size`), `fetched` puts `EXTRA_ATTACHMENTS = "attachments"` into `extra` as lines `name (type, size)` joined by `\n`; `knownBodies`/`withRead` carry it.
3. `Prompts.describe`, Email branch: after From, `Messages in this conversation: N (only the newest is shown; earlier ones may hold the request)` from `EXTRA_MESSAGES` when N > 1, and `Attachments: a.pdf (110 KB), b.docx` when any (AI-04). Sizes formatted `KB`/`MB`.
4. `TaskSheet` Notes: a line "Attachments: …" when present; tapping opens Gmail through the existing `TaskOpener`.

**Tests.** `MimeTest`: attachments from a multipart structure with a PDF attachment, an inline logo and a text part; `GmailTest`: `fetched` carries the extra; `EnrichTest`: `describe` prints the two lines.

**Done when.** A starred email with a PDF shows its name on the sheet and in `EvalSetTest.dump`'s user message.

---

## AI-04 Tell the model it sees only the newest message, and what's attached

**Change.** Prompt text only, once DX-01's lines exist.

**Files.** `enrich/Prompts.kt`.

**Steps.** Add to `Prompts.system(Email)` after the kinds: "You see the newest message only. If it refers to an earlier message or an attachment you can't see, don't guess what it asked: make nextStep 'Read the earlier message/attachment and decide' and effortMin 10."

**Tests.** Golden files (phase 8) will cover the text; for now `EnrichTest` asserts the sentence is present.

**Done when.** The sentence is in the dumped system prompt.
