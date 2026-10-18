# The stores, and how their data moves

What's kept where after the plan, the types the features add, and how each change to stored data is made safely on a phone whose data must not be lost. The mechanism comes first (Stage 3); every change after it uses it.

## The stores, now and after

| File | Now | After | Owner | Changes | Stage |
|---|---|---|---|---|---|
| `tasks.json` | every task, finished ones with their whole text (590 KB) | open tasks whole; finished ones as records, their text dropped a fortnight after they're finished | Sync (Readings, Instructions and OwnTasks through it) | at each sync, tick or reading | 7 |
| `runtime.json` | 38 fields, from the half-minute state to logs (98 KB) | what's left: the daily runs, the last backup, Teams' auto-sync, duplicates told apart, the stores' problems, the notifier's counts and held list | small owners | rarely | 5a, 7 |
| `session.json` | | credit, today's slots, slots being kept, photo checks under way, completions rewarded | Focus | every half minute | 7 |
| `protection.json` | | changes waiting, the override, armed, the code's lock, protection's state, forced blocking, the uptime mark | Protection | on a change | 7 |
| `model.json` | | the month's usage, the last calls, failed readings, dropped-plan alerts, micro-plans | ModelGateway | at each call | 7 |
| `anki.json` | | Anki's readings, when AnkiDroid came to the front, the minutes worked in it | Focus (Anki's watch) | as AnkiDroid is used | 7 |
| `calendar.json` | the versions written | also what was written where, the calendars to clear, your answers about events | CalendarWriter | at each write | 7 |
| `chat.json` | your messages and Felix's answers (30 KB, 25 messages) | every message of Felix's too, with its kind; your read position; his state (next check, drafts, retry, questions, completions to ask about, the check-in's draft); the live window only | Conversation | at each message | 5a |
| `chat-archive.json` | | the messages past the window, oldest first, never sent to the model, read on scrolling back | Conversation | as the window moves | 5a |
| `felix.json` | | notes on tasks; reminders with their drafts | Conversation | as they're set, drafted, sent | 8a |
| `memory.json` | facts and rules (1 KB) | the same, each able to carry an until, a fact a task | Conversation | at each memory edit | 10a |
| `gmail-writes.json` | | the Gmail writes journaled and not yet made | Sync | at a finish, a try, an undo | 11 |
| `log.json` | slots, completions, blocks, days, check-ins, reviews, Felix's records (23 KB) | the same, without Felix's records | Learning | at each slot and completion | 5a |
| `learning.json` | | the calibration (from `runtime.json`) | Learning | at the review | 7 |
| `instructions.json`, `settings.json`, `school.json`, `secrets.bin` | | as now (`Instruction.by` added in 7; `Settings.gmailFinishes` in 11) | Instructions, (all), Planning, Stores | | |

`RuntimeState`'s 38 fields, by where they go:

| Goes to | Fields | Stage |
|---|---|---|
| `chat.json` (his state) | `felix`, `assessLater`, `checkInDraft` | 5a |
| `session.json` | `credit`, `slots`, `keeping`, `photosDone`, `rewarded` | 7 |
| `protection.json` | `pending`, `overrideUntil`, `adminArmed`, `codeLock`, `protection`, `forceActiveUntil`, `uptimeMark` | 7 |
| `model.json` | `aiUsage`, `calls`, `enrichFailed`, `droppedAlerted`, `microPlans` | 7 |
| `anki.json` | `ankiReadings`, `ankiFrontSince`, `ankiWorked` | 7 |
| `calendar.json` | `calendarWritten`, `calendarWrittenTo`, `calendarsToClear`, `eventAnswers`, `eventsAsked` | 7 |
| `learning.json` | `calibration` | 7 |
| stays in `runtime.json` | `notifiedToday`, `heldNotifications` (the notifier's), `dailyRanAt`, `dailyRetries`, `lastBackupAt`, `teamsAuto`, `notSame`, `unreadable`, `unreadableSeen` | |

On today's numbers, the half-minute state's file would be a few kilobytes instead of 98.

## Versions and migrations (Stage 3)

Today a store is read by its serializer, with defaults for what's missing and fields kept from older shapes (28 notes in the code say "from before"). From Stage 3 each store has a version and a list of migrations, and no field is kept only so old files read: it's migrated away and deleted.

1. **A version in each state class** (`val version: Int = 1`); a file without one is version 1.
2. **Migrations on the JSON, before decoding**: `Migration(from, to) { obj: JsonObject -> JsonObject }`, run in order by `JsonStore` as it loads. Working on the JSON lets a migration rename, move or drop fields that the new class no longer has.
3. **The old file kept**: once migrated, the file it came from is kept as `<name>.v<from>` until a week after the next successful start, and the new one written atomically as now.
4. **One release, many stores.** A migration that moves fields between stores (Felix's state into the chat in 5a, the `runtime.json` split in 7) is made by `Stores` at start, before any service reads: it reads the old file, writes the new ones, and only then trims the old.
5. **Backups go through the same migrations.** A backup carries its parts' versions; restoring an older one migrates them first, so a backup made before a change restores after it.
6. **Unreadable stays recoverable.** `JsonStore`'s recovery (each field that reads on its own kept, the file set aside) runs after migration, as it does now.

### Testing a migration

- **From every shape it may meet.** Stage 0's fixtures are the first shape; each migration adds its output's, and its test runs every older fixture through every migration since.
- **On the real data, never committed.** The rehearsal ([03](03-stages.md) §Stage 0) migrates the latest phone backup in the scratch folder and prints counts before and after: tasks, open ones, steps ticked, your values, instructions in use, chat messages, pins, the month's spend. Any difference stops the release.
- **Idempotent.** Each migration run twice gives what it gives once.

## The data changes, one by one, in order

### The chat (v2), Stage 5a

- **On each message**: `kind` (from the stored `reason`, else `Chat`), `byApp`, `replyTo`, `pieceKey`, `nextCheckAt`, `asks`, `opens`; `remembered` becomes `memoryEdits` (as the screen already reads it, `Memories.editOf`), `done` becomes `acts` with nothing to undo; the three old fields go.
- **On the state**: `readAt` (set to the newest message's time, so nothing's unread on the day of the update) and `felix` (from `RuntimeState.felix`, `assessLater` and `checkInDraft`, which go).
- **From the log**: each `FelixRecord` of the last 30 days becomes a message of his: `id` and `at` as they were, `text`, `replies` as its suggested replies, `reason` as its kind, `key` as `pieceKey`, `taskId` as what it's about, `nextCheckAt` and `asks` as themselves; a record's `replyText` becomes a message of yours at `repliedAt` with `replyTo` the record's id. `ActivityLog.felix` and `FELIX_KEEP_DAYS` go.
- **The window and the archive**: `chat.json` keeps the last 200 messages or 60 days, whichever keeps more; what's past it moves to `chat-archive.json` (`ChatArchive`, oldest first, append-only) by the outbox after each message: the archive's `update` first, then the live store's trim, so a crash between leaves a duplicate (dropped by id at the next move), never a hole. The first move after the update makes them; no migration of their own.
- **`Speaker.Felix`** (5b) with `@SerialName("Claude")`, so the stored value doesn't change.

### Tasks v2, Stage 7

- **Your values.** The twelve `user…` fields go, and `TaskItem.mine` takes their place. No values need converting: they're laid from `instructions.json` (the plan does so at every run already), so the migration drops the old fields and `Instructions` lays `mine` from the instructions in use before anything reads the tasks. A task whose instructions are gone has nothing of yours, as now.
- **Steps.** Each step gets an id (eight random hex characters, kept from then on) and an origin, inferred this once as the app tells them apart today: the reading's steps (`stepsFromEnrichment`) are `Reading`, or `Rules` where the rules made the reading; steps laid from your instruction are `Yours` (or `Felix`, where the instruction was the chat's); a task of your own's are `OwnTask`; the rest (Anki's) `Source`.

### `runtime.json` split, Stage 7

The fields in the table above, each to its file, in one migration at start; `runtime.json` keeps the rest.

### Finished tasks trimmed, Stage 7

A task finished more than 14 days ago (C-Q3; `Merge.KEEP_FINISHED_MS` is the same fortnight) keeps what the rest of the app reads of it: its identity, title, class, kind, dates and status, its estimates and minutes worked, its steps' titles, minutes and ticks, your values and its source's values. It drops its text (`detail`), its reading, its answers, its photo and its source's bulky extras (an email's text, messages and ids). On 17 Oct's numbers that was about 450 of the 585 KB. If a source lists it again (a hand-in undone, an email starred again), the sync brings its text back and it's read again, a paid call, as for a task seen for the first time. That's the one behaviour this changes, and only for work finished a fortnight before. A note (8a) or a task-bound fact (10a) on a trimmed task still shows on its record.

### `felix.json` (v1), Stage 8a

Notes (on a task, or standing, for every task a scope matches) and reminders, versioned from the start; in the backup (format 7) and in Reset → "The chat and Felix's history". Not on `TaskItem`: a merge rewrites tasks and must not be able to drop them; keyed by task id, which is stable across syncs and reopenings, or by a scope the matcher reads at each use.

### Memory (v2), Stage 10a

`Fact.until`, `Fact.taskId`, `Rule.until`, each with a default: a file from before reads as it is, its version raised on the next write.

### `gmail-writes.json` (v1), Stage 11

The journal of writes to Gmail not yet made; in no backup (a pending write is this phone's and this hour's); cleared by nothing in Reset.

## The types, after

```kotlin
// core/Chat.kt (5a, 5b)
@Serializable
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    /** When you last left the chat, or opened it from a notification: his messages after it are unread. */
    val readAt: Long = 0,
    /** His state between looks (from RuntimeState.felix, assessLater and checkInDraft). */
    val felix: FelixState = FelixState(),
    val version: Int = 2,
)

@Serializable
enum class MessageKind { Chat, Morning, SlotStart, SlotEnd, SlotKept, DayDone, Question, Completions, CheckIn, Review, Summary, EventQuestion, Reminder, Note }

@Serializable
enum class Speaker { You, @SerialName("Claude") Felix }

@Serializable
data class ChatMessage(
    val id: String, val from: Speaker, val text: String, val at: Long,
    val kind: MessageKind = MessageKind.Chat,
    /** The words are the app's, in his name: the check-in's questions, a reminder as you set it, a line about something done. */
    val byApp: Boolean = false,
    /** The message this one answers: a swipe, a chip, or a notification's reply box. */
    val replyTo: String? = null,
    val about: About = About(),
    /** His own message's piece (a slot's key), and when he said he'd look again. */
    val pieceKey: String? = null, val nextCheckAt: Long? = null,
    /** A Completions message's completions. */
    val asks: List<AssessLater> = emptyList(),
    /** A byApp message's own button, kept visible under the bubble: "review", "summary:<week>". */
    val opens: String? = null,
    /** As now. */
    val proposals: List<Proposal> = emptyList(), val answeredAt: Long? = null, val note: String? = null, val stuck: Boolean = false,
    val memoryEdits: List<MemoryEdit> = emptyList(), val acts: List<Act> = emptyList(), val suggestedReplies: List<String> = emptyList(),
    val searched: List<String> = emptyList(), val sources: List<WebSource> = emptyList(),
)

/** The messages before the live window: oldest first, never sent to the model, read on scrolling back. */
@Serializable
data class ChatArchive(val messages: List<ChatMessage> = emptyList(), val version: Int = 1)

// core/Mine.kt (7)
@Serializable
data class Mine(
    val notATask: Boolean = false, val from: Long? = null, val after: String? = null, val dueAt: Long? = null,
    val minutes: SetMinutes? = null, val onDay: String? = null, val atTime: Long? = null, val sameAs: String? = null,
    val steps: SetSteps? = null, val title: String? = null,
)
@Serializable data class SetMinutes(val minutes: Int, val by: By, val paced: Paced? = null)
@Serializable data class Paced(val from: Int, val contentHash: String)
@Serializable data class SetSteps(val steps: List<OwnStep>, val by: By)
@Serializable enum class By { You, Felix }

// core/Task.kt (7)
@Serializable data class SubStep(val id: String, val origin: StepOrigin, /* as now */ …)
@Serializable enum class StepOrigin { Source, Reading, Rules, Yours, Felix, OwnTask }

// core/Notes.kt, core/Reminders.kt (8a)
/** A note on one task (taskId), or a standing one for every task its scope matches (Q31). */
@Serializable
data class Note(val id: String, val text: String, val at: Long, val by: By, val taskId: String? = null, val scope: NoteScope? = null, val deletedAt: Long? = null)
@Serializable data class NoteScope(val subject: String? = null, val className: String? = null, val source: Source? = null, val titleContains: String? = null)

@Serializable
data class Reminder(
    val id: String, val text: String, val at: Long, val by: By, val taskId: String? = null, val due: Due,
    val sentAt: Long? = null, val snoozedTo: Long? = null, val doneAt: Long? = null,
    /** His words for it, written ahead. */
    val draft: FelixAnswer? = null, val draftedAt: Long? = null,
)
@Serializable data class Due(val kind: DueKind, val at: Long? = null, val minutesBefore: Int? = null)
@Serializable enum class DueKind { At, SlotStart, BeforeDue }

// data/FelixStore.kt (8a)
@Serializable data class FelixStore(val notes: List<Note> = emptyList(), val reminders: List<Reminder> = emptyList(), val version: Int = 1)

// core/Memory.kt (10a)
@Serializable data class Fact(val topic: String, val text: String, val at: Long, val until: Long? = null, val taskId: String? = null)
@Serializable data class Rule(val text: String, val at: Long, val until: Long? = null)

// data/GmailWriteStore.kt (11)
@Serializable
data class GmailWrite(val id: String, val threadId: String, val uids: List<Long>, val uidValidity: Long?, val unstar: Boolean, val archive: Boolean, val at: Long, val notBefore: Long, val tries: Int = 0, val lastError: String? = null)
@Serializable data class GmailWriteStore(val writes: List<GmailWrite> = emptyList(), val version: Int = 1)
```

## Rolling back

- **Before an install**, as always: the APK and the data kept (`keep_before.sh`).
- **After a migration**, the files it came from are on the phone (`<name>.v<from>`) for a week.
- **If a release must be undone**: the previous APK installed over it (`adb install -r -d`, allowed for a debug build), then the data from before restored into the app's folder with `run-as`, from the backup taken before the install. The app is never uninstalled. A migration that went wrong is fixed forward where it can be, as rolling back loses what was done since.
- **A newer store under an older build**: `chat-archive.json`, `felix.json` and `gmail-writes.json` are ignored by a build that doesn't know them (what's in them isn't shown, not lost); a store whose version is newer than the build knows is set aside by `JsonStore` as unreadable, and the data from before restored as above.
- **A Gmail write already made** isn't undone by a rollback: starring the email again is yours to do in Gmail, which is why the grace and the first-time sheet are there (Stage 11).

## What stays as it is

- JSON, written whole and atomically. Once the stores are split, the largest written often is `tasks.json`'s open part, about 80 KB, which is fine; the archive is written only when the window moves.
- `secrets.bin`, encrypted by the keystore as now.
- The backup's format, but for the versions it carries, the archive (while it fits the backup's cap, the oldest left out first) and `felix.json` (format 7).
