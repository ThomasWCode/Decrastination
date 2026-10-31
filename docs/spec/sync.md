# The phone and the server

How the phone keeps the server's copy of the state (`sync/Replica.kt`, `data/SyncState.kt`, `sync/ReplicaWorker.kt`). In 5.0.0 this is mirror mode: the phone is the only writer, and everything the website shows comes from it (D-126). The phone doesn't depend on any of it: unpaired, offline or with the server down, it does all it did before.

## Pairing

Setup → This phone → The website. The website's Phone page shows a six-digit code, which works once, for five minutes; the phone gives it back and is handed a token, kept in the encrypted secrets (`Secret.DeviceToken`) and never written anywhere else. Unpairing lets the token go and forgets what's been sent, so pairing again sends everything. A store's content stays where it is either way.

## What's sent

`Stores.MIRRORED`, each store's whole text as its file holds it: tasks, settings, session, protection, model, anki, learning, log, instructions, chat, school, memory, felix. With them:

- each month of the chat's archive, once one has changed;
- `calendar-read.json`: the calendar as last read, and your answers about its events, which the server can't read for itself (`CalendarRead`, from `Sync.calendarRead`).

What's the phone's alone is never sent: the secrets, `runtime.json`, `device.json`, `calendar.json`, `gmail-writes.json` and `sync.json`.

Only what's changed goes: `sync.json` keeps each store's SHA-256 as last accepted, and a store whose hash matches stays home. Nothing changed still sends an empty batch, so the server knows the phone is there and the website can say how fresh it is. Each batch says the phone's time zone, as a day is the owner's wherever they are, and its build.

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

## Testing

`ReplicaTest`, on temporary stores and a stand-in server. From a PC, on the phone (`adb reverse`, then these debug commands): `sync-server` with `url`, a test server's address (`http://127.0.0.1:<port>`, the one address `res/xml/network_security.xml` allows in plain HTTP), `sync-pair` with `code`, `sync-send` and `sync-state`.
