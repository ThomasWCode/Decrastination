# What needs you

The v1 build ran while you were away (from 8 Oct 2026, evening). Anything that needed you, a decision or a hand on the phone, waited here instead of stopping the build. Newest phase last; each item says what's waiting and what happens meanwhile.

## In short

**Now: version 3.0.0 (15 Oct, tagged `v3.0.0`)**, armed, with Claude on, its record begun afresh on the 16th: what was tried on the phone and what's waiting for you are in *3.0.0: review v2*, at the end. What follows is from the v1 build.

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
- **Back up and Restore** (Setup): *Export* saves the settings, the activity log, what the app has learned and your calendar answers to a file you choose (it never includes passwords or keys, which stay encrypted on the phone). *Import* reads one back. The settings go through the same waiting as any change once protection is armed, and this phone's own protection and Claude key stay as they are; once armed, the log and what the app learned aren't restored, so an edited file can't teach the planner to plan less. **Since 1.7.0** (from the review): a backup also keeps your instructions in use, and restoring brings each back as applying it here would (days, times, hide and hold at once; once armed, a due-date change waits in Instructions for your dad's code); and a file holding what the app never writes (negative minutes, a setting out of the screen's range) is refused whole, with nothing restored.
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
- **Protection** (your calls): while armed, applying or taking back a **change of due date** needs your dad's code (taking one back changes the date again). Days, times, events, hiding a task and holding one back apply when you tap Apply. *Changed in 2.0.0: no change to the work needs his code.*
- **Cost**: one Claude call per instruction, a few cents. With Claude off, resting or capped, it waits and says so.
- **Tried on the phone, 9 Oct**: six instructions read through the real path. Claude read each one right:
  - "this cannot be done until the Prep… review is done" on Statics Prep;
  - "this email is just an email";
  - "I cannot do anything on this day" on Saturday;
  - "football every Tuesday from 4 to 6pm";
  - "the due date needs to be next Wednesday" (shown with *Apply with parent code*);
  - "make me do more maths": unclear, with why.

  I applied Saturday's and took it back (the Calendar page showed it in between), then discarded them all: nothing of the tests is left.
- **Not done**: instructions aren't in backups yet (they are since 1.7.0). And an email Claude hid as only to read can't be brought back by an instruction, as it isn't listed anywhere to write one about.

## 1.6.0: the widget's look

- **Your ask, 10 Oct**: the widget cleaned up as the app was.
  - **The next thing** is a card in the accent colour: *DO NOW* over its name, its due date and minutes as pills (the due date in red when it's urgent), then the *Then:* line, and ↻.
  - **Small** (one row high, up to about 4×2), the whole widget is that card. As it gets taller, it adds the *Then:* line, the *DO NOW* label, a warning, the day's count, and a second line for the name. Lines with nothing to show take no room. Where the pills are too wide to sit side by side (2 columns), they stack, or at one row high become plain words.
  - **Taller** (about 4×3 up), the card sits over the day's count, which heads the rest of the day as tiles. Urgent ones have a red edge. Each shows its minutes in a pill and opens its task.
  - **Trouble** (protection off, a source that can't be read, Teams not synced) is a red strip at the top, as the app puts it first. Tapping it, or the day's count, opens the app.
  - **Nothing taken away**: a list longer than 30 ends *N more in the app*.
- **Tried on the phone, 10 Oct, with you**: on the 4th screen at 4×2 (401 × 226 dp: the card, the day's count and two tiles), 4×3, 4×5 (13 tiles), 4×1 (the card alone, with its label and *Then:* line) and 2×1 (188 dp wide: the pills stacked beside ↻, the name cut short). It's back at 4×2. In light mode the minutes pill all but vanished into the pale card, so **1.6.1** puts the plain pills in the surface colour: white in light mode, near-black in dark, clear on the card and the tiles in both. The app itself was fine in light mode (Plan, Tasks, Stats).

## 1.7.0: fixes from the repository review

- **What it is**: the 33 bugs and inconsistencies in the review of 1.6.0 (`docs/review/BUGS-AND-INCONSISTENCIES.md`, #20), each checked against the code. 31 are fixed in #21–#24; the other two are below. The improvements list (`docs/review/IMPROVEMENTS.md`) waits, as you asked.
- **What you'll notice**:
  - **Anki is counted in cards, not words** (your answer: your decks go both ways, two cards a word). Deck 1.2 is 144 cards, not 72; a deck's steps go by its own *New cards/day* in AnkiDroid (40 on every deck now, as you'd set).
  - **On a day homework vocabulary is due by tomorrow (or overdue), the daily quota is reviews only**, for the rest of that day (your call). Right now that's every day until the three overdue German assignments are handed in.
  - **An email that asks you to do something** ("please complete…", "return the form by…", "action required") is work under the rules even from a no-reply address, due by the date after "by". With Claude on, its reading still replaces the rules'.
  - **Backups keep your instructions in use**, and a file holding what the app never writes is refused whole.
  - **Settings, an instruction being written, the parent-code QR and a photo check survive a turn of the phone**; Settings saves only what you changed.
- **Left as they are** (with you):
  - **BUG-P2-008, the fixtures**: real teacher and class names and an Isaac Science link stay in the public fixtures: you said they're fine to be public. Only the capture script changed (BUG-P2-026): it pulls into `private/` now, and checks the signing key itself.
  - **BUG-P2-025, tests of the blocker on a device or emulator**: not added. It's a test harness, not a fix (an emulator matrix and a coordinator pulled out of the focus service); the blocker is still checked by hand on the phone. Worth weighing with the improvements.
- **Tried on the phone, 10 Oct**: see #22 (Anki counts, a sync) and #23 (screens).

## 1.8.0: improvements from the review

- **What it is**: the 36 improvements in `docs/review/IMPROVEMENTS.md`, each checked against 1.7.0; what each came to, and why, is in `docs/review/IMPROVEMENTS-EVALUATED.md`. Nine needed no decision; ten were yours to call (11 Oct); seventeen aren't worth it here, each with its reason.
- **The Plan** (#25):
  - **Start, Check it's done and Photo check on the Plan**, not only on the block screen: the DO NOW card has the block screen's buttons, and tapping any other piece opens them in a sheet. Earning free time before 16:45 no longer means opening a blocked app first. A session under way shows on the card, counting down, with *Stop session*.
  - **Needs a look**: a red card at the top of the Plan when a source can't be read, Teams hasn't synced for a day, or protection is off, with the way to put it right (*Open Setup*, *Protection*, *Sync Teams*). *Nothing to do* says when that's because a source hasn't been read yet.
  - **Not planned**: folded at the foot of the Plan, the open work it leaves out, each with why: not open yet (its source's date), waiting for another task (as you said), opening after the plan's last day, or no room for it by then. Before, these just weren't there.
  - **The widget's red !**: at sizes too small for the red warning strip (2×1, 4×1), a red ! by ↻ says something's wrong; a tap opens the app.
- **Calendar questions answered on the Calendar page** (#25): an event the plan can't judge shows *All of it / A few hours / None* where it's listed, so a dismissed notification no longer loses the question; your answer can be changed there too. An answer still holds for every event of that name.
- **Quick changes in Tasks** (#25), with no Claude: on an open task, *Not a task*, *Can't start before…* and *Due on…* (a date picker; the time of day it's due stays). Each is kept as an instruction, listed in Instructions and taken back there; a due date needs your dad's code while armed, as an instruction's does, and isn't kept if the code isn't given. *Since 2.0.0 it applies at once.*
- **Protection** (#26):
  - **A saved file that can't be read no longer disarms protection.** Before, a `settings.json` that failed to load (after an app update, say) started from the defaults, unarmed, and the app then took the device admin away. Now a file that can't be read whole keeps every part that still reads, settings that can't be read at all stay armed while the device admin is on, the bad file is kept, and Setup (and the Plan's *Needs a look*) says what happened until you tap *Got it*. Saved passwords and keys that can't be decrypted are told apart from none saved.
  - **Chrome's or Brave's address bar going unreadable is a protection problem**: if an update renamed it, blocked sites in that browser would go uncovered while all looked well. Ten visits running without the address read, and it's reported (the alert, the widget, *What's in place*), with the fix: block that browser outright in Settings.
  - **Arming again keeps your dad's authenticator**: he types a code from it, rather than scanning a new QR code and leaving his old entry useless. *Set up a new one instead* and *Skip* are still there.
  - **Arm now?** lists anything in *What's in place* that isn't (it arms all the same).
- **Free time** (#26): while earned free time is spent on a blocked app, a silent notification counts it down, and a heads-up comes a minute before it's covered. *Back to work* opens the Plan. Neither grants time.
- **The weekly review's changes apply at once**, armed or not (your call): box length, safety margin, undated minutes a day and work minutes per free minute, each within its bounds. The review says what changed. A change of yours waiting for one of those settings is replaced by the review's; every other waiting change keeps its 24 hours.
- **Learning, settings, notifications, photos** (#27):
  - **Learned estimates rest on evidence** (your call): a class's own multiplier counts once it has three timed pieces; until then its kind's figure stands, so one long evening can't set it. Stats says what each figure rests on ("from 4 timed pieces", "from your answers", "2 timed pieces so far").
  - **Four notification channels** where one carried four things (your call): *Morning briefing*, *How it went*, *Calendar questions*, *Weekly check-in and review*. Each starts as the old one was set (importance, sound, vibration, light), so a silenced one stays silenced; the old one goes once nothing's left on it, so a question still showing isn't lost.
  - **Settings: blocked apps first, then a search box** (your call) over the rest of the installed apps.
  - **Photo checks the right way up**: a portrait page stored as landscape with a note to turn it (EXIF) is turned before it's sent.
  - **The Gradle wrapper checks the distribution's SHA-256** when it downloads it.
- **The evaluation set** (#27, your call): your real emails and assignments with the right answers (you checked the labels, 11 Oct), kept in `private/eval/` and never committed; `docs/eval.md` says how to run it. Opus answers through Claude Code, so it costs nothing on the API.
- **Tried on the phone, 11 Oct** (1.8.0 installed over 1.7.0):
  - the Plan's DO NOW card and a piece's sheet (*Check it's done* answered for its own task only);
  - *Due on…* through the parent-code step, cancelled, nothing kept; *Can't start before…* applied, then taken back in Instructions;
  - the Calendar's answer buttons and *Keep it*;
  - Settings' *Find an app* ("tube": YouTube, blocked, first);
  - the four new notification channels, with the old one's importance, and the old one gone;
  - no false address-bar alarm while you used Chrome;
  - and, with your go-ahead, the protection fix for real: `settings.json` with one field that can't be read stayed armed, with the device admin still on and Claude still on, and the incident showed on the Plan and in Setup; your file was then put back byte for byte. Not done whole-file junk: falling back to every default would have switched Claude off, and the rules would have read over Claude's readings.
- **Not tried on the phone**: arming again with the authenticator kept, and *Arm now?*'s list (they need protection disarmed, so your dad); the free-time countdown (it needs a day with nothing due); the widget's red ! (it needs the widget made 2×1); the review's at-once changes (tonight's review, at 21:00).
- **Waiting for you: what the first evaluation run found** (11 of 13 pass):
  - **The German assessment's plan is thrown away.** Claude split *Prep and Assessment preparation* into nine sensible steps, but one ("revise using the marked sheet", from 9 Oct) starts after the Teams due date (8 Oct, the hand-in) though the assessment is on 12 Oct. The app's checks then drop the whole split and plan 3½ hours as one block, already overdue. Either the prompt tells Claude no step can start after the due date, or the checks drop only that step.
  - **A note to self holding a recording** ("Imperial Physics Talk", a link to the talk's audio) is read as only to read (2 min); you labelled it something to do. The prompt could say a note to self that keeps something to go back to is something to do.


## 2.0.0: an organiser first

- **Your ask, 12 Oct**: much more an organiser than a blocker. Blocked apps stay blocked and free time is still earned, but nothing about the work is held back any more. Your answers are Q26 to Q31 in `docs/open-questions.md`.
- **Only blocking waits** (#28). Once armed, the 24 hours and your dad's code guard only what would switch blocking off: taking an app, site or browser off its list, longer quiet hours, a later start on school days, more free time for your work, disarming, and the delay itself. Everything else applies when you save or tap *Apply*, armed or not:
  - due dates, from an instruction, *Due on…* or a restored backup (no parent code any more);
  - working hours, box length, safety margin, undated work, Anki's textbook and deadline;
  - the Teams syncs, Claude (on, its key, its cap), and the reminders' times;
  - a backup's log, what the app learned and your calendar answers (before, only while unarmed).
- **What that leaves of the blocker**: moving work off today and tomorrow can lift the strict mode, but a blocked app then costs earned free time, and free time still comes only from focus sessions, photo checks and work its source says is done.
- **An organiser** (#29):
  - **Your own tasks**: *New task* in Tasks opens a form (what it is; Homework, Revision, Admin or Event; subject; due date and time; hours and minutes; can't start before; notes). It's planned, shown on the widget and blocks like any task. Its page has *Done* (earns no free time: nothing but you says it's done; its focus sessions earn theirs), *Edit*, *Delete*, and *Not done* once done. On the Plan and the block screen, *Done* takes the place of *Check it's done* for one of yours.
  - **Every task's page**: a tap on a task in Tasks (or *Change its due date, minutes…* on a piece of the Plan) shows when it's due, how long it takes and when it can start, each with *Change* (a date and a time; hours and minutes; a date), at once, armed or not. A source's task keeps each as a quick change, which *Undo* takes back to what the source says; a newer one replaces the older rather than piling up in Instructions. *Not a task* and *Add an instruction* are there too. Minutes for a task with steps scale the steps to match.
  - **Your minutes count as you gave them**: the plan doesn't stretch them by what it's learned of its own estimates (it says when it does stretch its own: "Planned as 54 min: such work has taken you longer"), and they teach it nothing. Finishing a task earns by your minutes or the app's estimate, whichever is less.
  - **Claude can read minutes now**: "this will take 45 min" was unclear before (Claude: "the app can't set how long a task takes"); it's a *Minutes* change now. Yours about *Call with Jags after school* is still in Instructions to write again.
  - **Tasks by due date** (Q30): Overdue, Today, Tomorrow, This week (to Sunday), Later, No due date, each task tagged with its source and its steps done; below, folded, the tasks you said aren't, those finished lately, and **Sources** (how each was last read, which the source headings used to say).
  - **The Plan a task at a time** (Q31): each day lists a task once, with its pieces that day and their minutes together ("Bridge and Balances Beam Q's · 4 pieces · 2 h"), opening into the pieces; today went from 29 rows to 7. The day's count is of tasks, and so is the widget's, whose list is a line a task ("Statics · 3 pieces").
- **Tried on the phone, 12 Oct** (#29, armed): the Plan grouped and opened; a piece's *Change…* to its task's page; a Teams task's minutes set to 1 h (the plan went 54 → 60 min) and undone; a task made with the form, turned to landscape and back with nothing lost, due Wed 18:59; *Done* (free time unchanged, logged as your estimate), *Not done*, *Delete*; Open on one of yours opening its page. Left as found: the test task and its log record gone (`forget-task`), your settings and instructions as they were.
- **Ask Claude** (#30, Q29): the ⋮ menu's *Ask Claude* (in place of Instructions), the chat button over *New task* in Tasks, *Ask Claude about it* on a task's page, and the Calendar's box all open one chat. Say anything about your work: a task to add ("UCAS statement by 1 December, about 8 hours"), a deadline or how long something takes, a day or an evening you can't work, or a question ("what's due tomorrow?", or since #31 "what's on tomorrow?", "how busy is Saturday?", "what should I do first tonight?", answered from the Plan as it shows it). Claude answers in a line or two, asks when it needs to (which task, when), and puts what it would do on cards: a new task (*Add*, *Change first* to edit it in the form, *Set aside*) or changes (*Apply*, *Set aside*). Nothing changes until you tap, and *Undo* on a card takes it back. Its other tab, *Your changes*, lists the changes in use (taken back there) and any older instructions still to check. One call a message, a few cents. With Claude off, the chat says so and the form still adds tasks.
- **Tried on the phone, 12 Oct** (#30, two real calls, about $0.05): "remind me to buy mum a birthday present before Sunday" came back as a card (Admin, due Sat 23:59, 45 min, the guess said) in under five seconds; *Add* made the task. "Actually it will only take 20 minutes" was read against it (Minutes, on the task), and *Apply* changed the task itself. *Undo* deleted the task; *Clear the chat* emptied it. Nothing left behind. The ten-message evaluation is in `docs/eval.md`.

## 3.0.0: review v2

Built from `docs/review_v2_improvements/impl/` in eight phases, each a pull request (#35 to #42, merged 14 and 15 Oct), each tried on the phone before its merge. What each phase does is in the README's status, `docs/scheduler.md` and `docs/data-sources.md`.

- **Tried on the phone, 15 Oct** (phases 1 to 3 with you that morning, 3 to 8 that evening):
  - **Phase 3**: a slot left undone past its end and ten minutes' grace moved off its time, the rest of the evening keeping theirs, and its calendar event went.
  - **Phase 4**: the reward line saying which pieces need a photo; the block screen's reason ("A slot you missed went to tomorrow, so tonight stays blocked"); AnkiDroid counted without opening the app (three visits, the deck's piece ticked by the one that finished it, all their minutes counted); a tick on record and in Stats; free time in the gap after a ticked slot, a blocked app opening and covered again as the next slot began.
  - **Phase 5**: Felix ten minutes into a slot with nothing done, with three replies; your "give me 15" moving the slot, and his answer; a change made at once in the chat; a *Remembered:* line (yours).
  - **Phase 6**: every screen light and dark in the icon's pink; Day, Work and Stats; Settings' pages; a slot swiped done and back, *Later today*, and one held and moved to a day, then taken back in *Your changes*.
  - **Phase 7**: a task's sheet (its steps, the plan's reasons, photos); the chat splitting a task of your own into steps, ticking one, and opening one.
  - **Phase 8**: the night's backup in Documents/Decrastination, a second the same day replacing it, *Import* restoring it unchanged, Settings → Backup's time; the Reviews and Debug pages; Stats by term and subject; and the 20 open tasks Claude had read, read again by version 2 of the prompts (20 calls, $0.30 in all: two estimates smaller, one email's planning date found, and the quotes behind the readings).
- **Found on the phone and fixed before the merges**, each with a test:
  - The focus service stopped as it started, in phase 4 (its AnkiDroid watch was asked for before it was set), and Android switched it off: protection was off from 18:22 to 19:12 on the 15th, until the fixed build. Armed throughout, and Decrastination back among the accessibility services.
  - A time for a step's piece ("give me 15") was kept under the piece's old name, so the plan never laid it; a time off the five minutes was never laid at its minute; a pin whose time was taken looked only at free time starting after it; and once its time had passed, the piece was laid five minutes on at every plan, so it never began, and the gap before it kept moving.
  - *Undo* after a swipe left the piece without its time, one swipe ticked twice, Day wasn't drawn again after an Undo, and the day's heading was squeezed to "Tod/ay" by a long line beside it.
- **Waiting for you:**
  - **Felix's morning briefing**: see that it reads as his.
  - **A photo check**: the next written homework you finish, *Check with a photo* on its sheet (a few cents); and a photo of homework with the camera button, made into a task of your own.
  - **A copy of the backup off the phone**: the night's backup stays on the phone; a sync app mirroring Documents/Decrastination, or *Export* now and then, keeps one elsewhere.
  - **Long calendar events count as a few hours of each of their days until you're asked (DX-14)**: the all-day events of your calendar further off than a week (holidays among them) take 3 hours of each of their days until they're within the week, when the app asks about each. Holidays are likely free time: answer *Free* when asked, or tell the chat now.
- **Settled on the 15th**: the seven old starred conversations, unstarred by you, finished at the next sync, so none of them is read again; and the estimate for a note of German resources, 884 minutes, is 10 since the re-read.
- **Settled on the 16th**, with you:
  - **The widget's ✓** ticked a piece from the home screen: its slot kept and the task done, as *Done* in the app keeps them, and Day went on to the next (a test task of mine, gone since).
  - **The Teams widget's 0.4.0** (TeamsAssignmentsWidget #15) is merged and on the phone, installed over 0.3.1 with its data and Teams sign-in kept. A full resync opened every assignment and saved them all, and a task's sheet now names the files attached to it (DX-08), which Claude read it with again.
  - **The weekly summary's address (NF-02)** is set on the phone, in Settings → Notifications; it's in no file here.
  - **The screens at the largest font (UI-12)**: looked at by you.
  - **The record begins on the 16th**: Settings → Reset → *Everything learned* cleared the stats, the reviews, what the app had learned, the chat, Felix's history and what he remembers, and every change in use, after the copy it saves first (in Documents/Decrastination). What the checks left went with it, the replies and the memory they asked of you among it; so did the evening of the 15th, its slots gone by while you tested. That morning's one completion, a sync's, was put back, and a test's free time taken off.
  - **Tagged** `v3.0.0`, on main's 3.0.0 merge.

## 3.0.1: your answers of the 16th

- **Every starred email is a task** (Q3): an event's email too, what it needs of you before then; where it's unclear, Claude's reading asks you. Work's *Events in your email* list is gone, and the emails it held are on the plan. The prompts are at version 3, so the open tasks are read again once (about 20 calls, some 30 cents).
- **The morning briefing is Felix's message whole** (Q12), with no list of the day under it, and he has 480 characters for it (240 for the rest), as his prompt now says. A message over its room ends at its last whole sentence rather than mid-way.
- **The chat says where a time went** (Q12): asked for a time with no free time, the plan lays the piece at the first free time after it (PL-04), and a line under the answer says so ("06:35 had no free time: it's at 10:45").
- **The Reset page** has the margin the other pages have (Q12).
- **Dropped**: the Exams page and recurring work (NF-04, NF-05, Q7).
- **Returned work (NF-06)**: I looked in Teams on the 16th; nothing of yours has been returned yet (every completed card says *Submitted*), so the widget's half still waits for one. Settled as yours to tell me, or for me to look again, once something comes back with feedback.
- **Questions for you** are on the PC, in `private/QUESTIONS.md`, kept out of the repository as some are about what's in it.

## 3.0.2: an overdue task's card applies

- **Found by you on the 16th:** a card from the chat moving an overdue task to today wouldn't apply: *Apply* did nothing, only "It's due before then" under it, and the answer above had said "Done". The rule that keeps work before its deadline (PL-04) took any time for work already overdue as too late. It now holds only while the deadline is ahead: for overdue work, any time from now is as soon as it can be.
- **And a change refused** after the chat said it was made is said under the answer ("Not made: …").

## 3.1.0: your list of the 16th

- **Dark blue** in place of the pink (UI-12's, from the logo): by night, near-black with navy cards, bubbles and chat button under pale blue, and light-blue highlights; by day, navy on paper with pale blue cards. The widget and the notifications' accent follow. The app's icon stays the logo's pink, as you chose.
- **Felix's button is bigger**: Material's standard size (56 dp) in place of its small one (40), on every screen; the lists leave room for it under their last row.
- **Felix types**: while he answers, three dots rise in turn in a bubble on his side, as a messaging app shows someone typing, in place of "Felix is thinking… 12 s"; *Cancel* is still beside it once the call has run 10 seconds.
- **Nothing Felix does waits for a button**: a new task, days off, a busy time or a limit any day, and a reply to his question about a task are made as they come, each card saying *Done* or *Added* with its *Undo*; a task he added has *Change* beside it, and one undone has *Redo*. Days off come out of the school calendar with the card's Undo. Cards from before 3.1.0 keep their Add or Apply.
- **What he remembers**: each line under his answer has its *Undo* (a fact gone, and the one it replaced back), the lines of today's answers too while what they name is unchanged; and the chat's menu (⋮ → *What Felix remembers*) lists it all, each with its delete, as Settings → Felix does.
- **Felix changes everything**: any task's steps (Gmail's, Teams', Power Planner's, yours; not Anki's, which are its cards), any task's name in the app, a step to do again, a task of yours done, open again or deleted, and the settings about the work and about him (never blocking, protection or Claude's). Each is said under his answer with its *Undo*. A Teams, Power Planner, Gmail or Anki task is still done when its source says so.
- **Felix searches the web** when a message needs facts neither you nor the app have given him: his answer asks for a look, a call of its own searches (at most three searches), and he answers again with what it found. A message that searches is three calls, some 25 to 35 cents in all (your first, with three searches, was 36), where one that doesn't is about 6 to 8. What he searched for, and the pages found, are under his answer. Settings → Felix has the switch. (The first 3.1.0 build had the search in his own call, which the API refused as too large a grammar beside his answer's shape: every chat call failed until the fix, as you saw.)
- **A reply to Felix's question that tells rather than changes** goes by itself from now: it's kept with its task, which is read again with it. One from the evening of the 16th is still under *Your changes*, as Claude couldn't make a change of it; *Discard* takes it away.

## 3.1.1: the calendar's stray events, and fewer steps

- **Events drawn over the plan in Google Calendar** (found by you on the 17th): ten of the app's events in the Decrastination calendar had lost the marks the app knows its own by, so when their pieces moved they stayed, over the new plan (a piece across Saturday evening's). Google's sync had brought them back as new events: run while the app was changing an event it was still sending (a refresh in Google Calendar just after a change to Felix does it). The app now finds such a copy by its words and deletes it at its next write, and writes the calendar once changes have been quiet 5 seconds, so a burst of them is one write. Short events looked overlapped too, as Google Calendar draws one under 30 minutes 30 minutes tall: you've set it to draw them as they are.
- **Fewer, longer steps** (your call, 17 Oct): Claude now splits a task only where its homework lists separate pieces of work, or it's over about 90 minutes (then into parts done in different sittings, 25 to 60 minutes each). Questions on pages 28-30 are one step, and a few minutes' printing, marking or uploading goes in the title of the step it belongs to ("Past paper 1 (print it first), then mark it"), not a 5-minute event of its own. The prompts are at version 4, so your open tasks are read again once (some 20 calls, about 30 cents); steps you or Felix gave a task stay as they are.

## 3.1.2: work over an hour in sittings

- **Nothing's split unless it's over an hour** (your call, 17 Oct), and then by time, not by what's in it: Claude gives each piece of work whole (no "pages 35-39, then 40-43", as the app can't know how long each part takes), and the app cuts one over 60 minutes into even sittings of at most an hour: the first the work itself, then "Continue: …", and the last "Continue and finish: …". The prompts are at version 5, so your open tasks are read again once.
- **A piece's length is 60 minutes** on your phone (Settings → Planning → *Work cut into pieces of*; it was 30), so a task with no steps is cut only past an hour too, into even pieces; and the weekly review no longer changes it, as it's yours.
- **#47's review, answered here.** You asked Codex for it as 3.1.1 merged, so it came after; both points were real. (1) A stray copy in the calendar was known by its words alone, so an event of yours worded like the app's ("Gmail. …") in the calendar it writes to could have been deleted: now a copy is deleted only if the app wrote that title at those times there, each version remembered before it's written. (2) A task's ticks went only to steps of the same name, and the newer prompts merge steps: a task with a step done is no longer read again just for newer prompts, and a reading made otherwise (*Read again with Claude*, an answer to Felix, a changed task) is shown which steps are done, to keep them. None of your open tasks had a step done when this morning's re-read ran, so it lost nothing.
