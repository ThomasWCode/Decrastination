# The structure, and the conventions

The app's structure as the 4.x plan left it (4.3.2, D-107), and the conventions every change keeps to. Each concern has one home, owns its data, and is tested without a phone; Felix's features are built inside his. How each area behaves is in [spec/](../spec/); how a change is made, reviewed and shipped is in [development.md](../development.md).

## The shape

```
 screens (ui/, Compose)        Android entry points (workers, receivers, the focus service, the widget)
            │ read flows, call functions             │ call functions
            ▼                                        ▼
 ┌──────────────────────────── feature services ────────────────────────────┐
 │ Conversation (felix/)   Planning   Sync   Readings   Instructions   OwnTasks │
 │ Focus      CalendarWriter    Protection     Learning   Backups    Photos    │
 └──────────────────────────────────────────────────────────────────────────┘
            │ own their stores; call each other's functions, never each other's stores
            ▼
 ┌────────────── platform ──────────────┐   ┌──────────────── core (pure) ────────────────┐
 │ Stores (every file, versions,        │   │ Planner, Slotter, Merge, Sittings, Rebalance │
 │   migrations)                        │   │ the instructions' fold, the chat's types and │
 │ ModelGateway (Claude)                │   │ acts, memory, notes, reminders, GmailFinish, │
 │ Notifier (ids, channels, policy)     │   │ the calibration's maths, the domain types    │
 │ WallClock, ports to Android          │   │ (no Android, no services: the :core module)  │
 └──────────────────────────────────────┘   └─────────────────────────────────────────────┘

 AppGraph: makes the platform, then the services, and wires their flows. Nothing else.
```

Three Gradle modules hold this: `:core` the pure rules and types; `:engine` every line of the app that needs nothing of Android, in the packages it had, what it needs of a platform a port (D-125); `:app` the screens and their state holders, the Android entry points, the device adapters and the ports' Android implementations, with `AppGraph`; no owner, prompt or migration (`ArchitectureTest`). So a package can be split across two modules, its Android-free part in `engine/src/main/kotlin` and the rest in `app/src/main/java`; the classes Android holds by name are in `:app`, under the names they always had.

## The rules

1. **`core` stays pure**: no Android, no stores, no services. It's a module of its own (`:core`, `core/`), so the compiler holds it; a rule or a type that needs neither goes there, tested there. **`:engine` is free of Android** the same way: plain Kotlin, held by its build, which is why code in it can run on the server.
2. **A store is written only by its named writers.** Each store belongs to one service, which calls `update` on it; others read its `StateFlow`, or call the service. Where several services each write their own part of one store (the tasks', the chat's, the log's, the half-minute state's), `ArchitectureTest` names each with what it writes, so no other can start to. `Stores.replace` is the one way round an owner, for a restore or a reset, called by the backup service and nothing else; the test names it. A single writer for every store isn't planned (D-107).
3. **Services are built like `Focus`**: stores, the clock and small interfaces for Android's effects (notifications, the calendar provider, work, alarms, content resolvers) in the constructor. `AppGraph` passes the real ones; tests pass fakes and stores on temporary files.
4. **Screens call services.** A composable may read flows and call a service's function; it never calls `update` on a store. The largest screens (the chat, Settings, the task sheet) have a state holder, a plain class tested without Compose (`ChatScreenState`, `SettingsScreenState`, `TaskSheetState`); the composables only draw.
5. **No cycles.** A service may call another's functions (the Conversation acts on instructions, your own tasks and the focus); none calls back into one that called it. Where two need each other, the later one takes a callback, as `Focus` takes `onOwnTaskDone` and the Conversation takes `requestSync`.
6. **`ArchitectureTest`** holds the rules that can be checked by reading the code: only the notifier posts; only the gateway builds a Claude client; `core` imports nothing of Android or the services (held by the module's build too); nothing but a store's named writers calls its `update`, and no screen calls one.

## `AppGraph`

Makes the stores (through `Stores`), the gateway, the notifier and the services; wires the flows that join them (a new plan's hooks: slot alarms, the reminders' alarm, the calendar, the widget, writing ahead; the instructions' values laid when tasks or instructions change); `get(context)`. Its members are the services (`graph.conversation`, `graph.planning`, `graph.sync`, `graph.instructions`, `graph.ownTasks`, …), not functions of its own. 537 lines at 4.3.2, accepted as wiring whatever its length (D-107).

## The services

| Service | Owns | Called by |
|---|---|---|
| **Conversation** (Felix, `felix/`) | `chat.json` (the window) and the archive (`chat-archive-<yyyy-MM>.json`, a file a month); `memory.json`; `felix.json` (notes and reminders); his state (next check, drafts, retry, questions, completions to ask about, the check-in's draft); the outbox | screens, workers, the notification's receiver, the slot and reminder alarms |
| **Planning** (`plan/`) | the plan watcher, today's slot starts, writing ahead | the focus, the widget, screens, the Conversation |
| **Focus** (`block/`) | the blocker's session state (`session.json`), Anki's watch (`anki.json`) | the focus service, screens, the Conversation's ticks and kept pieces |
| **CalendarWriter** (`calendar/`) | `calendar.json`: what was written where, the calendars to clear, your answers about events | Planning's hooks, the Conversation's event answers |
| **Protection** (`protect/`) | `protection.json`: changes waiting, the override, armed, the code's lock, protection's state | screens, the review, the watchdog |
| **Instructions** (`tasks/`) | `instructions.json`; your values laid on tasks (`TaskItem.mine`); `Instruction.by` | screens, the Conversation |
| **OwnTasks** (`tasks/`) | your own tasks | screens, the Conversation, the focus |
| **Readings** (`enrich/`) | the readings on tasks | Sync, screens |
| **Sync** (`sync/`) | `tasks.json`: the tasks as sources give them, the sources' statuses; `gmail-writes.json` and `GmailWriteWorker` | workers, screens, the Conversation's `sync` action, the finish of an email |
| **Photos** (`block/`) | the photos folder | screens |
| **Learning** (`learn/`) | `log.json`, `learning.json` (the calibration) | workers, screens, the Conversation's replies |
| **Backups** (`data/BackupService`) | the backup folder, reset copies | screens, the backup worker |

Each service is a class in the package that holds its concern; `Slots`, `AnkiWatch`, `Briefing` and `Moves`, smaller, are built the same way. All are in `:engine`. Felix's has a package of its own, `felix/`: the service (`Conversation`), his looks (`Looks`), his rules, how he feels (`Mood`, D-119), prompts and caller, the check-in's flow, the chat's prompts, answers, rounds and reader, the outbox and his words (`FelixWords`), the replies' routing (`Replies`), his notification and his port to Android (`FelixPorts`), and the receiver and workers that carry a reply or a look.

## The platform

### `Stores`

Every store is made in one place, `data/Stores.kt`, with its file name, its schema version and its migrations ([data-and-migrations.md](data-and-migrations.md)). It reports the files that couldn't be read whole (`Stores.problems`), says of each store what a backup keeps of it and which resets change it (`Stores.all`), and has the one way round a store's owner, `Stores.replace`, which the backup service alone calls; `AppGraph` hands each service its stores from it. So a new store can't be left out of a backup or a reset. The chat's archive is the one store that isn't a single file: `ChatArchiveStore`, made in `Stores` as the others are, makes its own `JsonStore` a month as each is first wanted; a backup holds them as one `chat-archive.json` part.

### `ModelGateway`

One class for every call to Claude (`enrich/ModelGateway.kt`): whether Claude can be asked (`hold`, `available`: Claude on, its key, the monthly cap and its rests), `call` with the record of calls and their costs, `worked` and `failed`, the alerts, one `AnthropicClient` for each key and endpoint (made once, remade when either changes), timeouts by job (`ModelEffort.Job` names them all). The readers (`ClaudeEnricher`, `ChatReader`, `FelixCaller`, `InstructionReader`, …) take the client the gateway gives, in their constructor, and are asked through `call`.

### `Notifier`

One object posts every notification: `NoticeId`, the registry of ids with their request-code ranges (no two alike, held by a test); the channels (`Channels`); `NotifyRules`, the policy that holds or sends, by channel for the app's own and by the kind of his message for Felix's; and his one notification, built by the outbox from the chat's unread ([spec/felix.md](../spec/felix.md) §Notifications and replies). Nothing else calls `NotificationManagerCompat`.

### Ports

Small interfaces for what Android does, so services are tested without it:

| Port | What | Real one | In tests |
|---|---|---|---|
| `Notices` | cancel, whether a channel's shown, post what was held (`Posts` adds a notification Android made, on the phone alone) | `Notifier` | a list of what was posted |
| `Log` (`ports/`) | the log, in `android.util.Log`'s calls | `AndroidLog` | nothing |
| `CalendarProvider` | read and write the calendar's events | `SlotCalendar` | events in memory |
| `FelixPorts` | his notification, his look's alarm, the work that outlives what asked, a task opened where it lives | `AndroidFelixPorts` | `FelixHarness`'s |
| `SyncPorts` | a sync as a job, the Teams widget asked and watched, the calendar read, the widget drawn | `AndroidSyncPorts` | `SyncTest`'s |
| `GmailWriter` | the writes a finished email needs | `ImapGmailWriter`, over `ImapWriter`'s commands on the socket | a fake writer, with the commands it was sent |

## The data model

- **Your values in one place.** `TaskItem.mine: Mine` holds every value you or Felix set, each wrapped with who set it (`SetBy<T>(value, by)`: `By.You` from the instructions screen or a sheet's quick change, `By.Felix` from the chat), which an `Instruction` records too. `yourMinutes`, `yourSteps`, `pacedMinutes` and `pacedTeaches` are short functions of `mine`.
- **Steps with an id and an origin.** `SubStep.id` (made once, kept through edits) and `SubStep.origin` (`Source`, `Reading`, `Rules`, `Yours`, `Felix`, `OwnTask`); "are these the reading's steps?" asks the origin, not the titles; a tick follows the id. `OwnStep` carries an id too, so laying an instruction again keeps its steps' ticks. Slot keys are still by a step's place (`taskId#stepIndex`).
- **The conversation**: every message with a kind, who wrote it, what it answers; the read position; his state; the window and the archive. **Notes and reminders**, **memory with an until**, **the Gmail write journal**: each a small versioned store or a field with a default ([data-and-migrations.md](data-and-migrations.md)).

## Conventions

### Names

- **Claude is the model; Felix is who you talk to.** Code about calling the model says Claude (`ModelGateway`, `ClaudeEnricher`); code about the persona and his messages says Felix (`Speaker.Felix`, `felix/Outbox`). The screens say "Claude" where it's the setting or the service (*Claude on*, *Read again with Claude*).
- **A service is named for its concern**: `Conversation`, `Planning`, `Sync`, `Instructions`, `OwnTasks`, `CalendarWriter`, `Protection`, `Readings`, `Learning`. A pure object in `core/` doesn't share a service's name (`InstructionRules`, `OwnTaskRules`).
- **Prompts and their answers by job**: `ReadingPrompts`/`ReadingAnswers`, beside `ChatPrompts`, `InstructionPrompts`, `FelixPrompts`, `SummaryPrompts` and `ResearchPrompts`.
- **Your values are `mine`**, each with `By.You` or `By.Felix`; derived questions read as questions (`yourSteps`, `pacedTeaches`).

### Files

- **One top-level type a file**, named for it, but for a small sealed family or a type's private helpers. **One test class a file**, but for a file of small rule tests grouped by concern (`BlockingTest.kt`, `ProtectTest.kt`, `LearnTest.kt`, `DailyTest.kt`, `WidgetModelTest.kt`).
- **Imports, not inline fully qualified names**, but where a name would be ambiguous (`java.time.Duration` beside `kotlin.time.Duration`).
- **Classes Android holds by their name.** An activity or receiver a notification's tap names (posted, or held to be posted again) and a worker in WorkManager's queue (periodic work's, for good) are named by their class: a move leaves them pointing at nothing, unless the phone holds none under the old name when the build is installed (`private/tools/held_by_name.py` looks first), the old name stays (an `activity-alias`, a forwarding class), or a migration rewrites what names it.
- **A package a concern**: Felix's code in `felix/`; Planning's in `plan/`; the platform's in `data/` (stores), `enrich/` (the gateway) and `notify/`; services in their concern's package; pure rules in `core/`.

### Code

- **Services are built like `Focus`**: stores, the clock and ports in the constructor; no `AppGraph` inside.
- **A store is written by its named writers**; others read its flow or call its owner.
- **Pure where it can be**: rules and calculations in `core/`, tested directly; services hold the effects. A feature's rules (`ChatSummary`, `Replies.route`, `Reminders`, `Memories.expired`, `GmailFinish`, `Rebalance`) land first, with their tests, then the code that uses them.
- **State holders for the largest screens**: plain classes holding a screen's state and actions, tested without Compose; the composables only draw.
- **Stored types**: `@Serializable` with defaults, a version, migrations for any change of shape. A field kept only so old files read is migrated away and deleted, not kept with a "from before" note.
- **Errors**: a failed effect logged (`Log.w`) and the rest carried on; a store's `update` never throws away what it couldn't write.

### Comments

- **What and why, in the present.** A comment says what the code does and why it must; how it came to be is git's and the PRs'.
- **Requirement ids stay** (PL-12, AI-19, BL-08): they tie the code to the spec ([spec/requirement-ids.md](../spec/requirement-ids.md)).
- **Your decisions are referenced, not retold**: "(D-14)", pointing to `docs/decisions.md`, in place of "your call, 17 Oct" and a paraphrase.
- **No review history**: "#48's second review" and "since 3.1.2" go; the PR that made a change is in `git blame`. A "since" that explains a rule still needed (an older file's shape a migration reads) stays.
- **The voice stays**: plain British English, short sentences.

### Tests

- **Fixtures are made up**: no task, event, person or address from your data, ever. A test that needs real data's shape uses the rehearsal (`private/`, never committed).
- **Snapshots and goldens change on purpose only** (`-PupdateSnapshots=true`, `-PupdateGolden=true`), with the diff in the PR.
- **`ArchitectureTest`** guards the rules above that can be checked by reading the code.
- **Every service has its own tests**, built on stores in a temporary folder, as `FocusTest` does; every feature's flows are tested there too (`FelixHarness` for Felix's).
- **Schemas**: `SchemaLimitsTest` as the tripwire, `SchemaCompileTest -PcheckSchemas=true` by the API before any schema change merges.

## How a change is shown to work

A cleanup PR claims to keep behaviour, and shows it four ways: every test passes, none removed but by a stated reason; the plan snapshot is unchanged (`PlanSnapshotTest`: a fixed state planned the same, to the piece and the minute); the goldens are unchanged; the phone check passes after the install (`scripts/phone-check.sh`: the version, the accessibility services enabled and bound, the calendar written to with no stray copies, no crash, what's in front, the stores' sizes), with the PR's own checks after it. A feature PR says what changes, and its tests show it: the pure rules first, the flows on temporary stores, the goldens rewritten on purpose with their diff in the PR, the schemas compiled by the API where one changed, a migration rehearsed on a copy of the phone's data ([data-and-migrations.md](data-and-migrations.md) §On the real data). Reviews and the merge checklist are in [development.md](../development.md) §Changes, pull requests and reviews.

## The docs

| Where | What |
|---|---|
| `README.md` | what the app is, how to build and install it, where everything else is |
| `docs/spec/` | how the app works now, one area a file: `sources.md`, `readings.md`, `planner.md`, `slots-and-calendar.md`, `day.md`, `blocking-and-protection.md`, `felix.md` (with `felix-inputs.md`, everything Felix is sent, written by a test), `learning.md`, `storage-and-backups.md`, `privacy.md`; and `requirement-ids.md`, what each id the code cites asks of the app |
| `docs/decisions.md` | your decisions, numbered (D-1…), dated, each with what it means for the code and where it applies; and what's still open for you |
| `docs/development.md` | how changes are made, reviewed, checked on the phone and shipped; the public repository's rules |
| `docs/plan_v4/` | the 4.x plan's record: this file, the plan's README, the stores and migrations, the questions answered |
| `docs/astra-review-4.3.13/` | the review of 4.3.13 and what became of each finding |

Nothing else: the old plans, reviews, findings and per-release notes were deleted once their decisions and still-true facts had moved, and there's no change log or history folder; git's tags and the PR descriptions hold what changed when.

**Writing the spec:** current behaviour only, no "since" or "was" (a change is a PR, and the spec is edited to match); short paragraphs, one idea each; requirement ids and decision numbers where they apply, so the code and the spec point at each other; kept in step, a PR that changes behaviour changing its spec file in the same PR, `felix-inputs.md` regenerated by its test (`FelixInputsDocTest -PupdateDocs=true`), a decision numbered in `decisions.md` by the PR that applies it.

**Privacy in the docs:** the repository is public (D-79). The old `data-sources.md` once named the Gmail account's address in a heading; it left the docs with that file, and git's history keeps it as it is (C-Q8, D-81). `scripts/scan-public.sh` is the scan done before each push: email addresses, phone numbers, and any word listed in the git-ignored `private/scan-words.txt`.
