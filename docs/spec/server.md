# The server

What runs on the box: the API, how a client signs in, how the state is held, and how it's deployed (D-129 to D-131). `server/`, a Ktor application built from `:core` and `:engine`, so the rules it computes with are the phone's own. How the phone and the server talk is in [sync.md](sync.md), what the website shows in [website.md](website.md); the plan's reasoning in [plan_web/](../plan_web/README.md).

## The modes

`meta.mode` says who writes the state.

- **Mirror** (5.0.0): the phone is the only writer. It sends its stores whole and the server keeps a copy, computes from it and writes nothing to it. `SqliteBackend.writable` holds to that: a store's own write goes nowhere, so an older shape is read up to date in memory and its row stays as the phone sent it. A route never writes a store.
- **Commands** (`meta.mode = commands`, D-141): each change is a command, applied through its owner. Built and tested in 5.2.0, and not yet in use: `POST /commands` refuses with a 409 in mirror mode, and a seed does in this one. The switch is one way.

## The API

Everything is under `/api`, on the same origin as the website. Open: `GET /health`, `GET /version`, `GET /me`, sign-in, and `POST /devices/claim`. Every other route needs a client (`signedIn` in `server/Api.kt`): a device's bearer token, or a session.

| Route | Who | Does |
|---|---|---|
| `GET /health` | anyone | `ok`, for the container's health check and the uptime check |
| `GET /version` | anyone | the server's version, the protocol, the mode and each store's version |
| `GET /me` | anyone | whether this browser is signed in |
| `GET /auth/github`, `GET /auth/callback` | anyone | the sign-in, below |
| `POST /auth/logout` | a session | ends it |
| `POST /devices/pair` | the website | a six-digit code, for five minutes |
| `POST /devices/claim` | anyone with a code | a device's id and token, shown this once |
| `GET /devices`, `DELETE /devices/{id}` | the website | what's paired, and revoking one |
| `POST /stores/seed` | a phone | its stores, [sync.md](sync.md) |
| `POST /commands` | a client | a batch of commands, each one's outcome; a 409 in mirror mode |
| `GET /state?since=` | a client | every store above a revision, whole |
| `GET /events` | a client | the state's revision: the one it's at on connecting, then each new one, with a heartbeat every 25 seconds |
| `GET /views/day?date=`, `GET /views/work`, `GET /views/task/{id}`, `GET /views/settings`, `GET /views/chat?earlier=&reach=`, `GET /views/notices`, `GET /views/stats?period=`, `GET /views/week`, `GET /views/reviews`, `GET /views/backup`, `GET /views/setup` | a client | the pages' view models, [website.md](website.md) |
| `POST /tasks/{id}/ask` | the website | what's asked of a task, made the commands the phone's sheet would make; a 409 in mirror mode |
| `POST /day/drop` | the website | a block let go on the Day, judged as the phone judges one; a 409 in mirror mode |
| `POST /chat/try-again` | a client | your message that waits asked for again, outside any command; a 409 in mirror mode |
| `POST /week/check-in` | the website | the week's check-in, kept as the phone's form keeps it, setting off the review; a 409 in mirror mode |
| `GET /record.zip` | the website | the record as CSVs, zipped, a download nothing on the way keeps |
| `GET /backup` | the website | the Backup-format file, read between commands and seeds, a download nothing on the way keeps; a 409 if it's more than Import reads |
| `POST /backup/import` | the website | a backup taken in, all of it or none; a 409 in mirror mode, a 413 past 8 MB by what it says it is |
| `POST /backup/copy` | the website | a reset's copy, by its name, taken in the same way; a 409 in mirror mode |
| `POST /photos/tasks` | the website | a JPEG of homework read into tasks to check, one at a time; a 409 in mirror mode, or with another being read |
| `GET /credentials`, `POST /credentials` | the website | which sign-ins are held, and setting them: never their values back |
| `POST /parent-code` | a phone | the parent code's secret |

A failed request answers with a `Problem` and logs the kind of failure and where it happened: never its message, what a store held, or a header.

## Signing in

One account (D-128). `GET /auth/github` starts GitHub's sign-in with PKCE, its `state` and verifier in a short-lived cookie; the callback checks the `state`, exchanges the code, and lets in only `ALLOWED_GITHUB_USER_ID`. Anyone else gets a 403.

- A session is a random token, kept hashed, for 30 days, in a `__Host-session` cookie: `HttpOnly`, `Secure`, `SameSite=Lax` (a plain `session` over plain HTTP in tests).
- A route that changes something also needs an `X-Requested-With` header, which a cross-site form can't send.
- Sign-in, the callback and claiming a code are rate-limited to ten a minute by address.

## Devices

The website shows a six-digit code; a phone that gives it back within five minutes is given a token. The code works once. Tokens are kept hashed (SHA-256), and a revoked device's stops working at once. A request from a device names its protocol in `X-Protocol`: the server takes its own and the one before, and anything else is a 409 (D-138).

## The state

SQLite, one file, in WAL mode (`server/Database.kt`), the server its only writer. `SqliteBackend` is the `StoreBackend` that `Stores` runs on here, as `FileBackend` is on the phone, so a store has the same shape, migrations and versions on both.

- `stores`: each store's JSON, its version and the revision that last wrote it. A revision is one transaction. No row goes: `/api/state` can't say what isn't there, so what's deleted (a month of a cleared chat) stays as an empty row at a new revision, and clients take that as any change.
- `store_history`: each store's every text, with what caused it, kept 90 days and at least the last 20 of each, let go of at each start and daily after (`ServerGraph.tidy`).
- `commands`: every command received, whose it was, and what came of it, kept 90 days (`ServerGraph.tidy`). A repeat of an id answers from here, looked up inside the command's lock, so a retry racing its first send waits and finds what came of it. Clearing the chat blanks what those that hold its words sent (`SendMessage`, `ReplyFromNotification`, `RateMessage`), as it lets go of its history, and letting go of the log's copies blanks a check-in's (`RecordCheckIn`): each stays on record, so a repeat still answers, with none of its words.
- `devices`, `pairing_codes`, `sessions`, `meta`.

A command is one transaction, all of it or none (`CommandApplier`, `Database.command`), one at a time, in the order they arrive. A backup taken in is held the same way (`CommandApplier.alone`), but isn't on record as one: its file doesn't belong in `commands`, and each store's text before it is in `store_history`. The owners suspend and change thread on the way, so it's held by a coroutine's lock, and reads that mustn't see it half-way go through a second connection (`Database.read`), which in WAL mode sees only what's committed. One that fails is rolled back, every store read again from what's committed, mirrored or not, and it's on record as dropped, all before another's let in (`Database.commandOr`): what it left in memory mustn't be built on.

The owners are `ServerOwners`, wired as `AppGraph` wires the phone's, what touches the device left out. In mirror mode nothing on the server watches a store or runs by itself. Once it applies commands, `ServerGraph.start` sets off the jobs below and what watches the stores: an own task with its steps done is finished, a weekly rule's week rolled (`OwnTasks.start`), and the plan made again as what it's made from changes (`PlanWatcher`), with today's starts kept and the work done ahead, as on the phone but for its alarms, its calendar and its widget. The mode is read once, at a start, so everything that hangs on it goes together, and the switch to commands takes a restart.

The server's time zone is the phone's: each batch says it (`ServerClock`), as a day is the owner's wherever they are. Its time is what a loosening's wait is counted by on the server: a mark of the phone's is another clock's, so the gap from it counts nothing and the count goes on from there.

## Jobs

What runs with nobody asking (`ServerJobs`), once the server applies commands (D-141): each minute, whatever's due, one at a time, each's last run in `meta`, so a restart neither repeats one nor misses one. A job that fails is logged and tried again: one that runs on a cadence at its next turn, one at a set time 15 minutes on, the nightly file an hour on, so nothing that reaches Claude or a source goes round each minute. One at a set time that's never run counts from the start of today, so the first run is today's, and nothing gone by before it is caught up on. The morning never catches up, even with a run on record: it says today's whenever it runs, so a morning missed is left, and today's comes at its time; and a run today stands for today, even with its time moved later since. The nightly file's text is made between commands, so it holds none half done, and its send, outside that, is bounded (five minutes), as the jobs run one at a time.

| Job | When | Does |
|---|---|---|
| `slots` | each minute | today's slots as they pass; a loosening's wait over, counted by the server's clock (W-23) |
| `looks` | each minute | Felix's looks and your reminders |
| `sources` | every 15 minutes | Power Planner and Gmail read (D-133), what follows a read as on the phone, the readings |
| `morning` | the briefing's time | the day on record, Felix's morning, what's run out let go |
| `check-in`, `review` | Sunday's | the week's check-in asked, and its review |
| `nightly` | after 03:00 | the Backup-format file to B2 (W-20), under a key that can't delete; the bucket's lifecycle rule lets the old ones go |
| `heartbeat` | each minute | healthchecks.io, so a stopped box is noticed |

A job's writes aren't a command's: each is a revision of its own. One made while a command's open is held back, in its order, till that closes (`Database.writing`), so a rollback takes nothing of theirs with it. It never waits there: whoever writes a store holds the store's lock, and perhaps an owner's, which the command may be waiting for. If the command stands, what it wrote since takes the job's change in, as a store changes in memory one change at a time. If it fails, what's held back was made from memory the command had been at, so nothing's let through: with the command rolled back each held write is made afresh on what's committed, in its order (`Database.commandOr`), the stores are read again from the result, and again for any that came meanwhile. Each job's change is in the database once, and nothing the command wrote to that store is. That's store by store: a change can carry what it read from another store while the command was open, so a job's work can still reflect a command that then failed, which the next run of it sees past. Should putting it right fail as well, what's held is let go rather than written, the stores are read again from what's committed, and the failure's said. What's waiting for Claude is asked after a command lands, never inside it, as a call can't hold the state's write lock.

**Credentials.** The sources' sign-ins, entered on the website (W-19, `POST /credentials`, the website's alone; `GET /credentials` says only which are held). Sealed under `MASTER_KEY`, in `secrets.bin` and in the `credentials` table, so Litestream carries them and a rebuilt box gets them back. Claude's key is `ANTHROPIC_API_KEY` in `.env`.

## Deployment

`deploy/`, three containers under Docker Compose at `/opt/decrastination` on the box (`plan_web/hosting.md`):

- `server`: `deploy/server.Dockerfile`, a JRE and the fat JAR, as uid 1000, `-Xmx1g`, limited to 1.5 GB; 130 MB in use idle, measured on a PC in rehearsal.
- `web`: `deploy/web.Dockerfile`, Caddy, the built pages and `deploy/Caddyfile`: TLS, the static files, `/api` to the server, HSTS and the content security policy. The event stream isn't compressed, so each event arrives as it's sent.
- `litestream`: `deploy/litestream.yml` streams the database to a private R2 bucket, a snapshot a day kept 30 days.

Each service gets only the secrets it uses (`deploy/compose.yaml`), all of them in `.env`, listed with no values in `deploy/env.production.example`.

**Releasing.** CI builds both images on every PR. A push to `main` pushes them to GHCR, tagged with the version and `latest`; `decrastination-update.timer` runs `decrastination-update.sh` on the box every three minutes, which pulls and restarts what changed. `CI` run by hand on a branch pushes images tagged with the branch's name, to try on the box through `IMAGE_TAG` in `.env`. A file `.hold` beside `compose.yaml` stops the updates.

**A new box.** `deploy/bootstrap.sh`: Docker, the firewall, `/opt/decrastination`, `MASTER_KEY` made once. With `.env` filled it restores the database from R2 if the box has none and R2 has one, then starts everything. It stops if the restore fails, so an empty server never replicates over the good copy.

**Restoring.** `docker compose run --rm --no-deps litestream restore -config /etc/litestream.yml -if-db-not-exists -if-replica-exists /data/decrastination.db`, with the server stopped and the old database gone; `-timestamp` for a moment in the last month. The drill: the database destroyed, restored, the server started from it, and `GET /state` the same, byte for byte, its sessions and devices still good. A database restored to an older moment catches up by itself: the phone's next send sees from the answer what it no longer holds, and sends it. Once commands apply it doesn't, by itself: the phone holds what the restore took back, and must not take the older copy. So after a restore the server's put back to mirroring before it starts (`UPDATE meta SET value = 'mirror' WHERE key = 'mode'`): the phone goes back too at its next exchange, sends all it holds over the restored copy, and the switch is made again. What the website sent since the backup and the phone hadn't pulled is gone either way.

**Keep `MASTER_KEY` off the box as well.** A restored database's encrypted credentials can't be read without it.
