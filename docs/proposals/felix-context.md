# What Felix is sent: always, or on request

A proposal, not a decision: nothing here is built, and nothing in [decisions.md](../decisions.md) or [plan_v4](../plan_v4/) changes with it. How each call is made now is in [spec/felix-inputs.md](../spec/felix-inputs.md); this file lists what each call sends, whether it goes always or only when Felix asks, and what this proposal would have it be.

## Why

Asked to set a task as due "for the next lesson", Felix said he can't see the timetable. He's right: he's sent a one-line summary of the school day, but not which class is when.

## The timetable now

Power Planner's timetable is kept with the tasks from its last good read (`TaskState.timetable`, DX-09) and used in four places:

- **A Power Planner item's due time.** An item due at a class gets that class's first lesson that day, from the two-week timetable (`PowerPlannerItems.due`, `slot`). Felix sees such a task already at its lesson's time.
- **The school day's windows.** Study periods (classes named "Study Period", D-8), break, lunch and the evening are the planner's working time (`SchoolDays.windows`, used by `Planning`, `SlotAlarms`, Day and Settings).
- **The profile's school day.** `Profile.schoolDay` sends one line: "School days: lessons 08:50–15:30; study periods Tue 14:35–15:30; break …; lunch …". That is the span of the day and the study periods, not a class's lessons.
- **Nowhere else.** The chat's context and plan hold tasks, events and the plan, but no lessons by class.

So for a Teams assignment, a starred email or a task of the owner's, "the next lesson" can't be turned into a date and time: the chat's prompt has him work dates out from what he's given ("next Friday"), and he isn't given the timetable.

## Sending less, and asking

The chat already lets Felix ask before he answers. His answer can name `details` (up to three tasks to read in full, D-93) and `research` (a web search); he's then asked again with what they brought, at most twice more (`ChatRounds.MAX_ROUNDS`). The plan has two more things fetched that way: G6, the week's numbers, and G7, the calendar further out ([06-what-felix-can-do.md](../plan_v4/06-what-felix-can-do.md) §3).

Sending much less and having him ask for the rest is not better in itself:

- **A round is a call.** The conversation and the context go again, he writes again, and the owner waits about twice as long. On the phone, the wait is the round's main cost.
- **Its price can be made small.** Only the system prompt is cached now (`ModelCalls.systemBlocks`, an hour). The context is in the newest message, uncached, so a round pays for it in full again. At the rates in `ClaudeEnricher` ($4 a million tokens in, $0.20 read from the cache), a full 40,000-character input of plain English, about 10,000 tokens (an estimate; the record of calls has the real figures), is about $0.04 a call, and about $0.002 read from the cache. A cache mark on the newest message would make a round cheap: rounds come seconds apart, well within the cache's five minutes.
- **He asks only for what he knows is there.** Left out and not named, a thing isn't asked for: the timetable is such a case. Sending less needs a list in his prompt of what he can ask for.
- **Models ask too little.** What's left out tends to be guessed, or answered with "I can't see that", more often than asked for. What most messages need stays in.

**The rule proposed:** small and needed by most messages, sent always; large and needed by few, sent on request, named in the prompt's list of what can be asked for. Calls that no one is waiting on in the chat (his own messages) have no rounds, and send what they need always.

## Everything Felix is sent

"Now" is how it's sent today; "Proposed" what this proposal would have it be. Sizes are the code's limits.

### The chat

| What | Its limit | Now | Proposed | Why |
|---|---|---|---|---|
| The chat's prompt and schema | | Always (cached an hour) | Always | His instructions. A list of what he can ask for is added to it. |
| **Profile**: year group and subjects | 12 subjects | Always | Always | Small; every answer is in its light. |
| Profile: the school day (lessons' span, study periods, break, lunch) | one line | Always | Always | Small. |
| Profile: working hours and the next three spells of no school | one line | Always | Always | Small; dates are worked out from it. |
| Profile: the German textbook | one line | Always | Always | Small. |
| Profile: the owner's own lines (Settings → About you) | within the profile's 2,000 characters | Always | Always | Small, and the owner wrote them to be used. |
| Profile: standing instructions ("How Felix should behave") | 20 | Always | Always | They govern every answer; a rule asked for is a rule forgotten. |
| Profile: what's remembered (facts) | 6,000 characters | Always | Always | Bounded, and what makes him know the owner; he can't tell which fact matters without seeing them. |
| **The conversation**: the last 12 messages and his last 2 nudges with their replies, with what each answer made | the input's room, a quarter at most | Always | Always | What "that" and "it" refer to. |
| **The context**: today's date, time and time zone | one line | Always | Always | Every date is worked out from it. |
| Context: open tasks, each a line with its steps | 80 | Always | Always | What most messages are about. |
| Context: the pace learned | a line a kind or class | Always | Always | Small; needed when he sets minutes (D-69). |
| Context: tasks that look like the same work (DX-15) | where there are any | Always | Always | Small; stops a duplicate being made. |
| Context: calendar events in the next fortnight, with their keys | 60 | Always | Always | Needed to plan, and to answer about an event by its key. |
| Context: the changes in use, in words | | Always | Always | He mustn't contradict or repeat one. |
| Context: own tasks finished in the last week | 15 | Always | Always | Small; without it, "I didn't finish that" has no id to reopen, and he wouldn't know to ask. |
| Context: the settings he may change, with their values | about 20 lines | Always | On request | Needed only to change a setting or say what one is; about 1,300 characters a message otherwise. A small saving: the last to move. |
| Context: each open task's own text, cut to 600 characters | 12,000 characters | Always (D-93) | On request | The largest block, and most messages don't need it; the `details` round already fetches it whole. The task list would say which tasks have text, and how long. The task a message is about still comes whole. D-93 is the owner's decision, so this is theirs to change. |
| Context, new: each class's next lessons in the fortnight, school days off left out | about 12 lines | Not sent | Always | Small, and "next lesson" and "when's my next Physics?" are common. Needs a rule for matching a task's class code to Power Planner's class name. |
| **The plan**: the slot under way and next, today's slots, what isn't planned, events off the days, what's after the fortnight, and 14 days of pieces | 20 lines each summary | Always | Always | What most questions are about ("what's on", "can this move"). |
| Free time and blocking (G5, planned) | one line | Not sent | Always | One line; asked about often. |
| Today's kept pieces (G1, planned) | one line | Not sent | Always | Needed to unkeep one. |
| **What the message is about**: a task's whole text, sender, attachments, links and answers to its questions; or an event's place and description | 8,000; 500 | Always, where asked from a task or event | Always | It's what the owner is asking about. |
| **Tasks read in full** (`details`) | 3 tasks, 16,000 characters | On request | On request | Large, and needed by few. |
| **What a web search found** (`research`, searches on, D-62) | 3,000 characters | On request | On request | A call of its own, and paid for. |
| The week's numbers (G6, planned) | 20 lines | Not sent | On request | Needed only for a message about the week. |
| The calendar further out (G7, planned) | to the plan's 90 days | Not sent | On request | Needed only for a message about a month ahead. |
| The chat's archive (older messages) | | Not sent | Not sent | No need seen; on request if one shows. |

### A web search

| What | Now | Proposed | Why |
|---|---|---|---|
| The search's prompt | Always | Always | No profile: nothing of the owner's is searched for. |
| Today's date, the owner's words and what to look up | Always | Always | A few lines; all it needs. |

### His own messages

These run by themselves (a slot, the morning, a question, the evening's completions), the morning's with 20 seconds to answer, so they have no rounds: what they send goes always.

| What | Its limit | Now | Proposed | Why |
|---|---|---|---|---|
| His prompt, with his name and line | | Always (cached an hour) | Always | |
| The profile, as the chat's | as above | Always | Always | As above. |
| Why he's asked, and now | two lines | Always | Always | |
| The piece: its label, minutes, times, what earns its free time and its first action; or how a slot went; or a question's task | | Always | Always | What the message is about. |
| The piece's task's own text, with attachments, links and sender | 1,500 characters | Always | Always | A first action or a nudge needs what the work is. |
| Today: slots kept, missed and to come; the next; those left | | Always | Always | |
| Free time now (SlotKept, DayDone) | one line | Always | Always | |
| Done, as the sources say (Completions) | | Always | Always | |
| His last 6 messages of his own, each with its reply | 6 | Always | Always | Stops him repeating himself. |
| The question to put (Question) | | Always | Always | |
| The task list, the calendar, the pace, other days, the chat | | Not sent | Not sent | Not needed for one message about now. |

## How to get there

In this order, each a step that can stop if the one before shows no need:

1. **A cache mark on the newest message**, so a round reads the context from the cache. No change to what's sent.
2. **A list of what can be asked for** in the chat's prompt: tasks' text, and later the week's numbers and the calendar further out. A prompt change: its golden copy and [felix-inputs.md](../spec/felix-inputs.md) change in the same PR.
3. **The next lessons line** in the context, from `PowerPlannerItems.schedulesOn`, school days off left out, with fixtures of made-up classes.
4. **Measure** from the record of calls: how often a message takes a round, its tokens, and whether answers worsen once something moves out.
5. **Move the tasks' own text, then the settings, to on request**, if the measures support it. `ChatRounds.MAX_ROUNDS` stays: rounds are bounded, never a loop, and under the cap.

Not proposed now: the API's own tool use in place of the JSON answer's `details`. It handles several requests more neatly, but means a new caller, schema, goldens and tests for the chat; `details` gives most of the gain for little of the work.

## What needs the owner

- Whether to do any of this, and when: no stage of [plan_v4](../plan_v4/03-stages.md) covers it.
- A change to D-93, for the tasks' own text to go on request.
- A decision for the next lessons line, and how a task's class code is matched to a Power Planner class: by the subject in `Settings.classMap`, or by a Power Planner name added to each entry.
