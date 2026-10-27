# Decrastination 4.x: the cleanup and Felix as the app's one voice

The plan for the run of work from 3.3.0 to 4.3.19 (17 to 24 Oct 2026), kept as its record now that it's done. It merged two plans on 18 Oct: the targeted cleanup (the code and its stores restructured without changing what the app does) and the Felix plan (everything he says in one WhatsApp-like chat, notes and reminders, memory that expires, a teenager's voice, a starred email finished in Gmail), in one order, a stage a PR, every PR a release checked on the phone before it merged. Fifty PRs (49 releases) merged between 18 and 24 Oct, against the plan's estimate of about thirty-one days of evenings and weekends; every question it raised is answered ([answers.md](answers.md)).

This folder holds what of the plan still serves:

- this file: why, what was built, release by release, what's left to check, and what comes after;
- [architecture.md](architecture.md): the structure the code now has and the conventions every change keeps to, as `CLAUDE.md` and [development.md](../development.md) cite them;
- [data-and-migrations.md](data-and-migrations.md): the stores, how a change to a stored shape is made and rehearsed, and how a release is undone;
- [answers.md](answers.md): every question the plan or its building put to you, with your answer and where it's recorded, so a "(Q31: A)" in the specs or the decisions can be followed.

How the app works now is in [spec/](../spec/), your decisions in [decisions.md](../decisions.md), how work is done in [development.md](../development.md). The plan's design documents (the chat, what Felix can do, notes and reminders, memory, his voice, emails done, what he's sent) were deleted once what they described was built and in the spec; git holds them (`git log -- docs/plan_v4`).

## Why

At 3.2.0 the planner, the slotter, the sources and the learning were in good shape: pure where they could be, behind clear interfaces, covered by 874 tests. Two things weren't.

**The structure, where every change met:**

- `AppGraph.kt` did everything else: 2,628 lines, 117 functions, ten stores, thirteen concerns, no tests of its own; 51 other files reached into it, most straight into its stores.
- Felix had two homes: what he said unprompted was a record in the log and a notification; the chat had only his answers. Seven places produced messages in his name, each building its own notification, most sharing one id, so each new one replaced the last.
- "Who set this?" was spread over twelve `user…` fields on a task; a step's origin was told from its title.
- Eleven classes built their own Claude client; 18 notification ids lived in 17 files.
- `tasks.json` was 590 KB, most of it finished tasks, rewritten whole at every tick; `runtime.json` mixed the half-minute state with logs; no store had a version.

**What Felix could be asked, and how he reached you:**

- His messages were lost: tap a notification and the chat opened without it; his notifications replaced each other.
- He couldn't be asked what a friend with his access could do: no reminder at a time, no note on a task; "remind me to buy a present before Sunday" made a task.
- He remembered for ever or not at all: "don't message me tonight" was a standing instruction until you deleted it.
- He sounded like a planner app.
- An email finished in the app stayed starred in Gmail until you unstarred it by hand, which was also when the app counted it finished.

## Goals, and how each came out

1. **Felix's work built on clean ground**: one notifier (3.3.0), one gateway (3.3.1), stores with versions and migrations (3.3.2), his own service with tests (3.3.3), before his features were written.
2. **`AppGraph` only the composition root**: services own their stores and expose narrow functions; screens and workers call services, not stores. 537 lines at 4.3.2, the stores and services made and the flows that join them, accepted as wiring (D-107).
3. **One record of who set what**: your and Felix's values in one value on the task (`TaskItem.mine`), each with who set it; steps with an id and an origin (4.0.13). The app's limits and its learned pace apply to its own values, never to yours or Felix's (D-4).
4. **Stores sized to how they're used**, each with a version, every migration rehearsed on a copy of your data first (4.0.13 split `runtime.json` by owner; a finished task's text goes a fortnight after it's finished, its record kept).
5. **The chat is his one visible voice** (4.0.0 to 4.0.2): every word in his name is a message there; his one notification is made from the chat's unread; a reply from either is the same turn (D-95 to D-99).
6. **He does what a friend with his access could**: notes and reminders (4.1.0), the gaps closed (4.1.1), memory that lasts as long as it matters (4.2.0), a voice that sounds like someone your age (4.2.2), and the one write outside the phone, a finished email unstarred and archived (4.3.0) (D-100 to D-106).
7. **Consistent names and conventions, comments that say what and why, docs kept with the code** (4.0.12, 4.3.2; [architecture.md](architecture.md) §Conventions).

## What the plan held to

- **Move first, then improve.** A move is its own commit, unchanged but for imports and visibility, so its review is "nothing changed"; improvements follow in their own commits. No feature inside a cleanup PR, no restructuring inside a feature PR; a bug found inside a move is fixed in a PR of its own.
- **One concern a PR**, small enough for its review rounds, with the phone check before merging; cleanup-only releases before the chat were 3.3.x, every release from the chat on 4.x in the order it landed, cleanup or feature.
- **The pattern that already worked.** `block/Focus.kt` takes its stores, its clock and small callbacks in its constructor, and `FocusTest` drives it on temporary files. Every service was built that way, so each became testable as it left `AppGraph`, and every feature was written inside its service from its first commit.
- **Data never lost.** Every migration was rehearsed on a copy of your phone's data in `private/` before it was installed; the app keeps the file it migrated from until a week after its next successful start, and the backup taken before every install keeps it for good.
- **Your values are yours** (D-4); **the chat is the record** (D-95); **one entrance for replies** (D-98); **he acts at once, with an Undo** (D-60); **free where it can be** (notes, the email's finish and a reminder's chips make no model call; a reminder's words are one small call, written ahead and asked for again at its moment where they're out of date; what he asks for comes in a round of the chat's own call, two at most); **the public repository stays clean** (D-79).
- **The order**: the chat work was the largest feature and lived in the most tangled code, so the notifier, the gateway, the stores and Felix's service came before it (Stages 1 to 4) and the chat was written inside a tested service; the other services (6) and the data model (7) came after the chat and before reminders, the gaps and the email's finish, which needed them; names were settled (9) as soon as the services were in, so the data release, the features and the prompts' rewrite were written on them; the write outside the phone came last (11); the sweep (12) left the code as the conventions want it.

## Not in scope

Rewriting the planner, the slotter, the calibration or the sources (their code moved at most); a database (JSON files stay, small once split, written atomically, readable in a backup); merging the Teams widget app into this one (D-6); photos in the chat, voice notes, a second chat, read receipts, a tone setting (D-57: his name and line are the knobs); writing to Teams, Power Planner or AnkiDroid (none has a write API the app uses); the chat as the app's home screen (Q26: dropped; the app opens on Day); summaries standing in for the raw data he reads, but for the conversation digest below (Q28); the API's own tool use in place of his `details` requests (a new caller, schema and goldens for little gain over `details`).

## The releases

| # | Stage | Version (PR) | Kind | What |
|---|---|---|---|---|
| 0 | Safety net | none (#57) | cleanup | store fixtures (`fixtures/stores/<date>/`, `StoresDecodeTest`), the plan snapshot (`PlanSnapshotTest`), the rehearsal (`scripts/rehearse.sh`), `scripts/phone-check.sh`, `scripts/scan-public.sh`, the baselines |
| 1 | One notifier | 3.3.0 (#58) | cleanup | `NoticeId`, `Notifier`, every caller switched; `ArchitectureTest`'s first rule |
| 2 | One model gateway | 3.3.1 (#59) | cleanup | `ModelGateway`: every call to Claude, one client a key; the second rule |
| 3 | Stores and backups | 3.3.2 (#60) | cleanup | `Stores`, a version and migrations on every store (D-94), `BackupService` |
| 4 | Felix's code in one place | 3.3.3 (#61) | cleanup | `felix/Conversation` and `plan/Planning`, moved verbatim; his files split; the first tests of flows that had none |
| — | | 3.3.4 (#62) | fix | the secrets' write tried again as the stores' is (a test that failed now and then on Windows) |
| — | | 3.3.5 (#67) | fix | a piece under way stays today's when the plan is made again |
| — | | 3.3.6 (#68) | fix | the chat names the tabs that exist; the briefing's quiet-hours rule written down |
| 5a | The chat as his one voice | 4.0.0 (#69) | feature, data | every message in the chat (`chat.json` v2, the log's records migrated in), the outbox, his one `MessagingStyle` notification, the archive (D-95 to D-97) |
| 5b | WhatsApp-like | 4.0.1 (#70) | feature | read state, dates, earlier messages, swipe to reply, one entrance for replies (`Replies.route`, D-98), `ChatScreenState` |
| 5c | The extras folded | 4.0.2 (#71) | feature | `ChatSummary`: the tail under a chevron, the label that slides out |
| — | | 4.0.3 (#73) | fix | the Day screen's crash on a slot kept twice; a Done tapped again keeps nothing more |
| — | | 4.0.4 (#74) | fix, data | the chat's archive a file a month (`ChatArchiveStore`), each read only when wanted; the live window a fortnight (D-96 amended) |
| 6a | | 4.0.5 (#76) | cleanup | `CalendarWriter` |
| 6b | | 4.0.6 (#77) | cleanup | `Protection`; `SettingsScreenState` |
| 6c | | 4.0.7 (#78) | cleanup | `Instructions` and `OwnTasks` (`tasks/`); `TaskSheetState`; the `instruction-delete` debug command |
| 6d | | 4.0.8 (#79) | cleanup | `Readings`, `Sync` and `Photos` |
| 6e | | 4.0.9 (#80) | cleanup | `Learning`, the log's one writer |
| — | | 4.0.10 (#82) | fix, feature | the chat's box answers his newest nudge; his messages waiting for your answer shown so, with *Mark answered* (D-98 amended, D-99) |
| 6f | The `:core` module | 4.0.11 (#86) | cleanup | `core/` a plain Kotlin module, its dependencies on the app turned round first; the third rule held by the compiler |
| 9 | Names | 4.0.12 (#87) | cleanup | `ReadingPrompts`/`ReadingAnswers`, `InstructionRules`/`OwnTaskRules`, a type a file, a test class a file |
| 7 | Who set what, the stores split | 4.0.13 (#89) | cleanup, data | `TaskItem.mine` with `By`, steps with an id and an origin, `runtime.json` split into `session.json`, `protection.json`, `model.json`, `anki.json`, `learning.json` and `calendar.json`'s new fields, finished tasks trimmed after a fortnight |
| 8a | Notes and reminders | 4.1.0 (#91) | feature, data | `felix.json`; notes on a task or standing (Q31); reminders at a time, a slot's start or before a due date, in his words written ahead (D-100) |
| 8b | The gaps | 4.1.1 (#92) | feature | keep or unkeep a piece, check a source, open a task where it lives, blocking and free time in his context, each class's next lessons (G8) (D-101) |
| — | | 4.1.2 (#93) | fix | from the 20 Oct phone session: a notification reply isn't new in the chat; a slot's words asked for once a moment; a tick's Undo for its own tick |
| — | | 4.1.3 (#97) | fix | from the 20 Oct docs review: a missed slot whose task finished keeps blocking strict; hour-cached writes priced; the briefing by the school calendar (D-74, Q34) |
| 8c | Less sent, more asked for | 4.1.4 (#98) | feature | the week's numbers and the calendar further out asked for in a round; each task's line says what its text holds; the text kept at 600 characters by the eval (D-102) |
| 10a | Memory that expires | 4.2.0 (#99) | feature, data | a fact or rule with an until, a fact with its task; `memory.json` v2 (D-103) |
| — | | 4.2.1 (#101) | fix | from the 21 Oct docs review: the archive months' copies let go of after a week and by a clear (D-94); Teams' automatic syncs by the school calendar (D-105) |
| 10b | His voice | 4.2.2 (#102) | feature | the register pinned by examples; just talk; the summary email in the third person; his say over what your words answered (D-57, D-99) |
| 11 | Emails done | 4.3.0 (#103) | feature, data | a starred email finished here unstarred and archived in Gmail: `gmail-writes.json`, `GmailWriteWorker`, `ImapWriter`, the grace, Undo, the first-time sheet, the switch (D-106; D-7, D-10, D-54 amended) |
| — | | 4.3.1 (#105) | fix | from 4.2.1's reviews and 11's phone check: an archive month read during a clear; Setup's list of unreadable files; Felix with the Gmail switch off offers *not a task* |
| 12 | The sweep | 4.3.2 (#106) | cleanup | imports, comments, the architecture test complete (each store's writers named, no screen among them), the baselines compared (D-107) |
| — | | 4.3.3 (#107) | fix | from Stage 12's read of the spec: a reset keeps every instruction in its copy (D-72); a studied quota with no new cards of its own is done; the block screen's wording |
| — | | 4.3.4 (#108) | fix | a task's steps keep their order within a day (D-108, Q56) |
| — | | 4.3.5 (#109) | feature, data | a step can say it doesn't need the steps before it (`anyOrder`, D-109, Q57); `ReadingPrompts.VERSION` 9 |
| — | | 4.3.6 (#110) | feature | in a school day's 07:00 briefing call Felix may rebalance two adjacent steps of his own so the next fits a free time at school (D-110, Q58) |
| — | | 4.3.7 (#111) | fix | a proposed task opened in its form keeps its steps' own dates (Q59); the form's "Any order" chip (Q60) |
| — | | 4.3.8 (#112) | feature | the rebalance covers the reading's steps too, every step written at its minutes as planned (D-110) |
| — | | 4.3.9 (#113) | fix | from 10b's read of a day of his messages: more like a teenager, a greeting now and then, no how-to in nudges (D-57) |
| — | | 4.3.10 (#114) | fix | a rebalance lets go of only the changed steps' kept starts (D-110) |
| — | | 4.3.11 (#115) | fix | the greeting on the app's word (his first message after three hours with nothing said); he's told what's done of today's tasks (D-57) |
| — | | 4.3.12 (#116) | fix | your reply to his reading question is read shown his question and kept only as an answer; one that isn't is answered in the chat (D-19, Q64) |
| — | | 4.3.13 (#117) | fix | a task's sheet's "Next:" is its first step still to do (Q65) |
| — | | 4.3.14 (#120) | fix | from the review of 4.3.13 ([astra-review-4.3.13](../astra-review-4.3.13/README.md)): a backup too big for Import isn't written nor older ones pruned for it; a restore leaves out your messages still waiting for an answer (D-111) |
| — | | 4.3.15 (#121) | fix | Gmail's Undo puts back everything starred or in the inbox before the finish; one task read once at a time; the calendar's switch can't race a write |
| — | | 4.3.16 (#122) | fix | a reply to his reading question from his notification is read as work and shown its own question; the archive's move keeps an answer; a non-answer that changed something leaves his question waiting (D-19) |
| — | | 4.3.17 (#123) | fix | a reminder's fresh words asked for in its work, not the alarm's receiver; one cancelled meanwhile isn't sent; a slot's words written ahead go only if its slot hasn't moved |
| — | | 4.3.18 (#124) | fix, data | a long break kept after work laid before a kept slot; free time taken back as earned (`log.json` 4, `session.json` 2); the check-in form keeps its draft through a turn of the phone; D-49's words |
| — | | 4.3.19 (#126) | fix | D-19 as answered in Q66; the instruction reader told that a reply that isn't an answer but asks for a change has it made |

Beside them: #56 (this plan), #63 (the Claude reviewer's workflow and the `CLAUDE.md` it reviews by), #65 (the fixtures made up in their shapes, D-79), and the docs-only PRs #64, #66, #72, #75, #81, #83, #84, #85, #88, #90, #94, #95, #96, #100, #104, #118, #119, #125 and #127: the time rules, the plan's rows marked done, the review rules, and the docs read against the code after each run of releases.

## What changed, in sum

- **Felix's code lives in `felix/`**, a service built like `Focus` with tests on temporary stores (`FelixHarness`); `AppGraph` is wiring, its members the services.
- **`chat.json` is the conversation**: every message of his with a kind, what it's about, the replies it offered and his next check; your read position; his state. Every message is kept for you, the older ones in an archive a file a month, shown on scrolling back; what he's sent of it is the recent window (D-96, D-97).
- **One notification for him**, in Android's messaging style, rebuilt from the chat's unread, cleared when the chat opens; tapped, it opens the chat at the first unread. Every notification is named in one place and none replaces another.
- **The chat screen** has dates, times, a read divider, swipe to reply with a quote, a folded tail under his bubbles with a chevron, a label that slides out saying what a new message did, and the box answering his newest message still waiting for your answer.
- **Two new things he keeps**: notes on tasks (or standing, for every task of a class, subject or source) and reminders, in `felix.json`, each set from the chat with an Undo, shown on the task's sheet and in his context; a reminder comes in his words for the moment, written ahead or asked for at its moment where those are out of date.
- **Memory with a shelf life**: a fact or a rule can carry an until, a fact belong to a task, and each goes when that passes.
- **His prompts say how he talks**, the register pinned by examples; the fixed lines in his name follow; a message that's just talk gets a reply and nothing else; the week's summary email is about you in the third person.
- **He's sent less, and asks for the rest**: the week's numbers and the calendar past the fortnight come when he asks, in a round; each task's line says what its text holds; the next lessons by class are in his context.
- **Gmail gets one write**: finishing a starred email's work unstars and archives its conversation, journaled and retried, undone within a grace (D-106).
- **Tasks keep your values in one place** (`TaskItem.mine`), each with who set it; steps carry an id and an origin, and ticks follow the id; a step can be marked as needing nothing before it, and two adjacent steps of his or the reading's can be rebalanced for a free time at school.
- **The stores split**: `runtime.json` into files by how often they change; a finished task's text dropped a fortnight on, its record kept; every store versioned, its migrations tested from every shape ([data-and-migrations.md](data-and-migrations.md)).
- **One class builds Claude clients; one object names every notification; one test holds the architecture's rules** ([architecture.md](architecture.md)).

## Decisions it added

Numbered in [decisions.md](../decisions.md) by the PRs that applied them: D-94 (3.3.2, every store versioned), D-95 to D-97 (4.0.0, the chat his one voice, every message kept, what he's sent of it), D-98 (4.0.1, one entrance for replies; amended by 4.0.10), D-99 (4.0.10, his messages waiting for your answer), D-100 (4.1.0, notes and reminders), D-101 (4.1.1, the gaps), D-102 (4.1.4, what he's sent always and what he asks for), D-103 (4.2.0, memory that expires), D-104 (Q36, a briefing whose time moves to one gone by), D-105 (4.2.1, Teams' syncs by the school calendar), D-106 (4.3.0, a finished email unstarred and archived), D-107 (4.3.2, the structure as Stage 12 left it), D-108 (4.3.4, steps in order within a day), D-109 (4.3.5, a step that needs nothing before it), D-110 (4.3.6, two steps rebalanced), D-111 (4.3.14, a restore leaves out messages waiting for an answer). D-7, D-10, D-11, D-19, D-49, D-54, D-57, D-72, D-74, D-83 and D-90 were amended along the way. The next free number is D-112.

## Done when

- [x] `AppGraph.kt` holds no feature logic; each service has tests of its own; one class builds Claude clients; one object names every notification id; the architecture test holds all four rules. *(Settled by D-107 at 4.3.2: 537 lines, not the plan's 400, the stores and services made and the flows that join them; every service has tests, Planning's through `PlanSnapshotTest`; `ModelGateway` alone builds a client and `NoticeId` names every notification, both held by `ArchitectureTest`, whose fourth rule names each store's writers, with no screen among them.)*
- [x] Every message in his name is in the chat, and `felix-say` twice gives one notification with both; a tapped notification opens the chat at that message; a reply from it and a reply swiped in the chat answer the same message the same way. *(On the phone with you, 18 Oct.)*
- [x] His bubble shows a chevron and nothing under it until opened; a new message's label slides out and back; a message that's just talk has neither. *(18 and 21 Oct.)*
- [ ] Scrolling up past the chat's oldest message loads the earlier ones; `chat-context` shows only the window. *(Built and tested, the archive a month a file since 4.0.4; not yet seen on the phone, whose chat has no month past its window yet: §Still to check.)*
- [x] "Remind me at 7 to…" sets a reminder that arrives at 7 as his message written for that moment; "remind me when I next sit down to…" arrives at that slot's start. *(20 and 21 Oct.)*
- [x] "Tell me to do her assignments on sheets" lands on every task of that class: on each sheet, under each in his context, in his slot message for each. *(Standing notes, Q31.)*
- [x] "Don't message me tonight" is gone by morning without your deleting it. *(Set on 21 Oct at 19:05 till 07:00, gone at 07:00 on 22 Oct; the night's three nudges were stored empty, none sent.)*
- [x] The private chat and Felix evals pass at the rate they passed before the voice change. *(23 of 23, from 0.957 before the voice.)*
- [x] "It's due next lesson" dates a task to that class's next lesson; "how did last week go?" and "what's on in December?" are answered after a round; the chat's eval scores at least what it did before 8c. *(20 and 21 Oct; the text kept at 600 by the eval's rule, D-102.)*
- [x] Ticking the last piece of a starred email unstars and archives it within a minute online, and after reconnecting offline; Undo within the grace leaves Gmail untouched. *(With you and Gmail on 21 Oct; a star's kind stays drawn on Gmail's website, Gmail's limit, D-106.)*
- [x] A new override of yours is a field in one place; no store is rewritten for a change that isn't its own: the half-minute state has a file of its own, under 10 KB. *(`TaskItem.mine`; `session.json` 7,202 bytes on the phone.)*
- [x] All tests pass, more than the 874 the plan began with; every release checked on the phone; the baselines compared. *(1,372 tests at 4.3.19, the app's 1,251 and core's 121, from 874; `AppGraph.kt` 537 lines from 2,628; `runtime.json` 493 bytes from 94,904; the tests take 71 s where they took 13 and a full build 109 s where it took 90, slower as the services' flows run on temporary stores with a stand-in for Claude's API, which you accepted on 23 Oct.)*

## Still to check

These want your taps, or a day of their own, whenever they come up; none needs a session of its own. Everything else every release asked for was checked on the phone as it was installed.

1. With more of his messages unread than a screen holds, open the chat from his button (not his notification): it opens at the *New messages* line, not at its foot.
2. Once the chat has messages past its window (a fortnight old and past the newest 200), scroll back past them: the older rows show under *Earlier messages* with no Undo or buttons of their own, read a month at a time (4.0.4).
3. The week's check-in answered on a Sunday, in the chat or on its form: kept once in the log, and the week's review asked for through `Learning`.
4. A slot missed today, then its task finished: blocking as the day's other slots have it (4.1.3; seen only within the day's blocking hours).
5. His first message after three hours with nothing said opens with a greeting (4.3.11); a quiet morning's first shows it.
6. A reply of yours to his question that isn't its answer (4.3.12): answered in the chat, not kept with its task, his question waiting for its answer again.
7. A reply from his notification to one of his reading questions (4.3.16): read by Claude as work of its own, shown that question; the task changed or his question waiting again.
8. A kept slot undone (4.3.18): the free time it earned given back, as far as it's unspent, and none of today's for one kept another day.
9. A timer stopped by hand at a slot's end with its piece undone (4.4.0, D-112): his slot-over message 30 minutes after the Stop, not at once. (A timer run past its zero was seen holding two misses till its hold lifted, on 26 Oct.)

Also not yet tried on the phone, from before the plan: a photo check of written homework and photographing homework into a task; re-arming with the authenticator kept, and the *Arm now?* list (needs the parent); the free-time countdown; the widget's red ! at its smallest size ([decisions.md](../decisions.md) §Open).

## After the plan

Each of these is decided by real use of the app, which began only once the whole plan was in (Q21), and is built as a release of its own when wanted. Built since the plan: 4.3.20 (#129, the strings that still named focus sessions, and an unused photo-context job), 4.4.0 (#134, a timer on a piece: D-112, its questions in [answers.md](answers.md) §The timer), 4.4.1 (#137, a break outside kept once begun: D-113), 4.5.0 (#138, a time you give kept to the minute: D-114), 4.5.1 (#139, a day for only some tasks: D-115), 4.5.2 (#140, Felix moves, skips and adds breaks outside: D-116), 4.5.3 (#141, your busy times in your calendar: D-117) and 4.6.0 (#142, the Day as a calendar: D-118, [spec/day.md](../spec/day.md)); their questions in [answers.md](answers.md) §The Day as a calendar, §Breaks outside and §Busy times in your calendar.

- **A conversation digest** (Q28; numbered 4.4.0 then, a number the timer has since taken). Felix sees the conversation's last 12 messages and his last two nudges with your replies (D-97); what was agreed, asked or put off before that is gone from his view, and memory holds facts about you, not the thread of the talk. The one summary worth building: `ChatState.digest` (at most 600 characters) and `digestedTo` (the id of the last message it covers) in `chat.json`, in the backup, shown on the debug page; rewritten by a small call through the gateway (`ModelEffort.Job.Digest`, the cheapest model, under the cap and the rests) once a day at the nightly run when ten or more messages are new, or sooner when the window is about to move a message it doesn't cover to the archive ("rewrite the digest in 600 characters: what they've agreed, asked for, put off and said about their week; drop what's done"), about a cent a day; sent before the history in the chat's call ("Earlier in the chat: …") and in his own messages' context ("Lately in the chat: …"), with a line saying the messages after it win. About a day's work in the Conversation service: the pure parts, the call, a golden, the two prompts' lines, the doc page, the tests. Build it once it's clear whether what he forgets is the thread of the talk (the digest's job) or facts about you (memory's). Everything else he reads is condensed already (D-93) or isn't worth a call.
- **The cache mark on the chat's context**, only if the record of calls shows more than about one message in four taking a round (your call, 19 Oct). With it, the newest turn is built so a round only adds: the context and the plan go as a text block before your words, byte for byte the same in the round as in the first call; a round may run past `Pricing.MAX_INPUT_CHARS` by its own blocks alone (at most about 60,000 characters), the cap holding back its worst case uncached (`AiUsage.allows` given each call's own, not the flat `Pricing.WORST_CALL_USD`), so no first call is cut for it. If fewer messages take a round, the mark costs more than it saves and isn't made.
- **The tasks' text asked for instead of sent**, only if a sample of your real messages (kept in `private/`, never committed), run through the chat's eval with the 600 characters sent and with none, shows fewer than about one in four would need a round, at answers as good (D-102).
- **What only this phone knows of the sources' tasks, kept in a backup** (the review of 4.3.13's P01; your call, 23 Oct: planned, built when wanted): their steps' ticks, the minutes worked and found done by a photo, the photo, and your answers to Felix's questions, each by its task's id; restored into a store of its own, each laid on its task once its source lists it again (its steps matched by id, then by title), and let go after a while where it never does. Never the task whole: the next sync would close as done one its source no longer lists, and give free time for it. A data release: a backup format and a store with their migrations, tested from every shape, and a rehearsal.
- **A week of use, then a read** of how the app did. The phone keeps enough to go through a week, as long as no Reset or clear-data wipes it: the chat and its archive, 120 days of the log, your changes, crash records, and 14 nightly backups. The gaps: only the last 100 calls are kept (`CallRecord.MAX`); the Android log rolls over within about a day; Felix's inputs aren't stored; ticks keep no time. Offered, not built: a fortnight of calls kept instead of the last 100.
- **Source data typed** (optional): `TaskItem.extra`'s keys as a small typed value per source (`GmailData`, `TeamsData`, `AnkiData`), read where they're used, with the map kept only for what nothing reads.
