# Notes and reminders

Felix keeps two new things at your asking: a **note** on a task, for himself and for you, read whenever the task comes up; and a **reminder**, delivered as his message at a time, at the start of a task's next slot, or a while before a task is due. Both are set from the chat, made at once with an Undo, and kept in a store of their own. A reminder is delivered in his words for the moment it's due, written just before (Q9: B; Q24), or as you set it when he can't write; a note costs no call.

## What they are

```kotlin
/** A note on a task (4.1.0): its words, who wrote them, and when; shown on the task's sheet and told to Felix with the task. */
@Serializable
data class Note(val id: String, val taskId: String, val text: String, val at: Long, val by: By /* You | Felix */, val deletedAt: Long? = null)

/** Something to say at a moment (4.1.0): the words as set, when, and about which task if any. */
@Serializable
data class Reminder(
    val id: String,
    val text: String,
    val at: Long,                 // when it was set
    val by: By,
    val taskId: String? = null,
    val due: Due,
    val sentAt: Long? = null,     // delivered
    val snoozedTo: Long? = null,  // a snooze moves its time
    val doneAt: Long? = null,     // Done from its message, or cancelled
    val draft: FelixAnswer? = null, val draftedAt: Long? = null,  // his words for it, written ahead (Q24)
)

/** When a reminder is due: flat fields, as the schema is. */
@Serializable
data class Due(val kind: DueKind, val at: Long? = null, val minutesBefore: Int? = null)
enum class DueKind { At, SlotStart, BeforeDue }
```

- `At`: a time of day on a date ("at 7", "Friday morning" read as 09:00 by him, said in his reply).
- `SlotStart`: the start of the task's next work slot on or after a date (today, with none): "when I next sit down to the physics". If the plan has no slot for it, it waits; if the task closes first, it's let go and said.
- `BeforeDue`: `minutesBefore` the task's deadline in force (`TaskItem.deadlineAt`), following the deadline if that moves; none, it's let go and said.

`By` is the cleanup's Phase 4a enum (`You`, `Felix`); until 4a lands, it's defined here and 4a reuses it. A note from you comes from the task's sheet (Q14: A); from him, the chat.

The store: one file, `felix.json` (`FelixStore(notes, reminders, version = 1)`), owned by his service (cleanup 3f's Conversation, or a `felix/Reminders` class built like `Focus` until then), in the backup (format 7) and in Reset's "The chat and Felix's history" (Q12: A). Not on `TaskItem`: a merge rewrites tasks and must not be able to drop them; keyed by task id, which is stable across syncs and reopenings.

## Setting them from the chat

**The schema** (`ChatPrompts.SCHEMA`) gains two arrays of plain fields and two actions, so no union is added (`SchemaLimitsTest`); the API compiles it before the release (`SchemaCompileTest -PcheckSchemas=true`, D-87). The chat's schema was refused once as too large (3.1.4): if it is again, the fallback is to carry them as change types (`Remind`, `Note`) on the existing `CHANGE` object, whose fields already hold a task, a date, a time, a title and minutes, at no growth at all (Q13: A, with B in the same PR if the API refuses).

```text
reminders: [{ text, date (YYYY-MM-DD or empty), time (HH:MM or empty), taskId (or empty), when: "at" | "slotStart" | "beforeDue", minutesBefore (-1 for none) }]
notes:     [{ taskId, text }]
actions:   type gains "cancelReminder" (taskId empty, value the reminder's id) and "deleteNote" (value the note's id)
```

**The prompt** (one item each, in the chat's numbered list, the wording in [05](05-his-voice.md)'s register):

- Remind (reminders): when they ask to be reminded of something, that's a reminder, not a task: its words in theirs, as you'd say them then; at a date and time; or at the start of their next slot on a task (when: slotStart, taskId); or a while before a task's due (beforeDue, minutesBefore). Say when it'll come. Not for work to plan, which is a task.
- Note (notes): something to keep with a task for when it comes up (what the teacher said, which source to use, what they've decided): taskId and the words. Not a reminder, and not a fact about them.
- The context lists the reminders set (id, when, words, task) and each open task's notes under its steps, so he answers "what have I asked you to remind me of?" and avoids setting one twice.

**Checks** (`ChatAnswers.parse`): a reminder's time from an hour ago to a year ahead (one gone by is refused and said); `slotStart` and `beforeDue` need a listed open task; the text up to 300 characters; at most 10 reminders and 10 notes a message; a note's task listed. Limits in the store: 50 reminders not yet sent, 10 notes a task (the oldest noted). What's refused is said under his reply, as changes are.

**Made at once.** Each reminder and note set is an `Act` with its Undo line ("Reminder set: Friday 19:00, “call gran”"; "Noted on “Vectors”: use the second source"), undone while it's as he left it (not sent, not deleted). `cancelReminder` and `deleteNote` are acts too, with Undo bringing the thing back.

**From the instructions screen** (*Your changes*, `InstructionPrompts`): not planned. The screen reads your own words into changes to tasks; reminders are the chat's. If Q13's fallback is taken (change types), they come to it for free.

## Delivery

**When.** `Reminders.next(state, plan, now)` (pure) gives the earliest moment any reminder is due: an `At`'s time (or its snooze), a `SlotStart`'s next slot start from the plan, a `BeforeDue`'s deadline less its minutes. One exact alarm is set to it (`SlotAlarms.armReminder`, as `armFelix` is, `USE_EXACT_ALARM` is already declared), set again after every plan (a `PlanWatcher` hook beside `draftAhead`) and after every change to the store. A sweep at app start and at each of his looks sends any gone by (the phone off), late and marked so ("(late: it was for 19:00)").

**Written ahead, sent on time** (Q9: B; Q24, decided live on 18 Oct). A reminder due within `FelixRules.AHEAD_MS` (ten minutes) is drafted as his slot messages are (`Felix.draft`, run by `Ahead.run` after every plan and by each of his looks): `FelixPrompts.Reason.Reminder`, with `describe` given the reminder's words as you set them (quoted, so they're in what he says), the task's text and notes where it has a task, the slot under way or next, today's kept and missed, his recent messages, and the time it's for; written as of that moment, under `MAX_MESSAGE` (240 characters), in his register ([05](05-his-voice.md)): "oi, you said to remind you: call gran. you're between slots now, so good time". The draft is kept on the reminder (`draft`, `draftedAt`), one at a time. At the time, the sweep sends the draft as a message of his, kind `Reminder`, `about` its task, with chips *Done*, *Snooze 15 min*, *Snooze 1 h* (Q11: A), through the outbox, so it's in the chat and on his notification. No draft by then (no network, Claude off or resting, over the cap, the call failed or was refused, the phone off through the window) and your words go instead, verbatim, as a `byApp` message, "reminder: call gran" (`FelixWords.reminder`), with the same chips: never nothing. A snooze drops the draft, and the new time is drafted ahead again. Each draft is one call (`ModelEffort.Job.Felix`, a few cents, under the monthly cap). Where one of his own messages is due at the same moment (a `SlotStart` reminder at a slot's start, when his SlotStart look fires ten minutes later), the reminder is in `FelixPrompts.describe` for that piece ("Their reminder for this slot: …"), so he doesn't say it twice.

**Replies.** *Done* marks it `doneAt`; a snooze moves `snoozedTo`; any other words are a chat turn with the reminder quoted ([01](01-the-chat.md) §4). A `SlotStart` reminder whose slot moves before it fires moves with it; one whose task closes is let go with a `Note` message saying so.

**Quiet hours and busy events** (Q10: A). His own messages never come then (D-58). A reminder is yours: an `At` reminder comes at its time whatever the hour, as an alarm would; `SlotStart` and `BeforeDue` follow their slot or deadline, which the planner already keeps out of quiet hours. The draft is written in quiet hours too (a call, not a message).

**The morning.** His Morning context lists today's reminders ("Reminders today: 16:00 dentist; 19:00 call gran"), so the briefing can mention them, at no call of its own.

## Where notes show

- **The task's sheet**, About: "Notes" with each note's words, who wrote it and when, and a delete; "Add a note" for yours (Q14: A).
- **The chat's context**: under each open task's steps, "note: …" (by him or you); the reminders in a list of their own after the settings.
- **His own messages**: `FelixPrompts.describe` adds "Notes on it: …" for the piece's task, so a slot-start message can say "you wanted to use the second source for this".
- **Micro-plans** (AI-15): the task's notes go with its instructions to the micro-planner, so a first action can take them in. Cheap, and worth it (Q14: A).
- **The widget and Day**: nothing; the sheet is the place.

## Pruning

Notes of a task no longer in `tasks.json` go a fortnight after it went (the merge keeps finished tasks 14 days; cleanup 5b trims their text, not their record, so a note can still show on a finished task's sheet). Reminders sent or done go after 30 days; cancelled ones with their act's Undo window (their message's life). The store stays small.

## Implementation (4.1.0-D)

One PR, the commits in this order, each with its tests:

1. **The types and the store.** `core/Notes.kt` and `core/Reminders.kt` (pure): `Note`, `Reminder`, `Due`, `DueKind`, `By` (unless the cleanup's 4a has it); `Reminders.next`, `due`, `snoozed`, `prune`; `Notes.prune`. `FelixStore(notes, reminders, version = 1)` in `data/FelixStore.kt`, read by `JsonStore` as `felix.json` from `AppGraph` (from `Stores` after the cleanup's 3a).
2. **The schema and the prompt.** `ChatPrompts.SCHEMA` gains `reminders` and `notes` (arrays of flat strings and ints, `""` and `-1` for an unused field, as 3.1.6 shaped the lists); `actions.type` gains `cancelReminder` and `deleteNote`; items 8 and 9 in `ChatPrompts.system`; `ChatPrompts.context` takes the reminders (listed after the settings) and each task's notes (under its steps). Goldens rewritten; `SchemaLimitsTest`; `SchemaCompileTest -PcheckSchemas=true` run, its result in the PR. Refused: the Q13 fallback in the same commit (`ChangeType.Remind` and `ChangeType.Note` on `Change`, read into the same `Reading` fields, so nothing after this step changes).
3. **The parse.** `ChatAnswers.Reading.reminders` and `notes` with the checks (a time gone by, a task not listed, the lengths, the counts); what's refused into `leftOut`.
4. **The acts.** `AppGraph.answerChat` → `act` makes them: `Act` lines ("Reminder set: Fri 24 Oct 19:00, “call gran”", "Noted on “Vectors”: use the second source"); `ChatAction.Type.SetReminder`, `CancelReminder`, `AddNote`, `DeleteNote`, each with `undoAct`'s reverse and `ChatActs.canUndo` false once the thing's sent, snoozed, deleted or changed since.
5. **The alarm and the sweep.** `SlotAlarms.armReminder(context, at)` (request code 4, `ACTION_REMINDER`), set from `AppGraph`'s plan hooks beside `SlotAlarms.arm` and after every change to the store; `SlotReceiver` → `felix.sweepReminders(now)`: each due one sent through the outbox (its draft, or the verbatim words), `sentAt` set, late marked; the sweep also at app start and in `Felix.check`.
6. **The drafting.** `FelixPrompts.Reason.Reminder` and its `describe` branch (the words as set, the task's text and notes, the plan now, the time); `Felix.draft` extended: after a slot draft, each reminder due within `AHEAD_MS` without a fresh draft, one call each; a snooze or a cancel clears the draft. `felix.txt` and `felix-inputs.md` regenerated.
7. **The replies.** `Replies.route` for kind `Reminder` ([01](01-the-chat.md) §4): *Done* → `doneAt`; the two snoozes → `snoozedTo` and the alarm re-armed; anything else a chat turn with the quote.
8. **Notes shown.** `TaskSheet`'s About lists them with a delete and "Add a note" (a text field in a sheet, `By.You`); `FelixPrompts.describe` adds "Notes on it: …" and "Their reminder for this slot: …"; `Ahead.microPlans` passes the task's notes with its instructions in `MicroPlanner.plan`'s user text.
9. **Kept and cleared.** The backup (`Backup.felixStore`, format 7, checked by `Backups.problem`); Reset → "The chat and Felix's history" clears it; `DebugActivity`'s stores page and the `felix` command show it; pruning in the nightly run (`Daily`).
10. **The docs**: `felix.md` §What he can do and §His messages; `felix-inputs.md` with a reminder's and a note's example; `decisions.md` D-96.

## Tests

- `Reminders.next` and `Reminders.due`: an At before and after its time and snooze; a SlotStart with a slot today, tomorrow, none; a BeforeDue whose deadline moves; quiet hours per Q10.
- `ChatAnswers.parse`: each field, a time gone by refused, a slotStart without a task refused, the limits.
- `SchemaLimitsTest` passes; `SchemaCompileTest` compiled by the API before the PR merges (one call a schema, a cent each).
- The acts: set, cancel, delete, each undone and not undone once changed (`ChatActs.canUndo`).
- Delivery through a fake outbox: with a draft, the draft's words as his; without, your words verbatim marked `byApp`; the chips either way; late marked; a closed task's reminder let go with its note.
- Drafting through a fake caller: a reminder due within `AHEAD_MS` drafted once and not again while the draft fits; a snooze drops it; Claude off, resting or capped → no draft, and the sweep still sends.
- The sheet's model (`TaskSheetModel`): notes listed, added, deleted.
- The context: reminders and notes listed (`FelixInputsDocTest` regenerated with an example of each).
- The store in a backup and restored; Reset clears it.

## Phone check

- `chat-ask --es text "remind me in 3 minutes to stretch"` (a paid call, and a second for the draft, made at once as it's within the window): the act under his reply; `felix` shows the reminder and its draft; three minutes on, his message in the chat and on the notification with its chips, in his words; *Snooze 15 min* from the notification moves it (no call; the draft goes, a new one is written near the time); *Done* ends it.
- The same with aeroplane mode on through the window (yours to switch): the reminder comes as your words, marked as the app's.
- `chat-ask --es text "note on <a task>: check the mark scheme first"`: on the sheet; in `chat-context`; in the next `felix-say --es reason SlotStart` about that task.
- A `SlotStart` reminder on tomorrow's first slot: `plan` shows the slot; the alarm is set (`dumpsys alarm | grep decrastination`); the slot's start brings it.
- Phone off through a reminder's time: on, it comes, marked late.
