# 9. New features

Things the app doesn't do that would be useful, judged against what it's for: a student who procrastinates, a plan he can't argue with, and a phone that opens when the work is done. Revised after your answers: the calendar write (NF-07) is now central, the photo feature grows a second use (NF-01), the exams and recurring work wait (Q-27), streaks are out (Q-25).

## Summary

| ID | Feature | Priority | Effort | Status |
|---|---|---|---|---|
| NF-01 | A photo of a worksheet or the board becomes a task, or informs one | P1 | M | Changed (Q-22); Implemented (v3 phase 7) |
| NF-02 | A weekly summary in the email itself, sent to your dad, with a Sunday reminder | P1 | M | Changed (Q-24, Q-33) |
| NF-03 | Anki work counted from the deck's counts, without pressing anything | P2 | M | Changed (Q-23, note 1); Implemented (v3 phase 4) |
| NF-04 | Exam season: revision plans per subject from the exam dates | – | L | Deferred (Q-27) |
| NF-05 | Recurring work and weekly-minutes goals | – | M | Deferred (Q-27) |
| NF-06 | Returned work with feedback becomes a short task | P2 | S | –; Implemented here (v3 phase 7); the widget's half waits for captures of returned work |
| NF-07 | Every slot written to your Google Calendar, adjusted live | P1 | M | Changed (note 2); confirmed (Q-39); Implemented (v3 phase 3) |
| NF-08 | Streaks and a weekly target | – | – | Withdrawn (Q-25) |

---

## NF-01 A photo of a worksheet or the board becomes a task, or informs one

**Now.** Work set on paper exists only in your memory or a note to self; an own task takes a form. Teams tasks are estimated from their text alone, and your note says the Physics sheets have been getting two hours when they're thirty minutes.

**Proposal.** A camera button on the Work view. The photo goes to Claude (the vision path `PhotoChecker` uses) with a prompt that returns `{tasks: [{title, subject, due, instructions, steps[], where}], question}`; each returned task is shown as the own-task form prefilled, the photo kept with it. A board with three subjects' homework returns three. And the second use, your answer to Q-22: *Attach to an existing task* on the same screen (or *Add a photo* on a task's sheet) re-reads that task with the photo as context (AI-23) and re-estimates it from what the sheet shows.

**Final state.** Paper homework is in the plan in twenty seconds; a Teams task that says "do the sheet" is planned for the sheet's real length.

**Caveats.** Handwriting on a board is hard; a low confidence prefills less and asks (AI-10). The photo goes to the API and may show other pupils' names on a board; that's your choice each time.

**Effort.** M.

## NF-02 A weekly summary in the email itself, sent to your dad, with a Sunday reminder

**Now.** The log knows the week: minutes, pieces, on time, days done in full, slots kept and missed, blocks, free time, instructions given, Felix's messages and your replies.

**Proposal.** Your answers to Q-24 and Q-33. Settings → Summary: your dad's address, and *Send this week's summary*, which composes the email and hands it to the phone's email app (`ACTION_SENDTO`, no credentials held). On Sunday evening Felix reminds you ("Summary's ready; send it?") with a notification that opens the composed email; nothing is sent by itself. Everything goes in the body, no attachments: the numbers (minutes, pieces, on time, days done, slots kept and missed, free time), the review's note and changes, how the week's nags went, the moves and instructions, as short paragraphs and one small table. Written in your voice ("I kept 14 of 19 slots this week"). The same text is readable in the Reviews page (LR-01).

**Caveats.** An email body is the right form for a reader who won't open a CSV; the CSV (PL-11) stays for you. The voice is yours, so Felix's drafting prompt for it is "write as the student, plainly, no excuses and no spin", and you can edit before sending.

**Effort.** M.

## NF-03 Anki work counted from the deck's counts, without pressing anything

**Now.** A deck piece is done when its counts reach zero at a sync; no minutes are credited unless a session ran. Sessions go.

**Proposal.** Your answer to Q-23: yes. The deck's counts are read at the start and end of the deck piece's slot and at syncs (DX-11); cards cleared while AnkiDroid was in front (the accessibility service knows) count as the piece's progress, the piece is ticked when the counts reach the piece's share, the minutes are the time AnkiDroid was in front within the slot, and free time is earned on that under BL-08's rule. Shown afterwards as "Anki: 18 min, 42 cards".

**Caveats.** Counts fall for other reasons (midnight, a desktop session); only same-day readings with AnkiDroid in front count.

**Effort.** M.

## NF-04 Deferred

An Exams page (exam dates and topics generating spread revision tasks): your answer to Q-27, not yet. The first version's proposal stands in the history for when the mocks come round; PL-01's spreading and NF-05's shapes are what it would build on.

## NF-05 Deferred

Recurring work and weekly-minutes goals: not yet (Q-27).

## NF-06 Returned work with feedback becomes a short task

**Now.** The Teams widget's rows carry a tab and a due text (`sources/teams/TeamsSource.kt:85`). Returned work with comments disappears when done; the feedback is never read.

**Proposal.** If the widget's provider can expose "returned" (and the feedback text or a flag), a 10-minute Admin task "Read the feedback on Statics Prep" due in three days, Open going to the assignment.

**Effort.** S here; depends on the widget.

## NF-07 Every slot written to your Google Calendar, adjusted live

**Now.** The app reads the calendar (`READ_CALENDAR`); it doesn't write. Your note 2: every task gets a time, in Google Calendar, adjusted live.

**Proposal.** `WRITE_CALENDAR`, and the "Decrastination" calendar you created at calendar.google.com (an app-created calendar stays on the phone and doesn't sync, which is why it's yours), picked once in Settings (Q-39). After each replan (PL-12, PL-13) the slots for today and the next six days are written as events (title the piece, description the micro-plan AI-15 and the task's source link, colour the pink, availability free so the read-back never counts them as busy), diffed against what's there by a stable id in the event's `SYNC_DATA` so unchanged slots aren't rewritten; the app's own calendar is excluded from reading (DX-13). Events further out are written weekly. Moving an event in Google Calendar by hand is read back as an `AtTime` change (the event's time differs from the slot's: the planner takes it as pinned, to another day too), refused with a message from Felix if it's past the due date; deleting one of the app's events there isn't a move: the slot is written back at the next replan if its time is still free, and if you've put an event of your own in its place the piece is re-fitted around it, as any event you add that overlaps a slot displaces that slot (your answer to Q-53; PL-13). School slots and the weekend's outside breaks are written too. Confirmed in Q-39.

**Final state.** Your Google Calendar shows the week as it will go: lessons, events, and the work between them, each with its first two minutes in the description.

**Caveats.** Churn: re-slotting writes often; batch per replan and skip unchanged events, or Google Calendar's own notifications become the nag you didn't ask for (turn the app's calendar's notifications off on creation). A calendar on the Google account syncs to every device and to anyone the calendar is shared with; that's your sharing setting, not the app's.

**Effort.** M.

## NF-08 Withdrawn

Streaks and a weekly target: your answer to Q-25, no.

---

## Considered and not recommended

- **A tutor in the chat** ("explain momentum to me"). It would work and it changes what the app is; you have claude.ai for that. If you want it, it's a system-prompt change; I'd keep it out so the chat stays about the plan.
- **Social or shared features**: no.
- **A web or desktop view**: the summary (NF-02), the CSV (PL-11) and the calendar (NF-07) cover what you'd look at elsewhere.
- **A Wear OS face or a lock-screen widget**: the home-screen widget (UI-05) is the same thing with less code.
