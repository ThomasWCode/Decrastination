# The stores, and how their data moves

What's kept where, and how each change to stored data is made safely on a phone whose data must not be lost. The stores are JSON files, written whole and atomically by `JsonStore`; `secrets.bin` is encrypted by the keystore. What each holds in detail, and the backups, restores and resets, are in [spec/storage-and-backups.md](../spec/storage-and-backups.md).

## The stores

| File | Holds | Owner | Changes |
|---|---|---|---|
| `tasks.json` | the open tasks whole; finished ones as records, their text dropped a fortnight after they're finished | Sync (Readings, Instructions and OwnTasks write their own parts) | at each sync, tick or reading |
| `instructions.json` | your changes and Felix's, read into values laid on the tasks (`TaskItem.mine`), each with who set it | Instructions | at each change |
| `settings.json` | the settings, a change still waiting among them | several, each its own fields | on a change |
| `runtime.json` | the daily runs, the last backup, Teams' auto-sync, duplicates told apart, the files found unreadable, the notifier's counts and held list | small owners | rarely |
| `session.json` | credit, today's slots, slots being kept, photo checks under way, completions rewarded | Focus | every half minute |
| `protection.json` | changes waiting, the override, armed, the code's lock, protection's state, forced blocking, the uptime mark | Protection | on a change |
| `model.json` | the month's usage, the last calls, failed readings, dropped-plan alerts, micro-plans | ModelGateway | at each call |
| `anki.json` | Anki's readings, when AnkiDroid came to the front, the minutes worked in it | Focus (Anki's watch) | as AnkiDroid is used |
| `calendar.json` | the versions of its events the app wrote, what was written where, the calendars to clear, your answers about events | CalendarWriter | at each write |
| `chat.json` | every message of yours and his with its kind; your read position; his state; the live window only | Conversation | at each message |
| `chat-archive-<yyyy-MM>.json` | the messages past the window, a file a month, never sent to the model, shown on scrolling back | Conversation | as the window moves |
| `felix.json` | notes on tasks; reminders with their drafts | Conversation | as they're set, drafted, sent |
| `memory.json` | facts and rules, each able to carry an until, a fact a task | Conversation | at each memory edit |
| `gmail-writes.json` | the Gmail writes journaled and not yet made, and the receipts of those made, kept while their Undo stands | Sync | at a finish, a try, an undo |
| `log.json` | slots, completions, blocks, days, check-ins, reviews | Learning | at each slot and completion |
| `learning.json` | the calibration | Learning | at the review |
| `school.json` | the school calendar | Planning | rarely |

Each is made in `data/Stores.kt` with its version and migrations; `Stores.all` says what a backup keeps of each and which resets change it, so a new store can't be left out of either.

## Versions and migrations

Every store has a version, and a change to its shape is a migration (D-94); no field is kept only so old files read: it's migrated away and deleted.

1. **A version in each state class** (`val version: Int = 1`); a file without one is version 1.
2. **Migrations on the JSON, before decoding**: `Migration(from) { obj: JsonObject -> JsonObject }` (to `from + 1`), run in order by `JsonStore` as it loads (`data/Migration.kt`). Working on the JSON lets a migration rename, move or drop fields the new class no longer has.
3. **The old file kept**: once migrated, the file it came from is kept as `<name>.v<from>` until a week after the next successful start, and the new one written atomically. A clear or a reset takes the copies of what it clears with it (D-94).
4. **One release, many stores.** A migration that moves fields between stores (`Move`) is made by `Stores` at start, before any service reads: it reads the old file, writes the new ones, and only then trims the old. A move guards on the files it writes, not the one it reads, so a failed first run doesn't run again over newer values; a move that decodes old files upgrades them through the store's migrations first.
5. **Backups go through the same migrations.** A backup carries its parts' versions; restoring an older one migrates them first (`Backups.upgraded`), so a backup made before a change restores after it. A part a backup keeps of a store (your own tasks of `tasks.json`, the slots of `session.json`) is migrated as an object of only those fields, which its migrations must allow for; a field moved between stores is moved in a backup's parts too, which a test holds each new move to.
6. **A raise with nothing to change** still happens where a type a store holds gains a field with a default (a step's `anyOrder`, a fact's `until`), so an older build sets the newer file aside whole rather than read it without the field. A type more than one store holds raises each store's version with it.
7. **Unreadable stays recoverable.** `JsonStore`'s recovery (each field that reads on its own kept, the file set aside) runs after migration.

## Testing a migration

- **From every shape it may meet.** `fixtures/stores/<date>/` is every store as it was on that date, made up, read by `StoresDecodeTest`; each migration adds its output's folder, and its test runs every older fixture through every migration since (`MigrationTest`, `TasksMigrationTest`, `ChatMigrationTest`, `RuntimeSplitTest`).
- **Idempotent.** Each migration run twice gives what it gives once.
- **Each new test fails without its fix**: stash the fix, run, pop.

### On the real data

Before a release that migrates data is installed, `scripts/rehearse.sh <a folder of private/backup/>` unpacks a phone backup into the git-ignored `private/rehearsal/<name>/` and runs `RehearsalTest` (`-PrehearseFrom=<dir>`, skipped otherwise), which reads every store with the code as it is, runs the migrations, and prints only counts and invariants: tasks, open ones, steps ticked, your values, instructions in use, chat messages, pins, the month's spend. Nothing it reads is printed or committed. It fails when a store every phone has isn't in the backup, a store doesn't read whole or changes read and written again, a file holds a key or a list's items the app wouldn't keep (said by its path, never its value), the nightly backup beside the stores wouldn't be restored, or a count differs before and after. Its "before" is each file read with the new build's types, so a migration that moves, renames or merges data shows as a difference even when nothing is lost: that migration's PR teaches the rehearsal where its data goes (`RehearsalTest`'s moves), so the counts are compared through the move, and never loosens the check to let a migration through.

## The shapes, release by release

Each store's migrations are listed in `data/Stores.kt`; the shapes they came from are the fixture folders.

- **3.3.2**: a version on every store; the mechanism (`Migration`, `Stores`, `BackupService`).
- **4.0.0, the chat v2** (`ChatMigration`): on each message a kind, `byApp`, `replyTo`, `pieceKey`, `nextCheckAt`, `asks` and `opens`, the old `reason`, `remembered` and `done` folded in; on the state `readAt` and his state (moved from `runtime.json`); the log's Felix records of the last 30 days moved into the chat as his messages and your replies, merged by id so nothing is doubled; the window and the archive (the newest 200 messages or 14 days, whichever keeps more, and every unread one; the rest moved by the outbox, the archive's write first, then the trim, so a crash between leaves a duplicate dropped at the next move, never a hole). `Speaker.Felix` is stored as `"Claude"`, so the value didn't change.
- **4.0.4**: the archive a file a month (`ChatArchiveStore.SPLIT`: each month written, then the one file let go; a stop between splits it again, each message once).
- **4.0.13, tasks v2 and the split** (`TasksMigration`, `RuntimeSplit`): the twelve `user…` fields dropped and `TaskItem.mine` laid from the instructions in use before anything reads the tasks (an instruction from before gets `by = You`, which changes nothing, D-4; `Felix` only where the chat still held the card that made it); every step an id (eight hex characters, kept from then on) and an origin inferred this once from the title rule, an instruction's step given the id of the task step it laid; `runtime.json`'s fields to `session.json`, `protection.json`, `model.json`, `anki.json`, `calendar.json` and `learning.json` in one move at start; a task finished more than 14 days ago trimmed to its record (its text, reading, answers, photo and bulky extras dropped; a source listing it again brings its text back and it's read again, a paid call; `pruneOwn` trims a finished own task the same way and no longer deletes it).
- **4.1.0** (`FelixStore`): `felix.json`; the chat and the archive raised for the acts' new types and a message's reminder; **4.1.1** raised again for a piece kept or taken back.
- **4.2.0** (`MemoryMigration`): a fact's `until` and `taskId`, a rule's `until`; `memory.json`, the chat, the archive and the log raised.
- **4.3.0**: `gmail-writes.json` (pending writes in no backup, receipts in the backup, format 9; cleared by no reset).
- **4.3.5** (`StepOrderMigration`): `anyOrder` on `SubStep` and `OwnStep`; `tasks.json`, `instructions.json`, the chat and the archive raised; the fixture `fixtures/stores/2026-10-22-4.3.5`.
- **4.3.18** (`CreditMigration`): a kept slot's record carries the credit it earned, so an Undo gives back what was earned, not the rate's value now; `log.json` and `session.json` raised.
- **4.4.0** (`TimerMigration`): `session.json` raised for the timer on a piece (`SessionState.timer`, D-112), none in a file from before; the fixture `fixtures/stores/2026-10-26-4.4.0`.
- **4.5.1** (`OnlyMigration`): a change of a new type, `DayOnly`, with what else its day lets in (`Change.alsoAllow`, D-115): `instructions.json` at version 4, `chat.json` and the archive's months raised (8 and 7), nothing migrated; the fixture `fixtures/stores/2026-10-27-4.5.1`.
- **4.5.2** (`OutsideMigration`): three changes of new types for a day's breaks outside, `OutsideAt`, `NoOutside` and `OutsideAdded`, with the break's own time (`Change.outsideMin`, -1 for none, D-116): `instructions.json` at version 5, `chat.json` and the archive's months raised (9 and 8), nothing migrated; the fixture `fixtures/stores/2026-10-27-4.5.2`.
- **4.6.0** (`ZoomMigration`): `settings.json` at version 3, the Day grid's zoom (`Settings.dayHourDp`, the height of an hour as last pinched, 40 to 120 dp), none in a file from before, nothing migrated; the fixture `fixtures/stores/2026-10-27-4.6.0`.

## Rolling back

- **Before an install**, as always: the APK and the data kept (`private/tools/keep_before.sh`).
- **After a migration**, the files it came from are on the phone (`<name>.v<from>`) for a week.
- **If a release must be undone**: the previous APK installed over it (`adb install -r -d`, allowed for a debug build), then the data from before restored into the app's folder with `run-as`, from the backup taken before the install. The app is never uninstalled. A migration that went wrong is fixed forward where it can be, as rolling back loses what was done since.
- **A newer store under an older build** is set aside by `JsonStore` as unreadable, nothing lost; a store a build doesn't know (the archive's months, `felix.json`, `gmail-writes.json` under an older build) is ignored, and shows again once the newer build is back.
- **A Gmail write already made** isn't undone by a rollback: starring the email again is yours to do in Gmail, which is why the grace and the first-time sheet are there (D-106).
