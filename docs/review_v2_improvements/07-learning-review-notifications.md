# 7. Learning, the weekly review, Felix and notifications

What the app learns (`learn/Calibrator.kt`, `learn/Days.kt`), the weekly review (`learn/Review.kt`, `learn/ReviewInput.kt`), the self-assessment and check-in (`learn/Assessment.kt`, `learn/CheckIns.kt`), the briefing (`learn/Briefing.kt`) and the notification channels (`notify/Channels.kt`).

Revised after your notes and both rounds of answers. Note 6 is the centre of this document: the app should feel like a person nagging you, and you should be able to answer. Your answer to Q-31 gives that person a name and a character: Felix, a strict friend, who decides when he'll check again. LR-03 is the mechanism (the prompt is AI-22, the reply path AI-24). Note 1 (no sessions) changes what the calibration learns from (LR-09). Note 7 adds the reset (LR-08).

## Summary

| ID | Improvement | Priority | Effort | Status |
|---|---|---|---|---|
| LR-01 | A page for the reviews: what they said, what they changed, undo | P1 | M | – |
| LR-02 | The check-in from its notification, prefilled from the week | P2 | S | – |
| LR-03 | Felix: Claude-written, timed to your slots, answerable, paced by himself | P1 | L | Changed (note 6, Q-20, Q-31) |
| LR-04 | The morning message from Felix | P2 | S | Changed (Q-21) |
| LR-05 | "How did it go?" becomes Felix's question at the slot's end | P2 | S | Changed (note 1, Q-31) |
| LR-06 | Say what the calibration learned and why | P2 | S | Changed |
| LR-07 | Quiet hours, and a ceiling for everything but Felix | P2 | S | Changed (Q-31) |
| LR-08 | Reset: stats, calibration, memories, chat, in one place | P1 | S | New (note 7); settled (Q-34) |
| LR-09 | Slot outcomes as the learning signal | P1 | M | New (note 1) |

---

## LR-01 A page for the reviews: what they said, what they changed, undo

**Now.** The weekly review runs as a job (`learn/Review.kt:28`), posts its note as a notification (`Channels.WEEKLY`) and applies its bounded changes (`AppGraph.applyReviewChanges`, `AppGraph.kt:262`). No screen shows past reviews (`ui/` has none).

**Proposal.** A *Reviews* page in the menu (UI-18): each week's note, its changes ("box 45 → 30", "Physics margin 1 → 2 days", "yield 0.8 → 0.7"), *Undo* per change, a line under the next review on whether the change helped (AI-18), the week's check-in beside it, the week's summary text (NF-02), and the memory tidy's before-and-after with undo (AI-21, Q-51).

**Effort.** M.

## LR-02 The check-in from its notification, prefilled from the week

**Now.** The weekly check-in is a form (`ui/CheckIns.kt:105`): a 1–5 and four free-text questions.

**Proposal.** Felix asks the 1–5 as a message with a reply (AI-24); the text questions follow as a conversation in the same thread if you answer; "What did you avoid?" prefilled with the week's evidence (slots missed, pieces moved, days not done) as chips. The form stays for typing it all at once.

**Effort.** S.

## LR-03 Felix: Claude-written, timed to your slots, answerable, paced by himself

**Now.** The briefing says what today holds (`Briefing.summary`, `learn/Briefing.kt:27`); nothing is said again until the block screen is met. Your note 6: notifications should be Claude-written nags you can reply to; your answers to Q-20 and Q-31: yes; a strict friend called Felix; quiet when nothing is scheduled; no daily ceiling for now; he sets the gap to his next check himself.

**Proposal.** A `Felix` component beside the planner, fed by the slots (PL-12):

- **When he speaks.** Only around scheduled work (Q-31): a slot's start plus ten minutes with no activity on its piece (no tick, no Anki counts moving, the task not opened); a slot's end with no tick (the same moment PL-13 re-slots); the end of a slot of 20 minutes or more, to ask for the photo (BL-08); a kept slot, sometimes ("good; next is the reply at 18:15", not every time); the day's last slot kept (BL-04's line); the morning (LR-04); a question the model has (AI-10); Sunday evening for the summary (NF-02). When nothing is scheduled, nothing is said: no evening nag on a day with no slots left, no weekend morning message before the first slot.
- **How often.** Each message names when he'll check again (`nextCheckMin`, AI-22), and that's when the next check happens; 20 minutes when he names none. No daily ceiling for now (Q-31); quiet hours and busy calendar events still silence him (LR-07). AC-07's log counts his messages so you can set a ceiling later if you want one.
- **Text.** Written by Claude ahead of time (AC-10) from the plan, the piece's micro-plan, the profile and memories, and the last few messages and your replies; checked against the state at the moment of sending (if you ticked the piece at 17:11, the 17:12 message isn't sent).
- **Replies.** Every message has an inline reply and the quick replies Felix offered. A reply goes through the chat (AI-24): "Give me 15" moves the slot at once and he says so; "Not tonight, because football" moves it, remembers football (AI-21) if it's news, and says so; a reply with no action gets a sentence back. Big changes (a new event, a deadline) come back as a button. The thread is in the chat screen.
- **Character.** Felix, a strict friend; his name, the one line that describes him and your standing instructions (AI-21: "not before 8", "be blunter") in Settings → Felix (AI-22). The chat is Felix too (confirmed in Q-44).
- **At school.** A school slot is scheduled work, so he messages around it as around any other (your Q-31 rule: quiet only where nothing is scheduled); a standing instruction ("not at school") changes that. Q-54 confirms.
- **Record.** Messages, replies and the slots then kept or missed are in the log, so the review (AI-18) and the summary (NF-02) can say what worked.

**Final state.** 17:12: "You put the statics at five. Twelve past; p.7, Q1, two minutes. Going?" You: "give me 15". 17:13: "Fine: 17:27, and the reply to Mr Peters is now 18:15. I'll look in at half past." 17:30: nothing, because you ticked it at 17:29. 17:30 for the statics: "Done? Pic of the page and the 15 minutes are yours." 22:00: "Both done. 25 minutes are yours; night."

**Caveats.** The biggest change in feel in the whole review and the easiest to get wrong: a voice that's wrong about what you're doing is worse than a list. The state check before sending, the quiet rules and the self-set gap are the controls; AC-04 makes sure a bad minute doesn't silence him for an hour. With no ceiling, a bad evening could be a dozen messages; the log will show whether that's too many. It needs PL-12 (slots) to have anything to speak about.

**Effort.** L.

## LR-04 The morning message from Felix

**Now.** The briefing is a computed list.

**Proposal.** Your answer to Q-21: yes. The briefing becomes Felix's first message of the day (AI-22 with the "morning" reason): one or two sentences on the day's shape ("Study period at eleven and a clear evening: vocab at break, the statics at five, nothing after seven.") followed by the computed list as now, so the facts are there if the sentence is off. Written at the briefing time with the plan as it stands; skipped silently when the model is off or resting. On a day with no slots, the list alone.

**Effort.** S.

## LR-05 "How did it go?" becomes Felix's question at the slot's end

**Now.** After each verified completion of homework or revision, a notification asks Harder / As expected / Easier (`Assessment.ask`, `learn/Assessment.kt:69`), one per completion, in the app's words.

**Proposal.** Your answer to Q-31: that notification becomes Felix's. The slot-end question (LR-09: Done / Not yet / Took longer, and the photo ask for long pieces) carries the same information piece by piece and is asked in his voice. For work done outside the app's knowledge (a source-confirmed completion with no kept slot), he asks once in the evening for the day's completions together ("Teams says the German's in. Harder than you thought, or about right?"), with the three answers as quick replies.

**Effort.** S.

## LR-06 Say what the calibration learned and why

**Now.** Multipliers (0.5–3.0), margins (1–3 days, moved after 2 late or 5 early in a row) and the box experiment are computed from the log and described in words on Setup.

**Proposal.** A *Calibration* section on Stats: per kind and class, the multiplier, the number of slot outcomes behind it, the margin and the run that set it, the yield (PL-06) and the days behind it. The box experiment retires with sessions (BL-08); `boxMin` stays as the piece length (30, PL-12) and the review may still change it. A *Forget* per class, and the full reset in LR-08.

**Effort.** S.

## LR-07 Quiet hours, and a ceiling for everything but Felix

**Now.** Ten channels (`notify/Channels.kt:9–22`); the briefing, review and calendar questions open the app or carry answers.

**Proposal.** Felix is one channel (`FELIX`) with inline reply and his own pacing (LR-03): no ceiling, silent in quiet hours, during calendar events marked busy, and whenever nothing is scheduled. Everything else: nothing in quiet hours except protection and key problems; a ceiling of four a day with the rest held for morning; *Open* on the dropped-plan alert; calendar questions answered inline. A "Messages today" line in Settings → Notifications and Felix.

**Effort.** S.

## LR-08 Reset: stats, calibration, memories, chat, in one place

**Now.** Nothing resets short of clearing the app's data, which also clears the key, the sources and the protection state.

**Proposal.** Your note 7, settled in Q-34. Settings → Reset, one row each: the activity log (stats, day records, slot outcomes; the calibration follows, being replayed from the log); the calibration only (a "learn from here on" marker in the log, keeping the history); memories (AI-21) and your profile lines; the chat and Felix's history; instructions (listing what would go, since some are busy times you still want); and *Everything learned*, which is all five. Each with a dialog naming what's lost, a backup written first (`data/Backup`, to the nightly folder if AR-07 exists), no parent code (none is a loosening), and nothing touching the key, the sources or protection.

**Effort.** S.

## LR-09 Slot outcomes as the learning signal

**Now.** The calibration's multipliers learn from timed sessions (minutes worked against the estimate, `Calibrator.WEIGHT`), nudged by the self-assessment; the day records' done minutes come from sessions; the yield (PL-06) would need done minutes. Sessions go.

**Proposal.** Each slot's end is Felix's question (LR-05): *Done* / *Not yet* / *Took longer*; *Took longer* takes a number in the reply ("+20") or a chip (+10, +20, +30, +60), applied at once (AI-24); a later tick extends the slot to the tick. For a piece of 20 minutes or more he asks for the photo in the same message (BL-08). Outcomes: *Done* at the slot's end records the piece's minutes as worked; *Done* with "+N" records minutes + N; *Not yet* re-slots (PL-13) and records nothing yet; a tick well before the slot's end records the minutes to the tick. The multipliers learn from worked ÷ estimated per kind and class as they did from sessions; the day record's done minutes are the kept slots' minutes; the yield is done ÷ planned over the day. A slot ticked before its end gets no question.

**Caveats.** Self-reported where sessions were measured; "Took longer" is the honest part of the design and needs the question at the right moment, which is why it rides on the slot's end. The photo check is the verified path, and under Q-28 it's also the paid one for long pieces.

**Effort.** M.

---

## What I looked at and left alone

- **The calibration's maths** (bounded, replayed from the log): good; the signal changes (LR-09), the maths doesn't.
- **The review's bounds**: keep; PL-06 adds the hours under the same bounds.
- **The check-in's questions**: fine; LR-02 makes them easier to answer.
