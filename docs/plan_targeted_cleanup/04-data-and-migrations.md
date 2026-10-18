# The stores, and how their data moves

What's kept where after the cleanup, and how each change to stored data is made safely on a phone whose data must not be lost.

## The stores, now and after

| File | Now | After | Owner | Changes |
|---|---|---|---|---|
| `tasks.json` | every task, finished ones with their whole text (590 KB) | open tasks whole; finished ones as records, their text dropped a fortnight after they're finished (Phase 5b, Q3) | Sync (Readings, Instructions and OwnTasks through it) | at each sync, tick or reading |
| `runtime.json` | 38 fields, from the half-minute state to logs (98 KB) | what's left: the daily runs, the last backup, Teams' auto-sync, duplicates told apart, the stores' problems | small owners | rarely |
| `session.json` | | credit, today's slots, slots being kept, photo checks under way, completions rewarded | Focus | every half minute |
| `protection.json` | | changes waiting, the override, armed, the code's lock, protection's state, forced blocking, the uptime mark | Protection | on a change |
| `model.json` | | the month's usage, the last calls, failed readings, dropped-plan alerts, micro-plans | ModelGateway | at each call |
| `anki.json` | | Anki's readings, when AnkiDroid came to the front, the minutes worked in it | Focus (Anki's watch) | as AnkiDroid is used |
| `calendar.json` | the versions written | also what was written where, the calendars to clear, your answers about events | CalendarWriter | at each write |
| `chat.json` | your messages and Felix's answers | every message of Felix's too, and his state (next check, draft, retry, questions, completions to ask about, the check-in's draft); the live window only, the rest in its archive | Conversation | at each message |
| `chat-archive.json` | | the messages past the chat's window, oldest first, never sent to the model, read on scrolling back (plan_v4 Q8) | Conversation | as the window moves |
| `log.json` | slots, completions, blocks, days, check-ins, reviews, Felix's records | the same, without Felix's records | Learning | at each slot and completion |
| `learning.json` | | the calibration (from `runtime.json`) | Learning | at the review |
| `instructions.json`, `settings.json`, `memory.json`, `school.json`, `secrets.bin` | | as now | Instructions, (all), Conversation, Planning, Stores | |

`RuntimeState`'s 38 fields, by where they go:

| Goes to | Fields |
|---|---|
| `session.json` | `credit`, `slots`, `keeping`, `photosDone`, `rewarded` |
| `protection.json` | `pending`, `overrideUntil`, `adminArmed`, `codeLock`, `protection`, `forceActiveUntil`, `uptimeMark` |
| `model.json` | `aiUsage`, `calls`, `enrichFailed`, `droppedAlerted`, `microPlans` |
| `anki.json` | `ankiReadings`, `ankiFrontSince`, `ankiWorked` |
| `calendar.json` | `calendarWritten`, `calendarWrittenTo`, `calendarsToClear`, `eventAnswers`, `eventsAsked` |
| `chat.json` (Felix's state) | `felix`, `assessLater`, `checkInDraft` |
| `learning.json` | `calibration` |
| the notifier's (in `runtime.json`) | `notifiedToday`, `heldNotifications` |
| stays in `runtime.json` | `dailyRanAt`, `dailyRetries`, `lastBackupAt`, `teamsAuto`, `notSame`, `unreadable`, `unreadableSeen` |

On today's numbers, the half-minute state's file would be a few kilobytes instead of 93.

## Versions and migrations

Today a store is read by its serializer, with defaults for what's missing and fields kept from older shapes. From Phase 5a each store has a version and a list of migrations:

1. **A version in each state class** (`val version: Int = 1`); a file without one is version 1.
2. **Migrations on the JSON, before decoding**: `Migration(from, to) { obj: JsonObject -> JsonObject }`, run in order by `JsonStore` as it loads. Working on the JSON lets a migration rename, move or drop fields that the new class no longer has.
3. **The old file kept**: once migrated, the file it came from is kept as `<name>.v<from>` until a week after the next successful start, and the new one written atomically as now.
4. **One release, many stores.** A migration that moves fields between stores (the `runtime.json` split) is made by `Stores` at start, before any service reads: it reads the old file, writes the new ones, and only then trims the old.
5. **Backups go through the same migrations.** A backup carries its parts' versions; restoring an older one migrates them first, so a backup made before the split restores after it.
6. **Unreadable stays recoverable.** `JsonStore`'s recovery (each field that reads on its own kept, the file set aside) runs after migration, as it does now.

### Testing a migration

- **From every shape it may meet.** Phase 0's fixtures are the first shape; each migration adds its output's, and its test runs every older fixture through every migration since.
- **On the real data, never committed.** The rehearsal ([03](03-phases.md#phase-0-the-safety-net)) migrates the latest phone backup in the scratch folder and prints counts before and after: tasks, open ones, steps ticked, your values, instructions in use, chat messages, pins, the month's spend. Any difference stops the release.
- **Idempotent.** Each migration run twice gives what it gives once.

## The data changes, one by one

### Felix's records into the chat (Phase 1b)

Each `FelixRecord` becomes a message of his: `id` and `at` as they were, `text`, `replies` as its suggested replies, `reason` as its kind, `key` and `taskId` as what it's about, and `nextCheckAt`, `asks` and the reply he got as its own fields. `FelixRules` reads today's messages from the chat. The log's list is copied in once, the last 30 days (Q5: A), then dropped from `ActivityLog`.

### Tasks v2: your values and steps (Phase 4)

- **Your values.** The twelve `user…` fields go, and `TaskItem.mine` takes their place. No values need converting: they're laid from `instructions.json` (the plan does so at every run already, `AppGraph.plan`), so the migration drops the old fields and `Instructions` lays `mine` from the instructions in use before anything reads the tasks. A task whose instructions are gone has nothing of yours, as now.
- **Steps.** Each step gets an id (eight random hex characters, kept from then on) and an origin, inferred this once as the app tells them apart today: the reading's steps (`stepsFromEnrichment`) are `Reading`, or `Rules` where the rules made the reading; steps laid from your instruction are `Yours` (or `Felix`, where the instruction was the chat's); a task of your own's are `OwnTask`; the rest (Anki's) `Source`.

### Finished tasks trimmed (Phase 5b)

A task finished more than 14 days ago (Q3: B) keeps what the rest of the app reads of it: its identity, title, class, kind, dates and status, its estimates and minutes worked, its steps' titles, minutes and ticks, your values and its source's values. It drops its text (`detail`), its reading, its answers, its photo and its source's bulky extras (an email's text, messages and ids). On 17 Oct's numbers that was about 450 of the 585 KB; the file is 590 KB on 18 Oct.

If a source lists it again (a hand-in undone, an email starred again), the sync brings its text back and it's read again, a paid call, as for a task seen for the first time. That's the one behaviour this changes, and only for work finished a fortnight before.

### The chat's old fields (Phase 6)

`ChatMessage.remembered` (before 16 Oct) becomes `memoryEdits`, as the screen already reads it (`Memories.editOf`); `done` becomes `acts` with nothing to undo; the old fields go. `Speaker.Claude` becomes `Speaker.Felix`, with `@SerialName("Claude")` so the stored value doesn't change.

## Rolling back

- **Before an install**, as always: the APK and the data kept (`keep_before.sh`).
- **After a migration**, the files it came from are on the phone (`<name>.v<from>`) for a week.
- **If a release must be undone**: the previous APK installed over it (`adb install -r -d`, allowed for a debug build), then the data from before restored into the app's folder with `run-as`, from the backup taken before the install. The app is never uninstalled. A migration that went wrong is fixed forward where it can be, as rolling back loses what was done since.

## What stays as it is

- JSON, written whole and atomically. Once the stores are split, the largest written often is `tasks.json`'s open part, about 80 KB, which is fine.
- `secrets.bin`, encrypted by the keystore as now.
- The backup's format, but for the versions it carries.
