# What Felix can do, and what he should

Your question: to what extent can he act as someone you're texting would, with the control he has? Can you tell him to remind you of something at a time, or to put a notification up at the start of a task in a few days? This document answers it from the code as it is, names what's missing, and says which of the gaps this plan closes and how. The aim you gave: it should seem that he can manage it all, and that nothing but the chat needs touching to change things.

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
| "Remind me at 7 to call gran" | a task of your own, due 19:00 (the empty chat's own example suggests this) | a reminder at 19:00, his message then ([03](03-notes-and-reminders.md)) |
| "Remind me about the form when I next sit down to the physics" | nothing of the kind | a reminder at that task's next slot's start |
| "Before the essay's due, remind me to print it" | nothing | a reminder a set time before the task's deadline |
| "Make a note on the essay: use the second source" | nothing; `notes` exist only on a task of your own, through the form | a note on any task, shown on its sheet and told to him whenever the task comes up |
| "What have I asked you to remind me of?" | nothing | listed in his context; he answers |
| "Cancel that" / "push it to 8" | nothing | an action on the reminder; a new time is a change to it |
| "Remind me again in 20 minutes" (from a reminder) | nothing | Snooze, a chip on the reminder, no call |

### Memory

| You'd say | Today | After |
|---|---|---|
| "I'm away Thursday to Sunday" | ✓ a NoSchool or BusyTime change | ✓ |
| "I've got a cold this week" | a fact, kept till the weekly tidy drops it | a fact until Sunday ([04](04-memory-that-expires.md)) |
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
| "I'm done with that email" | ✗: "done when Gmail says so" | finished: unstarred and archived ([06](06-emails-done.md)) |
| "I handed it in, check Teams" / "I've just starred something" | ✗ | a `sync` action for one source, and he answers once it's read (G2) |
| "Open it in Teams" | the task's sheet opens (`open`) | `open` with `where = source`: Teams, Power Planner, Gmail or AnkiDroid, as the sheet's Open does (G3) |
| "Add the invite to my calendar" | ✗ (the sheet's button) | a task of your own of kind Event is what he can do today; the calendar app's new-event form is a screen's job (not planned) |

### Questions

| You'd say | Today | After |
|---|---|---|
| "What's due this week / what's on Tuesday / why isn't X planned / what does the email say / who's it from" | ✓ | ✓ |
| "How much free time have I got?" / "Why am I blocked?" | ✗: the chat's context has no free time and no blocking state (his own messages get free time for SlotKept and DayDone only) | one line in the context: free time now, whether blocking applies and why (`Focus.rewardLine`'s facts) (G5) |
| "How did this week go?" | ✗: no stats in his context | the week's numbers in his context when you ask about the week, through the `details`-like round (G6), or always as two lines (Q17) |
| "What's on next month?" | the fortnight's events only | events to the plan's reach, named by month when asked (G7, optional) |
| "When will I be done tonight?" | ✓ (today's slots) | ✓ |

### Him, and the app

| You'd say | Today | After |
|---|---|---|
| "Call yourself Fe" / "be blunter" / "check on me sooner" | ✓ (`felixName`, a rule, `felixGraceMin`) | ✓ |
| "Stop blocking YouTube" / "turn protection off" / "change the key" | never (D-61) | never |
| "Back up now" / "reset the chat" / "pick a calendar" | never | never: the phone's hands, Settings |
| "Here's a photo of it" | ✗ (the sheet's photo check) | not planned; noted in §3 |

## 3. Closing the gaps

Each is small, and most are one new action in the chat's schema plus a line of context. Which to take is Q17.

- **G1. Keep and unkeep a piece.** `keepPiece` (taskId, and the piece's key as the plan lists it) → `Focus.keepSlot`; `unkeepPiece` → `Focus.unkeepSlot`. The context's plan already names each piece; the task list gains each open task's next piece key where it has no steps. Undo as a tick's.
- **G2. Check a source.** `sync` (source) → `SyncWorker.syncNow(sources)`; his reply says it's reading, and the message is asked again when the read's done (a round, as a web search is), or he's told to say "I've asked Teams to read; it'll show in a minute" and stop. The round is dearer (a second call); the stop is simpler. Recommended: the stop, with a `byApp` Note message when the read changes the task ("Teams says the worksheet's handed in").
- **G3. Open where it lives.** `open` gains `where`: "app" (the sheet, as now) or "source" (`TaskOpener.open`).
- **G5. Free time and blocking in his context.** A line after the plan's "Now:": "Blocking: on until today's work's done (2 pieces, 1 h); free time 12 min" from `Focus`'s state, which `ChatPrompts.plan` is given.
- **G6. The week's numbers.** When your message is about the week (he asks for them as he asks to read a task: `details` with "stats"), a second round with `Stats`' period summary (kept minutes, completions, deadlines met, slots kept and missed, by subject) in 20 lines. Or two lines always, cheaper in rounds and dearer in tokens.
- **G7. The calendar further out.** Optional: events to the plan's reach (90 days) when the message names a month.

Not closed, by design: anything D-61 forbids; anything needing the camera or a file picker; the calendar's choice; Setup.

## 4. The chat as the control surface

What makes it seem he manages it all, beyond the abilities:

- **His button shows unread** ([01](01-the-chat.md) §3), so the chat is where news is.
- **The empty chat's examples** say what's new: "Remind me at 7 to…", "Note on the essay: …", "I'm done with the trip email".
- **"Your changes"** stays as the list to take back, one tap from the chat (Q20); the aim says nothing but the chat needs touching, and the tab is part of the chat's screen. The quick changes on a task's sheet (without Claude) stay too: they're the no-cost route.
- **A notification is the chat**: replying from it is the same as typing in it ([01](01-the-chat.md) §4).
- **Undo in his words**: he already undoes by giving what puts a thing back; with reminders and notes, "cancel that" and "delete the note" are actions with their own Undo.

Ideas in the same direction, not planned, noted so they aren't lost: a photo sent in the chat as a photo check of the piece under way or a photo of homework (NF-01); *Undo all of it* on a message; the widget's tap opening the chat with the piece as its subject; a message to him from the lock screen's quick reply with no notification showing (an Android shortcut). Each is a feature of its own, asked about when wanted.
