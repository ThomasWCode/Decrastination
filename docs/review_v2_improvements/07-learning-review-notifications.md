# 7. Learning, the weekly review, nags and notifications

What the app learns (`learn/Calibrator.kt`, `learn/Days.kt`), the weekly review (`learn/Review.kt`, `learn/ReviewInput.kt`), the self-assessment and check-in (`learn/Assessment.kt`, `learn/CheckIns.kt`), the briefing (`learn/Briefing.kt`) and the notification channels (`notify/Channels.kt`).

Revised after your notes. Note 6 is the centre of this document now: the app should feel like a person nagging you, and you should be able to answer. LR-03 is that mechanism (the prompt is AI-22, the reply path AI-24). Note 1 (no sessions) changes what the calibration learns from (LR-09). Note 7 adds the reset (LR-08).

## Summary

| ID | Improvement | Priority | Effort | Status |
|---|---|---|---|---|
| LR-01 | A page for the reviews: what they said, what they changed, undo | P1 | M | – |
| LR-02 | The check-in from its notification, prefilled from the week | P2 | S | – |
| LR-03 | Nags from a person: Claude-written, timed to your slots, answerable | P1 | L | Changed (note 6, Q-20) |
| LR-04 | The morning message in the same voice | P2 | S | Changed (Q-21) |
| LR-05 | Fewer "how did it go?" questions: the slot's end asks instead | P2 | S | Changed (note 1) |
| LR-06 | Say what the calibration learned and why | P2 | S | Changed |
| LR-07 | Actions on notifications, quiet hours, and a ceiling for everything but the voice | P2 | S | Changed |
| LR-08 | Reset: stats, calibration, memories, chat, in one place | P1 | S | New (note 7) |
| LR-09 | Slot outcomes as the learning signal | P1 | M | New (note 1) |

---

## LR-01 A page for the reviews: what they said, what they changed, undo

**Now.** The weekly review runs as a job (`learn/Review.kt:28`), posts its note as a notification (`Channels.WEEKLY`) and applies its bounded changes (`AppGraph.applyReviewChanges`, `AppGraph.kt:262`). No screen shows past reviews (`ui/` has none).

**Proposal.** A *Reviews* page in the menu (UI-18): each week's note, its changes ("box 45 → 30", "Physics margin 1 → 2 days", "yield 0.8 → 0.7"), *Undo* per change, a line under the next review on whether the change helped (AI-18), and the week's check-in beside it.

**Effort.** M.

## LR-02 The check-in from its notification, prefilled from the week

**Now.** The weekly check-in is a form (`ui/CheckIns.kt:105`): a 1–5 and four free-text questions.

**Proposal.** The 1–5 as a reply to the notification (the voice asks, "How was the week, 1 to 5?", AI-24), the text questions as a conversation in the same thread if you answer; "What did you avoid?" prefilled with the week's evidence (slots missed, pieces moved, days not done) as chips. The form stays for those who'd rather type it all.

**Effort.** S.

## LR-03 Nags from a person: Claude-written, timed to your slots, answerable

**Now.** The briefing says what today holds (`Briefing.summary`, `learn/Briefing.kt:27`); nothing is said again until the block screen is met. Your note 6: notifications should include Claude-written nags, you reply, it becomes a conversation; and your answer to Q-20: yes.

**Proposal.** A `Nagger` beside the planner, fed by the slots (PL-12):

- **Triggers.** A slot's start plus ten minutes with no activity on its piece (no tick, no Anki counts moving, the app not opened to it); a slot's end with no tick (the same moment PL-13 re-slots); the evening, ninety minutes before the hours end, with pieces left; a kept slot (a short "good, next is …", not every time); the day done (BL-04's line). Each trigger is a reason the prompt sees (AI-22).
- **Cadence.** At most one message per slot event, none twice within twenty minutes, none in quiet hours, none during a calendar event marked busy, at school only for school-window slots (Q-31). A ceiling of eight a day for the voice (Q-31), the rest held.
- **Text.** Written by Claude ahead of time (AC-10) from the plan, the piece's micro-plan, the profile and memories, and the last few nags and your replies; checked against the state at the moment of sending (if you ticked the piece at 17:11, the 17:12 nag isn't sent).
- **Replies.** Every nag has an inline reply and the quick replies Claude proposed ("Starting now", "Give me 15", "Not tonight, because…"). A reply goes through the chat (AI-24): "Give me 15" moves the slot and says so; "Not tonight, because football" proposes the move and remembers football (AI-21) if it's news; a reply with no action gets a sentence back. The thread is in the chat screen.
- **Tone.** A setting with three voices and a name field (Q-31); the system prompt forbids guilt, sarcasm unless you chose "blunt", and more than two sentences.
- **Record.** Nags sent, replies given and slots then kept or missed are in the log, so the weekly review (AI-18) and the summary (NF-02) can say what worked.

**Final state.** 17:12: "You put the statics at five. Twelve past; p.7, Q1, two minutes. Going?" You: "give me 15". 17:13: "Fine: 17:27, and I've moved the reply to Mr Peters to 18:15." 17:27: nothing, because you ticked it at 17:40 and the 17:37 check saw you'd opened the task. 22:00: "Both done. 25 minutes are yours; night."

**Caveats.** This is the biggest change in feel in the whole review and the easiest to get wrong: a voice that's wrong about what you're doing is worse than a list. The state check before sending, the cadence limits and the tone setting are the controls; AC-07's log shows every nag and AC-04 makes sure a bad minute doesn't silence it for an hour. It needs PL-12 (slots) to have anything to nag about; before that, the evening trigger alone is a worthwhile first step.

**Effort.** L.

## LR-04 The morning message in the same voice

**Now.** The briefing is a computed list.

**Proposal.** Your answer to Q-21: yes. The briefing becomes the day's first message from the voice (AI-22's job with the "morning" reason): one or two sentences on the day's shape ("Two study periods and a clear evening: German vocab at 10:50, the statics at five, nothing after seven.") followed by the computed list as now, so the facts are still there if the sentence is off. Written at the briefing time with the plan as it stands; skipped silently when the model is off or resting.

**Effort.** S.

## LR-05 Fewer "how did it go?" questions: the slot's end asks instead

**Now.** After each verified completion of homework or revision, a notification asks Harder / As expected / Easier (`Assessment.ask`, `learn/Assessment.kt:69`), one per completion.

**Proposal.** The slot-end check (LR-09: Done / Not yet / Took longer) carries the same information piece by piece, so the per-completion question is only needed for work done outside the app's knowledge (a source-confirmed completion with no kept slot). Ask it then, grouped into one evening notification with the day's completions, not one per task.

**Effort.** S.

## LR-06 Say what the calibration learned and why

**Now.** Multipliers (0.5–3.0), margins (1–3 days, moved after 2 late or 5 early in a row) and the box experiment are computed from the log and described in words on Setup.

**Proposal.** A *Calibration* section on Stats: per kind and class, the multiplier, the number of slot outcomes behind it, the margin and the run that set it, the yield (PL-06) and the days behind it. The box experiment retires with sessions (BL-08); `boxMin` stays as the piece length and the review may still change it. A *Forget* per class, and the full reset in LR-08.

**Effort.** S.

## LR-07 Actions on notifications, quiet hours, and a ceiling for everything but the voice

**Now.** Ten channels (`notify/Channels.kt:9–22`); the briefing, review and calendar questions open the app or carry answers.

**Proposal.** The nags and the morning message are one channel (`VOICE`), with inline reply, and their own cadence (LR-03). Everything else: nothing in quiet hours except protection and key problems; a ceiling of four a day with the rest held for morning; *Open* on the dropped-plan alert; calendar questions answered inline. A "Notifications today" line in Settings → Notifications and nags.

**Effort.** S.

## LR-08 Reset: stats, calibration, memories, chat, in one place

**Now.** Nothing resets short of clearing the app's data, which also clears the key, the sources and the protection state.

**Proposal.** Your note 7. Settings → Reset, one row each, separately: the activity log (stats, day records, slot outcomes: the calibration follows, since it's replayed from the log); the calibration only (a marker in the log that says "learn from here on", keeping the history); memories (AI-21) and your profile lines; the chat and nag history; instructions (with a list of what would go, since some are busy times you still want). Each with a confirmation naming what's lost, and a backup written first (`data/Backup`, to the nightly folder if AR-07 exists). None is a loosening, so none waits the delay; none touches the key, the sources or protection.

**Effort.** S.

## LR-09 Slot outcomes as the learning signal

**Now.** The calibration's multipliers learn from timed sessions (minutes worked against the estimate, `Calibrator.WEIGHT`), nudged by the self-assessment; the day records' done minutes come from sessions; the yield (PL-06) would need done minutes. Sessions go.

**Proposal.** Each slot's end asks one thing, in the voice or as a plain notification: *Done* / *Not yet* / *Took longer*; *Took longer* opens a chip row (+10, +20, +30, +60) or accepts a later tick (the slot is extended to the tick). Outcomes: *Done* at the slot's end records the piece's minutes as worked (done in the time planned); *Done* with "+N" records minutes + N; *Not yet* re-slots (PL-13) and records nothing yet; a tick well before the slot's end records the minutes to the tick (done early). The multipliers learn from worked ÷ estimated per kind and class as they did from sessions; the day record's done minutes are the kept slots' minutes; the yield is done ÷ planned over the day.

**Caveats.** Self-reported, where sessions were measured; "Took longer" is the honest part of the design and the part that needs the question asked at the right moment, which is why it rides on the slot's end. Photo checks remain as the verified path for anything you want verified.

**Effort.** M.

---

## What I looked at and left alone

- **The calibration's maths** (bounded, replayed from the log): good; the signal changes (LR-09), the maths doesn't.
- **The review's bounds**: keep; PL-06 adds the hours under the same bounds.
- **The check-in's questions**: fine; LR-02 makes them easier to answer.
