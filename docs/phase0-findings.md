# Phase 0: what proving each data path found

*Run on 8 October 2026, 20:40–21:15 BST, on the Galaxy S24 (Android 16, One UI) over adb, with the probe app in `app/` (package `com.thomaswcode.decrastination`, `probe/`) and the scripts in `scripts/`. Every path works. Three things turned up that change later phases; they're at the end.*

| Source | Path | Result |
|---|---|---|
| Teams Assignments widget | Signature-protected provider, added in widget 0.3.0 (TeamsAssignmentsWidget #13) | Works: all 11 assignments and the sync state read; `adb shell` refused; sync and open start; change notifications arrive |
| Power Planner | Web API from the PC probe, with your account | Works: login, semester, agenda (2 items), 18 classes with a two-week timetable |
| AnkiDroid | Official provider, after the runtime permission | Works: 57 decks with counts |
| Gmail | IMAP with an app password, from the PC probe | Works: 24 inbox messages, Gmail's ids and labels |
| Blocking | Accessibility-service spike | YouTube covered 0.21 s after it opens; Back goes home |
| Settings guard | Same spike | Backs out of the service's own page, this app's App info and its uninstall prompt; leaves everything else alone |

## 1. Teams: the provider

Built in the widget (merged as #13, version 0.3.0): permission `com.teamsassignments.widget.permission.READ_ASSIGNMENTS` (`signature`), provider `content://com.teamsassignments.widget.assignments` with `/assignments` and `/state`, and calls `requestSync` and `open`. The contract is the widget's `provider/AssignmentsContract.kt`; `docs/data-sources.md` §1 has the summary.

On the phone:

- The probe, signed with the widget's committed key, was granted the permission without a prompt (`dumpsys package`: `granted=true`) and read all 11 assignments, in the widget's order, with keys, classes, due times, tabs and full instructions (21 to 925 characters), plus the state row: last success 06:47, status idle, sync service on.
- `adb shell content query` and `adb shell content call` were both refused: `SecurityException: Permission Denial: opening provider … requires com.teamsassignments.widget.permission.READ_ASSIGNMENTS`.
- `requestSync` returned `started=true`; Teams opened and the sync ran. `open` returned `started=true` and Teams opened on Assignments.
- The probe's observer heard the sync start at once (55 ms) and its end 10 s late: by then the probe was in the background, and Android held the notification back. Decrastination can't rely on observers for timing; they're a hint to re-read soon.
- Decrastination's manifest declares the same permission, identically, so the order the two apps are installed in doesn't matter.

**Found: the widget's sync and open fail once Past due holds seven or more cards.** Teams now puts a zero-height "load more" `ProgressBar` (`SHIMMER_GROUP`, bounds `[45,2298][1035,2298]`) under the Past due list, as it always has under Completed. The widget treats any `ProgressBar` on Forthcoming or Past due as "still loading", so the sync gave up with *Couldn't read the Past due list* (captured in the widget as `failure-20261008-210646.xml`), and opening an overdue assignment waited 17 s on Past due and then couldn't find it. This morning's sync worked because two of today's items were still Forthcoming. Scrolled to by hand, the placeholder gave way to the list's footer, with the same seven cards: it's a lazy "load more", which Teams only fetches once it's on screen. **Fixed in the widget (TeamsAssignmentsWidget #14, 0.3.1)**: an off-screen placeholder no longer counts as loading, but does mean the list isn't known whole, and the sync brings it into view (`ACTION_SHOW_ON_SCREEN` works on Teams' WebView). On the phone Teams answered within 2.6 s, and the sync read all seven and saved all 11 assignments.

## 2. Power Planner: the web API

Run with `DECRASTINATION_PP_USERNAME` and `DECRASTINATION_PP_PASSWORD` from your Windows user environment, so the credentials never appeared in a terminal or a file.

- `LoginWeb` → `{AccountId, Session, Error}`; `GetSelectedSemesterId` → `{SelectedSemesterId, Error}`.
- `GetAgenda` → `{Items, Classes, Error}` with **`Classes: null`**. Class names and timetables come from **`/GetClassesAndSchedules`** (`{"Login": L, "SemesterIdentifier": id}`) → `{WeekOneStartsOn, Classes: [{Identifier, Name, Color, Schedules: [{Identifier, UpperIdentifier, Room, StartTime, EndTime, DayOfWeek, ScheduleWeek}]}], Error}`.
- An item: `{PercentComplete, Identifier, DateCreated, Name, ShortDetails, Date, ClassIdentifier, ItemType}`. `ItemType` is the API's `PowerItemType`: 5 a task (called Homework in the API), 6 an event (Exam).
- `Date` is local time with no zone, and **its seconds carry the time option** (web app `viewItems.ts`, `timeOption`): for a task with a class, `:00` start of class, `:01` before class, `:02` during class, `:03` end of class, `:04` a set time, anything else all day; for a task with no class, `:04` a set time, else all day. Events use their `EndTime` instead (`:59` all day).
- **A task with no class has the semester's id as its `ClassIdentifier`.**
- The timetable runs over two weeks: `ScheduleWeek` 1, 2 or 3 (both), counted from `WeekOneStartsOn` (Monday 24 Aug 2026). `DayOfWeek` is .NET's (0 Sunday).

Worked through: *Pg 60&61* is `2026-09-25T00:00:01` in German PETERS, so "before class"; 25 Sep is a Friday of week 1, when German PETERS starts at 12:20, so it was due at **12:20 on 25 Sep**. *Call with Jags after school.* is `2026-10-14T16:00:04`, no class, so **16:00 on 14 Oct**. `fixtures/powerplanner_agenda.json` keeps these shapes, redacted for the public repo.

## 3. AnkiDroid: the provider

- The runtime prompt reads *"Allow Decrastination to access existing notes, cards, note types, and decks, as…"*; after **Allow**, `/decks` returned **57 decks**, nested with `::`: `Extras`, `GCSE Vocab`, `Textbook 1` with `Textbook 1::1.1` to `6.3`, `Textbook 2` with the same section numbers, and `Verbs` with `Verbs::00 Verb list` to `15 Verbs with prepositions`.
- `deck_count` is `[learn, review, new]`: AnkiDroid's `CardContentProvider.kt` builds it from `lrnCount`, `revCount`, `newCount` in that order. Every deck read `[0,0,20]` tonight, matching the deck picker's 20 new and nothing due, so the phone alone couldn't tell learn from review.
- **Section numbers repeat across Textbook 1 and Textbook 2**, so "Learn vocabulary column 1.2" can't be mapped from the number alone. The regex needs to know which textbook is current (Q18).

## 4. Gmail: IMAP

- Login with the app password worked. Capabilities include `X-GM-EXT-1` (Gmail's message and thread ids and labels) and `IDLE` (the server can push new mail); there's no `CONDSTORE`.
- Folders: `INBOX`, one user label (`Parkrun Results`), and `[Gmail]/All Mail`, `Bin`, `Drafts`, `Important`, `Sent Mail`, `Spam`, `Starred`. **There is no Snoozed folder or label over IMAP**: a snoozed message simply leaves `INBOX` and comes back when it wakes, so it can't be told from an archived one in the meantime. Both count as "not now", and a message that returns reopens its task. That is the deferral you wanted, at no cost.
- 24 messages in INBOX, 21 unread. The 14 notes you sent yourself carry the `\Sent` label, whether sent as `thomasawhite321@gmail.com` or as `tom@thomaswhite.me`, so "from me" needs no address list.
- Every message has `X-GM-MSGID` and `X-GM-THRID`, which give a stable id for the task and a link into Gmail.
- The probe opened the inbox with `EXAMINE` and fetched headers with `BODY.PEEK`, so nothing was changed or marked as read. No inbox content is committed; the shapes are described here instead.

## 5. Blocking and the settings guard

The focus service was switched on over adb (`settings put secure enabled_accessibility_services …`, appended to your four), tried, and switched off again afterwards: the spike blocks YouTube whenever it's on, whatever is due.

- **Blocking.** Acting on window-state events alone, the block screen went up **0.62–0.66 s** after YouTube came to the front, so YouTube's splash showed for about half a second. Nearly all of that is Android delivering an app's first window-state event late (0.6 s, Settings the same; 0.1 s for screens changing within an app). Also acting on *windows-changed* events, and blocking whatever owns the active window, brought that down to **0.21 s**. Back on the block screen went to the home screen, not to YouTube.
- **Late events.** A blocked app keeps sending events after it's covered or left: YouTube's own UI replacing its splash behind the block screen, and, after **Go to the home screen**, its bedtime-reminder snackbar 1.5 s later. The service only blocks an app that still owns the active window, so that snackbar was left alone instead of covering Home.
- **Guard, the service's page.** One UI shows: the title, an **On** switch bar (`sesl_switchbar_switch`), a **Decrastination focus shortcut** toggle, *Settings*, *App info*, and the description. Opening it from *Installed apps* was answered with Back about 0.2 s later, on the first content change, and Settings was back on the list.
- **Guard, App info.** This app's *App info* (Open, Uninstall, Force stop) was left within 0.6 s; YouTube's (Open, Disable, Force stop) was left alone, as was the *Installed apps* list, which names every service.
- **Guard, uninstall.** The uninstall prompt for this app (`ACTION_DELETE`; One UI shows *Uninstall this app?*, the app's name, **Cancel** and **Uninstall**) was dismissed at once; the app stayed installed. The rule needs both the app's name and "uninstall", so the installer's install and update prompts stay usable.

**Found: three ways around the guard.**

1. **Accessibility shortcuts.** *Settings → Accessibility → Accessibility shortcuts → Side and Volume up buttons* (and, presumably, the other two shortcut types) lists *Decrastination focus* among the services it can switch. Ticked there, a key press turns the blocker off without ever opening its guarded page. Phase 3 must cover this: back out when that row is tapped in a shortcut chooser, and have the watchdog check `accessibility_shortcut_target_service` and `accessibility_button_targets`.
2. **`uiautomator` and other UiAutomation tools** run over adb pause every accessibility service while they run (the focus service disconnected and reconnected around each screen dump). adb already defeats the blocker, so this changes nothing, but it explains gaps in logs taken while debugging.
3. **A second user profile exists** (user 150, which `pm` couldn't access: most likely Secure Folder). Apps installed there are outside the blocker, as PLAN.md §7 already accepts.

**Found: covering YouTube sent its video into picture-in-picture.** In one of these tests YouTube had resumed a Short, and the block screen went over it while it played. Android took the launch as you leaving YouTube, so YouTube moved the video into a floating picture-in-picture window (`mWindowingMode=pinned`): over the block screen, and after **Go to the home screen** over everything else, still playing, for 20 minutes until you spotted it. What came of it:

- **The block screen is started with `FLAG_ACTIVITY_NO_USER_ACTION`**, which tells Android this isn't the user leaving the covered app: it gets no `onUserLeaveHint` and doesn't enter picture-in-picture automatically. Retested: a Short covered while playing, then **Go to the home screen**, left no floating window.
- **A blocked app already in picture-in-picture** (sent there before blocking began) is caught too. The floating window is never the window in use, so the service looks through every window for one. On One UI its root is the app's own view, offering no dismiss action, so the service relaunches the app to full screen, as tapping its icon does, and the block screen covers it: 0.18 s on the phone, with nothing left floating.
- **The Teams widget can't work under a floating window.** With that video still floating, a row tap opened Teams on Assignments underneath, but the widget reads the window in use, the video's, and stopped after 20 s with *Couldn't open Teams Assignments*. It could read Teams' own window instead; until then, the block screen's **Refresh Teams** should say so when it fails.
- **Every test that opens a blocked app now ends** by force-stopping it and checking that no window is left in picture-in-picture.

**For Phase 3 (Q19, decided 8 Oct):** grant Decrastination `WRITE_SECURE_SETTINGS` once over adb (`adb shell pm grant com.thomaswcode.decrastination android.permission.WRITE_SECURE_SETTINGS`). With it, the watchdog can switch the focus service straight back on and remove it from any shortcut, instead of only emailing about it. It survives reboots and updates, and is lost only by uninstalling, which the guard and the device admin resist.

## 6. What this changes

- **Phase 1** starts from this app: the Gradle setup, the shared signing key, CI and the provider readers exist. The probe package stays as the debug screen until Phase 3 replaces the spike.
- **Teams:** syncs work again with a paged Past due list (widget 0.3.1).
- **Power Planner:** read class names and timetables from `GetClassesAndSchedules`, resolve each item's time option against the two-week timetable, and treat the semester id as "no class".
- **AnkiDroid:** map section numbers to `Textbook N::x.y` using a current-textbook setting, starting at Textbook 1 (Q18, decided).
- **Gmail:** use `X-GM-MSGID` as the task id; `\Sent` marks your own notes; a message leaving and later re-entering INBOX is a deferral, not two tasks.
- **Anti-tamper:** add the shortcut route to the guard and the watchdog; the watchdog gets `WRITE_SECURE_SETTINGS`, granted over adb at setup, to undo it (Q19, decided).
- **Blocker:** start the block screen with `FLAG_ACTIVITY_NO_USER_ACTION`, and check every window, picture-in-picture included, not only the one in use.
