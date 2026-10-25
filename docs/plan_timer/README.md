# A timer on a piece (4.4.0)

The owner's ask, 25 Oct 2026: a Start button on a piece of work that marks it started and runs a countdown in a notification, so Felix's "10 minutes in" nudge doesn't come while they're at it. Not the focus sessions of 1.0.0 to 3.x (removed 15 Oct, AR-09, D-55): no blocking rides on it, it earns no free time and teaches the pace nothing. It is a timer, and the owner's word that they're on the piece.

This folder is what a fresh session needs to build it, in one release, without the conversation that planned it:

- `README.md` (this file): what the timer is and how it behaves, the spec the build implements.
- [`build.md`](build.md): the stages in order, each with its files, functions, rules, tests and an estimate; the release, the docs to change in the same PR, the phone session.
- [`answers.md`](answers.md): the questions the owner was asked and their answers, verbatim; and the choices made without asking, each with its alternative, for the owner to overturn.

Where this folder and the code disagree once the release is merged, the code and `docs/spec/` are right: this is the plan's record, as `docs/plan_v4/` is the 4.x plan's.

## Why it's wanted

Felix's slot-start nudge (`FelixRules.due`, reason `SlotStart`) comes `felixGraceMin` (10 minutes) into a slot when the app has seen nothing done on its task: no "activity" (`FelixState.activity`, written only by `Looks.noteActivity`) and no slot of the task kept since the slot began. Activity is written by exactly two paths: the app's own **Open** on a piece (`ui/TaskOpener.kt`, once the source's app starts) and the Anki watch (AnkiDroid in front during a deck task's slot, or cards studied). So the owner who opens Teams from Teams' own notification and starts writing is nudged all the same, and so is one who starts early, or on a task whose source app isn't watched. The timer gives them a way to say "I'm on it" that works for every source, and a clock to work against.

## What it is

- **Start** on a piece runs a countdown of the piece's planned minutes in an ongoing notification. One timer at a time: Start while one runs replaces it, and the toast says which it replaced.
- **Where Start is**: beside **Open** on the Day's *Do now* card, on each piece's sheet from the Plan and on the block screen (one code path, `ui/PieceActions.kt`); on each undone step in a task's sheet (`TaskSheet.Steps`), for every open task, not only the tickable ones; in the sheet's actions row for a task with no steps, for its next piece in the plan, shown only while the plan has one; and on the widget, a ▶ above the ✓ in one column, the ↻ in its own column to the right as now.
- **What it counts**: the plan's piece's minutes (`Chunk.minutes`, as laid); for a step the plan has no piece for, the step's minutes as the sheet shows them (`task.plannedStepMinutes(1.0)[index]`).
- **Running**: the notification shows the piece's label (`Chunk.label`, "Task: step"), its minutes and a **Stop** button, counting down by Android's own chronometer, so nothing wakes the phone to keep it current. Its tap opens the task's sheet. Where Start was, **Stop** stands, with a line "Timer: 12 min left"; the widget's ▶ becomes ■ and a pill reads "⏱ till 17:30".
- **At zero**: an exact alarm fires. The app posts a separate **"Time's up: <piece>"** notification, with sound, and retitles the running one to **"Over on: <piece>"**, "Stop when you're done". Its counter carries on by itself, which Android draws as −mm:ss (the owner's choice: see `answers.md`). The line reads "Timer: 5 min over", the pill "⏱ over". The "time's up" notification comes whatever the hour: the owner started it, so Felix's quiet rules don't apply, and it isn't his.
- **It ends** by **Stop** (the notification's, the card's, the sheet's, the widget's ■), or by the piece being done: its step ticked, its slot kept (Done, the widget's ✓, Felix's tick or keep, a passed photo check), or its task closed (its source confirming it, *Done* or *Delete* on one of the owner's own, marked not a task). Ending clears both notifications and the alarm. Nothing is written to the task or the log; an Undo of the tick doesn't restart it.
- **It blocks nothing, earns nothing, teaches nothing.** `BlockPolicy` doesn't know it; `Credit` doesn't; the calibration doesn't. The slot's outcome is recorded exactly as now: a slot gone by undone is missed (LR-09), so the learning and tonight's blocking (BL-11) are untouched.

## What it does to Felix

- **His slot-start nudge** about the timer's task waits while the timer runs, up to 30 minutes past its zero: a timer forgotten and left running silences nothing after that. Start also counts as something done on the task (`Looks.noteActivity`), so the nudge stays off for that slot after a Stop. A nudge written ahead for a slot the hold covers isn't written at all, so no call is paid for that would never be sent.
- **His slot-over message** (reason `SlotEnd`: the slot gone by undone, or a long piece ticked without its photo) about the timer's task waits too, until the hold lifts: **30 minutes after the timer ends**, at its zero or at a **Stop by hand**, whichever comes first (a timer stopped after running over lifts at its zero plus 30 minutes), or at the slot's record where that's later. It is then sent, even though the record is older than his usual 30-minute freshness (`FelixRules.FRESH_MS`): its freshness is measured from the later of the record's time and the hold's lift. That holds only for a record the timer could have covered, one still fresh when the timer began or made after it; a miss stale before the timer began stays stale, never brought back by the lift. A timer ended by the piece being done, running or stopped, is cleared and lifts nothing: the record is judged as now, so a piece ticked late, or stopped and ticked a moment after, isn't followed by "you missed it".
- **His other messages** (slot kept, day done, the morning, a reading's question, the evening's completions, reminders, the check-in) are unchanged.
- **He's told** of a running timer in one line of his context, with the slot under way or the next thing to do (`ChatPrompts.plan`, and his own messages' plan in `FelixPrompts.describe`): "timer on it since 17:02, 8 min left" or "12 min over". No schema changes: he can't start or stop one from the chat in this version.

## What it must cope with

- The piece not done when the countdown ends: the timer runs on, over, until Stop or the piece done; the slot's miss is recorded as now and said after the hold.
- A Stop with the piece undone: the slot's miss, if recorded, is said 30 minutes after the stop, so a tick a moment after the Stop isn't followed by it. A Start on another task meanwhile replaces the stopped timer's record, and the first task's miss falls back to Felix's ordinary freshness, so it may go unsaid.
- A restart of the app or the phone: the timer is in a store, so its notification is shown again and its alarm set again, or its zero told at once if it passed while the app was down.
- The piece replanned or moved while the timer runs: the timer is on the task and its step, not on a slot, so it stands.
- The task's steps changed under it (Felix's Steps change, a sync): a step no longer there by its index and title ends the timer.
- The day ending: the timer runs across midnight if left; a reset of everything clears it and its notifications.
- Two Starts at once, a Stop racing the alarm: the store is written in one step; the second finds what the first left and does nothing more.

## Done when

- Every behaviour above holds on the phone (the session in `build.md` §The phone), with the slot-start nudge seen not to come while a timer runs and the slot-over message seen to come after the hold.
- CI green on `./gradlew testDebugUnitTest lintDebug assembleDebug`; the migration from a version-2 `session.json` tested; the goldens and the plan's snapshot unchanged, or their diff explained in the PR.
- The docs moved with the code (`build.md` §Docs), the new decision numbered in `docs/decisions.md`, D-55 and AR-09 amended.
- Merged as `4.4.0: a timer on a piece`, tagged `v4.4.0`, at the owner's word.
