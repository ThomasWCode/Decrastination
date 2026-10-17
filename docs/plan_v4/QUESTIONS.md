# Questions for you

Every call this plan needs, each with a recommendation. Nothing here has been acted on. Where a question repeats one the cleanup plan asked and you answered ([its QUESTIONS.md](../plan_targeted_cleanup/QUESTIONS.md): Q4 one conversation notification, Q5 his past records put in once, Q6 the calendar's questions and "how did it go?" as his), this plan takes your answer as given and asks only what's new. One note: your list's first task ends "I am still here live…", which was true when you wrote it and not now; nothing hangs on it.

## Q1. The order and the versions

- **A (recommended).** The cleanup's Phases 0 and 1, then 4.0.0 (the chat: A, B, C), 4.1.0 (reminders, notes and the gaps: D, E), 4.2.0 (memory and the voice: F, G), 4.3.0 (emails done: H); the cleanup's later phases between them as its own order has them.
- **B.** The voice first, as 4.0.x: goldens only, you hear him sooner; then as A.
- **C.** Emails done first, as 4.0.x: independent, small, and the one that changes your days most; then as A.
- **D.** Another order: say it.
- ANSWER:

## Q2. Which of the app's own words go into the chat in his name

With the chat as his one visible voice, the fixed messages the app sends in his name become `byApp` messages of his ([01](01-the-chat.md) §1). Tick each (multi-select); the recommendation is all but the last two.

- The morning's fallback (the day's title and list, when he can't write): recommended yes, so every morning is in the chat.
- The Sunday check-in's five questions and its thanks: yes (they're his already).
- "How did it go?" with Claude off, one a completion: yes (with Claude on it's his Completions message already).
- The calendar's questions (cleanup Q6 said yes): yes.
- The week's review: his message with the note's first line and a card opening the review: recommended yes.
- "The week's summary is ready; send it?": yes, with a card that opens the email.
- A dropped plan ("Claude's plan for X was dropped"): recommended no (the app's, with a task to open).
- A key problem, protection, free time, a calendar move put back: no (the app's).
- ANSWER:

## Q3. The morning's channel

One notification can't be on two channels. Either the morning goes on his channel inside his one notification, or it keeps the briefing channel and is a second notification beside his.

- **A (recommended).** His channel for everything he says, the morning included; the briefing channel deleted once empty (as the old `daily` was). Your phone's sound and importance for "Felix" cover it all.
- **B.** The morning as its own notification on the briefing channel, as now, beside his.
- ANSWER:

## Q4. What folds under the chevron

- **A (recommended).** Folded: change cards, new-task cards, acts, memory edits, searched and sources, read in full, not made, left out. Visible: an *Open* card, his suggested-reply chips, a `byApp` message's own card (the review to open, the summary to send).
- **B.** Everything under the bubble folds, *Open* cards included.
- **C.** Only the small lines fold (acts, memory, searched, left out); cards stay.
- ANSWER:

## Q5. The label that slides out

Three parts: what "Changes: N" counts; how a problem shows; when it plays.

- Counting: **A (recommended)** changes made + tasks added + actions done, as one "Changes: N"; **B** separate parts ("Changes: 2 · Added: 1 · Ticked: 1"); **C** only the instruction's changes.
- A problem (not made, left out, not looked up): **A (recommended)** "1 not made" in red in the label, and the chevron red; **B** the label only; **C** nothing special, it's in the tail.
- When: **A (recommended)** for a message arriving while the chat's on screen, and once for each unread message when the chat opens; **B** only while on screen; **C** every message of his, every time the chat opens (noisy).
- ANSWER:

## Q6. Swipe to reply

- Direction: **A (recommended)** swipe right on any bubble (his or yours), as WhatsApp; **B** right on his, left on yours; **C** his only.
- What the model's told: **A (recommended)** the quoted message prefixed to your turn whenever it isn't the message just before yours; **B** always; **C** never (the history's enough).
- Replying to his Question, Completions, CheckIn or calendar question in the chat is routed as from the notification, with no call where none's needed: **A (recommended)** yes; **B** no, a swipe-reply is always a chat turn.
- ANSWER:

## Q7. Unread

- **A (recommended).** A "New messages" divider on opening, the chat opening at the first unread, and the unread count as a badge on his button on every screen (the block screen's too).
- **B.** The divider and the position, no badge.
- **C.** Neither: the notification is the unread.
- ANSWER:

## Q8. The chat's cap

The cleanup's 05 proposes the last 200 messages or 60 days, whichever keeps more, once his own messages are in it (a busy day is a dozen of his).

- **A (recommended).** As proposed.
- **B.** A number: say it.
- ANSWER:

## Q9. How a reminder is delivered

- **A (recommended).** As he set it, verbatim, with no model call: "reminder: call gran", in his register as he wrote it when setting it; where one of his own messages is due at the same slot, its context carries the reminder so he can fold it in; the morning's context lists today's reminders.
- **B.** Written fresh by the model at the time (a call, a few cents each), so it's about the moment ("you're on the physics now: the form's due tomorrow").
- **C.** A, but the morning's message doesn't list them.
- ANSWER:

## Q10. Reminders in quiet hours and busy events

His own messages never come then (D-58). A reminder is yours to set.

- **A (recommended).** An `At` reminder comes at its time whatever the hour, like an alarm; `SlotStart` and `BeforeDue` follow their slot or deadline, which the planner keeps out of quiet hours anyway.
- **B.** Held to the end of quiet hours (07:00), marked late.
- **C.** He asks when you set one in quiet hours ("that's 23:30, still want it then?").
- ANSWER:

## Q11. A reminder's chips

- **A (recommended).** *Done*, *Snooze 15 min*, *Snooze 1 h*, handled by the app with no call; anything else typed is a chat turn with the reminder quoted.
- **B.** No chips: any reply is a chat turn (a call).
- **C.** *Done* and *Snooze 15 min* only.
- ANSWER:

## Q12. Where notes, reminders and the Gmail journal live, and Reset

- **A (recommended).** `felix.json` (notes and reminders, versioned), in the backup, cleared by Reset → "The chat and Felix's history"; the Gmail write journal in `RuntimeState` until the cleanup's Phase 5 gives it a file, in no backup, cleared by no reset.
- **B.** Notes and reminders inside `chat.json`'s conversation state.
- **C.** A file each (`notes.json`, `reminders.json`).
- ANSWER:

## Q13. The schema route for reminders and notes

- **A (recommended).** Their own fields (`reminders`, `notes`, two actions), with the API's compile as the gate in the PR; if refused, B in the same PR.
- **B.** Change types `Remind` and `Note` on the existing change object (no growth; the instructions screen gets them free; a reminder's state then lives beside its instruction, which is a stretch).
- ANSWER:

## Q14. Notes: who writes them, and where they go

- Who: **A (recommended)** him from the chat and you from the task's sheet ("Add a note"); **B** him only.
- Where they're read: **A (recommended)** the sheet, the chat's context, his own messages' context, and the micro-planner (so a first action can use them); **B** the sheet and the chat's context only.
- Limits: 300 characters a note, 10 a task, the oldest noted: fine, or say otherwise.
- ANSWER:

## Q15. Memory that expires

- Forgetting: **A (recommended)** silent, with the until shown in the lists (Settings → Felix, the chat's menu); **B** a line in the chat when something expires.
- Rules with an until too ("don't message me tonight"): **A (recommended)** yes; **B** facts only.
- The profile's shape (an until shown) ties to `Prompts.VERSION`: **A (recommended)** raise it, and batch F with any other reading change due (about 40 calls, 60 cents, once); **B** keep the until out of the profile's text (he isn't told a fact is passing).
- A "quiet until" example in his own prompt, so a reply like "leave me alone tonight" makes a rule until the morning: **A (recommended)** yes; **B** no.
- ANSWER:

## Q16. The voice

This is taste, and the examples pin it; the plan writes the paragraph and examples with you before the goldens change.

- How far: **A (recommended)** lower case, contractions, a little slang ("alr", "gonna", "tbh"), short; dates and times still exact; **B** further: more slang, playful; **C** lighter: casual but capitalised, no slang.
- Emojis: **A (recommended)** none, as now; **B** a few; **C** his call.
- Swearing: **A (recommended)** never; **B** mild.
- `felixLine`'s new default (your phone keeps its own value; this is the default for a reset): "your mate who's on your case about the work", or your words:
- The fixed lines in his name reworded in the register (the check-in's questions, "Thanks: the week's review comes next.", the chips "harder / about right / easier" with the app matching either form): **A (recommended)** yes; **B** leave them.
- The private evals re-run before the merge ($2): **A (recommended)** yes; **B** skip.
- ANSWER:

## Q17. Which gaps to close in 4.1.0-E

Multi-select ([02](02-what-felix-can-do.md) §3); recommended: G1, G2 (the stop, not the round), G3, G5.

- G1 keep and unkeep a piece with no step (`keepPiece`, `unkeepPiece`).
- G2 check a source ("check Teams"): the stop (he says he's asked; a `Note` message when the read changes the task), or the round (asked again once the read's done; dearer).
- G3 open where it lives (Teams, Power Planner, Gmail, AnkiDroid).
- G5 free time and blocking in the chat's context, one line.
- G6 the week's numbers on request (a round) or always (two lines).
- G7 events past the fortnight when a month is named.
- ANSWER:

## Q18. Emails done

- The switch's default: **A (recommended)** on, with the first-time sheet; **B** off until you turn it on; **C** on, no sheet.
- The grace before the write: **A (recommended)** 60 s, Undo within it cancels; after it, Undo re-stars by a reverse write; **B** 5 minutes; **C** no grace: write at once, Undo always a reverse write.
- The triggers: multi-select; recommended all four: the last piece kept (Day, the widget, the block screen, Felix's tick); the last step ticked by hand on the sheet; Felix's `done` on an email; a "Finish: unstar and archive" button on an email's sheet.
- What's written: **A (recommended)** every starred message of the conversation unstarred and the whole conversation archived; **B** unstar only (no archive); **C** archive only when the conversation has no unstarred messages left you'd want in the inbox (can't be told; not recommended).
- A failure after a day of tries is said by: **A (recommended)** a `Note` message of his in the chat, and the sheet; **B** the sheet only; **C** the app's own notification.
- ANSWER:

## Q19. The decisions amended

D-7 ("Sources are only ever read") becomes "…but for one write: finishing a starred email's work unstars and archives it, behind a switch"; D-10 adds "or its work finished here (4.3.0)"; D-54 adds "but a starred email, which finishing here unstars". New decisions as [README](README.md) §"Decisions it adds".

- **A (recommended).** As written.
- **B.** Changes: say them.
- ANSWER:

## Q20. The "Your changes" tab

The aim says nothing but the chat needs touching. The tab is the chat's second tab, and the place to take a change back.

- **A (recommended).** Keep it as it is for now; revisit once reminders and notes have their own Undo and the chat's tail folds.
- **B.** Fold it into the chat's menu as a sheet ("Your changes"), leaving one tab.
- **C.** Keep the tab, and add a line under each of his changes in the chat saying "in Your changes" so the link's clear.
- ANSWER:

## Q21. Felix's "done" on a Gmail task before 4.3.0

Until emails can be finished here, his `done` on an email is refused ("done when Gmail says so"). If 4.3.0 comes last (Q1 A), that stays for three releases.

- **A (recommended).** Fine: he says so, and the context marks it.
- **B.** Move H earlier (Q1 C).
- ANSWER:

## Q22. Anything in the suggestions you don't want

The plan adds things you didn't list: the unread badge, date separators and times, long-press Reply/Copy, the empty chat's new examples, reminders' snooze chips, today's reminders in the morning, notes to the micro-planner, a quiet-until rule, the gaps in Q17, the first-time sheet for Gmail. Each is in its question above or on by recommendation; name any to drop here.

- ANSWER:
