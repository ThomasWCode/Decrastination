# Emails done: unstar and archive on the last tick

When you tick off the last of a starred email's work in the app, the app unstars and archives the conversation in Gmail. Today the star is the state (DX-03, D-10): an email's task is done only when a read finds no starred message in its conversation, so an email whose work you've finished sits open in the app and starred in Gmail until you unstar it by hand; its completion (and the free time it earns, D-10) waits for that too.

This is the one change in the plan that writes outside the phone, and it amends three decisions: D-7 ("sources are only ever read"), D-10 ("unstarring finishes it") and D-54 ("a source's task can't be ticked done by hand"). Each gets its amended form in `docs/decisions.md` with the PR (Q19: A). Built last, in Stage 11 ([03](03-stages.md)), inside the Sync service (Stage 6d), with its journal in a store of its own from the start (Stage 7's split precedes it).

## When it happens

**Two kinds of trigger** (Q18: all four). The automatic ones go through a pure predicate, `GmailFinish.due(task, plan)`: a Gmail task, open, not hidden (`TaskItem.hidden`), whose work is all done: every step ticked, or, with no steps, its remaining minutes at zero, which is when `Planner.pieces` returns nothing for it (`Planner.kt`, "an email's blocks done, nothing is left of it"). It's checked after:

- a slot kept (`Focus.keepSlot`, from Day's tick, the widget's ✓, the block screen's Done, Felix's `tickStep` or `keepPiece`);
- a step ticked by hand on the sheet (`Focus.tickBlock`).

The explicit ones don't ask the predicate, only that the task is an open Gmail task: you've said it's finished, ticked or not:

- Felix's `done` action on a Gmail task, which today is refused ("done when Gmail says so") and after this means "finish it: unstar and archive";
- a button on an email's sheet, "Finish: unstar and archive", for an email you've dealt with without ticking anything.

Both reach the same `Sync.finishGmail(task, explicit)`: the automatic path refuses a task with work left, the explicit path doesn't.

Not on: unstarring in Gmail yourself (that's the source saying so, as now); a photo check (emails are Admin, none); a source's own completion.

## What happens

1. **Journaled first.** A `GmailWrite(threadId, uids, unstar = true, archive = true, notBefore = now + GRACE, tries = 0)` goes into `gmail-writes.json` (`GmailWriteStore`, [04](04-data-and-migrations.md) §The types), a store of its own made by `Stores` and owned by the Sync service (Q12 had it in `RuntimeState` until the split; the split now comes first). The task's sheet and pill say "Done: unstarring it in Gmail" meanwhile; the plan has nothing left of it anyway. The very first finish is different: it waits for your say-so (§The switch, and the first time).
2. **A grace** (60 s, `GRACE`, Q18: A): the keep's Undo on Day, or Felix's untick, within it cancels the write (the journal entry is dropped). After it, an undo puts back exactly what the write took: a reverse write (`unstar = false`) with `+FLAGS.SILENT (\Flagged)` on the UIDs that were starred and `+X-GM-LABELS.SILENT (\Inbox)` on the UIDs that were in the inbox, read from the entry's `flagged` and `inbox` lists, which the forward write records before it changes anything (step 3). A conversation's other messages, never starred or long out of the inbox, aren't touched either way.
3. **The write**, as a WorkManager job with the network constraint and exponential backoff (`GmailWriteWorker`), one at a time: connect as a read does (`GmailSource.connect`, made shared), `LOGIN`, `SELECT` All Mail (not `EXAMINE`: a write needs the mailbox opened for writing), then for the conversation's messages (`EXTRA_UIDS`, under the stored `UIDVALIDITY`; a changed validity lists the thread again by `X-GM-THRID` first):
   - `UID FETCH <uids> (FLAGS X-GM-LABELS)`: which of them are starred and which are in the inbox, written to the entry as `flagged` and `inbox` before any change, so the reverse write knows what to restore (skipped if the entry has them already, from an earlier try);
   - `UID STORE <flagged> -FLAGS.SILENT (\Flagged)`: every starred message of the conversation unstarred (there may be several);
   - `UID STORE <inbox> -X-GM-LABELS.SILENT (\Inbox)`: archived, which in Gmail is the conversation leaving the inbox; nothing is deleted, moved or marked read;
   - `LOGOUT`.
   A `NO` (the message gone) on one UID doesn't fail the rest. Each command's success is written to the entry as it happens (`unstarred`, `archived`), so a try that fails between the two picks up at the second, never redoes the first, and never leaves the mailbox half-changed unknowingly. A failure keeps the entry for the next try; after five tries over a day, it's given up (`gaveUp`) and said (step 5), with what did succeed. A write that completes becomes a receipt: the entry stays with `doneAt` set, and its `flagged` and `inbox` lists are what a later Undo restores; it goes once the act can no longer be undone (the chat message's life, 30 days).
4. **The read that closes it.** A Gmail-only sync is asked for at once (the Sync service's `syncNow(setOf(Gmail))`): it lists no starred message in the conversation, the merge marks the task done (`Merge.apply`, "not listed"), `Focus.onCompleted` records the completion and gives its free time (D-10: an unstarred email earns as any completion does). So the app's state changes only through the source, as every source's does, and nothing is marked done locally ahead of the read, which would reopen it at the next listing while the write was still pending.
5. **Said.** Only when something went wrong, after the five tries: the app's own notification, not a message of his (Q18: C): `NoticeId.GmailWrite` (reserved in Stage 1), tagged by thread, on a new channel for the sources (`Channels.SOURCES`, "Your sources", default importance, not ceilinged; Q27: A), its words from what the entry says did and didn't happen: "Couldn't unstar “Trip consent form” in Gmail (no connection for a day): it's still starred", or "Unstarred “Trip consent form” in Gmail but couldn't archive it: it's still in the inbox"; tapped, opening the task's sheet, which says the same under its status; Setup's attention mark counts it as the source failing (`TaskState.status(Gmail).error`). A half-made write is still true to the source: if the unstar went through, the next ordinary sync finds no star and closes the task, as it would had you unstarred by hand, and only the inbox is left to you. Success is silent: the task closes, as it does when you unstar by hand.

## The reader stays read-only

`ImapClient` is read-only "by construction" and says so; the write commands go in a separate class, `ImapWriter`, holding `select`, `uidStore` and the one `uidFetch` of flags and labels it needs before a write, and nothing else, with a test that lists every command `ImapClient` can send and fails if `SELECT` or `STORE` appears there. Each write is logged at info level with the thread id and the command's name, never the subject or any address. The two acts are the only ones (`\Flagged` off, `\Inbox` off, and their reverses): no delete, no move, no mark-as-read, ever.

## The switch, and the first time

- `Settings.gmailFinishes: Boolean` (Q18: A, on by default, with the first-time sheet), under Settings → Sources → Gmail: "When you finish a starred email's work here, unstar and archive it in Gmail". Setup's Gmail section says it too. It isn't one of Felix's settings (`FelixSettings`): it writes to your mailbox.
- **The first finish waits for you** (Q18's sheet). It can come from the widget, the block screen or Felix, where no sheet can be shown, so it doesn't start the grace: the entry is journaled with `notBefore` unset ("awaiting your OK"), no worker is enqueued, and the task's sheet and pill say "Done here: Gmail waits for your OK". The next time one of the app's screens opens (Day, the chat, the sheet), a sheet asks once: "This will unstar and archive the conversation in Gmail. Carry on?" *Carry on* sets `notBefore = now + GRACE`, enqueues the worker and is remembered (`hintsSeen`): every finish after it goes with the grace alone. *Not this time* drops the entry, leaves the task open as today, and is not remembered: the next finish asks again, since a refusal isn't consent. The sheet's third choice, *Turn this off*, drops the entry and sets `gmailFinishes` off, so nothing asks again until you turn it on in Settings. Felix's `done` on that first email says so in its act line ("Finished here; Gmail waits for your OK in the app").

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

- `GmailFinish.due`: steps all done; boxes at zero; one step left; hidden; not Gmail; done already. `finishGmail` explicit: a task with work left still journaled; automatic: refused.
- The grace and undo rules (pure `GmailWrites`): cancelled within the grace; reversed after, from the receipt's `flagged` and `inbox` subsets, a reverse of a reverse; a receipt kept until its act's window closes, then pruned; the first finish awaiting an OK, then Carry on (remembered), Not this time (asked again next time) and Turn this off (the switch off).
- The worker through a fake writer: a try failing between the two commands resumes at the second; the fifth failure keeps what succeeded and the notification's words say which state the mailbox is in.
- `ImapWriter` against a fake server stream (as `Imap`'s tests drive `ImapClient`): `SELECT`, the `FETCH` of flags and labels, the two `STORE`s on the recorded subsets with the right flags and `.SILENT`, `LOGOUT`; a `NO` on one UID carries on; the reader's command list has no `SELECT` or `STORE`.
- The worker's outcomes through a fake writer: success keeps the receipt and asks for the Gmail sync; failure keeps the entry with its try count; five failures give it up, post the notification (a fake `Notices`) and mark the task.
- The merge after an unstar: done, completion recorded, free time given (`FocusTest` with a Gmail task).
- The switch off: nothing journaled; the `done` action refused as today.
- The sheet's button and Felix's `done` through the same function.

## Phone check

With your real mailbox, so chosen with care (D-79: no detail of it in the PR):

- A note-to-self email you star for the test, read into a task (`sync --es sources gmail`); `plan` shows it; tick its piece on Day (yours to tap); the first time, the sheet asks and you tap *Carry on*; within the grace, `state` shows the write journaled; a minute on, Gmail on the phone shows it unstarred and out of the inbox; `state` shows the task done and its completion rewarded.
- A conversation with two messages, one starred: after the write and an Undo past the grace, Gmail shows only that one starred again, and only the ones that were in the inbox back in it.
- Again, with Undo within the grace: nothing in Gmail changes.
- Again offline (aeroplane mode, by you): the entry waits; online, it goes.
- The switch off: a tick leaves Gmail alone.
- The IMAP log (`adb logcat -s Decrastination | grep IMAP`) shows `SELECT` and the two `UID STORE`s, nothing else new, and no subject.
