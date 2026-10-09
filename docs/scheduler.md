# Planner, block policy, anti-tamper, and learning

All four are pure Kotlin with an injected `Clock`, no Android types, unit-tested against `fixtures/`. Revised 7 Oct 2026 after your answers: strict blocking while anything is due today or tomorrow, earned free time otherwise, large tasks split into day-sized chunks, no bypass, and calibration from your own history.

## 1. Inputs

- `tasks`: open tasks with `availableFrom <= now`. Each has `dueAt?`, `effortMin`, `progress`, `kind`, `class`, and optionally an ordered list of **sub-steps** (from the LLM: "Q1–8 ~25 min", "Q9–16 ~25 min", "mark answers ~10 min") whose estimates sum to `effortMin`.
- `availability`: weekly template, your answer of 7 Oct: **Mon–Fri 16:45–22:00, Sat–Sun 08:30–22:30** (commute already inside the 16:45 start), minus calendar busy blocks (Phase 5), minus the part of today already gone.
- `calibration`: per-(kind, class) effort multipliers and safety margins learned from history (§5), defaults 1.0 and 1 day.
- `now`.

## 2. Effort

`remainingMin = effortMin × multiplier(kind, class) × (1 − progress)`, floored at 5. Effort comes from, in order: your override, the LLM estimate, a per-kind default (Homework 40, Admin 15, Event 0, Anki from card counts: reviews × 8 s, new × 25 s).

## 3. Day-bucket allocation

The planner builds one bucket per study day from today to the furthest deadline, each with its capacity in minutes, then places every task's remaining work into buckets. **What lands in today's and tomorrow's buckets is, by definition, "due today or tomorrow"**, which is what the block policy reads.

Placement, tasks taken in deadline order (earliest first; overdue and no-deadline tasks handled below):

1. The last usable day is `dueDay − margin` (margin **1 day** by default, longer for kinds you tend to leave late, §5). If `dueAt` is in the morning (before 12:00) the due day itself is never usable.
2. The task is cut into chunks: its sub-steps if it has them, otherwise boxes of `boxMin` (**ASSUMED 45**, Q6), last one shorter.
3. Chunks are placed **backwards from the last usable day**, filling each day up to its remaining capacity, one chunk per day where possible so a task is spread rather than dumped on one evening. A chunk that cannot fit anywhere before the deadline is placed in today's bucket and flagged `behind`.
4. Overdue tasks and tasks due today go straight into today's bucket, oldest deadline first; they are placed before step 3 runs so they consume today's capacity first.
5. The **daily Anki quota** (all due reviews + 20 new from the lowest unfinished deck + any deck named by German homework) is a fixed chunk in today's bucket, deadline 21:00 (**ASSUMED**; could be 22:00 given the weekday window). Homework-linked deck tasks are ordinary tasks with the assignment's deadline.
6. Tasks with no deadline (self-sent emails, Power Planner items with no date) get a soft deadline of `firstSeenAt + 7 days` and are placed last, so they fill free capacity rather than displace homework.
7. **As built (Phase 2, 9 Oct):** undated work is planned at most **60 minutes a day** (`softMinPerDay`), working backwards from its soft deadline. On the first day the whole inbox (28 conversations) was first seen at once, so without the cap all of it fell on one evening a week later; with it, it spreads over that week. A task that can only go so far a day (`stepsPerDay`: an Anki deck, which releases 20 new cards a day) never gets more steps than that on one day, overdue or not: an overdue 36-card deck is 20 cards today and 16 tomorrow, not both today. Today's step is what Anki will still show today (with 5 of today's new cards left, 45 unseen cards are 5, 20 and 20), and a deck whose next cards come at 04:00 isn't the thing to do until then. When either kind can't all fit before its deadline, it's done in order from the first day it can be, so as much as possible is done by the deadline (45 cards due in two days: 20 and 20 before it, 5 after); what fits nowhere in the horizon is left unplanned rather than breaking a limit. Remaining time is cut into even boxes (100 minutes is 34 + 33 + 33, not 45 + 45 + 10).

Why backwards from the deadline rather than "everything as early as possible": placing early would put every open task into today's bucket and the block would never lift, which turns the app into a wall you learn to ignore. Backward placement means today's bucket contains exactly the work that must happen today for every deadline to be met, given the days left and their capacity. The margin and the per-kind calibration are what stop this from being a last-minute planner: a kind you habitually leave late gets a bigger margin, which pulls its chunks earlier.

Why spread one chunk per day: a 90-minute physics prep due Monday becomes Thu/Fri/Sat chunks instead of one Sunday-night block, which is both easier to start and makes "due tomorrow" chunks appear a few days ahead, exactly as you asked.

**Within a bucket**, order is: overdue → due today → `behind` chunks → ascending deadline → kind weight (Homework > Revision > Admin > Event prep) → shorter first. Ties broken by title so the widget never flickers.

Worked example from tonight's data (22:00, Tue 7 Oct, capacity left tonight 0, tomorrow 16:45–22:00 = 315 min):

| Bucket | Contents |
|---|---|
| Today (overdue/due today) | Ch17 mixed practice (due 23:59), Binomial Expansion, Statics Prep, three German items, Power Planner "Pg 60&61" |
| Tomorrow (Wed) | Dr Frost F=ma (due 08:30, so really tonight), Prep and Assessment preparation (due 09:00), Gefahren im Internet chunk (due Fri 08:30 → last usable day Wed), Anki quota |
| Thu–Sun | Young's modulus test revision chunks (test Mon 12 11:00, 3 chunks Thu/Fri/Sat), PREP 2 chunks (due Mon 13 08:00, 2 chunks Fri/Sat/Sun) |

Today's bucket is hopelessly over capacity, so everything in it is `behind` and the block is strict until it clears. That is the honest picture.

## 4. Block policy

```
dueSoon  = today's bucket ∪ tomorrow's bucket (chunks, not whole tasks)
pressure = dueSoon is non-empty
quiet    = now in [22:30, 07:00)        ──your sleep window (7 Oct)

shouldBlock(now) = !quiet && ( pressure || credit <= 0 )
```

- **While `pressure`:** strict. No credit can be spent, none is earned beyond being banked. Blocked apps show the block screen with the top chunk of today's bucket.
- **When `pressure` is empty:** earned free time. Each verified completion banks `min(30, max(10, remainingMin / 2))` minutes (whole task) or `boxMin / 3` (one chunk). Credit is spent only while a blocked app is in the foreground. Unspent credit expires at the end of the study day. With no credit, the block screen shows the next chunk from the nearest future bucket as the thing to do to earn time.
- **Quiet hours** are the only unconditional release, and shortening them is a "loosening" change subject to the 24-hour delay (§6).
- **No bypass button.** Removed at your request. The only override is the parent-held code (§6).

**As built (Phase 3, 9 Oct).** The policy (`block/BlockPolicy.kt`) applies, in order:
- a focus session you started blocks whatever the hour;
- a parent-approved unblock lets everything through for an hour;
- quiet hours (22:30–07:00) and, on weekdays, school hours (before 16:45, Q6b) never block;
- then pressure blocks strictly, and with no pressure earned time is spent.

Earned time: a finished focus session earns a third of its minutes (Q22). A task its source confirms done earns a third of what no session counted, up to 30 minutes, with **no floor** and nothing for reading or archiving an email or an event passing: with the 10-minute floor, archiving a pile of emails would have earned time. Blocked apps and sites are in `block/Blocklist.kt` (Q8). Chrome's and Brave's address bars are read (`<browser>:id/url_bar`), so a blocked site is covered as soon as its address is typed, before it loads; Firefox and Tor are covered outright.

**Chunk completion.** The final state of a task is always source-verified (hand in, tick, archive, deck count). A *chunk* of a bigger task cannot be verified at the source, so a chunk completes in one of two ways:

1. **Focus session.** Tapping **Start** on the block screen runs a timer for the chunk's minutes; blocked apps stay blocked throughout; the chunk is marked done when the timer completes. Idling through a timer is possible, but the deadline and the source check still bite at the end, and the learning layer (§5) notices when timed chunks never translate into finished tasks.
2. **Photo check** (Phase 5, optional). For written work you photograph the pages; the LLM (vision) answers "does this show completed answers for Ch17 questions 1–8?" with a yes/no and a confidence; yes marks the chunk done. Costs cents per check, well inside your credit.

## 5. Learning and calibration

Everything the app learns is a number you can see in Settings, and every parameter has bounds. The record per task: estimate, actual minutes (sum of sessions until verified done), kind, class, hour each session started, blocks triggered before the first session ("resistance"), whether the deadline was met, how many days before the deadline it was finished.

1. **Effort multipliers**, per (kind, class): exponentially weighted mean of `actual / estimate`, clamp 0.5–3.0. Applied in §2. Physics preps taking 1.6× the LLM's guess is the kind of thing this catches in a week.
2. **Safety margin**, per kind: starts at 1 day; rises by 1 (max 3) when two tasks of that kind in a row finished within an hour of the deadline or missed it; falls back when five in a row finished early. Pulls chunks earlier for the kinds you leave late.
3. **Box length**, per kind: an experiment over {25, 45, 60} minutes, epsilon-greedy with reward "session completed and the task's verified completion came within its deadline"; locks in after 20 samples, re-opens if completion rate drops.
4. **Capacity reality check.** If today's bucket is completed in full on fewer than 40 % of days over two weeks, the template capacity is overstated; the app proposes lowering it (you confirm) because the alternative is a plan that is always `behind`.
5. **Quick self-assessment after each verified completion** (your choice, 7 Oct): one tap, *harder / as expected / easier*, plus an optional one-line note. Feeds item 1 directly (a "harder" vote nudges the multiplier up even before the actual-minutes signal settles) and is included verbatim in the weekly review.
6. **Weekly check-in questionnaire** (your choice): five fixed questions on Sunday evening before the review runs, each a 1–5 scale or one line: how the week felt, what you avoided and why, what got in the way, what you'd change about the plan, energy by time of day. Answers are stored with the week's log.
7. **Weekly review (LLM, Opus 5.5, high effort).** Sunday evening: the week's log, the self-assessments, the questionnaire answers and the current calibration go to the model, which returns proposed parameter changes within the bounds above and a five-line note ("You start German fastest around 17:00 and never after 20:30; moved its chunks earlier."). Changes that loosen blocking still go through the 24-hour delay. The model cannot change the floor settings.

Practice-test scheduling before assessments was offered and not chosen; it stays out.

## 6. Anti-tamper

Nothing on an un-rooted personal phone is unbypassable: adb from a PC, booting to safe mode, a factory reset, or a second user profile all get round any app. What the layers below do is remove every *impulsive* route, so defeating the block takes a deliberate, slow, visible act.

| Layer | Mechanism | Stops |
|---|---|---|
| 1. No bypass | No override button; quiet hours are the only release | The "just this once" tap |
| 2. Settings guard | The accessibility service watches `com.android.settings`, Samsung Device Care (`com.samsung.android.lool`) and the package installer. When a screen in those apps shows the app's own accessibility toggle, its App info page (Force stop / Uninstall / Clear data), the device-admin deactivation screen, "Reset accessibility settings" / "Reset all settings", or a tap on *Decrastination focus* in an accessibility-shortcut chooser, it presses Back (and Home if Back fails). Proved on the phone in Phase 0 for the service's page, App info and the uninstall prompt (`docs/phase0-findings.md` §5) | Turning the service off, force-stopping, uninstalling, clearing data, resetting settings, putting the service on a key shortcut |
| 3. Device admin | A `DeviceAdminReceiver` with no policies; Android refuses to uninstall an active admin until it is deactivated, and the deactivation screen is behind layer 2 | Uninstall from the launcher or Play Store |
| 4. Delayed loosening | Any change that reduces blocking (shorter quiet hours, removing a blocklist entry, lower quota, bigger box, disabling a layer) takes effect **24 hours** after you request it and is shown as pending; tightening is immediate; the delay itself can only be lengthened immediately. The parent-held code below is the one way to skip the wait | Rewriting the rules in a weak moment |
| 5. Watchdog | A periodic job and a boot receiver check the service is enabled, the admin active, and the service on no accessibility shortcut (`accessibility_shortcut_target_service`, `accessibility_button_targets`); if not: a persistent notification, the widget shows "PROTECTION OFF" in red, and the event is logged with its time. With `WRITE_SECURE_SETTINGS`, granted once over adb at setup (Q19, decided 8 Oct), it puts the service back and clears the shortcut itself | Quietly leaving it off |
| 6. Parent-held override (your choice, 7 Oct) | Every pending loosening request, and any "protection off" state lasting over an hour, is emailed to **richard.white@lshtm.ac.uk**. The email carries a one-time code; typing it into the app applies that one pending change immediately (or, for a protection-off alert, simply tells him). Design below | Leaving it off for days; being stuck when a change is genuinely needed |

**Parent-held override, how the code works.**

- A request (say "remove Instagram from the blocklist") gets a random `requestId`. The code is `HMAC-SHA256(secret, requestId)` truncated to 8 digits; the `secret` is generated at setup, lives only in EncryptedSharedPreferences, and is never shown. One code opens exactly one request; it expires when the 24-hour delay would have elapsed anyway; three wrong entries lock code entry for an hour.
- An "unblock for 60 minutes" request is also allowed through this path, so a genuine emergency has a route that involves another person rather than a button.
- **Delivery must not pass through a mailbox you can read.** If the app sent the code from your own Gmail, it would sit in your Sent folder and the override would be yours, not his. So the sender is a **dedicated mailbox whose credentials your dad enters at setup** (a free Gmail address created for the app; its app password goes into the app's encrypted storage through a setup screen he completes, and the password is never displayed afterwards). The app sends over SMTP (`smtp.gmail.com:465`). Alternative if he would rather not run a mailbox: an authenticator app on *his* phone holding a TOTP secret the app shows once as a QR code at setup; the email then just says "Thomas requested X; if you approve, read him the current code"; no secret ever travels by email. Both are implementable; the dedicated mailbox matches what you asked for and is the **default**.
- The weekly note going to him as well is a toggle, **default off**, since you did not ask for it.
- Nothing else is ever emailed to anyone.

**As built (Phase 3, 9 Oct): left unarmed (Q20), with an authenticator app for the override and no email (Q17).**
- *Arm protection* (Setup → *Blocking and protection*) asks for the device admin, then shows a QR code for your dad's authenticator app and checks a code from it (skippable), then arms.
- **Unarmed**: the guard is off, loosening applies at once, and the watchdog only warns.
- **Armed**: a loosening change waits `loosenDelayHours` (24) and shows under *Waiting*, where a code from his app applies it at once; *Unblock for an hour* takes a code too. The 24 hours are counted on the phone's uptime clock, which setting the date can't move: hours the phone is switched off don't count. A focus session likewise finishes, and earns its time, only when the uptime clock agrees its minutes are up.
- Each code works once, and three wrong ones lock entry for an hour, across restarts (`protect/Totp.kt`, `CodeLock`).
- The watchdog (`protect/Watchdog.kt`) runs in the focus service every five minutes, as a job every 15, at boot, and the moment the service is switched off. It reads the four shortcut settings defensively: Android 12+ refuses some to apps, and reading `accessibility_qs_targets` threw on the phone and took the service down before that was caught. A setting it can't read is reported as unknown on the protection screen, never as clear; switching the service off from such a shortcut is undone at once once armed.
- The guard (`protect/GuardRules.kt`) adds this app's storage and device-admin pages, Device Care's page for it, the *Date and time* page (setting the date forward would hurry the parent's hour and a session's end; tried on the phone), and the confirmation pages of *Reset all settings* and *Reset accessibility settings*. The reset pages' button text ("Reset settings") is taken from One UI's other reset pages and is unverified.
- A tap on the service's name on a shortcut page is undone, and the watchdog takes it off any shortcut.
- **A crashed service is restarted, armed or not.** Android leaves a crashed accessibility service switched on but never binds it again until it's switched off and on (it happened on the phone at 00:59 on 9 Oct). The watchdog notices at its next check (within 15 minutes; at once if anything else runs it), allows a minute for it to come back by itself, then switches it off and on, at most every ten minutes, so a service that crashes as it starts isn't churned. A second trap showed on the phone: Android keeps the crashed connection bound and hands the restarted service to it as well, and that connection then resets the service's connection id. Android still counts the service bound but delivers it no events, so nothing is blocked while everything looks well. So "running" means the instance's own connection works (checked every half minute), and a restart that leaves it stopped within a minute is tried once more at once: the second, with the service already up, gets a clean connection. Tried on the phone by crashing the app (`adb shell am crash`): the first restart was reset 16 ms after connecting, the retry 1.5 s later held, and YouTube was blocked again.

Residual holes stated plainly: adb (`settings put secure enabled_accessibility_services`, or any UiAutomation tool such as `uiautomator`, which pauses every accessibility service while it runs), safe mode, factory reset, a second user profile (the phone already has one, user 150, most likely Secure Folder), and an Android update that changes the Settings screens layer 2 recognises (same class of fragility as the Teams scraper; the guard matches on the app's own name, which is stable, and the setup screen has a "test the guard" button). The override's strength rests on the sender mailbox staying his.

Layer 2 needs care so it never traps you out of Settings entirely: it acts only on screens that name this app or the reset pages, never on Settings as a whole, and it is disabled automatically while the setup checklist is incomplete.

## 7. Edge cases the tests must cover

- Teams sync fails or is stale: tasks keep their last state; the widget shows "Teams synced 06:42".
- A deadline passes while the widget is on screen: redraw at the deadline (alarm).
- A split-screen or pop-up window holding a blocked app: the service checks every window, not only the active one.
- A blocked app in picture-in-picture: the block screen is started with `FLAG_ACTIVITY_NO_USER_ACTION`, so covering an app never sends it there; one already there is found through the window list (a pinned window is never the one in use, so the active-window check alone misses it), relaunched to full screen (One UI's floating window offers no dismiss action) and covered. Proved in Phase 0 after a blocking test left a YouTube Short floating for 20 minutes (`docs/phase0-findings.md` §5).
- A task that disappears from its source without you acting: `Done` with reason `GoneFromSource`, shown separately in stats and excluded from calibration.
- The daily quota at 23:30 unmet: it is overdue; whether that blocks at that hour is quiet hours' decision.
- Clock and time-zone changes: planner re-runs on the system broadcasts.
- A pending loosening change crossing midnight, a reboot, or a reinstall: pending changes are persisted with their apply-at time.
- The settings guard must not fire on another app that happens to contain the word "Decrastination" in its text (match on the Settings package *and* the app's own component or package name in the node tree).
- Override codes: a code for request A must not open request B; a code must stop working once the delay has elapsed on its own; the lockout after three wrong entries must survive a restart; a request raised while offline queues the email and still starts the 24-hour clock.
- The dedicated sender mailbox being unreachable (password revoked, no network) must degrade to "the request waits its 24 hours" with a visible notice, never to "the change applies".
