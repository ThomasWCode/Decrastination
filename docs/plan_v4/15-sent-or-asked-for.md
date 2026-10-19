# What he's sent, and what he asks for

You asked (19 Oct) why Felix said he couldn't see the timetable when you told him a task was due for the next lesson; then whether, rather than adding more to everything he's sent, he should be sent less and ask for what he needs before answering. This document answers both from the code as it is: what he's sent call by call, which of it goes always and which only when he asks, and what changes. The next lessons by class are G8, built in Stage 8b with the other gaps ([06](06-what-felix-can-do.md) §3); what moves to on request, with G6 and G7 as things he asks for, is Stage 8c ([03](03-stages.md)).

## The timetable he doesn't see

Power Planner's timetable is kept with the tasks from its last good read (`TaskState.timetable`, DX-09) and used for three things:

- **A Power Planner item's due time**: an item due at a class gets that class's first lesson that day, from the two-week timetable (`PowerPlannerItems.due` and `slot`). Such a task reaches him already at its lesson's time.
- **The school day's windows**: study periods (classes named "Study Period", D-8), break, lunch and the evening are the planner's working time (`SchoolDays.windows`, for `Planning`, `SlotAlarms`, Day and Settings).
- **One line of the profile**: `Profile.schoolDay` gives the span of the week's lessons and the study periods ("School days: lessons 08:30–15:30; study periods Tue 11:15–12:15; break …; lunch …"), not which class is when.

The chat's context and plan hold tasks, events and the plan, and no lessons by class. So for a Teams assignment, a starred email or a task of your own, "the next lesson" can't become a date and time: the prompt has him work dates out from what he's given, and he isn't given the timetable. A second gap sits under it: Power Planner's class names aren't Teams' class codes, so even with the timetable he couldn't tell which of its classes a Teams task's code is.

## Sending less, and asking

He can already ask before he answers: his answer names `details` (up to three tasks to read in full, D-93) or `research` (a web search, D-62), and he's asked again with what they brought, at most twice more (`ChatRounds.MAX_ROUNDS`). Sending much less and having him ask for the rest isn't better in itself:

- **A round is a call.** The conversation and the context go again, he writes again, and you wait about twice as long; on the phone the wait is the round's main cost.
- **Its price.** At the rates `ClaudeEnricher` holds ($4 a million tokens in, $5 written to the cache, $0.20 read from it), a full 40,000-character input of plain English, about 10,000 tokens at about four characters a token (an estimate; the record of calls has the real figures), is about $0.04 a call. Only the system prompt is cached today (`ModelCalls.systemBlocks`); the context is in your newest message, uncached, so a round pays for it again in full. A cache mark on the context would make a round read it for about $0.002, but every message would pay about $0.05 for the write, $0.01 more, round or none: it saves money only when more than about one message in four takes a round. So it waits for the record of calls to show that ([§The cache mark](#the-cache-mark-after-the-plan)).
- **He asks only for what he knows is there.** Left out and not named, a thing isn't asked for: the timetable is such a case. Each thing he can ask for is named in his prompt.
- **Models ask too little.** What's left out is more often guessed, or answered with "I can't see that", than asked for. What most messages need stays in.

**The rule**: small and needed by most messages, sent always; large, or needed by few, asked for, and named in the prompt's list of what he can ask for. His own messages run with no one waiting in the chat and no rounds, so they send what they need always. What moves is measured by the chat's eval before and after (§The eval), and goes back to always where his answers are worse without it.

## Everything he's sent

"Now" is how it goes today; "After" is how it goes once Stage 8c is in. Notes, reminders and G1's and G5's lines come in 8a and 8b; they're listed so the table is whole. Limits are the code's.

### The chat

| What | Its limit | Now | After | Why |
|---|---|---|---|---|
| The chat's prompt and schema | | always, cached an hour | always | his instructions; the list of what he can ask for is added to them (8c) |
| **The profile**: year group and subjects | 12 subjects | always | always | small; every answer is in its light |
| Profile: the school day (lessons' span, study periods, break, lunch) | a line | always | always | small |
| Profile: working hours and the next three spells of no school | a line | always | always | dates are worked out from it |
| Profile: the German textbook | a line | always | always | small |
| Profile: your own lines (Settings → About you) | within the profile's 2,000 characters | always | always | written by you to be used |
| Profile: standing instructions ("How Felix should behave") | 20 | always | always | they govern every answer; a rule asked for is a rule forgotten |
| Profile: what's remembered | 6,000 characters | always | always | bounded, and he can't tell which fact matters without seeing them |
| **The conversation**: the last 12 messages and his last two nudges with your replies, each answer with what it made | a quarter of the room, at most | always | always | what "that" and "it" refer to |
| A message replied to from the archive, past the window | 300 characters, quoted | when you reply to it | as now | it's what you're answering (`Outbox.find`, `ChatPrompts.turns`) |
| **The context**: today's date, time and time zone | a line | always | always | every date is worked out from it |
| Context: the open tasks, a line each, with their steps | 80 | always | always, each line gaining its source's sender, attachments' names, links and its text's length (8c) | what most messages are about; the line says what each task's text holds, so he knows which to ask for |
| Context: notes under each task, and the reminders (8a) | | not yet | always | small; "cancel that" needs the reminder's id |
| Context: the pace learned | a line a kind or class | always | always | small; needed when he sets minutes (D-69) |
| Context: tasks that look like the same work (DX-15) | where there are any | always | always | small; stops a duplicate being made |
| Context: the calendar's events in the next fortnight, with their keys | 60 | always | always | the plan's fortnight; an event is answered by its key |
| Context: each class's next lessons in the fortnight (G8, 8b) | three a class, about 1,000 characters | not sent | always | small, and "next lesson" and "when's my next chemistry?" are common |
| Context: the changes in use, in words | | always | always | he mustn't contradict or repeat one |
| Context: your own tasks finished in the last week | 15 | always | always | small; "I didn't finish that" needs its id, and he wouldn't know to ask |
| Context: the settings he may change, with their values | 22, about 1,200 characters | always | asked for (8c) | needed only to say or change one by an amount; an absolute value ("set it to 2") needs no round |
| Context: each open task's own text, cut to 600 characters | 12,000 characters | always (D-93) | asked for (8c), if the eval holds | the largest block, and most messages don't need it; the task line says what's there |
| **The plan**: the slot under way and the next, today's slots, what isn't planned, events off the days, what's after the fortnight, 14 days of pieces | 20 lines each summary | always | always | what most questions are about |
| Plan: today's kept pieces (G1) and free time and blocking (G5), 8b | a line each | not sent | always | a line each; "actually I haven't done it" and "how much free time?" need them |
| **What your message is about**: a task's whole text, sender, attachments, links and your answers to its questions; or an event's place and description | 8,000; 500 | always, where asked from a task or an event | always | it's what you're asking about |
| **Tasks read in full** (`details`) | three, 16,000 characters | asked for | asked for | large, and needed by few |
| **What a web search found** (`research`, searches on) | 3,000 characters | asked for | asked for | a call of its own, paid for |
| The settings (`details` "settings") | 22 | always | asked for (8c) | as above |
| The week's numbers (G6, `details` "stats") | 20 lines | not sent | asked for (8c) | needed only for a message about the week |
| The calendar further out (G7, `details` "calendar") | to the plan's 90 days, 60 events | not sent | asked for (8c) | needed only for a message about weeks ahead |
| The rest of the archive | | not sent | not sent | no need seen; the conversation digest is 4.4.0's ([13](13-summaries.md)) |

### A web search

| What | Now | After | Why |
|---|---|---|---|
| The search's prompt | always | always | no profile: nothing of yours is searched for |
| Today's date, your words, what to look up | always | always | a few lines, all it needs |

### His own messages

These run by themselves (a slot, the morning, a question, the evening's completions), the morning's with 20 seconds to answer, so they have no rounds and nothing in them moves.

| What | Its limit | Now and after | Why |
|---|---|---|---|
| His prompt, with his name and line | | always, cached an hour | |
| The profile, as the chat's | as above | always | as above |
| Why he's asked, and now | two lines | always | |
| The piece: its label, minutes, times, what earns its free time, its first action; or how a slot went; or a question's task | | always | what the message is about |
| The piece's task's own text, with attachments, links and sender | 1,500 characters | always | a nudge needs what the work is |
| Today: slots kept, missed and to come; the next; those left | | always | |
| Free time now (SlotKept, DayDone) | a line | always | |
| Done, as the sources say (Completions) | | always | |
| His last six messages of his own, each with your reply | 6 | always | stops him repeating himself |
| The question to put (Question) | | always | |
| The task list, the calendar, the pace, other days, the chat | | not sent | not needed for one message about now |

## G8: the next lessons (8b)

- **The rule** (pure, beside `SchoolDays`, from the lessons `PowerPlannerItems.lessonsOn` gives wherever 6f leaves it): every lesson in the next 14 days, with `lessonsOn`'s rule for the week: where the timetable's week one isn't known, only the lessons of both weeks, as those alone are sure; a day of no school (`SchoolCalendar.noSchool`) has none; a half day has none from its end (`HalfDay`'s end) on. By class, each class's next three.
- **A Power Planner class and its Teams code are tied by the subject you give each.** Settings → About you's classes list each class name the sources give, Teams' codes and Power Planner's names alike, and the subject it is (`Settings.classMap`); its list gains the timetable's classes that have no task yet, so each can be given one. A Power Planner class and a Teams code with the same subject are one class: its lessons are listed under the subject with both names. None of Power Planner's names matches Teams' codes, so the subjects are yours to give, once (the list you offered on 19 Oct, entered on the phone and never in the repository, D-79). A class with no subject is listed by its own name and said to be tied to no code.
- **The line**, after the calendar's events: "Lessons in the next fortnight (class, its names | the next three):", then a line a class ("- Physics (12X/Ph1, Phys) | Tue 21 Oct 08:50–09:45; Thu 23 Oct 12:20–13:15; Tue 28 Oct 08:50–09:45"). Made-up names here as everywhere in the repository.
- **The prompt**: "next lesson" or "next <subject>" is that class's next lesson from the list; a `DueBy` at its start, date and time, as a Power Planner item due at a class is. A class not in the list, or tied to no code he can match, is asked about, not guessed.
- No change to the schema: `DueBy` takes a date and a time already.

## The requests and the moves (8c)

- **What he can ask for.** `details` takes, beside the tasks' ids, `settings`, `stats` and `calendar`. The schema doesn't change (it's a list of strings), so no compile by the API is needed. `ChatRounds.next` reads the kinds: tasks up to `MAX_READ` (three) as now, each other kind once; `MAX_ROUNDS` stays two, so a message is at most three of his calls (a web search a call of its own, as now), never a loop, and each goes through the gateway under the cap. What a round brings comes after your words, as the tasks' texts do now, each under its own heading.
- **The list in the prompt.** Item 7 becomes what he can ask for before answering, and when: a task's whole text by its id (to quote or answer from it); `settings` (to say a setting's value or change it by an amount); `stats` (a message about how a week went); `calendar` (anything after the fortnight). With each, "say only that you're looking, and leave the rest empty", as `details` is today.
- **The settings asked for.** The context's settings block goes; asked for, the same lines come in the round. A setting's change is checked as now (`FelixSettings`, D-61), with or without the round.
- **G6, the week's numbers.** `stats` brings `Learning`'s period summary (from `Stats`, 6e): kept minutes, completions, deadlines met, slots kept and missed, by subject, for this week so far and last week, in 20 lines.
- **G7, the calendar further out.** `calendar` brings the events from the fortnight's end to the plan's reach (90 days), up to 60, with their keys, as the fortnight's are listed.
- **The tasks' text asked for** (D-93 amended, if the eval holds). The context's text block goes. Each task's line gains what `TaskText.about` gives (its source, an email's sender, the attachments' names, the links) and its text's length ("text 1,850 characters"; "no text"), so "which email has the consent form attached?" is answered from the list and he knows which texts to ask for. Asked for, a text comes as `details` brings it today (whole up to 8,000 characters, three at most). The task your message is about still comes whole, unasked. His own messages are unchanged.
- **The newest turn built so a round only adds.** The context and the plan go as a text block of their own, before your words and what a round brought. A round keeps the first call's moment (`at`), its snapshot, its context, its plan and its rooms (the earlier turns' and the context's), so everything before your words is the same, byte for byte, and a round only adds blocks after. Nothing is marked for the cache yet; this is the shape that makes the mark work when it comes.

## The eval

The chat's private eval (`private/eval-chat`, never committed; the set 10b runs, made here if it isn't by then): each case a state and a message, labelled with the changes it should make, the dates it should read, and what it should ask for. `EvalRunTest` asks it through `ChatReader` as the Conversation does, rounds included (it runs the readings' set today), and reports the scores with each case's rounds, tokens and cost. The set gains cases for everything that moves or is new: a setting's value and a change by an amount; the week; a month ahead; an email's sender or attachment; what a task's text asks; a next lesson.

1. **The baseline**: run on the code as it is before any move, its figures in the PR.
2. **After**: run again with everything in.
3. **What decides**: a thing moved stays asked for only where its cases score at least what the baseline did. Where they score lower, it goes back to always in the same PR, with the figures. Each run is held to `EVAL_BUDGET_USD`, $2.
4. **Rolling back** after the merge is the PR's revert: nothing stored changes.

The set leans on the cases that need a round, so its rate of rounds says nothing about everyday use; that comes from the record of calls, once the app's in use.

## The cache mark, after the plan

You won't use the app until the whole plan is in (Q21), so how often a message takes a round can't be measured before then. After a few weeks of use, the record of calls gives it (the chat's calls a message: one, or two or three with rounds). If more than about one message in four takes a round, a small release marks the context's block for the five-minute cache: rounds come seconds apart. If fewer do, the mark costs more than it saves and isn't made. The shape 8c leaves means the mark is a line and a test.

## Not planned

The API's own tool use in place of `details`: it handles several requests more neatly, but means a new caller, schema, goldens and tests for the chat; `details` gives most of the gain for little of the work.

## Built in

[03](03-stages.md) §Stage 8b, G8's commit; §Stage 8c: the eval's baseline first, then the requests, the moves, the newest turn's shape, the eval again, the docs.

## Tests

- G8's rule: lessons from a two-week timetable over a fortnight; the week unknown (both weeks' lessons only); a day of no school; a half day, a lesson before its end kept and one after left out; a class tied to a code by subject, one tied to none; three a class.
- The class map's list in Settings gaining the timetable's classes with no task.
- `ChatRounds.next` with each kind: tasks to three, each other kind once, nothing new ends it; two rounds at most.
- The context: no settings block and no text block; each task's line with its sender, attachments, links and length; the lessons line (`FelixInputsDocTest` regenerated).
- Each round's block: the settings' lines, `stats`' summary from a fixed log, `calendar`'s events past the fortnight.
- The newest turn: a round's text before your words is equal, byte for byte, to the first call's, though the clock has moved on a minute.
- The goldens: `chat.txt` rewritten on purpose, its diff in the PR; `chat.schema.json` unchanged.

## Phone check

- 8b: your classes' subjects entered first; `chat-ask` (paid): "when's my next chemistry?" (the lesson from the list); "the worksheet's due next lesson" (a `DueBy` at that lesson's start, its Undo).
- 8c: `chat-ask` (paid): "what's my margin days?" (a round for the settings, "Read: the settings" under his answer); "how did last week go?" (`stats`); "what's on in December?" (`calendar`); "what does the trip email say?" (its text read); "which email has the form attached?" (answered without a round). `chat-context` shows no settings and no texts, each task's line with what its text holds.
