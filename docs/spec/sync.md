# The phone and the server

How the phone keeps the server's copy of the state (`sync/Replica.kt`, `data/SyncState.kt`, `sync/ReplicaWorker.kt`). In 5.0.0 this is mirror mode: the phone is the only writer, and everything the website shows comes from it (D-126). The phone doesn't depend on any of it: unpaired, offline or with the server down, it does all it did before.

## Pairing

Settings → The website, or the same row on Setup. The website's Phone page shows a six-digit code, which works once, for five minutes; the phone gives it back and is handed a token, kept in the encrypted secrets (`Secret.DeviceToken`) and never written anywhere else. Unpairing lets the token go and forgets what's been sent, so pairing again sends everything; its line stays on the Phone page, to revoke there, though nothing holds its token any more. A store's content stays where it is either way.

## What's sent

`Stores.MIRRORED`, each store's whole text as its file holds it: tasks, settings, session, protection, model, anki, learning, log, instructions, chat, school, memory, felix, and from 5.15.0 Gmail's journal (`gmail-writes.json`), its writes still to come among it, so the server takes them over at the switch ([sources.md](sources.md) §Gmail). With them:

- each month of the chat's archive, once one has changed, and a month that's gone sent empty, so a chat cleared here is cleared there;
- `calendar-read.json`: the calendar as last read, and your answers about its events, which the server can't read for itself (`CalendarRead`, from `Sync.calendarRead`).

What's the phone's alone is never sent: the secrets, `runtime.json`, `device.json`, `calendar.json`, `gmail-writes.json` and `sync.json`.

Only what the server doesn't hold goes. Each answer says what it holds, every store by its SHA-256, and `sync.json` keeps that: a store whose hash matches stays home, and a server restored to an older copy is sent what it's lost in the same send. Nothing changed still sends an empty batch, so the server knows the phone is there and the website can say how fresh it is. Each batch says the phone's time zone, as a day is the owner's wherever they are, and its build.

## When

- Within a minute of any change to a store the server keeps: a run of changes is one send (`ReplicaWorker.soon`).
- As the app comes to the front (`ReplicaWorker.now`).
- At the end of each 15-minute sync.
- When the server says it's changed, through Firebase (§Told at once): once it applies commands, or when the phone asks to be told.

Each send is WorkManager work, with a network constraint, as Android 15 cuts an app's network seconds after it leaves the screen.

## Told at once

Stage 8 (W-18; 5.11.0, the server's half in 5.10.0, [server.md](server.md) §Devices): the server tells this phone of a change it didn't make, through Firebase Cloud Messaging, and the phone pulls it in seconds rather than at its next 15-minute sync. Only in a build made with the Firebase project's file (D-145): one without it hears at its sync, as before. Dormant till the switch, as the server tells nothing while it mirrors, but a phone that asks.

- **Its id.** Firebase registers this phone by its installation id (`sync/PushService.kt`, `onRegistered`), as it does at a start now and then and whenever the id changes; the manifest asks for that (`firebase_messaging_installation_id_enabled`), as Firebase now calls the registration token deprecated. It's kept in `sync.json` (`SyncState.pushId`, version 3) and sent (`POST /api/devices/push`) with the first upload of each process that goes through, and once a pairing, and when it changes, by a send after any on its way (`ReplicaWorker.again`), as one under way may be past it, so a server restored without it hears it again at the next start. One the server doesn't take goes at the next upload, and says nothing on Setup. Unpairing keeps it, as it's this phone's, and pairing again sends it.
- **The word.** The server's message says only a revision. What's done with it is work of its own (`ReplicaWorker.told`): expedited, as a high-priority message lets a job start at once, and after any exchange on its way, which may have pulled before the change. It exchanges, unless what it said is here already with nothing waiting to go, as a phone that made the change pulled it in the same exchange (`Replica.told`).
- **From a PC.** `sync-push-test` uploads, the id with it, then asks the server to tell this phone now, and logs what came of it; the message is logged as it's heard ("The server says it's at revision …"); `sync-state` says whether an id is held.

## When it doesn't work

Nothing's lost: what wasn't accepted stays unsent, and goes next time. Setup says why, in `SyncState.lastError`:

- the server can't be reached;
- a 401: it doesn't know this phone, which should pair again;
- a 409: the server needs a newer app (D-138).

**A store the server refuses.** A store of a newer version than the server can read is left out of the batch's commit, and said in `refused`, and Setup says the server is behind this app; it goes again until it's taken. A release that raises a store's version is installed on the phone before its merge deploys the server, so this lasts only as long as the deploy (D-138).

## Commands

A screen's change is a command (D-140): an action, one owner's operation with its arguments as data (`sync/protocol/Actions.kt`), with an id and the moment it was done (`Command`). `graph.act(FinishOwnTask(id))` makes one and applies it through its owner; no screen calls an owner's change itself, which `ArchitectureTest` holds to. In 5.1.0 that's all it does: what's changed goes to the server as it did, whole, and nothing behaves differently. It's the shape the switch to commands needs (D-141).

So that the same command does the same on the phone and the server, each is applied inside a `Replay` of it (`core/Ids.kt`):

- The owners read its moment, not the time it's applied (`ReplayClock`), so one that arrives late says when it happened.
- Every id made on the way is drawn from its own (`Ids.next`, `newStepId`), so both ends make the same ones, and a later command can name them.
- What a change to the settings carries is the fields it touched (`SettingsChanges.changed`), laid over whatever's there when it lands, so a change made elsewhere to any other field stands. `Commands.changeSettings` makes one from a change as a screen writes it.
- `OwnTask` keeps two things only in memory, which of its steps each was and that it came from the form. `SaveOwnTask` carries them, so a renamed step keeps its tick across the wire.

What isn't a command, and why: a change that's only of this phone (its pairing, the parent code's secret, its calendar's answers), one that starts a job rather than changes the state (a sync, a reading), housekeeping that runs by itself (what Felix remembers past its date let go as its page opens), and what's reached only through Claude until that moves to the server. What a sync or the blocker finds is a command once the two keep in step by commands (§Observations).

## Observations

What only the phone can know (W-24; `sync/protocol/Observations.kt`, 5.13.0), sent as commands of their own kinds once the two keep in step by commands, as a pull takes the server's stores whole and would take back anything the phone wrote outside one. Each is applied on both sides through the same owner, so the phone's screens and its blocker have it at once, and the server's copy, and the website, have it too. While mirroring, none is made: the stores carry them, whole, as before.

| Command | What the phone saw | Its owner |
|---|---|---|
| `ObserveSources` | Teams and Anki as read, one command a source, Teams' applied before Anki's is read (its homework decks come from Teams' assignments); a read the same as the last sent (once its command's on record) as its status alone, so the record doesn't fill with copies; a failed read's reason. Power Planner and Gmail aren't read on the phone: the server reads them (`Source.SERVERS`) | `Syncer.observe`, then what follows a sync: on the server inside the command, its Claude work on its own time; on the phone once the sync's commands have closed, as some of it waits on what can wait for the command gate |
| `NoteSource` | the Teams widget refusing to sync, where its status shows it | `Syncer.note` |
| `ObserveCalendar` | the calendar as read, and your answers about its events, as they change: the server plans by it and can't read it, and its plan is made again with it. Only once it's been read in this process, or can't be read at all (no permission): the nothing a start has before its first read, or a first read that failed, would take the server's last from it (`Sync.calendarKnown`) | the server keeps it (`calendar-read.json`) |
| `AskAboutEvent`, `SettleEvent` | an event the rules can't judge, as this phone's calendar and what it's asked decide (`calendar.json`), on record there as asked as the command's applied (`Owners.eventAsked`, nothing on the server); his questions on one settled by an instruction, or by an answer given here (the Day's event sheet, his question's chips), so the server's copies settle too | `Conversation` |
| `AnkiCame`, `AnkiWent`, `AnkiReset` | AnkiDroid come to the front and gone, the decks as read then: the stretch's time as work, a deck done keeping its slot | `AnkiWatch.came`, `went`: the decks are read on the phone, the rest made on both sides |
| `RecordBlock`, `RecordProtection` | a block, a lapse of protection or its end, for the record | `Focus` |
| `SpendFreeTime` | free time spent, a half-minute at a time: merged into the outbox's last one while that's not been sent (`Commands.sending` marks what goes), so a pull can neither give it back nor take earned time | `Focus.spend` |
| `TimerOver` | the timer's zero, heard by its alarm: told once, not again at each pull | `Focus.timerOver` |
| `AttachPhoto` | a photo of a task's work kept with it, the file this phone's | `Photos.keep` |

A slot moved by hand in the calendar goes as `AddOwnChange`, as a screen's would. A command made while another's being applied, an event's question found by the calendar read a sync sets off, is made once that closes, as one of its own: the gate it would wait for is that one's.

## The exchange, once the server applies commands

Built and tested in 5.2.0, and dormant: `SyncState.mode` stays `mirror`, and the phone behaves as it did, till the server goes over to commands (D-141). The server says so by answering a seed with a 409, and `GET /api/version` by its `mode`; the phone then keeps in step this way, from its next upload. The server's half of going over is built (5.12.0, [server.md](server.md) §The modes): the phone's last whole seed, the mode set in its transaction and its answer kept under its id; the phone's half comes with the switch.

- **A change** is recorded in `outbox.json` before it's applied (`Commands.act`), so it's sent whatever stops the process, and the screens show it at once. It isn't applied a second time at a start: a process stopped part-way may have made some of it, or all, with nothing to say which. The server applies it once, and the pull after a start with commands waiting is of everything, so whatever was left of it goes.
- **An upload** posts what's waiting, in order, to `POST /api/commands`, and each that's answered leaves the outbox. A command the server dropped is said on Setup (`SyncState.dropped`), which it was, when and why, till it's cleared, and what it did here goes: the server changed nothing a pull of what's changed would bring, so the next pull is of everything (the revision goes back to 0). A failure on the server (a lock, its disk, a bug in a release) is no answer, and drops nothing: it answers a 500, and everything stays in the outbox to go again at the next upload, what had gone through answering as it did. Setup says the server said no till it's past, and nothing's pulled till then.
- **Then a pull**: `GET /api/state?since=` for every store changed since the last, each taken whole (`JsonStore.adopt`). Only with nothing waiting (`Commands.replacing`): a command made since the upload began is already in the stores, and applying it again on top would make it twice, so it goes first, and the pull's asked for again after it. No command is applied while the stores are replaced. A month of the chat's archive that comes empty is a chat cleared elsewhere, and goes here with its copies (`ChatArchiveStore.adopt`). The pull says too which stores a clear or a reset made elsewhere let go of the copies of (`PulledState.letGo`, 5.12.0), each by the revision that let them go: the copies kept here of those go with it (D-94), what's reset being kept in no copy. Once each: `sync.json` keeps the newest acted on (`SyncState.letGoSeen`, version 4), as a pull of everything says every one there's been, and a copy made here since is what a migration that fails is put right from. Going back to mirroring starts it again from none, as a restored server's revisions start again below it.
- A pull follows only an answer. With none, what's waiting may already be on the server, and it mustn't be applied twice over.
- A store newer than this build can read stops the pull whole, and Setup says to install: the stores change together, and half of them new is worse than all of them old.
- A server behind the revision this phone last took has been restored, and what's here is all that's left of what it lost: nothing's taken from it, and Setup says so. The restore steps set it back to mirroring ([server.md](server.md)), which each pull and a turned-away command tell the phone (`PulledState.mode`): the phone goes back too, and seeds all it holds, what's waiting in the outbox with it, as that's in the stores already. A restored server that's since passed this phone's revision can't be told from one that hasn't, which is why the steps set the mode rather than leave it to be noticed.
- A command that throws part-way here is half made as a stopped process's is, and the next pull is of everything (`Replica.pullEverything`).
- The phone's time zone goes with each exchange (`Protocol.ZONE_HEADER`), a command waiting or none, as a seed says it in its body.
- **Held up** (5.14.0): a change waiting three hours or more (`SetupPage.HELD_MS`), by the oldest in the outbox, holds up what's after it and every pull, whatever the cause (a fault, a release's bug, a command nothing can apply, the server gone). The Day says so in a line at its top, which opens Setup, and so does the bar's mark (`SetupPage.syncLine`, `attention`); a week with no exchange is said the same way, what's changed on the website not here (`ADRIFT_MS`). Setup shows the change, when it was made and the last try's error, with a way out at your word: *Let it go*, asked first, says it among those the server didn't apply ("Let go at your word") and takes it out of the outbox, and the next pull is of everything, so what it did here goes, as for one the server dropped (`Replica.letGo`).
- **Felix heard as he's pulled** (5.14.0): what he says on the server reaches this phone's chat by a pull, and his notification with it: the messages a pull brings that weren't here before are handed to the outbox (`Replica`'s `heard`, `Outbox.heard`), which sounds for the newest of his that hasn't sounded here, and makes his notification again from the chat either way, so what's been read on the website goes from it. One said here sounded as it was said, and the server's copy of it, the same id from the same command, doesn't again.
- **Asked of the server** (5.14.0): what the phone did itself while mirroring and the server does now. An Import, or a reset's copy restored, goes to `POST /api/backup/import`, after what's waiting has gone, and the pull after is of everything; the backup's calendar answers are kept here, as only the phone reads the calendar (`Replica.importOnServer`, `BackupService.answersOf`). A reset is a command (`ResetData`), made here and on the server alike. The chat's Try again is `POST /api/chat/try-again` (`Replica.askAgainOnServer`), as Claude's the server's (D-142), and a photo of homework taken in Work is read by `POST /api/photos/tasks` (`Replica.readPhotoOnServer`, D-148), what it read checked in the form as the phone's own reading is, its answer waited for as long as Claude's call there may take and a minute over (`Replica.PHOTO_WAIT_MS`, a wire of its own); a refusal says why in the server's words. Each is made off the thread that asks, a screen's being the main one. A photo check, and a task read again with its photo, aren't the server's yet: they say Claude's off. The parent code's secret goes to `POST /api/parent-code` after an exchange where it differs from what the server last took, by its hash (`SyncState.parentCodeSent`, version 5), so a new one, or none, goes, and one that didn't go is tried after the next; never while mirroring, and again after a pairing or going back to mirroring, as a restored server may not hold it.

An answer lost on the way does no harm: the retry's id is one the server's seen, and it answers as it did the first time, applying nothing, but for what only the first answer carried (`Outcome.undo`, `Outcome.said`).

## Stale commands

The server applies commands in the order they arrive, and the owners are the conflict policy, as they are when a sync and a tap race on the phone (D-140). A command that can't apply to the state as it now is, is dropped, with its reason (`Action.stale`), and nothing of it is applied.

| Command | Stale when | Said |
|---|---|---|
| `FinishOwnTask`, `ReopenOwnTask`, `DeleteOwnTask`, `TickBlock`, `StartTimer`, `AddNote` | its task has gone | That task had gone |
| `AddOwnChange` | the task it's about has gone | That task had gone |
| `SaveOwnTask` | the form wouldn't take it (`OwnTaskRules.problem`) | what's wrong with it |
| `ChangeSettings` | a value the screen wouldn't take (`SettingsLimits`), or the settings' `version` | what's wrong with it |
| `ApplyChangeNow` | the change isn't waiting now, before the code's used up | That change isn't waiting now |
| `RecordCheckIn` | a feeling off the scale, or a week that can't be read, as one in a backup would stop every backup after it | what's wrong with it |
| `ForgetCalibration` | it isn't one that can be forgotten now | That's nothing it's learned now |
| any other | never: its owner takes a stale call as it does today, doing nothing, or laying the day again | |

A client that hears only the outcome is told what takes a command back, where only its owner's answer can say (`Action.undoing`, `Outcome.undo`): Mark answered's Undo needs the moment the server marked it, which the website has no other way to know. It's told what came of one that went through in the same way (`Action.said`, `Outcome.said`): a review's Undo may apply at once or wait its hours, and a reset says where its copy is.

A command that ran and came to nothing is `refused`, by what its owner answered (`Action.refused`), not dropped: what it did stands, so a wrong parent code still counts towards the lock, and it isn't a change lost, so Setup doesn't list it, your screen having said what came of it. The pull after one is of everything all the same, as it may have gone otherwise on the phone:

| Command | Said |
|---|---|
| `UseParentCode`, `ApplyChangeNow`, `AddNote`, `UndoAct`, `UndoMemory`, `ResetData` | what its owner said |
| `KeepSlot` | That piece isn't there now |
| `UndoKeep` | That can't be taken back now |
| `TickBlock` | That step has changed |
| `LaterToday` | No time left today for it |
| `StartTimer` | That piece has changed since (done, or planned again). |
| `StopTimer` | No timer was running |
| `RecordCheckIn` | That week's check-in is done |
| `ApplyInstruction` | It couldn't be applied |

A command's moment is the server's where a client's clock would decide something (`CommandApplier.stamped`): the website's is always the moment it lands, as it has no queue to send late and a browser can say anything, and a phone's may be earlier but never later, so neither can run the clock past a code's lock or a wait.

## Testing

`ReplicaTest`, on temporary stores and a stand-in server, the id Firebase reaches the phone by among it; `SyncHarnessTest` in `:server`, a real applier over a real database and two phones on stores of their own, a wire between them that can be cut or lose an answer; `CommandApplierTest`. From a PC, on the phone (`adb reverse`, then these debug commands): `sync-server` with `url`, a test server's address (`http://127.0.0.1:<port>`, the one address `res/xml/network_security.xml` allows in plain HTTP), `sync-pair` with `code`, `sync-send`, `sync-state` and `sync-push-test`.
