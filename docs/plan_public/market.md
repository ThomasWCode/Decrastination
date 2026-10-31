# The market: competitors, size, who pays, and what the evidence says

Researched on 31 Oct 2026 by web search; every figure has its source, and the date where the source shows one. **Est.** marks a third-party app-intelligence estimate (Adapty, AppGoblin and the like), unaudited and often differing between publishers by two to five times. **Thin** marks a claim resting on one weak source. Where nothing credible was found, that's said rather than guessed.

## Competitors

The product sits between four categories. None of them is empty; the intersection is.

### App blockers

| App | Platform | Price and model | Blocks how | Parent features | Scale |
|---|---|---|---|---|---|
| Opal | iOS (Android "first launch" planned, May 2025) | Freemium; $8.99 to $19.99 a month, $59.99 to $99.99 a year ([Adapty](https://adapty.io/paywall-library/opal-screen-time-for-focus/)) | Apple's Screen Time API | Families, students and school partnerships on the roadmap ([Speedinvest, 19 May 2025](https://speedinvest.com/blog/scaling-smart-how-opal-built-a-10m-arr-business-in-just-2-years)) | $10M ARR with 11 staff (Speedinvest, May 2025); $4.3M seed 2021 ([ISAI](https://isai.vc/news/opal-the-digital-wellbeing-app-raises-43m-in-a-seed-round)); a $10M raise and a claimed 1M daily users ([RuntimeWire, 26 May 2026](https://runtimewire.com/article/opal-raises-10m-unveils-new-opal-attention-os-kenneth-schlenker), the founder's own post, thin) |
| Brick | iOS and Android, an NFC device | About $59 once, no subscription ([getbrick.com](https://getbrick.com/pages/apple)) | Apps locked until the phone is tapped on the Brick | None | "Over 500,000 active users", "multiple eight figures in revenue" ([Entrepreneur, 19 May 2026](https://www.entrepreneur.com/business-news/these-founders-built-a-device-that-solves-phone-addiction), single source) |
| Freedom | iOS, Android, Mac, Windows, Chrome | $39.99 a year, $199 for life, about $8.99 a month ([App Store](https://apps.apple.com/gb/app/id1269788228)) | Screen Time or VPN on iOS; accessibility and VPN on Android | None | "Over 3 million" users, self-reported |
| one sec | iOS and Android | Freemium, about €14.99 a year ([screensdesign](https://screensdesign.com/showcase/one-sec-screen-time-focus)); est. $150K a month | A pause before an app opens | None | A peer-reviewed field study (§Evidence) |
| Forest | iOS, Android, Chrome | Free with a Forest Plus subscription since Dec 2025 ([Wikipedia](https://en.wikipedia.org/wiki/Forest_(application)), thin) | A timer; a tree dies | None | 10M+ on Play; est. $100K to $500K a month ([AppGoblin](https://appgoblin.info/apps/cc.forestapp)) |
| Jomo | iOS | $5.99 a month, $29.99 a year, a family plan $59.99 a year ([App Store](https://apps.apple.com/app/id1609960918)) | Screen Time API | A family plan | Est. $80K to $200K a month, thin |
| AppBlock, StayFree, BlockSite, ScreenZen, Unpluq, Roots, ClearSpace | Android and iOS variously | Freemium; BlockSite's iOS monthly $24.99 ([App Store](https://apps.apple.com/app/id1474967653)); Unpluq's tag about $29 plus $6.99 a month | An accessibility service on Android, Screen Time on iOS | None | AppBlock about 211K Play ratings; the rest unknown |
| Cold Turkey | Windows and Mac | About $39 once ([getcoldturkey.com](https://getcoldturkey.com/pricing/)) | Desktop process and hosts blocking | None | Unknown |
| Google Family Link, Digital Wellbeing; Apple Screen Time | Built in | Free | The system's own limits | Yes, both | Everyone; teen bypasses widely documented ([Protect Young Eyes](https://protectyoungeyes.com/12-ingenious-screen-time-hacks-how-to-beat-them/)) |

Every one blocks by a schedule, a usage limit or a session the user starts. None reads the student's work, and none knows whether it got done. Every Android blocker above uses the accessibility service, and all are on Google Play, which asks for a declaration form and prominent in-app disclosure, and threatens termination for "deceptive and non-declared uses" ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/10964491)). Opal is the nearest funded competitor moving toward this ground: families, students, school partnerships and Android are its stated roadmap.

### Student planners and homework apps

| App | Price | Reads an LMS | Blocks | Parent | Scale |
|---|---|---|---|---|---|
| MyStudyLife | Free; Plus $4.99 a month, $29.99 a year; family bundles $49.99 to $99.99 a year ([App Store](https://apps.apple.com/us/app/-/id910639339)) | No, typed in | No | A family bundle | Claims "24M students"; est. 586K installs and about $10K a month ([AppGoblin](https://appgoblin.info/apps/910639339)); the two can't both be right |
| Power Planner | Free, about $4 once ([App Store](https://apps.apple.com/app/id1278178608)) | No | No | No | A one-person developer |
| Shovel | $9.79 a month or $35 a year ([DormWay](https://dormway.app/blog/dormway-vs-shovel), a competitor's page) | **Yes**: Canvas, Brightspace, Moodle, Google Classroom, read-only, about a day behind; syllabus PDFs | No | No | Unknown |
| Studyo Go | Free ([App Store](https://apps.apple.com/be/app/studyo-go/id1580932650)) | **Yes**: Google Classroom coursework | No | A planner shared with parents and teachers | Unknown |
| Saturn | Free | The timetable, not assignments | No | No | $44M raised 2021 ([Built In](https://builtin.com/articles/saturn-raises-44m-hiring-social-calendar-app)) |
| Egenda, Todait, iStudiez | Free or small | No | No | No | Egenda and Todait last updated 2022 |

No consumer planner reads Microsoft Teams assignments, though Graph's education API exposes them and Microsoft's own docs give "a dashboard that shows upcoming due dates" as an example ([Microsoft Learn](https://learn.microsoft.com/graph/education-concept-overview)). The LMS integrations that exist are American and Canvas-and-Classroom-shaped.

### AI schedulers and ADHD planners

| App | Price | Schedules automatically | Scale |
|---|---|---|---|
| Motion | About $19 a month, no free plan ([Launchspace](https://launchspace.net/product/motion-pricing-overview/); sources range $19 to $39) | Yes, fully | $60M Series C Sep 2025 at $550M, $102M in all, over 1M users claimed ([Sacra](https://sacra.com/c/motion)) |
| Reclaim.ai | Free Lite; $8 to $18 a user a month | Yes, into Google Calendar | 320K users when Dropbox bought it, Aug 2024 ([GeekWire](https://geekwire.com/2024/dropbox-acquires-reclaim-a-calendar-app-that-uses-ai-scheduling-to-boost-productivity/)) |
| Structured | Free; Pro $6.99 a month, $29.99 a year ([Saner, Sep 2026](https://blog.saner.ai/structured-review/)) | A visual timeline with an AI replan | Unknown |
| Tiimo | Free; Pro about $7.99 a month or $79.99 a year ([Lifestack](https://lifestack.ai/blog/tiimo-pricing)) | A visual schedule | About $4.8M raised; about 50K paying, 500K free ([Vestbee](https://vestbee.com/insights/articles/tiimo-raises-4-8-m)) |
| Sunsama, Akiflow, Morgen, Amie | $16 to $34 a month | Time-blocking for adults | Unknown |
| Goblin.tools | Free on the web, $1.99 app | Task breakdown by an LLM, no schedule | Est. 18K iOS installs |
| Inflow (ADHD CBT, not a planner) | About $200 a year ([TechCrunch, 11 Jan 2023](https://techcrunch.com/2023/01/11/inflow-a-platform-for-managing-adhd-through-cbt-raises-11m)) | No | $11M Series A |

None reads a school's work or blocks anything; all are built for adults at work. Their prices ($7 to $19 a month) are the nearest evidence of what an AI planner can charge.

### Accountability and coaches

- **Focusmate** (human body-doubling): free for three sessions a week, about $7 to $8 a month unlimited ([toolradar](https://toolradar.com/tools/focusmate/pricing)); 28 % of its users report an ADHD diagnosis (company data).
- **Flown**: £20 a month for human group sessions ([Flown](https://help.flown.com/en/articles/8264144-what-membership-options-do-you-offer)).
- **Finch** (a self-care pet, no LLM): $39.99 to $69.99 a year; est. about $1M a month and 600K downloads a month ([Adapty](https://adapty.io/paywall-library/finch/)). The clearest evidence that teenagers and young adults pay for an emotionally bonded companion.
- **Rize**, **Centered**: "AI productivity coaches" for desk workers at about $10 a month.
- A cluster of small 2025 and 2026 "AI accountability partner" apps (Nag Bot, Focus Buddy, BetterLife, Accountabilify): no school data, no blocking, few ratings.
- **stickK**, **Beeminder**, **Forfeit**: commitment contracts with money at stake, the other way to make a commitment bind.
- **Duolingo's owl**: the passive-aggressive reminder is a deliberate, tested strategy; its former product chief credits the streak-saver notification as the first big win in a 4.5× growth in daily users over four years ([Lenny's Newsletter](https://lennysnewsletter.com/p/how-duolingo-reignited-user-growth)). Company-reported; no peer-reviewed causal evidence on the persona itself.

### The closest analogues

- **FocusFlow** (iOS): pick distracting apps, add tasks or import Canvas assignments, earn screen time by completing them, with optional photo proof; free ([AlternativeTo](https://alternativeto.net/software/focusflow-by-spencer-lay/about/)). **Thin**: a directory page only; the store listing wasn't found and it may not be live.
- **Study Lock: Earn Screen Time** (iOS): import courses, answer AI-made questions to earn time ([App Store](https://apps.apple.com/us/app/id6758857561)). Quiz-gated, not work-gated.
- **Workmaxxing**, **Habit Doom**, **StudyLock**: apps locked until a self-declared task is done; nothing read from a school.

### The gap

Four pillars: (i) automatic ingestion of the school's work; (ii) planning into the calendar; (iii) blocking enforced by whether that work is done; (iv) a persona with a continuing relationship.

| Pillars | Products found |
|---|---|
| i and ii | Shovel; Studyo Go |
| i and iii | FocusFlow (thin) |
| ii alone, with AI | Motion, Reclaim, Structured, Tiimo, Morgen |
| iii alone | Study Lock, Workmaxxing, Habit Doom; every schedule-based blocker by a weaker reading |
| iv alone | Finch (a pet), Nag Bot, Focus Buddy; Rize and Centered for adults |
| **Three or four** | **None found** |

The Teams, Power Planner, AnkiDroid and starred-Gmail combination in particular has no match. The caveat is the long tail: 2025 and 2026 brought many small launches, and the app stores aren't searchable for mechanics. The one competitor to watch is Opal, funded and moving toward families, students, schools and Android.

## Market size

**App blockers.** No primary category figure is public. Order of magnitude only: "digital detox app" reports put 2025 at $1B to $2.5B ([Roots Analysis](https://www.rootsanalysis.com/digital-detox-apps-market); [Growth Market Reports](https://growthmarketreports.com/report/digital-detox-app-market)), both research-mill reports of low quality. The visible leaders (Opal, Brick, Forest, Finch, one sec) sit between $5M and $30M a year, mostly iOS, mostly American.

**Parental control.** $1.05B to $1.76B in 2025 by publisher ([Expert Market Research](https://www.expertmarketresearch.com/reports/parental-control-software-market/companies); [Fortune Business Insights](https://www.fortunebusinessinsights.com/press-release/global-parental-control-software-market-10382)), the same caveat. Prices: Bark $14 a month or $99 a year ([Bark](https://www.bark.us/blog/bark-vs-qustodio/)); Qustodio $54.95 to $109.95 a year ([Qustodio](https://qustodio.com/en/compare-qustodio-and-barkus)); Family Link and Screen Time free.

**Parents paying for study help in the UK.** The Sutton Trust's *Private Tutoring 2026* (Ipsos, 3,214 pupils aged 11 to 17, fieldwork January to June 2025, independent schools and sixth-form colleges excluded): **11 % had private tuition in 2025, 29 % ever** (18 % in 2005); Year 11 the peak at 25 %; London 45 % ([PDF](https://www.suttontrust.com/wp-content/uploads/2026/02/Private-Tutoring-2026.pdf)). Secondary coverage often mis-reports the 29 % as the yearly rate. Older Sutton Trust figures: a market "up to £2bn a year" ([Schools Week](https://schoolsweek.co.uk/booming-private-tuition-stretches-attainment-gap)) and an average tutor at £24 an hour ([Sutton Trust](https://www.suttontrust.com/news-opinion/all-news-opinion/parents-pay-private-tutors-24-an-hour)); a £6bn figure circulates with no method (thin). UK parent-pay edtech: Tassomai at £44.99 a month for Years 9 to 11 ([tassomai.com](https://www.tassomai.com/extra-subjects)).

**Who pays.** No 2025 survey on who pays for teenagers' app subscriptions was found. Childnet (UK, 2021, over 1,000 11- to 18-year-olds): 37 % had made an in-app purchase, 83 % said a parent pays the phone bill ([Childnet](https://www.childnet.com/wp-content/uploads/2021/11/Young-peoples-experiences-of-in-app-purchases.pdf)). Piper Sandler (US, 2023): about 60 % of teenagers' spending comes from parents ([DECA](https://www.decadirect.org/articles/deca-and-piper-sandler-complete-45th-semi-annual-survey)). The parent is the buyer; the student is the user; the product has to be sold to the first and wanted by the second.

**The UK cohort.** 88.2 % of 16- and 17-year-olds were in education or apprenticeships at the end of 2025 ([DfE, 25 Jun 2026](https://explore-education-statistics.service.gov.uk/find-statistics/participation-in-education-training-and-employment-age-16-to-21/2025)); the DfE's NEET count (56,800, 4.0 %) implies about 1.4 million 16- and 17-year-olds in England ([DfE](https://explore-education-statistics.service.gov.uk/find-statistics/neet-statistics-annual-brief/2024); the division is this plan's, not the DfE's). About 54 % of funded 16- to 18-year-olds are in colleges rather than school sixth forms ([AoC](https://aoc.co.uk/policy/education-policy/16-18-new)). State-funded secondary pupils in England: 3.216 million in 2026 ([DfE projections](https://explore-education-statistics.service.gov.uk/find-statistics/national-pupil-projections/2026)). **Round numbers: about 1.2 million 16- to 18-year-olds in full-time education and about 3.2 million secondary pupils.** The Android-using, Teams-or-Classroom-schooled, procrastinating subset isn't quantified anywhere; Android's UK share of under-18s is well under half of phones, which cuts the first number to a few hundred thousand.

## What the evidence says

Cited with care; one commonly cited paper is out.

- **Commitment devices work when they bind, and can backfire when too harsh.** Bryan, Karlan and Nelson (2010), "Commitment Devices", *Annual Review of Economics* 2:671–698, [doi:10.1146/annurev.economics.102308.124324](https://doi.org/10.1146/annurev.economics.102308.124324). People under-demand them; design matters.
- **Self-set, binding screen-time limits cut use substantially.** Allcott, Gentzkow and Song (2022), "Digital Addiction", *American Economic Review* 112(7):2424–2463, [doi:10.1257/aer.20210867](https://doi.org/10.1257/aer.20210867): the model puts 31 % of social-media use down to self-control problems people are only partly aware of. The strongest support for an enforced, pre-set blocker with a delay on loosening.
- **Blocking tools rarely combine mechanisms, and "making blocking hard to override" is underexplored.** Lyngs et al. (2019), "Self-Control in Cyberspace", CHI 2019, [doi:10.1145/3290605.3300361](https://doi.org/10.1145/3290605.3300361): of 367 tools, 53 % did blocking alone, almost none were evaluated. The 24-hour wait and the parent's code are exactly the second layer the paper calls for.
- **Digital self-control tools have a small-to-medium effect on time on device.** Monge Roffarello and De Russis (2023), *ACM TOCHI* 30(4), [doi:10.1145/3571810](https://doi.org/10.1145/3571810): a meta-analysis over 255 participants, Hedges' g = 0.47 (95 % CI 0.27 to 0.68), from short studies.
- **Blocking mobile internet for two weeks improved attention and wellbeing in adults.** Castelo et al. (2025), *PNAS Nexus* 4(2), [doi:10.1093/pnasnexus/pgaf017](https://doi.org/10.1093/pnasnexus/pgaf017): a preregistered trial of 467 adults using Freedom; only 25.5 % complied with the full block, and the sample wasn't teenagers.
- **A pause before an app opens cuts openings.** Grüning, Riedel and Lorenz-Spreen (2023), *PNAS* 120(8), [doi:10.1073/pnas.2213114120](https://doi.org/10.1073/pnas.2213114120): one sec, 280 users, six weeks, target-app openings down 57 %; a co-author built the app.
- **Procrastination follows task aversiveness, delay and impulsiveness.** Steel (2007), *Psychological Bulletin* 133(1):65–94, [doi:10.1037/0033-2909.133.1.65](https://doi.org/10.1037/0033-2909.133.1.65). Near-term timed pieces of aversive work is what the planner makes.
- **If-then plans raise goal attainment, d = 0.65.** Gollwitzer and Sheeran (2006), *Advances in Experimental Social Psychology* 38:69–119, [doi:10.1016/S0065-2601(06)38002-1](https://doi.org/10.1016/S0065-2601(06)38002-1). A calendar slot with a first action is a machine-made implementation intention.
- **Not to be cited:** Ariely and Wertenbroch (2002) on self-imposed deadlines was **retracted in September 2026** after Data Colada found duplicated observations and a direct replication found a negligible effect ([Retraction Watch, 3 Sep 2026](https://retractionwatch.com/2026/09/03/procrastination-study-duke-dan-ariely-psychological-science-data-colada-tampering-retraction/); [Data Colada 138](https://datacolada.org/138)). Nor the "65 %/95 % accountability" figures, which have no retrievable paper.
- **Accountability partners:** the popular claims rest on nothing retrievable; monitoring and progress-reporting do raise attainment in the coaching literature, and body-doubling for ADHD has only small, mixed studies. Thin overall.
- **AI chatbots and young people:** a 2025 meta-analysis of 31 trials (29,637 adolescents and young adults) found chatbots reduce distress (SMD −0.35) with small effects on behaviour (SMD 0.11) and no long-term evidence ([PMC](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12661615/)). **No evidence was found that an AI coach improves study behaviour in teenagers.** That is the claim this product would be making, and it would be making it first.

## What the evidence means for the product

1. The enforcement side rests on good ground: binding limits, a delay on loosening, a second layer that's hard to override, if-then plans with a first action. These are the planner and the blocker.
2. The voice rests on commercial precedent (Duolingo, Finch) and no causal evidence. Felix is the part to measure, not to assume; the beta's design in [stages.md](stages.md) counts kept slots with and without him.
3. The same voice is what the regulators are looking at ([compliance.md](compliance.md) §The UK and §The EU and the US): Common Sense Media's 2025 survey found 72 % of American teenagers had used an AI companion and recommended none under 18 should ([CSM, Jul 2025](https://commonsensemedia.org/press-releases/nearly-3-in-4-teens-have-used-ai-companions-new-national-survey-finds)); Character.AI closed open-ended chat to under-18s from 25 Nov 2025 ([Engadget](https://www.engadget.com/ai/characterai-to-ban-teens-from-talking-to-its-chatbots-180027641.html)) and settled the Garcia case in Jan 2026. The product's defence is the one it already has: Felix speaks only around scheduled work (D-58). The public version keeps him there.
