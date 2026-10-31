# Decisions needed: the questions for the owner

Twelve questions, numbered on from the web plan's Q138 ([plan_web/decisions.md](../plan_web/decisions.md)), each with its options and a recommendation, in the order they're needed. None is answered here; the answers set [stages.md](stages.md). The decisions each would reverse are named, as [decisions.md](../decisions.md) asks: a change to one is the owner's, in the PR that applies it.

**Q139. Go public at all?** The app is not a product as it is ([README.md](README.md) §The verdict). Offered: **(A)** the staged path of this plan, committing to stage 0 alone now and deciding again at its gate; **(B)** keep it personal and build the web plan, which is stage 1 either way; **(C)** publish it as a free, complete, self-hosted product for adults with no company behind it (the Claude-off app, bring-your-own-key, the repository as the manual), and sell nothing. *Recommended: A, with B as what happens when stage 0 says no, and C kept as an honourable end for the code if the owner wants strangers to have it without wanting a business.* Reverses: D-3.

**Q140. Where it's distributed.** Offered: **(A)** Google Play, with the accessibility declaration and the locks out; **(B)** a signed APK outside Play, the locks kept, reaching those who'll sideload and allow restricted settings (and, from 2027, twenty devices without the owner's identity verified); **(C)** both: the product on Play without the locks, and a "full" build outside it with them, for the owner and anyone who asks. *Recommended: C; the product is A, and B is the owner's own build with a name.* Reverses: D-48; bends D-45.

**Q141. What the parent hears when blocking goes off.** On Play the student can switch the service off in Settings and the app can't stop them ([compliance.md](compliance.md) §Google Play). Offered: **(A)** the parent account is told at once, and the student is shown beforehand exactly what the parent will see; **(B)** told only in the week's summary; **(C)** told nothing; the student alone sees it. *Recommended: A: it's the one mechanism that replaces the lock, and the student's consent to it at arming is what makes it theirs rather than surveillance.* Bends D-45, D-47; adds a line to D-66.

**Q142. Felix's default.** Offered: **(A)** Coach by default, Mate for 16 and over who choose it, Plain for anyone and for every account over its allowance; **(B)** Mate by default, as the owner has him; **(C)** Plain by default, Felix a paid extra. *Recommended: A ([felix-public.md](felix-public.md)).* Bends D-57, D-119, D-120; keeps the owner's prompts as the Mate preset.

**Q143. The models.** Offered: **(A)** the suite decides: Haiku 5.5 and Sonnet 5.5 tried for his messages, micro-plans and the chat against the owner's sign-off level, with a target under $6 a user a month; **(B)** Sonnet 5.5 for everything but the readings, no trial of Haiku; **(C)** Opus 5.5 as built, the price set to cover it (£20 a month or more). *Recommended: A; C is not a consumer product.* Reverses D-5's "quality over cost" for the product's tiers; D-15's one model.

**Q144. The Teams widget.** Offered: **(A)** an optional companion for power users, its own install off Play, its provider trusting the product's release signature, its terms said plainly, and never the product's path; **(B)** dropped from the product entirely, the owner's alone; **(C)** the product's Teams path. *Recommended: A, and C ruled out ([compliance.md](compliance.md) §Driving another app's screens).* Bends D-6.

**Q145. Power Planner.** Offered: **(A)** ask its author for a key and terms, and drop it from the product if there's no answer in a month; **(B)** drop it from the product now, the owner's build keeping it; **(C)** keep the development key. *Recommended: A; C is not defensible.* Bends D-8.

**Q146. The free tier.** Offered: **(A)** the whole app with Claude off, free, for anyone; bring-your-own-key for adult self-hosters; **(B)** a trial of Standard only; **(C)** no free tier. *Recommended: A: it costs the business nothing, it's the product the repository already is, and it's the honest answer to a parent who can't pay.* Keeps D-14.

**Q147. The price.** Offered: **(A)** Standard £7.99 a month or £59 a year, Plus £12.99 or £99, Schools £12 to £20 a pupil a year; **(B)** £4.99 and £39; **(C)** £14.99 and £119. *Recommended: A until stage 0's conversations say otherwise ([costs-and-pricing.md](costs-and-pricing.md) §What it could charge).*

**Q148. The company.** Google Play's and Stripe's accounts need an adult of eighteen or over; a UK company needs a director, an ICO registration and terms a solicitor has read. Offered: **(A)** a limited company before any payment is taken, a parent its director until the owner is eighteen, the owner the shareholder; **(B)** a sole trader through a parent; **(C)** no company until the beta shows there's a product (nothing is sold before then). *Recommended: C until stage 5, then A; the ICO registration with the DPIA at stage 5.*

**Q149. Which platform first.** Offered: **(A)** Android first, iOS at stage 9 if the Android numbers earn it; **(B)** iOS first as a new client, the Android app the prototype; **(C)** both at once. *Recommended: stage 0's phone share decides: A if a third or more of UK 15- to 17-year-olds carry Android, B if not; never C with one developer.* On iOS Family Sharing gives back the lock Play forbids ([compliance.md](compliance.md) §iOS).

**Q150. The repository.** Public today, with the signing key committed, real teacher and class names and the owner's first name kept under D-80, and history kept under D-81. Offered: **(A)** stays public; a new release key; D-80 reversed (the fixtures and comments cleaned); D-81 kept (history as it is, since what's there is already public and a recreation buys little); **(B)** the product's code to a private repository, the engine and `:core` public as the open core; **(C)** as it is. *Recommended: A; the open repository is also the free tier's manual and the thing a school's IT lead can read.* Reverses D-80.

**Q151. The name.** "Decrastination" has no trademark search behind it and lives on the owner's personal domain. Offered: **(A)** a UK trademark search and a product domain before stage 0's waitlist page; **(B)** rename now; **(C)** leave it till the listing. *Recommended: A; a rename after a beta is a loss.*

## Decided without asking

Where the plan decided for itself, each with its reason, for the owner to overturn:

1. **Felix stays around the work for under-18 accounts**, a conversation about nothing ending after a couple of turns ([felix-public.md](felix-public.md)): it's the design that keeps him out of the companion laws' definitions, and it's D-58 already.
2. **Web search is off in the product** (D-62): it alone would bring the app inside the Online Safety Act's scope.
3. **The product is 13 and over**, with no parental-consent flow below that: the UK's age of digital consent, and the Children's Code's demands below it are a product of their own.
4. **Nothing is sold before stage 6's gate holds**: the beta is free, so no company, no Stripe and no Play merchant account are needed until there's evidence.
5. **UK first**, in English, Android, one school-kind preset tuned (the sixth form the owner knows); the American register and the other presets after.
6. **The server plans for every account** (plan_web W-4, W-14), so the website, an iOS client and the Android app render one plan: a thin client is a client that can be built in the hours [stages.md](stages.md) gives.
7. **Ratings never tune the prompts automatically** (the owner's Q109 kept): a product's suite is run by a person.
8. **The owner's build keeps everything**: the guard, the device admin, the Mate register, Opus, Power Planner, the widget, the web search, behind flags and presets; the product is a configuration of the codebase, not a fork.
