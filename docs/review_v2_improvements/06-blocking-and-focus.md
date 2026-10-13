# 6. Blocking and focus

The incentive system: when blocked apps are blocked (`block/BlockPolicy.kt`), how free time is earned and spent (`block/Credit.kt`, `block/Focus.kt`), sessions (`block/Sessions.kt`) and the blocker itself (`block/FocusService.kt`). The anti-tamper side (protection, the watchdog, the delayed loosening) was reviewed in v1 and is left alone here.

## Summary

| ID | Improvement | Priority | Effort | Decision |
|---|---|---|---|---|
| BL-01 | Free time spendable once today's plan is done | P1 | S | Q-18 |
| BL-02 | A weekend "block from" time, like the weekday one | P2 | S | Q-18 |
| BL-03 | Short starts and extensions | P1 | M | – |
| BL-04 | The reward visible at the point of temptation | P1 | S | – |
| BL-05 | Bank free time toward the weekend | P3 | S | Q-19 |
| BL-06 | Study use of a blocked app during a session | P3 | M | Q-19 |
| BL-07 | A day with nothing to do | P2 | S | Q-18 |

---

## BL-01 Free time spendable once today's plan is done

**Now.** Blocking is strict while anything is in today's or tomorrow's bucket (`Plan.pressure` = `dueSoon.isNotEmpty()`, `core/Plan.kt:110–113`; `BlockPolicy.decide`, `block/BlockPolicy.kt:72`: pressure → Block before credit is considered). Earned free time (a third of each finished session, `Credit.forSession`, `block/Credit.kt:34`) can only be spent when both buckets are empty.

**Why.** In term time tomorrow's bucket is almost never empty: backwards placement puts tomorrow's homework there as a matter of course. So on a school night the student can finish every piece planned for today and still be blocked, and the free time earned sits unspendable until a day with nothing due tomorrow. An incentive that can't be collected on the evening it was earned isn't one. The planner's own rule guarantees the relaxation is safe: a chunk is in tomorrow's bucket because tomorrow's evening can hold it (a morning deadline's day is never usable, so work due tomorrow morning is already in today's bucket, `Planner.lastUsableDay`, `core/Planner.kt:483`).

**Proposal.** `pressure` = today's bucket has pieces still to do. Tomorrow's bucket keeps its place in the ordering (`dueSoon` stays for the planner's "soon" sort) but stops gating the block. Show the rule on the Today screen and the block screen (BL-04): "Finish today's 2 pieces (50 min) → 25 min of free time tonight."

**Final state.** A finished evening earns a usable reward that evening. The strict block still holds while anything planned for today is undone, overdue work included.

**Caveats.** This is a loosening and it's your call. The honest risk: the student learns to game today's bucket by moving work (UI-04's limits matter) or by telling Claude "do it tomorrow" (instructions are logged; NF-02 shows them). The planner's "won't fit" state (PL-02) can leave today's bucket undone through no fault of the student; then there's no reward, as now.

**Effort.** S. **Decision.** Q-18.

## BL-02 A weekend "block from" time, like the weekday one

**Now.** On weekdays, blocked apps are allowed until `weekdayBlockFromMin` (16:45) ("school hours", `BlockPolicy.isSchoolHours`); there's no weekend equivalent, so on a Saturday the block is strict from the end of quiet hours (07:00) whenever tomorrow's bucket has anything in it, which under the current rule is every Saturday with Sunday work.

**Proposal.** `weekendBlockFromMin` (suggested default 10:00), applied like the weekday one on Saturday and Sunday; also a setting that takes part in the 24-hour loosening delay (`SettingsChanges.LOOSER`).

**Caveats.** With BL-01 the weekend problem mostly goes away (Saturday's bucket is often empty); this is still right on its own because a weekend morning before work isn't school hours, and it's one field.

**Effort.** S. **Decision.** Q-18.

## BL-03 Short starts and extensions

**Now.** A session runs the piece's minutes (the box, 45 by default, or the step's), up to 180 (`Focus.MAX_SESSION_MIN`). Start on a 45-minute piece commits to 45; a session stopped early credits the minutes worked and earns no free time (`Focus.ending`, `block/Focus.kt:195`).

**Why.** The barrier is the first minute. A "just 15 minutes" start is the standard advice for procrastination because it works: once started, people tend to continue. A session that can be extended keeps that momentum without a second Start.

**Proposal.**

- On the DO NOW card: *Start 45* (the piece), and a smaller *Just 15*. A 15-minute session on a 45-minute piece is `whole = false` (adds its minutes, ticks nothing), earning free time at the same rate.
- On the session notification and the session view (UI-14): *+10 min*, extending the current session in place (the alarm and uptime end move; the record keeps the longer length). An extended session that reaches the piece's minutes ticks the step.
- *Done early*: the step done before the time is up; today the only way is Stop, which records a stop. Marking the step done early should ask for a photo (the check already exists) or count as a stop without the tick.

**Final state.** "Just 15" gets a session going; "+10" keeps it going; the plan credits what was done.

**Caveats.** "Just 15" sessions chained with breaks are slower than one 45; that's still better than nothing started. Done-early without a photo is the gameable path; keep the tick behind the photo or the full time.

**Effort.** M.

## BL-04 The reward visible at the point of temptation

**Now.** The block screen explains why (reason), shows the next piece and offers Start (`block/BlockedActivity.kt:47`). It doesn't say what finishing earns, and the session's end notification says how long it ran, not what it unlocked.

**Proposal.** One computed line in three places: the block screen ("Finish Statics Prep (45 min) → 15 min of free time; finish today's 2 pieces → 25 min"), the DO NOW card, and the session's end notification ("Done: 15 min of free time earned; today's plan is done, so it's yours now" with an *Open Instagram* action that opens the app that was last blocked). Under the current rule the line must say "once today and tomorrow are clear", which is why BL-01 comes first.

**Caveats.** Truthfulness: the line must be computed from `BlockPolicy` and `Credit`, never a fixed string, or it will lie when a day limit or the calendar changes the plan.

**Effort.** S.

## BL-05 Bank free time toward the weekend

**Now.** Credit is a day's ledger (`Credit.day`; `earn` and `spend` are per day), so free time earned on a Tuesday evening that couldn't be spent (BL-01) is gone at midnight.

**Proposal.** A `bank` that carries unspent minutes forward up to a cap (60 min), spendable on any day under the normal rules; shown on Stats and the widget. The cap keeps a week of unspent evenings from becoming a Saturday of six hours.

**Caveats.** Delayed rewards motivate less than immediate ones, which is well established in the procrastination literature (Steel, 2007, *Psychological Bulletin* 133(1), 65–94, on temporal motivation theory), so this is a weak lever; BL-01 is the strong one. With BL-01 in place the bank matters only on evenings the student finishes but doesn't want the phone.

**Effort.** S. **Decision.** Q-19.

## BL-06 Study use of a blocked app during a session

**Now.** The blocklist is apps and sites (`Blocklist.APPS`, `SITES`); YouTube is blocked whatever it's for, and a step that says "watch the Seneca video on momentum" can't be done on the phone.

**Proposal.** A per-session allowance: a task's step may name a site ("needs: youtube.com/watch?v=…" from the instructions, or the student's own step), and during a session on that step the blocker lets that one address through in a checked browser, re-blocking the moment the address changes. Never for apps (the YouTube app can't be limited to one video), only for the browser's address, and shown on the session view.

**Caveats.** Gameable in the obvious way (name a video, watch something else in a second tab); the address check runs on every window change (`FocusService.checkAddress`) so a second tab is caught, but a related-videos click is one tap away. Honest verdict: probably not worth it on a phone; the student has a laptop for videos. Listed because the question will come up.

**Effort.** M. **Decision.** Q-19.

## BL-07 A day with nothing to do

**Now.** With no pressure and no credit, the verdict is `Block(NoFreeTime)` (`BlockPolicy.kt:74`). On a day with an empty plan (half term, a Sunday after a clear week) and no soft work left, the phone is blocked all day with no way to earn.

**Proposal.** A daily allowance on such days: when the plan has nothing for today and nothing overdue, credit opens the day with `freeAllowanceMin` (suggested 60) at the start of the hours window. Shown as "Nothing planned today: 60 min free, then earn more with undated work". Part of the loosening delay.

**Caveats.** Rare in term time; without it the system is harsher than you probably intend on the days it has nothing to ask. If you'd rather such days stay blocked (the phone is for earned time only), say so and this is dropped.

**Effort.** S. **Decision.** Q-18.

---

## What I looked at and left alone

- **The policy's order** (session > override > quiet and school hours > pressure > credit): right.
- **The credit rate** (3 minutes of work per minute free) and the 30-minute ceiling on completion credit: fine, and the review may change the ratio.
- **The blocker's mechanics** (30-second tick, one block per burst, picture-in-picture handling, address checks): solid; the v1 bugs here were fixed.
- **Protection, the watchdog, the delayed loosening, the parent code**: reviewed in v1 and working; nothing to add beyond listing the new settings that must take the delay (BL-02, BL-07).
