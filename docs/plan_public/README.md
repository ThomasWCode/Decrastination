# Decrastination as a public product: the release and business plan

Asked for on 31 Oct 2026: whether the app could be made public instead of built for its owner alone, what keeps its niche from the saturated field of procrastination apps, what would have to change, and whether it's viable at all. Written from the code at 4.10.0 (versionCode 96; the Teams widget at 0.5.1), every spec and decision in `docs/`, a code sweep of both repositories, and web research on the market, the platforms, the school systems, the model's terms and the law, all on that day. Nothing here is built; nothing here is decided: the decisions are the owner's, listed in [decisions-needed.md](decisions-needed.md).

## The verdict

**In its current form, no.** The app is a personal appliance, and a very good one: sideloaded over adb to one phone, signed with a key committed to a public repository, granted a permission only adb can grant, reading Teams by driving the Teams app's own screens, reading Power Planner on another product's development key, reading Gmail by an app password a school account usually can't make, calling Opus 5.5 at high effort for every job on the owner's own key at $12 to $28 a user a month, in a UK sixth-former's school day, through a persona one person tuned to their own taste over seventeen runs, with their first name and "your dad's authenticator" in the code. None of that is a fault; all of it is what "built for one" means. A stranger can't install it, a store wouldn't list it, and a parent couldn't pay for it.

**Its niche is real, and empty.** Nobody ships the combination of **work read from where the school puts it, enforcement contingent on that work being done, and a commitment device a parent holds**, with a voice that acts and cares. The research found no product with three of its four pillars and two with two ([market.md](market.md) §The gap). App blockers are saturated and block by the clock; planners are saturated and need the work typed in; AI schedulers are for adults at work; companions have a relationship and no work. This app is none of them, and a public version must stay none of them: [niche.md](niche.md) §The rules a public version keeps says how.

**A public version is possible, on a narrow path, at the cost of four things the owner chose.**

1. **The locks become a witness.** Google Play forbids an app stopping its own removal: the settings guard and the device admin are named prohibited uses ([compliance.md](compliance.md) §Google Play). On Android the product can't stop a student switching it off; it can notice and tell the parent. "No bypass" (D-45) becomes "no silent bypass". On iOS, Apple's Family Sharing gives the lock back, which makes iOS the stronger platform for the core promise and the Android app, possibly, its prototype.
2. **Teams goes; what the school sends comes.** Every API to a school's work needs the school's consent; driving another app's screens is contestable under Play's and Microsoft's terms. The product's own path is notification emails, calendar feeds and the share sheet, read by the model as the app reads starred emails today; the Teams widget stays a power user's companion off Play; the school-consented APIs are a partner tier.
3. **Felix's register becomes a preset.** The voice as built ("the closest person you've got", "in charge because you want it that way", let down and proud) is right for the person who asked for it and can't be a default for strangers of sixteen in the year regulators and courts are examining companion chatbots for minors. The product ships him as the voice of a planner: three presets inside one envelope, disclosed as an AI, with a distress path and an academic-integrity rule, staying around the work, searching nothing ([felix-public.md](felix-public.md)).
4. **Opus becomes a mix.** No consumer price covers the app's model bill. The only mix with a margin at £7.99 a month puts Haiku 5.5 on the routine and Sonnet 5.5 on the chat, at $2.50 to $6 a user; whether it holds the voice is the first thing to measure ([costs-and-pricing.md](costs-and-pricing.md)).

**The odds, judged, not computed**: perhaps one in four or five that this reaches 2 000 paying accounts within two years of starting, which is a living for one person and under one percent of UK sixth-formers. Most of the ways it fails are cheap to find: stage 0 costs no code and six weeks, stage 6 a free term with a hundred families. The web plan ([plan_web](../plan_web/README.md)) is stage 1 and worth building whichever way it goes. The honourable alternative to a company is also open: publish the Claude-off app as a complete free product for adults who'll bring their own key, and sell nothing (Q139).

## What's in this folder

| File | What it holds |
|---|---|
| [niche.md](niche.md) | The six parts of the app and which carry the niche; what's distinctive and what's merely the owner's; the nine rules a public version keeps so it never becomes another blocker or planner |
| [what-changes.md](what-changes.md) | The inventory, area by area: what's there, what a public release needs, why, and which decisions it bends, each marked blocks, needed, later or keep |
| [felix-public.md](felix-public.md) | The persona as a product: why he can't ship as he is, what stays, the presets inside one envelope, disclosure, distress, integrity, models by job, the budget |
| [market.md](market.md) | Competitors in four categories with prices and scale, the gap, the market's size, who pays, and what the evidence says, with sources and dates; one commonly cited paper retracted |
| [compliance.md](compliance.md) | Google Play's policies, iOS, the school platforms' APIs, driving another app's screens, Gmail, the model's terms and prices, the UK, the EU and the US, the company |
| [costs-and-pricing.md](costs-and-pricing.md) | What a call and a user cost, the model mixes, infrastructure, tiers and prices, break-even |
| [risks.md](risks.md) | Twelve risks ranked, each with its likelihood, its damage and its gate; the kill criteria in one table |
| [stages.md](stages.md) | Ten stages from decide-and-validate to iOS, each with hours, calendar time, a done-when and a killed-when; 175 to 260 hours and 9 to 13 months to a listing |
| [decisions-needed.md](decisions-needed.md) | Q139 to Q151 for the owner, each with options and a recommendation, and eight choices the plan made for itself |

## The stages in one table

| Stage | What | Gate |
|---|---|---|
| 0 | Decide and validate: Q139 to Q151, twenty conversations, five school IT leads, a waitlist, the declaration drafted, the phone share, a solicitor's hour | Six of ten parents would pay; the declaration answerable; Android not a rounding error |
| 1 | The engine and the server with accounts (the web plan, with an account id and an allowance from the first line) | The owner on it a fortnight, nothing lost |
| 2 | The Play-shaped phone: a release key, no PC, the declaration, the locks out, no silent bypass, five makes | Play accepts closed testing |
| 3 | Ingestion without the school: the forwarding address, ICS, the share sheet, the timetable with presets; the widget a companion | Four platforms' notifications read at 95 % |
| 4 | Felix for strangers: the envelope, presets, disclosure, distress, integrity, models by job, the budget | The suite holds the voice under $6 a month |
| 5 | Accounts people can buy: the parent account, the checkout, the allowance, the age gate, export and delete, the policies, the company | A stranger pays, pairs, arms, exports, deletes |
| 6 | The beta: 25 then 100 families through a term, Felix measured against Plain | Retention over 40 %; half the parents would pay; cost under $6 |
| 7 | The listing in the UK, the website, the free tier's source, the September push | Live |
| 8 | Schools: Graph and Classroom with consent | A school's pupils on it |
| 9 | iOS, when earned, or first if stage 0 says so | A second client with Family Sharing's lock |

## What to decide first

Three answers set everything else: **Q139** (go at all), **Q149** (which platform, which stage 0's phone share informs) and **Q141** (what the parent hears, which is what protection becomes on Play). The rest can wait for stage 0's conversations. Nothing in the codebase changes until Q139 is answered; stage 1 is the web plan and can start regardless.

## What this plan doesn't do

It doesn't change a line of the app, a decision, or a spec; it doesn't name a user, a school or a family; it doesn't claim a number it couldn't source (each figure in [market.md](market.md) and [compliance.md](compliance.md) has its URL and date, and the thin ones are marked); and it doesn't decide anything that's the owner's. Where it had to choose to write at all, [decisions-needed.md](decisions-needed.md) §Decided without asking lists the choice and its reason.
