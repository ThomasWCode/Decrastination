# Data sources: what was found, and how each will be read

Everything here was verified on the phone or in the upstream source on 7 October 2026 unless marked **verify**.

## 1. Teams assignments (`com.teamsassignments.widget`)

**Where the data lives.** `/data/data/com.teamsassignments.widget/files/widget_state.json`, written atomically by `AssignmentStore` (`app/src/main/java/com/teamsassignments/widget/data/AssignmentStore.kt`). Debug build, so `adb shell run-as com.teamsassignments.widget cat files/widget_state.json` reads it from the PC; another app on the phone cannot.

**Shape** (`Assignment.kt`):

```json
{
  "assignments": [
    {
      "key": "<Teams GUID or h-xxxxxxxx fallback>",
      "title": "Statics Prep",
      "className": "12.2-PH3",
      "description": "Page 7-11 of booklet.",
      "dueText": "Due 5 October 2026 14:30",
      "dueAt": 1791207000000,
      "tab": "Forthcoming" | "PastDue",
      "detailReadAt": 1791…,
      "lastSyncedAt": 1791…
    }
  ],
  "lastSuccessAt": 1791351744439,
  "status": {"type": "idle" | "running" | "failed" | "stopped", …},
  "classColors": {"12.2-PH3": 3},
  "handedIn": {"<key>": 1791…},
  "handedInWork": [ …assignments as they stood when handed in… ]
}
```

**Access method: a read-only ContentProvider in the Teams widget app** (built in widget 0.3.0, TeamsAssignmentsWidget #13; checked on the phone 8 Oct, `docs/phase0-findings.md` §1). The contract is the widget's `provider/AssignmentsContract.kt`.

Manifest in the Teams widget: a `signature` permission `com.teamsassignments.widget.permission.READ_ASSIGNMENTS`, and `.provider.AssignmentsProvider` at authority `com.teamsassignments.widget.assignments`, exported, with `android:permission` set to it (queries; `call()` checks it in code, since Android checks no permission on calls).

| URI or call | Returns |
|---|---|
| `content://com.teamsassignments.widget.assignments/assignments` | One row per assignment not handed in, in the widget's order (due time, undated last, then title): `key, title, class_name, description, due_text, due_at, tab, detail_read_at, last_synced_at` |
| `content://com.teamsassignments.widget.assignments/state` | One row: `last_success_at, status` (`idle`/`running`/`failed`/`stopped`), `status_message, status_at, assignment_count, sync_service_enabled` |
| `call(root, "requestSync", null, null)` | Starts a sync as ↻ does. Result: `started`, and `reason` (`service_off`, `busy`) when not |
| `call(root, "open", key, null)` | Opens that assignment in Teams as a row tap does. One more `reason`: `unknown_key` |

The provider collects the store's `StateFlow` and calls `notifyChange` on the root for every change, so a `ContentObserver` on the root (or either path) hears of hand-ins seen while Teams is open. Notifications to an app in the background arrived 10 s late on the phone, so they're a prompt to re-read, not a clock.

Decrastination's manifest: `<uses-permission>` for it, the same `<permission>` declared identically (so install order doesn't matter), and `<queries><package android:name="com.teamsassignments.widget"/></queries>` (package visibility on API 30+). Queries and calls block while the widget's process starts, so they run off the main thread.

**Signature caveat, resolved.** A `signature` permission is granted only when both APKs are signed by the same key. The Teams widget repo commits its own debug key (`TeamsAssignmentsWidget/app/debug.keystore`, alias `androiddebugkey`, password `android`, SHA-256 `9A:48:B9:F9:…:7B:F6`) and signs every debug build with it, CI included; the APK installed on the phone carries that exact certificate (checked with `apksigner` tonight). This PC's default `~/.android/debug.keystore` is a *different* key (`35:17:80:81:…`). So Decrastination copies the committed keystore into its own `app/` and declares the same `signingConfigs.debug` block; then both apps share a signer, the permission is granted silently, and the Teams widget never needs reinstalling. `scripts/pull_teams_state.ps1` compares the installed certificate with that keystore.

**Completion test.** Assignment key absent from `/assignments` (and present in `handedIn` memory, or simply gone). Decrastination treats "gone" as done, matching the widget's own semantics.

**Built in Phase 1** (`sources/teams/TeamsSource.kt`): each row becomes a Homework task keyed `teams:<key>`; the state row's `last_success_at` is shown as when Teams itself was last read, and a failed or running widget sync, or its service being off, as a note under the source.

**Opening an assignment.** `call("open", key)`: the widget's service navigates Teams to the card by its id, as a widget row tap does, so Decrastination's **Open** lands on the exact assignment.

**Freshness.** Manual sync only (plus read-along while Teams is open). Decrastination calls `requestSync` from the block screen's **Refresh Teams** button and once in the morning routine, never silently in the background, because a sync takes over the screen. On 8 Oct both calls started but the widget's own sync and navigation then failed, because Teams now pages a Past due list of seven or more behind a "load more" placeholder; widget 0.3.1 (TeamsAssignmentsWidget #14) brings the placeholder into view, and its syncs work again (`docs/phase0-findings.md` §1).

## 2. Power Planner (`com.barebonesdev.powerplanner` 2609.30.191.0)

**On the phone:** Xamarin app, content providers are only Mono/Firebase/startup/file-provider internals, nothing on `/sdcard/Android/data`. The two widgets (`WidgetAgendaProvider`, `WidgetScheduleProvider`) are the only exposed surface, and reading them means screen scraping.

**Access method: the web API used by the open-source web app** (`github.com/powerplanner/powerplannerwebapp`, `src/src/api/util.ts` and `index.ts`; request/response models in `github.com/powerplanner/shared`, `PowerPlannerSending/WebRequests.cs` and `Requests.cs`).

Base URL `https://web.api.powerplanner.net/api`. Every request is `POST` with `Content-Type: application/json` and header `HashedKey: 3a4d3d842fd5c63e8c8ba5677c18abcc59affe2f3a8179081180d56a67376a74` (the "rate-limited development key" the web app ships with).

| Endpoint | Body | Response |
|---|---|---|
| `/LoginWeb` | `{"Username", "Password"}` | `{"AccountId": long, "Session": string, "Error": string?}` |
| `/GetSelectedSemesterId` | `{"Login": L}` | selected semester GUID |
| `/GetYearsAndSemesters` | `{"Login": L}` | years → semesters → classes |
| `/GetAgenda` | `{"Login": L, "SemesterIdentifier": guid, "CurrentTime": iso}` | `{"Items": [ListItem…], "Classes": null, "Error"}` (`Classes` came back null on 8 Oct) |
| `/GetClassesAndSchedules` | `{"Login": L, "SemesterIdentifier": guid}` | `{"WeekOneStartsOn", "Classes": [{Identifier, Name, Color, Schedules: [{StartTime, EndTime, DayOfWeek, ScheduleWeek, Room, …}]}]}` |
| `/GetItemsForRange` | `{"Login": L, "SemesterIdentifier", "StartDate", "EndDate"}` | same item shape |
| `/GetHomework` / `/GetExam` | `{"Login": L, "Identifier": guid}` | full `Details`, `PercentComplete`, `ClassName`, `ClassColor` |

where `L = {"AccountId": accountId, "Username": username, "Password": session}` (the session token goes in the `Password` field; that is how the web app does it).

List item fields (verified with your account on 8 Oct, `fixtures/powerplanner_agenda.json`): `PercentComplete, Identifier, DateCreated, Name, ShortDetails, Date, ClassIdentifier, ItemType`. `ItemType` 5 is a task (Homework in the API), 6 an event (Exam). `Date` is local time with no zone, and its seconds carry the time option: for a task with a class, `:00` start of class, `:01` before class, `:02` during, `:03` end of class, `:04` a set time, else all day; for one with no class, `:04` a set time, else all day. A task with no class has the semester's id as its `ClassIdentifier`. Class-relative options resolve against the two-week timetable from `GetClassesAndSchedules` (`ScheduleWeek` 1, 2 or 3 for both, counted from `WeekOneStartsOn`; `DayOfWeek` 0 is Sunday): *Pg 60&61*, `:01` in German on Friday 25 Sep (week 1), was due at 12:20. `docs/phase0-findings.md` §2.

Live check on 7 Oct: `POST /LoginWeb` with a nonsense username returned HTTP 200 `{"AccountId":0,"Session":null,"Error":"No account under that username exists. Check your username."}`. On 8 Oct the probe logged in with your account and read both items and all 18 classes.

**Credentials.** Username and password in EncryptedSharedPreferences; session token cached and refreshed on an `Error` response. The mobile apps' richer sync API (`data.powerplanner.net/api/Sync`) needs a device registration flow that lives in a closed NuGet (`PowerPlannerAppAuthLibrary`), so the web API is the practical choice.

**Completion test.** `PercentComplete >= 1.0`, or the identifier no longer in the agenda. Events (exams/"Call with Jags") are `Event` kind: they are not tasks to do but reduce available time and may carry prep.

**Built in Phase 1** (`sources/powerplanner/`): the session is kept in the encrypted store and reused until a call fails, then one fresh login is tried; the semester and timetable are read at most every 6 hours, so a sync is usually one `GetAgenda` call. A task with a class is Homework, one without Admin. An all-day task is due at 23:59; Power Planner's "no due date" (31 Dec 1999) means no deadline; a class-relative time on a day without that class falls back to all day. Note: *Call with Jags after school* is a task in Power Planner, not an event, so it's planned as 15 minutes of admin.

**Opening an item.** No documented deep link into the Android app; **Open** launches the app's main activity. If a `powerplanner://` scheme turns up in the manifest (`adb shell dumpsys package com.barebonesdev.powerplanner | grep -A3 "Scheme"`), use it.

**Fallback if there is no online account:** accessibility read of the Agenda screen, same technique as the Teams widget, or the Agenda widget's `RemoteViews` tree via the launcher window. Both fragile; both documented here only as last resort.

## 3. AnkiDroid (`com.ichi2.anki` 2.25.1)

**Access method: the official content provider**, authority `com.ichi2.anki.flashcards` (`CardContentProvider`). Contract source: `api/src/main/java/com/ichi2/anki/FlashCardsContract.kt`. Library: JitPack `com.github.ankidroid:Anki-Android:api-v1.1.0` (confirmed resolvable). The dependency merges `<uses-permission android:name="com.ichi2.anki.permission.READ_WRITE_DATABASE"/>` into the manifest; it is `dangerous`, so request it at runtime once.

| URI | Use |
|---|---|
| `content://com.ichi2.anki.flashcards/decks` | columns `deck_name, deck_id, deck_count, options, deck_dyn, deck_desc`. `deck_count` is a JSON array of today's counts, `[learn, review, new]` (AnkiDroid's `CardContentProvider.kt` builds it from `lrnCount, revCount, newCount`; every deck read `[0,0,20]` on 8 Oct). Names are full paths: `Textbook 1::1.2` |
| `content://…/decks/<id>` | one deck |
| `content://…/selected_deck` | `update` with `deck_id` selects the deck AnkiDroid opens next |
| `content://…/schedule` | the next due cards (`note_id, ord, button_count, next_review_times`), optional `deckID` and `limit` query params |
| `content://…/notes` with `selection` in Anki search syntax | e.g. `deck:1.2 is:new` to count untouched cards per deck |
| intent `com.ichi2.anki.DO_SYNC` | AnkiWeb sync, at most once per 5 min |

Querying from `adb shell` fails with `Permission not granted for: CardContentProvider.query /decks (com.android.shell)`, as expected. From the probe app, after **Allow** on the runtime prompt, it returned all 57 decks (8 Oct, `docs/phase0-findings.md` §3).

**What the deck picker showed tonight** (`fixtures/anki_decks.json`): Extras, GCSE Vocab, Textbook 1, 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 4.1, 4.2, 4.3, 5.1 (and more below the fold); every row 20 new / 0 learn / 0 review; header "100 cards due"; "Studied 0 cards … today".

**Linking homework to decks.** German instructions seen on 7 Oct: "Learn vocabulary column 1.2 Familie und Ehe", "Learn vocabulary p46-47/ 2.2/2.3", "Learn vocabulary - verschiedene Familienformen". Regex `\b([1-9]\.[1-9])\b` over the instructions finds the section, but sections repeat across `Textbook 1` and `Textbook 2`, so it maps to `Textbook N::x.y` for the current textbook, a setting that starts at Textbook 1 (Q18, decided 8 Oct). The enrichment reads section numbers the regex misses in wordier instructions, and the regex result always wins when present. A topic named without its number ("verschiedene Familienformen") can't be matched: the decks are named by number only, and the app doesn't have the textbook's section titles (tried 9 Oct).

**Task derivation.**
- *Daily quota task* (every day): "Anki: N reviews due + M new" with deadline 21:00. Effort ≈ reviews × 8 s + new × 25 s. Quota (your answer, 7 Oct): all due reviews plus 20 new cards from the lowest-numbered deck that still has new cards, plus any deck a German assignment names.
- *Homework deck task*: for each assignment with linked decks, "Learn deck 1.2 (k cards still new)", deadline = assignment due time (or the test date if the instructions name one), effort = new cards × 25 s + due × 8 s.

**Completion test.** For a deck task: `new + learn + review == 0` for that deck. For the daily quota: all review/learn counts zero and new cards introduced ≥ quota (new-introduced-today is derived as `min(quota, newAtStartOfDay − newNow)`).

**Opening a deck.** `update(selected_deck, deck_id)` then start `com.ichi2.anki/.Reviewer`, which is exported and opens on the selected deck (checked on the phone on 9 Oct: deck 1.2's first new card). The daily quota spans every deck, so it opens the deck list instead, with its deck highlighted.

**Built in Phase 1** (`sources/anki/`), with three refinements found on the phone:

- **The quota's deck is fixed at the Anki day's first read** (Anki's day starts at 04:00) and kept for the day, so finishing it doesn't move the quota on to the next deck. A deck showing no new cards may still have unseen ones, held back by its daily limit of 20 if you studied before that read; the choice asks Anki (`deck:"…" is:new`) rather than trusting today's count. It's done once nothing is due anywhere and that deck has no new cards left today.
- **"Cards still new" counts notes** (`content://…/notes` with Anki's own search, `deck:"Textbook 1::1.2" is:new`): the provider has no card count. A deck learnt "both ways" has two cards per note, so its estimate is low until calibration catches up. On 8 Oct: 1.2 had 72 unseen notes, 2.2 61, 2.3 36.
- **A homework deck task lasts while its assignment is open**, in steps of 20 new cards (the daily limit). If the assignment is handed in first, the deck task is dropped as missed, not counted as done: only Anki's counts say a deck is done.

## 4. Gmail (`thomasawhite321@gmail.com`)

**Access method: IMAP with a Google app password** (requires 2-Step Verification on the account). Host `imap.gmail.com:993`, TLS. Verified on 8 Oct with your app password (`scripts/gmail_probe.py`, read-only): capabilities include `X-GM-EXT-1` and `IDLE`; there is no Snoozed folder or label over IMAP, so a snoozed message just leaves INBOX until it wakes; your own notes carry the `\Sent` label. `docs/phase0-findings.md` §4.

Operations the app needs:

| Need | IMAP |
|---|---|
| List inbox | `SELECT INBOX`, `UID SEARCH ALL` (or `SINCE` for incremental), `UID FETCH … (UID FLAGS ENVELOPE BODYSTRUCTURE)` |
| Body for triage | `UID FETCH … BODY.PEEK[TEXT]` or the first `text/plain` part; strip HTML, cap at 20 000 chars, of which the LLM reads up to 16 000 |
| Completion test | `UID SEARCH X-GM-MSGID <id>` in INBOX; absent = archived/snoozed/deleted = done for now; back in INBOX later (a snooze waking) = the same task, reopened |
| Open the message | **The Gmail app can't be opened on one conversation from outside**: it answers `https://mail.google.com/mail/u/0/#inbox/<thread id in hex>` and `#all/…` by opening its inbox (tried on the phone, 9 Oct). So **Open** opens Gmail and says which subject, from whom, to look for; the inbox is in date order, so it's near the top. |

Library: `jakarta.mail` works on Android with the `android-mail`/`android-activation` artifacts (`com.sun.mail:android-mail:1.6.7`), or a 200-line hand-rolled IMAP client over `SSLSocket` since only `SELECT`, `UID SEARCH`, `UID FETCH` are needed. The hand-rolled one has no dependency risk and is my recommendation.

**Inbox tonight (24 messages, headers only):** self-sent notes ("Zip card", "Use Jev for thinking mod.", "Imperial Physics Talk", "Quizlet vocab lists", "german books/radio/tv", "Grandparents 999", "Shoot a new profile picture"), a Warwick Open Day ticket for 10 Oct (needed on the day, no action before), LinkedIn notifications, Sportograf photo links, two Drive share notifications, a bank-switch report, and a parent's email with a calendar of 2027 work-experience deadlines (30 Oct 2026 Imperial STEM Potential opens; December NPL/Diamond; January RAL).

**Built in Phase 1** (`sources/gmail/`): a 300-line IMAP client (`Imap.kt`) that only ever sends `LOGIN`, `EXAMINE`, `UID SEARCH`, `UID FETCH` with `BODY.PEEK`, and `LOGOUT`, so it can't change the mailbox. What changed from the plan above:

- **One task per conversation** (`X-GM-THRID`), not per message: the inbox shows and archives conversations, so three messages in one thread are one thing to deal with. Its title is the newest message's subject; it's done once no message of it is in INBOX.
- **A message's text** comes from its first plain part, else its HTML, found from `BODYSTRUCTURE`; the part is fetched whole (up to 200 KB, plain or HTML), since Warwick's Open Day email had 50 KB of styles before its first sentence, and invisible padding (`&zwnj;` and zero-width characters, 4 000 of them in one plain part) is dropped before the text is cut to 20 000 characters: past the 16 000 the model reads (`Prompts.MAX_TEXT`), so it's told when there's more. Text is fetched once per conversation, again only when a new message arrives in it, or when it was cut shorter than reads now go. Until 1.1.1 the cut was at 4 000, which left the second half of your dad's work-experience email (8 500 characters, its dated timeline included) unread; each task records how far its text was read, and text stored before it did is read again once (those reads stopped at 4 000 characters, or sooner at 32 KB of a part). Until an email's text is read as far as reads now go it isn't asked about, as one whose text is still to be fetched isn't, so nothing is planned from part of an email; one that can't be read again (gone between the listing and the fetch) keeps the text it had.
- **Android 15+ cuts an app's network a few seconds after it leaves the screen**, mid-connection ("Software caused connection abort", on the phone on 8 Oct). Every sync not started from a screen in use therefore runs as a WorkManager job, which keeps its network.

**Triage rules (always on, LLM optional on top):**
- From yourself → `Admin`, actionable now, effort 15 min unless the LLM says otherwise.
- Known noise senders (LinkedIn, marketing) → `Info`, suggested action "archive".
- Contains a date in the future and words like ticket/booking/open day → `Event` with `availableFrom = date − 1 day`.
- Everything else → `Admin`, actionable now, "read and decide".

**Alternative not taken: Gmail API (OAuth).** Cleaner scopes, proper message ids and deep links, labels; cost is a Google Cloud project, consent screen, and either 7-day token expiry in "Testing" or publishing unverified. You chose IMAP (Q2, 7 Oct).

**Sending, for the parent-held override only.** The app sends two kinds of email, both to `richard.white@lshtm.ac.uk`: a pending-change notice carrying a one-time code, and a "protection off for over an hour" alert. They go over SMTP (`smtp.gmail.com:465`, implicit TLS, `AUTH PLAIN`) from a **dedicated mailbox whose credentials your dad enters at setup**, not from your own account, because a code sent from your Gmail would be readable in your Sent folder. The same hand-rolled client approach works for SMTP (`EHLO`, `AUTH`, `MAIL FROM`, `RCPT TO`, `DATA`); messages are plain text with the request description, the code, and when the change would apply on its own. Design and the authenticator-app alternative: `docs/scheduler.md` §6.

## 5. The LLM enrichment

Decided 7 Oct: Anthropic API key, `claude-opus-5-5`, high effort (you have $200 of prepaid credit and no card on the account, so cost is not the constraint: calls simply stop when the credit runs out; a monthly cap in Settings is optional). Five bounded jobs, each a structured-output call cached by a hash of its input so re-syncs never re-send:

1. **Email triage** → `{kind: Admin|Event|Info, actionableFrom: date?, deadline: date?, effortMin: int, nextStep: string, blocks: [{title, minutes, from: date?, due: date?}]}`.
2. **Assignment split** → `{subSteps: [{title, minutes, from: date?, due: date?}], effortMin: int, ankiDecks: [string], testDate: date?}`. Input: title, class, instructions, due time, and (for calibration) the class's current effort multiplier. Sub-steps are what the planner places into day buckets.
3. **Effort for Power Planner items** with no better signal, and blocks for one big enough to do in parts: `{effortMin: int, blocks: [{title, minutes, from: date?, due: date?}]}`.
4. **Weekly review** (Sunday evening): the week's completion log and current calibration in, bounded parameter proposals and a five-line note out. `docs/scheduler.md` §5.
5. **Photo check** (Phase 5): an image of written work plus the chunk description in, `{done: bool, confidence, reason}` out.

Request shape per the Claude API guidance: adaptive thinking left on, `output_config.effort = "high"` (the weekly review may use `"xhigh"`), JSON schema via `output_config.format`, `max_tokens` 4 096 for the split and review calls, server-side `fallbacks: "default"` enabled, `usage` recorded per call against the cap. Kotlin uses the Anthropic Java SDK (`com.anthropic:anthropic-java`); if its transitive dependencies prove awkward on Android, the raw Messages endpoint is one `POST` with three headers.

Cost at list price ($4 / $20 per million tokens in/out): an email with a 4 000-character body is ~1 200 input tokens and ~150 output tokens, ≈ $0.008 (the longest the model reads, 16 000 characters, ~4 200 input tokens, ≈ $0.02); an assignment split with thinking at high effort perhaps $0.05; the weekly review with a week of logs perhaps $0.30; photo checks a few cents each. A busy week is well under $2, so under £10 a month even with every optional job on. The key is pay-as-you-go and separate from a claude.ai subscription.

**As built (Phase 4, 9 Oct): built, and off until you switch it on (decided 8 Oct: no paid calls yet).**
- **The rules always run** (`enrich/Enricher.kt`): an assignment whose instructions list its parts ("1. Learn vocabulary…", "- Translation…") gets them as its steps, sharing its estimate; on the phone that split three of the eleven assignments. A part that's learning numbered vocabulary is tagged with its sections. An email's kind, estimate and next step already come from the email rules at the source.
- **Vocabulary and the decks**: a step that's only about learning vocabulary names its sections, and the planner leaves it out where the current textbook's Anki deck tasks for that assignment hold them (open or finished), so the cards aren't planned twice; where no deck does (a section the textbook's decks lack), it's planned like any other step. A step that asks for anything else as well ("…and revise the grammar") stays planned whole. When the enrichment finds sections the deck pattern missed, Anki is read again at once for their decks.
- **Claude, once switched on** (`enrich/ClaudeEnricher.kt`, `enrich/Prompts.kt`), does jobs 1–3 through the Anthropic Java SDK: Opus 5.5 at high effort, the answer held to a JSON schema (every field required, optional ones nullable, every object closed), and the server-side fallback on (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`). A refusal or an answer cut off at the token limit counts as no answer. `max_tokens` is 16 000, not 4 096: thinking counts towards it, and a call costs only what it uses.
- **What it says** is kept on the task (`Enrichment`) and laid over the source's values at every merge, so a sync doesn't undo it. The source's deadline wins (a test before it brings it forward, and an email's own deadline counts where the source has none), the later start date counts unless it's after the deadline, its estimate and an email's kind apply, and its steps fill in where the source gives none. Each fresh enrichment is laid over what the source said, not over the last one, so a deadline or estimate it no longer gives is gone. Answers out of range are dropped, not trusted: an estimate over ten hours, a date more than a month back or a year ahead, a section that isn't like "1.2".
- **Blocks (Q12, 1.1.0)**: any of the three jobs can come back in blocks of work (an assignment's steps; an email's or a planner item's blocks), each with its own dates where it has them: `from`, before which it can't be done, and `due`, its own deadline. The planner places each run of blocks with the same dates in its own window (`core/Planner.kt`, `windows`), so one email holding a calendar of deadlines becomes work spread over the months; a block opening past the plan's reach (90 days at most) waits until it's within it. An email with blocks is something to do (Admin), and its estimate is their total. Blocks not to be trusted (one with no title or minutes out of range, a date out of range or a start after its own deadline, more than thirty (the prompt says so), more than twenty hours in all, or not adding up to the total) are dropped and the estimate stands; a block longer than a focus session is cut into parts that each fit one. An email archived while blocks are still to do, dated by their own dates or by the email's deadline, stays on the list as a follow-up (`core/Merge.kt`) until they're done, and one archived before its latest content was read (or, the model on, before the model's reading has replaced the rules') stays until it has been, so blocks found in it aren't lost: a focus session on one ticks it, as any step's does, and an email's blocks can be ticked off by hand in Tasks (no free time for a tick, nor when the email completes: an email is yours to say, as archiving it is, and its ticked minutes teach the calibration nothing). Only the model splits like this: the rules can't read dates out of free text. Tried on your dad's work-experience email by Opus through the subscription. The first tries (1.1.0) read only its first 4 000 characters, all the app then kept: ten blocks, four quick actions due within two weeks, NPL and Diamond between 1 and 31 December, RAL in January, and the Beamline for Schools proposal as three hours by 1 March; two earlier wordings had left the quick actions on the email's last deadline and given the proposal as one six-hour block, so the prompt asks for rough windows to run to their last day, quick actions to be due within two weeks, and blocks of 10 to 60 minutes. Read whole (1.1.1), four tries gave 17 to 20 blocks, 12 to 14 hours over five months, adding Imperial STEM Potential (from 30 Oct), the Rosalind Franklin Institute and Imperial Work Experience (January): past 1.1.0's ten hours and twenty blocks, so every one would have been dropped, hence the limits above.
- **When**: after every sync, as a job (`enrich/EnrichWorker.kt`, since the model needs the network), for tasks new or changed since they were last enriched (a hash of what's read), soonest due first, at most 20 model calls a run. Switched on, it goes over what only the rules have seen. A call that fails leaves the rules' answer and the model alone for the rest of the run; one the model declines is recorded as the model's, so it isn't asked again until the task changes.
- **Switching it on** takes the switch in Settings and a key put to use (`aiKeyActive`, set when a key is saved); once armed, each waits 24 hours like any loosening change, as do a higher cap and a lower pounds-per-dollar rate. Each task is read afresh just before its call, and an answer is laid only over the task as it was read.
- **A dropped plan is said (1.2.0)**: steps or blocks the checks above don't trust aren't dropped quietly any more. The enrichment keeps why (`Enrichment.dropped`: "20.8 hours of blocks, past the 20 the app takes from one task", "a step of 300 minutes, past the 240 one can take", "the blocks' dates are out of order", and so on), Tasks shows "Claude's plan was dropped (why): planned as one piece" on the task without opening it, while that enrichment stands, and a notification says so once, as it's laid (on the *Claude* channel; one a task; tapping opens Tasks; cancelled if a later plan for it is kept). Only the model's plans can be dropped: the rules' never are.
- **A key that stops working is alerted (1.2.0)** (`enrich/KeyProblem.kt`): a failed call is told apart by what the API said. A 401 (or `authentication_error`) is the key rejected: revoked, deleted, expired or mistyped. A 403 (or `permission_error`) is a key not allowed to use the model. A `billing_error`, a 402, or a 400 about credit or balance is the account's credit run out. Anything else (no network, the model busy) is an ordinary failure, as before. A key problem is kept with the month's usage, with when it began, and alerted once a stretch of it: a notification on the *Claude* channel (tapping opens Setup), Setup's Claude item saying what's wrong and what puts it right, and a banner at the top of the Plan while Claude is on. The hourly retries carry on (a refused key isn't billed) without alerting again; the rules stand in meanwhile; a call that works, or a new key, ends it and takes the alert away.
- **The cap** (`enrich/AiUsage.kt`): each call's cost from its token counts at list price (and at the dearer older Opus's if a fallback answered), in dollars as the API bills, per calendar month. **No cap by default** (9 Oct): the account is prepaid with no card, so its calls stop when the credit runs out, and that's alerted (below). One can be set in Settings; raising or lifting it waits once armed. With one, a call is made only if even its dearest case stays under it: $0.66, the longest text the model reads at three tokens a character, with all 16 000 output tokens, at the older Opus's rates.
- **Tried without the API**: the client against a stand-in server in the JVM tests (the request's model, effort, schema and fallback checked, and the answers read), and on the phone against a stand-in on the PC (`ai-check`): the SDK runs on Android, all three jobs. The prompts were tried on 18 real items by Opus through the subscription (`ai-prompts`, kept in the git-ignored `private/`).
- **What that showed** (8 emails, 8 assignments, 2 planner items: 9 good, 6 doubtful, 3 wrong), and what was changed:
  - the class multiplier was in the prompt *and* applied by the planner, so twice: the model now gives a typical student's times;
  - an assignment's vocabulary steps repeated the Anki deck tasks: a vocabulary step now names its sections, and the planner drops it where a deck task holds them (Codex then pointed out that dropping it outright lost the work where no deck exists);
  - an appointment in Power Planner ("Call with …") was estimated as work: now only its preparation;
  - a "check and hand in" step was guessed: now only where asked;
  - notes to self, forwards that ask nothing, and deadlines given as a day were unclear: each is now said.
  - Left as they are: overdue work that may already be done but not handed in on Teams (nothing says so), a test after an assignment's due date (steps carry no dates), and vocabulary named by topic only.

**Why not the subscriptions:** Anthropic and OpenAI both limit subscription OAuth tokens to their own clients (Claude Code, the Claude apps; ChatGPT, Codex). A personal Android app calling the API with a subscription token is outside their terms, and the tokens are short-lived in any case. A PC-side "Claude Code on a schedule" workaround would need the PC on and a channel to the phone, which defeats the point of an always-on phone app.

## 6. Blocking mechanics

- `FocusAccessibilityService`, `accessibilityEventTypes="typeWindowStateChanged"`, `canRetrieveWindowContent="true"` (needed only for the optional Chrome URL check), no `packageNames` filter (it must see every app come to the front).
- On event: if `event.packageName` is in the blocklist and `BlockPolicy.shouldBlock(now)` → `startActivity(Intent(BlockedActivity).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP))`. Accessibility services are exempt from the background-activity-launch restriction. Debounce 1 s so the block screen itself coming to the front does not re-trigger.
- `BlockedActivity`: `excludeFromRecents`, `launchMode="singleInstance"`, Back → `performGlobalAction(GLOBAL_ACTION_HOME)` via the service (the activity itself cannot). Shows `NextAction`, **Open**, **Check it's done**, **Refresh Teams**, **Why am I blocked?** (lists the pressure tasks), and the bypass control.
- Chrome: read `com.android.chrome:id/url_bar` text on window-content events (only when Chrome is foreground and the policy is active) and block on hostname match. Costs a little battery; optional (Q8).
- Blocklist stored as package names; the settings screen lists installed launchable apps with toggles.
- Proved in Phase 0 (`probe/FocusProbeService.kt`): on window-state events alone the block screen covered YouTube 0.62–0.66 s after it opened, because Android delivers an app's first window-state event about 0.6 s late; also acting on `typeWindowsChanged` and checking which app owns the active window cut that to 0.21 s. The same check skips a blocked app's late events once something else is in front (YouTube's bedtime snackbar would otherwise have covered Home). `BlockedProbeActivity` uses `singleTask` with its own `taskAffinity`, and Back sends it home itself. It's started with `FLAG_ACTIVITY_NO_USER_ACTION`: without it, covering a playing YouTube Short counted as the user leaving, and YouTube carried on in a picture-in-picture window over everything. A blocked app already in picture-in-picture is relaunched to full screen and covered, since One UI's floating window offers no dismiss action; that needs the blocked apps under the manifest's `<queries>`.
- **Built in Phase 3** (`block/FocusService.kt`), tested on the phone on 9 Oct with blocking hours forced on from a PC (`.debug.Command --es cmd force-block`):
  - YouTube was covered 0.24 s after it started; the block screen's *Go to the home screen* went home;
  - typing `youtube.com` into Chrome's address bar was covered before the page loaded;
  - Firefox was covered. On its first run, Firefox opened a "make Firefox your default browser?" system dialog, which isn't Firefox's window: the service covered Firefox once the dialog closed.
  - The service also re-checks the app in front every half minute, so an app already open when blocking begins (16:45, free time running out) is covered then too.
- The service must survive One UI: request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and the setup screen walks through "Never sleeping apps", exactly as the Teams widget's README does.

## 7. Optional: device calendar

`CalendarContract.Instances` with `READ_CALENDAR` gives busy blocks (Open Day 10 Oct, "Call with Jags" 14 Oct 16:00 if it is also in Calendar). Subtracted from the availability template when computing slack. Q7.
