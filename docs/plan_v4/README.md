# Decrastination 4.x: the cleanup and Felix as the app's one voice, in one order

One plan for the next run of work, merged on 18 Oct 2026 from two: the targeted cleanup (written 17 Oct: the code and its stores restructured without changing what the app does) and the Felix plan (written 18 Oct: everything he says in one WhatsApp-like chat, notes and reminders, memory that expires, a teenager's voice, a starred email finished in Gmail). Written against `main` at e6cd54b (3.2.0) and measured on 18 Oct ([01](01-current-state.md)); every question either plan asked is answered and applied ([ANSWERS.md](ANSWERS.md) is the record; the "Q3: A" marks through the documents point into it); nothing is implemented. [14](14-your-part.md) says which stages need your phone and which need you.

The order is the merge's one real decision: the cleanup comes first where a feature would otherwise be built on the mess, and between features where it only has to be done at all. [03](03-stages.md) lays out every stage and PR in order, with what each needs from the ones before it and what it leaves for the ones after; nothing in it relies on anything later.

## Why

The planner, the slotter, the sources and the learning are in good shape: pure where they can be, behind clear interfaces, covered by 874 tests. Two things aren't.

**The structure, where this fortnight's changes met** ([01](01-current-state.md) has the evidence):

- `AppGraph.kt` does everything else: 2,628 lines, 117 functions, ten stores, thirteen concerns, no tests of its own; 51 other files reach into it, most straight into its stores.
- Felix has two homes: what he says unprompted is a record in the log and a notification; the chat has only his answers. Seven places produce messages in his name, each building its own notification, and most share one id, so each new one replaces the last.
- "Who set this?" is spread over twelve `user…` fields, mirrored by position; a step's origin is told from its title.
- Eleven classes build their own Claude client; 18 notification ids live in 17 files.
- `tasks.json` is 590 KB, most of it finished tasks, rewritten whole at every tick; `runtime.json` mixes the half-minute state with logs; no store has a version.

**What Felix can be asked, and how he reaches you**:

- His messages are lost: tap a notification and the chat opens without it. His notifications replace each other.
- The chat buries what he did under cards and lines; a message that does three things is a screen long.
- He can't be asked what a friend with his access could do: no reminder at a time, no "ping me when I sit down to the physics", no note on a task. "Remind me to buy mum a present before Sunday" is one of the empty chat's own examples, and today it makes a task.
- He remembers for ever or not at all: "don't message me tonight" is a standing instruction until you delete it.
- He sounds like a planner app: "I'll put this in as a task for: Friday" rather than "alr cool, let's do that then".
- An email finished in the app stays starred in Gmail until you unstar it by hand, which is also when the app counts it finished.

## Goals

1. **Felix's work is built on clean ground**: one notifier, one gateway, stores with versions and migrations, and a service of his own with tests, before his features are written.
2. **`AppGraph` is only the composition root**: services own their stores and expose narrow functions; screens and workers call services, not stores.
3. **One record of who set what**: your and Felix's values in one value on the task, each with who set it; steps with an id and an origin. The app's limits and its learned pace apply to its own values, never to yours or Felix's (D-4).
4. **Stores sized to how they're used**, each with a version, every migration rehearsed on your data first.
5. **The chat is his one visible voice**: every word in his name is a message there; a notification is the chat reaching you; a reply from either is the same turn.
6. **He does what a friend with his access could**: notes, reminders, memory that lasts as long as it matters, a voice that sounds like someone your age, and the one write outside the phone: a finished email unstarred and archived.
7. **Consistent names and conventions, comments that say what and why, docs kept with the code.**

## Not in scope

- New features inside a cleanup PR, and restructuring inside a feature PR: each PR is one or the other, and says which.
- Rewriting the planner, the slotter, the calibration or the sources: their code moves at most.
- A database (Room): JSON files stay, small once split, written atomically, readable in a backup.
- Merging the Teams widget app into this one (17 Oct: not now).
- Bug hunting inside a move: what turns up is noted and fixed in its own PR.
- Photos in the chat, voice notes, a second chat, group messages, read receipts; a tone setting (D-57: his name and line are the knobs).
- Writing to Teams, Power Planner or AnkiDroid: none has a write API the app uses.
- The chat as the app's home screen: asked on 18 Oct, then dropped the same day (Q26); the app opens on Day.
- Summaries standing in for the raw data he reads: evaluated in [13](13-summaries.md); the one worth building, a conversation digest, is a later 4.4.0 (Q28).

## Principles

- **Move first, then improve.** A move is its own commit, unchanged but for imports and visibility, so its review is "nothing changed"; improvements follow in their own commits.
- **One concern a PR**, small enough for its review rounds, with the phone check before merging.
- **The pattern that already works.** `block/Focus.kt` takes its stores, its clock and small callbacks in its constructor, and `FocusTest` drives it on temporary files. Every service is built that way, so each becomes testable as it moves out of `AppGraph`; every feature is written inside its service, so it's tested the same way from its first commit.
- **Data never lost.** Every migration is rehearsed on a copy of your phone's data in `private/` (never committed) before it's installed; the installed app keeps the file it migrated from until a week after its next successful start, and the backup taken before every install keeps it for good.
- **Your values are yours.** Nothing in the restructuring reworks a value you or Felix set; a reminder's time and words are yours as he set them (D-4).
- **The chat is the record.** Every word in his name is a message in `chat.json` first; a notification is made from the chat, never beside it; nothing he says exists only as a notification or only in the log.
- **One entrance for replies.** A reply from a notification and one in the chat are the same turn, routed the same way.
- **He acts at once, with an Undo** (D-60), for the new things too: a reminder set, a note kept, an email finished.
- **Free where it can be.** Notes, the email's finish and a reminder's chips make no model call; a reminder's message is one small call, drafted ahead and sent as you set it when he can't write; no other new paid call.
- **The public repository stays clean** (D-79): examples are made up; the private evaluation sets and your backups stay in `private/`.

## The order

Why it's this order, in short ([03](03-stages.md) §Why this order has it in full): the chat work is the largest feature and lives in the most tangled code, so the notifier, the gateway, the stores with migrations and Felix's own service come before it (Stages 1–4), and the chat is then written inside a tested service rather than moved into one afterwards. The other services and the data model don't touch the chat, so they wait until it's in (6, 7), but they do touch reminders, the gaps and the email's finish, so they come before those. Names are settled (9) before the prompts are rewritten (10); the write outside the phone comes last (11); the sweep leaves the code as [11](11-conventions-and-docs.md) wants it (12).

Every PR is a release ([development.md](../development.md)): cleanup-only releases before the chat are 3.3.x; from the chat on, every release is 4.x in the order it lands, cleanup or feature. The number says when, not what.

| # | Stage | Version | Kind | Needs | Days |
|---|---|---|---|---|---|
| 0 | Safety net: store fixtures, the plan snapshot, the rehearsal, the phone-check script, baselines | none (tests and scripts) | cleanup | — | ½ |
| 1 | One notifier: `NoticeId`, `Notifier`, every caller switched | 3.3.0 | cleanup | 0 | ½ |
| 2 | One model gateway | 3.3.1 | cleanup | 0 | ½–1 |
| 3 | Stores and backups, with versions and migrations | 3.3.2 | cleanup | 0 | 1 |
| 4 | Felix's code in one place: the Conversation and Planning services, moved; his files split | 3.3.3 | cleanup | 1, 2, 3 | 1 |
| 5a | The chat as his one voice: every message in it, the outbox, one notification, the archive | 4.0.0 | feature, data | 0, 1, 3, 4 | 2 |
| 5b | WhatsApp-like: read state, dates, earlier messages, swipe to reply, one entrance for replies | 4.0.1 | feature | 5a | 1½ |
| 5c | The extras folded; the label that slides out | 4.0.2 | feature | 5b | ½ |
| 6a | `CalendarWriter` | 4.0.3 | cleanup | 3 | ½ |
| 6b | `Protection`, and the Settings screen's state holder | 4.0.4 | cleanup | 3 | 1 |
| 6c | `Instructions` and `OwnTasks`, and the task sheet's state holder | 4.0.5 | cleanup | 3 | 1 |
| 6d | `Readings`, `Sync` and `Photos` | 4.0.6 | cleanup | 2, 3 | ½ |
| 6e | `Learning` | 4.0.7 | cleanup | 3, 5a | ½ |
| 6f | The `:core` module, its dependencies on the app turned round first | 4.0.8 | cleanup | 6a–6e | 1 |
| 7 | Who set what (`Mine`, `By`, steps with ids) and the stores split, trimmed: one data release | 4.0.9 | cleanup, data | 0, 3, 6 | 2½ |
| 8a | Notes, standing notes and reminders | 4.1.0 | feature, data | 2, 3, 4, 5, 7 | 3 |
| 8b | The gaps: keep a piece, check a source, open where it lives, free time in his context | 4.1.1 | feature | 5, 6d, 8a | 1 |
| 9 | Names: the readings' prompts renamed, files and tests split | 4.1.2 | cleanup | 6 | ½ |
| 10a | Memory that expires | 4.2.0 | feature, data | 3, 4, 7, 9 | 1 |
| 10b | His voice; just talk; the summary email in the third person | 4.2.1 | feature | 5, 6e, 9 | ½ |
| 11 | Emails done | 4.3.0 | feature, data | 1, 3, 6d, 7 | 1½ |
| 12 | The sweep: imports, comments, the architecture test complete, baselines compared | 4.3.1 | cleanup | all | ½ |

About twenty-three days in 22 PRs (21 releases), evenings and weekends. After it, the conversation digest as 4.4.0 ([13](13-summaries.md)). You've said you won't use the app until the whole of it is in (Q21): each release is still built and checked on the phone, but nothing between releases has to serve a day's use.

## What changes substantially

- **Felix's code lives in `felix/`**, a service built like `Focus`, with tests on temporary stores; `AppGraph` shrinks to wiring, under 400 lines, its members the services.
- **`chat.json` is the conversation**: every message of his with a kind, what it's about, the replies it offered and his next check; your read position; his state. The log stops keeping his records. Every message is kept for you; the older ones in an archive read only on scrolling back; what he's sent of it is the recent window. [05](05-the-chat.md).
- **One notification for him**, in Android's messaging style, holding his unread messages, rebuilt from the chat, cleared when the chat opens, tapped, opening the chat at the first unread. Notifications are named in one place and never replace each other.
- **The chat screen** gains dates, times, a read divider, swipe to reply with a quote, a folded tail under his bubbles with a chevron, and a label that slides out for five seconds saying what a new message did.
- **Two new things he keeps**: notes on tasks (or on every task of a class, subject or source) and reminders, in a store of their own, each set from the chat with an Undo, shown on the task's sheet and in his context; a reminder delivered in his words for the moment, written ahead. [07](07-notes-and-reminders.md).
- **Memory with a shelf life**: a fact or a rule can carry an until, or belong to a task, and goes when that passes. [08](08-memory-that-expires.md).
- **His prompts say how he talks**, with the register pinned by examples; the fixed lines in his name follow; a message that's just talk gets a reply and nothing else; the week's summary email is about you in the third person. [09](09-his-voice.md).
- **Gmail gets one write**: finishing a starred email's work unstars and archives its conversation, journaled and retried, undone within a grace. D-7's "only ever read" is amended. [10](10-emails-done.md).
- **Tasks keep your values in one place** (`TaskItem.mine`), each with who set it; steps carry an id and an origin, and ticks follow the id.
- **The stores split**: `runtime.json` into files by how often they change; a finished task's text dropped a fortnight after it's finished, its record kept; every store versioned, its migrations tested. [04](04-data-and-migrations.md).
- **One class builds Claude clients; one object names every notification; one test holds the architecture's rules.**

## Decisions it adds

Numbered in `docs/decisions.md` by the PRs that apply them (the next free number is D-94):

- The chat is Felix's one visible voice; a notification is made from it and never beside it (5a). A reply from a notification and one in the chat are the same turn, routed by the message it answers (5b).
- The chat keeps every message for you; what he's sent of it is the recent window (5a).
- He keeps notes and reminders at your asking, made at once with an Undo; a note can stand for every task of a class, subject or source; a reminder is delivered in his words for the moment, written ahead, or as you set it when he can't write (8a).
- What he remembers can expire: by a date, or with a task; nothing of it is yours to prune (10a).
- His voice is a sixth-former's, set by his prompts and pinned by examples; his name and line stay the knobs (D-57). A message that's just talk gets a reply and nothing else. The week's summary email is about you in the third person, never in his voice (10b).
- Finishing a starred email's work in the app unstars and archives it in Gmail (amending D-7, D-10 and D-54), behind a switch; a write that can't be made is the app's own notification, not his (11).
- A finished task's text is kept a fortnight, its record for good (7). Every store has a version and a migration (3).
- One `@claude review` on every PR that gets up to three Codex rounds, answered point by point and closed with "Resolved" (already in [development.md](../development.md), C-Q2).

## Done when

- `AppGraph.kt` is under 400 lines and holds no feature logic; each service has tests of its own; one class builds Claude clients; one object names every notification id; the architecture test holds all four rules.
- Every message in his name since 4.0.0 is in the chat, and `felix-say` twice gives one notification with both. A tapped notification opens the chat at that message; a reply from it and a reply swiped in the chat answer the same message the same way.
- His bubble shows a chevron and nothing under it until opened; a new message's label slides out and back; a message that's just talk has neither.
- Scrolling up past the chat's oldest message loads the earlier ones; `chat-context` shows only the window.
- "Remind me at 7 to…" sets a reminder (not a task) that arrives at 7 as his message written for that moment (your words, when he couldn't write); "remind me when I next sit down to…" arrives at that slot's start.
- "Tell me to do her assignments on sheets" lands on every task of that class: on each sheet, under each in his context, in his slot message for each.
- "Don't message me tonight" is gone by morning without your deleting it.
- The private chat and Felix evals pass at the rate they pass today after the voice change.
- Ticking the last piece of a starred email unstars and archives it within a minute online, and after reconnecting offline; Undo within the grace leaves Gmail untouched.
- A new override of yours is a field in one place, not six; no store is rewritten for a change that isn't its own: the half-minute state has a file of its own, under 10 KB.
- All tests pass, more of them than today's 874; every release checked on the phone; the baselines of Stage 0 compared and bettered.

## The documents

- [01-current-state.md](01-current-state.md): measurements and findings, with evidence; the baseline.
- [02-target-architecture.md](02-target-architecture.md): the structure aimed at: the services, the platform, the rules.
- [03-stages.md](03-stages.md): the order: every stage and PR, its needs, steps, tests and phone check.
- [04-data-and-migrations.md](04-data-and-migrations.md): the stores now and after, the types, how each data change is made and rehearsed.
- [05-the-chat.md](05-the-chat.md): the conversation, the outbox, the notifier; the chat as his one visible voice; opening at a message; WhatsApp-like; the folded extras.
- [06-what-felix-can-do.md](06-what-felix-can-do.md): what you can ask him today and after; the gaps closed; the chat as the control surface.
- [07-notes-and-reminders.md](07-notes-and-reminders.md): notes on tasks and reminders at a time or a slot, written ahead.
- [08-memory-that-expires.md](08-memory-that-expires.md): facts and rules that last as long as they matter.
- [09-his-voice.md](09-his-voice.md): the register, the prompts, the fixed lines, just talking, the summary email, the evals.
- [10-emails-done.md](10-emails-done.md): unstar and archive on the last tick.
- [11-conventions-and-docs.md](11-conventions-and-docs.md): names, files, code, comments, tests and docs, from Stage 1 on.
- [12-verification-and-risks.md](12-verification-and-risks.md): how each PR is shown to work; tests, phone checks, reviews, risks, rolling back.
- [13-summaries.md](13-summaries.md): the summaries evaluation; the conversation digest as 4.4.0.
- [14-your-part.md](14-your-part.md): which stages need your phone and which need you, for what and for how long; how to batch them.
- [ANSWERS.md](ANSWERS.md): every call either plan needed, with your answers, and the merge's own calls; closed. Questions raised during implementation go in a separate `QUESTIONS.md`.
