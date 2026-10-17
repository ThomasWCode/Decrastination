# The chat as Felix's one visible voice

Everything Felix says is a message in the chat. A notification is the chat reaching you while you're not on it: made from the chat, holding what you haven't read, cleared when you open it, and tapped, opening the chat at that message. A reply, from the notification or swiped in the chat, is a turn in the one conversation, answered the same way whichever way it came. Under his bubble, what he did is folded away until you open it, and a label slides out to say what's there.

Built on the cleanup plan's Phase 1 ([05-felix-and-notifications.md](../plan_targeted_cleanup/05-felix-and-notifications.md)): the conversation store, the outbox every producer sends through, and the notifier's registry with his one stacked notification (its Q4, answered A; its Q5, A: his past records put in once; its Q6: the calendar's questions and "how did it go?" are his). That document was written knowing the chat work was coming, with part of this list; this one says the features themselves, and where they go further than 05 assumed.

## What 05 settles, and what this adds

| 05 lays down | This adds |
|---|---|
| `ChatMessage.kind` for his messages; what his records held moves onto the message | The full set of kinds, the app's own words in his name among them (§1); `replyTo` used by the chat, not only notifications (§4) |
| The outbox: chat first, then a notification if the chat isn't on screen | The notification rebuilt from the chat's unread messages, so held and stacked are the same mechanism (§2) |
| A read state, the chat opening at the first unread | The divider, the badge, the highlight; `readAt` set as the screen leaves (§3) |
| One conversation notification (Q4 A) | Its shape in Android's messaging style; the morning in it (§2, Q3) |
| "Swipe to reply: `replyTo`; the history saying what each reply was to" | The gesture, the quote in the bubble, replies routed by the kind of the message they answer, without a call where none is needed (§4) |
| "The collapsed extras: the open or closed state kept by the screen only" | What folds and what stays, the chevron, the label that slides out and what it counts (§5) |

## 1. Every message in the chat

**Kinds.** `ChatMessage.kind: MessageKind`, `Chat` by default (yours, and his answers to you), else why he wrote: `Morning`, `SlotStart`, `SlotEnd`, `SlotKept`, `DayDone`, `Question`, `Completions`, `CheckIn`, `Review`, `Summary`, `EventQuestion`, `Reminder` ([03](03-notes-and-reminders.md)), `Note` (a line the app wrote in his name about something it did or couldn't: "Couldn't unstar it in Gmail: it's still there"). Today's `ChatMessage.reason: String?` (set only when a record was copied in by a reply) becomes the kind, read from the stored `reason` once.

**Who wrote the words.** `byApp: Boolean`: the words are the app's, in his name, with no model call (the check-in's questions, "Thanks: the week's review comes next.", the list-only briefing, a reminder delivered as he set it, a `Note`). The screen shows them as his; the model's history is told "(the app wrote this for you)" so he doesn't take fixed words for his own style ([05](05-his-voice.md)).

**The producers, each through the outbox** (`felix/Outbox.send`):

| Message | Today | After |
|---|---|---|
| His looks: SlotStart, SlotEnd, SlotKept, DayDone (`learn/Felix.kt`) | a `FelixRecord` and a notification under 4200 | a message of that kind, with `about` the piece's task, `pieceKey`, `nextCheckAt`, `suggestedReplies` |
| A reading's question (`Felix.write`, Reason.Question) | the same | kind `Question`; your reply is the task's answer (as now, `Felix.answered`) |
| The evening's completions (Reason.Completions) | the same, `asks` on the record | kind `Completions`, `asks` on the message; Harder / As expected / Easier recorded with no call |
| "How did it go?" with Claude off (`Assessment.post`, one notification a completion) | the app's own notification | the same question in his name, `byApp`, one message a completion, the three replies (Q2) |
| The morning (`Briefing.run` → `Felix.morning`) | a notification on the briefing's channel, under the day's title; without his words, the day's list | kind `Morning`, his words; without them (no slot, Claude off or resting, no answer in 20 s) the day's title and list as a `byApp` message, so every morning is in the chat (Q2) |
| The check-in's five questions (`AppGraph.askNext`) and its thanks | records, under 4200 | kind `CheckIn`, `byApp`, replies 1–5 and the week's put-off work as now; answers routed as `checkInReply` does |
| The review's note (`Review.run`, id 4003 → the check-in screen) | the app's notification on the weekly channel | kind `Review`, `byApp`: the note's first line and a card opening the review (Q2) |
| "The week's summary is ready; send it?" (NF-02) | the app's | kind `Summary`, `byApp`, a card that opens the email (Q2) |
| The calendar's questions (`CalendarTime.ask`, 4200 tagged) | the app's, on the calendar channel, three buttons | kind `EventQuestion`, `byApp`, replies All of it / A few hours / None → `CalendarTime.answer`, no call (cleanup Q6) |
| Answers to a reply from a notification (`AppGraph.tellAnswer`) | a record and a notification | nothing special: his answer is a `Chat` message as any; the notification shows it because it's unread |

Not his, and not in the chat: syncs under way, protection, free time and its end, the model's alerts (a dropped plan, a key problem), a calendar move put back (cleanup Q6).

**The log.** `ActivityLog.felix` goes (cleanup [04](../plan_targeted_cleanup/04-data-and-migrations.md) §"Felix's records into the chat"): the records of the last 30 days become messages once. Three readers switch to the chat: `FelixRules.due` (`sent` = today's messages of his, silent ones included: a message he chose not to write is kept as an empty-text message of its kind, as the record was, shown nowhere), `FelixPrompts.describe`'s "Recent, oldest first" (his last six with text, and your replies to them: a reply is now the next message of yours with `replyTo` it, or the next of yours at all within a few minutes), and `ReviewInput`/`Summary`'s "Felix's counts" (messages of his that week, and replies).

**The cap.** 200 messages today; with his own in it, the last 200 or 60 days, whichever keeps more (05). `Clear the chat` keeps nothing of his either; what his rules count today ("said once a slot") then starts afresh, which is fine.

## 2. A notification is the chat reaching you

**One notification, his**, at the registry's id for him (05: 6000), in `NotificationCompat.MessagingStyle`:

- the person is him, his name from Settings, the app's icon; the conversation title is nothing (a one-to-one chat);
- its messages are his unread ones (`at > ChatState.readAt`), oldest first, each at its time, text only (a card's gist in words: "Added “Trip form”" after the text, as the history tells him);
- a reply from it is shown in it as yours (MessagingStyle takes your messages too), under his, while he answers; his answer is added as it comes;
- the reply box offers the newest message's `suggestedReplies` as choices, as now; the buttons for cards (Add / Apply) go: since 3.1.0 nothing waits for a button, and `tellAnswer` only ever found pre-16-Oct cards;
- tapped, it opens the chat at the first unread (§3); opening the chat clears it;
- `setOnlyAlertOnce` where it's rebuilt for a reply sent, as now, so it doesn't sound again; a new message of his sounds.

**Rebuilt from the chat, not accumulated.** `Outbox.notify(now)` posts his notification from the chat's unread messages, or cancels it when there are none; `Outbox.send` calls it after writing the message, `AskActivity` on pause after setting `readAt`, and the briefing in the morning. So "held back" needs no copy: a kind that today goes on a ceilinged channel in quiet hours (the calendar's questions, the morning's fallback, the review) is in the chat at once and simply left out of the notification until the morning's rebuild (`NotifyRules` keeps its say, by kind instead of channel). The `heldNotifications` list stays for the app's own notifications only.

**Channels.** His channel (`Channels.FELIX`) for all of it, the morning included, is the simplest: one notification can't be on two channels (Q3). The briefing's channel then carries nothing of his; it's deleted once empty, as the old `daily` was, unless you'd rather the morning kept its own channel and sound, as a second notification beside his (Q3). The calendar and weekly channels keep the app's own notifications (a move put back, the review if it stays the app's).

**When the chat's on screen** nothing's posted (`chatInFront`), as now; when another screen of the app is in front, a notification still is: "not on the chat screen" is the test, as you put it.

## 3. Opening at the message

- **Read position.** `ChatState.readAt: Long`: set to now when the chat screen pauses (so what arrived while you were on it counts as read), and at the moment a notification's tap opens it. Before that update, the screen remembers `firstUnread` for the divider.
- **The divider.** A thin "New messages" line above the first unread, as messaging apps show it, for that visit only.
- **Where it opens.** From a notification: `AskActivity.intent(context, openAt = messageId)`, carried in the intent (singleTop: `onNewIntent` takes it); the list scrolls to that message (or the first unread, with none given) and tints its bubble for a second. Opened from his button or the menu: at the first unread if there is one, else the foot, as now.
- **The badge.** His button (`ui/FelixButton.kt`, UI-16) wears the unread count on every screen that has it (Q7); the block screen's too.
- **A message gone** (the chat cleared, the cap): the tap opens the chat at its foot.

## 4. WhatsApp-like

**The look.** Date separators between days ("Today", "Yesterday", "Tuesday 13 October"); the time under each bubble in small type; his bubbles left, yours right, as now; `byApp` messages in his bubble like any other. The typing dots stay (AC-08). The input bar stays, with the "about" chip and voice.

**Long-press** on any bubble: *Reply*, *Copy*. (Later, maybe: *Undo all of it* on his.)

**Swipe to reply.** A horizontal drag on a bubble, right for the thumb's reach (Q6), past a third of the width: a haptic tick, a reply arrow shown while dragged, and the bubble springs back (`AnchoredDraggable` with one anchor, or `SwipeToDismissBox` whose `confirmValueChange` refuses, so nothing's dismissed); vertical scrolling wins where the drag is more down than across, as Compose's gesture system decides. Then:

- a strip over the box: "Replying to Felix" (or "to you") with the quoted text's first line and an ✕;
- the message sent carries `replyTo = id`, and its bubble shows the quote in a tinted block above your words; tapping the quote scrolls to it;
- a reply to one of his `suggestedReplies` chips is the same: `replyTo` his message.

**One entrance for replies.** `AppGraph.ask(text, about, id, replyTo)` takes over `replyToFelix`'s routing, by the kind of the message replied to, so the chat and the notification behave alike:

| Replying to | Done with no call | Else |
|---|---|---|
| `CheckIn` | the answer kept and the next question asked (`checkInReply`) | |
| `Completions`, and the words are Harder / As expected / Easier | recorded on each completion (`Assessment.record`) | any other words: a chat turn |
| `Question` | the task's answer: an instruction, the task read again (`Felix.answered`) | |
| `EventQuestion`, and the words are one of its three | `CalendarTime.answer` | any other words: a chat turn (he can make an EventTime change) |
| `Reminder` | Done, Snooze ([03](03-notes-and-reminders.md)) | a chat turn |
| anything else | | a chat turn, the quoted message told to the model |

`FelixReplyReceiver` then calls `ask(words, replyTo = id)` and rebuilds the notification with your words under his; `FelixReplyWorker` stays, for the answer to be paid for and shown after the receiver's gone.

**What the model is told.** `ChatPrompts.turns` prefixes your turn with the quoted message where it isn't the one just before yours: "(Replying to your message: “…”)" for his, "(Replying to their own earlier message: “…”)" for yours; the last 12 messages may not hold it. One sentence in the chat's prompt says a message may quote what it replies to and that's what to answer. The chat golden and `FelixInputsDocTest`'s page change; `Prompts.VERSION` doesn't (it's not a reading's prompt).

**`Speaker.Felix`** replaces `Speaker.Claude`, read from the stored "Claude" (cleanup Phase 6 lists it; it belongs with this work, whichever lands first).

## 5. The extras folded

Under his bubble today, in order: what he searched and the pages found (`Searched`), a card for each change set, new task and task to open (`ProposalCard`), what he remembered (`Remembered`), what his actions did (`Acts`), the lines with nothing to undo (`done`: not made, where a time went, read in full), and `note` (left out).

**After.** A chevron at the bubble's bottom-right corner, shown only when the message has anything folded. Closed, the bubble shows his words alone. Open, the tail unfolds below the bubble as it's drawn today, with every Undo. The state is the screen's (`remember` keyed by message id, in a set); it's cleared when the screen pauses, so coming back finds every message closed, as you asked. What folds and what doesn't is Q4; recommended:

| Folds | Stays visible |
|---|---|
| change cards, new-task cards, acts, memory edits, searched and sources, read in full, not made, left out | an *Open* card (the point of a message that shows you a task), his `suggestedReplies` chips, a `byApp` message's own card (the review to open, the summary to send) |

A message that has a problem in its tail (not made, left out, not looked up) colours the chevron red, so it isn't hidden (Q5).

**The label that slides out.** For a message of his that arrives while the chat's on screen (its `at` after the screen resumed), and once for each unread one when the chat opens (Q5), a small label drawn behind his bubble slides out to its right 300 ms after the bubble appears, stays five seconds, and slides back behind it (`AnimatedVisibility` with a horizontal slide, the bubble above it in z-order so it emerges from under the bubble's edge). Its text is `ChatSummary.line(message)`, pure and tested: "Changes: 2 · Remembered · Searched", each part only where it applies, and "1 not made" in red where something was refused. What "Changes" counts is Q5; recommended: changes made + tasks added + actions done (a tick, a setting), since each is one thing he did. Tapping the label opens the tail. With nothing to say, no label.

## 6. Notifications that don't replace each other

The cause is `FelixNotifications.ID = 4200` for every message of his (and the briefing's fallback, the check-in's reminder and the review each under one id of their own, which is right for them: one a day). The fix is the cleanup's Phase 1a registry (`NoticeId`: one id a kind, request codes in ranges, the calendar's questions moved off 4200) and §2's one notification holding every unread message, so two messages in a row are one notification with two lines, not the second over the first. The app's own kinds (free time, protection, the model's alerts, a move put back) keep their own ids and show beside his.

Samsung groups an app's notifications under its name; his messaging-style one shows its last lines in the group. Nothing to do there.

## Data, after

```kotlin
@Serializable
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    /** When you last left the chat, or opened it from a notification: his messages after it are unread. */
    val readAt: Long = 0,
    /** His state between looks (from RuntimeState.felix, cleanup 05). */
    val felix: FelixState = FelixState(),
)

@Serializable
data class ChatMessage(
    val id: String, val from: Speaker, val text: String, val at: Long,
    val kind: MessageKind = MessageKind.Chat,
    /** The words are the app's, in his name: the check-in's questions, a reminder as he set it, a line about something done. */
    val byApp: Boolean = false,
    /** The message this one answers: a swipe, a chip, or a notification's reply box. */
    val replyTo: String? = null,
    val about: About = About(),
    /** His own message's piece (a slot's key), and when he said he'd look again (from FelixRecord). */
    val pieceKey: String? = null, val nextCheckAt: Long? = null,
    /** A Completions message's completions (from FelixRecord.asks). */
    val asks: List<AssessLater> = emptyList(),
    /** As now. */
    val proposals: List<Proposal> = emptyList(), val answeredAt: Long? = null, val note: String? = null, val stuck: Boolean = false,
    val memoryEdits: List<MemoryEdit> = emptyList(), val acts: List<Act> = emptyList(), val suggestedReplies: List<String> = emptyList(),
    val searched: List<String> = emptyList(), val sources: List<WebSource> = emptyList(),
    // remembered, done and reason: migrated and dropped (cleanup Phase 6).
)
```

The migration (Phase 0's fixtures first): the log's records of the last 30 days into messages (`id`, `at`, `text`, `kind` from `reason`, `about` from `taskId`, `pieceKey` from `key`, `nextCheckAt`, `asks`, `suggestedReplies` from `replies`), a record's `replyText` as a message of yours at `repliedAt` with `replyTo`; the chat's existing messages with `reason` get their kind; `readAt` = the newest message's time (nothing unread on the day of the update).

## Tests

- The outbox's (05): in the chat before any notification; nothing posted with the chat in front; every producer through it, by a test that fakes each trigger (`FelixTest`, `CheckInFlowTest`, a briefing test, an assessment test, a calendar-question test).
- `Replies.route(message, words)` (pure): each kind to its handler, the three words of Completions and EventQuestion matched whatever their case, anything else to the chat.
- `ChatSummary.line`: each part, the counting, the red count.
- The screen's state holder (cleanup Q9: B, state holders now): `firstUnread` from `readAt`; the open set cleared on pause; which messages get a label (arrived since resume, or unread on open, once).
- `ChatPrompts.turns` with `replyTo`: the quote prefixed, not for the message just before, the doc page regenerated (`FelixInputsDocTest`).
- `FelixRules.due` against messages instead of records: the same triggers for the same day (the test's records turned into messages by the migration's own function).
- The notification builder (pure part): the unread messages, oldest first; none → cancel; `readAt` moved → fewer.
- The migration from the Phase 0 fixtures, twice.

## Phone check

After the install, with the 4.0.0 build:

- `felix-say --es reason SlotStart` twice (two paid calls): two messages in the chat, one notification with both lines; you tap it: the chat opens at the first, with the divider above it; the notification's gone.
- Reply from the notification with a chip: your words appear in the notification under his; his answer follows; both in the chat with `replyTo` set.
- Swipe one of his older messages, send a word: the quote shows in your bubble; the model's turn (chat-context) shows the "(Replying to your message…)" line.
- A message with a change: the chevron, nothing under the bubble; the label slides out and back; open it, Undo works; leave and return: closed.
- `briefing`: the morning in the chat (his words, or the list as his); on the one notification (Q3).
- `check-in` (Sunday's flow): the questions in the chat, answered by chips, routed without a call (no call in Settings → Claude → Recent calls).
- `assess --es task <id>` with Claude off: the question in the chat in his name; Harder recorded on the completion.
- The log's old records: in the chat at their times; `felix` (the debug command) reads his state from the chat.
- The agent never taps: the taps and swipes above are yours to make and report.
