# Felix's conversation, his outbox, and the notifier

The structural ground for the coming chat work: every message of Felix's in the chat, notifications made from chat messages, several at once. This document says how the code is arranged for it; the features themselves (the chat as his one visible voice, swipe to reply, the collapsed extras and the line that slides out) are built on it in their own PRs.

## Today

- **Two homes.** His unprompted messages are `FelixRecord`s in `log.json` (kept 30 days) and notifications; the chat (`chat.json`) has your messages and his answers. A record reaches the chat only when you reply to its notification.
- **Five producers**, each posting its own notification: his looks and the morning (`learn/Felix.kt`), the morning's title and list (`learn/Briefing.kt`), the check-in (`AppGraph`'s check-in flow, `learn/CheckIns.kt`), the weekly review (`learn/Review.kt`), and answers to a notification's reply (`AppGraph.tellAnswer`).
- **One notification at a time**: `FelixNotifications` posts every message under id 4200, so each new one takes the last one's place.
- **His state in three places**: the log's records, `RuntimeState.felix` (next check, draft, retry, questions) and `assessLater`, and the chat.

## The conversation

One store, `chat.json`, holds everything said between you and Felix, and his state.

- **Every message of his is a chat message**, with a `kind`: an answer to you, or one he started (Morning, SlotStart, SlotEnd, Question, Completions, CheckIn, Review). What his records held moves onto the message: what it's about (the piece, the task), his next check, the completions it asks about, the replies offered.
- **`replyTo`** on any message: the message it answers. A reply from a notification sets it; swipe to reply will too. The history sent with your next message says what each reply was to, so he answers the message you meant.
- **Read state**: when you last saw the chat. It decides which of his messages are unread, which the notification shows and where the chat opens.
- **His state** (next check, draft, retry, questions waiting, completions to ask about, the check-in's draft) is the conversation's, not `RuntimeState`'s.
- **The speaker is Felix** (`Speaker.Felix`, read from the stored "Claude").
- **The cap.** The chat keeps its last 200 messages today. With his own messages in it, that's a month or two; the cap becomes count and age (the last 200, or 60 days, whichever keeps more), so what his rules count today is always there.

`FelixRules` reads today's messages from the conversation instead of the log; its tests stay, run against the conversation.

## The outbox

```kotlin
class Outbox(
    private val conversation: Conversation,
    private val notices: Notices,
    private val chatOnScreen: () -> Boolean,
) {
    /** [message] put in the chat first, so it's never lost; then shown as a notification if the chat isn't on screen. */
    suspend fun send(message: ChatMessage, channel: Channel = Channel.Felix)
}
```

- **The chat first.** The message is written to the conversation before anything is posted, so a notification can't point at a message that isn't there.
- **A notification only when the chat isn't on screen**, made from the message (text, its quick replies, its cards' buttons), on its channel: Felix's, or the briefing's for the morning, so the briefing's time and its ceiling stay as they are.
- **Tapping it opens the chat at that message**; replying from it is your message in the chat, replying to his.
- **Every producer goes through it**: his looks, the morning (the briefing's title and Felix's words become one morning message of his, with the day's list attached), the check-in's questions, the review's note, answers to replies. None posts a notification of its own.
- **What isn't Felix stays a notification**: syncs under way, protection, free time, the model's alerts, the calendar's moves and questions, "How did it go?". Which of these count as his voice is your call (Q6).

## The notifier

### One registry

`NoticeId` names every notification the app makes, with a range of request codes for its buttons, so no two can collide:

| Kind | Id now | Id after | Several at once |
|---|---|---|---|
| Work under way: sync, readings, review, Felix's look, a reply | 1001–1005 | the same | one each |
| Free time, its end | 2003, 2004 | the same | one each |
| Protection (the watchdog) | 3001 | the same | one |
| Briefing, check-in, review, summary | 4001–4004 | the same | one each |
| How did it go? | 4100 | the same | one a completion (tagged) |
| Calendar questions | 4200 | 4400 | one an event (tagged) |
| Calendar moves | 4300 | the same | one a move (tagged) |
| Model alerts | 5001, 5002 | the same | one each |
| Felix's conversation | 4200 | 6000 | see below |

Each kind's buttons get request codes in a range of their own (Felix's 6001–6099, and so on), where today they're made by adding to the id. The two uses of 4200 (the calendar's questions, tagged, and Felix's, untagged, so apart only by the tag) part. The ids that stay keep a notification already showing on your phone where it is through the update.

### Felix's notifications stack

Two ways, both ending "a new one removes the last" (Q4 is which):

- **One conversation notification (recommended)**, as messaging apps do it: Android's messaging style, holding his unread messages, newest last, each added as it comes; a reply box replying to the newest; tapping opens the chat at the first unread. Opening the chat marks them read and clears it.
- **One notification a message**, tagged with its id and grouped under a summary: each dismissed on its own, each with its own reply box replying to it.

### One policy

`NotifyRules`' ceiling of four routine notifications a day, quiet hours, and those held for the next briefing apply in one place, `Notifier.post`, as now; Felix's and protection's stay outside the ceiling. A channel's being off still means Felix has no voice for it (`Notify.shown`), as his rules check today.

## The coming chat work, on this ground

| Feature (from your next request) | What it uses |
|---|---|
| Every message of his in the chat, the morning's too | the outbox; the morning as a message of his |
| A tapped notification opens his message | the message id on the notification; the chat opening at it |
| The chat as his one visible voice; notifications when away | the outbox's "chat on screen" check; the stacking above |
| Swipe to reply | `replyTo`; the history saying what each reply was to (a prompt change, with its golden) |
| Changes, remembered and searched under an arrow; "Changes: 1, Remembered, Searched" sliding out for five seconds | the message's acts, memory edits and searches, which it already has; the open or closed state kept by the screen only, so leaving the chat closes them all |
| Notifications not replacing each other | the registry, and Felix's stacking |

## Order

1. **1a**: the registry and the notifier, every caller switched, no behaviour changed.
2. **1b**: the conversation's new fields, the outbox, every producer through it, the notification from the message and its tap.
3. **The features**, a PR each: the chat as his voice and the stacked notification; swipe to reply; the collapsed extras.

## What to watch

- **What his rules count.** Moving "what he's sent today" from the log to the chat must count the same messages; its tests run on both before the switch.
- **The briefing's timing and ceiling.** The morning stays on its channel and time; only where it's kept changes.
- **Notifications you've turned off.** A channel off still silences that kind; a message still goes in the chat.
- **The chat's size.** A message of his a slot, on a busy day, is a dozen messages; the cap by count and age keeps that in bounds.
