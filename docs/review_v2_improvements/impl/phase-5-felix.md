# Phase 5: the profile, memories, Felix, replies, effort and the lock

Items: AI-05, AI-06, AI-21, AC-01, AC-02, AC-04, AC-05, AC-10, AI-22, AI-24, AI-10, LR-03, LR-04, LR-05, LR-07, UI-06, UI-16. Depends on phase 4 (slots and their records exist; sessions are gone). Order inside the phase: AC-02/AC-04/AC-05 (plumbing), AI-05/AI-06 (the profile), AI-21 (memory), AI-22 (the prompt), AI-24 (replies), LR-03 (the triggers), LR-04/LR-05/LR-07, AC-10, UI-06/UI-16, AI-10.

Settings added (all `NEVER` in LOOSER unless said):

| Field | Default |
|---|---|
| `yearGroupStart: Int` | `2026` (the September the student entered Year 12) |
| `profileLines: List<String>` | `emptyList()` |
| `classMap: Map<String, String>` | `emptyMap()` (class code → subject, e.g. `"12.2-PH3" to "Physics"`) |
| `felixName: String` | `"Felix"` |
| `felixLine: String` | `"a strict friend who knows your week and wants the work done"` |
| `felixGraceMin: Int` | `10` (minutes after a slot's start before he asks) |
| `felixDefaultCheckMin: Int` | `20` |

---

## AC-02 Effort per job

**Files.** New `enrich/ModelEffort.kt`; every caller that builds `OutputConfig`.

**Steps.** `object ModelEffort { enum class Job { Email, Assignment, Effort, Instruction, Review, PhotoToTask, PhotoContext, Chat, Reply, Felix, MicroPlan, PhotoCheck, Summary, Tidy }; fun of(job: Job): OutputConfig.Effort }` returning HIGH for Email, Assignment, Effort, Instruction, Review, PhotoToTask, PhotoContext; MEDIUM for Chat, Reply, Felix, MicroPlan, PhotoCheck, Summary, Tidy. Replace every `OutputConfig.Effort.HIGH` literal with `ModelEffort.of(…)`. `ClaudeEnricher` maps `Enrichments.Job` to it. The debug command `ai-effort <job> <level>` overrides at runtime (a map in `AppGraph`, not persisted).

**Tests.** None beyond compile; `EnrichTest` asserts the table once.

---

## AC-04 Rest by what failed

**Files.** `enrich/ModelHold.kt`, `enrich/AiUsage.kt`, `enrich/KeyProblem.kt`, `AppGraph.kt` (`modelFailed`, `modelHold`), `enrich/EnrichWorker.kt`.

**Steps.**

1. `AiUsage.restUntil: Long? = null`, `failuresInRow: Int = 0`, `lastFailureClass: String? = null`. `AiUsage.failure(error, at, problem, restMs)` sets `restUntil = at + restMs` (null when `restMs == 0`), increments `failuresInRow`; `record` resets both.
2. `enrich/Failures.kt`: `fun classify(error: Throwable): Failure` with `enum class Failure { RateLimit, Overloaded, Server, Timeout, Network, BadRequest, Key, Unknown }`. Map from the Anthropic Java SDK's exceptions (look at how `KeyProblem.of(error)` already tells 401/403 apart and extend the same `when`): `RateLimitException` → RateLimit; `InternalServerException` with status 529 → Overloaded, other 5xx → Server; `AnthropicIoException`/`SocketTimeoutException` → Timeout; `UnknownHostException`/`ConnectException` → Network; `BadRequestException`/`UnprocessableEntityException` → BadRequest; `UnauthorizedException`/`PermissionDeniedException` → Key.
3. `fun restMs(failure: Failure, failuresInRow: Int): Long`: RateLimit/Overloaded → 5, 15, 60 min by `failuresInRow` (1, 2, 3+); Server/Timeout → 15, 60; Network → 0; BadRequest → 0; Key → 0 (KeyProblem handles it); Unknown → 15 min. Honour a `Retry-After` header when the SDK exposes it (`AnthropicServiceException.headers()`), using the larger.
4. `AppGraph.modelFailed(error, month, at, job)`: classify, compute the rest, `aiUsage.failure(...)`, and for BadRequest with a task: `tasks.update` marking `task.extra["enrichFailed"] = "v<Prompts.VERSION>"` so `Enrichments.stale` skips it until the version changes (phase 8 adds VERSION; until then store `"1"`).
5. `ModelHold.of(...)`: `Resting` when `restUntil != null && now < restUntil`. `AppGraph.modelHold(interactive: Boolean = false)`: `Resting` is returned only for background callers; interactive ones (chat, replies, Felix, photo) get null during a rest unless `keyProblem != null` or `Capped`. Update the three callers' messages ("resting an hour") to say the minutes left.
6. `EnrichWorker.retryAfter(…, restMs)` uses the computed rest. A global cap: `AiUsage.failedToday` ≥ 3 in the last hour → rest 60 min whatever the class.

**Tests.** `EnrichTest`: `classify` on constructed exceptions; `restMs` table; `ModelHold.of` with `restUntil`; interactive bypass.

---

## AC-05 Interactive calls don't queue behind enrichment

**Files.** `AppGraph.kt`, `enrich/AiUsage.kt`, `block/PhotoChecks.kt`, `learn/Review.kt`.

**Steps.** `AppGraph.reserveCall(interactive: Boolean): Reservation?` under `modelCalls.withLock`: checks `modelHold(interactive)`, checks `aiUsage.allows(cap, inFlight)` (`allows` gains `inFlight: Int`: `spent + (inFlight + 1) * WORST_CALL_USD <= cap`), increments `inFlight` (an `AtomicInteger` on the graph), returns a `Reservation(month, at)`; `release(reservation, result)` under the lock decrements and records `modelWorked`/`modelFailed`. Every caller: reserve → send outside the lock → release. `enrichNow`'s loop keeps one-at-a-time by its own structure.

**Tests.** `EnrichTest`: `allows` with in-flight calls.

---

## AI-05 and AI-06 The profile block

**Files.** New `enrich/Profile.kt`; `data/Settings.kt`; every prompt builder; `ui/SettingsActivity.kt`.

**Steps.**

1. `object Profile { const val MAX_CHARS = 2_000; fun yearGroup(now: Long, zone: ZoneId, yearGroupStart: Int): Int; fun block(settings, tasks: List<TaskItem>, timetable: Timetable?, school: SchoolCalendar, memory: MemoryState, now: Long, zone: ZoneId): String }`. Year group: 12 + (academic years since `yearGroupStart`, rolling over on 1 September). The block, in this order, cut at `MAX_CHARS` from the end (memories go last so they're cut first):
   ```
   About the student
   Year 12 at a UK sixth form. Subjects: Physics (12.2-PH3), German (German PETERS), Maths (12.1-MA2).
   School days: lessons 08:30–15:30; study periods Tue 11:15–12:15, Thu 09:30–10:30; break 10:45–11:15; lunch 13:15–14:35 (eating 13:40–13:55).
   Works weekdays 16:45–22:00, weekends 08:30–22:30. No school: 26–30 Oct (half term).
   German textbook 1.
   From the student: <profileLines, one per line>
   Remembered: <facts, newest first, "topic: text">
   How Felix should behave: <standing instructions, one per line>
   ```
   Subjects: `settings.classMap` entries, plus class names seen on open tasks that aren't mapped (as the code alone). Lessons from `PowerPlannerItems.lessonsOn` for the current week (five lines at most). Dates relative to `now`.
2. `Prompts.STUDENT`, `ChatPrompts.SYSTEM`, `InstructionPrompts.SYSTEM`, `PhotoChecker.SYSTEM`, `ReviewInput.SYSTEM`: replace "(Year 12)" with a `yearGroup` parameter (make them functions of it where they're `val`s).
3. Every call sends the profile as the second system block: `.systemOfTextBlockParams(listOf(TextBlockParam.builder().text(system).build(), TextBlockParam.builder().text(profile).build()))` instead of `.system(system)`. `AppGraph.profile(): String` builds it from the stores; the callers (`ClaudeEnricher.enrich`, `ChatReader.answer`, `InstructionReader`, `PhotoChecker.check`, `ClaudeReviewer.review`, and the new Felix/micro-plan/summary callers) take `profile: String` as a parameter.
4. Settings → "About you": year group start (a number), the class map (code → subject rows, prefilled from the codes seen, editable), the profile lines (a multi-line field), the German textbook (moved here from Anki).
5. `EvalSetTest.dump` prints the profile block (built from the fixture settings) after the system prompt.

**Tests.** `EnrichTest` (new `ProfileTest`): year group rollover; the block's lines; cut at the limit keeps the subjects and loses memories first.

---

## AC-01 Cache the stable prefix

**Files.** Every caller that sends system blocks (after AI-05).

**Steps.** On the first system block (the job's instructions, examples and limits, which don't change between calls) set `cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())` on its `TextBlockParam`; leave the profile block (which changes with memories) uncached. Opus 5.5 ignores the marker below 512 tokens, so this does nothing until the examples (phase 7) are in; add it now so it's in place. `CallRecord.cacheRead` (AC-07) shows whether it works. Do it in one helper, `ModelCalls.systemBlocks(system: String, profile: String): List<TextBlockParam>`, used by every caller.

**Tests.** None (SDK parameter).

---

## AI-21 Memories and standing instructions

**Files.** New `core/Memory.kt`; `AppGraph.kt`; `enrich/ChatReader.kt` (schema); `data/Backup.kt`; `ui/SettingsActivity.kt` (phase 6 moves it to Settings → Felix).

**Steps.**

1. ```kotlin
   @Serializable data class MemoryState(val facts: List<Fact> = emptyList(), val rules: List<Rule> = emptyList())
   @Serializable data class Fact(val topic: String, val text: String, val at: Long)
   @Serializable data class Rule(val text: String, val at: Long)
   object Memories {
       const val FACT_BUDGET_CHARS = 6_000   // about 1,500 tokens
       const val RULE_MAX = 20
       fun apply(state: MemoryState, given: List<Given>, now: Long): Pair<MemoryState, List<String>>  // the lines to show ("Remembered: …", "Forgot: …", "Felix will: …")
   }
   @Serializable data class Given(val topic: String, val text: String? = null, val kind: String = "fact")  // kind "fact" | "rule"; text null drops
   ```
   `apply`: a fact with an existing topic (trimmed, ignore case) replaces it; null text drops; a rule is appended unless an equal one exists (null drops the matching one); facts over the budget drop oldest-first; rules over `RULE_MAX` refuse the newest with a line "Too many standing instructions; drop one first".
2. `AppGraph.memory = JsonStore(File(app.filesDir, "memory.json"), MemoryState.serializer(), ::MemoryState)`. `Backup.memory: MemoryState = MemoryState()` (FORMAT 4; accept 2–4).
3. Chat schema (`ChatPrompts.SCHEMA`) gains `"memories" to mapOf("type" to "array", "items" to obj("topic" to STRING, "text" to nullable(STRING), "kind" to mapOf("type" to "string", "enum" to listOf("fact", "rule"))))`; `ChatAnswers.Reading.memories: List<Given>`; `ChatPrompts.SYSTEM` adds:
   ```
   4. Remember (memories): a fact about the student worth keeping for later (a preference, a pattern, something about their week: "football is on Tuesdays"), with a short topic so a newer fact on the same topic replaces it; or, kind "rule", a standing instruction about how you should behave ("don't message before 8", "be blunter"). Give text null to forget one. Busy times, limits and holidays are changes, not memories.
   ```
   Felix's schema (AI-22) carries the same field.
4. `AppGraph.answerChat`: after parsing, `Memories.apply(...)` → store, and the lines go on the Claude message as `ChatMessage.remembered: List<String>` (new field; `AskActivity` shows them in small text under the message).
5. Weekly tidy (phase 8's Reviews page shows it; the tidy itself is here): `enrich/MemoryTidy.kt`: a call with the facts in, `{facts: [{topic, text}]}` out, system prompt "Rewrite these remembered facts about the student into fewer, current lines: merge duplicates, drop what's stale or contradicted by a newer line, keep the newest on each topic, keep every rule-like preference. At most N lines." Run by `Review.run` after the model review when the facts exceed 70 % of the budget; store `WeeklyReview.memoryTidy: MemoryTidyRecord(before: List<Fact>, after: List<Fact>)`; apply `after`. Rules are never tidied.

**Tests.** `MemoryTest` (new): replace by topic; drop; budget; rule cap; lines. `ChatTest`: memories parsed from an answer.

---

## AI-22 Felix: the prompt

**Files.** New `enrich/FelixPrompts.kt`, new `enrich/FelixCaller.kt`.

**Steps.**

1. ```kotlin
   object FelixPrompts {
       enum class Reason { SlotStart, SlotEnd, SlotKept, DayDone, Morning, Question, Completions, Summary, Reply }
       fun system(settings: Settings, yearGroup: Int): String
       fun describe(reason: Reason, plan: Plan, slot: Slot?, task: TaskItem?, today: List<SlotRecord>, recent: List<FelixRecord>, question: String?, now: Long, zone: ZoneId): String
       val SCHEMA: Map<String, Any>  // message: string; replies: array of string (max 3); nextCheckMin: integer|null; memories: array (as the chat's); question: string|null
   }
   ```
   System prompt (verbatim; `${name}` and `${line}` from settings):
   ```
   You are ${name}: ${line}. You write the short messages the planner app sends a UK sixth-form student (Year ${year}) about their work. You get the reason for the message, the piece of work it's about with its first action, today's plan and what's been kept or missed, your recent messages and the student's replies, and the time.

   Be concrete: name the piece and its first action. Two sentences at most. No guilt, no sarcasm, no emojis, no praise for nothing, no promise the app can't keep. Don't repeat what you said last time. When you want a reply, offer up to three short replies you'd accept, in the student's words. Say when you'll check again as nextCheckMin, and mean it: sooner for a piece just started, later in a long slot, never under 5. Reasons: SlotStart (the slot began and nothing's happened: ask if they're starting); SlotEnd (the slot ended with no tick: ask what happened, or for the photo if the piece needs one); SlotKept (a slot was kept: one line, and only sometimes); DayDone (today's last slot kept: say what's earned, briefly); Morning (the day's shape in a sentence or two); Question (put the question given, in your words); Completions (a source says work is done: ask harder, as expected or easier); Summary (Sunday: say the week's summary is ready to send); Reply (you're answering what they said).
   ```
2. `describe`: lines `Reason: …`, `Now: …`, `Piece: <label>, <minutes> min, slot <start>–<end>, needs a photo to earn: yes/no`, `First action: <micro-plan first line or "none yet">`, `Today: kept N of M slots; next: …; left: …`, `Recent: [time] you: … / them: …` (last 6), `Question: …`.
3. `FelixCaller(apiKey, endpoint, model)` with `suspend fun say(system: String, profile: String, user: String): Result(answer: FelixAnswer?, costUsd, refused)`, the usual call shape, effort `ModelEffort.of(Felix)`; `FelixAnswer(message, replies, nextCheckMin, memories, question)` with `message` cut at 240 characters and `replies` at 3 × 40.

**Tests.** `FelixTest` (new): the system text contains the name and line; `describe` for each reason; parse clamps.

---

## AI-24 Replies continue in the chat; changes to existing tasks apply at once

**Files.** `enrich/ChatReader.kt`, `AppGraph.kt`, new `notify/FelixNotifications.kt`, new `notify/FelixReplyReceiver.kt`, `core/Chat.kt`, `AndroidManifest.xml`.

**Steps.**

1. `Speaker.Claude` stays as the enum value (stored data); the UI labels it with `settings.felixName`. `ChatMessage.reason: String? = null` (Felix's reason when the message was a notification), `remembered: List<String>`, `applied: List<String>` (the lines of changes applied at once).
2. `ChatPrompts.SYSTEM` is rewritten as Felix (UI-16, Q-44): the first paragraph becomes "You are ${name}: ${line}, and this is the chat in the student's planner app." The capabilities list stays (add tasks, changes, answer questions, remember), the reply rule becomes: "Reply in one to three short sentences. A change to a task that already exists is made as soon as you give it, so say what you've done ('Moved it to 19:30'); a new task or event, a busy time beyond today, days off or a half-day are shown to the student to confirm, so say what you'd add. Offer up to three short replies as suggestedReplies when a reply is natural." Schema gains `suggestedReplies: array of string` (UI-06).
3. Applying at once: `object InstantChanges { val TYPES = setOf(AtTime, OnDay, StartFrom, DueBy, Minutes, NotATask, Steps /*phase 7*/, After); fun instant(change: Change, tasks: Map<String, TaskItem>, today: LocalDate, zone: ZoneId): Boolean }`: true when `type in TYPES && change.taskId in tasks`, or `type == BusyTime && change.date == today`, or `type == EventTime`. Everything else (new tasks, `BusyTime` beyond today, `NoSchool`, `HalfDay`, `DayLimit` beyond today) is a proposal as now. In `AppGraph.answerChat`, split `reading.changes` into instant and proposed: instant ones become one `Instruction(quick = false, state = Understood)` applied through `applyInstruction` at once (its refusal notes, if any, go on the message's `note`); proposed ones become `Proposal`s as today. `ChatMessage.applied` lists `Instructions.describe` of the applied ones. Undo: "undo that" is a chat turn Claude answers with the inverse change (the prompt says so: "To undo, give the change that puts it back"); the Your-changes tab can also delete it.
4. Notifications with replies. `FelixNotifications.post(context, record: FelixRecord, replies: List<String>, proposals: List<Proposal>)`: channel `FELIX`; `NotificationCompat.Action` with `RemoteInput` (key `reply`) labelled "Reply"; up to three quick replies as actions (each a broadcast carrying the text); up to two proposal buttons ("Add 'UCAS draft'") carrying the proposal id. `FelixReplyReceiver`: reads the `RemoteInput` or the action's text → `graph.replyToFelix(text, recordId)`; a proposal button → `graph.applyProposal(messageId, proposalId)`.
5. `AppGraph.replyToFelix(text, recordId)`: append `ChatMessage(from = Claude, text = record.text, reason = record.reason, about = record.about)` if the thread doesn't already hold it (match by `recordId` stored in `ChatMessage.id`), then `ask(text, record.about)` → `answerChat()`; when the answer lands and the chat screen isn't in front, post it as a Felix notification (reason Reply) with its `suggestedReplies` and proposal buttons.

**Tests.** `ChatTest`: `InstantChanges.instant` table; the split of a reading; `suggestedReplies` parsed; `applied` lines.

---

## LR-03 Felix: triggers, pacing, quiet rules

**Files.** New `learn/Felix.kt` (the component), new `learn/FelixRules.kt` (pure), `data/RuntimeState.kt`, `data/RuntimeState.kt` (`ActivityLog.felix`), `block/SlotAlarms.kt`, `block/FocusService.kt`, `learn/Daily.kt`, `AppGraph.kt`.

**Steps.**

1. Records: `@Serializable data class FelixRecord(val id: String, val at: Long, val reason: String, val text: String, val replies: List<String>, val key: String? = null, val taskId: String? = null, val nextCheckAt: Long? = null, val replyText: String? = null, val repliedAt: Long? = null)`; `ActivityLog.felix: List<FelixRecord>` (trimmed). `RuntimeState.felix: FelixState(nextCheckAt: Long? = null, lastSentAt: Long? = null, draft: FelixDraft? = null, activity: Map<String, Long> = emptyMap() /* key → last activity seen */)`.
2. `FelixRules` (pure): `fun due(plan, state: FelixState, today: List<SlotRecord>, settings, now, zone, quiet: Boolean, busyNow: Boolean): Trigger?` where `Trigger(reason, slot?, taskId?)`. In order: nothing when `quiet || busyNow`; nothing when no work slot today remains and none is in progress (the "quiet when nothing is scheduled" rule), except `DayDone` once when today's last slot was just kept; `SlotStart` when `now ≥ slot.start + felixGraceMin` and no activity on the slot's key since its start and no message for it yet; `SlotEnd` when `now ≥ slot.end + GRACE` and the slot isn't kept and no SlotEnd message for it; `SlotKept` sometimes (every third kept slot, decided by `key.hashCode() % 3 == 0`); and generally nothing before `state.nextCheckAt` except `SlotEnd` and `DayDone`.
3. Activity: `Felix.noteActivity(key, now)`: called when the task is opened (`TaskOpener`), ticked, photographed, or Anki counts move for it; stored in `FelixState.activity`.
4. `Felix.check(now)`: compute the trigger; if none → re-arm the check alarm for `state.nextCheckAt ?: now + felixDefaultCheckMin`; if one → use the draft from `Ahead` (AC-10) when it matches the reason and key and is < 10 min old, else call `FelixCaller` now; post the notification (AI-24), record it, set `nextCheckAt = now + (answer.nextCheckMin ?: felixDefaultCheckMin).coerceAtLeast(5) minutes`, apply its memories, re-arm. Triggers come from: `SlotAlarms` START/END receivers (PL-13 step 3), a `ACTION_FELIX_CHECK` alarm at `nextCheckAt`, and `FocusService.tick` (a cheap `check` every 30 s is fine because `due` is pure and cheap; the model is called only when a trigger fires).
5. Quiet: `quiet = BlockPolicy.isQuiet(now)`; `busyNow = calendarTime.busy.any { now in it.start until it.end }`. Standing instructions (AI-21 rules) go in the prompt, not the rules; a rule like "not at school" is for the model to honour by answering with an empty message: `FelixAnswer.message.isBlank()` → nothing posted, next check re-armed.
6. Channel `FELIX` ("Felix", IMPORTANCE_DEFAULT) in `Channels.create`.

**Tests.** `FelixRulesTest` (new): each trigger and each silence rule; pacing by `nextCheckAt`; the "nothing scheduled" rule; DayDone once.

**Done when.** Ten minutes into a slot with no activity, Felix's message arrives with three replies; "give me 15" moves the slot and he answers.

---

## LR-04 The morning message from Felix

**Files.** `learn/Briefing.kt`.

**Steps.** `Briefing.run`: after the day record, ask Felix (reason `Morning`) with a 20 s timeout; on an answer, the notification's title is his message's first sentence and `setContentText` the second, with the computed list as the `InboxStyle` lines; on no answer (off, resting, timeout) the list alone as now. Posted on the `FELIX` channel with a reply action (AI-24). Nothing on a day with no slots (the list alone, no call).

**Tests.** `DailyTest`: the summary builder unchanged; the Felix path tested through `FelixRules.morningInput`.

---

## LR-05 "How did it go?" becomes Felix's question

**Files.** `learn/Assessment.kt`, `learn/Felix.kt`.

**Steps.** `Assessment.ask` no longer posts per completion: it adds the completions to `assessLater`; `FelixRules.due` adds a `Completions` trigger at the first check after 19:00 when `assessLater` is non-empty and none of them has a kept slot today; the message carries three quick replies mapped to `Calibrator.HARDER`/`AS_EXPECTED`/`EASIER` (the receiver recognises these three texts and calls `Assessment.record` for each listed completion). `AssessActivity` stays for the note. The `HOW_IT_WENT` channel is deleted at start-up (like `DAILY`).

**Tests.** `FelixRulesTest`: the Completions trigger; `LearnTest`: `answered` unchanged.

---

## LR-07 Quiet hours and a ceiling for everything but Felix

**Files.** `notify/Notify.kt`, `data/RuntimeState.kt`.

**Steps.** `Notify.post(context, id/tag, notification, channel, now)`: for channels in `CEILINGED = setOf(BRIEFING, CALENDAR, WEEKLY, MODEL)`: if `isQuiet(now)` or today's count ≥ 4 → store in `RuntimeState.heldNotifications: List<Held(channel, id, tag, at, title, text)>` and return; `Briefing.run` re-posts held ones first thing. `PROTECTION` and `FELIX` bypass the ceiling; `FELIX` respects quiet hours through `FelixRules`. `RuntimeState.notifiedToday: Map<String, Int>` keyed by date.

**Tests.** `Notify` is Android; put the counting in a pure `NotifyRules` with tests.

---

## AC-10 Work ahead

**Files.** New `enrich/Ahead.kt`; `AppGraph.kt` (a watcher hook).

**Steps.** `Ahead.run(plan, now)` as a `PlanWatcher` hook after the calendar write: (a) micro-plans (phase 7, AI-15) for today's and tomorrow's work slots without one; (b) Felix's next draft: compute the next trigger `FelixRules.next(plan, state, now)` (the earliest of the next slot start + grace, the next slot end + grace, `nextCheckAt`); if it's within 15 minutes and no draft exists for it, call `FelixCaller` and store `FelixState.draft = FelixDraft(reason, key, answer, at)`. The draft is used only if the state at sending time still matches (`Felix.check` step 4). Calls go through `reserveCall(interactive = false)`.

**Tests.** `FelixRulesTest`: `next` picks the earliest.

---

## UI-06 Quick replies under answers; UI-16 Felix on every screen

**Files.** `ui/AskActivity.kt`, `ui/MainActivity.kt`, `ui/TasksScreen.kt`, `ui/TodayScreen.kt`, `ui/StatsScreen.kt`, `ui/TaskSheet.kt`, `ui/SettingsActivity.kt`, `block/BlockedActivity.kt`, `res/values/strings.xml`.

**Steps.** `AskActivity`: title `settings.felixName`; `Claudes` labelled with the name; chips (`SuggestionChip`) above `InputBar` from the last Claude message's `suggestedReplies`, a tap sends the text. New composable `ui/FelixButton.kt`: `FelixFab(activity, about: About)` = `SmallFloatingActionButton` with `ic_chat` and content description "Felix", starting `AskActivity.intent(activity, about)`; placed bottom-right in `MainActivity`'s `Scaffold` (`floatingActionButton`, with `About()`; the Tasks tab's existing chat FAB is removed, its "New task" FAB stays), `TaskSheetHost` (about the task), `SettingsActivity`, `BlockedActivity` (about the next piece's task). Strings: "Ask Claude about it" → "Ask Felix about it"; "Split with Claude" (phase 7) → "Split with Felix"; the menu entry "Ask Claude" is removed (UI-18 in phase 6 does the rest of the menu).

**Tests.** None (Compose).

---

## AI-10 One question when the model isn't sure

**Files.** `enrich/Prompts.kt`, `core/Enrichment.kt`, `learn/Felix.kt`, `AppGraph.kt`.

**Steps.** `Prompts.schema(Email)` and `schema(Assignment)` gain `"question" to nullable(STRING)` with the prompt sentence "question: rarely: the one question that would settle a reading the text can't (which Friday; whether the test is the 12th or the 19th); otherwise null." `Enrichment.question: String? = null`. After an enrichment with a question is laid (`enrichNow`), `Felix.ask(reason = Question, taskId, question)` posts it with a reply (no quick replies). The reply becomes an instruction on the task (`addInstruction(text, About(taskId))`) **and** is appended to `task.extra["answers"]` (newline-separated); `Prompts.describe` prints `The student says: …` lines from it; `Enrichments.inputHash` includes `extra["answers"]` so the task is re-enriched with the answer.

**Tests.** `EnrichTest`: question parsed; `inputHash` changes with an answer; `describe` prints it.
