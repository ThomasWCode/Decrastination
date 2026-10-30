# Learning

What the app learns from how the work goes: your pace for each kind and class of work, a safety margin for each kind, how much of each kind of day gets used, and AnkiDroid's seconds a card.
Every figure is bounded; the pace, the margins and the yields are shown in Stats and worked out afresh from the record, so the same record always teaches the same values.
`learn/Learning.kt` does what's done with the record: the week's review, a figure forgotten, a review's change or tidy put back, the week's summary, and the check-in and a completion's answer kept (the chat's and the form's go through it). It's built like `Focus`, its pure parts `Review`'s, `Calibrator`'s, `Stats`' and `Summary`'s; `LearningTest` drives it on temporary stores.

## The record

`ActivityLog` (`log.json`, kept 120 days) is what everything here reads:
- `SlotRecord`: each slot's outcome (kept, kept late, missed, moved), its window and the minutes worked (LR-09);
- `CompletionRecord`: each task confirmed done;
- `DayRecord`: each day's plan as it stood in the morning, and how much of it got done;
- the blocks, the check-ins, the weekly reviews, and each reviewed week's totals (`WeekRollup`, two years). Felix's messages are the chat's ([felix.md](felix.md)).

A completion's record is made as it's confirmed (`Focus.onCompleted`):
- `estimateMin`: the estimate it's measured against (yours, the app's, or for a paced total the app's it came from), before any pace, for what's left of it when first seen, less the vocabulary its decks hold;
- `workedMin`: the minutes of its kept slots; `byHand` and `photo`: some of it ticked off by hand, or found done by a photo check;
- `yourEstimate`: its minutes are your own reckoning; `hardDeadline`: its date is a real deadline (PL-14); and your answer to "How was it?".

A day's record is made at the morning briefing (`Days.record`) and finished the next morning (`Days.finish`): a task confirmed done that day counts in full, any other as far as its kept slots that day went.
An answer about a calendar event, or a change applied or taken back (yours or Felix's; a restored backup's answers too), makes today's record again with what the day can hold now (`Briefing.replanToday`, `Days.replan`).

## Calibration

`Calibration` (`core/Plan.kt`, kept in `learning.json`, `LearningState.calibration`) is learned by `Calibrator.learn` at each weekly review, and the planner uses it at once:
- `multipliers`, with `timed`: the pace, per kind and class (`Kind|class`) and per kind (`Kind|`);
- `marginDays`: a safety margin per kind;
- `yields`, with `yieldDays`: how much of each kind of day's free time is used (PL-06);
- `ankiSecondsPerCard`, with `ankiStretches`: learned as AnkiDroid is used, not at the review;
- `forgotten` and `learnFrom`: what *Forget* and *Reset* leave out (LR-06, LR-08).

A calibration without its counts (`Calibration.counted` false) has its multipliers and their counts made again from the record at the next start, or as a backup is restored (`Calibrator.upgraded`); its margins and yields stand till the next review.

## The pace

Each multiplier is a moving average (weight 0.3) of worked over estimated minutes from timed completions, kept within 0.5 and 3.0 (`Calibrator.multipliers`).
- A completion teaches both its class's figure and its kind's.
- A class's own figure counts once it rests on three timed pieces (`Calibrator.MIN_CLASS_TIMED`, IMP-P2-016); until then its kind's stands in, so one long evening can't set a new class's figure.
- The planner lays the pace only over the app's own estimates (`Calibration.multiplierFor`, [planner.md](planner.md)).

A completion is timed (`Calibrator.isTimed`) when it has an estimate and kept-slot minutes, none of it is ticked off by hand or found done by a photo check, and its minutes aren't your own reckoning.

## What a completion teaches

- **Your own reckoning teaches nothing.** A task you gave minutes (a Minutes change), or one of your own, is recorded `yourEstimate` and left out, answer and all: the pace corrects the app's estimates, not yours.
- **A total Felix works out at your pace still teaches** (`TaskItem.pacedMinutes`). When he sizes a task's steps to your pace he gives it a new total, marked paced (his answer's `paced`, which the app reads into `Change.pacedFrom` and `Change.pacedHash`, kept on the task as `mine.minutes.paced`, its `from` and `contentHash`; D-69).
- It's planned as given, like any minutes of yours, but its completion is measured against the app's estimate it came from (`mine.minutes.paced.from`), not against the new total.
- It teaches only while the task's content stays as it is when Felix paces it (`TaskItem.pacedTeaches`: its content hash, `Enrichments.inputHash`, unchanged).
- Read again for newer prompts, or with *Read again with Claude*, its content stays the same, so it still teaches, whatever the new estimate.
- A change to the task's own text (an assignment's instructions, a new email in its thread), your answers to its question, or a photo of the work changes its content: it then teaches nothing, as the work may be different.
- Only the chat's totals are paced: the instructions screen reads your own words.
- **Steps you or Felix gave with no new total** are planned at their own minutes, but the completion is measured against the app's estimate, which is still the task's.
- **Untimed work** (no kept-slot minutes, or some of it ticked off by hand or found done by a photo check) moves the pace only by your answer, below.

## How was it?

After homework or revision is confirmed done, the app asks how it went: *harder*, *about right* (kept as *as expected*) or *easier* (`learn/Assessment.kt`, `FelixWords.chip`).
- With Claude on, Felix asks instead, in the evening, about work no slot kept that day saw, his replies the three answers (LR-05, [felix.md](felix.md)); with Claude off, a message of his in the app's words asks about each, its chips the three answers.
- *Harder* raises the completion's figures 5 % where it's timed and 10 % where it isn't; *easier* lowers them as much; *as expected* changes nothing. All within 0.5 and 3.0.
- The answer goes on the completion's record, and the weekly review reads it.

## Safety margins

Each kind's margin is learned from completions with a real deadline only (PL-14), starting from your `Settings.marginDays` (`Calibrator.margins`):
- one day more after two in a row finished within an hour of the deadline or after it;
- one day less after five in a row finished a day early or more;
- between 1 and 3 days, widened to take in your setting, and kept only where it differs from it.

The planner uses a kind's learned margin in place of your setting ([planner.md](planner.md)).

## Day yields (PL-06)

Each kind of day learns how much of its free time gets used (`DayKind`: weekday evenings, weekends, school windows):
- weekday evenings and weekends: the median of done over planned on the kind's last 14 finished days with work planned, once it has 7, kept between a half and all of it;
- school windows: the median, over its last 14 days with any, of the minutes kept there in their own time over the minutes laid there that morning, once it has 7, kept between a half and all of it.

Each kind learns from its own days, so a run of bad Saturdays doesn't move the weekdays. Until a kind has its 7 days, it's all of its free time.
The planner multiplies each day's evening by its kind's yield, and its school windows by school's; a limit you set on a day is of the work, after the yield.
A day's record has the kind it was planned as (`SchoolDays.kindOf`, D-27): a weekday off school is a weekend's, planned with the weekend's yield and teaching it. A record made before kinds were kept is told by its date (`DayKind.of`).

## AnkiDroid's seconds a card (DX-11)

While AnkiDroid is in front, the decks' counts are read as it comes and goes (`block/AnkiWatch.kt`, `learn/AnkiProgress.kt`). A stretch's cards are its counts fallen, and its minutes go to the deck tasks it studied.
- A deck's seconds a card are the median of its last 10 stretches with 5 or more of its cards, between 1 and 600 seconds (`AnkiProgress.secondsPerCard`).
- They're updated at the end of each visit; the weekly review keeps them as they stand.
- The quota's and the deck tasks' minutes go by them ([planner.md](planner.md)); Settings → Sources → Anki lists each deck with the stretches it rests on.

## The capacity check

`Days.capacityAdvice`: once ten finished days with work are recorded in the last fortnight, if fewer than 40 % of their plans got done in full, the weekly review says your hours look more than your days hold.
It only advises: the hours are yours to change.

## Forget and Reset

**Forget** (LR-06): Stats lists what's been learned, each class's figure (and one still coming) with *Forget*. Asked first, the figure goes at once (`Learning.forgetCalibration`) and its kind's stands in.
That class's work from before then teaches nothing at the reviews after, its kind's figure included (`Calibration.forgotten`, `Calibrator.remembered`).

**Reset** (LR-08, Settings → Reset): Stats (the record, and what's learned from it), what the app has learned (learned afresh from now: `Calibration.learnFrom`), what Felix remembers, the chat and Felix's history, your changes, or everything learned.
Each is asked first, with no parent code, and a copy of all the app keeps is saved first (`reset-<date>-<time>.json`); a weekly review still running when the record is reset isn't kept.

## The Sunday check-in

At `Settings.checkInMin` (19:30) on Sundays, Felix asks the week's five questions in the chat, one at a time, in fixed words with no model call (LR-02, `felix/CheckInFlow.kt`, `felix/FelixWords.kt`; the answers so far in `ChatState.felix.checkInDraft`), each answered from his notification or his chips:
- how the week felt, 1 to 5; what you avoided and why; what got in the way; what you'd change; when you had most energy.

In quiet hours a reminder opens the form instead (`Conversation.remindCheckIn`), which takes all five at once, keeping what's typed through a turn of the phone till it's saved, a new week's starting empty; where his notifications can't be shown, he asks in the chat all the same (D-95) and the form's reminder goes too, so it's seen. A week before's questions never finished are settled as a new check-in starts, so an answer to one can't be taken as this week's. The answers are kept by the week's Monday (`CheckIn`).

## The weekly review

`Learning.review` runs 90 minutes after the check-in's time (21:00), answered or not, or as soon as the last answer is in; once a week (`Review.reviewed`). One asked for before the week's Sunday ("Save, and review the week" midweek) reviews the week so far, its days Monday to then, its note and its summary its own, with nothing learned or changed by it; Sunday's still runs, learns as ever, and is the week's: the one the next week's review and the summary page read (D-123).
It runs as a job (`ReviewWorker`), which waits for a connection while Claude is on.

The rules' part always runs:
- the calibration learned afresh from the record, and put in use at once;
- what changed, in words ("Weekends hold about 80 % of their free time now");
- the capacity advice, or "Nothing to change this week: the estimates held."

With Claude on, the week goes to the model too (`enrich/ClaudeReviewer.kt`, `ReviewInput.describe`).
It's given the work finished, against its estimates, with your answers; the slots by hour, day and window; each day's plan and what got done; the blocks; Felix's messages and what followed them.
And the week by subject, weekly work's minutes against what each week asked (D-124: what falls short is dropped, so it's said here), the check-in, last week's review and its changes, the settings and the calibration.
It writes a note of at most five lines, opening on how last week's change went, and may change, where the week gives a reason (`ReviewInput.bounds`):
- `marginDays`, 0 to 3; `softMinPerDay`, 30 to 120; `workMinPerFreeMin`, 2 to 5;
- a school day's start and end (`weekdayHoursStart`, `weekdayHoursEnd`), each within an hour of where it is.

It never changes `boxMin` (a piece's length is yours, D-33), what's blocked, quiet hours, protection, or any hours but a school day's; the free-time rate is the only blocking setting in its reach.
Its changes apply at once, armed or not (`Protection.applyReviewChanges`, IMP-P2-015), in place of any waiting change of yours to the same setting; each is kept with its value before it (`WeeklyReview.changes`, `settingsBefore`).

After it, what's run out of what Felix remembers is let go of, and the facts with neither an until nor a task are tidied once they're past 70 % of the room the rest leave them (AI-21, `MemoryTidy.due`, [felix.md](felix.md)); the week's totals are kept (`Stats.rollup`, PL-10); Felix's message in the chat gives the review's first line, with *Open the review* (`Conversation.reviewed`), on his notification; then the week's summary for home is written (NF-02), with a message of his offering to send it (`Conversation.summaryReady`).
The Reviews page (LR-01) lists each review with its note, each change with *Undo* while it's still what the review left (a change of yours, so once armed one that loosens blocking waits).

## Stats

`learn/Stats.kt`, on the Stats tab, over two weeks, six, or this term (`Stats.termStart`: from the day after the last run of five or more days without school):
- each day's completions, kept-slot minutes, blocks and whether its plan got done; the period's totals, deadlines met counting real deadlines only;
- by subject (PL-08, `Stats.bySubject`): minutes and pieces in kept slots, deadlines met, and timed work against its estimates;
- the last eight weeks' slots by weekday and hour (PL-09, `Stats.heat`), with *Set weekday hours from this* (`Stats.suggestedWeekdayHours`, the outlying tenth at each end left out), asked before it's set;
- each reviewed week's totals (PL-10), read for what's older than the record's 120 days;
- what's been learned, in words, each class's figure with *Forget* (`Stats.calibrationRows`);
- *Export the record as CSV* (PL-11, `learn/Export.kt`): completions, slots, days, blocks, Felix's messages (the archive's with the chat's) and the calibration, zipped to share.

`Stats.weekLines` gives this week so far and last in figures, by subject, as Felix is told them when he asks (G6, [felix.md](felix.md)).

## The daily jobs

`learn/Daily.kt` keeps three alarms, each set again once it has run: one that ran today stands for today, and one that failed is tried again 10 minutes later, up to three times (`Daily.failed`).
- **The morning briefing** (`learn/Briefing.kt`): 07:00 on a school day by the school calendar, 08:30 on any other, a weekday off included (`briefingWeekdayMin`, `briefingWeekendMin`; D-74); set again when the school calendar changes. A change that moves today's time to one already gone by, its briefing not yet run (a day off taken out after 07:15 and before its 08:30, or the time set earlier in Settings), leaves today without one: the next is tomorrow's; one gone by within the last quarter of an hour runs at once (`Daily.from`, D-104).
  What's run out of what Felix remembers is let go of first (`Conversation.forgetExpired`, D-103); then it posts what the night held back, reads the calendar, finishes yesterday's day record and makes today's, offers the morning's Teams sync for 90 minutes, then sends Felix's message or the day's list; after it, Felix's notes and reminders no longer wanted are let go (`Conversation.pruneFelix`).
- **The check-in**, on Sundays at `checkInMin`, above.
- **The weekly review**, 90 minutes after it, above.

Completions are recorded as they're confirmed, after each sync and at your *Done* (`Focus.rewardCompletions`); the decks' seconds a card at the end of each AnkiDroid visit.
