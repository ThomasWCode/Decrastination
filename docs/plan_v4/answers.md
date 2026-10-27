# The questions, and your answers

Every question the 4.x plan put to you, and every one raised while it was built, in a line each: what was asked, what you answered, and where it's recorded; after it, the timer's (4.4.0), with the choices its building made without asking, then those of the Day as a calendar (4.5.0 to 4.6.0), the breaks outside (4.4.1 and 4.5.2) and your busy times in your calendar (4.5.3), each with the choices made without asking. A "(Q31: A)" in [decisions.md](../decisions.md) or [spec/](../spec/) points here; "C-Q" is the cleanup plan's, "M" a call the merge of the two plans made on its own (18 Oct 2026), and Q32 on were asked while building, each with a recommendation and your answer. Where a line gives only the letter, you took the recommendation. From Q32 on, a Q id is always one of these, with its letter or without, as "(Q83)"; below that, one written without its answer's letter, as "(Q13)", and the hyphenated ones, as "(Q-31)", are older requirement ids, not these ([spec/requirement-ids.md](../spec/requirement-ids.md) §Q).

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
- **Q28, summaries for him**: A, later: a conversation digest ([README.md](README.md) §After the plan; numbered 4.4.0 then, a number the timer has since taken); nothing else summarised.
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

## The timer (4.4.0, 25 and 26 Oct)

Asked as the timer's plan was made (its Q1 to Q4), then while it was built. How it works is in [spec/blocking-and-protection.md](../spec/blocking-and-protection.md) §A timer on a piece and [spec/felix.md](../spec/felix.md) §His messages; the decision is D-112.

- **Q67, which of Felix's messages a running timer holds**: as recommended, his slot-start nudge and his slot-over message, the slot's outcome recorded as ever: "Ensure that it will still record and message as 'missed' if the task does not get ticked off within 30 min of the session ending. In general, ensure that it can cope with the task not actually being completed in the session." The hold lifts 30 minutes after the countdown's zero, and his message comes then, fresh from the lift.
- **Q68, time over at the zero**: Android's own counter running on as −mm:ss, the running notification retitled and a "Time's up" of its own (not re-based to count up, nor rewritten every half minute).
- **Q69, where Start is**: the piece's actions and the task's sheet, and ▶ on the widget: "Make the tick and start button on the widget appear above each other in a column, and then the reload button appear in the column to the right, as it does now."
- **Q70, after a Stop by hand with the piece undone**: 30 minutes after any end, its zero or a Stop, whichever comes first; not at once on a Stop, when a tick a moment after it would follow "you missed it".
- **"Time's up" pops up** (26 Oct, at the phone): at the high level, as free time's last minute is, so it's seen on silent; built first at the middle level, which only sounded.

Chosen without asking, yours to overturn (the alternative in brackets):

- It's a timer in the code and on screen, not a session, so D-55 keeps its meaning. (Called a session, D-55 amended.)
- One at a time: a Start replaces the one there is and says so; a second on the piece it runs on restarts nothing. (One a task.)
- Start doesn't open the task; Open stays beside it. (One tap for both; Open can fail, and the line said would carry both.)
- It counts the plan's piece's minutes; for a step the plan hasn't laid, the step's as its sheet shows them; a task with no steps has Start for its next piece only while the plan has one. (Minutes picked at each Start.)
- Its time counts for nothing: not a kept slot's minutes, not the calibration, not free time. (The overrun offered as the kept slot's extra minutes when a piece is ticked over time; against it, a timer left running through dinner would teach a wrong pace.)
- A Start is something done on its task (`Looks.noteActivity`), so the slot's nudge stays off after a Stop too. (Only a running timer holds it.)
- Felix is told of a running timer in his plan's line, and can't start or stop one from the chat. (An action of his: a schema change.)
- The wall clock, not uptime: nothing rides on it. (Uptime, as the old sessions kept for their blocking.)
- "Time's up" comes whatever the hour: you started it, and it isn't his. A zero gone by unseen (the phone off over it) is told at the next start within its 30-minute hold, and past that is marked told without a word. (Held in quiet hours; told whenever the phone's next on.)
- Not backed up; a reset leaves it, as no reset touches `session.json`. (In the backup's part of `session.json`; cleared with *Everything learned*.)
- An Undo of the tick that ended it doesn't restart it. (Restarted where it was.)
- A timer ended by its piece done is cleared and lifts no hold, so a piece ticked late isn't followed by "you missed it". A Start on another task replaces a stopped timer's record, and that task's miss falls back to his ordinary freshness.
- A forgotten timer holds the slot-start nudge only till 30 minutes past its zero. (For as long as it runs, days included.)
- The widget's column drops ▶ first where one icon fits (from a card 64 dp high); its pill says "⏱ till 17:30" and "⏱ over", not minutes left, as the widget redraws every 15 minutes at most. (Both shrunk to fit; minutes left, redrawn each minute.)
- No setting for the 30-minute hold: `FelixRules.TIMER_HOLD_MS`. (Beside `felixGraceMin` in Settings.)
- Declined in review: a hold that lifts after midnight finds its miss under yesterday's date, and it goes unsaid. With quiet hours from 22:30 and the day's hours ending by then, such a hold lifts in them, when he never writes (D-58); the fix, yesterday's records and messages in his look without his once-a-day messages counting yesterday's, is for when quiet hours start after midnight.

## The Day as a calendar (4.5.0 to 4.6.0, 25 Oct)

The questions put to the owner while the Day's change was planned (25 Oct 2026; the plan's Q1 to Q14, here Q71 to Q84), their answers as given, and the choices the plan makes without asking. How it works now is in [spec/day.md](../spec/day.md), [spec/slots-and-calendar.md](../spec/slots-and-calendar.md) §Pins and [spec/planner.md](../spec/planner.md) §Days and times you gave; the decisions are D-114 (4.5.0), D-115 (4.5.1) and D-118 (4.6.0).

### Asked and answered

**Q71. Which Google Calendar view should the Day tab copy?** Offered: the Day view, a time grid (recommended: hours down the left, each slot and event a block as tall as it lasts, side by side where they overlap, a now-line, all-day events as chips under the date, the windows as bands, the "Free, 30 min" rows gone); the Schedule view, a restyled list; the grid with a Schedule toggle.

> Day view: time grid (Recommended)

Applied in 4.6.0: [spec/day.md](../spec/day.md) §The grid.

**Q72. Google changes day by a sideways swipe; on the Day a sideways swipe on a slot is Done or Later today (UI-03). What should a sideways swipe do?** Offered: change day, with a ✓ on each block for Done and Later today on the piece's sheet, UI-03 amended (recommended); keep UI-03's swipes, days changed only from the header; both, by where the swipe starts.

> Keep UI-03's swipes

Applied in 4.6.0: [spec/day.md](../spec/day.md) §The blocks (the swipe column) and §Moving a piece by hand (a swipe and a hold on one block); no swiping between days.

**Q73. How should a day be picked, and the week's load (UI-02) be shown?** Offered: Google's header with a month drop-down carrying each day's load, the strip gone (recommended); keep the 7-day strip above the grid; both.

> Keep the 7-day strip

Applied in 4.6.0: [spec/day.md](../spec/day.md) §Top to bottom (the week strip, and the header's date as Google's, with no month drop-down) and §Moving a piece by hand (the strip's days as drop targets).

**Q74. Where should today's DO NOW card, "won't fit", "No time today", "Done today" and "An instruction about this day…" go?** Offered: the card kept, folding to one line as the grid scrolls, the rest as chips under the date, each opening a sheet (recommended); all above the grid as now, two scrolls one inside the other; the card dropped from the Day, the rest as chips.

> Card kept; the rest as chips (Recommended)

Applied in 4.6.0: [spec/day.md](../spec/day.md) §Top to bottom (the card, and the chips in the day's header).

**Q75. How should blocks be coloured?** Offered: events in their calendar's colour, the app's pieces in its navy (UI-12) (recommended); pieces in the colour of the calendar they're written to; pieces by source, with a legend; everything in the app's blues.

> Events by calendar, slots navy (Recommended)

Applied in 4.6.0: [spec/day.md](../spec/day.md) §The blocks.

**Q76. Holding a slot opens the move sheet today (UI-04). Google moves an event by hold-and-drag. Which for the grid?** Offered: hold and drag up or down to a time, only a task's next piece taking a time (PL-04), another day from the sheet (recommended); hold opens the sheet, as now; drag, or drop on a day of the strip.

> You should both be able to hold and drag up and down in a day, as you described, but also onto the day blocks at the top to move it all onto that day. When moving it within a day, you should be able to move it to anywhere you want as long as it is before the deadline, which means if you move it overlapping onto another existing task, the other tasks should reorganise, and if you move it after any of the later steps within that task, the other steps should reorganise to be afterwards.

Applied in 4.6.0: [spec/day.md](../spec/day.md) §Moving a piece by hand. Two parts of it needed more than the Day could give as the planner stood, so Q79 and Q80 were asked:

- "Anywhere you want": a time was kept only where it was free (PL-04), so a drop over another task's slot, outside the windows or over an event would have jumped to the next free time. Q80.
- "The other steps should reorganise to be afterwards": that is what the planner already does for the task's next piece (D-108: a step follows the steps before it), so moving the next piece after its later steps' slots moves them after it. A later piece couldn't take a time at all (PL-04: one time a task, for its next piece). Q79.
- "The other tasks should reorganise": a pin is laid before today's kept starts already, so the pieces it covers are laid again around it; with Q80's answer, this holds wherever the pin is.

**Q77. Which hours should the grid show?** Offered: all 24 hours as Google does, opening an hour before now, pinch to zoom 40–120 dp an hour, remembered on this phone, the hours outside the windows shaded (recommended); only the day's windows, an hour either side; all 24 hours, no zoom.

> All 24 h, pinch to zoom (Recommended)

Applied in 4.6.0: [spec/day.md](../spec/day.md) §The grid (the zoom kept in the settings).

**Q78. Google makes a new event when empty grid is tapped. What should a tap on empty grid do?** Offered: nothing (recommended); a new task of yours, pinned at that time; the chat, about that time.

> Nothing (Recommended)

Applied in 4.6.0: a tap on empty grid makes nothing.

**Q79. A time is stored for a task's next piece only (PL-04); the steps after it already follow it (D-108). Which pieces should drag to a time?** Offered: the next piece only, a later one dragged onto a day of the strip but not to a time, no stored data changed (recommended); any piece, its task's earlier steps kept before it, a time per piece in the stored changes (`instructions.json`'s version raised, a migration, PL-04 amended); any piece, its earlier steps pushed ahead of it.

> Next piece only (Recommended)

Applied in 4.6.0: [spec/day.md](../spec/day.md) §Moving a piece by hand (a later piece of its task).

**Q80. PL-04 puts a pinned time that isn't free at the next free time after it. When you drop a piece somewhere like that, what should happen?** Offered: kept to the minute, for every time you or Felix give (on the Day, in the chat, by moving its event in Google Calendar), beside an event as Google draws overlaps, unpinned pieces moving out of its way, a pin it lands on moving to the next free time after it, refused only for a time gone, before it can start, or ending after its real deadline (overdue work aside, D-30), no stored data changed, PL-04 amended (recommended); kept to the minute for the Day's drags only, a flag on the stored change; the next free time, as now.

> Exact, for every pin (Recommended)

Applied in 4.5.0, all of it (D-114): [spec/slots-and-calendar.md](../spec/slots-and-calendar.md) §Pins.

#### An extra point: a day for only some tasks

The owner's ask, after the plan was first pushed:

> An extra point: currently Felix can pin items but in order to make them the only events, must decrease available time to their exact duration, which is fragile. Increase Felix's ability so he can make 'only' certain tasks on a day as well as pinning.

Checked against the code before the questions: his changes (`ChangeType`, and the chat prompt's list) put a task on a day (`OnDay`, "placed before everything else", other work still filling the day's time left) or a piece at a time (`AtTime`), and cap a day (`DayLimit`, minutes); nothing says "only these". So the only way was a `DayLimit` matched to the tasks' minutes, which stops matching when those minutes change.

**Q81. When Felix makes a day "only" for some tasks, what happens to those tasks?** Offered: placed there first (as `OnDay`) and nothing else (recommended); the day reserved, the tasks planned as usual.

> Keep the day reserved and tasks planned as usual as long as me telling Felix to put all of a task's work on that day would work.

Applied in 4.5.1: [spec/planner.md](../spec/planner.md) §Days and times you gave (reserved; planned as usual; `OnDay` as well works). Said plainly, as the answer's condition rests on it: `OnDay` makes the day its task's last and places its work there first; a task with more work than the day holds has the rest on the days before, as now. On a reserved day all its time is the listed tasks', so that happens only when they truly don't fit.

**Q82. What else may still be planned on an "only" day?** Offered: nothing else (recommended); Anki's daily cards too; Anki's, and work that would otherwise miss its deadline.

> When Felix makes a day only some tasks, it should all whether to include nothing, anki or anki and anything that would miss it's deadline. Default to nothing.

Read as: Felix should ask which of the three, and allow nothing until told. Applied in 4.5.1: [spec/planner.md](../spec/planner.md) §Days and times you gave (what else may come in) and [spec/felix.md](../spec/felix.md) §What he can do (Felix asks; `alsoAllow` on the change, the prompt's rule and his quick replies).

**Q83. If another task is later put on an "only" day (dragged, the move sheet, Felix's pin, its event moved in Google Calendar), what should happen?** Offered: the newer move wins and it joins (recommended); refused while the "only" stands; the "only" dropped.

> The newer move wins; it joins (Recommended)

Applied in 4.5.1: [spec/planner.md](../spec/planner.md) §Days and times you gave (a later move wins, `InstructionRules.onlyDays`; the join said).

**Q84. If every task an "only" day was for is finished (or gone) before that day, what happens to the day?** Offered: an ordinary day again (recommended); it stays free of work.

> Stays free of work

Applied in 4.5.1: [spec/planner.md](../spec/planner.md) §Days and times you gave (its tasks finished early: a finished task stays in the day's set) and [spec/felix.md](../spec/felix.md) §What he can do (Felix sees them marked done, so he can offer to free the day).

### Decided without asking

Each a routine call the owner may overturn; the alternative is named so the build can switch.

1. **Three releases, after the timer.** The planner's changes (4.5.0, then 4.5.1 with Felix's) are reviewed and checked on the phone before the screen that leans on them (4.6.0); all after 4.4.0, the timer (merged 26 Oct), which touched the Day only in its DO NOW card, which 4.6.0 keeps. Alternative: one release.
2. **The card folds as Material's top bar does with `enterAlways`**: a scroll towards later hours folds it first, one towards earlier hours unfolds it first, a tap on the folded line unfolds it; in landscape it starts folded. Alternative: it unfolds only when the grid is back at its top, which, as the grid opens at the hour before now, would leave it folded most of the time.
3. **Today's line has no label**: a line and a dot, as Google draws it; the hours in the gutter say the time. Alternative: keep "Now 16:12" in the gutter beside the dot.
4. **Every block at least 24 dp tall**, so a short piece can still be read, tapped and swiped; **a ✓ on a piece's block** where it's at least 32 dp tall and 120 dp wide, doing what *Done* on the row does now. Alternative: no ✓, Done by the swipe and the sheet alone, as Google's blocks have no button.
5. **An event's block**: outlined only where the plan counts it free (so a piece laid over it reads right), and a small "?" where the plan needs your answer about it; the verdict's words stay on its sheet. Alternative: the verdict as a pill on every event's block, as the row has it now, where there's room.
6. **Overturned by the owner** (§Changed at the owner's word): it said a pin outside the windows would still count against that day's free time. Alternative: its minutes added to that day's free time, a change to `Planner.capacityOf` and to what the strip's bar says.
7. **Only the Day's and the move sheet's refusals count the piece's minutes** (a piece that wouldn't be over by its real deadline is refused before it's made). Felix's times (`InstructionRules.latePin`) and the calendar's hand moves keep "at or after the deadline", and a time of theirs whose piece can't be over by it is laid as now, at the next start that is, else with no time. Alternative: one rule everywhere, which needs the piece's minutes where `latePin` is checked, from the plan.
8. **A pin outside every window takes the kind of the window nearest it in time**, for its record and for what it teaches the day yields: one in a lesson counts as school's, one late at night as the evening's. Alternative: always the evening's.
9. **The zoom is kept in `settings.json`** (its version raised, nothing migrated), as Q77's answer said "remembered on this phone". Alternative: kept only while the app runs, back to 60 dp at each start, with no store change.
10. **A second first-run hint** for the drag, which nothing on the screen shows: "Hold a piece to move it: drag it to a time, or onto a day above." Alternative: none.
11. **Let go on its own day in the strip, nothing changes**, as that's most likely a drop that missed. Alternative: all of the task pinned to that day (an `OnDay` change), as the move sheet's *All of it, any time that day* does when its own day is picked.
12. **Overturned by the owner** (§Changed at the owner's word): it said events, records and outside breaks don't drag, a break outside being the app's own, put back by the calendar's hand moves already. Events and records still don't: events are the calendar's, records history.
13. **The grid is the wall clock's day**: on the day the clocks go back, the repeated hour is drawn once, two things in it side by side; on the day they go forward, its missing hour is empty. Alternative: a 23- or 25-hour grid on those days, which Google doesn't draw either.
14. **The header's chips' words**: "2 won't fit", "3 with no time", "40 min done", "Instructions · 2", or "Instruction…" where there's none; the sheets they open hold what the list held, as it was worded.
15. **The drag's numbers**: five-minute steps; the grid scrolls when the finger is within 48 dp of its edge, up to 12 dp a frame; settled on the phone.
16. **TalkBack's *Move it* opens the move sheet**, so moving a piece never needs the drag; the sheet keeps the fortnight and a time by the clock.
17. **A new spec page, `docs/spec/day.md`**, as the Day has had none, its rules spread over the requirement ids. Alternative: the rules in `slots-and-calendar.md` and the requirement ids alone.
18. **The "only" day is a release of its own, 4.5.1**, not part of 4.5.0: it changes goldens and schemas (compiled by the API) and raises three stores' versions, so it's reviewed apart from the exact pins. Alternative: inside 4.5.0, one review and one phone session for both.
19. **A date, not every such weekday**: with Q84's answer, a weekday's "only" would keep every such day free of work once its tasks were done, till taken back. Alternative: a weekday too, as `DayLimit` takes one.
20. **Felix makes the day at once, allowing nothing, and asks in the same reply**, as everything he gives is made at once (his prompt says so); the answer changes what it allows. Alternative: he asks first and makes nothing till answered, a turn later.
21. **The instruction reader doesn't ask**: it allows nothing where your words don't say, as it can't hold a conversation. Alternative: it notes the instruction as unclear and asks, as it does what it can't read.
22. **The latest `DayOnly` of a day says what the day allows**, whichever task it names, so Felix's answer to his own question is one change. Alternative: each task's change its own allowance, the most allowing one counting.
23. **"Work that would miss its deadline" takes the day only where no other day before its deadline has room for it**, tried last. Alternative: any work with a real deadline before the only-day's next free day.
24. **A move in Google Calendar that makes a task join an "only" day is said nowhere but in the day's instructions**: it's yours, made there, and the app has no screen open to say it on. Alternative: a notification, as a refused move gets.
25. **The part of a pin over busy time inside the windows uses none of the day's free time either**, as the owner's note says of the part outside the windows: that time is already off the day's free time, and would come off twice. Alternative: only the part outside the windows, as the note's words say.
26. **A day you've limited still counts all of a pinned piece's minutes**, wherever in the day it is: the limit is "at most so much work that day", not a share of its windows. Alternative: the limit counts only the part inside the free time, as the free time does.

### Changed at the owner's word

After the plan was pushed, on decided-without-asking 6:

> Another note on '(6) A piece pinned outside your windows still uses up that day's free time': that should not use up the free time for the time it is outside of the hours.

Applied in 4.5.0 (D-114): [spec/planner.md](../spec/planner.md) §A day's time. Decisions 25 and 26 above are the two calls it left open.

On decided-without-asking 12, on 26 Oct, while the breaks outside's plan was made (§Breaks outside below, Q86: Felix to move them, and you on the grid alone), with the ask:

> you should not be able to drag outside events between days

Applied in 4.6.0: [spec/day.md](../spec/day.md) §The blocks and §Moving a piece by hand (a break drags within its own day, by 4.5.2's `OutsideAt`, `Moves.outsideTo`; on a day of the strip it springs back).

### What the plan couldn't check

The look of Google Calendar's Day view is described from the app as it's commonly seen; this session couldn't open it. Its Material 3 Expressive update put the hours and the day's header in tinted containers in place of faint lines (reported by [9to5Google](https://9to5google.com/?p=683608) and [PhoneArena](https://www.phonearena.com/news/calendar-app-to-get-material-3-redesign_id173057)), and no source found confirmed the details of its now-line or its gestures. So the tints, the line's colour, the gutter's width and the blocks' corners are settled on the phone, side by side with the owner's own Google Calendar (4.6.0's phone session, its first step), not taken from here as fixed.

## Breaks outside (4.4.1 and 4.5.2, 26 Oct)

The owner's ask, 26 Oct 2026: why the "Outside" events in the calendar keep being pushed back while they're under way; then a fix planned, with Felix able to control breaks outside as he controls tasks, and a break never dragged between days. The plan's Q1 to Q3 are here Q85 to Q87. How it works now is in [spec/slots-and-calendar.md](../spec/slots-and-calendar.md) §Outside breaks, §Kept starts and §Hand moves, and [spec/day.md](../spec/day.md) §Moving a piece by hand; the decisions are D-113 (4.4.1) and D-116 (4.5.2).

### The owner's answers (26 Oct)

**A break that's over.** The ask:

> After the break is over, it should do the same as any other task event: either stay or drop out.

and, asked what that meant:

> When I said 'breaks should work how tasks work', I did not mean they can be marked done/missed. After their time as passed, they are completed.

Applied: a break is completed at its end. A completed task's event drops out (a kept piece leaves the plan, `SlotState.kept` lets go of its start, and `SlotCalendarDiff.desired` writes only the plan's slots): a break does the same, gone from the Day and the calendar at its end, and never laid again that day (4.4.1, D-113).

**Q85. Which controls over breaks outside should Felix get?** Offered: move one to a time on its day (recommended); skip one or all on a date (recommended); add one on any day, a school day too; change one day's length.

> Move one to a time, Skip one or all, Add one on any day

Applied in 4.5.2 (D-116). One day's length isn't among them: the setting `outsideBreakMin`, which Felix can already change, sets every day's.

**Q86. And your own control of breaks, beside Felix's?** Offered: a break's event moved in Google Calendar on its day taken as your move, and the 4.6.0 grid's drag within its day (recommended); the grid's drag only, a calendar move still put back, now with a notification; the calendar only; only Felix.

> Grid drag only

Applied in 4.5.2 (a calendar move put back and said) and 4.6.0 (the drag, within its day: §The Day as a calendar above, its §Changed at the owner's word). In the ask: "you should not be able to drag outside events between days".

**Q87. How should this ship, around the timer (4.4.0) and the day grid (4.5.0 to 4.6.0)?** Offered: the fix alone now as 4.4.1, Felix's changes as 4.5.2 after 4.5.1, the drag in 4.6.0 (recommended); Felix's changes inside 4.5.1; all in one, 4.4.1.

> Fix now, Felix later (Recommended)

Applied: 4.4.1, then 4.5.2 between 4.5.1 and 4.6.0, the drag in 4.6.0.

### Decided without asking

Each a routine call the owner may overturn; the alternative is named so the build can switch.

1. **Completed at its end, with no grace.** Alternative: a piece's 10 minutes' grace (`Slotter.GRACE_MS`) before it drops out.
2. **A break still to come isn't held at its kept start**, but laid afresh at each plan, as its own time doesn't move. Alternative: kept like a work piece's start, where it's still free.
3. **A break no plan laid before its time went by is laid from now**, then kept. Alternative: not laid once its time has gone.
4. **Each break named by its own time** ("the 13:00 break", key `outside:780`), not by morning, midday and afternoon; a change naming a time the settings no longer have applies to nothing, and *Your changes* still lists it to take back. Alternative: by its place in the day.
5. **A date, not a weekday**, for Felix's changes, as the day grid's only-days (its decided-without-asking 19, in §The Day as a calendar above). Alternative: a weekday too, as `DayLimit` takes one.
6. **Breaks you or Felix placed are kept as set (D-4)**: never given up for work, and after 18:00 too. Alternative: D-41's 18:00 for every break.
7. **The instruction reader makes them too**, as it shares the change list. Alternative: Felix's alone.
8. **Felix sees today's breaks**, in the chat and in his own messages, and the week's break times in the chat. Alternative: none, and he moves a break by its settings' time unseen.
9. **The calendar's put-back is said from 4.5.2**, when its words can say what to do instead; until then it stays silent, as now. Alternative: said in 4.4.1.

## Busy times in your calendar (4.5.3, 26 Oct)

Asked in the chat while 4.5.3 was built, on your ask that a busy time Felix sets shows in your calendar. How it works is in [spec/slots-and-calendar.md](../spec/slots-and-calendar.md) §Writing the calendar and §Hand moves, and [spec/day.md](../spec/day.md) §The blocks; the decision is D-117.

- **Q88, a busy time's event moved or deleted by hand in Google Calendar**: "Put back, said (Recommended)": put back as it was, and a notification says a busy time is changed by asking Felix, or in Your changes.
- **Q89, how its event is named**: "Felix names it (Recommended)": Felix, or the instruction reader, names it from your words, else it's "Busy".

Chosen without asking while it was built, yours to overturn:

- Its own release, 4.5.3, after 4.5.2 and before the Day as a calendar, which became 4.6.0; the grid draws busy blocks.
- Every busy time in use is written, whoever set it (Felix, the instruction screen, a weekly one), not only Felix's: the ask was about Felix, but a busy time from the instruction screen blocks the same time.
- Shown busy, unlike the slots, which stay free: blocking the time out is the ask.
- A name of 40 characters at most (`InstructionRules.MAX_BUSY_TITLE`): a word or two is what's meant.
- Two busy times with the same start and end on a day are one event, named by the latest that gives a name.
- One changed by hand there (stretched, shortened, renamed) is put back and said too, as one moved or deleted is.

## Asked while 4.5.0 to 4.6.0 were checked (27 Oct)

The first three were asked in a file while you were away, from the reviews of 4.5.0 and 4.6.0 and about their phone checks; the next two at the phone, in 4.6.0's session; the last in 4.6.1's review. How the Day works is in [spec/day.md](../spec/day.md), and how a move in the calendar is read in [spec/slots-and-calendar.md](../spec/slots-and-calendar.md) §Hand moves.

- **Q90, a piece under way and a pin given over it** (4.5.0): A, "leave it" (recommended): a piece moved onto the one you're doing is most likely meant, so the one under way goes to the next free time, as before 4.5.0.
- **Q91, the phone checks that go through Felix's chat** (4.5.1 to 4.6.0): A: they're asked of Felix by the debug `chat-ask`, a paid call each, and every test message, change and memory is taken back after, so your chat and plan are as they were.
- **Q92, the DO NOW card with the phone on its side** (4.6.0): A, "a tap on the line opens the piece's sheet" (recommended, as built): with no room for the card it stays its line, and the sheet has the card's actions.
- **Q93, a second quick drag lost to the calendar's sync** (found in 4.6.0's session): "4.6.1, after the merges (Recommended)". A piece dragged twice within seconds went back to the first drop: Google's sync, finishing the first move's upload, wrote that time back over the app's second, and the writer took it for your hand ("Moved in the calendar"). In 4.6.1 an event found at a time the app itself wrote moments before is the sync's echo, written back to the plan's time, not taken as your move.
- **Q94, the Day with the phone on its side** (found in 4.6.0's session): "Fix in 4.6.1 (Recommended)". On its side, the bar, the strip, the DO NOW line and the day's header fill the screen's height and leave the grid none; in 4.6.1 what's above the grid scrolls away as the grid scrolls, so the grid has the screen.
- **Q95, how what's above the grid comes back on its side** (4.6.1's review): "On any scroll back (Recommended)": it slides back in first as the grid scrolls back, as the DO NOW card unfolds upright, so the strip can be reached from any hour; offered against coming back only once the grid is back at midnight. A piece held in a drag still scrolls the grid to earlier hours first.
