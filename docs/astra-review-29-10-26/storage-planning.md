# Storage, planning, notifications and the release rehearsal

Eleven findings, all confirmed against adfe71dc. Each names its reviewer ids (max R-, ultra CD-/UB-), what reproduces it, and what checking it changed. Probes are in [evidence/](evidence/): the max ones and ultra's `ReviewUiBlockTest` assert the behaviour wanted and fail; ultra's `AstraCoreDataProbeTest` asserts the behaviour seen and passes.

### S-01 · P1 · A store update cancelled during its write leaves disk and memory apart, and the next update overwrites what reached disk

Max R01, ultra CD-01. Reproduced: `AstraReviewRegressionTest` "cancelling a store save cannot lose a committed field on the next save" fails; `AstraCoreDataProbeTest` "cancellation after disk write leaves stale state and a later update erases the saved change" passes.

- **Where:** `data/JsonStore.kt:112-113` writes inside `withContext(Dispatchers.IO)` and sets `_state.value = next` after it returns; `data/SecretStore.kt:80-82` has the same shape.
- **What happens:** cancel the calling coroutine while the file is being replaced: `withContext` throws on return, the file holds the new value, `_state` the old. The next update transforms the old value and writes it over the new one. The mutex doesn't help: it serialises, it doesn't publish.
- **Callers that can be cancelled mid-write:** Setup's credential writes (`ui/SetupScreen.kt:244-262`, a composition scope; the app is also left keyless in memory till restart), the task sheet's reopen and delete (`ui/TaskSheet.kt:263,514` → `tasks/OwnTasks.kt:87-99`), and the review worker's calibration write (`learn/Learning.kt:90`, a WorkManager stop). `saveOwnTask` and `finishOwnTask` are `NonCancellable` already.
- **Checked:** this is one store in a live process, not the process death between two stores accepted in [the 4.3.13 review](../astra-review-4.3.13/README.md); no decision covers it. The windows are a few milliseconds each.
- **Fix:** make the write and the publication one non-cancellable section under the mutex, in both stores; a write that itself fails still publishes nothing.

### S-02 · P1 · Reset erases a chat message that arrives while its recovery copy is being written

Max R02. Reproduced: `AstraReviewRegressionTest` "chat arriving while reset saves its copy is not silently erased" fails.

- **Where:** `data/BackupService.kt:294-310` snapshots the stores, checks and writes the copy (a MediaStore write of up to 8 MB), then `:331-334` replaces the live chat. Nothing pauses the writers: `reset` takes no lock the chat uses, `cancelFelix` only cancels notifications, and an answer lands straight in `chat.update` (`felix/Conversation.kt:878-894`).
- **What happens:** a Felix call in flight (its scope outlives the screen) answers between the snapshot and the replacement: the answer is in neither the copy nor the chat after the reset. The same window holds for his timed messages, a notification reply, and the log and memory under their own resets.
- **Against:** `storage-and-backups.md:115`, a reset's copy is the undo of the reset.
- **Checked:** rare (a reset during a call), but real; the copy's promise is the point.
- **Fix:** drain or hold the writers from the snapshot to the replacement, or reset the snapshotted generation only and keep what came after.

### S-03 · P1 · Moving a piece to a time already gone today is accepted, then dropped by the plan

Max R05. Reproduced: `AstraReviewRegressionTest` "the time picker rejects a time already gone today" fails.

- **Where:** `core/Planner.kt:1325-1327` `pinProblem` says `DayGone` only when the day's last millisecond is past; `ui/Moves.kt:52-58`, `ui/DayScreen.kt:251` (a drag above the now line) and `ui/MoveSheet.kt:86-89` (the clock) rely on it. The break path does check the time (`Moves.kt:100`).
- **What happens:** at 20:00 a 30-minute piece moved to 17:00 is reported "At Thu 17:00"; `Slotter.exactPins` (`Slotter.kt:372`, ten minutes' grace) skips a pin whose end and grace are gone and lays the piece where the last plan had it.
- **Against:** `Moves.kt:23-24` promises a refusal "so none's said done that the plan would drop"; D-114 lists a pin "its time and grace gone by" among what's not kept, which describes an existing pin's fate, not a licence to accept a new one. A pin under way (`Slotter.kt:350`, `PlannerTest.kt:1675`) must stay allowed.
- **Fix:** refuse a new move whose time, piece and grace are all gone, at `pinProblem` rather than in `Moves` alone, as the chat shares it (`tasks/Instructions.kt:157`); "time before now" would refuse a pin a few minutes back that today runs as under way.

### S-04 · P1 · Scaling uneven steps to a total you gave saves more minutes than you typed

Max R03 (P1), ultra CD-02 (P2). Reproduced: `AstraReviewRegressionTest` "requested minutes remain the total with uneven steps" fails (10 over [100, 1, 1] gives [10, 1, 1]); `AstraCoreDataProbeTest` "a valid total at least the number of steps is inflated" passes (3 over the same gives a task saved as 5).

- **Where:** `core/Task.kt:363-371` `shareOut`: floor, largest remainder, then `coerceAtLeast(1)` on each share without taking those minutes back. `OwnTaskRules.minutesFor` (`core/OwnTasks.kt:77`) only requires total ≥ steps; its Minutes branch (`:251-257`) replaces the steps with the shares; `TaskItem.plannedStepMinutes` feeds `Planner.pieces`.
- **Against:** `planner.md:54` ("whole minutes adding up to exactly yours"), `minutesFor`'s own KDoc, and D-4: the total you set is the one the app keeps.
- **Checked:** both reviews are right about the arithmetic; the trigger is a short finishing step beside a big one with the total cut below about half their sum (60 and 2 set to 10 gives 11). Kept at P1 because the saved task's total is not the one you typed, which D-4 forbids; ultra's P2 reads it as a wrong figure only.
- **Fix:** give each step its minute first and apportion the rest; total == steps must give every step exactly 1 (`minutesFor`'s floor), and steps you gave minutes to one by one stay as given.

### S-05 · P2 · A cancelled held notification comes back at the morning briefing

Max R04, ultra UB-02 (both P1). Reproduced: `AstraReviewRegressionTest` "a cancelled held alert cannot reappear in the morning" and `ReviewUiBlockTest` "canceling a held key warning must prevent its return at the briefing" both fail: tray id 5002 is posted after its cancel.

- **Where:** `notify/Notifier.kt:90` `cancel` touches the tray only; `:120-142` holds routine channels' notices in quiet hours or past the four-a-day ceiling (`NotifyRules.kt:12` counts `Channels.MODEL` among them); `:149-154` `postHeld` posts every held entry, each morning (`Briefing.kt:60`). `enrich/ModelAlerts.kt:39` `keyFixed` cancels; `:73-76` `withdrawExcept` looks only at `notifier.showing`, the tray.
- **What happens:** a key error found in quiet hours is held; you mend the key that evening; the briefing says Claude has stopped working. Likewise a dropped-reading warning held overnight for a task since finished.
- **Checked:** `Notifier.retire` (`:99-103`) already removes held entries, but only `NoticeId.RETIRED` uses it at start. The held write is asynchronous (`:135-140`), so a cancel racing the hold is a second ordering the fix must cover. P2: a stale alert, nothing lost; both reviews' P1 counts a stale notification as a UI bug.
- **Fix:** have `cancel` (and `withdrawExcept`) remove matching held entries, ordered with the hold's write.

### S-06 · P2 · Settings' margin preview puts a morning deadline's day a day earlier than the planner

Max R06 (P1). Reproduced: `AstraReviewRegressionTest` "margin preview agrees with the one day deadline margin" fails: "planned by Tuesday" for a Thursday 08:30 deadline with a margin of 1, where the planner's last usable day is Wednesday.

- **Where:** `ui/SettingsEffects.kt:37-39` subtracts a day for the deadline falling before the evening window and then the margin; `core/Planner.kt:1135` subtracts the margin alone when it's positive and uses the day before (`lastDayBefore`) only at margin 0. `SettingsEffectsTest.kt:32` pins the preview as it is.
- **Checked:** the two disagree for every morning deadline with a margin; they agree at margin 0. Which to change is your call: the planner makes margin 0 and margin 1 the same day for a morning deadline, which sits oddly with the setting's label "Days to spare before a deadline" (`ReviewInput.kt:235`), and no decision fixes the arithmetic. P2: a wrong caption, nothing acts on it.
- **Fix:** derive the caption from `Planner.lastUsableDay`, or decide the planner should count the margin from the last usable day; then one rule, one test.

### S-07 · P2 · An applied dated instruction older than two years makes every new backup unimportable

Max R07, ultra CD-06. Reproduced: `AstraReviewRegressionTest` "a retained instruction does not make a later nightly backup unrestorable" fails; `AstraCoreDataProbeTest` "valid retained instructions age into backups the importer refuses" passes.

- **Where:** `data/Backup.kt:356` accepts an instruction's time only within 730 days before and 400 after the backup's export date (`:360` StartFrom and DueBy, `:372` AtTime), on every applied instruction (`:282`). Applied instructions are never pruned (`core/Instructions.kt:203`; quick changes replace only same-kind ones for the same task). Import refuses the whole file (`BackupService.kt:166`); a reset refuses its copy and stops (`:301-302`); `backupNow` (`:141-153`) checks the size only and prunes older backups.
- **What happens:** two years on, with one such instruction still applied, each nightly backup is written and reported, older good ones pruned, and none of them imports; Reset refuses to run.
- **Checked:** certain once the app is that old, unless the instruction is taken back. The reset's refusal is the right safety net and should stay.
- **Fix:** bound the time against the instruction's own `at` (its creation), not the export date; have the nightly writer run the same validation as Import before pruning.

### S-08 · P2 · The release rehearsal never reads the monthly chat archives or the Gmail journal

Max R08, ultra CD-07. Reproduced: `AstraRehearsalReviewTest` "the release rehearsal must reject unreadable monthly chat and Gmail journal files" fails because the real rehearsal passes with both unreadable; `AstraCoreDataProbeTest` "the migration rehearsal succeeds without inspecting corrupt monthly archive and Gmail journal" passes.

- **Where:** `app/src/test/.../data/RehearsalTest.kt:372` `STORES`, `:128-145` the before-reads and `:172-182` the after-reads name only the legacy `chat-archive.json` and omit `gmail-writes.json`; `:158` copies only `STORES` into the temporary folder, so `ChatArchiveStore` finds no months. The archive has been monthly since 4.0.4 (`data-and-migrations.md:57`); `data/Stores.kt:136` lists the journal.
- **Against:** `data-and-migrations.md:49` and `development.md:28` promise every store is read as it is and as the app will.
- **Checked:** ultra also blamed `scripts/rehearse.sh:26-33` for not aggregating the months; the script unpacks all of `files/` (`:27`), so the months are there. The gap is the test's lists alone, as max says. Its sibling check of the nightly backup (`:355-365`) covers receipts and the fitted archive only where a nightly file is beside the stores.
- **Fix:** take the list from `Stores`, enumerate the archive's months on disk, and fail on any app-owned file present but unaccounted for.

Max's candidate R09 (a source path ending `/..` making `rehearse.sh` delete `private/`) was withdrawn by its reviewer: GNU `rm` refuses a final `.` or `..` component and `set -e` stops the script. Checked and agreed; its passing negative control is not kept.

### S-09 · P2 · A midweek review counts the previous week's subjects

Max R10. Reproduced: `AstraReviewRegressionTest` "a partial current week review does not count last weeks subjects" fails.

- **Where:** `learn/ReviewInput.kt:104-107` windows completions, slots and days to Monday-to-now; `:172` asks `Stats.bySubject` for the seven days ending now (`Stats.kt:165`, `first = today - 6`). `Daily.checkInWeek` (`Daily.kt:152-155`) picks the nearer Sunday, so a Thursday review is of this Monday's week.
- **What happens:** a review run on Thursday says nothing was finished this week and lists a subject finished the Friday before. Sunday's review is unaffected (its seven days are Monday to Sunday).
- **Reachable:** "This week" on the home screen → "Save, and review the week" (`CheckInActivity.kt:96`), or Felix's review after a check-in in chat (`Conversation.kt:267`).
- **Beyond the finding:** a midweek review is written with that week's number (`Learning.kt:102`), so Sunday's due review for the same week returns early (`:80`). A fix should decide what a second review of a week does.
- **Fix:** give `bySubject` the review's own first and last day.

### S-10 · P2 · A newly learned Anki pace changes the task's total but not its steps' minutes

Ultra CD-03. Reproduced: `AstraCoreDataProbeTest` "a learned Anki rate changes the fresh step but merge keeps the old minutes" passes.

- **Where:** `core/Merge.kt:98` takes a source's new steps only when their titles differ (`:110` else keeps the old ones) while `:106` updates `sourceEffortMin`. Anki steps are titled by count ("20 new cards", `core/AnkiRules.kt:383`) with minutes from the learned rate (`sources/anki/AnkiSource.kt:129`); the planner uses the steps' minutes (`Planner.kt:1163`).
- **What happens:** a rate learned from a stretch that changes no count of that deck leaves the plan at the old pace until a count changes; total and steps disagree meanwhile.
- **Checked:** the comment at `Merge.kt:96-97` gives the intent (keep the ticks till the counts catch up); the stale minutes are a side effect. D-4 covers minutes you or Felix gave, not a source's. `sources.md:81` promises minutes by the learned rate (DX-11).
- **Fix:** on matching titles, refresh minutes and other source-owned fields but keep ids, `done`, `byHand`, `timedMin`, and leave `stepsWorkedMin` as it is (`:112` sets it only on a count change).

### S-11 · P2 · Felix can't forget a standing rule longer than sixty characters

Ultra CD-04. Reproduced: `AstraCoreDataProbeTest` "forgetting a standing rule longer than sixty characters silently keeps it" passes.

- **Where:** `core/Memory.kt:159` cuts the topic to `MAX_TOPIC` (60) before the rule branch; `:168-169` then compares rules of up to `MAX_TEXT` (300) against the cut string, and adds no edit when nothing matches. The chat prompt (`felix/ChatPrompts.kt:176`) tells the model to forget a rule by giving its own words as the topic with an empty text. `MemoryTest.kt:33,66` forget only short rules.
- **What happens:** "stop doing that" about a sentence-length rule does nothing, silently; the rule keeps going into every prompt. Settings' memory section can still delete it.
- **Fix:** apply the topic bound to facts only and match a rule by its full normalised words.
