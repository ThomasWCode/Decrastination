# Felix

Felix is the app's voice: the persona Claude writes as, in the chat and in the messages the app sends about your work. A strict friend who knows your week; Claude is the model behind him.
He can change almost anything about your work, each change made as he gives it with an Undo, and he never touches the blocker, protection or Claude's own settings.

## Who he is

- His name and the line that says who he is are settings (`felixName`, `felixLine`), changed in Settings → Felix or by him; every prompt of his names him with them (AI-22).
- Settings → Felix also has how far into a slot he asks (`felixGraceMin`, 10 minutes), when he checks again if he doesn't say (`felixDefaultCheckMin`, 20), and whether he may search the web (`felixSearches`, on).
- Stored chat messages call him `Speaker.Claude`; the screens show his name.
- His button is on every screen: Day, Work, Stats, Settings, a task's sheet (*Ask Felix about it*), and the block screen, about the next piece's task (UI-16).
- While Claude is off (`AppGraph.claudeKey` null) he says nothing unprompted, and the chat says why it waits.

## The chat

`ui/AskActivity.kt` has two tabs, *Chat* and *Your changes*; its menu has *What Felix remembers* and *Clear the chat*. The chat is kept in `chat.json`, the newest 200 messages; clearing it leaves what it made.
- You write by keyboard or voice (UI-07). A message from a task's, an event's or a day's page carries what it's about (`About`).
- One message of yours waits at a time. While Claude can't be asked (off, resting, at its cap) it says why, and it's answered once Claude can be: the chat opened again, *Try again*, Claude back, or after a sync.
- While he answers, three dots show; after 10 seconds *Cancel* lets the answer go (AC-08). A call once sent is paid for, so it isn't closed.
- An answer the app can't use (declined, cut off, unreadable) waits for *Try again*, as each try is paid for.
- His suggested replies, up to three (`suggestedReplies`), are chips under his last message (UI-06).

`enrich/ChatReader.kt` makes one call a message (`ChatReader.answer`), at medium effort (AC-02), under the cap (AC-05). Someone is waiting, so it goes through a rest after a failure, but not past a key problem or the cap (AC-04).
- `ChatPrompts.system`: who he is, what he can do (below), and how to answer: one to three short sentences saying what he's done, one short question and no change when he needs to know more, and nothing he isn't asked for.
- `ChatPrompts.turns`: the last 12 messages (`HISTORY`), starting with one of yours; each earlier answer with its cards and what became of them; a message of his from a notification you answered, in your first turn.
- All of it within 40 000 characters (`Pricing.MAX_INPUT_CHARS`): your newest message whole, earlier turns in up to a quarter of the rest, then the context, then the plan, which keeps at least a third of their room.
- `ChatAnswers.parse` checks his answer against what there is. What can't be used is left out and said under the reply ("Left out, as the app can't use it: …").

## What he's told

With your newest message, from one snapshot of the tasks, the calendar and your changes, which his answer is then checked against (`AppGraph.chatContext`):
- `ChatPrompts.context`: today and the time; the open tasks (id, title, source, kind, due date and whether it's a real deadline or a plan, minutes, minutes worked, can't start until), each with its steps by place;
- tasks that look like the same work (DX-15), the fortnight's calendar events, the changes in use, your own tasks finished in the last week, and the settings he may change, with their values;
- **your pace**: the multipliers the calibration has learned, by kind and class, with how many timed tasks each rests on, or that nothing's learned yet;
- what each task's minutes come to in the plan: "planned as 54 at their pace" for the app's estimates, "as given" for your minutes and for steps you or he gave, and a paced total as below;
- `ChatPrompts.plan`: the plan as Day shows it: the slot under way or the next thing to do, today's slots each past, now or ahead (AI-13), what isn't planned and why, other events, and what's planned past the fortnight;
- then each of the plan's next 14 days, with its minutes against its working time and each piece's reason;
- the profile every call carries (AI-05, AI-06; [privacy.md](privacy.md)).

## What he can do

Everything he gives is made as his answer comes, each with its Undo (next section).
- **New tasks of your own** (`newTasks`, at most ten): title, kind (Homework, Revision, Admin or Event), subject, due date and time (with no time, an event's is 09:00, anything else's the end of the day), start, minutes, notes, and steps for a big one. Each is checked as the form checks one (`OwnTasks.problem`), its dates from a month back to just over a year ahead.
- **Changes** (`changes`, at most 40), of an instruction's types (`InstructionPrompts.CHANGE_TYPES`): a task isn't one, can't start until a date, waits for another, is due by a date, takes N minutes, goes on one day, has its next piece at a time, is another's work, has these steps or this name;
- and for days and events: how long an event takes, a day's limit, busy times, days off and half-days (into the school calendar, PL-16).
- **Steps** for any task but Anki's, whose steps are its cards (AI-19): laid over the source's and the reading's (`TaskItem.userSteps`), ticks kept by name. They must add up to the task's minutes, or come with a Minutes change for the new total, or they're left out.
- **Memories** (`memories`): facts and standing instructions (below).
- **Actions** (`actions`, AI-14): tick or untick a step (its slot kept where the plan has one, earning as a tick does); finish, reopen or delete one of your own tasks (or feedback done by its tick, NF-06); open a task on a card; set a setting.
- **Settings**, only those in `data/FelixSettings.kt`: his name and line, when he asks and checks, the briefings and the check-in, working hours, the morning break, the shortest school time used, pieces and their breaks, breaks outside, margins, undated work, the Anki deadline and textbook.

Never the blocker's settings, protection's or Claude's, the calendars, your classes, your profile, the Teams syncs, his web search or where the summary goes; a value the Settings screen wouldn't take is refused (`FelixSettings.set`).
A task from Teams, Power Planner, Gmail or AnkiDroid is done when its source says so: he can't mark one done or delete it, only say it isn't a task.

## Made at once, with an Undo

`ChatAnswers.cards` turns his answer into cards, each made as if applied (`Proposal.instant`, `AppGraph.applyProposal`): his changes on one card, each new task on its own, and an *Open* card for each task he shows you.
- A changes card becomes an instruction in the words that asked for it, listed under *Your changes*. Changes to your own tasks are made on the task itself; days off go into the school calendar.
- Each card says *Done* or *Added*, with *Undo* (`AppGraph.undoProposal`); a task he added has *Change* (the form); one undone has *Redo*.
- One that couldn't be made says "Not made: …" under his answer; a time the plan couldn't keep says where it went: "06:35 had no free time: it's at 10:45." (`ChatAnswers.movedPins`, PL-04).
- His actions are listed under his answer (`ChatMessage.acts`, `Act` in `core/Chat.kt`), each with its *Undo* while `ChatActs.canUndo` finds it as he left it (`AppGraph.undoAct`).
- Undo takes back a tick (one of yours it finished opens again), ticks an untick again, opens a finished task again or finishes a reopened one as it stood, restores a deleted task with the cards that added it, and puts a setting back.
- Once something else has changed it, its Undo says it can't be undone any more.

## Minutes he sets, and your pace

Minutes he sets (a new task's, a Minutes change, each step's) are planned exactly as he sets them: your pace stretches only the app's own estimates (`Calibration.multiplierFor`). So he allows for your pace himself.
- Where he sizes steps for a task the app estimated, he gives a Minutes change for the new total, marked `paced`: the app's estimate at your pace. Where you say how long something takes, he uses your minutes, unpaced.
- Only the chat's schema has `paced`. Reading his answer, the app keeps with a paced Minutes change the app's estimate it came from (`Change.pacedFrom`, the task's `appEffortMin`) and the task's content at that moment (`Change.pacedHash`, `Enrichments.inputHash`), in `InstructionAnswers.change`.
- On the task they're `userEffortPacedFrom` and `userEffortPacedHash` (`TaskItem.pacedMinutes`), planned as given like any minutes of yours.
- While the content is unchanged (`TaskItem.pacedTeaches`), finishing it is measured against that estimate, so your pace still learns from it; a re-read for newer prompts keeps the content the same.
- New content (an instruction, an email, your answers, a photo) may mean new work: it then teaches nothing, as a total of your own never does (`CompletionRecord.yourEstimate`).
- He's told when the app now estimates otherwise, or the task has changed: "as given: the app's 60 at their pace, which it now puts at 75". The task's sheet says "The app's 1 h at your pace", and the list's pill "Minutes at your pace".

## Web research

When a message needs facts neither you nor the app have given him (a competition's rules, a specification), and `felixSearches` is on, his answer asks to look something up (`research`, a few words) and says only that he's looking (AI-24).
- A call of its own then searches (`enrich/WebResearch.kt`, `WebResearcher.look`): Claude's web search tool, at most three searches, located in the UK, with no profile, never about you, answering in at most 15 lines.
- He's asked again with what it found added to your newest turn (`ResearchPrompts.told`); that second answer is the one kept and acted on.
- Under it: what he searched for, and the first four pages found as links (`ChatMessage.searched`, `sources`).
- A look that can't be made says why ("Not looked up: …"), and his first answer stands.
- It's a call apart because the search tool beside the chat's answer schema makes a grammar the API refuses as too large. What a search found is kept till he's answered with it, so a failed answer is asked again without searching again.

## His messages

`learn/Felix.kt` looks for a reason to write (`learn/FelixRules.kt`, `FelixRules.due`, pure) at each slot's start and end alarm, at the check he set (`SlotAlarms.armFelix`, `FelixWorker`), and on the focus service's half-minute tick. The model is asked only when there's a reason.
- **Never** in quiet hours, during a busy calendar event, or while his channel can't be seen.
- **SlotEnd**: a slot gone by undone, or a long piece of written work ticked without the photo it needs to earn; once a slot.
- **DayDone**: today's last slot kept, once a day, with the free time there is.
- **Question**: the one question a reading had (AI-10), when he's free, at most once a day a task. Your answer is an instruction on the task, kept with it (`TaskItem.answers`), and the task is read again with it ([readings.md](readings.md)).
- **Completions**: from 19:00, after his check, once an evening, how the work its source confirmed (with no kept slot) went: harder, as expected or easier, as its three replies (LR-05).
- With no slot of today's under way or to come, nothing more. Otherwise, after his check: **SlotStart**, a slot past his grace with nothing done on its task in it (opened, studied in AnkiDroid, or a slot of it kept), at most three times a slot; **SlotKept**, about one kept slot in three.
- A slot ended, a slot kept and the day done are said only while fresh (30 minutes), so a restart hours on says nothing stale.

Each is written by `FelixCaller.say` (AI-22, medium effort) from `enrich/FelixPrompts.kt` (`FelixPrompts.describe`), told:
- the reason and the time; the piece with its minutes, slot and what earns its free time, and its first action (AI-15);
- today's slots kept, missed and to come; the free time; his last six messages and your replies; and the question.

What comes back, and what's done with it:
- He answers with a message of two sentences at most (240 characters), up to three replies (40 each), when he'll check again (5 minutes at least), and anything to remember (`FelixAnswers.parse`).
- Your standing instructions come first: where one means he shouldn't write now, he gives an empty message, and nothing is sent.
- His next check is when he said, else `felixDefaultCheckMin`; a call held or failed is tried again after five minutes.
- His next SlotStart is written up to ten minutes ahead after each plan (AC-10, `Felix.draftAhead`), and sent if it still fits.
- Every message, silent ones too, is a `FelixRecord` in `log.json`, kept 30 days.

Three more come at set times:
- **The morning** (LR-04, `learn/Briefing.kt`, `Felix.morning`): the briefing is his message whole, up to 480 characters, under the day's title, on the briefing's channel. With no slot today, Claude unavailable, or no answer within 20 seconds, the briefing is the day's list instead.
- **The check-in** (LR-02, `learn/CheckInFlow.kt`, `AppGraph.askCheckIn`): on Sunday he asks its five questions one at a time, the first with 1 to 5, the second with what the week shows you put off; the form takes them all at once instead. Then "Thanks: the week's review comes next."
- **The summary** (NF-02): after Sunday's review, "The week's summary is ready; send it?"; a tap opens it in your email app, to send.

## Notifications and replies

`notify/FelixNotifications.kt` posts his messages on the *Felix* channel: his words, a reply box whose choices are his replies, and a button for each of up to two cards still to make. One at a time: a newer one takes the last one's place. Tapping one opens the chat.
He has no daily ceiling. The routine channels (briefing, calendar questions, weekly, Claude) go at most four a day and never in quiet hours, the rest held for the next briefing (LR-07, `notify/NotifyRules.kt`).

A reply (`notify/FelixReplyReceiver.kt`, `AppGraph.replyToFelix`) goes:
- to the check-in, as its next answer; to his completions question, as the answer on each completion; to his question about a task, as above;
- otherwise into the chat: his message put there if it isn't yet, your reply asked there, and the answer posted as his next notification while the chat isn't on screen (`FelixReplyWorker`). An unprompted message of his is in the chat only once you've replied to it.

## What he remembers

`core/Memory.kt` keeps `memory.json`: facts about you, each under a short topic that a newer fact replaces, and standing instructions on how he behaves (AI-21).
- The chat and his messages give at most five a time; an empty text forgets one. Busy times, limits and days off are changes, not memories.
- Facts have 6 000 characters of room, the oldest going first; standing instructions at most 20, one more refused with a line saying so.
- Each is said under his answer ("Remembered: …", "Forgot: …", "Felix will: …"), with its *Undo* while what it did is unchanged (`Memories.undo`), bringing back what it replaced or pushed out.
- Settings → Felix and the chat's menu list them all, each with its delete, and take a standing instruction in your own words (`ui/MemorySection.kt`).
- All of it goes in every call's profile: every standing instruction, and the facts newest first.

**The tidy** (`enrich/MemoryTidy.kt`): past 70 % of the room, Sunday's review sends the facts to Claude in a call of its own, which gives back at most two thirds as many lines.
They're kept only if they make room and the facts are unchanged meanwhile (`MemoryTidy.after`, `applied`). The facts ahead of the tidy and after it go with the week's review, whose page can undo it. Standing instructions are never tidied.

## The instructions screen

*Your changes*, the chat's second tab, lists the changes in use, each with *Take back*, and those to check: still being read, or unclear with why (*Write again*, *Discard*).
It holds Felix's changes, the quick changes made on a task's sheet without Claude, and instructions read by `enrich/InstructionReader.kt`.
- `InstructionPrompts` reads your own words about a task, an event, a day or nothing in particular into changes of the chat's types, given the open tasks and the fortnight's events (`InstructionPrompts.describe`).
- It reads an instruction you write again, and your answer to Felix's question about a task.
- `InstructionAnswers.parse` checks the reading: if one change can't be made, none are, and it says why.
- A reading is applied as soon as it's read, armed or not, and listed for you to check and take back. One that would make tasks wait for each other in a circle, put work after a deadline still to come (overdue work can go to any time, D-30), or leave one of your own as the form wouldn't take it is marked unclear instead.
- It's never told your pace, and its schema has no `paced`: its totals are always your own reckoning (`Change.pacedFrom` empty).
