# Felix's moods: what the plan rests on

Each claim the plan makes about the code, the numbers it takes and the research behind its limits, with where it was checked (27 Oct 2026, `main` at 24a8774, 4.6.2's branch at 4b59be2a; checked again on 28 Oct after the PR's review and 4.6.2's merge, `main` at 23f6596, whose merge changed none of the code quoted here). Read again before building: a claim about the code can go stale with any release.

## The code

| Claim | Where | What was found |
|---|---|---|
| His tone is one paragraph, in both prompts | `felix/FelixPrompts.kt:37`; `felix/ChatPrompts.kt:177` | `REGISTER` is a `const val`; `ChatPrompts.system` interpolates `${FelixPrompts.REGISTER}`, and `FelixPrompts.system` has it at line 81 |
| It rules out guilt today | `FelixPrompts.kt:37` | "Still strict about the work: no guilt, no sarcasm at them, no praise for nothing, no promise the app can't keep." |
| It's D-57's voice | `docs/decisions.md`, D-57 | "Felix, a strict friend … in a sixth-former's voice … a teenager's, never a teacher's or a coach's … with no tone setting" |
| His messages' prompt puts standing instructions first | `test/resources/prompts/felix.txt`; `FelixPrompts.kt:81`, `83` | "The student's standing instructions on how you behave (with what you're told about them) come before all of this", **after** `${REGISTER}` (81, the sentence at 83), so "all of this" covers the register |
| The chat's prompt doesn't | `ChatPrompts.system` (160 on); `enrich/Profile.kt:84` | No such sentence in the chat's system prompt; the profile lists them under "How Felix should behave:" with nothing saying they come first. So the build adds the sentence, after `REGISTER`, `JUST_TALK` and `FEELING` (README §The prompt; the PR's review found a first draft placing it before `REGISTER`, where it would cover none of them) |
| `JUST_TALK` is the chat's alone | `FelixPrompts.kt:40`; `ChatPrompts.kt:179` | Defined in `FelixPrompts`, used only by `ChatPrompts.system`; `FelixPrompts.system` doesn't have it |
| What a message of his is told of today | `FelixPrompts.kt:176–180` | "Today: N kept, N missed, N to come", from the `SlotRecord`s passed in |
| Where those records come from | `felix/Looks.kt:361` | `log.value.slots.filter { it.date == date }`, `date` being `focus.today(now)` |
| The chat's kept records are counted otherwise | `felix/Conversation.kt:672` | `keptToday` filters the kept only, by `focus.today(it.at)`: so the rule reads the log as `Looks` does, and doesn't reuse it |
| Slot outcomes, and that a move isn't a miss | `data/RuntimeState.kt:118` | `Kept`, `KeptLate`, `Missed`, `Moved` ("moved on purpose"); `kept` is `Kept` or `KeptLate` |
| The records are the app's types, not `:core`'s | `data/RuntimeState.kt:118`, `136` | `SlotOutcome` and `SlotRecord` are in the app module: the rule sits in `felix/` beside `FelixRules`, not in `:core` |
| A real deadline can be told from a plan's date | `core/Task.kt:247`, `250` | `deadlineAt(now)` and `hardAt(now)` (the date in force is the real one) |
| "Nothing done on it" can be read | `core/Task.kt:148`, `153`, `161`, `257` | `workedMin` (its kept slots' minutes), `photoMin` (a photo check's), and `subSteps` (`done`, `byHand`): a step ticked by hand adds no minutes, counted only in `handMin` (257), so it's read from `subSteps` |
| `stepsWorkedMin` isn't a count of steps | `core/Task.kt:163–168` | "Of [workedMin] and [photoMin], the minutes the source's own steps already leave out": a part of the two, an Anki deck's; the first draft read it as steps ticked, which the PR's review caught |
| His nudges on a piece can be counted | `core/Chat.kt:55`, `57`, `63` | `ChatMessage.about` (its `taskId`), `kind` (`MessageKind.SlotStart`) and `pieceKey` |
| The nudge being written isn't counted, and a silent one is stored | `felix/Looks.kt:431–446`, `591` | The message is made with `kind` and `pieceKey = trigger.key` and sent through the outbox whatever its text, blank for a silence; `describe` (591) is built before it's sent. So counting from the chat, the first nudge sees 0 and the second 1, and silent ones must be filtered out (`text.isNotBlank()`) |
| He's asked at most three times a slot | `felix/FelixRules.kt:48`, `149` | `MAX_PER_SLOT = 3`; SlotStart only while `said(...).size < MAX_PER_SLOT`: hence the second nudge is the firm one |
| A missed piece kept later replaces its miss | `block/Focus.kt:348`, `398–403`, `422` | `keep` finds today's `Missed` record of the same key; the task's `workedMin` is raised; `finish` writes `filterNot { it == k.replaces } + kept`, the kept record `at` the keep's time. So an earlier mood can't be worked out again from the records (both reviewers, the PR's second round), and a make-up is a `KeptLate` record after its slot's end, not a `Missed` beside a kept one. Hence the level kept on his message (Q6) |
| Two exits come before the SlotKept arm | `felix/FelixRules.kt:132`, `141`, `142`, `51` | DayDone at 132; then `if (work.none { it.end > now }) return null` and `if (paced) return null`; `FRESH_MS` 30 minutes. Hence the let-down's arm above them, beside DayDone |
| "Every slot kept" can't be read from the records | `felix/Looks.kt:361`; `FelixPrompts.kt:178` | `today` is the log's records; what's to come is the plan's (`toCome`). Hence "every slot" on DayDone only |
| A long answer is cut, not refused | `felix/FelixAnswers.kt:4–6`, `34–38`; `felix/FelixCaller.kt:22` | `parse` returns `bounded(...)`, whose `message` cuts at a sentence or a word; `Result` has no raw text. Hence `Result.cut` for the eval |
| The chat store's versions | `core/Chat.kt:21`, `114`; `data/Stores.kt:63`; `docs/plan_v4/data-and-migrations.md` rule 6 | `ChatState.version` 9, the archive month's 8, the chat's migrations listed to `OutsideMigration.CHAT`; a type gaining a field with a default still raises its store, nothing migrated (4.5.1: 8 and 7, 4.5.2: 9 and 8) |
| A kept slot is answered about one time in three | `felix/FelixRules.kt:153` | `Math.floorMod(it.key.hashCode(), 3) == 0`; nothing is sent when work starts. Hence Q5's change, so the let-down isn't his last word |
| The planner lays work the evening before it's due | `ChatPrompts.plan`'s reasons; `docs/spec/felix.md` | `duesoon` "(due today or tomorrow)"; the chat's prompt: "plans the work into the evenings before each is due". So a deadline within 24 h with nothing done is the ordinary evening, and can't set strong by itself |
| The length his answers may have | `felix/FelixAnswers.kt:10`, `13` | `MAX_MESSAGE = 240`, `MAX_MORNING = 480` |
| How his message is asked | `felix/FelixCaller.kt:26` | `say(system, profile, user, maxMessage)` |
| A test reads `describe` by line | `test/…/felix/FelixTest.kt:76` | `assertTrue(lines[4].startsWith("Today: 1 kept, 0 missed, 2 to come"))`: a line added straight after keeps 0 to 4 in place |
| The existing eval sets don't ask his messages | `test/…/enrich/EvalRunTest.kt:50`, `112` | `-PrunEval=true` asks the readings' set (`ClaudeEnricher`), `-PrunEval=chat` the chat's; a third test is needed for his messages |
| The two sets keep to two different budgets | `EvalRunTest.kt:58–66`, `96–110`, `126` | The readings': hard, `spent + Pricing.WORST_CALL_USD > budget`, 2 dollars unless set. The chat's: soft, 3 dollars unless set, stopping once what's left is under twice the dearest case so far; its note: "a case is about $0.07 to $0.16 a call, where holding each to the worst call's $1.35 would stop a $3 run before it starts". The new set takes the chat's |
| What the scenarios cost | the chat set's note above | 19 cases and the chat's rounds at $0.07 to $0.16 a call: about $1.50 to $3.50 a run, $3 to $7 for the two. An estimate from that note, not a measured run: stage 1's first case gives the real figure. No paid call was made to plan this |
| A re-run needs `--rerun` | `app/build.gradle.kts:70` | "(the chat's set: -PrunEval=chat --rerun)": unchanged inputs leave the test up to date |
| Changing the default line doesn't reach the phone | `core/Settings.kt:23`; `data/JsonStore.kt:321`; `block/Focus.kt:52` | Settings are a `JsonStore<Settings>` (`settings.json`), written with `encodeDefaults = true`: the stored line is in the file, and stays as it is when the default changes |
| The profile has no name of the student's | `test/resources/prompts/profile.txt` | Year, subjects, school days, working hours, "From the student", "How Felix should behave", "Remembered": no name. So a nickname comes only from a memory (Q4) |
| He may not control the blocker | `docs/decisions.md`, D-61 | "Never blocking's, protection's or Claude's settings" |

## The numbers

| What | Found | How |
|---|---|---|
| The next decision | D-119 | The highest `**D-n` in `docs/decisions.md` is D-118, on `main` before and after 4.6.2's merge |
| The first question | Q98 | The highest `**Qn` in `docs/plan_v4/answers.md` is Q97, 4.6.2's (merged as #145) |
| The version | 87, "4.7.0" | `app/build.gradle.kts`: 86 / "4.6.2" on `main` since #145 |

Each moves one up if another release goes in first.

## The overlap with 4.6.2

When this was written, 4.6.2 was still a branch: `git diff origin/main origin/v4.6.2` on the files this folder's PR touches showed `README.md` changed in lines 2–8 (this PR adds a table row at 15), `CLAUDE.md` untouched, and `docs/plan_v4/README.md` changed at line 166, §After the plan's list of what's been built. This PR left that file alone so the two wouldn't conflict. 4.6.2 has since merged (#145, 23f6596) and changed no code this plan quotes (`git diff --stat 24a8774 23f6596` on them: only `app/build.gradle.kts` and `docs/plan_v4/answers.md`). The build names 4.7.0 in `docs/plan_v4/README.md` (build.md §6).

## The research

The levels' limits (disappointment about the choice, never the person; over the moment work starts; dropped for a reason, an illness or distress) rest on three findings. They're cited from memory, not looked up again while this plan was written: check them before quoting them anywhere else.

- **Putting work off is mostly mood repair.** Procrastination is the short-term relief of a bad feeling about the task, at the cost of the future self; anything that makes the task feel worse (guilt among them) feeds it. Sirois, F. M., & Pychyl, T. A. (2013). Procrastination and the priority of short-term mood regulation: Consequences for future self. *Social and Personality Psychology Compass*, 7(2), 115–127.
- **Forgiveness after a lapse cut the next lapse.** Students who forgave themselves for putting off revision for a first exam put off less before the next one. Wohl, M. J. A., Pychyl, T. A., & Bennett, S. H. (2010). I forgive myself, now I can study: How self-forgiveness for procrastinating can reduce future procrastination. *Personality and Individual Differences*, 48(7), 803–808. Hence: once work starts, it's over, nothing held; and, as the lapse let go only helps when it's said, a kept slot after a let-down is always answered (Q5).
- **Guilt about an act helps; shame about the self doesn't.** Guilt ("I did a bad thing") goes with putting it right; shame ("I am bad") with hiding and avoiding. Tangney, J. P., Stuewig, J., & Mashek, D. J. (2007). Moral emotions and moral behavior. *Annual Review of Psychology*, 58, 345–372. Hence: strong says how he feels about what was chosen ("that's why this one gets to me"), never what they are.

What the research doesn't settle is whether being let down by a friend-like voice in an app works on its reader. That's what the laptop scenarios (how it reads) and the days of use (whether it helps) are for, and why a standing instruction can still turn it down.
