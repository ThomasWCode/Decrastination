# Felix's moods: what the plan rests on

Each claim the plan makes about the code, the numbers it takes and the research behind its limits, with where it was checked (27 Oct 2026, `main` at 24a8774, 4.6.2's branch at 4b59be2a). Read again before building: a claim about the code can go stale with any release.

## The code

| Claim | Where | What was found |
|---|---|---|
| His tone is one paragraph, in both prompts | `felix/FelixPrompts.kt:37`; `felix/ChatPrompts.kt:177` | `REGISTER` is a `const val`; `ChatPrompts.system` interpolates `${FelixPrompts.REGISTER}`, and `FelixPrompts.system` has it at line 81 |
| It rules out guilt today | `FelixPrompts.kt:37` | "Still strict about the work: no guilt, no sarcasm at them, no praise for nothing, no promise the app can't keep." |
| It's D-57's voice | `docs/decisions.md`, D-57 | "Felix, a strict friend … in a sixth-former's voice … a teenager's, never a teacher's or a coach's … with no tone setting" |
| His messages' prompt puts standing instructions first | `test/resources/prompts/felix.txt` | "The student's standing instructions on how you behave (with what you're told about them) come before all of this" |
| The chat's prompt doesn't | `ChatPrompts.system` (160 on); `enrich/Profile.kt:84` | No such sentence in the chat's system prompt; the profile lists them under "How Felix should behave:" with nothing saying they come first. So the build adds the sentence (README §The prompt) |
| What a message of his is told of today | `FelixPrompts.kt:176–180` | "Today: N kept, N missed, N to come", from the `SlotRecord`s passed in |
| Where those records come from | `felix/Looks.kt:361` | `log.value.slots.filter { it.date == date }`, `date` being `focus.today(now)` |
| The chat's kept records are counted otherwise | `felix/Conversation.kt:672` | `keptToday` filters the kept only, by `focus.today(it.at)`: so the rule reads the log as `Looks` does, and doesn't reuse it |
| Slot outcomes, and that a move isn't a miss | `data/RuntimeState.kt:118` | `Kept`, `KeptLate`, `Missed`, `Moved` ("moved on purpose"); `kept` is `Kept` or `KeptLate` |
| The records are the app's types, not `:core`'s | `data/RuntimeState.kt:118`, `136` | `SlotOutcome` and `SlotRecord` are in the app module: the rule sits in `felix/` beside `FelixRules`, not in `:core` |
| A real deadline can be told from a plan's date | `core/Task.kt:247`, `250` | `deadlineAt(now)` and `hardAt(now)` (the date in force is the real one) |
| "Nothing worked on it" can be read | `core/Task.kt:148`, `168` | `workedMin`, `stepsWorkedMin` (and the photo's minutes beside them) |
| His nudges on a piece can be counted | `core/Chat.kt:57`, `63` | `ChatMessage.kind` (`MessageKind.SlotStart`) and `pieceKey` |
| He's asked at most three times a slot | `docs/spec/felix.md` §His messages | "SlotStart … at most three times a slot": hence two nudges for firm, not three |
| The length his answers may have | `felix/FelixAnswers.kt:10`, `13` | `MAX_MESSAGE = 240`, `MAX_MORNING = 480` |
| How his message is asked | `felix/FelixCaller.kt:26` | `say(system, profile, user, maxMessage)` |
| A test reads `describe` by line | `test/…/felix/FelixTest.kt:76` | `assertTrue(lines[4].startsWith("Today: 1 kept, 0 missed, 2 to come"))`: a line added straight after keeps 0 to 4 in place |
| The existing eval sets don't ask his messages | `test/…/enrich/EvalRunTest.kt:50`, `112` | `-PrunEval=true` asks the readings' set (`ClaudeEnricher`), `-PrunEval=chat` the chat's; a third test is needed for his messages. Its budget guard: `EVAL_BUDGET_USD`, 2 dollars unless set |
| Changing the default line doesn't reach the phone | `core/Settings.kt:23`; `data/JsonStore.kt:321`; `block/Focus.kt:52` | Settings are a `JsonStore<Settings>` (`settings.json`), written with `encodeDefaults = true`: the stored line is in the file, and stays as it is when the default changes |
| The profile has no name of the student's | `test/resources/prompts/profile.txt` | Year, subjects, school days, working hours, "From the student", "How Felix should behave", "Remembered": no name. So a nickname comes only from a memory (Q4) |
| He may not control the blocker | `docs/decisions.md`, D-61 | "Never blocking's, protection's or Claude's settings" |

## The numbers

| What | Found | How |
|---|---|---|
| The next decision | D-119 | The highest `**D-n` in `docs/decisions.md` is D-118, on `main` and on `origin/v4.6.2` |
| The first question | Q98 | The highest `**Qn` in `docs/plan_v4/answers.md` is Q95 on `main`, Q97 on `origin/v4.6.2` (its Q96 and Q97) |
| The version | 87, "4.7.0" | `app/build.gradle.kts`: 85 / "4.6.1" on `main`, 86 / "4.6.2" on `origin/v4.6.2` |

All three assume 4.6.2 merges first and nothing else does; each moves one up if another release goes in between.

## The overlap with 4.6.2's branch

`git diff origin/main origin/v4.6.2` on the files this folder's PR touches:

- `README.md`: 4.6.2 changes a line in lines 2–8; this PR adds a row to the docs table at line 15: no overlap.
- `CLAUDE.md`: 4.6.2 doesn't change it.
- `docs/plan_v4/README.md`: 4.6.2 changes line 166 (§After the plan's list of what's been built). This PR leaves it alone so the two don't conflict; the build names 4.7.0 there (build.md §6).

## The research

The levels' limits (disappointment about the choice, never the person; over the moment work starts; dropped for a reason, an illness or distress) rest on three findings. They're cited from memory, not looked up again while this plan was written: check them before quoting them anywhere else.

- **Putting work off is mostly mood repair.** Procrastination is the short-term relief of a bad feeling about the task, at the cost of the future self; anything that makes the task feel worse (guilt among them) feeds it. Sirois, F. M., & Pychyl, T. A. (2013). Procrastination and the priority of short-term mood regulation: Consequences for future self. *Social and Personality Psychology Compass*, 7(2), 115–127.
- **Forgiveness after a lapse cut the next lapse.** Students who forgave themselves for putting off revision for a first exam put off less before the next one. Wohl, M. J. A., Pychyl, T. A., & Bennett, S. H. (2010). I forgive myself, now I can study: How self-forgiveness for procrastinating can reduce future procrastination. *Personality and Individual Differences*, 48(7), 803–808. Hence: once work starts, it's over, nothing held.
- **Guilt about an act helps; shame about the self doesn't.** Guilt ("I did a bad thing") goes with putting it right; shame ("I am bad") with hiding and avoiding. Tangney, J. P., Stuewig, J., & Mashek, D. J. (2007). Moral emotions and moral behavior. *Annual Review of Psychology*, 58, 345–372. Hence: strong says how he feels about what was chosen ("that's why this one gets to me"), never what they are.

What the research doesn't settle is whether being let down by a friend-like voice in an app works on its reader. That's what the laptop scenarios (how it reads) and the days of use (whether it helps) are for, and why a standing instruction can still turn it down.
