# Questions for you

What the plan needs you to decide before, or as, it starts. Each has a recommendation. Your answers of 17 and 18 Oct (Q2–Q12) are applied in the plan's documents; Q1 is taken from plan_v4's Q1 (see its note); Q11 you left to the plan, which takes its recommendation (A).

## Q1. The order

- **A (recommended).** Phases 0 and 1 first, then the chat work you've written up (Felix's messages all in the chat, a WhatsApp-like chat, swipe to reply, the collapsed extras, notifications that don't replace each other), then Phases 2 to 7 between other features.
- **B.** The whole cleanup first, then features.
- **C.** The lighter route: Phases 0 to 2 now (the chat work's ground and one model gateway), the rest only when a feature needs it.
- ANSWER:
- Note (18 Oct): not answered here, but [plan_v4's Q1](../plan_v4/QUESTIONS.md) (answered A) gives this order, Phases 0 and 1 first, then the chat work, then the rest between features, so the plan takes it as A. Say here if that's wrong.

## Q2. Codex rounds for the cleanup's PRs

- **A (recommended).** One round for a PR that only moves code (most of Phase 3, Phase 6); up to three, stopping at a round with at most one P2, for those that change data or behaviour (1b, 4, 5).
- **B.** One round for every PR.
- **C.** No round for a move: CI and the phone check only.
- ANSWER: A. Also run one claude review (@claude review) per PR that has up to three codex reviews. It comments on the PR in one big piece of text, unlike codex's comments per item. Once responsed to, reply to claude's comment with 'Resolved'.

## Q3. How long a finished task keeps its text

Finished tasks are 87 % of `tasks.json` (509 of 585 KB on your phone), most of it emails' text. Trimming keeps each task's record (title, dates, minutes, ticks, your values) and drops its text and Claude's reading. One thing changes: if a source lists a long-finished task again (a hand-in undone, an email starred again), it's read again, a paid call.

- **A (recommended).** 30 days after it's finished.
- **B.** 14 days.
- **C.** 90 days.
- **D.** Never: keep everything, as now.
- ANSWER: B

## Q4. Felix's notifications

Both stop a new message removing the last one.

- **A (recommended).** One notification for the conversation, as messaging apps do: his unread messages in it, newest last, a reply box answering the newest; tapping opens the chat at the first unread; opening the chat clears it.
- **B.** One notification a message, grouped together: each dismissed on its own, each with its own reply box answering that message.
- ANSWER: A

## Q5. Felix's past messages

His unprompted messages of the last 30 days are only in the activity log (15 on your phone today, none in the chat).

- **A (recommended).** Put them into the chat once, each at its time, with the update that moves his messages there.
- **B.** Start from the update: the old ones age out of the log.
- ANSWER: A

## Q6. Which notifications are Felix speaking

With the chat as his one visible voice, his messages go through the chat. The ones that are clearly his: the morning briefing, his looks at a slot's start and end, his questions, the check-in's questions and the review's note. The ones in between:

- **"How did it go?"** after a completion (harder, as expected, easier).
- **The calendar's questions** ("how much of your time does this event take?").
- ANSWER: Recomendation is correct.

Recommended: both become his messages, asked in the chat with quick replies, and notified from there. These stay the app's own notifications, outside the chat: free time and its end, protection, syncs under way, the model's alerts, a calendar move put back.

## Q7. The docs' new shape (answered, 17 Oct)

Your call: every doc deleted but this plan, after its decisions and still-true facts were moved; the decisions in `docs/decisions.md`, how the app works in `docs/spec/`, how the work is done in `docs/development.md`. No change log or history folder (git and the PRs keep the history).

## Q8. The Gmail account's address in the repository's history

The old `docs/data-sources.md` named the account's address in its Gmail heading. It left the docs when that file was deleted; git's history still has it.

- **A (recommended, unless you want it gone entirely).** Leave the history as it is.
- **B.** Remove it from the repository's history too: a rewrite and a force-push of the public repository, after which any clone must be made again.
- ANSWER: A

## Q9. The optional Phase 8

A pure `:core` module (the compiler keeping it free of Android, its tests faster), and state holders for the largest screens (the chat, Settings, the task sheet).

- **A (recommended).** Later, when there's time.
- **B.** Now, with Phase 3.
- **C.** Not at all.
- ANSWER: B

## Q10. Releasing the data changes

Phases 4 and 5 both change stored data.

- **A (recommended).** One release for both: one rehearsal on your latest backup, one backup before the install, one migration to watch.
- **B.** A release each, so each is smaller.
- ANSWER: A

## Q11. Where Phase 8 sits inside Phase 3 (your Q9: B)

Phase 8 (the `:core` module and the state holders) now goes with Phase 3. The module split moves every `core/` file and its tests into a Gradle module, which is best done when nothing else is moving.

- **A (recommended).** The `:core` module as Phase 3's last PR (3g), after 3f; each screen's state holder as its service lands (the chat's with plan_v4's 4.0.0-B, Settings' with 3c, the task sheet's with 3d).
- **B.** The module first, before 3a, so every service is written against it from the start.
- ANSWER: I do not know enough about this: your call.
- Taken (18 Oct): A. The module is the one PR that touches every `core/` file at once, so it goes when nothing else is moving, after 3f; each state holder lands with its screen's service, so no screen is reworked twice.

## Q12. The review step in `docs/development.md` (your Q2)

Your Q2 answer adds one `@claude review` per PR that gets up to three Codex rounds, answered point by point and closed with "Resolved". That reads as a rule for every PR, not only the cleanup's.

- **A (recommended).** Put it in `docs/development.md` §Changes, pull requests and reviews beside the Codex rule (D-83's companion), in the first PR of either plan.
- **B.** Keep it to the two plans' documents.
- ANSWER: A
