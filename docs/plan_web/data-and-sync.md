# Data and sync: how the state moves and is kept

The one rule above all others in `CLAUDE.md`: **no data is ever lost**. This file says how the state is held on the server, how it moves between the server and its two clients, what happens when the two disagree, and where the copies are. `docs/plan_v4/data-and-migrations.md` describes the stores and migrations as they are; they keep working unchanged underneath all of this.

## The database

SQLite, one file, `/opt/decrastination/data/decrastination.db`, in WAL mode (Litestream needs it). The server is its only writer.

| Table | Columns | Holds |
|---|---|---|
| `stores` | `name` PK, `version`, `revision`, `json`, `updated_at` | the current text of every store: `tasks`, `settings`, `session`, `chat`, `chat-archive-2026-10`, … exactly the JSON the phone's files hold |
| `store_history` | `revision`, `name`, `version`, `json`, `cause`, `at` | every past text of every store, one row a write; `cause` is the command's id. Trimmed to 90 days of history and never fewer than the last 20 rows a store |
| `commands` | `id` PK, `device`, `kind`, `protocol`, `payload`, `received_at`, `applied_at`, `outcome`, `revision` | every command and observation received, with how it went: applied, dropped (and why), or refused |
| `devices` | `id`, `name`, `token_hash`, `created_at`, `last_seen_at`, `revoked_at` | the phone's token(s) |
| `sessions` | `id`, `created_at`, `expires_at`, `last_seen_at` | the website's sign-ins |
| `pairing_codes` | `code_hash`, `expires_at` | the six-digit codes, five minutes each |
| `credentials` | `name` PK, `ciphertext`, `nonce`, `updated_at` | Power Planner, Gmail, the TOTP secret, under `MASTER_KEY` |
| `push_subscriptions` | stage 9 | Web Push endpoints |
| `meta` | `key`, `value` | the schema version, the global revision, the mode (`mirror` or `commands`), the protocol version |

Why a table of JSON documents and not a table a type: the stores are already the app's unit of writing, versioning, migrating and backing up, with months of tests behind them. The server adds transactions and history to that unit rather than replacing it. The state is one to two megabytes in all; nothing here needs a relational shape.

## The store backend

`JsonStore` gains a `StoreBackend` interface in stage 0 with three operations: read a store's text, write it (with its version), list the stores. `FileBackend` is today's behaviour (write `.tmp`, rename) and the phone keeps it. `SqliteBackend` on the server writes `stores` and `store_history` in the enclosing transaction. Everything above the backend (the `StateFlow`, the version check, the migrations run on the text before decoding, the `.v<n>` keeping) stays as it is; the phone's files keep the `.v<n>` and unreadable copies, the server keeps `store_history`.

## Revisions

One global counter in `meta`. Each committed command bumps it once, and every store the command changed takes that revision. A client holds the last revision it took; `GET /api/state?since=R` returns every store with a revision above R, whole. `GET /api/events` is a server-sent event stream of new revision numbers: a client that hears one pulls `state`. The website keeps the stream open; the phone pulls at its sync and, from stage 8, when a push message says to.

A store is sent whole, not as a diff. `tasks.json` was 590 KB at 3.2.0 and is smaller since finished tasks were trimmed; gzip (Caddy's `encode`) takes it to a fraction. On the phone a pull on mobile data is a few hundred kilobytes at most, at a 15-minute cadence. Delta sync is not in this plan; if the sizes grow, it would be the first thing to add.

## Commands

A command is an owner's operation with its arguments: what a screen calls today when a button is pressed.

```
{ "id": "<uuid, the client's>", "device": "<device id or 'web'>", "protocol": 1,
  "kind": "MarkDone", "payload": { "taskId": "…", "at": "2026-11-03T16:20:00Z" }, "sentAt": "…" }
```

The kinds are the owners' public operations, one serialisable class each in `:engine`'s `sync/protocol/`, grouped as the owners are: tasks (an own task added, edited, deleted; done and its undo; Same? and Not same; an instruction applied; a `mine` override), the plan (a slot moved, a piece kept), the timer (started, stopped), the chat (a message sent, a reply, a rating, a text picked, read-at), settings (a change, through Protection), school, memory, the weekly rules, the reviews (a check-in's answers, a change undone), a reset, Felix (a look with its context, a reminder made). A test in `:engine` lists every owner operation a screen reaches and every command class, and fails when one side lacks the other.

**On the server**, `CommandApplier` takes a batch, and for each command, in order: checks the protocol version and the device; answers a repeat of an id it has seen with the outcome and revision recorded the first time, applying nothing (idempotence: the client retries until it hears back, and a lost response can't turn an applied command into a dropped one; `refused` is kept for a protocol version or a device the server doesn't accept); opens a transaction; calls the owner; writes the stores the owner changed and their history rows; bumps the revision; records the outcome; commits. One `Mutex` round the lot: commands never interleave.

**On the phone**, a screen calls the owner as it does today. The owner on the phone is wrapped so that the call is recorded in `outbox.json` before it is applied to the replica (stage 2 settles the mechanism: owner interfaces with a recording implementation, or an outbox the owners write to). `SyncWorker` posts the outbox in order, drops what the server has acknowledged, and then pulls: the server's stores replace the replica, and the commands still in the outbox (recorded during the sync, or unacknowledged because the server went out of reach part-way) are applied again on top through the same owners, so nothing the owner did disappears from the screens, the widget or the blocker between syncs. On the website, a click posts the command and waits for the outcome; there is no local application.

## Observations

Facts only the phone can know, sent as commands with their own kinds: `TeamsRows` and `TeamsState` (the widget's provider), `AnkiCounts` and `AnkiForeground`, `CalendarBusy` (the next days' busy time), `SlotRecorded` and `BlockRecorded` (what `Focus` and `FocusService` write to the log today), `ProtectionLapse`, `DeviceStatus` (the phone's build and store versions, its battery state for the record). The server applies them through the same owners (`Sync` merges the Teams rows as it merges them today).

The phone spends free-time credit every 30 seconds while a blocked app runs on earned time (`FocusService.creditCheck`). Each spend is a command in the outbox (W-24), merged with the one before it until the upload, so the server's credit is at most a minute behind while the phone is online, and a pull, which re-applies the outbox on top of the server's copy, can never give free time back or lose earned time. A slot's end, a block and anything the owner did (the timer, a Done) go up at once. The server's revision moves only when a store's text changes.

## Order and conflicts

The server applies commands in the order they arrive, each device's in the order it sent them. There is no merge of two states: the owners are the conflict policy, as they are the policy today when a sync and a tap race on the phone. Each owner is already deterministic and tolerant of a stale call (Done on a task that has gone is a no-op; a move onto a slot that has changed re-lays the day). Stage 2 writes the table of stale cases, command by command, and the outcome each gets, and tests it. A command that can't apply is recorded `dropped` with its reason, and the client shows a card ("that task had gone"); nothing is applied half-way.

A scalar setting changed on both clients while one was offline: the later arrival wins, and Protection's delay still applies to whichever loosens. The delay is counted by the server's clock (W-23): a pending change carries the `dueAt` the server set, the server applies it when due, and the phone, online or offline, never applies a loosening itself, so the two sides can't disagree about when a change is due.

The phone's provisional plan (`architecture.md` §Offline on the phone) is never sent: the server plans from the state once the commands have landed, and the phone adopts its slots. Two planners never both write the calendar: the calendar is written only from slots the server has confirmed (W-15).

## Versions and compatibility

- **Store versions** stay the one versioning of the data. The server runs the same migrations: a new server build migrates a store's text on first read, in a transaction, with a history row before and after.
- **The protocol version** rides in a request header. The server accepts its own version and the one before, so a phone one release behind still syncs; an older one gets a 409 saying to install, and works offline from its replica until it is installed.
- **The phone is never behind the server on a store's version.** The server's copy of a store, once migrated, is what the phone receives; a phone that can't read it would refuse the pull. So a release that bumps a store's version is installed on the phone **before** the merge that deploys the server (the merge checklist's phone check already comes first). `GET /api/version` names the server's store versions, and the phone refuses to pull a version above its own, saying so in Setup → This phone.
- **Rolling back** a server release: the previous image tag, and `litestream restore -timestamp` to just before the deploy if a migration went wrong. The phone's replica keeps its `.v<n>` copies as today.

## The modes: seeding and the switch to commands

Stage 1 runs the server in **mirror** mode: the phone is still the only writer, and `POST /api/stores/seed` takes a whole store and keeps it (with history). The website reads. Stage 2 switches the server to **commands** mode in its release's migration: the phone does one last full upload, the server flips `meta.mode`, and from then whole-store uploads are refused and every change is a command. The flip is one-way, and the phone's release that sends commands is installed before the server's deploy, as above.

## Which stores move

`inventory.md` §Stores has the table: **both** for the state (tasks, settings, session, the synced half of protection, instructions, chat and its archive, school, memory, felix, log, anki); **server** for Claude's record and the Gmail journal from stage 3 and the calibration; **phone** for `runtime.json`'s notification bookkeeping, the device half of protection (`device.json`, new in stage 0), `calendar.json` and the secrets. New: `outbox.json` and `sync.json` on the phone.

## Backups and the copies

Three independent copies of the state exist at all times (W-20):

1. **Litestream** streams SQLite's WAL to a private Cloudflare R2 bucket (`hosting.md` §Litestream), continuously; a dead box loses seconds. Snapshots kept 30 days.
2. **The nightly backup**: the server writes a Backup-format file (format 11 by then, as `data/Backup.kt` writes it, with the same exclusions) to a **Backblaze B2** bucket under an application key of its own, one that can write and not delete, 60 kept: another provider and another credential, so no one mistake (a leaked key, a deleted bucket, a suspended account) takes both off-box copies. It is readable with no SQLite and no server: the phone's Import takes it, so the oldest restore path in the app stays alive.
3. **The phone**: its replica is a full copy of every synced store, and `BackupWorker` keeps writing its daily file to `Documents/Decrastination/` as today.

The Oracle volume backup (five in the allowance) is a fourth, weekly, for the whole box.

**Restore drill** (stage 1's done-when, then every quarter): a clean machine, `litestream restore` from R2, the server started from the file, the owner's state read whole; and the nightly file imported into the phone's Import on a test build. `scripts/rehearse.sh` (a migration's rehearsal on a backup) gains a server mode that runs the server's migrations over a restored database.

## Testing the sync

A harness in `:engine`'s tests: one server (the applier over an in-memory `SqliteBackend`) and two clients (each a `FileBackend` in a temporary folder with an outbox), all in one JVM, the clock a `WallClock` under test. Scenarios, each a test: a task added offline and finished online; two devices moving the same slot; a command on a task that has gone; a batch cut in half by a crash and retried, its outcomes identical (idempotence); a command recorded mid-sync, still shown after the pull; a protocol version behind; a store version ahead; mirror mode's seed and the switch. The fixtures are the made-up stores in `fixtures/stores/`. The planner's and owners' own tests don't change: they test the same code.
