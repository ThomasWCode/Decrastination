# Documentation drift

Five findings: four stand (two narrower than written, D-02 and D-04), one rejected. All P3. Ids: max DOC-/INT-, ultra CD-/WT-/AS-. Each gives the wording the code supports, so a docs PR can take it as is.

### D-01 · The storage reference's store versions are a release behind

Max DOC-01, ultra CD-05. `storage-and-backups.md:38,40` give `felix.json` as version 2, `chat.json` as 10 and the archive's months as 9. `SpeechMigration` (`data/SpeechMigration.kt:17,20,23`) takes them to 3, 11 and 10; `StoresTest.kt:175` and the newest fixture folder (`fixtures/stores/2026-10-28-4.8.0/`) agree; `data-and-migrations.md:69` has it right. Session 3, log 4 and memory 2 in the same table are right.

- felix.json: "version 3 (4.8.0, `SpeechMigration`: his answer written ahead keeps the texts he didn't send, `FelixAnswer.unsent`)".
- chat.json: "version 11 (4.8.0, `SpeechMigration`: on each message of his the texts he didn't send and the one you'd rather he had sent, `unsent`, `preferred`), the archive's months at 10".

### D-02 · Version, fixture and requirement summaries omit the current state

Max DOC-02, ultra WT-04. Five items; one side remark of max's rejected.

- `README.md:5` says 4.9.0; `app/build.gradle.kts:15-16` is 4.9.1 (code 90). Say 4.9.1, or drop the version from the overview.
- `fixtures/README.md:19` ends its catalogue at 4.7.0; `fixtures/stores/2026-10-28-4.8.0/` exists and `StoresDecodeTest` reads it. Append: "and from 2026-10-28-4.8.0, 4.8.0's: the texts Felix didn't send and the one you'd rather he had, in `chat.json`, `chat-archive.json` and `felix.json`, and Felix's default line in `settings.json`". Max's remark that the file implies removed fields still exist in current types is wrong: "the fields kept only so older files read" describes each folder in its shape on that date.
- `plan_v4/README.md:131` says the next free decision number is D-112; the file's own line 166 cites D-112 to D-121 and `decisions.md` holds D-122. A historical record: "The next free number was then D-112", or drop the sentence.
- `requirement-ids.md:48` (AR-04) says the property tests hold "for any input" that slots sit inside windows and home work is never at school; D-114 keeps a pin outside the windows or at school, and `PlannerPropertyTest.kt:455-464` skips pins before those checks. Say: slots the planner lays (not pins, D-114), on seeded random inputs.
- `requirement-ids.md:161` (PL-12) says every piece gets a time and no outside break starts after 18:00; `slots-and-calendar.md:13` keeps a piece no free time holds as `unslotted`, and D-116 keeps a break you or Felix move after 18:00. Add both exceptions.

### D-03 · The architecture guide's "one test class a file" has five exceptions it doesn't name

Max DOC-04. `architecture.md:103` gives the exception for production types only. Five test files hold 22 classes between them: `learn/LearnTest.kt` (7), `protect/ProtectTest.kt` (5), `block/BlockingTest.kt` (4), `learn/DailyTest.kt` (3), `widget/WidgetModelTest.kt` (3); max named three. `ArchitectureTest` enforces the rule for neither production nor test files. Either split them or write: "One test class a file, but for a file of small rule tests grouped by concern (`BlockingTest.kt`, `ProtectTest.kt`, `LearnTest.kt`, `DailyTest.kt`, `WidgetModelTest.kt`)."

### D-04 · The Felix input reference and the privacy page describe the payload before 4.8.0

Max INT-06, ultra AS-06, and max's Felix pass. The generated blocks in `felix-inputs.md` are current (`FelixInputsDocTest` checks fence equality, nested fences and one sentence, nothing else); the prose round them isn't. Every item below was checked against the code. Both reviews said the `Reason:` line had gone; it hasn't, it now sits under `Details:` (`FelixPrompts.kt:203-204`). What went is `Now:` (the time is in "Today so far") and the "Recent" label.

| Where | Says | Code |
|---|---|---|
| `felix-inputs.md:31`, `privacy.md:9-10`, `requirement-ids.md:23` (AI-05) | Felix's profile carries your year group | `Profile.kt:72` with `felix`'s `year = false` (`:44-47,61`): "A UK sixth-form student." (D-120); the generated block at `:44` shows it |
| `felix-inputs.md:23` | the call's own prompt is cached for an hour | the web search's isn't (`WebResearcher.kt:35`, no cache control); `:424` and AC-01 say so, the overview doesn't |
| `felix-inputs.md:325-335` | the chat's response fields | no `feeling` row; `chat.schema.json:4,364` requires it |
| `felix-inputs.md:469` | a kept slot is answered about one in three | a keep after a let-down is always answered (`FelixRules.kt:135-138`, Q102) |
| `felix-inputs.md:484-497` | `Reason:`, `Now:`, "Recent, oldest first", "Not sent: any day but today, or the chat" | "Today so far (…)" with today's slots and what was said, chat included, his feelings noted; the feeling ceiling; "Your diary:" (the last 7 days, the week before, the week's check-in); then `Details:` with `Reason:`, the piece, "Its task's due:", "Breaks outside today:", "Steps you may rebalance"; then "Your texts before today, oldest first" (`FelixPrompts.kt:191-271`, `Story.kt:54-99`, `Looks.kt:373,376`). Not sent: the task list, the calendar, your pace |
| `felix-inputs.md:755`, and the calls table at `:15` | the answer is a `message` | `feeling`, `texts` (three), `send`, `replies`, `nextCheckMin`, `memories`, `rebalances` (`FelixAnswers.kt:34`, `felix.schema.json:67`); `message` is the text he chose, parsed (`:44`) |
| `felix-inputs.md:806` | the check-in's answers don't go to the model | the week's go to him in his diary (`Story.kt:77-99`) and to the weekly review (`ReviewInput.kt:181`) |
| `privacy.md:24-25` | the chat and his messages get his last six messages, today's slots and your replies | both now get today's story (chat exchanges included), his diary of the last week and the week's check-in (`Conversation.kt:595-607`, `Looks.kt:373,376`); neither review listed the chat row |

### Rejected: DOC-03, the timer-stop wording

Max read "until 30 minutes after its zero or a Stop by hand, whichever comes first" (`decisions.md:96`; `felix.md:148` and `felix-inputs.md:463` say "came") as lifting the hold at once on Stop. "Whichever came first" chooses between the two ends, the zero and the Stop, and "30 minutes after" applies to the earlier: what Q70, `FelixRules.liftAt` (`FelixRules.kt:66`) and `FelixRulesTest.kt:317-329` do, and `felix.md` names `liftAt` beside the phrase. Ultra checked the same and reported nothing. Optional, if the sentence is ever touched: "until 30 minutes after the earlier of its zero and a Stop by hand".
