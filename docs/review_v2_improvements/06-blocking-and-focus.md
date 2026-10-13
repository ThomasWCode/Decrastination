# 6. Blocking and focus

The incentive system: when blocked apps are blocked (`block/BlockPolicy.kt`), how free time is earned and spent (`block/Credit.kt`, `block/Focus.kt`), and the blocker itself (`block/FocusService.kt`). The anti-tamper side (protection, the watchdog, the delayed loosening, the parent code) was reviewed in v1 and is left alone.

Revised after your notes. Sessions go (note 1): you read the job and do it; the app is for knowing what to do, not for doing it. That removes the timed session, its notification, its Start, the box experiment that depended on it, and the main way free time was earned (`Credit.forSession`). BL-08 is the question that opens: what counts as done, and what earns free time, without sessions. The loosening answers (Q-18) stand.

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
| BL-08 | Without sessions: what is done, and what earns free time | P1 | M | New (note 1) |
| BL-09 | The phone at school while a slot is scheduled there | P2 | S | New (note 2) |

---

## BL-01 Free time spendable once today's plan is done

**Now.** Blocking is strict while anything is in today's or tomorrow's bucket (`Plan.pressure` = `dueSoon.isNotEmpty()`, `core/Plan.kt:110–113`; `BlockPolicy.decide`, `block/BlockPolicy.kt:72`). In term time tomorrow's bucket is never empty, so free time earned on a school night can't be spent on it.

**Proposal.** `pressure` = today's slots have pieces still to do. With even spreading (PL-01) today always holds a fair share, so "today done" means a real day's work done, every day; tomorrow's bucket keeps its place in the ordering but stops gating the block. The rule is shown wherever the block is felt (BL-04). Your answer to Q-18: yes.

**Caveats.** With ticks as the measure of done (BL-08), the honesty of a tick is the honesty of the system; that's your choice to make for yourself, and the nags (LR-03) are the lever rather than policing.

**Effort.** S.

## BL-02 A weekend "block from" time, like the weekday one

**Now.** On weekdays blocked apps are allowed until `weekdayBlockFromMin` (16:45); there's no weekend equivalent, so on a Saturday the block is strict from the end of quiet hours.

**Proposal.** `weekendBlockFromMin` (default 10:00), applied on Saturday and Sunday; part of the 24-hour loosening delay (`SettingsChanges.LOOSER`). Your answer to Q-18: yes.

**Effort.** S.

## BL-03 Withdrawn

Short starts, extensions and done-early were session mechanics. Without sessions the equivalents are the micro-plan (AI-15), which gives the first two minutes, and *Later today* (UI-03), which moves the slot.

## BL-04 The reward visible at the point of temptation

**Now.** The block screen explains why and shows the next piece (`block/BlockedActivity.kt:47`); it doesn't say what finishing earns.

**Proposal.** One computed line in four places: the block screen ("Finish today's 2 slots (50 min) → 25 min free tonight"), the Day view's now card, the widget (UI-05), and the end-of-day message (LR-03's voice: "Done. 25 minutes are yours."), with an *Open Instagram* action that opens the app last blocked. Computed from `BlockPolicy` and `Credit`, never a fixed string.

**Effort.** S.

## BL-05 Withdrawn

Banking free time: your answer to Q-19, no.

## BL-06 Withdrawn

A per-session allowance for a blocked site: no (Q-19), and there are no sessions.

## BL-07 A day with nothing to do

**Now.** With no pressure and no credit, the verdict is `Block(NoFreeTime)` (`BlockPolicy.kt:74`): a day with an empty plan is blocked all day with no way to earn.

**Proposal.** A daily allowance (`freeAllowanceMin`, 60) that opens the day when the plan has nothing for today and nothing overdue; shown as "Nothing planned today: 60 min free". Part of the loosening delay. Your answer to Q-18: yes.

**Effort.** S.

## BL-08 Without sessions: what is done, and what earns free time

**Now.** Done is: a source says so (Teams turned in, Power Planner complete, an email archived or, after DX-03, unstarred, an Anki deck's counts at zero), a step ticked (emails and own tasks only, `Focus.tickable`, `block/Focus.kt:554`), or a photo check passed. Free time is earned by a finished session (`Credit.forSession`, a third of its minutes), a source-confirmed completion (`forCompletion`, up to 30 minutes' worth) and photo-checked minutes; a tick earns nothing. Sessions were the one measure of work that was both timed and hard to fake.

**Proposal.** With sessions gone, the slot (PL-12) is the unit: a slot is kept when its piece is ticked (any task, including a source's steps; the source's "done" still ends the task) or its photo check passes; the day is done when its slots are. Credit: a kept slot earns its minutes at the usual rate (a third), capped per day at the day's planned minutes so ticking can't mint time; source completion and photo credit as now. The slot's end asks "Done? Not yet / Took longer" (LR-09), which is both the tick and the calibration's signal.

**Options for you (Q-28).**
1. Ticks earn free time (above). Simplest; trusts you; the nags and your dad's summary are the audit.
2. Ticks earn for pieces under 20 minutes; longer pieces earn only with a photo. Keeps the photo check in the loop where it matters.
3. Ticks earn nothing; only source completion and photos do (today's rule). Honest but harsh: most evenings would earn little.

**Recommendation.** 1. The app is your own tool now, not a cage; a reward you can grant yourself with a lie is a reward you'll grant yourself honestly most of the time, and the summary shows the pattern if not.

**Caveats.** The calibration loses its timed minutes (the multipliers rested on them, `Calibrator.MIN_CLASS_TIMED`); LR-09 replaces them with slot outcomes and "took longer by N". The box experiment (`Calibrator.BOXES`) retires. Stats' Focus tile becomes "Slots kept".

**Effort.** M.

## BL-09 The phone at school while a slot is scheduled there

**Now.** School hours (a weekday before `weekdayBlockFromMin`) allow blocked apps (`BlockPolicy.isSchoolHours`). PL-15 puts work into study periods; your note says relaxing "previous school-time restrictions".

**Proposal.** I've read the relaxation as the planner's (it may now plan into school free periods), not the blocker's: the phone's rules at school stay as they are, and a study-period slot is done with the booklet or the laptop, ticked afterwards. If you meant the blocker too, the alternative is: during a school-window slot, pressure applies as in the evening (blocked apps blocked until the slot's piece is ticked), which is one condition in `decide`. Q-29.

**Effort.** S either way.

---

## What I looked at and left alone

- **The policy's order** (override > quiet and school hours > pressure > credit): right, minus the session clause.
- **The credit rate** (3 minutes of work per minute free) and the 30-minute ceiling on completion credit: fine.
- **The blocker's mechanics** (30-second tick, one block per burst, picture-in-picture handling, address checks): solid.
- **Protection, the watchdog, the delayed loosening, the parent code**: unchanged; the new settings that take the delay are BL-02, BL-07 and, if you choose it, BL-08's credit rule.
