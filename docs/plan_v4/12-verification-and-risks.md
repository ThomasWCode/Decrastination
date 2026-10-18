# Verification, and the risks

How each PR is shown to keep the app working, or to change it only as it says; what could go wrong; how a release is undone.

## "Nothing changed": how it's shown

A cleanup PR claims to keep behaviour, and shows it four ways:

1. **Every test passes**, the 874 of today and the new ones, with none removed but by a stated reason.
2. **The plan snapshot is unchanged** (Stage 0): a fixed state planned the same, to the piece and the minute.
3. **The goldens are unchanged**: no prompt or schema differs, unless the PR says why.
4. **The phone check passes** after the install, and the stage's own checks with it.

A feature PR, and a cleanup PR that changes behaviour on purpose (5a's message routing; 7's trimmed text for long-finished tasks), says what changes, and its tests show it: the pure rules first, the flows on temporary stores, the goldens rewritten on purpose with their diff in the PR, the schemas compiled by the API where one changed.

## The tests added, in order

| Stage | What |
|---|---|
| 0 | every store's shape as it is, read by the app's serializers; a fixed state's plan, as text; the rehearsal's counts |
| 1 | the registry: no two ids or request-code ranges overlap; the held list through the notifier; the architecture test's first rule |
| 2 | the gateway: holds, the cap, the record, a failure's class, one client for many calls; the second rule |
| 3 | the migration mechanism on a made-up store, twice; the backup flows (written, restored into an emptier phone; a reset and its copy) |
| 4 | the Conversation's flows on temporary stores through a fake gateway: the chat's answer and its undo, a card applied and taken back, the check-in, a reply from a notification, a look |
| 5a | the outbox (in the chat before a notification; none with the chat on screen; every producer); his rules counting the same from the chat; the migration from the fixtures, twice; the archive's move, twice; the notification's unread and held kinds |
| 5b | the state holder; `Replies.route`; `ChatPrompts.turns` with the quote; the doc page |
| 5c | `ChatSummary.line` and `problems`; a bare answer |
| 6 | each service's flows: the calendar's copy and move rules through its port; armed and unarmed protection; an instruction applied, refused, deleted and a quick change taken back; own tasks; a sync with one source failing; a reading due and refused; a review from a fixed log; the third rule by the module |
| 7 | every flag of 3.1.2 to 3.1.4 over `mine`; ticks following an id through a re-read that reorders; origins; each migration from every fixture shape, twice; a backup from before restored after; the rehearsal's invariants |
| 8a | `Reminders.next/due`; `Notes.forTask` (a standing note by subject, class, source and title); the parse's checks; the acts and their Undo; delivery with and without a draft; drafting through a fake caller; the sheet's model; the context; the store in a backup and Reset; `SchemaLimitsTest`; `SchemaCompileTest -PcheckSchemas=true` |
| 8b | each gap's parse, act and Undo; the context line; the schemas compiled again |
| 9 | all as before, under new names |
| 10a | `Memories.expired`; `edit` and `undo` with the until; the tidy skipping; `Profile.block`; both parsers; both schemas compiled; `profile.txt` and `ReadingPrompts.VERSION` |
| 10b | the goldens; `FelixWords` and the chips' two forms; `summary.txt`; the private evals re-run (paid, by hand) |
| 11 | `GmailFinish.due`; the grace and undo rules; `ImapWriter` over a fake stream and the reader's command list; the worker's outcomes through a fake writer; the merge after an unstar; the switch off |
| 12 | the architecture test complete: only the notifier posts, only the gateway builds a client, `core` pure, one writer a store (with `Stores.replace` its one named exception, for restores and resets) |

The paid checks: `SchemaCompileTest` by the API (a cent a schema) in 8a, 8b, 10a; the private evals ($2) in 10b; `felix-say` and `chat-ask` on the phone (a few cents each) in 4, 5, 8, 10. Everything else is free.

## The phone check

After every install, read-only, by `scripts/phone-check.sh` (Stage 0):

- the version and the time it was installed;
- the six accessibility services enabled and bound, the focus service among them;
- the calendar written to: its slots, the longest of the app's own steps an hour or less, no stray copies;
- no crash in the log since the install;
- what's in front (the home screen, not a screen left open);
- the stores' sizes, against the last run.

Then the stage's own checks ([03](03-stages.md)), through the debug commands where they can be. The notification shade, a gesture, another app, aeroplane mode and the power button are yours: where a check needs one, you make it and say what you saw; a tap inside the app can go through the guarded `tap.sh` with screenshots when you're not using the phone ([14](14-your-part.md) says which is which, stage by stage). A backup of the APK and the data comes before every install (`keep_before.sh`), and the app is never uninstalled. The phone's away on school days (07:40–18:00): installs and checks are evenings and weekends.

## Reviews

- **Moves, and restructurings that change no behaviour** (Stages 1, 2, 4, 6, 9, 12): one Codex round, the PR saying what moved and that nothing changed, so its findings are about the move.
- **PRs that change data or behaviour** (3, 5, 7, 8, 10, 11): up to three rounds, stopping at a round with at most one P2 (D-83), and one `@claude review` as well, answered point by point and closed with "Resolved" ([development.md](../development.md); C-Q2, C-Q12).
- Before merging: CI green; any changed schema compiled by the API; the final build installed and checked on the phone; the docs changed in the same PR ([11](11-conventions-and-docs.md) §The docs).

## Risks

| Risk | Where | Likelihood | Harm | How it's met |
|---|---|---|---|---|
| Blocking or protection behaves differently | 6b | low | high: blocked when it shouldn't be, or not when it should | moved as it is; `ProtectTest` and new service tests of armed and unarmed paths; `test-arm`/`test-disarm` and `force-block` on the phone; released on a weekend; the previous APK kept |
| Data lost or changed in a migration | 3, 5a, 7, 8a, 10a, 11 | low | high | the rehearsal on your latest backup first; migrations tested from every shape and run twice; the old file kept a week; the backup before the install |
| A message of his lost or doubled in the move to the chat | 5a | medium | medium | the chat written before the notification; a test per producer; what his rules count compared before and after; the records migrated with a count compared on your backup |
| The one notification misbehaves on One UI (grouping, a reply not shown) | 5a | medium | low | checked on the phone by you; the fallback is one notification a message, tagged, a builder's switch away |
| Swipe fights the list's scroll | 5b | low | low | the threshold is a third of the width; vertical wins; tried on the phone |
| A move changes behaviour unseen | 4, 6, 9 | medium | medium | moves verbatim, with delegates; the plan snapshot; the goldens; the phone check |
| The chat's schema refused as too large | 8a, 8b, 10a | medium | low | compiled by the API in the PR before anything else; the change-type fallback (Q13) |
| A reminder never fires (the alarm dropped, Doze) | 8a | low | medium | an exact alarm with `USE_EXACT_ALARM`; the sweep at start and at his looks; late marked, never silent |
| A reminder's draft isn't there at the time (offline, resting, the cap) | 8a | medium | low | your words go verbatim, marked as the app's: the reminder is never lost, only plainer |
| The archive grows past the backup's cap | 5a | low (years of use) | low | the oldest messages left out of the backup first, said in Settings → Backup; the file itself is never trimmed |
| The voice costs a change or a date | 10b | medium | medium | the evals re-run before the merge; the "still exact" lines; the examples yours to read first |
| The profile's shape re-reads every task | 10a | certain | low (about 60 cents) | accepted once (Q15) |
| A write touches the wrong conversation | 11 | low | high | only a thread the app made a task from, by its stored thread id and UIDs; only unstar and archive; the writer's command list tested; the first write shown to you; the switch |
| A write races a read | 11 | low | medium | the write journaled first; the task never marked done locally; the Gmail-only sync after; a reverse write for undo |
| Clashes between a cleanup PR and a feature PR | all | low | low | one order, each PR from `main`, nothing in flight at once; features written inside the services they live in |
| Paid calls in checks | all | low | low | `ai-check` against a local stub; a paid call only where the stage needs one, said in the PR |
| The PC's memory during builds | all | low | low | one warm Gradle daemon and Kotlin's beside it, each capped (`gradle.properties`); `./gradlew --stop` after each session's builds |
| The phone's away (school days, 07:40–18:00) | all | certain | time | installs and checks in the evening or at the weekend |

## Rolling back

Each PR's merge can be reverted on its own. A PR that migrated data is undone as [04](04-data-and-migrations.md) §Rolling back says: the previous APK installed over the new one, the data from before restored with `run-as`, never an uninstall. 5a migrates the log's records into the chat and drops them from the log, so the previous APK installed over the migrated files has no Felix history of its own: it comes from the kept `log.json.v1` (the migration mechanism keeps the file it came from for a week) or the backup taken before the install, restored with `run-as` as above. `felix.json`, `chat-archive.json` and `gmail-writes.json` are ignored by an older build. A Gmail write already made isn't undone by a rollback: starring the email again is yours to do in Gmail, which is why the grace and the first-time sheet are there.
