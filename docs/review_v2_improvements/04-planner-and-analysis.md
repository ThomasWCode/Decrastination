# 4. Planner and analysis

The pure core (`core/Planner.kt`, `core/Plan.kt`), what it could place better, what it could tell the student about its choices, and what the Stats tab could show from the activity log it already keeps (`learn/Stats.kt`, `learn/Days.kt`, `learn/Calibrator.kt`).

The planner's design is sound and the v1 review's architecture proposals were rightly declined; nothing here changes the backwards-from-deadline rule. These are additions around it.

## Summary

| ID | Improvement | Priority | Effort | Decision |
|---|---|---|---|---|
| PL-01 | Spread revision for a test across the days before it | P1 | M | Q-14 |
| PL-02 | Say when today can't hold what's in it, and what gives | P1 | M | – |
| PL-03 | A preference for how an evening starts | P3 | S | Q-15 |
| PL-04 | "On Thursday": one change that pins work to a day | P2 | S | – |
| PL-05 | A most-per-day, apart from the hours | P1 | S | Q-16 |
| PL-06 | Plan with the hours the evenings really hold | P2 | M | Q-16 |
| PL-07 | Each piece says why it's where it is | P2 | S | – |
| PL-08 | Stats by subject, with estimate accuracy | P2 | M | – |
| PL-09 | When the work actually happens | P3 | S | – |
| PL-10 | A term's view, not a fortnight's | P3 | M | – |
| PL-11 | Export the log as CSV | P2 | S | – |

---

## PL-01 Spread revision for a test across the days before it

**Now.** Every dated task is placed backwards from its last usable day (`placeBack`, `Planner.kt:369`): one chunk a day, as late as the margin allows. For homework that's right. For revision it's the opposite of what the evidence says works: the steps Claude writes for "revise for the test on the 12th" land on the last evenings before the 12th, which is massed practice.

**Why.** Distributed practice is one of the two best-supported study techniques: Cepeda, Pashler, Vul, Wixted and Rohrer (2006, *Psychological Bulletin* 132(3), 354–380) found spaced practice beat massed practice in the large majority of the 254 studies they reviewed, and Dunlosky, Rawson, Marsh, Nathan and Willingham (2013, *Psychological Science in the Public Interest* 14(1), 4–58) rate it "high utility". The app already knows which steps are revision: the Assignment job returns `testDate`, and `Kind.Revision` exists.

**Proposal.** For a task with a test date (or of kind Revision), a second placement: the first step on the earliest day it can go, the last on the last usable day, the rest spread evenly between with at least a day's gap; a step that can't fit its day moves to the nearest free day. To stop spreading from making every day "under pressure" (a spread chunk on today's bucket would turn strict blocking on), spread chunks are placed as **soft** (like undated work: they fill spare time, don't count toward `pressure`, and can slip) until they reach the day backwards placement would have given them, when they become ordinary chunks. A `spreadRevision` setting, default on, and a `DayLimit`-style instruction to override per task ("do all the German revision on Sunday").

**Final state.** Revision for the 12th appears from the 5th as "if you have time" pieces at the bottom of each day, becoming "due" on the last evenings. The Today screen's soft section already shows such work.

**Caveats.** It changes how the plan looks for revision tasks, which the student has learnt to read; the soft marking keeps the block rule as it is. The margin and calibration still apply. The eval set has few revision cases, so test with the planner's own tests first (`PlannerTest`).

**Effort.** M. **Decision.** Q-14.

## PL-02 Say when today can't hold what's in it, and what gives

**Now.** A chunk that fits nowhere before its deadline goes into today "flagged behind" (planner item 4), so today's bucket can hold more than today's capacity. The Today screen lists it all; the student can see a long list but not that it's impossible, nor which pieces are the ones that won't fit. `Days.capacityAdvice` (`Days.kt:121`) speaks only after ten such days.

**Proposal.** Three small things on the same data:

- `DayBucket` already has `capacity`; add `overflowMin` and mark the chunks past it (`Chunk.wontFit`). On Today: a line under the day header, "3 h 15 free, 4 h 30 planned: the last two pieces won't fit tonight", the pieces greyed.
- A *What gives?* button on that line that opens the chat with "Tonight can't hold everything. What should I shorten, move or ask for more time on?" prefilled; Claude already knows the plan and can propose `Minutes`, `StartFrom` and `DueBy` changes as cards.
- The same sum for the next seven days ("12 h planned, 14 h free; Thursday is over") as a tile on Stats and a line in the briefing when any day is over.

**Final state.** An impossible evening is named as such at 16:45, not discovered at 22:00; the fix is a conversation, not a scroll.

**Caveats.** "Won't fit" depends on the hours in Settings being right (PL-05, PL-06). A student who learns that "what gives?" always moves things will use it to move things; the moves are instructions, logged, and visible to you (NF-02).

**Effort.** M.

## PL-03 A preference for how an evening starts

**Now.** Within a day, after urgency, pieces are ordered homework before revision before admin, then the shorter task first (`ORDER`, `Planner.kt:462`). That's deliberate (no flicker) and sensible, and it's the same for every student.

**Proposal.** A setting, `eveningStart: Quickest | Hardest | Anki | Default`, used only as the tie-break after urgency: Quickest puts the shortest piece first (a five-minute win to get going), Hardest the longest or the subject with the lowest completion rate, Anki the deck pieces. Default keeps today's order.

**Caveats.** It's a preference, not a scheduling rule, and only reorders within a day; it can't move work between days. Honest doubt: the DO NOW card already shows one piece, so the gain is small; P3.

**Effort.** S. **Decision.** Q-15.

## PL-04 "On Thursday": one change that pins work to a day

**Now.** Pinning a task to a day takes two instructions' worth of change (`StartFrom` the day and `DueBy` its end), and Claude has to work that out from "do the German on Thursday".

**Proposal.** `ChangeType.OnDay(date)`, applied as the floor and the ceiling together, listed in the instruction and chat prompts as one change ("OnDay: all of it on that day"). Pairs with drag-to-day on the Today screen (UI-04), which would write the same change without Claude.

**Caveats.** A day with no room makes the task "behind" on that day; the card should say so before it's applied (the chat's cards could show the plan's answer, as the proposal card already previews changes).

**Effort.** S.

## PL-05 A most-per-day, apart from the hours

**Now.** A day's capacity is the hours window less busy time (`capacity`, `Planner.kt:587`): 5 h 15 on a weekday, 14 h on a weekend day. The planner will put six hours of a big project on a Saturday if that's what backwards placement gives, and the day-limit instruction (`dayCaps`) is per day, by hand.

**Proposal.** Two settings, `weekdayMaxMin` and `weekendMaxMin` (suggested defaults 180 and 300), applied as a default `dayCap` where no instruction sets one. The window still says *when*; the max says *how much*. Work that then doesn't fit before its deadline shows as behind earlier, which is the planner's own argument in `capacityAdvice`.

**Caveats.** Any cap makes "behind" more common for big tasks, which is honest but can look alarming; the briefing should say "this week needs more than your usual evenings; the weekend's cap is 5 h" rather than just flagging. The defaults are your call.

**Effort.** S. **Decision.** Q-16.

## PL-06 Plan with the hours the evenings really hold

**Now.** `DayRecord` keeps each day's planned and done minutes and whether the plan was done in full; `capacityAdvice` turns ten bad days into a sentence. The planner keeps planning the full window.

**Proposal.** A learned *yield* in `Calibrator`: the median of done ÷ planned over the last 14 days with work in them, clamped to 0.5–1.0, needing at least 7 days; the planner's capacity becomes window × yield (before the day cap, PL-05). Shown on Stats: "Your evenings hold about 70 % of the hours in Settings". The weekly review may be allowed to change the hours themselves, within bounds, as it may the box and margin today (a new bounded change in `ReviewInput`).

**Caveats.** Feedback loops: less planned → less done → less planned. The clamp and the minimum days limit it, and planning less than is done raises yield again. It also hides a settings problem (hours set too long) behind a number; show both.

**Effort.** M. **Decision.** Q-16.

## PL-07 Each piece says why it's where it is

**Now.** A piece's sheet shows the task, the step and the minutes. Why it's today (due tomorrow; the margin; a chunk that fits nowhere else; undated filler) is in the planner's head only.

**Proposal.** A `Chunk.why` enum set during placement: `DueSoon`, `Overdue`, `LastUsableDay(margin)`, `SpreadBack(from deadline)`, `WontFitLater`, `Soft`, `PerDayLimit`, `Spread` (PL-01). One line on the piece's sheet ("Today because it's due Thursday morning and your Physics margin is 1 day") and in the chat's plan context, which lets Claude explain the plan correctly instead of guessing.

**Caveats.** It's a string more per chunk in a structure rebuilt on every change; negligible. The wording must stay short.

**Effort.** S.

## PL-08 Stats by subject, with estimate accuracy

**Now.** Stats shows a fortnight: finished, on time, focus minutes, blocks, free time, and a row per day (`ui/StatsScreen.kt:77`). The calibration's multipliers are per kind and class (`Calibrator.multipliers`, `Calibrator.kt:66`) and are described in words on the Setup page, not as numbers by subject.

**Proposal.** A *By subject* section: for each class in the period, minutes worked, pieces finished, on-time share, and the ratio of worked to estimated minutes ("Physics: 2.1× Claude's estimate, from 4 tasks"). Sorted by minutes. The same table in the weekly review's input (AI-18) so it can comment on it.

**Final state.** You and the student can see that Physics takes twice the estimate and German is on time, which is the conversation the app exists to inform.

**Caveats.** Needs the class on each completion record (`Completion.className`; check it's stored, else add it). Small samples lie: show the task count beside every ratio and nothing under two tasks.

**Effort.** M.

## PL-09 When the work actually happens

**Now.** Sessions by hour are computed for the weekly review (`ReviewInput.describe`) and not shown. The hours in Settings are a guess at when the student works.

**Proposal.** A small 7 × 24 heat grid (day of week × hour) of focus minutes over the last 8 weeks on Stats, and a line "Most work: 17:00–19:00 on weekdays; Sunday mornings". A tap offers to set the hours from it.

**Caveats.** Only sessions count; untimed work isn't seen. Eight weeks of sessions is a few hundred rows, trivial.

**Effort.** S.

## PL-10 A term's view, not a fortnight's

**Now.** `Stats.DAYS` is 14; the runtime keeps 120 days of records (`RuntimeState.KEEP_DAYS`, `RuntimeState.kt:133`).

**Proposal.** A period picker (2 weeks, half term, term) and weekly rollups kept forever (one row a week: minutes, finished, on time, blocks, full days), so the log can stay at 120 days without losing the trend. A sparkline of weekly minutes and full-day share.

**Caveats.** The rollup is new data to migrate and back up; it belongs in the activity log's file with its own version. P3 until a term's worth exists to look at.

**Effort.** M.

## PL-11 Export the log as CSV

**Now.** `data/Backup` exports the stores as JSON for restore. There's no form you can open in R or a spreadsheet.

**Proposal.** Settings → Stats → Export: `completions.csv`, `sessions.csv`, `days.csv`, `blocks.csv`, `calibration.csv`, zipped and handed to the share sheet (email it to yourself). One row per record with ISO dates, the class, kind, source, estimate, worked, due, done-at.

**Caveats.** The export holds task titles; it leaves the phone only by the student's share. No Claude text is in it.

**Effort.** S.

---

## What I looked at and left alone

- **The backwards placement and the pressure rule** (`Plan.pressure`, `Plan.kt:113`): the design's centre and right; PL-01 works around it with soft chunks rather than changing it. The spendable-credit question it raises is in [06-blocking-and-focus.md](06-blocking-and-focus.md), BL-01.
- **The 90-day horizon, the 14-day minimum, the 5-minute minimum chunk**: fine.
- **Undated work's soft week and 60 minutes a day**: fine; the review can change the minutes.
- **The v1 proposals for a constraint solver or a different allocation model** (IMP-P2-023/024): declined by you, and I agree.
