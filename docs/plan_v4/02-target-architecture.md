# The structure aimed at

The same app, organised so that each concern has one home, owns its data, and can be tested without a phone; and Felix's features built inside that home from their first commit. Most of the code doesn't change; it moves. [03](03-stages.md) says when each part of this arrives.

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
 │ Stores (every file, versions,        │   │ Planner, Slotter, Merge, Sittings,           │
 │   migrations)                        │   │ the instructions' fold, the chat's types and │
 │ ModelGateway (Claude)                │   │ acts, memory, notes, reminders, GmailFinish, │
 │ Notifier (ids, channels, policy)     │   │ the calibration's maths, the domain types    │
 │ WallClock, ports to Android          │   │ (no Android, no services: a module, Stage 6f)│
 └──────────────────────────────────────┘   └─────────────────────────────────────────────┘

 AppGraph: makes the platform, then the services, and wires their flows. Nothing else.
```

## The rules

1. **`core` stays pure**: no Android, no stores, no services. It's that way now; Stage 6f makes it a module of its own so the compiler holds it.
2. **One writer a store.** Each store belongs to one service, which alone calls `update` on it. Others read its `StateFlow`, or call the service. The one exception is `Stores` itself, which replaces whole stores for a restore or a reset (`Stores.replace`, called by the backup service and nothing else); the architecture test names it.
3. **Services are built like `Focus`**: stores, the clock and small interfaces for Android's effects (notifications, the calendar provider, work, alarms, content resolvers) in the constructor. `AppGraph` passes the real ones; tests pass fakes and stores on temporary files.
4. **Screens call services.** A composable may read flows and call a service's function; it never calls `update` on a store. The largest screens (the chat, Settings, the task sheet) get a state holder, a plain class tested without Compose, as each is worked on (the chat's in Stage 5b).
5. **No cycles.** A service may call another's functions (the Conversation acts on instructions, your own tasks and the focus); none calls back into one that called it. Where two need each other, the later one takes a callback, as `Focus` takes `onOwnTaskDone`.
6. **A small architecture test** checks the rules that can be checked by reading the code, gaining a rule as each becomes true: only the notifier posts (Stage 1); only the gateway builds a Claude client (2); `core` imports nothing of Android or the services (6f, by the module); nothing outside a store's owner calls its `update` (complete at 12).

## `AppGraph`, after

What stays: making the stores (through `Stores`), the gateway, the notifier and the services; the flows that join them (a new plan's hooks: slot alarms, the reminders' alarm, the calendar, the widget, writing ahead; the instructions' overrides laid when tasks or instructions change); `get(context)`. Under 400 lines, with no feature logic. Its members are the services (`graph.conversation`, `graph.planning`, `graph.sync`, `graph.instructions`, `graph.ownTasks`, …) instead of its own functions.

## The services

| Service | Owns | Made from (at 3.2.0) | Called by | Stage |
|---|---|---|---|---|
| **Conversation** (Felix, `felix/`) | `chat.json` (the window) and the archive (`chat-archive-<yyyy-MM>.json`, a file a month since 4.0.4); `memory.json`; `felix.json` (notes and reminders); his state (next check, drafts, retry, questions, completions to ask about, the check-in's draft); the chat's retry state (`Gathered`); `chatInFront`; the outbox | the chat functions in `AppGraph`, `learn/Felix.kt`, the check-in flow, `enrich/ChatReader.kt`, `enrich/FelixPrompts.kt`, `enrich/FelixCaller.kt`, `notify/Felix*`; then 5a's outbox, 5b's replies, 8a's notes and reminders, 10a's expiry | screens, workers, the notification's receiver, the slot and reminder alarms | 4, then 5, 8, 10 |
| **Planning** (`plan/`) | the plan watcher, today's slot starts, writing ahead | `AppGraph.plan()`, `workedToday`, `keepSlots`, `PlanWatcher`, `Ahead` | the focus, the widget, screens, the Conversation | 4 |
| **Focus** (exists, `block/`) | the blocker's session state (`session.json` from Stage 7), Anki's watch (`anki.json`) | `block/Focus.kt`, as it is | the focus service, screens, the Conversation's ticks and kept pieces | as now |
| **CalendarWriter** (`calendar/`) | `calendar.json`: what was written where, the calendars to clear, your answers about events | `writeSlots`, `clearOldCalendars`, `watchCalendar`, `calendar/SlotCalendar*`, `CalendarTime.answer` | Planning's hooks, the Conversation's event answers | 6a |
| **Protection** (`protect/`) | `protection.json` (from Stage 7): changes waiting, the override, armed, the code's lock, protection's state | `changeSettings`, `applyReviewChanges`, `applyDueChanges`, `useParentCode`, `cancelChange`, `applyNow`, `protect/*` | screens, the review, the watchdog | 6b |
| **Instructions** (`tasks/`) | `instructions.json`; your values laid on tasks (`mine` from Stage 7); `Instruction.by` | `addInstruction`, `addOwnChange`, `readInstructions`, `applyInstruction`, `deleteInstruction`, the quick changes, `layTaskOverrides` | screens, the Conversation | 6c |
| **OwnTasks** (`tasks/`) | your own tasks | `saveOwnTask`, `finishOwnTask`, `reopenOwnTask`, `deleteOwnTask`, `pruneOwn`, `forgetCards` | screens, the Conversation, the focus | 6c |
| **Readings** (`enrich/`) | the readings on tasks | `enrichNow`, `readAgain`, the rules' and Claude's readers, the dropped-plan alerts | Sync, screens | 6d |
| **Sync** (`sync/`) | `tasks.json`: the tasks as sources give them, the sources' statuses; `gmail-writes.json` and `GmailWriteWorker` (Stage 11) | `Syncer`, `refreshAll`, `requestTeamsSync`, the Teams and calendar observers, `CalendarTime.refresh` and `read` | workers, screens, the Conversation's `sync` action (8b), the finish of an email (11) | 6d, 11 |
| **Photos** (`block/`) | the photos folder | `attachPhoto`, `readPhotoTasks`, `readPhotoOf`, `sweepPhotos`, `block/PhotoChecks.kt` | screens | 6d |
| **Learning** (`learn/`) | `log.json`, `learning.json` (the calibration, from Stage 7) | the review, `Stats`, `Calibrator`, `forgetCalibration`, `undoReviewChange`, `undoTidy`, `writeSummary`, `Assessment.record` | workers, screens, the Conversation's replies | 6e |
| **Backups** (`data/BackupService`) | the backup folder, reset copies | `exportBackup`, `backupNow`, `importBackup`, `restoreInstructions`, `reset`, `restoreResetCopy` | screens, the backup worker | 3 |

Each service is a class in the package that already holds its concern. Felix's is the exception: he has had a package of his own since 3.3.3, `felix/`: the service (`Conversation`), his looks (`Looks`), his rules, prompts and caller, the check-in's flow, the chat's prompts, answers, rounds and reader, his notification and his port to Android (`FelixPorts`), and the receiver and workers that carry a reply or a look ([03](03-stages.md) §Stage 4 lists the files). The outbox and his words join it in 5a, the replies' routing in 5b. What the chat work adds (5), reminders and notes (8a), the gaps (8b), memory's expiry (10a) and the voice (10b) are written inside it.

## The platform

### `Stores` (Stage 3)

Every store is made in one place, `data/Stores.kt`, with its file name, its schema version and its migrations ([04](04-data-and-migrations.md) §Versions and migrations). It reports the files that couldn't be read whole (`Stores.problems`), says of each store what a backup keeps of it and which resets change it (`Stores.all`), and has the one way round a store's owner, `Stores.replace`, which the backup service alone calls; `AppGraph` hands each service its stores from it. The reset copies are the backup service's (`data/BackupService.kt`). So a new store (the archive, `felix.json`, the journal) can't be left out of a backup or a reset. The chat's archive is the one store that isn't a single file: `ChatArchiveStore` (4.0.4), made in `Stores` as the others are, makes its own `JsonStore` a month (`chat-archive-<yyyy-MM>.json`) as each is first wanted, outside `Stores`; a backup holds them as one `chat-archive.json` part.

### `ModelGateway` (Stage 2)

One class for every call to Claude. As built in 3.3.1 (`enrich/ModelGateway.kt`):

```kotlin
class ModelGateway(
    private val settings: JsonStore<Settings>,    // Claude on, its key in use, the monthly cap
    private val savedKey: () -> String?,          // the API key kept in the secrets, or null
    private val runtime: JsonStore<RuntimeState>, // the month's spend, the calls, the failures (model.json from Stage 7)
    private val tasks: JsonStore<TaskState>,      // for the dropped plans' alerts
    private val clock: WallClock,
    private val alerts: Alerts,                   // ModelAlerts on the phone, a list in a test
    private val scope: CoroutineScope,
    private val retryAfter: (Long) -> Unit,       // what only the rules have read, asked again once a rest is over
) {
    var endpoint: String?                         // debug only (ai-endpoint): a stand-in for Anthropic, till a restart
    fun client(job: ModelEffort.Job): AnthropicClient?
    fun claudeKey(): String?
    fun hold(interactive: Boolean = false): ModelHold?
    fun available(): Boolean
    suspend fun <T> call(interactive: Boolean, task: TaskItem? = null, record: ModelCalls.OnRecord<T>? = null, cost: (T) -> Pair<Double, Boolean>, send: suspend (at: Long) -> T): Called<T>
    suspend fun worked(costUsd: Double, refused: Boolean, month: String, at: Long)
    suspend fun failed(error: Throwable, month: String, at: Long, task: TaskItem? = null)
    fun reconcileAlerts()
    suspend fun droppedAlerts(all: List<TaskItem>)
    fun because(hold: ModelHold): String          // since 3.3.3
    fun retryWords(): String                      // since 3.3.3

    companion object {
        fun build(key: String, endpoint: String? = null): AnthropicClient
    }
}
```

- One `AnthropicClient` for each key and endpoint, made once and remade only when the key or the endpoint changes; timeouts by job (`ModelEffort.Job` names them all: the three readings, instructions, the review, photos, the chat and its replies, Felix, micro-plans, photo checks, the summary, the tidy, research; later the digest).
- The readers (`ClaudeEnricher`, `ChatReader`, `FelixCaller`, …) stop building clients: each takes the one `client(job)` gives, in its constructor, and is asked through `call`.
- `callModel`'s holds, the cap, the record of calls, the key's problems and the alerts move here unchanged, with `AppGraph.Called` and `ModelHold`. A reminder's draft (8a) and the digest (13) are calls like any other.

### `Notifier` (Stage 1)

One object posts every notification: `NoticeId`, the registry of ids with their request-code ranges; the channels (`Channels`, which Stage 11 gives `SOURCES`); `NotifyRules`, the policy that holds or sends, by channel for the app's own and, since 5a, by the kind of his message; and his one stacked notification, built by the outbox ([05](05-the-chat.md) §3). Every other file stops calling `NotificationManagerCompat` and `Notify.post` directly.

### Ports

Small interfaces for what Android does, so services can be tested:

| Port | What | Real one | In tests |
|---|---|---|---|
| `Notices` | post, cancel, whether a channel's shown, post what was held | `Notifier` | a list of what was posted |
| `CalendarProvider` | read and write the calendar's events | `SlotCalendar` | events in memory |
| `FelixPorts` | his notification, his look's alarm, the work that outlives what asked, a task opened where it lives | `AndroidFelixPorts` | `FelixHarness`'s |
| `SyncPorts` | a sync as a job, the Teams widget asked and watched, the calendar read, the widget drawn | `AndroidSyncPorts` | `SyncTest`'s |
| `Writer` | the Gmail write (Stage 11) | `ImapWriter` over the socket | a list of the commands sent |

## The data model, after

The details, the types and every migration are in [04](04-data-and-migrations.md); in short:

- **Your values in one place** (Stage 7). `TaskItem.mine: Mine` replaces the twelve `user…` fields; `Mine` is the same type the instructions fold into (`TaskOverrides` till 4.0.13), so the copy across goes. Each value is wrapped with who set it (`SetBy<T>(value, by)`: `By.You` from the instructions screen or a sheet's quick change, `By.Felix` from the chat), which an `Instruction` records from then on; an instruction from before counts as yours. `yourMinutes`, `yourSteps`, `pacedMinutes` and `pacedTeaches` become short functions of `mine` (and `source == Own`), in one file, with one test each.
- **Steps with an id and an origin** (Stage 7). `SubStep.id` (stable: made once, kept through edits) and `SubStep.origin` (`Source`, `Reading`, `Rules`, `Yours`, `Felix`, `OwnTask`). "Are these the reading's steps?" asks the origin, not the titles; a tick follows the id. Slot keys can then use the step's id instead of its place (a later, separate change, as it moves today's kept starts once).
- **The conversation** (Stage 5a): every message with a kind, who wrote it, what it answers; the read position; his state; the window and the archive.
- **Notes and reminders** (8a), **memory with an until** (10a), **the Gmail write journal** (11): each a small versioned store or a field with a default.
- **Source data typed** (optional, later): `TaskItem.extra`'s keys become a small typed value per source (`GmailData`, `TeamsData`, `AnkiData`), read where they're used, with the map kept only for what nothing reads.

## What doesn't change

- The planner's, the slotter's and the calibration's algorithms, and their tests.
- How the blocker decides (`BlockPolicy`, `FocusService`), and the protection's rules (`protect/`): they move into the Protection service as they are, and their tests stay.
- The prompts' wording and the goldens, unless a stage says so (5b, 8, 10 and 11 do, each on purpose).
- The debug commands: they call the services' functions instead of `AppGraph`'s.
- JSON files, written atomically by `JsonStore`.
