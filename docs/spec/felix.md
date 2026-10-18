# Felix

Felix is the app's voice: the persona Claude writes as, in the chat and in the messages the app sends about your work. A strict friend who knows your week; Claude is the model behind him.
He can change almost anything about your work, each change made as he gives it with an Undo, and he never touches the blocker, protection or Claude's own settings.
Everything each of his calls is sent, circumstance by circumstance and with examples, is in [felix-inputs.md](felix-inputs.md).
His code is in `felix/`: `Conversation` (the chat, a reply to his notification, what he remembers, the week's check-in, what the app says in his name), `Looks` (what he writes unprompted) and `Outbox` (every message in his name into the chat first, and his one notification made from it), each built like `Focus`: its stores, the clock, and `FelixPorts` for what Android does. `ConversationTest`, `LooksTest` and `OutboxTest` drive them on temporary stores, his calls answered by a stand-in for Claude's API (`FelixHarness`).
The chat is his one visible voice (D-95): every word in his name is a message there, and a notification of his is made from it, never beside it.

## Who he is

- His name and the line that says who he is are settings (`felixName`, `felixLine`), changed in Settings → Felix or by him; every prompt of his names him with them (AI-22).
- Settings → Felix also has how far into a slot he asks (`felixGraceMin`, 10 minutes), when he checks again if he doesn't say (`felixDefaultCheckMin`, 20), and whether he may search the web (`felixSearches`, on).
- A message of his is `Speaker.Felix` (from 4.0.1), stored as "Claude", as it was before; the screens show his name.
- His button is on every screen: Day, Work, Stats, Settings, a task's sheet (*Ask Felix about it*), and the block screen, about the next piece's task (UI-16). It wears the count of his messages unread (`Conversation.unread`): those since you last left the chat, but a question you've answered from his notification.
- While Claude is off (`ModelGateway.claudeKey` null) he says nothing unprompted, and the chat says why it waits.

## The chat

`ui/AskActivity.kt` has two tabs, *Chat* and *Your changes*; its menu has *What Felix remembers* and *Clear the chat*. The chat holds every message of his, what he writes unasked among them, and every one is kept (D-96):
- the live window in `chat.json`: the newest 200 messages or those of the last 60 days, whichever keeps more, and every one of his still unread; what's past it and read moves to `chat-archive.json`, oldest first, the archive written before the window is trimmed (`Outbox.moveWindow`);
- clearing the chat empties both, and leaves what it made and his state;
- it reads as a messaging app's (`ui/ChatScreenState.kt`): each day's date over its first message ("Today", "Yesterday", "Tuesday 13 October"), the time under each, and a *New messages* line over the first of his messages unread when it was opened, for that visit;
- it opens at that message, or, from his notification, at the message the notification was for, tinted for a moment, else at its foot; leaving it, what's in it is read (`ChatState.readAt`, `Conversation.read`);
- scrolled back to its top, the archive's messages show, a page of 100 at a time (*Earlier messages*), never put back in the window;
- a message, his or yours, swiped right past a third of the width (with a haptic tick) or held for *Reply* is the one your next replies to: a strip over the box says whose and its first line, with ✕ to reply to none. Sent, yours carries it (`replyTo`) and shows it over your words, tinted, where it isn't the message just above; tapped, the chat goes to it, from the archive too. Held, *Copy* copies a message's words.
- when his last message is a question of his still waiting for its answer (a check-in's while it's under way, a reading's, how work went, a calendar event's: `Replies.waiting`), the box answers it, as its chips do: its strip shows over the box by itself, and its ✕ sends your words to him instead (D-98).
- under an answer of his, what he did is folded (`ChatSummary`): his words alone, and a chevron at the bubble's corner, red where something went wrong (not made, not looked up, left out). Open, his cards, acts, what he remembered, his searches and what was left out show as they always did, with every Undo; leaving the chat closes every one. A task he shows you, his chips and a button in the app's words stay outside the fold. A message of his that arrives while the chat is on screen, or was unread when it opened, has a label slide out from behind its bubble for five seconds ("Changes: 2 · Remembered · Searched", what went wrong in red), once; tapped, it opens the fold. A message that's just talk has neither.
- You write by keyboard or voice (UI-07). A message from a task's, an event's or a day's page carries what it's about (`About`).
- A message sent while he answers another of yours lets that answer go, paid for and unused, and he answers both together (D-98); what waits, waits together, the newest saying why. While Claude can't be asked (off, resting, at its cap) yours says why, and it's answered once Claude can be: the chat opened again, *Try again*, Claude back, or after a sync.
- While he answers, three dots show; after 10 seconds *Cancel* lets the answer go (AC-08). A call once sent is paid for, so it isn't closed.
- An answer the app can't use (declined, cut off, unreadable) waits for *Try again*, as each try is paid for.
- His suggested replies, up to three (`suggestedReplies`), are chips under his last message (UI-06); a tap is a reply to it, as one from anywhere is (below): a check-in's, a calendar question's or how work went with no call.
- A message in the app's words can carry a button of its own (`ChatMessage.opens`): the week's review to open, its summary to send.

`felix/ChatReader.kt` makes one call a message (`ChatReader.answer`), at medium effort (AC-02), under the cap (AC-05). Someone is waiting, so it goes through a rest after a failure, but not past a key problem or the cap (AC-04).
- `ChatPrompts.system`: who he is, what he can do (below), and how to answer: one to three short sentences saying what he's done, one short question and no change when he needs to know more, and nothing he isn't asked for.
- `ChatPrompts.turns`: what `ChatPrompts.history` takes of the chat (D-97): the conversation's last 12 messages (`HISTORY`: yours, and his answers to you) and his last two nudges (`NUDGES`: what he wrote unasked), each with your reply, in their order, starting with one of yours; each earlier answer with its cards and what became of them; a nudge before any of yours said in your first turn. A reply that went to no call (a check-in's answer) goes only with its nudge, so nudges can't push the conversation out. The message being answered is the newest of yours: what he wrote unasked after it isn't sent with it. A message of yours that replies to one that isn't just before it starts with that one, quoted from the whole chat ("(Replying to your message: “…”)"), and the prompt says that's what it answers; a message the app wrote in his name is marked "(the app wrote this for you)".
- All of it within 40 000 characters (`Pricing.MAX_INPUT_CHARS`): your newest message whole, earlier turns in up to a quarter of the rest, then the context, then the plan, which keeps at least a third of their room.
- `ChatAnswers.parse` checks his answer against what there is. What can't be used is left out and said under the reply ("Left out, as the app can't use it: …").

## What he's told

With your newest message, from one snapshot of the tasks, the calendar and your changes, which his answer is then checked against (`Conversation.chatContext`):
- `ChatPrompts.context`: today and the time; the open tasks (id, title, source, kind, due date and whether it's a real deadline or a plan, minutes, minutes worked, can't start until), each with its steps by place;
- tasks that look like the same work (DX-15), the fortnight's calendar events, the changes in use, your own tasks finished in the last week, and the settings he may change, with their values;
- **your pace**: the multipliers the calibration has learned, by kind and class, with how many timed tasks each rests on, or that nothing's learned yet;
- what each task's minutes come to in the plan: "planned as 54 at their pace" for the app's estimates, "as given" for your minutes and for steps you or he gave, and a paced total as below;
- **each open task's own text** (D-93, `enrich/TaskText.kt`): an assignment's instructions, an email's conversation, a planner item's details, your notes, each at most 600 characters (`ChatPrompts.BRIEF_TEXT`, 12 000 in all), with what's attached, its links and an email's sender; last in the context, so it's what a long one loses first. A longer text is its reading's summary of all of it (`Enrichment.summary`, [readings.md](readings.md)); past the 16 000 characters a reading sees (`Prompts.MAX_TEXT`), of its start, with its end after it; with none (Claude off when it was read, or read before summaries), its start and its end, saying how much of the middle was left out: a conversation is kept oldest first, so its end holds the newest replies;
- `ChatPrompts.plan`: the plan as Day shows it: the slot under way or the next thing to do, today's slots each past, now or ahead (AI-13), what isn't planned and why, other events, and what's planned past the fortnight;
- then each of the plan's next 14 days, with its minutes against its working time and each piece's reason;
- the profile every call carries (AI-05, AI-06; [privacy.md](privacy.md)).

What your message is about comes with it in full, before your words (D-93): a task's whole text (`ChatPrompts.WHOLE_TEXT`, 8 000 characters; past that, its reading's summary, then its start and end) with your answers to its questions, or an event's place and description.

**Reading a task in full** (`details`, D-93): where he needs more of a task's text than the context gives, his answer names it (three at most, never the one the message is about, which came whole) and says only that he's reading it; he's asked again with their whole texts (each an even share of 16 000 characters, 8 000 at most) after your words, and that answer is his, with "Read in full: …" under it. Asked for a web search too, he's asked once with both; where the texts show a need for a search, or the search for a text, once more, never more (`ChatRounds`). The texts he read are kept till he's answered with them, so a failed answer is asked again with them, not for them again.

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

`ChatAnswers.cards` turns his answer into cards, each made as if applied (`Proposal.instant`, `Conversation.applyProposal`): his changes on one card, each new task on its own, and an *Open* card for each task he shows you.
- A changes card becomes an instruction in the words that asked for it, listed under *Your changes*. Changes to your own tasks are made on the task itself; days off go into the school calendar.
- Each card says *Done* or *Added*, with *Undo* (`Conversation.undoProposal`); a task he added has *Change* (the form); one undone has *Redo*.
- One that couldn't be made says "Not made: …" under his answer; a time the plan couldn't keep says where it went: "06:35 had no free time: it's at 10:45." (`ChatAnswers.movedPins`, PL-04).
- His actions are listed under his answer (`ChatMessage.acts`, `Act` in `core/Chat.kt`), each with its *Undo* while `ChatActs.canUndo` finds it as he left it (`Conversation.undoAct`).
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
- He's asked again with what it found added to your newest turn (`ResearchPrompts.told`); that answer is the one kept and acted on, unless what it found shows a need to read a task in full (above): then he's asked once more with that too.
- Under it: what he searched for, and the first four pages found as links (`ChatMessage.searched`, `sources`).
- A look that can't be made says why ("Not looked up: …"), and his first answer stands.
- It's a call apart because the search tool beside the chat's answer schema makes a grammar the API refuses as too large. What a search found, or why one made found nothing, is kept till he's answered with it, so a failed answer is asked again without searching again; a search held back (Claude off or resting, the cap) can be made later.

## His messages

`felix/Looks.kt` looks for a reason to write (`felix/FelixRules.kt`, `FelixRules.due`, pure) at each slot's start and end alarm, at the check he set (`SlotAlarms.armFelix`, `FelixWorker`), and on the focus service's half-minute tick. The model is asked only when there's a reason.
- **Never** in quiet hours or during a busy calendar event. His notifications off silence only them: what he writes is in the chat all the same.
- **SlotEnd**: a slot gone by undone, or a long piece of written work ticked without the photo it needs to earn; once a slot.
- **DayDone**: today's last slot kept, once a day, with the free time there is.
- **Question**: the one question a reading had (AI-10), when he's free, at most once a day a task. Your answer is an instruction on the task, kept with it (`TaskItem.answers`), and the task is read again with it ([readings.md](readings.md)).
- **Completions**: from 19:00, after his check, once an evening, how the work its source confirmed (with no kept slot) went: harder, as expected or easier, as its three replies (LR-05).
- With no slot of today's under way or to come, nothing more. Otherwise, after his check: **SlotStart**, a slot past his grace with nothing done on its task in it (opened, studied in AnkiDroid, or a slot of it kept), at most three times a slot; **SlotKept**, about one kept slot in three.
- A slot ended, a slot kept and the day done are said only while fresh (30 minutes), so a restart hours on says nothing stale.

Each is written by `FelixCaller.say` (AI-22, medium effort) from `felix/FelixPrompts.kt` (`FelixPrompts.describe`), told:
- the reason and the time; the piece with its minutes, slot and what earns its free time, and its first action (AI-15);
- its task's own text, up to 1 500 characters (`FelixPrompts.TEXT`): a longer one as its reading's summary, else its start and end; with what's attached (D-93);
- today's slots kept, missed and to come; the free time; his last six messages of his own from the chat and your reply to each (`ChatHistory.replyOf`: the message of yours answering it, or the next of yours within ten minutes); and the question.

What comes back, and what's done with it:
- He answers with a message of two sentences at most (240 characters), up to three replies (40 each), when he'll check again (5 minutes at least), and anything to remember (`FelixAnswers.parse`).
- Your standing instructions come first: where one means he shouldn't write now, he gives an empty message, and nothing is sent.
- His next check is when he said, else `felixDefaultCheckMin`; a call held or failed is tried again after five minutes.
- His next SlotStart is written up to ten minutes ahead after each plan (AC-10, `Looks.draftAhead`), and sent if it still fits.
- Every message is sent through his outbox (`felix/Outbox.kt`): into the chat first, with why he wrote it (`ChatMessage.kind`), its piece and its next check, then his notification made from it. A silent one (his choice not to write) is kept with no words, shown nowhere, so his rules count it as said (`FelixRules.sent`).

Three more come at set times:
- **The morning** (LR-04, `learn/Briefing.kt`, `Looks.morning`): the briefing is his message whole, up to 480 characters. With no slot today, Claude unavailable, or no answer within 20 seconds, it's the day's title and list instead, in his name in the app's words (`Conversation.morningList`), so every morning is in the chat. His notification is then made again, with what waited overnight (below).
- **The check-in** (LR-02, `felix/CheckInFlow.kt`, `Conversation.askCheckIn`): on Sunday he asks its five questions one at a time in the chat, in the app's words (`FelixWords`), the first with 1 to 5, the second with what the week shows you put off; each reply is kept under its question and the next asked with no call. The form takes them all at once instead, and his questions leave his notification. Then "Thanks: the week's review comes next."
- **The review** (LR-01): after Sunday's review, its note's first line, with *Open the review*.
- **The summary** (NF-02): after the review, "The week's summary is ready; send it?", with *Send it*, which opens it in your email app.

The app asks two more things in his name, in its own words, each answered from his chips with no call:
- **A calendar event the plan can't judge** (Q7, `learn/CalendarTime.kt`): "Open day, Saturday 24 October: how much of the day does it take?", with *All of it*, *A few hours* and *None*; answered for every event of that name. Answered elsewhere (the Calendar page, an instruction), it leaves his notification.
- **How finished work went, with Claude off** (LR-05, `learn/Assessment.kt`): a message for each completion, with *Harder*, *As expected* and *Easier*. With Claude on, they wait for his evening question.

## Notifications and replies

He has one notification (`NoticeId.Felix`), made by his outbox from the chat (`Outbox.notify`, `felix/FelixNotifications.kt`), on the *Felix* channel, in Android's messaging style: his unread messages (after `ChatState.readAt`, with words), oldest first, and under them your replies to them; a reply box answering the newest, its choices his replies. It's made again, not added to, whenever the chat changes: a new message of his sounds, a rebuild doesn't. Tapped, it opens the chat at the first unread.
- Nothing's posted while the chat is on screen, and opening the chat takes it away. A reply from it doesn't (it isn't auto-cancelled), so its Reply stays for the next.
- A question of his answered, here or elsewhere, leaves it.
- In quiet hours the week's review, its summary and a calendar event's question are in the chat at once and left out of it till the morning's briefing makes it again (`NotifyRules.waitsForMorning`).

He has no daily ceiling. The app's routine channels (a calendar move put back, the check-in's reminder, Claude's) go at most four a day and never in quiet hours, the rest held for the next briefing (LR-07, `notify/NotifyRules.kt`).
Every notification of the app's is named once, with request codes of its own for its tap and buttons (`notify/NoticeId.kt`), and posted through `notify/Notifier.kt`, which applies those rules and alone posts and cancels them (`notify/Channels.kt` makes the channels; WorkManager shows the work-under-way ones from each worker's `ForegroundInfo`). The notifications his messages took the place of (`NoticeId.RETIRED`) are taken away at start, where one's left.

A reply, from his notification (`felix/FelixReplyReceiver.kt`), one of his chips, or a swipe or *Reply* in the chat, comes in one way (D-98: `Conversation.ask` with `replyTo`): it's kept in the chat under the message it answers (`ChatMessage.replyTo`) and routed by what that message is (`felix/Replies.kt`):
- a check-in's question: its next asked; a reading's question: an instruction on its task, as above; how work went: the answer on each completion; a calendar event's question: its answer kept. Each with no call.
- anything else: a chat turn, about what the message it answers was; from his notification, your words under his there while he answers, and his answer his next message, made as work of its own (`Conversation.replyFromNotification`, `FelixReplyWorker`). One sent while he answers another of yours goes with it, as one in the chat does (above).

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
