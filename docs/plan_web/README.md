# The website's plan: the record

Planned 31 Oct 2026, from ten questions answered that day; built as stages 0 to 9 (4.12.0 to 5.11.0), each dormant behind the server's mode; switched on 4 Nov 2026 (5.12.0 to 6.0.0, with 6.0.1 after it). This file is what's left of the plan's folder, as [plan_v4/README.md](../plan_v4/README.md) is of the 4.x plan's: what it set out to do, what was built otherwise than planned, its own numbers (W-1 to W-29, which the code still cites), the questions asked, and what's still open.

How it works now is in `docs/spec/`: [server.md](../spec/server.md), [sync.md](../spec/sync.md), [website.md](../spec/website.md), [hosting.md](../spec/hosting.md). The decisions are D-125 to D-149 in [decisions.md](../decisions.md).

## What it set out to do

The app lived on one phone: its state that phone's files, its planner and Felix run there. The owner wanted most of it on a website too, from a laptop, with the phone and the website showing one state. Some of what the phone does can't leave it (Teams read by driving the Teams app, AnkiDroid's content provider, the blocker, the calendar), so the website is a second full client, not a replacement.

A Kotlin server (Ktor) holds the state in SQLite, replicated off the box by Litestream, and runs the rules from a module cut out of the app (`:engine`, with `:core`). The phone keeps every device duty and becomes a client with a copy of the state and an outbox of its own changes, so it works offline. The website is a React and TypeScript single-page app served from the same box, signed in with the owner's GitHub account alone. Claude is called from the server only. It runs on an Oracle Always Free instance.

**Goals**: one state, two clients; the phone working offline; no data lost (every command and every store's history kept, three copies); one copy of the rules; nothing personal in the public repository; no new calls to Claude.

**Not in scope**: blocking on a laptop; other users; Teams, AnkiDroid or the calendar moved off the phone; a native desktop app, or a website that works offline; a new look.

## The W-numbers

The plan numbered its decisions W-n, and the code's comments cite them so. Each became a D-number in [decisions.md](../decisions.md) but four, given here as built.

| W | D | What |
|---|---|---|
| W-1 | D-126 | a second full client on shared state |
| W-2 | D-127 | the phone works offline to an extent |
| W-3 | D-128 | one account, the owner's |
| W-4 | D-129 | a Kotlin/JVM server reusing `:core` and `:engine` |
| W-5 | D-130 | an Oracle Always Free Arm instance |
| W-6 | D-131 | SQLite with Litestream to R2 |
| W-7 | D-132 | a React and TypeScript app served by Caddy from the same box |
| W-8 | D-133 | the server runs the Power Planner and Gmail syncs |
| W-9 | D-134 | notices in the page first, Web Push a last stage |
| W-10 | D-135 | no blocking on a laptop |
| W-11 | D-136 | the stages in the most technically feasible order |
| W-12 | D-142 | Claude called from the server only |
| W-13 | D-140 | commands, not merged states |
| W-14 | D-137 | the website renders view models the server computes |
| W-15 | — | below |
| W-16 | D-138 | a protocol version in every request; the phone never behind the server on a store's version |
| W-17 | D-139 | one repository |
| W-18 | — | below |
| W-19 | D-143 | credentials re-entered on the website, never uploaded |
| W-20 | D-144 | three independent copies of the state |
| W-21 | D-125 | the versions: 4.12.x, 4.13.0, 5.0.0 on |
| W-22 | — | below |
| W-23 | — | below |
| W-24 | D-147 | what only the phone can know goes as commands |
| W-25 | D-141 | the switch to commands comes last |
| W-26 | D-149 | a first pull never at the cost of what the phone holds |
| W-27 | D-145 | `google-services.json` out of git |
| W-28 | D-146 | the phone's Import through the server |
| W-29 | D-148 | the phone's photos through the server |

- **W-15. The calendar is written only from slots the server has confirmed**, so two planners never both write it. The phone plans for itself while a change of its own waits to go, so DO NOW and the blocker follow a task finished offline, and the calendar waits till the outbox is empty and the plan's made again (`AppGraph`). The plan had a provisional mark on such slots; none was needed.
- **W-18. The phone learns of the server's changes at its sync; Firebase Cloud Messaging an optional stage** to make it seconds. Built as stage 8 (5.10.0, 5.11.0), the phone reached by its Firebase Installation ID ([server.md](../spec/server.md) §Devices, [sync.md](../spec/sync.md) §Told at once).
- **W-22. Nothing is deleted on the phone until the server holds its own**, and then only the credentials. A mistake in the sync's first stages could lose no data. As built, nothing is deleted at all: the phone keeps its sign-ins and Claude's key, and stops using them once it sends commands, which D-143 now says (the owner's word, 21 Nov 2026).
- **W-23. The protection delay is counted by the server's clock once the state is shared.** The phone counts a loosening's wait by its uptime, as its date is the owner's to set; the server's clock isn't. Built without the `dueAt` the plan gave each pending change: the server counts by its own time through the same `SettingsChanges.counting`, a mark of the phone's being another clock's there, and the phone, sending commands, applies no loosening itself ([blocking-and-protection.md](../spec/blocking-and-protection.md)).

## The stages, as built

Estimated at 61 to 83 hours of sessions (66 to 90 with the two optional stages). The order was the most technically feasible, as the website wasn't to be used till all of it was done; the phone kept working through every release.

| Stage | Releases | What, and what went otherwise than planned |
|---|---|---|
| 0 | 4.12.x, 4.13.0 | `:engine`: the app's Android-free code in a module the app and the server both build, moves alone; then the store split (`protection.json`'s device half to `device.json`, `notSame` to `tasks.json`, backup format 11) |
| 1 | 5.0.0 | The server, mirroring; pairing; the Day and Work to read; the box, live from 1 Nov 2026 (5.9.2, 5.9.3), when the restore drill was run. The heartbeat came forward from the switch |
| 2 | 5.1.0, 5.2.0 | The command layer on the phone, then the exchange, the outbox and the stale cases, dormant. **The switch moved to the end** (W-25): 18 of the 20 stores were written by both a person and a background job, so a switch here would have needed commands written only to be thrown away |
| 3 | 5.3.0 | The server's jobs, the credentials, the nightly file, Claude's gateway on the server |
| 4 | 5.4.0 | The website writes: Work, the task sheet, Settings, the parent code |
| 5 | 5.5.0 | The chat as the website's home. The archive read by count, not by month; your changes beside the chat, not on a tab; Claude in the browser tests a stand-in of their own |
| 6 | 5.6.0 | The Day's grid with drag, and the timer. A drop is judged by the server (`POST /day/drop`), not sent as a move |
| 7 | 5.7.0, 5.8.0 | Stats, This week, Reviews; then Backup, Reset, Setup. A reset is a command; a backup taken in is a route, held as a command is |
| 9 | 5.9.0 | Web Push. Which channels is each browser's own, not a setting; a slot's start is told, which the phone has no notification for |
| 8 | 5.10.0, 5.11.0 | The phone told at once. By its Firebase Installation ID, as Firebase had deprecated the registration token the plan named; `google-services.json` kept out of git (W-27); `ReplicaWorker`, not `SyncWorker` |
| Last | 5.12.0 to 6.0.0, 6.0.1 | The switch: the phone's last whole seed, the mode set in its transaction (5.12.0); what only the phone knows as commands (5.13.0, W-24, widened from free time alone after a sweep found every write outside a command would go at the next pull); the offline lines and what's asked of the server (5.14.0); Gmail's journal (5.15.0); the photo check (5.16.0); the switch itself (6.0.0), made 4 Nov 2026 in 2.4 s. The server's first Gmail read then failed on a JVM's default host name verifier: 6.0.1 |

## Asked and answered

The numbers continue [answers.md](../plan_v4/answers.md)'s, and go on there from Q146. Each was put with its options and a recommendation; what wasn't picked is in the decision's "Not".

- **Q129. What should the website be, beside the phone?** Two full clients on shared state; the phone primary and the website a mirror; or the web primary and the phone an agent. **Two full clients.** → W-1.
- **Q130. Who signs in?** Just the owner; a few others later, designed in; or a product. **Just the owner.** → W-3.
- **Q131. Blocking on a laptop?** An optional last stage; not in this plan; or a core part. **Not in this plan.** → W-10.
- **Q132. What runs the rules on the server?** Kotlin/JVM reusing `:core`; TypeScript with the rules ported; or Kotlin Multiplatform. **Kotlin/JVM.** → W-4.
- **Q133. Where does it run?** A new Oracle Always Free Arm instance; a paid VPS; beside another project on a 1 GB micro; or a PaaS. **Oracle Always Free.** → W-5.
- **Q134. How is the state held?** SQLite with Litestream; the JsonStore files on a volume; or Postgres. **SQLite and Litestream.** → W-6.
- **Q135. What's the website built with, and served from?** React and TypeScript on the box; vanilla TypeScript; GitHub Pages with the API elsewhere; or Compose for web. **React and TypeScript on the box.** → W-7.
- **Q136. Should the server run the Power Planner and Gmail syncs?** Both; neither; or Power Planner alone. **Both.** → W-8.
- **Q137. Notices while a laptop has it open?** In the page now and Web Push later; Web Push at once; or in the page only. **In the page now, Web Push later.** → W-9.
- **Q138. What comes after the read-only view?** The owner: it doesn't matter, as the website isn't used until every stage is done; the most technically feasible order. → W-11.
- **Q139. The switch to commands comes last: is that right?** (1 Nov.) **Yes.** → W-25.
- **Q140. What of the room the server's history takes?** (1 Nov.) Leave it, and read the real size after a fortnight. **Yes.** Read on 11 Nov: 531 MB, 494 of it `store_history`, growing by about 65 MB a day. → D-151, then D-154.
- **Q141. What if the server's copy is the older at a first pull?** (1 Nov.) The phone saves a copy first, and refuses a pull that would cost anything. **Yes.** → W-26.
- **Q142. `google-services.json`: commit it, or keep it out of git?** (3 Nov.) GitHub's secret scanning would flag its key. **Out of git.** → W-27.
- **Q143. A repeated command's answer leaves out its Undo and its words: keep them on record?** (3 Nov.) No client reads them on a repeat. **Accept it.** → [sync.md](../spec/sync.md) §The exchange.
- **Q144. After the switch, the phone's Import?** (3 Nov.) Sent to the server's, with a pull of everything after; or left to the website. **Sent to the server's.** → W-28.
- **Q145. After the switch, the phone's three features that ask Claude with a photo?** (3 Nov.) The photos sent to the server, which asks; or off on the phone. **Sent to the server.** → W-29.

Said without being asked, 31 Oct: "The phone should still be able to work offline to an extent." → W-2.

## What the plan couldn't settle, and how each ended

- **Oracle's capacity.** An instance was had, and the box has run since 1 Nov 2026. The account was upgraded to Pay As You Go; whether that stops idle reclamation today is still unverified ([hosting.md](../spec/hosting.md) §The box).
- **The server's memory.** Measured on the box: 175 MB in use, `-Xmx1g` and a 1.5 GB limit.
- **How the phone records its owners' calls.** Every change a screen makes is an action handed to `graph.act`, recorded in the outbox before it's applied ([sync.md](../spec/sync.md) §Commands).
- **The IMAP client on a server's JVM.** It ran but for the host name check, mended in 6.0.1.
- **The calendar's learnt answers.** Still the phone's; one given on the website is kept as an instruction (D-153).
- **Voice input on the website.** Not built.
- **The history's size.** Thinned (D-151), and the snapshots cut to 14 days (D-154). Litestream's requests to R2 were the cost nobody foresaw ([hosting.md](../spec/hosting.md) §Litestream).

## Done when

- [x] Every phone feature not bound to the device is on the website. *Stages 4 to 7 and 9, in use from the switch.*
- [x] A change made on the website is on the phone at its next sync, and one made on the phone, online, is on the website within a second. *4 Nov 2026: a change on the website on the phone about 3 s later, by stage 8's push; a task added on the phone on the server 160 ms after, and on the open page within a second.*
- [x] The phone, with its network off, shows its plan and tasks, blocks, runs the timer, and takes a task added and a task finished; with the network back, both reach the website and the server's plan replaces the phone's. *4 Nov 2026, in aeroplane mode: ten commands queued and the calendar left as it was; with the network back all of it on the server within the minute, in the order made. Blocking wasn't tried offline.*
- [x] A restore drill has been run. *1 Nov 2026, on a PC from the real bucket: 14 of 14 stores the same, in 26 s. Its other half, a nightly file into a test build's Import, hasn't been run.*
- [ ] The server has run a month with no state lost, Claude's spend as before, and nothing paid for hosting. *Due 4 Dec 2026. Not as worded: R2's requests past its free million were billed in November ([hosting.md](../spec/hosting.md) §Litestream), and mended on 11 Nov.*

## After the plan

None of it decided: a browser extension that blocks sites on the laptop (W-10), a second person's account (W-3), Google Calendar written from the server.
