# Notes and reminders

Felix keeps two new things at your asking: a **note** on a task, or standing, for every task of a class, subject or source (Q31: A), for himself and for you, read whenever the task comes up; and a **reminder**, delivered as his message at a time, at the start of a task's next slot, or a while before a task is due. Both are set from the chat, made at once with an Undo, and kept in a store of their own. A reminder is delivered in his words for the moment it's due, written just before (Q9: B; Q24), or as you set it when he can't write; a note costs no call. Built in Stage 8a ([03](03-stages.md)), inside the Conversation service, after Stage 7 has given the app `By`.

## What they are

The types are in [04](04-data-and-migrations.md) §The types: `Note(id, text, at, by, taskId?, scope?, deletedAt)` with `NoteScope(subject, className, source, titleContains)`, `Reminder(id, text, at, by, taskId, due, sentAt, snoozedTo, doneAt, draft, draftedAt)`, `Due(kind, at, minutesBefore)` with `DueKind`:

- `At`: a time of day on a date ("at 7", "Friday morning" read as 09:00 by him, said in his reply).
- `SlotStart`: the start of the task's next work slot on or after a date (today, with none): "when I next sit down to the physics". If the plan has no slot for it, it waits; if the task closes first, it's let go and said.
- `BeforeDue`: `minutesBefore` the task's deadline in force (`TaskItem.deadlineAt`), following the deadline if that moves; none, it's let go and said.

`By` is Stage 7's enum (`You`, `Felix`), in place before this. A note from you comes from the task's sheet (Q14: A); from him, the chat.

The store: one file, `felix.json` (`FelixStore(notes, reminders, version = 1)`), made by `Stores` (Stage 3), owned by the Conversation service (Stage 4), in the backup (format 7) and in Reset's "The chat and Felix's history" (Q12: A). Not on `TaskItem`: a merge rewrites tasks and must not be able to drop them; keyed by task id, which is stable across syncs and reopenings, and outlives a task's trimmed text on its record (Stage 7).

## Setting them from the chat

**The schema** (`ChatPrompts.SCHEMA`) gains two arrays of plain fields and two actions, so no union is added (`SchemaLimitsTest`); the API compiles it before the release (`SchemaCompileTest -PcheckSchemas=true`, D-87). The chat's schema was refused once as too large (3.1.4): if it is again, the fallback is to carry them as change types (`Remind`, `Note`) on the existing `CHANGE` object, whose fields already hold a task, a date, a time, a title and minutes, at no growth at all (Q13: A, with B in the same PR if the API refuses). A standing note's scope has no field of its own there: the fallback carries it in the object's `eventKey` string as `kind:value` ("subject:chemistry", "class:12A", "source:Teams", "title:essay"), which the parse reads and the prompt's note item spells out; a cost of the fallback, said in the PR if it's taken.

```text
reminders: [{ id (empty for a new one; a listed reminder's id to move or reword it), text, date (YYYY-MM-DD or empty), time (HH:MM or empty), taskId (or empty), when: "at" | "slotStart" | "beforeDue", minutesBefore (-1 for none) }]
notes:     [{ taskId (or empty), text, subject (or empty), className (or empty), source (or empty), titleContains (or empty) }]   // a task, or a scope: one or the other
actions:   type gains "cancelReminder" (taskId empty, value the reminder's id) and "deleteNote" (value the note's id)
```

**The prompt** (one item each, in the chat's numbered list, the wording in [09](09-his-voice.md)'s register once Stage 10b lands; plain until then):

- Remind (reminders): when they ask to be reminded of something, that's a reminder, not a task: its words in theirs, as you'd say them then; at a date and time; or at the start of their next slot on a task (when: slotStart, taskId); or a while before a task's due (beforeDue, minutesBefore). To move or reword one already set ("push it to 8"), give its id from the list with the new time or words. Say when it'll come. Not for work to plan, which is a task.
- Note (notes): something to keep with a task for when it comes up (what the teacher said, which source to use, what they've decided): taskId and the words; or, for every task of a subject, a class, a source or with a word in its title ("all of her chemistry homework on sheets"), the scope instead of a task. Not a reminder, and not a fact about them.
- The context lists the reminders set (id, when, words, task) and each open task's notes under its steps, so he answers "what have I asked you to remind me of?" and avoids setting one twice.

**Checks** (`ChatAnswers.parse`): a reminder's time from an hour ago to a year ahead (one gone by is refused and said); `slotStart` and `beforeDue` need a listed open task; the text up to 300 characters; at most 10 reminders and 10 notes a message; a note's task listed; an `id` must be a listed reminder not yet sent (else refused and said), and the entry then changes that reminder's due and words in place (its draft dropped, drafted again), an act of its own ("Moved: “call gran” to 20:00") whose Undo puts the old due and words back. Limits in the store: 50 reminders not yet sent, 10 notes a task (the oldest noted). What's refused is said under his reply, as changes are.

**Made at once.** Each reminder and note set is an `Act` with its Undo line ("Reminder set: Fri 24 Oct 19:00, “call gran”"; "Noted on “Vectors”: use the second source"), undone while it's as he left it (not sent, not deleted). `cancelReminder` and `deleteNote` are acts too, with Undo bringing the thing back: neither removes anything. A cancelled reminder gets `cancelledAt` and is never delivered (`Reminders.next` and `due` skip it, its draft dropped), a deleted note `deletedAt`; each stays, whole, until its act can no longer be undone (the message's life), and Undo clears the mark.

**From the instructions screen** (*Your changes*, `InstructionPrompts`): not planned. The screen reads your own words into changes to tasks; reminders are the chat's. If Q13's fallback is taken (change types), they come to it for free.

## Delivery

**When.** `Reminders.next(state, plan, now)` (pure) gives the earliest moment any reminder is due: an `At`'s time (or its snooze), a `SlotStart`'s next slot start from the plan, a `BeforeDue`'s deadline less its minutes. One exact alarm is set to it (`SlotAlarms.armReminder`, as `armFelix` is; `USE_EXACT_ALARM` is already declared), set again after every plan (Planning's hook, beside writing ahead) and after every change to the store. A sweep at app start and at each of his looks sends any gone by (the phone off), late and marked so ("(late: it was for 19:00)").

**Written ahead, sent on time** (Q9: B; Q24, decided live on 18 Oct). A reminder due within `FelixRules.AHEAD_MS` (ten minutes) is drafted as his slot messages are (`Looks.draft`, run by Planning's `Ahead` after every plan, by each of his looks, and at once when a reminder is set, snoozed or moved, so one set three minutes ahead gets its draft without waiting for a plan or a look): `FelixPrompts.Reason.Reminder`, with `describe` given the reminder's words as you set them (quoted, so they're in what he says), the task's text and notes where it has a task, the slot under way or next, today's kept and missed, his recent messages, and the time it's for; written as of that moment, under `MAX_MESSAGE` (240 characters), in his register ([09](09-his-voice.md)): "oi, you said to remind you: call gran. you're between slots now, so good time". The draft is kept on the reminder (`draft`, `draftedAt`), one at a time. At the time, the sweep sends the draft as a message of his, kind `Reminder`, `about` its task, with chips *Done*, *Snooze 15 min*, *Snooze 1 h* (Q11: A), through the outbox, so it's in the chat and on his notification. No draft by then (no network, Claude off or resting, over the cap, the call failed or was refused, the phone off through the window) and your words go instead, verbatim, as a `byApp` message, "reminder: call gran" (`FelixWords.reminder`), with the same chips: never nothing. A snooze drops the draft, and the new time is drafted ahead again. Each draft is one call through the gateway (`ModelEffort.Job.Felix`, a few cents, under the monthly cap). Where one of his own messages is due at the same moment (a `SlotStart` reminder at a slot's start, when his SlotStart look fires ten minutes later), the reminder is in `FelixPrompts.describe` for that piece ("Their reminder for this slot: …"), so he doesn't say it twice.

**Replies.** *Done* marks it `doneAt`; a snooze moves `snoozedTo`; any other words are a chat turn with the reminder quoted ([05](05-the-chat.md) §5). A `SlotStart` reminder whose slot moves before it fires moves with it; one whose task closes is let go with a `Note` message saying so.

**Quiet hours and busy events** (Q10: A). His own messages never come then (D-58). A reminder is yours: an `At` reminder comes at its time whatever the hour, as an alarm would; `SlotStart` and `BeforeDue` follow their slot or deadline, which the planner already keeps out of quiet hours. The draft is written in quiet hours too (a call, not a message).

**The morning.** His Morning context lists today's reminders ("Reminders today: 16:00 dentist; 19:00 call gran"), so the briefing can mention them, at no call of its own.

## Where notes show

- **The task's sheet**, About: "Notes" with each note's words, who wrote it and when, and a delete; "Add a note" for yours (Q14: A).
- **The chat's context**: under each open task's steps, "note: …" (by him or you); the reminders in a list of their own after the settings.
- **His own messages**: `FelixPrompts.describe` adds "Notes on it: …" for the piece's task, so a slot-start message can say "you wanted to use the second source for this".
- **Micro-plans** (AI-15): the task's notes go with its instructions to the micro-planner, so a first action can take them in. Cheap, and worth it (Q14: A).
- **The widget and Day**: nothing; the sheet is the place.

## Standing notes (Q31: A)

You asked whether he can keep a note that goes beyond one assignment: "tell me to do Ms Peters' assignments on sheets, not in my book". Today that becomes a standing instruction in his memory, and the profile goes to every call (the chat, his slot messages, the readings that write a task's steps, the micro-planner), so he can abide by it in several places at once. Two weak links: no source names a teacher (Teams gives a class code, Power Planner a class id, Gmail a sender), so it works only once you've told him which class she teaches; and the link between the profile's line and the task in front of him is the model's to make each time, so it lands most times, not every time.

A standing note closes both: a note scoped to a class, subject, source or title pattern instead of one task, attached to every matching task by code. `Note.taskId` is optional beside `scope: NoteScope?` (`subject`, `className`, `source`, `titleContains`, each optional; a note has a task or a scope, never both); `Notes.forTask(task, notes, classMap)` (pure; `Settings.classMap` is an input, since a task carries only its source's class code and the subject is what the map makes of it) gives a task its own notes and every standing one that matches (a subject against the task's code through the map, a class against its code, a source against its source, a word against its title, case ignored); the schema's `notes` gains the four plain strings, `subject`, `className`, `source` and `titleContains` (no union); the prompt's note item says a note can be for a task or for every task of a class, subject or source; the sheet shows "Note for every chemistry task: …" with its delete; the context, his messages and the micro-planner take them as a per-task note, through `forTask`, so nothing downstream knows the difference. You'd still tell him once which class is hers ("Ms Peters is chemistry" is a fact he keeps). Half a day of Stage 8a.

## Pruning

A note on a task whose record is gone from `tasks.json` goes at the next prune once the delete can no longer be undone (a deleted own task's `Act` keeps the task for its Undo, and the notes and task-bound facts wait with it, so Undo brings the task back whole; Stage 7 keeps every finished task's record, so only an own task you deleted can be gone; the same rule as a task-bound fact's, [08](08-memory-that-expires.md)); a note on a finished task stays with its record and still shows on its sheet. Reminders sent or done go after 30 days; cancelled ones, and deleted notes, with their act's Undo window (their message's life). The store stays small.

## Built in

[03](03-stages.md) §Stage 8a: the commits in order, from the pure types to the docs.

## Tests

- `Reminders.next` and `Reminders.due`: an At before and after its time and snooze; a SlotStart with a slot today, tomorrow, none; a BeforeDue whose deadline moves; quiet hours per Q10.
- `ChatAnswers.parse`: each field, a time gone by refused, a slotStart without a task refused, the limits; an entry with an id moving a listed reminder, one with an unknown or sent id refused.
- A move's act and its Undo (the old due and words back); a deleted own task's notes kept until its delete can't be undone, back with the task on Undo.
- `SchemaLimitsTest` passes; `SchemaCompileTest` compiled by the API before the PR merges (one call a schema, a cent each).
- The acts: set, cancel, delete, each undone and not undone once changed (`ChatActs.canUndo`); a cancelled reminder never delivered and whole for its Undo; a deleted note the same.
- Drafting on creation: a reminder set three minutes ahead drafted at once, with no plan or look between.
- Delivery through a fake outbox: with a draft, the draft's words as his; without, your words verbatim marked `byApp`; the chips either way; late marked; a closed task's reminder let go with its note.
- Drafting through a fake caller: a reminder due within `AHEAD_MS` drafted once and not again while the draft fits; a snooze drops it; Claude off, resting or capped → no draft, and the sweep still sends.
- The sheet's model (`TaskSheetModel`): notes listed, added, deleted.
- The context: reminders and notes listed (`FelixInputsDocTest` regenerated with an example of each).
- The store in a backup and restored; Reset clears it.
- Standing notes: `Notes.forTask` by subject (through the class map), class code, source and title; none for a task that matches nothing; a subject with no mapping matches nothing; a note with both a task and a scope refused by the parse.

## Phone check

- `chat-ask --es text "remind me in 3 minutes to stretch"` (a paid call, and a second for the draft, made at once as it's within the window): the act under his reply; `felix` shows the reminder and its draft; three minutes on, his message in the chat and on the notification with its chips, in his words; *Snooze 15 min* from the notification moves it (no call; the draft goes, a new one is written near the time); *Done* ends it.
- The same with aeroplane mode on through the window (yours to switch): the reminder comes as your words, marked as the app's.
- `chat-ask --es text "note on <a task>: check the mark scheme first"`: on the sheet; in `chat-context`; in the next `felix-say --es reason SlotStart` about that task.
- `chat-ask --es text "for all my chemistry homework: do it on sheets, not in my book"`: the standing note's act under his reply; on every chemistry task's sheet; under each in `chat-context`; in a `felix-say --es reason SlotStart` about one of them.
- A `SlotStart` reminder on tomorrow's first slot: `plan` shows the slot; the alarm is set (`dumpsys alarm | grep decrastination`); the slot's start brings it.
- Phone off through a reminder's time: on, it comes, marked late.
