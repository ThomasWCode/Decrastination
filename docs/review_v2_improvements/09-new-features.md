# 9. New features

Things the app doesn't do that would be useful, judged against what it's for: a student who procrastinates, a plan he can't argue with, and a phone that opens when the work is done. Features that would change its nature (a tutor, a social app) are listed at the end as considered and not recommended.

## Summary

| ID | Feature | Priority | Effort | Decision |
|---|---|---|---|---|
| NF-01 | A photo of a worksheet or the board becomes a task | P2 | M | Q-22 |
| NF-02 | A weekly summary you can be sent | P1 | M | Q-24 |
| NF-03 | Anki time counted without pressing Start | P2 | M | Q-23 |
| NF-04 | Exam season: revision plans per subject from the exam dates | P1 | L | Q-27 |
| NF-05 | Recurring work and weekly-minutes goals | P2 | M | Q-27 |
| NF-06 | Returned work with feedback becomes a short task | P2 | S | – |
| NF-07 | Planned work written to the calendar | P3 | M | Q-24 |
| NF-08 | Streaks and a weekly target | P3 | M | Q-25 |

---

## NF-01 A photo of a worksheet or the board becomes a task

**Now.** Work set on paper or on the board (common in sixth form: "finish the sheet for Thursday") exists only in the student's memory or a note to self. An own task takes a form.

**Proposal.** A camera button on the Tasks tab. The photo goes to Claude (the vision path `PhotoChecker` already uses) with a prompt that returns `{title, subject, due, instructions, steps[], confidence}`; the result is shown as the own-task form prefilled, the photo kept with the task (and shown on the sheet), and the student confirms. A board photo with three subjects' homework can return three tasks. Subject from the class map (AI-05).

**Final state.** Paper homework is in the plan in twenty seconds, with steps.

**Caveats.** A photo of handwriting on a board is hard; confidence under 0.6 should prefill less and say so. Cost about 3,300 tokens per image plus output (a few cents). The photo goes to the API; it's the student's own work, but it may show other pupils' names on a board, which is your call.

**Effort.** M. **Decision.** Q-22.

## NF-02 A weekly summary you can be sent

**Now.** You see the app by picking up the phone. The log knows the week: minutes, pieces, on time, days done in full, sessions, blocks, free time, instructions given and tasks moved.

**Proposal.** A weekly summary (text, with the CSV of PL-11 attached if wanted) that the student sends you from the app with one tap on Sunday evening, or that the app sends automatically to an address in Settings by the phone's email app (`ACTION_SENDTO` with the body prefilled; no credentials held). Contents: the numbers above, the review's note and changes, and the instructions he gave Claude ("3 moves: German to Thursday, …").

**Final state.** A Sunday message that replaces "how's the homework going?".

**Caveats.** This is the privacy question for a sixteen-year-old: the summary is about him, and the instructions list is in effect a record of his negotiations with the app. My recommendation is that he sends it, knows what's in it, and the app never sends it behind his back; the audit trail then also builds trust rather than surveillance. The automatic form is listed because you may want it; it should at least be visible in his Settings.

**Effort.** M. **Decision.** Q-24.

## NF-03 Anki time counted without pressing Start

**Now.** A deck piece earns its minutes by a session started in the app; studying in AnkiDroid without one counts for nothing (and the block isn't the issue, AnkiDroid isn't blocked). The accessibility service sees which app is in front (`FocusService.onFront`, `block/FocusService.kt:237`), and the deck's counts are read each sync.

**Proposal.** When AnkiDroid is in front and a deck piece is in today's plan, count the time as a session on that piece, started and ended by the app's presence (with a two-minute grace), credited only if the deck's counts fell during it (DX-11's read). Shown afterwards as "Anki: 18 min counted, 42 cards" and free time earned at the usual rate.

**Caveats.** AnkiDroid in front isn't studying; the counts check is what makes it honest, and it needs a counts read at the start and end (a provider query, cheap). The service already watches the front app, so no new permission. Whether automatic credit fits your view of the incentive is Q-23.

**Effort.** M. **Decision.** Q-23.

## NF-04 Exam season: revision plans per subject from the exam dates

**Now.** Revision exists when a teacher sets it (Teams, a test date in an assignment) or the student makes an own task. Mocks in January and A-levels in May and June are the biggest planning problem the student has, and the app has nothing for them beyond what he types.

**Proposal.** An *Exams* page: exam dates by subject (typed, or Power Planner's exam items where he keeps them), topics per subject (typed, or asked of Claude once from the specification's name: "AQA A-level Physics 7408: list the topics"), and a weekly revision budget per subject. From these the app makes own tasks per topic with Claude's split, spread (PL-01) across the weeks to the exam with the last week kept for past papers, re-spread when the weeks go badly. The chat can be asked "what's my revision looking like?" with the Exams data in its context.

**Final state.** In March the plan already holds Physics revision Tuesdays and Thursdays, and "behind" means something.

**Caveats.** It's the largest item here (a page, a generator, a re-spread rule, Claude's topic lists which must be checked against the real specification), and it's a mode the app doesn't have yet: planning from goals, not from set work. Topic lists from the model need the student to correct them against the spec; the prompt should say which board and code, and the app should show them for editing before any task is made.

**Effort.** L. **Decision.** Q-27.

## NF-05 Recurring work and weekly-minutes goals

**Now.** Own tasks are one-off with a deadline or none. "Two hours a week on the EPQ", "piano 20 minutes a day", "read for the personal statement" have no shape the planner takes.

**Proposal.** Two own-task shapes: a **weekly goal** (minutes a week, spread as soft pieces over the week, the remainder carried to the next day, never behind), and a **recurring task** (every day or named days, minutes, soft). Both earn free time by session as now. Shown on the Plan tab as soft pieces, on Stats as "EPQ: 1 h 40 of 2 h this week".

**Caveats.** Soft work already exists; this is a generator for it. The weekly goal must not become pressure (so it can't lock the phone), which keeps it honest as a goal rather than a nag.

**Effort.** M. **Decision.** Q-27.

## NF-06 Returned work with feedback becomes a short task

**Now.** The Teams widget's rows carry a tab and a due text (`sources/teams/TeamsSource.kt:85`). When a teacher returns work with comments, the assignment is done and disappears; the feedback is never read.

**Proposal.** If the widget's provider can expose "returned" (and the feedback text or a flag), the app makes a 10-minute Admin task "Read the feedback on Statics Prep" due in three days, with Open going to the assignment. Reading feedback is one of the highest-value, lowest-cost study activities and the one students skip.

**Caveats.** Depends on the widget's data; if it has no returned state, this needs a change there first.

**Effort.** S here.

## NF-07 Planned work written to the calendar

**Now.** The app reads the calendar; it doesn't write. A shared family calendar can't show "Physics 17:00–17:45 (planned)".

**Proposal.** With a setting on, today's and tomorrow's pieces written as events to a calendar the student picks (its own "Decrastination" calendar, so they're easy to hide), replaced on each replan, marked free so they don't count as busy time when read back. Useful for a parent who looks at the shared calendar and for the student who lives in it.

**Caveats.** Write permission to the calendar, a loop risk (the app must ignore its own events when reading: it can, by the calendar's id), and churn (a replan rewrites events, which some calendar apps notify about). P3 and your call on visibility (Q-24).

**Effort.** M. **Decision.** Q-24.

## NF-08 Streaks and a weekly target

**Now.** The reward is free time; the record is Stats.

**Proposal.** Light touch only: a streak of days done in full (shown on the Plan tab and widget, reset without drama), a weekly target of minutes the student sets with you ("12 h"), and a line at the week's end. No badges, no points, no leaderboards.

**Caveats.** The evidence on gamification in learning is mixed: Sailer and Homner's meta-analysis (2020, *Educational Psychology Review* 32, 77–112) finds small positive effects on motivation and behaviour that vary a lot with design, and streaks in particular can turn into anxiety and then avoidance when broken. The honest expectation is a small effect for a small cost. Q-25.

**Effort.** M. **Decision.** Q-25.

---

## Considered and not recommended

- **A tutor in the chat** ("explain momentum to me"). It would work (Opus is good at it) and it changes what the app is: a planner the student can't argue with becomes a homework helper, with a cost per question and a new way to spend time in the app rather than on the work. He has claude.ai for that. If you want it, it's a system-prompt change and a cost line, nothing more; I'd keep it out.
- **Social or shared features** (friends, comparison): no.
- **A web or desktop view**: the laptop is where the work happens, but the planner's state lives on the phone; a web view means a server and an account. The weekly summary (NF-02) and the CSV (PL-11) cover what you'd look at.
- **A Wear OS face or a lock-screen widget**: the home-screen widget with Start (UI-05) is the same thing with less code.
