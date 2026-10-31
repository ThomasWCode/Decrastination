# Decisions: what was decided for the website, and the questions that decided it

Made 31 Oct 2026. Numbered **W-n** here; each becomes a D-number in `docs/decisions.md` in the release that builds it (stage 1's PR adds W-1 to W-11). The questions continue `docs/plan_v4/answers.md`'s numbering, which ended at Q126, in the order they were asked.

## The decisions

- **W-1. The website is a second full client on shared state, not a replacement** (Q127). The server holds the state; the phone and the website are both clients. The phone keeps every device duty. Not: the phone primary with the website a mirror; not: the phone shrunk to an agent.
- **W-2. The phone works offline to an extent** (the owner, unprompted, 31 Oct). Reads, the blocker, the timer and the alarms from a replica; writes queued and applied locally first; the plan re-laid locally and marked provisional; only Felix and the syncs need a connection. `architecture.md` §Offline on the phone.
- **W-3. One account, the owner's** (Q128). Sign-in with GitHub restricted to one user id, as edit.thomaswhite.me's Worker does. Tables carry an owner id and nothing else is built for a second person. Not: multi-user from the start; not: a product.
- **W-4. A Kotlin/JVM server reusing `:core`, with the app's pure logic cut into `:engine`** (Q130). One copy of the rules, the goldens and the migrations; the Anthropic Java SDK stays. Not: a TypeScript server with the rules ported (two copies to keep in step); not: Kotlin Multiplatform for the browser (a port of `java.time` through the planner first).
- **W-5. A new Oracle Always Free Arm instance** (Q131): A1.Flex, the whole 2 OCPU / 12 GB allowance, bootstrapped as SharpnPro's box. The idle-reclamation risk is carried with its mitigations (`hosting.md` §The box). Not: a paid VPS; not: beside SharpnPro on the 1 GB micro; not: a PaaS.
- **W-6. SQLite with Litestream to R2** (Q132). The stores as JSON documents in one table with a history table and the command log; a write is a transaction; the WAL streamed off the box. Not: the JsonStore files on a volume with timed copies; not: Postgres.
- **W-7. A React + TypeScript single-page app served by Caddy from the same box** (Q133). Vite, vitest, Playwright, ESLint as the owner's sites; one origin, a cookie session, no CORS; the code in this repository under `web/`. Not: vanilla TypeScript; not: GitHub Pages with the API elsewhere; not: Compose Multiplatform for web.
- **W-8. The server runs the Power Planner and Gmail syncs** (Q134), their credentials held on the server encrypted at rest, entered on the website. The Gmail finish too. Teams and AnkiDroid stay the phone's. Not: every sync on the phone; not: Power Planner on the server and Gmail on the phone.
- **W-9. Notices in the page first; Web Push an optional last stage** (Q135). The phone keeps every notification throughout. Not: Web Push in the first release; not: in-page only, ever.
- **W-10. No blocking on a laptop** (Q129). A browser extension would be its own project. Not: an optional last stage; not: a core part.
- **W-11. The stages in the most technically feasible order; the website unused until complete** (Q136). The order serves the build. The phone keeps working at every release.

Decided without asking, each with its reason, for the owner to overturn:

- **W-12. Claude is called from the server only**, the key an environment variable on the box. One place, one cap, one record, as `CLAUDE.md` asks; a phone that still called Claude would be a second place. The phone's calls become commands.
- **W-13. Commands, not merged states.** The server is authoritative; clients send owner operations; the owners are the conflict policy, as they are today when a sync and a tap race. Merging two states store by store would need a merge rule for every list and would lose the owners' tolerance of stale calls.
- **W-14. The website renders view models the server computes** (`DayModel`, `Groups`, `TaskSheetModel`, the stats) through `/api/views/*`. The TypeScript holds layout and interaction, never a rule about tasks, slots or days: the alternative is a slow re-port of the screens' Kotlin.
- **W-15. The calendar is written only from slots the server has confirmed.** The phone's provisional offline plan never reaches the calendar, so two planners never both write it.
- **W-16. A protocol version in every request; the server accepts its own and the one before; a client behind gets a 409 and works offline until installed. The phone is never behind the server on a store's version:** a release that bumps one is installed on the phone before the merge that deploys the server (the merge checklist's phone check already comes first).
- **W-17. One repository.** `:engine` and `:server` are Gradle modules beside `:app` and `:core` (they share `:core`'s build); `web/` and `deploy/` are folders. A second repository would duplicate the build and split the PRs that change both sides.
- **W-18. The phone learns of the server's changes at its sync (15 minutes, or the app's opening); Firebase Cloud Messaging is an optional stage** to make it seconds. The owner's day-to-day works at the slower cadence; the faster one matters once the website is in use.
- **W-19. Credentials are re-entered on the website, never uploaded from the phone.** The phone deletes a credential once the server reports it holds its own. The TOTP secret lives on both (the phone verifies offline, the server verifies the website).
- **W-20. Three independent copies of the state at all times**: Litestream's stream, the nightly Backup-format file, the phone's replica and its own backups. `data-and-sync.md` §Backups.
- **W-21. Versions.** Stage 0's cleanups are 4.11.x; the first phone release that syncs is 5.0.0, and each later stage's phone release a minor. The server's image is tagged with the same version.
- **W-22. Nothing is deleted on the phone until stage 3**, and then only the credentials the server holds. A mistake in the sync's first two stages loses no data.

## Asked and answered

Ten questions, one at a time, each with its options and a recommendation; the owner's picks as given.

**Q127. What should the website be, in relation to the phone app?** Offered: two full clients on shared cloud state (recommended); the phone primary with the website a mirror that queues changes for it; the web primary with the phone shrunk to an agent. **Pick: two full clients.** → W-1.

**Q128. Who signs in?** Offered: just the owner, one account, tables with an owner id (recommended); the owner now and a few others later, designed in; a product for anyone. **Pick: just the owner.** → W-3.

**Q129. Should the website have an equivalent of the phone's blocking on a laptop?** Offered: an optional last stage for a browser extension (recommended); not part of this plan; a core part of the plan. **Pick: not part of this plan.** → W-10.

**Q130. What runs the rules on the server?** Offered: a Kotlin/JVM server reusing `:core`, the app's pure logic in a shared module (recommended); a TypeScript server with the rules ported; Kotlin Multiplatform so `:core` runs in the browser too. **Pick: Kotlin/JVM.** → W-4.

**Q131. Where should the JVM server run?** Offered: a new Oracle Always Free Arm instance (recommended); a small paid VPS; alongside SharpnPro on the 1 GB micro; a PaaS. **Pick: Oracle Always Free Arm.** → W-5.

**Q132. How should the server hold the state?** Offered: SQLite with Litestream to R2 or B2 (recommended); the JsonStore files on a volume with timed off-box copies; Postgres on the box or Supabase. **Pick: SQLite + Litestream.** → W-6.

**Q133. What is the website built with, and where is it served from?** Offered: a React + TypeScript SPA served by Caddy on the same box (recommended); vanilla TypeScript with esbuild as edit.thomaswhite.me; the SPA on GitHub Pages with the API on the box; Compose Multiplatform for web. **Pick: React + TypeScript on the box.** → W-7.

**Q134. Should the server run the Power Planner and Gmail syncs, their credentials moving to it?** Offered: yes, both (recommended); no, the phone keeps every sync; Power Planner on the server and Gmail on the phone. **Pick: yes, both.** → W-8.

**Q135. What should the website do about notices while a laptop has it open?** Offered: in the page now, Web Push as a later stage (recommended); Web Push from the first release; in the page only, ever. **Pick: in the page now, Web Push later.** → W-9.

**Q136. Which comes right after the read-only view: chat, task editing, or the Day grid?** Offered: chat with Felix (recommended); task editing first; the Day grid and timer first. **The owner's answer: it doesn't matter; the website isn't used until every stage is complete; the stages go in the most technically feasible order.** → W-11, and `stages.md`'s order.

## Said without being asked

- "The phone should still be able to work offline to an extent." (31 Oct, while Q128 was open.) → W-2.

## What the plan couldn't settle

Open until the stage that meets each; none blocks the start.

- **Oracle's capacity and reclamation.** Whether an A1 instance can be had in the home region when stage 1 comes, and whether a PAYG upgrade stops idle reclamation today (`hosting.md` §The box, §Evidence). Settled by trying; the fallbacks are listed.
- **The server's memory.** No credible measured figure for a small Ktor app on JVM 21; stage 1 measures it and sets `-Xmx` and `mem_limit` from the measurement.
- **How owner calls are recorded on the phone** (stage 2): interfaces with a recording implementation, or an outbox port the owners write to. Settled when the owners' shapes after stage 0 are seen.
- **The IMAP client on JRE 21.** The phone's Gmail source uses the JVM's socket and TLS classes; stage 3 confirms it runs unchanged on the server.
- **`calendar.json`'s `eventAnswers`.** Learnt answers that only the phone holds; a later move to the server would let the website show them. Not in this plan.
- **Voice input on the website.** The phone uses Android's recogniser; the browser's speech API is a maybe for stage 5, not planned.
