# Building the timer

One release, `4.4.0: a timer on a piece`, on branch `v4.4.0` from `main`, `versionCode` 78 and `versionName` "4.4.0" in `app/build.gradle.kts`. Two staged commits: the timer, its notifications and Felix's rules with their tests; then the UI, the widget and the docs. The rules of the house hold throughout: `CLAUDE.md` (the repository's and the owner's), `docs/development.md`, `docs/plan_v4/architecture.md` §The rules and §Conventions. Nothing personal in code, tests, fixtures, docs or the PR (D-79): fixtures and examples are made up.

Before the first edit: `PROGRESS.md` from `private/tools/progress.py init`, one item a stage below with its estimate, kept current and deleted before the PR is called done; never staged. Read, in full: `block/Focus.kt`, `block/SlotAlarms.kt`, `felix/FelixRules.kt`, `felix/Looks.kt` (`check`, `noteActivity`, `draftAhead`), `notify/NoticeId.kt`, `notify/Channels.kt`, `notify/Notify.kt`, `data/SessionState.kt`, `data/Stores.kt`, `data/CreditMigration.kt`, `ui/PieceActions.kt`, `ui/PlanCards.kt`, `ui/TaskSheet.kt` (`Steps`), `widget/NextWidget.kt`, `widget/WidgetModel.kt`, `widget/WidgetUpdater.kt`, `AppGraph.kt` (the planning hooks and the start-up block that runs `finishKeeps`). For the old sessions' notification, which did the same countdown, `git show f1a1c99d^:app/src/main/java/com/thomaswcode/decrastination/block/Sessions.kt`; for a version raise with nothing to change, `git log --oneline -S CreditMigration` and that commit's test and fixture.

## The stages

Estimates are from stages of this size in 4.3.x; the actuals go in `PROGRESS.md` as each stage ends.

### 1. The state and its store (45 min)

- `data/SessionState.kt`: a new type beside `PhotoDone` and `Keeping`:

  ```kotlin
  /** A timer on a piece (docs/plan_timer): the owner's word they're on it, running from [startedAt] for [minutes]; stopped by hand, kept with [stoppedAt] till the next Start, so Felix's slot-over message waits its 30 minutes from the stop. */
  @Serializable
  data class PieceTimer(
      val key: String,
      val taskId: String,
      val stepIndex: Int?,
      /** The step's title as it was, so a step changed under it ends it. */
      val step: String?,
      val label: String,
      val minutes: Int,
      val startedAt: Long,
      /** Its zero has been told (the "time's up" notification posted, the running one retitled). */
      val told: Boolean = false,
      val stoppedAt: Long? = null,
  ) {
      val endsAt: Long get() = startedAt + minutes * 60_000L
      val running: Boolean get() = stoppedAt == null
  }
  ```

  and `val timer: PieceTimer? = null` on `SessionState`, its `version` 2 → 3. A timer ended by its piece being done is cleared (null); one stopped by hand stays with `stoppedAt`.
- `data/TimerMigration.kt`: `val SESSION = Migration(2) { it }`, a raise with nothing to change (`docs/plan_v4/data-and-migrations.md` §Versions and migrations, rule 6), added to `Stores.session`'s list after `CreditMigration.SESSION`. The backup's part of `session.json` is its slots alone (`Kept("session.json", backup = "today's slots")`), so the timer isn't backed up: a running timer is this phone's moment. Only `Reset.Everything` clears `session.json`; stage 3 takes the notifications down with it.
- Tests: `MigrationTest`'s chain test covers the new step; a version-2 file read into version 3 as `StoresDecodeTest` and the 4.3.18 commit did it (its `since` date); `ArchitectureTest`'s writers of `session` unchanged (Focus writes the timer).

### 2. Focus: the timer's owner (60 min)

Focus owns `session.json` (`ArchitectureTest`: `"session" to setOf("block/Focus.kt", …)`), so every change to the timer is Focus's. Pure state: no Android here.

- `val timer: PieceTimer? get() = session.value.timer`.
- `suspend fun startTimer(taskId: String, stepIndex: Int?, key: String? = null, now: Long = clock.now()): PieceTimer?`: the task open, else null; the piece's key `key ?: Chunk.keyOf(taskId, stepIndex, 0)`; its minutes from the plan's piece with that key (`plan(now).ordered.firstOrNull { it.key == key }`), else the plan's piece of that task and step (`plan.chunksOf(taskId).firstOrNull { it.stepIndex == stepIndex }`), else for a step the sheet's minutes (`task.plannedStepMinutes(1.0)[stepIndex]`), else the task's minutes left (`Planner.remaining` at the calibration, rounded, at least 1); its label `Chunk.label` where the plan has the piece, else "title: step" or the title. Written in one `session.update`, replacing whatever was there, running or stopped. Returns the timer.
- `suspend fun stopTimer(now: Long = clock.now()): Boolean`: the running timer gets `stoppedAt = now`; false when none runs. A stop by hand.
- `suspend fun timerOver(now: Long = clock.now()): Boolean`: a running timer whose `endsAt <= now` and not yet `told` gets `told = true`; returns whether it did, so the caller posts the "time's up" notification once.
- `suspend fun settleTimer(now: Long = clock.now()): Boolean`: the running timer's piece done, so the timer is cleared (set to null, no record): its task gone or not open; for a step, the step at `stepIndex` gone, its title changed, or `done`; or a slot of its task kept since it began (`log.value.slots.any { it.taskId == t.taskId && it.outcome.kept && it.at >= t.startedAt }`), which covers Done, the widget's ✓, Felix's tick or keep and a passed photo check. Returns whether it ended one.
- Settle is called at the end of `keepSlot` (after `finishKeep`), `tickBlock`, `photoChecked` and `onCompleted`, so the end is immediate; and from a planning hook in `AppGraph` (`Planning(hooks = …)`, beside `focus.forgetPlan()`), so a task finished, deleted or marked not a task anywhere (`tasks/OwnTasks.kt`, a sync's `Merge`) ends the timer within the plan's remake; and at start, in the block that runs `finishKeeps` and `finishPhotos`.
- `FocusTest` (temporary stores, as the file does throughout): start writes the timer and its minutes come from the plan's piece, the sheet's step, then the task's remainder in that order; start while one runs replaces it; over tells once and not again; stop keeps a record with `stoppedAt`; each way of being done clears it (tick of its step, keepSlot on its key, keepSlot on another piece of the same task, photoChecked, onCompleted, the task closed then settle, the step's title changed then settle); a timer stands when its piece is laid at another time (the plan remade); settle with no timer is a no-op.

### 3. The Android side: notifications, alarms, receiver (45 min)

New file `block/TimerAlarms.kt` (the pattern of `SlotAlarms` and `Slots`), one object and one receiver, and the one place that touches the timer's notifications and alarms:

- `notify/NoticeId.kt`: `Timer(2005, 2005..2006)` (the running one: its tap, its Stop) and `TimerOver(2007, 2007..2007)` (the "time's up" one: its tap), KDoc in the file's style; `NoticeIdTest` holds the ranges apart.
- `notify/Channels.kt`: `TIMER` ("Timer", `IMPORTANCE_LOW`: the running one, silent) and `TIMER_OVER` ("Timer over", `IMPORTANCE_DEFAULT`: sound), created with the others; the line deleting the old `session` channel stays.
- `TimerAlarms.sync(context)`: makes the notifications and alarms match the store, idempotent, so it can run twice: a timer running and not told → the running notification (small icon `ic_focus`, title the label, text "N min. Stop when you're done.", `setWhen(endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)`, ongoing, `Notify.button` Stop to `TimerReceiver` `ACTION_STOP`, `Notify.opening` the task's sheet: `MainActivity` with `EXTRA_TAB` Tasks and `EXTRA_TASK`, as `TaskOpener` opens one of the owner's own) and an alarm at `endsAt` (`ACTION_END`, exact when `canScheduleExactAlarms`, else a minute's window: lift `SlotAlarms.set` to `internal` and share it); running and told → the same notification retitled "Over on: <label>", "Stop when you're done.", the same `when`, so Android's counter runs on as −mm:ss, and an alarm at `endsAt + FelixRules.TIMER_HOLD_MS` (`ACTION_HOLD_END`); stopped → both notifications cancelled, the zero's alarm cancelled, and the hold's alarm at `liftAt` (stage 4) where that's still ahead, so the slot-over message comes on time without the focus service's tick; none → everything cancelled. Posted through `Notifier.post`, never held.
- `TimerAlarms.start(context, taskId, stepIndex, key)`: `focus.startTimer`, then `looks.noteActivity(listOf(taskId))` (Start is something done on the task), `sync`, `planning.watcher.poke()` so the Day and the widget redraw; returns what to tell the owner ("Timer started: 25 min", or "Timer moved from <label>"). `TimerAlarms.stop(context)`: `focus.stopTimer`, `sync`, poke; the hold's alarm, set by `sync`, brings the slot-over message when its 30 minutes are up. `TimerAlarms.over(context)`: `if (focus.timerOver()) post the TimerOver notification` (title "Time's up: <label>", text "N min on it. Tick it when it's done, or stop the timer.", `setAutoCancel`, tap the sheet), then `sync`.
- `TimerReceiver` (manifest, `exported="false"`): `ACTION_END` → `over`; `ACTION_STOP` → `stop`; `ACTION_HOLD_END` → `looks.check()`. `goAsync` and a coroutine as `SlotReceiver` does. Its request codes are its own (a pending intent is told apart by its component).
- `AppGraph`: a collector on `stores.session.state.map { it.timer }.distinctUntilChanged()` calling `sync`, so a timer cleared by settle anywhere takes its notification down, and a start after a restart or a boot shows it again and re-arms its alarm (a zero passed while the app was down: `over` at start, so the "time's up" is told then). `Reset.Everything`'s path calls `sync` after the stores are cleared (none running → cancelled).
- No new test runs Android; `NotifierTest`'s pattern can hold `sync`'s choice of notification per state if it's cheap to add.

### 4. Felix: the hold and the context (45 min)

`felix/FelixRules.kt`, pure, tested in `FelixRulesTest`:

- `const val TIMER_HOLD_MS = 30 * 60_000L`: how long after a timer ends, at its zero or by a Stop, his slot-over message about its task waits.
- `due(...)` takes `timer: PieceTimer?`. Define, for a task: `liftAt = timer?.takeIf { it.taskId == taskId }?.let { minOf(it.endsAt, it.stoppedAt ?: Long.MAX_VALUE) + TIMER_HOLD_MS }`: 30 minutes after the timer ended, at its zero or by a Stop, whichever came first.
  - **Slot-start**: the `SlotStart` trigger is skipped when `timer?.running == true && timer.taskId == chunk.taskId`.
  - **Slot-over**: a record with `liftAt != null && now < liftAt` is held (not fresh, not due); its freshness is `now - maxOf(r.at, liftAt ?: r.at) <= FRESH_MS`, so one held is said once the hold lifts, 30 minutes after the zero or the Stop, or at its own time where that's later. The `said(Reason.SlotEnd, …)` test still keeps it to once. A timer ended by its piece being done is null, so a record is judged as now.
  - Nothing else in `due` changes.
- `Looks.check` passes `focus.timer`. Check the draft-ahead path (`draftAhead`, `FelixRules.laid`): a slot-start written ahead is sent only when `due` returns its trigger, so the hold covers it; if the path sends on its own test, give it the same skip.
- `ChatPrompts.plan` ("Now: 17:00–17:30 …" / "Next to do: …") and `FelixPrompts.describe`'s plan part each add one clause while a timer runs: ", timer on it since 17:02, 8 min left" or ", 12 min over"; on another task's piece, a line of its own "Timer running on <label> since 17:02, 8 min left". Run `FelixInputsDocTest` and bring `docs/spec/felix-inputs.md` to what it writes or checks.
- `FelixRulesTest`: the slot-start nudge held while a timer runs on the slot's task, also one started before the slot; not held for another task; not held once the timer is stopped and no activity is on record; the slot-over message held while running and before `endsAt + TIMER_HOLD_MS`, then due with a record older than `FRESH_MS`; held for 30 minutes after a Stop by hand with the piece undone, then due; a Stop after zero lifting at zero plus 30 minutes, not later; said once; a record newer than the lift judged by its own time; a timer cleared by its piece being done leaves an old record stale.

### 5. The card, the sheet and the block screen (45 min)

- `ui/PieceActions.kt`: `val session by graph.stores.session.state.collectAsStateWithLifecycle()`; `timer = session.timer?.takeIf { it.running }`; `onThis = timer?.key == piece.key`. After **Open**: **Stop** (`FilledTonalButton`) when `onThis`, else **Start**, each through `TimerAlarms`, its answer to `say`. A `TimerLine(piece, now)` composable in the same file: "Timer: 12 min left" or "Timer: 5 min over" from `now` (Day's `now` ticks each minute: `DayScreen`'s `delay(60_000)`), shown above the buttons by the three callers (`PlanCards`' Do now card and piece sheet, `BlockedActivity`).
- `ui/TaskSheet.kt` `Steps`: on each undone step of an open task, a `TextButton` **Start**, or **Stop** with the time when the timer is on that step (`timer.taskId == task.id && timer.stepIndex == index`); keep **Done** as it is, for the tickable ones. For a task with no steps, **Start**/**Stop** in the sheet's actions row beside *Open in …*, for `plan.chunksOf(task.id).firstOrNull()`, absent when the plan has no piece of it.
- Drawables `ic_play.xml` and `ic_stop.xml` in the style of `ic_check.xml`; `ic_focus.xml` is the notification's.
- No new UI tests; the behaviour sits in Focus and `TimerAlarms`.

### 6. The widget (45 min)

- `widget/WidgetModel.kt`: `timing: Boolean` (the timer runs on the Do now piece) and `timerPill: String?` ("⏱ till 17:30" while counting down, "⏱ over" after: text that doesn't go stale between redraws, which come every 15 minutes at most, `WidgetUpdater.nextRedrawAt`); `nextRedrawAt` also takes the timer's `endsAt` so the pill turns at zero.
- `widget/NextWidget.kt` `Next`: in place of the ✓ alone, a `Column` holding ▶ (or ■ when `timing`) above ✓, each 24 dp with a small gap; then the ↻ in its own column to the right, as now. `WidgetLayout` gets `start: Boolean`, true when the height fits both icons (`fits` as the other parts use it); when it doesn't, ✓ alone at its 28 dp as now. The owner wants the column; the height threshold is settled on the phone at 2×1 and 4×2.
- `StartAction : ActionCallback` beside `TickAction`: `TimerAlarms.start` for `KEY`'s piece (`taskId` and `stepIndex` from the plan's piece with that key), or `stop` when the timer is on it; then `WidgetUpdater.update`.
- `WidgetModelTest`: `start` true at a height that fits two icons and false below; the pill's text before and after zero.

### 7. Docs, in the same PR (30 min)

- `docs/decisions.md`: the next D-number, "A timer on a piece (4.4.0, 25 Oct)": Start runs a countdown in a notification and is the owner's word they're on it; Felix's slot-start nudge and slot-over message about its task wait while it runs, the slot-over one until 30 minutes after its zero or a Stop by hand; it blocks nothing, earns nothing, teaches nothing; it ends by Stop or the piece done; ▶ above ✓ on the widget. D-55 gains a clause: sessions as blocking and credit stay gone; the timer isn't one. Applies: `block/Focus.kt`, `block/TimerAlarms.kt`, `FelixRules.due`.
- `docs/spec/requirement-ids.md`: AR-09 reworded to say what stays gone (blocking, credit, the box experiment) and that the timer of 4.4.0 is a countdown and a word, not a session.
- `docs/spec/felix.md` §His messages: the slot-start and slot-over bullets gain the hold; the `ChatPrompts.plan` bullet the timer's clause.
- `docs/spec/felix-inputs.md`: the plan's line, as `FelixInputsDocTest` has it.
- `docs/spec/blocking-and-protection.md`: the piece's actions (**Start**/**Stop** beside **Open**), the widget's column, and a short section "A timer on a piece" saying what it is and isn't, pointing at `felix.md` for the hold.
- `docs/spec/storage-and-backups.md`: `session.json`'s row, version 3, the timer among its fields, not backed up.
- `docs/plan_v4/data-and-migrations.md`: the migrations' list gains 4.4.0 (`TimerMigration`): `session.json` raised for the timer.
- `docs/plan_v4/architecture.md` §`Notifier`, where it lists ids or channels.
- `README.md`: the release's line, as the releases before it.
- This folder: `README.md` gains a line at the top, "Built in 4.4.0 (#N, merged <date>)"; `answers.md` records which assumptions the owner overturned. `CLAUDE.md` §Where things are said may list the folder, if the owner wants it there.

### 8. CI, the PR and the phone (45 min, the phone's waits apart)

- While changing: `./gradlew compileDebugUnitTestKotlin`, then the touched classes (`--tests '*FocusTest*'` …). Before the push: `./gradlew testDebugUnitTest lintDebug assembleDebug`, then `scripts/scan-public.sh` and `git diff origin/main..HEAD` read for anything personal.
- Push the branch; `gh pr create` with the title above and a description saying what and why, that it's a feature, the two commits, the decision it adds and the plan's folder. No review is asked for and nothing is merged unless the owner says so (`CLAUDE.md` §Reviews and merging).
- `./gradlew --stop` when the builds are done.

## The phone

The owner is at the phone for this; send them the list first, every step in order, so their time goes only on what needs them. Scripts first: `private/tools/keep_before.sh` (the APK and data kept), `adb install -r`, `scripts/phone-check.sh`, `dumpsys accessibility` (every service bound), `adb logcat -d | grep -c "FATAL EXCEPTION"` (0). Relaunch with `am start` before any tap; never `am force-stop`; taps only through `private/tools/tap.sh`; read the screen from screenshots, never `uiautomator dump`. On a school day the phone is away 07:40–18:00.

1. **Start from the Day's card**: the running notification with its countdown and Stop; the card shows Stop and "Timer: N min left"; the widget's ▶ is ■ with "⏱ till HH:MM". Expected within a second of the tap.
2. **Stop from the notification**: both gone; the card shows Start; the widget's ■ is ▶ again.
3. **To zero**: a made-up task of the owner's own with one 2-minute step (deleted at the end, with anything else the test made). Start it; at zero the "Time's up" notification sounds, the running one reads "Over on: …" and its counter shows −00:0x climbing; the card "Timer: 1 min over"; the widget "⏱ over" after its redraw.
4. **Done ends it**: tick the step on its sheet; both notifications go; the card and widget show Start.
5. **A restart**: start a timer, `adb shell run-as <pkg> kill -KILL <pid>` (the service restarts by itself; never `am force-stop`), open the app: the notification is back with the right countdown.
6. **The widget's column** at 2×1 and 4×2: ▶ above ✓, ↻ to the right; at 2×1 whether both fit, else ✓ alone. Screenshots, then deleted if they show anything of the owner's.
7. **The hold, with a real slot in the evening**: start the timer at the slot's start; 10 minutes in, no slot-start nudge in the chat (`private/tools/chat_check.py`); let the slot and its grace pass with the piece undone: no slot-over message yet, and the slot missed in `log.json`; 30 minutes after the timer's zero, his slot-over message. On another slot, or another evening: Stop at the slot's end with the piece undone, and see the message 30 minutes after the stop, not at once. Then tidy: the slot's record stands (it was missed), the made-up task deleted.
8. **After**: `enabled_accessibility_services` unchanged, every service bound, no `FATAL EXCEPTION`, no leftover notification, the test task gone, dark mode as found.

## Watch for

- `FelixRules.due` is pure and its callers pass state: the timer comes from `focus.timer`, never read inside the rules.
- The `SlotEnd` branch also covers a long piece ticked without its photo (`r.outcome.kept && !r.earned && needsPhoto(r)`); the same hold formula serves it, and a tick clears the timer, so no special case.
- The chronometer's base is `endsAt` for both the countdown and the run-on: changing it at zero would start the counter from zero without a sign, which the owner didn't choose.
- Posting from a Glance callback and from a receiver: `AppGraph.get(context)` gives the graph; `sync` after each change, so the collector racing the process's end costs nothing.
- Keys: `Chunk.key` is `keyOf(taskId, stepIndex, part, sitting?.after)`; a piece in sittings has no step, so the timer's settle for it rests on a kept slot of its task since it began.
- The nudge's own rule still stands beside the timer: Start writes activity too, so the two agree after a Stop.
- A migration's test needs a version-2 fixture that `StoresDecodeTest` reads; its `since` date is the day the shape changed.
