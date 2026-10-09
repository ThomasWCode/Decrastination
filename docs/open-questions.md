# Open questions

## Answered 7 Oct 2026

| # | Question | Decision |
|---|---|---|
| Q1 | Power Planner access | **Web API** with stored (encrypted) credentials. Phase 0 runs `scripts/powerplanner_probe.py` first. |
| Q2 | Gmail access | **IMAP + Google app password.** |
| Q3 | AI | **Anthropic API key, `claude-opus-5-5`, high effort.** £200/month of unused credit; the model does triage, assignment splitting, the weekly review, optional photo checks. Monthly cap in Settings. |
| Q4 | Unlock rule | **Strict while anything is in today's or tomorrow's bucket; earned free time by task size only when nothing is.** Large tasks split backwards from their deadline into day chunks. **No bypass button.** The app learns from how tasks actually go. `docs/scheduler.md` §3–§6. |
| Q5 | Anki quota | **All due reviews + 20 new from the lowest-numbered deck with new cards left + any deck a German assignment names.** Quota deadline **21:30** (Q5b, 8 Oct). |
| Q6 | Hours | **Capacity Mon–Fri 16:45–22:00, Sat–Sun 08:30–22:30; quiet (no blocking) 22:30–07:00.** Commute is inside the 16:45 start. Box length starts at 45 min and the learning layer experiments with 25/45/60. |
| Q14 | Anti-tamper | **24-hour delay on loosening changes.** Override: each pending change emails a one-time code to **richard.white@lshtm.ac.uk**; typing it applies that one change immediately. Protection-off alert after an hour goes to the same address. Sender is a dedicated mailbox your dad sets up (so the code never passes through your own Gmail); the authenticator-app variant is documented as an alternative. Weekly note to him: toggle, default off. *The override became an authenticator app on 8 Oct (Q17), and v1 sends no email.* |
| Q15 | Learning | **Measure and calibrate + one-tap self-assessment after each task + Sunday five-question check-in.** Practice-test scheduling not chosen. |

## Settled by Phase 0, 8 Oct 2026

| # | Question | Outcome |
|---|---|---|
| Q18 | Which textbook a German section number means | **A "current textbook" setting, starting at Textbook 1** (your answer, 8 Oct). "Learn vocabulary column 1.2" maps to `Textbook 1::1.2`; you switch the setting when the class moves on, and the LLM may override it when an assignment names the book or a topic only one has. The daily quota's "lowest-numbered deck with new cards left" walks the current textbook first. |
| Q19 | Letting the watchdog switch protection back on | **Yes: `WRITE_SECURE_SETTINGS` granted once over adb at setup** (your answer, 8 Oct; `adb shell pm grant com.thomaswcode.decrastination android.permission.WRITE_SECURE_SETTINGS`). The watchdog switches the focus service straight back on and removes it from any accessibility shortcut, as well as showing PROTECTION OFF and, after an hour, emailing your dad. |
| Q10 | Where the Teams provider change goes | In the widget, as planned: TeamsAssignmentsWidget #13 (0.3.0) adds the permission, the provider, change notifications and the two calls. Widget behaviour unchanged. |
| Q11 | Package name and location | `com.thomaswcode.decrastination`, in `C:\Users\thoma\Documents\Decrastination`, repository `ThomasWCode/Decrastination` (public, so `private/` keeps real inbox and agenda data out of git), signed with a copy of the widget's committed `app/debug.keystore`. |

## Answered 8 Oct 2026 (evening), for building v1 unattended

| # | Question | Decision |
|---|---|---|
| Q20 | The state v1 is left in | **Blocking on, with the real policy; anti-tamper built and tested but left unarmed** behind an *Arm protection* button. Until armed: the settings guard is off, the device admin isn't active, loosening a setting applies at once, and the watchdog warns but doesn't switch anything back on. |
| Q17 | Override delivery | **An authenticator app (TOTP) on your dad's phone, not email.** v1 sends no email at all: a protection-off alert is a notification and the widget's state. *Arm protection* asks for his scan of a QR code, and can be skipped; until he has scanned it there's no override, only the 24-hour wait. |
| Q6b | Blocking hours | **Quiet 22:30–07:00 unchanged; on weekdays blocking starts at 16:45** (school hours unblocked); weekends 07:00–22:30. |
| Q21 | Automatic Teams syncs | **On a tap, on the first unlock after 16:45, and every 3 hours.** Every automatic one first shows a banner at the top of the screen counting down 10 s, with **Cancel** and **Delay 5 min**. Defaults I chose: only while the phone is unlocked and in use, never 22:30–07:00; the 3-hourly ones from 16:45 on weekdays and from 07:00 at weekends. |
| Q8 | Blocklist and browsers | **Blocked: YouTube, TikTok, Netflix, BBC iPlayer, Twitch, Webtoon, Snapchat, Bluesky, Discord, LinkedIn.** Never blocked: calls, messages, WhatsApp, Slack, Teams, school and study apps, maps and travel, banking, authenticators, AI assistants, health and support apps. **Chrome and Brave: sites checked** (their address bar); **Firefox and Tor: blocked outright** while blocking applies. |
| Q22 | Earned free time | **About 1 minute per 3 minutes of work**, expiring at the end of the day. |
| Q3b | AI | **The Claude path is built but off by default, and makes no paid API request** until you switch it on (even with the £200 a month of credit). Prompts are tried out on Opus through Claude Code, on your subscription, instead. Monthly cap when on: £200. While it's off: rules triage email and estimate effort, calendar events are judged by rules and by asking you, and photo checks and the weekly review wait. |
| Q7 | Calendar | **Yes, as busy time, judged event by event**: a drum lesson takes its whole slot; you can work on a train ("Train to X"); an all-day van hire takes only a few hours. When it can't tell, the app asks you. |
| Q13 | Morning briefing | **Yes: 07:00 on school days, 08:30 at weekends.** |
| Q16 | Photo checks | **Yes** (they wait for the AI). |
| Q5b | Anki quota deadline | **21:30.** |
| Q23 | When Codex review stalls | Wait an hour, ask again twice, then merge on my own review and say so in the PR. |
| Q24 | Testing on the phone | The widget goes on the 4th (last) home page, where there's room to try every size. Stay awake stays on; tests can run overnight. I may ask the Teams widget to sync at any hour when a test needs fresh data: the allowed hours govern only the app's own automatic syncs. |
| Q25 | Credentials | Loaded from this PC's user environment into the app over adb (`scripts/load_credentials.py`): into its private storage, then its encrypted store, then the file is deleted. Never printed or committed. |

## Answered 9 Oct 2026

| # | Question | Decision |
|---|---|---|
| Q9 | The sideloaded "Digital Wellbeing" app (`com.screentime` 1.6) and `app.humanforest` | **Ignore them.** The app leaves both alone. |
| Q12 | The work-experience email from your dad (a calendar of 2027 deadlines) | **Yes: the AI can split anything it needs to into blocks.** Once Claude is on, an email, a Power Planner item or a Teams assignment can come back as blocks of work, each with its own dates where it has them (an application that opens on 30 Oct, another due in January). The planner places each in its own window, and an email archived with dated blocks still to do stays on the list until they're done. Built in 1.1.0; since 1.1.1 a long email is read to its end (the app had kept only its first 4 000 characters, which cut that email's timeline off), and its blocks can come to 20 hours (that email's came to 12 to 14). |
