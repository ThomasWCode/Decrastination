# 6. Blocking and focus

The incentive system: when blocked apps are blocked (`block/BlockPolicy.kt`), how free time is earned and spent (`block/Credit.kt`, `block/Focus.kt`), and the blocker itself (`block/FocusService.kt`). The anti-tamper side (protection, the watchdog, the delayed loosening, the parent code) was reviewed in v1 and is left alone.

Revised after your notes and both rounds of answers. Sessions go (note 1): you read the job and do it; the app is for knowing what to do, not for doing it. That removes the timed session, its notification, its Start, the box experiment that depended on it, and the main way free time was earned (`Credit.forSession`). BL-08 is what replaces it, settled by Q-28. The loosening answers (Q-18) and the school rule (Q-29) stand.

## Summary

| ID | Improvement | Priority | Effort | Status |
|---|---|---|---|---|
| BL-01 | Free time spendable once today's plan is done | P1 | S | – (Q-18 yes) |
| BL-02 | A weekend "block from" time, like the weekday one | P2 | S | – (Q-18 yes) |
| BL-03 | Short starts and extensions | – | – | Withdrawn (note 1) |
| BL-04 | The reward visible at the point of temptation | P1 | S | Changed |
| BL-05 | Bank free time toward the weekend | – | – | Withdrawn (Q-19) |
| BL-06 | Study use of a blocked app during a session | – | – | Withdrawn (Q-19, note 1) |
| BL-07 | A day with nothing to do | P2 | S | – (Q-18 yes) |
| BL-08 | Without sessions: a tick keeps a short slot; a long one needs a photo to earn | P1 | M | New (note 1); settled (Q-28) |
| BL-09 | The phone at school while a slot is scheduled there | P2 | S | New (note 2); settled (Q-29) |
| BL-10 | Free time between today's slots | P1 | S | New; Q-47 |
| BL-11 | A missed slot, or a moved one, and today's block | P1 | S | New; Q-48 |

---

## BL-01 Free time spendable once today's plan is done

**Now.** Blocking is strict while anything is in today's or tomorrow's bucket (`Plan.pressure` = `dueSoon.isNotEmpty()`, `core/Plan.kt:110–113`; `BlockPolicy.decide`, `block/BlockPolicy.kt:72`). In term time tomorrow's bucket is never empty, so free time earned on a school night can't be spent on it.

**Proposal.** `pressure` = today's slots have pieces still to do. With even spreading (PL-01) today always holds a fair share, so "today done" means a real day's work done, every day; tomorrow's bucket keeps its place in the ordering but stops gating the block. The rule is shown wherever the block is felt (BL-04). Your answer to Q-18: yes.

**Caveats.** With ticks as the measure of done (BL-08), the honesty of a tick is the honesty of the system; the photo rule for long pieces (Q-28) and Felix (LR-03) are the levers rather than policing.

**Effort.** S.

## BL-02 A weekend "block from" time, like the weekday one

**Now.** On weekdays blocked apps are allowed until `weekdayBlockFromMin` (16:45); there's no weekend equivalent, so on a Saturday the block is strict from the end of quiet hours.

**Proposal.** `weekendBlockFromMin` (default 10:00), applied on Saturday and Sunday; part of the 24-hour loosening delay (`SettingsChanges.LOOSER`). Your answer to Q-18: yes.

**Effort.** S.

## BL-03 Withdrawn

Short starts, extensions and done-early were session mechanics. Without sessions the equivalents are the micro-plan (AI-15), which gives the first two minutes, and *Later today* (UI-03), which moves the slot.

## BL-04 The reward visible at the point of temptation

**Now.** The block screen explains why and shows the next piece (`block/BlockedActivity.kt:47`); it doesn't say what finishing earns.

**Proposal.** One computed line in four places: the block screen ("Finish today's 2 slots (50 min) → 25 min free tonight; the statics needs a photo to count"), the Day view's now card, the widget (UI-05), and Felix's day-done message ("Done. 25 minutes are yours."), with an *Open Instagram* action that opens the app last blocked. Computed from `BlockPolicy` and `Credit`, never a fixed string.

**Effort.** S.

## BL-05 Withdrawn

Banking free time: your answer to Q-19, no.

## BL-06 Withdrawn

A per-session allowance for a blocked site: no (Q-19), and there are no sessions.

## BL-07 A day with nothing to do

**Now.** With no pressure and no credit, the verdict is `Block(NoFreeTime)` (`BlockPolicy.kt:74`): a day with an empty plan is blocked all day with no way to earn.

**Proposal.** A daily allowance (`freeAllowanceMin`, 60) that opens the day when the plan has nothing for today and nothing overdue; shown as "Nothing planned today: 60 min free". Part of the loosening delay. Your answer to Q-18: yes.

**Effort.** S.

## BL-08 Without sessions: a tick keeps a short slot; a long one needs a photo to earn

**Now.** Done is: a source says so (Teams turned in, Power Planner complete, an email unstarred after DX-03, an Anki deck's counts at zero), a step ticked (emails and own tasks only, `Focus.tickable`, `block/Focus.kt:554`), or a photo check passed. Free time is earned by a finished session (`Credit.forSession`, a third of its minutes), a source-confirmed completion (`forCompletion`, up to 30 minutes' worth) and photo-checked minutes; a tick earns nothing. Sessions were the one measure of work that was both timed and hard to fake.

**Proposal.** The slot (PL-12) is the unit. Your answer to Q-28 (option 2):

- **Done.** A slot is kept when its piece is ticked (any task, including a source's steps; the source's "done" still ends the task) or its photo check passes. The day is done when its slots are. A tick keeps a slot whatever its length; the plan moves on.
- **Free time.** A kept slot of a piece planned at under 20 minutes earns its minutes at the usual rate (a third). A kept slot of a longer piece earns only when its photo check passes (AI-16, AI-17): a tick alone keeps it done but earns nothing. Source completion and photo credit as now. Credit is capped per day at the day's planned minutes, so ticking can't mint time.
- **The ask.** At the end of a slot over 20 minutes Felix asks for the photo with the Done question (LR-09): "Done? Send me a pic of it and the 15 minutes are yours." A tick without a photo keeps the slot but earns nothing; a photo added from the sheet later the same day earns then (confirmed in Q-45). The sheet shows which slots earned and which were kept without earning.

**Final state.** Short admin and vocab earn on a tick; the statics earns when the booklet's page is photographed; the day's free time opens when today's slots are all kept, earned or not.

**Caveats.** The calibration loses its timed minutes (the multipliers rested on them, `Calibrator.MIN_CLASS_TIMED`); LR-09 replaces them with slot outcomes and "took longer by N". The box experiment (`Calibrator.BOXES`) retires. Stats' Focus tile becomes "Slots kept" with "earned" beside it. The 20-minute line is by the piece's planned minutes, not the time taken.

**Effort.** M.

## BL-09 The phone at school while a slot is scheduled there

**Now.** School hours (a weekday before `weekdayBlockFromMin`) allow blocked apps (`BlockPolicy.isSchoolHours`). PL-15 puts work into study periods, break and lunch.

**Proposal.** Your answer to Q-29 (option 1): the blocker's rules at school are unchanged; the planner may plan into the school windows; a school slot is done with the booklet or the laptop and ticked after, and earns under BL-08 like any other. Nothing to build here beyond keeping `isSchoolHours` as it is while PL-15 lands.

**Effort.** None.

## BL-10 Free time between today's slots

**Now (as proposed).** BL-01 lifts the block when today's slots are all kept. Between slots (the 5-minute break; the gap between a slot kept at 17:50 and the next at 19:00) the phone stays blocked, so free time earned at 17:15 waits until the last slot is kept.

**Proposal.** Two options (Q-47):
1. Spend in the gaps: once the slot before a gap is kept, earned free time is spendable until the next slot's start, when the block returns. The countdown exists (`FocusService.startSpending`); its end becomes the earlier of the credit's end and the next slot's start.
2. As BL-01 alone: nothing until the day's slots are all kept.

**Recommendation.** 1. The reward lands when the work does, which is the point, and the next slot takes the phone back.

**Caveats.** A gap that's really the time you'd have used to start the next piece early is phone time instead; the slot times are the contract, and Felix can say so ("you've 40 minutes; the reply would take 10").

**Effort.** S. **Decision.** Q-47.

## BL-11 A missed slot, or a moved one, and today's block

**Now (as proposed).** PL-13 re-slots a missed piece into today's remaining time, and into tomorrow when today is full; UI-04 and Felix (AI-24) move pieces on request with no limit (Q-17); BL-01 lifts the block when today's slots are kept. Together they leave a gap: let every slot lapse until the day is full, or move them all, and today's list empties and the phone opens.

**Proposal.** Three options (Q-48):
1. A piece missed today and pushed to tomorrow still counts as today's pressure until the hours end: the phone stays blocked for the rest of the evening. A deliberate move (by drag, or by telling Felix) is a plan change you made and lifts it; Felix can push back ("you moved it yesterday too"), but the app applies it.
2. Missed and moved pieces both keep today's pressure until the hours end.
3. Neither: whatever is in today's list is what gates the block.

**Recommendation.** 1. Missing a slot shouldn't open the phone; choosing to move it is your call and your record (the summary shows the moves).

**Effort.** S. **Decision.** Q-48.

---

## What I looked at and left alone

- **The policy's order** (override > quiet and school hours > pressure > credit): right, minus the session clause.
- **The credit rate** (3 minutes of work per minute free) and the 30-minute ceiling on completion credit: fine.
- **The blocker's mechanics** (30-second tick, one block per burst, picture-in-picture handling, address checks): solid.
- **Protection, the watchdog, the delayed loosening, the parent code**: unchanged; the new settings that take the delay are BL-02, BL-07 and BL-08's credit rule.
