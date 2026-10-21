# Storage and backups

How the app keeps its state: JSON files in its own storage, one a concern, and the secrets in one encrypted file. What happens to a file that can't be read, the daily backup and restoring it, the resets, and the commands for testing from a PC.
The app stores nothing off the phone but the plan's slots in the calendar it writes to ([slots-and-calendar.md](slots-and-calendar.md)); copies elsewhere are yours to make.

## The stores

`data/JsonStore.kt` is one piece of the app's state kept in a JSON file and shared as a `StateFlow` by everything in the app's one process: the sync, the focus service, the widget and the screens. `data/Stores.kt` makes one of each, and the secrets, and `AppGraph` makes it first.
- `JsonStore.update` makes changes one at a time and writes only when something changed: to `<name>.tmp`, then renamed over the file, so a crash mid-write can't leave half a file.
- Each store is written whole at every change.
- Unknown keys are ignored and defaults written (`JsonStore.json`).
- **Every store has a version** (its state's `version`, D-94; a file without one is version 1), and a change to its shape is a migration (`data/Migration.kt`): run on the file's JSON as the store loads, before it's decoded, one version a step, the new shape then written. The file it came from is kept beside it as `<name>.v<from>`, let go a week later at a start that gets that far (`Stores.letGoOfOldShapes`); a chat archive's month's too. *Clear the chat* takes the copies kept of what it clears, older shapes' and unreadable ones' alike: the window's and the archive months' (`ChatArchiveStore.clear`). Each reset takes those of every store it clears whole, as `Stores.all` lists them (`Kept.clears`, `Stores.letGoOfCopies`): not `settings.json`'s, as a memory reset clears only what they say about you, and its copies hold the rest. A copy that can't be deleted is emptied (`JsonStore.letGoOfCopies`, D-94).
- A file of a newer version than the build, which it can't read, is set aside as one that can't be read (below); what reads of it is kept in this build's shape.
- Fields that move from one store's file to another's are moved by `Stores` at start, before any store is read (`Move`): the new file written first, then the old trimmed. Three have:
  - Felix's records from the log and his state from the runtime into the chat (`data/ChatMigration.kt`, 4.0.0), merged by id so nothing's doubled; a chat that can't be read is set aside first, as its store would set it aside, and they go into a fresh one, as the log and the runtime are about to lose them.
  - Your values on each task into its `mine`, each with who set it (yours, or Felix's where his card made the instruction, as the chat's cards and their archive say), and every step given an id, a step laid from an instruction the same id as the instruction's (`data/TasksMigration.kt`, 4.0.13).
  - The runtime split by owner (`data/RuntimeSplit.kt`, 4.0.13): each field that has an owner now to that owner's file, as it was, the calendar's beside its versions; a file it wrote at a start before is left as it is, so a start after a stop, or after one whose runtime's trim couldn't be written, puts nothing older back. A file there that can't be read is set aside first, as its store would set it aside, and said as a store's file is; where it can't be set aside, it isn't written over.
- `Stores.all` says, store by store, what a backup keeps of it (or why nothing) and which resets change it, so none can be added and left out of either; `Stores.replace`, called by the backup service alone, is the one way round a store's owner.
- A failed write is logged; the app carries on.

## The files

Under the app's `files/` folder:

| File | What it holds |
|---|---|
| `tasks.json` | `TaskState`: every task, open or finished (one its source stops listing, a fortnight once it's finished; yours till you delete it), with its text, steps (each with an id and where it came from) and readings, and your values over it, each with who set it (`TaskItem.mine`); one finished a fortnight ago keeps its record alone (`Merge.trimmed`, C-Q3); each source's last read; the day's Anki quota deck and the decks' topics; completions not yet rewarded; Power Planner's timetable |
| `settings.json` | `Settings`: hours and school windows, planning, blocking, Felix, Claude, calendars, notifications, protection |
| `runtime.json` | `RuntimeState`: what has no owner of its own: the day's notification counts, held notifications, when each daily job last ran and its retries, the Teams sync's state, when the last backup was made, the pairs you've said aren't the same work, and the files that couldn't be read |
| `session.json` | `SessionState`, Focus's, changing every half-minute while work is under way: free time, today's slots, the photos of work done, keeps under way, and what's been rewarded |
| `protection.json` | `ProtectionState`, Protection's: waiting changes, an override's end, the device admin's arming, the code lock, the watchdog's findings, blocking forced on, the uptime mark |
| `model.json` | `ModelState`, the gateway's: Claude's month and last 100 calls, the readings the API refused, the alerts already given, micro-plans |
| `anki.json` | `AnkiState`, the Anki watch's (`block/AnkiWatch.kt`): AnkiDroid's readings, since when it's been in front, and the day's minutes in it by deck |
| `learning.json` | `LearningState`: the calibration ([learning.md](learning.md)) |
| `log.json` | `ActivityLog`: slots kept, missed and moved; completions; blocks; protection lapses; each day's plan; check-ins; weekly reviews; each week's totals ([privacy.md](privacy.md)) |
| `instructions.json` | `InstructionState`: your changes, each with its words, what it's about, its reading and its state |
| `memory.json` | `MemoryState`: what Felix remembers ([felix.md](felix.md)); version 2 since 4.2.0, a fact's and a rule's until and a fact's task (a file from before read as it is), raised with `chat.json` (6), the archive's months (5) and `log.json` (3), whose memory edits and tidies hold them (`data/MemoryMigration.kt`); what's run out let go of where it's read and each morning ([felix.md](felix.md)) |
| `felix.json` | `FelixStore`, the Conversation's (4.1.0): Felix's notes, on a task or standing for every task a scope matches, and your reminders, each with what's become of it (sent, snoozed, done, cancelled) and his words written ahead for it; a deleted note and a cancelled reminder kept while their act's Undo can bring them back, the rest a month once done, pruned each morning ([felix.md](felix.md)) |
| `gmail-writes.json` | `GmailWriteStore`, `GmailFinishes`' (4.3.0, D-106): each write to Gmail an email finished here makes, journaled before it's made, with which of the conversation was starred and in the inbox before it (by Gmail's message ids) and each command's success; and its Undo's, after the grace. Kept as a receipt while its Undo can stand (30 days, or till a read finds its email starred again), one given up till it's sorted, taken back or finished again, and one an Undo still to make takes back till that's made. The receipts of writes made go in a backup; a write still to make doesn't (this phone's and this hour's), nor one given up (this phone's to sort); no reset clears it |
| `chat.json` | `ChatState`: the chat's live window (every message of yours and Felix's, the newest 200 or those of the last 14 days, whichever keeps more, and every one of his unread), where you'd read up to, and Felix's state (his next check, what he wrote ahead, what waits to be asked, the check-in's answers so far) |
| `chat-archive-<yyyy-MM>.json` | `ChatArchive`, a month's each (`ChatArchiveStore`, by each message's time in UTC): the chat's messages past its window, oldest first, kept for you; none is sent to him but one a reply of yours quotes (its first 300 characters). Each month's file is read only when it's wanted, never at start: the window's move into it, scrolling back (the newest months, as far as the pages go), a reply to one of its messages, a backup, a restore, a reset and the export. As a whole it's `chat-archive.json` still, one part of a backup. 4.0.0 to 4.0.3 kept it in that one file, split into its months at 4.0.4's first start, in the shape its months have then (`ChatArchiveStore.SPLIT`; one that can't be read is set aside and said as any store's file, what can be kept of it split); a move made again after a stop keeps the window's copy, the newer; a clear waits for a month being read, as a month's first read can write it again in this build's shape ([felix.md](felix.md)) |
| `school.json` | `SchoolCalendar`: days with no school, and half-days |
| `calendar.json` | `CalendarState`, CalendarWriter's: fingerprints of the event versions the app wrote to the calendar, the last 2 000, so a stray copy is told from an event of yours; each slot event's start as last written and the calendar it went to, the calendars to clear, and your answers about events and those asked ([slots-and-calendar.md](slots-and-calendar.md)) |
| `secrets.bin` | The credentials and keys, encrypted (`data/SecretStore.kt`, [privacy.md](privacy.md)) |

Beside them:
- `photos/`: a photo of the work for a task, one a task, let go once no task holds it (AI-23);
- `crashes/`: the last three crashes (`data/Crashes.kt`, AR-06), the newest in Settings → Claude;
- `backups/`: a reset's copy, where `Documents` can't be written;
- `<name>.unreadable-<when>`: copies of files that couldn't be read (below);
- `<name>.v<n>`: the file a migration came from, kept a week (above), a month of the chat's archive's among them (`chat-archive-<yyyy-MM>.json.v<n>`);
- the debug commands' files: `credentials.import` (only until it's imported), `ai-prompts.json`, `chat-context.txt` and `chat-turns.txt`.

The cache holds a photo check's pages till they're checked, and the record exported from Stats to share (PL-11).

## A file that can't be read

A store's file that can't be read whole (IMP-P1-003, `JsonStore.load`):
- keeps each top-level field that still reads on its own, the rest from its defaults, so an update that can't read one field loses only that field (`JsonStore.salvage`);
- is copied aside as `<name>.unreadable-<yyyyMMdd-HHmmss>`, the newest three of each kept, the one just made always among them (`JsonStore.setAside`);
- sets `JsonStore.problem`.

`AppGraph` puts every problem `Stores` reports on record (`RuntimeState.unreadable`) ahead of anything else, and only then has each store write what it kept over the bad file (`Stores.keepRecovered`). A file that's migrated and then doesn't read whole is recovered from its new shape. A start cut short in between finds the file again, and says so again.
Setup and Settings → Backup say what happened, and where the copy is while it's there (a clear or a reset takes the copies of what it clears, and a file set aside again keeps only its newest three: `Unreadable.says`, `Stores.holds`), until you tap *Got it* (`AppGraph.forgetUnreadable`).

Settings that can't be read at all start armed while the device admin is on, as only arming turns it on: a bad file never disarms protection ([blocking-and-protection.md](blocking-and-protection.md)).

Secrets that can't be decrypted are told apart from none saved (`SecretStore`): the keystore is tried three times, and the file is left where it is with a copy beside it, so a keystore that comes back reads it all at the next start. Meanwhile Setup asks for the credentials again.

## Backups

`data/Backup.kt` (`Backup`, format 9, `Backups.encode`) holds what the sources can't give back, with the version of each store its parts come from (`Backup.versions`):
- the settings as asked for (waiting changes applied), the activity log, the calibration, your calendar answers, and Claude's month;
- your changes in use, your own tasks, the school calendar, what Felix remembers, today's slots, the chat with Felix's state, and his notes and reminders (format 8, 4.1.0); the receipts of writes made to Gmail, merged back by id in the order they were made (format 9, 4.3.0);
- the chat's earlier messages, while the backup fits what Import reads (8 MB): past that, the oldest are left out first, as many as it takes, and Settings → Backup says how many (`Backups.fitted`).

Never the passwords or keys, nor the sources' tasks, which the next sync reads again.

**Each day** (AR-07): `data/BackupWorker.kt`, a daily job, writes all of it to `Documents/Decrastination/decrastination-<date>.json` (`data/BackupService.kt`, `BackupService.backupNow`).
- It goes through MediaStore (`data/BackupFolder.kt`, Android 10 and later), with no folder to pick and no permission, and outlasts clearing the app's data.
- It replaces that day's file, keeps the newest 14, and notes the time for Settings → Backup; a failed write is tried again up to three times.

**Export** writes the same to a file you choose, and **Import** reads one back (Settings → Backup, or Setup).
A copy off the phone is yours to make: a sync app mirroring the folder, or Export. Android's own backup is off (`allowBackup` false; the extraction rules exclude everything).

## Restoring

`BackupService.importBackup` restores nothing from a file that isn't one of this app's, is a newer format, carries a store newer than this build's (a store it doesn't carry, as `anki.json`, may be newer), has a part its store's migrations can't bring to this build's shape (a step missing, or a migration failing on it), is over 8 MB, or holds what the app never writes (`Backups.problem`: a setting out of the screen's range, negative minutes, a learned figure out of bounds, a write to Gmail by ids that aren't Gmail's, and so on). A backup's parts from a store of an older version are brought to this build's shape first, by that store's migrations (`Backups.decode`). Otherwise:
- the settings go through `changeSettings`, keeping this phone's protection, Claude's key in use and its calendars: once armed, one that loosens the blocker waits;
- your changes in use come back as applying each here would (one that would make tasks wait for each other in a circle stays unclear, saying so), and those not in use (a reset's copy holds them: one being read, or left unclear) as they were, one being read read when Claude can be asked; your own tasks not here are added, with your changes laid over them again;
- the school calendar is merged; what's remembered is merged by topic and words, this phone's winning, after the chat (below), what's run out on either side let go of first (D-103); the chat comes back whole only where this phone's is empty, its earlier messages and Felix's state with it; into a chat that isn't, his own messages it hasn't come back by id, with your replies to them, as the log's records of them were merged before 4.0.0, those read there read here as far as that goes without passing one of his unread here;
- Felix's state otherwise comes back where this phone has none, and today's slots on the same day, where this phone has laid and missed none of today's yet; his notes and reminders it hasn't come back by id, this phone's winning, within what the app keeps (50 reminders still to come, ten notes a task, twenty standing), the rest left out and said; one still to come whose time has gone by comes at once, late (`Backups.mergedFelix`); one on a source's task not read yet (a restore before the first sync) waits for that source's read rather than being let go, and a note on one stays (`TaskItem.goneSince`);
- a backup from before the chat held Felix's messages (format 6) has his records and state moved into its chat as it's read, as the phone's files were;
- the log is merged with nothing doubled, this phone's winning; the calibration is the backup's; calendar answers merge, this phone's winning; Claude's month takes the higher of each count;
- the reminders' alarms are set again, the calendar read again, and today's record made again.

It then says what came back, and what waits.

## Resets

Settings → Reset (LR-08, `BackupService.reset`) clears one part, after saving a copy of everything as `reset-<date>-<time>.json` in `Documents/Decrastination` (in the app's own `backups/` where that can't be written, which Settings → Backup restores from). Where the chat goes and that copy has no room for its earliest messages (past 8 MB), they're kept whole beside it as `chat-archive-<date>-<time>.json`, so none is lost; it isn't a backup to import. A copy over 8 MB even without them couldn't be read back, so then nothing is reset.
Nothing goes until its copy is saved. None needs the parent code, and none touches Claude's key, the sources or protection.

| Reset | What goes |
|---|---|
| Stats | The record (slots, completions, days, blocks, protection lapses, reviews, check-ins and the weeks' totals), and the whole calibration; Felix's messages stay |
| What the app has learned | The calibration, learned afresh from now; the decks' seconds a card from AnkiDroid's last readings, at its next visit |
| What Felix remembers | His facts and standing instructions, and your profile lines |
| The chat and Felix's history | The chat and its earlier messages, Felix's among them, what he's waiting to say, and his notes and reminders (Q12: A) |
| Your changes | Every change, in use or not (one still being read or left unclear among them); the copy saved first holds every one, and its restore brings back those not in use as they were |
| Everything learned | All of these |

Each asks first, naming what goes, your changes listed. A reset's copy is never pruned: import it to undo the reset.

## Commands from a PC

`debug/CommandActivity.kt` runs commands for setting up and testing over adb, each logged to `adb logcat -s Decrastination`:

```
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd <name> [--es|--ei|--ez <key> <value> …]
```

The alias `.debug.Command` needs the `DUMP` permission, which the adb shell holds and no ordinary app can; the activity itself isn't exported.
On a debug build the menu's *Debug* page (AR-03) has a button for each command that takes nothing (`CommandActivity.PLAIN`: `sync`, `enrich`, `briefing`, `review`, `calendar`, `protection`, `backup-now`).

**Setting up and reading**
- `import-credentials`: moves `files/credentials.import` (written there by `scripts/load_credentials.py`) into the encrypted store, then deletes it; values are never logged.
- `sync` [`--es sources teams,gmail`]; `state` (each source's status and every task); `anki-counts`; `plan` (the next seven days' slots); `open --es task <id>`.

**The calendar**
- `calendar` [`--ez again true`], read now; `calendar-written`; `calendar-sync`; `calendar-pick --es name <calendar>`.
- `calendar-move --es key <slot> --ei by <minutes>` and `calendar-delete --es key <slot>`, as a hand in the calendar would.

**Blocking and protection**
- `force-block --ei minutes <n>` (0 ends it; it only tightens); `protection` [`--ez repair true`]; `offer-teams-sync`; `free-time` [`--ei minutes <n>`] (free time's countdown and its heads-up as notifications, nothing spent or given).
- `test-arm` and `test-disarm` (at once); `remove-admin`; `clear-parent-code`.
- `clean-up`: this app's notifications, a delayed Teams sync, forced blocking, the watchdog's restarts, and reviews run at any time but Sunday evening.

**Claude and readings**
- `enrich`; `enrich-task --es task <id>`; `ai-prompts --ei count <n>` (writes `files/ai-prompts.json`).
- `ai-check --es base <url>` (every job's reader once, through the real client, to a stand-in server with a dummy key: `scripts/claude_stub.py` on the PC, `adb reverse tcp:8089 tcp:8089`); `ai-endpoint --es base <url>` and `ai-effort --es job <job> --es level <low|medium|high|none>`, both till the app restarts.

**The chat, Felix and instructions**
- `chat-ask --es text "<words>"` (a paid call; with `--es replyTo <message id>`, a reply to that message, routed as any reply is: a question of his answered with no call); `chat-context` (writes `files/chat-context.txt`, and the turns your newest message was sent with, a reply's quote among them, to `files/chat-turns.txt`); `chat-card --es do <apply|undo>`; `chat-clear`.
- `felix` (his state, his last messages, and his notes and reminders); `felix-check`; `felix-say --es reason <reason>` (a paid call); `memory`; `memory-tidy` (a paid call).
- `instruction --es text "<words>"` [`--es task <id>`, `--es event <key>` or `--es day <YYYY-MM-DD>`]; `instructions`; `instruction-discard --es id <id|all>`; `instruction-delete --es id <id>` (one applied taken back, as *Take back* does; one not applied discarded).

**Tasks, settings and the week**
- `own-add --es title "<name>"` [`--es due <YYYY-MM-DDTHH:MM>`, `--ei minutes <n>`, `--es kind <kind>`]; `own-done`, `own-reopen` and `own-delete --es task <id>`.
- `forget-task --es task own:<id>` (a test's task of your own, with its completions and slots); `quick-minutes --es task <id> --ei minutes <n>` (0 takes it back).
- `setting --es name <field> --es value <value>` (one Felix may change); `gmail-finishes --ez on <true|false>` (finishing emails in Gmail, as Settings → Sources → Gmail's switch); `briefing`; `check-in`; `review`; `assess --es task <id>`; `backup-now`.

Those that ask Claude are paid calls: `chat-ask`, `felix-say`, `memory-tidy` and `instruction`, and `enrich`, `enrich-task`, `felix-check`, `briefing` and `review` while Claude is on; a `sync` too, as what it finds new or changed is read after it. `ai-check` reaches only the stand-in.

The files `ai-prompts` and `chat-context` write hold your tasks' text: pull them into the git-ignored `private/`, then delete them from the phone.
