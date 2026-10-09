# What needs you

The v1 build ran while you were away (from 8 Oct 2026, evening). Anything that needed you, a decision or a hand on the phone, waited here instead of stopping the build. Newest phase last; each item says what's waiting and what happens meanwhile.

## In short

**Version 1.3.0 is on your phone (9 Oct, evening), armed, with Claude on**: the four sources sync, the plan and the widget are live, blocking starts at 16:45 on school days, and the morning briefing comes at 07:00. Done with you that evening:

- **Protection armed with your dad**: the device admin, and his authenticator app's code. Changes that loosen blocking now wait 24 hours unless his code applies them.
- **Claude switched on**: its first pass read your inbox and assignments (40 calls, $0.60). Your dad's work-experience email became 18 blocks over five months, Imperial STEM Potential among them (check eligibility by 30 Oct, apply from then), so it's tracked now. There's no monthly cap: the account is prepaid, and the app alerts you when its credit runs out.
- **Tried on the phone**:
  - a blocked app in split screen and in a pop-up window (covered);
  - the guard on the app's accessibility page, its App info, the uninstall prompt, and both reset-settings pages (backed out of, the reset pages after about a second);
  - a backup restored (no change, as it should be);
  - the morning briefing;
  - answers to two calendar questions;
  - both new alerts, the dropped plan and the key failure, through a stand-in on the PC.

Still waiting for you:

1. **The photo check**: next time the block screen's next piece is written homework, tap *Photo check* and photograph it (a few cents; Phase 5).
2. **Spending earned free time**: needs a day with nothing due today or tomorrow (Phase 3; unit-tested).
3. **Two more calendar questions** (Apple day, and R in Amsterdam): asked once they're within a week.
4. **The repository review** (`docs/review`, #10): not worked through yet, as you asked; some of it is your call (P1-001, the committed signing key, is deliberate).

## Phase 1: sources, storage, sync

- **Nothing blocking.** All four sources read on the phone on 8 Oct: Teams (11 assignments), Power Planner (2 items), Gmail (28 conversations) and AnkiDroid (the quota and 3 linked decks). Credentials went in with `scripts/load_credentials.py`.
- **Your inbox is all tasks now** (as you asked: everything in the inbox is outstanding; **changed on 9 Oct**: only emails with something to do are tasks, see 1.3.0). Your own notes are 10 minutes each, other people's emails 15, notifications 2 (GitHub's emails about these PRs are notifications too). Each email with no deadline gets a soft one a week after the app first sees it; since the 28 conversations already there were all first seen on 8 Oct, the planner caps undated work at an hour a day (Phase 2), so they spread over 9–14 Oct instead of all falling on one evening. The cap is a setting.
- **Three German vocabulary decks are overdue tasks** (1.2, 2.2, 2.3: 169 cards between them), because the assignments that name them (*Familie und Ehe*, *Gefahren in den sozialen Netzwerken*) are past due and not handed in. Each disappears when its assignment is handed in.

## Phase 2: the planner and the widget

- **The Next task widget is on your 4th home page, at 4×3.** I tried it there at 2×1, 4×1, 2×2, 4×2, 4×3 and 4×5; resize it as you like. Tapping it opens the task; ↻ reads every source and asks the Teams widget to sync Teams (it takes over the screen for a minute, as its own ↻ does).
- **Opening an email can't land on that email.** The Gmail app opens its inbox for any link; the app opens Gmail and shows the subject and sender to look for.
- **Today's plan is heavy**: 14 things, about 7 hours, against 5 h 15 min free this evening, mostly the five overdue assignments and their German vocabulary decks. That's the honest picture, and blocking (Phase 3) will be strict until it clears. Handing in what's already done is the quickest way to shrink it.

## Phase 3: the blocker and protection

- **Blocking is on, for real, from 16:45 today (Friday 9 Oct).** With 14 things due today, every blocked app (YouTube, TikTok, Netflix, iPlayer, Twitch, Webtoon, Snapchat, Bluesky, Discord, LinkedIn, and Firefox and Tor) shows the block screen until 22:30, and so do their sites in Chrome and Brave. It stays strict while anything is planned for today or tomorrow, so handing work in is what lifts it: *Check it's done* on the block screen asks the source at once. If something goes wrong, the focus service can be switched off in Settings → Accessibility → Installed apps, since protection isn't armed.
- **Protection is built but not armed, as you decided.** When you're ready, Setup → *Blocking and protection* → *Arm protection*. It asks for the device admin (a system dialog), then shows a QR code for **your dad** to scan into an authenticator app (Google or Microsoft Authenticator). He reads you the code it shows to confirm, or you skip that step. After that, a code is for him to type in himself, on the screen that says what it's for: an authenticator's code works for whatever it's typed into, so one read out for one change could be used for another. Without his code there's no override, only the 24-hour wait. The self-repair permission is already granted.
- **Automatic Teams syncs start today**: on your first unlock after 16:45, then every three hours until 22:30. Each one shows the 10-second banner first. One test sync of Teams ran at 01:02 (allowed: you said I could sync Teams at any hour).
- **Tried on the phone on 9 Oct, evening, with you**: a blocked app in split screen and in a pop-up window (both covered, logged as blocks), and, once armed, the guard on the app's accessibility page, its App info, the uninstall prompt and both reset-settings pages (the guessed button text, *Reset settings*, is right: it backs out after about a second, once the page has drawn its button). **Not tried**: spending earned free time, which needs a day with nothing due (unit-tested).
- **Media volume**: I muted media while testing at night and set it back to 5, where it was.

## Phase 4: Claude and the Settings screen

- **Claude is built but off, as you decided: nothing has been sent to it, and nothing will be until you turn it on.** To try it: Setup → *Claude* → *Enter key* (an Anthropic API key, `sk-ant-…`), then Settings → *Use Claude*. Once protection is armed, both the switch and a new key wait 24 hours, as any change that can lift pressure does. It then goes over your inbox and assignments once (roughly 50 calls: about £1–2 at list price) and then only what's new. There's no monthly cap (as you asked on 9 Oct: the account is prepaid with no card, so calls stop when its credit runs out, and the app alerts you then); you can set one in Settings, in dollars. Setup shows the month's spend.
- **Without it, the rules split assignments that list their parts** into those parts as steps: on the phone, three of eleven (the two German homeworks with numbered or dashed lists, and the assessment preparation).
- **Settings** (the ⋮ menu → *Settings*) edits the hours, Teams' automatic syncs, the planning numbers, Anki, the blocked sites, browsers and apps (a switch per installed app), and Claude. Once protection is armed, a change that loosens blocking waits 24 hours and is listed there until it applies. The screen shows what you've asked for, waiting changes included; setting one back and saving cancels it.

## Phase 5: learning, the briefing and the calendar

- **The morning briefing starts today**, Friday 9 Oct, at 07:00: today's plan in a notification; unlock within 90 minutes and the Teams sync is offered. At weekends it's 08:30. Both times are in Settings.
- **The calendar now counts as busy time.** Four of your events in the next fortnight are all-day or four hours or more, so the app can't tell how much of the day they take. Each is asked about once, in a notification in the week before it (from 07:00, never in quiet hours): *Free*, *Busy*, or *A few hours*. Your answer holds for every event of that name. Holidays, birthdays and travel count as free; anything shorter than four hours takes its slot.
- **After each finished homework or revision**, a notification asks how it went (*harder*, *as expected*, *easier*, or a line). Your answers adjust its estimates for that kind and class.
- **On Sunday at 19:30**, a reminder opens the five-question check-in, and the week's review follows at 21:00 (or as soon as you save the answers). Until Claude is on, the review is the rules': what the app learned from the week, and, after ten days of plans, whether your hours are more than your evenings hold. It changes the planner's numbers only within fixed bounds, and never blocking, protection or your hours.
- **Waiting for Claude** (you decided on no paid calls yet): the model's weekly note and the photo check of written work. Both appear by themselves once Claude is on and its key is in use.
- **Tried on the phone on 9 Oct, evening**: the briefing notification (posted on demand: "Today: 34 things, 14 h 49 min", led by what's due today), and answers to two calendar questions (the UG Open Day takes all of Saturday; the van hire, a few hours of Sunday): both kept, and the plan counts them. **Not tried**: the photo check, now possible with Claude on. The calendar's judgement of your real events, the check-in screen and a review by the rules were tried earlier.

## Phase 6: stats, backup, version 1.0.0

- **A Stats tab** (after Tasks): the last fortnight's finished work (and how much of it with a deadline was done by it), your focus sessions, how often the blocker stopped you and on what, and what protection found; day by day below. Then today's free time, what the app has learned about your estimates in plain words, and Claude's month. Most of it fills in as you use the app; tonight it shows the testing.
- **Back up and Restore** (Setup): *Export* saves the settings, the activity log, what the app has learned and your calendar answers to a file you choose (it never includes passwords or keys, which stay encrypted on the phone). *Import* reads one back. The settings go through the same waiting as any change once protection is armed, and this phone's own protection and Claude key stay as they are; once armed, the log and what the app learned aren't restored, so an edited file can't teach the planner to plan less.
- **Tried on the phone**: the Stats tab, and a backup saved to Downloads through Android's file picker (12 KB; the format has no place for passwords or keys; I deleted it afterwards). Restoring one was tried on 9 Oct, evening, with you, before arming: a backup exported and imported again changed nothing, as it should ("Restored the settings, 25 completions and 2 sessions, what the app had learned, and your calendar answers").

## 1.1.0: blocks (Q12)

- **What it does**: once Claude is on, it can split any task into blocks: an email, a Power Planner item or a Teams assignment, each block with its own dates where it has them. Your dad's work-experience email becomes its applications, each planned between the day it opens and its own deadline. Archive the email and it stays on your list (marked as a follow-up) until its dated blocks are done; tick a block off in Tasks, or run a focus session on it.
- **Tried without the API**: the prompt on that email by Opus through the subscription, in the JVM tests, and against the planner and the merge in tests. Not on the phone: it wasn't connected.
- **Waiting for you**: Claude switched on; and the phone, to install it (now as 1.1.1, below).

## 1.1.1: long emails read to their end

- **What was wrong**: the app kept only the first 4 000 characters of each email, so Claude would have read only the first half of your dad's work-experience email (8 500 characters). Its timeline was in the half cut off: Imperial STEM Potential (opens 30 Oct), the Rosalind Franklin Institute and Imperial Work Experience (January). A side check caught it: the first Opus tries for 1.1.0 read that cut copy, and none named STEM Potential.
- **And the whole email's plan was more than 1.1.0 would take**: read whole, Opus planned it as 17 to 20 blocks, 12 to 14 hours over five months. 1.1.0 trusted at most 10 hours and 20 blocks from one email, and past either it drops every block, and the estimate with them.
- **What it does now**: it keeps up to 20 000 characters of each email, and Claude reads up to 16 000 (and is told when there's more). Every email already stored is read again once, at the next sync: 5 of the 31 in your inbox on 9 Oct had been cut short at 4 000 characters, and the old reads could stop sooner on some. An email's or a planner item's blocks can come to 20 hours and 30 blocks (the prompt names the 30); an assignment's steps, and an estimate without blocks, keep the 10-hour limit.
- **Tried without the API**: the app's own prompt for the whole email, four times by Opus through the subscription, each answer put through the app's checks and its planner. All four named STEM Potential (from 30 Oct; planned for 12 Nov), the Rosalind Franklin Institute and Imperial Work Experience (January), alongside NPL, Diamond, RAL, UKAEA, Rolls-Royce and the Beamline for Schools proposal; 1.1.0 would have dropped all four, and 1.1.1 takes all four. In the plan, the quick actions fall in the next three weeks and each application in its own window; whatever is due after the plan's 90-day reach sits on its last day for now and moves into its window as its deadline comes within reach.
- **Waiting for you**: the phone, to install it; Claude switched on for the blocks.

## 1.2.0: alerts

- **A dropped plan is said**: when Claude's steps or blocks for a task aren't trusted (out of range, or not adding up), the task says so in Tasks ("Claude's plan was dropped (why): planned as one piece"), and a notification tells you once. Its estimate is planned whole meanwhile, so it's worth a look.
- **A key that stops working is alerted**: Claude's key rejected (revoked, deleted, expired or mistyped), not allowed to use the model, or the account out of credit. A notification once, a banner on the Plan, and Setup saying what to do; the rules stand in, and it's tried again an hour after each failed try. A new key in Setup, or a call that works, clears it; one that couldn't be shown (notifications off) is shown once they're on, when the app next comes to the front.
- **No monthly cap by default, and in dollars**: you meant $200, not £200, and with no card on the account it simply stops when the credit runs out, which the alert above tells you. A cap can still be set in Settings (blank for none); raising or lifting one waits 24 hours once armed. The pounds-per-dollar setting is gone: spend is shown in dollars, as Anthropic bills.
- **Work due today or tomorrow comes before overdue work** (your call, 9 Oct): it's placed first, taking the evening's time, and comes first in the Plan, on the widget and in the briefing; overdue work follows it, oldest first. An email's overdue block still comes before its own due-soon one, so its order holds.
- **Both arrive on a new notification channel, *Claude***, which you can turn down in Android's settings like the others.
- **Tried**: in the JVM tests (every reason a plan is dropped, and the key failures as the SDK raises them from a stand-in's 401, 403 and out-of-credit 400). For trying them on the phone without a paid call, the debug commands `ai-endpoint` (the model's calls to a stand-in on the PC, till cleared or the app restarts) and `enrich-task` (one task asked about again now).

## 1.3.0: just emails aren't tasks

- **Your call, 9 Oct**: only an email with something to do is a task. One that only needs reading (notifications, LinkedIn, newsletters, marketing, an empty note) or that tells of an event (an open day's information, a ticket) isn't: it's off the plan, the widget, the briefing and blocking, it's not in the Tasks list, and archiving it earns no free time and asks nothing. That's 13 of the 25 emails in your inbox on 9 Oct (10 to read, 3 about events).
- **Kept, unseen**: Claude (or, with it off, the rules) still reads each email to tell which it is, and a reply that asks you something brings one back as a task. One Claude misjudges as only to read is only seen in Gmail, as you chose.

## 1.4.0: a cleaner look

- **Your ask, 9 Oct**: settings and setup out of the way, screens that skim, nothing taken away, more contrast between sections and items.
- **The menu** (⋮, top right) holds *Setup*, *Settings*, *Blocking and protection* and *This week*; the tabs are Plan, Tasks and Stats. A dot on ⋮ (and by *Setup*) means something there needs you: a source failing to read, Claude's key, or protection. Alerts about those open Setup over the plan, so Back comes to the plan.
- **Tiles**: each item is a tile a shade lighter than the background, with a sliver of background between items and a bold coloured heading over each section. Urgent work has a red bar down its edge; durations are pills.
- **Folded, not gone**:
  - **Plan**: today and tomorrow are open, later days folded to a heading that still gives the day's load and pieces. A day shows 8 pieces, then *Show N more*.
  - **Tasks**: each source folds under its heading (what it last read, and any failure, stay on the heading). A task shows its title, when it's due (red when late) and one line of class or sender. *Claude's plan dropped*, *Hidden till…* and *Archived* show as tags. A tap opens the rest: the full reasons, the next step, the steps and the source's own text. *Finished or missed lately* starts folded.
  - **Stats**: six tiles: finished, focus, blocked, protection, free time left, and Claude's month. Day by day shows the last week, with the older week a tap away. The sentences the tiles come from are under *In full*.
- **Tried on the phone, 9 Oct**: each tab, the menu, Setup through an alert's route (it opened over the plan), a long day's *Show 5 more*, and a task opened. Light mode wasn't looked at: the phone's in dark mode.

## 1.5.0: instructions

- **Your ask, 9 Oct**: instructions in your own words, read by Claude. Where to write them:
  - about a task: open it in **Tasks**, then *Add an instruction*;
  - about an event or a day: the new **Calendar** page (⋮ menu): tap an event, or a day's *Add instruction*;
  - about nothing in particular: **Instructions** (⋮ menu).
- **What Claude can make of one**:
  - a task isn't one (off the plan and the list; it's listed under *Not tasks, as you said*);
  - a task can't start before a date, or waits until another task is done;
  - a task's due date is another;
  - an event of that name is free time, takes all its time, or takes some hours of its day;
  - a day (or every such weekday) holds at most so many minutes of work, or none;
  - you're busy at set times (once, or every week).
- **You check first**: Instructions shows Claude's reading ("Sat 10 Oct: no work"), with *Apply* and *Discard*. One it can't make into changes says why, with *Write again*. One in use can be taken back, which undoes it.
- **Protection** (your calls): while armed, applying or taking back a **change of due date** needs your dad's code (taking one back changes the date again). Days, times, events, hiding a task and holding one back apply when you tap Apply.
- **Cost**: one Claude call per instruction, a few cents. With Claude off, resting or capped, it waits and says so.
- **Tried on the phone, 9 Oct**: six instructions read through the real path. Claude read each one right:
  - "this cannot be done until the Prep… review is done" on Statics Prep;
  - "this email is just an email";
  - "I cannot do anything on this day" on Saturday;
  - "football every Tuesday from 4 to 6pm";
  - "the due date needs to be next Wednesday" (shown with *Apply with parent code*);
  - "make me do more maths": unclear, with why.

  I applied Saturday's and took it back (the Calendar page showed it in between), then discarded them all: nothing of the tests is left.
- **Not done**: instructions aren't in backups yet. And an email Claude hid as only to read can't be brought back by an instruction, as it isn't listed anywhere to write one about.
