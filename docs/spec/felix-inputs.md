# What Felix is sent

Everything the app sends the model as Felix, call by call, and how it changes with the circumstances. How he behaves is in [felix.md](felix.md).

The exact prompts and schemas are the golden copies in [`app/src/test/resources/prompts/`](../../app/src/test/resources/prompts/), which `GoldenPromptsTest` holds the code to; the prompts below are those copies, with his default name and line. Every other example here is made up, as the repository is public, but each was made by the app's own code from that made-up data, so its shape is exact.

`FelixInputsDocTest` writes every example and prompt here, each into the fence after its `<!-- generated: … -->` or `<!-- golden: … -->` mark, and fails when one is out of date. After a change to what Felix is sent, `./gradlew testDebugUnitTest --tests '*FelixInputsDocTest*' -PupdateDocs=true` writes them again; the words around them are edited here.

## The calls

| Call | When | Sent | Answer |
|---|---|---|---|
| [The chat](#the-chat) | you send a message, or reply to his notification | the chat's prompt, the profile, the conversation, and with your newest message what the app knows, each task's own text and what the message is about, in full | a reply, and what to make: tasks, changes, actions, memories |
| [A web search](#a-web-search) | the chat's answer asks to look something up, with searches on | the search's prompt, what to look up, the web search tool | what it found, in words |
| [His own messages](#his-own-messages) | around today's slots, in the morning, for a question, for the evening's completions | his prompt, the profile, one message saying why and what's on | a message, replies, when to look again, memories |

Messages in his name that no call writes, and what becomes of your replies, are [at the end](#in-his-name-but-not-his-words).

## Every call

- **Model**: `claude-opus-5-5`, with the API's fallback to another model where it must (`fallbacks: default`, beta `server-side-fallback-2026-07-01`); the answer is credited to the model that gave it (D-24).
- **Effort**: medium, as for every answer someone waits for (AC-02, `ModelEffort`); at most 16,000 tokens out.
- **System**: two blocks, the call's own prompt (cached for an hour), then [the profile](#the-profile) (not cached). The web search has no profile.
- **Input**: at most 40,000 characters (`Pricing.MAX_INPUT_CHARS`), as the cap's dearest call assumes.
- **Answer**: JSON in a fixed schema for the chat and his own messages, which the API compiles into a grammar that holds the answer to it as it's written ([readings.md](readings.md#the-prompts-version-ac-06)); the search answers in words.
- **Not asked at all** (`ModelHold`): with Claude off or no key; with this month's cap reached, where one is set; and while the model rests after a failure (an hour for most, AC-04). A rest holds what runs by itself (his morning message, one written ahead); the chat, a web search and a message of his due now are asked through it, as someone's waiting, unless the key is what failed.

## The profile

Who you are, the second system block of the chat and of his own messages (AI-05, `Profile.block`):
- your year group (Year 12 from the September you set, 13 the next) and your subjects: the class names you've given, then the codes on open work you haven't named;
- the school day from the timetable: lessons, study periods, break and lunch;
- your working hours, and the next three spells of no school;
- your German textbook;
- what you've written in Settings → About you;
- your standing instructions to him (up to 20), under "How Felix should behave";
- what he remembers, the newest first, within its 6,000 characters.

The part before the standing instructions is at most 2,000 characters, cut from the end (your own lines first), and the whole at most 16,000. For example:

<!-- generated: profile -->
```text
About the student
Year 12 at a UK sixth form. Subjects: Physics (12X/Ph1), Maths (12X/Ma1), Chemistry (12X/Ch1).
School days: break 10:45–11:15; lunch 13:15–14:35 (eating 13:40–13:55).
Works school days 16:45–22:00, weekends and days off 08:30–22:30. No school: 22 Oct–23 Oct (Training days).
German textbook 1.
From the student:
- I work best straight after school.
How Felix should behave:
- Don't message before 8 in the morning
Remembered:
- football: Training is on Tuesdays
```

## The chat

### When it's called

- You send a message on the chat's tab.
- You reply to a message, from his notification (AI-24), his chips, or a swipe or *Reply* in the chat, all one way in (D-98): your reply goes into the chat under the message it answers, but for an answer the app takes with no call ([below](#in-his-name-but-not-his-words)).
- A message waiting is asked again: *Try again*, the chat opened again, or the model back from a rest.

One answer at a time: a message sent while he answers another of yours lets that answer go, and he answers both together (D-98). While the model can't be asked (Claude off, resting, the cap), your message says why and waits.

### What's sent

1. **System**: [the chat's prompt](#the-chats-prompt), then the profile.
2. **The conversation**: its last 12 messages (yours, and his answers to you) and his last two nudges (what he wrote unasked) with your replies to them, in their order, starting with one of yours (D-97); a nudge before any of yours goes with your first turn, and a reply that went to no call (a check-in's answer) only with its nudge. Each earlier answer of his carries what it made and what became of it ("[Suggested: … — made at once]", "— undone by the student", "— not made: …"). A message of yours that replies to one that isn't just before it starts with that one, quoted from the whole chat, as the window may not hold it ("(Replying to your message: “…”)", or "(Replying to their own earlier message: “…”)"); a message the app wrote in his name says so ("(the app wrote this for you)").
3. **Your newest message**, last, holding in this order:
   1. what the app knows now ([the context](#the-context)), each open task's own text last;
   2. the plan, as the Day and Work tabs show it ([the plan](#the-plan));
   3. what your message is about, in full ([below](#where-you-asked-from)): a task's whole text, or an event's place and description;
   4. your words, after a line saying what they're about;
   5. after a web search, [what it found](#a-web-search); after he asked to read a task in full, [its whole text](#reading-a-task-in-full).

Within the 40,000 characters: your newest words whole; the earlier turns, newest first, in up to a quarter of what's left, the oldest dropped; the context and the plan in the rest, where both can't be whole the plan keeping at least a third (its near days first).

### Where you asked from

Your words start with what they're about, where the screen you asked from knows:

| From | Your words start |
|---|---|
| A task's sheet (*Ask Felix about it*, *Split this into steps*), the block screen (its next piece's task), Day's *What gives?* | `(About the task <id>, “<title>”) ` |
| An event on Day | `(About the calendar event “<title>”, eventKey <key>) ` |
| A day on Day | `(About Tuesday 13 October 2026) ` |
| The chat itself | nothing |

*What gives?* and *Split this into steps* put their words in the box, for you to change and send.

Before those words comes what they're about, in full (D-93): the task's whole text (up to 8,000 characters) with its sender, attachments and links, and your answers to its questions; or the event's place and description (500 characters). A task with no text of its own, or an event with neither, adds nothing.

### The context

What the app knows when you send (`ChatPrompts.context`):
- **Today**: the date, the time and the time zone.
- **Open tasks**, up to 80, by the deadline in force: each with its id, title, source, kind, due date (marked "(a plan)" where the app or Claude set it), its minutes and what the plan makes of them at your pace, the minutes worked, and when it can start; under it, its steps, numbered for a tick.
- **Your pace**: each multiplier learned (by kind, and by class where it has its own), with the timed pieces it's from; or that none is learned yet.
- **Tasks that look like the same work** (DX-15), where there are any.
- **The calendar's events in the next fortnight**, up to 60, each with its eventKey.
- **The changes in use**, in words.
- **Your own tasks finished in the last week**, up to 15, to open again.
- **The settings he may change**, with their values now (`FelixSettings`).
- **The tasks' own text** (D-93, `TaskText`): each open task's (but Anki's counts) that has any, by the deadline in force: its source, an email's sender, its attachments' names and its links, then its text (an assignment's instructions, an email's conversation, a planner item's details, your notes), at most 600 characters; 12,000 characters in all. A longer text is its reading's summary of all of it: the reading writes one for a text over about 600 characters, at no extra call ([readings.md](readings.md#the-three-jobs)). A text past the 16,000 characters a reading sees has its summary said to be of its start, with its end after it. With none (Claude off when it was read, or read before summaries), its start and its end, saying how much of the middle was left out: a conversation is kept oldest first, so its end holds the newest replies, where a changed date or a new ask turns up. The task in full and the tasks he reads are whole up to 8,000 characters; past that, the summary first, then the start and end. It comes last, so a context too long for its room loses it first.

Not sent: a task's reading, but as its minutes and steps; a finished task's text.

### The plan

`ChatPrompts.plan`:
- a paragraph on what the plan is, and what each `[why: …]` code means;
- the slot under way, or the next thing to do, and the one after;
- today's slots, each past, now or ahead;
- what isn't planned, and why;
- events not under a day below;
- what's planned after the fortnight, by task;
- then the next 14 days: each with its minutes against its working time, and its tasks a line each, with their pieces that day and why each is there.

Each summary is at most 20 lines.

### An example

A Tuesday at 17:20. The evening before, you'd asked what was due, and his answer moved a piece. Now you ask from the vectors' sheet, and the conversation goes as three turns. The plan in it counts the calendar as the app does: Wednesday's drum lesson takes its 45 minutes, Friday has the hour you limited it to, and Saturday's open day, not yet answered, takes all of its day (D-44).

**Yours, the evening before:**

<!-- generated: chat turn 0: You -->
```text
What's due this week?
```

**His:**

<!-- generated: chat turn 1: Felix -->
```text
The worksheet's due Thursday morning and the vectors on Friday. I've put the vectors' first half on Wednesday at 18:00.
[Suggested: “Vectors exercise 4B” at 18:00 on Wed 14 Oct — made at once]
```

**Yours, now** (the context, the plan, then your words; the plan's last days, all "nothing planned", are cut here):

<!-- generated, quiet days folded: chat turn 2: You -->
```text
Today: Tuesday 13 October 2026, 17:20 (Europe/London)

Open tasks (id | title | from | kind | due, a real deadline unless marked a plan, which is never overdue and moves on once it passes | minutes in all, with what the plan makes of them at their pace where it's learned | minutes worked so far | can't start before):
- teams:t1 | Rates of reaction worksheet | Teams | Homework | Thu 15 Oct 2026 08:30 | 45, planned as 59 at their pace | 0 | -
- powerplanner:p1 | Vectors exercise 4B | Power Planner | Homework | Fri 16 Oct 2026 23:59 | 60, planned as 69 at their pace | 0 | -
    step 0: Questions 1 to 6 (30 min)
    step 1: Questions 7 to 12 (30 min)
- gmail:m1 | Trip consent form | Gmail | Admin | Fri 16 Oct 2026 23:59 (a plan) | 10 | 0 | -
- own:o1 | Personal statement first draft | Yours | Admin | no date | 120 | 0 | -

Their pace, learned from work they've finished (kind, and class where it's its own | how long their work takes against the app's own estimates): the plan stretches only the app's estimates by it.
- Homework | ×1.15 (from 9 timed)
- Homework, 12X/Ch1 | ×1.3 (from 4 timed)

Calendar events in the next fortnight (eventKey | title | when):
- drum lesson | Drum lesson | Wed 14 Oct 2026 18:00 to Wed 14 Oct 2026 18:45
- open day | Open day | all day Saturday 17 October 2026

Changes already in use:
- Fri 16 Oct: at most 1 h of work

Their own tasks finished in the last week (id | title | finished):
- own:o0 | Return the library book | Mon 12 Oct 2026 18:10

Settings you can change (setting | what it is | its value now):
- felixName | Felix's name | Felix
- felixLine | Who Felix is | a strict friend who knows your week and wants the work done
- felixGraceMin | Felix asks this far into a slot with nothing done (minutes) | 10
- felixDefaultCheckMin | Felix checks again after (minutes) | 20
- briefingWeekdayMin | Morning briefing, school days | 07:00
- briefingWeekendMin | Morning briefing, weekends | 08:30
- checkInMin | Sunday check-in | 19:30
- weekdayHours | School-day working hours | 16:45-22:00
- weekendHours | Weekend working hours | 08:30-22:30
- breakWindow | Morning break | 10:45-11:15
- minSchoolSlotMin | Shortest school time used (minutes) | 15
- boxMin | A piece's length (minutes) | 30
- slotBreakMin | Break between pieces (minutes) | 5
- slotLongBreakMin | Longer break (minutes) | 15
- slotLongBreakAfterMin | Longer break after (minutes of work) | 90
- outsideBreaks | Breaks outside on a free day | 3
- outsideBreakMin | Each break outside (minutes) | 20
- marginDays | Days to spare before a deadline | 1
- softDeadlineDays | Days given to undated work | 7
- softMinPerDay | Undated work a day (minutes) | 60
- ankiDeadlineMin | Anki deadline | 21:30
- ankiTextbook | Current German textbook | 1

Reminders set, still to come (id | when | their words | its task):
(none)

The tasks' own text (id | from, and what comes with it; then its text: an assignment's instructions, an email's conversation, a planner item's details, their notes), each at most 600 characters: a longer one as its reading's summary (each says of how much), or else its start and end (an email's newest replies are at its end):
- teams:t1 | Teams; links: https://example.org/rates-sheet
    Answer questions 1 to 8 on the sheet. For question 5, use the results table from Monday's practical.
    Show your working for the calculations.
- powerplanner:p1 | Power Planner
    Textbook exercise 4B, questions 1 to 12. Check your answers at the back.
- gmail:m1 | Gmail, from Ms Example; attachments: consent-form.pdf (109 KB)
    Its reading's summary of all 722 characters: Return the signed consent form to the school office by Friday 16 October. The trip is on Friday 30 October: the coach now leaves at 08:00 (be there by 07:50) and is back by 16:30. Bring a packed lunch, a notebook and a pen; tell the department of any access or dietary needs by Wednesday. A worksheet from the visit is due the Monday after.
- own:o1 | Yours
    About 4,000 characters; the first draft by half term.

The plan: the work the app has them do each day so every deadline is met, as its Day and Work tabs show it. Its minutes are the app's, from what it's learned of how long things take them, so they can differ from the task list's. While anything's planned for today, blocked apps stay blocked in blocking hours; otherwise they cost free time earned by working. Each piece's [why: …] says why it's on its day: duesoon (due today or tomorrow), overdue, spread (its share of the free time to its deadline), minimumtouch (kept within a week of its piece before), revisiongap (a day after its revision piece before), mostroom (a task in one piece, on the roomiest day before its deadline), soft (no due date: spare time this week), packed (its days are too tight to spread it, so as late as they allow), kept (where its slot today already was, under way or still to come), noroom (no room before its deadline).
Now: 17:20–17:50 Rates of reaction worksheet: part 1 of 2 (30 min); then Vectors exercise 4B: Questions 1 to 6 (35 min)
Today's slots (the times its pieces are laid at):
- 17:20–17:50 Rates of reaction worksheet: part 1 of 2 (30 min) [now]
- 17:55–18:30 Vectors exercise 4B: Questions 1 to 6 (35 min) [ahead]
Day by day:
- Today, Tuesday 13 October 2026: 65 min planned of 280 min of working time left
  - teams:t1 | Rates of reaction worksheet | 30 min: part 1 of 2 (30 min) [why: spread] | due Thu 15 Oct 2026 08:30
  - powerplanner:p1 | Vectors exercise 4B | 35 min: Questions 1 to 6 (35 min) [why: spread] | due Fri 16 Oct 2026 23:59
- Tomorrow, Wednesday 14 October 2026: 59 min planned of 270 min of working time
  - teams:t1 | Rates of reaction worksheet | 29 min: part 2 of 2 (29 min) [why: spread] | due Thu 15 Oct 2026 08:30
  - own:o1 | Personal statement first draft | 30 min: part 1 of 4 (30 min) [why: soft] | no due date
- Thursday 15 October 2026: 75 min planned of 315 min of working time
  - powerplanner:p1 | Vectors exercise 4B | 35 min: Questions 7 to 12 (35 min) [why: spread] | due Fri 16 Oct 2026 23:59
  - gmail:m1 | Trip consent form | 10 min: all of it (10 min) [why: mostroom] | due Fri 16 Oct 2026 23:59
  - own:o1 | Personal statement first draft | 30 min: part 2 of 4 (30 min) [why: soft] | no due date
- Friday 16 October 2026: 30 min planned of 60 min of working time
  - own:o1 | Personal statement first draft | 30 min: part 3 of 4 (30 min) [why: soft] | no due date
- Saturday 17 October 2026: nothing planned (0 min of working time)
- Sunday 18 October 2026: 30 min planned of 315 min of working time
  - own:o1 | Personal statement first draft | 30 min: part 4 of 4 (30 min) [why: soft] | no due date
- Monday 19 October 2026: nothing planned (315 min of working time)
- Tuesday 20 October 2026: nothing planned (315 min of working time)
[… 6 more days, each “nothing planned” …]

The task this message is about, powerplanner:p1 “Vectors exercise 4B” (Power Planner), in full:
  Textbook exercise 4B, questions 1 to 12. Check your answers at the back.

(About the task powerplanner:p1, “Vectors exercise 4B”) Can I do these tomorrow instead?
```

**A reply to his nudge**, with nothing of yours before it, starts your turn with his message (the context and the plan as above):

<!-- generated: reply turn 0: You -->
```text
(the context, as above)

(the plan, as above)

(You'd written to them: “Starting the worksheet? First: read question 1 and underline what it asks.”)

Give me ten minutes
```

**A reply swiped in the chat to an earlier message of his** starts with that message, quoted, as it isn't the one just before (the context and the plan as above):

<!-- generated: quoted reply -->
```text
(the context, as above)

(the plan, as above)

(Replying to your message: “Starting the worksheet? First: read question 1 and underline what it asks.”)
Can that be at seven instead?
```

### Reading a task in full

Where he needs more of a task's text than the context gives, his answer names it in `details` (three at most, listed and open, never the one the message is about, which came whole) and says only that he's reading it. He's then asked again, as before, with their whole texts after your words (each an even share of 16,000 characters, 8,000 at most), and that answer is his, with "Read in full: …" under it. Asked for a web search as well, he's asked once with both. Where the texts show a need for a search, or a search for a text, he's asked once more, never more (`ChatRounds`), and never for the same search or text twice. What he read is kept till he's answered with it, so a failed answer is asked again with it, not for it. Asked again with the trip's form:

<!-- generated: chat turn with read -->
```text
(the context)

(the plan)

What do I need for the museum trip?

The whole text of the tasks you asked to read (answer and act on it, and leave details empty):
- gmail:m1 “Trip consent form” (Gmail, from Ms Example; attachments: consent-form.pdf (109 KB)):
  Dear Year 12,
  Please return the signed consent form for the museum trip by Friday 16 October; forms go back to the school office, not to your form tutor.
  The trip is on Friday 30 October. The coach leaves at 08:15 from the front of the school and is back by 16:30.
  Bring a packed lunch, a notebook and a pen: the museum's cafe is closed that day for a private event.
  The department covers the cost, but tell us by Wednesday of any access or dietary needs we should pass on to the museum.
  You'll fill in a short worksheet during the visit, due the following Monday.
  Kind regards,
  Ms Example
  On Wednesday 14 October, Ms Example wrote:
  Change of plan: the coach now leaves at 08:00, so be at the front of the school by 07:50.
```

### What comes back

[`chat.schema.json`](../../app/src/test/resources/prompts/chat.schema.json): every field is given, those not used empty (an empty string, -1 for a number). None is nullable: unions in its lists made its grammar too large for the API to compile.

| Field | What | Kept to |
|---|---|---|
| `reply` | one to three short sentences, or a short list | |
| `newTasks` | tasks of your own to add: title, kind, subject, due date and time, start date, minutes, notes, steps | 10 |
| `changes` | of the instructions' types (`NotATask` to `Rename`), each with its fields, and `paced` on a Minutes total worked out at your pace (D-69) | 40 |
| `memories` | a fact or a standing instruction, by topic | 5 |
| `suggestedReplies` | replies you might give, in your words | 3, of 40 characters each |
| `actions` | tick or untick a step, finish, reopen or delete one of your own, open a task, set a setting | 10 |
| `research` | what to look up on the web, or empty | |
| `details` | the tasks whose whole text to read before answering | 3 |

Everything in it is made at once, with an Undo (D-60); what can't be used is said under his reply.

### The chat's prompt

With searches off, its item 6 reads instead: "6. research is always an empty string: you can't look things up."

<!-- golden: chat.txt -->
```text
You are Felix: a strict friend who knows your week and wants the work done, and this is the chat in a UK sixth-form student's (Year 12) planner app. The app gathers their homework (Teams), their own planner (Power Planner), emails they've starred (Gmail), German vocabulary (Anki) and tasks they add themselves; plans the work into the evenings before each is due, around their calendar; and blocks distracting apps while work is due.

The student tells you about their work. You can:
1. Add tasks of their own (newTasks): something to do that isn't listed ("write my UCAS statement by 1 December", "buy mum a present before Sunday"). Give a short title in their words; kind: Homework, Revision, Admin, or Event for something that happens at a time (an appointment, a match); subject if it's for one (else an empty string); dueDate and dueTime if they gave them (an event's start; with no time, an event starts at 09:00 and anything else is due by the end of that day); minutes for the whole thing (your best estimate if they didn't say, and say so in your reply); startDate if it can't be started before a day; notes only for something worth keeping (else an empty string); and steps only for a big task worth splitting, each with minutes (adding up to the task's) and its own dates where it has them.
2. Change their tasks and days (changes), with these:
- NotATask: the task isn't work to plan (an email that only needs reading, something already dealt with). Needs taskId.
- StartFrom: the task can't be started before a date. Needs taskId, date, and time if it gives one (else empty: the start of that day).
- After: the task can't be started until another listed task is done. Needs taskId and afterTaskId.
- DueBy: the task's deadline is a different date. Needs taskId, date, and time if it gives one (else empty: the end of that day).
- Minutes: how long the whole task takes, as the student reckons it. Needs taskId and minutes (the whole task's: where they say how much more it needs, add the minutes already worked on it).
- EventTime: how much of the student's time a calendar event takes, for every event of that name. Needs eventKey and eventTime: "free" (they can work through it), "busy" (it takes all its time; for an all-day event, the whole day), or "hours" with minutes (it takes that many minutes of its day, at no set time).
- DayLimit: the most minutes of work the student can do on a day: minutes (0 for none at all), and date (one day) or weekday (every such day).
- BusyTime: a stretch of time the student can't work: time (its start, HH:MM) and endTime (HH:MM), and date or weekday.
- NoSchool: days with no school (a holiday, half term, an INSET day). Needs date and endDate (the same date for one day).
- HalfDay: a school day that ends early. Needs date and endTime (HH:MM).
- OnDay: all of the task's remaining work goes on one day. Needs taskId and date.
- AtTime: the task's next piece starts at a set time. Needs taskId, date and time (HH:MM).
- SameAs: the task is the same work as another listed task, from another source, which is the one kept (this one is hidden). Needs taskId and afterTaskId (the one kept).
- Steps: replace a task's steps with these (any task's, from any source, but Anki's, whose steps are its cards), adding up to its minutes (for a new total, add a Minutes change with it too); dates within the task's. Needs taskId and steps (each with title, minutes, startDate and dueDate as YYYY-MM-DD or empty, and where: anywhere or home). One step is the whole task in one go.
- Rename: the task's name as the app shows it (its source keeps its own). Needs taskId and title (the new name).

Use the ids and event keys exactly as listed. Work out relative dates ("next Friday", "the weekend") from today; a weekday named alone ("on Saturday") means the next one, and only "every Saturday" or "on Saturdays" means each week. A stretch of days ("20 to 25 October", "this weekend") is one change per day, but for NoSchool, which takes the stretch whole. Leave every field a change type doesn't use empty: an empty string, minutes -1 (0 is a real value: a DayLimit's no work at all), and steps an empty list.
Minutes you set (a new task's, a Minutes change, each step's) are planned exactly as you set them, never stretched by the student's pace: that's only for the app's own estimates (the context gives their pace, and what each task's minutes come to in the plan by it). So where you estimate, allow for their pace: steps for a task the app estimated, at their pace, come with a Minutes change for the new total with paced true, as it's the app's estimate at their pace, so their pace is still learned from it. Where they say how long something takes, use theirs, paced false, as for every other change.
3. Answer questions about their work from what you're given, briefly: what's due when from the task list; what a task asks of them from its own text (each open task's is listed after the settings, cut short, and a message about a task comes with all of it); what's on a day, how full it is, what to do next and why something isn't planned from the plan. The plan is what the app has them do and shows them, so say what it says rather than planning the work again yourself. The context gives the time now: a deadline later today is still ahead ("due at 16:00 today"), not past, and a slot marked past has gone by; say "overdue" only where the plan marks it so.
4. Remember (memories): a fact about the student worth keeping for later (a preference, a pattern, something about their week: "football is on Tuesdays"), with a short topic, so a newer fact on the same topic replaces it; or, kind "rule", a standing instruction about how you should behave ("don't message before 8", "be blunter"). To forget one, give its topic (a rule's own words) and an empty text. Busy times, limits and days off are changes, not memories; most messages need none.
5. Act (actions): tickStep when the student says a step is done, untickStep when one ticked isn't (taskId, and stepIndex from the list's steps); done when they've finished a task of their own (from Yours), or one whose tick finishes it; reopen for one of theirs finished by mistake (from the finished list); delete for a task of their own they no longer want (another source's they can only say isn't a task: NotATask); open to show them a task; setting to change one of the settings listed with their values (taskId empty, setting its name, value as the list writes it: a number, a time as HH:MM, hours as HH:MM-HH:MM); cancelReminder to cancel a reminder set (taskId empty, value its id from the reminders' list); deleteNote to delete a note (taskId empty, value its id from the task's notes). stepIndex is -1 but for steps, setting empty but for settings, and value empty but for settings, cancelReminder and deleteNote. A task from Teams, Power Planner, Gmail or Anki is done when its source says so (handed in, completed in Power Planner, unstarred, studied), so it can't be marked done. Most messages need none.
6. Look things up (research): when their message needs facts that neither they nor the app have given you (a competition's rules and dates, an exam board's specification, what an event involves), give what to search the web for in a few words, reply only that you're looking it up, and change nothing: you'll be asked again with what's found, to answer and act on (its steps, dates and minutes). Never for what the lists here tell you, nor for anything about the student themselves; most messages need none, and research is then an empty string.
7. Read a task in full (details): where you need more of a task's own text than the list shows (it says how much was left out), give its id, up to three, reply only that you're reading it, and change nothing: you'll be asked again with the whole text, to answer and act on. Not for the task a message is about, whose text comes whole; most messages need none, and details is then an empty list.
8. Remind (reminders): when they ask to be reminded of something, that's a reminder, not a task: text in their words, as you'd say them then; when "at" for a date and time (date, time); "slotStart" for the start of their next slot on a task (taskId, and date only where it's not to be from today); "beforeDue" for a while before a task's due (taskId, minutesBefore). To move or reword one already set ("push it to 8"), give its id from the reminders' list, with when and its time as they're to be, and text empty to keep its words; a new one's id is empty. Say when it'll come. Not for work to plan, which is a task; most messages need none.
9. Note (notes): something to keep with a task for when it comes up (what the teacher said, which source to use, what they've decided): taskId and the words. Not a reminder, nor a fact about them; most messages need none.

Reply in one to three short sentences of plain UK English (asked what's on, a short list instead, a line a task). Everything you give is made as soon as you give it (a new task or event, a change, days off, a step ticked, a setting, a reminder, a note), so say what you've done ("Moved it to 19:30", "Added it, due Tuesday"), never what you'd do: the student has an Undo for each. To undo something yourself, give what puts it back. When a reply from them is likely, offer up to three short ones in their words (suggestedReplies). If what you need is missing or unclear (which task they mean, or when), ask one short question and change nothing. Don't add a task that's already listed: change it instead. When their message is about a given task, event or day, it's about that unless they clearly name another. When it starts with a message it replies to, quoted, it's answering that one. Do only what they asked for, and leave every field you don't use empty: an empty string, -1 for a number.
```

## A web search

When the chat's answer gives `research`, and Settings → Felix has searches on (D-62), a call of its own looks it up (`WebResearcher`):
- **System**: the search's prompt, below. No profile: nothing of yours is searched for.
- **The message**: today's date, your words, and what to look up.
- **Tool**: the API's web search, at most three searches, from the UK, in your time zone. No schema: it answers in words, of which 3,000 characters are kept, with the first four pages it read.
- Not cached: its few lines are under the cache's least.

<!-- generated: research user -->
```text
Today: Tuesday 13 October 2026
The student wrote: “What happens at the open day on Saturday?”
Look up: the open day's programme and times
```

The chat is then asked again, as before, with what it found after your words; its answer is his:

<!-- generated: chat turn with research -->
```text
(the context)

(the plan)

What happens at the open day on Saturday?

What a web search found for “the open day's programme and times” (answer and act on it, say in a few words where it's from, and leave research empty):
Saturday 17 October 2026, 10:00 to 15:00: talks every hour from 10:30, subject stands all day.
Booking isn't needed.
From: Open day programme
```

(The context and the plan before your words are as in the example above.) A search that fails, finds nothing or runs too long leaves his first answer, saying why.

The search's prompt:

<!-- generated: research system -->
```text
You look things up on the web for the assistant in a UK sixth-form student's (Year 12) planner app, to plan their work by. Search for what it asks (a few searches at most), then answer in plain UK English in at most 15 short lines: the facts that matter for planning the work (what's asked of them, its dates and deadlines, its rules, word or time limits, what they'll need, how it's judged), each as you found it, and say where something is unsure or wasn't found. Give a date as its day, month and year. Don't plan the work or give advice, don't make anything up, and never search for anything about the student themselves.
```

## His own messages

### When he writes

Every half minute while the blocker runs, at each slot's alarm, and at the check he set, the rules look for a reason to write (`FelixRules.due`), in this order:
1. **SlotEnd**: a slot of today's went by undone, or was ticked without the photo it needs to earn, its time over; once a slot, while it's fresh (30 minutes).
2. **DayDone**: today's last slot kept, with nothing left planned today; once a day.
3. **Question**: a question from a reading (AI-10), the oldest first; not before his next check.
4. **Completions**: from 19:00, work a source says is done that no kept slot saw that day (LR-05); once an evening, not before his next check.
5. Then only while a slot of today's is under way or to come, and not before his next check:
   - **SlotStart**: a slot under way past his grace (`felixGraceMin`, 10 minutes) with nothing done on its task since it began; up to three times a slot.
   - **SlotKept**: about one kept slot in three, while it's fresh.

He isn't asked in quiet hours, during a timed calendar event that takes its time, or with Claude off. His notifications off silence only them: what he writes is in the chat. A standing instruction can quiet him further: he gives an empty message.

Also:
- **Morning**: at the briefing (07:00 on school days, 08:30 at weekends, both in Settings, D-74), when today has work slots. He has 20 seconds; without his answer the day's list is the morning's message, in his name in the app's words.
- **Written ahead** (AC-10): the next slot's SlotStart, up to 10 minutes before it's due, written as of that moment; sent as it is if it still fits then.
- A call held or failed puts nothing on record: it's tried again after 5 minutes while it's still to say.
- `felix-say` writes one now for any reason, from a PC ([storage-and-backups.md](storage-and-backups.md)).

### What's sent

1. **System**: [his prompt](#his-prompt), with his name and line from Settings, then the profile.
2. **One message** (`FelixPrompts.describe`), a line each:
   - `Reason:` and `Now:`;
   - the piece: for a slot under way, its label, minutes, times and what earns its free time, and its first action where one's written (AI-15); for a slot on record, whether it was missed, kept, or ticked without its photo; for a question, the task;
   - `Its task's own text`: up to 1,500 characters of it (a longer one as its reading's summary, else its start and end), with its attachments, links and an email's sender (D-93);
   - `Today:` the slots kept, missed and to come, and the next; `Left today:` those to come;
   - `Free time now:` for SlotKept and DayDone;
   - `Done, as their sources say…`: for Completions, the tasks;
   - `Recent, oldest first:` his last 6 messages of his own in the chat that said something, each with your reply to it (the message of yours answering it, or the next of yours within ten minutes);
   - `Question:` for a Question, the question to put.

Not sent: the task list, the calendar, your pace, any day but today, or the chat.

### For each reason

All on the same made-up Tuesday, planned at 16:50: the worksheet's first piece is laid then, the vectors' at 17:25. The day before, he'd asked if you were starting the vectors, and you'd said you were.

**SlotStart**, ten minutes into the worksheet's slot, nothing done on it:

<!-- generated: felix SlotStart -->
```text
Reason: SlotStart
Now: Tuesday 13 October 2026, 17:00
Piece: Rates of reaction worksheet: part 1 of 2, 30 min, slot 16:50–17:20; it earns its free time with a photo of it, which it needs to earn
First action: Read question 1 and underline what it asks
Its task's own text (Teams; links: https://example.org/rates-sheet):
  Answer questions 1 to 8 on the sheet. For question 5, use the results table from Monday's practical.
  Show your working for the calculations.
Today: 0 kept, 0 missed, 2 to come; next: Vectors exercise 4B: Questions 1 to 6 at 17:25
Left today: Rates of reaction worksheet: part 1 of 2 16:50–17:20; Vectors exercise 4B: Questions 1 to 6 17:25–18:00
Recent, oldest first:
- Mon 17:00, you (SlotStart): Starting the vectors? First: copy out question 1's diagram.
- Mon 17:02, they replied: Starting now
```

**SlotEnd**, the slot gone by undone:

<!-- generated: felix SlotEnd (missed) -->
```text
Reason: SlotEnd
Now: Tuesday 13 October 2026, 17:26
Piece: Rates of reaction worksheet: part 1 of 2, 30 min, slot 16:50–17:20; it went by undone, and has been moved to later
Its task's own text (Teams; links: https://example.org/rates-sheet):
  Answer questions 1 to 8 on the sheet. For question 5, use the results table from Monday's practical.
  Show your working for the calculations.
Today: 0 kept, 1 missed, 1 to come
Left today: Vectors exercise 4B: Questions 1 to 6 17:25–18:00
Recent, oldest first:
- Mon 17:00, you (SlotStart): Starting the vectors? First: copy out question 1's diagram.
- Mon 17:02, they replied: Starting now
```

**SlotEnd**, the piece ticked, but its 30 minutes are over the 20 that need a photo to earn (`photoFromMin`):

<!-- generated: felix SlotEnd (kept, photo needed) -->
```text
Reason: SlotEnd
Now: Tuesday 13 October 2026, 17:22
Piece: Rates of reaction worksheet: part 1 of 2, 30 min, slot 16:50–17:20; ticked done, but it earns its free time only with a photo of it
Its task's own text (Teams; links: https://example.org/rates-sheet):
  Answer questions 1 to 8 on the sheet. For question 5, use the results table from Monday's practical.
  Show your working for the calculations.
Today: 1 kept, 0 missed, 1 to come; next: Vectors exercise 4B: Questions 1 to 6 at 17:25
Left today: Vectors exercise 4B: Questions 1 to 6 17:25–18:00
Recent, oldest first:
- Mon 17:00, you (SlotStart): Starting the vectors? First: copy out question 1's diagram.
- Mon 17:02, they replied: Starting now
```

**SlotKept**, with 15 minutes of free time now:

<!-- generated: felix SlotKept -->
```text
Reason: SlotKept
Now: Tuesday 13 October 2026, 17:21
Piece: Rates of reaction worksheet: part 1 of 2, 30 min, slot 16:50–17:20; kept
Its task's own text (Teams; links: https://example.org/rates-sheet):
  Answer questions 1 to 8 on the sheet. For question 5, use the results table from Monday's practical.
  Show your working for the calculations.
Today: 1 kept, 0 missed, 1 to come; next: Vectors exercise 4B: Questions 1 to 6 at 17:25
Left today: Vectors exercise 4B: Questions 1 to 6 17:25–18:00
Free time now: 15 min
Recent, oldest first:
- Mon 17:00, you (SlotStart): Starting the vectors? First: copy out question 1's diagram.
- Mon 17:02, they replied: Starting now
```

**DayDone**, today's last slot kept, at 21:40:

<!-- generated: felix DayDone -->
```text
Reason: DayDone
Now: Tuesday 13 October 2026, 21:40
Piece: Rates of reaction worksheet: part 1 of 2, 30 min, slot 16:50–17:20; kept
Its task's own text (Teams; links: https://example.org/rates-sheet):
  Answer questions 1 to 8 on the sheet. For question 5, use the results table from Monday's practical.
  Show your working for the calculations.
Today: 1 kept, 0 missed, 0 to come
Free time now: 25 min
Recent, oldest first:
- Mon 17:00, you (SlotStart): Starting the vectors? First: copy out question 1's diagram.
- Mon 17:02, they replied: Starting now
```

**Morning**, at 07:00 (planned then, the day's slots start at 16:45):

<!-- generated: felix Morning -->
```text
Reason: Morning
Now: Tuesday 13 October 2026, 07:00
Today: 0 kept, 0 missed, 2 to come; next: Rates of reaction worksheet: part 1 of 2 at 16:45
Left today: Rates of reaction worksheet: part 1 of 2 16:45–17:15; Vectors exercise 4B: Questions 1 to 6 17:20–17:55
Recent, oldest first:
- Mon 17:00, you (SlotStart): Starting the vectors? First: copy out question 1's diagram.
- Mon 17:02, they replied: Starting now
```

**Question**, a reading's question about the worksheet:

<!-- generated: felix Question -->
```text
Reason: Question
Now: Tuesday 13 October 2026, 17:00
Task: Rates of reaction worksheet
Its task's own text (Teams; links: https://example.org/rates-sheet):
  Answer questions 1 to 8 on the sheet. For question 5, use the results table from Monday's practical.
  Show your working for the calculations.
Today: 0 kept, 0 missed, 2 to come; next: Vectors exercise 4B: Questions 1 to 6 at 17:25
Left today: Rates of reaction worksheet: part 1 of 2 16:50–17:20; Vectors exercise 4B: Questions 1 to 6 17:25–18:00
Recent, oldest first:
- Mon 17:00, you (SlotStart): Starting the vectors? First: copy out question 1's diagram.
- Mon 17:02, they replied: Starting now
Question: Does question 5 need Monday's results, or can it be done from the textbook?
```

**Completions**, at 19:10, two tasks their sources say are done that no kept slot saw:

<!-- generated: felix Completions -->
```text
Reason: Completions
Now: Tuesday 13 October 2026, 19:10
Today: 0 kept, 0 missed, 0 to come
Done, as their sources say, with none of it timed in a slot: Rates of reaction worksheet; Vectors exercise 4B
Recent, oldest first:
- Mon 17:00, you (SlotStart): Starting the vectors? First: copy out question 1's diagram.
- Mon 17:02, they replied: Starting now
```

### What comes back

[`felix.schema.json`](../../app/src/test/resources/prompts/felix.schema.json):

| Field | What | Kept to |
|---|---|---|
| `message` | his message; empty, he'd rather not write now | 240 characters (the morning's 480), cut at its last whole sentence |
| `replies` | replies he'd accept, in your words | 3, of 40 characters each; for Completions, the three answers instead; for a Question, none, as your answer is in your words |
| `nextCheckMin` | when to look again | 5 minutes to a day; with none, `felixDefaultCheckMin` (20) |
| `memories` | facts or standing instructions to keep | 5 |

### His prompt

<!-- golden: felix.txt -->
```text
You are Felix: a strict friend who knows your week and wants the work done. You write the short messages the planner app sends a UK sixth-form student (Year 12) about their work. You get the reason for the message, the piece of work it's about with its first action and its task's own text (an assignment's instructions, an email; cut short), today's plan and what's been kept or missed, your recent messages and the student's replies, and the time.

Be concrete: name the piece and its first action, and what its text asks of them where that helps. Two sentences at most, under 240 characters (the morning's under 480). No guilt, no sarcasm, no emojis, no praise for nothing, no promise the app can't keep. Don't repeat what you said last time. When you want a reply, offer up to three short replies you'd accept, in the student's words. Say when you'll check again as nextCheckMin, and mean it: sooner for a piece just started, later in a long slot, never under 5. Reasons: SlotStart (the slot began and nothing's happened: ask if they're starting); SlotEnd (the slot ended with no tick: ask what happened, or for the photo if the piece needs one); SlotKept (a slot was kept: one line, and only sometimes); DayDone (today's last slot kept: say what's earned, briefly); Morning (the day's shape in a sentence or two: it's the whole briefing, with no list of the day beside it); Question (put the question given, in your words); Completions (a source says work is done: ask harder, as expected or easier); Summary (Sunday: say the week's summary is ready to send); Reply (you're answering what they said); Reminder (a reminder they asked you for comes at this moment: say it, their words in yours, with what's going on then where it helps).

The student's standing instructions on how you behave (with what you're told about them) come before all of this: where one means you shouldn't write now, give an empty message. Remember (memories) a fact about them or a standing instruction only when their replies give one that isn't remembered yet; most messages need none.
```

It names Summary and Reply among its reasons, but no message of his is asked for with either ([below](#in-his-name-but-not-his-words)).

## In his name, but not his words

- **The Sunday check-in** (LR-02): its questions are the app's own text, sent as his messages; your answers go to the check-in, not to the model.
- **The morning's list**, where he has no words for the morning; **the week's review's note**, with *Open the review*.
- **A calendar event's question** (Q7): the app's own text, its answers (*All of it*, *A few hours*, *None*) kept for every event of its name, with no call.
- **How work went, with Claude off** (LR-05): a message for each completion, its answers kept as below.
- **The week's summary**: written in a call of its own, in your voice (D-66); "the summary is ready" is the app's own text, with *Send it*.
- **Your answer to his Question**: read by the instructions reader, as an instruction on that task, and the task read again (AI-10); not by him.
- **An answer to Completions** (*Harder*, *As expected*, *Easier*): kept on each of those completions (LR-05), with no call.
- **Any other reply**, from his notification, his chips or the chat: answered in the chat, as above; his answer is his next message, and in his notification while the chat isn't on screen.
