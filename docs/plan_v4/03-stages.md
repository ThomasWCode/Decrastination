# The order: every stage and PR

The whole of the work as a sequence of PRs, each a release: a branch named for its version from `main`, staged commits, the tests, CI, the Codex rounds and the Claude review ([12](12-verification-and-risks.md) §Reviews), the build on the phone behind a backup, the checks, then the merge and the tag ([development.md](../development.md)). A cleanup PR that moves code moves it first, in a commit that changes nothing else, and improves it in the next; a feature PR lands its pure rules and their tests before the code that uses them. The conventions in [11](11-conventions-and-docs.md) apply to every line written from Stage 1 on.

Versions: cleanup-only releases before the chat are 3.3.x; from the chat on, every release is 4.x in the order it lands, cleanup or feature: the number says when, not what. Stage 0 changes no app code and isn't a release.

Each stage says what it **needs** (always from earlier stages: nothing here relies on anything later), what it **does**, its **steps** (the commits, in order), its **tests** and **phone check**, and what it **leaves** for the stages after it. The common phone check after every install is in [12](12-verification-and-risks.md) §The phone check; a stage's own checks are listed with it.

## Why this order

1. **The cleanup comes first where a feature would otherwise be built on the mess.** The chat work is the largest feature and lives in the code that's most tangled (`AppGraph`'s chat functions, `learn/Felix.kt`, `notify/Felix*`). So the notifier (1), the gateway (2), the stores with versions and migrations (3) and Felix's own service (4) come before it. The move in Stage 4 is verbatim and cheap (one Codex round); the chat work then has a service built like `Focus`, with tests on temporary stores, from its first commit. The cleanup plan had this move after the chat work "so the newest code moves once"; built inside the service, the new code never moves at all.
2. **The cleanup comes between features where it only has to be done at all.** The other services (6), the data model and the stores (7) don't touch the chat; they wait until the chat is in, so the headline feature is in your hands after four cleanup PRs, not eleven. They do touch reminders, the gaps and the email's finish (`By`, the Sync service, the split stores), so they come before those.
3. **Data changes are batched where their rehearsal is one.** The chat's migration is its own (5a); who-set-what and the store split are one release (7, C-Q10); each feature's store or field is versioned from the start, through the mechanism Stage 3 makes, so no later migration is a special case.
4. **Names are settled before the prompts are rewritten.** The readings' prompts are renamed (9) before memory and the voice (10) change them, so those diffs are only the change.
5. **The write outside the phone comes last** (11), and the sweep (12) leaves the code as [11](11-conventions-and-docs.md) wants it.

[README](README.md) §The order has the table; the days add up to about twenty-three. [14](14-your-part.md) says, stage by stage, when the phone and you are needed.

## Stage 0: the safety net (no release)

**Needs** nothing. **Does**: the tests and scripts every later migration and move is checked against. The app doesn't change.

**Steps**

1. **Store fixtures.** Made-up files for every store in its shape today, every field filled, the old ones too (`ChatMessage.remembered`, `done` and `reason`; tasks with each `user…` field, a paced total, steps of each kind, finished tasks, an email's extras; `log.json` with `FelixRecord`s of every reason, replies among them), under `fixtures/stores/<date>/`. `StoresDecodeTest` reads each with the app's own serializers and checks what came out. Every later shape gets its own folder, so each migration is tested from every shape it may meet.
2. **A plan snapshot.** A fixed state (tasks from every source, steps of each kind, your instructions, a pin, a school calendar, busy time, a learned pace) planned by `Planner.plan`, its pieces, keys, days and slots written as text and compared with `fixtures/snapshots/plan.txt`; rewritten only on purpose (`-PupdateSnapshots=true`), as the goldens are. Any move that changes a plan shows here.
3. **A migration rehearsal.** `scripts/rehearse.sh <backup>` unpacks a phone backup from `private/backup/` into the git-ignored `private/rehearsal/<name>/` and runs a test (`-PrehearseFrom=<dir>`, skipped otherwise) that reads every store with the code as it is, runs the migrations there are, and prints only counts and invariants (tasks, open ones, steps ticked, instructions in use, chat messages, pins, the month's spend). Nothing it reads is printed or committed.
4. **Two scripts.** `scripts/phone-check.sh`: the read-only checks of [12](12-verification-and-risks.md) §The phone check as one command. `scripts/scan-public.sh`: the privacy scan done by hand before each push, as a script, run on the diff before committing ([11](11-conventions-and-docs.md) §Privacy in the docs).
5. **Baselines** for the done-when list: test count, build and test time, `AppGraph`'s size, the stores' sizes ([01](01-current-state.md) has today's).

**Tests**: the fixtures decode; the snapshot matches. **Phone check**: the script runs and reports. **Leaves**: the fixtures every migration (3, 5a, 7, 8a, 10a, 11) is tested from; the snapshot every move (4, 6, 9) is checked against; the rehearsal every data release runs first.

## Stage 1: one notifier (3.3.0)

**Needs** 0. **Does**: every notification named in one place and posted by one class; no behaviour changes. [05](05-the-chat.md) §3 has the registry's design.

**Steps**

1. `notify/NoticeId.kt`: every id the app uses, with a range of request codes for its buttons, in one enum: the eighteen as they are ([01](01-current-state.md) §3; the summary's 4004 among them), plus the ones later stages take, reserved now: `Felix` 6000 (5a), `CalendarQuestion` 4400 (the calendar's questions left 4200, which they shared with Felix's; retired in 5a, when they became his messages), `GmailWrite` 4500 (11). A test checks that no two ids or ranges overlap.
2. `notify/Notifier.kt`: `Notify`'s posting, holding and channel checks behind one class, with the port `Notices` (`post`, `cancel`, `shown`, `postHeld`) that services take ([02](02-target-architecture.md) §Ports); the seventeen files that post switch to it, keeping their ids; nothing else calls `NotificationManagerCompat`. `NotifyRules` stays pure beside it.
3. `ArchitectureTest`, with its first rule: only the notifier posts.

**Tests**: the registry; `NotifyRules` as now; the held list through the notifier. **Phone check**: a briefing (`briefing`) and a free-time notice through the debug commands, as before. **Leaves**: `NoticeId` and `Notices` for 5a's one notification and 11's failure; the test that catches a stray post.

## Stage 2: one model gateway (3.3.1)

**Needs** 0. **Does**: one class makes every call to Claude; no behaviour changes. [02](02-target-architecture.md) §ModelGateway.

**Steps**

1. `enrich/ModelGateway.kt`: `call`, `hold`, `worked`, `failed`, `keepCall`, `available`, `claudeKey`, `reconcileAlerts` and the alerts, moved from `AppGraph` (where they were `callModel`, `modelHold`, `modelWorked`, `modelFailed` and `modelAvailable`), with `Called`, `ModelHold` and the record of calls; its state is `runtime.json`'s model fields until Stage 7 gives them `model.json`.
2. One `AnthropicClient` a key and endpoint, kept, remade only when the key or the endpoint changes; the eleven readers (`ChatReader`, `ClaudeEnricher`, `ClaudeReviewer`, `FelixCaller`, `InstructionReader`, `MemoryTidier`, `MicroPlanner`, `PhotoChecker`, `PhotoTaskReader`, `SummaryWriter`, `WebResearcher`) take the client instead of building one; the timeouts by `ModelEffort.Job` in one table.
3. `AppGraph`'s factories go (`felixCaller()`, `microPlanner()`, `modelEnricher()`, `memoryTidier()`, `photoChecker()`, `photoTaskReader()`, `modelReviewer()`, `instructionReader()`, and the inline `ChatReader`, `SummaryWriter` and `WebResearcher`): a caller makes its reader from the gateway.
4. The architecture test's second rule: only the gateway builds a client.

**Tests**: holds (resting, the cap, interactive or not), the record of a call, a failure's class and the key's alert, through a fake `send`; one client for many calls, a new one after the key changes. **Phone check**: `ai-check` (every job through the real client against a local stub, free), then one `chat-ask` and one `enrich-task` (paid, a few cents), both in the record of calls with their costs. **Leaves**: the gateway every later call goes through (4's move, 8a's drafts, 10's prompts, 13's digest).

## Stage 3: stores and backups, with versions and migrations (3.3.2)

**Needs** 0. **Does**: every `JsonStore` made in one place, each with a version and a list of migrations; backups and resets enumerate the stores through it. No store's shape changes yet. This is the cleanup's 3a with the mechanism its Phase 5 planned pulled forward, so that every data change after it (5a's chat, 7's tasks and runtime, 8a's `felix.json`, 10a's memory, 11's journal) is made and tested the same way. [04](04-data-and-migrations.md) §Versions and migrations.

**Steps**

1. `data/Stores.kt`: makes each store with its file name, its state class, its version and its migrations; reports what couldn't be read (as `AppGraph.found` does); hands each service its stores; `AppGraph` builds it first.
2. `data/Migration.kt`: `version: Int = 1` in each state class (a file without one is version 1); `Migration(from) { JsonObject -> JsonObject }` (to `from + 1`) run in order by `JsonStore` as it loads, before decoding; the file it came from kept as `<name>.v<from>` until a week after the next successful start; `JsonStore`'s recovery after migration, as now. A migration that moves fields between stores runs in `Stores` at start, before any service reads.
3. `data/BackupService.kt`: `exportBackup`, `backupNow`, `importBackup`, `restoreInstructions`, `reset`, `restoreResetCopy` moved from `AppGraph`, enumerating stores through `Stores`, so a new store can't be left out of a backup or a reset; a backup carries its parts' versions, and restoring an older one migrates them first. The pure `Backups` object (encode, decode, the checks) stays as it is. A restore or a reset replaces whole stores, which the one-writer rule ([02](02-target-architecture.md) §The rules) doesn't allow a service to do: so `Stores` alone has `replace(store, value)`, the backup service calls it and nothing else does, and the architecture test names `Stores` as the one exception to "only the owner updates", tested as such.
4. The rehearsal (Stage 0) runs the migrations there are (none yet) and prints its counts before and after.

**Tests**: the mechanism with a made-up store (two shapes; the old file kept; run twice, the same; a backup of the old shape restored); the backup flows on temporary stores, which had none (a backup written and read back into an emptier phone; a reset and its copy restored). **Phone check**: `backup-now`; Settings → Backup → Import of it; a Reset of Stats and its copy restored (`state` before and after). **Leaves**: `Stores`, versions and migrations for every later data change; the backup service.

## Stage 4: Felix's code in one place (3.3.3)

**Needs** 1 (the `Notices` port), 2 (the gateway), 3 (`Stores`). **Does**: his service, `felix/Conversation`, and the Planning service, moved verbatim out of `AppGraph`, `learn/`, `enrich/` and `notify/`; the files that hold several types split. No behaviour changes; the chat work (5) is built on this. [02](02-target-architecture.md) §The services.

**Steps**

1. **The package.** `felix/`: `Conversation.kt` (the chat functions from `AppGraph`: `profile`, `ask`, `cancelAnswer`, `chatContext`, `answerChat`, `act`, `tick`, `undoAct`, `remember`, `undoMemory`, `replyToFelix`, `answerReply`, `tellAnswer`, `withdrawQuestion`, `clearChat`, `applyProposal`, `undoProposal`, `discardProposal`, the check-in flow `askCheckIn`, `askNext`, `checkInReply`, `endCheckInFlow`, `chatInFront`, the `Gathered` retry state); `Looks.kt` (3.3.2's `learn/Felix.kt`: `noteActivity`, `check`, `say`, `morning`, `queueQuestion`, `answered`, `draftAhead`); `FelixRules.kt`, `FelixPrompts.kt`, `FelixCaller.kt`, `CheckInFlow.kt`, `FelixWorker.kt`, `FelixReplyReceiver.kt`, `FelixReplyWorker.kt`, `FelixNotifications.kt`; `ChatReader.kt` split into `ChatPrompts.kt`, `ChatAnswers.kt`, `ChatRounds.kt` and `ChatReader.kt`. `learn/CheckIns.kt`'s screen becomes `ui/CheckInActivity.kt`; its `remind` goes to the service. So `learn/` is split across two moves: Felix's files leave it here, and what's left (the review, the stats, the calibration, the summary, the assessment's record, the briefing's day records) becomes `Learning` in 6e.
2. **The service's shape.** `Conversation(stores: chat, memory, tasks, instructions, settings, log, runtime; gateway; notices; clock; scope; callbacks into instructions, own tasks and the focus)`, built like `Focus`; `AppGraph.conversation` replaces the moved functions, with one-line delegates for one commit, then the callers switched (screens, receivers, workers, the debug commands) and the delegates deleted.
3. **Planning.** `plan/Planning.kt`: `AppGraph.plan()`, `workedToday`, `keepSlots`, the plan hooks, `PlanWatcher` (from the root) and `Ahead` (from `enrich/`); `Slots` stays in `block/`. `AppGraph.planning` replaces them the same way.
4. **Imports.** Every fully qualified name in the moved files becomes an import (the 198 in `AppGraph` go with the code that held them).
5. **The first tests of flows that had none**, on temporary stores through a fake gateway: the chat's answer with cards made at once and its undo; a card applied and taken back; the check-in's five questions through `checkInReply`; a reply from a notification landing under the message it answers (as 3.3.3's `replyToFelix` did); his look writing a record and posting (as it did then).

**Tests**: the five above; every existing test unchanged (the `FelixRules`, `ChatPrompts`, `ChatAnswers` and `CheckInFlow` tests move with their files). **Phone check**: the plan snapshot unchanged; `chat-ask` (paid), `felix-say` (paid), `check-in`, `briefing`, `chat-card --es do apply`, then `--es do undo`, each as before. **Leaves**: the service the chat work is written in, with its harness; `AppGraph` about 900 lines lighter.

## Stage 5: the chat as his one voice (4.0.0, 4.0.1, 4.0.2)

The design is [05](05-the-chat.md); the stores' change is [04](04-data-and-migrations.md) §The chat (v2).

### 5a. Every message in the chat (4.0.0)

**Needs** 0 (fixtures of `chat.json` and `log.json`), 1 (`NoticeId.Felix`, `Notices`), 3 (the chat's migration, the archive store), 4 (the service). **Does**: every word in his name is a message in the chat, first; one notification made from the chat's unread; the log's records migrated in; the window and the archive. The cleanup's 1b and the Felix plan's A as one PR, staged:

1. **The types** (`core/Chat.kt`): `MessageKind`, `ChatMessage.kind`, `byApp`, `replyTo`, `pieceKey`, `nextCheckAt`, `asks`, `opens`; `ChatState.readAt` and `felix`; `ChatArchive`; `felix/FelixWords.kt` holding the fixed lines as they are today. The migration to chat v2 ([04](04-data-and-migrations.md) §The chat): `reason` into `kind`, `remembered` and `done` into `memoryEdits` and `acts`, `RuntimeState.felix`, `assessLater` and `checkInDraft` into `ChatState.felix`, the log's `FelixRecord`s of the last 30 days into messages (your replies as messages with `replyTo`), merged by id with what the chat already holds (a record copied in by a reply, an answer's record) so nothing is doubled, `readAt` = the newest message's time; the old fields deleted from `ChatMessage`, `RuntimeState` and `ActivityLog`. Tested from Stage 0's fixtures, twice.
2. **The outbox** (`felix/Outbox.kt`): `send(message)` writes to `chat.json`, moves what's past the window and read to `chat-archive.json` (never an unread message; the archive's update first, then the trim), then `notify(now)`; `notify` builds his one `MessagingStyle` notification from the live messages of his after `readAt` with text, or cancels it when there are none; `chatInFront` suppresses the post. `FelixNotifications.post` becomes the builder `notify` calls: `NoticeId.Felix`, no card buttons, `setOnlyAlertOnce` when rebuilt after a reply, your reply shown as yours. (Built as `FelixNotifications.show`; your lines are a speaker of their own, "You", as One UI hides the Reply while the newest message is the user's, and a reply doesn't cancel it.)
3. **Each producer through it**, a commit each with its test: `Looks.write` and `Looks.morning` (the `FelixRecord` becomes the message); `Briefing.run` (the fallback list as a `byApp` Morning message, the title its first line); `askNext` and `checkInReply` (`CheckIn`, `byApp`, the thanks from `FelixWords`); `Review.run` (`Review`, `byApp`, the note's first line, `opens = "review"`); `writeSummary` (`Summary`, `byApp`, `opens = "summary:<week>"`; its own id and `SUMMARY_READY` gone); `Assessment.post` (`Completions`, `byApp`, one message a completion, `asks = listOf(it)`); `CalendarTime.ask` (`EventQuestion`, `byApp`, `about.eventKey`, `suggestedReplies` its three answers; `NoticeId.CalendarQuestion` retired); `tellAnswer` deleted. Each stops posting a notification of its own.
4. **The readers**: `FelixRules.due`'s `sent` from the chat (a `Sent(reason, key, at)` view, so its tests stay); `FelixPrompts.describe`'s recent from the chat (his last six with text, each with the next message of yours that has `replyTo` it, or the next of yours within ten minutes); `ReviewInput` and `Summary.input` counting from the chat; `Looks.queueQuestion`'s "asked lately" and `Looks.answered` by message.
5. **Held, by kind**: `NotifyRules` says which kinds of his wait for the morning (Review, Summary, EventQuestion: the ones whose channels were ceilinged; the morning's own message is the briefing, at its time, so it doesn't wait: `NotifyRules.MORNING_KINDS`); `Outbox.notify` leaves those out of the notification until the briefing's rebuild, never a held copy; `Notifier.postHeld` stays for the app's own. The `Notices.shown` check that stopped `Looks.due` and the morning before they wrote moves to `Outbox.notify`: his channel off silences the notification, not the chat (Claude off still means no look). His channel for all of it (Q3); `Channels.create` deletes the briefing's once empty, as it does `daily`; the weekly channel keeps only the check-in's form reminder (`Conversation.remindCheckIn`, posted by `FelixPorts.remindForm`).
6. **Clear and Reset**: `clearChat` and Reset → "The chat and Felix's history" empty the archive too; the backup carries the archive while it fits (`Backups.MAX_BYTES`), the oldest left out first and said in Settings → Backup.
7. **The docs**: `felix.md` §His messages and §Notifications and replies; `storage-and-backups.md` (the archive); `decisions.md` (the chat is his one voice; every message kept).

**Tests**: [05](05-the-chat.md) §Tests: the outbox's, the migration's, the archive's, the notification's. **Phone check**: [05](05-the-chat.md) §Phone check: `felix-say` twice → one notification; tap → the chat at it; `briefing`, `check-in`, `assess` in the chat; the old records in the chat at their times. **Leaves**: the kinds, `byApp`, `replyTo`, `opens`, `readAt`, the outbox and `FelixWords` for 5b, 5c, 8, 10b; the `Reminder` and `Note` kinds declared now, used by 8a and 8b.

### 5b. WhatsApp-like (4.0.1)

**Needs** 5a. **Does**: read state, dates and times, the archive paged in, long-press and swipe to reply, one entrance for replies, `Speaker.Felix`. [05](05-the-chat.md) §4 and §5.

1. **A state holder**, `ui/ChatScreenState.kt` (a plain class, tested without Compose; the pattern [11](11-conventions-and-docs.md) §Code names for the largest screens, first used here): `firstUnread` from `readAt`, the open set, which messages get a label, the earlier pages loaded; `AskActivity`'s composables read it.
2. **Dates and times**: a date item between days; the time under each bubble.
3. **Read state**: `readAt` set in `onPause` and on a notification's tap (`AskActivity.intent(context, openAt)`); the divider; the list scrolled to `openAt` or `firstUnread`; the badge on `FelixFab` (and `BlockedActivity`'s) from an `unread` flow the service exposes.
4. **Earlier messages**: `ChatArchive` loaded with the other stores at start and shown 100 a page by the state holder, never put into `ChatState`.
5. **Long-press and swipe**: `combinedClickable` for Reply and Copy; a horizontal drag around each bubble that springs back (`draggable`), past a third of the width with a haptic on crossing it; the reply strip over the box; `Conversation.ask(text, about, id, replyTo)`.
6. **One entrance**: `felix/Replies.kt`, `route(message, words)` pure ([05](05-the-chat.md) §5's table); `Conversation.ask` calls it when `replyTo` is set; `FelixReplyReceiver` calls `replyFromNotification(words, id)`, sending it as any reply (`send` with `replyTo`); `replyToFelix` and `answerReply` go (`FelixReplyWorker` stays, running `answerChat` after the receiver's gone, for a turn of the chat). The `chat-ask` debug command gains `--es replyTo <message id>`, so a routed reply can be checked from the PC without a tap ([14](14-your-part.md)).
7. **The model told**: `ChatPrompts.turns` prefixes the quote where the replied-to message isn't the one before; `text()` marks `byApp` messages "(the app wrote this for you)"; one sentence in `ChatPrompts.system`; `chat.txt` and `felix-inputs.md` regenerated.
8. **`Speaker.Felix`** with `@SerialName("Claude")`; `decisions.md` (one entrance for replies).

**Tests** and **phone check**: [05](05-the-chat.md). **Leaves**: `Replies.route` (8a adds `Reminder`'s branch), the state holder, `ask(replyTo)`.

### 5c. The extras folded (4.0.2)

**Needs** 5b. **Does**: [05](05-the-chat.md) §6. 1. `ChatSummary.line` and `problems` (`core/Chat.kt`), pure, first. 2. `Claudes` (`ui/AskActivity.kt`): the tail under `AnimatedVisibility(open)`; the chevron, red for problems; the open set in the state holder, cleared on pause; the `opens` button and the chips outside the fold. 3. The label behind the bubble, 300 ms in, five seconds, back; a tap opens the tail. A bare answer (nothing to fold) has neither.

**Tests** and **phone check**: [05](05-the-chat.md). **Leaves**: the fold 10b's "just talk" relies on.

## Stage 6: the rest of the services, and the pure module (4.0.3 to 4.0.8)

**Needs** 3 (`Stores`); 2 for 6d; 5a for 6e (it moves `Review.run`, `writeSummary` and `Assessment` as 5a left them). **Does**: `AppGraph` shrinks to wiring; each service gets tests of the flows that had none ([02](02-target-architecture.md) §The services). Each PR: the move verbatim with delegates, the callers switched, the delegates deleted, the service's tests, the moved files' names imported. The plan snapshot and the goldens are unchanged by every one.

| PR | Version | Service | From | Tests added |
|---|---|---|---|---|
| 6a | 4.0.3 | `CalendarWriter` (`calendar/`) | `writeSlots`, `clearOldCalendars`, `watchCalendar`, `calendar/SlotCalendar*`; `CalendarTime.answer` and the event answers | the copy and move rules through the `CalendarProvider` port, beyond `SlotCalendarDiff` |
| 6b | 4.0.4 | `Protection` (`protect/`), and the Settings screen's state holder (`ui/SettingsScreenState.kt`, C-Q11: a plain class holding the screen's state and actions, the composables only drawing) | `changeSettings`, `applyReviewChanges`, `applyDueChanges`, `useParentCode`, `cancelChange`, `applyNow`, `protect/*`; `SettingsActivity`'s logic | armed and unarmed paths; `ProtectTest` as it is; the state holder's actions without Compose |
| 6c | 4.0.5 | `Instructions` and `OwnTasks` (a package `tasks/`; the pure folds stay in `core/`), and the task sheet's state holder (`ui/TaskSheetState.kt`, beside today's `TaskSheetModel`, C-Q11) | `addInstruction`, `addOwnChange`, `laterToday`, `takeBackQuick`, `quickInForce`, `rereadInstruction`, `readInstructions`, `applyInstruction`, `deleteInstruction`, `layTaskOverrides`; `saveOwnTask`, `finishOwnTask`, `reopenOwnTask`, `deleteOwnTask`, `pruneOwn`; `TaskSheet`'s logic | an instruction applied, refused and deleted (on the phone through a new `instruction-delete` debug command, as none deletes an applied one); a quick change taken back; an own task saved, finished, reopened, deleted; the sheet's state holder |
| 6d | 4.0.6 | `Readings`, `Sync` and `Photos` (`enrich/`, `sync/`, `block/`) | `enrichNow`, `readAgain`, the dropped-plan alerts; `Syncer`, `refreshAll`, `requestTeamsSync`, the Teams and calendar observers, `CalendarTime.refresh` and `read` (its questions asked through the Conversation's outbox, as 5a left them; no cycle: the Conversation never calls Sync); `attachPhoto`, `readPhotoTasks`, `readPhotoOf`, `sweepPhotos` | a sync through fake sources with one failing; a reading due and refused; a photo attached |
| 6e | 4.0.7 | `Learning` (what's left in `learn/` after Stage 4 took Felix's files) | the activity log's owner: `Review.run`, `Stats`, `Calibrator`, `forgetCalibration`, `undoReviewChange`, `undoTidy`, `writeSummary`, `Assessment.record` (its question is the Conversation's since 5a); the Conversation's writes to the log (a check-in kept, a completion's answer) become calls to `Learning.recordCheckIn` and `recordAssessment`, so the log has one writer | a review from a fixed log; its undo; the summary's input; a check-in recorded through it |
| 6f | 4.0.8 | the `:core` module | not a bare move: `core/` reaches into the app today (`Planner` and `Slotter` take `data.Settings`; `SchoolDays` reads Power Planner's types; `Task` reads `sources.anki.AnkiRules`; `Enrichment` reads the Gmail and Teams extras' keys and `Prompts.VERSION`; `ChatActs` reads `data.FelixSettings`; since 4.0.0, `Chat` reads `data.FelixState`, which holds `felix.FelixAnswer` and `CheckInFlow.Draft`, and `data.AssessLater`), and a module the app depends on can't depend on the app. First the dependencies are turned round, in commits of their own: `Settings`, `Window`, `FelixSettings`, `FelixState` (with the two it holds) and `AssessLater` are pure and move into the module with `core/`; the sources' constants `core` reads (Anki's rules, the extras' keys, Power Planner's types) move into `core/` or become values passed in; `Enrichment`'s version check takes the version as a parameter. Then `core/` and its tests become a plain Kotlin module the app depends on (C-Q11). A day, not half. | the architecture test's third rule, `core` free of Android, now held by the compiler |

**Phone check** after each: the script, and that PR's concern through the debug commands (a sync, a re-read, an instruction applied and taken back, a backup written, `review`). **Leaves**: `AppGraph` under 400 lines, its members the services; the Sync service 8b and 11 build in; `Learning`, where 10b's summary prompt sits; the module every later pure rule goes into.

## Stage 7: who set what, and the stores split (4.0.9)

**Needs** 0 (the rehearsal), 3 (migrations), 6 (every service owning its store; `Instructions` laying `mine`). **Does**: the cleanup's Phases 4 and 5 in one data release (C-Q10): four staged commits, one rehearsal on your latest backup, one backup before the install, one migration to watch. [04](04-data-and-migrations.md) §Tasks v2, §`runtime.json` split, §Finished tasks trimmed.

1. **Your values as one value** (the cleanup's 4a): `core/Mine.kt` (`Mine`, every value wrapped as `Set<T>(value, by)` so each carries who set it, `SetMinutes`, `Paced`, `SetSteps`, `By { You, Felix }`); the instructions' fold returns `Mine`, `TaskOverrides` goes; `Instruction.by` (`You` from the instructions screen and the sheets' quick changes, `Felix` from the chat); `TaskItem.mine` replaces the twelve `user…` fields and the derived flags (`yourMinutes`, `yourSteps`, `pacedMinutes`, `pacedTeaches`, `effortMin`, `stepScale`, `multiplierFor`) read it; the migration drops the old fields and `Instructions` lays `mine` from the instructions in use before anything reads the tasks. An instruction from before gets `by = You`, the value that changes nothing (`by` only labels; the app treats yours and Felix's alike, D-4), and `Felix` only where the chat still holds the card that made it: who set an old instruction can't be told otherwise, as the chat is capped and clearable ([04](04-data-and-migrations.md) §Tasks v2).
2. **Steps with an id and an origin** (4b): `SubStep.id` and `SubStep.origin` (`Source`, `Reading`, `Rules`, `Yours`, `Felix`, `OwnTask`), set wherever steps are made; `OwnStep.id` too, made when an instruction is read and kept in its `Steps` change and `SetSteps`, so laying the instruction again carries the same ids and no tick on a step of yours or Felix's is lost; `stepsFromEnrichment()` asks the origins; `carryMarks` carries by id where both lists have ids, by title only for a reading made again; the migration gives every step an id and, once, the origin today's title rule infers, and gives an instruction's step the id of the task step it laid. Slot keys stay by place (a later, separate change).
3. **`runtime.json` split** (the cleanup's 5a) by owner and by how often it changes: `session.json` (Focus), `protection.json` (Protection), `model.json` (the gateway), `anki.json` (Focus's Anki watch), `calendar.json` gains what was written where and the event answers (CalendarWriter), `learning.json` (the calibration); Felix's state went to `chat.json` in Stage 5a; what's left stays in `runtime.json`. One migration in `Stores` at start.
4. **Finished tasks trimmed** (5b): a task finished more than 14 days ago keeps its record and drops its text, reading, answers, photo and bulky extras (C-Q3); a source listing it again brings its text back and it's read again, a paid call. `pruneOwn`, which deletes a finished own task after that fortnight today, stops doing so and trims it like the rest: every finished task keeps its record, and only an explicit delete removes an own task's; tested.

**Tests**: every flag of 4a against the cases that made it (the 3.1.2 to 3.1.4 tests, kept); ticks following a step through a re-read that reorders; origins across a sync, a reading and an instruction; each migration from every fixture shape, twice; a backup from before restored after; the rehearsal's invariants. **Phone check**: the rehearsal first; after the install the open tasks, their ticks and your values as they were (counts compared with the rehearsal's); the stores' sizes; a slot kept, a sync, a backup written. **Leaves**: `By` for 8a's notes and reminders; `model.json` and the split stores; the records 8a's notes and 10a's task-bound facts outlive a trimmed task on.

## Stage 8: notes, reminders and the gaps (4.1.0, 4.1.1)

### 8a. Notes and reminders (4.1.0)

**Needs** 2 (drafts through the gateway), 3 (`felix.json` versioned), 4 (the service), 5 (the outbox, the kinds, `Replies.route`, `FelixWords`), 7 (`By`). **Does**: [07](07-notes-and-reminders.md). One PR, staged:

1. **The types and the store**: `core/Notes.kt` and `core/Reminders.kt` (`Note`, `Reminder`, `Due`, `DueKind`; `Reminders.next`, `due`, `snoozed`, `prune`; `Notes.prune`), pure; `data/FelixStore.kt` (`FelixStore(notes, reminders, version = 1)`) as `felix.json` from `Stores`, owned by the Conversation.
2. **The schema and the prompt**: `ChatPrompts.SCHEMA` gains `reminders` (with an `id`, empty for a new one, a listed reminder's to move or reword it in place) and `notes` (flat strings and ints, `""` and `-1` for an unused field, as 3.1.6 shaped the lists) and the actions `cancelReminder` and `deleteNote`; items 8 and 9 in `ChatPrompts.system`; the context lists reminders after the settings and each task's notes under its steps. Goldens rewritten; `SchemaLimitsTest`; `SchemaCompileTest -PcheckSchemas=true`, its result in the PR. Refused: the Q13 fallback in the same commit (`ChangeType.Remind` and `ChangeType.Note`, read into the same `Reading` fields, so nothing after this step changes).
3. **The parse**: `ChatAnswers.Reading.reminders` and `notes` with the checks (a time gone by, a task not listed, the lengths, the counts); what's refused into `leftOut`.
4. **The acts**: `Conversation.act` makes them, `Act` lines with Undo (`ChatAction.Type.SetReminder`, `MoveReminder` (the old due and words kept on the act for its Undo), `CancelReminder`, `AddNote`, `DeleteNote`; `ChatActs.canUndo` false once sent, snoozed, deleted or changed since). A cancel marks `cancelledAt` and a delete `deletedAt`, neither removes: the thing stays whole for its Undo, skipped by delivery and the lists, pruned with the message's life.
5. **The alarm and the sweep**: `SlotAlarms.armReminder(context, at)` (request code 4, `ACTION_REMINDER`), set from Planning's hooks and after every change to the store; `SlotReceiver` → `Looks.sweepReminders(now)`: each due one through the outbox (its draft, or the verbatim words from `FelixWords.reminder`, a line added here, marked `byApp`), `sentAt` set, late marked; also at app start and in `Looks.check`.
6. **The drafting**: `FelixPrompts.Reason.Reminder` and its `describe` branch; `Looks.draft` extended: after a slot draft, each reminder due within `AHEAD_MS` without a fresh draft, one call each through the gateway; a snooze or a cancel clears the draft. `Looks.draftReminders(now)` is also called right after a reminder is set, snoozed or moved (from the Conversation's act, in the app's scope), so one due inside the window gets its draft without waiting for a plan or a look. `felix.txt` and `felix-inputs.md` regenerated.
7. **The replies**: `Replies.route` for kind `Reminder`: *Done*, the two snoozes (the alarm re-armed), anything else a chat turn with the quote.
8. **Notes shown**: `TaskSheet`'s About lists them with a delete and "Add a note" (`By.You`); `FelixPrompts.describe` adds "Notes on it: …" and "Their reminder for this slot: …"; `Ahead.microPlans` passes the task's notes with its instructions in `MicroPlanner.plan`'s user text.
9. **Kept and cleared**: the backup (`Backup.felixStore`, format 8: 5a took 7 for the chat's archive; checked by `Backups.problem`); Reset → "The chat and Felix's history" clears it; the debug page and the `felix` command show it; pruning in the nightly run (`Daily`), a deleted own task's notes waiting until its delete's act can no longer be undone.
10. **Standing notes** (Q31: A): `Note.taskId` optional beside `scope: NoteScope?` (subject, class code, source, title contains); `Notes.forTask(task, notes, classMap)` pure, the class map an input so a subject matches a task's class code, tested first; the schema's `notes` gains the four plain strings (no union); the prompt's note item says a note can be for a task or for every task of a class, subject or source; the sheet shows "Note for every chemistry task: …"; the context, `FelixPrompts.describe` and the micro-planner take them through `forTask`, as a per-task note.
11. **The docs**: `felix.md` §What he can do and §His messages; `felix-inputs.md` with a reminder's, a note's and a standing note's example; `decisions.md`.

**Tests** and **phone check**: [07](07-notes-and-reminders.md). **Leaves**: notes in his context; the store 10a's task-bound facts sit beside.

### 8b. The gaps (4.1.1)

**Needs** 5 (the outbox for `Note` messages; `Conversation.act`), 6d (the Sync service's `syncNow` and the `Syncer` listener), 8a (the schema's shape settled; one API compile covers both). **Does**: [06](06-what-felix-can-do.md) §3: G1, G2 as the stop, G3 and G5 (Q17), a commit each with its test, prompt line and golden:

1. **G1**: `ChatAction.Type.KeepPiece` and `UnkeepPiece`; the schema's `type` enum gains `keepPiece` and `unkeepPiece`; `ChatAnswers.action` reads `value` as the piece's key: a keep is refused unless the plan shown lists it, an unkeep unless today's kept records do (a kept piece leaves the plan, so the context carries a "Kept today: …" line with their keys); `Conversation.act` → `focus.keepSlot(key)` and `focus.unkeepSlot(key, since)`, the `Act` lines "Kept: …" and "Not kept after all: …", Undo the reverse (not once the slot's record has changed since); `ChatPrompts.plan` adds the key to today's slots, to the next piece of each task without steps, and the kept line; prompt item 5.
2. **G2**: `ChatAction.Type.Sync`, `setting` the source's name; `Conversation.act` → a `requestSync(sources)` callback the Conversation takes in its constructor, wired in `AppGraph` to `Sync.syncNow` (Sync already calls the Conversation for the calendar's questions, so the Conversation can't call Sync back: the no-cycle rule), the line "Asked Teams to read", no Undo; a `Syncer` listener in the Conversation posts a `Note` message through the outbox when that read changes the task he was asked about ("teams says the worksheet's handed in"); prompt line.
3. **G3**: `open` gains `value` "app" or "source"; "source" → `TaskOpener.open`, which already tells him it was opened; prompt line.
4. **G5**: `ChatPrompts.plan` takes a line from `Focus` ("Blocking: on until today's work's done (2 pieces, 1 h); free time 12 min", from `verdict`, `rewardLine` and `creditLeftMs`), passed by `Conversation.chatContext`; the doc page shows it.
5. The goldens (`chat.txt`, `chat.schema.json`); `SchemaLimitsTest`; `SchemaCompileTest -PcheckSchemas=true`; `felix.md` §What he can do.

**Tests**: each action's parse, act and Undo; the context line in the doc page. **Phone check**: `chat-ask` for each (paid): "I've done today's bit of…", "check Teams", "how much free time have I got", "open it in Teams".

## Stage 9: names (4.1.2)

**Needs** 6 (the services in place, so these are the last of the moves). **Does**: the mechanical part of [11](11-conventions-and-docs.md) §Names and §Files, before the prompts are rewritten in Stage 10: `Prompts` and `Answers` become `ReadingPrompts` and `ReadingAnswers` (`GoldenPromptsTest`'s path to `Prompts.kt` with them; `Prompts.VERSION` becomes `ReadingPrompts.VERSION`, its value unchanged); `InstructionReader.kt` split into its prompts, answers and reader; `EnrichTest.kt`'s seven classes into seven files; any file left holding two unrelated types split. The goldens' contents don't change; the plan snapshot doesn't. One Codex round (a move).

**Tests**: all pass unchanged but for their file names and the renamed references (the callers of `Prompts.VERSION` too: the debug page, and whatever of `AppGraph`'s reading lines 6d left behind). **Phone check**: the script only. **Leaves**: the names 10a and 10b write against.

## Stage 10: memory and the voice (4.2.0, 4.2.1)

### 10a. Memory that expires (4.2.0)

**Needs** 3 (`memory.json` versioned), 4 (the Conversation owns memory), 7 (a task-bound fact outlives a trimmed task on its record), 9 (`ReadingPrompts.VERSION`). **Does**: [08](08-memory-that-expires.md). One PR, staged:

1. `core/Memory.kt`: `Fact.until`, `Fact.taskId`, `Rule.until`; `Memories.expired(state, tasks, now)`; `Memories.edit` reading `Given.until` and `Given.taskId` with the checks (past refused with its line, over a year cut, an unknown task kept without and said); `MemoryEdit.line` with the until; `undo` comparing whole; `memory.json` version 2 (defaults, no migration code). Tests first.
2. `MEMORY`'s `until` and `taskId` strings in `ChatPrompts.SCHEMA` and `FelixPrompts.SCHEMA`; both parsers; the goldens; `SchemaLimitsTest`; `SchemaCompileTest -PcheckSchemas=true` on both, the result in the PR.
3. The prompts' until rule and one example each; the "quiet until" example in `felix.txt`; `felix-inputs.md`.
4. `Conversation.profile` runs `Memories.expired` and writes the result when it differs (one `update`, idempotent); `Briefing.run` calls it at the morning's sweep too.
5. `Profile.block` shows "(until Sun 25 Oct)" and "(with “Trip consent form”)"; `profile.txt` regenerated; `ReadingPrompts.VERSION` 8, the one re-read of the open tasks accepted (Q15).
6. `MemoryTidy.due` and `describe` on facts with neither field only; `after` keeps the expiring ones as they were.
7. `MemorySection` (Settings → Felix and the chat's menu) shows the until and the task; the backup carries the fields.
8. `felix.md` §What he remembers; `decisions.md`.

**Tests** and **phone check**: [08](08-memory-that-expires.md).

### 10b. His voice (4.2.1)

**Needs** 5 (`FelixWords`, `byApp` marked in the history, the fold), 6e (the summary's writer in Learning), 9. **Does**: [09](09-his-voice.md). One PR, staged:

1. The register paragraph and the three or four exchanges for each prompt (one a vent) written in a scratch file and read by you first; this commit is the text in the two prompt objects and nothing else, so the goldens' diff is the whole change.
2. `ChatPrompts.system` and `FelixPrompts.system` take the paragraph, the examples and the "just talk" sentence; `Settings.felixLine`'s default (Q29); `GoldenPromptsTest -PupdateGolden=true` for `chat.txt` and `felix.txt`; `FelixInputsDocTest -PupdateDocs=true`.
3. `FelixWords` reworded in the register (the check-in's five questions and its thanks, the summary's offer, how work went, a calendar event's question, a reminder's verbatim frame; the morning's list-only title, which `Briefing.summary` makes); `Assessment.ANSWERS` and `CheckInFlow.answered` match both forms through `FelixWords.chip`.
4. `SummaryPrompts.SYSTEM` to the third person (Q25); `summary.txt` regenerated.
5. `EvalRunTest -PrunEval=true` on both private sets, the rates in the PR beside today's; the register tightened and run again if a change or a date is lost.
6. `felix.md` §Who he is; `decisions.md`.

**Tests** and **phone check**: [09](09-his-voice.md).

## Stage 11: emails done (4.3.0)

**Needs** 1 (`NoticeId.GmailWrite`), 3 (`gmail-writes.json` from `Stores`), 6d (the Sync service: `finishGmail`, `GmailSource.connect` shared, the worker beside `SyncWorker`), 7 (the split stores, so the journal has a file of its own from the start). **Does**: [10](10-emails-done.md). One PR, staged:

1. **The writer.** `sources/gmail/ImapWriter.kt`: `select(mailbox)`, `uidFetch(uids, "FLAGS X-GM-LABELS")` (the subsets to record before a write), `uidStore(uids, item)`, `logout`, over the same reader and socket as `ImapClient`; its test over a fake stream; `ImapClientTest` asserting the reader's command list has neither `SELECT` nor `STORE`. Nothing calls it yet.
2. **The rules.** `core/GmailFinish.kt` (`due(task, plan)` for the automatic triggers); `GmailWrite` (with `flagged` and `inbox` as Gmail's message ids, the subsets a reverse write restores whatever the `UIDVALIDITY`, and `notBefore` null while the first finish waits for your OK) and `GmailWrites.cancel`, `reverse`, `next`, `confirm` (the grace, undo and first-time rules), pure; `data/GmailWriteStore.kt` as `gmail-writes.json` (version 1; its receipts in the backup, its pending entries not; cleared by no reset). Setup's attention mark and the sheet read a given-up receipt from here, not from the source's read status, which a later sync would clear; the sheet's "It's sorted" clears it.
3. **The worker.** `sync/GmailWriteWorker.kt` (WorkManager, the network constraint, exponential backoff, unique work): the next entry past `notBefore`; connect as the reader does (made shared), `LOGIN`, `SELECT` All Mail, the stored `uidValidity` checked against the mailbox's (changed: the thread listed again by `X-GM-THRID`), the `UID FETCH` of message ids, flags and labels written to the entry before anything changes (a reverse write after a `UIDVALIDITY` change lists the thread again and maps the ids back to UIDs), the two `UID STORE`s on those subsets, each one's success written to the entry as it happens (`unstarred`, `archived`) so a retry resumes at the one that failed, `LOGOUT`; success keeps the entry as a receipt (`doneAt`, its subsets what an Undo restores, pruned with the act's window) and asks `Sync.syncNow(setOf(Source.Gmail))`; failure bumps `tries` and `lastError`; the fifth failure marks it given up, posts `NoticeId.GmailWrite` on `Channels.SOURCES` (Q27) with words from what did succeed, and marks the task and the source's status. Through a `Writer` interface, so the test fakes it.
4. **The triggers.** `Focus.keepSlot` and `Focus.tickBlock` call `Sync.finishGmail(task, explicit = false)` (from `Focus` as a callback wired in `AppGraph`, as `onOwnTaskDone` is, so `Focus` doesn't depend on Sync), which asks `GmailFinish.due`; `Conversation.act`'s `Done` and the sheet's button call it with `explicit = true`, which doesn't. Either way: the entry with `notBefore = now + GRACE` and the worker enqueued for it, except the very first finish, journaled with `notBefore` unset and no worker until the sheet's *Carry on* (shown by the next screen of the app to open, and the only answer put into `hintsSeen`; *Not this time* drops the entry and asks again next time; *Turn this off* drops it and sets `gmailFinishes` off). `Focus.unkeepSlot`, `untickBlock` and the `Act`'s Undo call `Sync.unfinishGmail(task)` the same way: within the grace (or while awaiting the OK) the entry's dropped; after it, a reverse entry restoring the subsets the receipt recorded.
5. **Felix.** `ChatAnswers.action`'s `Done` on a Gmail task allowed when `settings.gmailFinishes`; the prompt line and the context's "finish with done" mark; the empty chat's example ("I'm done with the trip email"); `chat.txt` regenerated.
6. **The sheet.** "Finish: unstar and archive" on an email's About (behind the switch); "Done: unstarring it in Gmail" with *Undo* while an entry or its receipt stands (cancel within the grace, the reverse write after), so a finish from the sheet is as reversible as the chat's or a tick's; the failure's line with "It's sorted".
7. **The switch and the sheet.** `Settings.gmailFinishes` (default true); Settings → Sources → Gmail and Setup's Gmail text; the first-time sheet, shown by `MainActivity`, `AskActivity` and the task sheet when an entry awaits the OK, its answer put away into `hintsSeen`.
8. **The notification.** `Channels.SOURCES` made in `Channels.create` with the others; `NoticeId.GmailWrite`'s registry test.
9. **The docs**: `sources.md` §Gmail; `felix.md` §What he can do; `decisions.md`: D-7, D-10 and D-54 amended, the new one added (Q19).

**Tests** and **phone check**: [10](10-emails-done.md), with your mailbox and nothing of it in the PR (D-79).

## Stage 12: the sweep (4.3.1)

**Needs** everything. **Does**: what [11](11-conventions-and-docs.md) asks of the code as a whole, now that nothing is moving: every inline fully qualified name left becomes an import (437 at 3.2.0, 258 after Stage 4, most of the rest gone with the moves); comments that only date a change lose the date, and a decision they retell becomes its number; the architecture test's fourth and last rule added, one writer a store, which only holds once every service owns its store (the first three came in Stages 1, 2 and 6f: only the notifier posts, only the gateway builds a client, `core` pure by the module); the baselines of Stage 0 compared with now (test count, `AppGraph`'s size, the stores' sizes, build time) and the README's done-when list ticked; `docs/spec/` read through once against the code. One Codex round.

## After

The conversation digest, 4.4.0 ([13](13-summaries.md), Q28): planned on its own once you've lived with the chat a few weeks.

## Size

| Stage | PRs | Days |
|---|---|---|
| 0 | 1 (no release) | ½ |
| 1, 2, 3 | 3 | 2 |
| 4 | 1 | 1 |
| 3.3.4 (QUESTIONS.md I2) | 1 | — |
| 3.3.5 (I3), 3.3.6 (open items) | 2 | — |
| 5 | 3 | 4 |
| 6 | 6 | 4½ |
| 7 | 1 | 2½ |
| 8 | 2 | 4 |
| 9 | 1 | ½ |
| 10 | 2 | 1½ |
| 11 | 1 | 1½ |
| 12 | 1 | ½ |
| | 25 (24 releases) | about 23 |

Evenings and weekends, as every release ([12](12-verification-and-risks.md) §Risks: the phone's away on school days).
