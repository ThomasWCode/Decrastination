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

## What the agent must not do

- Don't implement anything the review withdrew or deferred (listed in `../README.md`): sessions stay removed, no streaks, no exams page, no sender rules, no two weekend windows.
- Don't change the planner's purity: everything it depends on goes in through `Planner.Input`.
- Don't add Claude attribution, session links or co-author trailers to commits (the owner's rule).
- Don't commit anything from `private/` (git-ignored real data).
- Don't ask the owner questions that `../QUESTIONS.md` already answers; where a phase file says "decide", decide and record the decision in `PROGRESS.md`.
