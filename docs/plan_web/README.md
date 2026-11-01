# The website: Decrastination at decrastination.thomaswhite.me

Planned 31 Oct 2026, from ten questions answered that day ([decisions.md](decisions.md)). Built to 5.9.0: stages 0 to 7 and 9, dormant till the switch, the last release (D-141), with stage 8 waiting on a Firebase project; how each built part works now is in `docs/spec/` ([server.md](../spec/server.md), [website.md](../spec/website.md), [sync.md](../spec/sync.md)). The folder is the plan's record, as `docs/plan_v4/` was the 4.x plan's: this file is the outline, [architecture.md](architecture.md) the shape the code takes, [data-and-sync.md](data-and-sync.md) how the state moves and is kept, [hosting.md](hosting.md) where it runs and what that costs (with the evidence read for it), [stages.md](stages.md) the steps in order with their estimates, [decisions.md](decisions.md) the choices made and the questions they answered, and [inventory.md](inventory.md) what the phone app holds and does today and where each piece goes.

## Why

The app lives on one phone. Its state is that phone's files, its planner and Felix run there, and it can be used only where the phone is. The owner wants most of what the phone does on a website too: the chat with Felix, the day's plan, the work list, the timer, the week's rules, the reviews and the settings, from a laptop, with the phone and the website showing one state.

Some of what the phone does can't leave it. Teams assignments are read by driving the Teams app on the phone (the Graph API needs the school's IT admin; `inventory.md` §Integrations), AnkiDroid is a content provider on the phone, the blocker is an accessibility service, and the calendar is written through `CalendarContract`. So the phone stays in the loop whatever is built: the website is a second full client, not a replacement (W-1).

## What it is, in one paragraph

A **Kotlin/JVM server** (Ktor) in this repository holds the app's state in **SQLite**, replicated off the box by Litestream, and runs the rules the phone runs today from a **shared JVM module** cut out of the app (`:engine`, with `:core` as it is). The **phone** keeps every device duty (reading Teams and AnkiDroid, blocking, the calendar, notifications, protection) and becomes a client with a **local replica** of the state and an **outbox** of its own changes, so it works offline (W-2). The **website** is a React + TypeScript single-page app served by Caddy from the same box as the server, signed in with the owner's GitHub account alone (W-3). **Claude** is called from the server only, through the same one place, cap and record as today. The server also runs the two syncs that don't need the phone (Power Planner, Gmail). It all runs on an **Oracle Always Free Arm instance** at no monthly cost beyond Claude's.

## Goals

1. **One state, two clients.** A change made on either shows on the other; neither holds anything the other can't see, except what is device-bound (`data-and-sync.md` §Which stores move).
2. **The phone keeps working offline** (the owner's word, 31 Oct): reading the plan and the tasks, blocking, the timer, finishing and adding work; only Felix and the syncs need a connection, as they do today.
3. **No data is ever lost**, as the top of `CLAUDE.md` says: the server keeps every command and every store's history; three independent copies of the state exist at all times (`data-and-sync.md` §Backups).
4. **One copy of the rules.** The planner, Felix's rules, the readings' prompts and the stores' migrations run on the server from the same Kotlin the phone builds; the goldens and `ArchitectureTest` keep holding (W-4).
5. **Nothing personal in the public repository** (D-79), the server's code and the web app included: every secret is an environment variable on the box.
6. **No new Claude calls** beyond the phone's today; one key, one cap, one record, now on the server.

## Not in scope

- Blocking on a laptop (W-10: a browser extension would be its own project).
- Other users: one account, the owner's; tables carry an owner id and nothing more is built for it (W-3).
- Moving Teams or AnkiDroid off the phone, or the calendar off `CalendarContract` (a server could write Google Calendar through its API; the phone's sync adapter already does the job).
- A native desktop app, or a PWA that works offline: the laptop is online.
- Redesigning the app's look. The website takes the phone's colours and words; its layout is its own, for a wide screen.

## The stages

In the most technically feasible order (the owner's word on 31 Oct: the website isn't used until every stage is done, so the order serves the build, not early use). Each is a release with a PR, a phone check where the phone changes, and its docs. [stages.md](stages.md) has each in full.

| Stage | What | Phone release |
|---|---|---|
| 0 | `:engine`: the app's Android-free code moved into a JVM module both the app and the server build | 4.12.x, cleanups; 4.13.0 the store split |
| 1 | `:server` skeleton on the box: Ktor, SQLite, sign-in, device tokens, the image, Caddy, Litestream; the phone uploads its stores; the website shows the Day and the Work list read-only | 5.0.0 |
| 2 | The command log: the phone sends commands, the server applies them through the owners, revisions flow back; the outbox and the offline rules | 5.1.0, 5.2.0 |
| 3 | The server's jobs: Power Planner and Gmail syncs, the readings, planning ahead, memory tidy, nightly backups; Claude's key and gateway on the server, in use from the switch | 5.3.0 |
| 4 | The website writes: the Work list, task sheet, own tasks, Done, Same?, instructions, Settings (protection's delays enforced by the server) | 5.4.0 |
| 5 | Felix on the website: the chat as its home, replies, cards, ratings; Conversation, looks and reminders on the server | 5.5.0 |
| 6 | The Day grid with drag, and the timer, on the website | 5.6.0 |
| 7 | This week, Reviews, Stats, Backup and Reset, Setup on the website | 5.7.0, 5.8.0 |
| 8 | Optional: the phone told at once of the server's changes (Firebase Cloud Messaging) | not built: it needs a Firebase project |
| 9 | Optional: Web Push for Felix's messages, slot starts and the timer (W-9) | 5.9.0 |
| Last | The switch: the phone goes over to commands, and what's dormant wakes (D-141). It needs the box | to come |

Estimated at 61 to 83 hours of sessions in all (66 to 90 with the two optional stages), from the durations measured in the 4.x releases (`stages.md` §Estimates), plus the owner's own time at the Oracle console, the DNS, the GitHub App and the phone.

## Done when

- Every phone feature not bound to the device is on the website: chat, Day, Work, Stats, This week, Reviews, Settings, Backup, Reset, Setup.
- A change made on the website is on the phone at its next sync, and one made on the phone (online) is on the website within a second.
- The phone, with its network off, shows its plan and tasks, blocks, runs the timer, and takes a task added and a task finished; with the network back, both reach the website and the server's plan replaces the phone's.
- A restore drill has been run: the box's database rebuilt from R2 on a clean machine and the server started from it, with the owner's state whole.
- The server has run a month with no state lost, Claude's spend as before, and nothing paid for hosting.

## After the plan

What would come next, none of it decided: the browser extension that blocks sites on the laptop (W-10), a second person's account (W-3), Google Calendar written from the server, and the digest of Felix's ratings the owner has so far said no to (`memory`: ratings are acted on by an agent only).
