# 7. Learning, the weekly review and notifications

What the app learns (`learn/Calibrator.kt`, `learn/Days.kt`), the weekly review (`learn/Review.kt`, `learn/ReviewInput.kt`), the self-assessment and check-in (`learn/Assessment.kt`, `learn/CheckIns.kt`), the briefing (`learn/Briefing.kt`) and the notification channels (`notify/Channels.kt`).

## Summary

| ID | Improvement | Priority | Effort | Decision |
|---|---|---|---|---|
| LR-01 | A page for the reviews: what they said, what they changed, undo | P1 | M | – |
| LR-02 | The check-in from its notification, prefilled from the week | P2 | S | – |
| LR-03 | An evening nudge while today's plan is undone | P2 | S | Q-20 |
| LR-04 | One line from Claude in the morning briefing | P3 | S | Q-21 |
| LR-05 | Fewer "how did it go?" questions, grouped | P2 | S | – |
| LR-06 | Say what the calibration learned and why | P2 | S | – |
| LR-07 | Actions on notifications, and a daily ceiling | P2 | S | – |

---

## LR-01 A page for the reviews: what they said, what they changed, undo

**Now.** The weekly review runs as a job (`learn/Review.kt:28`), posts its note as a notification (`Channels.WEEKLY`) and applies its bounded changes (`AppGraph.applyReviewChanges`, `AppGraph.kt:262`). There's no screen that shows past reviews (`ui/` has none), so a note dismissed is gone, and a change the student disagrees with can only be undone by finding the setting.

**Proposal.** A *Reviews* page in the menu: each week's note, its changes as "box 45 → 60", "Physics margin 1 → 2 days", with *Undo* per change (restoring the old value through the normal settings path, which for a loosening takes the delay) and a line under the next week's review saying whether the change helped (AI-18 gives the model the data). The check-in's answers for the week shown beside.

**Final state.** The review becomes a conversation you can read back, not a notification that passes.

**Caveats.** Undoing a review change is a settings change, so the loosening delay applies where it should (a longer box isn't a loosening; a shorter margin might be, as it's planning rather than blocking: `SettingsChanges.LOOSER` says only blocker settings wait since 2.0.0, so none of these do).

**Effort.** M.

## LR-02 The check-in from its notification, prefilled from the week

**Now.** The weekly check-in is a form (`ui/CheckIns.kt:105`): a 1–5 "how did the week feel", and four free-text questions (what you avoided, what got in the way, what you'd change, when you had most energy). It's opened from a notification at `checkInMin` on the check-in day.

**Proposal.**

- The 1–5 as five action buttons on the notification itself, so the week is at least rated when the form is never opened; the form then opens for the text.
- Prefill "What did you avoid?" with the week's evidence as chips to tap: tasks moved by instruction, pieces that went behind, days whose plan wasn't done. The student confirms or adds rather than recalls.
- Drop or merge a question if the answers stay empty for a month (the data will show).

**Caveats.** Notification actions on Samsung's One UI show up to three; the five chips may need "Rate" opening a small dialog instead. Prefilled chips bias the answer toward what the app saw; the free-text field stays.

**Effort.** S.

## LR-03 An evening nudge while today's plan is undone

**Now.** The morning briefing says what today holds (`Briefing.summary`, `learn/Briefing.kt:27`); nothing is said again until the block screen is met. A student who leaves the phone face down and the laptop open avoids both.

**Proposal.** One notification a day, at `hoursEnd − 90 min` (20:30 on a weekday), only when today's bucket has pieces left: "1 h 10 left today: Statics Prep (45), Reply to Mr Peters (10)" with *Start* on the first. None on a day already done; none during quiet hours; none if a session is running.

**Caveats.** Every extra notification trains the student to ignore notifications. One a day, conditional, with an action, is the most I'd add. Your call (Q-20) because you know whether nagging works on him.

**Effort.** S. **Decision.** Q-20.

## LR-04 One line from Claude in the morning briefing

**Now.** The briefing is a computed list. It's accurate and dry.

**Proposal.** One model call a morning (MEDIUM effort, a few cents): the plan's today and tomorrow, the calibration, and yesterday's record in; one sentence out, under 140 characters, concrete: "Physics first: it's due 08:30 tomorrow and usually takes you longer than the estimate." Shown as the briefing's first line; the list follows. Cached for the day; skipped when the model is off, resting or capped, without a word.

**Caveats.** It's a daily cost for a sentence that may be ignored, and another prompt to keep honest (the sentence must only say what the plan says; the prompt forbids advice that contradicts the plan). I'd try it for a month and look at whether sessions start earlier on days it was read; that's measurable from the log.

**Effort.** S. **Decision.** Q-21.

## LR-05 Fewer "how did it go?" questions, grouped

**Now.** After each verified completion of homework or revision, a notification asks Harder / As expected / Easier (`Assessment.ask`, `learn/Assessment.kt:69`), one per completion. A sync that finds four things done posts four.

**Proposal.** One notification per evening listing the day's completions, each with its three answers inside the expanded view (or opening a small sheet); and skip the question for a task done entirely in timed sessions whose minutes are already known (the multiplier learns from the minutes; the nudge adds little: `NUDGE_WITH_TIME` is 0.05 against 0.10 without).

**Caveats.** A delayed question is answered less accurately; by the evening the student still remembers. Fewer questions, better answered.

**Effort.** S.

## LR-06 Say what the calibration learned and why

**Now.** The multipliers (bounded 0.5–3.0, `Calibrator.MIN_MULTIPLIER`/`MAX_MULTIPLIER`), the margins (1–3 days, moved after 2 late or 5 early in a row), and the box experiment are computed from the log and described in words on Setup. Why a margin moved, or on how many pieces a multiplier rests, isn't shown.

**Proposal.** A *Calibration* section on Stats: per kind and per class, the multiplier, the number of timed pieces behind it (and "using the kind's until 3"), the margin and the run that set it ("2 late in a row: German margin 1 → 2 days"), and the box experiment's state ("25 vs 45 vs 60: 45 finishes most often, 11 sessions"). A *Forget* per class that drops its records from the calibration (not the log).

**Caveats.** Replaying the log is how the calibration works, so "forget" is a filter, not a deletion. Pure display otherwise.

**Effort.** S.

## LR-07 Actions on notifications, and a daily ceiling

**Now.** Ten channels (`notify/Channels.kt:9–22`). The briefing, the review and the calendar questions open the app or carry answers; the session's notification has Stop; the free-time ones count down.

**Proposal.** *Start* on the briefing (the first piece) and on the evening nudge; *Open* on a model notification (the dropped-plan alert opens the task); and a rule in `Notify.post` that nothing but the session and free-time channels posts during quiet hours or more than N a day (suggested 6), the rest held for the next morning. A "Notifications today: 4" line in Setup for your own check.

**Caveats.** Holding a calendar question until morning is fine; holding a key-problem alert until morning is not, so the protection and model channels are exempt from the ceiling but not from quiet hours.

**Effort.** S.

---

## What I looked at and left alone

- **The calibration's maths** (bounded, replayed from the log, no hidden state): good, and the reason the review's changes are safe to let a model make.
- **The review's bounds** (box, margin, undated minutes, ratio): keep; PL-06 proposes one more, the hours, under the same bounds.
- **The box experiment**: let it run; LR-06 shows its state.
- **The check-in's questions**: fine; LR-02 makes them easier to answer.
