# The plan, end to end

The whole design in one place, after five rounds of answers, walked through a school day, a weekend day and a week, each step naming the item that specifies it. Written to find the gaps; the gaps it found were round four in [QUESTIONS.md](QUESTIONS.md) and are all settled at the end.

## 1. What the app knows

- **Work** comes from Teams (assignments), Power Planner (items and the timetable, DX-09), AnkiDroid (decks), your starred emails only (DX-03) and your own tasks (typed, or from a photo, NF-01). Duplicates across sources are linked by a tap (DX-15).
- **Claude reads** each piece of work once (and again when it changes): kind, minutes, steps with soft dates, a test date, where it can be done (anywhere or home), a quoted reason, a question if it can't tell (AI-01 to AI-12, AI-16, AI-23). A failed check gets one repair call, then a salvage, then a drop (AI-01).
- **Time** comes from the device calendar (busy events, to the planner's horizon, DX-14; chosen calendars only, DX-13), the timetable's study periods, break and lunch (PL-15), the school calendar you tell Felix (holidays and half-days, PL-16), your hours (the weekday evening; the weekend's one window, with the yield learning per kind of day what it really delivers, PL-06) and your instructions (busy times, limits, pins).
- **You** are a profile block on every call: subjects and codes, year, the timetable, your lines, facts Felix has remembered and standing instructions about how he behaves (AI-05, AI-21).

## 2. How the plan is made

1. Overdue work and work due today or tomorrow go first, into today (PL-01 step 1).
2. Each day's capacity is its windows less calendar busy time, times the learned yield for its kind of day (PL-06). No fixed maximum.
3. Every other dated task is spread over its window, in proportion to each day's free time, at least one piece a week; revision at least a day apart; single pieces on the day with most room (PL-01). Dates the app or Claude set are soft and re-fitted when missed; only a source's deadline can be overdue (PL-14).
4. Each day's pieces (30 minutes by default) are laid into its windows as slots with 5-minute breaks and a 15-minute one after 90 minutes; on weekends three 20-minute outside breaks go in first and give way to work when the day is short (PL-12). "Home" pieces never land at school.
5. The slots are written to the Decrastination calendar you created on your Google account a week ahead; a hand move there pins; an event you add displaces the slot it overlaps; a deleted slot event comes back only if its time is still free (NF-07, PL-13).
6. The plan is pure and deterministic; property tests hold its invariants (AR-04). Each piece can say why it's where it is (PL-07).

## 3. A school day

- **07:00** Felix's morning message: two sentences on the day's shape, then the list (LR-04). Written a few minutes before (AC-10).
- **10:45–11:15, break.** A slot of German vocab, "anywhere" work. The micro-plan on its card and in the message (AI-15). You do it in AnkiDroid; the deck's counts falling while AnkiDroid is in front count the work (NF-03, DX-11). Under 20 minutes, so the tick earns free time (BL-08). The phone's rules at school are unchanged (BL-09).
- **13:55–14:25, lunch (after eating).** A short admin piece from a starred email. Ticked; earned.
- **15:30, school ends.** Nothing scheduled, so Felix is quiet (LR-03).
- **16:45–17:15, evening.** Statics Prep 1 of 4. At 16:55, no activity: "You put the statics at a quarter to. p.7, Q1 is two minutes. Going?" with quick replies; your reply "give me 15" moves it to 17:10 at once and he says when he'll check (AI-22, AI-24, LR-03). At 17:40 he asks: "Done? Pic of the page and the 10 minutes are yours." You tick and photograph; the slot is kept and earns (BL-08, LR-09). If you tick without a photo it's kept, not earned; a photo later today earns.
- **17:45–18:35, a gap.** Free time earned so far is spendable until the next slot starts, when the block returns (BL-10).
- **18:35–19:05.** A slot missed outright: its end passes with no tick. It's re-slotted after the current slots; if today is full it goes to tomorrow, re-spreading the rest of its task (PL-13), and the phone stays blocked for the rest of the evening because of it; had you moved it yourself, it wouldn't (BL-11). Felix says what happened.
- **19:10.** A new Teams assignment arrives at a sync: enriched (HIGH effort), spread, slotted into the days ahead with the stability rule so nothing else moves without reason (PL-13); the calendar updated.
- **22:00, hours end.** Last slot kept: "Both done. 25 minutes are yours; night." (BL-04). The day record: planned, done, full (LR-09). Quiet hours from 22:30.

## 4. A weekend day

- One long window (08:30–22:30), with the block from 10:00 (BL-02). Three outside breaks laid in first (PL-12). The spread gives Saturday its proportional share of the week's work, which for the first few weekends will be more than a Saturday delivers; the weekend's own yield then brings it down (PL-06, Q-49). A day with nothing planned opens with 60 minutes (BL-07). Felix is silent before the first slot.

## 5. The week

- **Each sync** reads the sources, merges, enriches what's new, replans, re-slots, writes the calendar and the widget (AR-08 trims the no-change case).
- **Sunday evening** Felix reminds you the summary is ready; you send it to your dad from the email app, everything in the body, in your voice (NF-02).
- **The check-in** is a message from Felix with the 1–5 as a reply, the text questions as a conversation (LR-02).
- **The review** runs with last week's review, the day records, the slots kept and the week's messages in its input (AI-18), applies its bounded changes (box, margin, undated minutes, ratio, hours) and tidies the memories, both shown on the Reviews page with undo (LR-01, AI-21).
- **Stats** shows the fortnight by day and by subject, slots kept, estimate accuracy and the calibration's reasons (PL-08, LR-06); CSV export for you (PL-11).

## 6. Blocking, in one paragraph

Blocked apps are blocked during quiet hours' complement when today's slots have pieces still to do; allowed in school hours on weekdays and before the block-from time at weekends; spendable from earned credit in the gaps between today's slots and when they're all kept (BL-01, BL-10); a missed slot keeps the block on tonight, a deliberate move doesn't (BL-11). Credit comes from kept slots (a short piece on its tick, a long one on its photo, capped at the day's planned minutes), source completions and photo checks (BL-08). A day with nothing planned opens with an allowance (BL-07). Sessions, Start buttons and the box experiment are gone (AR-09). Protection, the watchdog, the loosening delay and the parent code are unchanged.

## 7. Felix, in one paragraph

Every message in the app is his, written by Claude ahead of time from the plan, the piece's micro-plan, the profile and memories, and the recent thread (AI-22, AC-10). He speaks only around scheduled work, sets his own next check, has no daily ceiling, stays silent in quiet hours and busy events (LR-03). Your reply applies at once if it alters an existing task, and comes back as a button if it creates something (AI-24). He remembers facts and standing instructions on his own and shows a line when he does (AI-21). He is the chat on every screen (UI-16) and the voice of the morning, the slot's end, the day's end, the check-in and the Sunday reminder.

## 8. Assumptions I've made without asking

Say so if any is wrong; none has a question.

- Calibrated margins (1–3 days) still apply: a task's window ends at its deadline less its margin.
- `DayLimit` instructions still cap a day; `BusyTime` instructions still take time out.
- Undated work keeps its soft week and `softMinPerDay`, now elastic (PL-14).
- The Anki deadline (`ankiDeadlineMin`, 21:30) still bounds where a deck's slot can go.
- Quiet hours (22:30–07:00) are unchanged and silence Felix.
- The Power Planner Effort job and its soft blocks are unchanged.
- The loosening delay (24 h) applies to BL-02, BL-07 and BL-08's credit rule, as to blocker settings now.
- Stats' "Focus" tile becomes "Slots kept"; the activity log keeps old session records as history.
- The widget shows the current or next slot with a tick.
- "Open in Teams/Gmail/Power Planner" on the sheet is unchanged.
- The eval set's Email cases that were Info are retired (AI-02); Felix and the micro-plans get eval sets when there's real data (AR-02).

## 9. The gaps the walkthrough found, and how rounds four and five settled them

- **Free time between slots** (Q-47, BL-10): spendable in the gaps, until the next slot starts.
- **A missed or moved slot and tonight's block** (Q-48, BL-11): a missed slot keeps the block on; a deliberate move lifts it.
- **Weekend load** (Q-49, Q-56): the one long window stays; the yield learns per kind of day (PL-06).
- **What a holiday or half-day changes** (Q-50, PL-16): the weekend's rules; a half-day's evening from the early finish.
- **Memory** (Q-51, AI-21): no cap; a budget; Felix tidies weekly, shown as a diff; you never have to prune.
- **Same-day deadlines with school windows** (Q-52, PL-15): yes; margins unchanged.
- **Hand edits in Google Calendar** (Q-53, NF-07, PL-13): moves pin; an event you add displaces the slot; a deleted slot event comes back only if its time is still free (Q-57).
- **Felix at school** (Q-54, LR-03): he messages around school slots.
- **"Claude" and "Felix"** (Q-55): Felix everywhere except Settings.
