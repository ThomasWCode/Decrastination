# Phase 8: the record, the tools, the tests

Items: LR-01, LR-02, LR-06, LR-08, PL-08, PL-09, PL-10, PL-11, NF-02, AC-06, AC-07, AC-08, AI-18, AR-01, AR-02, AR-03, AR-06, AR-07, AR-08. Depends on phases 6 and 7.

---

## AC-06 A prompt version

**Files.** `enrich/Prompts.kt`, `core/Enrichment.kt`, `AppGraph.kt`.

**Steps.** `Prompts.VERSION: Int` (start at 2; bump on every change to any system prompt, schema, example or profile shape). `Enrichment.promptVersion: Int = 1`; `Answers.parse` sets it. `Enrichments.stale(...)` also true when `e.promptVersion < Prompts.VERSION && task.isOpen`; `enrichNow` orders candidates by `(promptVersion, dueAt)` so old versions go first, still within `MAX_MODEL_CALLS` per run. The sheet's "Read again with Claude" button → `enrichNow(only = taskId)` after clearing the enrichment. The eval dump prints the version.

---

## AC-07 A record of recent calls

**Files.** `data/RuntimeState.kt`, `AppGraph.kt` (`reserveCall`/`release`), `ui/SettingsActivity.kt` (Claude section).

**Steps.** `@Serializable data class CallRecord(val at: Long, val job: String, val title: String?, val effort: String, val inTokens: Long, val outTokens: Long, val cacheRead: Long, val latencyMs: Long, val stop: String?, val outcome: String, val costUsd: Double)`; `RuntimeState.calls: List<CallRecord>` (last 100). `release(reservation, result)` writes it: `outcome` ∈ used, repaired, salvaged, dropped:<why>, refused, failed:<class>, empty. Settings → Claude → "Recent calls": a list, newest first, each row expandable with Copy.

---

## AC-08 Progress and cancel in the chat

**Files.** `ui/AskActivity.kt`, `AppGraph.kt`.

**Steps.** `AppGraph.answering: StateFlow<Long?>` (the start time of the call in flight); the chat shows "Felix is thinking… 12 s" and, after 10 s, a Cancel that cancels the `Job` (`answerChat` runs in a `Job` kept on the graph; cancellation closes the HTTP call) and marks the message `stuck` with the note "Cancelled".

*(As built: `answeringSince`, as `answering` is the chat's lock; Cancel lets the answer go rather than closing the call. A call once sent is paid for and counted, whatever stops waiting for it (AC-04, as every call is), and the SDK's call blocks till it's done, so it's left to come back and its answer goes unused.)*

---

## AI-18 The review's input

**Files.** `learn/ReviewInput.kt`.

**Steps.** Add sections: "Last week's review" (its note lines and each change's setting then and now), "Days" (date, kind, planned, done, full), "Slots" (kept/missed/moved by window kind and hour), "Free time" (earned, spent), "Felix" (messages sent, replies, slots then kept), "By subject" (PL-08's table). Ask the note to open with one line on last week's change. `BOUNDS` gains `weekdayHoursStart`/`weekdayHoursEnd` (±60 min of current) so the review may move the hours.

---

## LR-01 The Reviews page

**Files.** New `ui/ReviewsActivity.kt`, `data/RuntimeState.kt` (`WeeklyReview.changes: List<ReviewInput.Change>`, `memoryTidy`), `AppGraph.kt`, `AndroidManifest.xml`, `MainActivity.MENU`.

**Steps.** `WeeklyReview.changes` stored at review time (and `settingsBefore: Map<String, Int>`). The page lists reviews newest first: the note, each change as "box 45 → 30 (why)" with Undo (`changeSettings` to the before value), the memory tidy's before/after with Undo (restore `before` to `memory.facts`), the week's check-in answers, the week's summary text (NF-02).

---

## LR-02 The check-in from Felix

**Files.** `learn/CheckIns.kt`, `learn/Felix.kt`, `learn/FelixRules.kt`.

**Steps.** `CheckIns.remind` → a Felix message (reason `CheckIn`, added to the enum) "How was the week, 1 to 5?" with quick replies 1–5; the reply records `feel` and Felix asks "What did you avoid, and why?" (prefilled chips from the week: slots missed, pieces moved, days not done, as replies), then "What got in the way?", "What would you change?", "When did you have most energy?"; each reply fills the `CheckIn` fields; the form stays for typing it all. `ReviewWorker.enqueue(ifDue = true)` after the last answer.

---

## LR-06 Calibration shown

**Files.** `learn/Stats.kt`, `ui/StatsScreen.kt`.

**Steps.** `Stats.calibrationLines` → structured `CalibrationRow(kind, className, multiplier, basis, margin, marginWhy, yieldKind, yieldValue, days)` and a "Calibration" section on Stats with a *Forget* per class (`Calibration.forgotten: Set<String>` filtering the log in `Calibrator.learn`).

---

## LR-08 Reset

**Files.** `ui/SettingsActivity.kt` (Reset section), `AppGraph.kt`.

**Steps.** Rows: Stats (the activity log: `log.update { ActivityLog() }`; the calibration follows at the next review; also `runtime.calibration = Calibration()` now), Calibration only (`Calibration.learnFrom: Long = now` stored and honoured by `Calibrator.learn`, which ignores records before it), Memories and profile lines (`memory.update { MemoryState() }`, `profileLines = emptyList()`), Chat and Felix history (`clearChat()`, `runtime.felix = FelixState()`, `log.felix = emptyList()`), Instructions (lists what would go, then `instructions.update { InstructionState() }` and `layInstructions()`), Everything learned (all five). Each: an `AlertDialog` naming what's lost; before it, `exportBackup()` written as `reset-<date>.json` to `Documents/Decrastination` through `BackupFolder.write` (the writer AR-07 below specifies: build `data/BackupFolder.kt` here, AR-07 adds the worker), and to `filesDir/backups/` when that write fails. No parent code.

*(As built: the copy is `reset-<date>-<time>.json`, so two resets in a day each keep theirs, and it holds the chat, which a reset can clear (the backup's format 6 has it; an import restores it where this phone's chat is empty).)*

---

## PL-08, PL-09, PL-10, PL-11 Stats and export

**Files.** `learn/Stats.kt`, `ui/StatsScreen.kt`, new `learn/Export.kt`, `data/RuntimeState.kt` (`ActivityLog.weeks: List<WeekRollup>`).

**Steps.**
- PL-08: `Stats.bySubject(log, calibration, period)`: per class (via `classMap`/`className`): minutes done (slots), pieces finished, on-time share (hard deadlines), actual ÷ estimated with the task count; a section on Stats; the review's input.
- PL-09: `Stats.heat(log, weeks = 8): Map<Pair<DayOfWeek, Int>, Pair<kept, missed>>` from slot records; a 7×24 grid composable with a tap "Set hours from this" that proposes `weekdayHours` from the densest kept hours.
- PL-10: `WeekRollup(week, minutes, finished, onTime, blocks, fullDays, slotsKept, slotsMissed)` written by `Review.run`; a period picker on Stats (2 weeks / 6 weeks / term) reading rollups for the older part.
- PL-11: `Export.csv(log, calibration): Map<String, String>` (file name → CSV text) for completions, slots, days, blocks, felix, calibration; zipped to `cacheDir/export-<date>.zip` and handed to the share sheet via the existing `FileProvider`.

**Tests.** `StatsTest`: by-subject numbers; heat; rollups; CSV escaping.

---

## NF-02 The weekly summary

**Files.** New `learn/Summary.kt`, new `enrich/SummaryPrompts.kt`, `learn/Felix.kt`, `ui/SettingsActivity.kt` (Summary section), `data/Settings.kt` (`summaryEmail: String = "richard.white@lshtm.ac.uk"`, the owner's dad; editable in Settings).

**Steps.** `Summary.input(log, reviews, felix, week)`: the numbers (minutes, pieces, on time, days done, slots kept/missed, free time), the review's note and changes, the week's nags and how they went, the moves and instructions. `SummaryPrompts.SYSTEM`: "Write the student's weekly summary for their dad, in the student's voice, first person, plainly, no excuses and no spin: what got done, what didn't and why, what changed. Short paragraphs and one small table. Under 300 words."; effort `Summary`. Sunday: Felix (reason `Summary`) posts "The week's summary is ready; send it?" with an action that opens the email: `Intent(ACTION_SENDTO, Uri.parse("mailto:$summaryEmail")).putExtra(EXTRA_SUBJECT, "Week of …").putExtra(EXTRA_TEXT, text)`. The same text on the Reviews page. Settings → Summary: the address and a "Send this week's summary" button.

**Tests.** `Summary.input` text; nothing else is pure.

*(As built: `summaryEmail` is empty by default, as the repository is public and an address in its code would be published; the owner sets it in Settings → Notifications, and the email opens with none until then. The summary is written right after the review, by the review's job, rather than at a time of its own; its notification opens the email.)*

---

## AR-01 Golden files

**Files.** New `app/src/test/.../enrich/GoldenPromptsTest.kt`, `app/src/test/resources/prompts/*.txt|*.schema.json`.

**Steps.** Render every system prompt (`Prompts.system` × jobs + limits + examples, `ChatPrompts.SYSTEM`, `InstructionPrompts.SYSTEM`, `PhotoChecker.SYSTEM`, `ReviewInput.SYSTEM`, `FelixPrompts.system`, `MicroPlans.SYSTEM`, `PhotoTasks.SYSTEM`, `SummaryPrompts.SYSTEM`, `MemoryTidy.SYSTEM`, the profile block for fixture settings) and every schema (as JSON) with fixed inputs; compare to the resource files; on mismatch print a unified diff and fail; with `-PupdateGolden=true` (read `System.getProperty("updateGolden")`, passed through `build.gradle.kts`'s `tasks.withType<Test> { systemProperty(...) }`) rewrite them. A second test asserts `Prompts.VERSION` changed whenever any golden file differs from git's `HEAD` version (read via `git show HEAD:path` through `ProcessBuilder`; skip when git isn't available).

*(As built: the version test asks only of the copies a reading is made by (the three jobs' prompts and schemas, `enrich-*`, and the profile), since `Prompts.VERSION` is theirs: a reading made by older ones is made again, a paid call a task, which a change to the chat's prompt doesn't call for. It compares what's made now with HEAD's copies, so it holds in the run that writes them again too. A copy new since HEAD has nothing to compare with.)*

---

## AR-02 A scored eval run

**Files.** `app/src/test/.../enrich/EvalRunTest.kt`, `build.gradle.kts`, `docs/eval.md`.

**Steps.** A JUnit test gated by `System.getenv("CLAUDE_DECRASTINATION_API_KEY")` (skipped otherwise; the owner sets it on the machine the session runs on): for each case in `private/eval/cases.json`, call the real `ClaudeEnricher` with the key, effort from `-Peffort`, write the answer to `private/eval/answers/<id>-<run>.json`, then run the existing `score` logic; stop when the summed cost passes `EVAL_BUDGET_USD` (default 2). Run with `./gradlew :app:testDebugUnitTest --tests '*EvalRunTest*'` with the variable in the environment (Gradle test tasks inherit it; if not, pass it through `systemProperty` as the golden-file flag is). Document in `docs/eval.md`. Never in CI.

*(As built: it runs only with `-PrunEval=true` as well as the key, since the key is in this PC's environment for the app's own testing: gated by the key alone, every test run here would be a paid round. The answer's text comes back on `Enricher.Result.answer`; the profile is the settings' defaults' with the case's task, as `dump`'s prompts have it; and the first answer is scored, as the app's reading is until a repair, which the run doesn't ask for. Round 6, its first: 10 of 10, $0.245.)*

---

## AR-03 The debug page

**Files.** New `ui/DebugActivity.kt` (debug builds: `if (BuildConfig.DEBUG)` in the menu), `debug/CommandActivity.kt`.

**Steps.** Sections: recent calls (AC-07), the rendered prompt for a chosen task (`Prompts.system` + profile + `describe`), the chat context (`AppGraph.chatContext`), Felix's state and queue, store sizes and ages, the last sync's timings per source (`SourceStatus`), the watchdog's findings; every block copyable; buttons for the argument-less commands (`sync`, `enrich`, `briefing`, `review`, `calendar`, `protection`).

*(As built: `backup-now` (AR-07) is among the buttons. `CommandActivity` ran only what came through its DUMP-guarded alias; it now runs those commands too when the app itself starts it (it isn't exported), on a debug build, and no others. Felix's queue is in his state (the questions waiting and the message to try again), shown with his last ten messages.)*

---

## AR-06 A crash record

**Files.** `DecrastinationApp.kt`, new `data/Crashes.kt`, `ui/SettingsActivity.kt`.

**Steps.** In `DecrastinationApp.onCreate`: `Thread.setDefaultUncaughtExceptionHandler { t, e -> Crashes.record(filesDir, t, e, versionName); previous?.uncaughtException(t, e) }`; `Crashes.record` writes `crashes/<time>.txt` (time, thread, stack, version), keeps three. Settings → Claude (or About) → "Last crash" with Copy.

---

## AR-07 A nightly backup

**Files.** New `data/BackupFolder.kt` (built under LR-08 above), new `data/BackupWorker.kt`, `ui/SettingsActivity.kt`, `debug/CommandActivity.kt`, `AppGraph.kt`.

**Steps.** The folder is fixed: `Documents/Decrastination` on the phone's shared storage, reached through `MediaStore`, so there is no folder picker, no permission, no setting and nothing for the owner to do. `BackupFolder` (Android, thin): `write(resolver, name, text)` inserts into `MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)` with `DISPLAY_NAME`, `MIME_TYPE = "application/json"`, `RELATIVE_PATH = "Documents/Decrastination/"` and `IS_PENDING = 1`, writes through `openOutputStream`, then clears `IS_PENDING` (the folder is made by the first insert); a name `list` already finds is overwritten in place (`openOutputStream(uri, "wt")`), since a second insert of the same name would be renamed `… (1).json` by MediaStore; `list(resolver)` queries that collection for `RELATIVE_PATH = 'Documents/Decrastination/'`, returning `(name, uri)` by `DATE_ADDED` (an app sees the files it made there without any permission, and keeps them through a clear of its data; files from before a fresh install it can't see, and the app is never installed fresh); `delete(resolver, uri)`. Guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` (minSdk is 26): below it nothing runs and the Settings row says the backup needs Android 10. The pruning rule is pure: `BackupFolder.toPrune(names: List<String>, keep: Int = 14): List<String>`, the oldest of the `decrastination-<date>.json` names beyond `keep`, by the date in the name; `reset-*` files are never pruned. `BackupWorker` (periodic, 24 h, `ExistingPeriodicWorkPolicy.KEEP`, enqueued at app start beside the sync) writes `decrastination-<date>.json` through `BackupFolder.write`, deletes what `toPrune` names, records `runtime.lastBackupAt`. Settings → Backup: "Nightly backup: Documents/Decrastination", "Last backup: …" (or "not yet"), with Export and Import as today; Import can open those files through Android's file picker as it does any. A `backup-now` debug command runs the worker's work once. `Backup` covers every store: tasks' own tasks, settings, log, calibration, event answers, usage, instructions, school, memory, Felix state, slot state (FORMAT 5).

**Tests.** `BackupFolderTest`: `toPrune` keeps the newest 14 by the date in the name, never names a `reset-*` file, and names nothing at 14 or fewer. `BackupTest`: FORMAT 5 round trip, an older format refused.

*(As built: FORMAT 6, as 5 was taken by what Felix remembers in phase 5; it adds Felix's state and today's slots, restored only where this phone has none of its own (Felix's) and on the same day (the slots). A newer format is refused, as before; an older one is still read, so backups from before stay importable.)*

**Done when.** `backup-now` leaves `decrastination-<today>.json` under `/sdcard/Documents/Decrastination` (`adb shell ls`), a second run the same day replaces it rather than adding a copy, Import of that file from Setup restores it, and Settings → Backup shows the time. Keeping a copy off the phone is the owner's own doing and not this item's (Google Drive and OneDrive offer no folder to Android's folder picker; a sync app mirroring the folder, or Export by hand, does it).

---

## AR-08 Trim the sync

**Files.** `sources/anki/AnkiSource.kt`, `PlanWatcher.kt`, `calendar/SlotCalendar.kt`.

**Steps.** `AnkiSource`: skip `unseenCards` for a deck whose `(counts, options)` equal the last read's (keep a map in the source instance). `PlanWatcher`: skip the hooks when `plan == lastPlan` (data-class equality). `SlotCalendar.write`: skip the query when the desired events equal the last written set.

*(As built: the plan's hooks aren't skipped: a plan the same but for the time still needs the alarms armed for the next slot (only the next start and end are set), the widget's line moved on and what's due written ahead, so only the calendar write, the costly one, is. It skips while the calendar hasn't changed since it was last read, as well as the slots being the same: a slot moved or deleted there by hand is read on its change, as NF-07 needs.)*

---

## Closing the plan

When every phase is done: delete `PROGRESS.md`; mark each item **Implemented** in the review documents' summary tables; make sure `README.md`, `docs/scheduler.md` and `docs/data-sources.md` read as a description of the app as it now is; bump `versionName` to `3.0.0` and `versionCode`; run the full CI command once more.
