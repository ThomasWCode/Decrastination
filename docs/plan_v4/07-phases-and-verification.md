# The PRs, and how each is checked

Each release is a branch, staged commits, the tests, CI, Codex rounds as you say per PR (D-83: by default up to three, stopping at a round with at most one P2; the cleanup's Q2 adds one `@claude review` per PR), the build on the phone behind a backup, the checks, then the merge and tag ([development.md](../development.md)). The docs change in the same PR: `docs/spec/felix.md` (and `felix-inputs.md` by its test), `sources.md` for the email's finish, `decisions.md` for each new decision.

## The releases

### 4.0.0: the chat as his one voice (A, B, C)

Needs the cleanup's Phase 0 (fixtures of `chat.json` and `log.json` as they are), 1a (the registry and the notifier) and 1b (the outbox and the conversation). Three PRs, or one with three staged commits if 1b is fresh:

| PR | What | Tests | Phone check |
|---|---|---|---|
| 4.0.0-A | `MessageKind`, `byApp`, `replyTo`, `readAt`; every producer through the outbox with its kind; the app's words in his name (the morning's fallback, the check-in, the review, the summary, the calendar's questions, "how did it go?" with Claude off); the one messaging-style notification rebuilt from the unread; the log's records migrated in; `FelixRules`, `FelixPrompts`, `ReviewInput` reading the chat | [01](01-the-chat.md) §Tests: the outbox, the routing, the notification's unread, the migration twice, his rules on messages | [01](01-the-chat.md) §Phone check: `felix-say` twice → one notification; tap → the chat at it; `briefing`, `check-in`, `assess` in the chat |
| 4.0.0-B | Dates, times, the divider, the badge, long-press, swipe to reply with the quote; `ask(replyTo)` as the one entrance; the chat golden's sentence; `Speaker.Felix` | the state holder; `turns` with the quote; the doc page | a swipe-reply; a chip; a notification reply: all three with `replyTo` |
| 4.0.0-C | The chevron and the folded tail; the slide-out label; `ChatSummary` | `ChatSummary.line`; the open set cleared on pause; which messages get the label | a change made: folded, the label, Undo inside; leave and return: closed |

Codex: the default rounds. Paid: `felix-say` a few times (each a few cents), `chat-ask` a few.

### 4.1.0: what he can do (D, E)

Needs 4.0.0. Better after the cleanup's 3f (his service) and 4a (`By`), but not blocked by either: reminders and notes are a class built like `Focus` wherever it lives.

| PR | What | Tests | Phone check |
|---|---|---|---|
| 4.1.0-D | `felix.json` (notes, reminders, version 1); the schema's `reminders`, `notes`, `cancelReminder`, `deleteNote`; the prompt items; the acts and their Undo; the alarm and the sweep; delivery through the outbox with the chips; the sheet's Notes; the context's lists; the backup and Reset | [03](03-notes-and-reminders.md) §Tests; the API compiles the chat's schema (`-PcheckSchemas=true`) before the merge | [03](03-notes-and-reminders.md) §Phone check |
| 4.1.0-E | The gaps you choose (Q17): `keepPiece`/`unkeepPiece`, `sync`, `open` where it lives, free time and blocking in the context, the week's numbers | each action's parse and act and Undo; the context lines in the doc page | `chat-ask` for each: "I've done today's bit of…", "check Teams", "how much free time", "open it in Teams" |

If the API refuses the grown chat schema (3.1.4 did), D takes Q13's fallback (change types) in the same PR: the tests are written against `ChatAnswers.Reading`, not the JSON shape, so they hold.

### 4.2.0: memory and voice (F, G)

| PR | What | Tests | Phone check |
|---|---|---|---|
| 4.2.0-F | `Fact.until`, `Fact.taskId`, `Rule.until`; `Memories.expired`; the tidy skipping them; the profile's until; both schemas' fields; the prompts' items and examples; the lists' until | [04](04-memory-that-expires.md) §Tests; both schemas compiled by the API; `Prompts.VERSION` per Q15 | [04](04-memory-that-expires.md) §Phone check |
| 4.2.0-G | The register paragraph and examples in both goldens; `felixLine`'s default; `FelixWords`; the chips' two forms | the goldens; `FelixWords`; the doc page; the private evals re-run ($2) | three `chat-ask`s, a `felix-say`, the morning |

G can go first of everything as 4.0.x if you'd rather hear him sooner: it touches nothing the others do, bar the fixed lines, which [01](01-the-chat.md) collects into `FelixWords` anyway (whichever lands first makes the object).

### 4.3.0: emails done (H)

Independent of the rest; placed last because it writes outside the phone. One PR: the trigger, the journal, `ImapWriter`, the worker, the grace and undo, the switch and the first-time sheet, Felix's `done`, the sheet's button, the decisions amended. Tests and the phone check in [06](06-emails-done.md). Codex: the full rounds, as it changes behaviour outside the app.

## Meeting the cleanup

For the merge of the two plans into one order, where each change touches the cleanup's phases:

| Cleanup phase | This plan |
|---|---|
| 0: fixtures and the plan snapshot | before 4.0.0: `chat.json` and `log.json` fixtures with `FelixRecord`s, so the migration into messages is tested from them; a fixture of `memory.json` before 4.2.0-F |
| 1a: the registry and the notifier | 4.0.0-A's notification is the registry's id for him; his messaging-style builder is the notifier's one place; `NotifyRules` by kind |
| 1b: the outbox and the conversation | the ground of 4.0.0; the kinds, `byApp`, `replyTo` and `readAt` are 1b's fields or 4.0.0-A's, whichever's first |
| 2: the gateway | nothing new to call; reminders and notes make no call; the writer isn't a model call |
| 3f: his service | 4.1.0's reminders and notes are a service of their own (`felix/Reminders`, `felix/Notes`), or part of his; 3f after 4.0.0 moves the chat's routing once (the cleanup README already orders 3f after the chat work) |
| 3e: Readings, Sync, Photos | 4.3.0's `GmailWriteWorker` and `ImapWriter` sit with the Gmail source; the Sync service exposes "read one source now" for E's `sync` action |
| 4a: `Mine` and `By` | `Note.by`, `Reminder.by` use `By`; if 4.1.0 comes first it defines `By` and 4a takes it |
| 4b: steps with an id | `keepPiece` keys a piece as the plan does today (`Chunk.key`, by the step's place); when 4b changes keys to ids, the action's key follows |
| 5a: the stores split, versions and migrations | `felix.json` is made versioned from the start; `GmailWrite`'s home is `RuntimeState` until 5a, then its own file; the chat's `readAt` and `felix` state are 04's "Felix's state into the conversation" |
| 5b: finished tasks trimmed | a note on a trimmed task still shows on its record |
| 6: names | `Speaker.Felix` lands with 4.0.0-B if 6 hasn't; `ChatMessage.reason`, `remembered`, `done` migrated and dropped with the kinds |
| 7: docs | each PR edits `felix.md`; `felix-inputs.md` is regenerated by its test; the decisions numbered as applied |
| 8: state holders | the chat's state holder (unread, the open set, the labels) is 4.0.0-B's, in the pattern Phase 8 wants (cleanup Q9: B) |

## Tests, in all

| Kind | What | Release |
|---|---|---|
| Pure rules | `Replies.route`, `ChatSummary.line`, `Reminders.next/due`, `GmailFinish.due`, `GmailWrites` (grace, undo), `Memories.expired`, `FelixWords` | each |
| Flows on temporary stores (the `FocusTest` pattern) | the outbox through every producer; a reminder set, delivered, snoozed, done; a note added and deleted; the email's finish through keep → journal → fake writer → sync → completion | 4.0.0, 4.1.0, 4.3.0 |
| Prompts and schemas | the goldens; `SchemaLimitsTest`; `SchemaCompileTest` by the API on every schema change (4.1.0-D, 4.2.0-F); `FelixInputsDocTest` | each that touches them |
| Migrations | the log's records into messages, from the Phase 0 fixtures, twice; `memory.json` with the new fields | 4.0.0, 4.2.0 |
| State holders | the chat's: unread, open set, labels | 4.0.0 |
| Evals (private, paid, by hand) | the chat's and his own after the voice | 4.2.0-G |

## Risks

| Risk | Where | Likelihood | Harm | How it's met |
|---|---|---|---|---|
| A message of his lost in the move to the chat | 4.0.0-A | medium | medium | the outbox writes before posting; a test per producer; the log's records migrated with a count compared before and after on your backup (the cleanup's rehearsal) |
| The one notification misbehaves on One UI (grouping, a reply not shown) | 4.0.0-A | medium | low | checked on the phone by you; the fallback is one notification a message, tagged (cleanup Q4's B), a builder's switch away |
| Swipe fights the list's scroll | 4.0.0-B | low | low | the threshold is a third of the width; vertical wins; tried on the phone |
| The chat's schema refused as too large | 4.1.0-D, 4.2.0-F | medium | low | compiled by the API in the PR before anything else; the change-type fallback (Q13) |
| A reminder never fires (the alarm dropped, Doze) | 4.1.0-D | low | medium | an exact alarm with `USE_EXACT_ALARM`; the sweep at start and at his looks; late marked, never silent |
| The voice costs a change or a date | 4.2.0-G | medium | medium | the evals re-run before the merge; the "still exact" lines; the examples yours to read first |
| The profile's shape re-reads every task | 4.2.0-F | certain if the version's raised | low (about 60 cents) | batched with another reading change, or accepted once (Q15) |
| A write touches the wrong conversation | 4.3.0 | low | high | only a thread the app made a task from, by its stored thread id and UIDs; only unstar and archive; the writer's command list tested; the first write shown to you; the switch |
| A write races a read | 4.3.0 | low | medium | the write journaled first; the task never marked done locally; the Gmail-only sync after; a reverse write for undo |
| The phone's away (school days, 07:40–18:00) | all | certain | time | evenings and weekends, as every release |

## Rolling back

Each PR's merge can be reverted on its own. 4.0.0 migrates the log's records into the chat: the log keeps them until the cleanup's Phase 5 trims them (its `felix` list stays readable a release), so the previous APK installed over it (`adb install -r -d`) still has his history. `felix.json` and `GmailWrite`s are ignored by an older build. A Gmail write already made isn't undone by a rollback: starring the email again is yours to do in Gmail, which is why the grace and the first-time sheet are there.
