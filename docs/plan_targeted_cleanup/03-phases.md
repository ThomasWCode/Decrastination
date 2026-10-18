# The phases

Each phase is one or more PRs, made as every release is: a branch, the tests, CI, the Codex rounds and the Claude review Q2 sets ([07](07-verification-and-risks.md#reviews)), the build on the phone behind a backup, the checks, then the merge. A phase that moves code moves it first, in a commit that changes nothing else, and improves it in the next.

The common checks after every install are in [07](07-verification-and-risks.md#the-phone-check); each phase adds its own.

## Phase 0: the safety net

One PR, tests and scripts only; the app doesn't change.

1. **Store fixtures.** Made-up files for every store in its shape today, every field filled (the old ones too: `ChatMessage.remembered` and `done`, tasks with each `user…` field, a paced total, steps of each kind, finished tasks, an email's extras), under `fixtures/stores/<the day they're made>/`. `StoresDecodeTest` reads each with the app's own serializers and checks what came out. Every later shape gets its own folder, so each migration is tested from every shape it may meet.
2. **A plan snapshot.** A fixed state (tasks from every source, steps of each kind, your instructions, a pin, a school calendar, busy time, a learned pace) planned by `Planner.plan`, its pieces, keys, days and slots written as text and compared with `fixtures/snapshots/plan.txt`. Rewritten only on purpose (`-PupdateSnapshots=true`), as the goldens are. Any move that changes a plan shows here.
3. **A migration rehearsal.** `scripts/rehearse.sh <backup>` unpacks a phone backup from `private/backup/` into the scratch folder and runs a test (`-PrehearseFrom=<dir>`, skipped otherwise) that reads every store with the code as it is, migrates it once migrations exist, and prints only counts and invariants (tasks, open ones, steps ticked, instructions in use, chat messages, pins). Nothing it reads is printed or committed.
4. **A phone check script.** `scripts/phone-check.sh`: the read-only checks run by hand this week (version, the six accessibility services bound, the focus service, the calendar's slots and stray copies, crashes since the install, what's in front, the stores' sizes) as one command.
5. **Baselines** for the "done when" list: test count, build and test time, `AppGraph`'s size, the stores' sizes.

**Done when** the fixtures decode, the snapshot matches, and the rehearsal runs on the latest backup.

## Phase 1: Felix's outbox and the notifier

The foundation of the coming chat work; [05](05-felix-and-notifications.md) has the design.

### 1a. The notifier (no change in behaviour)

1. `notify/NoticeId.kt`: every notification id, with its request codes, in one enum: the eighteen ids as they are now (the summary's 4004 among them), each with a range of request codes that can't meet another's. A test checks that no two ids or ranges overlap.
2. `notify/Notifier.kt`: `Notify`'s posting, holding and channel checks behind one class; the twelve files that post switch to it, keeping their ids. Nothing else calls `NotificationManagerCompat`.
3. The architecture test's first rule: only the notifier posts.

**Phone check:** a briefing and a free-time notice via the debug commands, as before.

### 1b. The outbox (Felix's messages in the chat; agreed with you first)

1. `felix/Outbox.kt`: `send(message)`, which every producer calls instead of writing a `FelixRecord` and posting: his looks and the morning (`learn/Felix.kt`), the morning's list (`Briefing`), the check-in, the review's note, the summary's offer (`writeSummary`), "How did it go?" (`Assessment`) and the calendar's questions (`CalendarTime`), both his by Q6, and an answer to a notification's reply.
2. Felix's messages go into the chat store as messages of his (`kind`, from `FelixPrompts.Reason` and the app's own: SlotStart, SlotEnd, SlotKept, DayDone, Morning, Question, Completions, CheckIn, Review, Summary, EventQuestion, and a plain answer; plan_v4's [01](../plan_v4/01-the-chat.md) §1 has the full set), with what his record held (the reason, the piece or task, his next check, the replies, the completions it asks about). `FelixRules` reads today's messages from the chat instead of the log.
3. A notification is made from the message when the chat isn't on screen, carrying its id; tapped, the chat opens at it. A reply from the notification is your message in the chat, replying to his.
4. His past records: put into the chat once, the last 30 days (Q5: A).

**Tests:** the outbox posts once and puts the message in the chat; nothing's posted with the chat on screen; his rules find today's messages in the chat; a reply from a notification lands under the message it answers.
**Phone check:** a message of his via `felix-say --es reason SlotStart` (a paid call); it's in the chat, and its notification, tapped by you (the agent doesn't touch the screen), opens the chat at it.

plan_v4's 4.0.0 then lands on this in its own PRs: the chat as his one visible voice, swipe to reply, the collapsed changes with the line that slides out, and notifications that stack ([its 07](../plan_v4/07-phases-and-verification.md) says where each touches these phases).

## Phase 2: the model gateway

One PR.

1. `enrich/ModelGateway.kt`: `callModel`, `modelHold`, `modelWorked`, `modelFailed`, `keepCall`, `modelAvailable`, `claudeKey` and the alerts, moved from `AppGraph` unchanged, with `Called` and the record of calls.
2. One `AnthropicClient` a key and endpoint, kept; each reader takes it instead of building its own. The timeouts stay as they are, set by job in one table.
3. `AppGraph`'s ten factories go: a service makes its reader from the gateway.
4. The architecture test's second rule: only the gateway builds a client.

**Tests:** holds (resting, the monthly cap, interactive or not), the record of a call, a failure's class and the key's alert, with a fake `send`; one client made for many calls, a new one after the key changes.
**Phone check:** `ai-check` (one call for every job through the real client, against a local stub, so free), then one real message (`chat-ask`) and one re-read (`enrich-task`); both appear in the record of calls with their costs.

## Phase 3: `AppGraph` split into services

Six PRs for the services, in this order, and a seventh for the `:core` module (Phase 8's, Q11). Each service PR moves a concern's functions into its service verbatim (references to `graph.` becoming constructor parameters), leaves one-line delegates in `AppGraph` so nothing else changes in that commit, then switches the callers, deletes the delegates, adds the service's tests, and turns its fully qualified names into imports.

| PR | Service | Notes |
|---|---|---|
| 3a | `Stores`, `Backups` | First, as every other service takes its stores from `Stores`. Backups and resets enumerate stores through it, so none can be missed. |
| 3b | `CalendarWriter` | Its port (`CalendarProvider`) lets the copy and move rules be tested beyond `SlotCalendarDiff`. |
| 3c | `Protection` | The settings that loosen blocking. Moved as they are; `ProtectTest` stays as it is, and the service's own tests cover armed and unarmed paths. |
| 3d | `Instructions`, `OwnTasks` | Together, as both lay your values on tasks. |
| 3e | `Readings`, `Sync`, `Photos` | |
| 3f | `Conversation` (Felix), `Planning` | After Phase 1 and plan_v4's 4.0.0, so the newest code moves once. |
| 3g | The `:core` module | Last, when nothing else is moving (Q11: A): `core/` and its tests into a plain Kotlin module, the app depending on it; no code changes but the module files and imports. |

**Tests:** each service gains tests of the flows that had none: the chat's answer and its undo, a card applied and taken back, an instruction applied, refused and deleted, a backup restored into an emptier phone, a reset and its copy restored.
**Phone check:** after each, the phone check, and that PR's concern used through the debug commands (a sync, a re-read, an instruction applied and taken back, a backup written).
**Done when** `AppGraph.kt` is under 400 lines and its members are the services.

## Phase 4: who set what

Two PRs and one data migration ([04](04-data-and-migrations.md#tasks-v2-your-values-and-steps-phase-4)).

### 4a. Your values as one value

1. `core/Mine.kt`: `Mine`, `SetMinutes`, `Paced`, `SetSteps`, `By`. The instructions' fold returns `Mine`; `TaskOverrides` goes.
2. `Instruction.by`: `You` for the instructions screen and the sheets' quick changes, `Felix` for what the chat makes.
3. `TaskItem.mine` replaces the twelve `user…` fields; `yourMinutes`, `yourSteps`, `pacedMinutes`, `pacedTeaches`, `effortMin`, `stepScale` and `multiplierFor` read it. Every reader of the old fields (about 30 places) switches.
4. The migration: the old fields are dropped and `mine` is laid again from `instructions.json`, which is where they came from anyway.

### 4b. Steps with an id and an origin

1. `SubStep.id` and `SubStep.origin`, set wherever steps are made: a source's in `Merge`, a reading's in `Answers` and `RuleEnricher`, yours or Felix's where your steps are laid, your own task's in `OwnTasks`.
2. `stepsFromEnrichment()` asks the origins; `carryMarks` carries by id where both lists have ids, by title only for a reading made again.
3. The migration gives every step an id and, once, the origin today's title rule infers (its last use).

**Tests:** every flag of 4a against the cases that made it (the 3.1.2, 3.1.3 and 3.1.4 tests, kept); ticks that follow a step through a re-read that reorders; origins across a sync, a reading and an instruction.
**Phone check:** the rehearsal first, on the latest backup; after the install, the open tasks, their ticks and your values as they were (counts from the rehearsal, compared).

## Phase 5: the stores

Two PRs and their migrations ([04](04-data-and-migrations.md)).

1. **5a.** `runtime.json` split by owner and by how often it changes: the half-minute state (`session.json`), Claude's record (`model.json`), Anki's readings (`anki.json`), the protection's state (`protection.json`), what the calendar was written with (into `calendar.json`), Felix's state (into the conversation). Every store gets a schema version, and `Stores` runs the migrations.
2. **5b.** A finished task's text dropped a fortnight after it's finished (14 days, Q3: B; `Merge.KEEP_FINISHED_MS` is the same fortnight), its record kept: what stats, the reopen list and a source listing it again need.

**Tests:** each migration from every fixture shape; the rehearsal's invariants; a backup made before the split restored after it.
**Phone check:** the stores' sizes before and after; the phone check; a slot kept, a sync, a backup written.

## Phase 6: names and conventions

Small mechanical PRs, one area each, between the others ([06](06-conventions-and-docs.md)):

- `Speaker.Claude` becomes `Speaker.Felix`, read from the stored name "Claude" as before.
- The reading's `Prompts` and `Answers` become `ReadingPrompts` and `ReadingAnswers` (and `GoldenPromptsTest`'s path to `Prompts.kt` with them); files holding several types split into one each; `EnrichTest.kt`'s seven classes into seven files.
- Inline fully qualified names become imports.
- The chat's old fields (`remembered`, `done`) migrated into `memoryEdits` and `acts`, then dropped.
- Comments that only date a change lose the date; a decision they retell becomes its number in `docs/decisions.md` ("(D-14)").

**Done when** no file holds two unrelated types, no inline fully qualified name is left outside where it disambiguates, and the comments say what and why.

## Phase 7: the docs

Mostly done before Phase 0 (17 Oct, your call; [06](06-conventions-and-docs.md#the-docs)): `docs/spec/` (how the app works now, an area a file, requirement ids kept, with a glossary of the ids the code cites), `docs/decisions.md` (your calls, numbered, and what's open), `docs/development.md` (how the work is done), and the README pointing to them; every other doc deleted, its decisions and still-true facts moved first, and the code's comments pointed at the new files. No change log: git's tags and the PR descriptions hold the history.

What's left of it runs through the other phases:

1. Each phase that changes behaviour edits its spec file in the same PR.
2. As Phase 6 touches a comment that retells a decision ("your call, 17 Oct"), it becomes the decision's number ("(D-14)").

## Phase 8 (with Phase 3, Q9: B): a pure module, and state holders

- **A `:core` module** of plain Kotlin holding `core/`, so the compiler keeps it free of Android and its tests run without the Android plugin.
- **State holders** for the largest screens (the chat, Settings, the task sheet): plain classes holding a screen's state and actions, tested without Compose; the composables only draw.

Where inside Phase 3 (Q11, left to the plan on 18 Oct: A): the module as Phase 3's last PR (3g), when nothing else is moving, and each screen's state holder as its service lands (the chat's with plan_v4's 4.0.0-B, Settings' with 3c, the task sheet's with 3d).

## Size and order

| | PRs | Days | Can run alongside |
|---|---|---|---|
| 0 | 1 | ½ | |
| 1 | 2 (+ the chat work's own) | 1–1½ | 6, 7 |
| 2 | 1 | ½–1 | 6, 7 |
| 3 | 7 (3g the module) | 2–3 | 7 |
| 4 | 2 | 1½ | 7 |
| 5 | 2 | 1 | 7 |
| 6 | 4–6, small | 1 | any |
| 7 | done (17 Oct); kept in step by each phase | 0 | any |
| 8 | inside 3: the module as 3g, the state holders in 3c, 3d and plan_v4's 4.0.0-B | 1–2 | with 3 |

About eight to ten days of work in all, spread so that features can still land between phases. Phases 4 and 5 change stored data and are released together (Q10: A), with one rehearsal and one backup.
