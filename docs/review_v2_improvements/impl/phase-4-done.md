# Phase 4: sessions out, slot outcomes in, the block rules

Items: AR-09, LR-09, BL-08, BL-01, BL-02, BL-04, BL-07, BL-10, BL-11, NF-03, DX-11. BL-09 (the phone at school) was settled as "no change": `isBeforeBlockStart` keeps allowing blocked apps on school days before `weekdayBlockFromMin`; nothing to build. Depends on phase 3 (slots exist). Order inside the phase: LR-09 and BL-08 first (the new record and credit), then AR-09 (removal), then the block rules, then Anki.

Settings added (LOOSER lambda in brackets; all need LABELS and limits):

| Field | Default | LOOSER |
|---|---|---|
| `weekendBlockFromMin: Int` | `10 * 60` | `{ old, new -> new.weekendBlockFromMin > old.weekendBlockFromMin }` |
| `freeAllowanceMin: Int` | `60` | `{ old, new -> new.freeAllowanceMin > old.freeAllowanceMin }` |
| `photoFromMin: Int` | `20` | `{ old, new -> new.photoFromMin > old.photoFromMin }` (a higher line means more pieces earn on a tick) |

---

## LR-09 Slot outcomes as the record

**Change.** A kept, missed or moved slot is a `SlotRecord` in the log; a tick keeps a slot and adds its minutes to the task; the calibration, day records and stats read slots where they read sessions.

**Files.** `data/RuntimeState.kt` (`ActivityLog`), `block/Focus.kt`, `learn/Days.kt`, `learn/Calibrator.kt`, `learn/Stats.kt`, `learn/ReviewInput.kt`, `AppGraph.kt`.

**Steps.**

1. ```kotlin
   @Serializable enum class SlotOutcome { Kept, KeptLate, Missed, Moved }
   @Serializable data class SlotRecord(
       val date: String, val key: String, val taskId: String, val kind: Kind, val className: String? = null, val label: String,
       val plannedMin: Int, val start: Long, val end: Long, val window: WindowKind, val outcome: SlotOutcome,
       val workedMin: Int, val photo: Boolean = false, val earned: Boolean = false, val at: Long,
   )
   ```
   `ActivityLog.slots: List<SlotRecord> = emptyList()`, trimmed by `at`.
2. `Focus.keepSlot(key: String, extraMin: Int = 0, photo: Boolean = false, now: Long = clock.now()): Boolean`:
   - find the slot in `plan(now)` by key (today's, else any day's: a piece ticked ahead of its slot is still kept); the chunk is `slot.chunk`;
   - mark done: if the chunk has `stepIndex` → `tickBlock(taskId, stepIndex, step)` generalised to every source (`Focus.tickable` → `task.subSteps.isNotEmpty()` for the step path); the step's `timedMin = plannedMin + extraMin` so `pieces()` takes its minutes off; if no step → `addWorked(taskId, chunk.minutes + extraMin)` (new: `tasks.update` adding to `workedMin` and recording in `extra["slotsKept"]` the key so the same slot can't be kept twice);
   - for an own task whose steps are now all done, or whose remaining minutes reach 0 → `graph.finishOwnTask` (pass a callback as `Focus` can't see `AppGraph`: `onOwnTaskDone: suspend (String) -> Unit` constructor parameter);
   - append the `SlotRecord` with `outcome = if (now <= slot.end + GRACE_MS) Kept else KeptLate`, `workedMin = plannedMin + extraMin`;
   - credit per BL-08 (below); `earned` on the record;
   - remove the key from `runtime.slots.previous` and `missed`;
   - return true; false when the key isn't in the plan (already done or gone).
3. `Focus.missSlot(key, now)` (called by `Slots.onEnd` in PL-13): append `SlotRecord(outcome = Missed, workedMin = 0)`; `Focus.moveSlot(key, now)` for deliberate moves (`Moved`). Both no-ops when a record for that key and date exists.
4. `Days.workedIn(log, start, end)`: sum `SlotRecord.workedMin` by task for records with `start in [start, end)`; `Days.finish`/`replan` unchanged otherwise. `Days.record` gains `kind` (phase 2) and, new, `DayRecord.schoolMin: Int` (planned minutes in school windows) so the School yield can be learned: `Calibrator.yields` computes `School` from `slots` records with `window != Evening` (kept ÷ planned by day, median over 14 days with any school slot).
5. `Calibrator`: `isTimed(record)` → `record.workedMin > 0`; `multipliers` unchanged otherwise (`CompletionRecord.workedMin` is `task.workedMin` at completion, which `keepSlot` fed). Delete `boxes()` and the box-experiment lines in `describe`; `Calibration.boxMin` stays as a field (old data) but `learn` writes `emptyMap()` and `pieces()` ignores it (uses `settings.boxMin`).
6. `AppGraph.workedToday` → slots kept today (`log.value.slots` with `date == today` summing `workedMin`).
7. `Stats.summary`: `focusMin` → `slotMin` (kept slots' worked minutes), `sessions`/`sessionsFinished` → `slotsPlanned`/`slotsKept`; `Day.focusMin` → `slotMin`. `StatsScreen`'s tiles: "Slots kept: 14 of 19" with "earned 9" beside.
8. `ReviewInput.describe`: the "sessions by hour" section becomes "slots kept and missed, by hour and day".

**Tests.** `FocusTest` (rewritten around slots): `keeping a slot ticks its step, records it and adds its minutes`; `a slot kept after its grace is KeptLate`; `a slot can't be kept twice`; `an own task whose last step is kept is finished`; `a missed slot is recorded once`. `DailyTest`/`LearnTest`: `workedIn` from slots; the School yield from slot records. `StatsTest`: the new tiles' numbers.

**Done when.** Ticking a piece on the Plan tab writes a `SlotRecord` and the day's done minutes rise on Stats.

---

## BL-08 What earns free time

**Change.** A kept slot of a piece under `photoFromMin` earns on the tick; a longer piece earns when its photo check passes; credit is capped per day at the day's planned minutes.

**Files.** `block/Credit.kt`, `block/Focus.kt`, `block/PhotoChecks.kt`.

**Steps.**

1. `Credit.forSlot(minutes: Int, workMinPerFreeMin: Int): Double = minutes.toDouble() / workMinPerFreeMin.coerceAtLeast(1)` (replaces `forSession`). `Credit.earn(today, minutes, capMs: Long)`: `earnedMs = min(earnedMs + minutes * 60_000, capMs)`. The cap passed by `Focus` is `plan.todayBucket.plannedMin * 60_000 / ratio` plus whatever `forCompletion` and photos add (so the cap is "today's plan, not more").
2. In `keepSlot`: `if (chunk.minutes < settings.photoFromMin) earn(forSlot(plannedMin)) and earned = true else earned = false` (the record says so).
3. `Focus.finishPhoto` (existing path from `photoChecked`): when the piece's slot record exists with `earned = false` → earn `forSlot(record.plannedMin)` now and set `earned = true`, `photo = true` on the record; when no slot record exists yet (photo before the tick) → `keepSlot(key, photo = true)` which earns at once because `photo` is passed. The photo credit's existing cap (`Planner.remaining`) stays.
4. `PhotoChecks.check` unchanged; `PieceActions` "Photo check" stays (phase 6 relabels it).

**Tests.** `FocusTest`: a 15-minute piece earns 5 on its tick; a 30-minute piece earns nothing on its tick and 10 when its photo passes, once; a photo before the tick keeps and earns; the day's credit never exceeds its planned minutes ÷ ratio.

**Done when.** The reward line (BL-04) says which pieces need a photo, and the credit moves as the tests say.

---

## AR-09 Removing sessions safely

**Change.** Delete the focus-session feature; keep old records readable.

**Files.** Delete `block/Sessions.kt`. Edit `block/Focus.kt`, `block/BlockPolicy.kt`, `block/FocusService.kt`, `block/BlockedActivity.kt`, `block/PhotoChecks.kt` (nothing: it never used sessions), `ui/PieceActions.kt`, `ui/TodayScreen.kt`, `widget/WidgetModel.kt`, `widget/NextWidget.kt`, `enrich/ChatReader.kt`, `learn/Calibrator.kt`, `learn/Stats.kt`, `learn/Days.kt`, `learn/ReviewInput.kt`, `data/RuntimeState.kt`, `notify/Channels.kt`, `AndroidManifest.xml`, `AppGraph.kt`, tests, `docs/scheduler.md` §4–5, `README.md`.

**Steps.**

1. Migration first: in `AppGraph.init`, if `runtime.value.session != null`, append a `SessionRecord(completed = false, workedMin = ran minutes)` to the log for it and clear `session`; clear `finishing`. Keep `RuntimeState.session: FocusSession? = null` and `finishing: List<EndedSession>` as `@Deprecated("cleared at start-up; removed in the next release")` fields so old files still load; keep `FocusSession` and `EndedSession` data classes for that reason only.
2. Delete, in this order, compiling after each: `Sessions.kt` and the manifest's `SessionReceiver`; `Focus.startSession`, `stopSession`, `ending`, `finishSessions`, `earnsNow`, `session`, `MAX_SESSION_MIN`; `BlockPolicy.Input.session` and `Reason.Session` and the session clause in `decide`; `FocusService.tick`'s session line; `PieceActions`'s Start/Stop buttons and `starting`; `TodayScreen.SessionLine` and `NextCard`'s session parameter; `BlockedActivity`'s session text and `pieceOf(session…)`; `WidgetModel.from`'s `runtime`/`uptime` session use (keep the parameters' other uses); `ChatPrompts.plan`'s `session`/`sessionLeftMin` parameters and the "doing" line (replace with the current slot: "Now: 17:00–17:30 Statics Prep"); `AppGraph.answerChat`'s session arguments; `Credit.forSession`; `Calibrator.boxes` and `BOXES`/`BOX_SETTLED`; `Stats`' session tiles (done in LR-09); `Days.minutesIn(SessionRecord…)` once nothing calls it (Stats may still read old `sessions` for days before the switch: keep `minutesIn` and read both lists in `Stats.summary`, sessions for dates before `log.slots.minOf { it.date }`); `Channels.SESSION` (delete the channel at start-up as `DAILY` was deleted); `CommandActivity`'s session commands if any.
3. `debug/CommandActivity`: no session commands exist; nothing to do.
4. Tests: delete the session tests in `FocusTest` (names listing "session"), `BlockingTest`'s session cases, `WidgetModelTest`'s session lines, `LearnTest`'s box tests; rewrite the credit tests around slots (BL-08).
5. `docs/scheduler.md` §4: blocking without sessions; §5: the box experiment retired, slot outcomes as the signal. `README.md`: remove the session feature from its lists.

**Tests.** The suite compiles and passes with the deletions; `StoresTest`: a `runtime.json` with a running session loads and is cleared.

**Done when.** No `Start` button anywhere; `grep -rn "FocusSession\|startSession\|Sessions\." app/src/main` shows only the deprecated fields and the migration.

---

## BL-01 Free time spendable once today's plan is done

**Change.** The block's pressure is today's undone slots (and BL-11's missed ones), not today and tomorrow.

**Files.** `core/Plan.kt` (done in phase 2: `pressure = todayBucket chunks non-empty`), `block/BlockPolicy.kt`, `block/Focus.kt`, `docs/scheduler.md` §4.

**Steps.**

1. `BlockPolicy.Reason.DueSoon` → rename `TodayUndone("Today's work isn't done")`; `Input.pressure` is computed in `Focus.verdict` as `plan.pressure || missedKeepsPressure(now)` (BL-11).
2. `docs/scheduler.md` §4 rule 1: "Blocked apps are blocked while today's slots have pieces still to do, or a slot missed today was pushed to tomorrow (until the hours end)."

**Tests.** `BlockingTest`: tomorrow's work alone doesn't block; today's does.

**Done when.** With today's slots all kept and tomorrow's full, a blocked app opens (if credit) at 20:00.

---

## BL-02 A weekend "block from" time

**Files.** `data/Settings.kt`, `block/BlockPolicy.kt`, `protect/SettingsChanges.kt`, `ui/SettingsActivity.kt`.

**Steps.** `isSchoolHours` → `isBeforeBlockStart(now, zone, settings)`: weekday → `minute < weekdayBlockFromMin`; weekend or no-school day (pass `isSchoolDay` in: `Input.schoolDay: Boolean`) → `minute < weekendBlockFromMin`; quiet hours excluded as now. `Reason.SchoolHours` → `BeforeBlock("Before blocking starts")`. Settings row under Blocking.

**Tests.** `BlockingTest`: Saturday 09:00 allowed, 10:30 blocked when today has work.

**Done when.** The rule holds on a Saturday and on a no-school weekday.

---

## BL-04 The reward visible at the point of temptation

**Files.** `block/Focus.kt`, `block/BlockedActivity.kt`, `ui/TodayScreen.kt` (`NextCard`), `widget/WidgetModel.kt`.

**Steps.** `Focus.rewardLine(plan: Plan, now: Long): String?`: today's undone work slots, their minutes, the credit they'd earn (`forSlot` for pieces under `photoFromMin`; "with a photo" for the rest), e.g. "Finish today's 2 slots (50 min) → 15 min free now, 10 more with a photo of the statics". Null when today is done ("Today's plan is done. 25 min free."). Shown on the block screen under the headline, on the DO NOW card, and as `WidgetModel.reward: String?` (a line under the header). Phase 5 puts it in Felix's day-done message.

**Tests.** `FocusTest`: the line's three shapes (nothing left; short pieces; a long piece).

**Done when.** The block screen shows the computed line.

---

## BL-07 A day with nothing to do

**Files.** `block/Credit.kt`, `block/Focus.kt`, `data/Settings.kt`.

**Steps.** `Credit.allowanceOn: String = ""` (the date the allowance was given). In `Focus.creditLeftMs(now)`: if `plan(now).todayBucket.chunks.isEmpty()` and no overdue chunk anywhere and `credit.allowanceOn != today` → `runtime.update { credit = credit.on(today).copy(earnedMs = earnedMs + freeAllowanceMin * 60_000, allowanceOn = today) }` (do this in a suspend path: `Focus.grantAllowanceIfDue(now)` called from `FocusService.tick` and app start, not inside the non-suspending `creditLeftMs`). Shown on the DO NOW card: "Nothing planned today: 60 min free."

**Tests.** `FocusTest`: granted once a day; not when anything is planned or overdue.

**Done when.** An empty day opens with 60 minutes.

---

## BL-10 Free time between today's slots

**Files.** `block/BlockPolicy.kt`, `block/Focus.kt`, `block/FocusService.kt`, `core/Plan.kt`.

**Steps.**

1. `Plan.gapUntil(now: Long, kept: Set<String>): Long?`: when `now` is after the end of a work slot whose key is in `kept` (today's records with Kept/KeptLate), and before the start of the next work slot today → that start; else null.
2. `BlockPolicy.Input.gapUntil: Long? = null`; in `decide`, after the override and before-block checks: `if (input.gapUntil != null && input.creditLeftMs > 0) return Verdict.Spend`. Then `pressure → Block(TodayUndone)` as now.
3. `Focus.spendableUntil(now): Long?` = `min(now + creditLeftMs, gapUntil ?: Long.MAX_VALUE)`; `FocusService.startSpending` uses it for the countdown end and re-blocks at the next slot's start (the `creditCheck` runnable already re-evaluates the verdict).

**Tests.** `BlockingTest`: in a gap after a kept slot with credit → Spend; at the next slot's start → Block.

**Done when.** After ticking a slot, a blocked app opens for the earned minutes and closes when the next slot starts.

---

## BL-11 A missed slot keeps the block on; a deliberate move doesn't

**Files.** `block/Focus.kt`, `core/SlotState.kt`.

**Steps.** `Focus.missedKeepsPressure(now): Boolean` = `runtime.slots.on(today).missed.any { it.spilled }` and `now` before today's last window's end. `Focus.verdict` passes `pressure = plan.pressure || missedKeepsPressure(now)`. Deliberate moves clear the entry (PL-13 step 5). `Reason.TodayUndone`'s headline covers both; the block screen's "why" text adds "A slot you missed went to tomorrow, so tonight stays blocked." when that's the reason (`Focus.whyBlocked(now): String`).

**Tests.** `BlockingTest`: a spilled missed slot blocks until the hours end; an `AtTime` move for the same task clears it.

**Done when.** Letting a slot lapse into tomorrow keeps the phone blocked tonight; moving it by hand doesn't.

---

## NF-03 and DX-11 Anki counted from the deck's counts

**Change.** Time in AnkiDroid during a deck piece's slot (or any time of day) with the deck's counts falling counts as that piece's progress; seconds per card are learned per deck.

**Files.** New `learn/AnkiProgress.kt`; `block/FocusService.kt`; `sources/anki/AnkiSource.kt` (nothing); `sources/anki/AnkiRules.kt`; `core/Plan.kt` (`Calibration`); `data/RuntimeState.kt`.

**Steps.**

1. `RuntimeState.ankiReadings: List<AnkiReading>` (last 50), `@Serializable data class AnkiReading(val at: Long, val counts: Map<Long, Triple<Int,Int,Int>>)` (deck id → learn, review, new; use a small data class rather than Triple for serialisation), and `ankiFrontSince: Long? = null`.
2. `FocusService.onFront(pkg)`: when `pkg == "com.ichi2.anki"` and `ankiFrontSince == null` → `graph.anki.front(now)`; when another package comes to the front and `ankiFrontSince != null` → `graph.anki.left(now)`. Both read `AnkiProvider.decks(resolver)` on `Dispatchers.IO` and append a reading.
3. `AnkiProgress.credit(readings, tasks, calibration, now)`: for the latest pair of readings on the same day bracketing a stretch with AnkiDroid in front, for each deck task (`extra[EXTRA_DECK_ID]`) whose counts fell: `cards = Σ(decrease of learn + review + new)`, `minutes = (stretch minutes, capped at cards × 60 s)`; `addWorked(taskId, minutes)` and `extra["cardsDone"] += cards`; when the task's remaining minutes reach 0 or its slot's share of cards is cleared, `keepSlot(key)` for its slot today (earning per BL-08: deck pieces are under 20 minutes mostly). A reading pair spanning midnight is ignored.
4. Seconds per card: `Calibration.ankiSecondsPerCard: Map<Long, Double>`; `Calibrator.ankiSeconds(readings)`: per deck, the median of `stretchSeconds / cards` over the last ten stretches with `cards ≥ 5`; `AnkiRules.effortMin(reviews, new, secondsPerCard?)` uses it when present (fall back to `REVIEW_SECONDS`/`NEW_SECONDS`).
5. Show on the Anki settings line: "German 1.3: 9 s a card, from 6 readings".

**Tests.** New `AnkiProgressTest` (pure): a stretch with counts falling credits minutes and cards; a stretch without change credits nothing; midnight-spanning ignored; seconds-per-card median.

**Done when.** Studying a deck in AnkiDroid without touching the app ticks its piece on the Plan tab within a minute of leaving AnkiDroid.
