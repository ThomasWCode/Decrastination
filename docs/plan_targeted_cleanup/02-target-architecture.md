# The structure aimed at

The same app, organised so that each concern has one home, owns its data, and can be tested without a phone. Most of the code doesn't change; it moves.

## The shape

```
 screens (ui/, Compose)        Android entry points (workers, receivers, the focus service, the widget)
            │ read flows, call functions             │ call functions
            ▼                                        ▼
 ┌──────────────────────────── feature services ────────────────────────────┐
 │ Planning   Sync   Readings   Instructions   OwnTasks   Conversation (Felix) │
 │ Focus      CalendarWriter    Protection     Learning   Backups    Photos    │
 └──────────────────────────────────────────────────────────────────────────┘
            │ own their stores; call each other's functions, never each other's stores
            ▼
 ┌────────────── platform ──────────────┐   ┌──────────────── core (pure) ────────────────┐
 │ Stores (every file, versions,        │   │ Planner, Slotter, Merge, Sittings,           │
 │   migrations)                        │   │ the instructions' fold, the chat's acts,     │
 │ ModelGateway (Claude)                │   │ the calibration's maths, the domain types    │
 │ Notifier (ids, channels, policy)     │   │ (no Android, no services: as now)            │
 │ WallClock, ports to Android          │   └─────────────────────────────────────────────┘
 └──────────────────────────────────────┘

 AppGraph: makes the platform, then the services, and wires their flows. Nothing else.
```

## The rules

1. **`core` stays pure**: no Android, no stores, no services. It's that way now; Phase 8 can make it a module of its own so the compiler holds it.
2. **One writer a store.** Each store belongs to one service, which alone calls `update` on it. Others read its `StateFlow`, or call the service.
3. **Services are built like `Focus`**: stores, the clock and small interfaces for Android's effects (notifications, the calendar provider, work, alarms, content resolvers) in the constructor. `AppGraph` passes the real ones; tests pass fakes and stores on temporary files.
4. **Screens call services.** A composable may read flows and call a service's function; it never calls `update` on a store.
5. **No cycles.** A service may call another's functions (Felix's conversation acts on instructions, your own tasks and the focus); none calls back into one that called it. Where two need each other, the later one takes a callback, as `Focus` takes `onOwnTaskDone`.
6. **A small architecture test** checks the rules that can be checked by reading the code: `core` imports nothing of Android or the services; nothing outside a store's owner calls its `update`; no file but the gateway builds a Claude client; no file but the notifier calls `NotificationManagerCompat`.

## `AppGraph`, after

What stays: making the stores (through `Stores`), the gateway, the notifier and the services; the flows that join them (a new plan's hooks: slot alarms, the calendar, the widget; the instructions' overrides laid when tasks or instructions change); `get(context)`. Under 400 lines, with no feature logic. Its members become the services (`graph.chat`, `graph.instructions`, `graph.ownTasks`…) instead of its own functions.

## The services

| Service | Owns | Made from (now) | Called by |
|---|---|---|---|
| **Planning** | the plan watcher, today's slot starts | `AppGraph.plan()`, `workedToday`, `keepSlots`, `PlanWatcher`, `Slots` | the focus, the widget, screens, Felix |
| **Focus** (exists) | the blocker's session state | `block/Focus.kt`, as it is | the focus service, screens |
| **Sync** | the tasks as sources give them, the sources' statuses | `Syncer`, `refreshAll`, `requestTeamsSync`, the Teams and calendar observers | workers, screens |
| **Readings** | the readings on tasks | `enrichNow`, `readAgain`, the rules' and Claude's readers, the dropped-plan alerts | Sync, screens |
| **Instructions** | `instructions.json`; your values laid on tasks | `addInstruction`, `readInstructions`, `applyInstruction`, `deleteInstruction`, the quick changes, `layTaskOverrides` and the refusals | screens, Felix |
| **OwnTasks** | your own tasks | `saveOwnTask`, `finishOwnTask`, `reopenOwnTask`, `deleteOwnTask`, `pruneOwn`, `forgetCards` | screens, Felix, the focus |
| **Conversation** (Felix) | the chat with all his messages, his state | the chat functions in `AppGraph`, `learn/Felix.kt`, the check-in flow, the briefing's message, `notify/Felix*` | screens, workers, the notification's receiver |
| **CalendarWriter** | `calendar.json`, what was written where | `writeSlots`, `clearOldCalendars`, `watchCalendar`, `calendar/SlotCalendar*` | Planning's hooks |
| **Protection** | changes that loosen blocking, the armed state, the parent code's lock | `changeSettings`, `applyDue`, `useParentCode`, `cancelChange`, `applyNow`, `applyReviewChanges`, `protect/*` | screens, the review, the watchdog |
| **Learning** | the activity log, the calibration | the review, the stats, `forgetCalibration`, `undoReviewChange`, `undoTidy`, `writeSummary` | workers, screens |
| **Backups** | the backup folder, reset copies | `exportBackup`, `backupNow`, `importBackup`, `restoreInstructions`, `reset`, `restoreResetCopy` | screens, the backup worker |
| **Photos** | the photos folder | `attachPhoto`, `readPhotoTasks`, `readPhotoOf`, `sweepPhotos`, `block/PhotoChecks.kt` | screens |

Each service is a class in the package that already holds its concern. Felix's is the one exception: today he's spread over `learn/`, `enrich/`, `notify/` and `AppGraph`, and he gets a package of his own, `felix/`, holding the conversation, the outbox, his looks, the check-in, his prompts and his callers ([05](05-felix-and-notifications.md)).

## The platform

### `Stores`

Every `JsonStore` is made in one place, with its file name, its schema version and its migrations ([04](04-data-and-migrations.md)). It reports the files that couldn't be read (as `AppGraph.found` does now), keeps the reset copies, and hands each service its stores. Backups and resets go through it, so a new store can't be left out of either.

### `ModelGateway`

One class for every call to Claude:

```kotlin
class ModelGateway(
    private val key: () -> String?,          // the key in use, from the secrets
    private val endpoint: String?,           // the debug endpoint, or null
    private val usage: JsonStore<ModelState>,// the month's spend, the calls, the failures
    private val clock: WallClock,
    private val alerts: ModelAlerts,
) {
    fun available(): Boolean
    fun hold(interactive: Boolean): ModelHold?
    suspend fun <T> call(job: ModelEffort.Job, interactive: Boolean, task: TaskItem? = null, send: suspend (AnthropicClient) -> T): Called<T>
}
```

- One `AnthropicClient` for each key and endpoint, made once and remade only when the key changes; timeouts by job (`ModelEffort.Job` already names them all: the three readings, instructions, the review, photos, the chat and its replies, Felix, micro-plans, photo checks, the summary, the tidy, research).
- The readers (`ClaudeEnricher`, `ChatReader`, `FelixCaller`…) stop building clients: each takes the client in `send`, or the gateway in its constructor.
- `callModel`'s holds, the cap, the record of calls, the key's problems and the alerts move here unchanged, with `AppGraph.Called` and `ModelHold`.

### `Notifier`

One object posts every notification: an id registry, the channels, the policy that holds or sends, and stacking. [05](05-felix-and-notifications.md) has its design. Every other file stops calling `NotificationManagerCompat` and `Notify.post` directly.

### Ports

Small interfaces for what Android does, so services can be tested:

| Port | What | Real one | In tests |
|---|---|---|---|
| `Notices` | post, cancel, whether a channel's shown | `Notifier` | a list of what was posted |
| `CalendarProvider` | read and write the calendar's events | `SlotCalendar` | events in memory |
| `Background` | enqueue work, set alarms | WorkManager, `AlarmManager` | a list of what was asked |
| `TeamsProvider` | the Teams widget's provider | `TeamsSource`'s resolver | canned rows |

## The data model, after

The details are in [04](04-data-and-migrations.md); in short:

- **Your values in one place.** `TaskItem.mine: Mine` replaces the twelve `user…` fields; `Mine` is the same type the instructions fold into (today's `TaskOverrides`), so the copy across goes. Each value says who set it (`By.You` from the instructions screen or a sheet's quick change, `By.Felix` from the chat), which an `Instruction` records from now on.

  ```kotlin
  @Serializable
  data class Mine(
      val notATask: Boolean = false,
      val from: Long? = null,
      val after: String? = null,
      val dueAt: Long? = null,
      val minutes: SetMinutes? = null,
      val onDay: String? = null,
      val atTime: Long? = null,
      val sameAs: String? = null,
      val steps: SetSteps? = null,
      val title: String? = null,
  )

  @Serializable data class SetMinutes(val minutes: Int, val by: By, val paced: Paced? = null)
  @Serializable data class Paced(val from: Int, val contentHash: String)
  @Serializable data class SetSteps(val steps: List<OwnStep>, val by: By)
  @Serializable enum class By { You, Felix }
  ```

  `yourMinutes`, `yourSteps`, `pacedMinutes` and `pacedTeaches` become short functions of `mine` (and `source == Own`), in one file, with one test each.

- **Steps with an id and an origin.** `SubStep.id` (stable: made once, kept through edits) and `SubStep.origin` (`Source`, `Reading`, `Rules`, `Yours`, `Felix`, `OwnTask`). "Are these the reading's steps?" asks the origin, not the titles; a tick follows the id, and a reading made again carries ticks by title only for its own new steps, as now. Slot keys can then use the step's id instead of its place (a later, separate change, as it moves today's kept starts once).

- **Source data typed** (optional, later): `TaskItem.extra`'s keys become a small typed value per source (`GmailData`, `TeamsData`, `AnkiData`), read where they're used, with the map kept only for what nothing reads.

## What doesn't change

- The planner's, the slotter's and the calibration's algorithms, and their tests.
- How the blocker decides (`BlockPolicy`, `FocusService`), and the protection's rules (`protect/`): they move into the Protection service as they are, and their tests stay.
- The prompts' wording and the goldens, unless a phase says so (none does).
- The debug commands: they call the services' functions instead of `AppGraph`'s.
- JSON files, written atomically by `JsonStore`.
