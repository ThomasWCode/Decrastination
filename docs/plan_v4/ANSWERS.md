# The questions the plan asked, and your answers

Every call either plan needed, with your answers as you wrote them, kept here as the record; nothing in the plan is open. This file is closed: questions that come up while the stages are implemented go in a `QUESTIONS.md` of their own, with a recommendation and a blank `ANSWER:` line each, so they stay apart from what's settled here. Part A is the cleanup's questions (C-Q1 to C-Q12, answered 17 and 18 Oct 2026), Part B the Felix plan's (Q1 to Q30, answered 18 Oct), Part C the calls the merge of 18 Oct made on its own, for you to veto, and Q31 (answered 18 Oct).

---

## Part A: the cleanup's questions

### C-Q1. The order

- **A (recommended).** Phases 0 and 1 first, then the chat work you've written up (Felix's messages all in the chat, a WhatsApp-like chat, swipe to reply, the collapsed extras, notifications that don't replace each other), then Phases 2 to 7 between other features.
- **B.** The whole cleanup first, then features.
- **C.** The lighter route: Phases 0 to 2 now (the chat work's ground and one model gateway), the rest only when a feature needs it.
- ANSWER:
- Note (18 Oct): not answered here, but Q1 below (answered A) gives this order, so the plan took it as A. The merge then refined it (Part C, M1): the chat work still comes before the other services, but Felix's own service, the gateway and the stores come before the chat work.

### C-Q2. Codex rounds for the cleanup's PRs

- **A (recommended).** One round for a PR that only moves code (most of Phase 3, Phase 6); up to three, stopping at a round with at most one P2, for those that change data or behaviour (1b, 4, 5).
- **B.** One round for every PR.
- **C.** No round for a move: CI and the phone check only.
- ANSWER: A. Also run one claude review (@claude review) per PR that has up to three codex reviews. It comments on the PR in one big piece of text, unlike codex's comments per item. Once responsed to, reply to claude's comment with 'Resolved'.

### C-Q3. How long a finished task keeps its text

Finished tasks are 87 % of `tasks.json` (509 of 585 KB on your phone), most of it emails' text. Trimming keeps each task's record (title, dates, minutes, ticks, your values) and drops its text and Claude's reading. One thing changes: if a source lists a long-finished task again (a hand-in undone, an email starred again), it's read again, a paid call.

- **A (recommended).** 30 days after it's finished.
- **B.** 14 days.
- **C.** 90 days.
- **D.** Never: keep everything, as now.
- ANSWER: B

### C-Q4. Felix's notifications

Both stop a new message removing the last one.

- **A (recommended).** One notification for the conversation, as messaging apps do: his unread messages in it, newest last, a reply box answering the newest; tapping opens the chat at the first unread; opening the chat clears it.
- **B.** One notification a message, grouped together: each dismissed on its own, each with its own reply box answering that message.
- ANSWER: A

### C-Q5. Felix's past messages

His unprompted messages of the last 30 days are only in the activity log (15 on your phone today, none in the chat).

- **A (recommended).** Put them into the chat once, each at its time, with the update that moves his messages there.
- **B.** Start from the update: the old ones age out of the log.
- ANSWER: A

### C-Q6. Which notifications are Felix speaking

With the chat as his one visible voice, his messages go through the chat. The ones that are clearly his: the morning briefing, his looks at a slot's start and end, his questions, the check-in's questions and the review's note. The ones in between:

- **"How did it go?"** after a completion (harder, as expected, easier).
- **The calendar's questions** ("how much of your time does this event take?").
- ANSWER: Recomendation is correct.

Recommended: both become his messages, asked in the chat with quick replies, and notified from there. These stay the app's own notifications, outside the chat: free time and its end, protection, syncs under way, the model's alerts, a calendar move put back.

### C-Q7. The docs' new shape (answered, 17 Oct)

Your call: every doc deleted but this plan, after its decisions and still-true facts were moved; the decisions in `docs/decisions.md`, how the app works in `docs/spec/`, how the work is done in `docs/development.md`. No change log or history folder (git and the PRs keep the history).

### C-Q8. The Gmail account's address in the repository's history

The old `docs/data-sources.md` named the account's address in its Gmail heading. It left the docs when that file was deleted; git's history still has it.

- **A (recommended, unless you want it gone entirely).** Leave the history as it is.
- **B.** Remove it from the repository's history too: a rewrite and a force-push of the public repository, after which any clone must be made again.
- ANSWER: A

### C-Q9. The optional Phase 8

A pure `:core` module (the compiler keeping it free of Android, its tests faster), and state holders for the largest screens (the chat, Settings, the task sheet).

- **A (recommended).** Later, when there's time.
- **B.** Now, with Phase 3.
- **C.** Not at all.
- ANSWER: B

### C-Q10. Releasing the data changes

Phases 4 and 5 both change stored data.

- **A (recommended).** One release for both: one rehearsal on your latest backup, one backup before the install, one migration to watch.
- **B.** A release each, so each is smaller.
- ANSWER: A

### C-Q11. Where Phase 8 sits inside Phase 3 (your C-Q9: B)

Phase 8 (the `:core` module and the state holders) now goes with Phase 3. The module split moves every `core/` file and its tests into a Gradle module, which is best done when nothing else is moving.

- **A (recommended).** The `:core` module as Phase 3's last PR, after the services; each screen's state holder as its service lands (the chat's with the chat work, Settings' with Protection, the task sheet's with Instructions).
- **B.** The module first, before the services, so every service is written against it from the start.
- ANSWER: I do not know enough about this: your call.
- Taken (18 Oct): A. The module is the one PR that touches every `core/` file at once, so it goes when nothing else is moving, after the services (Stage 6f); each state holder lands with its screen's service, so no screen is reworked twice.

### C-Q12. The review step in `docs/development.md` (your C-Q2)

Your C-Q2 answer adds one `@claude review` per PR that gets up to three Codex rounds, answered point by point and closed with "Resolved". That reads as a rule for every PR, not only the cleanup's.

- **A (recommended).** Put it in `docs/development.md` §Changes, pull requests and reviews beside the Codex rule (D-83's companion).
- **B.** Keep it to the two plans' documents.
- ANSWER: A

---

## Part B: the Felix plan's questions

### Q1. The order and the versions

- **A (recommended).** The cleanup's Phases 0 and 1, then 4.0.0 (the chat: A, B, C), 4.1.0 (reminders, notes and the gaps: D, E), 4.2.0 (memory and the voice: F, G), 4.3.0 (emails done: H); the cleanup's later phases between them as its own order has them.
- **B.** The voice first, as 4.0.x: goldens only, you hear him sooner; then as A.
- **C.** Emails done first, as 4.0.x: independent, small, and the one that changes your days most; then as A.
- **D.** Another order: say it.
- ANSWER: A

### Q2. Which of the app's own words go into the chat in his name

With the chat as his one visible voice, the fixed messages the app sends in his name become `byApp` messages of his ([05](05-the-chat.md) §1). Tick each (multi-select); the recommendation is all but the last two.

- The morning's fallback (the day's title and list, when he can't write): recommended yes, so every morning is in the chat.
- The Sunday check-in's five questions and its thanks: yes (they're his already).
- "How did it go?" with Claude off, one a completion: yes (with Claude on it's his Completions message already).
- The calendar's questions (C-Q6 said yes): yes.
- The week's review: his message with the note's first line and a card opening the review: recommended yes.
- "The week's summary is ready; send it?": yes, with a card that opens the email.
- A dropped plan ("Claude's plan for X was dropped"): recommended no (the app's, with a task to open).
- A key problem, protection, free time, a calendar move put back: no (the app's).
- ANSWER: Keep recomendation. The summary that goes to dad should be suggested to the app user in Felix's voice but the email should talk about the user only, not using words like 'I' for the AI etc, so not totally in his voice.

### Q3. The morning's channel

One notification can't be on two channels. Either the morning goes on his channel inside his one notification, or it keeps the briefing channel and is a second notification beside his.

- **A (recommended).** His channel for everything he says, the morning included; the briefing channel deleted once empty (as the old `daily` was). Your phone's sound and importance for "Felix" cover it all.
- **B.** The morning as its own notification on the briefing channel, as now, beside his.
- ANSWER: A

### Q4. What folds under the chevron

- **A (recommended).** Folded: change cards, new-task cards, acts, memory edits, searched and sources, read in full, not made, left out. Visible: an *Open* card, his suggested-reply chips, a `byApp` message's own card (the review to open, the summary to send).
- **B.** Everything under the bubble folds, *Open* cards included.
- **C.** Only the small lines fold (acts, memory, searched, left out); cards stay.
- ANSWER: A

### Q5. The label that slides out

Three parts: what "Changes: N" counts; how a problem shows; when it plays.

- Counting: **A (recommended)** changes made + tasks added + actions done, as one "Changes: N"; **B** separate parts ("Changes: 2 · Added: 1 · Ticked: 1"); **C** only the instruction's changes.
- A problem (not made, left out, not looked up): **A (recommended)** "1 not made" in red in the label, and the chevron red; **B** the label only; **C** nothing special, it's in the tail.
- When: **A (recommended)** for a message arriving while the chat's on screen, and once for each unread message when the chat opens; **B** only while on screen; **C** every message of his, every time the chat opens (noisy).
- ANSWER: Keep recomendations.

### Q6. Swipe to reply

- Direction: **A (recommended)** swipe right on any bubble (his or yours), as WhatsApp; **B** right on his, left on yours; **C** his only.
- What the model's told: **A (recommended)** the quoted message prefixed to your turn whenever it isn't the message just before yours; **B** always; **C** never (the history's enough).
- Replying to his Question, Completions, CheckIn or calendar question in the chat is routed as from the notification, with no call where none's needed: **A (recommended)** yes; **B** no, a swipe-reply is always a chat turn.
- ANSWER: A

### Q7. Unread

- **A (recommended).** A "New messages" divider on opening, the chat opening at the first unread, and the unread count as a badge on his button on every screen (the block screen's too).
- **B.** The divider and the position, no badge.
- **C.** Neither: the notification is the unread.
- ANSWER: A

### Q8. The chat's cap

The cleanup proposed the last 200 messages or 60 days, whichever keeps more, once his own messages are in it (a busy day is a dozen of his).

- **A (recommended).** As proposed.
- **B.** A number: say it.
- ANSWER: Keep all the messages, but only for the purpose of me seeing them: They should should being included in things such as data sent to Felix much earlier.

### Q9. How a reminder is delivered

- **A (recommended).** As he set it, verbatim, with no model call: "reminder: call gran", in his register as he wrote it when setting it; where one of his own messages is due at the same slot, its context carries the reminder so he can fold it in; the morning's context lists today's reminders.
- **B.** Written fresh by the model at the time (a call, a few cents each), so it's about the moment ("you're on the physics now: the form's due tomorrow").
- **C.** A, but the morning's message doesn't list them.
- ANSWER: B, A if offline

### Q10. Reminders in quiet hours and busy events

His own messages never come then (D-58). A reminder is yours to set.

- **A (recommended).** An `At` reminder comes at its time whatever the hour, like an alarm; `SlotStart` and `BeforeDue` follow their slot or deadline, which the planner keeps out of quiet hours anyway.
- **B.** Held to the end of quiet hours (07:00), marked late.
- **C.** He asks when you set one in quiet hours ("that's 23:30, still want it then?").
- ANSWER: A

### Q11. A reminder's chips

- **A (recommended).** *Done*, *Snooze 15 min*, *Snooze 1 h*, handled by the app with no call; anything else typed is a chat turn with the reminder quoted.
- **B.** No chips: any reply is a chat turn (a call).
- **C.** *Done* and *Snooze 15 min* only.
- ANSWER: A

### Q12. Where notes, reminders and the Gmail journal live, and Reset

- **A (recommended).** `felix.json` (notes and reminders, versioned), in the backup, cleared by Reset → "The chat and Felix's history"; the Gmail write journal in `RuntimeState` until the cleanup's Phase 5 gives it a file, in no backup, cleared by no reset.
- **B.** Notes and reminders inside `chat.json`'s conversation state.
- **C.** A file each (`notes.json`, `reminders.json`).
- ANSWER: A
- Note (the merge): the store split now comes before the email's finish, so the journal has its file (`gmail-writes.json`) from the start; nothing else of A changes.

### Q13. The schema route for reminders and notes

- **A (recommended).** Their own fields (`reminders`, `notes`, two actions), with the API's compile as the gate in the PR; if refused, B in the same PR.
- **B.** Change types `Remind` and `Note` on the existing change object (no growth; the instructions screen gets them free; a reminder's state then lives beside its instruction, which is a stretch).
- ANSWER: A

### Q14. Notes: who writes them, and where they go

- Who: **A (recommended)** him from the chat and you from the task's sheet ("Add a note"); **B** him only.
- Where they're read: **A (recommended)** the sheet, the chat's context, his own messages' context, and the micro-planner (so a first action can use them); **B** the sheet and the chat's context only.
- Limits: 300 characters a note, 10 a task, the oldest noted: fine, or say otherwise.
- ANSWER: A

### Q15. Memory that expires

- Forgetting: **A (recommended)** silent, with the until shown in the lists (Settings → Felix, the chat's menu); **B** a line in the chat when something expires.
- Rules with an until too ("don't message me tonight"): **A (recommended)** yes; **B** facts only.
- The profile's shape (an until shown) ties to the readings' prompt version: **A (recommended)** raise it, and batch F with any other reading change due (about 40 calls, 60 cents, once); **B** keep the until out of the profile's text (he isn't told a fact is passing).
- A "quiet until" example in his own prompt, so a reply like "leave me alone tonight" makes a rule until the morning: **A (recommended)** yes; **B** no.
- ANSWER: A

### Q16. The voice

This is taste, and the examples pin it; the plan writes the paragraph and examples with you before the goldens change.

- How far: **A (recommended)** lower case, contractions, a little slang ("alr", "gonna", "tbh"), short; dates and times still exact; **B** further: more slang, playful; **C** lighter: casual but capitalised, no slang.
- Emojis: **A (recommended)** none, as now; **B** a few; **C** his call.
- Swearing: **A (recommended)** never; **B** mild.
- `felixLine`'s new default (your phone keeps its own value; this is the default for a reset): "your mate who's on your case about the work", or your words:
- The fixed lines in his name reworded in the register (the check-in's questions, "Thanks: the week's review comes next.", the chips "harder / about right / easier" with the app matching either form): **A (recommended)** yes; **B** leave them.
- The private evals re-run before the merge ($2): **A (recommended)** yes; **B** skip.
- ANSWER: A but no emojis and could add mild swearing.

### Q17. Which gaps to close

Multi-select ([06](06-what-felix-can-do.md) §3); recommended: G1, G2 (the stop, not the round), G3, G5.

- G1 keep and unkeep a piece with no step (`keepPiece`, `unkeepPiece`).
- G2 check a source ("check Teams"): the stop (he says he's asked; a `Note` message when the read changes the task), or the round (asked again once the read's done; dearer).
- G3 open where it lives (Teams, Power Planner, Gmail, AnkiDroid).
- G5 free time and blocking in the chat's context, one line.
- G6 the week's numbers on request (a round) or always (two lines).
- G7 events past the fortnight when a month is named.
- ANSWER: Keep recomendation.

### Q18. Emails done

- The switch's default: **A (recommended)** on, with the first-time sheet; **B** off until you turn it on; **C** on, no sheet.
- The grace before the write: **A (recommended)** 60 s, Undo within it cancels; after it, Undo re-stars by a reverse write; **B** 5 minutes; **C** no grace: write at once, Undo always a reverse write.
- The triggers: multi-select; recommended all four: the last piece kept (Day, the widget, the block screen, Felix's tick); the last step ticked by hand on the sheet; Felix's `done` on an email; a "Finish: unstar and archive" button on an email's sheet.
- What's written: **A (recommended)** every starred message of the conversation unstarred and the whole conversation archived; **B** unstar only (no archive); **C** archive only when the conversation has no unstarred messages left you'd want in the inbox (can't be told; not recommended).
- A failure after a day of tries is said by: **A (recommended)** a `Note` message of his in the chat, and the sheet; **B** the sheet only; **C** the app's own notification.
- ANSWER: Keep recomendations except for: **C** the app's own notification.

### Q19. The decisions amended

D-7 ("Sources are only ever read") becomes "…but for one write: finishing a starred email's work unstars and archives it, behind a switch"; D-10 adds "or its work finished here"; D-54 adds "but a starred email, which finishing here unstars". New decisions as [README](README.md) §Decisions it adds.

- **A (recommended).** As written.
- **B.** Changes: say them.
- ANSWER: A

### Q20. The "Your changes" tab

The aim says nothing but the chat needs touching. The tab is the chat's second tab, and the place to take a change back.

- **A (recommended).** Keep it as it is for now; revisit once reminders and notes have their own Undo and the chat's tail folds.
- **B.** Fold it into the chat's menu as a sheet ("Your changes"), leaving one tab.
- **C.** Keep the tab, and add a line under each of his changes in the chat saying "in Your changes" so the link's clear.
- ANSWER: A

### Q21. Felix's "done" on a Gmail task before emails are finished here

Until emails can be finished here, his `done` on an email is refused ("done when Gmail says so"). With the email's finish last, that stays for most of the plan.

- **A (recommended).** Fine: he says so, and the context marks it.
- **B.** Move the email's finish earlier.
- ANSWER: A, I will not be using the app until the whole implementation is done, so not an issue either way.

### Q22. Anything in the suggestions you don't want

The plan adds things you didn't list: the unread badge, date separators and times, long-press Reply/Copy, the empty chat's new examples, reminders' snooze chips, today's reminders in the morning, notes to the micro-planner, a quiet-until rule, the gaps in Q17, the first-time sheet for Gmail. Each is in its question above or on by recommendation; name any to drop here.

- ANSWER: None

### Q23. Where the old messages live (Q8's design)

Q8 keeps every message for you to see, with what's sent to him cut much earlier than that.

- **A (recommended).** `chat.json` keeps the window (the last 200 messages or 60 days, whichever keeps more: what the screen opens on, what his rules count, what the model's 12-message history is cut from); `chat-archive.json` the rest, oldest first, read only when you scroll up past the top; in the backup while the backup fits its 8 MB cap, the oldest left out first.
- **B.** Everything in `chat.json`: simplest, but the file is rewritten whole at every message (a year's use is a few MB a write).
- **C.** A file a month.
- ANSWER (given live, 18 Oct): A.

### Q24. How a reminder's message is written (Q9's B)

- **A (recommended).** Drafted up to ten minutes ahead, as his slot messages are (`draftAhead`), with the moment's context, and sent at the exact time; no draft by then (no network, Claude off or resting, over the cap, the call failed) and your words go verbatim, marked as the app's.
- **B.** The call made at the moment, the message landing a few seconds late; the same fallback.
- ANSWER (given live, 18 Oct): A.

### Q25. The summary email's form (Q2's note)

- **A.** As now: your own first person ("I finished the essay…"); Felix's voice nowhere in it.
- **B.** The third person about you ("they finished…", or by name), no "I" at all; the summary prompt and its golden change.
- ANSWER (given live, 18 Oct): B.

### Q26. The chat as the app's home screen (your extra note)

- **A.** The launcher opens the chat, with a Plan button to Day/Work/Stats.
- **B.** One screen with a bottom bar: Chat | Day | Work | Stats.
- **C.** The chat as the first top tab.
- ANSWER (given live, 18 Oct): dropped; the app opens on Day as now.

### Q27. The failed Gmail write's notification (Q18's C)

You chose the app's own notification over a message of his. It needs a channel.

- **A (recommended).** A new channel, "Your sources" (`Channels.SOURCES`), default importance, outside the ceiling: for this, and later for any source that can't be read for a day; the id `NoticeId.GmailWrite`, tagged by thread, tapped, opening the task's sheet.
- **B.** The "Claude" channel (`Channels.MODEL`), renamed "Claude and your sources".
- **C.** The protection channel (high importance: it buzzes, which this doesn't deserve).
- ANSWER: A

### Q28. Summaries for him ([13](13-summaries.md))

The emails are condensed already (D-93); the one gap is the conversation beyond the last 12 messages.

- **A (recommended).** Later: a conversation digest as a small 4.4.0, after you've lived with the new chat a few weeks; nothing else summarised.
- **B.** Now: the digest as a PR of its own after the chat work.
- **C.** Not at all.
- ANSWER: A

### Q29. `felixLine`'s new default (Q16 left it blank)

- **A (recommended).** "your mate who's on your case about the work".
- **B.** Your words:
- ANSWER: A

### Q30. The swearing's line (Q16: "could add mild swearing")

Taken as: mild ("bloody", "crap", nothing stronger), sparing (not every message), never at you, never in the fixed lines the app writes; one of the pinned examples shows it so the model knows where the line is.

- **A (recommended).** As taken.
- **B.** Say otherwise:
- ANSWER: A

### Your extra notes of 18 Oct, and what became of each

- "Ensure the chat can handle messages that just require a reply from Felix, no a change in any way: e.g. the user typing 'God isn't Miss X really annoying with her homeworks!'" → [09](09-his-voice.md) §Just talking: a sentence in both prompts, a vent among the pinned examples, the private eval measuring unasked changes; on screen a bare reply has no chevron and no label.
- "Look at plan_targeted_cleanup and ensure that its plan is up to date with the current code" → done 18 Oct: re-measured against `main` at e6cd54b (3.2.0) and the phone's stores that day ([01](01-current-state.md)); the producers, files and ids brought up to date; then merged into this plan.
- "Make the chat screen now be the app's homescreen that it opens on" → dropped, your call live on 18 Oct (Q26).
- "Evaluate first… constant summarisation model calls so that Felix doesn't have to read raw data" → [13](13-summaries.md): the emails are condensed already by the readings (D-93); the gap is the conversation beyond the last 12 messages; a digest is sketched, deferred to 4.4.0 (Q28).

---

## Part C: the merge's own calls (18 Oct), and what's still open

The merge put the two plans in one order. These are the calls it made beyond your answers, each with its reason, for you to veto; [03](03-stages.md) §Why this order has them in full.

- **M1. Felix's service, the gateway and the stores come before the chat work** (Stages 1–4, then 5), where the cleanup had its Felix service after it. The move is verbatim and cheap; the chat work then has a tested service from its first commit, and never moves. The other services (6) and the data model (7) still wait until the chat is in.
- **M2. Versions and migrations come with `Stores`** (Stage 3), pulled forward from the cleanup's store split, so the chat's migration (5a) and every store a feature adds use one tested mechanism from the start.
- **M3. Who-set-what and the store split come after the chat and before reminders** (Stage 7), as one data release (C-Q10): reminders need `By`, the email's finish gets its journal's own file, and neither migration touches the chat.
- **M4. The readings' prompts are renamed before memory and the voice change them** (Stage 9 before 10), so those diffs are only the change.
- **M5. A `Learning` service is added** (6e: the activity log, the review, the stats, the calibration's undo, the summary's writing), which the cleanup's services table named but its PR list left out.
- **M6. Every PR is a release with a version**: 3.3.x for the cleanup before the chat, 4.x from the chat on in the order things land. If you'd rather cleanup releases didn't bump the version, say so: the plan's order doesn't change.

### Q31. Standing notes: a note for every task of a class, subject or source

You asked (18 Oct) whether he can keep a note that goes beyond one assignment: "tell me to do Ms Peters' assignments on sheets, not in my book". Today that's a standing instruction in his memory, which every prompt sees, but no source names a teacher (Teams gives a class code), so it works only once you've told him which class she teaches, and the link to the task in front of him is the model's to make each time. [07](07-notes-and-reminders.md) §Standing notes has the design.

- **A (recommended).** In 8a, half a day more: `Note.taskId` optional beside a `scope` (subject or class, source, title contains); `Notes.forTask(task)` attaches a standing note to every matching task by code; it shows on each matching task's sheet, sits under each in his context, goes into his slot message and to the micro-planner, as a per-task note does. You'd still tell him once which class is hers.
- **B.** Not now: a memory rule does most of it; revisit after 4.x.
- ANSWER (given in the chat, 18 Oct): A.
