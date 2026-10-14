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
   - **Stability (phase 2 adds the real rule).** For now: adding one minute to `now` moves no chunk whose bucket is after today. *Found at 10 000 seeds (seed 1702): within today's hours a minute less of a day full to the minute pushes its last piece to tomorrow, which moves tomorrow's (the plan has no memory of the last one until PL-13's `previous`); so the check runs where the minute leaves today's time as it was, 277 of the first 300 seeds.*
4. Hold the seed list used in CI small (300 iterations run in well under a minute); the env var lets a developer run 10,000.

**Tests.** This item is the test. Also add `PlannerPropertyTest` to the CI's default run (it is, by being under `app/src/test`).

**Done when.** The test passes on `main` as it is today (if it finds a real bug, fix the planner minimally and record it in `PROGRESS.md`), runs in under 60 s at 300 iterations, and prints the seed on failure.

---

## DX-03 Read only starred emails; every starred email is a task

> **Changed by the owner, 14 Oct, during the build:** "Ensure to read the entire thread of any starred emails: if the parent is starred, or a reply is starred, read the other emails in the chain, even if they are not starred." So a starred message makes its whole conversation the task and every message of it is read, his own replies included (drafts left out). The Starred folder holds only the starred messages, so the read examines **All Mail** instead and finds the starred ones in it with `UID SEARCH FLAGGED`; steps 2, 3 and 6 below are as amended. Found against the code: `Merge.apply` keeps a Gmail task gone from the listing open while its reading is due or while it has dated blocks left (the follow-up rule), which contradicts "unstarred email tasks finish at the first starred-only sync": both rules go (step 3).

**Change.** `GmailSource` reads the conversations of the starred messages instead of the inbox; a conversation is a task while any message of it is starred; unstarring finishes it. The owner has already starred every email he wants to keep, so there's no grace period: unstarred email tasks finish at the first starred-only sync.

**Files.** `sources/gmail/Imap.kt`, `sources/gmail/GmailSource.kt`, `core/Merge.kt`, `sync/Syncer.kt`, `AppGraph.kt`, `ui/SetupScreen.kt`, `ui/TaskSheet.kt`, `ui/TasksScreen.kt`, `docs/data-sources.md` §4.

**Steps.**

1. `ImapClient.list(): List<Mailbox>` where `data class Mailbox(val name: String, val attributes: Set<String>)`: runs `LIST "" "*"` through the existing `run(command, "LIST")`, parses each untagged line `* LIST (<attrs>) "<delim>" <name>`; attributes are the atoms in the first list (keep their backslash, lower-cased); the name is the last value's string (quoted or literal). Unit test in `ImapTest` with a captured Gmail `LIST` response (write it from RFC 3501 §7.2.2 and Gmail's known output: `* LIST (\HasNoChildren \Flagged) "/" "[Gmail]/Starred"`, `* LIST (\All \HasNoChildren) "/" "[Gmail]/All Mail"`; the owner's account names the bin `[Gmail]/Bin`, so names aren't to be relied on).
2. `GmailSource.read`: after `imap.login`, one `list()`; All Mail is the mailbox with `\all`, else one whose name ends `/All Mail`, else `SourceUnavailable("Gmail has no All Mail folder")`. `imap.examine(allMail)`; `uidSearch("FLAGGED")` gives the starred messages; their conversations (`X-GM-THRID`, from what's stored for a known message and the envelope for a new one) are searched in one command per 40, `UID SEARCH NOT DRAFT OR X-GM-THRID a OR X-GM-THRID b …` (`GmailThreads.anyThread`), which gives every message of them. A conversation's task is named by its newest message; its sender is its newest message not his, and it's a note to self only when every message is his (`\Sent`). Its text is every message's, oldest first, each headed `--- From <sender>, <yyyy-MM-dd HH:mm> ---` (`From you` for his; ISO dates, so the rules' date patterns don't read a header as a date) once there's more than one, kept within `GmailThreads.MAX_CONVERSATION_CHARS` (16 000, what the model reads) from the newest back: older messages are cut, then left out with a line saying how many.
3. Finishing: a conversation absent from the read is finished, whatever it holds. `Merge.apply`'s two Gmail rules go: the one keeping an email whose reading was due (and with it `Merge.apply`'s and `Syncer`'s `unread`), and the follow-up rule keeping an archived email with blocks left (`Merge.EXTRA_FOLLOW_UP`, its line on the sheet and its "Archived" pill in Tasks). `MergeTest`'s tests of them become tests that an unstarred email is done, its blocks or its reading notwithstanding; add one test in `GmailTest`: a conversation listed in the last read and absent from this one comes back `completed`.
4. No grace: the owner has starred what he wants; the merge finishes the rest at the first starred-only sync. Nothing to add.
5. Nothing to show for unstarred tasks: they're finished tasks like any other.
6. `SetupScreen`'s Gmail section text: "The app reads your starred emails only, each with its whole conversation. Star an email in Gmail to make it a task; unstar it when it's done." Keep the app-password instructions.
7. `docs/data-sources.md` §4: replace the inbox description with the starred conversations from All Mail; note the special-use lookups (`\All`, and `\Flagged` for the Starred folder, which holds the same starred messages) and the fallback name.

**Tests.** `ImapTest`: `list` parsing. `GmailTest`: `fetched` of a starred-only read, a conversation's sender and note-to-self rule, its text oldest first within the budget; `toRead`/`knownBodies` by conversation. `MergeTest`: an unstarred email finishes.

**Done when.** A sync against a Gmail account lists only the starred messages' conversations, whole (check with `scripts/gmail_probe.py --starred`, which prints each conversation with its starred count); unstarring a thread finishes its task at the next sync.

---

## DX-07 The sync after starred-only

**Change.** For threads already known, fetch flags and labels only; envelopes and texts only for new UIDs; keep `UIDVALIDITY`.

> **As amended for whole conversations (DX-03, 14 Oct):** the unit is the conversation. One whose messages are all known (the same UIDs as stored, the same `UIDVALIDITY`) and whose text is current is built from what's stored; one with a new or a gone message, or whose text is still to read, has every message's envelope fetched, and its texts read whole.

**Files.** `sources/gmail/Imap.kt`, `sources/gmail/GmailSource.kt`.

**Steps.**

1. `ImapClient.examine` returns `Examined(exists: Int, uidValidity: Long?)`: parse the untagged `* OK [UIDVALIDITY n]` line in the EXAMINE response (`ImapLine.text` holds the bracketed code; parse `UIDVALIDITY (\d+)`).
2. `GmailThreads` gains `const val EXTRA_UIDS = "uids"` (the conversation's messages, its text read from exactly those unless `EXTRA_TEXT_PENDING`) and `const val EXTRA_UID_VALIDITY = "uidValidity"`, stored in `Fetched.extra` by `fetched`, with the sender's name and address (`EXTRA_FROM_NAME`, `EXTRA_FROM_ADDRESS`) so a known conversation's task can be rebuilt and triaged again. `knownUids(known: List<TaskItem>, validity: Long?): Map<Long, TaskItem>` returns the known tasks' UIDs when their stored validity equals the mailbox's; otherwise empty (a changed validity means a full read).
3. In `read`: the starred UIDs not known get their envelopes (for their conversation); the conversations' UIDs not known get theirs; a conversation that's unchanged and read is `GmailThreads.stored(task, uids, validity)`, and the rest have their known messages' envelopes fetched too. The "listed N but described M" check stays, on every envelope fetch.
4. Keep `MAX_BODIES` (now counting messages), the time budget and `toRead` (now by conversation, newest first, whole) as they are; `knownBodies` is keyed by the UIDs a stored text was read from.

**Tests.** `GmailTest`: `knownUids`, a stored conversation rebuilt, `toRead` by conversation; `ImapTest`: a read with nothing new sends no `FETCH` (the scripted server).

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

**Done when.** No code path makes a new Gmail task of kind Info; `Kind.Info` stays in the enum so the existing task file loads, and such tasks finish at the first starred-only sync.

---

## DX-06 Strip quoted replies and signatures before the model sees them

**Change.** Cut quoted earlier messages and signatures from an email's text, with a marker saying what was cut.

**Files.** `sources/gmail/Mime.kt`, `sources/gmail/GmailSource.kt`.

**Steps.**

1. `Mime.cutQuoted(text: String, html: Boolean): Cut` where `data class Cut(val text: String, val cutChars: Int)`.
   - HTML (apply before `htmlToText`'s tag stripping): remove every `<blockquote …>…</blockquote>` subtree and every `<div class="gmail_quote"…>…</div>` subtree, matching the closing tag by depth (count nested same-named opening tags). Implement with a small scanner over the string, not regex alone, so nesting is right.
   - Plain text (apply after HTML flattening and to plain parts): cut from the first line matching, trimmed, `^On .{3,200} wrote:$`, `^-----Original Message-----$`, `^From: .+$` when followed within 3 lines by `^Sent: ` or `^To: `, or `^-- $` (signature delimiter). Don't cut at `^---------- Forwarded message ---------$`: a forward's content is below it; cut only at the second quote marker after it.
   - Return the kept text and the number of characters cut.
2. `GmailThreads.CUT_QUOTED = "[Quoted text or a signature left out: %d characters.]"` (amended 14 Oct: with whole conversations the earlier messages are shown, so the marker no longer says they can't be seen). In `GmailSource.text()`, after decoding and before `take(MAX_BODY_CHARS)`, apply `cutQuoted`; append the marker line when `cutChars > 0`; for HTML, `cutChars` counts the text cut, not its markup. The stored `detail` is the cut text with the marker (the full text isn't stored; "Open in Gmail" has it).
3. `Prompts.describe` needs no change: the marker is in the text. Add to `Prompts.system(Email)` (amended): "Quoted text inside a message, and its signature, are left out and marked in square brackets: any earlier message of the conversation it quoted is shown above it, and anything else it quoted you can't see." Gmail wraps a forward in a `gmail_quote` div too: a `gmail_quote` div that begins with the forward marker is kept, and the quotes inside it cut.

**Tests.** `MimeTest`: nested blockquotes removed whole; `gmail_quote` div removed; plain "On … wrote:" cut; signature cut; a forward keeps its forwarded content and cuts the second-level quote; `cutChars` counts.

**Done when.** The eval dump (`EvalSetTest.dump`) of a reply case shows the marker and no quoted thread.

---

## DX-01 Attachment names and types

**Change.** Record each message's attachments (name, type, size) from the BODYSTRUCTURE already fetched; show them on the sheet; tell the model.

**Files.** `sources/gmail/Mime.kt`, `sources/gmail/GmailSource.kt`, `enrich/Prompts.kt`, `ui/TaskSheet.kt`.

**Steps.**

1. `Mime.Attachment(val name: String, val type: String, val size: Long?)` and `fun attachments(structure: ImapValue): List<Attachment>`: walk as `collect` does; a leaf part is an attachment when its disposition is `attachment`, or when its type isn't `text` and it has a filename (`filename` in the disposition's parameter list at index 9, or `name` in the body parameters at index 2). Skip `inline` images without a filename. `type` is `"$type/$subtype"` lower-cased.
2. `GmailSource.text()` returns `Pair<String?, List<Mime.Attachment>>` (rename to `textAndAttachments`); the structure is fetched once already. `GmailThreads.Body` gains `attachments: List<Mime.Attachment>`, every message's in the conversation, each once; `fetched` puts `EXTRA_ATTACHMENTS = "attachments"` into `extra` as a JSON list of them (amended: JSON reads back whole whatever a file is called, where `name (type, size)` lines couldn't); `knownBodies`/`withRead` carry it.
3. `Prompts.describe`, Email branch: after From, `Messages in this conversation: N, all shown, oldest first` from `EXTRA_MESSAGES` when N > 1 (amended: whole conversations are read), and `Attachments: a.pdf (110 KB), b.docx` when any (AI-04). Sizes formatted `KB`/`MB`.
4. `TaskSheet` Notes: a line "Attachments: …" when present; tapping opens Gmail through the existing `TaskOpener`.

**Tests.** `MimeTest`: attachments from a multipart structure with a PDF attachment, an inline logo and a text part; `GmailTest`: `fetched` carries the extra; `EnrichTest`: `describe` prints the two lines.

**Done when.** A starred email with a PDF shows its name on the sheet and in `EvalSetTest.dump`'s user message.

---

## AI-04 Tell the model it sees only the newest message, and what's attached

**Change.** Prompt text only, once DX-01's lines exist.

**Files.** `enrich/Prompts.kt`.

**Steps.** Add to `Prompts.system(Email)` after the kinds (amended 14 Oct for whole conversations): "You see the whole conversation the starred message is in, oldest message first; when there's more than one, each is headed by who sent it and when ("From you" for the student's own). You don't see attachments, only their names. If the email refers to an attachment, or to an earlier message you can't see, don't guess what it asked: make nextStep 'Read the attachment/earlier message and decide' and effortMin 10." And relative dates are worked out from when the message saying them was sent.

**Tests.** Golden files (phase 8) will cover the text; for now `EnrichTest` asserts the sentence is present.

**Done when.** The sentence is in the dumped system prompt.
