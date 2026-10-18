# Emails done: unstar and archive on the last tick

When you tick off the last of a starred email's work in the app, the app unstars and archives the conversation in Gmail. Today the star is the state (DX-03, D-10): an email's task is done only when a read finds no starred message in its conversation, so an email whose work you've finished sits open in the app and starred in Gmail until you unstar it by hand; its completion (and the free time it earns, D-10) waits for that too.

This is the one change in the plan that writes outside the phone, and it amends three decisions: D-7 ("sources are only ever read"), D-10 ("unstarring finishes it") and D-54 ("a source's task can't be ticked done by hand"). Each gets its amended form in `docs/decisions.md` with the PR (Q19).

## When it happens

**The trigger**, pure (`GmailFinish.due(task, plan)`): a Gmail task, open, not hidden (`TaskItem.hidden`), whose work is all done: every step ticked, or, with no steps, its remaining minutes at zero, which is when `Planner.pieces` returns nothing for it (`Planner.kt`, "an email's blocks done, nothing is left of it"). It's checked after:

- a slot kept (`Focus.keepSlot`, from Day's tick, the widget's ✓, the block screen's Done, Felix's `tickStep` or `keepPiece`);
- a step ticked by hand on the sheet (`Focus.tickBlock`);
- Felix's `done` action on a Gmail task, which today is refused ("done when Gmail says so") and after this means "finish it: unstar and archive" (Q18: all four triggers);
- a button on an email's sheet, "Finish: unstar and archive", for an email you've dealt with without ticking anything (Q18).

Not on: unstarring in Gmail yourself (that's the source saying so, as now); a photo check (emails are Admin, none); a source's own completion.

## What happens

1. **Journaled first.** A `GmailWrite(threadId, uids, unstar = true, archive = true, notBefore = now + GRACE, tries = 0)` goes into `RuntimeState.gmailWrites` (Q12: A; the cleanup's Phase 5a gives it a file of its own when `runtime.json` splits). The task's sheet and pill say "Done: unstarring it in Gmail" meanwhile; the plan has nothing left of it anyway.
2. **A grace** (60 s, `GRACE`, Q18: A): the keep's Undo on Day, or Felix's untick, within it cancels the write (the journal entry is dropped). After it, an undo re-stars: a reverse write (`unstar = false`: `+FLAGS \Flagged` on the messages that were starred, `+X-GM-LABELS \Inbox`), journaled the same way.
3. **The write**, as a WorkManager job with the network constraint and exponential backoff (`GmailWriteWorker`), one at a time: connect as a read does (`GmailSource.connect`), `LOGIN`, `SELECT` All Mail (not `EXAMINE`: a write needs the mailbox opened for writing), then for the conversation's messages (`EXTRA_UIDS`, under the stored `UIDVALIDITY`; a changed validity lists the thread again by `X-GM-THRID` first):
   - `UID STORE <uids> -FLAGS.SILENT (\Flagged)`: every starred message of the conversation unstarred (there may be several);
   - `UID STORE <uids> -X-GM-LABELS.SILENT (\Inbox)`: archived, which in Gmail is the conversation leaving the inbox; nothing is deleted, moved or marked read;
   - `LOGOUT`.
   A `NO` (the message gone) on one UID doesn't fail the rest. A failure keeps the entry for the next try; after five tries over a day, it's dropped and said (step 5).
4. **The read that closes it.** A Gmail-only sync is asked for at once (`SyncWorker.syncNow(sources = gmail)`): it lists no starred message in the conversation, the merge marks the task done (`Merge.apply`, "not listed"), `Focus.onCompleted` records the completion and gives its free time (D-10: an unstarred email earns as any completion does). So the app's state changes only through the source, as every source's does, and nothing is marked done locally ahead of the read, which would reopen it at the next listing while the write was still pending.
5. **Said.** Only when something went wrong, after the five tries: the app's own notification, not a message of his (Q18: C): `NoticeId.GmailWrite`, tagged by thread, on a channel for the sources (`Channels.SOURCES`, "Your sources", default importance, not ceilinged; which channel is Q27), "Couldn't unstar “Trip consent form” in Gmail (no connection for a day): it's still starred", tapped, opening the task's sheet, which says the same under its status; Setup's attention mark counts it as the source failing (`TaskState.status(Gmail).error`). Success is silent: the task closes, as it does when you unstar by hand.

## The reader stays read-only

`ImapClient` is read-only "by construction" and says so; the write commands go in a separate class, `ImapWriter`, holding `select` and `uidStore` and nothing else, with a test that lists every command `ImapClient` can send and fails if one of the writer's appears there. Each write is logged at info level with the thread id and the command's name, never the subject or any address. The two acts are the only ones (`\Flagged` off, `\Inbox` off, and their reverses): no delete, no move, no mark-as-read, ever.

## The switch, and the first time

- `Settings.gmailFinishes: Boolean` (Q18: A, on by default, with the first-time sheet), under Settings → Sources → Gmail: "When you finish a starred email's work here, unstar and archive it in Gmail". Setup's Gmail section says it too. It isn't one of Felix's settings (`FelixSettings`): it writes to your mailbox.
- The first finish with the switch on shows a sheet once ("This will unstar and archive the conversation in Gmail. Carry on?"), unless you've said never to ask (`hintsSeen`), so the first write is yours to see (Q18).

## Felix and the email

- `done` on a Gmail task becomes allowed in `ChatAnswers.action` and `AppGraph.act` when the switch is on: an `Act` "Finished: “Trip consent form” (unstarred and archived)", with Undo as above within the grace (after it, the reverse write).
- The chat's prompt line for `done` changes: "…or an email they've dealt with (it's unstarred and archived in Gmail)". The context marks Gmail tasks "finish with done".
- His own messages: nothing new.

## What stays

- Teams, Power Planner and AnkiDroid: unchanged; no write API the app uses. D-54 stands for them.
- An email starred again reopens the task, as now.
- A starred email's text, attachments, invite: read as now; the sync's read-only path is untouched.
- `EmailRules`, the readings: nothing.

## Data

```kotlin
@Serializable
data class GmailWrite(val id: String, val threadId: String, val uids: List<Long>, val uidValidity: Long?, val unstar: Boolean, val archive: Boolean, val at: Long, val notBefore: Long, val tries: Int = 0, val lastError: String? = null)
```

Kept until done or dropped; in no backup (a pending write is this phone's and this hour's); cleared by nothing in Reset.

## Implementation (4.3.0-H)

One PR, the commits in this order:

1. **The writer.** `sources/gmail/ImapWriter.kt`: `select(mailbox)`, `uidStore(uids, item)`, `logout`, over the same reader and socket as `ImapClient` (the private `run` shared, or the writer wrapping a client it's given); its test over a fake stream, and `ImapClientTest` asserting the reader's command list has neither `SELECT` nor `STORE`. Nothing calls it yet.
2. **The rules.** `core/GmailFinish.kt` (pure): `due(task, plan)`; `GmailWrite` and `RuntimeState.gmailWrites`; `GmailWrites.cancel`, `reverse`, `next` (the grace and undo rules), pure and tested.
3. **The worker.** `sync/GmailWriteWorker.kt` (WorkManager, the network constraint, exponential backoff, unique work): the next entry past `notBefore`; connect as `GmailSource.connect` does (made shared), `LOGIN`, `SELECT` All Mail, the stored `uidValidity` checked against the mailbox's (changed: the thread listed again by `X-GM-THRID`), the two `UID STORE`s, `LOGOUT`; success drops the entry and asks `SyncWorker.syncNow(app, setOf(Source.Gmail))`; failure bumps `tries` and `lastError`; the fifth failure drops it, posts the notification and marks the task. Through a `Writer` interface, so the test fakes it.
4. **The triggers.** `Focus.keepSlot`, `Focus.tickBlock` and `AppGraph.act`'s `Done` call `finishGmail(task)` (in the Sync service after the cleanup's 3e, `AppGraph` before): `GmailFinish.due` → the entry with `notBefore = now + GRACE`, the first-time sheet flagged, the worker enqueued for `notBefore`. `Focus.unkeepSlot`, `untickBlock` and the `Act`'s Undo call `unfinishGmail(task)`: within the grace the entry's dropped; after it, a reverse entry.
5. **Felix.** `ChatAnswers.action`'s `Done` on a Gmail task allowed when `settings.gmailFinishes`; the prompt line and the context's "finish with done" mark; the empty chat's example ("I'm done with the trip email"); `chat.txt` regenerated.
6. **The sheet.** "Finish: unstar and archive" on an email's About (behind the switch); "Done: unstarring it in Gmail" while an entry's pending; the failure's line.
7. **The switch and the sheet.** `Settings.gmailFinishes` (default true); Settings → Sources → Gmail and Setup's Gmail text; the first-time sheet, a `Hint` put away into `hintsSeen`.
8. **The notification.** `NoticeId.GmailWrite` and `Channels.SOURCES` (Q27); the cleanup's registry test.
9. **The docs**: `sources.md` §Gmail; `felix.md` §What he can do; `decisions.md`: D-7, D-10 and D-54 amended, D-99 added (Q19: A); the phone check below with your mailbox, nothing of it in the PR (D-79).

## Tests

- `GmailFinish.due`: steps all done; boxes at zero; one step left; hidden; not Gmail; done already.
- The grace and undo rules (pure `GmailWrites`): cancelled within the grace; reversed after; a reverse of a reverse.
- `ImapWriter` against a fake server stream (as `Imap`'s tests drive `ImapClient`): `SELECT`, the two `STORE`s with the right flags and `.SILENT`, `LOGOUT`; a `NO` on one UID carries on; the reader's command list has none of them.
- The worker's outcomes through a fake writer: success asks for the Gmail sync; failure keeps the entry with its try count; five failures drop it, post the notification (a fake `Notices`) and mark the task.
- The merge after an unstar: done, completion recorded, free time given (`FocusTest` with a Gmail task).
- The switch off: nothing journaled; the `done` action refused as today.
- The sheet's button and Felix's `done` through the same function.

## Phone check

With your real mailbox, so chosen with care (D-79: no detail of it in the PR):

- A note-to-self email you star for the test, read into a task (`sync --es sources gmail`); `plan` shows it; tick its piece on Day (yours to tap); within the grace, `state` shows the write journaled; a minute on, Gmail on the phone shows it unstarred and out of the inbox; `state` shows the task done and its completion rewarded.
- Again, with Undo within the grace: nothing in Gmail changes.
- Again offline (aeroplane mode, by you): the entry waits; online, it goes.
- The switch off: a tick leaves Gmail alone.
- The IMAP log (`adb logcat -s Decrastination | grep IMAP`) shows `SELECT` and the two `UID STORE`s, nothing else new, and no subject.
