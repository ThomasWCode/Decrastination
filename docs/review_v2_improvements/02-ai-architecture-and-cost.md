# 2. AI architecture and cost

How the model is called rather than what it's asked. Revised after your answer to Q-10: cost is not the constraint; intelligence and speed are. That removes the cheaper-model experiment, keeps Opus 5.5 everywhere, and makes latency the thing to optimise where you're waiting.

Facts used: Opus 5.5 is $4 in / $20 out per million tokens (cache reads $0.20); thinking is always on and the effort setting is the depth knob (`LOW` … `MAX`), default `medium`; the app sets `HIGH` on every job (`enrich/ClaudeEnricher.kt:49`, `InstructionReader.kt:296`, `ClaudeReviewer.kt:41`, `PhotoChecker.kt:59`, `ChatReader.kt:422`). The prompt cache needs a prefix of at least 512 tokens on Opus 5.5.

## Summary

| ID | Improvement | Priority | Effort | Status |
|---|---|---|---|---|
| AC-01 | Cache the stable prefix of each job | P3 | S | – |
| AC-02 | Effort per job: HIGH for readings, MEDIUM where you're waiting | P1 | S | Changed (Q-10, Q-37) |
| AC-03 | A cheaper model for email triage | – | – | Withdrawn (Q-10) |
| AC-04 | Rest by what failed, not a flat hour for everything | P1 | M | – |
| AC-05 | Interactive calls shouldn't queue behind enrichment | P1 | S | – |
| AC-06 | A prompt version on every enrichment | P2 | S | – |
| AC-07 | A record of recent calls you can read on the phone | P2 | M | – |
| AC-08 | Progress while Claude thinks, and a way to give up | P2 | S | – |
| AC-09 | Skip the model for emails a sender rule settles | – | – | Withdrawn (Q-11) |
| AC-10 | Work ahead: enrich, micro-plan and nag text before they're needed | P1 | M | New |

---

## AC-01 Cache the stable prefix of each job

**Now.** Every call sends the job's system prompt and schema afresh. The system prompts are a few hundred tokens each, below the 512-token minimum, so `cache_control` would do nothing today.

**Proposal.** Once the profile (AI-05) and examples (AI-07) take the system block past 512 tokens, mark it with `cacheControl(CacheControlEphemeral.builder().ttl(TTL_1H).build())` on the text block (`.systemOfTextBlockParams(...)` in the Java SDK). Stable text first; the date and anything that changes in the user message.

**Why, given cost doesn't matter.** A cache hit also trims time to first token slightly. It's a few lines; it's P3 because the gain is small either way, and memories (AI-21) change the block often enough to miss.

**Effort.** S.

## AC-02 Effort per job: HIGH for readings, MEDIUM where you're waiting

**Now.** `HIGH` everywhere. At HIGH the chat takes 20–40 s; a nag reply would take the same.

**Proposal.** You left the table to me (Q-10) with "intelligence and speed, not cost". Opus 5.5 everywhere, and:

| Job | Effort | Why |
|---|---|---|
| Email, Assignment, Effort, Instruction, Review | HIGH | background; accuracy is all that matters |
| Photo-to-task (NF-01), photo as context (AI-23) | HIGH | you wait once; the reading sets a whole task |
| Chat, notification replies (AI-24) | MEDIUM | you're waiting; the answer is conversational and its changes are cards you check |
| Nags (AI-22), micro-plans (AI-15) | MEDIUM | short text, generated ahead (AC-10) |
| Photo check | MEDIUM | a yes/no with a picture |

If a MEDIUM chat ever gives a worse reading of an instruction than HIGH did, the instruction path (which stays HIGH) is where changes are actually made. Confirm the table in Q-37.

**Caveats.** Effort also affects how carefully the model follows the schema's rules; AI-01's repair round catches the misses.

**Effort.** S.

## AC-03 Withdrawn

Sonnet 5.5 for email triage was a cost measure. With cost out of the picture, Opus stays everywhere. If a MEDIUM-effort Opus chat still feels slow, the next lever is Sonnet 5.5 at HIGH for the chat only, measured for latency with AC-07's log; not proposed until measured.

## AC-04 Rest by what failed, not a flat hour for everything

**Now.** Any failed call rests the model for an hour (`ModelHold.REST_MS`, `enrich/ModelHold.kt:20`; `AppGraph.modelFailed`, `AppGraph.kt:638`), and the rest applies to everything: enrichment, the chat (`AppGraph.kt:1042`), the photo check (`block/PhotoChecks.kt:52`), the review (`learn/Review.kt:91`). The SDK already retries twice inside the call (`.maxRetries(2)`).

**Why.** With nags and replies, an hour's silence after one overloaded minute is an hour in which the app stops being a person. A key problem is already its own state; a 400 won't improve by waiting; a dropped connection wants five minutes.

**Proposal.**

| Failure | Rest | Notes |
|---|---|---|
| 429 rate limit, 529 overloaded | 5 min, then 15, then 60 | `Retry-After` honoured |
| 5xx, timeout | 15 min, then 60 | |
| No network | none | the worker's network constraint waits for a connection |
| 400 invalid request | none; that task is marked "failed under prompt version N" | error kept for AC-07 |
| 401/403 | as now (KeyProblem) | |

The rest gates background enrichment only. Chat, replies, nags and the photo check go through during a rest except after a key problem or at the cap. Cap retries at three attempts an hour across the app.

**Effort.** M.

## AC-05 Interactive calls shouldn't queue behind enrichment

**Now.** Enrichment, the chat, instructions, the photo check and the review all take the one `modelCalls` lock (`AppGraph.kt:237`); a question asked while a sync is enriching waits for the call in flight, up to three minutes before its timeout, twice more on retry.

**Proposal.** Hold the lock for the check-and-count only, not the call: take it, check the hold, count, release, send. The cap's margin (`AiUsage.allows`) then counts calls in flight (`spent + inFlight × WORST_CALL_USD + WORST_CALL_USD <= cap`). Enrichment stays one-at-a-time by its own loop; interactive calls run alongside. With AI-24 (replies) this stops being nice-to-have.

**Effort.** S.

## AC-06 A prompt version on every enrichment

**Now.** An enrichment records who made it (`by`) and the input hash, not which prompt.

**Proposal.** `Prompts.VERSION`, stored as `Enrichment.promptVersion`, in the eval dump and on the sheet in debug builds. Re-enrichment on a bump is lazy: open tasks only, inside the 20-calls-a-run cap (`MAX_MODEL_CALLS`, `AppGraph.kt:1408`). A "Read again with Claude" action on the sheet covers one-offs.

**Effort.** S.

## AC-07 A record of recent calls you can read on the phone

**Now.** The ledger keeps the month's spend, calls, refusals, failures and the last error (`enrich/AiUsage.kt`). Per call, nothing.

**Proposal.** A ring of the last 100 calls in `runtime.json`: time, job, task title (first 40 characters), effort, tokens in/out/cache, latency, stop reason, outcome (used, repaired, salvaged, dropped and why, refused, failed and the error class), cost. Under Settings → Claude → Recent calls, each row expandable and copyable. Latency per job is what AC-02's table is tuned against.

**Effort.** M.

## AC-08 Progress while Claude thinks, and a way to give up

**Now.** The chat shows a spinner until the reply arrives or the 3-minute timeout fires.

**Proposal.** Seconds elapsed and a stage line under the spinner; Cancel after 10 s. With MEDIUM effort (AC-02) replies should land in under 15 s. Streaming isn't useful here: the answer is JSON with the reply inside it.

**Effort.** S.

## AC-09 Withdrawn

Sender rules to skip the model were for inbox noise. With starred-only reading (DX-03) there is no noise to skip.

## AC-10 Work ahead: enrich, micro-plan and nag text before they're needed

**Now.** Every call happens when something asks for it: a sync enriches, a tap asks. The micro-plans (AI-15) and nags (AI-22) would each be a wait if made on demand.

**Proposal.** A planning pass after each replan (the plan is deterministic, so it's cheap to know what's coming): make the micro-plan for every slot in today's and tomorrow's plan that doesn't have one; draft the next nag's text for the next slot a few minutes before it's due (the trigger decides whether to send it); pre-warm nothing else. Results keyed by piece and plan version, thrown away when the plan changes under them. The 20-calls-a-run cap applies per pass; the monthly cap stays as the safety net.

**Final state.** A nag arrives at 17:12 written at 17:09 with the plan as it was; a micro-plan is on the card before you look.

**Caveats.** Text written three minutes early can be wrong if you ticked the piece at 17:11; the trigger checks the state before sending. More calls per day (perhaps 20–40); the cap is the guard and you've said cost is fine.

**Effort.** M.

---

## What I looked at and left alone

- **The Batches API**: half price, slow; cost isn't the constraint and latency is. No.
- **The order enrichment runs in**: nearest due date first (`AppGraph.kt:730`). Right.
- **`max_tokens` 16,000 and the 3-minute timeout**: ample.
- **The monthly cap**: keep as a safety net with no default; the worst-case margin (`Pricing.WORST_CALL_USD`) is conservative by design.
- **The cost ledger's rates**: current (`Pricing.OPUS_5_5`), cost read from the response's usage.
