# Inventory: what the phone holds and does, and where each piece goes

Read from the code on 31 Oct 2026 (4.10.0, versionCode 96) for this plan. The right-hand columns say where each piece lives once the plan is built: **server** (held in SQLite, synced to both clients), **phone** (stays on the device, never uploaded), **both** (one copy on the server, a replica on the phone). `docs/spec/storage-and-backups.md` §The files has each store field by field.

## Stores

All built in `data/Stores.kt`, written whole and atomically by `data/JsonStore.kt`, each with a version and migrations run on the JSON text before decoding. Writers are as `ArchitectureTest.WRITERS` names them (owner first).

| File | Type | Ver | Holds | Writers | After the plan |
|---|---|---|---|---|---|
| `tasks.json` | `TaskState` | 4 | every task with text, steps, readings and `mine` overrides; per-source status; `ankiDay`, `deckTopics`, `unrewarded`, the Power Planner timetable, `weeksDeleted` | sync/Sync, sync/Syncer, enrich/Readings, tasks/Instructions, tasks/OwnTasks, block/Focus, block/Photos, felix/Conversation, felix/Looks | **both**: the server's is the truth; Teams and Anki rows reach it from the phone as observations, Power Planner and Gmail from the server's own syncs |
| `settings.json` | `Settings` (core) | 4 | about 50 fields: hours and windows, planning, blocklists, Felix, Claude (`aiEnabled`, `aiMonthlyCapUsd`), calendar ids, Teams auto-sync, `armed`, `dayHourDp` | protect/Protection only | **both**: changed on either client through Protection on the server, so a loosening waits its 24 h wherever it was asked (`architecture.md` §Protection on the website) |
| `runtime.json` | `RuntimeState` | 3 | notification counts and held list, `dailyRanAt` and retries, `TeamsAutoSync.State`, `lastBackupAt`, `notSame`, unreadable files | notify/Notifier, AppGraph, block/FocusService, learn/Briefing, learn/Daily | **phone**, except `notSame` (a judgement the website can make too), which moves to `tasks.json` in stage 0; the server has a `runtime` of its own for its jobs' `lastRanAt` |
| `session.json` | `SessionState` | 3 | `Credit` (free time), today's `SlotState`, `photosDone`, `keeping`, `rewarded`, `timer: PieceTimer?` | block/Focus, block/SlotAlarms, plan/Planning, tasks/Instructions | **both**: the slots are the plan, the timer must show on both; each 30 s spend of free time is a command in the outbox, merged with the last until the upload, so a pull can't rewind the credit (W-24; `data-and-sync.md` §Observations) |
| `protection.json` | `ProtectionState` | 1 | pending loosening changes, `overrideUntil`, `adminArmed`, `codeLock`, watchdog state, `forceActiveUntil`, `uptimeMark` | protect/Protection, protect/Watchdog | **split in stage 0** (4.13.0): the pending changes (with `uptimeMark` beside them, read by the phone alone until stage 2), `overrideUntil` and `codeLock` to a synced `protection.json` the server enforces; `adminArmed`, the watchdog's state and `forceActiveUntil` to a phone-only `device.json`. From stage 2 the server counts the wait by its clock, each pending change given a `dueAt`, and `uptimeMark` goes (W-23) |
| `model.json` | `ModelState` | 1 | `aiUsage` (the month's cost, rests, key problem), the last 100 `CallRecord`s, `enrichFailed`, `droppedAlerted`, `microPlans` | enrich/ModelGateway, plan/Ahead | **server** from stage 3 (the gateway moves); the phone reads it as a replica for Settings → Claude → Recent calls |
| `anki.json` | `AnkiState` | 1 | deck count readings, `ankiFrontSince`, `ankiWorked` | block/AnkiWatch | **both**: written on the phone only, uploaded as observations so the server derives the Anki tasks (`core/AnkiRules.kt`) |
| `learning.json` | `LearningState` | 1 | `calibration` | learn/Learning, block/AnkiWatch, AppGraph | **server**: learnt from the log on the server from stage 3 |
| `log.json` | `ActivityLog` | 4 | slots, completions, blocks, protection lapses, days, check-ins, weekly reviews, week rollups; 120 days, weeks 104 | learn/Learning, learn/Briefing, block/Focus, protect/Watchdog | **both**: slots and blocks arrive from the phone as observations; completions and check-ins from either client as commands |
| `instructions.json` | `InstructionState` (core) | 6 | the owner's and Felix's instructions and changes, the weekly rules (D-124) | tasks/Instructions, felix/Conversation | **both** |
| `chat.json` | `ChatState` (core) | 13 | the live window (newest 200 or 14 days), `readAt`, `FelixState`; ratings and unsent texts on each message | felix/Conversation, felix/Looks, felix/Outbox, tasks/Instructions, tasks/OwnTasks | **both** |
| `chat-archive-<yyyy-MM>.json` | `ChatArchive` (core) | 12 | messages past the window, a file a month, read lazily | its own store class (`data/ChatArchiveStore.kt`), outside `WRITERS` | **both**: a row a month on the server; the website reads a month on scroll-back as the phone does |
| `school.json` | `SchoolCalendar` (core) | 1 | `noSchool` ranges, `halfDays` | tasks/Instructions, felix/Conversation | **both** |
| `memory.json` | `MemoryState` (core) | 2 | facts and rules, each able to expire | felix/Conversation, learn/Learning | **both** |
| `felix.json` | `FelixStore` | 3 | notes, and reminders with their drafts | felix/Conversation, felix/Looks | **both** |
| `calendar.json` | `CalendarState` | 2 | fingerprints of written events (2,000), slot URI to start, the calendar written to, calendars to clear, `eventAnswers`, `eventsAsked` | calendar/CalendarWriter, sync/Sync | **phone**: it describes one device's calendar. `eventAnswers` are learnt and would be worth moving later; not in this plan |
| `gmail-writes.json` | `GmailWriteStore` | 1 | the journal of Gmail unstar and archive writes and their undo receipts | sync/GmailFinishes | **server** from stage 3, with the Gmail sync |
| `secrets.bin` | `SecretStore` | — | AES-256-GCM under an AndroidKeyStore key: Power Planner login, Gmail app password, Anthropic key, TOTP secret | — | **phone** keeps the TOTP secret and gains the device token; the Power Planner and Gmail credentials and the Anthropic key move to the server in stage 3 (`architecture.md` §Secrets) |

New in the plan: on the phone `outbox.json` (commands not yet accepted) and `sync.json` (the last revision taken, the device's id); on the server the tables in `data-and-sync.md` §The database.

## Features and screens

There is no Compose Navigation: several Activities, and `ui/MainActivity.kt`'s three tabs (`Day`, `Work`, `Stats`) with a menu (`This week`, `Reviews`, `Settings`, `Debug`).

| Feature | Where (phone) | On the website | Stage |
|---|---|---|---|
| Chat with Felix, the app's home (D-121): messages, cards with Undo, replies, chips, the archive on scroll-back, voice input | `ui/AskActivity.kt`, `ChatScreenState.kt` | the home page; typed input (the browser's speech API is a maybe, not planned) | 5 |
| Day: week strip, DO NOW card, the day as a grid with pinch zoom and drag | `ui/DayScreen.kt`, `DayGrid.kt`, `DayModel.kt`, `DayLayout.kt`, `DayDrag.kt` | read-only in stage 1; drag, the card's actions and the timer in stage 6 | 1, 6 |
| Work: trouble card, open tasks by due date, Same? pairs, New task, a photo into tasks | `ui/WorkScreen.kt`, `Groups.kt`, `PhotoTasksSheet.kt` | read-only in stage 1; editing in stage 4. The photo path takes an upload instead of the camera | 1, 4 |
| Task sheet, own-task form, move sheet, Gmail finish views | `ui/TaskSheet.kt`, `OwnTaskForm.kt`, `MoveSheet.kt`, `GmailFinishViews.kt` | stage 4 | 4 |
| Timer on a piece (D-112) | `block/Focus.kt` owns `PieceTimer`; `block/TimerAlarms.kt` | start and stop from the card and the sheet; the phone keeps the alarms and notifications | 6 |
| Stats: the fortnight, the calibration, CSV export | `ui/StatsScreen.kt`, `learn/Stats.kt`, `learn/Export.kt` | stage 7 | 7 |
| This week: the Sunday check-in, the latest review | `ui/CheckInActivity.kt` | stage 7 | 7 |
| Reviews, each change with Undo | `ui/ReviewsActivity.kt` | stage 7 | 7 |
| Settings: About you, Hours and windows, School, Planning, Blocking and protection, Felix, Claude, Sources, Calendar, Notifications, Backup, Reset | `ui/SettingsActivity.kt`, `SettingsScreenState.kt`, `SettingsEffects.kt` | stage 4 for the pages that are the shared state; Blocking's device parts, Calendar and Notifications stay phone-only pages | 4, 7 |
| Setup: Saved data, Sources, Claude, This phone, Backup | `ui/SetupActivity.kt`, `SetupScreen.kt` | Sources and Claude on the website from stage 3 (credentials to the server); the phone's Setup → This phone gains the pairing step, and its Sources and Claude lose the credentials the phone no longer holds | 1, 3, 7 |
| Protection: arming, the parent TOTP code, pending changes | `protect/ProtectionActivity.kt` | the pending changes shown; arming and the code stay on the phone | 4 |
| Block screen, OpenTaskActivity, Debug, adb commands | `block/BlockedActivity.kt`, `ui/OpenTaskActivity.kt`, `DebugActivity.kt`, `debug/CommandActivity.kt` | no | — |
| Widget (Glance) | `widget/NextWidget.kt`, `WidgetModel.kt`, `WidgetUpdater.kt` | no; it reads the phone's replica as now | — |
| Notifications: 11 channels through `notify/Notifier.kt`, ids in `NoticeId.kt`; Felix's messaging-style one with inline reply | `notify/*`, `felix/FelixNotifications.kt`, `FelixReplyReceiver.kt` | in-page (tab count, bell, unread marker) from stage 5; Web Push optional in stage 9 | 5, 9 |
| Backups and resets | `data/Backup.kt` (format 10), `BackupWorker`, `data/Reset.kt`, `BackupService` | the server writes the nightly backup and runs resets from stage 3; the pages in stage 7 | 3, 7 |

## Integrations

| Integration | How it works today | Needs the device? | After the plan |
|---|---|---|---|
| Teams | the separate Teams Assignments widget app drives the Teams app by accessibility (its README: the Microsoft Graph API would need the school's IT admin to approve a permission) and offers the rows through a signature-protected content provider; `sources/teams/TeamsSource.kt` reads them, `block/TeamsAutoSync.kt` asks for syncs | yes | the phone reads and uploads the rows as observations; the server merges them (`core/Merge.kt`) |
| Power Planner | JSON posts to its web API with the owner's username and password (`sources/powerplanner/PowerPlannerApi.kt`); `scripts/powerplanner_probe.py` already does it from a PC | no | the server, from stage 3 (W-8) |
| Gmail (starred) | IMAP with a Google app password (`sources/gmail/*`); one write, the finish (unstar and archive, D-106), through `sync/GmailFinishes.kt` and `GmailWriteWorker` | no | the server, from stage 3 (W-8), the finish included |
| AnkiDroid | content provider behind a runtime permission (`sources/anki/AnkiSource.kt`); `block/AnkiWatch.kt` re-reads as AnkiDroid comes and goes | yes | the phone reads and uploads the counts; the server derives the tasks |
| Calendar | `CalendarContract`: reads busy time (`learn/CalendarTime.kt`, `EventJudge.kt`), writes the next 7 days' slots (`calendar/CalendarWriter.kt`), treats hand moves as the owner's choice | yes, as built | the phone, from the server's confirmed slots only, never from a provisional offline plan |
| Blocker | accessibility service `block/FocusService.kt`, rules in `Focus.kt` and `BlockPolicy.kt`; the 30 s tick also spends free time, sweeps a slot whose alarm couldn't ring, applies due setting changes, runs Felix's look and the watchdog, and offers Teams syncs | yes | the phone, from its replica, offline included |
| Protection | device admin, watchdog (`WRITE_SECURE_SETTINGS` over adb), the 24 h delay (`SettingsChanges.kt`), the TOTP code (`Totp.kt`) | the device parts yes; the delay and the code are pure | the delay enforced on the server for every client; arming, the watchdog and the code on the phone |
| WorkManager jobs | `SyncWorker` (15 min and on demand), `WatchdogWorker`, `BackupWorker` (24 h), `EnrichWorker`, `GmailWriteWorker`, `FelixWorker`, `FelixReplyWorker`, `ReminderWorker`, `ReviewWorker` | no | `SyncWorker` becomes the sync (push the outbox, pull revisions, upload observations); the enrich, Gmail, Felix, reminder and review jobs move to the server's scheduler in stages 3 and 5; the watchdog and backup stay |
| Alarms (`setExactAndAllowWhileIdle`) | `learn/Daily.kt` (briefing, check-in, review), `block/SlotAlarms.kt`, `block/TimerAlarms.kt`, the watchdog | yes, for delivery | the phone, scheduled from the replica; the texts they show come from the server where Felix writes them |
| The weekly summary email | an `ACTION_SEND` intent to a mail app (`learn/Summary.kt`) | no | unchanged on the phone in this plan |

## Claude

- One place: `ModelGateway.call(...)` in `enrich/ModelGateway.kt`, with the hold, cap and rest checks under a lock; `ModelGateway.build` the only place a client is built (`ArchitectureTest`).
- Jobs (`enrich/ModelEffort.kt`): high effort Email, Assignment, Effort (the readings), Instruction, Review, PhotoToTask; medium Chat, Reply, Felix, MicroPlan, PhotoCheck, Summary, Tidy, Research (web search, its own call). Moods (`felix/Mood.kt`, D-119) are pure.
- Model `claude-opus-5-5` for every job, through `com.anthropic:anthropic-java` 2.71.0; structured output with the server fallback on; the system prompt cached for an hour.
- Cap: `aiMonthlyCapUsd`, none by default (D-91), checked at $1.35 a call; rests after failures as `ModelHold.kt` sets them. Record: the last 100 `CallRecord`s in `model.json`.
- Goldens: 27 files in `app/src/test/resources/prompts/`, held by `GoldenPromptsTest`; `ReadingPrompts.VERSION` is 9.
- The key comes from `secrets.bin`, typed in Setup or loaded by `scripts/load_credentials.py` over adb.

After the plan: the gateway, the jobs, the goldens and the record are the server's; the phone asks the server. `architecture.md` §Claude.

## The `:core` module

38 files, about 7,830 lines, under `core/src/main/kotlin/com/thomaswcode/decrastination/core/`; tests 15 files and 2 fixtures, about 3,150 lines. `kotlin.jvm` (with `java-library`, `java-test-fixtures` and Android's lint plugin, for checking only) with `kotlinx-serialization-json` its only dependency; no Android. It leans on `java.time` in about 15 files, `java.util.Locale`, `java.security.MessageDigest` and `String.format`, so it runs on a JVM server as it is and would be a port to JS or Wasm (W-4).

Pure logic in the app module, which stage 0 moves to `:engine`: `felix/FelixRules`, `Replies`, `Story`, `Mood`, the `*Prompts` and `*Answers` files, `learn/Calibrator`, `ReviewInput`, `block/BlockPolicy`, `TeamsAutoSync`'s decisions, `protect/Totp`, the owners (`sync/Sync`, `tasks/OwnTasks`, `tasks/Instructions`, `felix/Conversation`, `plan/Planning`, `block/Focus`'s rules, `protect/Protection`), `data/Stores`, `JsonStore`, `Backup`, `Reset`, `enrich/ModelGateway`.

## Build facts

`app/build.gradle.kts`: versionName 4.10.0, versionCode 96, compileSdk 36, minSdk 26, targetSdk 36, JDK 17. `gradle/libs.versions.toml`: AGP 9.0.1, Kotlin 2.2.10, Compose BOM 2026.06.01, Glance 1.2.0, WorkManager 2.11.2, coroutines 1.10.2, kotlinx-serialization 1.9.0, anthropic-java 2.71.0, Jackson BOM 2.21.7. Gradle 9.3.1. Modules `:app` and `:core`. CI: `ci.yml` (Python compile check, then `testDebugUnitTest lintDebug assembleDebug`, reports and APK uploaded), `claude.yml` (the reviewer), `author-check.yml`.
