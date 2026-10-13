# Questions for you

Each question is a decision the review can't make for you: it changes what the student experiences, what leaves the phone, or what you spend. For each: the question, the options, my recommendation, and which improvements wait on it. Improvements with no question against them need no decision beyond "do it or not".

Answer in any form (a number per question is enough, with notes where you disagree); I'll then adjust the documents to match.

| Q    | Decision                                                         | Depends on it              | My recommendation                                           | Answer                                                                                                                                                                                                                           |
| ---- | ---------------------------------------------------------------- | -------------------------- | ----------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Q-01 | Salvage a split with one bad date, or prompt-only                | AI-01                      | Salvage                                                     | Salvage but first attempt to reprompt the AI with the caught issue. If still fails, salvage.                                                                                                                                     |
| Q-02 | What goes in the student profile sent with every call            | AI-05, AI-06, AI-21, DX-15 | Subjects, codes, hours, textbook, year; your lines optional | Everything that could be useful, not going beyond the point where context is overclogged. This could be all 3.                                                                                                                   |
| Q-03 | Unsure emails: tag only, or tag and notify                       | AI-10                      | Tag only                                                    | Tag and notification                                                                                                                                                                                                             |
| Q-04 | A hidden-emails section with "It's a task"                       | AI-11, UI-10               | Yes, folded                                                 | Implement a change on how emails are read in: only read in starred emails, but also treat all starred emails as things I have marked as tasks (they cannot be dropped as info).                                                  |
| Q-05 | Soft dates for invented "quick action" deadlines                 | AI-12                      | Yes                                                         | Keep recomendation                                                                                                                                                                                                               |
| Q-06 | Which chat actions Claude may propose                            | AI-14, AI-19               | Tick step, start session, steps; not mark-done              | Keep recomendation but no start session.                                                                                                                                                                                         |
| Q-07 | "Help me start" micro-plans                                      | AI-15                      | Yes                                                         | Yes                                                                                                                                                                                                                              |
| Q-08 | Several photos and partial credit on a photo check               | AI-17                      | Several photos yes; partial credit no                       | Keep recomendation                                                                                                                                                                                                               |
| Q-09 | Remembered facts about the student                               | AI-21                      | Not yet                                                     | Yes, with a higher cap than 10 lines and can be overwritten to be updated so is not frozen once cap is reaeched.                                                                                                                |
| Q-10 | Effort and model per job                                         | AC-02, AC-03               | Effort table yes; Sonnet for Email only if the eval says so | This can be your call. Models and efforts can be different for different jobs but optimise only for intelligence and speed, not cost as that is basically unlimited.                                                             |
| Q-11 | Sender rules                                                     | AC-09, DX-05               | Yes, suggested never automatic                              | No longer important after response to Q4                                                                                                                                                                                         |
| Q-12 | Use the Power Planner timetable                                  | DX-09                      | Later                                                       | Yes, now. Timetable is kept up to date when looking at powerplanner and google calendar combined.                                                                                                                                |
| Q-13 | Link duplicates across sources                                   | DX-15                      | Yes, by tap                                                 | Keep recomendation                                                                                                                                                                                                               |
| Q-14 | Spread revision as soft pieces                                   | PL-01                      | Yes                                                         | Keep recomendation                                                                                                                                                                                                               |
| Q-15 | An evening-start preference                                      | PL-03                      | No                                                          | No                                                                                                                                                                                                                               |
| Q-16 | A most-per-day, and a learned yield                              | PL-05, PL-06               | Day max yes (180/300); yield later                          | Day max should just be the amount of time available in that day: anything not due today or tomorrow should be spread evenly out across days until the deadline, not piled onto first available day. Implement the learned yield. |
| Q-17 | Swipe to start/tick, and move-by-hand                            | UI-03, UI-04               | Swipe yes; move with limits                                 | Swipe and move yes. No limits on number of moves per day but cannot move items when the move would clash with the due date.                                                                                                      |
| Q-18 | Loosen the block: today-only pressure, weekend start, empty days | BL-01, BL-02, BL-07        | All three                                                   | Keep recomendation                                                                                                                                                                                                               |
| Q-19 | Bank free time; study use of blocked sites                       | BL-05, BL-06               | Bank no; study use no                                       | Keep recomendation                                                                                                                                                                                                               |
| Q-20 | An evening nudge                                                 | LR-03                      | Try it for a month                                          | Yes                                                                                                                                                                                                                              |
| Q-21 | A Claude line in the briefing                                    | LR-04                      | Try it for a month                                          | Yes                                                                                                                                                                                                                              |
| Q-22 | Photo of a worksheet becomes a task                              | NF-01                      | Yes                                                         | Yes, including being able context to an existing task (e.g. recent physics tasks have been allocated 2h when if it saw the worksheet it would have set 30min).                                                                   |
| Q-23 | Anki time counted automatically                                  | NF-03                      | Yes, counts-checked                                         | Yes                                                                                                                                                                                                                              |
| Q-24 | What you see: weekly summary, calendar write                     | NF-02, NF-07               | Summary he sends; no calendar write                         | Option to generate and send summary to dad's email.                                                                                                                                                                              |
| Q-25 | Streaks and a weekly target                                      | NF-08                      | A streak only, no target                                    | No                                                                                                                                                                                                                               |
| Q-26 | A paid eval task                                                 | AR-02                      | Yes, capped at $2 a run                                     | Yes                                                                                                                                                                                                                              |
| Q-27 | Exams page; recurring work and weekly goals                      | NF-04, NF-05               | Both, exams first, before January mocks                     | Not yet                                                                                                                                                                                                                          |

---

**Extra notes from me:**

- Remove the sessions feature as it is not used. I read the job from the app and do it; I don't click start and end for a session. I use the app to figure out what to do, not to do it.
- Assign every task a specific time in my day. Add this to my google calendar. Adjust live. Looking at my powerplanner calander/timetable, you will see that I have break, lunch, and study periods. On days that they are not blocked out by another item (either in powerplanner or google calandar), allow scheduling tasks in those slots. That will involve relaxing previous school-time restrictions.
- Ensure that tasks are allocated by the time left in a day, while overdue items and items due today/tomorrow are not affected. Spread items out across the days before the deadline, ensuring they all get done. Ensure that later deadline tasks are not infinitely pushed back until they suddenly come around and I have loads to do.
- Instead of tasks going overdue, make the app reorganise and restructure every time to keep fitting everything in allocated time. This only applies to subtasks in a task and items where the app set a deadline instead of the deadline being sourced. Other items should still be reorganised and restructured if overdue, but should still display as overdue.
- Make the whole app have the colour scheme of the logo. My phone has a dark theme so still honour that but adjust some items and areas with the new colour scheme.
- Make substantial improvement to feel like I am being nagged by a person, not an app. Notifications should include claude generated nags and I can respond. A notification may ask why I haven't started yet and I can respond and we can have a conversation about it. Add the chat button in the bottom right to all screens. Other improvements to this goal too.
- Add an easy reset for the stats and app learned notes.
- Clean up the UI again: one-time items and items of less importance likes setup, blocking and protection and settings should be lower in the menu and bundled under one. The difference between Plan, Tasks and Calendar is not clear; it seems like there are three screens with much overlap but some differences. Merge these into one or two screens and make them more obvious.

---

## Q-01 Salvage a split with one bad date, or prompt-only

**Context.** A step dated after the task's deadline drops Claude's whole split (`Prompts.kt:321`); the German assessment lost nine good steps to one. AI-01 proposes telling the model the rule *and* keeping the split with the offending step's dates removed.

**Options.**

1. Prompt only: the rule in the system prompt and the field description; the check stays strict.
2. Prompt and salvage: as 1, plus the check keeps the split and drops only the bad step's dates, telling the student.

**Recommendation.** 2. The strict drop was built when the model's plans were untrustworthy as a whole; now the failures are single fields. The unrepairable cases (unreadable dates, no title, sums that don't add up) still drop.

**Depends.** AI-01.

## Q-02 What goes in the student profile sent with every call

**Context.** AI-05 proposes a short stable block (under 600 characters) sent with every job and the chat: subjects and class codes, year group, hours, textbook, and optionally lines you write.

**Options.**

1. Derived facts only (subjects, codes, year, hours, textbook): nothing typed, nothing personal beyond what the emails already carry.
2. Derived facts plus your lines ("finds German vocab slow", "football Tuesdays").
3. Also the student's remembered facts (Q-09).

**Recommendation.** 1 now, 2 if a month's readings show the model missing things only you know. Each line is sent on every call to Anthropic; the privacy cost is small against the emails already sent, but it should be a choice.

**Depends.** AI-05, AI-06 (either way), AI-21, DX-15 (uses the class map).

## Q-03 Unsure emails: tag only, or tag and notify

**Context.** AI-10 lets the model say it isn't sure and ask one question. The email stays visible as Admin with a "Check" tag.

**Options.**

1. Tag only; the student meets it on the list.
2. Tag and a notification per unsure email with the question and two answers.

**Recommendation.** 1. A notification per uncertainty is a nag; the list is where he'll see it, and the morning briefing can count them ("2 to check").

**Depends.** AI-10.

## Q-04 A hidden-emails section with "It's a task"

**Context.** Emails Claude reads as Info are hidden everywhere; there's no way back (AI-11). The 1.3.0 decision was to hide them; this adds a folded list at the foot of Tasks with a one-tap return.

**Options.**

1. Yes, folded by default with a count.
2. No list; only a notification when Claude hides something (noisy).
3. Neither; rely on starring in Gmail (DX-03) to bring an email back.

**Recommendation.** 1, with 3 as well (they're independent).

**Depends.** AI-11, UI-10; AI-10's unsure emails could live there too.

## Q-05 Soft dates for invented "quick action" deadlines

**Context.** The Email prompt tells the model to date quick actions within two weeks; the app shows that date as hard ("Overdue"). AI-12 marks such dates soft.

**Options.**

1. Soft dates: ordering keeps them; "Overdue" doesn't apply; never ranked above a real deadline.
2. Drop the two-week rule: undated blocks fall under the task's soft week.

**Recommendation.** 1.

**Depends.** AI-12.

## Q-06 Which chat actions Claude may propose

**Context.** AI-14 lets the chat propose actions beyond new tasks and instructions; AI-19 adds a Steps change for own tasks.

**Options** (choose any): tick a step; start a session; replace an own task's steps; open a task; mark an own task done; mark a source task done.

**Recommendation.** Tick, start, steps, open. Not mark-done for source tasks (your Q27: the source says), and I'd leave own-task done out too, since a tap on the sheet is as quick and the chat shouldn't be where things get finished.

**Depends.** AI-14, AI-19.

## Q-07 "Help me start" micro-plans

**Context.** AI-15: one call per piece for a first action under two minutes, two next actions and a "done when". A few cents per piece.

**Options.**

1. Yes, on the DO NOW card and the session view.
2. No.

**Recommendation.** 1. It's the most direct thing in this review aimed at the actual problem.

**Depends.** AI-15, UI-14.

## Q-08 Several photos and partial credit on a photo check

**Context.** AI-17.

**Options** (independent): up to three photos per check; a fraction returned and credited as minutes.

**Recommendation.** Photos yes (multi-page work is common); partial credit no (easy to game, and the tick stays binary anyway).

**Depends.** AI-17.

## Q-09 Remembered facts about the student

**Context.** AI-21: a `remember` card type; facts join the profile block.

**Options.**

1. Yes, capped at ten lines, listed in Settings.
2. Not yet.

**Recommendation.** 2 until the profile (Q-02) has settled; then revisit.

**Depends.** AI-21.

## Q-10 Effort and model per job

**Context.** AC-02 (effort per job) and AC-03 (Sonnet 5.5 for email triage, decided by the eval set). Your v1 position was "cheaper models only with an eval set"; the set exists but is small.

**Options.**

1. Keep Opus HIGH everywhere (simple; costs more; slower chat).
2. Effort table (MEDIUM for Effort, Photo, Chat) with Opus everywhere.
3. 2 plus Sonnet 5.5 for Email if the eval score matches.

**Recommendation.** 2 now; 3 after the eval run (AR-02) shows the score, not before.

**Depends.** AC-02, AC-03, AC-08.

## Q-11 Sender rules

**Context.** AC-09 and DX-05: per-sender "always Info" rules, suggested after three Not-a-task marks or set in the chat, applied without a model call.

**Options.**

1. Yes, suggested and confirmed by the student, listed and deletable.
2. Yes, automatic after three marks.
3. No.

**Recommendation.** 1. Automatic rules will hide a school office's form one day.

**Depends.** AC-09, DX-05.

## Q-12 Use the Power Planner timetable

**Context.** DX-09: lesson order for same-day work, "tomorrow's lessons" in the briefing, school-end from the timetable.

**Options.** Yes now; later; no.

**Recommendation.** Later. It depends on the student keeping the timetable current in Power Planner, which the data will show.

**Depends.** DX-09.

## Q-13 Link duplicates across sources

**Context.** DX-15: the same work from Teams and Power Planner, or an email about it, linked by one tap so it's planned once.

**Options.**

1. Yes, suggested by a match, linked by tap.
2. No; the student archives or marks the extra one Not a task.

**Recommendation.** 1 if it's actually happening (check the log for same-titled open tasks first); 2 if it's rare.

**Depends.** DX-15.

## Q-14 Spread revision as soft pieces

**Context.** PL-01: revision for a test placed across the days before it as soft work until its backwards-placed day, instead of as late as possible.

**Options.**

1. Yes, default on, per-task override.
2. Yes, off by default.
3. No.

**Recommendation.** 1. It's the one planner change with evidence behind it.

**Depends.** PL-01, NF-04.

## Q-15 An evening-start preference

**Context.** PL-03: quickest / hardest / Anki first as the within-day tie-break.

**Recommendation.** No. The DO NOW card already decides; the setting would be one more thing to argue about.

**Depends.** PL-03.

## Q-16 A most-per-day, and a learned yield

**Context.** PL-05 (a weekday and weekend maximum, suggested 180 and 300 minutes, as default day caps) and PL-06 (plan with window × a learned done/planned ratio).

**Options.**

1. Day max only.
2. Day max and yield.
3. Neither; keep `capacityAdvice`'s sentence.

**Recommendation.** 1 now, with the defaults yours; 2 after a term of day records exists to judge the yield on.

**Depends.** PL-05, PL-06.

## Q-17 Swipe to start/tick, and move-by-hand

**Context.** UI-03 (swipe right to start, left to tick a step) and UI-04 (drag a piece to a day, a Tomorrow button). Moving is the one that can be used to avoid.

**Options.**

1. Swipe yes, move no (moves stay with Claude, where they're logged and reasoned).
2. Swipe yes, move yes with limits (not for work due tomorrow or earlier; two a day; logged).
3. Both no.

**Recommendation.** 2. The limits are the point; a move through Claude is also logged, so the audit is the same.

**Depends.** UI-03, UI-04, PL-04.

## Q-18 Loosen the block: today-only pressure, weekend start, empty days

**Context.** BL-01 (free time spendable once today's bucket is done, not today and tomorrow), BL-02 (a weekend "block from" time), BL-07 (an allowance on a day with nothing planned). Each is a loosening; each goes through the 24-hour delay.

**Options** (independent):

- BL-01: yes / no.
- BL-02: yes, at HH:MM / no.
- BL-07: yes, N minutes / no.

**Recommendation.** All three: BL-01 is the one I'd argue for hardest, because the reward the app promises is currently almost never collectable in term time, and a reward that can't be collected teaches that the system is unfair rather than that work pays. 10:00 and 60 minutes for the others.

**Depends.** BL-01, BL-02, BL-07, BL-04 (its wording).

## Q-19 Bank free time; study use of blocked sites

**Context.** BL-05 (carry unspent free time to the next day, capped) and BL-06 (let a session's named video through the browser).

**Recommendation.** No to both. The bank is a weak lever once BL-01 is in; the study exception is gameable and the laptop exists.

**Depends.** BL-05, BL-06.

## Q-20 An evening nudge

**Context.** LR-03: one conditional notification 90 minutes before the hours end, with Start.

**Recommendation.** Try it for a month and look at session starts after 20:30 in the log; keep it if they rise.

**Depends.** LR-03.

## Q-21 A Claude line in the briefing

**Context.** LR-04: one model call a morning for one sentence; a few cents a day.

**Recommendation.** Try it for a month, same measure as Q-20. Drop it if he doesn't read the briefing anyway.

**Depends.** LR-04.

## Q-22 Photo of a worksheet becomes a task

**Context.** NF-01: a camera button; Claude reads the sheet or the board into a prefilled own task.

**Recommendation.** Yes. Paper homework is the gap the sources can't close.

**Depends.** NF-01.

## Q-23 Anki time counted automatically

**Context.** NF-03: time in AnkiDroid on a planned deck counted as a session, credited only if the deck's counts fell.

**Recommendation.** Yes with the counts check; without it, no.

**Depends.** NF-03, DX-11.

## Q-24 What you see: weekly summary, calendar write

**Context.** NF-02 (a weekly summary he sends you, or the app sends) and NF-07 (planned pieces written to a shared calendar).

**Options.**

1. Summary he sends, nothing automatic; no calendar write.
2. Summary sent automatically to your address, visible to him in Settings.
3. 2 plus calendar write.

**Recommendation.** 1. He's sixteen; a system he knows reports on him only by his hand is one he'll keep using. If the summaries stop coming, that's information too.

**Depends.** NF-02, NF-07, PL-11.

## Q-25 Streaks and a weekly target

**Context.** NF-08.

**Recommendation.** A streak of full days on the widget, nothing else. No target, no badges.

**Depends.** NF-08.

## Q-26 A paid eval task

**Context.** AR-02: `./gradlew evalScore` calling the API with a key from the environment, capped per run.

**Recommendation.** Yes, capped at $2 a run, never in CI.

**Depends.** AR-02, and Q-10's experiment.

## Q-27 Exams page; recurring work and weekly goals

**Context.** NF-04 (exam dates and topics generate spread revision tasks) and NF-05 (weekly-minutes goals and recurring own tasks).

**Options.**

1. Both; exams first.
2. NF-05 only (small).
3. Neither.

**Recommendation.** 1, timed so the Exams page exists before the January mocks; NF-05 can ride on the same own-task changes.

**Depends.** NF-04, NF-05, PL-01.

---

## Answer sheet

```
Q-01:   Q-02:   Q-03:   Q-04:   Q-05:   Q-06:   Q-07:   Q-08:   Q-09:
Q-10:   Q-11:   Q-12:   Q-13:   Q-14:   Q-15:   Q-16:   Q-17:   Q-18:
Q-19:   Q-20:   Q-21:   Q-22:   Q-23:   Q-24:   Q-25:   Q-26:   Q-27:
```
