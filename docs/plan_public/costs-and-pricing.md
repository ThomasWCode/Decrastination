# Costs and pricing

What the app costs to run for one user, what it would cost to run for many, and what it could charge. The model prices are Anthropic's list prices as the app's own `Pricing.kt` holds them (Opus 5.5: $4 a million tokens in, $20 out, $0.20 cache reads, $8 written to the hour's cache) and as Anthropic's pricing page gave them on 31 Oct 2026 (Sonnet 5.5: $2 in, $10 out, $0.10 cache hits; Haiku 5.5: $0.10 in, $0.50 out, $0.01 cache hits, for prompts under 100K tokens; the Batch API at half price; a web search a cent). The 4.7-and-later tokeniser counts about 30 % more tokens for the same text than older models did, which the app's measurements already include. Every figure below is an estimate from the code's limits and the owner's measurements, stated with its basis; the first week of a beta replaces them with the record (`model.json` keeps every call's tokens and cost, AC-07).

## What one call costs

The app's own anchors: a full re-read of about 40 tasks costs about 60 cents (D-92), so a reading averages **about 1.5 cents** on Opus 5.5 at high effort with the prompt cached; the owner's speech experiments found a Felix message at low effort **about half the cost** of one at medium, so output (thinking included) is the larger share of a message's cost ([plan_v4/answers.md](../plan_v4/answers.md) §Felix's speech). From those and the input limits in code (`Pricing.MAX_INPUT_CHARS` 40 000; a Felix message's text 1 500 characters; the profile up to 16 000; `MAX_TOKENS` 16 000), at Opus 5.5 rates:

| Call | Input (tokens, typical) | Output (typical, thinking in) | Cost each, Opus 5.5 | Basis |
|---|---|---|---|---|
| A reading (Email, Assignment, Effort), high effort | 3 000 to 8 000 | 500 to 3 000 | $0.015 to $0.05 | D-92's measurement; the long end an email with 16 000 characters |
| A Felix message (morning, slot start or end, kept, day done), medium | 5 000 to 10 000 (the story, the diary, the task's text, the profile) | 800 to 1 500 (the note, three texts) | $0.04 to $0.07 | The prompt's parts; low effort about half |
| A chat turn, medium | 8 000 to 13 000 (the context and the plan fill 40 000 characters) | 500 to 1 500 | $0.05 to $0.08, plus a round of the same where he asks for more (up to two) | `ChatPrompts` limits |
| A micro-plan, medium | 1 500 to 2 500 | 150 to 300 | $0.01 to $0.015 | `MicroPlans` |
| The weekly review, high | 10 000 to 20 000 | 2 000 to 4 000 | $0.10 to $0.16 | `ReviewInput` |
| The summary, the memory tidy, medium | 3 000 to 6 000 | 300 to 800 | $0.02 to $0.04 each | |
| A web search | | | $0.01 plus a call of its own | `Pricing` |
| A photo check, medium | one to three images | 200 | $0.03 to $0.08 | |

## What one user costs a month

An active student on a school day: three to five slots, each with a slot-start nudge where nothing's been done, a slot-end message where it went by, a kept-slot message one time in three, the day done, the morning, the evening's completions question; two to four new tasks read; a handful of micro-plans written ahead; some chat. A quiet day much less; Felix is silent when nothing's due (D-58).

| | Calls a school day | At Opus 5.5 | Notes |
|---|---|---|---|
| Readings | 2 to 4 | $0.03 to $0.20 | Plus re-reads when a task's content or the prompts' version changes |
| Felix's messages | 6 to 10 | $0.25 to $0.70 | Some written ahead and not sent are still paid for (AC-10) |
| Chat | 0 to 6 turns | $0 to $0.50 | The widest range: some students will talk to him all evening |
| Micro-plans | 4 to 8 | $0.05 to $0.12 | |
| **A school day** | | **$0.35 to $1.50** | |
| Weekly (review, summary, tidy, check-in's none) | | $0.15 to $0.25 a week | |
| **A month** (22 school days, 8 light days at a third) | | **$12 to $28** | |

That is the honest figure for the app as it is: **$12 to $28 a month of model calls per active user**, on the owner's key, at the owner's "quality over cost" (D-5). No consumer subscription covers it with anything left over. Three levers change it, each to be measured with the suite before it's chosen ([felix-public.md](felix-public.md) §What changes):

| Mix | Readings | Felix's messages, micro-plans | Chat | A month, estimated | What's at stake |
|---|---|---|---|---|---|
| **As built** | Opus 5.5 high | Opus 5.5 medium | Opus 5.5 medium | $12 to $28 | The owner's tuned voice as it is |
| **Low effort** | Opus 5.5 high | Opus 5.5 low | Opus 5.5 low | $8 to $18 | The owner found low about as good for messages |
| **Sonnet for the voice** | Opus 5.5 high | Sonnet 5.5 low or medium | Sonnet 5.5 medium | $5 to $11 | Half the message cost; the voice to be re-judged |
| **Haiku for the routine** | Opus 5.5 high, Batch where not due soon | Haiku 5.5 for nudges and micro-plans; Sonnet 5.5 for the morning | Sonnet 5.5 medium | **$2.50 to $6** | Haiku is forty times cheaper than Opus; whether it can carry the register is the question the suite answers first |
| **Lite** | Sonnet 5.5 medium | Haiku 5.5, fewer nudges (one a slot) | Haiku 5.5, 20 turns a month | $0.80 to $2 | The free-with-ads-free tier's ceiling; Plain past it |

Prompt caching already cuts the system prompts to a fifth of their price (AC-01); the profile and the story change every call and can't be cached across users. The Batch API halves the readings not due within two days, perhaps a third of them. A per-account monthly allowance ([what-changes.md](what-changes.md) §Claude) is what makes any of these a cost the business controls rather than one the student does: past it he writes in the app's words, which cost nothing.

**The target**: the Haiku-for-the-routine mix at **$2.50 to $6 a user a month**, with the suite holding the voice to what the owner signed off, is the only mix that leaves a margin at a consumer price. If Haiku 5.5 can't carry the nudges and Sonnet 5.5 has to, the figure is **$5 to $11** and the price has to be at the top of the range below or the nudges fewer.

## Infrastructure

The web plan's box is Oracle's free tier for one account (W-5). For strangers:

| | Monthly | Basis |
|---|---|---|
| A small VPS (2 vCPU, 4 GB; Hetzner, DigitalOcean or the like) | $5 to $12 | Ktor and SQLite serve hundreds of accounts whose syncs come every 15 minutes; the planner runs in milliseconds; Felix's calls wait on Anthropic, not on the box |
| A second box and Postgres | $30 to $60 | From about 2 000 accounts, or when SQLite's single writer shows |
| Object storage for backups (R2, B2) | $1 to $5 | Per account a few megabytes |
| Email in (the forwarding addresses) and out (the parent's summary, sign-in) | $0 to $20 | Postmark, SES or a mailbox per domain |
| Monitoring, DNS, the domain | $5 to $15 | |
| **Per active account at 1 000 accounts** | **under $0.10** | The model is the cost; the box is noise |

Payment processing: Google Play takes 15 % of subscriptions (its rate for the first million dollars a year); a web checkout through Stripe about 1.5 % plus 20p in the UK. Selling the subscription on the website and letting the app read it is allowed for a non-game app only where the app itself offers no purchase, which is the shape to choose.

## What it could charge

Who pays is the parent, mostly ([market.md](market.md) §Who pays). The evidence for prices is the incumbents' ([market.md](market.md) §Competitors: app blockers at $6 to $20 a month, parental control at $4.50 to $14 a month, AI schedulers for adults at $7 to $19 a month, UK private tutoring at about £24 an hour by an older Sutton Trust figure, and a UK parent-pay revision app at £44.99 a month) and the one fact about this product: it's bought for a result a tutor is bought for, not for screen time.

| Tier | Price | What it is | Model cost |
|---|---|---|---|
| **Free** | £0 | The app with Claude off (D-14): ingestion, the rules' readings, the planner, the calendar, blocking, free time, the parent code, Felix in the app's words. Adult self-hosters bring their own key and get everything (Anthropic's commercial terms aren't for consumer use and its minors' guidelines fall on the key's holder, [compliance.md](compliance.md) §The model's terms) | £0 to the business; this is a complete product, and the one the public repository already is |
| **Standard** | £7.99 a month, £59 a year | Felix on the Haiku-and-Sonnet mix with the Coach or Mate preset, the readings on Opus, a monthly allowance that covers the month above, the parent account, the summary | $2.50 to $6 |
| **Plus** | £12.99 a month, £99 a year | A larger allowance, Sonnet for every message, the Graph and Classroom connections where the school allows, two children on one parent account | $5 to $11 |
| **School** | £12 to £20 a pupil a year, by cohort | The school consents to the Graph or Classroom API, pupils get Standard, the school gets a count of pupils using it and nothing else | $2.50 to $6, twelve months |

At £7.99 through Play, the business keeps about £6.79 ($8.60); at a model cost of $2.50 to $6 and infrastructure under $0.10 the margin on a Standard account is **$2.50 to $6 a month**: thin, real, and dependent on the Haiku question. At £12.99 the Plus margin is $7 to $10. Annual plans (most parents buy once, in September) hold the cash and halve the churn.

## Break-even, in round numbers

One developer, part time, with the AI tooling that built the app: say £1 500 a month for the developer's time at cost, £300 for the boxes, services, the store's fees and insurance, £3 000 a year for a solicitor's reading of the policies and terms. **About £2 000 a month** to keep the lights on.

| Standard accounts | Monthly margin | |
|---|---|---|
| 300 | £750 to £1 400 | Pays the services, not the time |
| 600 | £1 500 to £2 800 | Break-even |
| 2 000 | £5 000 to £9 400 | A living for one |
| 10 000 | £25 000 to £47 000 | A company of two or three |

The UK has about 1.2 million 16- to 18-year-olds in full-time education and about 3.2 million secondary pupils ([market.md](market.md) §Market size), of whom the Android-using, procrastinating subset is a few hundred thousand at most; 2 000 accounts is under one percent of that subset, 10 000 a few percent. Those are not fantasy numbers for a product that works; they are hard numbers for a product nobody has heard of, which is why [stages.md](stages.md) puts a hundred families before a listing and [risks.md](risks.md) puts the model cost and the Haiku question first.
