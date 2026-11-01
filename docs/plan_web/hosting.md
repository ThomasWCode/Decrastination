# Hosting: where it runs, what it costs, and the evidence

Decided 31 Oct 2026 (W-5, W-6, W-7). The pattern is SharpnPro's: one box, Docker Compose, Caddy for TLS, an image built by GitHub Actions and pulled by a systemd timer, so a merge to `main` becomes a running server with nobody logging in. The facts below were read from the primary sources on 31 Oct 2026; §Evidence lists them, with what could not be verified.

## The box

An Oracle Cloud **Always Free** `VM.Standard.A1.Flex` (Arm) instance, Ubuntu 24.04 aarch64, in the tenancy's home region.

- The Always Free A1 allowance is **2 OCPUs and 12 GB in all** (one instance of 2/12, or two of 1/6). It was 4 OCPUs and 24 GB until 15 June 2026, when Oracle halved it without announcement; instances over the new allowance were terminated from 18 August 2026. Take the whole allowance as one instance.
- Block storage: 200 GB across boot and block volumes, five volume backups. Outbound: 10 TB a month. Object storage (a free-only account): 20 GB, 50,000 requests a month — not used; R2 is.
- **"Out of host capacity"** on creating an A1 instance is common. Oracle's advice: another availability domain, or wait and retry; the FAQ says it can take days. A retry script against the API is the usual way round it. Upgrading the account to Pay As You Go is said to give priority; Oracle doesn't state that.
- **Idle reclamation**, Oracle's rule verbatim: an instance is idle if, over 7 days, CPU utilisation at the 95th percentile is under 20%, network utilisation is under 20%, and (A1 only) memory utilisation is under 20%. All three together. This server idles: its CPU is a few percent, its network is small. So it **is** at risk of being stopped, with about a week's emailed notice (Oracle's past practice, 2023). Mitigations, in order of preference:
  1. Upgrade the account to **Pay As You Go**. Oracle's docs say Always Free resources stay free after the upgrade, and Oracle's 2023 notice said converting stops idle instances being stopped; today's docs don't repeat that promise. A card on file, a $0 bill while within the allowance, and a budget alert set at $1.
  2. Memory above 20%: a JVM heap sized so the instance's used memory sits over 2.4 GB of 12 (a `-Xms` that large, or a smaller instance shape of 1 OCPU / 6 GB with `-Xms1500m`). Honest about it: this games a rule; it's a fallback, not the plan.
  3. Monitoring that notices a stopped instance (the heartbeat below) so it's restarted within the hour; the state is on the volume and in R2 either way.

A free-only account has no Oracle support, and an account left idle 30 days can be deemed abandoned; the server's own activity keeps it from that.

## What runs on it

`/opt/decrastination/`, as SharpnPro's `/opt/sharpn/`: `compose.yaml`, `litestream.yml`, `.env` (mode 0600), `data/`. The `Caddyfile` is in the web image.

| Service | Image | Does | Memory |
|---|---|---|---|
| `server` | `ghcr.io/thomaswcode/decrastination-server:<version>` (`eclipse-temurin:21-jre` + the fat JAR) | the API and the jobs, port 8080 inside | `mem_limit` 1.5 GB, `-Xmx1g`, SerialGC; 175 MB in use on the box with the real stores and a browser connected (1 Nov 2026) |
| `web` | `ghcr.io/thomaswcode/decrastination-web:<version>` (`caddy:2-alpine` + the SPA's `dist/`) | TLS, the static files, `/api` to `server:8080` | 21 MB on the box |
| `litestream` | `litestream/litestream:0.5` | replicates `data/decrastination.db` to R2, sharing the `data` volume | 12 MB on the box |

The server and Litestream share one volume; Litestream runs as a sidecar (the documented pattern), so the server's image stays a plain JRE image. Ports 80 and 443 (TCP, and 443 UDP for HTTP/3) open in the VCN's security list and in the instance's iptables, both — Oracle's Ubuntu images reject everything but SSH until the second is done, as SharpnPro's `bootstrap.sh` already handles.

The Caddyfile (`deploy/Caddyfile`) is Caddy's own single-page-app pattern with the headers above and the event stream left uncompressed, so each event reaches the browser as it's sent.

Caddy gets and renews the certificate from Let's Encrypt (ZeroSSL as the fallback) on its own; HTTP redirects to HTTPS.

## DNS

The domain's nameservers are Spaceship's, and `thomaswhite.me` points at GitHub Pages. `decrastination.thomaswhite.me` gets an **A record** (and an AAAA if the instance has IPv6) to the instance's public address, at Spaceship. No `CNAME` file and no Pages repository: the site isn't on Pages (W-7).

## Deploy

GitHub Actions, in `ci.yml`, on a push to `main`:

1. Gradle builds and tests as today, plus `:server:buildFatJar` (the Ktor plugin's task; the JAR lands in `server/build/libs/`). `web/`: `npm ci`, lint, vitest, `vite build`.
2. Two images, built with `docker/build-push-action`, pushed to GHCR with the workflow's `GITHUB_TOKEN` (`packages: write`), tagged with the release's version and `latest`:
   - `decrastination-server`: `FROM eclipse-temurin:21-jre`, `COPY` the JAR. The base image is published for arm64 and amd64, and a JAR is the same on both, so one `COPY` gives a multi-arch image with **no QEMU and no compilation under emulation**. (The repository is public, so GitHub's native `ubuntu-24.04-arm` runners are free as well, if a step ever needs to run on arm.)
   - `decrastination-web`: `FROM caddy:2-alpine`, `COPY dist/ /srv/`, `COPY Caddyfile /etc/caddy/Caddyfile`.
3. On the box, `decrastination-update.timer` runs every three minutes: `docker compose pull`, then `up -d`, which changes nothing unless an image has.

The phone's APK comes from the same CI run as today. Order on a release that changes the stores or the protocol: the APK is checked on the phone (the merge checklist), then the merge deploys the server (`data-and-sync.md` §Versions and compatibility).

Images are public on GHCR as SharpnPro's is; they hold no secret. Everything secret is in `.env` on the box, read by Compose, which gives each service only its own: `DOMAIN`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `ALLOWED_GITHUB_USER_ID`, `MASTER_KEY`, `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`; stage 3 adds `ANTHROPIC_API_KEY`, `B2_ENDPOINT`, `B2_KEY_ID`, `B2_APPLICATION_KEY`, `B2_BUCKET`. `HEARTBEAT_URL` too. A session is a random token held hashed, so there's no session secret. `deploy/env.production.example` lists them with no values.

## Litestream

Version 0.5.x (0.5.17 at the time of writing; one `replica:` a database). The configuration, from Litestream's own S3-compatible guide:

```yaml
dbs:
  - path: /data/decrastination.db
    replica:
      type: s3
      bucket: ${R2_BUCKET}
      path: db
      endpoint: ${R2_ACCOUNT_ID}.r2.cloudflarestorage.com
      region: auto
      access-key-id: ${R2_ACCESS_KEY_ID}
      secret-access-key: ${R2_SECRET_ACCESS_KEY}
```

Litestream's default is 24 hours' snapshots kept 24 hours; `deploy/litestream.yml` has `snapshot: retention: 720h`, so the plan's 30 days hold.

Restore on a fresh box: `litestream restore -config /etc/litestream.yml -if-db-not-exists -if-replica-exists /data/decrastination.db`, run by `bootstrap.sh` before the server starts, so a rebuilt box comes up with the state. `-timestamp` restores to a moment, for a rollback. A failed restore stops `bootstrap.sh`, as an empty server started beside Litestream would replicate over the good copy.

**R2's free tier**: 10 GB stored, a million Class A operations and ten million Class B a month, no egress charge. Litestream's steady writes are well inside it. The nightly Backup-format file goes to **Backblaze B2** (10 GB free) under an application key of its own, so the two off-box copies share no provider and no credential (W-20); Litestream could replicate to B2 the same way if R2 ever stopped suiting.

**What's to hold the nightly copies.** B2's console can't make a key that can't delete: its *Write Only* access carries `deleteFiles` and `writeBucketLifecycleRules`, and on 1 Nov 2026 such a key deleted a version outright, while listing and reading were refused. A narrower key can be made only through B2's API, with the account's master key. So the bucket has Object Lock on (the owner's word, 1 Nov), for 60 days' default retention, which binds every key alike, and can't be turned off on that bucket. That it holds isn't proven: a delete still went through on the last try that day, so it's tried again at the switch, when nightly files first go there (`private/tools/b2_refusals.py`). The bucket's lifecycle rule hides a file 60 days after it's written and deletes it 60 days after that. Both numbers do work: a file named by its date is never replaced, so nothing else would ever hide it, and a key that can write can hide every file at once, so the second 60 is the time there is to notice. At about 400 KB a file that's 50 MB.

## Monitoring

A heartbeat to healthchecks.io every minute from the server's scheduler, in either mode (5.9.3): a missed one means the box or the process is down, reclaimed included, or that it can't write to its database, or that a job has stuck, as it shares their loop. Alerts by email to the owner after a quarter of an hour's silence (period 5 minutes, grace 10). There's no uptime check from outside, so Caddy failing alone goes unnoticed. Logs are JSON lines on stdout, `docker compose logs`. No log line ever holds a store's content, a credential or a request header (the owner's rule): Caddy's go through one filter on both its loggers, which drops the headers and the sign-in's one-time code, as a request that fails is written by a different logger from one that doesn't. A client closing an event stream, as each page left does, isn't logged as a failure.

## Security

- SSH by key only; `unattended-upgrades` on; Docker from Docker's repository; the containers run as non-root users; `.env` and `data/` are 0600/0700.
- Caddy: TLS only, HSTS, the headers above, no `Server` header. The CSP keeps the SPA to its own origin.
- Every `/api` route behind the session or a device token except health, version and sign-in; sign-in and claiming rate-limited.
- The database file holds credentials only encrypted (`MASTER_KEY`), and device tokens only hashed.
- R2 bucket private; its keys scoped to that bucket.
- The repository is public: the server's code, the compose file and the Caddyfile are world-readable, as they should be; nothing in them is a secret.

## Cost

| Item | Monthly |
|---|---|
| Oracle A1.Flex 2 OCPU / 12 GB, 100 GB boot volume | £0 (Always Free; a PAYG upgrade changes nothing within the allowance) |
| Cloudflare R2, under 1 GB | £0 |
| Backblaze B2, under 1 GB | £0 |
| GHCR images, public | £0 |
| Let's Encrypt | £0 |
| healthchecks.io, one check | £0 |
| Claude | as today: the same calls, from the server, under the same cap |
| The domain | already paid |

## Evidence

Read 31 Oct 2026. A page's own "updated" date is given where it shows one.

- Oracle Always Free resources (updated 12 Jun 2026): the A1 allowance "equivalent to 2 OCPUs and 12 GB of memory", the reclamation rule, the capacity advice: https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm. The same page on 1 Mar 2026, with 4 OCPUs / 24 GB: http://web.archive.org/web/20260301095332/https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm. The halving reported: https://infoq.com/news/2026/07/oracle-cloud-free-tier-limits/. Oracle's FAQ (PAYG "subject to different capacity limits"; idle accounts): https://www.oracle.com/cloud/free/faq/. The 2023 idle notice, with the PAYG line: https://blog.51sec.org/2023/02/oracle-cloud-cleaning-up-idle-compute.html. A capacity retry script, archived 2024: https://github.com/hitrov/oci-arm-host-capacity.
- Ktor 3.6.1 (30 Oct 2026): https://github.com/ktorio/ktor/releases; the Gradle plugin: https://plugins.gradle.org/plugin/io.ktor.plugin; fat JARs: https://ktor.io/docs/server-fatjar.html; Docker: https://ktor.io/docs/docker.html. Netty's arm64 native transport: https://netty.io/wiki/native-transports.html.
- Litestream 0.5.17 (31 Aug 2026): https://github.com/benbjohnson/litestream/releases; configuration: https://litestream.io/reference/config/; R2 and other S3-compatible stores: https://litestream.io/guides/s3-compatible/; Backblaze: https://litestream.io/guides/backblaze/; Docker and the sidecar: https://litestream.io/guides/docker/; restore: https://litestream.io/reference/restore/.
- Cloudflare R2 pricing and free tier (updated 30 Oct 2026): https://developers.cloudflare.com/r2/pricing/; the S3 endpoint and `region: auto` (updated 14 Oct 2026): https://developers.cloudflare.com/r2/api/s3/api/. Backblaze B2 pricing (undated, and inconsistent between its two pages): https://www.backblaze.com/cloud-storage/pricing.
- Caddy's single-page-app pattern: https://caddyserver.com/docs/caddyfile/patterns; automatic HTTPS: https://caddyserver.com/docs/automatic-https.
- Multi-platform images in Actions: https://docs.docker.com/build/ci/github-actions/multi-platform/; QEMU's slowness and cross-compilation: https://docs.docker.com/build/building/multi-platform/; GHCR from Actions: https://docs.github.com/en/actions/use-cases-and-examples/publishing-packages/publishing-docker-images; `eclipse-temurin` architectures: https://hub.docker.com/_/eclipse-temurin; arm64 runners free in public repositories: https://github.blog/changelog/2026-01-29-arm64-standard-runners-are-now-available-in-private-repositories.
- Web Push (stage 9): RFC 8030, 8291, 8292 (https://www.rfc-editor.org/rfc/rfc8292); Safari on macOS 16+ and iOS 16.4+ for installed web apps (https://webkit.org/blog/12945/meet-web-push/, https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/); support table: https://caniuse.com/push-api. JVM libraries: `com.interaso:webpush` 1.3.0 (Jan 2026, Kotlin, no dependencies, small): https://github.com/interaso/webpush; `nl.martijndwars:web-push` 5.1.2 (Feb 2025, BouncyCastle, quiet since Jul 2025): https://github.com/web-push-libs/webpush-java.

- Read 1 Nov 2026. B2's keys and S3: https://www.backblaze.com/docs/cloud-storage-application-key-capabilities, https://www.backblaze.com/docs/cloud-storage-s3-compatible-app-keys; lifecycle rules: https://www.backblaze.com/docs/cloud-storage-lifecycle-rules; Object Lock: https://www.backblaze.com/docs/cloud-storage-object-lock, https://www.backblaze.com/docs/cloud-storage-enable-object-lock-or-a-legal-hold-on-an-existing-bucket. Caddy's log filter: https://caddyserver.com/docs/caddyfile/directives/log.

**Not verified**, and to be treated as unknown: that a PAYG upgrade stops reclamation today (community reports and a 2023 email only); that PAYG eases "out of capacity" (community only); whether the Ktor plugin's `publishImage` can push to GHCR (not needed: `build-push-action` does it).
