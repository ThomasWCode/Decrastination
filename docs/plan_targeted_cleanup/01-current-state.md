# Where the code is now

Measured on `main` at e6cd54b (3.2.0), re-measured on 18 Oct 2026 after 3.1.5 to 3.2.0 landed (first measured at b8469fc, 3.1.4, on 17 Oct), and on the phone's data on 18 Oct (sizes and counts only, read with `run-as`; nothing on the phone was changed).

## In numbers

| | |
|---|---|
| App code | 32,208 lines of Kotlin in 160 files |
| Tests | 14,022 lines, 874 tests (1 skipped: the paid eval) |
| Largest files | `AppGraph.kt` 2,628; `core/Planner.kt` 1,201; `block/FocusService.kt` 857; `enrich/ChatReader.kt` 841; `block/Focus.kt` 801; `ui/AskActivity.kt` 756; `ui/SettingsActivity.kt` 710; `sources/gmail/GmailSource.kt` 686 |
| `AppGraph` | 117 functions, ten stores (nine JSON files and the secrets), 198 inline fully qualified names, no tests (its constructor needs an Android `Context`) |
| Its users | 51 files; most-used members `graph.clock` (121 uses), `graph.runtime` (90), `graph.focus` (58), `graph.settings` (57), `graph.tasks` (40), `graph.log` (33) |
| `TaskItem` | 43 stored fields (12 of them your overrides, `user…`) and about 16 derived ones |
| `RuntimeState` / `Settings` | 38 and 51 fields |
| Claude clients | built in 11 classes, a new one each time one's asked for |
| Notification ids | 18 constants in 17 files (17 distinct numbers: 4200 is used twice) |
| Inline fully qualified names | 437 in the app's code, 637 with the tests (each use outside an import line counted) |
| Version history in comments | 54 tags ("#48's second review", "since 3.1.2", "your call, 17 Oct") and 28 "from before" notes |
| Docs | were `data-sources.md` 83 KB, `scheduler.md` 66 KB, `needs-you.md` 55 KB, `PLAN.md` 30 KB, with lines up to 2,809 characters; restructured on 17 Oct into `docs/spec/`, `docs/decisions.md` and `docs/development.md` |

By package: `ui` 6,622 lines, `enrich` 4,217, `core` 4,214, `learn` 2,967, `block` 2,913, the root (`AppGraph` and two more) 2,708, `sources` 2,948 in all, `data` 1,730, `protect` 1,516, `widget` 724, `debug` 563, `notify` 484, `calendar` 321, `sync` 242, `net` 39.

On the phone (18 Oct):

| Store | Size | What fills it |
|---|---|---|
| `tasks.json` | 590 KB | 178 tasks: 142 done, 24 open, 12 missed; 130 are emails, 29 Teams, 14 Anki, 3 your own, 2 Power Planner |
| `runtime.json` | 98 KB | Anki readings, the last calls and micro-plans most of it (57, 24 and 10 KB on 17 Oct), the rest small |
| `chat.json` | 30 KB | 25 messages, 13 of them Felix's |
| `log.json` | 23 KB | 35 of Felix's records (27 replied to, the check-in's questions among them), 12 slot records |
| `instructions.json` | 13 KB | 11 instructions in use |
| `calendar.json`, `settings.json`, `memory.json` | 3 KB, 2 KB, 1 KB | |

The app's process: about 127 MB resident, which is fine.

## Findings

### 1. `AppGraph` is most of the app

It began as the place the singletons are made (its doc still says so). It now holds about thirteen concerns, each a few hundred lines:

| Concern | Functions (examples) |
|---|---|
| Stores and start-up | the ten stores, `forgetUnreadable`, the syncer |
| Planning | `plan()`, `workedToday`, the focus, the plan watcher, slots |
| The calendar written | `writeSlots`, `clearOldCalendars`, `keepSlots`, `watchCalendar` |
| Settings that loosen blocking | `changeSettings`, `applyReviewChanges`, `applyDue`, `useParentCode`, `cancelChange`, `applyNow` |
| Syncs and Teams | `refreshAll`, `requestTeamsSync` |
| Backups and resets | `exportBackup`, `backupNow`, `importBackup`, `restoreInstructions`, `reset`, `restoreResetCopy` |
| Claude's plumbing | `callModel`, `modelHold`, `modelWorked`, `modelFailed`, alerts, ten factories (`felixCaller()`, `microPlanner()`…), photos |
| Readings | `enrichNow`, `readAgain` |
| The weekly review's undo | `forgetCalibration`, `undoReviewChange`, `undoTidy` |
| The check-in | `askCheckIn`, `askNext`, `checkInReply`, `endCheckInFlow` |
| The summary email | `writeSummary` |
| Instructions and quick changes | `addInstruction`, `readInstructions`, `applyInstruction`, `deleteInstruction`, `takeBackQuick`, `layTaskOverrides` and eight more |
| Your own tasks | `saveOwnTask`, `finishOwnTask`, `reopenOwnTask`, `deleteOwnTask` |
| The chat and Felix's replies | `ask`, `answerChat` (156 lines), `act`, `undoAct`, `applyProposal`, `undoProposal`, `replyToFelix`, `answerReply`, `tellAnswer`, `remember` |

Every store is public and changed from wherever it's needed: 41 lines of `AppGraph` touch the chat store, and 17 files outside it change its stores directly (in `block`, `enrich`, `learn`, `notify`, `protect`, `ui` and `debug`). Because `AppGraph` can't be built in a test, its flows (the chat's answers and undo, instructions applied and taken back, a backup restored, a reset) are checked only on the phone. The parts that moved out into classes built like `Focus` (stores and a clock in the constructor) are tested; the rest isn't.

### 2. Felix has two homes, and his notifications replace each other

- What Felix says unprompted (the morning, a slot's start or end, a question, how completions went, the check-in, the weekly review's thanks) is a `FelixRecord` in `log.json`, kept 30 days, and a notification.
- The chat (`chat.json`) has his answers to what you write there. A record goes into the chat only when you reply to its notification (`AppGraph.replyToFelix` copies it in). On the phone (18 Oct): 35 records in 30 days, 27 replied to (the check-in's questions among them), 1 in the chat. Tapping one of the others opens a chat that doesn't have it.
- Seven places produce messages in his name, each building its own notification: `learn/Felix.kt` (his looks and the morning), `learn/Briefing.kt` (the morning's title and list), the check-in flow in `AppGraph` and `learn/CheckIns.kt`, `learn/Review.kt`, `AppGraph.writeSummary` ("The week's summary is ready; send it?": a record of his, and a notification under its own id, 4004, on his channel or the week's), `learn/Assessment.kt` ("How did it go?", on his channel, 4100 tagged), and `AppGraph.tellAnswer` (an answer to a notification's reply).
- `notify/FelixNotifications.kt` posts every one of his looks, the morning, the check-in and the answers under one fixed id, 4200, by design ("One at a time: a newer one takes its place"). That's why a new message of his removes the one before.
- His state is in three places: the log's records, `RuntimeState.felix` (his next check, a draft, a retry, the questions waiting) and the chat.

### 3. Notifications are named all over

Eighteen ids are constants in seventeen files (seventeen distinct numbers): the workers' 1001–1005, free time's 2003 and 2004, the watchdog's 3001, the daily ones 4001–4004 (the summary's, 4004, in `AppGraph`), assessment 4100, calendar questions 4200 (with a tag), Felix 4200 (without one, so distinct only by the tag), calendar moves 4300, model alerts 5001 and 5002. Pending intents' request codes are made from the ids (`ID + 1 + i`), so they can collide unseen. The channel rules (`NotifyRules`: a ceiling of four a day for routine ones, quiet hours, held ones sent with the briefing) are applied in `Notify.post`, but every caller builds its notification itself.

### 4. Claude's calls are wired eleven times

`ChatReader`, `ClaudeEnricher`, `ClaudeReviewer`, `FelixCaller`, `InstructionReader`, `MemoryTidy`, `MicroPlanner`, `PhotoChecker`, `PhotoTaskReader`, `SummaryWriter` and `WebResearch` each build an `AnthropicOkHttpClient` with their own timeout (two to five minutes) and two retries. `AppGraph`'s factories (`graph.felixCaller()` and nine more) make a new one each call, so a new HTTP client with its own connection pool is made for each message Felix writes. The call wrapper (`callModel`, with holds, the monthly cap, the record of calls, failures and the key's alerts) lives in `AppGraph`, about 260 lines of it.

### 5. "Who set this?" is a stack of flags

- `TaskItem` holds your overrides as twelve flat fields (`userNotATask`, `userFrom`, `userAfter`, `userDueAt`, `userEffortMin`, `userEffortPacedFrom`, `userEffortPacedHash`, `userOnDay`, `userAtTime`, `userSameAs`, `userSteps`, `userTitle`).
- The instructions' `TaskOverrides` holds the same twelve; `TaskItem.overrides()` copies them across by position, and `withOverrides` copies them back by name.
- What they mean is a set of derived flags, each added as a rule of yours arrived: `yourMinutes`, `yourSteps` (3.1.2), `pacedMinutes` and `pacedTeaches` (3.1.4), `tickDone`, `hidden`, `hardDeadline`. Whoever plans, scales, learns or displays has to check the right ones. The pace bug of 3.1.3 was one of them not covering a case: a `Steps` change sets no total, so `yourMinutes` didn't apply.
- A new override touches six places: `Change`, `TaskOverrides`, the fold in `taskOverrides`, `withOverrides`, `overrides()` and the backup's clearing in `restoredOwnTasks`. The paced totals went through all six twice on 17 Oct.
- Nothing says whether an instruction came from you (the instructions screen, a sheet's quick change) or from Felix (the chat), so the app can't say "Felix's" where that would help.

### 6. A step's origin is told from its title

A task's steps can be the source's (Anki's), a reading's (Claude's or the rules'), yours or Felix's (an instruction), or a task of your own's. `SubStep` has no id and no origin: `stepsFromEnrichment()` compares the task's step titles with the reading's, and `carryMarks` moves ticks by title and place among same-named steps. The ticks lost in #47 and the done steps cut in #48 both came from this. A slot's key uses the step's index in its list (PL-13), so a re-read that adds a step before it moves every key after.

### 7. Source data is kept as strings

`TaskItem.extra` is a `Map<String, String>` holding about 25 keys, most of them a source's own: Gmail 14 (message ids, senders, the text read so far, attachments…), Teams 4, Anki 4, Power Planner 3, plus two of the app's (your own task's `where`, and `tickDone`). The planner and the enrichment branch on several of them, and a value's type is known only by who wrote it.

### 8. The stores have grown unevenly

- `tasks.json` holds every task ever seen. Finished ones are kept (a source listing one again opens it again, and stats and the reopen list read them), and with them their whole text: 509 of its 585 KB on 17 Oct (590 KB on 18 Oct). `JsonStore` writes a store whole, atomically, at every change, so a tick rewrites 590 KB.
- `runtime.json` holds what changes every half minute (slots, credit, slots being kept) beside logs that only grow (Anki readings 57 KB, the last hundred calls 24 KB, on 17 Oct), so every half-minute change rewrites 98 KB.
- No store has a schema version. Compatibility is by defaults and by fields kept from before: 38 notes in the code say "from before" or "before 16 Oct", such as `ChatMessage.remembered` beside `memoryEdits` and `done` beside `acts`.

### 9. Names have drifted

- The persona is Felix, but a chat message's speaker is `Speaker.Claude`, and "Claude" in the code means both the model and him.
- The enrichment's prompts are `Prompts` and `Answers`, beside `ChatPrompts`, `InstructionPrompts`, `FelixPrompts` and `SummaryPrompts`.
- Files hold several things each: `ChatReader.kt` has `ChatPrompts`, `ChatRounds`, `ChatAnswers` and the reader (841 lines); `InstructionReader.kt` the same; `EnrichTest.kt` holds seven test classes.
- 437 inline fully qualified names (`com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(…)`), 198 of them in `AppGraph`: edits made in place, without the imports.

### 10. The screens reach into everything

37 composables take `graph: AppGraph` and read or change its stores directly; there are no state holders (no ViewModels) and 13 activities. That works, but each screen depends on the whole app, and a screen's logic can only be tried on the phone.

### 11. History has crept into the code and the docs

- 54 comments carry when and why a thing changed ("#48's third review"), and 28 more keep a field or a branch "from before", which is history that git and the PRs already keep; the requirement ids (PL-12, AI-19: 944 of them) are useful and stay.
- The docs mixed how the app works now with how it came to: `needs-you.md` had a section for every release, and `data-sources.md` and `scheduler.md` added "since 3.1.2" sentences to long paragraphs. Fixed on 17 Oct: the docs now say what the app does today (`docs/spec/`), with your decisions in `docs/decisions.md`.
- One heading in the old `data-sources.md` (§4) named the Gmail account's address, in a public repository; it left with the file, and git's history keeps it (Q8).

### 12. What's healthy, and stays

- **A pure core.** `Planner`, `Slotter`, `Merge`, the instructions' fold, `Sittings`, the calibration's maths and the chat's acts are pure and well tested, with property tests of the planner's invariants. No `core` file imports Android.
- **Sources behind an interface** (`sources/TaskSource.kt`); `Syncer` lets one fail without the others.
- **`JsonStore`**: atomic writes, a file that can't be read set aside and what can be kept of it recovered.
- **Golden prompt tests**, with a guard that raises `Prompts.VERSION` when a reading's prompt changes.
- **`Focus`**: its stores and clock injected, driven by `FocusTest` on temporary files. It's the pattern for the services.
- **The debug commands** (50 of them) that make phone checks possible without touching the screen.

### 13. Since 3.1.4: what 3.1.5 to 3.2.0 added (17 Oct)

Nothing that changes a phase; the numbers moved, the structure didn't. What the plan should know of:

- **`ChatRounds`** (3.2.0): the chat's answer can take a second round for a web search or a task's whole text, and `AppGraph` holds the retry's state in memory (`Gathered`: what a failed answer had looked up or read). It's the Conversation service's ([02](02-target-architecture.md)).
- **The readings write a summary** of a long text (D-93, `Enrichment.summary`), which `enrich/TaskText.kt` gives the chat and his messages in place of the text: no new store, no new call. (plan_v4's [08](../plan_v4/08-summaries.md) builds on it.)
- **The schemas are guarded twice**: `SchemaLimitsTest` (at most 16 unions, two inside a list) as a tripwire, and `SchemaCompileTest -PcheckSchemas=true`, which has the API compile every schema, as the guard before a merge.
- **A doc kept by a test**: `FelixInputsDocTest -PupdateDocs=true` writes `docs/spec/felix-inputs.md` from the code's own examples, the first case of Phase 7's "kept in step" rule being enforced.
- `Prompts.VERSION` is 7; the warm, capped Gradle daemons are in `gradle.properties`; the tests are 874.
