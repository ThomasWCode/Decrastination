# Emails done: unstar and archive on the last tick

When you tick off the last of a starred email's work in the app, the app unstars and archives the conversation in Gmail. Today the star is the state (DX-03, D-10): an email's task is done only when a read finds no starred message in its conversation, so an email whose work you've finished sits open in the app and starred in Gmail until you unstar it by hand; its completion (and the free time it earns, D-10) waits for that too.

This is the one change in the plan that writes outside the phone, and it amends three decisions: D-7 ("sources are only ever read"), D-10 ("unstarring finishes it") and D-54 ("a source's task can't be ticked done by hand"). Each gets its amended form in `docs/decisions.md` with the PR (Q19: A). Built last, in Stage 11 ([03](03-stages.md)), inside the Sync service (Stage 6d), with its journal in a store of its own from the start (Stage 7's split precedes it).

## When it happens

**The trigger**, pure (`GmailFinish.due(task, plan)`): a Gmail task, open, not hidden (`TaskItem.hidden`), whose work is all done: every step ticked, or, with no steps, its remaining minutes at zero, which is when `Planner.pieces` returns nothing for it (`Planner.kt`, "an email's blocks done, nothing is left of it"). It's checked after:

- a slot kept (`Focus.keepSlot`, from Day's tick, the widget's ✓, the block screen's Done, Felix's `tickStep` or `keepPiece`);
- a step ticked by hand on the sheet (`Focus.tickBlock`);
- Felix's `done` action on a Gmail task, which today is refused ("done when Gmail says so") and after this means "finish it: unstar and archive" (Q18: all four triggers);
- a button on an email's sheet, "Finish: unstar and archive", for an email you've dealt with without ticking anything (Q18).

Not on: unstarring in Gmail yourself (that's the source saying so, as now); a photo check (emails are Admin, none); a source's own completion.

## What happens

1. **Journaled first.** A `GmailWrite(threadId, uids, unstar = true, archive = true, notBefore = now + GRACE, tries = 0)` goes into `gmail-writes.json` (`GmailWriteStore`, [04](04-data-and-migrations.md) §The types), a store of its own made by `Stores` and owned by the Sync service (Q12 had it in `RuntimeState` until the split; the split now comes first). The task's sheet and pill say "Done: unstarring it in Gmail" meanwhile; the plan has nothing left of it anyway.
2. **A grace** (60 s, `GRACE`, Q18: A): the keep's Undo on Day, or Felix's untick, within it cancels the write (the journal entry is dropped). After it, an undo re-stars: a reverse write (`unstar = false`: `+FLAGS \Flagged` on the messages that were starred, `+X-GM-LABELS \Inbox`), journaled the same way.
3. **The write**, as a WorkManager job with the network constraint and exponential backoff (`GmailWriteWorker`), one at a time: connect as a read does (`GmailSource.connect`, made shared), `LOGIN`, `SELECT` All Mail (not `EXAMINE`: a write needs the mailbox opened for writing), then for the conversation's messages (`EXTRA_UIDS`, under the stored `UIDVALIDITY`; a changed validity lists the thread again by `X-GM-THRID` first):
   - `UID STORE <uids> -FLAGS.SILENT (\Flagged)`: every starred message of the conversation unstarred (there may be several);
   - `UID STORE <uids> -X-GM-LABELS.SILENT (\Inbox)`: archived, which in Gmail is the conversation leaving the inbox; nothing is deleted, moved or marked read;
   - `LOGOUT`.
   A `NO` (the message gone) on one UID doesn't fail the rest. A failure keeps the entry for the next try; after five tries over a day, it's dropped and said (step 5).
4. **The read that closes it.** A Gmail-only sync is asked for at once (the Sync service's `syncNow(setOf(Gmail))`): it lists no starred message in the conversation, the merge marks the task done (`Merge.apply`, "not listed"), `Focus.onCompleted` records the completion and gives its free time (D-10: an unstarred email earns as any completion does). So the app's state changes only through the source, as every source's does, and nothing is marked done locally ahead of the read, which would reopen it at the next listing while the write was still pending.
5. **Said.** Only when something went wrong, after the five tries: the app's own notification, not a message of his (Q18: C): `NoticeId.GmailWrite` (reserved in Stage 1), tagged by thread, on a new channel for the sources (`Channels.SOURCES`, "Your sources", default importance, not ceilinged; Q27: A), "Couldn't unstar “Trip consent form” in Gmail (no connection for a day): it's still starred", tapped, opening the task's sheet, which says the same under its status; Setup's attention mark counts it as the source failing (`TaskState.status(Gmail).error`). Success is silent: the task closes, as it does when you unstar by hand.

## The reader stays read-only

`ImapClient` is read-only "by construction" and says so; the write commands go in a separate class, `ImapWriter`, holding `select` and `uidStore` and nothing else, with a test that lists every command `ImapClient` can send and fails if one of the writer's appears there. Each write is logged at info level with the thread id and the command's name, never the subject or any address. The two acts are the only ones (`\Flagged` off, `\Inbox` off, and their reverses): no delete, no move, no mark-as-read, ever.

## The switch, and the first time

- `Settings.gmailFinishes: Boolean` (Q18: A, on by default, with the first-time sheet), under Settings → Sources → Gmail: "When you finish a starred email's work here, unstar and archive it in Gmail". Setup's Gmail section says it too. It isn't one of Felix's settings (`FelixSettings`): it writes to your mailbox.
- The first finish with the switch on shows a sheet once ("This will unstar and archive the conversation in Gmail. Carry on?"), unless you've said never to ask (`hintsSeen`), so the first write is yours to see (Q18).

## Felix and the email

- `done` on a Gmail task becomes allowed in `ChatAnswers.action` and `Conversation.act` when the switch is on: an `Act` "Finished: “Trip consent form” (unstarred and archived)", with Undo as above within the grace (after it, the reverse write).
- The chat's prompt line for `done` changes: "…or an email they've dealt with (it's unstarred and archived in Gmail)". The context marks Gmail tasks "finish with done". The empty chat's examples gain "I'm done with the trip email".
- His own messages: nothing new.

## What stays

- Teams, Power Planner and AnkiDroid: unchanged; no write API the app uses. D-54 stands for them.
- An email starred again reopens the task, as now.
- A starred email's text, attachments, invite: read as now; the sync's read-only path is untouched.
- `EmailRules`, the readings: nothing.

## Data

`GmailWrite` and `GmailWriteStore` are in [04](04-data-and-migrations.md) §The types: kept until done or dropped; in no backup (a pending write is this phone's and this hour's); cleared by nothing in Reset.

## Built in

[03](03-stages.md) §Stage 11: the writer first, calling nothing; the rules; the worker; the triggers; Felix; the sheet; the switch; the notification; the decisions.

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
