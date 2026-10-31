# Sources

Where tasks come from, how each place is read, and how a read is merged into the tasks the app keeps.
Four places are read (Teams, Power Planner, AnkiDroid, Gmail); your own tasks are made in the app. Each source alone says when its tasks are done, but for feedback to read, which your tick finishes (below).

## At a glance

| Source | Read through | A task is | Done when |
|---|---|---|---|
| Teams | the Teams Assignments widget's provider | an assignment not handed in | it leaves the widget's list |
| Power Planner | its web API | an agenda item | 100 % complete, or gone from the agenda |
| AnkiDroid | its content provider | today's quota, or a deck homework names | Anki's counts say so |
| Gmail | IMAP, read, and one write: a finished email unstarred and archived (D-106) | a conversation with a starred message | no message in it is starred |
| Yours | made in the app | what you give it | you say so, or every step is done |

- Each source is a `TaskSource` (`sources/TaskSource.kt`). A read returns everything the source has now, or throws: a failure must never look like an empty list, as the merge takes a task missing from a read as done.
- A problem Setup can put right (no password saved, a permission not granted, an app not installed) throws `SourceUnavailable`, shown under the source.
- A read that can't be trusted fails too (BUG-P2-007): a Teams row with no key, an agenda answer with no item list, an Anki deck whose counts can't be read, Gmail describing fewer messages than it listed.

## Teams

Decrastination has no Teams reader of its own. A separate app, the Teams Assignments widget (TeamsAssignmentsWidget, `com.teamsassignments.widget`), reads Teams; Decrastination reads the widget (`sources/teams/TeamsSource.kt`).

- **Access.** The widget's provider, `content://com.teamsassignments.widget.assignments`, is guarded by the signature permission `com.teamsassignments.widget.permission.READ_ASSIGNMENTS`.
- Both apps are signed with the same key, so Android grants it without asking. Decrastination declares the permission too, so the order the two are installed in doesn't matter.
- **Paths and calls** (`TeamsProvider`): `/assignments`, one row per assignment not handed in; `/state`, the widget's last sync and status; `/returned`, work handed back with feedback (NF-06); and the calls `requestSync` and `open`.
- Queries and calls run off the main thread, as they block while the widget's process starts.
- **A row as a task** (`TeamsRows.fetched`): Homework, keyed `teams:<key>`, with its title, class, instructions, due time, tab and due text.
- **Its key is its identity**, so it never changes: an assignment gone from the list is done. The widget keeps it so: work it saved from its own screen before a list showed the card (keyed `h-…`, from its class and title) keeps that key here after it takes its Teams id, as a changed key would read as work handed in and new work set.
- Its reference materials (by name) and links, one a line, where the widget gives them (DX-08): the files are kept as an email's attachments are, and named to Claude; the links are listed on the sheet, each opening in the browser.
- **Returned work** (NF-06, `TeamsRows.returned`): each row is a task to read the feedback, "Read the feedback on …", Admin, 10 minutes, due three days after the work came back, its text the feedback.
- It's done by its tick (`TaskItem.tickDone`), as no source can say it's read, or once the widget stops listing it. Claude doesn't read it. A widget without `/returned` refuses the path, and there are none.
- **State.** The widget's `last_success_at` is shown as when the widget last read Teams itself. Its sync service off, a sync running or a sync failed is a note under the source (`TeamsRows.note`).
- **Done.** An assignment gone from `/assignments` is handed in. Listed again, the same task opens again.

Freshness:
- A Teams sync takes over the screen, so the widget syncs only when asked. Decrastination asks (`Sync.requestTeamsSync`) when you tap **Refresh Teams** on a piece, **Sync Teams** on Work's trouble card or the widget's ↻, and when you ask Felix to check Teams with the chat on screen (D-11).
- It also asks on its own (Q21, `block/TeamsAutoSync.kt`): at the first unlock after 16:45 (`teamsFirstUnlockMin`), every three hours (`teamsSyncEveryMin`), and at an unlock soon after the morning briefing.
- Automatic syncs come only while the phone is unlocked and in use, never in quiet hours, and not within 30 minutes of a Teams sync; one put off (*Delay 5 min*, or offered again) comes once its delay is up.
- On a school day, by the school calendar, the first-unlock and three-hourly ones wait for the school-day blocking time (`weekdayBlockFromMin`); a weekday off is a weekend day, as blocking's is (D-105, `Focus.schoolDayAt`); the morning's comes at an unlock within an hour and a half of the briefing.
- Each is announced by a banner counting down 10 s, with **Cancel** and **Delay 5 min** (`block/CountdownBanner.kt`).
- One the widget didn't start is offered again 15 minutes later, twice at most; then the next comes at its usual time.
- The widget announces each change to its list. Once the changes have stopped for 5 s, Teams and Anki are read (`Sync.teamsChanged`). An announcement to an app in the background can come late, so it's a prompt to read, not a clock.

**Open** asks the widget to open the assignment in Teams (`call("open", key)`); feedback opens its assignment. When the widget can't, Teams opens on its own and you're told why (`ui/TaskOpener.kt`).

## Power Planner

Power Planner's Android app exposes nothing readable, so the app uses the web API its open-source web app uses (`sources/powerplanner/`).

- **Calls** (`PowerPlannerApi`): JSON `POST`s to `https://web.api.powerplanner.net/api`, each with the web app's public development key: `LoginWeb`, `GetSelectedSemesterId`, `GetClassesAndSchedules` and `GetAgenda`. The session token goes in the login's `Password` field, as the web app sends it.
- **Login.** Your username and password are in the encrypted secrets file. The session is kept and reused until the API answers a call with an error (an expired session, say); then one fresh login is tried, and the semester read again (`PowerPlannerSource`). A call that doesn't get through (no network, an HTTP error) fails the read.
- **Semester and timetable** are read at most every six hours, so a read is usually one `GetAgenda` call.

An item as a task (`PowerPlannerItems.fetched`):
- `ItemType` 5 is a task: Homework with a class, Admin without (a task with no class carries the semester's id as its class). `ItemType` 6 is an event. Other types are ignored.
- `Date` is local time, and its seconds carry the time option. With a class: `:00` the class's start, `:01` ahead of class and `:02` during it (both its start), `:03` its end, `:04` a set time, anything else all day. With none: `:04` a set time, else all day.
- Class times come from the class's first lesson that day in the two-week timetable (`ScheduleWeek` 1, 2, or 3 for both, counted from `WeekOneStartsOn`). A class-relative time on a day without that class is all day. All day is due at 23:59.
- Power Planner's "no due date" (31 Dec 1999) is no deadline. `PercentComplete` is the task's progress; at 100 % it's done, as it is once it leaves the agenda.
- An agenda answer without its `Items` list fails the read (BUG-P2-007); an empty agenda comes as an empty list.
- The timetable a read used is kept with the tasks (`TaskState.timetable`, DX-09), and a failed read leaves it: the school days' lessons and study periods come from it (`PowerPlannerItems.lessonsOn`).

**Open** launches Power Planner: it has no link to one item.

## AnkiDroid

Read through AnkiDroid's own provider, `content://com.ichi2.anki.flashcards` (`sources/anki/AnkiSource.kt`), behind its runtime permission `com.ichi2.anki.permission.READ_WRITE_DATABASE`, granted once in Setup.

- **Decks** (`AnkiProvider.decks`): each deck's name, today's counts (`deck_count`, `[learn, review, new]`), its New cards/day (`new.perDay` in its options) and its description.
- **Cards never studied** (`AnkiProvider.unseenCards`): Anki's own search of notes, a side at a time (`deck:"…" is:new card:1`, then `card:2`, up to four), so a deck learnt both ways counts two cards a word.
- They're asked again only once a deck's counts or its New cards/day change, or after an hour (`UnseenCache`, AR-08).
- Section decks are named `Textbook N::x.y`; which textbook a number means is a setting (`ankiTextbook`, Q18).

Its tasks are derived from the counts (`core/AnkiRules.kt`), never listed by AnkiDroid:
- **The daily quota** (`AnkiRules.quota`), "Anki: N reviews + M new": every due review, plus today's new cards from the lowest-numbered section deck of the current textbook that still has cards never studied (then other textbooks'). Revision, due at 21:30 (`ankiDeadlineMin`).
- The quota's deck is fixed at the Anki day's first read and kept for the day (`AnkiDay`); the Anki day turns at 04:00 on the clock. The quota is done once nothing is due and its deck has no new cards left today.
- On a day a deck homework names is due by tomorrow (or overdue) and still has cards to learn, an assignment you haven't marked not a task, nor the same work as another, asking for it (`AnkiRules.asked`: one only such marked assignments name is listed still, but asks for nothing of the day), the quota is the reviews alone for the rest of the Anki day (`AnkiDay.reviewsOnly`); with no reviews due, there's no quota that day. One listed today with no new cards of its own (none left in a section deck, its deck homework's, or a reviews-only day) is done once its reviews are studied, not dropped (`AnkiRules.quota`'s `open`), which the merge would take as missed; one that listed new cards of its own earlier that day (`EXTRA_NEW_LISTED`; one an older build listed, with no mark, counts as such), since given to homework or put off, isn't, as its completion would count them as studied.
- **A deck homework names** (`AnkiRules.homeworkDecks`), "Learn Anki deck x.y": Homework, due with the earliest open assignment naming it, by the deadline in force (its real deadline kept, PL-14). Done once it has no card never studied; missed if its assignments go first.
- **Cards as steps.** A deck task's steps are "N new cards", sized by the deck's own New cards/day, the first what Anki will still show today; one a day (`stepsPerDay`). With today's new cards studied it waits for the next 04:00 (`notBefore`).
- A deck allowing no new cards a day is one step of all its cards, and its text says to raise the limit or use Custom study.
- **Minutes** go by the seconds a card learned for each deck (DX-11, `learn/AnkiProgress.kt`), else 8 s a review and 25 s a new card. A pace learned while the counts stand still changes the steps' minutes too, their ticks kept (`Merge`).

Which decks homework names (`AnkiRules.sectionsOf`), the first that finds any:
- a section number in a vocabulary instruction ("learn vocabulary 2.3"), not part of a longer number (`linkedSections`);
- the one deck whose description (its topic, four letters or more) the instructions hold as a whole phrase (DX-10, `describedSections`);
- the sections Claude's reading names (readings.md).
- Decks with a description are told to the Assignment job by topic. A change to the topics marks the readings of the assignments it affects out of date (`Syncer`).

Anki is read again at once when AnkiDroid leaves the screen (NF-03, `block/AnkiWatch.kt`), when its settings change, with Teams when the widget's list changes, and when a reading changes the sections an assignment names or moves the date of one that names some. **Open** selects the deck and opens AnkiDroid's reviewer on it; the quota opens the deck list.

## Gmail

Your starred emails, over IMAP with a Google app password (`sources/gmail/`): `imap.gmail.com:993`, TLS, the host checked against its certificate. The address and app password are in the encrypted secrets file.

- **Read, but for one write.** `ImapClient` (`Imap.kt`) sends only `LOGIN`, `LIST`, `EXAMINE`, `UID SEARCH`, `UID FETCH` (bodies with `BODY.PEEK`) and `LOGOUT`: a read marks nothing read, moves or sends nothing. Each command is logged at debug level, the login left out. The one write, an email finished here, is `ImapWriter`'s (below).
- **Starred only** (DX-03, `StarredConversations.list`). All Mail is found by its special use (`\All`, else a name ending `/All Mail`), examined, and searched for `FLAGGED`, Gmail's star.
- Then every message of those conversations is listed (`X-GM-THRID`, forty a search): starred or not, yours included, drafts aside.
- **One task per conversation**, keyed by its thread id, named by its newest message's subject. It's with the sender of the newest message that isn't yours; it's a note to self only when every message is yours (Gmail's `\Sent` label).
- **The star is the state.** A conversation with no starred message left (unstarred, or deleted) is done at the next read, whatever steps it has left. Archived but starred, it stays. Starred again, the same task opens again.
- **Nothing fetched twice** (DX-07). Each task keeps its messages' UIDs and All Mail's `UIDVALIDITY`. A conversation with nothing new is built from its stored task, no envelope fetched, under the email's own subject as last read (`SourceValues.title`), never a name you or Felix gave it, which stays laid over it.
- The rules read an email's dates as of the day its newest message came, not the day it's read: a date it gives before then is history, one after is kept, a deadline gone by included, so an unchanged email reads the same at every sync and its reading stays its own (`EmailRules.triage`).
- One with a message come or gone has every envelope fetched and its text read again, whole. A new `UIDVALIDITY` reads everything afresh.

Texts (`GmailSource.text`, `Mime.readable`, `GmailThreads.conversationText`):
- A message's text is its first `text/plain` part, else its HTML, found from `BODYSTRUCTURE` and fetched whole up to 200 KB; decoded, flattened, invisible padding dropped, cut to 20 000 characters.
- A part past 200 KB whose text came short ends "[The rest of this email is too long to be read here.]" (BUG-P2-022).
- A conversation of several is oldest first, each message headed `--- From …, YYYY-MM-DD HH:MM ---` ("From you" for yours), within 16 000 characters filled from the newest back.
- An older message too long for the room left is cut; those earlier are left out, a line at the top saying how many. A read takes a conversation's newest 20 messages at most.
- **Quotes cut** (DX-06): the first message shown keeps what it quotes. Later ones lose quoted earlier messages and signatures (HTML `blockquote`s and Gmail's quote blocks; "On … wrote:" lines with a date or time; Outlook's headers; `-- `).
- Each such message ends "[Quoted text or a signature left out: N characters.]". A forward keeps what it forwards.
- **Attachments named** (DX-01, `Mime.attachments`): a part whose disposition is attachment, or a named part that isn't text; an inline image isn't one, and an attached email goes by its subject.
- They're kept on the task as name, type and size, each file once a conversation; shown on the sheet, and named to Claude, who can't see them.
- **Invites** (DX-02, `Mime.invite`, `core/Invite.kt`): a `text/calendar` part or a `.ics` file gives its first event's name, start, end and place: in UTC, in your zone for a floating time, or in the zone it names (Java's, Windows' or its own `VTIMEZONE`). A zone none of these tells gives no invite.
- The newest message's invite stands, and there's none once one cancels it. A recurring event gives its first time.
- A text made by older text rules (`GmailThreads.TEXT_RULES`, 2) is read again once. A conversation whose text isn't read yet, or not as far as reads now go, isn't read by Claude (`GmailThreads.textRead`).
- **Limits a read** (BUG-P2-019): texts for at most 60 messages, newest conversations first, each whole or not at all; fetched for up to 45 s once listed, never past 70 s into the read, one conversation even then up to 82 s, so a read stays within the sync's 90 s. The rest wait for the next read.

**Emails finished here** (D-106, `sync/GmailFinishes.kt`):
- Finishing an email's work here unstars and archives its conversation in Gmail: its last piece kept (Day, the widget, the block screen, Felix's tick or keep) or its last step ticked on the sheet, when all its work is done (`GmailFinish.due`: every step ticked, or none and its minutes worked at the pace learned); or by your word, whatever's left: Felix's `done`, or the sheet's *Finish: unstar and archive*.
- Journaled first (`gmail-writes.json`, [storage-and-backups.md](storage-and-backups.md)), it goes a minute later (`GmailWrites.GRACE_MS`): an Undo within that minute (the keep's, the tick's, his act's, the sheet's) drops it, and Gmail's never touched. An Undo of one that's read the conversation and waits (a failed try's wait) stops it there and puts back what it may have made. An Undo takes back only its own finish: his act's the one it made (its task here or not, as after a backup's restored), and a keep or a tick taken back none by your word, nor one whose work is still all done. The very first finish waits for your OK instead, asked by the next of the main screen (whichever tab) or the chat to open: *Carry on* (kept in the hints: every finish after it goes with the minute alone), *Not this time* (asked again next time), *Turn this off*.
- The write (`GmailWriteWorker`, WorkManager with a connection, through the `GmailWriter` port, `ImapGmailWriter` on the phone; set again at each start for what's journaled, one set or running left as it is): its own connection, `LOGIN`, `SELECT` All Mail (writable), the conversation's messages listed afresh by `X-GM-THRID`, which were starred and which in the inbox kept by Gmail's message ids before anything changes (any starred or in the inbox since added at a later try), each command made on those that still need it, then `UID STORE -FLAGS.SILENT (\Flagged)` on the starred and `-X-GM-LABELS.SILENT (\Inbox)` on the inbox's (`ImapWriter`), each one's success kept as it comes, so a retry goes on at the one that failed; a message gone since is stored around. Nothing is deleted, moved or marked read. Each command is logged at info level by its name and UIDs, never a subject or an address.
- Done, it asks for a Gmail read, which finds no star and closes the task as one unstarred by hand (earning as any finished task does). Nothing is marked done here first; till a read after it, finishing it again writes nothing more (its sheet says it's being finished already). A later read that finds it starred again (a new round) lets go of its finish's receipt, as a month does (`GmailWrites.settle`, at a start and after every read).
- An Undo after it puts back what it took: the star on the messages that had it, the inbox on those that were in it. It reads the conversation afresh and puts back each message that isn't as it was before the finish, not only those the finish's journal says it changed: a command Gmail made whose OK was lost (the connection gone before it) left its success unkept, and the Undo would otherwise leave it made (`GmailWrites.toRestore`). One already as it was isn't changed again. So a message you archive or unstar on Gmail's website while a finish waits (its tries' waits run to hours) is put back as it was before the finish too: the app can't tell your change from one whose OK was lost. Once the read has closed the task, starring it again reopens it as an email starred again does.
- **A star's kind stays on Gmail's website** (Gmail's limit, D-106). A star set on Gmail's website carries, beside the star itself (`\Flagged`, Gmail's `\Starred`), a hidden label for its kind, the plain yellow star's too (`^ss_sy`; blue `^ss_sb`, and so on). IMAP can find one (`UID SEARCH X-GM-LABELS "^ss_sy"`) but not take it off: Gmail refuses the label's name (`BAD … Label name is not allowed`). Nor, by every report so far, can the Gmail API (which has no label id for a kind) or Apps Script's `unstar` (Google's issue 77320923, marked fixed but not yet seen working). So a finished email is out of Gmail's Starred, its searches by star and this app's reads, but the website still draws a star on it till it's clicked off there. An Undo's star comes back in its own colour, its kind never having gone.
- A write that fails five times is given up: the app's own notification on *Your sources* (`NoticeId.GmailWrite`, a conversation each) says what did and didn't happen and opens the task's sheet, which says it too, with *It's sorted*; Setup's mark shows meanwhile. A finish given up while an Undo of it is queued isn't said: the Undo puts back what it took, or is said itself. A try that fails waits before the next (`GmailWrites.backoff`: two minutes the first time, as a connection lost while the phone comes back online is the usual first failure; then half an hour, an hour and two hours, the fifth failure giving it up), the wait kept in the journal, so another write setting the worker going doesn't cut it short; each email's writes go in the order they were made. A finish again after one given up goes on from it (where it stopped; after an Undo given up, again from the finish it was taking back), so its Undo can still put back all the first one took; the failure's notification is taken away, as it is by an Undo of it.
- Settings → Sources → Gmail's switch (`gmailFinishes`, on) turns it off: a finish then writes nothing, and Felix's `done` on an email is refused, as a source's task's is (asked to finish one, he says he can't finish it in Gmail and asks whether to mark it not a task instead, never calling it done); a finish not yet started (waiting for your OK, in its minute or for a connection: its conversation not yet read) is dropped as it's switched off (`GmailWrites.switchedOff`).

The rules' triage (`EmailRules.triage`) is what a conversation is until Claude reads it, or with Claude off. Every starred email is something to do, Admin (AI-02):
- a note to self: 10 minutes, "Deal with your note";
- a shared document: 5 minutes, "Open the shared document";
- one asking you to do something ("please complete / return / sign…", "action required", "reply by…"): 15 minutes, "Do what it asks", due at 23:59 on the date after "by" if one follows (BUG-P1-003);
- words like ticket, booking, open day or registration with a date ahead: 5 minutes, "See what <date> needs of you", due at the start of that day;
- a notification or newsletter (a no-reply sender, a known notification service, an unsubscribe link): 5 minutes, "Read it and decide";
- anything else: 15 minutes, "Read it and decide".
- Dates it reads: "3 March", "Wed 3rd Mar 2027", "March 3", "03/03/2027" (day first), up to a year ahead; one with no year is this year's, or next year's once over 120 days gone.

**Open** launches Gmail and says what to look for, the subject and sender: Gmail can't be opened on one conversation from outside. An invite's sheet has **Add to calendar**.

Every sync that may need the network and isn't started from a screen in use runs as a WorkManager job (`SyncWorker.syncNow`): Android 15 and later cut a background app's network a few seconds after it leaves the screen, mid-connection. The reads of Teams and Anki that the widget's changes and AnkiDroid's leaving start run in place, as they need none.

## Your own tasks

Made in the app, not read from anywhere (Q28): `Source.Own` ("Yours"), keyed `own:<uuid>`, in `tasks.json` and in backups, as nothing else can give them back (`core/OwnTasks.kt`).

- **Made** with *New task* in Work (`ui/OwnTaskForm.kt`), by Felix in the chat (felix.md), or from a photo of homework (NF-01, `enrich/PhotoTasks.kt`), each checked as the form checks one (`OwnTaskRules.problem`). The form's save runs in the app's scope (`FormSaves`), so a turn of the phone mid-save doesn't stop it (a photo's attaching with it); the form made again closes once it's kept, or gives Save back if it wasn't.
- **What you give**: a name (200 characters), a kind (Homework, Revision, Admin or Event), a subject, a due date and time, a start date, minutes (1 minute to 60 hours), notes, up to 30 steps with their own minutes and dates.
- And where it can be done: home or anywhere (PL-15). An event needs its date and time and has no minutes.
- Kept as a source's values are (`sourceValues`), so instructions lay over it as over any task. A due date, start, minutes, steps or name an instruction gives is made on the task itself (`OwnTaskRules.changed`, `OwnTaskRules.MADE_ON_IT`), as the form would make it.
- Steps edited in the form are matched to their old steps by place, so a renamed step keeps its tick (UI-15). Each row keeps its step's own dates and whether it needs the steps before it (D-109), so a task Felix proposed, opened in the form before it's added, keeps them too; each step but the first shows the mark as an "Any order" chip beside "Home only", to set or clear, your own steps' too, and it goes with the row as it's moved or retitled.
- **Done** when you tap *All of it done*, or once every step is done. It earns no free time of itself; its kept slots earn theirs. *Not done* opens it again; *Delete* removes it, and only *Delete* does: a fortnight after it's done, it's kept as its record, its notes let go (`OwnTasks.pruneOwn`, C-Q3).
- Claude doesn't read it: its minutes and steps are yours, planned as given (readings.md).
- `tasks/OwnTasks.kt` keeps, finishes, opens again and deletes them, and trims those long done to their record, its rules `core/OwnTasks.kt`'s; `tasks/OwnTasksTest` drives it on temporary stores.

## The sync

- **When.** `sync/SyncWorker.kt` reads every source every 15 minutes (WorkManager's shortest period), with no network constraint, and at once when asked (`SyncWorker.syncNow`, all sources or some).
- A sync of all or some sources also runs on a refresh (the main screen's ↻, on any tab, and the widget's), when the Teams widget's list changes, when AnkiDroid leaves the screen, when Anki's settings change, when a reading changes an assignment's sections or the date of one that names some, when Felix reads a source for you from the chat (G2), and from Setup.
- *Check it's done* on a piece reads just its task's source and says whether it agrees (`ui/PieceActions.kt`).
- **How** (`sync/Syncer.kt`, which the app reaches through `sync/Sync.kt`): one sync at a time; the sources in turn, Anki last, as its deck tasks come from the others' homework; each read on its own, waited for at most 90 s.
- **A failed source fails only itself.** Its error is kept in its `SourceStatus` with the time of the attempt; its tasks stay as they are, and the other sources carry on.
- A read that overruns is cancelled and left to wind down, and no other read of that source starts beside it (BUG-P2-020).
- A good read records its time, the source's own "as of" time and note, and Anki's day, the decks' topics and Power Planner's timetable (`TaskState`).
- `Sync` is built like `Focus`: its stores, the clock, the syncer, and `SyncPorts` for what Android does (a sync as a job, the Teams widget asked and watched, the calendar read, the widget drawn); `SyncTest` drives it on temporary stores with made-up sources, `SyncerTest` the syncer itself.
- **After each sync**: completions are settled (free time and the log, learning.md); your instructions' overrides are laid on new and returning tasks (`Instructions.layTaskOverrides`); your own tasks finished a fortnight ago are kept as their record (`OwnTasks.pruneOwn`); Gmail's journal is settled (`GmailFinishes.settle`); Felix says what a read he asked for changed of the task (`Conversation.afterSync`, G2); instructions still waiting for Claude are read, and a question of yours still unanswered is tried again ([felix.md](felix.md)); and readings are queued where any task's is out of date (`EnrichWorker`, readings.md).

## The merge

`core/Merge.kt` applies one source's fresh list to the stored tasks; it's pure, and tested directly. Tasks of other sources are left alone.

- **Listed**, a task is updated from the source: title, text, class, due, start, kind, the source's estimate and progress, and its extras. The app's own bookkeeping stays: first seen, minutes worked, photo minutes, steps and their ticks, the reading, your overrides, your answers, the photo.
- A source's steps replace the kept ones only when their titles change (Anki's cards). The minutes worked are then counted as already out of them (`stepsWorkedMin`).
- **Listed as finished** (Power Planner at 100 %, a deck studied), an open task is done. **Not listed**, an open task is done: handed in, ticked, unstarred. A derived task (Anki's) is missed instead: only its counts say it's done.
- **Listed again**, a done task opens again afresh, its minutes worked and its steps' ticks starting from nothing. A derived task once done stays done, as does one done by its tick.
- A task already finished when first seen isn't added (a derived one is, as done). A finished task is kept while its source lists it, and otherwise 14 days from its finish (a missed one's from when it was last listed) (`Merge.KEEP_FINISHED_MS`), and longer while it's the same work as an open task.
- A fortnight after it's finished, a task still kept keeps only its record (`Merge.trimmed`, after every merge, C-Q3): its text, reading, answers and photo go, with an email's messages' UIDs and how far its text was read, and its attachments' list (an assignment's reference materials too); its title, dates, estimates, minutes, steps and their ticks, and your values stay, as the reading laid them, and while its source lists it as finished still, the merge leaves it so. Listed again as open, its text comes back and it's read again, as a task seen for the first time.

Every merge lays the rest over the source's values (`TaskItem.enriched`, `core/Enrichment.kt`):
- **The reading** (readings.md), always over what the source says now (`sourceValues`), so a new reading leaves none of the last one's dates.
- The source's deadline wins, but for an invite's start, Claude's deadline for an email, a test date earlier than the source's, or Claude's deadline where the source has none.
- Claude's start counts where it's later than the source's (for an email, it replaces the rules' guess), and is dropped if it's after the deadline.
- The reading gives its estimate, and its steps where the task has none of its own. An email is always Admin.
- A reading whose task's content has changed (`Enrichments.current` is null) lays nothing: the source's values stand, and the reading's steps go, until it's read again.
- **Your overrides**, last: your due date, your name for it (the source's kept in `SourceValues.title`) and your steps (`TaskItem.mine.steps`), their ticks carried by each step's id, or by its name and place for a step new to the list (`carryMarks`).
- Your other overrides (not a task, a start, after another task, a day, a time, the same as another, your minutes) are kept on the task and read by the planner (planner.md).
- The estimate in force is yours, else the reading's, else the source's or the rules', else its kind's default (`TaskItem.effortMin`).

## The same work twice (DX-15)

`core/Duplicates.kt` finds open tasks from two sources that look like the same work (`Duplicates.candidates`). Never Anki's, nor hidden tasks.

- Alike means the same subject (by Settings' class map, or the same class), due within a day of each other or both undated, and titles sharing at least 60 % of the shorter one's words of three letters or more.
- Work shows each such pair with *Same?*. Yes makes one the other's same work (a `SameAs` change, `TaskItem.mine.sameAs`): it's off the plan and the lists, and its minutes worked count for the one kept (`Duplicates.credited`). No keeps the pair from being asked about again (`TaskState.notSame`, `Instructions.notSame`).
- Felix is told which tasks look alike, for a `SameAs` you ask him for.
- A link to a task that's gone is let go at every merge, and the hidden task comes back (`Duplicates.linksPresent`).

## Events

- A Power Planner event (an exam, an appointment) is a task of kind Event at its time: all day where its end time says so (`:59`), else from its start to its end, or its class's lesson. Claude doesn't read it; the planner keeps it apart from the work (planner.md), and it earns no free time.
- An appointment entered in Power Planner as a task stays a task: the Effort job gives only the time to prepare for it.
- An event of your own is one of your tasks of kind Event, with a date and time and no minutes.
- An email's invite (DX-02) makes its email due at the event's start; the email stays something to do, what it needs of you by then. Where Claude's reading quotes a deadline from the email (a reply by Friday), that is when it's due.
- *Add to calendar* on an invite's sheet fills in the calendar app's new event for you to save.
- The phone's calendar isn't a source of tasks: its events are busy time, judged one by one (slots-and-calendar.md).
