# Risks, ranked, with what kills the plan

Each risk with how likely it looks on the evidence gathered on 31 Oct 2026, what it does to the plan, what's done about it, and where it's tested. "Likely" and "possible" are judgements, not measurements; [stages.md](stages.md) names the gate that turns each into a number.

| | Risk | Likelihood | If it lands | Tested at |
|---|---|---|---|---|
| R1 | Play's policy bars the locks, and may yet bar accessibility-based blocking altogether | The first is certain; the second possible within two years | The promise weakens from "no bypass" to "no silent bypass"; a later ban removes blocking from Android | Stage 0 (the declaration drafted), stage 2 (the listing) |
| R2 | The model's cost per user exceeds what a parent will pay | Likely at the app's current mix; possible at the cheapest | No Standard tier; Felix becomes a Plus feature or Plain | Stage 4 (the suite on Haiku and Sonnet), stage 6 (the record) |
| R3 | Regulation of chatbots for under-18s tightens before launch | Possible: a UK consultation is open, two US states have acted | Felix for minors is restricted or barred; the product ships Plain under 18 | Stage 0 (the solicitor), the watch on the consultation |
| R4 | Ingestion without the school is thinner than the owner's | Likely in part: notification emails carry titles and dates, rarely instructions | The readings are poorer, the steps rougher, the moat half as deep | Stage 0 (the four platforms' emails), stage 3 (the gate) |
| R5 | Most of the market carries an iPhone | Likely for UK teenagers; the number is stage 0's to find | Android-only addresses a minority; iOS is a second client, and the stronger platform for the lock | Stage 0 (the phone share) |
| R6 | The blocker stops silently on some phones | Certain on some OEMs | A parent who finds it off once doesn't renew | Stage 2 (the device lab), stage 6 (device-weeks) |
| R7 | One developer, a student by the app's own account, with exams | Certain | Support and compliance take the time the product needs; accounts need an adult | Stage 0 (the company), every stage's hours |
| R8 | Harm: a wrong block, a wrong "done", the mood landing on a vulnerable student | Possible, and the one that ends a children's product | Press, the store, the ICO; and a student hurt | Stage 4 (the distress and integrity sets), stage 6 (every firing read) |
| R9 | The Teams companion is challenged by Microsoft or Google | Possible if it's visible | The companion goes; the product's path is unaffected if the plan is kept to | Stage 3 (kept off the path) |
| R10 | A privacy incident from the public repository | Possible: the committed key, the fixtures D-80 kept, the owner's name in code | A product's incident, not a hobbyist's | Stage 2 (the key), stage 5 (D-80 reversed) |
| R11 | Opal, or Microsoft and Google themselves, build the combination | Possible: Opal's roadmap says families, students, schools, Android | A funded competitor with the iOS lock and ten million installs | Stage 6's speed |
| R12 | The product pulls against the owner's taste | Certain | Felix's register, the model, the locks: each a loss the owner feels | Every stage keeps the owner's build whole |

## R1. The platform

Play's accessibility policy names "prevent the ability for users to disable or uninstall any app or service" as a use the API cannot have, and its malware policy bans device admin used to block uninstalls ([compliance.md](compliance.md) §Google Play). The settings guard and the device admin are out; the blocker, the block screen and the address-bar check ship with the declaration, as Freedom's, AppBlock's and BlockSite's do. The promise weakens: a student can switch the service off in Settings in ten seconds, and the app can only notice and tell the parent. That is Freedom's model, with three million users, and it's what the evidence on commitment devices supports when the penalty is social ([market.md](market.md) §What the evidence says); but it's less than the owner built, and the plan says so.

The larger risk is the direction: Android 17's Advanced Protection restricts the API to verified accessibility tools when the mode is on, and Google offers no wellbeing API in its place. If Google closes the API to blockers, every Android blocker on Play dies the same day, and this product's third pillar with it. The hedge is iOS, where Apple's own API does the shielding and Family Sharing does the locking (§R5), and a product built so that ingestion, planning and the voice stand without the blocker.

**What kills**: Play refusing the declaration after an appeal and the guard's removal. The fallback, a signed APK outside Play, reaches the few who'll sideload and allow restricted settings, and from 2027 needs the owner's identity verified for more than twenty devices.

## R2. The model's cost

At the app's mix (Opus 5.5 for everything) a user costs $12 to $28 a month in calls; the only mix that leaves a margin at £7.99 puts Haiku 5.5 on the nudges and micro-plans and Sonnet 5.5 on the chat, at $2.50 to $6 ([costs-and-pricing.md](costs-and-pricing.md)). Whether Haiku can carry the register the owner signed off is unknown; the owner's own experiments found low effort as good as medium on Opus and Fable harsher, and never tried the smaller models. The suite decides in stage 4; the allowance and the Plain fallback bound the loss either way. **What kills**: no mix under $11 holds the voice, and parents won't pay Plus prices for it.

## R3. Regulation

The ICO's Children's Code applies to anyone under 18; Ofcom's reading keeps a work-only chatbot outside the Online Safety Act but a web-searching one inside; the UK government opened a consultation in February 2026 on restricting children's use of chatbots; California and New York regulate "companion chatbots" for minors; the FTC's inquiry is open ([compliance.md](compliance.md)). The design answers each as far as a design can: Felix stays around the work, says he's an AI, has a distress path, searches nothing, and is Plain by default below sixteen; the product works with him off. What a design can't answer is a law that restricts AI chat for under-18s outright; then Felix is an adult's feature and the product's voice is the app's words, which the student can still read. **What kills**: nothing, but it can take the fifth pillar away.

## R4. Ingestion

Every API to a school's work goes through the school ([compliance.md](compliance.md) §School platforms). The product's own path, notification emails, calendar feeds and the share sheet, needs no one's permission and carries less: a Classroom email has the title, the class, the due date and a link; the instructions are behind the link. The readings that make the owner's plan good (steps from the instructions, D-20) are poorer from a title alone; the planner still plans, by the kind's default minutes and the student's word. Stage 0 finds which platforms the owner's region uses and what their emails hold; stage 3 adds the notification listener if Play allows it, and the share sheet lets the student hand the instructions over in one gesture. The school-partner tier (stage 8) brings the instructions back where a school consents. **What kills**: nothing; it thins the moat to "the only planner that plans from what your school sends you", which is still a moat.

## R5. The phone in the pocket

UK and US teenagers mostly carry iPhones; this plan has no number for the UK share and stage 0 finds it. If under a third of UK 15- to 17-year-olds carry Android, the market this plan addresses is a few hundred thousand students and the product's natural home is iOS, where the Teams companion can't exist, the blocker is Apple's API, and Family Sharing's `.child` authorisation stops the child deleting the app: the commitment device Android can't offer on Play, given by the platform. The owner's codebase is Android; an iOS client is 150 to 250 hours, thin because the server plans. **What changes**: the order of [stages.md](stages.md), not the plan: stages 1, 3, 4 and 5 are platform-neutral; stage 2 becomes the iOS client; the Android app is the prototype that proved the idea.

## R6. Reliability

Accessibility services are killed by OEM battery managers, dropped silently after crashes at connect, and switched off by system updates; the app's own README documents Samsung turning the widget's service off. The watchdog can't repair without adb. A parent who once finds the blocker off and nobody told them is the renewal lost. The device lab, the OEM tables, the warning on the phone and the parent account's notice are the answer; the measure is device-weeks with the service silently off, under 5 % at the beta.

## R7. The team

The app was built by one person with AI tooling at a pace this plan leans on: fifty merged PRs in a week of evenings. A product adds what that pace doesn't buy: support, the store's correspondence, a solicitor's questions, a parent's email at 22:00, and the owner's own exams. Google Play and Stripe accounts need an adult; a UK company needs a director and an ICO registration. The free tier's community can carry some support; the price carries the rest; the stage gates keep the owner from building the parts a product nobody wants would need.

## R8. Harm

A children's product ends on one story. The ones this product could write: a phone blocked in an emergency (the never-break-the-phone rules keep calls, messages, maps, banking and health off the list and the home screen always reachable, D-49, and they stay); a task marked done that wasn't, so a parent's trust in the summary breaks; and Felix let down with a student who was already down. The distress path, the Coach default, the ceiling in code, the report button and a human reading every distress firing in the beta are the answers; so is saying in the terms what he is. The thing not to do is ship the Mate preset to a stranger of fifteen because it worked for the owner.

## R9, R10, R11, R12

The Teams companion stays a personal tool offered off Play with its terms said plainly, and goes the day Microsoft or Google object; the product never depends on it. The committed signing key is replaced and the fixtures D-80 kept are cleaned before a stranger installs, whatever the owner decides on history (Q150). Opal is the competitor to watch: funded, on the iOS API, with families and schools on its roadmap; what it hasn't got is the work read from the school and the parent's key, and speed in stage 6 is the only answer to a company of eleven. And the product will pull against the owner's taste at every turn, which is why every stage keeps the owner's own build whole: the presets include the owner's prompts, the flag keeps the guard, the owner's account is the first.

## The gates in one table

| Stage | Killed when |
|---|---|
| 0 | Fewer than three of ten parents would pay; the declaration can't be answered; the Android share makes the market a rounding error (then iOS first, not a kill) |
| 2 | Play refuses the declaration after one appeal with the guard and the device admin out |
| 4 | No model mix under $11 a month holds the voice (then Plain by default, Felix as Plus) |
| 5 | The solicitor says the Children's Code can't be met with the persona (then Plain under 18) |
| 6 | Retention under 25 % at 30 days; fewer than a third of parents would pay; cost over $11 with no mix that holds |

Stage 1, the web plan, has no kill: it's worth building for the owner alone.
