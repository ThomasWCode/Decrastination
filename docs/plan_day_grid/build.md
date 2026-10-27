# Building the Day as a calendar

Three releases, each on its own branch from `main`, one stacked on the one before at most while that one's reviews run (a stack of two, no more):

- `4.5.0: a time you give, kept to the minute`, branch `v4.5.0`, `versionCode` 80, `versionName` "4.5.0".
- `4.5.1: a day for only some tasks`, branch `v4.5.1`, `versionCode` 81, `versionName` "4.5.1".
- `4.6.0: the Day as a calendar`, branch `v4.6.0`, `versionCode` 83, `versionName` "4.6.0".

The numbers follow 4.4.0, the timer (D-112), merged on 26 Oct at `versionCode` 78, then the breaks outside's plan ([`plan_outside_breaks/`](../plan_outside_breaks/README.md)): 4.4.1 at 79, before 4.5.0, and 4.5.2 at 82, between 4.5.1 and 4.6.0. The rules of the house hold throughout: `CLAUDE.md`, `docs/development.md`, `docs/plan_v4/architecture.md` §The rules and §Conventions. Nothing personal in code, tests, fixtures, docs or the PRs (D-79): fixtures and examples are made up. No new library: everything here is in the Compose BOM the app already uses (`androidx.compose.foundation`'s gestures and scroll, `androidx.compose.ui`'s pointer input, haptics and semantics), so there's nothing to install.

Before the first edit of each release: `PROGRESS.md` from `private/tools/progress.py init`, one item a stage below with its estimate, kept current and deleted before the PR is called done; never staged. Estimates are from stages of this size in 4.3.x; the actuals go in `PROGRESS.md` as each stage ends.

## 4.5.0: a time you give, kept to the minute

**Built** in 4.5.0 (D-114). Found while building, beyond the stages below: the breaks outside still to come are laid around the pins kept at their time (a pin at a break's time would otherwise have been laid over it), which pins keep their time is worked out once, by the planner, for every day at once and across midnight (`Planner`'s `keptPins`), and the slotter told (`Slotter.slot`'s `exact`), for the breaks and the pieces; a kept pin takes school's or the evening's time as it falls (`Planner.pinSpan`); and a time given after the evening's end had no room on its day (its opening day's time from its start was none), so its piece went to the next day: with its part outside the free time taking none of it, it stays on its day.

Read first, in full: `core/Slotter.kt` (`slot`, `lay`: its pin pass, `holding`, `place`, `carve`, `runOf`, `follows`), `core/Planner.kt` (`Input`, the building of `atPins`, `pinProblem`, `pinnedTime`, the call of `Slotter.slot`), `plan/Planning.kt` (where it builds `Planner.Input`), `core/Instructions.kt` (`InstructionState.applied`, `InstructionRules.latePin`), `felix/ChatAnswers.kt` (`movedPins`), `ui/MoveSheet.kt`, `tasks/Instructions.kt` (`laterToday`), `calendar/CalendarWriter.kt` (the hand moves), and their tests (`SlotterTest`, `PlannerTest`, `ChatTest`, where `ChatAnswers` is tested, and `PlanSnapshotTest`).

### 1. The pins' order (30 min)

- `core/Planner.kt`: `Input.pinOrder: List<String> = emptyList()`, the ids of the tasks given a time, the latest given first (KDoc: "so the slotter lays the latest first, and an earlier pin it covers goes after it"). `atPins` is built as a `LinkedHashMap` in that order, tasks not in it after, so `input.pins + atPins` meets the newest first.
- `plan/Planning.kt`: `pinOrder` from `instructions.applied` (in the order applied, oldest first): each `AtTime` change's task, reversed, distinct. That covers every way a time is given: the Day's moves, the move sheet, Felix's changes and the calendar's hand moves, which are all changes of yours or his.
- Tests (`PlannerTest`): two tasks given overlapping times, the one given later keeps its time and the other goes to the next free start after it; with `pinOrder` empty, the order is as now.

### 2. The slotter: a pin kept to the minute (75 min)

In `Slotter.lay`'s pin pass, the loop after "Pinned: at their time where it's free":

- A pin whose time and grace have gone by is skipped, as now (laid as any piece, PL-13).
- It's laid **at its time** where its span `[pin, pin + minutes)` overlaps no work laid already in this pass (a pin given later, laid first), the piece is over by its deadline (`beforeDeadline`), and it opens by then (`pin >= chunk.availableAt`). No longer required: free time holding it (`holding`), or a window for its kind of work (`allowed`).
- Its interval for `place`: the free interval holding it where there is one, so its breaks are kept within its window as now; else an `Interval(pin, end, window)` with the window it overlaps most, or, outside every window, a `DayWindow` of its own span of the **kind of the window nearest it in time**. `Slot.window` is recorded with the slot (`Focus`, the `SlotRecord`) and teaches the day yields (`learn/Calibrator.kt`): a pin in a lesson counts as school's, one late at night as the evening's.
- `place` carves its span and its breaks from the free time; in a window of its own, the breaks clip to its own span, so none is carved outside the windows. Over busy time there's no free time to carve: nothing changes there. Check `carve` is a no-op on spans outside every interval, and `runOf` treats its own window as a run of one.
- The `moved` pass is unchanged: a pin over one given later goes to the next free start at or after its time, as a pin not free does now.
- Unchanged after it: today's kept starts it covers fail `holding` and are laid afresh; its task's later steps follow its end (`follows`, D-108); a piece under way stays where it is.
- The KDoc of `slot` and the comment over the pin pass say the new rule; `docs/spec/slots-and-calendar.md` §Pins with them (§Docs).
- Tests (`SlotterTest`, `:core`): a pin outside every window kept at its minute, its slot's window kind the nearest window's; one over a busy event, and over a time you said you can't work (`hardBusy`), kept; home work pinned in a school window kept; two pins over each other, the first in the map kept and the second after it; the task's later steps after the pin (D-108); a kept start it covers laid afresh; no break carved outside the windows; a pin whose piece isn't over by its deadline, and one before its piece opens, moved as now; time and grace gone, laid as any piece. The tests that held the old rule (a pin not free going to the next free time) change on purpose: each named in the PR.

### 3. The day's free time: a pin's time outside it uses none of it (45 min)

The owner's word (`answers.md` §Changed at the owner's word): a piece pinned outside the day's windows doesn't use up the day's free time for the time it's outside them. The same for the part over busy time within the windows (decided without asking, 25), which is already off the day's free time and would otherwise come off it twice.

- `core/Planner.kt`: `outsideFreeMin(day, start, minutes, input): Int`, pure: the minutes of `[start, start + minutes)` not in the day's free time: outside its windows (`input.windows[day]`, or your hours where it has none) or within `input.busy + input.hardBusy`.
- Where the days are given their pieces: the task's next piece, where its pin's time (`pinnedTime`) is on the day it's placed on, takes from that day only its minutes less `outsideFreeMin`: in `take` and its reverse, in `room`/`fits` for that piece, and in the evening's time (`freeHome`) for home work. Its whole minutes still count against a day's limit (`dayCaps`, `capped`), kept apart from the free time for that (decided without asking, 26).
- The day's free time as shown (`DayBucket.capacityMin`: the strip's bar, the Day's "planned, free", what Felix is told) is more by the same minutes, so a piece at 23:00 doesn't make the day look fuller than it is.
- The pin's time as given is used, even where the slotter then moves it (a pin given later over it): a rare case, and the next plan counts it where it's laid only if it's pinned again.
- Tests (`PlannerTest`): a piece pinned wholly outside the windows leaves the day's room for other work as it was, and its `capacityMin` more by its minutes; one half outside takes half; one over a busy event in a window takes none of the window's time for that overlap; home work pinned after the evening takes none of the evening's; on a day you've limited, its whole minutes count against the limit; `outsideFreeMin` at a window's edges and across midnight. With no pin outside the free time, plans are as they were (`PlanSnapshotTest`).

### 4. The Day's refusals count the piece's minutes (30 min)

- `Planner.pinProblem(task, at, now, zone, minutes: Int = 0)`: `PastDeadline` where the piece wouldn't be over by a real deadline still to come (`at + minutes * 60_000L > it`), not only where it would start at or after it. Callers: `ui/MoveSheet.kt` passes `chunk.minutes`; `Instructions.laterToday` passes `chunk.minutes` (it already finds a time the piece fits whole, so nothing it finds changes).
- `pinnedTime` is unchanged: the slotter's `beforeDeadline` already keeps a piece from a pin it wouldn't be over by.
- Felix's `InstructionRules.latePin` and the calendar's hand moves keep "at or after the deadline" (`answers.md`, decided without asking, 7): a pin of theirs whose piece can't be over by its deadline is laid as now.
- `ChatAnswers.movedPins`: its KDoc says what puts a pin elsewhere now (one given later over it; its deadline; its opening); its code doesn't change.
- Tests (`PlannerTest`): a piece ending after its deadline is `PastDeadline` with its minutes, not without; overdue work any time from now, as now.

### 5. The release (45 min)

- `app/build.gradle.kts`: 80, "4.5.0".
- `./gradlew testDebugUnitTest --tests '*PlanSnapshotTest*'` and `--tests '*FelixInputsDocTest*'`: where the made-up state has a pin that wasn't free, the plan changes; written again (`-PupdateSnapshots=true`, `-PupdateDocs=true`) only once its diff is understood, the diff explained in the PR. `GoldenPromptsTest` must pass unchanged: no prompt or schema changes.
- Docs (§Docs, 4.5.0), then CI's command on the branch, the diff scanned (`scripts/scan-public.sh`), the PR, the reviews the owner asks for.
- The phone session (§The phone, 4.5.0).

## 4.5.1: a day for only some tasks

**Built** in 4.5.1 (D-115). Decided while building: a task moved onto the day joins it where the move came after the day's *first* DayOnly in force, not its latest as stage 2 below says, so Felix's answer to his own question (one more DayOnly, "Anki too") keeps a task that joined between; `alsoAllow` is an enum in the schemas (empty, `anki`, `anki-deadlines`), a plain string still, never nullable; `OnlyDay` carries the tasks that joined (`joined`), so the join is said by Felix's answer and the move sheet.

Read first, in full: `core/Instructions.kt` (`ChangeType`, `Change`, `InstructionState`, `InstructionRules.dayCaps`, `describe`), `core/Chat.kt` (`Proposal`, and the two states' versions), `core/Planner.kt` (`Input`, `room`, `limited` and `capped`, `placeBack`, `freeUntilDue`), `plan/Planning.kt`, `enrich/InstructionAnswers.kt` (`Given`, `change`, `problem`), `enrich/InstructionPrompts.kt`, `felix/ChatPrompts.kt` (the system prompt and its Gmail variant, `plan`), `felix/ChatAnswers.kt`, `felix/FelixPrompts.kt` (`describe`), `data/Backup.kt` (its checks of each change's fields), `data/Stores.kt`, and the tests `InstructionRulesTest` (which tests `InstructionAnswers` too), `PlannerTest`, `ChatTest` (`ChatAnswers`), `ConversationTest`, `GoldenPromptsTest`, `SchemaLimitsTest`, `MigrationTest`, `StoresDecodeTest`.

### 1. The change, and the stores that hold it (45 min)

- `core/Instructions.kt`: `ChangeType.DayOnly`, KDoc "Only the tasks given a [DayOnly] for [Change.date] are planned on it, [Change.taskId] among them; [Change.alsoAllow] what else may come in." `Change.alsoAllow: String? = null`, KDoc "[ChangeType.DayOnly]'s day also takes Anki's cards (`anki`), or those and work that would otherwise miss its real deadline (`anki-deadlines`); else nothing." Its two values as constants on `InstructionRules`.
- A new value in a stored enum and a new field: an older build must set the newer files aside (`docs/plan_v4/data-and-migrations.md` §Versions and migrations, rule 6). `Change` is held by the instructions (`InstructionState`) and by Felix's proposals in his messages (`Proposal.changes`, `core/Chat.kt`), so each store holding it raises its version, as 4.2.0 raised every store its facts were in: `instructions.json` 3 → 4, and `chat.json` and the archive's months by one each. `data/OnlyMigration.kt`: a `Migration` for each, changing nothing, added to each store's list in `data/Stores.kt`. A backup's parts of the instructions go through them too (rule 5), harmlessly.
- `data/Backup.kt`'s checks of a restored change: a `DayOnly` stands with a task named, a date, and `alsoAllow` empty or one of the two values.
- `InstructionRules.describe`: "Thu 29: only Essay", then "; Anki too" or "; Anki, and work that would miss its deadline, too".
- Tests: `MigrationTest`'s chain for each store; `StoresDecodeTest` with a new `fixtures/stores/<date>-4.5.1/`, the latest folder copied with the raised versions and an instruction holding a `DayOnly`, the earlier folders read into the new versions; the backup's checks; `describe`.

### 2. The planner (90 min)

- `core/`: `data class OnlyDay(val taskIds: Set<String>, val anki: Boolean, val deadlines: Boolean)`, and `InstructionRules.onlyDays(applied, from, to): Map<LocalDate, OnlyDay>`, beside `dayCaps`: for each date, the tasks of its `DayOnly` changes; with them, the tasks whose `OnDay` for that date, or `AtTime` on it, was applied after the day's latest `DayOnly` (a later move wins); and what the latest `DayOnly` allows. A finished task stays in the set, so a day whose tasks are all done holds no work (Q14).
- `Planner.Input.onlyDays: Map<LocalDate, OnlyDay> = emptyMap()`, from `Planning` beside `dayCaps`.
- `Planner`: on such a day an item has no room (`room(item, day, i)` is 0) unless its task is in the set; or the day allows Anki and its task is an Anki deck's (`Source.Anki`); or the day allows deadlines, the item has a real deadline, and none of its other days up to the deadline has room for the piece (`placeBack`'s `latest` tries the other days first, then the only-day). Only-days count as `limited`, with no room for the tasks not on them, so overdue work doesn't pile into one but goes to the next day with time, as on a day you've limited. `freeUntilDue` counts the room as `room` gives it, so work an only-day pushes past its deadline is said in *won't fit* (PL-02).
- Unchanged: the slotter; the listed tasks' pins (4.5.0's rule); a piece under way stays where it is.
- Tests (`PlannerTest`, `InstructionRulesTest`): an only-day holds its tasks alone, the rest of its time free; its tasks planned as usual (one due later isn't pulled onto it); with an `OnDay` for a listed task, its work there first; a task not listed, given an `OnDay` for that day after the `DayOnly`, joins, and one given it before is left out; Anki's cards in only where allowed; with deadlines allowed, work with no other day before its deadline takes it and work with one doesn't; its tasks all done, the day empty; today an only-day, overdue work goes to tomorrow; *won't fit* said for work pushed past its deadline; the latest `DayOnly` sets what's allowed. With no `DayOnly`, plans are as they were (`PlanSnapshotTest` unchanged).

### 3. Felix and the instruction reader (75 min)

- The change in both prompts' lists of changes, after `OnDay` and `AtTime` (`felix/ChatPrompts.kt`'s system prompt in both its variants, `enrich/InstructionPrompts.kt`), in the prompts' own style. For Felix, in substance: "DayOnly: a day for only some tasks' work: no other task's work is planned on it, and the time they don't use stays free. They're planned as usual: add OnDay for one whose work should all go on that day. One change a task, each with taskId and date. alsoAllow: empty for nothing else, `anki` for Anki's cards too, `anki-deadlines` for those and work that would otherwise miss its deadline. Where the student hasn't said what else may come in, make it with alsoAllow empty, ask which in your reply and offer the three as quick replies; their answer is one DayOnly for that day with its alsoAllow, which sets what the day allows." For the instruction reader the same, but its last sentence: "Where the instruction doesn't say what else may come in, leave alsoAllow empty."
- The schemas (`chat.schema.json`, `instruction.schema.json`, as their code builds them): `DayOnly` in the change's type enum; `alsoAllow` a plain string, empty for nothing, never nullable (`SchemaLimitsTest`).
- `InstructionAnswers.Given.alsoAllow` (default empty); `change(g, …)`: a `DayOnly` needs a task listed and a date, and one with a weekday alone is left out ("a day only for some tasks takes a date"); `alsoAllow` kept where it's one of the two values, else nothing; `problem`'s words for what's left out.
- Felix's context: each only-day on its day's line of the plan he reads (`ChatPrompts.plan`, which `FelixPrompts.describe` shares), with its tasks, those finished marked done, and what it allows; the changes in use say it through `describe`.
- The join said: where a move makes a task join an only-day, the move sheet's and the Day's snackbar add "Thu 29 was only for Essay: Maths joins it"; Felix's answer gets a line from the app, as `ChatAnswers.movedPins` adds its own (`ChatAnswers.joined(changes, onlyDays)`). A move in Google Calendar joins without a word: it's yours, made there, and the day's instructions show it.
- `ui/DayScreen.kt`: the instructions about the day (the `said-$day` item) list `DayOnly` beside `DayLimit` and `BusyTime`.
- The goldens regenerated (`-PupdateGolden=true --tests '*GoldenPromptsTest*'`), their diff in the PR. No reading's prompt changes, so `ReadingPrompts.VERSION` stays. Every schema compiled by the API (`./gradlew testDebugUnitTest --tests '*SchemaCompileTest*' -PcheckSchemas=true`: about a cent a schema). `FelixInputsDocTest`: its made-up state given an only-day, the page written again with it.
- At the owner's word only, a throwaway probe of a few chat turns ("only the essay on Saturday", then "Anki too") through the real prompt and parser (`ChatAnswers`), judged by what the parser makes of the answer: a handful of calls, a few cents.
- Tests: `InstructionRulesTest` and `ChatTest` (a `DayOnly` with a date kept; with a weekday alone left out, said; `alsoAllow`'s two values kept, another dropped; `joined`'s words); `ConversationTest` through `FelixHarness` (free, the API a stand-in): an answer with `DayOnly` changes and three quick replies makes the changes and shows the replies, and a reply answered by a `DayOnly` with `anki` changes what the day allows.

### 4. The release (45 min)

- `app/build.gradle.kts`: 81, "4.5.1".
- It migrates stored data: `scripts/rehearse.sh` on a recent backup of the phone's before the PR is called ready (`docs/development.md` §Building and testing).
- CI's command on the branch; the diff scanned; the PR; the reviews the owner asks for.
- The phone session (§The phone, 4.5.1).

## 4.6.0: the Day as a calendar

Read first, in full: `ui/DayScreen.kt`, `ui/DayModel.kt`, `ui/MoveSheet.kt`, `core/Instructions.kt` (4.5.2's `OutsideAt`) and `core/Plan.kt` (`Slot.outsideAt`, 4.4.1), `ui/PlanCards.kt` (`NextCard`, `PieceSheet`, `WontFitTile`, `ChunkTile`), `ui/Components.kt` (`ListTile`, `SectionHeading`, `Pill`), `ui/Hint.kt`, `ui/Colours.kt`, `ui/Format.kt`, `ui/MainActivity.kt` (the tabs and the scaffold the Day sits in), `learn/CalendarTime.kt` (`read`), `learn/EventJudge.kt` (`CalendarEvent`), `core/Settings.kt`, `core/SettingsLimits.kt`, `data/Stores.kt` and a recent no-op migration (`git log --oneline -S CreditMigration`, and the timer's `data/TimerMigration.kt` once built), `data/Backup.kt` (the settings' part), and the tests `DayModelTest`, `MigrationTest`, `StoresDecodeTest`, `ArchitectureTest`.

Four commits: the move sheet's actions moved (stage 1, alone, as a move is); the setting, the colours, the model and the layout with their tests (stages 2 to 4); the screen (stages 5 to 7); the docs and the version (stage 8).

### 1. The move sheet's actions, moved (30 min, a commit of its own)

`ui/Moves.kt`, `internal object Moves`, with the move sheet's two actions moved out of `MoveSheet` as they are, so the drag and the sheet make the same changes with the same checks and words:

- `toTime(graph, chunk, day, minute, zone): Moved`: the sheet's `TimeDialog` result: `pinProblem` (with the piece's minutes, from 4.5.0), its problem's words, or the `AtTime` change of yours and its words ("At Thu 29 17:15").
- `toDay(graph, chunk, day, zone): Moved`: the sheet's *All of it, any time that day*: the chat's time holding it that day ("A time you gave in the chat holds it at …: take that back in the chat's Your changes first."), else the quick `AtTime` taken back and the `OnDay` change made ("All of it on Thu 29").
- `dayAllowed(task, day, now, zone): Boolean`: the sheet's `late`/`early` rule for a day's chip.
- `outsideTo(graph, slot, minute, zone): Moved`, new, for the drag alone: a break outside at that time on its own day, an `OutsideAt` change of yours (4.5.2) naming it by its own time (`Slot.outsideAt`), "Outside at Sat 31 14:30"; refused where its time has gone ("That time's gone") or it would be over another break of the day ("Another break outside is then").
- `sealed interface Moved { data class Done(val said: String); data class Refused(val why: String) }`.

`MoveSheet` calls them; nothing it does or says changes. In the next commit, the pure part (a `PinProblem`'s words, `dayAllowed`, `outsideTo`'s refusals) is tested (`MovesTest`).

### 2. The zoom, kept in the settings (30 min)

- `core/Settings.kt`: `val dayHourDp: Int = 60`, KDoc "The Day's grid: the height of an hour as last pinched (4.6.0), 40 to 120." Its clamp in `SettingsLimits` beside the others. The settings' `version` 2 → 3.
- `data/ZoomMigration.kt`: `val SETTINGS = Migration(2) { it }`, a raise with nothing to change (`docs/plan_v4/data-and-migrations.md` §Versions and migrations, rule 6), added to `Stores.settings`' list. The settings are a backup's part whole (`data/Backup.kt`), so a backup's settings go through it too (rule 5), harmlessly.
- Written only by its owner: `Protection` (`ArchitectureTest`: `"settings" to setOf("protect/Protection.kt")`); the grid saves through `graph.protection.changeSettings { it.copy(dayHourDp = …) }`, as the hints do, once a pinch ends, never a frame at a time.
- Tests: `MigrationTest`'s chain covers the step; a version-2 `settings.json` read into version 3 as `StoresDecodeTest` reads every folder; a new `fixtures/stores/<date>-4.6.0/`, the latest folder copied with the settings at version 3 and `dayHourDp`; the clamp.

### 3. The calendar's colours (20 min)

- `learn/EventJudge.kt`, `CalendarEvent`: `val colour: Int? = null`, KDoc "Its colour as the calendar app shows it (ARGB): its own, else its calendar's; null where the provider gives none."
- `learn/CalendarTime.read`: `CalendarContract.Instances.DISPLAY_COLOR` added to the projection (index 10), `colour = if (c.isNull(10)) null else c.getInt(10)`.
- Not stored, not sent to Claude: check with `grep -rn "CalendarEvent(" app/src core/src` that nothing writes an event's fields whole to a store or a prompt; `FelixInputsDocTest` and `GoldenPromptsTest` stay unchanged.
- `ui/Colours.kt`: `fun onEvent(argb: Int): Color`, black or white, whichever has the greater contrast on it (`Color.luminance()`); an event with no colour drawn in `secondaryContainer`. Test (a new `ColoursTest`): white on a dark blue, black on a yellow.

### 4. The day's model and the grid's layout (90 min)

`ui/DayModel.kt`, still pure, its `rows` replaced:

- `windows: List<Row.Window>`, `allDay: List<CalendarEvent>`, `blocks: List<Block>` (`Block(start, end, item)`, `item` a `Row.Event`, `Row.Planned` or `Row.Recorded`, its times cut to the day's: an event across midnight), `now: Long?` (today's only), `unslotted: List<Chunk>`, `done: List<SlotRecord>`, and `plannedMin`, `freeMin`, `doneMin`, `wontFit` as now.
- `opensAt`: where a day not today opens: half an hour before the earlier of its first window and first block.
- `Row.Gap`, `Row.Now`, `Row.Unslotted`, `Row.Done`, `GAP_MIN` and `order` go; `rowKey` becomes `blockKey`, each block's key its own as `rowKey` makes them now.

`ui/DayLayout.kt`, `internal object DayLayout`, pure, every number in it in minutes or pixels handed in:

- `minuteOf(at, date, zone)`: the minute of the day on its wall clock (`atZone(zone).toLocalTime()`), so the clocks changing don't shift the grid.
- `columns(spans, minMinutes): List<Placed>`: the blocks in clusters that overlap as drawn (a block's drawn end at least its start plus `minMinutes`, the 24 dp minimum at the zoom), each in the first column free at its start, then widened right over the columns free for all its time; `Placed(column, columns, span)`.
- `yOf(minute, hourPx)`, `minuteAt(y, hourPx)`, `snap(minute)` (to five), `dropStart(minute, minutes)` (kept between 00:00 and 24:00 less its minutes).
- `dayAt(x, y, chips: Map<LocalDate, Box>)`: the strip's day under the finger, `Box` a plain left, top, right, bottom, so the test needs nothing of Compose.

Tests: `DayModelTest` reworked (blocks by time, windows apart, all-day events out of the blocks, an event across midnight cut to the day, a move's record left out as now, today's moment only today, the pieces with no time and the done as lists, the load as now, `opensAt`); `DayLayoutTest` (two blocks overlapping, two columns; three where the first and the third don't overlap, the third widened; a short block's drawn minimum counted as overlap; minute and offset at 40, 60 and 120 dp an hour; a 25-minute piece dropped at 23:50 starts 23:35; the chip under the finger; the minute of a time on the day the clocks go back).

### 5. The grid (3 h)

New `ui/DayGrid.kt`:

- `DayGrid(model, hourDp, scroll: ScrollState, …)`: a `Box(Modifier.verticalScroll(scroll))` 24 hours tall. Behind (`drawBehind`): the shading outside the windows, the windows' bands and names, the hour lines; the gutter's labels; the blocks placed from `DayLayout.columns` by `Modifier.offset` and size; today's line and dot over them.
- The blocks: `PieceBlock` (the swipe of `PlannedRow`, its `SwipeToDismissBox` and background words moved into it as they are; its content by height: label and times, then the reason, "First: …" and the message as room allows; the ✓ where 32 dp tall and 120 wide; urgent's red edge), `EventBlock`, `RecordBlock`, `OutsideBlock`, each as `README.md` §The blocks says.
- The pinch: on the grid's parent, `pointerInput` in `PointerEventPass.Initial` with `awaitEachGesture`, acting only while two pointers are down (`calculateZoom`, `calculateCentroid`), consuming only then; the hour's height clamped to 40–120 dp, `scroll` moved so the minute under the centroid stays under it; saved (stage 2) as the second finger lifts.
- Where it opens: `LaunchedEffect(day)`: today, an hour before now; another day, `opensAt`.
- Semantics: each piece's block a content description ("15:10 to 15:35, Maths: Q1–4") and custom actions *Done*, *Later today*, *Move it* (the move sheet).
- `ui/DayScreen.kt` loses `WindowRow`, `EventRow`, `PlannedRow`, `RecordedRow`, `GapRow`, `NowRow` and the `LazyColumn`; it keeps `WeekStrip`, `EventSheet`, `verdict`, `outcome`, `dayName`, and its actions (Done with Undo and its double-swipe guard, later today), handed to the grid as they are.

### 6. The header, the chips and the card's fold (2 h)

- `DayScreen` becomes a `Column`: the hints, `WeekStrip`, today's card in its folding box, `DayHeader`, then `DayGrid` with `Modifier.weight(1f)`.
- The fold: a `NestedScrollConnection` on the column. An upward scroll of the grid (towards later hours) takes from the card's height first, down to its one line; a downward one gives it back first; the card's full height from `onSizeChanged`. Folded, one line "DO NOW · <label>", a tap unfolding it. In landscape (`LocalConfiguration.current.orientation`), it starts folded.
- `DayHeader`: the date (the gutter's width, 56 dp: the weekday in `labelMedium`, the date in `titleLarge`, today's in a filled `primary` circle); the day's name and its load (`Format.load`, and no "done" here: it's a chip); the all-day events as chips in their colours (`Colours.onEvent` for the words), each to its `EventSheet`; the chips for what won't fit, the pieces with no time, what's done and the instructions, each to a `ModalBottomSheet` holding what the list held, moved as it is (`WontFitTile`s, `ChunkTile`s, the done `ListTile`s, the instructions' column with *An instruction about this day…*); "Nothing planned." where the list said it.
- The second hint: `Hint(graph, "day-drag", "Hold a piece to move it: drag it to a time, or onto a day above.")`.

### 7. The drag (3 h)

- `ui/DayDrag.kt`: the drag's state (`class DayDrag`): the piece's key and chunk, its block's size, the finger in the root's coordinates, the snapped minute, the strip's day under the finger. `WeekStrip` reports each chip's bounds (`onGloballyPositioned { it.boundsInRoot() }`) into a `Map<LocalDate, Box>`.
- Start: each piece's block takes `pointerInput(key) { detectDragGesturesAfterLongPress(…) }` in place of `combinedClickable`'s `onLongClick` (the tap stays a `clickable`); `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)`. An `OutsideBlock` takes it too, but for a break under way, which doesn't lift.
- During: the lifted block drawn over the whole Day (a layer in the screen's root `Box`, `zIndex` and a shadow), following the finger; a ghost in the grid at `DayLayout.snap(minuteAt(y))` with its times; the chip under the finger highlighted, the days `Moves.dayAllowed` refuses dimmed; while the finger is within 48 dp of the grid's top or bottom, a `LaunchedEffect` loop scrolls it, up to 12 dp a frame.
- Drop, judged on the plan as it then is (the chunk found by key in `plan.ordered`, else "That piece has changed since (done, or planned again)."):
  - on a strip day not its own: `Moves.toDay`;
  - on the grid: its task's next piece, `Moves.toTime` at `DayLayout.dropStart`; a later piece, "Only its next piece takes a time: drag it onto a day above";
  - a break outside (its slot found by key in the day's slots): on the grid, `Moves.outsideTo` at `DayLayout.dropStart`; on any day of the strip, it springs back, "A break outside stays on its day"; while it's dragged the strip's days are all dimmed;
  - anywhere else: nothing.
  - Refused: the snackbar says why, and the block springs back (an `Animatable` offset to zero). Done: "<said>: undone in the chat's Your changes", as the move sheet's moves are said now.
- `MoveSheet`'s KDoc loses "No drag between rows: a sheet does it for less." and says where it's opened from now (TalkBack's *Move it*).

### 8. The release and the docs (45 min)

- `app/build.gradle.kts`: 83, "4.6.0".
- It migrates stored data (the settings): `scripts/rehearse.sh` on a recent backup of the phone's before the PR is called ready.
- CI's command on the branch; `GoldenPromptsTest`, `PlanSnapshotTest` and `FelixInputsDocTest` unchanged (nothing here changes a prompt or a plan); the diff scanned; the PR; the reviews the owner asks for.
- The phone session (§The phone, 4.6.0).

## Docs

**4.5.0**, in its PR:

- `docs/spec/requirement-ids.md`, PL-04: a time given is kept to the minute wherever it is in the day; one given over a pin given later goes to the first free time after it, and the chat says where; a pin past a real deadline is refused, while overdue work may go at any time from now.
- `docs/spec/slots-and-calendar.md`: §Pins (PL-04) rewritten from `README.md` §A time you give; §Hand moves, a move kept to the minute too.
- `docs/spec/planner.md` §Days and times you gave (PL-04): "which the slotter keeps to the minute, wherever it is in the day"; §A day's time (PL-15): the part of a pinned piece outside the day's free time (its windows, less busy time) takes none of it, but counts against a day's limit.
- `docs/decisions.md`: the next number, *A time you give is kept to the minute*, with the date, the owner's answer and where it applies (`Slotter.lay`, `Planner.pinProblem`, `Input.pinOrder`).

**4.5.1**, in its PR:

- `docs/spec/requirement-ids.md`, PL-04: a day can be kept for only some tasks (`DayOnly`), by Felix or an instruction, allowing nothing else, Anki's cards, or those and work that would otherwise miss its deadline; a task moved there later joins it.
- `docs/spec/planner.md` §Days and times you gave (PL-04): a third kind, *a day for only some tasks*, from `README.md` §A day for only some tasks; §What won't fit: work an only-day pushes past its deadline is said.
- `docs/spec/felix.md` §What he can do: the change, and his question with its quick replies.
- `docs/spec/felix-inputs.md`, as `FelixInputsDocTest` writes it, with the only-day of its made-up state.
- `docs/plan_v4/data-and-migrations.md` §The shapes, release by release: `instructions.json` at version 4, and `chat.json` and the archive's months raised (4.5.1, `DayOnly` and `alsoAllow`, nothing migrated).
- `docs/decisions.md`: the next number, *A day for only some tasks*, with the date, the owner's answers (Q11 to Q14) and where it applies.

**4.6.0**, in its PR:

- `docs/spec/day.md`, new: the Day as it works, naming its code (`DayScreen`, `DayModel`, `DayLayout`, `DayGrid`, `DayDrag`, `Moves`, `MoveSheet`, `NextCard`), from `README.md` §What the Day looks like, in the spec's present tense; `docs/spec/` has had no page for the Day, its rules spread over the requirement ids.
- `docs/spec/requirement-ids.md`: UI-04 (a piece held and dragged to a time on its day, or onto a day of the strip, as well as the move sheet for the fortnight), UI-08 (kept slots in place on the grid; what was done in its chip), UI-11 (events on the Day's grid, all-day ones in its header), UI-17 (a time grid of the chosen day, as Google Calendar's Day view, under the week ahead). UI-02 and UI-03 stand as they are.
- `docs/spec/slots-and-calendar.md` §Moving a slot by hand: the drag, beside the swipes and the sheet; §Outside breaks: a break dragged within its day.
- `docs/plan_v4/data-and-migrations.md` §The shapes, release by release: `settings.json` at version 3 (4.6.0, `dayHourDp`, nothing migrated).
- `docs/decisions.md`: the next number, *The Day is Google Calendar's Day view*: what it copies and what of the Day's stays (the strip, the swipes, the card, the chips), with the date and where it applies.

## The phone

Before each install: `private/tools/keep_before.sh <apk-name> <backup-name>`. After it: `scripts/phone-check.sh` (services bound, no crash, the calendar written, the stores' sizes), and `private/tools/ghostcheck.py`. The owner's list is sent before the session, every step in order and what to look for, with what the scripts have checked already. Everything moved for the session is taken back in the chat's *Your changes* at its end.

**4.5.0** (about 20 minutes of the owner's):

1. In the chat, ask Felix to put a piece at a time outside the day's windows (after the evening ends). The Day's list shows it there, to the minute; the calendar's event is there (ghostcheck).
2. In Google Calendar, move a piece's event onto a busy event of yours. No "refused" notification; the Day keeps it there, and a piece of another task that was there moves.
3. With the move sheet, put piece A at a time, then piece B of another task over it. B is at its time; A after it.
4. For a task with steps laid today, put its next piece after its later steps' times. They move after it.
5. Step 1's piece, outside the windows: the day's other work is as it was before it, and the strip's bar for the day shows its free time more by that piece's minutes.
6. Take each back in *Your changes*; the plan is as it was.

**4.5.1** (about 15 minutes of the owner's):

1. In the chat, ask Felix to make a day this week only for one task. He makes it and asks what else may come in, with three quick replies.
2. Tap "Anki too". On that day, only that task's work and Anki's cards; the rest of the day's work gone to other days; the time left free.
3. Ask him to put all of that task's work on that day: it's there, first.
4. With the move sheet, put another task on that day: it's there, and the snackbar says it joins.
5. Take each change back in *Your changes*; the plan is as it was.

**4.6.0** (about 45 minutes of the owner's), with Google Calendar's Day view open on the same day for comparison:

1. Open the Day: it opens an hour before now, today's line across; the windows' bands and the shading; events in their colours. Screenshots of both apps, dark mode on as the phone keeps it (a light pair needs `cmd uimode night no`, then `yes` again).
2. Pinch to zoom in and out; switch tabs and back, then leave the app and come back: the same zoom.
3. Tap a piece (its sheet), an event (its sheet), an all-day chip (its sheet), each of the header's chips (its sheet).
4. Swipe a piece right to left: Done, then Undo. Left to right: later today.
5. Scroll the grid down and up: the DO NOW card folds and unfolds; a tap on the folded line unfolds it.
6. Hold the next piece of a task and drag it: to a free time; over an event; past the evening's end; near the grid's bottom (it scrolls). Each stays where it was let go. Then past its real deadline: refused, said, sprung back.
7. Hold a later piece of a task: let go on the grid, it springs back with its words; let go on tomorrow's chip, all of the task goes to tomorrow. A day after its deadline is dimmed while dragging.
8. On a weekend's day, hold a break outside: let go at another time, it stays there, "Outside at …"; hold it again and let go on a day of the strip, it springs back with its words. A break under way doesn't lift.
9. Take each move back in *Your changes*.

TalkBack's actions aren't checked on the phone (turning TalkBack on changes the accessibility settings the phone is kept with); they're read in the code's review.

## Not in these releases

- Google's 3-day, week, month and schedule views, and swiping between days (the owner kept the swipes on the pieces: `answers.md`, Q2).
- A tap on empty grid making anything (Q8).
- Resizing a block: a piece's length is the planner's or the task's minutes (D-4), not a time to drag.
- Moving events: they're the calendar's own.
- A time for a piece that isn't its task's next (Q9): it would need a time per piece in the stored changes.
