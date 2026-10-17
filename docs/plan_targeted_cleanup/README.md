# Targeted cleanup and restructuring: the plan

A plan, not started, for tidying Decrastination's structure after the fast run of releases from 3.0.0 to 3.1.4 (15 to 17 Oct 2026). It changes how the code is organised and how its data is kept, not what the app does, with one exception made on purpose and agreed with you first: how Felix's messages reach you, which the coming chat work needs and this plan lays the ground for (Phase 1). Written on 17 Oct 2026 against `main` at b8469fc (3.1.4).

## Why

The planner, the slotter, the sources and the learning are in good shape: pure where they can be, behind clear interfaces, and covered by 850 unit tests. The mess is where this week's changes met:

- **`AppGraph.kt` does everything else.** 2,601 lines, 116 functions, ten stores, about thirteen concerns, and no tests of its own (it can't be built in a test). 56 other files reach into it, most straight into its stores.
- **Felix has two homes.** What he says unprompted is kept in the activity log and shown only as a notification; it reaches the chat only if you reply to it. On your phone, the last 30 days: 15 such messages, 2 replied to, none in the chat. His notifications share one id, so each new one replaces the last.
- **"Who set this?" is spread over flags.** Twelve `user…` fields on every task, mirrored in the instructions' overrides and copied back by position; whether a step is Claude's, the source's or yours is told from its title.
- **Plumbing is repeated.** Eleven classes build their own Claude client, a new one each time one's asked for; thirteen notification ids live in twelve files.
- **The stores have grown unevenly.** `tasks.json` is 585 KB on your phone, 87 % of it finished tasks, rewritten whole at every change; `runtime.json` (93 KB) mixes what changes every half minute with logs. No store has a schema version.
- **History has crept into the code.** 55 code comments carry review history. The docs did too (`data-sources.md` was 83 KB, with lines up to 2,800 characters); they were restructured on 17 Oct, before this plan starts: `docs/spec/`, `docs/decisions.md` and `docs/development.md`, the old plans, reviews and histories deleted.

[01-current-state.md](01-current-state.md) has the measurements and the evidence.

## Goals

1. **The coming Felix work is cheap and safe**: one conversation, one way out for his messages, notifications that stack ([05](05-felix-and-notifications.md)).
2. **`AppGraph` is only the composition root**: feature services own their stores and expose narrow functions; screens and workers call services, not stores ([02](02-target-architecture.md)).
3. **One model gateway**: one client per key, one place for holds, the monthly cap, the record of calls and failures.
4. **One record of who set what**: your and Felix's values in one value on the task, each with who set it; steps with an id and an origin. Your rule stands throughout: the app's limits and its learned pace apply to its own values, never to yours or Felix's (3.1.2, 3.1.3).
5. **Stores sized to how they're used**, each with a schema version and tested migrations ([04](04-data-and-migrations.md)).
6. **Consistent names and conventions**, and code comments that say what and why, not when ([06](06-conventions-and-docs.md)).
7. **Docs in three kinds**: how it works now, what changed when, and the decisions you made.

## Not in scope

- New features in the cleanup PRs. Each phase keeps behaviour, bar Phase 1's message routing, the coming feature's foundation.
- Rewriting the planner, the slotter, the calibration or the sources: their code moves at most.
- A database (Room). JSON files stay: small once split, written atomically, readable in a backup.
- Merging the Teams widget app into this one (answered separately on 17 Oct: not now).
- Bug hunting. What turns up on the way is noted, and fixed in its own PR, not inside a move.

## Principles

- **Move first, then improve.** A move is its own commit, unchanged but for imports and visibility, so its review is "nothing changed"; improvements follow in their own commits.
- **One concern a PR**, small enough for one Codex round, with the phone check before merging (as for every release).
- **The pattern that already works.** `block/Focus.kt` takes its stores, its clock and small callbacks in its constructor, and `FocusTest` drives it on temporary files. Every service is built that way, so each becomes testable as it moves out of `AppGraph`.
- **Data never lost.** Every migration is rehearsed on a copy of your phone's data in `private/` (never committed) before it's installed; the installed app keeps the file it migrated from.
- **Your values are yours.** Nothing in the restructuring reworks a value you or Felix set.
- **The public repo stays clean**: no task names, events, people or addresses in code, tests or docs; fixtures are made up.

## The phases

| Phase | What | Size | Risk | Behaviour |
|---|---|---|---|---|
| 0 | Safety net: store fixtures and decode tests, a plan snapshot test, a phone check script, baselines | ½ day | low | none |
| 1 | Felix's outbox and one notifier (an id registry; notifications that stack) | 1–1½ days | medium | Felix's messages in the chat (agreed first) |
| 2 | One model gateway | ½–1 day | low | none |
| 3 | `AppGraph` split into services, in six PRs | 2–3 days | medium | none |
| 4 | Who set what: your values as one value; steps with an id and an origin | 1½ days | medium | none (a data migration) |
| 5 | Stores: `runtime.json` split, finished tasks trimmed, schema versions | 1 day | medium | none (data migrations) |
| 6 | Names and conventions, area by area | 1 day, spread | low | none |
| 7 | Docs: spec, change log, decisions | 1 day | none | none |
| 8 | Optional: a pure `:core` module; state holders for the largest screens | 1–2 days | low | none |

[03-phases.md](03-phases.md) gives each phase's steps, files, tests and checks.

## Order, with the coming Felix work

1. **Phase 0**, then **Phase 1**: the outbox and the notifier, with every message of Felix's put in the chat.
2. **The coming chat work** (the chat as Felix's one visible voice, swipe to reply, the collapsed changes and the line that slides out, notifications that don't replace each other) on top of Phase 1, as its own PRs.
3. **Phase 2**, then **Phase 3**: the services split, Felix's service first, as it will have just been worked on.
4. **Phases 4 and 5**, the data model and the stores, in one migration release.
5. **Phases 6 and 7** in small PRs between the others; Phase 8 when there's time.

A new ability of Felix's after Phase 3 goes into his service, not `AppGraph`; one that sets a value on a task goes through the record of who set what (Phase 4).

## What changes substantially

- **Felix's messages live in the chat.** One conversation store; a notification is made from a chat message when the chat isn't on screen, and tapping it opens the chat at that message. His records in the activity log become what each message was for (its reason, its piece, his next check).
- **Notifications are named in one place** and can stack: Felix's conversation as one notification holding his recent messages, as messaging apps do (or one a message, your choice); every other kind keeps its own.
- **`AppGraph` shrinks** to wiring, under 400 lines. Screens and workers call services.
- **Tasks keep your values in one place** (`TaskItem.mine`), each with who set it; steps carry an id and an origin, and ticks follow the id.
- **The stores split**: `runtime.json` into files by how often they change; a finished task's text dropped after a while, its record kept; every store versioned, its migrations tested.
- **The docs are reorganised** (done on 17 Oct, your call): `docs/spec/` for how things work now, `docs/decisions.md` for your calls (numbered, with what's still open), `docs/development.md` for how the work is done. No change log: git's tags and the PRs hold what changed when.

## Decisions for you

[QUESTIONS.md](QUESTIONS.md), ten of them: the order; Codex rounds for the cleanup's PRs; how long a finished task keeps its text; one Felix notification or one a message; whether his past messages go into the chat; which notifications are his voice; the docs' new shape; the account's address in a public doc; the optional Phase 8; and how to release the data changes.

## Done when

- `AppGraph.kt` is under 400 lines and holds no feature logic; each service has tests of its own.
- One class builds Claude clients; one object names every notification id.
- Every message Felix sends is in the chat.
- A new override of yours is a field in one place, not six.
- No store is rewritten for a change that isn't its own: the half-minute state has a file of its own, under 10 KB.
- All tests pass, more of them than now, and every phase was checked on the phone.

## The documents

- [01-current-state.md](01-current-state.md): measurements and findings, with evidence.
- [02-target-architecture.md](02-target-architecture.md): the structure aimed at.
- [03-phases.md](03-phases.md): the phases, PR by PR.
- [04-data-and-migrations.md](04-data-and-migrations.md): the stores and how their data moves.
- [05-felix-and-notifications.md](05-felix-and-notifications.md): the conversation, the outbox and the notifier.
- [06-conventions-and-docs.md](06-conventions-and-docs.md): names, files, comments and docs.
- [07-verification-and-risks.md](07-verification-and-risks.md): tests, phone checks and risks.
- [QUESTIONS.md](QUESTIONS.md): what needs your call.
