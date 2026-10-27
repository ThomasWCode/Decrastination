# Breaks outside: kept once begun, and moved by Felix and the Day (4.4.1, 4.5.2 and 4.6.0's drag)

The owner's ask, 26 Oct 2026: why the "Outside" events in the calendar keep being pushed back while they're under way; then a fix planned, with Felix able to control breaks outside as he controls tasks, and a break never dragged between days.

This file is what a fresh session needs to build it, without the conversation that planned it: the bug, the owner's answers, the two releases and the day grid's part, each stage with its files, rules, tests and an estimate, and the choices made without asking. Where this file and the code disagree once the releases are merged, the code and `docs/spec/` are right: this is the plan's record.

## Two releases, and a part of 4.6.0

- **4.4.1: a break outside kept once begun.** The planner and the slots' keeping alone: no stored shape changes, no prompts, no goldens. Branch `v4.4.1`, `versionCode` 79, `versionName` "4.4.1"; built before the day grid, whose numbers each go one up.
- **4.5.2: Felix moves, skips and adds breaks outside.** After 4.5.1 (the day grid's), whose stores' versions and goldens change first. Branch `v4.5.2`, `versionCode` 82, `versionName` "4.5.2".
- **4.6.0's drag** takes a break up and down its day: written into [`docs/plan_day_grid/`](../plan_day_grid/README.md), §Moving a piece by hand.

The rules of the house hold throughout: `CLAUDE.md`, `docs/development.md`, `docs/plan_v4/architecture.md` §The rules and §Conventions. Nothing personal in code, tests, fixtures, docs or the PRs (D-79): fixtures and examples are made up. No new library, so there's nothing to install. Before the first edit of each release: `PROGRESS.md` from `private/tools/progress.py init`, one item a stage below with its estimate, kept current and deleted before the PR is called done; never staged.

## The bug

On a weekend or a day off, a break outside whose time has gone by is laid again from now at every plan (`Slotter.slot`, its outside pass: a day's free time starts at `onFive(now)`, and each break at `maxOf(interval.start, its time)`). Nothing remembers it was laid: `Planning.keepSlots` keeps only work slots' starts (`SlotKind.Work`), and a break's key is `"outside:$start"` (`Slot.key`), which changes as it moves. So:

- a break under way slides to the next five minutes at every plan, its calendar event deleted and written again, till it can't be over by the next break's time (15:40 for the 13:00 one), when it goes;
- a break already taken slides on all the same, holding 20 minutes the work could use;
- work after it can move too, as a kept start it now covers fails `holding` and is laid afresh.

The spec states the rule (`docs/spec/slots-and-calendar.md` §Outside breaks: "laid from now") and `SlotterTest` holds it (*on a free day under way, a break outside whose time has gone isn't laid after now*). It was meant for a break no plan had laid, the first plan of the day made after its time; it can't tell one from a break under way, as a break has no name from one plan to the next.

## The owner's answers (26 Oct)

**A break that's over.** The ask:

> After the break is over, it should do the same as any other task event: either stay or drop out.

and, asked what that meant:

> When I said 'breaks should work how tasks work', I did not mean they can be marked done/missed. After their time as passed, they are completed.

Applied: a break is completed at its end. A completed task's event drops out (a kept piece leaves the plan, `SlotState.kept` lets go of its start, and `SlotCalendarDiff.desired` writes only the plan's slots): a break does the same, gone from the Day and the calendar at its end, and never laid again that day (§4.4.1).

**Q1. Which controls over breaks outside should Felix get?** Offered: move one to a time on its day (recommended); skip one or all on a date (recommended); add one on any day, a school day too; change one day's length.

> Move one to a time, Skip one or all, Add one on any day

Applied: §4.5.2. One day's length isn't among them: the setting `outsideBreakMin`, which Felix can already change, sets every day's.

**Q2. And your own control of breaks, beside Felix's?** Offered: a break's event moved in Google Calendar on its day taken as your move, and the 4.6.0 grid's drag within its day (recommended); the grid's drag only, a calendar move still put back, now with a notification; the calendar only; only Felix.

> Grid drag only

Applied: §4.5.2 (a calendar move put back and said) and the day grid's plan (the drag). In the ask: "you should not be able to drag outside events between days".

**Q3. How should this ship, around the timer (4.4.0) and the day grid (4.5.0 to 4.6.0)?** Offered: the fix alone now as 4.4.1, Felix's changes as 4.5.2 after 4.5.1, the drag in 4.6.0 (recommended); Felix's changes inside 4.5.1; all in one, 4.4.1.

> Fix now, Felix later (Recommended)

Applied: §Two releases.

## 4.4.1: a break outside kept once begun

**Built** in 4.4.1 (D-113). Found while building, beyond the stages below: the start alarm (`SlotAlarms.next`) rings at a break's start and end too, so a completed break leaves the calendar at its end, not at the next plan something else makes; the breaks begun are read from the kept starts by their keys, whatever `outsideTimes` now holds, so a break under way stays though its time is taken out of the settings.

Read first, in full: `core/Slotter.kt` (`slot`, its outside pass and `choices`, `intervals`, `onFive`), `core/Plan.kt` (`Slot`), `core/SlotState.kt`, `plan/Planning.kt` (`keepSlots`), `calendar/SlotCalendarDiff.kt` (`desired`, `diff`), and their tests (`SlotterTest`, `SlotStateTest`, `PlannerTest`, `SlotCalendarDiffTest`, `CalendarWriterTest`, `PlanSnapshotTest`).

### 1. Each break a name of its own (15 min)

- `core/Plan.kt`: `Slot.outsideAt: Int = -1`, KDoc "A break outside's own time, its `outsideTimes` minute (from 4.5.2, an added one's): -1 for work." `Slot.key` becomes `chunk?.key ?: "outside:$outsideAt"`: `outside:780` is the 13:00 break wherever it's laid.
- `Slotter.slot` sets it on each break it makes.
- The calendar: each break's URI is `decrastination://slot/<date>/outside:780` from then on. The first write after the install finds the old URIs (`outside:<millis>`) wanted by no slot and deletes them, inserting the new ones, as `SlotCalendarDiff.diff` already does: a week's breaks written again, once. `CalendarWriterTest`: an event under an old URI deleted, its new one inserted.

### 2. Kept once begun, completed at its end (40 min)

`Slotter.slot`'s outside pass, for each break (`outsideTimes`, sorted, as now), by its start kept from the last plan, `previous["outside:<minute>"]` (today's only, as for work):

- **Under way** (its kept start at or before now, its end after it): laid at its kept start, whatever the free time and whatever the settings now say, and never given up for work: laid before `found`, the fold (`choices`) and its cap, its time carved first and out of `room`. The early return (`!outside || outsideBreaks <= 0`) covers only the breaks still to come.
- **Completed** (its end at or before now): not laid, and never laid again that day. No grace: grace is for a piece still to tick, and a break has nothing to tick.
- **Still to come** (its kept start after now): laid afresh as now, at or after its time. Its time doesn't move, so it doesn't drift, and busy time or the day's work may have changed since.
- **None kept, its time gone** (no plan laid it: the phone off, the day's first plan made after its time, or given up for work then freed): laid from now as now, while it can be over by the next one's time; kept from then on.
- **The count**: `outsideBreaks` counts the day's breaks begun, under way or completed. The fold chooses among those still to come, at most `outsideBreaks` less those begun, so a day never has more breaks than the setting says: with it at 1, the morning's completed, no afternoon one is laid.

`plan/Planning.keepSlots`: the starts kept are today's slots', breaks too (`made.todaySlots.associate { it.key to it.start }`), and with them each break's start from the last `previous` that has begun and is no longer in the plan (completed), so the next plan knows it's done. A new day starts empty, as now (`SlotState.on`).

A break's key in `previous` is safe where it's read: `SlotState.released` (by a task's `"$taskId#"`, which no break's key starts with), `SlotState.kept` and `missed` (work keys only), `reconciled`, the planner's kept day (`Why.Kept`, by a chunk's key), `Slots.onEnd` and `Slots.sweep` (work slots only); check each again while building. `SlotState`'s shape doesn't change, so no version is raised; an older build reading the new keys matches none of its chunks. `PlanWatcher` makes the plan again when today's starts change: once more as a break is first kept, then nothing changes.

### 3. Tests (25 min)

- `SlotterTest`: the 13:00 break laid at 12:58, then planned at 13:02, 13:11 and 13:19 with its kept start: 13:00–13:20 each time; at 13:20 and after, not laid, and not from now; a break under way kept though the work would give it up; one still to come laid at its time, not its kept start, once busy time moves it; none kept and its time gone, laid from now (the test that holds that now, its key changed); its key `outside:780` wherever it's laid; with `outsideBreaks` at 1, the midday break under way kept and no other laid, and the morning's completed leaving no other; with it at 0, a break under way kept.
- The keeping: a completed break's start carried over by `keepSlots` (wherever its test reaches it: a `SlotState` function for it, if that's simpler to test); a new day empty.
- `PlannerTest`: a Saturday planned at 12:58, 13:02, 13:11 and 13:25, each with the last plan's `previousSlots`: the break where it was, then gone, the work after it unmoved.
- `SlotCalendarDiffTest`: a break's URI by its own time.
- `PlanSnapshotTest`: where its made-up state has breaks, their keys change; written again (`-PupdateSnapshots=true`) only once the diff is understood, the diff explained in the PR.

### 4. The release (~10 min of docs, ~4 of CI and the PR, ~45 of reviews)

- `app/build.gradle.kts`: 79, "4.4.1".
- Docs, in its PR: `docs/spec/slots-and-calendar.md` §Outside breaks (the four cases in place of "laid from now"), §Kept starts (breaks' starts kept too), §Writing the calendar (a break's URI by its own time); `docs/decisions.md`, the next number, *A break outside is kept once begun, and completed at its end*, with the date, the owner's answer and where it applies (`Slotter.slot`, `Planning.keepSlots`, `Slot.key`); this file's 4.4.1 marked built.
- CI's command on the branch, the diff scanned (`scripts/scan-public.sh`), the PR, the reviews the owner asks for.
- The phone: today made a day off (a `NoSchool` change of the owner's in the chat), so today has breaks. Read the plan (the Day) and the calendar's "Outside" event before a break's time, a few minutes into it and after its end: the same start throughout, then gone, the work after it unmoved. The change taken back in *Your changes* after. Nothing of the owner's needed but the change and its undo.

## 4.5.2: Felix moves, skips and adds breaks outside

Read first, in full: what 4.5.1's read-first list names (`core/Instructions.kt`, `core/Chat.kt`, `enrich/InstructionAnswers.kt`, `enrich/InstructionPrompts.kt`, `felix/ChatPrompts.kt`, `felix/ChatAnswers.kt`, `felix/FelixPrompts.kt`, `data/Backup.kt`, `data/Stores.kt` and their tests), with `core/Slotter.kt` as 4.4.1 and 4.5.0 left it, `calendar/SlotCalendarDiff.kt` and `calendar/CalendarWriter.kt` (the hand moves), and `calendar/SlotCalendar.kt` (`sayRefused`).

### 1. The changes, and the stores that hold them (30 min)

- `core/Instructions.kt`, `ChangeType`, each on a date, never a weekday:
  - `OutsideAt`: [Change.date]'s break of [Change.outsideMin] (its own time) at [Change.startMin];
  - `NoOutside`: [Change.date]'s break of [Change.outsideMin] not laid; -1, none of the day's;
  - `OutsideAdded`: a break at [Change.startMin] on [Change.date], whatever kind of day.
- `Change.outsideMin: Int = -1`.
- `core/Plan.kt`: `Slot.outsideAdded: Boolean = false`, a break an `OutsideAdded` made; `Slot.key` gives it `outside+:<minute>`.
- New enum values and a field: `instructions.json`, `chat.json` and the archive's months each raised one more, as 4.5.1 raises them (`data/OutsideMigration.kt`, migrations changing nothing, `docs/plan_v4/data-and-migrations.md` §Versions and migrations, rule 6). `data/Backup.kt`'s checks of a restored change: each with a date, `OutsideAt` and `OutsideAdded` with a minute of the day.
- `InstructionRules.describe`: "Sat 31: the 13:00 break outside at 14:30", "Sat 31: no 13:00 break outside", "Sat 31: no breaks outside", "Tue 27: a break outside at 17:00".
- Tests: `MigrationTest`'s chains; `StoresDecodeTest` with a new `fixtures/stores/<date>-4.5.2/`; the backup's checks; `describe`.

### 2. The planner (45 min)

- `InstructionRules.outsideDays(applied, from, to): Map<LocalDate, OutsideDay>`, beside `dayCaps`; `OutsideDay(moved: Map<Int, Int>, off: Set<Int>, noneAtAll: Boolean, added: List<Int>)`, the latest change for a break winning. `Planner.Input.outsideDays`, from `Planning`; `Slotter.slot` takes the day's.
- In the outside pass:
  - a break that's off isn't laid;
  - one moved is laid at its time to the minute, as 4.5.0 keeps pins: over busy time or outside the windows kept, the work laid around it;
  - one added is laid so too, on any day (`outside` false lays only the added ones), keyed `outside+:<its startMin>` (`Slot.outsideAdded`), so it never shares a key or a calendar URI with a settings break; one at a minute the day's settings breaks have (`outsideTimes` restored from a backup that differs, say) isn't laid, the day already having a break then;
  - moved and added breaks are laid before `found`, the fold and its cap, as 4.4.1 lays a break under way, and the early return covers only the settings' breaks still to come, so with `outsideBreaks` at 0 they're laid all the same;
  - a moved break is one of the settings' breaks, so it counts towards `outsideBreaks` (the fold choosing among the rest, fewer by it); an added one is over and above them, and doesn't;
  - a break you or Felix placed is never given up for work (D-4), and the 18:00 of D-41 is the app's own rule, not his;
  - over a pin, the one given last keeps its time, as 4.5.0's pins between themselves (`Input.pinOrder` counts the breaks' changes too).
- 4.4.1's four cases hold for every break: a moved one under way stays, one completed drops out.
- Tests (`SlotterTest`, `PlannerTest`, `InstructionRulesTest`): each change; the latest for a break wins; a moved break kept over an event and after 18:00; never given up; an added one on a school day; with `outsideBreaks` at 0, a moved and an added break laid; with it at 1 and the morning's moved, no other settings break; an added one at a settings break's minute not laid; one off, its time to work; each taken back, the day as it was; with none, plans as they were (`PlanSnapshotTest` unchanged).

### 3. Felix, the instruction reader and the calendar (45 min)

- The three in `InstructionPrompts.CHANGE_TYPES` (both prompts share it), in its style. In substance: "OutsideAt: one of a day's breaks outside at a time that day, outsideTime naming it by its own time (\"13:00\"). NoOutside: a day's break outside not taken, outsideTime empty for all of the day's. OutsideAdded: a break outside at a time on a date, any day. Each with a date; a break stays on its day."
- The schemas (`chat.schema.json`, `instruction.schema.json`): the three in the change's type enum; `outsideTime` a plain "HH:MM" string, empty for none, never nullable (`SchemaLimitsTest`).
- `InstructionAnswers.change`: a date, not a weekday (left out, said); the break `OutsideAt` or `NoOutside` names one that day has (its `outsideTimes` on a weekend's kind of day, or an added one), else left out ("there's no 13:00 break outside that day"); a time today not gone; an added one at a break's time, or a move over another of the day's breaks, left out, said.
- What he's told: today's breaks among today's slots in `ChatPrompts.plan` ("13:00–13:20 Outside, the 13:00 break"; moved, "the 13:00 break, moved by you" or "by Felix"; completed ones not listed), and the week's later days' break times on their lines; today's in `FelixPrompts.describe` too, so a look of his knows a break is under way.
- A break's event moved in Google Calendar, in two places: `SlotCalendarDiff.diff`, which is pure and only finds moves, loses its `want.taskId != null` guard, so a moved break comes out a `HandMove` and not an update; and the `refuses` lambda `CalendarWriter.writeSlots` hands `write` checks `move.desired.taskId == null` first, refusing it with "a break outside is moved by asking Felix" (not "its task is no longer there", which a break with no task would be told otherwise). Written back, `SlotCalendar.sayRefused` says so, as a refused piece's move is said now; the loop over the moves taken skips a break already (`taskId ?: continue`), and none is taken.
- The goldens regenerated (`-PupdateGolden=true --tests '*GoldenPromptsTest*'`: `chat.txt`, `chat-gmail-off.txt`, `chat.schema.json`, `instruction.txt`, `instruction.schema.json`), their diff in the PR; no reading's prompt changes, so `ReadingPrompts.VERSION` stays. Every schema compiled by the API (`./gradlew testDebugUnitTest --tests '*SchemaCompileTest*' -PcheckSchemas=true`: about a cent a schema). `FelixInputsDocTest`: its made-up Saturday given a moved break, the page written again.
- Tests: `InstructionRulesTest` and `ChatTest` (each change parsed and kept; a weekday alone, an unknown break, a time gone and an overlap left out, their words); `ConversationTest` through `FelixHarness` (free, the API a stand-in): "no midday break on Saturday" answered with a `NoOutside` makes it; `SlotCalendarDiffTest`: a break moved by hand is a `HandMove`; `CalendarWriterTest`, where the lambda is: it's refused with its own words, written back, and no change made.
- At the owner's word only, a throwaway probe of a few chat turns ("move Saturday's 13:00 break to 14:30", "no breaks on Sunday", "a break at 17:00 tomorrow") through the real prompt and `ChatAnswers`: a handful of calls, a few cents.

### 4. The release (~10 min of docs, ~5 of CI and the PR, ~80 of reviews)

- `app/build.gradle.kts`: 82, "4.5.2".
- It migrates stored data: `scripts/rehearse.sh` on a recent backup of the phone's before the PR is called ready.
- Docs, in its PR: `docs/spec/felix.md` §What he can do; `docs/spec/felix-inputs.md`, as `FelixInputsDocTest` writes it; `docs/spec/slots-and-calendar.md` §Outside breaks (yours and Felix's) and §Hand moves (a break's put back, and said); `docs/spec/requirement-ids.md`, beside PL-04; `docs/plan_v4/data-and-migrations.md` §The shapes, release by release; `docs/decisions.md`, the next number, *Felix moves, skips and adds breaks outside*; this file's 4.5.2 marked built.
- CI's command on the branch, the diff scanned, the PR, the reviews the owner asks for.
- The phone (about 10 minutes of the owner's): ask Felix to move Saturday's 13:00 break to 14:30, to skip its 16:00 one, and to add one on a school day: the Day and the calendar show each; move a break's event in Google Calendar: put back, and the notification says why; take each back in *Your changes*: the plan as it was.

## Decided without asking

Each a routine call the owner may overturn; the alternative is named so the build can switch.

1. **Completed at its end, with no grace.** Alternative: a piece's 10 minutes' grace (`Slotter.GRACE_MS`) before it drops out.
2. **A break still to come isn't held at its kept start**, but laid afresh at each plan, as its own time doesn't move. Alternative: kept like a work piece's start, where it's still free.
3. **A break no plan laid before its time went by is laid from now**, then kept. Alternative: not laid once its time has gone.
4. **Each break named by its own time** ("the 13:00 break", key `outside:780`), not by morning, midday and afternoon; a change naming a time the settings no longer have applies to nothing, and *Your changes* still lists it to take back. Alternative: by its place in the day.
5. **A date, not a weekday**, for Felix's changes, as the day grid's only-days (its decided-without-asking 19). Alternative: a weekday too, as `DayLimit` takes one.
6. **Breaks you or Felix placed are kept as set (D-4)**: never given up for work, and after 18:00 too. Alternative: D-41's 18:00 for every break.
7. **The instruction reader makes them too**, as it shares the change list. Alternative: Felix's alone.
8. **Felix sees today's breaks**, in the chat and in his own messages, and the week's break times in the chat. Alternative: none, and he moves a break by its settings' time unseen.
9. **The calendar's put-back is said from 4.5.2**, when its words can say what to do instead; until then it stays silent, as now. Alternative: said in 4.4.1.

## Done when

- Every behaviour above holds on the phone (§4.4.1 and §4.5.2, the release's phone check).
- CI green on `./gradlew testDebugUnitTest lintDebug assembleDebug` for each; 4.5.2's migrations tested from every earlier shape and rehearsed on a backup of the phone's; the goldens changed only in 4.5.2, on purpose, and its schemas compiled by the API; the plan's snapshot changed only by the breaks' keys in 4.4.1, its diff explained.
- The docs moved with the code (each release's §The release), the new decisions numbered in `docs/decisions.md`.
- Merged as `4.4.1: a break outside kept once begun` and `4.5.2: Felix moves, skips and adds breaks outside`, tagged, at the owner's word.
