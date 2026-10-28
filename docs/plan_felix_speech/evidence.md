# Felix's speech: what the review rests on

Each claim in [README.md](README.md) and [ideas.md](ideas.md) about the code, the numbers and the runs, and where it was checked (28 Oct 2026; the `v4.7.0` branch as it stood in the checkout at f61b33b3 plus the uncommitted feedback feature; `main` at dcc041b7). The research is in [research.md](research.md). Read the code again before building: a claim goes stale with any release.

## The code

| Claim | Where | What was found |
|---|---|---|
| He's framed as the app's writer | `felix/FelixPrompts.kt:91` | "You write the short messages the planner app sends a UK sixth-form student (Year $year) about their work. You get the reason for the message, the piece of work it's about…" |
| The chat's voice comes last | `ChatPrompts.system`, golden `chat.txt` | Nine capabilities (about 2,700 words) before "How you write"; the register, feelings and note are paragraphs 13–16 of 18 |
| The user turn is a form | `FelixPrompts.kt:157–233` | `Reason:`, `Now:`, `Piece: …, N min, slot HH:MM–HH:MM; it earns its free time …`, `First action:`, `Its task's due:`, `Today: N kept, N missed, N to come`, the mood line, `Left today:`, `Recent, oldest first:` with `(Kind)` labels, `Question:` |
| The register's size and its bans | `FelixPrompts.kt:38` (`REGISTER`, 296 words), `:45` (`FEELING`, 463), `:51` (`NOTE`, 67) | Counted from the goldens; the phrases banned are listed in README §2 |
| The mood line's words | `felix/Mood.kt:161–170` | "How you feel, at most: let down, firmly (2 slots missed today; …)"; reasons from `down` (`:81–91`) and `up` (`:115–121`) in the app's words ("every slot today kept, 4 of 4") |
| Today's records only | `felix/Looks.kt:361–364`; `Mood.of` | `log.value.slots.filter { it.date == date }`; `ActivityLog.slots` keeps `KEEP_DAYS = 120` (`data/RuntimeState.kt:136–158`) |
| His chat answers are hidden from his notifications | `core/Chat.kt:141` (`ChatHistory.his`) | Keeps his messages with `kind != Chat`; `FelixPrompts.kt:219` takes the last `RECENT = 6` (`:57`) |
| One reply a message | `core/Chat.kt:148–153` (`replyOf`) | The message of theirs with `replyTo` his, else their next within `REPLY_WITHIN_MS` (10 min) |
| His notes aren't sent back | `core/Chat.kt:106–110` (`ChatMessage.feeling`) | "never sent back to him"; set at `Looks.kt:440` and `Conversation.kt:847` |
| The ratings aren't sent | `core/Chat.kt:111–117`; `Conversation.rate` (`:331–342`) | `rating`, `feedback` (≤ `MAX_FEEDBACK = 1_000`), `ratedAt`; "never sent to him"; read by `private/tools/felix_feedback.py` over adb |
| The check-in's answers are stored and never sent | `data/Days.kt:38–48` (`CheckIn`); `Conversation.kt:223` | Fields feel, avoided, in the way, change, energy; Felix's code checks only that one exists |
| The week's review and summary never reach him | `Conversation.kt:428–433`; `Learning.kt:116`, `:237` | The review's first line and `FelixWords.SUMMARY_READY` only; the summary is written for a parent |
| The learned pace goes to the chat, not his messages | `ChatPrompts.kt:612–623`; `describe` has no such parameter | `Looks` passes calibration only to `Rebalance` |
| The chat's window | `ChatPrompts.kt:62–95` | `HISTORY = 12` conversation messages plus `NUDGES = 2`; the archive "never sent to the model" (`core/Chat.kt:124–126`) but for a quoted reply (`Conversation.kt:734`) |
| The profile block, shared by both prompts | `enrich/Profile.kt:57–100`; `ModelCalls.kt:15–18` | Subjects, hours, no-school days, "From the student", rules (≤ 20), facts (≤ 6,000 chars), 16,000 in all; the second system block, uncached; the first (the job's prompt) cached an hour |
| The model and the effort | `enrich/ClaudeEnricher.kt:144–148`; `enrich/ModelEffort.kt:32–34` | `claude-opus-5-5`, `MAX_TOKENS = 16_000`, the server-side fallback beta; Felix, Chat and Research at MEDIUM, readings at HIGH; `ai-effort` overrides from a PC, not kept |
| No sampling knobs | `app/src/main` searched | No `temperature`, `top_p`, `top_k`, stop sequences or `thinking` anywhere; the claude-api skill: all three are rejected on the 2026-10-21 API version |
| Structured output | `felix/FelixCaller.kt:30–36` | `JsonOutputFormat` with `FelixPrompts.SCHEMA` (`:260–277`): `feeling`, `message`, `replies`, `nextCheckMin`, `memories`, `rebalances`; all required, no extra properties |
| The length rules | `felix/FelixAnswers.kt:10–21`, `:60–65` | `MAX_MESSAGE = 240`, `MAX_MORNING = 480`; over the room, cut at the last sentence end if that keeps half, else at a word with "…" |
| The notification's room | `felix/Outbox.kt:185–204` | `NOTICE_ROOM = 360`, `LINE_MIN = 60`, lines newest first, each trimmed by the same rule; several unread messages already show as several lines, so two texts a look need no new notification shape |
| Written ahead or at the moment | `felix/FelixRules.kt:181`, `:185–190`, `:199–201`; `Looks.kt:413–415`, `:724–740` | SlotStart drafted up to 10 min ahead and used only if nothing's been said since; SlotEnd, SlotKept, DayDone, Question, Completions at the moment; the morning waits 20 s; a failed call retries in 5 min with no plain line (the morning falls back to `FelixWords.morningList`) |
| The morning's list exists in the app's words | `felix/FelixWords.kt`; `learn/Briefing.kt:78` | `morningList` is the card when Claude's off; D-57 says the briefing is his alone when he can write |
| A silence counts as said | `felix/FelixRules.sent`; `Outbox` | An empty message is kept with no text and counts for the rules |
| The harness's flags | `enrich/EvalRunTest.kt:469–494`, `:556–564`, `:572–596` | Address words, `teacher:`, `counsellor:` (the coach's questions sit in it), `slang:`, `written:`, `copied:` (five words found only in a prompt example), `yesterday`, `emoji`, `cut`; shared 4–8-word phrases in 3+ cases listed; `EVAL_TIMES` ≤ 5, `EVAL_MODEL` for Felix cases, `EVAL_BUDGET_USD` 3 |
| The cases | `private/eval-felix-mood/cases.json` | 27 (22 felix, 5 chat): SlotEnd 7, SlotKept 6, Chat 5, SlotStart 4, DayDone 2, Morning 1, Question 1, Reminder 1; made up |
| The goldens and the doc | `enrich/GoldenPromptsTest.kt`; `FelixInputsDocTest.kt:248–270`; `SchemaCompileTest.kt` | Every prompt and schema pinned; `felix-inputs.md` rebuilt from the code; the schemas compiled live with `-PcheckSchemas=true` |
| `FelixTest` reads lines by index | `felix/FelixTest.kt:76`, `:83–91` | `lines[4]` "Today: …", the mood line at line 6: a reordered `describe` moves these asserts |
| The feedback dialog shows his note | `ui/AskActivity.kt:882–913` | "His note as he wrote it", a text box, Save/Remove; a thumb under the time reopens it |
| Memory-tidy's shape, for the feedback digest | `enrich/MemoryTidy.kt`; `learn/Learning.kt:161–178`; golden `memory-tidy.txt` | A weekly call after Sunday's review, at most 10 lines, rules never tidied, before and after kept on the review |

## The numbers

Measured on 28 Oct with `private/felix-speech-review/run_stats.py` (the runs) and `chat_stats.py` (the backups); both scripts kept there.

**The final prompt's answers** (runs 15 and 16, 75 answers: 59 notifications, 16 chat):

| Measure | Notifications | Chat |
|---|---|---|
| Length | median 127, mean 128 chars | median 120 |
| Sentences | median 3, mean 2.7 | mean 3.1 |
| Numbers a message | mean 1.6 | 1.7 |
| With a clock time (HH:MM) | 45 of 59 | 6 of 16 |
| Two or more numbers | 25 of 59 | 7 of 16 |
| Ends with a full stop | 51 of 59 | 6 of 16 |
| Three-word phrases in 3+ different cases | 28 kinds; "on now till" 8, "circular motion worksheet" 8 (the case set's own task), "part 's on" 6, "it's in at" 6, "that's a good" 5, "good way to" 4 | |
| Four-word phrases in 3+ cases | 13 kinds; "it's in at tomorrow" 6, "'s on now till" 5 | |
| Word overlap between attempts of one case (Jaccard) | mean 0.45, max 1.00 (identical), 90 pairs | |
| Commonest last four words | "of free time's yours" 7, "not moving it again" 4, "sec what's going on" 4, "photo when it's done" 3 | |

**Run 3, the flat prompt, for comparison** (46 answers: 36 notifications): mean 96 chars, 2.2 sentences, 16 of 36 with a clock time, 13 of 36 with two or more numbers, 26 of 36 ending in a full stop. The mood prompt lengthened the messages by a third and nearly trebled the share carrying a clock time.

**The owner's own chat messages** (the 19 Oct backup, 31 messages of theirs; samples in `private/felix-speech-review/chat-2026-10-19.txt`, never to be quoted):

| Measure | The owner |
|---|---|
| Words | median 14; chars median 80 |
| Ends with a full stop | 11 of 31 |
| Holds a comma | 8 of 31 |
| All lower case | 3 of 31 |
| More than one sentence | 13 of 31 |
| Holds a digit | 10 of 31 |

Several are plainly dictated (one repeated three times with a transcription slip), so capitals and full sentences are partly the keyboard's; the shortest, three or four words each, are the typed ones and read like texts. Felix's messages in the same backup (the pre-4.2.2 voice, 74 of them, kinds SlotStart 21, Chat 22, SlotEnd 12, Question 11) ran 182 chars mean, 0 of 74 all lower case, 47 with a question mark: a long way from today's.

**The prompts' sizes** (goldens): `felix.txt` 1,914 words (about 10,500 chars) in 8 paragraphs, the voice (register, feelings, note) 826 words of it; `chat.txt` 3,855 words (about 21,200 chars) in 18, the voice the same 826. Both are sent as the cached first system block; the profile block after it is uncached.

**Costs** (from the project's records, not re-measured): a Felix answer about a cent with the cache (run 2: 69 answers, $0.73); a chat answer $0.07–0.16; the sixteen rounds about $7.50 of scenarios; Haiku 5.5 $0.10 / $0.50 per million tokens against Opus 5.5's $4 / $20 (the claude-api skill's table, cached 2026-10-06).

## The runs, read

What the final report shows beyond the numbers (quotes are of made-up cases):

- **The clock in every warm message.** m11 "finished bang on 19:30", m16 "done right on 18:00, jay, even starting 10 min late", m20 "done bang on 18:00 and it's not even in till fri" (all three attempts of m20 open with the time). The prompt's "picking out something particular about this time (how late it was, …)" and the record's "kept at 18:00" meet.
- **The template ending.** m12 (×3), m13 #2, m21 (×3) end "N min of free time's yours"; the DayDone examples in the prompt end so twice.
- **The template opening.** SlotEnd: "X went by too/as well, that's two/three today/tonight" in m02 (×2), m23 (×2), and the firm example.
- **Identical attempts.** m09 "hey, that's ok. forget the essay for a sec. what's going on?" three times, with one word changed in one; m21 #1 and #2 differ by two words.
- **The best lines of the run** are the ones with a story in them: m03 "you said yes to 19:00 for the essay and nothing happened", m13 "you said you'd do the rates questions after the dentist and you actually did", m23 "you said physics at 7. that went by as well". Each rests on something they'd said, which the prompt happened to carry in a reply. The story turn (I-15) makes that the rule.
- **His notes read better than his messages.** "fed up. they told me physics at 7 themselves and still let it go. three gone. i want them to feel that and get on it now" is a person; the message after it is "you said physics at 7. that went by as well, and it's three today. circular motion's on now till 20:00, it's in fri 09:00, and you're not moving it again." The note has one number; the message four. The note shows the voice is in there; the briefing and the rules put the numbers back.

## Decisions and numbers to hand

- The next decision on `main` is D-120 (D-119 is 4.7.0's, not yet on `main`); the next question Q99 after 4.7.0's Q98 (the plan's own count: check `docs/plan_v4/answers.md` and `docs/decisions.md` when building).
- 4.7.0 is version 87; this work would be 4.8.0, version 88, after it.
- The worktree is `.claude/worktrees/felix-speech-review` on branch `worktree-felix-speech-review`, from `main`; nothing in the main checkout was changed.
