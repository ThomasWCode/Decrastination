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
| `GET /views/day?date=`, `GET /views/work` | a client | the pages' view models, [website.md](website.md) |

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

- `stores`: each store's JSON, its version and the revision that last wrote it. A revision is one transaction.
- `store_history`: each store's every text, with what caused it, kept 90 days and at least the last 20 of each, let go of at each start and daily after (`ServerGraph.tidy`).
- `commands`: every command received, whose it was, and what came of it. A repeat of an id answers from here.
- `devices`, `pairing_codes`, `sessions`, `meta`.

A command is one transaction, all of it or none (`CommandApplier`, `Database.command`), one at a time, in the order they arrive. The owners suspend and change thread on the way, so it's held by a coroutine's lock, and reads that mustn't see it half-way go through a second connection (`Database.read`), which in WAL mode sees only what's committed. One that fails is rolled back, the stores read again from what's committed, and it's on record as dropped.

The owners are `ServerOwners`, wired as `AppGraph` wires the phone's, what touches the device left out. None is started: nothing on the server watches a store or runs by itself.

The server's time zone is the phone's: each batch says it (`ServerClock`), as a day is the owner's wherever they are.

## Jobs

What runs with nobody asking (`ServerJobs`), once the server applies commands (D-141): each minute, whatever's due, one at a time, each's last run in `meta`, so a restart neither repeats one nor misses one. A job that fails is logged and tried at its next turn.

| Job | When | Does |
|---|---|---|
| `slots` | each minute | today's slots as they pass; a loosening's wait over |
| `looks` | each minute | Felix's looks and your reminders |
| `sources` | every 15 minutes | Power Planner and Gmail read (D-133), what follows a read as on the phone, the readings |
| `morning` | the briefing's time | the day on record, Felix's morning, what's run out let go |
| `check-in`, `review` | Sunday's | the week's check-in asked, and its review |
| `nightly` | after 03:00 | the Backup-format file to B2 (W-20), under a key that can't delete; the bucket's lifecycle rule lets the old ones go |
| `heartbeat` | each minute | healthchecks.io, so a stopped box is noticed |

A job's writes aren't a command's: each is a revision of its own, and waits while a command's open, so one that's rolled back takes nothing of theirs with it. What's waiting for Claude is asked after a command lands, never inside it, as a call can't hold the state's write lock.

**Credentials.** The sources' sign-ins, entered on the website (W-19, `POST /credentials`, the website's alone; `GET /credentials` says only which are held). Sealed under `MASTER_KEY`, in `secrets.bin` and in the `credentials` table, so Litestream carries them and a rebuilt box gets them back. Claude's key is `ANTHROPIC_API_KEY` in `.env`.

## Deployment

`deploy/`, three containers under Docker Compose at `/opt/decrastination` on the box (`plan_web/hosting.md`):

- `server`: `deploy/server.Dockerfile`, a JRE and the fat JAR, as uid 1000, `-Xmx1g`, limited to 1.5 GB; 130 MB in use idle, measured on a PC in rehearsal.
- `web`: `deploy/web.Dockerfile`, Caddy, the built pages and `deploy/Caddyfile`: TLS, the static files, `/api` to the server, HSTS and the content security policy. The event stream isn't compressed, so each event arrives as it's sent.
- `litestream`: `deploy/litestream.yml` streams the database to a private R2 bucket, a snapshot a day kept 30 days.

Each service gets only the secrets it uses (`deploy/compose.yaml`), all of them in `.env`, listed with no values in `deploy/env.production.example`.

**Releasing.** CI builds both images on every PR. A push to `main` pushes them to GHCR, tagged with the version and `latest`; `decrastination-update.timer` runs `decrastination-update.sh` on the box every three minutes, which pulls and restarts what changed. `CI` run by hand on a branch pushes images tagged with the branch's name, to try on the box through `IMAGE_TAG` in `.env`. A file `.hold` beside `compose.yaml` stops the updates.

**A new box.** `deploy/bootstrap.sh`: Docker, the firewall, `/opt/decrastination`, `MASTER_KEY` made once. With `.env` filled it restores the database from R2 if the box has none and R2 has one, then starts everything. It stops if the restore fails, so an empty server never replicates over the good copy.

**Restoring.** `docker compose run --rm --no-deps litestream restore -config /etc/litestream.yml -if-db-not-exists -if-replica-exists /data/decrastination.db`, with the server stopped and the old database gone; `-timestamp` for a moment in the last month. The drill: the database destroyed, restored, the server started from it, and `GET /state` the same, byte for byte, its sessions and devices still good. A database restored to an older moment catches up by itself: the phone's next send sees from the answer what it no longer holds, and sends it.

**Keep `MASTER_KEY` off the box as well.** A restored database's encrypted credentials can't be read without it.
