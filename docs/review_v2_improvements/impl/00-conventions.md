# Conventions

Read before any phase. These are the rules the existing code follows; new code follows them too.

## Code

- Kotlin, in the style of the files around it: `object` for pure logic with a `data class` or two beside it; `class` where state is held; one responsibility per file; names that say what, not how (`Slotter`, `SchoolDays`, `FelixPrompts`).
- Comments are sentences that explain *why*, written as the existing ones are (plain, specific, referencing the review item in brackets where it helps: `(PL-12)`). No comment restates the code. KDoc on every public type and function, one or two sentences.
- Pure core first: anything that can be a function of its inputs lives in `core/`, `learn/` or a `…Rules` object, takes `now: Long` and `zone: ZoneId` as parameters, and never touches Android. Android glue (notifications, alarms, content providers, Compose) is thin and calls the pure code.
- `AppGraph` is the one wiring point. New stores, components and workers are created there, as the existing ones are (`val x = JsonStore(File(app.filesDir, "x.json"), X.serializer(), ::X)`).
- Time: epoch millis as `Long` for instants; `LocalDate`/`LocalTime` for calendar days and minutes-of-day; `Window(startMin, endMin)` for a daily window. Convert with the zone given, never the system default.
- Money: `Pricing.costUsd(message)` for every model call; `AiUsage.record` / `modelWorked` / `modelFailed` as the existing callers do.

## Stores and migration

- Every store is a `@Serializable data class` read by `JsonStore` with kotlinx serialization (`ignoreUnknownKeys = true`, so a field removed from a class is skipped in an existing file). The owner is the only user and backwards compatibility between app versions isn't required: add fields with defaults, remove or rename old ones outright, keep no deprecated shims, and don't plan for rollback. What must still hold is that the data on the phone survives each upgrade: where a change would make existing data unreadable or lose something the owner uses, migrate it once at start-up in the same release (`AppGraph.init`) and remove the old shape in that release.
- Never read or write a store's file directly: `store.value`, `store.state` (a `StateFlow`), `store.update { }`.
- `log.json` (`ActivityLog`) is append-only history trimmed by `trimmed(now)` to `KEEP_DAYS`; new record types go there with their own list and a `trimmed` clause.
- `runtime.json` (`RuntimeState`) is state that changes often; `settings.json` is what the person set; `tasks.json` is the merged tasks; keep that split.

## Settings

- Every new `Settings` field needs: a default; a row in `SettingsChanges.LOOSER` (a lambda saying whether a change from old to new loosens blocking, or `NEVER`); a row in `SettingsChanges.LABELS`; a range in `SettingsLimits` if numeric; a field in the Settings screen. `ProtectTest` checks the first two cover every field; run it.
- A setting that loosens blocking goes through the 24-hour delay automatically once LOOSER says so.

## Model calls

- Every call: `MessageCreateParams.builder()`, `model`, `maxTokens(ClaudeEnricher.MAX_TOKENS)`, system text, structured output through `OutputConfig.builder().effort(…).format(JsonOutputFormat.builder().schema(…))`, the fallback header and body property as `ClaudeEnricher.enrich` does, `withContext(Dispatchers.IO)`, `Pricing.costUsd`, the `REFUSAL` and `MAX_TOKENS` stop reasons handled. Copy the shape of `ClaudeEnricher.enrich`.
- Effort comes from `ModelEffort.of(job)` once phase 5 adds it; until then `HIGH`.
- Schemas are maps built with `InstructionPrompts.obj` / `nullable` / `STRING`; every object closed (`additionalProperties: false`), every property required, optional ones nullable.
- Prompt text lives in a `…Prompts` object as a `val` or `fun`, never inline at the call site; the system prompt is the first system block, the profile (phase 5) the second.
- Any change to a prompt or schema bumps `Prompts.VERSION` (phase 8 adds it; until then note the change in `PROGRESS.md`) and updates the golden files (phase 8).

## Tests

- JUnit 4 with `kotlin.test` assertions and `kotlinx-coroutines-test` (`runTest`), as the existing tests. Test names are sentences in backticks that say the behaviour.
- `FixedClock` for time; `Fixtures.at("2026-10-14T17:00")` for London instants; `Planner.Input` built directly for planner tests (see `PlannerTest.plan(...)` helper).
- Pure code gets unit tests; Android glue gets none unless Robolectric is added (phase 8, AR-05 is not in this plan's scope beyond what phase 8 says).
- A deleted feature's tests are deleted with it; a changed behaviour's test is changed, not skipped. Never `@Ignore` to get green.

## Docs

- `docs/scheduler.md` §3 (allocation), §4 (block policy), §5 (learning) and `docs/data-sources.md` §4 (Gmail), §5 (the model), §7 (calendar) must describe the new behaviour when a phase changes it. Write in that file's voice: numbered rules, short, concrete, present tense.
- `README.md` at the root lists features and settings; update its lists.
- Don't edit the review documents (`../0*.md`) except to mark an item **Implemented** in its summary table.

## Commits

- One commit per item or small group of items, message in the imperative with the item ids in the first line (`Spread later work evenly to its deadline (PL-01, PL-07)`).
- No attribution footers, co-author trailers or session links.
- `PROGRESS.md` at the repository root: `## Done` (item, commit), `## Current`, `## Outstanding`, `## Decisions made` (anything a phase file left to the agent). Delete it when the last phase is done.
