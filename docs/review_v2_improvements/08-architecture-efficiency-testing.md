# 8. Architecture, efficiency and testing

The code's shape is good: a pure core with tests, Android at the edges, JSON stores written atomically, one graph. Nothing here reopens the v1 rewrites. Revised for the changes your answers bring: removing sessions (AR-09), spreading and slots (AR-04's invariants), the flows worth screen tests.

## Summary

| ID | Improvement | Priority | Effort | Status |
|---|---|---|---|---|
| AR-01 | Golden files for the prompts and schemas in CI | P1 | S | – |
| AR-02 | A scored eval run as a Gradle task, with a budget | P2 | M | – (Q-26 yes) |
| AR-03 | The debug commands as a page in the debug build | P2 | S | – |
| AR-04 | Property tests for the planner's invariants: spread, slots, stability | P1 | M | Changed; Implemented in part (v3 phase 1: the planner's rules; phase 2: spread, why, overdue; slots with phase 3) |
| AR-05 | Screen tests for the flows that matter | P2 | M | Changed |
| AR-06 | A crash record you can read on the phone | P2 | S | – |
| AR-07 | A nightly backup to Documents/Decrastination | P2 | M | – |
| AR-08 | Trim the work a sync does when nothing changed | P3 | S | – |
| AR-09 | Removing sessions safely | P1 | M | New (note 1); Implemented (v3 phase 4) |

---

## AR-01 Golden files for the prompts and schemas in CI

**Now.** The prompts are Kotlin string templates; a change is reviewed as a Kotlin diff and nothing ties it to a version.

**Proposal.** A test that renders every system prompt and schema (fixed date, zone, settings, profile) to `app/src/test/resources/prompts/<job>.txt` and `.schema.json`, failing on any difference; `-PupdateGolden` rewrites them; the test checks `Prompts.VERSION` changed when a golden file did. Felix's prompt (AI-22) and the profile block (AI-05) included.

**Effort.** S.

## AR-02 A scored eval run as a Gradle task, with a budget

**Now.** `EvalSetTest.dump` and `score` (`app/src/test/.../enrich/EvalSetTest.kt:66`, `:87`); the answering is manual.

**Proposal.** `./gradlew evalScore -Pjob=Email -Peffort=high` calling the API with a key from the environment, a spend cap per run ($2, your answer to Q-26), results to `private/eval/runs/<date>-<job>-<effort>.md`, changed cases printed. Not in CI. Used for AC-02's effort table and after any prompt change. The Email set loses its Info cases (AI-02); the slot-era cases (nags, micro-plans) get sets of their own once there's real data.

**Effort.** M.

## AR-03 The debug commands as a page in the debug build

**Now.** `debug/CommandActivity` takes adb intents.

**Proposal.** A *Debug* page in debug builds: recent calls (AC-07), the rendered prompt for a task, the chat context, the nags queued and sent, store sizes and ages, the last sync's timings, the watchdog's findings; everything copyable.

**Effort.** S.

## AR-04 Property tests for the planner's invariants: spread, slots, stability

**Now.** `PlannerTest` is example-based. The new placement (PL-01), slotting (PL-12) and live re-slotting (PL-13) are rules a random generator checks better than examples.

**Proposal.** A seeded random test over 1–40 tasks with random kinds, deadlines, estimates, steps, windows, calendar busy time and instructions; assert, per run:

- capacity: no day over its free time × yield, except by overdue and due-today work and by chunks flagged as unable to fit;
- deadlines: every chunk of a dated task on or before its last usable day, or flagged;
- spread: a task with a window of seven days or more has a chunk in every seven-day stretch of it (the minimum-touch rule, Q-32);
- slots: no two overlap, each inside a window, breaks kept, pinned slots where pinned, "home" pieces never in school windows, no school windows on a no-school day and none after a half-day's end (PL-16), outside breaks present on weekends unless the day's work needed their time, dropped in order;
- determinism: the same input gives the same plan;
- stability: after one change (a tick, a new event, one minute passing) at most the affected pieces move, and none moves without a reason the `why` field names.

Print the seed on failure. Plain Kotlin and `kotlin.random.Random(seed)`.

**Effort.** M. Do it before PL-01.

## AR-05 Screen tests for the flows that matter

**Now.** Nothing exercises a Compose screen.

**Proposal.** Compose UI tests with Robolectric for: the Day timeline's now card ticks a slot and the next shows; a reply to Felix applies a small move at once and offers a button for a big one (AI-24); the sheet's Not a task hides and undo returns; drag past the due date is refused with the reason.

**Effort.** M.

## AR-06 A crash record you can read on the phone

**Now.** No `UncaughtExceptionHandler`; a crash is in logcat only.

**Proposal.** A handler that writes the last three crashes to the files directory, shown under Settings with Copy, chaining to the default handler. Nothing leaves the phone.

**Effort.** S.

## AR-07 A nightly backup to Documents/Decrastination

**Now.** `data/Backup` encodes and restores on demand (`data/Backup.kt:67`).

**Proposal.** A nightly job writing `decrastination-<date>.json` to `Documents/Decrastination` on the phone's shared storage through MediaStore (your call: no folder to pick and no permission, and Google Drive and OneDrive offer no folder to Android's folder picker anyway), keeping 14; the backup grows the new stores (memories and standing instructions, the school calendar, slot outcomes). Also the backup LR-08 writes before a reset. A copy off the phone is yours to arrange: a sync app mirroring the folder, or Export by hand.

**Effort.** M.

## AR-08 Trim the work a sync does when nothing changed

**Now.** A sync reads every source, merges, enriches, replans, re-slots, writes the calendar and updates the widget. With DX-03 Gmail is light; the Anki read runs up to four `notes` queries per vocabulary deck (`AnkiSource.kt:54`).

**Proposal.** Skip the per-deck queries when counts and options are unchanged; skip the replan, the calendar write and the widget when the merged list's hash is unchanged; batch calendar writes per replan (PL-13).

**Effort.** S.

## AR-09 Removing sessions safely

**Now.** Sessions run through `block/Sessions.kt`, `FocusSession`, `Focus.startSession`/`stopSession`/`ending`, the session notification and its alarm, `BlockPolicy`'s session clause, `Credit.forSession`, the box experiment (`Calibrator.BOXES`), `Stats`' focus tiles, the widget's and block screen's Start, `PieceActions`, the chat's and widget's session text, and the tests `FocusTest`, `BlockingTest`, `PhotoTurnTest` in part, `StatsTest`, `WidgetModelTest`.

**Proposal.** One change, in this order, so the app never ships half-way:

1. Add the slot machinery (PL-12, LR-09) and the credit rule (BL-08) beside sessions, behind a `slots` flag off by default; tests for both.
2. Switch the flag on; the session paths are unreachable; run a week.
3. Delete: `Sessions`, `FocusSession` and its runtime field (a migration that ends a running session on first start after the update, recording it), the session notification and alarm (the alarm is reused for slot ends), the session clause in `BlockPolicy.decide`, `Credit.forSession` (replaced by the slot's credit), the box experiment, Start everywhere, `MAX_SESSION_MIN`. Keep `SessionRecord` in the log as history (old stats still read), renamed in the docs as the slot record.
4. Update `docs/scheduler.md` §4–5 and the tests.

**Caveats.** The photo check's cap and `Focus.remaining` read sessions' minutes; they read slot minutes after. The Anki auto-count (NF-03) was a session in the first version; it's a counts reading now (DX-11).

**Effort.** M.

---

## What I looked at and left alone

- **The store design** (JSON files, atomic writes, salvage): right for the size.
- **`AppGraph`** as the one wiring point: large, readable; stays.
- **CI** (`testDebugUnitTest lintDebug assembleDebug`): right; AR-01 and AR-04 add tests, not steps. No formatter: the code's style is consistent and deliberate.
- **Dependencies**: current; nothing to change. Writing to the calendar needs `WRITE_CALENDAR` in the manifest (NF-07).
