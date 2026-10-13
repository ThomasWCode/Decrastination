# 8. Architecture, efficiency and testing

The code's shape is good: a pure core with tests, Android at the edges, JSON stores written atomically, one graph. The v1 review's rewrites were declined and nothing here reopens them. These are the gaps that make the next change riskier than it needs to be, and the debugging that still needs a cable.

## Summary

| ID | Improvement | Priority | Effort | Decision |
|---|---|---|---|---|
| AR-01 | Golden files for the prompts and schemas in CI | P1 | S | – |
| AR-02 | A scored eval run as a Gradle task, with a budget | P2 | M | Q-26 |
| AR-03 | The debug commands as a page in the debug build | P2 | S | – |
| AR-04 | Property tests for the planner's invariants | P1 | M | – |
| AR-05 | Screen tests for the three flows that matter | P2 | M | – |
| AR-06 | A crash record you can read on the phone | P2 | S | – |
| AR-07 | A nightly backup to a folder of your choosing | P2 | M | – |
| AR-08 | Trim the work a sync does when nothing changed | P3 | S | – |

---

## AR-01 Golden files for the prompts and schemas in CI

**Now.** The prompts are Kotlin string templates (`enrich/Prompts.kt`, `ChatReader.kt`, `InstructionReader.kt`, `PhotoChecker.kt`, `ReviewInput.kt`); a change to one is reviewed as a diff of Kotlin, and nothing ties it to a version (AC-06) or to the eval set.

**Proposal.** A unit test that renders every system prompt and JSON schema (with a fixed date, zone and settings) and compares them to `app/src/test/resources/prompts/<job>.txt` and `<job>.schema.json`, failing on any difference with the diff printed. Updating is a flag (`-PupdateGolden`) that rewrites the files, so the change shows as a text diff in the PR beside the version bump. The same test asserts the limits paragraph (AI-03) matches the constants.

**Final state.** A prompt change is a readable diff in review, the version bump can't be forgotten (the test checks `Prompts.VERSION` changed when a golden file did), and the eval set can be tied to a version.

**Caveats.** Rendering the chat's context needs fixtures (a few tasks, a plan); `Fixtures.kt` in the tests already has them.

**Effort.** S.

## AR-02 A scored eval run as a Gradle task, with a budget

**Now.** `EvalSetTest.dump` writes the cases and `score` reads answers back (`app/src/test/.../enrich/EvalSetTest.kt:66`, `:87`); the answering is done by hand through Claude Code subagents (`docs/eval.md`). It works and keeps the API key out of the loop, at the cost of being a manual afternoon.

**Proposal.** A `./gradlew evalScore -Pjob=Email -Pmodel=claude-opus-5-5 -Peffort=high` task that calls the API directly with a key from the environment, caps spend per run (counting the response usage; stops at, say, $2), writes the per-case results and the score to `private/eval/runs/<date>-<job>-<model>-<effort>.md`, and prints the cases that changed since the last run. Not in CI (the set is private and the key shouldn't be there); run before a prompt-affecting merge.

**Final state.** AC-02 and AC-03 become one command each; a prompt change is scored in ten minutes.

**Caveats.** Each run costs real money (a few dollars at HIGH on the full set); the cap is the guard. The cases must stay private (`private/` is ignored), and the task must refuse to run if the directory isn't there.

**Effort.** M. **Decision.** Q-26.

## AR-03 The debug commands as a page in the debug build

**Now.** `debug/CommandActivity` takes adb intents (`ai-prompts`, `chat-context`, `chat-ask`, `ai-endpoint`, `enrich-task`, and the rest): useful, cable-bound.

**Proposal.** A *Debug* page in the menu of debug builds only: the recent calls (AC-07), the rendered prompt for a chosen task (what `ai-prompts` prints), the chat context as sent, store sizes and ages, the last sync's timings per source, the watchdog's last findings, and buttons for the commands that are safe without arguments. Everything copyable.

**Caveats.** Debug build only (`BuildConfig.DEBUG`), and the student's phone runs the release. It's for your phone and the emulator.

**Effort.** S.

## AR-04 Property tests for the planner's invariants

**Now.** `PlannerTest` is example-based (this input gives that plan), which is right for the behaviour described in `docs/scheduler.md`, and the planner's header comment states eight rules. The rules are the kind of thing a random generator checks better than examples: capacity never exceeded except by chunks flagged behind, every dated chunk on or before its last usable day or flagged, per-day limits never broken, soft work never displacing dated work, the plan a pure function of its input (same input, same plan), and a tiny change in the input (one minute worked) moving at most one chunk.

**Proposal.** A seeded random test: generate 1–40 tasks with random kinds, deadlines, estimates, steps, per-day limits, calendar busy time and day caps; run the planner; assert the invariants; print the seed on failure. A few hundred iterations per CI run, bounded by time. Plain Kotlin and `kotlin.random.Random(seed)` are enough; no new library needed.

**Final state.** Changes like PL-01 (spread revision) and PL-05 (day max) are made with a net under them.

**Caveats.** Invariants must be stated carefully or they'll fail on legitimate cases (a task that can't fit anywhere *is* allowed to exceed capacity, flagged). Expect the first run to find an edge case or two; that's the point.

**Effort.** M.

## AR-05 Screen tests for the three flows that matter

**Now.** Tests cover the core, sources, learning, formatting and the widget model (29 files); nothing exercises a Compose screen. The three flows a student meets most are untested end to end: the block screen's Start, the chat's card applied, the sheet's Not a task.

**Proposal.** Compose UI tests with Robolectric (`androidx.compose.ui.test.junit4` on the JVM, no device) for those three: given a plan, the block screen shows the next piece and Start starts a session; given a chat answer with a change card, Apply writes the instruction and the plan moves; given a task, Not a task hides it and the undo returns it. Three tests, one fixture each.

**Caveats.** Robolectric with Compose adds a minute to the test run and a dependency; keep it to the flows that would embarrass the app if broken.

**Effort.** M.

## AR-06 A crash record you can read on the phone

**Now.** No `UncaughtExceptionHandler` is set; a crash is in logcat only. There's no crash reporter, rightly (a private app shouldn't send its stack traces anywhere).

**Proposal.** A handler that writes the last three crashes (time, thread, stack trace, app version) to the app's files directory, shown under Setup as "Last crash: 12 Oct 21:14, NullPointerException in TaskSheet" with Copy. Nothing leaves the phone.

**Caveats.** The handler must chain to the default one so Android still shows the crash dialog and restarts what it should.

**Effort.** S.

## AR-07 A nightly backup to a folder of your choosing

**Now.** `data/Backup` encodes and restores the stores on demand (`Backup.encode`, `data/Backup.kt:67`), with careful checks on what's restored. It runs when asked.

**Proposal.** A folder picked once through the Storage Access Framework (a Drive or OneDrive folder on the phone, or Downloads), and a nightly WorkManager job that writes `decrastination-<date>.json` there, keeping the last 14. Setup shows "Last backup: last night, 410 KB". A lost or wiped phone then costs a day, not a term.

**Caveats.** The backup holds the task titles and the activity log; the folder's own sync carries it off the phone, which is the point and the exposure. The JSON is the same as the manual backup, so the restore path is unchanged.

**Effort.** M.

## AR-08 Trim the work a sync does when nothing changed

**Now.** A sync reads every source, merges, enriches, replans and updates the widget. Gmail's listing is the heavy part (DX-07). The Anki read runs up to four `notes` queries per vocabulary deck (`AnkiSource.unseenCards`, `AnkiSource.kt:54`) every sync.

**Proposal.** Skip the per-deck unseen-card queries when the deck's counts and options haven't changed since the last read (keep a small cache keyed by deck id and counts). Skip the replan and widget update when the merged task list's hash is unchanged. Small, measurable from the sync's timings (AR-03 shows them).

**Caveats.** The planner is cheap; this is for battery and the sync's 90-second budget, not correctness. Do it after DX-07, which is the real saving.

**Effort.** S.

---

## What I looked at and left alone

- **The store design** (JSON files, atomic writes, salvage on a bad file): right for the size; a database would be more code for no gain.
- **`AppGraph`** as the one wiring point: large, but readable, and the v1 call to leave it stands.
- **CI** (`testDebugUnitTest lintDebug assembleDebug`): right; AR-01 and AR-04 add tests, not steps. A formatter (ktlint) isn't proposed: the code's style is consistent and deliberate, and a formatter would fight the comments.
- **Dependencies**: current (AGP 9.0.1, Anthropic SDK 2.71.0); nothing to change.
