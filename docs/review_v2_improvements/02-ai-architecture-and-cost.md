# 2. AI architecture and cost

How the model is called rather than what it's asked: effort and model per job, caching, what happens after a failure, how calls queue, and what's recorded about them. The wording of the prompts is in [01-ai-prompting.md](01-ai-prompting.md).

Prices and limits quoted are the Claude API's as of this review (October 2026): Opus 5.5 $4 in / $20 out per million tokens, cache reads $0.20, cache writes $5 (1 h) ; Sonnet 5.5 $2 / $10; Haiku 5.5 $0.10 / $0.50. On Opus 5.5 thinking is always on and the effort setting is the depth knob; its default is `medium`, and the app sets `HIGH` on every job (`enrich/ClaudeEnricher.kt:49`, `InstructionReader.kt:296`, `ClaudeReviewer.kt:41`, `PhotoChecker.kt:59`, `ChatReader.kt:422`). The prompt cache needs a prefix of at least 512 tokens on Opus 5.5 and is silently not used below that.

## Summary

| ID | Improvement | Priority | Effort | Decision |
|---|---|---|---|---|
| AC-01 | Cache the stable prefix of each job | P3 | S | – |
| AC-02 | Effort per job: HIGH only where judgement is hard | P2 | S | Q-10 |
| AC-03 | A cheaper model for email triage, decided by the eval set | P2 | M | Q-10 |
| AC-04 | Rest by what failed, not a flat hour for everything | P1 | M | – |
| AC-05 | Interactive calls shouldn't queue behind enrichment | P1 | S | – |
| AC-06 | A prompt version on every enrichment | P2 | S | – |
| AC-07 | A record of recent calls you can read on the phone | P2 | M | – |
| AC-08 | Progress while Claude thinks, and a way to give up | P2 | S | – |
| AC-09 | Skip the model for emails a sender rule settles | P2 | M | Q-11 |

---

## AC-01 Cache the stable prefix of each job

**Now.** Every call sends the job's system prompt and schema afresh and pays full input price for them. The system prompts are a few hundred tokens each, which is below the 512-token minimum, so adding `cache_control` today would do nothing.

**Why.** With the student profile (AI-05) and worked examples (AI-07) the system block passes 512 tokens and becomes cacheable. Calls come in bursts (a sync enriches several tasks within a minute; a chat is several turns within an hour), so within the 1-hour TTL most calls after the first would read the prefix at a twentieth of the price.

**Proposal.** Send the system prompt as a text block with `cacheControl(CacheControlEphemeral.builder().ttl(TTL_1H).build())` (`.systemOfTextBlockParams(List.of(TextBlockParam.builder().text(...).cacheControl(...).build()))` in the Java SDK). Put the stable text first (instructions, examples, profile) and anything that changes (the date, "today is") in the user message, which `describe` already does. Record `usage().cacheReadInputTokens()` in the ledger (AC-07) so you can see it working. The pricing code already handles cache reads and writes (`Pricing.costUsd`).

**Final state.** The second and later calls in a burst pay about 5 % for the shared prefix. On the ledger, "cached" tokens appear next to input tokens.

**Caveats.** Be honest about the size of the win: a call's cost is mostly output (thinking at HIGH effort, at $20 per million) and the email's own text, neither of which caches. Expect a few per cent off the monthly bill, not a third. The first call in each hour pays 25 % more for the write. It's worth doing because it's a few lines, not because it's big.

**Effort.** S, after AI-05/AI-07.

## AC-02 Effort per job: HIGH only where judgement is hard

**Now.** `HIGH` everywhere, including the Effort job for Power Planner items (most are "an appointment, 5 minutes, no blocks"), the photo check (a yes/no with a picture) and the chat (where a 20–40 s wait is the cost the student feels).

**Why.** Thinking tokens are output tokens. At HIGH the model may spend a few thousand of them on a newsletter. The eval set exists now, so the quality cost of a lower setting can be measured rather than guessed.

**Proposal.** A per-job effort table, defaults: Email HIGH, Assignment HIGH, Effort MEDIUM, Instruction HIGH, Chat MEDIUM, Photo MEDIUM, Review HIGH. Run `EvalSetTest.score` on the Email and Assignment sets at MEDIUM too; if the score holds, drop them as well. Expose the table in the debug commands, not in Settings.

**Final state.** The chat answers faster; the bill drops on the easy jobs; the hard readings keep HIGH.

**Caveats.** The eval set is small, so "the score holds" is weak evidence; keep HIGH for Email and Assignment unless the set grows. Effort also affects how carefully the model follows the schema's rules (dates, sums), so a drop could show up as more dropped plans, which the alert makes visible.

**Effort.** S. **Decision.** Q-10.

## AC-03 A cheaper model for email triage, decided by the eval set

**Now.** Opus 5.5 reads every email. Your v1 verdict on cheaper models (IMP-P2-026) was "only with an evaluation set". The set now exists (`private/eval`, `EvalSetTest.dump` and `score`, `docs/eval.md`).

**Proposal.** A `model` per job beside the effort (AC-02), and one experiment: dump the Email set, answer it with `claude-sonnet-5-5` at HIGH and with Opus at HIGH, score both. Adopt Sonnet for Email only if its score matches Opus's on the cases that matter (forms, dates, self-notes). Don't try Haiku 5.5 for triage: at 40× cheaper it's tempting, but the eval set's hard cases are judgement calls ("is this for the student?"), which is where small models fail, and one missed form costs more than a month of calls saves.

**Final state.** Two model ids in the code, one per job table; the ledger shows each call's model; the price table already has a row per model (`Pricing.rates`).

**Caveats.** Two models means two sets of eval scores to keep up. Opus on everything is the simple, defensible default; the experiment is cheap (one eval run) and may well say "stay".

**Effort.** M (the plumbing is small; the eval run is the work). **Decision.** Q-10.

## AC-04 Rest by what failed, not a flat hour for everything

**Now.** Any failed call puts the model to rest for an hour (`ModelHold.REST_MS`, `enrich/ModelHold.kt:20`; `AppGraph.modelFailed`, `AppGraph.kt:638`), and the rest applies to everything: enrichment, the chat (`AppGraph.kt:1042`), the photo check (`block/PhotoChecks.kt:52`) and the weekly review (`learn/Review.kt:91`). The SDK already retries twice with backoff inside the call (`.maxRetries(2)`, `ClaudeEnricher.kt:38`).

**Why.** One overloaded API minute at 16:50 means no chat and no photo check until 17:50, on a school night. A key problem is already its own state (`KeyProblem`) and doesn't need the rest; a request the API rejected as malformed (a 400) won't get better by waiting an hour, and will fail the same way at 17:50; a dropped connection on the bus wants a retry in five minutes, not sixty.

**Proposal.** Classify the failure in `modelFailed` and set the rest from it:

| Failure | Rest | Notes |
|---|---|---|
| 429 rate limit, 529 overloaded | 5 min, then 15, then 60 | `Retry-After` honoured when sent |
| 5xx, timeout | 15 min, then 60 | |
| No network (`UnknownHostException`, connect failure) | none | the worker's network constraint already waits for a connection |
| 400 invalid request | none, but that task is marked "failed under prompt version N" | and the error is kept for AC-07; another task may well work |
| 401/403 key or account | as now (KeyProblem) | |

The rest gates background enrichment only. The chat and the photo check go through during a rest except after a key problem or at the cap, with the hold's reason shown if they fail too. Keep the "resting" line on Setup, with the minutes left.

**Final state.** A bad minute costs five minutes, not an hour. A prompt bug shows up as a named error on one task instead of an hour's silence.

**Caveats.** A retry storm is the risk: cap it at three attempts an hour across the app, counting interactive calls. An overloaded API that fails every call still costs nothing (failed calls aren't billed).

**Effort.** M.

## AC-05 Interactive calls shouldn't queue behind enrichment

**Now.** Enrichment, the chat, instructions and the photo check all take the one `modelCalls` lock (`AppGraph.kt:237`). A chat question asked while a sync is enriching waits for the call in flight, and a call can take up to three minutes before its timeout (`ClaudeEnricher.kt:38`), twice more on retry.

**Why.** The lock exists so the cap and the rest are checked just before each send, which is right. But a student typing a question shouldn't wait behind a newsletter being read.

**Proposal.** Keep the lock for the check-and-count (a few microseconds), not for the call itself: take it, check the hold, count the call, release, then send. The cap's worst-case margin (`AiUsage.allows`, one `WORST_CALL_USD` of headroom) should then count calls in flight, so two concurrent calls can't both squeeze under it: keep an `inFlight` counter and require `spent + inFlight × WORST_CALL_USD + WORST_CALL_USD <= cap`. Enrichment stays one-at-a-time by its own loop.

**Final state.** The chat answers while enrichment carries on. The cap can't be overshot.

**Caveats.** Two calls at once double the peak rate; the API's limits are far above two. The ledger must be updated under the lock, as it is.

**Effort.** S.

## AC-06 A prompt version on every enrichment

**Now.** You settled v1's provenance question (IMP-P2-017) with "a version number to bump when the prompt next changes". There isn't one yet. An enrichment records who made it (`by`) and the input hash, not which prompt.

**Proposal.** `Prompts.VERSION` (an integer bumped by hand with every change to a system prompt or schema), stored as `Enrichment.promptVersion`, written into the eval dump and shown on the task sheet's "by Claude" line in the debug build. Re-enrichment on a bump should be lazy: open tasks only, oldest prompt first, inside the existing 20-calls-a-run cap (`MAX_MODEL_CALLS`, `AppGraph.kt:1408`), so a bump doesn't fire thirty calls at once. A "Read again with Claude" action on a task's sheet covers the one-off case.

**Final state.** When a reading looks odd you can see which prompt made it; the eval can score by version; a prompt change rolls through the open tasks over a day.

**Caveats.** Folding the version into `inputHash` instead would be one line, but would re-enrich finished and stale tasks too and burn the cap. The lazy form is a dozen lines more and worth it.

**Effort.** S.

## AC-07 A record of recent calls you can read on the phone

**Now.** The ledger keeps the month's spend, call count, refusals, failures and the last error (`enrich/AiUsage.kt`). Per call, nothing: no job, no task, no tokens, no latency, no outcome. The `ai-prompts` and `enrich-task` debug commands need adb.

**Proposal.** A ring of the last 50 calls in `runtime.json`: time, job, task title (first 40 characters), model, effort, tokens in/out/cache read, latency, stop reason, outcome (used, dropped and why, refused, failed and the error class), cost. Shown as a list under Settings → Claude → Recent calls, newest first, each row expandable; "Copy" puts the row on the clipboard. The eval's dump could read from it to add real cases.

**Final state.** "Claude's plan was dropped" is one tap from "the steps added up to 180, not 200". Cache hits (AC-01) and effort changes (AC-02) are visible.

**Caveats.** The text of the request and reply are deliberately not kept (they hold emails); the row holds counts and outcomes only. 50 rows at ~200 bytes is nothing.

**Effort.** M.

## AC-08 Progress while Claude thinks, and a way to give up

**Now.** The chat shows a spinner until the reply arrives or the 3-minute timeout fires. The student can't tell a 20-second think from a stuck call, and can't cancel.

**Proposal.** A line under the spinner with the seconds elapsed and a stage ("Sending", "Reading your plan", "Writing"), the last two guessed from elapsed time since structured output can't stream a partial JSON usefully; a Cancel after 10 s that abandons the call (the coroutine's cancellation closes the HTTP call). With MEDIUM effort for the chat (AC-02), typical replies should arrive in under 15 s.

**Caveats.** A cancelled call is still billed for what it used. Streaming the reply text would be nicer, but the answer is a JSON object with the reply inside it, so streaming would need a tolerant partial parser; not worth it at this size.

**Effort.** S.

## AC-09 Skip the model for emails a sender rule settles

**Now.** With Claude on, every new Gmail thread goes to the model (`Enrichments.jobFor`), including the fifth newsletter this week from the same address that the student has marked Not a task four times.

**Proposal.** Sender rules (DX-05 in [03-data-extraction.md](03-data-extraction.md)): once the student has marked three emails from one sender Not a task, or has said so in the chat ("everything from UCAS newsletters is just reading"), threads from that sender are enriched by the rules as Info without a call, and listed in the hidden-emails section (AI-11) with the rule named. Senders with bulk-mail headers (DX-04) are only a hint to the model, never an automatic skip: school mass mailers use the same headers and carry forms.

**Final state.** Fewer calls on the inbox's noise; the same readings on everything else.

**Caveats.** A school office address that sends both newsletters and a form once a term must never get a rule; so the rule needs the student's say (three marks, or a chat confirmation), never the app's inference alone, and must be listed and deletable in Settings.

**Effort.** M. **Decision.** Q-11.

---

## What I looked at and left alone

- **The Batches API** (half price, up to 24 h turnaround). The app's volume is a few calls a day, so half of little is little, and the student wants a new assignment's steps within the minute. Not worth the second code path.
- **The order enrichment runs in**: nearest due date first, undated last (`AppGraph.kt:730`). Right as it is.
- **`max_tokens` 16,000 and the 3-minute timeout**: ample at HIGH and with structured output; a call costs only what it uses.
- **The monthly cap's worst-case margin** (`Pricing.WORST_CALL_USD`, about $1 at the older Opus rates): conservative by design, fine.
- **The cost ledger's rates**: Opus 5.5's current prices are in `Pricing.OPUS_5_5` and the cost is read from the response's usage, so the ledger is right. Worth re-checking the rates when the model id changes.
- **Streaming**: see AC-08.
