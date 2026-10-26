# Blocking and protection

How the blocker covers distracting apps and sites while today's work isn't done, how free time is earned and spent, and how protection, once armed, makes switching the blocker off or loosening it slow and visible.
Protection never locks you out of your phone: it guards only the app's own pages and the few that would undo it (below), and the block screen always lets you go home.

## The blocker

`block/FocusService.kt` is an accessibility service (`res/xml/focus_service_config.xml`). It hears every app come to the front and covers a blocked one with the block screen when `block/Focus.kt` says so (`Focus.verdict`).
- **Apps and browsers**: a blocked app, or a browser blocked outright, is covered the moment it owns the window in use (window-state and windows-changed events, so within about a fifth of a second), or wherever it shows in a split-screen or pop-up window (`coverBlockedSideWindows`).
- **Sites**: in the browsers whose address bar is read (Chrome and Brave, `<browser>:id/url_bar`), the address is read as it changes, at most every 400 ms, so a blocked site is covered as soon as its address is typed, ahead of the page loading.
- **Picture-in-picture**: the block screen starts with `FLAG_ACTIVITY_NO_USER_ACTION`, so covering a playing video never sends it into a floating window. A blocked app already floating is closed where its window allows, else brought back to full screen and covered (`closeBlockedPictureInPicture`).
- **A hidden address** (a video full screen): judged by the page that browser last showed while the service watched. With none seen in this connection, it gets Back once, to leave full screen; one beside another app is brought forward once. One that still hides its address counts as not blocked.
- **Every half minute** (`FocusService.tick`) it judges the whole screen again, so an app already open when blocking begins is covered then; one spending free time is covered the moment it runs out, by the spending's own check.
- One block a burst (`BLOCK_GAP_MS`), each logged as a `BlockRecord` (when, what, why) for Stats.

On the same tick it also:
- applies setting changes whose wait is over (`Protection.applyDueChanges`), and gives an empty day its free time (BL-07);
- records slots gone by (`Slots.sweep`), runs Felix's look ([felix.md](felix.md)), and runs the watchdog every five minutes;
- offers the automatic Teams syncs, with their 10-second banner (`block/TeamsAutoSync.kt`, `block/CountdownBanner.kt`; [sources.md](sources.md)).

## What's blocked

`core/Blocklist.kt` holds the defaults; `Settings.blockedApps`, `blockedSites`, `checkedBrowsers` and `blockedBrowsers` hold what's in force, edited in Settings → Blocking and protection (the blocked apps first, then a search over the installed ones). The defaults never hold calls, messages, study apps, maps and travel, banking, authenticators, AI assistants or health and support apps; any installed app can be added, one of those too, and taken off once armed only after the wait (D-46, D-49).
- **Apps**: video and social apps (YouTube, TikTok's two builds, Netflix, BBC iPlayer, Twitch, Webtoon, Snapchat, Bluesky, Discord, LinkedIn).
- **Sites**: the same services' sites. A site matches its subdomains, and an entry with a path matches its sub-paths (`Blocklist.blockedSite`).
- **Browsers blocked outright**: Firefox (three builds) and Tor, whose address bars aren't read.

An address bar that can't be read is a protection problem (IMP-P1-005, `block/AddressHealth.kt`). After ten visits in a row to Chrome or Brave with its address never read, the watchdog reports that browser's blocked sites as uncovered, armed or not.
It stays reported until an address is read again, the browser is blocked outright or no longer checked, or the focus service connects again, which starts the count afresh.

## When blocking applies

`block/BlockPolicy.kt` (`BlockPolicy.decide`) is pure, and decides in this order:
1. A parent code's unblock lets everything through for its hour (`ProtectionState.overrideUntil`).
2. Quiet hours (`quietHours`, 22:30 to 07:00) never block. Nor does the time until blocking starts: 16:45 on a school day (`weekdayBlockFromMin`), 10:00 on a weekend or a day off by the school calendar (`weekendBlockFromMin`, BL-02). So the phone at school is left alone (BL-09).
3. In the gap after a slot kept today, earned free time can be spent till the next work slot starts (BL-10, `Focus.gapUntil`, `Plan.gapUntil`); a slot missed after the keep closes the gap.
4. While today's work isn't done, blocking is strict (`Reason.TodayUndone`).
5. Otherwise earned free time is spent while a blocked app is in front; with none left, it's blocked (`Reason.NoFreeTime`).

**Today's work isn't done** (BL-01) while today's bucket holds pieces (`Plan.pressure`), or while a slot missed today has no slot left today, until today's working hours end (BL-11, `Focus.missedKeepsPressure`). Tomorrow's work alone never blocks. A missed slot whose task has since finished, or gone, went to no day: once the plan's made without it, it keeps nothing on (`SlotState.reconciled`).
A piece you move yourself (a time, a day or a start you give its task) is recorded moved and its miss forgotten, so moving work doesn't keep the evening blocked the way letting it lapse does.

From a PC, `force-block` makes blocking hours apply for up to two hours whatever the time, for testing (`ProtectionState.forceActiveUntil`); it can only tighten.

## The block screen

`block/BlockedActivity.kt` runs in its own task (`singleTask`, excluded from recents), so neither Back nor Recents shows the app behind it. It shows:
- "Not now", what's blocked and the reason's headline, and the reward line (BL-04, below);
- the next piece (`Plan.next`) with its due date and minutes, and its actions (`ui/PieceActions.kt`), as Day's *Do now* card and a piece's sheet have them: **Open**, **Start** (a timer on it, below) or **Stop** with its time over them, **Done** (keeps its slot), **Check it's done** (asks its source; **All of it done** for one of your own);
- **Refresh Teams** for a Teams task, and **Photo check** for written homework while Claude is on;
- the free time left; **Why am I blocked?** (today's pieces, or the missed slot with no time left today: "A slot you missed has no time left today, so tonight stays blocked."); **Go to the home screen**; and Felix's button, about the next piece's task (UI-16).

There's no way through it. Back goes to the home screen. It closes itself once nothing is blocked, or once its own app or site has left the blocklist.

## Earned free time

`block/Credit.kt` keeps one day's ledger in `session.json`: minutes earned and spent, and whether the day's allowance is given. Every three minutes of work earn about one of free time (`workMinPerFreeMin`, 3). What's left at midnight is gone.

It's spent only while a blocked app or site is on screen (in front, floating, or beside another app), timed on the phone's elapsed-time clock and saved every half minute; locking the phone stops it.
While it's spent, a silent notification counts it down, and a heads-up comes in its last minute (IMP-P2-011, `block/FreeTimeNotice.kt`). Neither grants time; **Back to work** opens the app.

**What a kept slot earns** (BL-08). A slot kept by its tick, its photo or its source (LR-09, [slots-and-calendar.md](slots-and-calendar.md)) earns a third of its piece's minutes, by `Chunk.earnsBy`:
- a piece under `photoFromMin` (20 minutes) earns on its tick;
- a longer piece of written homework earns when its photo check passes, once, or on the photo itself if the photo comes first;
- a longer piece no photo can check (revision, admin, a deck) earns by its source: AnkiDroid's cards counted as you leave it (NF-03), or its task confirmed done by its source the same day;
- a longer piece of one of your own that isn't written work earns nothing, and its **Done** says so.

What today's slots earn is at most today's plan's share at the ratio: the pieces left today and those kept today, less what kept slots have earned (`Focus.slotRoomMin`). A piece ticked ahead of its day, or work past the day's plan, earns nothing more today.

**What a completion earns.** A task its source confirms done earns the work no kept slot counted, at the same rate, up to 30 minutes, with no floor (`Credit.forCompletion`, `Focus.onCompleted`).
- It's capped by the lower of your minutes and the app's own estimate (without your minutes or your steps) at the pace learned, each in its own scale: raising a task's minutes plans more time for it, but can't make finishing it earn more.
- Vocabulary its deck tasks hold, and blocks you ticked off by hand, come off it.
- An event (`Kind.Event`), one of your own you say is done, and feedback done by its tick (NF-06) earn nothing; an email you unstar is its source confirming it, and earns as any completion does.
- Each completion earns once (`SessionState.rewarded`), and only on the day it's confirmed.

**A day with nothing to do** starts with an hour (`freeAllowanceMin`, BL-07), given once: nothing planned today or overdue, no slot of today's kept, missed or moved, the morning's record planning nothing, and every source read that day, but one never read (`Focus.grantAllowanceIfDue`).

**Taken back**: a keep undone (UI-03, `Focus.unkeepSlot`) gives back the free time it earned, as its record says (`SlotRecord.earnedMin`: its share within the day's plan at the rate then, whether it earned as it was kept, by its photo, or by its task's completion), as far as it's unspent (`Credit.takeBack`). One kept on another day gives back none of this day's, as that day's went at midnight; one whose record has no `earnedMin` (kept by an older build) is taken back at the rate now. The day's room left to earn counts what kept slots earned the same way.

**The reward in sight** (BL-04): `Focus.rewardLine` says what finishing today earns, on the block screen, Day's *Do now* card and the widget: "Finish today's 3 slots (1 h) → 10 min free, 10 min more with a photo of …".
Once today's done, it's "Today's plan is done. 25 min free."; on an empty day, "Nothing planned today: 1 h free."

A keep, a photo's earning and a completion are each saved first (`SessionState.keeping`, `SessionState.photosDone`, `TaskState.unrewarded`), so a stop part-way is carried through at the next start, once (`Focus.finishKeeps`, `finishPhotos`, `rewardCompletions`).

## A timer on a piece

D-112, 4.4.0; the choices made in building it, each with its alternative, are in [plan_v4/answers.md](../plan_v4/answers.md) §The timer. Felix nudges ten minutes into a slot when the app has seen nothing done on its task, and it sees that only by its own **Open** and the Anki watch, so work begun from Teams' own notification, or early, was nudged all the same: a Start is your word that works for every source. **Start** runs a countdown of a piece's minutes (the plan's piece's as laid; a step the plan has no piece for, its minutes as its sheet shows them; else what's left of the task) in a silent notification (channel *Timer*) with **Stop**, counted down by Android's own chronometer, its tap opening the task's sheet; and it's your word you're on the piece: something done on its task (`Looks.noteActivity`), and Felix's messages about it wait (D-112; [felix.md](felix.md) §His messages). It's on the piece's actions (above), on each step still to do in a task's sheet, on a task with no steps' sheet for its next piece, and on the widget, ▶ over ✓ in one column where the height holds both (■ while it runs, with a pill "⏱ till 17:30", then "⏱ over"), ↻ beside. One at a time: a Start replaces the one there is, and says so; a second on the piece it runs on restarts nothing. It's on its task and step, not on a slot: the piece moved or laid again leaves it running.

- **At its zero** an exact alarm says "Time's up" once (channel *Timer over*, popping up whatever the hour, so it's seen on silent too), and the running one is retitled "Over on: …", its counter running on as −mm:ss: its base stays at the zero throughout, so it carries on with a minus sign rather than starting again from nothing. "Time's up" stays only beside the timer it's about: a Start on another piece, a Stop or its piece done takes it away.
- **It ends** by **Stop** (its notification's, the card's, the sheet's, the widget's ■), kept with its stop till the next Start so Felix's hold lifts 30 minutes on; or by its piece done, running or stopped (its step ticked, renamed or gone, a slot of its task kept since it began, its task closed or off the list), cleared then (`Focus.settleTimer`, after each keep, tick, photo check and completion, with each plan and at a start). An Undo doesn't restart it.
- **It blocks nothing, earns nothing and teaches nothing**: `BlockPolicy`, `Credit` and the calibration don't know it; a slot gone by undone is missed as ever (LR-09).
- **It's kept** in `session.json` (`SessionState.timer`, Focus's), not backed up; its notifications and alarms are made to match it at each change and at a start (`block/TimerAlarms.kt`), so after a restart it's shown again, and a zero gone by meanwhile is told then, within its 30-minute hold; later than that (the phone off over it, so its alarm went with a reboot) it's marked told without a word (`TimerAlarms.timely`). It runs on across midnight till it's stopped or its piece is done; a reset leaves it.

## Photo checks

`Photos.check` (`block/Photos.kt`) sends photos of written work to Claude (`enrich/PhotoChecker.kt`), shrunk and turned by `block/PhotoChecks.kt`: only homework that isn't Anki's (`Chunk.photoCheckable`), and only while Claude is on.
- Up to three pages make one check (AI-17), with *Add another page?* after each. Each is shrunk to 1 568 pixels on its long side and turned upright by its EXIF note; if any page can't be read, no check is made.
- Claude is given the piece and its minutes, the task's title, subject, steps done and instructions (AI-16, `PhotoChecker.context`), and told that blank or crossed-out pages, a photo of a screen, printed answers or another task's work aren't done. It answers `{done, confidence, reason, seen}`.
- Done with a confidence of 0.7 or more (`PhotoChecker.ACCEPT`) keeps the piece's slot, earning, or makes a slot kept without earning earn now (`Focus.photoChecked`). A photo counts only for a keep of its piece made after it was taken, or in the half minute before (a **Done**, then the photo at once).
- The photos are deleted whatever happens. The check runs in the app's scope, so leaving the screen doesn't cancel a call already paid for; it goes through Claude's rest after a failure, but not past a key problem or the cap.

## Protection

Nothing on an unrooted phone is unbypassable: the layers make every impulsive route slow and visible. The code's comments cite them by number.

| Layer | What does it | What it stops |
|---|---|---|
| 1. No bypass | No override button: quiet hours and the time until blocking starts are the only release | "Just this once" |
| 2. Settings guard | `protect/GuardRules.kt`, run by the focus service once armed | Switching the service off, force-stopping, uninstalling, clearing data, resetting settings |
| 3. Device admin | `protect/Watchdog.kt`'s `AdminReceiver`, asking for no powers | Uninstalling while armed |
| 4. Delayed loosening | `protect/SettingsChanges.kt`: a change that loosens the blocker waits `loosenDelayHours` (24) | Rewriting the rules in a weak moment |
| 5. Watchdog | `protect/Watchdog.kt`, `protect/ProtectionCheck.kt` | Leaving the service off, or on a shortcut |
| 6. Parent code | `protect/Totp.kt`: an authenticator app's codes, held by a parent | Being stuck when a change is really needed |

**Unarmed** (`Settings.armed` false), blocking works, but the guard is off, every change applies at once, and the watchdog warns without repairing (a crashed service it still restarts, given self-repair). **Armed**, all six layers act.

`protect/Protection.kt` makes every settings change (`changeSettings`: at once, or waiting), counts the waits and applies what's due, uses a parent code, and keeps the device admin in step with arming. It's built like `Focus`: the settings, protection's own state (`protection.json`), the secrets, the clock, and callbacks for the device admin; `ProtectionTest` drives it on temporary stores, armed and unarmed. Settings saves through it from its state holder (`ui/SettingsScreenState.kt`: your edits over what the screen showed, only what you changed saved, BUG-P2-014), tested without Compose (`SettingsScreenStateTest`).

**Arming** (`protect/ProtectionActivity.kt`: its own page, and Settings → Blocking and protection) asks for the device admin, then sets up the parent code:
- a QR code for the parent's authenticator, checked with a code from it; or, with one set already, kept once a code from it is typed (IMP-P2-012); or skipped;
- then *Arm now?* lists what isn't in place yet (IMP-P1-006), and arms all the same. Left part-way, the wizard gives the device admin up again.

## What waits, and what's instant

Once armed, a change waits only if it loosens the blocker itself (`SettingsChanges.LOOSER`):
- taking an app, site or browser off its list (what's added to a list applies at once; only removals wait);
- longer quiet hours (any minute added to them);
- a later start to blocking, on school days or on other days;
- fewer minutes of work for a minute of free time;
- a higher photo line (`photoFromMin`: more pieces earn on a tick), or more free time for a day with nothing to do;
- disarming, or a shorter delay.

Everything else applies at once, armed or not:
- the work's settings: working hours and school windows, pieces and breaks, margins, undated work, Anki, the Teams syncs, Claude (on, its key, its cap), the reminders, Felix's settings and the calendars;
- tightening any of the blocker's settings;
- instructions, quick changes and Felix's changes, due dates and minutes included; your own tasks;
- a restored backup's instructions, log, calibration and calendar answers; and every reset.

The weekly review's bounded changes apply at once too, armed or not (IMP-P2-015, `Protection.applyReviewChanges`): margins, undated work a day, a school day's hours within an hour, and the minutes of work for a minute of free time between 2 and 5 (`ReviewInput.bounds`).
Undone on the Reviews page, one that loosens the blocker waits as yours do.

A waiting change is a `PendingChange` in `protection.json`, listed under *Waiting* with when it applies.
- Its wait is counted on the uptime clock (`DeviceClock.uptime`, `SettingsChanges.applyDue`), which setting the date can't move; hours the phone is off don't count.
- *Cancel*, or setting it back, drops it at once; a parent code applies it at once (`Protection.applyNow`).
- One that no longer loosens (a setting that no longer waits, a removal added back) applies at the next count.

## The parent code

An authenticator app's time-based codes (RFC 6238, Q17). The secret is made at arming, shown once as a QR code, and kept only in the encrypted secrets file ([storage-and-backups.md](storage-and-backups.md)).
- A code applies one waiting change, or, armed, **Unblock for an hour** (`Protection.useParentCode`).
- Each code works once; three wrong ones lock entry for an hour, across restarts (`CodeLock`).
- A code can't say what it's for, so the dialog says in full what it will do, and that the code's holder types it in, seeing that.
- Nothing is ever emailed: protection's alerts stay on the phone.

## The settings guard

Once armed, the focus service reads the windows of Settings, Device Care (`com.samsung.android.lool`) and the package installer, and presses Back (Home if Back is refused) on (`GuardRules.decide`):
- the focus service's own accessibility page;
- this app's App info (Force stop, Uninstall), its storage page (Clear data), its device-admin page, and Device Care's page for it;
- an uninstall prompt naming this app (the installer's install and update prompts stay usable);
- the *Date and time* page, as setting the date forward would hurry an hour's unblock;
- the confirmation pages of *Reset all settings* and *Reset accessibility settings*.

A tap on the service's name on an accessibility-shortcut page is undone at once (`FocusService.onClick`), and the watchdog takes it off any shortcut. Back is pressed only once the guarded page's window is the one in use, and never within a second of the last.

## The watchdog

`protect/Watchdog.kt` runs from the focus service every five minutes, as a job every 15 (`WatchdogWorker`, which applies waiting changes too), at boot and after an update (`BootReceiver`), and the moment the service disconnects. `ProtectionCheck.problems` reads what it finds:
- the service switched on, accessibility on, and the service really running (its own connection, checked every half minute);
- every checked browser's address bar readable, armed or not;
- armed: the service on none of the four accessibility shortcuts (one Android won't let it read shows as unknown on the Protection page, never as clear), the device admin active, self-repair granted, and protection's alerts able to show.

Armed, with `WRITE_SECURE_SETTINGS` (granted once over adb, Q19), it switches the service back on and takes it off every shortcut itself.
A service switched on that has stopped (a crash) is switched off and on, armed or not, once self-repair is granted: after a minute's grace and at most every ten minutes, with one quick retry when the first doesn't take.

Anything wrong is an ongoing notification on the *Protection* channel ("PROTECTION OFF" armed, "Blocking is off" unarmed), a line on the widget, and a `ProtectionRecord` in the log each time it changes, saying whether it got repaired.

A settings file that can't be read never disarms (IMP-P1-003): unreadable settings start armed while the device admin is on ([storage-and-backups.md](storage-and-backups.md)). Disarming, once it applies, gives up the device admin, so the app can be uninstalled (`Protection.start`'s watch on `armed`).

## Never break the phone

- Nothing off the blocklist is ever covered. Calls, messages, WhatsApp, Slack, Teams, school and study apps, maps and travel, banking, authenticators, AI assistants, health and support apps and Settings are never on it but where you add them (D-49), and this app itself can't be.
- The guard acts only on the pages above, never on Settings as a whole or another app's pages, and only once armed.
- The block screen always has **Go to the home screen**. A browser hiding its address is given Back once a connection, never again and again; whatever can't be judged counts as not blocked.
- A failure in one piece of the service's work is logged, never allowed to bring the blocker down; one bad file never stops the app.
- The debug commands that arm, disarm or force blocking work only over adb ([storage-and-backups.md](storage-and-backups.md)).

## Limits

Anti-tamper is friction, not security. These defeat it, slowly and visibly:
- adb: `settings put secure enabled_accessibility_services`, or a UiAutomation tool such as `uiautomator`, which pauses every accessibility service while it runs;
- safe mode, a factory reset, or a second user profile;
- an Android update that changes the Settings pages the guard recognises.

The parent code is only as strong as its holder typing it in, seeing what it's for.
