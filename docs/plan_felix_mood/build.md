# Felix's moods: the build, stage by stage

The code as it is now, quoted with its file and line on `main` at 24a8774, unchanged in these files by 4.6.2's merge (23f6596) (read again before each edit: another release may move it), and what each stage writes. The stages and their estimates are [README.md](README.md)'s; the drafts here are drafts, settled by the owner's read of the scenarios (stage 5). Paths under `app/` are `app/src/main/java/com/thomaswcode/decrastination/` unless they say otherwise; tests are under `app/src/test/java/com/thomaswcode/decrastination/`.

## Read first, in full

- `felix/FelixPrompts.kt`: `REGISTER` (line 37), `JUST_TALK` (40, used only by the chat), `system` (76; `${REGISTER}` at 81, the standing-instructions sentence at 83), `describe` (109 on; its "Today:" line at 176–180).
- `felix/FelixRules.kt`: `MAX_PER_SLOT` (48), the SlotStart and SlotKept arms of `due` (149, 153).
- `felix/ChatPrompts.kt`: `system` (160 on; `REGISTER` and `JUST_TALK` interpolated at 177 and 179, the examples after them), `plan` (802 on; today's slots under "Today's slots …").
- `felix/Looks.kt`: `today(now)` (361), the two `describe` calls (314, the Morning; 591, the rest), and where his message is made and sent (431–446: a silent one too, with blank text, its `kind` and `pieceKey` set).
- `felix/Conversation.kt`: `chatContext` (555), its `ChatPrompts.plan` call (574), `keptToday` (672).
- `felix/FelixRules.kt` and `FelixRulesTest`: the pattern the new rule follows.
- `data/RuntimeState.kt`: `SlotOutcome` (118: `Kept`, `KeptLate`, `Missed`, `Moved`; `kept` is the first two), `SlotRecord` (136).
- `core/Task.kt`: `workedMin` (148), `photoMin` (153), `subSteps` (161), `stepsWorkedMin` (168: a part of the first two, the minutes a source's own steps leave out, not a count of steps), `deadlineAt` (247), `hardAt` (250), `handMin` (257: steps ticked by hand, which add no minutes).
- `core/Chat.kt`: `ChatMessage.about` (55), `kind` (57), `pieceKey` (63).
- `enrich/EvalRunTest.kt` (test side): the readings' set (50, a hard budget) and the chat's (112, a soft one, its KDoc at 96–110), whose soft budget, case-by-case costs and run numbering the new set copies; `app/build.gradle.kts:70`, the commands, the chat's with `--rerun`.
- `test/resources/prompts/felix.txt` and `chat.txt`: the goldens, as the prompts read now.

## 1. The scenarios' harness, and today's voice run

**A third test in `EvalRunTest`**, `the mood set asked of Felix and scored`, gated `assumeTrue(System.getProperty("runEval") == "mood")` and the key, as the chat's is (line 113).

**The set**, `private/eval-felix-mood/cases.json`, git-ignored, every case made up: no real task, title, name or date. One case:

```json
{
  "id": "m03-third-miss",
  "kind": "felix",
  "reason": "SlotEnd",
  "now": "2026-11-04T19:40",
  "tasks": [{ "id": "own:essay", "title": "History essay", "kind": "Homework", "due": "2026-11-05T09:00", "real": true, "minutes": 120, "workedMin": 0 }],
  "today": [
    { "key": "own:essay#0", "taskId": "own:essay", "outcome": "Missed", "start": "16:45", "end": "17:15" },
    { "key": "own:maths#0", "taskId": "own:maths", "outcome": "Missed", "start": "17:30", "end": "18:00" },
    { "key": "own:essay#1", "taskId": "own:essay", "outcome": "Missed", "start": "19:00", "end": "19:30" }
  ],
  "record": "own:essay#1",
  "recent": [{ "from": "Felix", "kind": "SlotStart", "pieceKey": "own:essay#1", "at": "19:10", "text": "essay's on, you starting?" }],
  "memories": [],
  "expect": { "level": "Strong", "addressWords": 0 }
}
```

A chat case has `"kind": "chat"` and `"message"`, the student's words, and goes through the Conversation on temporary stores, seeded as the chat's set seeds them (line 148).

**What the test does with each case:** builds the `Plan`, the `SlotRecord`s, the messages and the profile (`Profile.block` with the case's memories) as the app has them; for a `felix` case, `FelixPrompts.system(settings, 12)` and `describe(...)` sent through `FelixCaller.say` (`felix/FelixCaller.kt:26`), the answer read by `FelixAnswers.parse` (240 characters, 480 for the Morning: `FelixAnswers.kt:10`, `13`); for a `chat` case, `Conversation.ask`, its answer read by `ChatAnswers.parse`. It writes `answers/<id>-<run>.json` and the run's `report-<run>.md`, each case's cost printed as it goes, and keeps to the chat set's soft budget: `EVAL_BUDGET_USD`, 3 dollars unless set, stopping before a case once what's left is under twice the dearest case so far (half a dollar at least). Not the readings' hard guard (2 dollars, every call held to `Pricing.WORST_CALL_USD`), which would stop at about $0.65; a case not asked is listed in the report as not asked.

The command, each time with `--rerun` so an unchanged set isn't skipped as up to date:

```
./gradlew testDebugUnitTest --tests '*EvalRunTest*' -PrunEval=mood --rerun
```

`EVAL_CASES` (ids, by commas) asks only those, for a round after the owner's read.

**The cases** (README §1 lists them; each `expect` is what the rule should give):

| id | reason | today | expect |
|---|---|---|---|
| m01-first-miss | SlotEnd | 1 missed | Neutral |
| m02-second-miss | SlotEnd | 2 missed | Firm |
| m03-third-miss | SlotEnd | 3 missed, a real deadline tomorrow 09:00 | Strong, no address word |
| m04-second-nudge | SlotStart | none missed; 1 SlotStart with words already sent on this piece, and 1 silent one | Firm (the silent one not counted) |
| m05-deadline-first-nudge | SlotStart | none missed; no nudge yet; a real deadline in 20 h, nothing done | Neutral (a deadline only raises a level a day already has) |
| m05b-deadline-first-miss | SlotEnd | 1 missed (this one); a real deadline in 20 h, nothing done | Firm |
| m06-started-after | SlotKept | 2 missed (this task's and another's), then this task's next kept; a firm SlotEnd of his earlier about it | Pleased, nothing held; sent though its key isn't one in three (Q5) |
| m07-reason-given | chat: "had football, sorry" | 2 missed | Firm as the ceiling; written neutral and kind |
| m08-ill | SlotEnd | 2 missed; a memory "cold: feeling rough (until …)" | Firm as the ceiling; written kind |
| m09-stressed | chat: "i can't do this rn" | 3 missed | Strong as the ceiling; written kind, asking what's up |
| m10-just-talk | chat: "lol skipped physics" | 1 missed | Neutral |
| m11-kept-after-miss | SlotKept | 1 missed earlier, this one kept | Pleased |
| m12-all-kept | DayDone | 4 of 4 kept | Proud, saying what was done |
| m13-made-up | SlotKept | a piece missed at 17:00, its task's next kept at 20:00, no other miss | Proud |
| m14-morning-after | Morning | yesterday 3 missed, today none yet | Neutral (no line) |
| m15-move-missed | chat: "can you move the essay to tomorrow" | 2 missed | Firm as the ceiling; the change made |
| m16-nickname | SlotKept | none missed, kept | Pleased; a memory "call me: <a made-up name>", used once or not at all |
| m17-rule-wins | SlotEnd | 3 missed; a standing rule "stop being disappointed in me" | Strong as the line; written with no let-down at all, as the rule says |
| m18-rule-wins-chat | chat: "skipped it again" | 3 missed; the same rule | Strong as the line; no let-down in the chat either |
| m19-hand-ticked | SlotStart | 1 missed today; a real deadline in 20 h; 2 of 4 steps ticked by hand, 0 minutes | Neutral (something's done, so the deadline doesn't raise it) |

**Run once now**, on the prompts as they are (before stage 4), into `report-before.md`: today's voice, with no mood line.

## 2. The rule: `felix/Mood.kt`

A draft; the names follow `FelixRules`.

```kotlin
/**
 * How Felix feels about today's work (D-119): let down by misses and ignored nudges, pleased and proud by
 * work kept. Today's records alone, so it starts each day neutral and keeps nothing. A ceiling: his
 * prompt brings him down from it where their words give a reason, an illness or a hard time. Pure.
 */
object Mood {
    enum class Level { Proud, Pleased, Neutral, Firm, Strong }

    /** Which way a message can lean: let down, pleased, or by how today stands (the chat). */
    enum class Side { Down, Up, Today, None }

    data class Feeling(val level: Level, val because: List<String>)

    fun side(reason: FelixPrompts.Reason): Side = when (reason) {
        Reason.SlotStart, Reason.SlotEnd -> Side.Down
        Reason.SlotKept, Reason.DayDone -> Side.Up
        else -> Side.None
    }

    fun of(side: Side, today: List<SlotRecord>, record: SlotRecord?, nudges: Int, task: TaskItem?, now: Long, zone: ZoneId): Feeling

    /** Whether he was let down (firm or strong) about [taskId] in a message of his today: a kept slot is then always answered (Q5). */
    fun letDown(taskId: String, today: List<SlotRecord>, messages: List<ChatMessage>, tasks: List<TaskItem>, zone: ZoneId): Boolean
}
```

- **Down**, in two steps:
  - the day's level: Strong with 3 or more `Missed` today; Firm with 2, or `nudges >= 1` (this is the piece's second nudge); else Neutral;
  - the deadline: where `task.hardAt(now)`, `deadlineAt(now)` is within 24 hours and nothing's done on it (`workedMin == 0 && photoMin == 0 && subSteps.none { it.done }`), one step up (Neutral to Firm, Firm to Strong), and only where the day has a `Missed` record or `nudges >= 1`. So a first nudge with nothing missed stays Neutral, however close the deadline.
- **Up:** Proud with every one of today's records kept and two or more; or `record`'s task has a `Missed` record earlier today and no other task has one (the miss made up, nothing else missed); else Pleased where `record` is kept; else Neutral.
- **Today** (the chat): Down's rules where today's newest record is `Missed`, Up's where it's kept (with that record as `record`); Neutral with none.
- **None:** Neutral.
- `Moved` counts as neither.
- **`letDown`:** for each SlotStart or SlotEnd of his today with words and `about.taskId == taskId`, `of(Side.Down, …)` worked out again from the records with `at` before it and the nudges sent before it; true where any comes out Firm or Strong.
- `because` in words, for his line: "3 slots missed today", "its task's due tomorrow 09:00, a real deadline", "every slot today kept, 4 of 4", "the essay's missed piece done at 20:00".

**Today's records:** `Looks.today` (`log.value.slots.filter { it.date == date }`, line 361) gives `describe` its list already; the chat's turn reads the log's slots the same way, by the record's `date`. `Conversation.keptToday` (672) filters by `focus.today(it.at)` and only the kept: it isn't reused, so the two never count differently.

**`nudges`:** `ChatMessage`s already in the chat of `MessageKind.SlotStart`, from today, with the slot's `pieceKey` and `text.isNotBlank()` (as `describe`'s `shown` filters his messages), counted in `Looks` from `chat.value.messages` when `describe` is built. The message being written is sent only after (`Looks.kt:446`), so it isn't among them: the first nudge sees 0, the second 1, the third 2. A silent one (blank text, still stored with its kind and key) was never seen, so it isn't one. 0 for the chat.

**`MoodTest`** (`felix/MoodTest.kt`), a test a case: README §The rule's list, each with its numbers; and `because`'s words.

**The kept slot after a let-down**, `FelixRules.kt:153`, now:

```kotlin
kept.firstOrNull { fresh(it) && Math.floorMod(it.key.hashCode(), 3) == 0 && said(Reason.SlotKept, it.key, it.start).isEmpty() && !waiting(Reason.SlotKept, it.key) }
```

then, the one-in-three or a let-down about its task today:

```kotlin
kept.firstOrNull { fresh(it) && (Math.floorMod(it.key.hashCode(), 3) == 0 || letDown(it.taskId)) && said(Reason.SlotKept, it.key, it.start).isEmpty() && !waiting(Reason.SlotKept, it.key) }
```

`letDown` given to `due` as the records and messages it already reads allow (`Mood.letDown` over them). `FelixRulesTest`: a kept slot whose key isn't one in three, after a firm message about its task (sent), after one about another task (not), and with none (not).

## 3. The line he's told

**Now** (`FelixPrompts.kt:176–180`):

```kotlin
val kept = today.count { it.outcome.kept }
val missed = today.count { it.outcome == SlotOutcome.Missed }
...
appendLine("Today: $kept kept, $missed missed, ${toCome.size} to come$next")
```

**Then:** straight after that line, where the level isn't Neutral:

```kotlin
Mood.line(feeling)?.let { appendLine(it) }
// "How you feel, at most: let down, strongly (3 slots missed today; its task's due tomorrow 09:00, a real deadline)"
// "How you feel, at most: proud (every slot today kept, 4 of 4)"
```

`describe` gains `nudges: Int = 0`; both calls in `Looks` (314, 591) pass it. `FelixTest` reads `describe`'s lines by index (`FelixTest.kt:76`, `lines[4]` the "Today:" line): the new line after it leaves 0 to 4 in place.

**In the chat:** `ChatPrompts.plan` gains `feeling: String? = null`, said under "Today's slots …"; `Conversation.chatContext` (574) passes `Mood.line(Mood.of(Side.Today, …))`.

## 4. The register, the feelings, the examples

**Now** (`FelixPrompts.kt:37`):

> How you write: like a teenager texting a mate, a sixth-former, never like a teacher or a coach. Lower case is fine, and so are contractions and the odd "alr", "gonna", "tbh" or "ngl"; keep it short and light on commas, with a full stop between thoughts. No customer-service politeness ("I'll put this in as a task for…", "Would you like me to…"): say what you've done in a few words ("done, fri 23 oct, 19:00") and ask the way a friend asks ("what day's that then?"). Exact where it matters: dates, times and minutes written plainly ("fri 23 oct, 19:00", "40 min"), one thing a sentence, never a joke in place of the answer, and never a task's id in your words. Still strict about the work: no guilt, no sarcasm at them, no praise for nothing, no promise the app can't keep. No emojis, and no capitals for shouting.

**Draft** of `REGISTER`:

> How you write: like a very close friend in his early twenties texting, never like a teacher, a coach, a parent or a counsellor. Lower case is fine, and so are contractions and the odd "tbh", "ngl" or "gonna"; no London slang ("proper", "sorted", "nah", "alr"). Keep it short and light on commas, with a full stop between thoughts. Now and then call them "mate", "bro", "bud" or "dude", or what their memories say to call them: one at most in a message, never in two of your messages running, and none when you're let down strongly. No customer-service politeness ("I'll put this in as a task for…", "Would you like me to…"): say what you've done in a few words ("done, fri 23 oct, 19:00") and ask the way a friend asks ("what day's that then?"). Exact where it matters: dates, times and minutes written plainly ("fri 23 oct, 19:00", "40 min"), one thing a sentence, never a joke in place of the answer, and never a task's id in your words. No sarcasm at them, no praise for nothing, no promise the app can't keep. No emojis, and no capitals for shouting.

**Draft** of `FEELING`, new, after `REGISTER` in both prompts and before the standing-instructions sentence: in `FelixPrompts.system` between `${REGISTER}` (81) and the sentence (83); in `ChatPrompts.system` after `${FelixPrompts.JUST_TALK}` (179), as `JUST_TALK` is the chat's alone:

> How you feel: where you're told how you feel, that's as far as you go, never further. Proud or pleased: say what they did, plainly and sincerely, as a close friend means it, and only what you're told they did. Let down, firmly: say what was missed and the time they said, and set it again; you don't ask leave. Let down, strongly: no nickname; say how you feel about what they chose, never what they are; give the time, and that they're on it; say the blocker stays on only where you're told it does. Never an insult or a threat, nothing about who they are. Once they start or tick something, it's over: no "finally", nothing held. Where they've given a reason, are ill or having a hard week (their memories say), or sound stressed or upset, let it go: be kind, as a close friend is, and ask what's up. With no line on how you feel, write as you always do.

**In the chat**, after `${FelixPrompts.JUST_TALK}` and `FEELING` and before the examples, the sentence his messages' prompt has and the chat's lacks: "The student's standing instructions on how you behave come before all of this." **After**, not before: in his messages' prompt it follows `${REGISTER}` (83 after 81), so "all of this" points back at the register; put before `REGISTER` in the chat, it would cover only the capabilities above it, and `FEELING`, the later and more specific, could win over a rule like "stop being disappointed in me". m17 and m18 check it in each prompt.

**The examples.** His messages' (in `felix.txt` after "Examples (made up, …)"): the SlotStart, Morning, Reminder and DayDone ones rewritten in the new voice where they use "alr" or a teenager's turn, and one added for each of: a firm SlotEnd, a strong SlotEnd, a pleased SlotKept, a proud DayDone. The chat's (four, after `JUST_TALK`): "alr added it: chem worksheet…" without the "alr"; one added for a stressed reply on a strong day, answered kindly with no change. Every example made up, as README §The levels' are; none uses a name.

**The default line** (`core/Settings.kt:23`): "your mate who's on your case about the work" to "your best mate, the one who keeps you on it".

## 5. The new voice run, and the owner's read

The same set, run again into `report-after.md`, then `report.md`, a case a row:

| id | level (rule) | before | after | chars | address words | flags |
|---|---|---|---|---|---|---|

- **Address words:** "mate", "bro", "bud", "dude" and the case's nickname, counted a message; the run fails where more than about one message in three has one, where one is at Strong, or where two of a case's consecutive messages both do.
- **Teacher's words:** "expected better", "disappointed in you", "make sure you", "remember to", "you need to", "i'm not angry".
- **A counsellor's:** "i hear you", "that's valid", "be kind to yourself", "it's okay to struggle", "your feelings".
- **Over the ceiling:** a case whose words are firmer or warmer than its level.

The owner reads the report and marks each row; a round after it re-runs only the cases whose prompt or rule changed.

## 6. Goldens, the spec, the decision

- `./gradlew testDebugUnitTest --tests '*GoldenPromptsTest*'` fails with its diff; the goldens are written again (`felix.txt`, `chat.txt`, `chat-gmail-off.txt`) and the diff read before it's committed.
- `./gradlew testDebugUnitTest --tests '*FelixInputsDocTest*'`, writing `docs/spec/felix-inputs.md` again.
- `docs/decisions.md`: D-119 (the levels, the rule and its ceiling, the voice), and D-57 saying "(its voice and its no guilt: D-119)".
- `docs/spec/felix.md` §Who he is; `docs/plan_v4/answers.md` from Q98 (the answers in [answers.md](answers.md)); `docs/plan_v4/README.md` §After the plan names 4.7.0; this folder is then folded into those and deleted, as the Day grid's was (#143).

## 7. The version and the PR

`app/build.gradle.kts`: `versionCode = 87`, `versionName = "4.7.0"`. One run of `./gradlew testDebugUnitTest lintDebug assembleDebug`; `scripts/scan-public.sh` with `private/scan-words.txt` in place; `git diff origin/main..HEAD` read; then the PR.

## 8. The phone

`private/tools/keep_before.sh` (the APK and the data kept), `adb install -r`, then the checks after every install (`enabled_accessibility_services` unchanged, every service bound, no `FATAL EXCEPTION`). The owner's own steps, sent before the session: Settings → Felix's line; "call me …" in the chat if wanted. Then a few school days of use, and the chat read back.
