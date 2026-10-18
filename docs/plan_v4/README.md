# Felix as the app's one voice: the plan for 4.x

A plan, not started, for the changes to how Felix works that you listed on 18 Oct 2026: everything he says in one WhatsApp-like chat, with notifications that are the chat reaching you; notes and reminders he keeps for you; memory that lasts as long as it's needed; a teenager's voice; and a starred email finished in the app finished in Gmail too. Written on 18 Oct 2026 against `main` at e6cd54b (3.2.0); your answers to its questions, given the same day, are applied throughout.

It stands beside [the targeted cleanup](../plan_targeted_cleanup/README.md), which was written with part of this list in view and lays the ground for it (its Phase 1: one conversation store, an outbox, a notifier whose notifications stack). This plan says what's built on that ground, and where each piece sits relative to the cleanup's phases, so the two can be merged into one order later. Nothing here is implemented. [QUESTIONS.md](QUESTIONS.md) holds every call it needed from you with your answers (Q1–Q30, all given on 18 Oct). Each document ends with how it's built: the steps, the files and the commits, before its tests and its phone check.

## Why

The app plans and blocks well; what's uneven is how Felix reaches you and what you can ask of him:

- **His messages are lost.** What he says unprompted (the morning, a slot's start or end, a question, the check-in) is a notification and a record in the log, not the chat. Tap the notification and the chat opens without the message. 15 such messages on your phone in 30 days, none in the chat ([cleanup 01, finding 2](../plan_targeted_cleanup/01-current-state.md)).
- **His notifications replace each other.** Every one is posted under id 4200, by design "one at a time". So does the briefing's fallback and the review's, each under its own single id.
- **The chat buries what he did.** Every change, memory and search sits under his bubble as cards and small lines; a message that does three things is a screen long.
- **He can't be asked for what a friend with his access could do.** No reminder at a time; no "ping me when I sit down to the physics"; no note on a task for later. "Remind me to buy mum a present before Sunday" is one of the empty chat's own examples, and today it makes a task, not a reminder.
- **He remembers for ever or not at all.** "Don't message me tonight" becomes a standing instruction until you delete it; "I've got a cold this week" a fact kept till the tidy gets to it.
- **He sounds like a planner app.** "I'll put this in as a task for: Friday. When would you like…?" rather than "alr cool, let's do that then. what's the date gonna be for it?"
- **An email finished in the app stays starred.** Its work is done, and the plan has nothing left of it, but it sits in Gmail until you unstar it by hand, which is also when the app counts it finished.

## What it covers

| Your list | Here |
|---|---|
| Every message of Felix's in the chat, the morning too; a tapped notification opens his message | [01](01-the-chat.md) §1–3 |
| The chat as his one visible voice, WhatsApp-like; notifications when you're away; swipe to reply | [01](01-the-chat.md) §2–4 |
| Changes, remembered and searched folded under an arrow; a line that slides out for five seconds | [01](01-the-chat.md) §5 |
| Notifications that don't replace each other | [01](01-the-chat.md) §6 |
| Notes he keeps on a task; alerts at a date or time | [03](03-notes-and-reminders.md) |
| What he can do now, and how far he can act like someone you text | [02](02-what-felix-can-do.md) |
| Remembering what you tell him for as long as it matters, with no pruning by you | [04](04-memory-that-expires.md) |
| A teenager's voice | [05](05-his-voice.md) |
| Managing it all from the chat, with nothing else to touch | [02](02-what-felix-can-do.md) §3, and the whole |
| A ticked starred email unstarred and archived | [06](06-emails-done.md) |
| A message that's just talk, answered as talk (your note of 18 Oct) | [05](05-his-voice.md) §Just talking |
| Whether summaries should stand in for the raw data he reads (your note of 18 Oct) | [08](08-summaries.md): an evaluation; decided as a later 4.4.0 (Q28: A) |

And what I'd add in the same direction, each marked as a suggestion and asked about in [QUESTIONS.md](QUESTIONS.md): the few things he still can't do that the screens can (keep a piece with no step, check a source, say how much free time there is, open a task where it lives), a quiet-until rule, reminders you can snooze from the notification, the day's reminders in his morning message, an unread badge on his button, and the calendar's questions and "how did it go?" as his (the cleanup's Q6, already agreed).

## Not in scope

- The restructuring itself: that's the cleanup plan. This plan says which of its phases each change needs, and builds on them rather than around them.
- Anything about the planner, the slotter, the blocker, protection or the readings, beyond what a new ability of Felix's needs them to expose (a piece kept from the chat, a source read).
- Photos in the chat, voice notes, a second chat, group messages, read receipts: ideas noted in [02](02-what-felix-can-do.md) §3, not planned.
- A tone setting (D-57: his name and line are the knobs); the voice is a prompt change.
- Writing to Teams, Power Planner or AnkiDroid: none has a write API the app uses; only Gmail gets a write, and only the two acts in [06](06-emails-done.md).
- The chat as the app's home screen: asked on 18 Oct, then dropped the same day (Q26); the app opens on Day as now.

## Principles

- **The chat is the record.** Every word in his name is a message in `chat.json` first; a notification is made from the chat, never beside it; nothing he says exists only as a notification or only in the log.
- **One entrance for replies.** A reply from a notification and a reply in the chat are the same turn, routed the same way: to the check-in, to a completion's "how was it?", to a reading's question, to a reminder, or to the model.
- **He acts at once, with an Undo** (D-60), for the new things too: a reminder set, a note kept, an email finished.
- **Free where it can be.** Notes, the email's finish and a reminder's chips make no model call; the voice and the folded extras change prompts and screens only. A reminder's message is written by him for its moment (Q9), one small call each, drafted ahead so it's sent on time and sent as you set it when he can't write; no other new paid call.
- **The app's rules over the app's values, yours over yours** (D-4): a reminder's time and words are yours as he set them; the app doesn't move them.
- **The public repository stays clean** (D-79): examples are made up; the private evaluation sets stay in `private/`.

## The changes at a glance

| # | Change | Version | Needs from the cleanup | Model calls | Size |
|---|---|---|---|---|---|
| A | Every message of his in the chat; the notification from the chat, opening at the message; one stacked notification | 4.0.0 | Phase 0, 1a, 1b | none new | 1½ days |
| B | WhatsApp-like chat: read state, dates, times, swipe to reply, replies routed by kind; every message kept, the older ones in an archive | 4.0.0 | A | a prompt line (chat golden) | 2 days |
| C | The extras folded under an arrow; the line that slides out | 4.0.0 | A | none | ½ day |
| D | Notes on tasks, and reminders at a time or at a task's slot, written by him for the moment | 4.1.0 | A; better after 3f (his service) and 4a (`By`) | the chat's schema grows (compiled by the API first); a call a reminder, drafted ahead | 2½ days |
| E | The gaps closed: keep a piece, check a source, free time and blocking in his context, open where it lives | 4.1.0 | none (3f tidier) | the chat's schema and prompt | 1 day |
| F | Memory that expires: facts and rules with an until, or tied to a task | 4.2.0 | none | the chat's and his own schema; the profile's shape (`Prompts.VERSION` raised: Q15) | 1 day |
| G | His voice; just talk answered as talk; the summary email in the third person | 4.2.0 | none | both his goldens and the summary's; the private evals re-run | ½ day |
| H | A starred email finished in the app unstarred and archived in Gmail | 4.3.0 | none (3e tidier) | none | 1½ days |

About ten days, in four releases, each a PR with its own Codex rounds and phone check as every release has ([development.md](../development.md)). [07](07-phases-and-verification.md) has each PR's steps, tests and checks.

## Order

Decided (Q1: A):

1. **Cleanup Phases 0 and 1** (the safety net, the notifier, the outbox and the conversation), as its own plan orders them.
2. **4.0.0: A, B, C** on top, the chat as his one voice. This is the piece you'll see every day, and the rest hangs off its message kinds and routing.
3. **4.1.0: D and E**, what he can do, once the chat is the place to ask. If cleanup Phase 3f (his service) comes first, reminders and notes are built as services from the start; if not, they're built like `Focus` anyway and moved in 3f.
4. **4.2.0: F and G.** Memory first (it changes two schemas; compile them once), then the voice (goldens only).
5. **4.3.0: H**, the email's finish: independent of all the rest, so it can go anywhere; last because it's the one that writes outside the phone.

Where the cleanup's Phases 4 and 5 fall (your values as `Mine`, the stores split) doesn't change these; D adds a store that Phase 5 should know of, and 4a's `By` is what D's "who wrote this note" should use ([07](07-phases-and-verification.md) §"Meeting the cleanup").

## What changes substantially

- **`chat.json` is the conversation**: every message of his, with a kind, what it's about, the replies it offered and his next check; your read position; his state. The log stops keeping his records. [01](01-the-chat.md).
- **One notification for him**, in Android's messaging style, holding his unread messages, cleared when the chat opens; tapping it opens the chat at the first unread. The morning goes in it (Q3).
- **The chat screen** gains dates, times, a read divider, swipe to reply with a quote, a folded tail under his bubbles with a chevron, and a label that slides out for five seconds saying what a new message did.
- **Two new things he keeps**: notes on tasks and reminders, in a store of their own, each set from the chat with an Undo, shown on the task's sheet and in his context, delivered as his messages. [03](03-notes-and-reminders.md).
- **Memory with a shelf life**: a fact or a rule can carry an until, or belong to a task, and goes when that passes. [04](04-memory-that-expires.md).
- **His prompts say how he talks**, with the register pinned by examples; the fixed lines in his name follow. [05](05-his-voice.md).
- **Gmail gets one write**: finishing a starred email's work unstars and archives its conversation, journaled and retried, undone within a grace. D-7's "only ever read" is amended. [06](06-emails-done.md).

## Decisions it adds

To be numbered in `docs/decisions.md` by the PRs that apply them (the next free number is D-94):

- The chat is Felix's one visible voice; a notification is made from it and never beside it.
- A reply from a notification and one in the chat are the same turn, routed by the message it answers.
- He keeps notes and reminders at your asking, made at once with an Undo; a reminder is delivered in his words for the moment, written ahead, or as you set it when he can't write.
- The chat keeps every message for you; what he's sent of it is the recent window, and the rest is an archive read only on scrolling back.
- What he remembers can expire: by a date, or with a task; nothing of it is yours to prune.
- His voice is a sixth-former's, set by his prompts and pinned by examples; his name and line stay the knobs (D-57). A message that's just talk gets a reply and nothing else.
- The week's summary email is about you in the third person, never in his voice.
- Finishing a starred email's work in the app unstars and archives it in Gmail (amending D-7, D-10 and D-54), behind a switch; a write that can't be made is the app's own notification, not his.

## Done when

- Every message in his name since the update is in the chat, and `felix-say` twice gives one notification with both.
- A tapped notification opens the chat at that message; a reply from it and a reply swiped in the chat answer the same message the same way.
- His bubble shows a chevron and nothing under it until opened; a new message's label slides out and back.
- "Remind me at 7 to…" sets a reminder (not a task) that arrives at 7 as his message written for that moment (your words, when he couldn't write); "remind me when I next sit down to…" arrives at that slot's start.
- Scrolling up past the chat's oldest message loads the earlier ones; `chat-context` shows only the window.
- A message that's just talk gets his reply and nothing else: no card, no chevron, no label.
- "Don't message me tonight" is gone by morning without your deleting it.
- The private chat and Felix evals pass at the rate they pass today after the voice change.
- Ticking the last piece of a starred email unstars and archives it within a minute online, and after reconnecting offline; Undo within the grace leaves Gmail untouched.
- All tests pass, more than today's 874; every release checked on the phone.

## The documents

- [01-the-chat.md](01-the-chat.md): the chat as his one voice; the notification; opening at a message; WhatsApp-like; the folded extras; stacking.
- [02-what-felix-can-do.md](02-what-felix-can-do.md): what you can ask him today and after; the gaps; the chat as the control surface.
- [03-notes-and-reminders.md](03-notes-and-reminders.md): notes on tasks and reminders at a time or a slot.
- [04-memory-that-expires.md](04-memory-that-expires.md): facts and rules that last as long as they matter.
- [05-his-voice.md](05-his-voice.md): the register, the prompts, the fixed lines, the evals.
- [06-emails-done.md](06-emails-done.md): unstar and archive on the last tick.
- [07-phases-and-verification.md](07-phases-and-verification.md): the PRs, what each needs, tests, phone checks, risks; meeting the cleanup.
- [08-summaries.md](08-summaries.md): whether summaries should stand in for the raw data he reads; an evaluation, decided as a conversation digest in a later 4.4.0 (Q28: A), to be planned then.
- [QUESTIONS.md](QUESTIONS.md): the calls this plan needed from you, with your answers, and what's still open.
