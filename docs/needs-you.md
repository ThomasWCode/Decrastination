# What needs you

The v1 build ran while you were away (from 8 Oct 2026, evening). Anything that needed you, a decision or a hand on the phone, waited here instead of stopping the build. Newest phase last; each item says what's waiting and what happens meanwhile.

## Phase 1: sources, storage, sync

- **Nothing blocking.** All four sources read on the phone on 8 Oct: Teams (11 assignments), Power Planner (2 items), Gmail (28 conversations) and AnkiDroid (the quota and 3 linked decks). Credentials went in with `scripts/load_credentials.py`.
- **Your inbox is all tasks now** (as you asked: everything in the inbox is outstanding). Your own notes are 10 minutes each, other people's emails 15, notifications 2 (GitHub's emails about these PRs are notifications too). Each email with no deadline gets a soft one a week after the app first sees it; since the 28 conversations already there were all first seen on 8 Oct, the planner caps undated work at an hour a day (Phase 2), so they spread over 9–14 Oct instead of all falling on one evening. The cap is a setting.
- **Three German vocabulary decks are overdue tasks** (1.2, 2.2, 2.3: 169 cards between them), because the assignments that name them (*Familie und Ehe*, *Gefahren in den sozialen Netzwerken*) are past due and not handed in. Each disappears when its assignment is handed in.

## Phase 2: the planner and the widget

- **The Next task widget is on your 4th home page, at 4×3.** I tried it there at 2×1, 4×1, 2×2, 4×2, 4×3 and 4×5; resize it as you like. Tapping it opens the task; ↻ reads every source and asks the Teams widget to sync Teams (it takes over the screen for a minute, as its own ↻ does).
- **Opening an email can't land on that email.** The Gmail app opens its inbox for any link; the app opens Gmail and shows the subject and sender to look for.
- **Today's plan is heavy**: 14 things, about 7 hours, against 5 h 15 min free this evening, mostly the five overdue assignments and their German vocabulary decks. That's the honest picture, and blocking (Phase 3) will be strict until it clears. Handing in what's already done is the quickest way to shrink it.

## Phase 3: the blocker and protection

- **Blocking is on, for real, from 16:45 today (Friday 9 Oct).** With 14 things due today, every blocked app (YouTube, TikTok, Netflix, iPlayer, Twitch, Webtoon, Snapchat, Bluesky, Discord, LinkedIn, and Firefox and Tor) shows the block screen until 22:30, and so do their sites in Chrome and Brave. It stays strict while anything is planned for today or tomorrow, so handing work in is what lifts it: *Check it's done* on the block screen asks the source at once. If something goes wrong, the focus service can be switched off in Settings → Accessibility → Installed apps, since protection isn't armed.
- **Protection is built but not armed, as you decided.** When you're ready, Setup → *Blocking and protection* → *Arm protection*. It asks for the device admin (a system dialog), then shows a QR code for **your dad** to scan into an authenticator app (Google or Microsoft Authenticator). He reads you the code it shows to confirm, or you skip that step. Without his code there's no override, only the 24-hour wait. The self-repair permission is already granted.
- **Automatic Teams syncs start today**: on your first unlock after 16:45, then every three hours until 22:30. Each one shows the 10-second banner first. One test sync of Teams ran at 01:02 (allowed: you said I could sync Teams at any hour).
- **Not tried on the phone**: a blocked app in split screen or a pop-up window (the code covers it; Phase 0 tried picture-in-picture); spending earned free time, which needs a day with nothing due (unit-tested); and the guard on the reset-settings pages, whose button text is a guess. Arming is the moment to try the guard on your own pages.
- **Media volume**: I muted media while testing at night and set it back to 5, where it was.

## Phase 4: Claude and the Settings screen

- **Claude is built but off, as you decided: nothing has been sent to it, and nothing will be until you turn it on.** To try it: Setup → *Claude* → *Enter key* (an Anthropic API key, `sk-ant-…`), then Settings → *Use Claude*. Once protection is armed, both the switch and a new key wait 24 hours, as any change that can lift pressure does. It then goes over your inbox and assignments once (roughly 50 calls: about £1–2 at list price) and then only what's new. It stops for the month at the cap (£200; change it in Settings), and Setup shows the month's spend.
- **Without it, the rules split assignments that list their parts** into those parts as steps: on the phone, three of eleven (the two German homeworks with numbered or dashed lists, and the assessment preparation).
- **Settings** (Setup → *Settings*) edits the hours, Teams' automatic syncs, the planning numbers, Anki, the blocked sites, browsers and apps (a switch per installed app), and Claude. Once protection is armed, a change that loosens blocking waits 24 hours and is listed there until it applies. The screen shows what you've asked for, waiting changes included; setting one back and saving cancels it.

## Phase 5: learning, the briefing and the calendar

- **The morning briefing starts today**, Friday 9 Oct, at 07:00: today's plan in a notification; unlock within 90 minutes and the Teams sync is offered. At weekends it's 08:30. Both times are in Settings.
- **The calendar now counts as busy time.** Four of your events in the next fortnight are all-day or four hours or more, so the app can't tell how much of the day they take. Each is asked about once, in a notification in the week before it (from 07:00, never in quiet hours): *Free*, *Busy*, or *A few hours*. Your answer holds for every event of that name. Holidays, birthdays and travel count as free; anything shorter than four hours takes its slot.
- **After each finished homework or revision**, a notification asks how it went (*harder*, *as expected*, *easier*, or a line). Your answers adjust its estimates for that kind and class.
- **On Sunday at 19:30**, a reminder opens the five-question check-in, and the week's review follows at 21:00 (or as soon as you save the answers). Until Claude is on, the review is the rules': what the app learned from the week, and, after ten days of plans, whether your hours are more than your evenings hold. It changes the planner's numbers only within fixed bounds, and never blocking, protection or your hours.
- **Waiting for Claude** (you decided on no paid calls yet): the model's weekly note and the photo check of written work. Both appear by themselves once Claude is on and its key is in use.
- **Not tried on the phone**: the briefing notification (it fires at 07:00), an answer to a calendar question, and the photo check (it needs Claude). The calendar's judgement of your real events, the check-in screen and a review by the rules were tried.
