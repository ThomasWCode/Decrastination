# The stages

The order of work from the app as it is to a product strangers pay for, each stage with what it builds, what it needs from the owner, how long it takes, what has to be true at its end, and what kills it. Estimates rest on the 4.x releases' measured durations (a cleanup move with its reviews about 35 minutes, a feature release's code 20 to 60 minutes and its reviews 30 to 140 minutes; [plan_web/stages.md](../plan_web/stages.md) §Estimates) and on the web plan's own figures where a stage is the same work; stages 2, 5 and 6 are new kinds of work for this repository and carry the widest ranges. Hours are sessions of the kind that built 4.x; calendar time is the owner's, the store's and the users', and is the larger number.

The order serves the gates: the stages most likely to kill the plan come first, before the work that only matters if it lives. Nothing in stages 1 to 5 needs a stranger; stage 6 is the first one that does.

| Stage | What | Hours | Calendar | Gate |
|---|---|---|---|---|
| 0 | Decide and validate: the owner's decisions, twenty conversations, five school IT leads, a waitlist page, the declaration drafted, a solicitor's hour | none of code; 20 to 30 of the owner's | 4 to 6 weeks | Parents who'd pay; Play's form answerable |
| 1 | The engine and the server with accounts: plan_web's stages 0 to 3, the owner id an account id, the key on the server | 40 to 55 | 6 to 8 weeks | The owner's own phone runs on it for a fortnight with no data lost |
| 2 | The Play-shaped phone: a release key, setup with no PC, the declaration, the guard and the device admin out, no-silent-bypass, OEM tables, the harness | 25 to 40 | 4 to 8 weeks, mostly Play's | A closed-testing listing accepted with the accessibility declaration |
| 3 | Ingestion without the school: the forwarding address, notification emails read into tasks, ICS, the share sheet, the timetable with presets; the widget a companion | 30 to 45 | 4 to 6 weeks | Made-up notification emails of four platforms read at 95 % |
| 4 | Felix for strangers: the envelope, presets, disclosure, distress, integrity, models by job, the budget, the suite | 25 to 35 | 3 to 5 weeks | The suite's judge rates the Coach and Mate presets at the owner's sign-off level on Haiku or Sonnet |
| 5 | Accounts people can buy: the parent account, the web checkout and the Play subscription, the allowance, the policies, the age gate | 30 to 45 | 4 to 6 weeks, the solicitor's among them | A stranger can pay, pair a parent, export and delete everything |
| 6 | The beta: 25 families, then 100, through a term | 15 to 25 of code | 12 to 16 weeks | Retention, kept slots, cost and support within the numbers below |
| 7 | The listing: open testing to production, the website, the free tier's source, the September push | 10 to 15 | 4 to 6 weeks | Live in the UK on Play |
| 8 | Schools: Graph and Classroom with consent, two or three sixth forms | 20 to 30 a platform | A school year | A school's pupils on it through the school's consent |
| 9 | iOS, if the Android numbers earn it, or first if stage 0's phone share says so | 150 to 250 | 6 to 9 months | A second client on Screen Time's API, with Family Sharing's lock |
| **1 to 7** | | **175 to 260** | **9 to 13 months** | |

For scale: the whole 4.x plan, fifty merged PRs, ran in a week of evenings; the web plan is 61 to 83 hours. This is three to four times the web plan in code, and most of the calendar is waiting on people: Play's review, the solicitor, the families, the term.

## Stage 0: decide and validate

No code. The owner reads [decisions-needed.md](decisions-needed.md) and answers Q139 to Q150; the answers set the rest of this file. Then:

- **Twenty conversations**: ten parents of 15- to 17-year-olds who procrastinate, ten students, found through the owner's own school and two others. Fifteen minutes each: how the homework reaches the student today (Teams? Classroom? Satchel? paper?), what they've tried, what the parent would pay for the sentence in [niche.md](niche.md) §What the niche is worth, and whether the student would arm it. Written up, no names.
- **Five school IT leads** (by email): would they consent to a read-only Graph assignments permission for a third-party app a parent chose; what would they need to see. Their answers decide whether stage 8 exists.
- **A waitlist page** at the product's domain (not the owner's personal one): the sentence, three screenshots, an email box. Spend £100 on ads to parents in two towns; count sign-ups.
- **The declaration drafted**: Play's accessibility-service declaration and the data-safety form, written as if filing them ([compliance.md](compliance.md)); the exercise shows which parts of the guard and the watchdog can't be justified.
- **A solicitor's hour**: the Children's Code's reach, the company, the ICO registration, the terms. About £300.
- **The phone share**: the share of UK 15- to 17-year-olds on Android, from Ofcom or a survey ([risks.md](risks.md) R5). If it's under a third, the plan's order changes: iOS can't be stage 9.
- **The notification path**: whether Play lets a `NotificationListenerService` read Teams', Classroom's and Satchel One's alerts of new work ([compliance.md](compliance.md) §Not settled), and which of the four platforms send notification emails to students by default. The answers size stage 3's moat.

**Done when**: at least six of ten parents say they'd pay £8 a month or £59 a year for it, unprompted on price; at least 50 waitlist sign-ups from £100; the declaration's answers hold. **Killed when**: fewer than three parents would pay, or the declaration can't be answered without the guard and the guard can't be narrowed, or the Android share makes the market a rounding error.

## Stage 1: the engine and the server, with accounts

plan_web's stages 0 to 3 as written ([plan_web/stages.md](../plan_web/stages.md)): `:engine`, the Ktor server, SQLite, the command log, the server's syncs and Claude's gateway on the server; with two changes made from the first line, because adding them later is a rewrite:

- every table's owner id is an **account id**, and a device pairs to an account (W-3 reversed in design, not yet in use: the owner is still the only account);
- the server's Claude gateway keeps a **per-account allowance** beside the one record, degrading to the app's words when spent.

The phone becomes the client plan_web describes (W-2: the replica, the outbox, offline rules). Power Planner and Gmail syncs run on the server as W-8 says, because the owner uses them; the product's own ingestion comes in stage 3.

**Done when**: the owner's phone has run on the server for a fortnight, the restore drill has been run from a clean box, and `ArchitectureTest` and the goldens hold. **Killed when**: nothing kills it; this is the web plan, worth building whatever happens to the product.

## Stage 2: the Play-shaped phone

The rows marked **blocks** under [what-changes.md](what-changes.md) §Distribution and the phone:

- a release signing key, in Play App Signing or a secrets store; the widget's provider re-keyed to the product's signature for those who install it;
- setup with no PC: notifications asked for in the app; the accessibility service enabled from a guided screen with the restricted-settings step shown (for a Play install it isn't needed); credentials typed; no `WRITE_SECURE_SETTINGS` in the product's manifest;
- the settings guard and the device admin out of the product's build, both named prohibited uses on Play ([compliance.md](compliance.md) §Google Play), kept behind a flag for the owner's sideloaded build; protection as "no silent bypass": the service's disconnect and the half-minute check record blocking off, the student is warned, and the parent account is told once stage 5 gives it one; the guard's OEM pages and English strings matter only to the owner's build;
- `QUERY_ALL_PACKAGES` replaced by `<queries>` for the blockable categories, or declared;
- a device lab of five makes (Samsung, Google, Xiaomi, OnePlus, Motorola) and an emulator harness for the blocker's non-UI rules (`BlockPolicy`, `Focus`, `GuardRules` on captured windows), D-56 ended;
- a closed-testing track on Play with the declaration and the data-safety form, the owner and five friends as testers.

**Done when**: Play accepts the closed-testing build with the accessibility declaration, and the blocker survives a week on each of the five makes without silently stopping. **Killed when**: Play refuses the declaration after one appeal and one narrowing. The fallback is a signed APK with an updater and the restricted-settings step in onboarding, which reaches the few who'll sideload; whether to go on at that reach is the owner's call (Q140).

## Stage 3: ingestion without the school

The product's primary path never asks the school's permission:

- a **forwarding address** per account on the server (`<id>@in.<domain>`), with a one-page guide to the Gmail and Outlook filter that forwards the school's senders; mail in through Postmark or SES; each message read as the Email job reads a starred conversation today, a notification of new work becoming a task with its title, class, due date and link; the readings' eval set gains made-up notifications from Teams, Google Classroom, Satchel One and ClassCharts;
- **ICS feeds**: a feed URL as a source, events with due dates as tasks, lesson events as windows (the timetable), term dates as days off; Canvas, Classroom's calendar and school timetable exports first;
- the **share sheet**: anything shared becomes a task through the photo-to-tasks path (NF-01), text and screenshots alike;
- a **timetable editor** with the school-kind presets ([what-changes.md](what-changes.md) §The school's shape), replacing Power Planner's timetable as the source of windows;
- "done by your word" for email and ICS tasks, said so on the sheet, earning as a task of your own (D-54);
- the **Teams widget** as an optional companion for power users: its own install, off Play, its provider trusting the product's release signature; the product's Teams source reads it when it's there and says nothing of it when it isn't.

**Done when**: made-up notifications from the four platforms read into tasks with the right title, class, due date and link 95 % of the time; a Canvas and a Classroom ICS feed each make a plan; a shared screenshot of a Teams assignment becomes a task. **Killed when**: nothing; but if stage 0's conversations show the schools of the owner's region send no notification emails and publish no feeds, the gate is the share sheet alone and the moat is thinner than this plan assumes.

## Stage 4: Felix for strangers

[felix-public.md](felix-public.md) §What changes, in full: the envelope every preset carries; the Coach, Mate and Plain presets with the age rule; "stays around the work" for under-18 accounts; disclosure; the distress pre-check and lines by country; the academic-integrity rule and its test set; models by job chosen by the suite; the budget's degradation; ratings as opt-in telemetry. The suite (`EvalRunTest`'s mood set and judge) runs over every preset on Opus 5.5, Sonnet 5.5 and Haiku 5.5, and the cases are rewritten made-up as the product's own.

**Done when**: the judge rates Coach and Mate on the chosen models at or above the level the owner signed off in 4.7.0 and 4.8.0, the integrity set passes whole, the distress set passes whole, and the month's cost on the chosen mix is under $6 on the model of [costs-and-pricing.md](costs-and-pricing.md). **Killed when**: no mix under $11 a month holds the voice; the product then ships Plain as its default and Felix as Plus, and the plan's economics are the Plus tier's.

## Stage 5: accounts people can buy

- the **parent account**: created from the student's app by a pairing code, holding the override secret (the QR kept for an authenticator), receiving the weekly summary the student sends, seeing the pending changes and nothing else; recovery by email;
- **the checkout**: Stripe on the website for the subscription, the app reading the entitlement; or Play Billing where the owner prefers one path ([costs-and-pricing.md](costs-and-pricing.md) §Infrastructure);
- the **allowance** by tier, shown to the student as a bar, never as dollars;
- the **age gate**: a date of birth at install, the Coach preset and web search off under 16, the product 13 and over;
- **export and delete**: everything about the account as a file, and gone in one tap with a day's grace;
- the **policies**: privacy policy, terms, the DPIA, Play's data-safety form, the AI disclosure, all read by the solicitor; the company, the ICO registration, insurance;
- **crash reports and counts**, opt in, content-free.

**Done when**: a stranger with no help from the owner installs from closed testing, pays, pairs a parent, arms it, uses it a week, exports and deletes. **Killed when**: the solicitor says the Children's Code can't be met with the persona as designed; then the product is Plain by default under 18 and the plan continues.

## Stage 6: the beta

Twenty-five families from the waitlist, then a hundred, through a school term, on Standard for free. Measured on the server, content-free:

| Measure | Keep going if |
|---|---|
| Still active after 30 days | over 40 % of students |
| Slots kept, of slots laid | over 50 % by week four (the owner's own figure is the yardstick; Stats has it) |
| Felix's effect | kept-slot rate with Coach or Mate against Plain, the preset drawn at random for consenting families: a difference worth paying for, or Felix is Plus |
| Model cost a student a month | under $6 on Standard |
| Support | under 20 minutes a family a month after the first week |
| Parents who'd pay at the term's end | over half |
| Blocker silently stopped | under 5 % of device-weeks |
| Distress path fired | counted; every firing read by the owner |

**Done when**: the table holds for the hundred. **Killed when**: retention under 25 %, or fewer than a third of parents would pay, or cost over $11 with no mix that holds the voice. This is the stage that answers whether there is a product; everything before it is what it costs to ask.

## Stage 7: the listing

Open testing to production on Play in the UK; the website with the sentence, the price and the parent's page; the free tier's source and the self-host guide on the public repository; the September push (the waitlist, the beta families' schools, two newspapers' education desks, the parenting forums). The widget's companion build linked from the website with its warnings.

## Stage 8: schools

With stage 0's five answers and the beta's two or three schools: the Graph Education assignments permission (delegated, read-only) and the Google Classroom API, each behind the school's consent ([compliance.md](compliance.md) §School platforms); a school page showing a count of pupils and nothing else; a price a pupil a year. A school year of sales for a product with a hundred-family record is the honest estimate; the ingestion is better than stage 3's (the instructions come with the assignment), and that's the pitch to the school: its pupils' plans get better and it sees nothing.

## Stage 9: iOS

Only if the Android numbers earn it, and only as a second client in Swift (or Kotlin Multiplatform for the engine, which plan_web's W-4 declined for the website): Screen Time's API shields apps on the app's own logic, so contingent blocking is possible; the Teams companion isn't, and isn't wanted. Two things make iOS more than a port. Family Sharing's `.child` authorisation has Apple stop the child deleting the app and the parent approve it ([compliance.md](compliance.md) §iOS): the lock Play forbids on Android, given back by the platform. And because the server plans (W-4, W-14), an iOS client can be thin: it renders the server's views, shields the picked apps when the plan says, and shows Felix's messages; offline planning would wait for a Kotlin Multiplatform engine. If stage 0's phone share shows most of the market on iPhone, this isn't stage 9: it's a different plan, and the Android app is its prototype.

## What each stage leaves for the owner's own use

Every stage keeps the owner's phone working as it does today: stage 1 is the web plan; stage 2's release key signs the owner's build too; stage 3 adds sources and takes none away (Power Planner and the widget stay the owner's); stage 4's presets include the owner's prompts as one; stage 5's accounts have the owner's as the first. The product and the personal app are one codebase throughout, as plan_web's W-17 wants.
