# The chat as Felix's one visible voice

Everything Felix says is a message in the chat. A notification is the chat reaching you while you're not on it: made from the chat, holding what you haven't read, cleared when you open it, and tapped, opening the chat at that message. A reply, from the notification or swiped in the chat, is a turn in the one conversation, answered the same way whichever way it came. Under his bubble, what he did is folded away until you open it, and a label slides out to say what's there. Built in Stage 5 ([03](03-stages.md)), inside the Conversation service Stage 4 makes, on the notifier of Stage 1 and the stores of Stage 3.

Your answers are settled here: his channel for everything he says (Q3), the folds and the label as recommended (Q4, Q5), swipe right on any bubble with the quote told to the model and replies routed by kind (Q6), the divider, the position and the badge (Q7), every message kept, the older ones in an archive (Q8, Q23), one notification for the conversation (C-Q4), his past records put in once (C-Q5), the calendar's questions and "how did it go?" as his (C-Q6).

## Before Stage 5 (3.3.x)

- **Two homes.** His unprompted messages were `FelixRecord`s in `log.json` (kept 30 days) and notifications; the chat (`chat.json`) had your messages and his answers. A record reached the chat only when you replied to its notification (`Conversation.replyToFelix` copied it in). On your phone on 18 Oct: 35 records in 30 days, 27 replied to, 1 in the chat. Tapping one of the others opened the chat without it.
- **Seven producers**, each posting its own notification: his looks and the morning (`felix/Looks.kt`), the morning's title and list (`learn/Briefing.kt`), the check-in (`Conversation`'s check-in flow, `felix/CheckInFlow.kt`; the form `ui/CheckInActivity.kt`), the weekly review (`learn/Review.kt`), the summary's offer (`AppGraph.writeSummary`, id 4004), "How did it go?" (`learn/Assessment.kt`, 4100 tagged, on his channel), and answers to a notification's reply (`Conversation.tellAnswer`).
- **One notification at a time**: `FelixNotifications` posted his looks, the morning, the check-in and the answers under id 4200, so each new one took the last one's place; the calendar's questions have had an id of their own, 4400 (`NoticeId.CalendarQuestion`), since 3.3.0.
- **His state in three places**: the log's records, `RuntimeState.felix` (next check, a draft, a retry, the questions waiting) with `assessLater` and `checkInDraft`, and the chat.
- **The chat buried what he did**: every change, memory and search sat under his bubble as cards and small lines; a message that did three things was a screen long.

## 1. The conversation

One store, `chat.json`, holds everything said between you and Felix, and his state ([04](04-data-and-migrations.md) §The chat has the types and the migration).

**Kinds.** `ChatMessage.kind: MessageKind`, `Chat` by default (yours, and his answers to you), else why he wrote: `Morning`, `SlotStart`, `SlotEnd`, `SlotKept`, `DayDone`, `Question`, `Completions`, `CheckIn`, `Review`, `Summary`, `EventQuestion`, `Reminder` ([07](07-notes-and-reminders.md)), `Note` (a line the app wrote in his name about something it did or let go: a reminder let go because its task closed; "teams says the worksheet's handed in" after a `sync` he asked for, [06](06-what-felix-can-do.md) G2). A Gmail write that couldn't be made is the app's own notification, not a message of his (Q18: C). 3.3.x's `ChatMessage.reason` became the kind, read from the stored `reason` once.

**Who wrote the words.** `byApp: Boolean`: the words are the app's, in his name, with no model call (the check-in's questions, "Thanks: the week's review comes next.", the list-only briefing, a reminder sent as you set it when he couldn't write, a `Note`). The screen shows them as his; the model's history is told "(the app wrote this for you)" so he doesn't take fixed words for his own style ([09](09-his-voice.md)). The fixed lines live in one place, `FelixWords`, made in 5a with today's words and reworded in 10b.

**The producers, each through the outbox** (§2):

| Message | Before (3.3.x) | After |
|---|---|---|
| His looks: SlotStart, SlotEnd, SlotKept, DayDone (`learn/Felix.kt`, `Looks` after Stage 4) | a `FelixRecord` and a notification under 4200 | a message of that kind, with `about` the piece's task, `pieceKey`, `nextCheckAt`, `suggestedReplies` |
| A reading's question (Reason.Question) | the same | kind `Question`; your reply is the task's answer (as now, `Looks.answered`) |
| The evening's completions (Reason.Completions) | the same, `asks` on the record | kind `Completions`, `asks` on the message; Harder / As expected / Easier recorded with no call |
| "How did it go?" with Claude off (`Assessment.post`, one notification a completion) | the app's own notification, on his channel (4100, tagged) | the same question in his name, `byApp`, one message a completion, the three replies (Q2, C-Q6) |
| The morning (`Briefing.run` → `Looks.morning`) | a notification on the briefing's channel, under the day's title; without his words, the day's list | kind `Morning`, his words; without them (no slot, Claude off or resting, no answer in 20 s) the day's title and list as a `byApp` message, so every morning is in the chat (Q2) |
| The check-in's five questions (`askNext`) and its thanks | records, under 4200 | kind `CheckIn`, `byApp`, replies 1–5 and the week's put-off work as now; answers routed as `checkInReply` does |
| The review's note (`Review.run`, id 4003 → the check-in screen) | the app's notification on the weekly channel | kind `Review`, `byApp`: the note's first line and a button opening the review (`opens`, Q2) |
| "The week's summary is ready; send it?" (NF-02, `writeSummary`) | a record, and a notification of its own (id 4004) on his channel, or the week's in quiet hours, opening Reviews | kind `Summary`, `byApp`, in his register ("the week's summary's ready, want me to send it?"), with a button that opens the email; the email itself is about you in the third person, not in his voice (Q2, Q25; [09](09-his-voice.md) §The summary email) |
| The calendar's questions (`CalendarTime.ask`, 4400 tagged, `NoticeId.CalendarQuestion` since 3.3.0) | the app's, on the calendar channel, three buttons | kind `EventQuestion`, `byApp`, replies All of it / A few hours / None → `CalendarTime.answer`, no call (C-Q6) |
| Answers to a reply from a notification (`tellAnswer`) | a record and a notification | nothing special: his answer is a `Chat` message as any; the notification shows it because it's unread |

Not his, and not in the chat: syncs under way, protection, free time and its end, the model's alerts (a dropped plan, a key problem), a calendar move put back, a Gmail write that failed.

**The readers.** `ActivityLog.felix` goes: the records of the last 30 days become messages once (C-Q5). Three readers switch to the chat: `FelixRules.due` (`sent` = today's messages of his, silent ones included: a message he chose not to write is kept as an empty-text message of its kind, as the record was, shown nowhere), `FelixPrompts.describe`'s "Recent, oldest first" (his last six with text, and your replies to them: a reply is the next message of yours with `replyTo` it, or the next of yours at all within ten minutes), and `ReviewInput`/`Summary.input`'s "Felix's counts" (messages of his that week, and replies).

**His state** (next check, drafts, retry, questions waiting, completions to ask about, the check-in's draft) is the conversation's (`ChatState.felix`), not `RuntimeState`'s.

**The speaker is Felix** (`Speaker.Felix`, read from the stored "Claude"), from 5b.

**Every message kept; the model sees less** (Q8, Q23). Before 4.0.0 the chat kept its last 200 messages (`MAX_CHAT`). You want them all kept for you to read, with what's sent to him cut much earlier, so the chat is in two parts:

- **The live window**, `chat.json`: the newest messages, the last 200 or 60 days, whichever keeps more, and every unread message whatever its age (a message is only moved once it's been read, so the notification, the badge and the opening position, which read the window, never lose one). It's what the screen opens on, what his rules read (`FelixRules.due`'s `sent` is today's messages), what his own messages' context reads (his last six, `FelixPrompts.RECENT`), and what the model's history is taken from: the conversation's last 12 messages (`ChatPrompts.HISTORY`, yours and his answers) and his last two nudges with your replies (`ChatPrompts.NUDGES`), within a quarter of the room, so his nudges can't push the conversation out (your call while 5a was built, 18 Oct: D-97).
- **The archive**, `chat-archive.json` (`ChatArchive`), oldest first, append-only. After a message is written, what's past the window moves there: the archive's `update` first, then the live store's trim, so a crash between the two leaves a duplicate (dropped by id at the next move), never a hole. From 4.0.4 it's a file a month (`ChatArchiveStore`), each read only when it's wanted, never at start (your call, 19 Oct); the screen shows it only when you scroll up past the oldest live message ("Earlier messages", a page of 100 at a time from its end, the newest months read as far as the pages go); it's never sent to the model and never read by his rules. A reply swiped on an archived message still carries its `replyTo`, and the quote is read from the archive.
- **Clear the chat** empties both, and its dialog says so; Reset → "The chat and Felix's history" too. The backup carries the archive while the backup fits its cap (`Backups.MAX_BYTES`, 8 MB); past that, the archive's oldest messages are what's left out first, and Settings → Backup says so. An older build ignores the file. What his rules count today ("said once a slot") starts afresh after a clear, which is fine.

## 2. The outbox

```kotlin
class Outbox(
    private val conversation: JsonStore<ChatState>,
    private val archive: JsonStore<ChatArchive>,
    private val notices: Notices,
    private val chatOnScreen: () -> Boolean,
    private val clock: WallClock,
) {
    /** [message] put in the chat first, so it's never lost; the window moved; then his one notification rebuilt. */
    suspend fun send(message: ChatMessage)
    /** His notification from the chat's unread messages, or cancelled when there are none; nothing while the chat's on screen. */
    fun notify(now: Long)
}
```

- **The chat first.** The message is written to the conversation before anything is posted, so a notification can't point at a message that isn't there.
- **Every producer goes through it**: his looks, the morning (the briefing's title and his words become one morning message of his), the check-in's questions, the review's note, the summary's offer, the completions' question, the calendar's questions, his answers. None posts a notification of its own.
- **Rebuilt from the chat, not accumulated.** `notify(now)` posts his notification from the chat's unread messages (`from == Felix`, `at > readAt`, with text), or cancels it when there are none; `send` calls it after writing, the screen on pause after setting `readAt`, and the briefing in the morning. So "held back" needs no copy: a kind that went on a ceilinged channel in quiet hours before (the calendar's questions, the review, the summary; the morning's own message is the briefing, at its time) is in the chat at once and simply left out of the notification until the morning's rebuild (`NotifyRules` keeps its say, by kind instead of channel). The `heldNotifications` list stays for the app's own notifications only.
- **When the chat's on screen** nothing's posted (`chatInFront`), as now; when another screen of the app is in front, a notification still is: "not on the chat screen" is the test.

## 3. The notifier

### One registry (Stage 1)

`NoticeId` names every notification the app makes, with a range of request codes for its buttons, so no two can collide:

| Kind | Id at 3.2.0 | Id after | Several at once |
|---|---|---|---|
| Work under way: sync, readings, review, Felix's look, a reply | 1001–1005 | the same | one each |
| Free time, its end | 2003, 2004 | the same | one each |
| Protection (the watchdog) | 3001 | the same | one |
| Briefing, check-in, review, summary | 4001–4004 | 4002 stays (the check-in's form reminder); 4001, 4003 and 4004 are retired in 5a, their messages his | one each |
| How did it go? | 4100 | retired in 5a, the question his | |
| Calendar questions | 4200 (tagged) | 4400 in Stage 1; retired in 5a, the questions his | |
| Calendar moves | 4300 | the same | one a move (tagged) |
| A Gmail write that failed | | 4500 (Stage 11) | one a conversation (tagged) |
| Model alerts | 5001, 5002 | the same | one each |
| Felix's conversation | 4200 | 6000 | one, holding every unread message |

Each kind's buttons get request codes in a range of their own (Felix's 6001–6099, and so on), as each kind's have been since 3.3.0 (`NoticeId.codes`). The ids that stay keep a notification already showing on your phone where it is through the update.

### One notification, his (5a)

At `NoticeId.Felix`, in `NotificationCompat.MessagingStyle` (C-Q4: A; one a message, grouped, is the fallback should One UI mishandle the style):

- the person is him, his name from Settings, the app's icon; the conversation title is nothing (a one-to-one chat);
- its messages are his unread ones (`at > ChatState.readAt`), oldest first, each at its time, text only (a card's gist in words: "Added “Trip form”" after the text, as the history tells him);
- a reply from it is shown in it as yours, under his, while he answers (as a speaker of its own, "You": One UI hides the Reply while the newest message is the user's own); his answer is added as it comes;
- the reply box offers the newest message's `suggestedReplies` as choices, as now; the buttons for cards (Add / Apply) go: since 3.1.0 nothing waits for a button, and `tellAnswer` only ever found pre-16-Oct cards;
- tapped, it opens the chat at the first unread (§4); opening the chat clears it;
- `setOnlyAlertOnce` where it's rebuilt for a reply sent, as now, so it doesn't sound again; a new message of his sounds.

Samsung groups an app's notifications under its name; his messaging-style one shows its last lines in the group. The app's own kinds (free time, protection, the model's alerts, a move put back, a failed write) keep their own ids and show beside his.

### One policy

`NotifyRules`' ceiling of four routine notifications a day, quiet hours, and those held for the next briefing apply in one place, `Notifier.post`, as now; from 5a the rule is by the kind of his message where the channel used to decide (Review, Summary and EventQuestion wait for the morning's rebuild, the morning's own message being that rebuild; his looks and the check-in don't). Felix's and protection's stay outside the ceiling. His channel being off no longer silences him: the chat is his voice, so his looks still write and the message goes in the chat; the channel's state is checked where the notification is built (`Outbox.notify`), not before the message is written. Before 5a, `Looks.due` and the morning returned before writing when the channel was off; that check moved to the outbox in 5a. (Claude being off still means no look: that's a cost rule, not a notification one.)

### Channels (Q3: A)

His channel (`Channels.FELIX`) for all of it, the morning included: one notification can't be on two channels. The briefing's channel then carries nothing: `Channels.create` deletes it once nothing's left on it, as it does the old `daily` and `how-it-went` channels today. The calendar channel keeps a move put back; the weekly channel keeps the check-in's form reminder (`Conversation.remindCheckIn`, posted by `FelixPorts.remindForm`) and nothing else; Stage 11 adds `SOURCES` for a write that failed. The briefing's time (`Daily.nextBriefing`) stays: the morning's message is written then, and his notification is rebuilt then with whatever was held overnight.

## 4. Opening at the message

- **Read position.** `ChatState.readAt: Long`: set to now when the chat screen pauses (so what arrived while you were on it counts as read), and at the moment a notification's tap opens it. Before that update, the screen remembers `firstUnread` for the divider.
- **The divider.** A thin "New messages" line above the first unread, as messaging apps show it, for that visit only.
- **Where it opens.** From a notification: `AskActivity.intent(context, openAt = messageId)`, carried in the intent (singleTop: `onNewIntent` takes it); the list scrolls to that message (or the first unread, with none given) and tints its bubble for a second. Opened from his button or the menu: at the first unread if there is one, else the foot, as now.
- **The badge.** His button (`ui/FelixButton.kt`, UI-16) wears the unread count on every screen that has it (Q7: A); the block screen's too.
- **A message gone** (the chat cleared): the tap opens the chat at its foot.

## 5. WhatsApp-like

**The look.** Date separators between days ("Today", "Yesterday", "Tuesday 13 October"); the time under each bubble in small type; his bubbles left, yours right, as now; `byApp` messages in his bubble like any other. The typing dots stay (AC-08). The input bar stays, with the "about" chip and voice.

**Long-press** on any bubble: *Reply*, *Copy*. (Later, maybe: *Undo all of it* on his.)

**Swipe to reply.** A horizontal drag on any bubble, his or yours, to the right (Q6: A), past a third of the width: a haptic tick, a reply arrow shown while dragged, and the bubble springs back (built as a horizontal `draggable`, so nothing's dismissed); vertical scrolling wins where the drag is more down than across, as Compose's gesture system decides. Then:

- a strip over the box: "Replying to Felix" (or "to you") with the quoted text's first line and an ✕;
- the message sent carries `replyTo = id`, and its bubble shows the quote in a tinted block above your words; tapping the quote scrolls to it;
- a reply to one of his `suggestedReplies` chips is the same: `replyTo` his message.

**One entrance for replies.** `Conversation.ask(text, about, id, replyTo)` takes over `replyToFelix`'s routing, by the kind of the message replied to (`Replies.route`, pure), so the chat and the notification behave alike:

| Replying to | Done with no call | Else |
|---|---|---|
| `CheckIn` | the answer kept and the next question asked (`checkInReply`) | |
| `Completions`, and the words are Harder / As expected / Easier | recorded on each completion (`Assessment.record`) | any other words: a chat turn |
| `Question` | the task's answer: an instruction, the task read again (`Looks.answered`) | |
| `EventQuestion`, and the words are one of its three | `CalendarTime.answer` | any other words: a chat turn (he can make an EventTime change) |
| `Reminder` | Done, Snooze ([07](07-notes-and-reminders.md)) | a chat turn |
| anything else | | a chat turn, the quoted message told to the model |

`FelixReplyReceiver` then calls `replyFromNotification(words, id)`, which sends it as any reply (`send` with `replyTo`), and the outbox rebuilds the notification with your words under his; `FelixReplyWorker` stays, for the answer to be paid for and shown after the receiver's gone.

**What the model is told.** `ChatPrompts.turns` prefixes your turn with the quoted message where it isn't the one just before yours: "(Replying to your message: “…”)" for his, "(Replying to their own earlier message: “…”)" for yours; the last 12 messages may not hold it. One sentence in the chat's prompt says a message may quote what it replies to and that's what to answer. The chat golden and `FelixInputsDocTest`'s page change; the readings' prompt version doesn't (it's not a reading's prompt).

## 6. The extras folded

Under his bubble before 4.0.2, in order: what he searched and the pages found (`Searched`), a card for each change set, new task and task to open (`ProposalCard`), what he remembered (`Remembered`), what his actions did (`Acts`), the lines with nothing to undo (`done`: not made, where a time went, read in full), and `note` (left out).

**After.** A chevron at the bubble's bottom-right corner, shown only when the message has anything folded. Closed, the bubble shows his words alone. Open, the tail unfolds below the bubble as it was drawn before 4.0.2, with every Undo. The state is the screen's (the state holder's open set); it's cleared when the screen pauses, so coming back finds every message closed, as you asked. A message with nothing to fold (an answer that's just talk, [09](09-his-voice.md) §Just talking) has no chevron and no label. What folds and what doesn't (Q4: A):

| Folds | Stays visible |
|---|---|
| change cards, new-task cards, acts, memory edits, searched and sources, read in full, not made, left out | an *Open* card (the point of a message that shows you a task), his `suggestedReplies` chips, a `byApp` message's own button (the review to open, the summary to send) |

A message that has a problem in its tail (not made, left out, not looked up) colours the chevron red, so it isn't hidden (Q5: A).

**The label that slides out.** For a message of his that arrives while the chat's on screen (its `at` after the screen resumed), and once for each unread one when the chat opens (Q5: A), a small label drawn behind his bubble slides out to its right (below it, where the row has no room to its right) 300 ms after the bubble appears, stays five seconds, and slides back behind it (`AnimatedVisibility` with a horizontal slide, the bubble above it in z-order so it emerges from under the bubble's edge). Its text is `ChatSummary.line(message)`, pure and tested: "Changes: 2 · Remembered · Searched", each part only where it applies, and "1 not made" in red where something was refused. "Changes" counts changes made + tasks added + actions done (a tick, a setting), since each is one thing he did (Q5: A). Tapping the label opens the tail. With nothing to say, no label.

## What to watch

- **What his rules count.** Moving "what he's sent today" from the log to the chat must count the same messages; `FelixRules`' tests run on both before the switch (the test's records turned into messages by the migration's own function).
- **The briefing's timing.** The morning moves to his channel but keeps its time; a sound you'd set on the briefing's channel is set on his instead.
- **Notifications you've turned off.** His channel off silences the notification only; his looks still run and the message still goes in the chat, which is the change from the `Notices.shown` gate in his rules before 4.0.0.
- **The chat's size.** A message of his a slot, on a busy day, is a dozen messages; the window keeps the live file in bounds, the archive takes the rest.

## Tests

- The outbox's: in the chat before any notification; nothing posted with the chat in front; every producer through it, by a test that fakes each trigger (the Conversation's tests, `CheckInFlowTest`, a briefing test, an assessment test, a calendar-question test).
- `Replies.route(message, words)` (pure): each kind to its handler, the three words of Completions and EventQuestion matched whatever their case, anything else to the chat.
- `ChatSummary.line`: each part, the counting, the red count.
- The screen's state holder: `firstUnread` from `readAt`; the open set cleared on pause; which messages get a label (arrived since resume, or unread on open, once); the earlier pages.
- `ChatPrompts.turns` with `replyTo`: the quote prefixed, not for the message just before, the doc page regenerated (`FelixInputsDocTest`).
- `FelixRules.due` against messages instead of records: the same triggers for the same day.
- The notification builder (pure part): the unread messages, oldest first; none → cancel; `readAt` moved → fewer; held kinds left out before the morning.
- The migration from the Stage 0 fixtures, twice.
- The archive: the move past the window (by count and by age; never an unread message), run twice, nothing doubled or lost; a page of earlier messages; Clear the chat empties both; a backup with room carries it, one without leaves the oldest out.
- A just-talk answer (no cards, no acts, no memory): `ChatSummary.line` empty, no chevron.

## Phone check

After the install, with the 4.0.0 build (then 4.0.1, 4.0.2):

- `felix-say --es reason SlotStart` twice (two paid calls): two messages in the chat, one notification with both lines; you tap it: the chat opens at the first, with the divider above it; the notification's gone.
- Reply from the notification with a chip: your words appear in the notification under his; his answer follows; both in the chat with `replyTo` set.
- Swipe one of his older messages, send a word: the quote shows in your bubble; the model's turn (`chat-context`) shows the "(Replying to your message…)" line.
- A message with a change: the chevron, nothing under the bubble; the label slides out and back; open it, Undo works; leave and return: closed.
- `briefing`: the morning in the chat (his words, or the list as his); on the one notification.
- `check-in` (Sunday's flow): the questions in the chat, answered by chips, routed without a call (no call in Settings → Claude → Recent calls).
- `assess --es task <id>` with Claude off: the question in the chat in his name; Harder recorded on the completion.
- The log's old records: in the chat at their times; `felix` (the debug command) reads his state from the chat.
- Scroll up past the oldest message: earlier messages load; `chat-context` shows only the window's last 12.
- The agent never taps: the taps and swipes above are yours to make and report.
