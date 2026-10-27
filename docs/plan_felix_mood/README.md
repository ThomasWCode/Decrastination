# Felix's moods: let down when work is skipped, proud when it's done (4.7.0)

The owner's ask, 27 Oct 2026: disappointment in Felix's personality, with an imbalance of power between the two of them, Felix the one in charge and using it to get the work done; then the opposite too, proud and pleased; and his voice moved from a teenager's to a very close friend's in his early twenties, sincere, with the odd "mate", "bro", "bud" or "dude".

These files are what a fresh session needs to build it, without the conversation that planned it. Unlike the other plans' folders, condensed once their releases were built, this one is written before the build and holds its working:

- this README: what's built, the levels, the rule, the prompt, the stages with their estimates, and the choices made without asking;
- [answers.md](answers.md): every question put to the owner, what was offered and recommended and why, and the answer word for word;
- [build.md](build.md): stage by stage, the code as it is now (quoted, with its file and line on `main` at 24a8774), the drafts of the new prompt text and the rule, the tests' cases, and the scenarios' set and report;
- [evidence.md](evidence.md): what each claim here rests on: the code checked, the numbers taken, the overlap with 4.6.2's branch, and the research behind the levels' limits.

Where these files and the code disagree once the release is merged, the code and `docs/spec/` are right: this is the plan's record.

## One release

- **4.7.0: Felix let down when you skip, proud when you don't.** A pure rule, a line in what both his prompts are told, his register rewritten, its examples and goldens, a kept slot always answered after a let-down (Q5), and an eval set run on the laptop and read by the owner before anything is installed. One stored field: the level each message of his was written at (`ChatMessage.mood`, Q6), so `chat.json` and the archive's months are raised, with nothing migrated; the rule itself reads today's records and messages as they are.
- Branch `v4.7.0`, `versionCode` 87, `versionName` "4.7.0": after 4.6.2 (`versionCode` 86, merged as #145), its numbers each one up if another release goes first.
- Its decision is **D-119**, amending D-57 (the next free: D-118 is the highest on `main`); its questions go into `docs/plan_v4/answers.md` from **Q98** (Q97 is the highest, 4.6.2's), in a section of their own.

The rules of the house hold throughout: `CLAUDE.md`, `docs/development.md`, `docs/plan_v4/architecture.md` §The rules and §Conventions. Nothing personal in code, tests, fixtures, docs or the PR (D-79): every example and scenario is made up, and no real name is ever an example's address word. No new library, so there's nothing to install. Before the first edit: `PROGRESS.md` from `private/tools/progress.py init`, one item a stage below with its estimate, kept current and deleted before the PR is called done; never staged.

## What it goes against, and why it's still built

- **D-57's "no guilt".** `FelixPrompts.REGISTER` ends "Still strict about the work: no guilt, no sarcasm at them, no praise for nothing, no promise the app can't keep." Disappointment is the guilt it rules out, so D-119 amends it; "no sarcasm at them", "no praise for nothing" and "no promise the app can't keep" stay.
- **D-57's "a sixth-former's voice".** The owner's word (answers.md §The age): early twenties, sincere. Still lower case, short, exact where it matters, no emojis, never a teacher's, a coach's or a parent's.
- **The risk, said to the owner and kept here.** Shame tends to feed procrastination (putting work off is mostly mood repair, and guilt is a worse mood); being let down by the broken commitment ("you said 19:00"), with a way straight back, is the kind that helps. So the rule's strongest level is still about the choice, never about who the student is, and every level ends the moment the work starts. The owner chose to go up to strong (Q2) knowing this; the laptop scenarios (stage 5) are where it's judged.
- **Felix's power is the power he already has.** He sets the time and says it plainly; he names the blocker only where `Focus.blockingLine` says it's on. He gets no control of the blocker or protection (D-61), and no new consequence of his own.

## The owner's answers (27 Oct)

In full, with what was offered and why, in [answers.md](answers.md); in short:

- **Q1.** A release with the code change, but example outputs for many scenarios made on the laptop and read before anything touches the phone (stages 1 and 5, the phone only after, stage 8).
- **Q2.** By how many misses a day and how serious, from firm up to strong; and the opposite, pleased and proud (§The levels).
- **Q3.** The app decides the level (§The rule).
- **The voice, at the owner's word:** a very close friend's, not a teacher's, with "mate", "bro", "bud", "dude"; the power imbalance kept (§The prompt).
- **Q4.** A personal nickname only through a memory, never in the repository: no code.
- **The age, at the owner's word:** early twenties, sincere and sweeter, no London slang ("proper proud of you mate" was the draft that prompted it).
- **Q5** (from the PR's review): a kept slot is always answered when he was let down about its task earlier today, so the let-down is never his last word on it (§The rule, §A kept slot after a let-down).
- **Q6** (from its second round): the level is kept on his message, as the records it rested on change once the piece is kept (§The level kept on his message).

## The levels

Made-up lines, to show the voice; the examples in the prompts are of the same kind.

| Level | When (today) | Address words | Example |
|---|---|---|---|
| Proud | the day done (DayDone) with every slot kept, two or more, none made up late; or this kept slot a make-up of its own miss, with no other miss or make-up today | warmest | "you did every slot today, even the physics you'd been putting off. really proud of you bud." |
| Pleased | any other slot kept: a make-up with other misses today, a kept slot with work still to come, a day done after make-ups | easy | "you came back and did the one you missed earlier. that means a lot, honestly." |
| Neutral | the default; a first miss; a first nudge, even with a deadline close; every morning | now and then | "physics slot's gone dude. what happened?" |
| Firm | two missed today; or this piece's second nudge (one already sent); or a first miss with a real deadline within 24 hours and nothing done on it | one, said flatly | "that's two today. you said 19:00, so let's make it 19:00." |
| Strong | three or more missed today; or firm's case with such a deadline | none: the missing nickname is the signal | "three gone today and it's due tomorrow. i know you can do this, that's why this one gets to me. 19:30, you're on it." |

**A deadline raises the level one step, never sets it.** A real deadline within 24 hours with nothing done takes neutral to firm and firm to strong, and only where the day already has a miss or this piece has had a nudge. So the first nudge of an ordinary evening stays neutral, even though the planner lays most work the evening before it's due (`duesoon`).

And once the work is kept after any of the negative three: straight back, nothing held ("there you go. knew you had it in you."), and always said (Q5: §A kept slot after a let-down).

## The rule

`felix/Mood.kt`, pure, beside `FelixRules` (the app's pure rules for Felix; not `:core`, as `SlotRecord` and `SlotOutcome` are the app's, in `data/RuntimeState.kt`), tested by `felix/MoodTest.kt` as `FelixRulesTest` tests `FelixRules`:

- `enum class Level { Proud, Pleased, Neutral, Firm, Strong }` and `Mood.of(side, today, record, nudges, task, now, zone)`, giving the level and the reasons it rests on, in words.
- **Its inputs are what `FelixPrompts.describe` has already:**
  - `today`: `List<SlotRecord>` (`outcome.kept`, `SlotOutcome.Missed`; `Moved` is a move on purpose, never a miss), one record a piece's key: **a missed piece kept later replaces its `Missed` record** with a `KeptLate` one under the same key, at the keep's time (`Focus.keep` finds the miss, 348; `finish` swaps it, 422), and adds to the task's minutes worked (398–403). So a miss made up is no longer a miss in `today`: it's known as a **make-up**, a record `KeptLate` with `at` after its `end` and a SlotEnd of his today with its `pieceKey`;
  - `record`: the slot record the message is about, for a SlotKept or DayDone;
  - the task: `TaskItem.hardAt(now)` and `deadlineAt(now)`, and "nothing done on it": `workedMin` and `photoMin` 0 and none of its `subSteps` done (a step ticked by hand adds no minutes, so it's read from `subSteps`; `stepsWorkedMin` is a part of the first two, not a count of steps);
  - `nudges`: his SlotStart messages **already sent** today with this slot's `ChatMessage.pieceKey`, and only those with words (a silent one is stored with no text and was never seen). The one being written isn't in the chat yet, so the first nudge counts 0, the second 1.
- **By the reason:** a SlotStart or a SlotEnd gone by undone takes the negative side (neutral, firm, strong); a SlotKept or DayDone the positive (neutral, pleased, proud); the chat's turn the side today's latest record is on; the Morning, a Reminder, a Question, Completions, the check-in and the summary are always neutral, so a new day starts clean and nothing he only passes on is coloured.
- **On the negative side, a make-up is no longer a miss:** the work done takes the count down.
- **Per day:** only today's records count, so it resets at midnight. The level is worked out afresh for each message; what's kept is the level each message of his was written at (§The level kept on his message), as the records it rested on don't stay as they were.
- **A ceiling, not an order.** The app can't read words: a reason given ("had football"), an illness or a hard week in his memories, or a reply that's stressed or upset brings him down to neutral and kind, and words never take him higher. The prompt says so (§The prompt).

`MoodTest` holds each level and its edges:
- none missed; one, two and three missed; `Moved` not counted;
- the first nudge (0 sent) neutral, the second (1 sent) firm, a silent one not counted;
- a first nudge with the deadline 20 hours off and nothing done: neutral; a first miss with it: firm; a second nudge with it: strong;
- a real deadline 23 and 25 hours away; a deadline that's only a plan (`hardAt` false) never counted;
- nothing done against a few minutes worked, against a photo's minutes, and against a step ticked by hand with no minutes;
- built as the app writes them (one record a key, a miss replaced by its `KeptLate`): a make-up with no other miss (proud), with another task's miss or make-up (pleased), two make-ups and nothing else (pleased, not "every slot kept"), a later kept slot of another task that day (pleased), a `KeptLate` within its slot's time with no SlotEnd (kept, not a make-up);
- every slot kept on DayDone, with one record and with two (proud from two); two kept on a SlotKept with a slot still to come (pleased: never "every slot" before the day's done);
- yesterday's misses not counted after midnight; each reason's side.

## A kept slot after a let-down (Q5)

A SlotKept is written for about one kept slot in three (`FelixRules.due`: `floorMod(key.hashCode(), 3) == 0`), and nothing is sent when work starts, so after a firm or strong message the let-down would usually be his last word. So a kept slot is always answered where he was let down about its task earlier today:

- `Mood.letDown(taskId, messages, zone, now)`, pure: whether any message of his today about that task (`ChatMessage.about.taskId`, with words) was written at Firm or Strong, read from the level kept on it (§The level kept on his message). Not worked out again from the records: keeping the piece has replaced its miss and added to its minutes, so a replay would mostly give Neutral (the PR's review, answers.md Q6).
- `FelixRules.due`: a kept slot after a let-down gets its own arm **above** `due`'s two early exits, beside DayDone: the one-in-three SlotKept sits after `if (work.none { it.end > now }) return null` (141) and `if (paced) return null` (142), and his let-down message may itself have set a later check, or the slot may be the evening's last. Still fresh (`FRESH_MS`, 30 minutes), once a slot (`said`), and not waiting; the one-in-three arm stays where it is for the rest.
- One more call a recovery, and only after a let-down: the owner's call under the cost rule (answers.md Q5).
- `FelixRulesTest`: a kept slot after a firm message about its task (sent), after one about another (not), with none (not), as the evening's last slot (sent), and with his next check not reached (sent).

## The level kept on his message (Q6)

- `ChatMessage.mood`: the level his message was written at, as a word (`"Firm"`, `"Strong"`, `"Pleased"`, `"Proud"`), empty for neutral and for every message not his: a plain field, empty for none, never nullable. Set in `Looks` from the `Mood.of` its `describe` used, when the message is made (`Looks.kt:431–446`); the chat's answers keep it empty, as only his own messages are counted.
- A stored field with a default, so a raise with nothing to change (`docs/plan_v4/data-and-migrations.md`, rule 6): `chat.json` from version 9 to 10 and the archive's months from 8 to 9 (`core/Chat.kt:21`, `114`), `MoodMigration` beside `OutsideMigration` in `data/Stores.kt:63`, nothing migrated, and the fixture `fixtures/stores/<date>-4.7.0`, as 4.5.1 to 4.6.0 did. An older build sets the newer file aside whole rather than read it without the field.
- Tests: the migration from every earlier shape, as the store's others are; `BackupTest` with the field kept; `LooksTest` that a firm message is kept with its mood, and a silent one with none.

## The line he's told

- `FelixPrompts.describe`: after "Today: … kept, … missed …", one line from `Mood.of`, e.g. "How you feel, at most: let down, strongly (3 slots missed today; its task's due tomorrow 09:00, a real deadline)", or "How you feel, at most: proud (every slot today kept, 4 of 4)". None at neutral, so most messages read as they do now.
- `ChatPrompts.plan`: the same line for the chat's turn (no nudges), under today's slots, so the chat and his messages feel the same (D-95: the chat is his one voice).
- `Looks` and `Conversation` pass what it needs (the nudges count from the chat's messages), and `Looks` keeps the level on the message it sends (§The level kept on his message); nothing else in their flow changes.

## The prompt

- **`FelixPrompts.REGISTER`**, rewritten, still in both prompts: like a very close friend in his early twenties texting, never like a teacher, a coach, a parent or a counsellor; lower case, contractions, the odd "tbh", "ngl" or "gonna"; no London slang ("proper", "sorted", "nah", "alr"). Address words "mate", "bro", "bud", "dude", or a name the profile's memories give: at most one a message, never in two of his messages running (he's shown his last six), none at strong. Exact where it matters, no emojis, no capitals for shouting; no sarcasm at them, no praise for nothing, no promise the app can't keep.
- **`FelixPrompts.FEELING`**, new, after `REGISTER` in both prompts and before the standing-instructions sentence: in his messages' prompt between `${REGISTER}` (line 81) and that sentence (83); in the chat's, after `${FelixPrompts.JUST_TALK}` (179), which his messages' prompt doesn't have. What each level sounds like (the table's middle column in words); the warm levels sincere and specific, saying what was done; firm and strong saying the time and not asking leave, the blocker only where he's told it's on; never an insult or anything about who they are; at strong, how he feels about the choice, never what they are; once work starts, over at once; the line a ceiling, brought down by a reason, an illness or a hard week remembered, or a stressed or upset reply, to neutral and kind; with no line, as he is now.
- **The standing instructions first in the chat too.** His messages' prompt says the student's standing instructions on how he behaves "come before all of this"; the chat's doesn't say so (evidence.md §The code). The chat gets the same sentence, **after** `REGISTER`, `JUST_TALK` and `FEELING` and before the examples, as his messages' prompt has it after `REGISTER`: "all of this" then points back at the register and the feelings, so a rule like "stop being disappointed" wins over `FEELING` in both. Put before `REGISTER`, it would cover only the capabilities above it. The scenarios hold a case for it in each prompt (m17, m18).
- **The examples** in both prompts, rewritten in the new voice (the chat's "alr added it…" among them), with one for each level, a recovery, a reason given and a stressed reply. All made up.
- **The default line** (`Settings.felixLine`, "your mate who's on your case about the work") becomes "your best mate, the one who keeps you on it". The phone keeps the line it has stored (`JsonStore` writes with `encodeDefaults = true`): the owner changes it in Settings → Felix, a step of the phone session.

## Stages

Each with its estimate; about 5 h 45 min in all, besides the owner's reading of the outputs and the days of use.

### 1. The scenarios' harness, and today's voice run (60 min)

- `EvalRunTest` gains a third test, `-PrunEval=mood`, beside `true` (the readings' set) and `chat` (`private/eval-chat`): neither asks his own messages. Its cases are in `private/eval-felix-mood/` (git-ignored, made up all the same), each a reason, a plan, today's records, the recent messages and the memories. They're sent through `FelixPrompts.system`/`describe` and `FelixCaller.say`, or for a chat case through the Conversation on temporary stores as the chat's set does, exactly as the app sends them. Each answer goes through `FelixAnswers.parse` or `ChatAnswers.parse`, so an unreadable one is a failure, not a nice line. **Its length is checked before that:** `parse` doesn't refuse a message past 240 characters (480 for the Morning), it cuts it at a sentence or a word (`bounded`, `message`), so a let-down could lose its "19:30, you're on it" and still read fine. `FelixCaller.Result` gains `cut` (the message was longer than its room), and the report has it as a column beside the text as the phone would show it; a cut message fails its case. [build.md](build.md) §1 has the case file's shape.
- **The budget is the chat set's soft guard**, not the readings' hard one: `EVAL_BUDGET_USD`, 3 dollars unless set, a run stopping before a case once what's left is under twice the dearest case so far. The readings' guard (2 dollars, each call held to `Pricing.WORST_CALL_USD`, $1.35) would stop a run at about $0.65 and quietly leave cases out of a report. A case left out is said in the report, never dropped silently.
- The cases (19): a first miss with nothing before it; a second miss today; a third; a second nudge; a first nudge with a real deadline tomorrow and nothing done (neutral); a first miss with it (firm); the work kept straight after a let-down message; a miss with a reason given ("had football"); a miss with an illness remembered; a stressed reply ("i can't do this rn"); just talk ("lol skipped physics"); a slot kept after a miss; every slot kept, DayDone; a missed piece made up; the morning after a bad day; a chat turn asking to move a missed piece; a memory naming what to call them; a rule "stop being disappointed in me" on a strong day, in his messages and in the chat.
- Run once on the prompts as they are (before stage 4), into `report-before.md`: 19 calls, the chat's with their rounds. The first case's cost is printed, as the chat's set prints each, and the budget set from it before the rest.
- Each run with `--rerun`: inputs Gradle sees as unchanged would otherwise leave the test up to date and skipped (`app/build.gradle.kts`, the chat set's note).

### 2. `Mood`, the kept slot after a let-down, and their tests (75 min)

As §The rule and §A kept slot after a let-down. `./gradlew compileDebugUnitTestKotlin`, then `./gradlew testDebugUnitTest --tests '*MoodTest*' --tests '*FelixRulesTest*'`.

### 2b. The level kept on his message (45 min)

As §The level kept on his message: the field, `Looks` setting it, `MoodMigration`, the fixture, and the migration's, the backup's and `LooksTest`'s tests (`--tests '*Migration*' --tests '*BackupTest*' --tests '*LooksTest*'`). A make-up's cases go through a real keep (`Focus` on temporary stores, as `block/FocusTest.kt` does), not records written by hand, so the replaced miss is the app's own.

### 3. The line in both contexts (30 min)

As §The line he's told. `FelixTest` reads `describe`'s lines by index (its `lines[4]` is "Today: …"): the new line goes straight after "Today:", so lines 0 to 4 keep their places; `LooksTest` and `ChatTest` checked for asserts on the context's exact lines, each changed only where the new line falls inside one.

### 4. The register, the feelings, the examples (40 min)

As §The prompt.

### 5. The new voice run, and the owner's read (50 min, then the owner)

- The same cases into `report-after.md`, and `report.md` side by side: each case's line before and after, its level, its length and whether it was cut (`FelixCaller.Result.cut`), and three flags: address words per message across the run (more than about one in three is a fail, as is one at strong), teacher's words ("expected better", "disappointed in you", "make sure you", "remember to"), and a counsellor's ("i hear you", "that's valid", "be kind to yourself", "it's okay to struggle").
- 19 calls and the chat's rounds. At the chat set's $0.07 to $0.16 a call (`EvalRunTest`'s note), a run is about $1.50 to $3.50, the two about $3 to $7, each under its budget and the cap; stage 1's first case gives the real figure. (An earlier draft said "well under a dollar": that counted neither the chat's rounds nor any measured price.)
- The owner marks the report; each round after it re-runs only the cases whose prompt or rule changed (about 20 min a round).

### 6. Goldens and the spec (25 min)

- `GoldenPromptsTest`: `felix.txt`, `chat.txt` and `chat-gmail-off.txt` written again, the diff in the PR; `profile.txt` unchanged. `ReadingPrompts.VERSION` stays: readings don't change.
- `FelixInputsDocTest`, writing `docs/spec/felix-inputs.md` again for the new line and examples.
- `docs/spec/felix.md` §Who he is: his voice, the levels, the rule and its ceiling; D-119 in `docs/decisions.md`, D-57 pointing to it; the answers above in `docs/plan_v4/answers.md` from Q98; `docs/plan_v4/README.md` §After the plan names 4.7.0; `docs/plan_v4/data-and-migrations.md` gains 4.7.0's entry (`MoodMigration`, `chat.json` 10 and the archive's months 9, nothing migrated, its fixture), and `docs/spec/felix.md` §The chat says a message of his keeps its mood.

### 7. The version and the PR (25 min)

`app/build.gradle.kts` to 87 and "4.7.0"; one run of `./gradlew testDebugUnitTest lintDebug assembleDebug`; `scripts/scan-public.sh` and `git diff origin/main..HEAD` read for anything personal; PR `4.7.0: Felix let down when you skip, proud when you don't`. Reviews only at the owner's word.

### 8. The phone (the owner, then a few days of use)

Only once the owner has signed stage 5's report off. `keep_before.sh`, `adb install -r`, the accessibility services checked as after every install. The owner's steps: Settings → Felix's line to the new one (or theirs); in the chat, "call me …" if wanted. Then real use: the levels can't be faked well on the phone, so a few school days are the test, with the chat read back afterwards.

## Decided without asking

1. The rule sits in `felix/`, not `:core`: its inputs are the app's types.
2. Neutral prints no line, so a quiet day's messages are as they are now and cost no more.
3. The Morning and the messages that only pass something on (a reminder, a reading's question, completions, the check-in, the summary) are always neutral.
4. "Nothing done on it" is `workedMin` and `photoMin` 0 and no step done, a hand-ticked one included; a deadline that's only a plan never counts; a close deadline raises the level a step, only on a day with a miss or a piece already nudged, never on a first nudge alone.
5. The second nudge on a piece is the firm one (one already sent), not the third: SlotStart is asked at most three times a slot (`FelixRules.MAX_PER_SLOT`), so the third would come too late to matter. A silent SlotStart (his choice not to write) isn't a nudge.
6. A missed piece made up is proud only with no other miss or make-up that day, and only for the kept slot's own piece; with others, pleased. A bad day with one thing made up isn't a day to be proud of, and "no praise for nothing" stays. "Every slot kept" is said only on DayDone, when there's nothing left to come; a SlotKept with work still ahead is pleased.
7. The kept slot after a let-down is checked above `due`'s early exits (a later check he set, nothing left today), as Q5 says always; it still needs to be fresh, so one kept hours before a look isn't answered then.
8. An Anki piece counts like any other: a missed quota is a missed slot.
9. No tone setting (D-57 has none): a standing instruction ("stop being disappointed") turns it down, as the sentence placed after the register and the feelings says, in both prompts (§The prompt).
10. Misses across days aren't told him: a follow-up, once real use shows whether a single day is enough.
11. On the negative side a make-up isn't counted as a miss: the work done takes the day's count down.
