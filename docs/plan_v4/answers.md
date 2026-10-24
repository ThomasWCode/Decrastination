# The questions, and your answers

Every question the 4.x plan put to you, and every one raised while it was built, in a line each: what was asked, what you answered, and where it's recorded. A "(Q31: A)" in [decisions.md](../decisions.md) or [spec/](../spec/) points here; "C-Q" is the cleanup plan's, "M" a call the merge of the two plans made on its own (18 Oct 2026), and Q32 on were asked while building, each with a recommendation and your answer. Where a line gives only the letter, you took the recommendation. A Q id written without its answer's letter, as "(Q13)", and the hyphenated ones, as "(Q-31)", are older requirement ids, not these ([spec/requirement-ids.md](../spec/requirement-ids.md) §Q).

## The cleanup's questions (17 and 18 Oct)

- **C-Q1, the order**: not answered as such; Q1 (A) gave the order, which the merge refined (M1).
- **C-Q2, Codex rounds**: A, one round for a PR that only moves code, up to three for one that changes data or behaviour; and one `@claude review` per PR that gets Codex rounds, answered point by point and closed with "Resolved" (D-83 as it then was; [development.md](../development.md)).
- **C-Q3, how long a finished task keeps its text**: B, 14 days (4.0.13).
- **C-Q4, Felix's notifications**: A, one notification for the conversation, his unread messages in it, a reply box answering the newest (D-95).
- **C-Q5, his past messages**: A, the log's records put into the chat once, each at its time (4.0.0).
- **C-Q6, which notifications are Felix speaking**: as recommended: "how did it go?" and the calendar's questions are his messages; free time, protection, syncs, the model's alerts and a calendar move put back stay the app's own.
- **C-Q7, the docs' shape**: every doc deleted but the plan, its decisions in `decisions.md`, how the app works in `spec/`, how work is done in `development.md` ([architecture.md](architecture.md) §The docs).
- **C-Q8, the account's address in the repository's history**: A, left as it is (D-81).
- **C-Q9, the `:core` module and the state holders**: B, now, with the services.
- **C-Q10, releasing the data changes**: A, one release for who-set-what and the store split (4.0.13).
- **C-Q11, where the module sits**: your call to me: the module after the services (4.0.11), each state holder with its screen's service.
- **C-Q12, the Claude review step**: A, in `development.md` beside the Codex rule.

## The Felix plan's questions (18 Oct)

- **Q1, the order and the versions**: A, the cleanup's first phases, then the chat, reminders and notes, memory and the voice, emails done, the cleanup's later phases between them.
- **Q2, which of the app's own words go into the chat in his name**: as recommended (the morning's fallback, the check-in's questions, "how did it go?" with Claude off, the calendar's questions, the week's review, the summary's offer; not a dropped plan, a key problem, protection, free time or a calendar move); the summary email itself about you alone, not in his voice (Q25).
- **Q3, the morning's channel**: A, his channel for everything he says, the briefing channel deleted once empty.
- **Q4, what folds under the chevron**: A, cards, acts, memory edits, searches and what wasn't made; an *Open* card, his chips and a `byApp` message's own card stay visible.
- **Q5, the label that slides out**: as recommended: one "Changes: N"; a problem in red, the chevron red; for a message arriving on screen and once for each unread message as the chat opens.
- **Q6, swipe to reply**: A, right on any bubble; the quote told to the model where the message isn't the one just before; a reply to his question routed as from the notification (D-98).
- **Q7, unread**: A, a "New messages" divider, the chat opening at the first unread, the count as a badge on his button.
- **Q8, the chat's cap**: every message kept for you to see; what's sent to him cut much earlier (D-96, D-97).
- **Q9, how a reminder is delivered**: B, written fresh by the model for the moment; your words as you set them if offline (D-100).
- **Q10, reminders in quiet hours**: A, a reminder at a time comes whatever the hour, as an alarm would.
- **Q11, a reminder's chips**: A, *Done*, *Snooze 15 min*, *Snooze 1 h*, with no call; anything else typed a chat turn.
- **Q12, where notes, reminders and the Gmail journal live**: A, `felix.json` in the backup and cleared by the chat's reset; the journal its own file, in no reset.
- **Q13, the schema route for reminders and notes**: A, their own fields, the API's compile as the gate.
- **Q14, notes**: A, his from the chat and yours from the sheet; read by the sheet, the chat's context, his own messages' context and the micro-planner; 300 characters a note, 10 a task.
- **Q15, memory that expires**: A, forgotten silently with the until shown; rules with an until too; the profile's shape raised (one re-read, about 60 cents); a "quiet until" example in his prompt (D-103).
- **Q16, the voice**: A (lower case, contractions, a little slang, dates and times exact), no emojis; mild swearing allowed, then left out of his prompts on your read of 21 Oct (D-57).
- **Q17, which gaps to close**: as recommended: a piece kept or unkept, a source checked (the stop, not a round), a task opened where it lives, blocking and free time in his context; the week's numbers and the calendar further out came as things he asks for (your call, 19 Oct; D-101, D-102).
- **Q18, emails done**: as recommended (the switch on with a first-time sheet, a minute's grace, every trigger, every starred message unstarred and the conversation archived), but a failure said by the app's own notification (D-106).
- **Q19, the decisions amended**: A (D-7, D-10, D-54).
- **Q20, the "Your changes" tab**: A, kept as it is.
- **Q21, Felix's "done" on an email before emails could be finished here**: A; you weren't using the app until the whole plan was in.
- **Q22, anything in the suggestions you didn't want**: none.
- **Q23, where the old messages live**: A, a window in `chat.json`, the rest in the archive, read only when you scroll up.
- **Q24, how a reminder's message is written**: A, drafted up to ten minutes ahead with the moment's context (and, since 4.3.11, asked for again at its moment where that's out of date).
- **Q25, the summary email's form**: B, the third person about you, no "I" (D-66).
- **Q26, the chat as the app's home screen**: dropped; the app opens on Day.
- **Q27, the failed Gmail write's notification**: A, a new channel, *Your sources* (`Channels.SOURCES`, `NoticeId.GmailWrite`).
- **Q28, summaries for him**: A, later: a conversation digest as 4.4.0 ([README.md](README.md) §After the plan); nothing else summarised.
- **Q29, his line's default**: A, "your mate who's on your case about the work".
- **Q30, the swearing's line**: A as taken (mild, sparing, never at you), then left out with Q16 on 21 Oct.
- **Q31, standing notes**: A, a note for every task of a class, subject or source (D-100).

## The merge's own calls (18 Oct)

- **M1**: Felix's service, the gateway and the stores before the chat work, the other services and the data model after it.
- **M2**: versions and migrations with `Stores` (3.3.2), so every later data change used one mechanism.
- **M3**: who-set-what and the store split after the chat and before reminders, as one data release.
- **M4**: the readings' prompts renamed before memory and the voice changed them.
- **M5**: a `Learning` service added (4.0.9).
- **M6**: every PR a release with a version: 3.3.x before the chat, 4.x from it on.

## Asked while building (19 to 24 Oct)

- **Q32** (4.0.13): a source's finished task its source no longer lists is still forgotten a fortnight on: A, as before, unless it's the same work as one still open.
- **Q33** (4.1.1): "check Teams" with the chat on screen starts the widget's own sync: B (D-11).
- **Q34** (4.1.3): the briefing on a weekday with no school: B, 07:00 on school days by the school calendar, 08:30 otherwise (D-74).
- **Q35** (4.1.4): the tasks' text cut from 600 to 250 characters: the eval showed no gain, so 600 stays (D-102).
- **Q36** (4.1.3's review): a briefing whose time moves to one gone by isn't run late (D-104).
- **Q37** (4.2.0): the "leave me alone tonight" check run at 19:05 on 21 Oct, not after 22:00: done; the rule was gone by 07:00.
- **Q38** (4.2.1): the chat archive's months' old copies let go of after a week and taken by a clear (D-94).
- **Q39** (4.2.1): Teams' automatic syncs by the school calendar (D-105).
- **Q40** (21 Oct): older personal data found in code and tests: C, left as it is and named in D-80.
- **Q42** (4.2.1): a clear or a reset takes every copy kept of what it clears, the unreadable ones too (D-94).
- **Q43, Q44** (4.2.2): Felix marks only his nudges as answered, with a "Taken as answered" line and no Undo: A, A (D-99).
- **Q45** (4.3.0): the calls made while building Stage 11, the Gmail write journal's rules (listed under *Decided while building it* in #103's description): A, kept as built.
- **Q46** (21 Oct): Codex rounds stop at the first round with at most one P2 and no P1: A (D-83).
- **Q47** (4.3.0): a tick saved just before the app stops is left as it is: A.
- **Q48, Q49, Q50** (4.3.2): each store's writers named, not one writer a store; `AppGraph`'s 537 lines accepted as wiring; the plan's folder kept to be cleaned later: A, A, kept (D-107; cleaned on 24 Oct into this folder's four files).
- **Q51** (4.3.3): a reset's copy keeps every instruction, in use or not: A (D-72).
- **Q52** (4.3.3): a studied Anki quota with no new cards of its own is done, not missed: A.
- **Q53** (4.3.3): the block screen says a missed slot "has no time left today": A.
- **Q54** (4.3.3): a reset refuses when its copy couldn't be restored, whatever it resets: A (D-72).
- **Q55** (4.3.3): a quota whose new cards a later read handed to homework ends missed when its reviews are studied: A, left; nothing earned or shown is wrong but the sheet's "Done" line and one record.
- **Q56** (4.3.4): a task's steps keep their order within a day: A (D-108).
- **Q57** (4.3.5): Felix marks the steps that don't depend on the one before, which can fill a gap; no automatic regrouping of steps to fit a gap (D-109).
- **Q58** (4.3.6): in a school day's briefing call Felix may rebalance two adjacent steps of his own so the next fits a free time (D-110).
- **Q59** (4.3.7): a task Felix proposed, opened in its form before it's added, keeps its steps' own dates: A.
- **Q60** (4.3.7): an "Any order" chip on each step's row in the form: A (D-109).
- **Q61** (23 Oct): a day's last step forecast on the next day ahead of untimed steps before it, till that day's own plan: A, left (D-108).
- **Q62** (23 Oct): a rebalance refused where the shortened step would take an earlier free time held by less urgent work with no kept start: A, left; rare at 07:00 (D-110).
- **Q63** (23 Oct): 4.3.8 to 4.3.10 merged, and the greeting built the same day (4.3.11).
- **Q64** (23 Oct): a reply of yours that was itself a question was kept as his question's answer: fixed in 4.3.12 (D-19).
- **Q65** (23 Oct): a task's sheet named as "Next:" a step already ticked: fixed in 4.3.13.
- **Q66** (24 Oct): a reply Claude can't read is kept as the question's answer, as built since 4.3.12: A, D-19 says so (4.3.19).

Q41 was answered A on 21 Oct; its subject was only in that day's questions file, which is gone, so it can't be followed. Of the review of 4.3.13's findings, your calls on I01, I04, P01 and B08 are in [astra-review-4.3.13](../astra-review-4.3.13/README.md).
