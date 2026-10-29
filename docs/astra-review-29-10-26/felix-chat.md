# Felix, the chat, readings and the evaluation runner

Six findings, all confirmed against adfe71dc; one narrower than written. Ids: max FX-/INT-, ultra AS-. Probes in [evidence/](evidence/) assert the behaviour wanted and fail.

### F-01 · P1 · A step action, or its Undo, lands on whatever step now holds the index, not the step you named

Max FX-01, ultra AS-02. Reproduced three ways: `AstraFelixReviewTest` "a returned tick must not apply to steps changed while the model answered" and "undo of an old untick must not tick a replacement step at the same index", and `AstraAiReviewTest` "a tick returned after steps change must not tick another step", all fail.

- **Where:** the action carries task id and index only, validated against the snapshot sent to the model (`felix/ChatAnswers.kt:469-473`, snapshot at `felix/Conversation.kt:723`). `Conversation.act` (`:959-987`) resolves the index on the current task and passes the current step's title to `tick`/`untickBlock`, so Focus's title check (`block/Focus.kt:583-595`, written for exactly this) compares the replacement with itself. The plan-piece branch keys by index too (`:1161`, `Chunk.keyOf`). Undo (`:1179-1186`) and `ChatActs.canUndo` (`core/Chat.kt:336`) check only that the current index is open and unticked.
- **What happens:** ask Felix to tick a step; while he answers, edit the task's steps on its form (Up, Down, Add, Delete: `ui/OwnTaskForm.kt:159-165,301-302`) or a reading replaces a source task's steps. The new step at that index is marked done, and for a planned step a kept record with minutes and free time is written. Later, an old untick's Undo does the same to a step added since.
- **Checked:** `KeepPiece` already guards with the snapshot's time (`Conversation.kt:1064`, `seenAt`); tick and untick have nothing comparable. The app's own step identity is id or title (`core/Enrichment.kt:89-97,154,181`), never index. Kept at P1: a completion and a kept record are written into the stores on your words about another step; the verifier would have P2 as Undo takes it back.
- **Differences:** FX-01 covers the Undo path and uses a real form edit; AS-02 covers the race alone, with a direct store write standing in for a reading.
- **Fix:** carry the snapshot's step title (or id where one exists: a reading's steps have none, `Enrichment.kt:87`) on the action, resolve it at application and at Undo, and refuse with a visible line when the step is gone. `ChatAction` is stored in `chat.json`, so a new field is a store version and a migration. A step that merely moved needs its new index before `keepSlot`.

### F-02 · P2 · Pending messages dropped by the input budget are marked answered

Max FX-02 (P1). Reproduced: `AstraFelixReviewTest` "a pending message omitted by the input budget must remain unanswered" fails: the omitted message has `answeredAt` set and no note.

- **Where:** `felix/Conversation.kt:877` takes `sent` from `ChatPrompts.history` (the last twelve) and `:887` marks all of them answered; only those outside the twelve get the left-out note and `stuck` (`:888`). `ChatPrompts.turns` (`:348-369`) then folds same-speaker runs and drops any turn over a quarter of the remaining budget, and everything older, without telling `answerChat`. The quote (`:341-346`) represents only what the newest message replies to.
- **Against:** `felix.md:59`: a request left out of the answer waits, visibly, for Try again.
- **Checked:** narrow. Each message is cut to 2,000 characters and the budget is 40,000, so with a short newest message six capped pending messages are needed; with a heavy one (a long about text, research, three read texts) two can do it. In every case a message of Felix's must sit between the pending run and the newest, or they fold into one turn and go whole. P2: the text stays visible; its state is wrong.
- **Fix:** have the prompt builder return the ids it actually sent, from the request that went (it's built up to three times a message, `:745`), and mark those alone; the rest keep `answeredAt` null with the left-out note.

### F-03 · P2 · A photo of seven or more tasks keeps six and says nothing of the rest

Ultra AS-03 (P1). Reproduced: `AstraAiReviewTest` "a photo reading must disclose tasks beyond its six task limit" fails.

- **Where:** `enrich/PhotoTasks.kt:83` `answer.tasks.take(MAX_TASKS)`; `:101` adds to `left` only tasks with a problem; nothing records the overflow. The chat's parallel has the line this path lacks (`felix/ChatAnswers.kt:234`, "N more tasks than the app takes at once"). Neither the prompt (`photo-tasks.txt`) nor the schema names a limit. The sheet would show it (`ui/PhotoTasksSheet.kt:96`, "Left out: …").
- **Checked:** reachable with any board or planner page of seven items. P2: no stored data is lost and you still hold the photo.
- **Fix:** the parser's one line, as in the chat; a prompt change would change its golden.

### F-04 · P2 · A slot-start nudge written during a call is sent after you've finished the piece

Ultra AS-04 (P1). Reproduced: `AstraAiReviewTest` "a slot start nudge must not be sent after its piece was kept during the call" fails: two SlotStart messages where one was.

- **Where:** `felix/Looks.kt:120-121` computes the trigger before the call (`FelixRules.due`, `:153-156`, checks the piece isn't done); after the call `:434` rechecks only a timer hold, then `:469` sends. A message written ahead is dropped when a piece was kept since (`:425`, `FelixRules.fits` with today's outcomes), but a live call's answer gets no such check.
- **What happens:** Done on the Day card, the widget's tick, a step ticked, or the task opened while his call is out: his "start it" lands in the chat and as a notification after the piece is recorded done.
- **Checked:** not a decision. The comment at `:428-429` and `LooksTest` "a nudge is kept at the level he was told, a piece kept while he was asked changing nothing of it" are about keeping the mood he was told (Q103); that test passes only because the stale message is added, incidentally. D-119 ("once the work starts it's over"), D-57 (a step ticked isn't asked for again) and the 4.3.13 fixes B02/B03 (reminders and drafts checked again before sending) all point the other way.
- **Fix:** after the call, re-run `due`'s done check for the trigger's key; when dropping, still settle `nextCheckAt` and clear `retry` as `:469-476` do, or the look repeats. Move the Q103 test's mood assertion to a message that still applies.

### F-05 · P2 · The mood evaluation's comparison with an older run judges it outside the budget

Ultra AS-05. Source-traced; no paid run.

- **Where:** `app/src/test/.../enrich/EvalRunTest.kt:293-296` stops asking cases once the soft budget is reached (with `EVAL_BUDGET_USD=0`, every case is skipped); `:331-332` writes the run and its report; `:338-343` then judges every unjudged answer of the `EVAL_BEFORE` run, each a paid call (`:440-447`), with no budget check, and after the report.
- **Checked:** opt-in only (`-PrunEval=mood` with a key), never in CI, cents per run. Still a paid loop outside an explicit budget, which the docs (`readings.md:214-215`) don't exempt.
- **Fix:** gate the comparison's judging on the remaining budget, write the report after it, and keep each judge's cost in the result.

### F-06 · P2 · One instruction that extends a deadline and dates a step inside the extension is refused

Max INT-03. Reproduced: `AstraIntegrationsReviewTest` "steps use a deadline extended by the same instruction" fails (Unclear where Changes was expected).

- **Where:** `enrich/InstructionAnswers.kt:102-103` validates every change against the original task map and returns Unclear on the first null; `:209-216` checks a step's date against the task's old due day. Minutes and Steps are checked together (`:245`) and breaks are projected in sequence (`:101,103`, D-116); nothing projects DueBy. Applied one after the other the pair is accepted (`core/Instructions.kt:299,306`; `Planner.kt:1181`).
- **Checked:** max said the chat rejects the same answer whole; it doesn't. `felix/ChatAnswers.kt:241-245` maps change by change, makes the deadline and says the step change was left out. The instruction screen refuses with a misleading reason (`:260`, a date after the task's). Workaround: two instructions.
- **Fix:** validate steps against the answer's own final DueBy, taken after the chat's `lessonOf` rewrite (`ChatAnswers.kt:398-404`); keep the inverse strict (a shortened deadline with a step beyond it is wrongly accepted today, as `:209` reads the old date).
