# Phase 3: windows, the school calendar, slots, live re-slotting, pins, the calendar write

Items: DX-09, PL-16, PL-15, PL-12, PL-04, PL-13, NF-07, DX-12, DX-13. Depends on phase 2. The pure parts (`SchoolDays`, `Slotter`, the planner's windows and pins) come first and are fully unit-tested; the Android parts (alarms, the calendar write, the plan watcher) come last.

Settings added in this phase (all `NEVER` in `SettingsChanges.LOOSER`, each with a `LABELS` row and a `SettingsLimits` range; the Settings screen rows can wait for phase 6 except where noted):

| Field | Default | Note |
|---|---|---|
| `studyPeriodName: String` | `"Study Period"` | the timetable entry name, compared trimmed, case-insensitive |
| `breakWindow: Window` | `Window(10*60+45, 11*60+15)` | |
| `lunchWindows: List<Window>` | `listOf(Window(13*60+15, 13*60+40), Window(13*60+55, 14*60+35))` | 13:40–13:55 is eating |
| `minSchoolSlotMin: Int` | `15` | a school interval shorter than this isn't used |
| `boxMin: Int` | `30` (was 45) | the piece length |
| `slotBreakMin: Int` | `5` | |
| `slotLongBreakMin: Int` | `15` | |
| `slotLongBreakAfterMin: Int` | `90` | |
| `outsideBreaks: Int` | `3` | per weekend or no-school day |
| `outsideBreakMin: Int` | `20` | |
| `outsideTimes: List<Int>` | `listOf(9*60+30, 13*60, 16*60)` | minute of day each outside break is laid at or after |
| `calendarWriteId: Long?` | `null` | the calendar the app writes to (NF-07); a row in Settings now |
| `calendarsExcluded: List<Long>` | `emptyList()` | calendars not read (DX-13); a row in Settings now |

---

## DX-09 The timetable: cached, and read for study periods

**Change.** Keep the last good timetable in the task state; expose which classes meet on a date and when the study periods are. The owner has confirmed his timetable holds the free periods as classes named "Study Period".

**Files.** `sources/powerplanner/PowerPlannerApi.kt`, `sources/powerplanner/PowerPlannerItems.kt`, `sources/powerplanner/PowerPlannerSource.kt`, `sources/TaskSource.kt`, `data/TaskState.kt`, `sync/Syncer.kt`.

**Steps.**

1. Mark `Timetable` `@Serializable` (its `PpClass`/`PpSchedule` already are). Add `TaskState.timetable: Timetable? = null`.
2. `SourceRead.timetable: Timetable? = null`. `PowerPlannerSource.read` returns `SourceRead(items, timetable = current.timetable)`. `Syncer.readOne`: `current.copy(…, timetable = read.timetable ?: current.timetable)`.
3. Extract from `PowerPlannerItems.slot` the day/week matching into `fun PowerPlannerItems.schedulesOn(klass: PpClass, day: LocalDate, timetable: Timetable): List<PpSchedule>` (public), and `fun timeOf(text: String): LocalTime` wrapping the existing `parse`. `slot` uses them; behaviour unchanged (`PowerPlannerItemsTest` must still pass).
4. `fun PowerPlannerItems.lessonsOn(timetable: Timetable, day: LocalDate): List<Lesson>` where `data class Lesson(val className: String, val start: LocalTime, val end: LocalTime)`, sorted by start. Used by `SchoolDays` (next item) and, in phase 5, the profile.

**Tests.** `PowerPlannerItemsTest`: `lessonsOn` for a two-week timetable on a week-A and a week-B day; `PowerPlannerSourceTest`: the read carries the timetable. `StoresTest`: `TaskState` with a timetable round-trips.

**Done when.** `tasks.value.timetable` is non-null after a Power Planner sync and survives a failed one.

---

## PL-16 The school calendar: holidays, half-days, INSET days

**Change.** A store of no-school stretches and half-days, told to the chat or typed in Settings, that the windows and the block-from rule read.

**Files.** New `core/SchoolCalendar.kt`; `AppGraph.kt`; `core/Instructions.kt`; `enrich/InstructionReader.kt`; `data/Backup.kt`; `ui/SettingsActivity.kt`.

**Steps.**

1. `core/SchoolCalendar.kt`:
   ```kotlin
   @Serializable data class SchoolCalendar(val noSchool: List<NoSchool> = emptyList(), val halfDays: List<HalfDay> = emptyList()) {
       fun isSchoolDay(date: LocalDate): Boolean  // Monday–Friday and not inside any noSchool stretch
       fun halfDay(date: LocalDate): HalfDay?
       fun noSchoolOn(date: LocalDate): NoSchool?
   }
   @Serializable data class NoSchool(val from: String, val to: String, val label: String = "")   // ISO dates, inclusive
   @Serializable data class HalfDay(val date: String, val endsAtMin: Int)
   ```
   Entries are kept sorted by date, deduplicated by overlap when added (`SchoolCalendar.with(entry)` merges overlapping stretches).
2. `AppGraph.school = JsonStore(File(app.filesDir, "school.json"), SchoolCalendar.serializer(), ::SchoolCalendar)`.
3. `ChangeType.NoSchool` and `ChangeType.HalfDay`. `Change.endDate: String? = null`. In `InstructionPrompts.CHANGE_TYPES` add:
   ```
   - NoSchool: days with no school (a holiday, half term, an INSET day). Needs date and endDate (the same date for one day).
   - HalfDay: a school day that ends early. Needs date and endTime (HH:MM).
   ```
   `InstructionPrompts.CHANGE` schema: add `"endDate" to nullable(DATE)`. `InstructionAnswers.parse`: `NoSchool` needs `date` (and `endDate ≥ date`, defaulting to `date`); `HalfDay` needs `date` and `endTime` → `endMin`. Both within the usual plausible range.
4. Applying: in `AppGraph.setApplied`, before `makeOnOwnTasks`, `makeOnSchool(id)`: for each NoSchool/HalfDay change, `school.update { it.with(...) }`, then remove those changes from the instruction as `makeOnOwnTasks` does (an instruction left with no changes is deleted). Undo is deleting the entry in Settings.
5. `Instructions.describe`: "No school from Mon 26 Oct to Fri 30 Oct", "Half-day on Fri 17 Oct, ends 12:30".
6. `Backup`: `school: SchoolCalendar = SchoolCalendar()`, `FORMAT = 3`; `Backups.problem` refuses older formats with a message (importing an old backup isn't needed); export/import carry it.
7. Settings → a "School" section: the list of entries with a delete icon each; "Add days off" (two date pickers + label) and "Add a half-day" (date picker + time picker) using `Pickers.DateTimeDialog`.

**Tests.** `InstructionsTest`: parse and describe both types; `with` merges overlaps. `BackupTest`: format 3 round-trips; format 2 imports with an empty school calendar.

**Done when.** "Half term is 26 to 30 October" in the chat makes a NoSchool entry after the tap (phase 5 makes it a button on a reply), and Settings → School lists it.

---

## PL-15 Windows: study periods, break and lunch, the evening

**Change.** Each day's planning time is a list of windows, each with a kind, instead of one evening window; the planner's capacity sums them and `where` keeps home-only work out of school.

**Files.** New `core/SchoolDays.kt`; `core/Planner.kt`; `core/Plan.kt`; `core/Task.kt`; `core/OwnTasks.kt`; `enrich/Prompts.kt`; `AppGraph.kt`.

**Steps.**

1. `core/SchoolDays.kt`:
   ```kotlin
   enum class WindowKind { Evening, Study, Break, Lunch }
   data class DayWindow(val startMin: Int, val endMin: Int, val kind: WindowKind) { val minutes get() = endMin - startMin }
   enum class Where { Anywhere, Home }
   object SchoolDays {
       fun kindOf(date: LocalDate, school: SchoolCalendar): DayKind   // Weekend for Sat/Sun and no-school days, else Weekday
       fun windows(date: LocalDate, settings: Settings, timetable: Timetable?, school: SchoolCalendar): List<DayWindow>
   }
   ```
   `windows`: if `kindOf == Weekend` → `[DayWindow(weekendHours, Evening)]`. Else: study periods from `PowerPlannerItems.lessonsOn(timetable, date)` whose `className` equals `settings.studyPeriodName` (trimmed, ignore case) → `Study`; `settings.breakWindow` → `Break`; each of `settings.lunchWindows` → `Lunch`; `weekdayHours` → `Evening`. On a half-day: drop or cut every school window at `endsAtMin`, and the evening window is `Window(endsAtMin, weekdayHours.endMin)`. Sorted by start; overlapping windows merged (a study period inside lunch takes the earlier kind). A window ends before it starts → dropped.
2. `Planner.Input` gains `windows: Map<LocalDate, List<DayWindow>> = emptyMap()`, `dayKinds: Map<LocalDate, DayKind> = emptyMap()`. When `windows[day]` is absent, `capacity` behaves as today (so every existing test stands).
3. `Planner.capacity(day, input)`: with windows: for each window, clip `[start, end)` to `now` for today, subtract `busy + hardBusy` (merged, as now), drop a school-kind window whose remainder < `settings.minSchoolSlotMin`, sum; multiply the evening part by `yieldFor(dayKinds[day] ?: weekday/weekend)` and the school part by `yieldFor(DayKind.School)`; subtract `dayLoads`; apply the day cap. Return both pools: change the signature to `capacityOf(day, input): Capacity(homeMin, anyMin)` where `homeMin` is the evening part and `anyMin` the total; keep `capacity(day, input) = capacityOf(...).anyMin` for callers.
4. Two free pools in `plan`: `freeHome` and `freeAny` per day. `room(item, day)` = `freeHome` for a home item, `freeAny` otherwise. `take(item, day, minutes)`: a home item subtracts from both; an anywhere item subtracts from `freeAny` and from `freeHome` only the part beyond the day's school free time (`max(0, minutes - (freeAny - freeHome))`). `give` reverses.
5. `where`: `SubStep.where: String? = null` ("anywhere" | "home"); `OwnTask.where: String = "anywhere"` (form field in phase 6; `OwnTasks.create/edited` copy it onto the task's `extra["where"]`); `Chunk.where: Where`. In `pieces()`: a step's `where`, else the task's: `Source.Own` → `extra["where"]`; `Source.Anki` → `Anywhere`; `Source.Teams && kind == Homework` with no step `where` → `Home`; else `Anywhere`. An `Item` is home when all its pieces are home; a mixed task is split into two items (home pieces, anywhere pieces) by `windows()`-style grouping so each takes from the right pool. *(As built: a task stays one item, each piece taking from its own pool (`room`, `take` and `give` by piece): split in two, a task's order, its week, revision's day apart and a per-day limit, all kept within an item, didn't hold across the two, as the property test found.)*
6. Assignment job: `Prompts.block(vocabulary = true)` gains `"where" to mapOf("type" to "string", "enum" to listOf("anywhere", "home"))`; prompt sentence: "where: \"home\" for a step that needs the laptop, books or quiet (an essay, an upload, a long problem set); \"anywhere\" for one that can be done at school in a free period with what's in the bag (a booklet, reading, vocabulary)." `Answers.parse` copies it to `SubStep.where`.
7. Same-day deadline (Q-52): `lastUsableDay`: with margin 0, the deadline's day is usable when `windows[day]` has a window with `startMin + MIN_CHUNK <= deadlineMinuteOfDay`; the slotter (PL-12) then respects the deadline time. Without windows (tests), the old rule.
8. `AppGraph.plan`: build `windows` and `dayKinds` for `today..reach` with `SchoolDays.windows(date, settings, tasks.value.timetable, school.value)`; pass them.

**Tests.** New `SchoolDaysTest`: windows on a school day with one study period; a half-day; a no-school weekday; overlap merge; the lunch split. `PlannerTest`: capacity sums windows; a home piece never takes school time (a day with only school free time places none of it); an anywhere task fills school time first; a same-day deadline at 13:00 is usable at break. `PlannerPropertyTest`: generate windows for some seeds; add the invariant **home pieces only in evening capacity** (sum of home chunks on a day ≤ that day's evening capacity).

**Done when.** On a Tuesday with a study period the plan's capacity for the day is evening + study + break + lunch, visible in the day header's load line.

---

## PL-12 Slots: every piece gets a time; outside breaks at weekends

**Change.** A pure slotting pass lays each day's pieces into its windows with breaks, places three outside breaks on weekend and no-school days, and respects pins, deadlines and `where`.

**Files.** New `core/Slotter.kt`; `core/Plan.kt`; `core/Planner.kt`.

**Steps.**

1. Types in `core/Plan.kt`:
   ```kotlin
   enum class SlotKind { Work, Outside }
   data class Slot(val kind: SlotKind, val chunk: Chunk?, val start: Long, val end: Long, val window: WindowKind, val pinned: Boolean) {
       val key: String get() = chunk?.key ?: "outside:$start"
   }
   val Chunk.key: String get() = "$taskId#${stepIndex ?: -1}#$part"
   // DayBucket gains:
   val slots: List<Slot> = emptyList(), val unslotted: List<Chunk> = emptyList()
   ```
   Replace `TodayScreen.keyOf` with `Chunk.key`.
2. `core/Slotter.kt`:
   ```kotlin
   object Slotter {
       data class Interval(val start: Long, val end: Long, val window: WindowKind)
       fun slot(
           date: LocalDate, chunks: List<Chunk>, windows: List<DayWindow>, busy: List<Busy>,
           pins: Map<String, Long>, previous: Map<String, Long>, now: Long, zone: ZoneId,
           settings: Settings, outside: Boolean, deadlineMinOf: (Chunk) -> Int?,
       ): Pair<List<Slot>, List<Chunk>>
       fun firstFreeAfter(slots: List<Slot>, windows: List<DayWindow>, busy: List<Busy>, from: Long, minutes: Int, where: Where, date: LocalDate, zone: ZoneId): Long?
   }
   ```
   Algorithm, in this order:
   1. **Intervals.** Each window as `[start, end)` epoch ms on `date` in `zone`; subtract `busy` (merged); for today, start no earlier than `now` rounded up to the next 5 minutes; drop school intervals under `minSchoolSlotMin` and evening intervals under 5 minutes. Keep a mutable list sorted by start.
   2. **Outside** (`outside == true`): for each of `settings.outsideTimes`, the first interval at or after that time with ≥ `outsideBreakMin` free; a candidate list of up to `outsideBreaks`. Decide how many to keep: `work = chunks.sumOf { minutes } + settings.slotBreakMin * chunks.size`; `free = intervals.sumOf { minutes }`; while `kept > 0 && free - kept * outsideBreakMin < work` drop one, in the order midday, afternoon, morning (by `outsideTimes` index 1, 2, 0). Carve the kept ones out of the intervals as `Slot(Outside, null, …)`. *(As built: each is over by the next one's time, the last by the day's end, so on a day under way one whose time has gone isn't laid after now; the number kept is also at most `outsideBreaks`, given up in the same order.)*
   3. **Pinned.** For each chunk with `pins[chunk.key]`: place at that time if it fits an interval (carve); else at the first interval start after it that fits; `pinned = true`. *(As built: every pin that fits at its time first, then the others, so a pin moved never takes the time of one that fits; a pin under way is kept; a pin for another day is left alone.)*
   4. **Stable.** For each remaining chunk with `previous[chunk.key]` = a start ≥ now: if an interval still contains `[start, start + minutes)` whole and the window kind allows the chunk's `where`, place it there (carve, plus its break). *(As built: a slot under way, started before now, is kept too, from now on: else `slotAt(now)` could never find one.)*
   5. **The rest** in the given order (the day's `ORDER`, already applied to `chunks`): the first interval, by start, where the chunk fits whole, with `where == Home` allowed only in `Evening` intervals, and with `deadlineMinOf(chunk)` (minute of day, when the deadline is today) requiring `interval.start + minutes ≤ deadline`; place at the interval's start; carve the slot and then a break of `slotBreakMin`, or `slotLongBreakMin` when the work placed in this window since the last long break (or since its start) ≥ `slotLongBreakAfterMin`. A chunk fitting no interval goes to `unslotted`. *(As built: the break is before a piece as well as after it, in its window, so one laid later never ends against a pin or a kept slot; the break after runs over a busy time too short to be one; no piece starts before its task opens, `availableAt`.)*
   6. Return slots sorted by start, and `unslotted` in order.
   `firstFreeAfter` reuses step 1 and finds the first start ≥ `from` with room for `minutes` for the `where`; the "later today" action (phase 6) and PL-13 use it. *(As built: it takes `breakMin`, a work slot's break either side; and `Interval` keeps its `DayWindow`, which the breaks need.)*
3. `Planner.Input` gains `pins: Map<String, Long> = emptyMap()`, `previousSlots: Map<String, Long> = emptyMap()`. After the buckets are built, for each day with `windows[day]` present: `Slotter.slot(day, bucket.chunks sorted by ORDER, windows[day], busy + hardBusy, pins, previousSlots (today only), now, zone, settings, outside = dayKinds[day] == Weekend, deadlineMinOf)` → `DayBucket(slots, unslotted)`. `deadlineMinOf(chunk)` = the chunk's deadline's minute of day when `date(deadline) == day && chunk is hard`, else null.
4. `Plan` gains `fun slotAt(now: Long): Slot?` (today's slot containing `now`), `fun nextSlot(now: Long): Slot?` (first work slot today starting ≥ now, else tomorrow's first), `val todaySlots`. `Plan.next` becomes `slotAt(now)?.chunk ?: nextSlot(now)?.chunk ?: ordered.firstOrNull { it.startable(now) } ?: ordered.firstOrNull()`; `then` the slot after.
5. Default `boxMin` 30 (`Settings`); `SettingsLimits.BOX_MIN` unchanged.

**Tests.** New `SlotterTest`: breaks of 5 between slots and 15 after 90 minutes; a home piece skips a study interval; a piece longer than every interval is unslotted; today's first slot ≥ now; a pin lands at its time, or the next free start when busy covers it; a previous start is kept when free; outside breaks: three on a free Saturday, two when the work needs the midday one's time, none when the day is full; the deadline-time rule. `PlannerPropertyTest`: **slots** (no overlap; each inside a window of a kind its `where` allows; every work slot's chunk is in the bucket; breaks ≥ `slotBreakMin` between consecutive slots in one window; outside slots only on weekend kinds; pinned chunks at their time when free).

**Done when.** `plan.buckets[0].slots` lists today's pieces with times; the Plan tab (until phase 6) shows the time next to each piece (add it to `ChunkTile` now: "17:00–17:30").

---

## PL-04 Pins: "on Thursday" and "at 19:30"

**Change.** Two change types the chat and the UI can make: a task on one day; a piece at a set time.

**Files.** `core/Instructions.kt`, `enrich/InstructionReader.kt`, `core/Task.kt`, `core/Planner.kt`, `AppGraph.kt`.

**Steps.**

1. `ChangeType.OnDay` (needs `taskId`, `date`), `ChangeType.AtTime` (needs `taskId`, `date`, `startMin`). `InstructionPrompts.CHANGE_TYPES`:
   ```
   - OnDay: all of the task's remaining work goes on one day. Needs taskId and date.
   - AtTime: the task's next piece starts at a set time. Needs taskId, date and time (HH:MM).
   ```
   `InstructionAnswers.parse`: both; `AtTime`'s `time` → `startMin` (reuse `minute(g.time)`). *(As built: an `AtTime` change also carries its start as `time`, epoch millis, so the task holds it with no zone to hand; a weekday isn't a day for either.)*
2. `TaskOverrides.onDay: String? = null`, `atTime: Long? = null` (epoch of the pinned start); `Instructions.taskOverrides` fills them from the latest applied change of each type per task; `TaskItem.userOnDay`, `userAtTime` with `withOverrides`/`overrides()` updated. *(As built: a later day for all of it drops a time on another day; a time on the day it's kept to keeps both.)*
3. Planner: an item of a task with `userOnDay` gets `notBefore = max(notBefore, dayStart)` and `deadline = min(deadline, dayEnd)` with `hard` unchanged, `why = Pinned`. `AppGraph.plan` builds `pins`: for each task with `userAtTime` in the future, `pins[key of its first undone chunk] = userAtTime` (the first chunk is known only after `pieces()`; compute inside `Planner.plan` instead: `Input.atTimes: Map<taskId, Long>` → in `plan`, after items are built, map to the task's first chunk key). A pin past the task's hard deadline is ignored with `why = NoRoom`. *(As built: the planner reads `userAtTime` off the task, no `Input.atTimes`: the next piece becomes a run of its own on the time's day, from the time, so the rest follow it, and the slotter lays it then; `hard` stays only where the task's own deadline is that day, so keeping work to a day never says it won't fit a deadline it doesn't have; the day is its last usable day whatever the margin; work put on a day is placed before the rest, so it has the day's time; a pin whose day has gone, before the task can start, or after its real deadline is ignored and the work placed as it would be, not marked `NoRoom`; a time past a plan's date leaves the date behind, the work undated from the time. The property test draws pins.)*
4. Refusal at apply time (`AppGraph.applyInstruction` → a new `refuseLate(instruction)`): an `OnDay`/`AtTime` whose date is after the task's hard deadline day → `InstructionStatus.Unclear`, note "It's due before then". `OwnTasks.MADE_ON_IT` unchanged (pins aren't made on the task).
5. `Instructions.describe`: "All of “Statics Prep” on Thu 16 Oct", "“Statics Prep” at 19:30 on Thu 16 Oct".

**Tests.** `InstructionsTest`: parse, describe, overrides. `PlannerTest`: OnDay confines the task; AtTime pins its first chunk (slot start equals the pin); a late pin is refused by `refuseLate` (test through `AppGraph`-free logic: put the check in `Instructions.latePin(change, task, zone): Boolean`).

**Done when.** "Put the physics at 19:30" in the chat (phase 5) or the Move sheet (phase 6) pins the slot.

---

## PL-13 Adjust live: slot alarms, missed slots, stability, the plan watcher

**Change.** The plan is recomputed when anything it depends on changes; slot starts and ends fire alarms; a missed slot is re-slotted and remembered; today's slot starts are kept stable across replans.

**Files.** New `core/SlotState.kt`, new `block/SlotAlarms.kt`, new `PlanWatcher.kt` (package root, beside `AppGraph`), `data/RuntimeState.kt`, `AppGraph.kt`, `AndroidManifest.xml`.

**Steps.**

1. `core/SlotState.kt`:
   ```kotlin
   @Serializable data class SlotState(val date: String = "", val previous: Map<String, Long> = emptyMap(), val missed: List<MissedSlot> = emptyList()) {
       fun on(date: LocalDate): SlotState   // a new day starts empty
   }
   @Serializable data class MissedSlot(val key: String, val taskId: String, val at: Long, val spilled: Boolean)
   ```
   `RuntimeState.slots: SlotState = SlotState()`. `MissedSlot.spilled` is true when, after the replan, the piece is no longer in today's slots (it went to tomorrow); BL-11 reads it.
2. `PlanWatcher` (Android-free except for the hooks it's given): constructed in `AppGraph` with the stores and a list of hooks `suspend (Plan) -> Unit`. It `combine`s `tasks.state`, `settings.state`, `instructions.state`, `calendarState`, `school.state`, and `runtime.state.map { it.slots.previous to it.calibration }.distinctUntilChanged()`, debounces 2 s (`kotlinx.coroutines.flow.debounce`), computes `graph.plan()`, then: writes `runtime.slots.previous = today's work slots' starts` (only if different, so no loop), then runs the hooks in order. `fun poke()` forces a run (used by receivers). Hooks registered in `AppGraph.init`: `SlotAlarms.arm`, `SlotCalendar.write` (NF-07), `WidgetUpdater.update`; phase 5 adds `Ahead.run` and Felix's check. Started in `AppGraph.init` on `scope`. *(As built: the widget's own redraw on the tasks, settings and runtime stays beside the watcher's, as the widget shows free time and sessions too.)*
3. `block/SlotAlarms.kt`: `ACTION_SLOT_START`, `ACTION_SLOT_END`; `fun arm(context, plan, now)`: cancel both pending intents, then set exact alarms (`setExactAndAllowWhileIdle(RTC_WAKEUP, …)` when `canScheduleExactAlarms()`, else `setWindow`, as `Sessions.scheduleEnd` does today) for the next work slot start ≥ now and the next work slot end + `GRACE_MS` (10 min) ≥ now, with the slot key as an extra. `SlotReceiver : BroadcastReceiver` (manifest, like `SessionReceiver`): on START → `graph.slots.onStart(key)` (phase 5 uses it; now it only pokes the watcher); on END → `graph.slots.onEnd(key)`.
4. `AppGraph.slots: Slots` (a small class, pure logic in `core/SlotRules.kt` if it grows): `onEnd(key, now)`: find the slot in the current plan; if its chunk is still open and not ticked (the chunk is still in some bucket) → `runtime.update { slots = slots.on(today).copy(missed = missed + MissedSlot(key, taskId, now, spilled = false), previous = previous - key) }`; `watcher.poke()`; after the replan, if the chunk's key is in no slot today → set `spilled = true` on that entry. (Do the second step in a watcher hook: `MissedSlots.reconcile(plan)`.) A kept slot (phase 4's `keepSlot`) removes its key from `previous` and `missed`. *(As built: `Slots` is in `block/SlotAlarms.kt`, and the reconciling is `SlotState.reconciled(plan)`, done in the same write as keeping the starts. And a slot just over stays where it was through its grace, `Slotter.GRACE_MS`, its piece still to tick: else the first replan after its end would slide it on at once, and its end's alarm would find nothing missed.)*
5. Deliberate moves: in `AppGraph.applyInstruction`, when an applied change is `AtTime`, `OnDay` or `StartFrom` for a task, remove that task's entries from `missed`. The "later today" action is phase 6 and uses `Slotter.firstFreeAfter` + an `AtTime` instruction with `quick = true`.
6. Stability across replans is `previous` (step 2 and the Slotter's step 4); a slot that was missed loses its `previous` entry so it moves.
7. On app start (`AppGraph.init`) and in `FocusService.tick`, call `graph.slots.sweep(now)`: any work slot today whose `end + GRACE_MS < now` and whose chunk is still open and not in `missed` → `onEnd` (an alarm missed while the phone was off). *(As built: judged by the starts the last plan kept, and only where one is past its grace, so the tick's sweep is nothing most of the time.)*

**Tests.** New `SlotStateTest`: `on(date)` resets; missed recorded once; a kept key leaves both maps; `reconcile` marks spilled. `PlanWatcher` is tested with a `TestScope` and fake stores (`JsonStore` works on a temp dir in tests already: see `StoresTest`): one change → one plan → hooks called in order; a `previous` write equal to the old value triggers no second run. *(As built: with `MutableStateFlow`s, as `JsonStore.state` is one: `JsonStore.update` writes on `Dispatchers.IO`, a real thread, which the test's virtual time doesn't wait for.)*

**Done when.** Letting a slot's end pass by 10 minutes with the piece undone moves it to the next free time (visible on the Plan tab after the alarm), and ticking it later leaves the other slots where they were.

---

## NF-07 Every slot written to your Google Calendar

**Change.** The app writes the next seven days' slots and outside breaks as events into a calendar the student picks; diffs by a custom URI; reads hand moves back as pins; ignores deletions.

**Files.** New `calendar/SlotCalendar.kt` (package `com.thomaswcode.decrastination.calendar`), `AndroidManifest.xml`, `data/Settings.kt`, `data/RuntimeState.kt`, `learn/CalendarTime.kt`, `ui/SettingsActivity.kt`, `ui/SetupScreen.kt`, `README.md`.

**Steps.**

1. Manifest: `android.permission.WRITE_CALENDAR`. Setup asks for it with `READ_CALENDAR` on a fresh install; on the owner's phone grant it over adb: `adb shell pm grant com.thomaswcode.decrastination android.permission.WRITE_CALENDAR`.
2. **Why the student creates the calendar.** A calendar an app creates locally on a Google account is not uploaded by Google's sync adapter: it stays on the phone. So the app writes into a calendar that exists on the account. The owner has created "Decrastination" at calendar.google.com and it's synced to the phone. Setup → Calendar: "Create a calendar called Decrastination at calendar.google.com (Settings → Add calendar → Create new calendar), wait a minute for it to appear below, then pick it. If Google Calendar gives it default notifications, turn them off there: the app's events carry none." Below, a list of writable calendars (`CalendarContract.Calendars.CONTENT_URI`, projection `_ID, CALENDAR_DISPLAY_NAME, ACCOUNT_NAME, CALENDAR_ACCESS_LEVEL`, filter `CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR`), saving `settings.calendarWriteId`. On the owner's phone pick "Decrastination" there as part of this item's phone check. Don't change Google Calendar's own settings on the phone (the owner's rule); the events are written with `HAS_ALARM = 0`.
3. `SlotCalendar.write(context, plan, zone, written: Map<String, Long>): Map<String, Long>`: no-op when `calendarWriteId == null` or the permission is missing. For the days `today..today+6`: desired events, one per `Slot`:
   - `CUSTOM_APP_PACKAGE = context.packageName`, `CUSTOM_APP_URI = "decrastination://slot/<date>/<slot.key>"`;
   - `TITLE` = the chunk's `label` (or "Outside"), `DESCRIPTION` = (phase 5 adds the micro-plan) the task's source and `why` line, `DTSTART`/`DTEND` = the slot's times, `EVENT_TIMEZONE = zone.id`, `AVAILABILITY = AVAILABILITY_FREE`, `HAS_ALARM = 0`, `CALENDAR_ID = calendarWriteId`.
   Query existing: `Events.CONTENT_URI`, selection `CALENDAR_ID = ? AND CUSTOM_APP_PACKAGE = ? AND DTSTART >= ? AND DTSTART < ? AND DELETED = 0`, projection `_ID, DTSTART, DTEND, TITLE, DESCRIPTION, CUSTOM_APP_URI`. Diff by URI: insert the missing, update the changed (times, title, description), delete ours that aren't desired. One `ContentProviderOperation` batch per write (`contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)`). Return the map URI → DTSTART written.
4. `RuntimeState.calendarWritten: Map<String, Long>` (URI → start we wrote). Hand moves: before the diff, for each existing work-slot event whose `DTSTART != calendarWritten[uri]` and whose chunk is still open: make an `AtTime` change for its task at the event's start (`graph.addOwnChange(id, "Moved in the calendar", About(taskId), Change(AtTime, taskId, date, startMin))` with `quick = true`); if the date is after the task's hard deadline day, refuse (phase 5: Felix says so; now a `Channels.CALENDAR` notification "Can't move X to …: it's due before then") and let the diff rewrite it. A deleted event isn't in the query (`DELETED = 0`), so the diff re-inserts it; the busy read (step 6) has already moved the slot if the student put an event in its place. *(As built: the change taken is the task's next piece at the event's start, as `AtTime` means, so dragging a later piece pins the next one there; a break outside dragged is put back; a moved event the plan now has at the same time is left be. Changing the calendar written to takes the app's events out of the old one.)*
5. Register `SlotCalendar.write` as a watcher hook after `SlotAlarms.arm`; it runs on every replan, so the diff must be cheap (one query, one batch).
6. `CalendarTime.read`: exclude `calendarWriteId` and `calendarsExcluded` (`CALENDAR_ID NOT IN (…)`), DX-13. Read `EVENT_LOCATION` and `DESCRIPTION` into `CalendarEvent.location`, `description` (DX-12); `EventJudge.judge`: a timed event whose description contains "optional" or "tentative" (ignore case) → `Ask`.
7. The app's calendar change watcher (`AppGraph.watchCalendar`, a `ContentObserver`) already refreshes `calendarTime`; make sure the observer ignores changes the app's own write makes (compare the write's time: skip a refresh within 5 s after our batch, or diff the result). *(As built: by the diff: every change reads the calendar again and makes the plan again, as a move or a delete in the calendar written to, which isn't read, is seen only by a replan's write; the app's own write brings one replan more, which writes nothing. A time skip would have missed a move made just after a write.)*
8. Settings → Calendar section: the writable-calendar picker (step 2), and a list of all calendars with a checkbox each for `calendarsExcluded` (the write calendar shown as "written to, never read").

**Tests.** Pure parts: `SlotCalendarDiff` in `core/` or `calendar/` as a pure function `diff(desired: List<Desired>, existing: List<Existing>): Diff(inserts, updates, deletes, handMoves)` with unit tests: insert/update/delete; a moved event is a hand move, not an update; a deleted event is an insert.

**Done when.** The phone's Google Calendar shows today's slots in the Decrastination calendar within a minute of a replan; dragging one in Google Calendar pins it in the app; deleting one brings it back at the next replan unless something else is in its time.
