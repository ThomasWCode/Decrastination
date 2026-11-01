# The phone and the server

How the phone keeps the server's copy of the state (`sync/Replica.kt`, `data/SyncState.kt`, `sync/ReplicaWorker.kt`). In 5.0.0 this is mirror mode: the phone is the only writer, and everything the website shows comes from it (D-126). The phone doesn't depend on any of it: unpaired, offline or with the server down, it does all it did before.

## Pairing

Settings → The website, or the same row on Setup. The website's Phone page shows a six-digit code, which works once, for five minutes; the phone gives it back and is handed a token, kept in the encrypted secrets (`Secret.DeviceToken`) and never written anywhere else. Unpairing lets the token go and forgets what's been sent, so pairing again sends everything; its line stays on the Phone page, to revoke there, though nothing holds its token any more. A store's content stays where it is either way.

## What's sent

`Stores.MIRRORED`, each store's whole text as its file holds it: tasks, settings, session, protection, model, anki, learning, log, instructions, chat, school, memory, felix. With them:

- each month of the chat's archive, once one has changed, and a month that's gone sent empty, so a chat cleared here is cleared there;
- `calendar-read.json`: the calendar as last read, and your answers about its events, which the server can't read for itself (`CalendarRead`, from `Sync.calendarRead`).

What's the phone's alone is never sent: the secrets, `runtime.json`, `device.json`, `calendar.json`, `gmail-writes.json` and `sync.json`.

Only what the server doesn't hold goes. Each answer says what it holds, every store by its SHA-256, and `sync.json` keeps that: a store whose hash matches stays home, and a server restored to an older copy is sent what it's lost in the same send. Nothing changed still sends an empty batch, so the server knows the phone is there and the website can say how fresh it is. Each batch says the phone's time zone, as a day is the owner's wherever they are, and its build.

## When

- Within a minute of any change to a store the server keeps: a run of changes is one send (`ReplicaWorker.soon`).
- As the app comes to the front (`ReplicaWorker.now`).
- At the end of each 15-minute sync.

Each send is WorkManager work, with a network constraint, as Android 15 cuts an app's network seconds after it leaves the screen.

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

What isn't a command, and why: a change that's only of this phone (its pairing, the parent code's secret, its calendar's answers), one that starts a job rather than changes the state (a sync, a reading), housekeeping that runs by itself (what Felix remembers past its date let go as its page opens), and what's reached only through Claude until that moves to the server.

## The exchange, once the server applies commands

Built and tested in 5.2.0, and dormant: `SyncState.mode` stays `mirror`, and the phone behaves as it did, till the server goes over to commands (D-141). The server says so by answering a seed with a 409, and `GET /api/version` by its `mode`; the phone then keeps in step this way, from its next upload.

- **A change** is recorded in `outbox.json` before it's applied (`Commands.act`), so it's sent whatever stops the process, and the screens show it at once. It isn't applied a second time at a start: a process stopped part-way may have made some of it, or all, with nothing to say which. The server applies it once, and the pull after a start with commands waiting is of everything, so whatever was left of it goes.
- **An upload** posts what's waiting, in order, to `POST /api/commands`, and each that's answered leaves the outbox. A command the server dropped is said on Setup (`SyncState.dropped`), which it was, when and why, till it's cleared, and what it did here goes: the server changed nothing a pull of what's changed would bring, so the next pull is of everything (the revision goes back to 0).
- **Then a pull**: `GET /api/state?since=` for every store changed since the last, each taken whole (`JsonStore.adopt`). Only with nothing waiting (`Commands.replacing`): a command made since the upload began is already in the stores, and applying it again on top would make it twice, so it goes first, and the pull's asked for again after it. No command is applied while the stores are replaced. A month of the chat's archive that comes empty is a chat cleared elsewhere, and goes here with its copies (`ChatArchiveStore.adopt`).
- A pull follows only an answer. With none, what's waiting may already be on the server, and it mustn't be applied twice over.
- A store newer than this build can read stops the pull whole, and Setup says to install: the stores change together, and half of them new is worse than all of them old.
- A server behind the revision this phone last took has been restored, and what's here is all that's left of what it lost: nothing's taken from it, and Setup says so. The restore steps set it back to mirroring ([server.md](server.md)), which each pull and a turned-away command tell the phone (`PulledState.mode`): the phone goes back too, and seeds all it holds, what's waiting in the outbox with it, as that's in the stores already. A restored server that's since passed this phone's revision can't be told from one that hasn't, which is why the steps set the mode rather than leave it to be noticed.
- A command that throws part-way here is half made as a stopped process's is, and the next pull is of everything (`Replica.pullEverything`).
- The phone's time zone goes with each exchange (`Protocol.ZONE_HEADER`), a command waiting or none, as a seed says it in its body.

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
| one its owner fails on | always | It couldn't be applied |

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

`ReplicaTest`, on temporary stores and a stand-in server; `SyncHarnessTest` in `:server`, a real applier over a real database and two phones on stores of their own, a wire between them that can be cut or lose an answer; `CommandApplierTest`. From a PC, on the phone (`adb reverse`, then these debug commands): `sync-server` with `url`, a test server's address (`http://127.0.0.1:<port>`, the one address `res/xml/network_security.xml` allows in plain HTTP), `sync-pair` with `code`, `sync-send` and `sync-state`.
