# Stages: the steps in order

The most technically feasible order (W-11): the foundation first, then the sync, then the server's jobs, then the website's screens from the simplest to the most interactive. The website isn't used until every stage is done, so no stage is placed early for the sake of early use; the phone, which is used every day, keeps working through all of them.

Each stage is one or more releases, each a PR as `docs/development.md` describes: a version bumped, the PR titled `X.Y.Z: what it does`, CI green, the stores' fixtures and migrations tested, the docs in the same PR, and the APK checked on the phone before the merge whenever the phone changes. Stage 1 onward also deploys the server: the merge to `main` builds and pushes the images and the box pulls them.

The estimates are hours of sessions, from the durations measured over the 4.x releases (§Estimates); they are starting points, revised as each stage finishes.

## Stage 0: `:engine`

**Goal.** The app's Android-free code in a JVM module that `:app` and, later, `:server` both build. No behaviour changes in the moves; every test passes as before. Releases 4.12.0 to about 4.12.3, cleanups, one move each (4.11.0 is the chat's kept words and Day's menus), then 4.13.0 for step 6's store changes.

**Work.**
1. The module: `engine/build.gradle.kts` (`kotlin.jvm`, JVM target 17 as `:core`'s, `kotlinx-serialization`, `anthropic-java`), `settings.gradle.kts`, `:app` depending on it.
2. The data layer: `data/Stores`, `JsonStore` with the new `StoreBackend` (the file behaviour as `FileBackend`), `Backup`, `Reset`, every migration and `fixtures/stores/`. Android's file paths and `MediaStore` stay in `:app` behind the backend and a `BackupFolder` port.
3. The owners and their ports: `sync/Sync`, `tasks/OwnTasks`, `tasks/Instructions`, `plan/Planning`, `plan/Ahead`, `block/Focus`'s rules (the service stays), `protect/Protection` (the delay, the TOTP), `learn/Learning`, `learn/Calibrator`. Where one touches Android (a notification posted, an alarm set, a string resource, `Log`), an interface in `engine/.../ports/` and an implementation in `:app`.
4. Felix and the readings: `felix/Conversation`, `Looks`, `Outbox`, `FelixRules`, `Replies`, `Story`, `Mood`, every `*Prompts` and `*Answers`, `enrich/ModelGateway`, `ModelEffort`, `Readings`, `ReadingPrompts`, `WebResearcher`; the goldens and `GoldenPromptsTest`, `SchemaCompileTest`, `FelixInputsDocTest` move to `engine/src/test/`.
5. The screens' models: `DayModel`, `WidgetModel`, `Groups`, `TaskSheetModel`, `learn/Stats`'s tables, `ReviewInput`, `BlockPolicy`, `TeamsAutoSync`'s decisions: anything a screen computes before it draws.
6. Release 4.13.0, a minor of its own as it changes stored data: two store shapes change, with migrations tested from every fixture. `protection.json` splits into the synced store (the pending changes with `uptimeMark` beside them as today, so the count stays one atomic write; `overrideUntil`; `codeLock`) and a phone-only `device.json` (`adminArmed`, the watchdog's state, `forceActiveUntil`); the phone goes on counting the wait exactly as now until stage 2 (W-23). `runtime.json`'s `notSame` moves to `tasks.json`. The backup format rises to 11 (`device.json` excluded, as runtime is).
7. `ArchitectureTest`: every owner in `:engine`; `:app` holds none; `WRITERS` paths updated.

**Tests.** The existing 1,500-odd tests, now split across `:core`, `:engine` and `:app`; `./gradlew test` runs them all. The migration tests for step 6.

**Docs.** `docs/plan_v4/architecture.md` §The shape and §The platform gain `:engine`; `docs/spec/storage-and-backups.md` the split and format 11; `docs/development.md` the module in its build commands.

**Phone.** One install and check per release (the usual `scripts/phone-check.sh`): the services bound, the blocker blocking, a backup written. Step 6's release gets the rehearsal (`scripts/rehearse.sh`) on a real backup first.

**Done when.** `:app` has no owner, no prompt, no migration; `:engine` builds with no Android dependency; every test passes; the phone runs 4.13.0 with nothing changed for the owner.

**Risks.** Hidden Android dependencies inside owners that need a port each (the first move finds them; each is small). The moves in commits, code first, nothing improved on the way (`CLAUDE.md`'s rule for moves: move first, improve after, a commit each).

**Estimate.** 8 to 12 hours: five releases of about 45 minutes' moving (a 4.x cleanup of ~1,000 lines took 16-24 minutes; these are bigger) and 30 minutes' reviews, plus the two migrations with their fixtures and rehearsal.

## Stage 1: the server skeleton, mirror mode, the read-only website

**Goal.** The server runs on the box with the owner's state mirrored into it; the website signs in and shows the Day and the Work list, read-only, as the phone shows them. Release 5.0.0.

**Work.**
1. `:server`: Ktor 3.6 with the `io.ktor.plugin` (`buildFatJar`), `sqlite-jdbc`, the tables (`data-and-sync.md` §The database) and `SqliteBackend`, `meta.mode = mirror`.
2. Sign-in: the GitHub App, `/api/auth/*`, sessions, the allowed user id; device pairing, `/api/devices/*`, the token hashed.
3. `POST /api/stores/seed`, `GET /api/state`, `GET /api/events`, `GET /api/views/day`, `GET /api/views/work`, `GET /api/health`, `GET /api/version`.
4. The phone: Setup → This phone → the pairing step, each batch saying the phone's time zone (which the server keeps to, as a day is the owner's wherever they are); `sync/Replica` and the upload: every synced store's write (coalesced to one upload a minute, and at once on the app's foreground and the 15-minute `SyncWorker`) posted to `seed`; Setup → This phone shows the last sync and the server's version; a 409 on versions shows there too.
5. `web/`: the scaffold (Vite, React, TypeScript, ESLint, vitest, Playwright), sign-in, the Day page and the Work page rendering `/views/*`, the events stream reloading them.
6. `deploy/`: `compose.yaml`, `Caddyfile`, `litestream.yml`, `bootstrap.sh`, `decrastination-update.{service,timer,sh}`, `env.production.example`, adapted from SharpnPro's; the Actions job that builds the two images and pushes them to GHCR.
7. The box: created (the owner, at the Oracle console: A1.Flex 2/12, Ubuntu 24.04, the home region; retried if out of capacity), bootstrapped, `.env` filled, the DNS A record at Spaceship, the GitHub App made with the callback, the R2 bucket and keys.
8. The restore drill (`data-and-sync.md` §Backups).

**Tests.** `:server`'s route tests with Ktor's `testApplication` over an in-memory SQLite; the backend's transaction and history tests; sign-in refused for another user id; the token's hash. `web/`: vitest on the view components; Playwright against the server in a `--test` mode (fixtures loaded, sign-in stubbed).

**Docs.** New `docs/spec/server.md` (the API, the modes), `docs/spec/website.md`, `docs/spec/sync.md` (mirror mode for now); `docs/development.md` gains the server's and the web's builds and the deploy; `docs/decisions.md` gains W-1 to W-11 as D-numbers.

**Phone.** 5.0.0 installed; the pairing done; the uploads seen in the server's log and the website; the phone's network turned off and on to see nothing changes for it.

**Done when.** The website shows today's plan as the phone does, and a change on the phone shows within a minute; the restore drill passed; the server's memory measured and written into `hosting.md`.

**Risks.** Oracle's capacity (the owner's time, not the plan's); the JVM's memory on the box (measured here); the GitHub App's callback and cookie details (edit.thomaswhite.me's Worker is the worked example).

**Estimate.** 10 to 14 hours, plus the owner's hour or two at the consoles. New ground (no server or web has been built in this repository), so ±50%.

## Stage 2: commands

**The order changed in building it (W-25).** Everything below is built here, dormant, but for what's the last release's, not this stage's: the switch itself (step 2's `commands` mode, step 6's clock), and step 3's provisional slots and seven-day line. 5.1.0 is the command layer on the phone with nothing behaving differently.

**The switch's own step.** A phone mirroring queues nothing, so what it changed since its last accepted seed is on the phone alone, and the first pull would write over it. So the switch is a final seed: the server, told to go over, takes one more whole seed from the phone and sets `meta.mode` in that seed's transaction, and the phone goes over with its revision from the answer, needing no pull at all. It writes a backup of its own first, and refuses a first pull from a server older than the hashes it last sent (W-26). A harness case holds a change made after the last seed through the switch. Observations and the free-time spends as commands (W-24) land with it, as a pull otherwise writes over what the phone writes outside commands: Focus's half-minute writes to `session.json`, the Teams and Gmail syncs, Felix's background writes.

**Goal.** Every change is a command the server applies through the owners; the phone's outbox and offline rules; the website live. Release 5.1.0.

**Work.**
1. `engine/.../sync/protocol/`: the command classes, one an owner operation, with the test that lists both sides; the observation kinds.
2. `CommandApplier` on the server: the transaction, the `Mutex`, idempotence by id, outcomes; `POST /api/commands` and `/api/observations`; the switch to `commands` mode in the release's migration after the phone's last full upload.
3. The phone: the recording of owner calls into `outbox.json` (the mechanism settled here: interfaces with a recording implementation, or the owners writing to an outbox port), the 30 s free-time spends among them, merged until the upload (W-24); `SyncWorker` posting the outbox in order, then pulling: the server's stores replace the replica (`Stores.replace` from `sync/Replica`, named in `ArchitectureTest`), only with the outbox empty; the provisional flag on slots the phone planned offline and `CalendarWriter` skipping them (W-15); the dropped-command card; the seven-day line.
4. The table of stale cases, command by command, with its tests.
5. The sync harness (`data-and-sync.md` §Testing the sync) and its scenarios.
6. Protection's wait counted by the server's clock (W-23): `PendingChange` gains the `dueAt` the server sets, the server's scheduler applies what falls due, the phone shows the list and applies nothing itself; `uptimeMark` dropped, the store's version raised and migrated.

**Tests.** The harness; the applier's; the phone's outbox under a killed process (a command recorded, not applied, applied once on restart); a command recorded mid-sync still shown after the pull; a batch whose first response was lost, retried, with identical outcomes.

**Docs.** `docs/spec/sync.md` in full; `data-and-sync.md` here updated with what the stale table found.

**Phone.** 5.1.0 installed; the offline test from `README.md` §Done when, step by step, with the owner: network off, a task added, a task finished, a slot moved, the timer started; network on; the website checked; the calendar checked for no provisional events.

**Done when.** The harness's scenarios pass; the phone's offline test passes; the server's `commands` table shows every change with its outcome.

**Risks.** The hardest stage: where owner calls are recorded, and the stale table's completeness. Nothing is deleted on the phone in this stage, so a mistake loses no data.

**Estimate.** 12 to 16 hours, ±50%.

## Stage 3: the server's jobs, Claude on the server, the credentials

**As built (W-25).** The jobs, the credentials and the nightly file are 5.3.0, dormant until the switch; the phone's workers stop, and its Setup drops the entries, in the switch's release. The website's page for the credentials comes with its Settings pages in stage 4.

**Goal.** The state is fresh while the phone sleeps; Claude is called from one place on the server; the credentials that can leave the phone have. Release 5.2.0.

**Work.**
1. The scheduler and the jobs: the Power Planner and Gmail syncs (their sources run on the JVM as they are; the IMAP client checked on JRE 21), the readings (`EnrichWorker`'s work), `Ahead`, the memory tidy, the daily briefing's and weekly review's texts at their times, the nightly Backup-format file to B2 (W-20), the heartbeat.
2. `ModelGateway` on the server with `ANTHROPIC_API_KEY`; the phone's Felix calls as commands (a look with its context, a reply, a photo); the Gmail finish on the server.
3. The `credentials` table under `MASTER_KEY`; the website's Setup → Sources and Claude pages; the phone's Setup drops the Power Planner, Gmail and Anthropic entries once the server reports it holds them (a `DeviceStatus` round-trip), and deletes its copies.
4. The phone's workers that did these go: `EnrichWorker`, `GmailWriteWorker`, `FelixWorker`, `FelixReplyWorker`'s call, `ReviewWorker`'s call, `ReminderWorker`'s text; the scheduling of their notifications stays.
5. The `FelixInputsDocTest` and the goldens run in `:engine` as before; `SchemaCompileTest` live against the API from the server's module (about a cent a schema, the owner's rule).

**Tests.** The scheduler's due-time rules on a test clock; the sources' tests as today; the backup file's round trip through the phone's `Backups.decode`.

**Docs.** `docs/spec/sources.md` (where each sync runs), `docs/spec/readings.md`, `docs/spec/felix.md` (the calls' place), `docs/spec/storage-and-backups.md` (the nightly file, B2), `docs/spec/privacy.md` (what the server holds).

**Phone.** 5.2.0 installed; the credentials re-entered on the website; the phone's Setup checked to hold none; a message to Felix answered through the server; Settings → Claude → Recent calls showing it on both.

**Done when.** With the phone's network off for an hour, the website shows a new Power Planner item and a newly starred email; the phone holds no Anthropic key; the month's spend reads the same on both.

**Estimate.** 8 to 10 hours.

## Stage 4: the website writes

**As built.** Dormant, as the rest is till the switch (W-25): the website shows all of it, with nothing to press while the server mirrors. 5.4.0, as 5.2.0 and 5.3.0 took the numbers before it. The server takes the TOTP secret from the phone (`POST /api/parent-code`), but the phone sends it only with the switch, so nothing leaves the phone before then. The photo path reads tasks from a photo; a photo kept with a task stays the phone's.

**Goal.** Work and Settings on the website as on the phone. Release 5.3.0 (the phone changes little: the TOTP secret's copy to the server).

**Work.** The Work list's actions (Done and Undo, Same? and Not same, New task, the own-task form, the move sheet), the task sheet with its instructions, the photo-to-tasks path by upload, the Settings pages that are shared state (About you, Hours and windows, School, Planning, Felix, Claude, Sources), the pending changes and the delay shown when armed, the TOTP code accepted (the secret on the server from this release).

**Tests.** Playwright for each action against the test server; the applier's outcomes shown as cards.

**Docs.** `docs/spec/website.md`; `docs/spec/blocking-and-protection.md` (the code verified on the server).

**Phone.** 5.3.0 installed; a loosening asked on the website seen pending on the phone with its 24 hours.

**Estimate.** 6 to 8 hours.

## Stage 5: Felix on the website

**As built.** Dormant, as the rest is till the switch (W-25): in mirror mode the chat's there to read, with nothing to press. 5.5.0, the numbers having moved on by one. The chat's rules moved out of the phone's screen into `:engine` (`ChatRows`, `Replies.turn`, `Outbox.unreadOf`), so both use them. Four things went otherwise than planned below. The archive is read by count, as the phone reads it, through `GET /views/chat?earlier=&reach=`, not by month: a month gives no way to page or to find a quote's message. Your changes sit beside the chat, not on a tab, as a wide page has the room. What he did says itself in a line beside its fold, where it's always shown, not in a label that slides out for five seconds. A message's own button, to the week's review or to send a summary, comes with stage 7, with the pages it opens. Claude in the browser tests is a stand-in of their own (`web/e2e/claude-stand-in.mjs`), not `scripts/claude_stub.py`, whose answer is an empty object: the chat needs answers its parser takes, and cards.

**Goal.** The chat as the website's home. Release 5.4.0.

**Work.** Conversation, Looks and the reminders already run on the server (stage 3); this stage is the page: messages, cards with Undo, replies, chips, the archive on scroll-back (`/api/chat/archive/{month}`), ratings and the unsent texts' pick, the read-at command, the in-page notices (the tab's count, the bell, the unread marker, W-9). The phone's chat is unchanged.

**Tests.** Playwright on the chat's flows with Claude stubbed (`scripts/claude_stub.py` already stands in for the Messages API); the read-at and unread logic.

**Docs.** `docs/spec/felix.md` §The app's home gains the website.

**Phone.** A message sent on the website seen on the phone at its next sync and vice versa; a rating on one seen on the other.

**Estimate.** 6 to 8 hours.

## Stage 6: the Day grid and the timer

**As built.** Dormant, as the rest is till the switch (W-25): in mirror mode the Day's there to read and a piece's sheet opens, with nothing to press or drag. 5.6.0. A drop isn't a `MoveSlot` command: it's `POST /day/drop`, which the server judges with the phone's own rule (`Moves.dropped`, moved out of `DayScreen`) and makes the commands that rule makes, as a task's moves are. Zoom is the wheel with Ctrl held, as a plain wheel scrolls the grid. A timer's Start is asked of the server, as the phone's also notes something done on its task. That the phone's alarms follow a timer started here needs a server taking commands, so it's the switch's.

**Goal.** The Day as on the phone, with drag, on a wide screen; the timer from the laptop. Release 5.5.0.

**Work.** The grid from `/views/day` (the layout maths in TypeScript; the model's from Kotlin), drag by pointer to move a piece (the `MoveSlot` command), zoom by wheel, the DO NOW card with its actions, Start and Stop on the card and the sheet (the `Timer` commands), the week strip. The phone's alarms follow the synced timer.

**Tests.** Playwright on drag and the timer; vitest on the layout maths against `DayLayout.kt`'s cases, ported as fixtures.

**Phone.** The timer started on the website seen counting on the phone's card, widget and notification; stopped on the phone, gone on the website.

**Estimate.** 6 to 8 hours.

## Stage 7: the rest of the pages

**As built.** Dormant, as the rest is till the switch (W-25): in mirror mode each page is there to read, with nothing to press. In two releases, the numbers having moved on by one. 5.7.0 has Stats, This week and Reviews, what they say moved out of the phone's screens into `:engine` (`StatsPage`, `ReviewPage`), and a message's own button from stage 5. The check-in's kept by `POST /week/check-in`, not a bare command, as the review it sets off is the server's to start. The CSV download is the zip the phone shares, for the website alone (`GET /record.zip`). A review's Send it is a `mailto:` link, as the server sends no email, with Copy beside it, as a long link can be cut short. 5.8.0 has Backup, Reset and Setup, what they say moved out of the phone's screens likewise (`BackupPage`, `SetupPage`). A reset is a command (`ResetData`), as `data-and-sync.md` has it, not the route the architecture named: a retry resets once, and the phone can send the same one at the switch. A backup taken in stays a route, as its file doesn't belong on record, held as a command is (`CommandApplier.alone`). A reset's copy stays on the box and goes on to B2 on its own time, not in the command's lock. Setup's *Got it* and the phone's own parts aren't on the website: what's listed is what the server couldn't read at this start, and that goes at the next. Revoking a phone asks first, and a sign-in typed in and cleared no longer takes the held one away.

**Goal.** Everything else the phone shows. Release 5.6.0.

**Work.** Stats (the fortnight and the calibration as tables and small charts; the CSV download), This week (the check-in's five questions, the latest review), Reviews with Undo, Backup (download the Backup-format file, import one), Reset (each kind, after a confirmation), Setup (Saved data, Sources, Claude, and a Phone page with pairing and revoking), the phone-only Settings pages listed as such.

**Tests.** Playwright on each page; the import's validation as `Backups.problem` gives it.

**Docs.** `docs/spec/website.md` complete; `docs/plan_web/README.md`'s "Done when" ticked.

**Estimate.** 5 to 7 hours.

## Stage 8 (optional): the phone told at once

**As built.** Not built: it needs a Firebase project, which is the owner's to make, and it only matters from the switch on, as till then the phone's the one writing.

**Goal.** A change on the server reaches the phone in seconds, not at the next 15-minute sync. Release 5.7.0.

**Work.** A Firebase project (the owner), `google-services.json` in `:app` (it holds project ids and a public key, as every app's does; nothing secret), the FCM token uploaded as an observation, the server sending a data message on each revision (coalesced to one a few seconds), the message starting an expedited `SyncWorker`. The 15-minute cycle stays as the fallback.

**Why optional.** The owner's day-to-day works without it; it matters once the website is used beside the phone. The alternative without Google (UnifiedPush with a self-hosted ntfy on the same box) is noted, not planned.

**Estimate.** 2 to 3 hours.

## Stage 9 (optional): Web Push

**As built.** Dormant, as the rest is till the switch (W-25): nothing's offered till the server applies commands and the box has its keys (`docker compose run --rm server vapid-keys`, into `.env`). 5.9.0, the numbers having moved on by two. `com.interaso:webpush`, as it brings nothing with it. Which channels is a browser's own, kept with its subscription, not a setting: it's about that browser, and a setting would go to the phone. A slot's start is new: the phone has no such notification, its alarm only making the plan again. A timer's zero is read, not marked told, or the phone's own "time's up" wouldn't sound. His message is told where it would sound on the phone (`Notice.alert`), so his notification made again tells nothing.

**Goal.** Felix's messages, slot starts and the timer reach a closed tab (W-9).

**Work.** A service worker in the SPA, `push_subscriptions`, VAPID keys in `.env`, `com.interaso:webpush` (or `nl.martijndwars:web-push`) on the server, a setting for which channels push to the browser so the owner isn't told twice; Chrome, Firefox, Safari 16+ on macOS, Safari on iOS only as an installed web app.

**Estimate.** 3 to 4 hours.

## Estimates

| Stage | Hours | Owner's time besides phone checks |
|---|---|---|
| 0 | 8-12 | — |
| 1 | 10-14 | 1-2 h: Oracle console, DNS, GitHub App, R2 |
| 2 | 12-16 | the offline test, ~30 min |
| 3 | 8-10 | re-entering credentials, 10 min |
| 4 | 6-8 | — |
| 5 | 6-8 | — |
| 6 | 6-8 | — |
| 7 | 5-7 | — |
| 8 | 2-3 | a Firebase project, 15 min |
| 9 | 3-4 | — |
| **All** | **66-90** with the optional stages; **61-83** without | |

The basis: the 4.x releases' measured durations (a cleanup move with its reviews ~35 min; a feature release's code 20-60 min and its reviews 30-140 min; a phone session's step 1-9 min). Stages 1 and 2 are new kinds of work for this repository, so they carry the widest range. Code has run under its estimate and reviews over it through the 4.x releases; the figure for each stage is revised in this table when it finishes.
