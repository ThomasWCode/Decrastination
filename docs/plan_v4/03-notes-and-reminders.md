# Notes and reminders

Felix keeps two new things at your asking: a **note** on a task, for himself and for you, read whenever the task comes up; and a **reminder**, delivered as his message at a time, at the start of a task's next slot, or a while before a task is due. Both are set from the chat, made at once with an Undo, kept in a store of their own, and cost no model call to deliver.

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
)

/** When a reminder is due: flat fields, as the schema is. */
@Serializable
data class Due(val kind: DueKind, val at: Long? = null, val minutesBefore: Int? = null)
enum class DueKind { At, SlotStart, BeforeDue }
```

- `At`: a time of day on a date ("at 7", "Friday morning" read as 09:00 by him, said in his reply).
- `SlotStart`: the start of the task's next work slot on or after a date (today, with none): "when I next sit down to the physics". If the plan has no slot for it, it waits; if the task closes first, it's let go and said.
- `BeforeDue`: `minutesBefore` the task's deadline in force (`TaskItem.deadlineAt`), following the deadline if that moves; none, it's let go and said.

`By` is the cleanup's Phase 4a enum (`You`, `Felix`); until 4a lands, it's defined here and 4a reuses it. A note from you comes from the task's sheet (Q14); from him, the chat.

The store: one file, `felix.json` (`FelixStore(notes, reminders, version = 1)`), owned by his service (cleanup 3f's Conversation, or a `felix/Reminders` class built like `Focus` until then), in the backup (format 7) and in Reset's "The chat and Felix's history" (Q12). Not on `TaskItem`: a merge rewrites tasks and must not be able to drop them; keyed by task id, which is stable across syncs and reopenings.

## Setting them from the chat

**The schema** (`ChatPrompts.SCHEMA`) gains two arrays of plain fields and two actions, so no union is added (`SchemaLimitsTest`); the API compiles it before the release (`SchemaCompileTest -PcheckSchemas=true`, D-87). The chat's schema was refused once as too large (3.1.4): if it is again, the fallback is to carry them as change types (`Remind`, `Note`) on the existing `CHANGE` object, whose fields already hold a task, a date, a time, a title and minutes, at no growth at all (Q13).

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

**What's sent.** A message of his, kind `Reminder`, `byApp = true`, its text as he set it, no model call (Q9): "Reminder: call gran" in his register as set ([05](05-his-voice.md)), with chips *Done*, *Snooze 15 min*, *Snooze 1 h* (Q11); through the outbox, so it's in the chat and on his notification. Where one of his own messages is due at the same moment (a `SlotStart` reminder at a slot's start, when his SlotStart look fires ten minutes later), the note is in `FelixPrompts.describe` for that piece ("Their reminder for this slot: …"), so he can fold it into his words; the reminder itself still goes at its time.

**Replies.** *Done* marks it `doneAt`; a snooze moves `snoozedTo`; any other words are a chat turn with the reminder quoted ([01](01-the-chat.md) §4). A `SlotStart` reminder whose slot moves before it fires moves with it; one whose task closes is let go with a `Note` message saying so.

**Quiet hours and busy events.** His own messages never come then (D-58). A reminder is yours: recommended that an `At` reminder comes at its time whatever the hour, as an alarm would, and that `SlotStart` and `BeforeDue` follow their slot or deadline, which the planner already keeps out of quiet hours (Q10). The alternative holds it to the quiet hours' end.

**The morning.** His Morning context lists today's reminders ("Reminders today: 16:00 dentist; 19:00 call gran"), so the briefing can mention them (Q9).

## Where notes show

- **The task's sheet**, About: "Notes" with each note's words, who wrote it and when, and a delete; "Add a note" for yours (Q14).
- **The chat's context**: under each open task's steps, "note: …" (by him or you); the reminders in a list of their own after the settings.
- **His own messages**: `FelixPrompts.describe` adds "Notes on it: …" for the piece's task, so a slot-start message can say "you wanted to use the second source for this".
- **Micro-plans** (AI-15): the task's notes go with its instructions to the micro-planner, so a first action can take them in. Cheap, and worth it (Q14).
- **The widget and Day**: nothing; the sheet is the place.

## Pruning

Notes of a task no longer in `tasks.json` go a fortnight after it went (the merge keeps finished tasks 14 days; cleanup 5b trims their text, not their record, so a note can still show on a finished task's sheet). Reminders sent or done go after 30 days; cancelled ones with their act's Undo window (their message's life). The store stays small.

## Tests

- `Reminders.next` and `Reminders.due`: an At before and after its time and snooze; a SlotStart with a slot today, tomorrow, none; a BeforeDue whose deadline moves; quiet hours per Q10.
- `ChatAnswers.parse`: each field, a time gone by refused, a slotStart without a task refused, the limits.
- `SchemaLimitsTest` passes; `SchemaCompileTest` compiled by the API before the PR merges (one call a schema, a cent each).
- The acts: set, cancel, delete, each undone and not undone once changed (`ChatActs.canUndo`).
- Delivery through a fake outbox: the message's kind, text, chips; late marked; a closed task's reminder let go with its note.
- The sheet's model (`TaskSheetModel`): notes listed, added, deleted.
- The context: reminders and notes listed (`FelixInputsDocTest` regenerated with an example of each).
- The store in a backup and restored; Reset clears it.

## Phone check

- `chat-ask --es text "remind me in 3 minutes to stretch"` (a paid call): the act under his reply; `felix` shows the reminder; three minutes on, his message in the chat and on the notification with its chips; *Snooze 15 min* from the notification moves it (no call); *Done* ends it.
- `chat-ask --es text "note on <a task>: check the mark scheme first"`: on the sheet; in `chat-context`; in the next `felix-say --es reason SlotStart` about that task.
- A `SlotStart` reminder on tomorrow's first slot: `plan` shows the slot; the alarm is set (`dumpsys alarm | grep decrastination`); the slot's start brings it.
- Phone off through a reminder's time: on, it comes, marked late.
