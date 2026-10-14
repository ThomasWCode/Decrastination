# Phase 2: even spreading, elastic dates, the yield, the horizon

Items: PL-01, PL-07, PL-14, AI-12, PL-06, DX-14, PL-02. Depends on phase 1 (AR-04's test is the net). Everything here except DX-14's read is pure and under `PlannerTest`, `PlannerPropertyTest`, `LearnTest`.

Read `core/Planner.kt` whole before starting; its header comment states the rules you're changing, and you'll rewrite that comment last.

---

## PL-01 Spread every later task evenly to its deadline, by the time each day has

**Change.** Work not due today or tomorrow is placed across its window in proportion to each day's free time, at least one piece a week, revision at least a day apart, a single piece on the day with most room. Backwards placement (`placeBack`) remains only for undated (soft) work.

**Files.** `core/Planner.kt`, `core/Plan.kt`, `docs/scheduler.md` §3, `PlannerTest`, `PlannerPropertyTest`.

**Steps.**

1. In `Planner.plan`, keep `placeNow` for `urgent` (deadline ≤ today) and for `soon` later items (due tomorrow) exactly as now. Replace the two `placeBack` calls for `later.filterNot(::soon)` with `placeSpread` for items with `!item.soft`, and keep `placeBack` for soft items.
2. `placeSpread(item: Item)`:
   ```
   val earliest = maxOf(firstDay(item), floorOf(item) ?: today)
   val last = maxOf(earliest, minOf(horizon, lastUsableDay(item, input), ceilingOf(item) ?: horizon))
   val days = earliest..last (as a List<LocalDate>)
   val n = item.chunks.size
   // weights: the free time left on each day, minus nothing for days that are capped out
   val weight = days.associateWith { d -> if (capped(d, MIN_CHUNK)) 0 else free.getValue(d).coerceAtLeast(0) }
   val total = weight.values.sum()
   val assigned = arrayOfNulls<LocalDate>(n)
   if (n == 1) {
       // the day with most room, earliest on a tie
       val day = days.maxByOrNull { weight.getValue(it) }  // maxByOrNull returns the first maximum
       place(item, 0, day, assigned)   // falls back to nearest day with room, else behind into today
   } else {
       var cumulative = 0; val boundaries = ...  // cumulative weight at the end of each day
       for (i in 0 until n) {
           val target = (i + 0.5) / n * total            // when total == 0, target = i (even by index)
           val day = first day whose cumulative weight >= target (or days[i * days.size / n] when total == 0)
           place(item, i, nearestWithRoom(item, day, days, i, assigned), assigned)
       }
   }
   enforceMinimumTouch(item, days, assigned)
   enforceRevisionGaps(item, days, assigned)
   note(item, assigned)
   ```
   - `place` does what `placeBack`'s successful branch does: `take(item, day, minutes)` and `placed[day] += chunk(item, i, behind = false)`; when no day in `days` has room (`room(item, day) >= minutes` and `countOn(assigned, day) < perDay`), it does what `placeBack`'s failure branch does today (into today, `behind = true`, recorded as `Unplanned.Reason.NoRoom` where the existing code does so).
   - `nearestWithRoom(item, day, days, i, assigned)`: the day itself if it has room; else alternate earlier/later within `days` (earlier first), never before the day assigned to chunk i−1 (order holds).
   - `enforceMinimumTouch`: for i in 1 until n, if `assigned[i] - assigned[i-1] > 7 days`, move chunk i to the nearest day with room in `(assigned[i-1] + 1)..(assigned[i-1] + 7)` (give back its minutes first with `give`, re-take on the new day). Only when `days.size >= 7`.
   - `enforceRevisionGaps`: when `item.task.kind == Kind.Revision || item.task.enrichment?.testDate != null`, for i in 1 until n, if `assigned[i] == assigned[i-1]`, move chunk i to the next day with room in `days`; if none, leave it.
3. Processing order: `later.filterNot(::soon).filter { !it.soft }.sortedWith(order)` (the existing `order` comparator, so near deadlines claim their days first), then soft items by `order` through `placeBack`.
4. `Plan.pressure` becomes `todayBucket?.chunks.orEmpty().isNotEmpty()`; keep `dueSoon` as it is for ordering. (BL-01's block-side changes are phase 4; this line is needed now so spreading doesn't make every day strict.)
5. Rewrite the header comment of `Planner.kt` items 3–5 to describe: due-now first; later dated work spread in proportion to free time with the minimum touch and the revision gap; undated work backwards from its soft week; and that today's bucket holds a fair share rather than everything.
6. `docs/scheduler.md` §3: the same, as numbered rules.

**Tests.** `PlannerTest` (new or changed):
- `a task due in 12 days with 6 pieces gets one every second day, not the last six evenings`;
- `spreading follows free time: a day with a busy evening takes a smaller share`;
- `a task with a three-week window is touched at least once a week`;
- `revision pieces are at least a day apart`;
- `a single piece goes to the day with most room, the earlier on a tie`;
- `work due tomorrow is still placed first and takes today's time`;
- `undated work still fills from its soft week backwards`;
- `today's bucket alone is pressure`.
Existing `PlannerTest` tests that encode backwards placement for dated work change to the spread expectation; keep their names if the behaviour they describe still holds (most do: deadlines, margins, per-day limits, windows).
`PlannerPropertyTest`: add **minimum touch** (for a task with ≥ 2 chunks and a window ≥ 7 days, no gap > 7 days between consecutive chunks unless a day cap or busy time makes it impossible: assert only when every day in the gap had room for the chunk) and **revision gap** (same caveat).

**Done when.** The tests above pass; the property test passes at 2,000 iterations locally; the Plan tab shows a far task's pieces across the weeks (check in the app with a Teams fixture).

---

## PL-07 Each piece says why it's where it is

**Change.** `Chunk.why` set during placement; shown on the sheet (phase 6 widens it).

**Files.** `core/Plan.kt`, `core/Planner.kt`, `ui/TodayScreen.kt` (`PieceSheet`), `enrich/ChatReader.kt` (`ChatPrompts.plan`).

**Steps.**

1. `enum class Why { DueSoon, Overdue, Spread, MinimumTouch, RevisionGap, MostRoom, Soft, Pinned, Refitted, NoRoom }` in `Plan.kt`; `Chunk.why: Why` (no default: every construction site sets it; `chunk(item, index, behind)` gains a `why` parameter).
2. Set it: `placeNow` → `Overdue` when the deadline has passed, else `DueSoon`; `placeSpread` → `Spread`, or `MostRoom` for a single piece, `MinimumTouch`/`RevisionGap` when those moved it; behind → `NoRoom`; `placeBack` (soft) → `Soft`; phase 3 adds `Pinned` and `Refitted`.
3. `Chunk.whyLine(zone): String`: "Due tomorrow", "Overdue since Mon 13 Oct", "Spread over the 12 days to its deadline", "Kept within a week of its last piece", "A day apart from the last revision piece", "The day with most room before Friday", "Undated: fills spare time this week", "Couldn't fit before its deadline".
4. `PieceSheet`: one line under the minutes. `ChatPrompts.plan`: append ` [why: …]` to each piece's line (short code, not the sentence, to save budget: the enum name lower-cased).

**Tests.** `PlannerTest`: `why` values on the placements the tests above make.

**Done when.** Every chunk in every plan has a `why`; the sheet shows the sentence.

---

## PL-14 Dates the app set are elastic; only a source's deadline is overdue

**Change.** A deadline is *hard* only when it came from a source, from the student (`userDueAt`), or from an email's own words (AI-12's `deadlineQuoted`). Everything else (a step's dates, a model-guessed email deadline, the undated week) orders the work but never shows "Overdue" and is re-fitted when it passes.

**Files.** `core/Task.kt`, `core/Enrichment.kt`, `core/Planner.kt`, `core/Plan.kt`, `ui/*` where "Overdue" is shown (`Badges` in `TodayScreen`, `TaskTile`, `Format.due`, `Groups.byDue`), `learn/Stats.kt`, `docs/scheduler.md`.

**Steps.**

1. `TaskItem.hardDeadline: Boolean` (computed): `userDueAt != null || (sourceValues?.dueAt ?: (if (sourceValues == null) dueAt else null)) != null || (source == Source.Gmail && enrichment?.deadlineQuoted == true)`. For `Source.Own`, `dueAt` is the student's own: hard.
2. `Planner`: `Item.hard: Boolean`. For a task-level item, `hard = task.hardDeadline`. For windowed items made by `windows()` from a step's `from`/`due` (`Piece.due != null`), `hard = false`.
3. Elasticity in `plan`: before building items, for each dated task with `!hardDeadline && dueAt < now`, treat it as undated from now: `dueAt = null` → it becomes soft with the soft week from `firstSeenAt`… no: from *now* (`softFrom(now)`), so it's planned within the next week, not immediately overdue again. Implement by passing `softFrom` the later of `firstSeenAt` and the time the date passed (use `input.now` when the stored date is past). *(As built: the week is counted from the date itself, and a week more at a time while it's past, `Planner.softWeek`; undated work's week the same from `firstSeenAt`. Counted from `now`, the soft deadline would be a week away at every replan and undated work, placed backwards from it, would never come round.)* For windowed step items whose `due < now`, drop the step's dates (`Piece(from = null, due = null)`) so the chunk falls back into the task's own item.
4. `Chunk.overdue` is true only when `item.hard && item.deadline < now`. `Chunk.dueToday` likewise only for hard deadlines; non-hard dated chunks still carry `deadline` for ordering (`rankedBy`).
5. UI: wherever "Overdue" is shown, it reads the chunk/task flag, so nothing else changes; `Groups.byDue` puts a task in `Overdue` only when `hardDeadline && dueAt < now`, else in the group its date would give (a soft date in the past → `Today`). `Stats.summary`'s `onTime` counts completions whose task had a hard deadline: `CompletionRecord` gains `hardDeadline: Boolean = true` (default true for old records), set from the task at completion.
6. `docs/scheduler.md` §3: a rule "Dates the app or Claude set are plans; only a source's, the student's, or an email's quoted deadline can be overdue."

**Tests.** `PlannerTest`: `a step whose date has passed is re-fitted, not overdue`; `an email deadline the model guessed never shows overdue and is re-planned within a week when it passes`; `a Teams deadline passed is overdue and placed first`. `PlannerPropertyTest`: `overdue` only on hard items.

**Done when.** The only red "Overdue" on the Plan and Tasks tabs comes from a source, the student, or a quoted email date.

---

## AI-12 Every date the model sets is soft

**Change.** The Email job says whether a deadline is the email's own words; the app treats a guessed one as soft.

**Files.** `enrich/Prompts.kt`, `core/Enrichment.kt`.

**Steps.**

1. `Prompts.schema(Email)`: add `"deadlineQuoted" to mapOf("type" to "boolean")`. Prompt bullet: "deadline: only a date the email itself gives or clearly implies; deadlineQuoted true then. A quick action with no date of its own: deadline null (the app plans it within a fortnight)." *(As built: "within the week", as step 2's soft week is 7 days; and a day the email only suggests stays its deadline, `deadlineQuoted` false, so it's a plan.)* Remove the old "due within the next two weeks" sentence from the blocks bullet; blocks' `due` stays only where the email gives one.
2. `Answers.Triage` gets `deadlineQuoted: Boolean = false`; `Enrichment.deadlineQuoted: Boolean = false`; `parse` copies it. The undated quick action's "fortnight" is the planner's soft week (`softDeadlineDays`, 7) applied from first seen; raise nothing.
3. `Enrichment.inputHash` unchanged (prompt change → re-enrichment happens through the prompt version in phase 8; until then, `enrich-task` by hand).

**Tests.** `EnrichTest`: `deadlineQuoted` parsed and stored; an answer without the field is unreadable (schema is closed; the eval set's old answers will need the field added when re-scored). *(As built: step 2's default holds, so an answer without it reads as not quoted: a missing flag can only make a date soft, and the API's closed schema always sends it.)*

**Done when.** New email enrichments carry the flag and PL-14 uses it.

---

## PL-06 A learned yield, per kind of day

**Change.** Each day's capacity is its free time × the yield of its kind (weekday evening; weekend or no-school day; school windows from phase 3), learned from day records.

**Files.** `core/Plan.kt` (`Calibration`), `learn/Calibrator.kt`, `learn/Days.kt`, `core/Planner.kt` (`capacity`), `learn/Stats.kt` (`calibrationLines`), `docs/scheduler.md` §5.

**Steps.**

1. `enum class DayKind { Weekday, Weekend, School }` in `core/Plan.kt`. `Calibration.yields: Map<DayKind, Double> = emptyMap()` and `fun yieldFor(kind: DayKind): Double = yields[kind] ?: 1.0`.
2. `DayRecord.kind: DayKind? = null`; `Days.record` sets it from the date (weekend → `Weekend`, else `Weekday`; phase 3 adds no-school days as `Weekend`). `DayRecord` for school windows is phase 4 (slot records); until then `School` has no data and stays 1.0.
3. `Calibrator.yields(days: List<DayRecord>, today: LocalDate): Map<DayKind, Double>`: for each kind, the records of the last 14 dates of that kind with `plannedMin > 0 && doneMin != null`; if fewer than 7, no entry; else the median of `doneMin / plannedMin` clamped to `0.5..1.0`. Called from `learn`, stored in `Calibration.yields`. `Calibrator.describe` adds a line when a yield changes by ≥ 0.05 ("Weekends hold about 45 % of their free time now").
4. `Planner.capacity(day, input)`: after `minutes` is computed and before the cap: `minutes = (minutes * input.calibration.yieldFor(kindOf(day))).roundToInt()` where `kindOf` is weekend/weekday (phase 3 passes the kind per window). The day cap (`dayCaps`) applies after.
5. `Stats.calibrationLines`: a line per yield with the day count.

**Tests.** `LearnTest`: median, clamp, minimum seven days, kinds separate (seven bad Saturdays don't move weekdays). `PlannerTest`: capacity scaled by the day's kind's yield.

**Done when.** After seven weekend records with 40 % done, Saturdays are planned at half their window; weekdays unchanged.

---

## PL-02 Say early when a source deadline can't be met

**Change.** When a hard-deadline task's remaining minutes exceed the free time in its window, the plan says so the day it becomes true, with the numbers.

**Files.** `core/Plan.kt`, `core/Planner.kt`, `ui/TodayScreen.kt` (the Day view in phase 6; Felix in phase 5).

**Steps.**

1. `data class WontFit(val taskId: String, val title: String, val leftMin: Int, val freeMin: Int, val deadline: Long)`; `Plan.wontFit: List<WontFit>`.
2. In `placeSpread` (and `placeNow` for due-soon work): after placement, if any chunk of a hard item was flagged `behind`/`NoRoom`, add a `WontFit` with `leftMin` = the item's minutes and `freeMin` = the free time its window had before placement (sum the pre-placement `free` over the window). One entry per task.
3. Shown: a line under today's header (phase 6 moves it to the Day view and the week strip's dot): "Physics coursework: 6 h left, 4 h 10 free before Friday", with a *What gives?* button opening the chat with "This week can't hold the Physics coursework by Friday. What should I shorten, move or ask for more time on?" prefilled (`AskActivity.intent(activity, About(taskId), text)`). Phase 5's Felix gets a `WontFit` reason sent once per task per day.

**Tests.** `PlannerTest`: a 6-hour task due in two days with 4 hours free yields one `WontFit` with those numbers; a soft task never does.

**Done when.** The line appears the day the spread can't fit a hard task.

---

## DX-14 Calendar: look as far ahead as the planner plans

**Change.** Read busy time to the planner's horizon; keep questions to seven days; count unanswered far all-day events as a few hours.

**Files.** `learn/CalendarTime.kt`, `learn/EventJudge.kt`.

**Steps.**

1. `CalendarTime.LOOK_AHEAD_MS = 90 * DAY_MS` (the planner's `MAX_HORIZON_DAYS`; reference the constant rather than repeat the number: make `Planner.MAX_HORIZON_DAYS` public).
2. `EventJudge.time(events, answers, zone, now: Long, askUntil: Long)`: in the `Judgement.Ask` branch, when `event.start > askUntil`, apply `Judgement.Load(FEW_HOURS_MIN)` to the day loads instead of adding to `toAsk`. `CalendarTime.refresh` passes `now + ASK_AHEAD_MS`. Existing callers in tests pass `now` and `Long.MAX_VALUE`.
3. `CalendarActivity` (until phase 6 replaces it) keeps showing 14 days; nothing else reads `events` by range.

**Tests.** `LearnTest` (EventJudge): an all-day event three weeks out with no answer loads its day with `FEW_HOURS_MIN` and isn't in `toAsk`; one within seven days is asked.

**Done when.** A trip in week three takes its day's hours out of the spread.
