# 4. Planner and analysis

The pure core (`core/Planner.kt`, `core/Plan.kt`), what it could place better, what it could tell you about its choices, and what Stats could show (`learn/Stats.kt`, `learn/Days.kt`, `learn/Calibrator.kt`).

Revised after your answers and notes. The first version kept the planner's backwards-from-deadline rule and added around it. Your answer to Q-16 and notes 2–4 change the rule itself: work not due today or tomorrow is spread evenly over the days to its deadline by the time each day really has; every piece gets a time of day, written to your Google Calendar and adjusted live; dates the app or Claude set never go "overdue", they're re-fitted; only a source's deadline can be overdue, and even then the work is re-fitted around it. PL-01, PL-12, PL-13, PL-14 and PL-15 are that design. The planner stays pure and deterministic; what changes is the placement.

## Summary

| ID | Improvement | Priority | Effort | Status |
|---|---|---|---|---|
| PL-01 | Spread every later task evenly to its deadline, by the time each day has | P1 | L | Changed (Q-16, note 3); details confirmed (Q-32); Implemented (v3 phase 2) |
| PL-02 | Say early when a source deadline can't be met, and what gives | P1 | M | Changed; Implemented (v3 phase 2; Felix's part with phase 5) |
| PL-03 | A preference for how an evening starts | – | – | Withdrawn (Q-15) |
| PL-04 | "On Thursday" and "at 19:30": pin work to a day or a time | P2 | S | Changed; Implemented (v3 phase 3; the Move sheet with phase 6) |
| PL-05 | A most-per-day, apart from the hours | – | – | Withdrawn (Q-16) |
| PL-06 | Plan with the hours the days really hold: a learned yield, per kind of day | P1 | M | Changed (Q-16, Q-49); Implemented (v3 phase 2: weekdays and weekends; school windows with phases 3 and 4) |
| PL-07 | Each piece says why it's where it is | P2 | S | Implemented (v3 phase 2; pins and refits in phase 3) |
| PL-08 | Stats by subject, with estimate accuracy | P2 | M | – |
| PL-09 | When the work actually happens | P3 | S | Changed |
| PL-10 | A term's view, not a fortnight's | P3 | M | – |
| PL-11 | Export the log as CSV | P2 | S | – |
| PL-12 | Every piece gets a time: slots in the day's free windows, and outside breaks at weekends | P1 | L | New (note 2, Q-32); Implemented (v3 phase 3) |
| PL-13 | Adjust live: slots move when the day does | P1 | M | New (note 2); Implemented (v3 phase 3) |
| PL-14 | Dates the app set are elastic; only a source's deadline is overdue | P1 | M | New (note 4); Implemented (v3 phase 2) |
| PL-15 | Study periods, break and lunch as planning windows | P1 | M | New (note 2, Q-30); settled (Q-52); Implemented (v3 phase 3) |
| PL-16 | The school calendar: holidays, half-days, INSET days | P1 | M | New (Q-43); settled (Q-50); Implemented (v3 phase 3; the chat's button with phase 5) |
| PL-17 | Weekend windows: two a day | – | – | Withdrawn (Q-49) |

---

## PL-01 Spread every later task evenly to its deadline, by the time each day has

**Now.** Every dated task is placed backwards from its last usable day (`placeBack`, `Planner.kt:369`): one chunk a day, as late as the margin allows. The header comment explains why: placing everything early would put every task in today and the block would never lift. The cost is the one you describe in note 3: a task with a far deadline gets nothing until its last days, then arrives all at once with everything else.

**Proposal.** A third placement between "due now" and "undated":

1. Overdue work and work due today or tomorrow are placed first, as now (`placeNow`): they take today's time before anything else and nothing below touches them.
2. Each day's capacity is the time it really has: its windows (evening hours; school free periods, PL-15) less calendar busy time and hard busy instructions, times the learned yield (PL-06). No fixed maximum (your answer to Q-16).
3. Every other dated task, in deadline order, is spread over its window (its first possible day to its last usable day, margin included): its remaining chunks go to days in proportion to the free time those days have left, so the task progresses every week of its window rather than waiting for its end. A day that's full passes the chunk to the nearest day in the window with room, earlier preferred, so the task still finishes. A task with a window of a week or more gets at least one chunk a week (the rule that stops a far task vanishing until it's urgent; confirmed in Q-32).
4. Revision for a test keeps its own rule: steps at least a day apart (the spaced-practice evidence from the first version: Cepeda et al., 2006, *Psychological Bulletin* 132(3), 354–380; Dunlosky et al., 2013, *Psychological Science in the Public Interest* 14(1), 4–58).
5. Undated and app-dated work (PL-14) fills what's left, as now, up to `softMinPerDay`.

Pressure, and so the block, is today's bucket: the chunks the spread gave today (BL-01). That is what makes spreading safe where "everything early" wasn't: today holds a share of the week, not the week.

**Final state.** A Physics booklet due in twelve days with six chunks is on Tuesday, Thursday, Saturday, Monday, Wednesday and the Thursday before, not on the last six evenings. Today's list is a fair day's work, every day, and the block lifts when it's done.

**Caveats.** More pieces per day, each shorter; the chunk size (`boxMin`) becomes the piece length, 30 minutes by default (Q-32). A single-chunk task has no "even": it goes to the day in its window with the most free time, earlier on a tie (Q-32). The spread changes with every change in capacity (a new calendar event), which is why PL-13's stability rule matters. The planner tests (`PlannerTest`) that encode backwards placement change; AR-04's property tests should go in first.

**Effort.** L. The details (proportional spread, one touch a week, the most-free day for a single piece, 30-minute pieces) were confirmed in Q-32.

## PL-02 Say early when a source deadline can't be met, and what gives

**Now.** A chunk that fits nowhere before its deadline goes into today "flagged behind"; today's list can hold more than today's capacity; nothing says it's impossible until `Days.capacityAdvice` speaks after ten bad days (`Days.kt:121`).

**Proposal.** With spreading, "can't fit" means the whole window is short: the spread finds the task's remaining minutes exceed the free time in its window. Say it the day it becomes true: a line on the Day view ("Physics coursework: 6 h left, 4 h 10 free before Friday"), a nag (LR-03) and a *What gives?* button that opens the chat prefilled ("This week can't hold the Physics coursework by Friday. What should I shorten, move or ask for more time on?"). For app-set dates (PL-14) there's no such state: they're re-fitted.

**Effort.** M.

## PL-03 Withdrawn

An evening-start preference (quickest / hardest / Anki first): your answer to Q-15, no.

## PL-04 "On Thursday" and "at 19:30": pin work to a day or a time

**Now.** Pinning a task to a day takes two instructions' worth of change; pinning to a time isn't possible.

**Proposal.** `ChangeType.OnDay(date)` and `ChangeType.AtTime(date, minuteOfDay)`: the first floors and ceilings the task to a day, the second gives a piece a fixed slot (PL-12) the planner builds around like a calendar event. Both in the instruction and chat prompts, both written by drag on the Day view (UI-04) without a call. A move that would clash with the task's due date is refused with the reason (your answer to Q-17).

**Effort.** S.

## PL-05 Withdrawn

A fixed weekday/weekend maximum: your answer to Q-16 is that a day's maximum is the time it has. That's PL-01's capacity rule with PL-06's yield.

## PL-06 Plan with the hours the days really hold: a learned yield, per kind of day

**Now.** `DayRecord` keeps each day's planned and done minutes; `capacityAdvice` turns ten bad days into a sentence; the planner keeps planning the full window.

**Proposal.** A learned *yield* in `Calibrator`, per kind of day and window, since your answer to Q-49 makes it the only thing that corrects the weekend's share: one yield for weekday evenings, one for weekend days (and no-school days, PL-16), one for school windows (PL-15). Each is the median of done ÷ planned over that kind's last 14 days with work in them, clamped to 0.5–1.0, used from 7 such days on and 1.0 until then. A day's capacity is its free time × its kind's yield; done minutes come from slot outcomes (LR-09). Shown on Stats: "Weekday evenings hold about 85 % of their free time; weekends about 45 %". The weekly review may change the hours themselves within bounds, as it may the box and margin today.

**Final state.** After a few weekends the spread stops putting three times a weekday's share on a Saturday, because Saturdays have shown what they deliver.

**Caveats.** Honest about Q-49's option 3: for the first few weekends the fourteen-hour window is planned near-full and under-delivered, and the missed slots re-fit into the week (PL-13); the per-kind yield then corrects it, and the clamp at 0.5 means a Saturday is never planned at less than seven hours of a fourteen-hour window. Narrowing the weekend hours in Settings remains the direct fix if that's still too much. The feedback loop (less planned, less done, less planned) is bounded by the clamp and reverses as soon as you do what's planned. Confirmed in Q-56; the 0.5 floor stays for every kind, since you didn't ask for a lower one on weekends.

**Effort.** M.

## PL-07 Each piece says why it's where it is

**Now.** A piece's sheet shows the task, the step and the minutes; why it's today is in the planner's head.

**Proposal.** `Chunk.why`: `DueSoon`, `Overdue`, `Spread(of N, to deadline)`, `MinimumTouch`, `Soft`, `PerDayLimit`, `Pinned`, `Refitted(from when)`. One line on the sheet and in the chat's plan context, so Claude explains the plan rather than guessing at it.

**Effort.** S.

## PL-08 Stats by subject, with estimate accuracy

**Now.** Stats shows a fortnight: finished, on time, focus minutes, blocks, free time, a row per day (`ui/StatsScreen.kt:77`). The calibration's multipliers are per kind and class (`Calibrator.multipliers`) and shown in words only.

**Proposal.** A *By subject* section: per class, minutes done (from slot outcomes), pieces finished, on-time share, and actual ÷ estimated ("Physics: 2.1× Claude's estimate, from 4 tasks"); task counts beside every ratio, nothing under two. The same table in the review's input (AI-18).

**Effort.** M.

## PL-09 When the work actually happens

**Now.** Sessions by hour are computed for the review and not shown; sessions go.

**Proposal.** A 7 × 24 grid of slots kept (done in their slot) against slots missed over 8 weeks; "Most kept: 17:00–19:00 weekdays; Tuesday study periods". A tap offers to set the hours from it.

**Effort.** S.

## PL-10 A term's view, not a fortnight's

**Now.** `Stats.DAYS` is 14; the runtime keeps 120 days (`RuntimeState.KEEP_DAYS`).

**Proposal.** A period picker and weekly rollups kept forever (one row a week), with a sparkline of weekly minutes and full-day share. P3 until a term exists to look at.

**Effort.** M.

## PL-11 Export the log as CSV

**Now.** `data/Backup` exports JSON for restore; nothing opens in R or a spreadsheet.

**Proposal.** Settings → Stats → Export: `completions.csv`, `slots.csv`, `days.csv`, `blocks.csv`, `calibration.csv`, zipped to the share sheet. Also what the weekly summary (NF-02) attaches.

**Effort.** S.

## PL-12 Every piece gets a time: slots in the day's free windows, and outside breaks at weekends

**Now.** A day's plan is an ordered list; when in the day a piece happens is up to you. Your note 2: assign every task a specific time, put it in Google Calendar, adjust live, and use the school's free periods.

**Proposal.** After the day buckets are made (PL-01), a slotting pass per day, with the details you confirmed in Q-32:

- The day's windows: the evening hours (weekday or weekend), plus the school windows (PL-15) on school days; minus calendar busy events, hard busy instructions and pinned slots (`AtTime`).
- Pieces are 30 minutes by default (`boxMin` 45 → 30) and are laid into the windows in the day's order (`ORDER`, `Planner.kt:462`: due-soon first, then deadline, then kind), each slot the piece's minutes, with a 5-minute break between slots and a 15-minute one after 90 minutes of work (both settings). A piece that doesn't fit the window's remainder goes to the next window; the first evening slot starts at the window's start; today's first slot starts no earlier than now.
- **Outside breaks (Q-32).** On a weekend day, three 20-minute *Outside* slots are laid in first, spread over the day's windows (morning, midday, afternoon), before the work is placed. They aren't work: they earn nothing, they don't count toward pressure, and they're on the calendar like everything else. When the day's work doesn't fit its free time with all three, they're dropped one at a time (three, two, one, none) until it does; their length never changes. Three on each of Saturday and Sunday, one each in the morning, around midday and in the afternoon at the start of the nearest free window, the midday one dropped first (Q-41).
- Each slot is `Slot(piece | Outside, start, end, window: Evening | School, pinned)`, on the `DayBucket`. The Day view (UI-17) shows them as a timeline with the calendar's events; NF-07 writes them to the calendar; AI-15's micro-plan is attached to each work slot; Felix's messages (LR-03) fire from them.

**Final state.** Tuesday reads: 10:45–11:15 German vocab (break); 13:55–14:25 Reply to Mr Peters (lunch); 16:45–17:15 Statics Prep; 17:20–17:50 Statics Prep 2 of 4. Saturday has three *Outside* cards between the work. The same on your phone's calendar.

**Caveats.** A plan with times is a plan that's visibly wrong more often (you're late, the bus is late); PL-13 is what makes that tolerable, and Felix's tone (AI-22) is what makes it a friend's reminder rather than an app's accusation. Slotting is deterministic given the buckets, so the planner's purity and tests hold; it's new code with its own invariants (AR-04): no overlap, inside windows, breaks kept, outside slots dropped in order.

**Effort.** L.

## PL-13 Adjust live: slots move when the day does

**Now.** The plan re-runs on every change (sync, instruction, calendar answer), but there's no notion of a slot being missed or kept.

**Proposal.** Events and rules:

- **Slot kept** (you ticked the piece, or the photo check passed): recorded as done with its minutes (LR-09). The following slots keep their times; the gap is yours. A *Pull the next forward* button on the Day view moves the rest up if you'd rather.
- **Slot missed** (its end passed with no tick, after a 10-minute grace): the piece is re-slotted into the next free time today after the current slots, pushing later pieces back within today's windows; what no longer fits today goes to tomorrow's spare time and the rest of its task is re-spread (PL-01). The piece is never "overdue" unless its source deadline has passed (PL-14). A nag goes out (LR-03).
- **What a missed slot does to the block** is BL-11 (Q-48): re-slotting the piece to tomorrow must not be a way of opening the phone tonight.
- **An event you add** in Google Calendar (or any calendar the app reads) that overlaps a slot takes the time: the slot is re-fitted around it at the next replan, like any other change to the day. A slot event you deleted there is written back only if its time is still free; with an event of yours in its place, the piece moves (your answer to Q-53, confirmed in Q-57).
- **The day changes** (a calendar event appears, an instruction lands, a task arrives): re-slot the affected day with a stability rule: a piece keeps its slot unless something now overlaps it, and moved pieces move as little as possible. Without this the timeline would reshuffle itself every sync and you'd stop trusting it.
- **Triggers.** An alarm at each slot's end (exact alarms are already used for sessions' ends; reuse), the app opening, a calendar change (`watchCalendar` exists), a sync.

**Final state.** At 17:40, having not started the statics at 16:45, the timeline shows it at 17:45 and the reply at 18:35; the calendar matches; a message asks what happened.

**Caveats.** The stability rule is the hard part to get right; write its invariants first (AR-04). Re-slotting writes to the calendar often (NF-07); batch the writes per replan.

**Effort.** M (given PL-12).

## PL-14 Dates the app set are elastic; only a source's deadline is overdue

**Now.** Every due date is hard: a step's `from`/`due` from Claude, a quick action's invented two weeks, the undated week, all show "Overdue" when passed and rank ahead of real work.

**Proposal.** Your note 4, read as: a date set by the app or by Claude (a step's dates, a quick-action date, the soft week, a spread day, a slot) is a plan, not a deadline. When it passes, the work is re-fitted (PL-13) and nothing is labelled overdue. A date from a source (Teams, Power Planner, an email's own words, `deadlineQuoted` in AI-12) is a deadline: the work is still re-fitted around it, placed first today as now, but the task shows "Overdue" from the moment it passes, in red, as it does today. I've read "subtasks in a task" as a task's steps and "items where the app set a deadline" as everything the model or the app dated; confirm in Q-40.

**Final state.** The only red on the screen is a real deadline missed. Everything else moves.

**Caveats.** Soft dates still order the work, so the model's step dates still matter; they just can't shame you. Stats' "on time" keeps counting source deadlines only.

**Effort.** M.

## PL-15 Study periods, break and lunch as planning windows

**Now.** Work is planned in the evening window only (`weekdayHours`, 16:45–22:00); school hours are a planning no-go and a blocking allow (`BlockPolicy.isSchoolHours`). Your note 2 and your answer to Q-30: the timetable has study periods; break and lunch are at fixed times every day.

**Proposal.** On each school day, three kinds of window, no buffer at their ends (Q-30):

- **Study periods**: the timetable's entries named "Study Period" (DX-09), at their times that day (two-week timetables handled).
- **Break**: 10:45–11:15, every school day.
- **Lunch**: 13:15–13:40 and 13:55–14:35, every school day; 13:40–13:55 is eating and never planned.

Break and lunch are settings with those defaults, since the timetable may not carry them; a study period comes from the timetable alone. None of them exist on a day the school calendar (PL-16) marks as no school, and on a half-day they end at the time given. Any calendar event overlapping a window takes its time out of it. A window shorter than 15 minutes after that isn't used (confirmed in Q-43). Only pieces that can be done there are placed in them: the model marks a step `where: "anywhere" | "home"` (a booklet is anywhere; a Teams upload or a long essay is home; Anki is always anywhere), and you can override on the sheet (confirmed in Q-43). One refinement the windows make possible: a deadline later the same day becomes usable up to its time in the windows before it (a task due at 13:00 can be slotted at break), where today `lastUsableDay` treats a morning deadline's day as unusable; the calibrated margins still apply (confirmed in Q-52). The windows are shown on the Day timeline in their own colour. The phone's rules at school are unchanged (your answer to Q-29; BL-09): a school slot is done with the booklet or the laptop and ticked after.

**Final state.** A Wednesday with a study period holds the vocab at break, the short admin at lunch, and the study period's half hour of physics, before the evening starts.

**Caveats.** Free periods vanish (a cover lesson, a trip) without the calendar knowing; a missed school slot is re-fitted like any other (PL-13), and the yield (PL-06) can be learned per window kind so school windows that rarely get used count for less. A cancelled study period or a trip needs a calendar event to say so (confirmed in Q-43); holidays and half-days are told to Felix instead (PL-16).

**Effort.** M.

## PL-16 The school calendar: holidays, half-days, INSET days

**Now.** The app knows weekdays and weekends; it doesn't know term dates. A school holiday is planned as a school day (study-period windows that don't exist, the weekday hours), and the blocker allows blocked apps all morning as "school hours".

**Proposal.** Your answer to Q-43: you tell Felix about holidays and half-days. A `SchoolCalendar` store with `NoSchool(from, to, label)` (holidays, INSET days) and `HalfDay(date, endsAt)`, told to Felix or the chat ("half term is 26 to 30 Oct", "Friday finishes at 12:30"), which makes the entry as a button-first change (Q-42: it's new data), or typed in Settings → School. Effects (confirmed in Q-50): a no-school day has no school windows and takes the weekend's hours, outside breaks (PL-12) and block-from time (BL-02); a half-day's school windows end at the time given and the evening window starts then; the spread (PL-01) sees the extra time at once; Felix knows ("half term next week: I've spread the German over it"). The week strip (UI-17) shows no-school days with the weekend's shape.

**Caveats.** Power Planner's semester has start and end dates but no holidays, so this stays typed or told. A holiday told wrongly plans a school day as free; the week strip shows it and the entry is editable.

**Effort.** M.

## PL-17 Withdrawn

Two weekend windows, or a share cap on the weekend: your answer to Q-49, no. The weekend keeps its one window and the yield learns what weekends deliver, per kind of day (PL-06).

---

## What I looked at and left alone

- **The planner's purity** (everything through `Input`, deterministic): kept; slots and spreading are more of the same.
- **The horizon** (90 days), the 5-minute minimum chunk, the margin and calibration: kept.
- **The v1 proposals for a solver** (IMP-P2-023/024): still not needed; proportional spreading and greedy slotting are enough and explainable.
