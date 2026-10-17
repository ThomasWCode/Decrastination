# Conventions, and the docs

How code is named, laid out and commented once the cleanup is done, and how the docs are reorganised. These apply to new code at once, and to old code as each phase touches it.

## Names

- **Claude is the model; Felix is who you talk to.** Code about calling the model says Claude (`ModelGateway`, `ClaudeEnricher`); code about the persona and his messages says Felix (`Speaker.Felix`, `felix/Outbox`). The screens keep saying "Claude" where it's the setting or the service (*Claude on*, *Read again with Claude*).
- **A service is named for its concern**: `Conversation`, `Instructions`, `OwnTasks`, `CalendarWriter`, `Protection`, `Readings`.
- **Prompts and their answers by job**: `ReadingPrompts`/`ReadingAnswers` (now `Prompts`/`Answers`), beside `ChatPrompts`, `InstructionPrompts`, `FelixPrompts`, `SummaryPrompts` and `ResearchPrompts`.
- **Your values are `mine`**, each with `By.You` or `By.Felix`; derived questions read as questions (`yourSteps`, `pacedTeaches`).

## Files

- **One top-level type a file**, named for it, but for a small sealed family or a type's private helpers. `ChatReader.kt` becomes `ChatPrompts.kt`, `ChatAnswers.kt` and `ChatReader.kt`; `InstructionReader.kt` likewise.
- **One test class a file**: `EnrichTest.kt`'s seven classes become seven files.
- **Imports, not inline fully qualified names**, but where a name would be ambiguous (`java.time.Duration` beside `kotlin.time.Duration`).
- **A package a concern**: Felix's code in `felix/`; the platform's in `notify/` and `enrich/` (the gateway) as now; services in their concern's package.

## Code

- **Services are built like `Focus`**: stores, the clock and ports in the constructor; no `AppGraph` inside.
- **One writer a store**; others read its flow or call its owner.
- **Pure where it can be**: rules and calculations in `core/`, tested directly; services hold the effects.
- **Stored types**: `@Serializable` with defaults, a version, migrations for any change of shape. A field kept only so old files read is migrated away and deleted, not kept with a "from before" note.
- **Errors**: as now, a failed effect logged (`Log.w`) and the rest carried on; a store's `update` never throws away what it couldn't write.

## Comments

- **What and why, in the present.** A comment says what the code does and why it must; how it came to be is git's and the change log's.
- **Requirement ids stay** (PL-12, AI-19, BL-08): they tie the code to the spec.
- **Your decisions are referenced, not retold**: "(D-14)", pointing to `docs/decisions.md`, in place of "your call, 17 Oct" and a paraphrase.
- **No review history**: "#48's second review" and "since 3.1.2" go; the PR that made a change is in `git blame`.
- **The voice stays**: plain British English, short sentences, as the code is written now.

## Tests

- **Fixtures are made up**: no task, event, person or address from your data, ever. A test that needs real data's shape uses the rehearsal (`private/`, never committed).
- **Snapshots and goldens change on purpose only** (`-PupdateSnapshots=true`, `-PupdateGolden=true`), with the diff in the PR.
- **The architecture test** guards the rules that can be checked by reading the code ([02](02-target-architecture.md#the-rules)).
- **Every service has its own tests**, built on stores in a temporary folder, as `FocusTest` does.

## The docs

### The shape (made on 17 Oct, your call)

| Where | What |
|---|---|
| `README.md` | what the app is, how to build and install it, where everything else is |
| `docs/spec/` | how the app works now, one area a file: `sources.md`, `readings.md`, `planner.md`, `slots-and-calendar.md`, `blocking-and-protection.md`, `felix.md`, `learning.md`, `storage-and-backups.md`, `privacy.md`; and `requirement-ids.md`, what each id the code cites asks of the app |
| `docs/decisions.md` | your decisions, numbered (D-1…), dated, each with what it means for the code and where it applies; and what's still open for you |
| `docs/development.md` | how changes are made, reviewed, checked on the phone and shipped; the public repository's rules |
| `docs/plan_targeted_cleanup/` | this plan |

Nothing else: the old plans, reviews, findings and per-release notes were deleted once their decisions and still-true facts had moved, and there's no change log or history folder; git's tags and the PR descriptions hold what changed when.

### Writing the spec

- **Current behaviour only.** No "since", no "was"; a change is a change-log entry, and the spec is edited to match.
- **Short paragraphs**: one idea each, bullets of a line or three. Today's longest lines are 2,800 characters.
- **Requirement ids and decision numbers** where they apply, so the code and the spec point at each other.
- **Kept in step**: a PR that changes behaviour changes its spec file in the same PR, as the docs are kept with the code now.

### Privacy in the docs

- The heading of the old `data-sources.md`'s Gmail section named the account's address. It left the docs with that file; git's history still has it, and removing it from there means rewriting the repository's history (your call, Q8).
- `scripts/scan-public.sh`: the scan done by hand before each push, as a script (email addresses, phone numbers, and any word listed in a git-ignored file of names from your data), run on the diff before committing.
