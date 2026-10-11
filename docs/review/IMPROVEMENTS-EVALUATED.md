# The improvements, evaluated

Each of the 36 proposals in [IMPROVEMENTS.md](IMPROVEMENTS.md) (a review of `d1eefaa`) checked against `main` as it stood after 1.7.0 (`ef54ed5`), on 11 October 2026. Three verdicts:

- **Do**: worth it, and needs no decision from you. Built in 1.8.0.
- **Your call**: worth it only if you want it; asked, and your answers are below.
- **Not worth it**: the reason is given. Some may be worth it later, and the reason says when.

Most proposals are sized for a product with many users. This app has one user, one phone and four small sources (open on 11 Oct: 23 emails, 13 assignments, 1 Power Planner item, 4 Anki decks). That changes several verdicts.

## Do (1.8.0)

| ID | What it proposed | What's built, and why |
|---|---|---|
| P1-001 | Start a focus session, check a task is done, and so on, from the plan, not only from the block screen | Confirmed: the Plan's card has only *Open*. *Start*, *Check it's done*, *Photo check* and *Refresh Teams* are only on the block screen, so earning free time before 16:45 meant opening a blocked app first. The Plan gets the same actions, through the same code the block screen uses. |
| P1-003 | Make an unreadable saved file a visible, recoverable incident | Confirmed, and worse than the review says: `armed` defaults to off, so a `settings.json` that can't be read **disarms protection silently** (the guard, the 24-hour wait, the watchdog's repair), and the app then drops the device admin as for a finished disarm. Built in its bounded form: each unreadable file kept under its own time-stamped name (a second incident no longer overwrites the first); settings that can't be read while the device admin is active come back armed; the incident shown in Setup; saved passwords and keys that can't be decrypted are told apart from none saved. Not built: snapshots, a restore journal. |
| P1-005 | Show when a browser's address bar can't be read | Confirmed: if Chrome or Brave renames its address bar in an update, blocked sites silently stop being covered while protection reports all well. Built: a browser in front ten times running with its address never read is a protection problem ("switch it to blocked outright in Settings"), shown like the others. Not built: per-window state, adapters. |
| P1-006 | A readiness check before arming | Small part only: the *Arm now?* dialog lists anything in *What's in place* that isn't. No guided guard self-test: what it would test (One UI's page texts) can only be checked by hand, as was done on 9 Oct. |
| P1-007 | Show source health on the Plan | App part: the Plan says when a source can't be read, or Teams hasn't synced for a day, and *Nothing to do* says whether that's because nothing has been read yet. The widget part is your call (below). |
| P2-007 | Keep unanswered calendar questions visible | Confirmed: a dismissed question is lost, and the Calendar page goes on saying "you'll be asked in the week before it" after it was. Built: *All of it / A few hours / None* on each unanswered event in the Calendar page, and the wording fixed. Not built: answers per occurrence (an answer still holds for every event of that name, as you have it). |
| P2-012 | Keep the parent authenticator when arming again | Confirmed: arming again always shows a new QR code, so your dad has to scan again, and his old entry stops working. Built: *Keep his current one* (checked with a code from it), beside *New authenticator* and *Skip*. |
| P2-014 | Photo checks: preview, crop, blur checks, EXIF orientation | Only the orientation: the photo is decoded without its EXIF rotation, so a portrait page can be sent sideways. Preview, crop and blur checks aren't worth the screens: a bad photo costs one cent and a retake. |
| P2-025 | Reproducible builds | Only the Gradle distribution's SHA-256 in the wrapper (one line). Not dependency locking or verification metadata, which need updating by hand at every dependency change. |

## Your call: your answers (11 Oct)

| ID | Proposal | Your answer, and what's built in 1.8.0 |
|---|---|---|
| P1-002, P2-004 | Explain the plan; forecast work not yet available | **A *Not planned* list**: work that fits nowhere within the plan's reach, a block opening after it, a task waiting for another or not open yet, each with its reason, folded at the foot of the Plan. Not "why today" on a tap, nor forecasting not-yet-open work into the days' time. |
| P1-007 (widget) | A health cue at every widget size | **A small red !** by ↻ wherever the warning strip doesn't fit; a tap opens the app. |
| P1-004 | An evaluation set for Claude's decisions | **A small private set**: a dozen of your real emails and assignments with the right answers, in `private/` (never committed), put through Opus here and the app's own checks whenever a prompt changes. No API spend. The harness that builds the prompts and scores the answers is committed; the data isn't. |
| P2-001 | Forms for the seven instruction changes | **Quick actions only**, on a task in Tasks, no Claude: *Not a task*, *Can't start before…* and *Due on…* (date pickers; a due date needs your dad's code while armed, as an instruction's does). |
| P2-003 | Earlier messages of a conversation | **Left as is.** |
| P2-009 | Settings: find an app | **Blocked apps first, then a search box** over the rest. |
| P2-011 | A countdown while free time is spent | **A silent countdown notification** while it's spent, and one heads-up a minute before it runs out. |
| P2-015 | The weekly review proposes, you confirm | **Kept applying, and without the wait**: its changes (box length, safety margin, undated minutes a day, work minutes per free minute, each within fixed bounds) apply at once even when armed, and the review says what changed. |
| P2-016 | Learned estimates by evidence | **Shown, and three needed**: Stats says how many timed pieces each figure rests on; a class's own figure counts once it has three, the kind's until then. |
| P2-019 | Separate notification channels | **Split into four**: morning briefing, how it went, calendar questions, weekly check-in. |

## Not worth it

| ID | Proposal | Why not |
|---|---|---|
| P1-008 | React at once to blocking boundaries instead of a 30-second tick | The tick costs a few window lookups (no tree walk unless a browser is in front); 30 seconds late at 16:45 is harmless. Reworking the live blocker's timing risks more than it gains. |
| P2-002 | Per-source sync scheduling and coalescing | Four sources, a 15-minute job, and *Check it's done* already reads only its own source. |
| P2-005 | Read the calendar as far ahead as the plan reaches | Real but small: work three weeks out is planned again as those days come within the fortnight. Worth a look once exam season puts deadlines further out. |
| P2-006 | A catalogue of homework-to-deck mappings | The textbook-section rule finds the decks your real assignments name; no wrong or missing link has been seen. Worth it if one is. |
| P2-008 | Large text and TalkBack layouts | One user, default text size, no screen reader. |
| P2-010 | Task search and filters | About 40 open tasks, grouped by source. |
| P2-013 | A ledger of free time earned and spent | Spending hasn't been tried yet; the day's totals are shown. Worth it if a balance ever looks wrong. |
| P2-017 | Versioned prompts and provenance | Each prompt change so far shipped its own re-read where it was needed (1.1.1). A version number to bump would be enough, when the prompt next changes. |
| P2-018 | Typed sync diagnostics and a history | The failure text is shown in Tasks and on the widget; the logs have the rest. |
| P2-020 | Cache Gmail envelopes | An inbox of a few dozen messages is one small fetch. |
| P2-021 | Read emails archived before their text was | Only the first sync of a large inbox can hit it, and that has happened. |
| P2-022 | Offline replays of whole syncs | A test harness (as BUG-P2-025 was), not a change you'd notice. |
| P2-023 | Split AppGraph; one plan snapshot | A rewrite of the app's core for no visible change; the review itself warns against a large rewrite. |
| P2-024 | An event processor in the focus service | The same, for the live blocker, which is tested by hand on the phone. |
| P2-026 | A model per job; interactive calls first | Calls take the lock one at a time, so an instruction waits at most one call. Cheaper models only make sense with an evaluation set (P1-004). |
| P2-027 | Power Planner's full details | Probed on 11 Oct (read-only): your one item's full `Details` is as empty as its `ShortDetails`. |
| P2-028 | Measure startup | Five small JSON files; nothing to measure yet. |
