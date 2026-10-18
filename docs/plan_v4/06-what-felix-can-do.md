# What Felix can do, and what he should

Your question: to what extent can he act as someone you're texting would, with the control he has? Can you tell him to remind you of something at a time, or to put a notification up at the start of a task in a few days? This document answers it from the code as it is, names what's missing, and says which of the gaps this plan closes and how. The aim you gave: it should seem that he can manage it all, and that nothing but the chat needs touching to change things. The gaps are built in Stage 8b ([03](03-stages.md)); the rest of this plan's features in the stages their documents name.

## 1. What he can do today

From the chat (`ChatPrompts.system`, `ChatAnswers.parse`, `AppGraph.answerChat`), everything made at once with an Undo (D-60):

- **Add tasks of your own** (`newTasks`): title, kind, subject, due date and time, start, minutes, notes, steps.
- **Change tasks and days** (`changes`, the instructions' 15 types): not a task; can't start before; waits for another; due by; minutes (paced or yours); how much of your time an event takes; a day's limit; a busy stretch; days off; a half-day; all of it on a day; its next piece at a time; the same as another; its steps; its name.
- **Act** (`actions`): tick or untick a step; finish, reopen or delete one of your own; show a task; set one of the 22 settings about the work and about him (`FelixSettings`).
- **Remember**: facts by topic, standing instructions, forget either.
- **Answer** from what he's given: the open tasks with steps, minutes and their pace; the fortnight's events; the changes in use; your own finished lately; each task's text (D-93), the one you asked about in full; the plan's next 14 days with why each piece is where it is; today's slots as past, now or ahead.
- **Look things up** on the web (three searches, a call of its own) and **read a task in full** before answering.
- **Offer replies** (three chips).

On his own (`FelixRules`): a slot started with nothing done, a slot missed or ticked without its photo, a kept slot now and then, the day done, the morning, a reading's question, the evening's completions; the Sunday check-in in fixed words.

## 2. What you'd say to a friend with his access

Each row: what you'd text; what happens today; what this plan makes of it, and where. ✓ is "works today".

### Reminders and notes

| You'd say | Today | After |
|---|---|---|
| "Remind me at 7 to call gran" | a task of your own, due 19:00 (the empty chat's own example suggests this) | a reminder at 19:00, his message written for that moment just before it, or your words when he can't write ([07](07-notes-and-reminders.md); Q9: B, Q24) |
| "Remind me about the form when I next sit down to the physics" | nothing of the kind | a reminder at that task's next slot's start |
| "Before the essay's due, remind me to print it" | nothing | a reminder a set time before the task's deadline |
| "Make a note on the essay: use the second source" | nothing; `notes` exist only on a task of your own, through the form | a note on any task, shown on its sheet and told to him whenever the task comes up |
| "Tell me to do Ms Peters' assignments on sheets, not in my book" | a standing instruction in his memory, which every prompt sees; no source names a teacher, so only once you've said which class is hers, and the link to the task in front of him is the model's to make | the same, or a standing note the app attaches to every matching task by code, if Q31 is A ([07](07-notes-and-reminders.md) §Standing notes) |
| "What have I asked you to remind me of?" | nothing | listed in his context; he answers |
| "Cancel that" / "push it to 8" | nothing | an action on the reminder; a new time is a change to it |
| "Remind me again in 20 minutes" (from a reminder) | nothing | Snooze, a chip on the reminder, no call; the message at the new time written again |

### Memory

| You'd say | Today | After |
|---|---|---|
| "I'm away Thursday to Sunday" | ✓ a NoSchool or BusyTime change | ✓ |
| "I've got a cold this week" | a fact, kept till the weekly tidy drops it | a fact until Sunday ([08](08-memory-that-expires.md)) |
| "Don't message me tonight" | a standing instruction, for ever | a rule until the morning |
| "While the trip's on, I'm not doing German" | a fact or a change, for ever | a fact tied to the trip's task, gone with it |
| "Forget that" | ✓ | ✓ |
| "What do you know about me?" | ✓ (the profile) | ✓, with untils shown |

### The work

| You'd say | Today | After |
|---|---|---|
| "Add / move / rename / split / that's not a task / it's the same as…" | ✓ | ✓ |
| "I've done step 2" | ✓ `tickStep` | ✓ |
| "I've done today's bit of the essay" (a task with no steps, planned in boxes) | ✗: `tick` finds pieces by step only | a `keepPiece` action: the slot kept as Day's Done keeps it (gap G1) |
| "Actually I haven't done it" | ✓ for a step; ✗ for a box | `unkeepPiece` (G1) |
| "I'm done with that email" | ✗: "done when Gmail says so" | finished: unstarred and archived ([10](10-emails-done.md)) |
| "I handed it in, check Teams" / "I've just starred something" | ✗ | a `sync` action for one source, and a note from him once it's read (G2) |
| "Open it in Teams" | the task's sheet opens (`open`) | `open` with `where = source`: Teams, Power Planner, Gmail or AnkiDroid, as the sheet's Open does (G3) |
| "Add the invite to my calendar" | ✗ (the sheet's button) | a task of your own of kind Event is what he can do today; the calendar app's new-event form is a screen's job (not planned) |

### Questions

| You'd say | Today | After |
|---|---|---|
| "What's due this week / what's on Tuesday / why isn't X planned / what does the email say / who's it from" | ✓ | ✓ |
| "How much free time have I got?" / "Why am I blocked?" | ✗: the chat's context has no free time and no blocking state (his own messages get free time for SlotKept and DayDone only) | one line in the context: free time now, whether blocking applies and why (`Focus.rewardLine`'s facts) (G5) |
| "How did this week go?" | ✗: no stats in his context | not now (G6, not taken in Q17): the week's numbers would come through a `details`-like round, a later release |
| "What's on next month?" | the fortnight's events only | not now (G7, not taken in Q17) |
| "When will I be done tonight?" | ✓ (today's slots) | ✓ |
| "You said Thursday for the essay, didn't you?" (from last week) | ✗: the last 12 messages | not now: the conversation digest, 4.4.0 ([13](13-summaries.md)) |

### Him, and the app

| You'd say | Today | After |
|---|---|---|
| "Call yourself Fe" / "be blunter" / "check on me sooner" | ✓ (`felixName`, a rule, `felixGraceMin`) | ✓ |
| "God isn't my chemistry teacher annoying with the homework" | a reply, and sometimes a change or a memory he wasn't asked for | a reply and nothing else ([09](09-his-voice.md) §Just talking) |
| "Stop blocking YouTube" / "turn protection off" / "change the key" | never (D-61) | never |
| "Back up now" / "reset the chat" / "pick a calendar" | never | never: the phone's hands, Settings |
| "Here's a photo of it" | ✗ (the sheet's photo check) | not planned; noted in §4 |

## 3. Closing the gaps

Each is small, and most are one new action in the chat's schema plus a line of context. Taken (Q17): G1, G2 as the stop, G3 and G5; G6 and G7 wait for a later release. Built in Stage 8b, after 8a settles the schema's shape, so one compile by the API covers both.

- **G1. Keep and unkeep a piece.** `keepPiece` (taskId, and the piece's key as the plan lists it) → `Focus.keepSlot`; `unkeepPiece` → `Focus.unkeepSlot`. The context's plan already names each piece; the task list gains each open task's next piece key where it has no steps. Undo as a tick's.
- **G2. Check a source.** `sync` (source) → the Sync service's `syncNow(sources)` (Stage 6d); his reply says it's reading, and a `Syncer` listener in the Conversation posts a `Note` message when the read changes the task he was asked about ("teams says the worksheet's handed in"). The stop, not the round: a second call would be dearer and the note says what the round would.
- **G3. Open where it lives.** `open` gains `where`: "app" (the sheet, as now) or "source" (`TaskOpener.open`, which already tells him it was opened).
- **G5. Free time and blocking in his context.** A line after the plan's "Now:": "Blocking: on until today's work's done (2 pieces, 1 h); free time 12 min" from `Focus`'s state, which `ChatPrompts.plan` is given.
- **G6. The week's numbers** (not now). When your message is about the week (he asks for them as he asks to read a task: `details` with "stats"), a second round with `Stats`' period summary (kept minutes, completions, deadlines met, slots kept and missed, by subject) in 20 lines. Or two lines always, cheaper in rounds and dearer in tokens.
- **G7. The calendar further out** (not now). Events to the plan's reach (90 days) when the message names a month.

Not closed, by design: anything D-61 forbids; anything needing the camera or a file picker; the calendar's choice; Setup.

## 4. The chat as the control surface

What makes it seem he manages it all, beyond the abilities:

- **His button shows unread** ([05](05-the-chat.md) §4), so the chat is where news is.
- **The empty chat's examples** say what's new: "Remind me at 7 to…", "Note on the essay: …", "I'm done with the trip email".
- **"Your changes"** stays as the list to take back, one tap from the chat (Q20); the aim says nothing but the chat needs touching, and the tab is part of the chat's screen. The quick changes on a task's sheet (without Claude) stay too: they're the no-cost route.
- **A notification is the chat**: replying from it is the same as typing in it ([05](05-the-chat.md) §5).
- **Undo in his words**: he already undoes by giving what puts a thing back; with reminders and notes, "cancel that" and "delete the note" are actions with their own Undo.
- **Just talking is answered as talk**: a message with nothing to do gets his reply and nothing else, no card and no fold ([09](09-his-voice.md) §Just talking).

Ideas in the same direction, not planned, noted so they aren't lost: a photo sent in the chat as a photo check of the piece under way or a photo of homework (NF-01); *Undo all of it* on a message; the widget's tap opening the chat with the piece as its subject; a message to him from the lock screen's quick reply with no notification showing (an Android shortcut). Each is a feature of its own, asked about when wanted.

## Built in

[03](03-stages.md) §Stage 8b: the commits, one a gap, each with its test, prompt line and golden; the schema compiled by the API before the merge.

## Tests

- Each action's parse (`ChatAnswers.action`: a key the plan doesn't list refused; a source named wrong refused), its act and its Undo (`ChatActs.canUndo` false once the slot's record changed).
- The `Syncer` listener: a `Note` message only when the asked-about task changed; none for an unrelated read.
- The context's blocking line (`FelixInputsDocTest` regenerated with it).
- `SchemaLimitsTest`; `SchemaCompileTest -PcheckSchemas=true` before the merge.

## Phone check

- `chat-ask` for each (paid): "I've done today's bit of the essay" (the slot kept on Day, Undo takes it back), "check Teams" (a sync runs; a note from him if the task changed), "how much free time have I got" (the line's facts in his answer), "open it in Teams" (Teams opens; `felix` shows the activity noted).
