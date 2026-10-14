# Implementation plan: how to use these files

These files are for the agent that implements review v2. They state each change, how to make it, and what the agent needs to know; the reasons and the decisions behind them are in the numbered review documents one level up (`../01-ai-prompting.md` … `../09-new-features.md`), the walkthrough in `../PLAN-OVERVIEW.md`, and the decisions in `../QUESTIONS.md`. Read `00-conventions.md` first, then the phase you're on. Don't skip a phase: each builds on the one before.

## Phases

| Phase | File | What it delivers | Depends on |
|---|---|---|---|
| 1 | [phase-1-tests-and-email.md](phase-1-tests-and-email.md) | Property tests for the planner's invariants; Gmail reads only starred mail; the Email job without Info; quoted text cut; attachment names | – |
| 2 | [phase-2-spread.md](phase-2-spread.md) | Even spreading of later work; elastic app-set dates; the yield per kind of day; the calendar read to the horizon; each piece's "why" | 1 |
| 3 | [phase-3-slots.md](phase-3-slots.md) | The timetable's windows and the school calendar; slots with breaks and weekend outside breaks; live re-slotting; pins; the Google Calendar write; the plan watcher | 2 |
| 4 | [phase-4-done.md](phase-4-done.md) | Sessions removed; slot outcomes as the record; what's done and what earns; the block rules (today only, weekend start, gaps, missed slots, empty days); Anki counted from counts | 3 |
| 5 | [phase-5-felix.md](phase-5-felix.md) | The profile block; memories and standing instructions; Felix's prompt, triggers, replies and pacing; the chat as Felix; effort per job; rest by error class; the lock; working ahead | 4 |
| 6 | [phase-6-screens.md](phase-6-screens.md) | The Day timeline and the Work view; the menu; the logo's theme; pickers, swipes, moves, the widget, the sheet, settings explanations | 5 |
| 7 | [phase-7-readings.md](phase-7-readings.md) | The repair round and salvage; limits, examples, reasons, questions; micro-plans; photo context and photo-to-task; chat actions; duplicates | 5 |
| 8 | [phase-8-record.md](phase-8-record.md) | Reviews page; reset; stats by subject; export; the weekly summary; prompt version; call log; golden prompts; the eval task; crash record; nightly backup | 6, 7 |

Phases 6 and 7 are independent of each other and can be done in either order after 5.

## The bar for "done"

A phase is done when every item in it says so by its own **Done when** list, and:

- `./gradlew testDebugUnitTest lintDebug assembleDebug` passes (that's CI, `.github/workflows/ci.yml`).
- Every new behaviour has a unit test in `app/src/test`, pure code tested without Android where the code is pure (`core/`, `learn/`, `enrich/` parsers and prompts, `sources/*/…Rules`, `widget/WidgetModel`).
- `docs/scheduler.md`, `docs/data-sources.md` and `README.md` say what the app now does, in the same voice as the rest of those files (see `00-conventions.md`).
- `PROGRESS.md` at the repository root is updated at the end of every item, and deleted when the whole plan is done.

## Commands

```
./gradlew testDebugUnitTest --console=plain          # all unit tests (~600 today)
./gradlew testDebugUnitTest --tests '*PlannerTest*'  # one class
./gradlew lintDebug --console=plain
./gradlew assembleDebug --console=plain
```

JDK 17. The debug APK's keystore (`app/debug.keystore`) is committed on purpose: the Teams widget's signature permission depends on it. Never regenerate it.

## Facts the owner has settled

- Backwards compatibility between app versions isn't required: the owner is the only user. Data on the phone must still survive each upgrade (see `00-conventions.md`); nothing else needs to keep working with older data or older builds.
- The owner has starred every email he wants as a task, so DX-03 needs no grace period.
- A starred message brings its whole conversation: every message of it is read, starred or not, his own replies included (the owner, 14 Oct; DX-03 as amended in phase 1).
- His Power Planner timetable has the free periods entered as classes named "Study Period".
- The Google calendar "Decrastination" exists and is synced to the phone; the app picks it in Settings (NF-07). Grant the write permission over adb: `adb shell pm grant com.thomaswcode.decrastination android.permission.WRITE_CALENDAR`.
- The Claude API key is in the environment variable `CLAUDE_DECRASTINATION_API_KEY` on the machine the session runs on (for the eval task, AR-02); on the phone it's in the app's secret store already.
- The weekly summary goes to `richard.white@lshtm.ac.uk` (NF-02's default).
- The nightly backup (AR-07) writes to `Documents/Decrastination` on the phone's shared storage through `MediaStore`: no folder picker, no permission, nothing for the owner to do. A copy off the phone is the owner's own doing.
- Don't change settings of apps that aren't this one (Google Calendar, Gmail, AnkiDroid, the Teams widget, Power Planner) on the phone; the app's own settings, and the phone's (accessibility, rotation, permissions) are yours to set.

## The Teams widget

Two items (DX-08, NF-06) need the Teams widget's content provider to expose data it doesn't today. The widget is the owner's other project, `ThomasWCode/TeamsAssignmentsWidget` (`com.teamsassignments.widget`); its provider's contract is `provider/AssignmentsContract.kt` and it exposes `key, title, class_name, description, due_text, due_at, tab, detail_read_at, last_synced_at` (see `docs/data-sources.md` §1 here). It syncs by reading the Teams app's screens through an accessibility service, so anything new must come from what those screens show. When you reach those items: find the widget's checkout beside this repository (ask the owner for the path once if it isn't there), and change it under the same rules as here: a branch from its `main`, one commit per item, its own tests and CI green, a pull request against its `main` titled `Expose <what> for Decrastination (DX-08)` or `(NF-06)`, and no further action on that PR unless told. The widget's signature permission and keystore don't change. Decrastination's side reads the new columns when present and does nothing when they're absent, so the two PRs can merge in either order.

## What the agent must not do

- Don't implement anything the review withdrew or deferred (listed in `../README.md`): sessions stay removed, no streaks, no exams page, no sender rules, no two weekend windows.
- Don't change the planner's purity: everything it depends on goes in through `Planner.Input`.
- Don't add Claude attribution, session links or co-author trailers to commits (the owner's rule).
- Don't commit anything from `private/` (git-ignored real data).
- After opening a phase's pull request, take no further action on it unless told: the owner merges.
- Don't ask the owner questions that `../QUESTIONS.md` already answers; where a phase file says "decide", decide and record the decision in `PROGRESS.md`.
