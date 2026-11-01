# Decisions: what was decided for the website, and the questions that decided it

Made 31 Oct 2026. Numbered **W-n** here; each becomes a D-number in `docs/decisions.md` in the PR of the first stage that builds on it (stage 0's adds W-21; stage 1's W-1 to W-11, W-14, W-16 and W-17; stage 2's W-13, W-15, W-22 to W-24; stage 3's W-12, W-19 and W-20; stage 8's W-18; the switch's W-26). The questions continue `docs/plan_v4/answers.md`'s numbering, which ended at Q126, after the two of 4.11.0 (Q127, Q128): Q129 to Q138, in the order they were asked, then Q139 to Q141 in building it.

## The decisions

- **W-1 (D-126). The website is a second full client on shared state, not a replacement** (Q129). The server holds the state; the phone and the website are both clients. The phone keeps every device duty. Not: the phone primary with the website a mirror; not: the phone shrunk to an agent.
- **W-2 (D-127). The phone works offline to an extent** (the owner, unprompted, 31 Oct). Reads, the blocker, the timer and the alarms from a replica; writes queued and applied locally first; the plan re-laid locally and marked provisional; only Felix and the syncs need a connection. `architecture.md` §Offline on the phone.
- **W-3 (D-128). One account, the owner's** (Q130). Sign-in with GitHub restricted to one user id, as edit.thomaswhite.me's Worker does. Tables carry an owner id and nothing else is built for a second person. Not: multi-user from the start; not: a product.
- **W-4 (D-129). A Kotlin/JVM server reusing `:core`, with the app's pure logic cut into `:engine`** (Q132). One copy of the rules, the goldens and the migrations; the Anthropic Java SDK stays. Not: a TypeScript server with the rules ported (two copies to keep in step); not: Kotlin Multiplatform for the browser (a port of `java.time` through the planner first).
- **W-5 (D-130). A new Oracle Always Free Arm instance** (Q133): A1.Flex, the whole 2 OCPU / 12 GB allowance, bootstrapped as SharpnPro's box. The idle-reclamation risk is carried with its mitigations (`hosting.md` §The box). Not: a paid VPS; not: beside SharpnPro on the 1 GB micro; not: a PaaS.
- **W-6 (D-131). SQLite with Litestream to R2** (Q134). The stores as JSON documents in one table with a history table and the command log; a write is a transaction; the WAL streamed off the box. Not: the JsonStore files on a volume with timed copies; not: Postgres.
- **W-7 (D-132). A React + TypeScript single-page app served by Caddy from the same box** (Q135). Vite, vitest, Playwright, ESLint as the owner's sites; one origin, a cookie session, no CORS; the code in this repository under `web/`. Not: vanilla TypeScript; not: GitHub Pages with the API elsewhere; not: Compose Multiplatform for web.
- **W-8 (D-133). The server runs the Power Planner and Gmail syncs** (Q136), their credentials held on the server encrypted at rest, entered on the website. The Gmail finish too. Teams and AnkiDroid stay the phone's. Not: every sync on the phone; not: Power Planner on the server and Gmail on the phone.
- **W-9 (D-134). Notices in the page first; Web Push an optional last stage** (Q137). The phone keeps every notification throughout. Not: Web Push in the first release; not: in-page only, ever.
- **W-10 (D-135). No blocking on a laptop** (Q131). A browser extension would be its own project. Not: an optional last stage; not: a core part.
- **W-11 (D-136). The stages in the most technically feasible order; the website unused until complete** (Q138). The order serves the build. The phone keeps working at every release.

Decided without asking, each with its reason, for the owner to overturn:

- **W-12 (D-142). Claude is called from the server only**, the key an environment variable on the box. One place, one cap, one record, as `CLAUDE.md` asks; a phone that still called Claude would be a second place. The phone's calls become commands.
- **W-13 (D-140). Commands, not merged states.** The server is authoritative; clients send owner operations; the owners are the conflict policy, as they are today when a sync and a tap race. Merging two states store by store would need a merge rule for every list and would lose the owners' tolerance of stale calls.
- **W-14 (D-137). The website renders view models the server computes** (`DayModel`, `Groups`, `TaskSheetModel`, the stats) through `/api/views/*`. The TypeScript holds layout and interaction, never a rule about tasks, slots or days: the alternative is a slow re-port of the screens' Kotlin.
- **W-15. The calendar is written only from slots the server has confirmed.** The phone's provisional offline plan never reaches the calendar, so two planners never both write it.
- **W-16 (D-138). A protocol version in every request; the server accepts its own and the one before; a client behind gets a 409 and works offline until installed. The phone is never behind the server on a store's version:** a release that bumps one is installed on the phone before the merge that deploys the server (the merge checklist's phone check already comes first).
- **W-17 (D-139). One repository.** `:engine` and `:server` are Gradle modules beside `:app` and `:core` (they share `:core`'s build); `web/` and `deploy/` are folders. A second repository would duplicate the build and split the PRs that change both sides.
- **W-18. The phone learns of the server's changes at its sync (15 minutes, or the app's opening); Firebase Cloud Messaging is an optional stage** to make it seconds. The owner's day-to-day works at the slower cadence; the faster one matters once the website is in use.
- **W-19 (D-143). Credentials are re-entered on the website, never uploaded from the phone.** The phone deletes a credential once the server reports it holds its own. The TOTP secret lives on both (the phone verifies offline, the server verifies the website).
- **W-20 (D-144). Three independent copies of the state at all times**: Litestream's stream to R2, the nightly Backup-format file to Backblaze B2 under a key of its own that can write and not delete, and the phone (its replica and its own backups): three places, three credentials, so no one mistake takes two. `data-and-sync.md` §Backups.
- **W-21 (D-125). Versions.** Stage 0's moves are 4.12.x (4.11.0 is the chat's kept words and Day's menus) and its two store changes a minor of their own, 4.13.0, as a stored-data change isn't a cleanup; the first phone release that syncs is 5.0.0, and each later stage's phone release a minor. The server's image is tagged with the same version.
- **W-22. Nothing is deleted on the phone until stage 3**, and then only the credentials the server holds. A mistake in the sync's first two stages loses no data.
- **W-23. The protection delay is counted by the server's clock once the state is shared** (stage 2). Today the phone counts a loosening's 24 hours by its uptime clock, because the phone's date is the owner's to set; the server's clock isn't, so each pending change carries the `dueAt` the server set, the server's scheduler applies it when due, and the phone shows the list and never applies a loosening itself, online or offline. The time the phone is off now counts towards the wait; the wait still can't be hurried from either client. Until stage 2 the phone counts as today, `uptimeMark` kept beside `pending` in the synced store and read by the phone alone; stage 2 drops it.
- **W-24. Free-time credit is spent through the outbox.** Each 30 s spend while a blocked app runs on earned time is a command recorded locally, merged with the one before it until the upload, and a pull re-applies the outbox on top of the server's copy; so a pull can neither give free time back nor lose earned time.
- **W-25 (D-141). The switch to commands comes last** (decided in building it, 31 Oct, under Q138's "the most technically feasible order"; the owner's word on it, Q139). `stages.md` had it in stage 2, with Felix's calls, the readings and the syncs moving in stage 3: between the two, each change those make on the phone would need a command written only to be thrown away, and a sweep of the code found that 18 of the 20 stores are written by both a person and a background job. So the command layer, the outbox, the offline rules and the server's jobs are built in their stages and left dormant behind `meta.mode`, and the phone goes on mirroring, unchanged, until one last release switches it, rehearsed on real data first. Nothing about the end state changes, and the website isn't used until it's complete either way (W-11).
- **W-26. A first pull is never at the cost of what the phone holds** (Q141). When the phone goes over to commands it writes a backup of its own first, and it refuses a first pull from a server whose stores are older than the hashes it last sent: a pull takes the server's stores whole, so one from an older copy would write over newer data.

## Asked and answered

Ten questions, one at a time, each with its options and a recommendation; the owner's picks as given.

**Q129. What should the website be, in relation to the phone app?** Offered: two full clients on shared cloud state (recommended); the phone primary with the website a mirror that queues changes for it; the web primary with the phone shrunk to an agent. **Pick: two full clients.** → W-1.

**Q130. Who signs in?** Offered: just the owner, one account, tables with an owner id (recommended); the owner now and a few others later, designed in; a product for anyone. **Pick: just the owner.** → W-3.

**Q131. Should the website have an equivalent of the phone's blocking on a laptop?** Offered: an optional last stage for a browser extension (recommended); not part of this plan; a core part of the plan. **Pick: not part of this plan.** → W-10.

**Q132. What runs the rules on the server?** Offered: a Kotlin/JVM server reusing `:core`, the app's pure logic in a shared module (recommended); a TypeScript server with the rules ported; Kotlin Multiplatform so `:core` runs in the browser too. **Pick: Kotlin/JVM.** → W-4.

**Q133. Where should the JVM server run?** Offered: a new Oracle Always Free Arm instance (recommended); a small paid VPS; alongside SharpnPro on the 1 GB micro; a PaaS. **Pick: Oracle Always Free Arm.** → W-5.

**Q134. How should the server hold the state?** Offered: SQLite with Litestream to R2 or B2 (recommended); the JsonStore files on a volume with timed off-box copies; Postgres on the box or Supabase. **Pick: SQLite + Litestream.** → W-6.

**Q135. What is the website built with, and where is it served from?** Offered: a React + TypeScript SPA served by Caddy on the same box (recommended); vanilla TypeScript with esbuild as edit.thomaswhite.me; the SPA on GitHub Pages with the API on the box; Compose Multiplatform for web. **Pick: React + TypeScript on the box.** → W-7.

**Q136. Should the server run the Power Planner and Gmail syncs, their credentials moving to it?** Offered: yes, both (recommended); no, the phone keeps every sync; Power Planner on the server and Gmail on the phone. **Pick: yes, both.** → W-8.

**Q137. What should the website do about notices while a laptop has it open?** Offered: in the page now, Web Push as a later stage (recommended); Web Push from the first release; in the page only, ever. **Pick: in the page now, Web Push later.** → W-9.

**Q138. Which comes right after the read-only view: chat, task editing, or the Day grid?** Offered: chat with Felix (recommended); task editing first; the Day grid and timer first. **The owner's answer: it doesn't matter; the website isn't used until every stage is complete; the stages go in the most technically feasible order.** → W-11, and `stages.md`'s order.

Three more in building it, each put with a recommendation, the owner's answers of 1 Nov.

**Q139. The switch to commands comes last: is that right?** Offered: keep it (recommended), the safest for the phone in daily use, at the cost of not trying the website's writing pages on real data until the switch. **The owner's answer: as recommended.** → W-25.

**Q140. What of the room the server's history takes?** `store_history` keeps 90 days of whole copies and Litestream's 30 days of snapshots multiply it: by estimate inside R2's free 10 GB, not measured. Offered: leave it as planned and read the real size on the box after a fortnight, the snapshots cut to 7 days if it's near the limit (recommended). **The owner's answer: as recommended.** → §What the plan couldn't settle.

**Q141. A first pull takes the server's stores whole: what if the server's copy is the older?** Offered: at the switch the phone writes a backup of its own first, and refuses a first pull from a server older than the hashes it last sent (recommended). **The owner's answer: as recommended.** → W-26.

## Said without being asked

- "The phone should still be able to work offline to an extent." (31 Oct, while Q130 was open.) → W-2.

## What the plan couldn't settle

Open until the stage that meets each; none blocks the start.

- **Oracle's capacity and reclamation.** Whether an A1 instance can be had in the home region when stage 1 comes, and whether a PAYG upgrade stops idle reclamation today (`hosting.md` §The box, §Evidence). Settled by trying; the fallbacks are listed.
- **The server's memory.** No credible measured figure for a small Ktor app on JVM 21; stage 1 measures it and sets `-Xmx` and `mem_limit` from the measurement.
- **How owner calls are recorded on the phone** (stage 2): interfaces with a recording implementation, or an outbox port the owners write to. Settled when the owners' shapes after stage 0 are seen.
- **The IMAP client on JRE 21.** The phone's Gmail source uses the JVM's socket and TLS classes; stage 3 confirms it runs unchanged on the server.
- **`calendar.json`'s `eventAnswers`.** Learnt answers that only the phone holds; a later move to the server would let the website show them. Not in this plan.
- **Voice input on the website.** The phone uses Android's recogniser; the browser's speech API is a maybe for stage 5, not planned.
- **The history's size.** Whether 90 days of `store_history` and 30 of snapshots stay inside R2's free tier. Read on the box after a fortnight; near the limit, the snapshots go to 7 days, as the nightly file covers the long term (Q140).
