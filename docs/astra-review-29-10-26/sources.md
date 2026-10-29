# Sources: Gmail, Anki, and their records

Seven findings: six stand (two narrower than written), one rejected. Ids: max INT-, ultra AS-/DH-. Probes in [evidence/](evidence/) assert the behaviour wanted and fail.

### I-01 · P1 · An unchanged Gmail poll stores your rename as the email's own subject

Max INT-01. Reproduced: `AstraIntegrationsReviewTest` "an unchanged Gmail read preserves the source title under a rename" fails ("My short title" where "Original subject" was expected).

- **Where:** `sources/gmail/GmailThreads.kt:210` rebuilds an unchanged conversation's envelope from the task with `subject = task.title`, which is the rename (`core/Enrichment.kt:77`, `mine.title` first). `GmailSource.kt:118-122` takes that path for every conversation whose text is whole and unchanged; `:220` feeds it to `fetched`, and `Merge.kt:100,118` stores the result as `SourceValues.title`.
- **What happens:** rename a Gmail task (Felix or an instruction), let a sync run: the rename is now the source's subject. Undo of the rename shows the short name. The reading's input hash (`Enrichment.kt:222-223`) changes though nothing in Gmail did: the reading's deadline and steps are dropped till a paid re-read (`:118-125`), and the ticks on those steps go with them. The rules re-triage the renamed subject (`EmailRules.kt:84`) and the re-read is sent the rename as the subject (`ReadingPrompts.kt:217`). A new message in the thread is the only recovery.
- **Against:** `sources.md:182`, `readings.md:117`, `InstructionPrompts.kt:56` ("its source keeps its own").
- **Fix:** `task.sourceValues?.title ?: task.title` at `:210`, plus a test that a rename, a poll and an undo leave the hash and the reading as they were. Distinct from I-02.

### I-02 · P1 · The first sync after a quoted deadline passes drops the hard deadline, the reading's steps and their ticks

Ultra AS-01. Reproduced: `AstraAiReviewTest` "an overdue starred email must keep its deadline through the next sync" fails (null where the deadline was).

- **Where:** `sources/gmail/EmailRules.kt:146` keeps a date found in the text only if it's today or later; `GmailThreads.kt:291,300` re-triage every conversation, stored or fetched, with now; `Merge.kt:103,118` replace `dueAt` and `sourceValues.dueAt`. `Enrichment.kt:228` hashes `sourceValues.dueAt`, so the model's reading (with `deadlineQuoted`) goes stale: `:119-125` return a null due and no reading steps, `Merge.kt:119` persists that, and `:176-182` carries ticks from the now-empty list. `:250` queues a paid re-read; the Email prompt doesn't send the rules' date (`ReadingPrompts.kt:214-222`), so the model quotes the same deadline and the hard date returns when the call lands. `MicroPlans.hashOf` (`enrich/MicroPlans.kt:65`) and `TaskItem.pacedTeaches` (`Task.kt:285`) go stale with it.
- **What happens:** a starred email "by 28 October" still starred on the 29th loses its missed state on Day and Work till the re-read (minutes; longer resting, capped or offline); the step ticks don't come back; a reading and its micro-plans are paid for again.
- **Against:** `readings.md:143`: a quoted deadline is shown as missed once it passes. Passage of time is not new content.
- **Fix, with care:** keep a time-dependent fallback out of the hash (for Gmail, an invite's start is the only source date). That changes every existing Gmail task's hash once: a one-off paid re-read of each unless migrated. Keeping past dates in `EmailRules` instead would change what the rules' guess means and what `ReadingAnswers.sourceDue` (`:359`) validates against.

### I-03 · P2 · AnkiDroid dying between two queries counts as no cards left and finishes the homework for good

Max INT-02 (P1). Source-traced.

- **Where:** `sources/anki/AnkiSource.kt:60` `resolver.query(notesUri, …)?.use { it.count } ?: 0`; the decks query at `:51` treats null as the source being away. `core/AnkiRules.kt:338,356` make `left == 0` into `done`; `Merge.kt:122` completes it with its reward and `:124` keeps a derived task done thereafter (`sources.md:172`). `UnseenCache` only shortens the window.
- **Checked:** the reviewer's own countercheck is the point: AnkiDroid's provider answers null for a notes search with no matches, so null alone is not failure. `ContentResolver.query` returns null rather than throwing only when the provider's process dies mid-call, in the seconds between the decks query (which just woke it) and the notes queries. An exception would already be caught (`Syncer.kt:120-123`, the merge skipped). So: a narrow race, but once it fires the false completion stands for the assignment's life, with free time earned. P2 for that: rare and no stored data lost, but unrecoverable.
- **Fix:** query through `acquireUnstableContentProviderClient` (released after), where process death is a `RemoteException` and a healthy null stays "none"; a stable client would kill this app when the provider dies.

### I-04 · P2 · A vocabulary assignment marked "not a task" still switches the Anki day to reviews only

Max INT-04. Reproduced: `AstraIntegrationsReviewTest` "an assignment marked not a task does not suppress the daily new cards" fails ("Anki: 5 reviews" where "+ 20 new" was expected).

- **Where:** `core/AnkiRules.kt:307-308` derives homework from every open non-Anki assignment with no check of `hidden` (`Task.kt:213`, from `mine.notATask`); `:285` `homeworkDueSoon` takes any undone deck due by tomorrow; `sources/anki/AnkiSource.kt:130-133` feeds both to the quota; `:226` latches `reviewsOnly` for the day; `:241` gives no quota at all when no reviews are due.
- **What happens:** mark an open vocabulary assignment not a task while it's due by tomorrow or overdue; the next Anki read withholds the day's new cards and keeps to that all day. An old excluded assignment still listed as overdue does it each new day.
- **Checked:** deriving the task is right (the planner already keeps a hidden assignment's deck off the plan, `Planner.kt:244-250`, `InstructionRulesTest.kt:144-146`; the reviewer withdrew an earlier assertion to that effect); feeding the quota from it is the defect. No decision covers it; D-9 presupposes homework.
- **Fix, with care:** don't filter hidden assignments out of `homeworkDecks`'s loop: the deck task would go unlisted, `Merge.kt:150` would mark it Missed, and an Undo of the exclusion would re-add it with its worked minutes and ticks reset (`:130-138`). Filter only what `homeworkDueSoon` and the quota's homework set see.

### I-05 · P3 · Five comments describe behaviour the code replaced

Max INT-07 listed seven places; five are stale, and the two that said the email is the invite's event (the table's last row: four places in code comments and in DX-02) are stale only in their wording.

| Where | Says | Code does |
|---|---|---|
| `sources/gmail/EmailRules.kt:23-24` | A ticket or open day is an Event, appearing the day before, unstarred the day after | `:93-102`: Admin, 5 min, due at the day's start, no start date |
| `core/Enrichment.kt:65-69` | A reading supplies a deadline only where the source has none, and takes the later start | For Gmail `:133` the reading's deadline wins and `:150` its start replaces the rules' when it gives a kind (the comment describes the other branch) |
| `enrich/EnrichWorker.kt:57-58` | An offline call rests the model for an hour | `Failures.kt:54` gives a network failure no rest; the hour comes only past `MAX_AN_HOUR` failures (`AiUsage.kt:112-115`). The network constraint itself is still right |
| `enrich/ModelGateway.kt:193-195` | The mutex is held from the cap check to the cost's recording | `:210-216` hold it for the hold check and the in-flight count only; `:202-207` describe it right |
| `enrich/ModelCalls.kt:10-12` | A cache read is a tenth of the input price | `Pricing.kt:10`: a twentieth for the named model; a tenth only for the fallback (`:11`) |
| `GmailThreads.kt:284`, `core/Invite.kt:12-13`, `Enrichment.kt:126-127`, `requirement-ids.md:89` (DX-02) | The email is the invite's event | Wording from before D-10's amendment: the email is Admin due at the invite's start (`Enrichment.kt:129-131,144`); `GmailThreads.kt:297` and `sources.md:200` say so |

### I-06 · P3 · D-9 still gives a fixed twenty new cards and a reviews-only switch on the due day

Ultra DH-01.

- **Where:** `decisions.md:20`. The code takes the chosen deck's remaining new count under its own daily limit (`core/AnkiRules.kt:229`; 20 is only the fallback, `:60`) and switches to reviews only when unfinished homework is due by tomorrow or overdue, latched for the day (`:283-286`, `AnkiDay`). `sources.md:77` and `AnkiRulesTest.kt:112,130` describe the code. The by-tomorrow trigger entered on 10 Oct (0970357e); D-9 was written in the 17 Oct restructure and never amended.
- **Fix:** amend D-9 to the code's rule.

### Rejected: INT-05, D-110's "not read again"

Max read D-110 ("its steps … aren't paced again nor read again until the rebalance is undone, as with his changes in the chat") as forbidding a re-read of the task, which `readings.md:92` and the code allow (`Enrichment.kt:201-212,248-253` ignore `mine.steps`; `:78` lays your steps over every reading). The sentence's subject is the steps: a reading no longer supplies or replaces them, which the code honours (`readings.md:90`), and the rebalance's paced total is built to survive a re-read (`Task.kt:280-285`, `pacedTeaches`). Not a contradiction; at most add "(the steps, not the task)" to D-110 and `felix.md:183`.
