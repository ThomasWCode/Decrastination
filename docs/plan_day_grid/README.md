# The Day as a calendar (4.5.0 and 4.6.0)

The owner's ask, 25 Oct 2026: the Day tab changed "so it looks very similar to the Google Calendar page". Planned with the owner the same day: Google Calendar's **Day view**, a time grid, with the Day's own parts kept where the owner chose to keep them (the week strip, the swipes, the DO NOW card).

This folder is what a fresh session needs to build it, without the conversation that planned it:

- `README.md` (this file): what the Day becomes and how it behaves, the spec the builds implement.
- [`build.md`](build.md): the two releases, their stages in order, each with its files, functions, rules, tests and an estimate; the docs to change in each PR; the phone sessions.
- [`answers.md`](answers.md): the questions the owner was asked and their answers, verbatim; and the choices made without asking, each with its alternative, for the owner to overturn.

Where this folder and the code disagree once the releases are merged, the code and `docs/spec/` are right: this is the plan's record, as `docs/plan_v4/` is the 4.x plan's.

## Two releases

- **4.5.0: a time you give is kept to the minute.** The planner alone (`core/Slotter.kt`, `core/Planner.kt`): a change to how plans are made, reviewed and checked on the phone apart from a new screen.
- **4.6.0: the Day as a calendar.** The grid, its header, its blocks and the drag, on 4.5.0's rule.

Both come after 4.4.0, the timer (`docs/plan_timer/`), which is planned and not yet built; neither touches what the timer changes but the Day's DO NOW card, which 4.6.0 keeps as it is. Built before the timer, the numbers swap (`answers.md`, decided without asking, 1).

## What the Day looks like (4.6.0)

Top to bottom, under the app's bar and tabs, which don't change (UI-17, UI-18):

1. **The first-run hints**: the Day's own, as now; and one more, shown once: "Hold a piece to move it: drag it to a time, or onto a day above."
2. **The week strip**, as now (UI-02): seven days from today, each with its load as a bar and a red dot where work is behind; a tap picks the day. While a piece is dragged its days are drop targets (§Moving a piece by hand).
3. **Today's DO NOW card**, as now (`NextCard`), on today only. It folds into one line ("DO NOW · Essay: plan") as the grid scrolls towards later hours, and unfolds as it scrolls back towards earlier ones, or at a tap on the folded line, as Material's top bar does with `enterAlways`.
4. **The day's header**, as Google's:
   - on the left, the gutter's width: the weekday over its date ("Thu" over "29"), today's date in a filled circle of the primary colour;
   - beside it: the day's name and load, as the heading says them now ("Today", "1 h 40 planned, 1 h 20 free" by `Format.load`); under that, the day's **all-day events** as chips in their calendar's colour, each opening its event sheet; under those, a row of **chips for what the list now shows under its rows**, each opening a sheet that holds what the list holds today:
     - "2 won't fit" (today only, PL-02), in the error colour: the `WontFitTile`s, each with *What gives?* into the chat;
     - "3 with no time": the day's pieces with no slot (`DayBucket.unslotted`), the `ChunkTile`s, each opening its piece's sheet;
     - "40 min done": the day's slots kept (UI-08), each with its outcome, minutes and "earned";
     - "Instructions · 2": the day's instructions (a day limit, busy times: UI-11) and *An instruction about this day…*; "Instruction…" where there's none.
   - "Nothing planned." stays, under the chips, when the day has no slot, record or piece.
5. **The grid**: the whole day, 00:00 to 24:00, scrolling on its own.
   - The hours down a gutter on the left, as the app writes times ("15:00"), every hour but midnight's, with a thin line across at each.
   - The day's windows as bands behind the blocks: school's tinted as `WindowRow` tints them now, the evening plain, each band's name at its top ("Study period", "Break", "Lunch", "Your time", as `WindowRow` names them); the hours outside every window shaded.
   - Each slot, record and timed event a **block** from its start, as tall as it lasts, but never under 24 dp, so a short one can still be read, tapped and swiped (Google draws short events taller too). Blocks at the same time stand side by side in columns, each widened to the right over columns free for all its time.
   - Today's moment: a line across in the primary colour with a dot at the gutter's edge, moved each minute as the screen's clock moves now. The "Now 16:12" label goes: the line and the hours say it.
   - It opens, on today, an hour before now; on another day, half an hour before its first window or block, whichever is earlier. Picking a day scrolls so again.
   - Two fingers pinch it between 40 and 120 dp an hour (60 at first), about the point between them; the zoom is kept in the settings, so it's the same next time. One finger only scrolls.
   - The free time between slots is the empty grid: the "Free, 30 min" rows go.

### The blocks

| Block | How it looks | Tap | Swipe (UI-03) | Hold |
|---|---|---|---|---|
| A piece ahead | navy (`primaryContainer`): its label, its times, then as its height allows its reason (`Chunk.whyLine`), its first step ("First: …", AI-15) and the last thing said about its task; urgent, a red edge on its left; a ✓ at its right where it's at least 32 dp tall and 120 dp wide | its piece's sheet (`PieceSheet`) | right to left: Done, with Undo; left to right: later today (today's only) | drag (below) |
| The piece under way | the same in `primary`, its times followed by " · now" | as above | as above | as above |
| A piece gone by, not yet moved | faded, " · gone by" | as above | as above | as above |
| A break outside | a pale strip, "Outside" | none | none | none |
| A slot kept, or kept late | grey, its label struck through, its outcome | none | none | none |
| A slot missed | outlined and worded in the error colour, "Missed" | none | none | none |
| An event | its colour (the event's own, else its calendar's); its words black or white, whichever reads better on it; outlined only where the plan counts it free; a small "?" where the plan needs your answer about it (`EventJudge.Judgement.Ask`, not asked yet) | its event sheet (`EventSheet`, as now) | none | none |

For TalkBack, each piece's block offers *Done*, *Later today* and *Move it* (the move sheet, `ui/MoveSheet.kt`), so nothing needs the drag or a swipe.

### Moving a piece by hand (UI-04)

- **Hold a piece**: it lifts, with a tick of haptics, and follows the finger. A ghost marks where it would go, in five-minute steps, with its times beside it ("17:15–17:40"). Near the grid's top or bottom edge, the grid scrolls.
- **Let go on the grid**: the next piece of its task (PL-04) takes that time on that day, as the move sheet's time does: an `AtTime` change of yours, "At Thu 29 17:15", undone in the chat's *Your changes*; the snackbar says so as the move sheet's does. With 4.5.0's rule it stays exactly where it was let go (§A time you give, below): over an event, outside the windows, in a time you said you can't work. Its task's later steps then follow it (D-108), the other tasks' pieces it covers are laid again around it, and a piece you pinned earlier that it covers goes to the next free time after it.
- **Refused**, the block springs back and the snackbar says why, in the move sheet's words: the time's gone; it can't be started till then; it's due before its piece would be over (a real deadline still to come; overdue work goes anywhere from now, D-30).
- **A later piece of its task** can't take a time (PL-04): let go on the grid, it springs back, "Only its next piece takes a time: drag it onto a day above".
- **Let go on a day of the strip**: all of its task on that day (`OnDay`), as the move sheet's *All of it, any time that day*, with its check (a time you gave in the chat holding it on that day is taken back there first). While dragging, the days it can't go to are dimmed, as the move sheet's chips are: after a real deadline still to come, before it can start. Let go on its own day, nothing changes.
- **Let go anywhere else**, nothing changes.
- **The move sheet stays**: TalkBack's *Move it* opens it, for the fortnight and a time by the clock; the task sheet's *Change* keeps its days (UI-09).
- **Events, records and outside breaks don't move**: events are the calendar's own, moved in Google Calendar; a break outside is the app's, as the calendar's hand moves already treat it.

### Colours

The app's own blocks keep UI-12's blues; events take their calendar's colours, as Google shows them (`CalendarContract.Instances.DISPLAY_COLOR`, read with the events, which are held in memory: no stored data changes for it). The error red stays Material's.

## A time you give, kept to the minute (4.5.0)

PL-04 now: a time given for a task's next piece that isn't free goes to the next free time after it. From 4.5.0, a time you or Felix give it (on the Day, in the move sheet, in the chat, or by moving its event in Google Calendar, D-42) is **kept to the minute**:

- outside the day's windows, over a busy event, over a time you said you can't work, home work in a school window: kept, as D-4 keeps what you set;
- the task's later steps follow it (D-108), as now;
- other pieces it covers (today's kept starts, PL-13) are laid again around it, as pins are laid before them now;
- two pins over each other: **the one given last keeps its time**; the other goes to the next free time after it, as a pin not free does now, and the chat says where (`ChatAnswers.movedPins`);
- not kept, as now: its day gone; before its task can start or its piece opens (a deck's cards at 04:00); a piece not over by its real deadline still to come (refused on the Day before it's made, `Planner.pinProblem`, which now counts the piece's minutes); its time and grace gone by with the piece undone (PL-13), when it's laid as any piece is;
- breaks: none kept around a pin outside every window, as there's no window to keep apart in; inside one, as now;
- a pin outside every window is recorded, kept or missed, under the kind of the window nearest it in time (`Slot.window`), so its record teaches the day yields (`Calibrator`) as school's or the evening's, as the time of day says;
- a pin outside the windows still counts against that day's free time, as one inside does now, so the day may hold a little less other work than its windows could (decided without asking, 6);
- blocking is unchanged: today's work is today's pieces, wherever their times (BL-01).

## What it must cope with

- **Many blocks at once**: as many columns as the cluster needs, each block's words clipped to its width; a block under 40 dp wide shows its first word only.
- **An event across midnight**: drawn on each day it covers, from 00:00 or to 24:00.
- **The clocks changing**: the grid is the day's wall clock, 24 hours. On the day the clocks go back, the hour repeated is drawn once, two things in it side by side; on the day they go forward, the missing hour is empty.
- **The plan changing during a drag** (a sync, the minute's redraw): the drop is judged on the plan as it then is, by the piece's key; a piece no longer there is "That piece has changed since (done, or planned again).", as the Done says now.
- **Rotation, the process killed**: a drag under way is dropped; the day chosen and the zoom are kept.
- **Landscape**: the card starts folded, so the grid has room.
- **A swipe and a hold on one block**: a sideways movement before the hold's time is a swipe; a hold then a movement is a drag; an upward or downward movement before it is the grid's scroll.

## Done when

- Every behaviour above holds on the phone (`build.md` §The phone, for each release), side by side with the owner's Google Calendar on the same day.
- CI green on `./gradlew testDebugUnitTest lintDebug assembleDebug` for each; the settings' migration tested from version 2; the goldens unchanged; the plan's snapshot and the Felix inputs page unchanged, or their diff explained in the PR.
- The docs moved with the code (`build.md` §Docs, for each), the new decisions numbered in `docs/decisions.md`, PL-04, UI-04, UI-08, UI-11 and UI-17 amended.
- Merged as `4.5.0: a time you give, kept to the minute` and `4.6.0: the Day as a calendar`, tagged, at the owner's word.
