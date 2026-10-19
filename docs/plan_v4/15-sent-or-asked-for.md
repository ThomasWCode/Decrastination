# What he's sent, and what he asks for

You asked (19 Oct) why Felix said he couldn't see the timetable when you told him a task was due for the next lesson; then whether, rather than adding more to everything he's sent, he should be sent less and ask for what he needs before answering. This document answers both from the code as it is: what he's sent call by call, which of it goes always and which only when he asks, and what changes. The next lessons by class are G8, built in Stage 8b with the other gaps ([06](06-what-felix-can-do.md) §3); G6 and G7 as things he asks for, and each task's text cut shorter with its line saying what the text holds, are Stage 8c ([03](03-stages.md)); the text asked for instead of sent waits for real use ([§After the plan](#after-the-plan)).

## The timetable he doesn't see

Power Planner's timetable is kept with the tasks from its last good read (`TaskState.timetable`, DX-09) and used for three things:

- **A Power Planner item's due time**: an item due at a class gets that class's first lesson that day, from the two-week timetable (`PowerPlannerItems.due` and `slot`). Such a task reaches him already at its lesson's time.
- **The school day's windows**: study periods (classes named "Study Period", D-8), break, lunch and the evening are the planner's working time (`SchoolDays.windows`, for `Planning`, `SlotAlarms`, Day and Settings).
- **One line of the profile**: `Profile.schoolDay` gives the span of the week's lessons and the study periods ("School days: lessons 08:30–15:30; study periods Tue 11:15–12:15; break …; lunch …"), not which class is when.

The chat's context and plan hold tasks, events and the plan, and no lessons by class. So for a Teams assignment, a starred email or a task of your own, "the next lesson" can't become a date and time: the prompt has him work dates out from what he's given, and he isn't given the timetable. A second gap sits under it: Power Planner's class names aren't Teams' class codes, so even with the timetable he couldn't tell which of its classes a Teams task's code is.

## Sending less, and asking

He can already ask before he answers: his answer names `details` (up to three tasks to read in full, D-93) or `research` (a web search, D-62), and he's asked again with what they brought, at most twice more (`ChatRounds.MAX_ROUNDS`). Sending much less and having him ask for the rest isn't better in itself:

- **A round is a call.** The conversation and the context go again, he writes again, and you wait about twice as long; on the phone the wait is the round's main cost.
- **Its price.** At the rates `ClaudeEnricher` holds ($4 a million tokens in, $5 written to the cache, $0.20 read from it), the record of calls on the phone (20 chat calls, 18 and 19 Oct) puts a call at about 15,000 tokens of input not cached (the text runs nearer 2.7 characters a token than four), 5,000 read from the cache and 500 written: about $0.07 (the median $0.074), and about eight seconds' wait (the median 7.8, the longest 11), so a round about doubles the wait as well as the price. Only the system prompt is cached today (`ModelCalls.systemBlocks`); the context is in your newest message, uncached, so a round pays for it again in full. A cache mark on the context would make a round read it for about $0.003, but every message would pay about $0.075 for the write, $0.015 more, round or none: it saves money only when more than about one message in four takes a round, and in that record about one in twenty did. So it waits for the record of calls, once the app's in use, to show more ([§After the plan](#after-the-plan)).
- **He asks only for what he knows is there.** Left out and not named, a thing isn't asked for: the timetable is such a case. Each thing he can ask for is named in his prompt.
- **Models ask too little.** What's left out is more often guessed, or answered with "I can't see that", than asked for. What most messages need stays in.

**The rule**: small and needed by most messages, sent always; large, or needed by few, asked for, and named in the prompt's list of what he can ask for. His own messages run with no one waiting in the chat and no rounds, so they send what they need always. What moves is measured by the chat's eval before and after (§The eval), and goes back to always where his answers are worse without it; what the eval can't tell, how often everyday messages would need a round, real use decides ([§After the plan](#after-the-plan)).

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
| Context: the open tasks, a line each, with their steps | 80 | always | always, each line gaining its class (8b) and its sender, attachments' names, links and text's length, within their own room (8c) | what most messages are about; the class ties a task to its lessons, and the rest says what each task's text holds, so he knows which to ask for |
| Context: notes under each task, and the reminders (8a) | | not yet | always | small; "cancel that" needs the reminder's id |
| Context: the pace learned | a line a kind or class | always | always | small; needed when he sets minutes (D-69) |
| Context: tasks that look like the same work (DX-15) | where there are any | always | always | small; stops a duplicate being made |
| Context: the calendar's events in the next fortnight, with their keys | 60 | always | always | the plan's fortnight; an event is answered by its key |
| Context: each class's next lessons (G8, 8b) | three a class, to the plan's reach, about 1,000 characters | not sent | always | small, and "next lesson" and "when's my next chemistry?" are common |
| Context: the changes in use, in words | | always | always | he mustn't contradict or repeat one |
| Context: your own tasks finished in the last week | 15 | always | always | small; "I didn't finish that" needs its id, and he wouldn't know to ask |
| Context: the settings he may change, with their values | 22, about 1,200 characters | always | always | a change names a setting exactly as this block writes it (`FelixSettings.field`), so the names must stay, and the values without them are about 600 characters: not worth a round (your call, 19 Oct) |
| Context: each open task's own text, cut to 600 characters | 12,000 characters; 5,000 at 250 | always (D-93) | always, cut to 250 characters (8c), if the eval holds; asked for only if real use shows few messages need it ([§After the plan](#after-the-plan)) | the largest block; the task's line says what the text holds, so he knows when to ask for it whole |
| **The plan**: the slot under way and the next, today's slots, what isn't planned, events off the days, what's after the fortnight, 14 days of pieces | 20 lines each summary | always | always | what most questions are about |
| Plan: today's kept pieces (G1) and free time and blocking (G5), 8b | a line each | not sent | always | a line each; "actually I haven't done it" and "how much free time?" need them |
| **What your message is about**: a task's whole text, sender, attachments, links and your answers to its questions; or an event's place and description | 8,000; 500 | always, where asked from a task or an event | always | it's what you're asking about |
| **Tasks read in full** (`details`) | three, 16,000 characters | asked for | asked for | large, and needed by few |
| **What a web search found** (`research`, searches on) | 3,000 characters | asked for | asked for | a call of its own, paid for |
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

- **The rule** (pure, beside `SchoolDays`, from the lessons `PowerPlannerItems.lessonsOn` gives wherever 6f leaves it): each class's next three lessons from now, today's that have begun passed over, looked for day by day as far as the plan's reach (90 days), so a holiday doesn't hide them; with `lessonsOn`'s rule for the week: where the timetable's week one isn't known, only the lessons of both weeks, as those alone are sure; a day of no school (`SchoolCalendar.noSchool`) has none; a half day has none from its end (`HalfDay`'s end) on.
- **A Power Planner class and its Teams code are tied by the subject you give each.** Settings → About you's classes list each class name the sources give, Teams' codes and Power Planner's names alike, and the subject it is (`Settings.classMap`); its list gains the timetable's classes that have no task yet, so each can be given one. A Power Planner class and a Teams code with the same subject are one class: its lessons are listed under the subject with both names. None of Power Planner's names matches Teams' codes, so the subjects are yours to give, once (the list you offered on 19 Oct, entered on the phone and never in the repository, D-79). A class with no subject is listed by its own name and said to be tied to no code.
- **Each task's line gains its class**: its code, and the subject the class map gives it ("12X/Ph1, Physics"). Today the line has neither, so without it "the worksheet's due next lesson" couldn't tie the worksheet to its class's lessons.
- **The line**, after the calendar's events: "Next lessons (class, its names | the next three):", then a line a class ("- Physics (12X/Ph1, Phys) | Tue 20 Oct 08:50–09:45; Thu 22 Oct 12:20–13:15; Tue 27 Oct 08:50–09:45"). Made-up names here as everywhere in the repository.
- **A timetable gone stale, or none**: it's Power Planner's last good read (DX-09), so where that's more than a week old the line says when ("from the timetable read on Sat 3 Oct"), and a `DueBy` naming a lesson is refused, saying when it was read: he asks you, and the date you give is set as any date is (a `DueBy` with its date and time), so a stale timetable can't set one unchecked. With no timetable read at all the line is left out, and a lesson's change is refused the same way.
- **A due date at a class's next lesson is the app's to work out.** "Next lesson" or "next <subject>" is a `DueBy` naming the class (a new field, `lesson`: the subject or the class's name, empty for none) rather than a date and a time, and the app sets it to that class's next lesson's start by the rule above, as a Power Planner item due at a class gets its lesson's: the date isn't his reading of a table. With `lesson` set, the change's own date and time aren't read. A class it can't find (no subject tying it, no lessons ahead) refuses the change, saying which class, and he asks. The list stays for what he's asked about lessons ("when's my next chemistry?"); a class not in it, or tied to no code he can match, is asked about, not guessed.
- **The schema** gains `lesson` on a change, plain ("" for none, as list items' fields are kept, so the compiled grammar stays small): 8b's `SchemaCompileTest -PcheckSchemas=true` covers it, and `chat.schema.json` changes with it.

## The requests, and the text cut shorter (8c)

- **What he can ask for.** `details` takes, beside the tasks' ids, `stats` and `calendar`, the latter with the month it's about where the message names one (`calendar:2026-12`). The schema doesn't change (it's a list of strings), so no compile by the API is needed. `ChatAnswers.read` keeps the kinds: today it keeps only listed, open tasks' ids and takes three (`ChatAnswers.kt:182`), so the kinds would be dropped before a round saw them. `Reading` carries the kinds apart from the ids, each once, the tasks still three at most, and `ChatRounds.next` reads both; `MAX_ROUNDS` stays two, so a message is at most three of his calls (a web search a call of its own, as now), never a loop, and each goes through the gateway under the cap. What a round brings comes after your words, as the tasks' texts do now, each under its own heading.
- **The list in the prompt.** Item 7 becomes what he can ask for before answering, and when: a task's whole text by its id (to quote or answer from it); `stats` (a message about how a week went); `calendar`, or `calendar:<yyyy-MM>` for a month (anything after the fortnight). With each, "say only that you're looking, and leave the rest empty", as `details` is today.
- **The settings stay.** A change names a setting exactly as the context's block writes it (`FelixSettings.field` matches the name and nothing else), so its names stay, and without them its values are about 600 characters: not worth a round. Kept always (your call, 19 Oct).
- **G6, the week's numbers.** `stats` brings `Learning`'s period summary (from `Stats`, 6e): kept minutes, completions, deadlines met, slots kept and missed, by subject, for this week so far and last week, in 20 lines.
- **G7, the calendar further out.** `calendar:<yyyy-MM>` brings that month's events, up to 60, with their keys, as the fortnight's are listed; `calendar` alone, the four weeks after the fortnight. Each block says what it covers, and how many events it left out past its 60 and from when, so a month crowded by recurring events isn't taken for an empty one: he asks for the month he's asked about.
- **What each task's text holds**, on its line. Each task's line gains what `TaskText.about` gives (its source, an email's sender, the attachments' names, the links) and its text's length ("text 1,850 characters"; "no text"), so "which email has the consent form attached?" is answered from the list and he knows which texts to ask for whole. That part of a line has a room of its own, so it can't crowd later tasks out of the list (`TaskText.about` allows 800 characters, and 80 tasks of it would pass the whole input's 40,000): at most 160 characters a line, and 6,000 in all, by the deadline in force as the texts are now; a task past that keeps only its text's length.
- **The text, cut shorter** (D-93 amended, if the eval holds). The block stays, each task's text cut to 250 characters where it's 600 now (12,000 characters at most becomes 5,000), which pays for the lines' room; what's past the cut is a `details` away, whole up to 8,000 characters, three at most, as today. Where the eval scores the cut lower, the text goes back to 600 in the same PR. The task your message is about still comes whole, unasked. His own messages are unchanged.
- **Not in 8c: the text asked for instead of sent.** The block going altogether would save about $0.018 a message, but each message that then needed a text would pay a round, about $0.07 and eight seconds: it pays only if fewer than about one message in four need one, and the eval can't say how many do, as its set leans on the cases that need a round. So it waits for real use ([§After the plan](#after-the-plan)).

## The eval

The chat's private eval (`private/eval-chat`, never committed; the set 10b runs, made here if it isn't by then): each case a state and a message, labelled with the changes it should make, the dates it should read, and what it should ask for. `EvalRunTest` asks it through `ChatReader` as the Conversation does, rounds included (it runs the readings' set today), and reports the scores with each case's rounds, tokens, cost and seconds. The set gains cases for everything that moves or is new: the week; a month ahead; an email's sender or attachment; what a task's text asks; a next lesson.

1. **The baseline**: run on the code as it is before any move, its figures in the PR.
2. **After**: run again with everything in.
3. **What decides**: the text's cut stands only where its cases score at least the baseline's in both of two runs after, at no more rounds; its cases run twice before and twice after (the rest once), so a small set's noise can't decide it. Where it falls short, the text goes back to 600 characters in the same PR, with the figures. G6 and G7 are new, so they can only add. Each run is held to `EVAL_BUDGET_USD`, $2.
4. **Rolling back** after the merge is the PR's revert: nothing stored changes.

The set leans on the cases that need a round, so its rate of rounds says nothing about everyday use; that comes from the record of calls, once the app's in use.

## After the plan

You won't use the app until the whole plan is in (Q21), so how often a message takes a round can't be measured before then. After a few weeks of use, the record of calls gives it (the chat's calls a message: one, or two or three with rounds). If more than about one message in four takes a round, a small release marks the context's block for the five-minute cache: rounds come seconds apart. If fewer do, the mark costs more than it saves and isn't made.

**The tasks' text asked for instead of sent** is decided by real use too. Once the app's in use, a sample of your real messages (kept in `private/`, never committed) is the everyday mix the 8c set can't be: run through the chat's eval with the 250 characters sent and with none, it gives how many messages would take a round without them, at what cost and wait, and how good the answers are. Only where fewer than about one in four would, at answers as good, does a small release drop the block, the lines saying what each text holds (D-93 amended again).

Till then rounds are built as today: the earlier turns' and the context's rooms shrink as your newest turn grows, which keeps every call within `Pricing.MAX_INPUT_CHARS`.

That release also builds the newest turn so a round only adds (your call, 19 Oct: with the mark, as it serves nothing else):

- **The prefix kept.** The context and the plan go as a text block of their own, before your words and what a round brought. A round keeps the first call's moment (`at`), its snapshot, its context, its plan and its rooms, so everything before your words is the same, byte for byte; a hit needs every byte before the mark the same.
- **A round runs past the room by its own blocks.** With the rooms fixed, a round's blocks (three tasks' texts, 16,000 characters; the week's numbers; the calendar) take it past the 40,000 characters a call has, and the record of calls shows first calls already near that room, so holding room back in each would cut the context most calls need, which never take a round (your call, 19 Oct evening). Instead a round may run past `MAX_INPUT_CHARS` by its blocks alone: with everything before them read from the cache, it costs about $0.03, under what `Pricing.WORST_CALL_USD` allows a whole uncached call, and `AiUsage.allows` counts what's read from the cache at the cache's price. A round that would cost more than that goes without the mark, built as today.
- **Tests**: a round's text before your words equal, byte for byte, to the first call's, though the clock has moved on a minute; the largest round, every kind asked for at once, within the cap's worst call at the cache's price, its first call's context not cut for it.

## Not planned

The API's own tool use in place of `details`: it handles several requests more neatly, but means a new caller, schema, goldens and tests for the chat; `details` gives most of the gain for little of the work.

## Built in

[03](03-stages.md) §Stage 8b, G8's commit, with each task's class on its line and the lesson a due date names; §Stage 8c: the eval's baseline first, then the requests, the lines and the cut, the eval again, the docs; §After, the cache mark with the newest turn's shape, and the text asked for, each decided by real use.

## Tests

- G8's rule: lessons from a two-week timetable; the week unknown (both weeks' lessons only); a day of no school; a half day, a lesson before its end kept and one after left out; a class whose next lesson is past a fortnight's holiday, found; a class tied to a code by subject, one tied to none; three a class; today's lesson already begun passed over for the next; the line dated where the timetable's over a week old, and left out with none.
- A `DueBy` naming a class's lesson: set to that lesson's start, its own date and time not read; a class no subject ties, or with no lessons ahead, refused, saying which; refused too on a timetable over a week old, saying when it was read, and a date given then set as given.
- Each task's line with its class code and subject; "the worksheet's due next lesson" in `FelixInputsDocTest`'s example, the task's class and its lessons both in the context.
- The class map's list in Settings gaining the timetable's classes with no task.
- `ChatAnswers.read` with three tasks' ids and both kinds in one answer: the ids to three, each kind kept once, an unknown word dropped; `ChatRounds.next` with each kind, nothing new ending it; two rounds at most.
- The context: the text block at 250 characters a task, 5,000 in all; the settings block as now; each task's line with its sender, attachments, links and length, within 160 characters a line and 6,000 in all; 80 tasks with long attachments' names keep every id in the list; the lessons line (`FelixInputsDocTest` regenerated).
- Each round's block: `stats`' summary from a fixed log; `calendar`'s four weeks past the fortnight; `calendar:2026-12` with that month's events only, and a month of more than 60 saying how many it left out.
- The goldens: `chat.txt` rewritten on purpose, its diff in the PR (8b and 8c); `chat.schema.json` with `lesson` (8b), unchanged in 8c.

## Phone check

- 8b: your classes' subjects entered first; `chat-ask` (paid): "when's my next chemistry?" (the lesson from the list); "the worksheet's due next lesson" (a `DueBy` naming the class, set by the app to that lesson's start; its Undo).
- 8c: `chat-ask` (paid): "how did last week go?" (`stats`, "Read: the week's numbers" under his answer); "what's on in December?" (`calendar` for December); "what does the trip email say?" (its text read); "which email has the form attached?" (answered without a round). `chat-context` shows each task's text cut to 250 characters, and each task's line with what its text holds.
