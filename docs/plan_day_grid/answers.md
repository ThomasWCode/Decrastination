# The owner's answers, and the choices made for them

The questions put to the owner while the Day's change was planned (25 Oct 2026), their answers as given, and the choices the plan makes without asking. An assumption the owner overturns is changed here and in `README.md` before the build touches it.

## Asked and answered

**Q1. Which Google Calendar view should the Day tab copy?** Offered: the Day view, a time grid (recommended: hours down the left, each slot and event a block as tall as it lasts, side by side where they overlap, a now-line, all-day events as chips under the date, the windows as bands, the "Free, 30 min" rows gone); the Schedule view, a restyled list; the grid with a Schedule toggle.

> Day view: time grid (Recommended)

Applied: `README.md` §What the Day looks like (5); `build.md` 4.6.0 stages 4 and 5.

**Q2. Google changes day by a sideways swipe; on the Day a sideways swipe on a slot is Done or Later today (UI-03). What should a sideways swipe do?** Offered: change day, with a ✓ on each block for Done and Later today on the piece's sheet, UI-03 amended (recommended); keep UI-03's swipes, days changed only from the header; both, by where the swipe starts.

> Keep UI-03's swipes

Applied: `README.md` §The blocks (the swipe column) and §What it must cope with (a swipe and a hold on one block); `build.md` 4.6.0 stage 5 (`PieceBlock` keeps `PlannedRow`'s swipe) and §Not in these releases (no swiping between days).

**Q3. How should a day be picked, and the week's load (UI-02) be shown?** Offered: Google's header with a month drop-down carrying each day's load, the strip gone (recommended); keep the 7-day strip above the grid; both.

> Keep the 7-day strip

Applied: `README.md` §What the Day looks like (2, and the header's date in 4 as Google's, with no month drop-down); `build.md` 4.6.0 stages 6 and 7 (the strip's days as drop targets).

**Q4. Where should today's DO NOW card, "won't fit", "No time today", "Done today" and "An instruction about this day…" go?** Offered: the card kept, folding to one line as the grid scrolls, the rest as chips under the date, each opening a sheet (recommended); all above the grid as now, two scrolls one inside the other; the card dropped from the Day, the rest as chips.

> Card kept; the rest as chips (Recommended)

Applied: `README.md` §What the Day looks like (3 and 4); `build.md` 4.6.0 stage 6.

**Q5. How should blocks be coloured?** Offered: events in their calendar's colour, the app's pieces in its navy (UI-12) (recommended); pieces in the colour of the calendar they're written to; pieces by source, with a legend; everything in the app's blues.

> Events by calendar, slots navy (Recommended)

Applied: `README.md` §The blocks and §Colours; `build.md` 4.6.0 stage 3.

**Q6. Holding a slot opens the move sheet today (UI-04). Google moves an event by hold-and-drag. Which for the grid?** Offered: hold and drag up or down to a time, only a task's next piece taking a time (PL-04), another day from the sheet (recommended); hold opens the sheet, as now; drag, or drop on a day of the strip.

> You should both be able to hold and drag up and down in a day, as you described, but also onto the day blocks at the top to move it all onto that day. When moving it within a day, you should be able to move it to anywhere you want as long as it is before the deadline, which means if you move it overlapping onto another existing task, the other tasks should reorganise, and if you move it after any of the later steps within that task, the other steps should reorganise to be afterwards.

Applied: `README.md` §Moving a piece by hand; `build.md` 4.6.0 stages 1 and 7. Two parts of it needed more than the Day could give as the planner stood, so Q9 and Q10 were asked:

- "Anywhere you want": a time was kept only where it was free (PL-04), so a drop over another task's slot, outside the windows or over an event would have jumped to the next free time. Q10.
- "The other steps should reorganise to be afterwards": that is what the planner already does for the task's next piece (D-108: a step follows the steps before it), so moving the next piece after its later steps' slots moves them after it. A later piece couldn't take a time at all (PL-04: one time a task, for its next piece). Q9.
- "The other tasks should reorganise": a pin is laid before today's kept starts already, so the pieces it covers are laid again around it; with Q10's answer, this holds wherever the pin is.

**Q7. Which hours should the grid show?** Offered: all 24 hours as Google does, opening an hour before now, pinch to zoom 40–120 dp an hour, remembered on this phone, the hours outside the windows shaded (recommended); only the day's windows, an hour either side; all 24 hours, no zoom.

> All 24 h, pinch to zoom (Recommended)

Applied: `README.md` §What the Day looks like (5); `build.md` 4.6.0 stages 2 and 5.

**Q8. Google makes a new event when empty grid is tapped. What should a tap on empty grid do?** Offered: nothing (recommended); a new task of yours, pinned at that time; the chat, about that time.

> Nothing (Recommended)

Applied: `build.md` §Not in these releases.

**Q9. A time is stored for a task's next piece only (PL-04); the steps after it already follow it (D-108). Which pieces should drag to a time?** Offered: the next piece only, a later one dragged onto a day of the strip but not to a time, no stored data changed (recommended); any piece, its task's earlier steps kept before it, a time per piece in the stored changes (`instructions.json`'s version raised, a migration, PL-04 amended); any piece, its earlier steps pushed ahead of it.

> Next piece only (Recommended)

Applied: `README.md` §Moving a piece by hand (a later piece of its task); `build.md` 4.6.0 stage 7 and §Not in these releases.

**Q10. PL-04 puts a pinned time that isn't free at the next free time after it. When you drop a piece somewhere like that, what should happen?** Offered: kept to the minute, for every time you or Felix give (on the Day, in the chat, by moving its event in Google Calendar), beside an event as Google draws overlaps, unpinned pieces moving out of its way, a pin it lands on moving to the next free time after it, refused only for a time gone, before it can start, or ending after its real deadline (overdue work aside, D-30), no stored data changed, PL-04 amended (recommended); kept to the minute for the Day's drags only, a flag on the stored change; the next free time, as now.

> Exact, for every pin (Recommended)

Applied: `README.md` §Two releases and §A time you give, kept to the minute; `build.md` 4.5.0, all of it.

## Decided without asking

Each a routine call the owner may overturn; the alternative is named so the build can switch.

1. **Two releases, after the timer.** The planner's change (4.5.0) is reviewed and checked on the phone before the screen that leans on it (4.6.0); both after 4.4.0, whose plan is ready and which touches the Day only in its DO NOW card, which 4.6.0 keeps. Alternative: one release; or these before the timer, which then takes the next number.
2. **The card folds as Material's top bar does with `enterAlways`**: a scroll towards later hours folds it first, one towards earlier hours unfolds it first, a tap on the folded line unfolds it; in landscape it starts folded. Alternative: it unfolds only when the grid is back at its top, which, as the grid opens at the hour before now, would leave it folded most of the time.
3. **Today's line has no label**: a line and a dot, as Google draws it; the hours in the gutter say the time. Alternative: keep "Now 16:12" in the gutter beside the dot.
4. **Every block at least 24 dp tall**, so a short piece can still be read, tapped and swiped; **a ✓ on a piece's block** where it's at least 32 dp tall and 120 dp wide, doing what *Done* on the row does now. Alternative: no ✓, Done by the swipe and the sheet alone, as Google's blocks have no button.
5. **An event's block**: outlined only where the plan counts it free (so a piece laid over it reads right), and a small "?" where the plan needs your answer about it; the verdict's words stay on its sheet. Alternative: the verdict as a pill on every event's block, as the row has it now, where there's room.
6. **A pin outside the windows still counts against that day's free time**, as one inside does: the day may then hold a little less other work than its windows could. Alternative: its minutes added to that day's free time, a change to `Planner.capacityOf` and to what the strip's bar says.
7. **Only the Day's and the move sheet's refusals count the piece's minutes** (a piece that wouldn't be over by its real deadline is refused before it's made). Felix's times (`InstructionRules.latePin`) and the calendar's hand moves keep "at or after the deadline", and a time of theirs whose piece can't be over by it is laid as now, at the next start that is, else with no time. Alternative: one rule everywhere, which needs the piece's minutes where `latePin` is checked, from the plan.
8. **A pin outside every window takes the kind of the window nearest it in time**, for its record and for what it teaches the day yields: one in a lesson counts as school's, one late at night as the evening's. Alternative: always the evening's.
9. **The zoom is kept in `settings.json`** (its version raised, nothing migrated), as Q7's answer said "remembered on this phone". Alternative: kept only while the app runs, back to 60 dp at each start, with no store change.
10. **A second first-run hint** for the drag, which nothing on the screen shows: "Hold a piece to move it: drag it to a time, or onto a day above." Alternative: none.
11. **Let go on its own day in the strip, nothing changes**, as that's most likely a drop that missed. Alternative: all of the task pinned to that day (an `OnDay` change), as the move sheet's *All of it, any time that day* does when its own day is picked.
12. **Events, records and outside breaks don't drag**: events are the calendar's, records history, and a break outside the app's own, put back by the calendar's hand moves already.
13. **The grid is the wall clock's day**: on the day the clocks go back, the repeated hour is drawn once, two things in it side by side; on the day they go forward, its missing hour is empty. Alternative: a 23- or 25-hour grid on those days, which Google doesn't draw either.
14. **The header's chips' words**: "2 won't fit", "3 with no time", "40 min done", "Instructions · 2", or "Instruction…" where there's none; the sheets they open hold what the list held, as it was worded.
15. **The drag's numbers**: five-minute steps; the grid scrolls when the finger is within 48 dp of its edge, up to 12 dp a frame; settled on the phone.
16. **TalkBack's *Move it* opens the move sheet**, so moving a piece never needs the drag; the sheet keeps the fortnight and a time by the clock.
17. **A new spec page, `docs/spec/day.md`**, as the Day has had none, its rules spread over the requirement ids. Alternative: the rules in `slots-and-calendar.md` and the requirement ids alone.

## What the plan couldn't check

The look of Google Calendar's Day view is described from the app as it's commonly seen; this session couldn't open it. Its Material 3 Expressive update put the hours and the day's header in tinted containers in place of faint lines (reported by [9to5Google](https://9to5google.com/?p=683608) and [PhoneArena](https://www.phonearena.com/news/calendar-app-to-get-material-3-redesign_id173057)), and no source found confirmed the details of its now-line or its gestures. So the tints, the line's colour, the gutter's width and the blocks' corners are settled on the phone, side by side with the owner's own Google Calendar (`build.md` §The phone, 4.6.0, step 1), not taken from here as fixed.
