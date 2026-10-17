# The planner

How `core/Planner.kt` turns your open tasks into a plan: each task's remaining work cut into pieces, the pieces placed on days from today to the furthest deadline, and each day's pieces given times by the slotter.
It's pure Kotlin: everything comes in through `Planner.Input`, so the same input always makes the same `Plan`.

## Inputs

`AppGraph.plan` builds `Planner.Input` and calls `Planner.plan`; `PlanWatcher` makes the plan again whenever an input changes ([slots-and-calendar.md](slots-and-calendar.md)).

- **Tasks**, each with your instructions laid on it (`Instructions.taskOverrides`), and the minutes of work it's the same as credited to it (`Duplicates.credited`, DX-15).
- **Windows** (PL-15): each day's planning time, from `core/SchoolDays.kt` and the school calendar (PL-16).
- **Busy time**: the phone's calendar (`busy`, and `dayLoads` for time taken at no set time) and the times you said you can't work (`hardBusy`), which nothing overfills, not even overdue work.
- **Day limits** you gave (`dayCaps`), and the minutes kept today (`workedTodayMin`), which come off today's limit.
- **Settings**: your hours, `boxMin`, `marginDays`, `softDeadlineDays`, `softMinPerDay`, and the slotter's breaks.
- **Calibration**: the pace, margins and day yields learned ([learning.md](learning.md)).
- **Today's slots** in the last plan: their starts (`previousSlots`) and those missed (`missedSlots`, PL-13).

A task is planned when it's open, available (`TaskItem.isAvailable`), not hidden (`TaskItem.hidden`: not a task, or the same as another) and not waiting for another open task (`Instructions.waiting`).
Events (`Kind.Event`) are reminders in `Plan.events`, not work.

## A day's time (PL-15)

A school day's windows are its study periods (the timetable's lessons named `Settings.studyPeriodName`), break, the two parts of lunch, and the evening (`weekdayHours`).
A weekend or a day off school is `weekendHours` as one window; a half-day's school windows end when it does, and its evening starts then.

`Planner.capacityOf` gives each day a `Capacity`:
- each window's free minutes, less what's gone today and the busy time, overlaps merged first;
- a free stretch of a school window under `minSchoolSlotMin` (15) isn't counted, as the slotter lays nothing in one;
- time taken at no set time comes off the evening first, then school's;
- the evening's part is multiplied by its kind of day's yield, school's by school's (PL-06); a day's limit then caps the whole.

`homeMin` is the evening's part and `anyMin` all of it: work only for home takes the evening's time, and work that can be done anywhere takes school's first.

## Effort and remaining time

A task's estimate (`TaskItem.effortMin`) is the first there is of: your minutes (`userEffortMin`, a Minutes change), Claude's (`aiEffortMin`), the source's or the rules' (`sourceEffortMin`), the kind's default (Homework 40, Revision 30, Admin 15, Event 0).
`appEffortMin` is the same without yours.

**The pace multiplier** (`Calibration.multiplierFor`) is 1.0 for your minutes and a task of your own (`TaskItem.yourMinutes`), and for steps you or Felix gave (`TaskItem.yourSteps`).
Otherwise it's what's been learned for the task's kind and class, else for its kind, else 1.0. The learned pace only stretches the app's own estimates (D-35); Felix is told it, to allow for it when he sets times ([felix.md](felix.md)).

`Planner.remaining(task, multiplier)` is what's left, the smaller of two measures, so the same work isn't taken off twice:
- by the source: the estimate × the multiplier × (1 − its progress);
- by the work: the same from the lower of the first-seen and current progress, less the minutes worked (`workedMin`) and found done by a photo check (`photoMin`).

A task without steps is planned at no less than 5 minutes, or its whole estimate where that's less; one with no estimate has nothing to plan.

### Steps

`TaskItem.plannedStepMinutes(multiplier)` gives each step's minutes: its own × the multiplier, or, where you gave the task's total, your minutes shared over the steps in proportion (`shareOut`: whole minutes adding up to exactly yours).
`TaskItem.stepScale` (your total over the steps' sum) scales step minutes the same way where they're counted elsewhere (`Calibration.stepMultiplierFor`).

`Planner.pieces` then takes off what's done:
- minutes worked beyond the steps ticked come off the next steps in order, each kept to at least 5;
- a step ticked by hand holds only the timed minutes it kept (`SubStep.timedMin`); minutes a source's own steps already leave out (`stepsWorkedMin`: a deck's studied cards) come off none;
- the source's own progress counts too, the smaller of the two again;
- a vocabulary step whose sections the textbook's Anki deck tasks hold is left out: the cards are that work.

With every step done, an email or a task of your own has nothing left; any other keeps a 5-minute "finish and hand in" piece until its source says it's done.

## Pieces

A task's pieces are its steps still to do, or, with no steps, boxes of its remaining time.

- **Boxes** are at most `Settings.boxMin`: your setting, 60 on your phone, so nothing is cut that isn't over an hour (30 by default). The weekly review never changes it.
- Boxes are even: 100 minutes at 60 is 50 and 50, not 60 and 40. A task in one box is one piece; several are "part 2 of 3".
- Where your instructions limit days, a box is no longer than the most any day the task can go on holds (`Planner.roomiest`). Steps aren't shortened: one longer than every such day goes unplanned.
- **Where** (PL-15, `Chunk.where`): a step's own place from Claude's reading (home, or anywhere); else your own task's as you set it, Teams homework at home, the rest anywhere.

## Sittings

One of the app's steps still over an hour as planned is cut into even sittings as the plan is made (`Planner.sittings`, `core/Sittings.kt`).
Claude's reading cuts a long step when it's read (`Answers.fitParts`), but a step can still be over an hour as planned: the pace stretches it, your total is shared over it, or its reading left it whole.

- Each is at most `Sittings.MAX_MIN` (60), and they're even (`Sittings.even`: 125 minutes is 42, 42 and 41).
- They're named by their place in the whole step: the first has the step's title, then "Continue: …", and the last "Continue and finish: …" (`Sittings.titles`), unless the next step carries the work on (`Sittings.finishes`).
- How many the whole step takes is counted from its planned minutes, and how many are left from what's left of it, so the ones left keep their names as earlier ones are kept.
- Keys (`Chunk.keyOf`): a step's piece is `id#i`; a sitting before its step's last is `id#i#n`, n being how many of the step's sittings come after it. Counted from the end, keeping one renumbers none, so kept starts and pins hold.
- The last sitting has the step's own key, and only it ticks the step (`Chunk.ticks`). Keeping one before it adds its minutes to the task's worked minutes (`Focus.finish`), which come off the sittings left.
- Steps you or Felix gave (`TaskItem.yourSteps`: a task of your own's, or `userSteps` on any task) are never cut, however long (D-34). Boxes need no sittings: none is longer than `boxMin`.

## Real deadlines and plans (PL-14)

`TaskItem.realDeadline` is yours (`userDueAt`); an email's own words as Claude quoted them (`deadlineQuoted`, AI-12), or an invite's start (DX-02); a homework deck's assignments' earliest real one; else the source's own.
`TaskItem.hardDeadline` says the date in force is that one.

- Any other date is a plan: one Claude read without quoting it, the rules' guess, a test date before the source's deadline, a step's own dates. It orders the work and can leave a piece behind, but it's never overdue or due today.
- Gone by, a plan gives way to the real deadline behind it (`TaskItem.deadlineAt`); with none, the work is undated again, its week counted on from that date. A step's own dates gone by are dropped.
- Undated work has a soft deadline (`Planner.softWeek`): `softDeadlineDays` (7) after it's first seen, a week more at a time once that's gone by, so it still comes round by its week's end.
- Only a real deadline can be overdue, or count as met or missed in Stats and the margins.

## The last usable day

A task's last usable day is its deadline's date less its kind's margin (`Calibration.marginDays`, else `Settings.marginDays`, 1 by default).
- With a margin of 0 it's the deadline's own day, where a window the work can use starts at least 5 minutes before the deadline (for work for home, an evening window); else the day before (`Planner.lastDayBefore`).
- On a real deadline's own day, a piece has only the time before the deadline, as its slot must end by then.
- A day you put a task on (PL-04) is its last usable day, whatever the margin.

## Placing the pieces

The plan's days run from today to the latest deadline or day you put work on: at least 14 days, more where a task limited per day needs them, never more than 90 (`Planner.MAX_HORIZON_DAYS`).
Work is placed in this order, each taking its days' time before the next:

1. Work you put on a day (PL-04).
2. Work dated later today, then tomorrow: those deadlines can still be met. A task with some brings its overdue blocks along, ahead of it, ranked by the task's soonest deadline to come rather than their own.
3. Overdue work, the oldest deadline first, into today.
4. Dated work due after tomorrow, spread over its window (PL-01), the earliest last usable day first.
5. Undated work, backwards from its soft week.

Work due by today goes on the first day it can (`placeNow`): today, unless a per-day limit, undated work's allowance or a day's limit sends a piece on to the next day with the time. What fits nowhere in the plan's days is left out rather than break a limit.

## Spreading over the window (PL-01)

`placeSpread` spreads a dated task's pieces from the first day it can be done to its last usable day:
1. Each piece goes where its share of the window's free time falls: six pieces over twelve even evenings are one every second evening, and a busy day takes a smaller share.
2. In order: never before the piece before it, and never on a day past which the pieces after it can't follow.
3. Over a window of a week or more, within a week of the piece before, where a day in that week has room (the minimum touch), so a far task moves on every week.
4. Revision, or work for a test Claude read (`Enrichment.testDate`), a day after the piece before, where a later day has room (spaced practice).
5. A task in one piece goes to the day with most room, the earlier of two.
6. A day without room passes its piece to the nearest that has some, earlier first.
7. A task its window can't hold in order is placed backwards from its last usable day instead (`placeBack`).

Spreading gives today a fair share of each task's days, not a far task's whole work in its last ones. Blocking reads today's work alone (`Plan.pressure`), so it lifts once today's share is done ([blocking-and-protection.md](blocking-and-protection.md)).

## Backwards, and behind

`placeBack` places pieces from the last usable day backwards, one a day, then sharing days, still in order.
- A piece that fits nowhere before its deadline goes on the first day it can be done, mostly today, flagged `behind`.
- A task limited per day, undated work, and work whose first day is one you've limited don't crowd in: they go in order from the first day they can, the rest after the deadline; what fits nowhere in the plan's days is left out.

## Undated work

Undated work goes after everything dated, backwards from its soft week, at most `softMinPerDay` (60) minutes of it a day.
A day's first undated piece may be longer than that, never longer than the day's time, so no piece is too big for every day. A batch first seen together spreads over its week instead of filling one evening.

## Anki decks

AnkiDroid's work comes as tasks the app derives from its counts (`sources/anki/AnkiRules.kt`), done when the counts say so:
- **The daily quota** (`AnkiRules.quota`): every due review, and today's new cards from the current textbook's first section deck with cards never seen, due at `Settings.ankiDeadlineMin` (21:30) each Anki day (it turns at 04:00).
- The quota takes no new cards while homework's vocabulary is due by tomorrow.
- **A deck homework names** (`AnkiRules.homeworkDecks`): "Learn Anki deck 1.2", due with the earliest of its assignments' deadlines in force. Each step is a day's new cards (the first what AnkiDroid will still show today), one step a day (`TaskItem.stepsPerDay`).
- A deck whose new cards for today are used up can't start before the next 04:00 (`TaskItem.notBefore`).
- Minutes: 8 seconds a review and 25 a new card, or the deck's own seconds a card as learned (DX-11, [learning.md](learning.md)).

A deck task stays on the plan while any of its assignments does, and is held back with them; its start is the earliest of theirs where each has one you gave.
A task limited per day never has more of its steps on one day than that, overdue or not: an overdue deck of 36 new cards is 20 today and 16 tomorrow.

## Blocks with their own dates

Where a task's steps have dates of their own (`SubStep.from`, `dueAt`: one email's calendar of deadlines), `Planner.windows` makes each run of blocks sharing dates an item placed in its own window: not before its start, by its own deadline, never after the task's.
Runs stay in order: none starts before the one before it, each is planned to be done by the deadline of any run after it, and one opening past the plan's reach waits.

## Days and times you gave (PL-04)

Told to Felix or as an instruction, or made by moving a piece on Day or in the calendar, they're changes on the task (`userOnDay`, `userAtTime`):
- **A day** (`OnDay`): all of the task on that day, placed before everything else; that day is its last usable day whatever the margin, and a deadline only where the task's own is that day.
- **A time** (`AtTime`): its next piece a run of its own from that time, which the slotter keeps to the minute; the rest follow it.
- Not kept: one whose day has gone, one before the task can start, and, while the task's real deadline is still to come, a day after its day or a time at or after it. Those last two are refused as they're given ("It's due before then", `Instructions.latePin`; on Day, `Planner.pinProblem`).
- Work already overdue takes any day or time from now (D-30): all of it on the day you gave, or its next piece at your time and the rest after it, none of it before. It's still overdue there, and says so.
- A time past a plan's date makes the date moot: the work is undated from that time. A later day for all of it drops a time on another day, and keeps one on that day.

## Why a piece is on its day (PL-07)

Each piece carries its reason (`Chunk.why`), set as it's placed, and its sheet says it in a sentence (`Chunk.whyLine`):
- due today or tomorrow; overdue, naming its deadline's day; spread over the days to its deadline; kept within a week of its last piece; a day apart from the last revision piece; the day with most room;
- undated, filling spare time this week; packed as late as its days allow; where you put it; moved when its time passed (PL-13); couldn't fit before its deadline.

Behind with no room before the deadline itself, a piece says it couldn't fit, but overdue and undated work keep their own reason; behind only the safety margin, it keeps the reason that placed it.
The plan Felix reads has each as a short code (`[why: spread]`).

## What won't fit (PL-02)

`Plan.wontFit` names, once a task, work with a real deadline still to come that has more left than the free time before it, or some of it carried past it (`WontFit`).
Day shows "6 h left, 4 h free before Sat 09:00", with *What gives?* to ask Felix what to shorten, move or ask more time for.
- The free time runs to the deadline itself (its day, where a window the work can use starts before it), as much of each day as the task can have; not to its last usable day, as the margin is the app's own.
- Never for overdue work, undated work, a plan's date, or a deadline past the plan's reach.
- Work not open yet is said too, where even its days from its opening, all their time its own, can't hold it.

## What's left out (IMP-P1-002)

`Plan.unplanned` lists the open work in no day, with why (`Unplanned.Reason`), folded at the foot of Work:
- `NotOpen`: its source, or Claude's reading, says it can't start yet;
- `Waits`: you said it waits for another task still open;
- `OpensLater`: it, or a run of its blocks, opens after the plan's last day;
- `NoRoom`: there's no room for it by the plan's last day within each day's limits (a deck past its 90 days, undated work past its allowance).

A task's runs left out for the same reason are one line. Hidden tasks, events and Anki's derived tasks aren't listed. The list only explains: it changes neither the days nor blocking.

## Within a day

A day's pieces are in `Planner.ORDER`: dated work before undated; then work due later today or tomorrow (its task's overdue part first), overdue, behind, the rest.
Ties go by the deadline each ranks by; homework, revision, admin; the shorter task; the title, so the order never flickers; a task's parts stay in order.
The slotter then gives every piece a time (PL-12), today's where the last plan had them (PL-13): see [slots-and-calendar.md](slots-and-calendar.md).

## The same work from two sources (DX-15)

Open tasks from two sources, of the same subject, due within a day of each other (or both undated), whose titles share 60 % of the shorter's words (`core/Duplicates.kt`), are asked about on Work.
*Same* hides one (`SameAs`); the one kept is planned with both tasks' minutes worked (`Duplicates.credited`), so taking the change back takes the credit with it.
