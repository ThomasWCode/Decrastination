# Architecture: the shape the code takes

What the repository looks like once the plan is built, and the rules that keep it so. `docs/plan_v4/architecture.md` describes the app as it is today; this file says what changes and what doesn't. Nothing here is built yet.

## The shape

```
                      decrastination.thomaswhite.me   (Caddy: TLS, one origin)
                      +---------------------------------------------------+
   laptop  --------->  /          web/   React + TypeScript SPA (static)   |
                      |  /api/*   -----> :server  Ktor on JVM 21           |
                      |                    | the owners, the planner,      |
                      |                    | Felix, the readings, the      |
                      |                    | syncs that need no device:    |
                      |                    | :engine + :core               |
                      |                    v                               |
                      |                 SQLite  --> Litestream --> R2      |
                      +---------------------------------------------------+
                           ^  commands, observations      |  revisions
                           |  (POST, retried in order)    v  (pull; SSE)
                      +---------------------------------------------------+
   phone  :app        |  replica: the JsonStore files, as today            |
                      |  outbox -> sync (SyncWorker)  <- pull              |
                      |  device duties: Teams rows, AnkiDroid counts,      |
                      |  the blocker, the calendar, notifications and      |
                      |  alarms, protection's device parts, the widget     |
                      +---------------------------------------------------+
                      Claude: the Anthropic SDK, from :server only
```

One state, on the server. Two clients: the phone, which keeps a replica and works from it offline, and the website, which reads what the server computes. Everything that decides something (what the plan is, what Felix says, whether a setting may loosen now) runs in Kotlin that both the phone and the server build.

## The modules

| Module | Kind | Holds | Depends on |
|---|---|---|---|
| `:core` | `kotlin.jvm`, as today | the domain types and the pure rules: the planner, merge, duplicates, the Anki rules, the instructions, the chat's types, the weekly rules | `kotlinx-serialization-json` |
| `:engine` (new, stage 0) | `kotlin.jvm` | everything in `:app` today that imports nothing from `android.*`: the owners (`sync/Sync`, `tasks/OwnTasks`, `tasks/Instructions`, `felix/Conversation`, `felix/Looks`, `plan/Planning`, `block/Focus`'s rules, `protect/Protection`, `learn/Learning`), `data/Stores`, `JsonStore` over a `StoreBackend`, `Backup`, `Reset`, the migrations, `enrich/ModelGateway` and the prompts, Felix's rules, the calibrator, the screens' models (`DayModel`, `WidgetModel`, `Groups`, `TaskSheetModel`), the sync protocol's types, and the **ports** the owners need from a platform (a clock, a notifier, a scheduler, a file for photos) | `:core`, `anthropic-java`, `kotlinx-serialization-json` |
| `:server` (new, stage 1) | `kotlin.jvm` with the `io.ktor.plugin` | the HTTP API, sign-in and device tokens, the SQLite `StoreBackend`, the command applier, the scheduler for the jobs, the ports' server implementations, the Docker image | `:engine`, Ktor 3.6, `sqlite-jdbc` |
| `:app` | Android, as today | the screens, the services and receivers, the workers, the device adapters (Teams, AnkiDroid, calendar, blocker, notifications), the ports' Android implementations, the replica (`FileBackend`), the outbox and the sync | `:engine`, Compose, Glance, WorkManager |
| `web/` (new, stage 1) | npm, outside Gradle | the SPA: Vite, React 19, TypeScript strict, vitest for units, Playwright against a test server; ESLint as the owner's other sites | the server's API alone |

The cut of `:engine` is the compiler's: a module with no Android dependency can't hold a line that needs one. Where an owner needs the platform (to post a notification, to schedule an alarm, to read the clock), it takes an interface from `:engine`'s ports, which `:app` implements with Android and `:server` with its own means. `docs/plan_v4/architecture.md` already names ports among the platform; stage 0 finds the rest.

## The server

A Ktor application (3.6.x, the CIO or Netty engine; both run on arm64 Linux) on a JRE 21, built as a fat JAR and copied into an `eclipse-temurin:21-jre` image (`hosting.md` §Deploy). It is one process with one writer: a `Mutex` serialises the application of commands from start to commit (`ModelGateway`'s lock, by contrast, guards only its hold check and its count of calls in flight; the calls themselves run side by side).

**Routes** (all under `/api`, all behind sign-in except `health`, `version` and the sign-in routes themselves):

| Route | Does |
|---|---|
| `GET /auth/github`, `GET /auth/callback`, `POST /auth/logout`, `GET /me` | sign-in with GitHub, one account allowed (§Sign-in) |
| `POST /devices/pair` (web), `POST /devices/claim` (phone, no sign-in: the code is the proof), `GET /devices`, `DELETE /devices/{id}` | pairing the phone, listing and revoking device tokens |
| `GET /state?since={revision}` | every store whose revision is above the one given, whole (`data-and-sync.md` §Revisions) |
| `GET /events` | a server-sent event stream of revision numbers; a client pulls `state` when one arrives |
| `POST /commands` | a batch of commands, applied in order through the owners; one outcome each |
| `POST /observations` | a batch of the phone's device-born facts, applied the same way |
| `POST /stores/seed` | stage 1 only: a whole store from the phone while the server is in mirror mode |
| `GET /views/day?date=`, `GET /views/work`, `GET /views/task/{id}`, … | the screens' models, computed on the server by the same Kotlin the phone's screens use (§The website) |
| `GET /chat/archive/{yyyy-MM}` | a month of the archive, as the phone reads one on scroll-back |
| `POST /photos` | a photo for the photo-to-tasks path (stage 4) |
| `GET /backup`, `POST /backup/import`, `POST /reset/{kind}` | the Backup-format file out and in, the resets (stage 7) |
| `GET /health`, `GET /version` | for Caddy, the heartbeat and the clients (the protocol version and each store's version) |

A route never writes a store. It turns the request into a command and hands it to the applier, which calls the owner; the same rule as "no screen is among a store's writers" (D-107), moved to the server.

**Jobs.** A scheduler loop (a coroutine, every minute) runs what is due, with each job's `lastRanAt` in the server's own runtime store: the Power Planner and Gmail syncs every 15 minutes (stage 3), the readings of new tasks, planning ahead, the memory tidy, the daily briefing's text and the weekly review's at their times, the nightly backup to R2. The phone's WorkManager jobs that did these go (`inventory.md` §Integrations); the phone's `SyncWorker` becomes the sync alone.

## The sync, in brief

`data-and-sync.md` has it in full. A client changes the state by sending a **command** (an owner's operation with its arguments, given an id by the client). The server applies it through the owner, in one SQLite transaction, which bumps the global **revision** and writes a history row. Clients pull the stores changed since the revision they last saw. The phone also sends **observations**: facts only it can know (Teams rows, AnkiDroid counts, the calendar's busy time, slots and blocks recorded). The phone applies its own commands locally first, through the same owner code, so what it shows offline is what the server will show once the command lands; when the outbox has drained, the server's copies replace the replica.

## Offline on the phone

The owner's word (31 Oct): the phone works offline to an extent. The extent:

- **Reads**: every screen, the widget and the block screen read the replica, as they read the files today.
- **The blocker, the timer and the alarms** run from the replica: blocking never needs the network.
- **Writes**: a task added or finished, a slot moved, the timer started, a setting changed, a check-in answered: applied locally through the owner and queued in the outbox. A message to Felix is queued too, and answered when the network is back, as a message sent with no key is today.
- **The plan**: the phone's planner runs over the replica when it changes while offline, so DO NOW and the blocker follow a task finished; those slots are **provisional** and the calendar is never written from them (W-15). Once the outbox has drained and the server's slots have arrived, they replace the provisional ones.
- **Felix and the syncs** need a connection, as they do today.
- After seven days without a sync, the Day shows a line saying so.

## The website

One origin: Caddy serves the SPA's files and proxies `/api` to the server, so the session is an ordinary cookie and there is no CORS. The SPA renders **view models the server computes** (W-14): `DayModel`, `Groups`, `TaskSheetModel`, `WidgetModel` and the stats' tables are Kotlin in `:engine` after stage 0, and `/views/*` serve their output as JSON. The TypeScript does layout, interaction and words only; it holds no rule about what a task, a slot or a day is. Where the phone's screen does maths that is really layout (the Day grid's pixel positions in `DayLayout.kt`), the web has its own.

The pages are the phone's, laid out for a wide screen: the chat as the home with the day beside it, the Work list, the Day grid, Stats, This week, Reviews, Settings, Backup and Reset, Setup, and a Phone page for pairing and revoking devices. The colours and the words are the phone's. Interaction that the phone does by touch (drag a piece, pinch the grid) is done by pointer and wheel.

The SPA keeps nothing offline: the laptop is online, and the phone is the offline client.

## Sign-in and the phone's pairing

**The website** signs in with GitHub, through a GitHub App whose callback is the server's `/api/auth/callback`, as edit.thomaswhite.me's Worker does. The server allows one GitHub user id, from its environment, and refuses every other. A session is a random id in a cookie (`HttpOnly`, `Secure`, `SameSite=Lax`, 30 days sliding), held in the `sessions` table. Routes that change state also require a header the SPA sets (`X-Requested-With`), which a cross-site form can't.

**The phone** holds a **device token**: 256 random bits, kept hashed on the server in `devices`, sent as `Authorization: Bearer`. Pairing: the website's Phone page asks the server for a one-time six-digit code (five minutes), the owner types it into the phone's Setup → This phone, the phone claims the token. A device can be named and revoked from the website. The token lives in the phone's `SecretStore` beside the TOTP secret.

Sign-in, pairing and claiming are rate-limited; everything else is behind the session or the token.

## Claude

`ModelGateway` moves to the server in stage 3, with the cap, the rests, the record and the goldens, and the Anthropic key becomes an environment variable on the box (W-12). The phone no longer calls Claude: a chat message, a reply, a look, a photo are commands, and the server's Conversation makes the call; the answer reaches the phone as a revision. The record of calls (`model.json`) syncs down, so Settings → Claude → Recent calls reads on both. The month's cap is one, on the server. `GoldenPromptsTest`, `SchemaCompileTest` and `FelixInputsDocTest` move with the prompts to `:engine`'s tests.

The research call (web search, its own call) and the readings stay as they are, now made by the server's jobs.

## Device duties

The phone keeps everything that touches the device, and each becomes an observation or reads the replica:

- **Teams**: `TeamsSource` reads the widget's provider as today and uploads the rows (`TeamsRows`) and the sync state; `TeamsAutoSync`'s decisions in `:engine`, its banner and its `requestSync` call on the phone.
- **AnkiDroid**: `AnkiSource` and `AnkiWatch` read and upload the counts and the foreground changes; `core/AnkiRules.kt` derives the tasks on the server.
- **The calendar**: `CalendarTime` uploads the busy time; `CalendarWriter` writes the slots from the replica, confirmed ones only (W-15); `calendar.json` stays the phone's.
- **The blocker**: `FocusService` reads the replica, records slots and blocks as observations; `BlockPolicy` in `:engine`.
- **Notifications and alarms**: scheduled on the phone from the replica (slot starts, the timer, reminders, the daily ones); the texts Felix writes arrive as revisions.
- **Protection**: arming, the watchdog and the device admin on the phone; the delay and the code on the server too (§Protection on the website).
- **The widget**: reads the replica.
- **The weekly summary email**: unchanged, from the phone.

## Protection on the website

`Protection.changeSettings` runs on the server for every settings command, from either client, so a loosening asked on the website waits its 24 hours as one asked on the phone does, and the pending list (now in the synced `protection.json`) shows on both. The parent's TOTP code is verified on the server as well as the phone, so a code typed on the website works; the secret is held on both (`§Secrets`). When armed, the website's Settings pages that could loosen show the delay rather than hide the page: the server enforces, the page explains. The settings guard that presses Back on the phone's own pages stays the phone's.

## Secrets

| Secret | Today | After the plan |
|---|---|---|
| Anthropic key | `secrets.bin` on the phone | `ANTHROPIC_API_KEY` in the box's `.env` (stage 3); gone from the phone |
| Power Planner username and password | `secrets.bin` | the server's `credentials` table, AES-256-GCM under `MASTER_KEY` from `.env`, entered on the website's Setup (stage 3); gone from the phone |
| Gmail address and app password | `secrets.bin` | the same |
| TOTP secret | `secrets.bin` | both: the phone's copy verifies offline, the server's verifies the website's (stage 4) |
| Device token | — | the phone's `secrets.bin`; hashed in the server's `devices` |
| GitHub App client id and secret, the allowed user id, `SESSION_SECRET` | — | `.env` |
| R2 access keys | — | `.env`, read by Litestream and the nightly backup |

Credentials are **re-entered on the website**, never uploaded from the phone (W-19): the phone's release that stops holding one deletes it once the server reports it has its own. Nothing in `.env` is ever in the repository; `deploy/env.production.example` lists the names with no values, as SharpnPro's does.

## Notifications

The phone stays the notifier throughout. The website shows notices in the page from stage 5 (the tab's title count, a bell, the chat's unread marker). Web Push is an optional last stage (W-9). The phone learns of the server's changes at its next sync, every 15 minutes or on the app's opening; stage 8, optional, adds Firebase Cloud Messaging so a data message wakes the sync at once (W-18).

## What `ArchitectureTest` comes to hold

- Every owner lives in `:engine`; `:app` holds none.
- A store's writers, as today, plus the sync: `sync/Replica` may call `Stores.replace` beside `BackupService`, and nothing else may.
- On the server, only `CommandApplier` calls an owner; no route does.
- Every owner operation a screen can reach has a command class in `:engine`'s protocol, and every command class is applied by the applier (a test lists both sides).
- `ModelGateway.build` is the one place a client is built, in `:engine`, and `:app` holds no key.
- Nothing in `web/` holds a rule: a lint rule forbids importing anything but the API's types into the views (the owner's call whether to keep it).

## Conventions kept

Everything in `docs/plan_v4/architecture.md` §Conventions holds for the Kotlin. For `web/`: the owner's other sites' tooling (ESLint flat config, Playwright, vitest), TypeScript strict, made-up fixtures, no personal data (D-79), comments in the same plain present tense. For `deploy/`: SharpnPro's layout (`compose.yaml`, `Caddyfile`, `bootstrap.sh`, the update timer, `env.production.example`), adapted and committed here.
