# Hosting

Where the server and the website run, how a release reaches them, where the copies of the state are, and what it costs (D-130, D-131, D-144). The server itself is in [server.md](server.md). One box, Docker Compose, Caddy for TLS, images built by GitHub Actions and pulled by a systemd timer, so a merge to `main` becomes a running server with nobody logging in.

## The box

An Oracle Cloud **Always Free** `VM.Standard.A1.Flex` (Arm) instance, Ubuntu 24.04 aarch64, in the tenancy's home region: the whole A1 allowance, 2 OCPUs and 12 GB, as one instance (it was 4 and 24 until June 2026, when Oracle halved it). With it come 200 GB of block storage and 10 TB a month outbound.

- **Idle reclamation.** Oracle's rule: an instance is idle if, over 7 days, its CPU at the 95th percentile, its network and (A1 only) its memory are each under 20%, all three together. This server idles, so it's at risk of being stopped, with about a week's emailed notice by Oracle's past practice. What stands against it: the account is on Pay As You Go (the owner's word, 21 Nov 2026), under which Always Free resources stay free, and which Oracle's 2023 notice said stops idle instances being stopped, though today's docs don't repeat that; and the heartbeat (§Monitoring), so a stopped box is noticed within a quarter of an hour. Memory held above 20% by the JVM's heap would game the rule, and isn't done. The state is on the volume and in R2 either way.
- **"Out of host capacity"** on creating an A1 instance is common: another availability domain, or wait and try again, which can take days.
- A free-only account has no Oracle support, and one left idle 30 days can be deemed abandoned; the server's own activity keeps it from that.

## What runs on it

`deploy/`, three containers under Docker Compose at `/opt/decrastination` on the box, beside `.env` (mode 0600), `litestream.yml` and `data/`:

- `server`: `deploy/server.Dockerfile`, a JRE and the fat JAR, as uid 1000, `-Xmx1g` with the serial collector, leaving at once if it runs out of memory, limited to 1.5 GB (the other two to 128 MB each); 175 MB in use on the box with the real stores, measured 1 Nov 2026. The base image is published for arm64 and amd64 and a JAR is the same on both, so the image is made for both with nothing compiled under emulation.
- `web`: `deploy/web.Dockerfile`, Caddy, the built pages and `deploy/Caddyfile`: TLS, the static files, `/api` to the server, HSTS and the content security policy. Everything but the event stream is compressed (zstd, gzip), so a pull of whole stores is a fraction of their size, and each event arrives as it's sent. The built files under `/assets/` are kept a year by a browser, and the page itself is asked for again each time, so a deploy shows at once. No log line holds a request's headers or sign-in's one-time code: both of Caddy's loggers go through one filter, as a request that fails is written by the default one. Caddy gets and renews its certificate by itself. Ports 80 and 443 (and 443 UDP, for HTTP/3) are open in the VCN's security list and in the instance's iptables, both: Oracle's Ubuntu images refuse everything but SSH till the second is done, which `bootstrap.sh` does.
- `litestream`: `deploy/litestream.yml` streams the database to a private R2 bucket (§Litestream), a sidecar on the server's volume, so SQLite's locks hold between them. Its metrics are on the compose network alone (`addr: ":9090"`, no port published): the server reads its count of uploads with each heartbeat (`StreamWatch`, `LITESTREAM_METRICS`), and as it writes each minute, a stream that's going uploads that often. None gone up for 15 minutes, whether its key's been taken away, it's stuck, or it's down and can't be asked, and the heartbeat says it failed. Tried on Litestream 0.5.17 on a PC: with its bucket taken away, the count stood still and no count of errors came.

Each service gets only the secrets it uses (`deploy/compose.yaml`), all of them in `.env`, listed with no secret's value in `deploy/env.production.example`; nothing in `.env` is ever in the repository. The images are public on GHCR and hold no secret.

**DNS.** `decrastination.thomaswhite.me` is an A record to the instance's public address, at the domain's registrar. The site isn't on GitHub Pages, as the rest of the domain is.

## Releasing

CI builds both images on every PR. A push to `main` pushes them to GHCR, tagged with the version and `latest`; `decrastination-update.timer` runs `decrastination-update.sh` on the box every three minutes, which pulls and restarts what changed. `CI` run by hand on a branch pushes images tagged with the branch's name, to try on the box through `IMAGE_TAG` in `.env`. A file `.hold` beside `compose.yaml` stops the updates. The timer pulls images alone: a change to `compose.yaml` or `litestream.yml` is put on the box by hand (`private/tools/box.py put`) as its release deploys, then `docker compose up -d`.

A release that raises a store's version or the protocol's is installed on the phone before its merge deploys the server (D-138).

## Litestream

Litestream 0.5.18, pinned, a snapshot a day kept 14 days (D-154), its path in the bucket `db-2`. The nightly file in B2 is the long term (§The copies).

**Its requests.** Each of Litestream's timers is a request to R2, and most are Class A (PUT and LIST; DELETE is free), of which R2 gives a million a month free. On Litestream's defaults that was passed in November 2026, and the rest billed. What did it, read in 0.5.18's source and counted in the bucket on 11 Nov 2026:

- A LIST reads a whole level. The S3 client lists by prefix alone and skips what it doesn't want on its own side (`s3/replica_client.go`, `newFileIterator`), so each listing is a request for each 1,000 files in the level.
- L1 to L3 keep every file till it's behind the oldest snapshot kept (`Store.EnforceSnapshotRetention`). With a write each minute (the heartbeat's) and L1 compacted every 30 s, L1 gained about 2,600 files a day.
- L0's retention check lists all of L1, then L0, every 15 s whether anything's changed or not (`Compactor.EnforceL0Retention`): about 150,000 requests a day ten days in, and growing. Litestream's metrics count its PUTs and DELETEs, never its LISTs, so the count it gave was far short of the bill.

So `deploy/litestream.yml` sets each timer itself. A day, with L1 holding its 14 days (96 files a day, 1,344 in all, two requests a listing):

| Setting | Default | Set | Class A a day |
|---|---|---|---|
| `sync-interval` (the replica's) | 1s | 1m | up to 1,440 PUTs |
| `l0-retention-check-interval` | 15s | 10m | 144 checks, 432 LISTs |
| `levels` (L1, L2, L3) | 30s, 5m, 1h | 15m, 1h, 6h | about 300: a PUT and a LIST of the level below each |
| `snapshot.interval` | 24h | 24h | a few |

About 2,200 a day, 66,000 a month, worked out from the code and the counts rather than measured: the real figure, by day and by kind, is read once a day and shown in Settings → Storage (§Monitoring). What it costs: a box that's lost loses up to its last minute of writes, not its last second, and a restore to a moment lands on a 15-minute boundary rather than a 30-second one. The phone holds its own copy either way.

**A fresh path.** Files under an old path go on being listed for as long as they'd be kept, so a change of the timers comes with a new path (`db-2` since November 2026). The new path starts empty, so Litestream's local state is reset first (`litestream reset`, with Litestream stopped): its next sync then uploads one fresh snapshot, where otherwise it would try every L0 file since the first, most of them long gone. `restore -timestamp` reaches back to the move and no further; the old path's files stay till an R2 lifecycle rule on that prefix expires them, and are restored from with a copy of the config naming the old path.

**R2's free tier**: 10 GB stored, a million Class A operations and ten million Class B a month, no egress charge. The history the database keeps is thinned to stay inside it (D-151), and the snapshots' 14 days are what the stored size turned on (D-154).

## The copies

Three independent copies of the state at all times (D-144), in three places under three credentials, so no one mistake takes two:

1. **Litestream's stream to R2**, continuous, a minute behind at most.
2. **The nightly file in Backblaze B2** (the `nightly` job, [server.md](server.md) §Jobs): a Backup-format file, as the phone writes ([storage-and-backups.md](storage-and-backups.md)), readable with no SQLite and no server, as the phone's Import and the website's take it. About 400 KB a file. The bucket's lifecycle rule hides a file 60 days after it's written and deletes it 60 days after that. Both numbers do work: a file named by its date is never replaced, so nothing else would ever hide it, and a key that can write can hide every file at once, so the second 60 is the time there is to notice.
3. **The phone**: every store the two share, and its own daily backup file.

**What holds the nightly copies.** They're as safe as the key: nothing in the server deletes one, but a key that leaked could. B2's console can't make a key that can't delete: its *Write Only* access carries `deleteFiles` and `writeBucketLifecycleRules` (a narrower key can be made only through B2's API, with the account's master key). The bucket has Object Lock on, but that protects nothing till the bucket's default retention is set, and it isn't: tried on 1 and 3 Nov 2026, the key deleted a version outright (`private/tools/b2_refusals.py`, a 204 where a held file answers 403). The owner's word, 3 Nov: it doesn't matter, as nothing will delete them. Without a retention in force B2 takes a file with no `Content-MD5`; should one be set, try the upload again, as S3 wants one then.

## A new box, restoring, rolling back

**A new box.** `deploy/bootstrap.sh`: Docker, the firewall, `/opt/decrastination`, `MASTER_KEY` made once. With `.env` filled it restores the database from R2 if the box has none and R2 has one, then starts everything. It stops if the restore fails, so an empty server never replicates over the good copy.

**Restoring.** `docker compose run --rm --no-deps litestream restore -config /etc/litestream.yml -if-db-not-exists -if-replica-exists /data/decrastination.db`, with the server stopped and the old database gone; `-timestamp` for a moment in the last 14 days. A database restored to an older moment catches up by itself while the server mirrors: the phone's next send sees from the answer what it no longer holds, and sends it. Once commands apply it doesn't, by itself: the phone holds what the restore took back, and must not take the older copy. So after a restore the server's put back to mirroring before it starts (`UPDATE meta SET value = 'mirror' WHERE key = 'mode'`): the phone goes back too at its next exchange, sends all it holds over the restored copy, and the switch is made again ([sync.md](sync.md) §The exchange). What the website sent since the backup and the phone hadn't pulled is gone either way.

**Rolling back a release.** The image before it, by `IMAGE_TAG` in `.env`; and, if a migration went wrong, a restore to just before the deploy.

**The drill**, each quarter (`private/tools/restore_drill.py`, on a PC, not the box): the database restored from the real bucket, its integrity checked, every store the same as the box's by hash, the real image started from it and read through its API, a Backup-format file made. First run 1 Nov 2026: 14 of 14 stores the same, in 26 s. Its other half, a nightly file from B2 taken in by a test build's Import, hasn't been run.

**Keep `MASTER_KEY` off the box as well.** A restored database's encrypted credentials can't be read without it.

## Secrets

| Secret | Where |
|---|---|
| Claude's key | `ANTHROPIC_API_KEY` in the box's `.env` (D-142) |
| Power Planner's and Gmail's sign-ins, the parent code's secret | the database's `credentials` table and `secrets.bin`, AES-256-GCM under `MASTER_KEY` from `.env`; the sign-ins entered on the website, never sent from the phone (D-143); the parent code's secret on the phone too, which checks a code offline |
| A phone's token | the phone's encrypted secrets; its hash in `devices` |
| GitHub's client id and secret, the allowed user id | `.env` |
| R2's keys (Litestream's), B2's key (the nightly file's), Cloudflare's analytics token | `.env`, each given to the one service that uses it |
| The Firebase project's service account | `FCM_SERVICE_ACCOUNT` in `.env`, the key file in base64 |
| Web Push's VAPID keys | `.env`, made by `docker compose run --rm server vapid-keys` |

A session is a random token held hashed, so there's no session secret.

## Monitoring

- **The heartbeat**: healthchecks.io (`HEARTBEAT_URL`), each minute from the server's jobs, in either mode ([server.md](server.md) §Jobs). A missed one means the box or the process is down, reclaimed included, or that it can't write to its database, or that a job has stuck, as it shares their loop; and it says it failed while Litestream's uploaded nothing for 15 minutes. An email to the owner after a quarter of an hour's silence (period 5 minutes, grace 10).
- **Storage**: what the box, R2 and B2 hold is read once a day and shown in Settings, with an alert at 80% and 95% of a free allowance (D-161). R2's figures come from Cloudflare's GraphQL analytics, with `CF_ANALYTICS_TOKEN`: a token that reads the account's analytics and can't read or write the bucket. With no token, Settings says R2 isn't read.
- **Logs** are JSON lines on stdout, `docker compose logs`, capped by Docker. No log line ever holds a store's content, a credential or a request header.
- There's no uptime check from outside, so Caddy failing alone goes unnoticed.

`private/tools/` has the box's helpers (its `README.md`): `box.py` to reach it, `box_health.sh`, `box_after_deploy.py`, `box_files.py` after a merge that touched `deploy/`.

## Security

- SSH by key only; `unattended-upgrades` on; Docker from Docker's repository; the containers run as non-root users; `.env` and `data/` are 0600 and 0700.
- Caddy: TLS only, HSTS for a year, `nosniff`, no referrer sent, no `Server` header; the content security policy keeps the pages to their own origin.
- Every `/api` route is behind the session or a device's token but `health`, `version`, `me`, sign-in and claiming a code; sign-in and claiming a code are rate-limited ([server.md](server.md) §Signing in).
- The database holds credentials only encrypted, and tokens only hashed. The R2 bucket is private, its keys scoped to it.
- The Firebase key is the console's default, of its `firebase-adminsdk` account, which holds Firebase's admin roles over the whole project: the server asks only for Cloud Messaging's scope, but a leaked key could ask for more. The project holds nothing but this app's registration; a key of an account with the Firebase Cloud Messaging API Admin role alone would narrow it. A message says only a revision.
- The repository is public (D-79): the server's code, the compose file and the Caddyfile are world-readable, and nothing in them is a secret.

## Cost

Nothing a month but Claude's calls, the same calls as before under the same cap, while each free allowance holds: Oracle's Always Free instance, R2 (10 GB, a million Class A), B2 (10 GB), GHCR's public images, Let's Encrypt, one healthchecks.io check, Firebase Cloud Messaging. Paid once: R2's Class A requests past the million in November 2026 (§Litestream).

## What was read

The facts above about others' services were read from their own pages, and change: read them again before relying on one.

- Oracle Always Free (read 31 Oct 2026; the page updated 12 Jun 2026): the allowance, the reclamation rule, the capacity advice: https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm; its FAQ: https://www.oracle.com/cloud/free/faq/.
- Litestream's configuration and restore: https://litestream.io/reference/config/, https://litestream.io/reference/restore/, https://litestream.io/guides/s3-compatible/; its releases: https://github.com/benbjohnson/litestream/releases.
- R2's pricing and free tier (read 31 Oct 2026): https://developers.cloudflare.com/r2/pricing/.
- B2's keys, lifecycle rules and Object Lock (read 1 Nov 2026): https://www.backblaze.com/docs/cloud-storage-application-key-capabilities, https://www.backblaze.com/docs/cloud-storage-lifecycle-rules, https://www.backblaze.com/docs/cloud-storage-object-lock.
- Caddy's single-page-app pattern and its log filter: https://caddyserver.com/docs/caddyfile/patterns, https://caddyserver.com/docs/caddyfile/directives/log.
- Firebase Cloud Messaging's HTTP v1 send and installation ids (read 3 Nov 2026): https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages, https://firebase.google.com/docs/cloud-messaging/manage-tokens; a server's token from a service account: https://firebase.google.com/docs/cloud-messaging/auth-server.
- Web Push's library, `com.interaso:webpush`: https://github.com/interaso/webpush.

**Not verified**, and to be treated as unknown: that being on Pay As You Go stops reclamation today, or eases "out of host capacity" (community reports and a 2023 email only).
