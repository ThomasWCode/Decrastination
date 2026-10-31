# Felix for strangers: the persona as a product

What Felix is today, why he can't ship as he is to people the owner has never met, and the design that keeps what makes him work. The regulatory ground is in [compliance.md](compliance.md); the cost in [costs-and-pricing.md](costs-and-pricing.md).

## What he is today

Felix is the app's one voice (D-95) and its home (D-121). He's briefed as a person (D-120): 22, British, middle-class, "the closest person you've got, in the shape of a stern boyfriend's with the romance taken out", cheeky, "the one in charge because you want it that way", nothing sexual, ever. He texts in lower case, one thought a text; he's told today's story and a week's diary; he writes three texts and sends one; he has a mood the app works out from the day's record (D-119): pleased and proud at kept slots and a day done, let down firmly at two misses or a second nudge, strongly at three, a step more with a real deadline a day off and nothing done, and properly fed up he may make a mock threat nobody would take as real (Q112). A reason given, a hard week, a stressed reply or a standing instruction brings him down; nothing takes him up. He remembers facts and standing instructions (D-64), acts at once with an Undo (D-60), and never touches blocking, protection or the model's settings (D-61).

He was tuned on one person: seventeen runs of a scenario set, each read blind by the owner and by a Sonnet 5.5 judge asking "a friend's or an app's?", and signed off as "not perfect, accept this voice" ([plan_v4/answers.md](../plan_v4/answers.md) §Felix's moods, §Felix's speech). The dynamic he was built around was the owner's own ask, in words the public record leaves out on purpose.

That is exactly the right way to build a persona for one person, and exactly the wrong thing to ship as a default to ten thousand sixteen-year-olds.

## Why he can't ship as he is

1. **The relationship frame.** "The closest person you've got" and "the one in charge" describe an attachment, and the let-down escalation applies emotional pressure by design. For an adult who asked for it, that's a choice. For a product whose users are under 18, it's the thing regulators and the courts are looking at now: companion chatbots for minors ([compliance.md](compliance.md) §The UK and §The EU and the US). A product can't default to a persona one person chose for themselves, and it mustn't be able to drift into one.
2. **The model's own terms.** Anthropic's usage policy sets conditions on products that minors use ([compliance.md](compliance.md) §The model's terms and prices); the app meets none of them today because it never had to.
3. **Shame.** The owner's record itself notes that shame feeds procrastination, and D-119 aims every let-down at the broken commitment, never the person, with the way back always open (Q102: a kept slot after a let-down is always answered). That's defensible. But the ceiling is a prompt rule plus a pure level; a public product needs it bounded in code, settable by the student in a moment (never a "loosening" that waits, D-46 guards the blocker only) and off by default below a chosen age.
4. **Distress.** Teenagers will tell him things: anxiety before an exam, trouble at home, worse. Today "what's going on at home, you alright?" is an accepted line (§Felix's speech). A public Felix needs a path for distress that drops the mood to neutral at once, says something a decent person would, and signposts help, by country; and a hard rule that he never plays the stern friend in that moment.
5. **Homework.** He sees the assignment's text (D-93) and students will ask him to write it. D-57's "never how to do it unless you ask" was written for one person's honesty. A product for school work needs an academic-integrity stance, in the prompt and the tests, that parents and schools can read: he plans, breaks down, starts, explains a concept in a few lines when asked, and never produces work to hand in.
6. **Cost.** Opus 5.5 at medium effort for every message, ten or more a day, on the owner's own key ([costs-and-pricing.md](costs-and-pricing.md)).
7. **Evaluation.** His quality rests on the owner's blind reads and a private set. A product needs a regression suite over several personas, ages and registers, a red-team set, and a judge, run before every prompt change, with the goldens the repo already keeps.

## What stays

- **One voice, one chat, the home.** D-95 and D-121 are the product. No second assistant, no "chat with AI" button.
- **He acts, with an Undo (D-60), and never touches the blocker (D-61).** The student's trust in the Undo and the parent's trust in the blocker both rest on this.
- **The app decides the level; he voices it (Q100).** `Mood.of` is pure and tested, and so are its bounds; it stays the only thing that says how far he may go.
- **The story and the diary (D-120).** Being told the day as a person would know it is what made him stop sounding like a system; it's also cheap.
- **Memory that expires (D-103), listed and deletable (D-64).** Add *delete everything he knows* and an export, and it's most of what the Children's Code asks of a profile.
- **The app's words when he can't write.** Every path already has a fallback in the app's words (`FelixWords`): the model off, resting, at its cap. That is the degradation path for a budget tier, already built.
- **The harness.** `EvalRunTest`'s mood set and blind judge become the product's suite; the measures already flag lines that sound written, copied phrases, pride said outright and a coach's questions.

## What changes

**Registers as presets inside one envelope.** His name and line are already settings (D-57); the register and the mood's ceiling become one too, from three presets, each a prompt block and a cap on `Mood`, all inside one envelope the prompts carry in every preset (an AI, never a person; nothing sexual; never about who you are; never a real threat, never physical; the way back always open; distress handled as below):

| Preset | Register | Ceiling | Default for |
|---|---|---|---|
| Coach | Warm, direct, plain; pleased and proud as now; let down at most "not impressed"; no cheek beyond a light dig | Firm | Everyone under 16; the default at install |
| Mate | The voice as built: cheeky, "mate" now and then, firm and strong let-downs about the choice | Strong; mock threats off unless the student turns them on | 16 and over, by the student's choice |
| Plain | The app's own words in his name; no persona, no feelings; what the app sends with Claude off | Neutral | Anyone who asks; every free-tier user over budget |

The student picks, in a moment, from Settings → Felix and by telling him; it's never a change that waits. A parent never sets the tone: the student's consent to be pushed is what makes the pushing work ([niche.md](niche.md) rule 6). "The closest person you've got" and the boyfriend's shape leave every preset's prompt; what they were for (he cares how you're doing, not how the plan's doing) is said plainly instead.

**He stays around the work.** D-58 (he speaks only around scheduled work) is the product's defence in the category regulators are now examining ([market.md](market.md) §What the evidence means; [compliance.md](compliance.md) §The UK and §The EU and the US): he isn't a companion, he's the voice of a planner. The public version keeps him there in the chat too: a message that's just talk still gets a reply (D-57), but for an under-18 account he doesn't carry a conversation about nothing past a couple of turns; he says something warm and brings it back to the day, or says goodnight. Common Sense Media's 2025 survey recommended no one under 18 use an AI companion, and Character.AI closed open-ended chat to minors in November 2025; a product whose persona can be shown to talk only about the work, in a log the student can read, is on the other side of that line.

**He says he's an AI.** The first message of every install says it in his voice; the chat's header says *Felix · written by Claude*; asked, he says so; after three hours of chat in a day he says it again and suggests a break, as California's and New York's companion laws ask of a bot talking to a minor, though he isn't one ([compliance.md](compliance.md) §The EU and the US); he never claims to be a person, to have a day of his own, or to miss anyone. The pairs in his prompt (`FelixPrompts.PAIRS`) gain one for the question.

**Report this message.** Beside the thumbs, a *Report* that sends the message's text to the owner with the student's words, at the student's choice: Play's AI-generated-content policy asks for in-app reporting of offensive output, and it's how the suite gets its hardest cases.

**No web search.** D-62's look-ups are off in the product: Ofcom's reading puts a chatbot that searches the web inside the Online Safety Act's scope, and it's the only feature of his that would ([compliance.md](compliance.md) §The UK). The owner's build and adult self-hosters keep it.

**Distress.** A pure pre-check on the student's words (`core/`, a short list of signals, tested) and a rule in both prompts: a message that sounds like distress is answered at neutral whatever today's record says, in a few plain words, with the country's lines (Childline and Shout in the UK, 988 in the US, the rest from a table) once a day at most, and the let-down stays off for the rest of the day. The parent isn't told: the product's policy says so plainly, and the student is told where the parent *is* involved (the code and the summary) so there's no surprise. A standing instruction can't turn this off.

**Academic integrity.** A rule in the chat's prompt and the readings': he helps the student plan, start and understand, never writes a submission, a paragraph of one, or answers to a set of questions; asked for one, he says what he'll do instead and does it (the first step, a plan of the essay, the one concept explained in three lines). A test set of such asks sits beside the mood set. Parents and schools are shown the rule in words.

**Models by job.** The owner's own experiments found low effort writing about as well as medium at half the cost of a message, high adding nothing, and Fable 5.1 harsher and about twice the price (§Felix's speech, decided without asking 5). The product runs the suite on Sonnet 5.5 and Haiku 5.5 for his messages and the chat before anyone decides; the readings and the weekly review stay on Opus 5.5 unless the eval says otherwise. The judge stays Sonnet 5.5 (Q110). [costs-and-pricing.md](costs-and-pricing.md) has what each choice costs a user a month.

**A budget, not a cap.** Each account has a monthly allowance by tier; past it he writes in the app's words (Plain) until the month turns, the student told once. Nothing is lost: every path already has its words.

**Ratings as telemetry, opt in.** A thumb up or down on his message, with the student's words, already exists (D-119). The product sends the thumb and the preset and the level, never the message's text nor the student's words, unless the student taps *share this one*; shared ones feed the suite as cases, made up-alike by hand. The owner's rule that only an agent reading them acts on them (Q109) stays the product's: no automatic prompt tuning from ratings.

**The voice's examples.** The pairs are British sixth-form by taste. Each preset's pairs come in a locale set (en-GB, en-US first); the American register is a separate tuning run with American readers, not a search and replace.

## What's lost, honestly

The owner's Felix is sharper than any default a product can ship: a product can't be "in charge because you want it that way" of someone it has never met. The Mate preset keeps most of him for the students who choose it, and the owner keeps all of him on their own build (the presets are settings; the prompts the owner uses today become one of them). What the product loses is the unguarded version; what it gains is a persona that can be defended in a settings page, to a parent and to a regulator.

## Order of work

In [stages.md](stages.md) this is stage 4, after the engine and the server and before any stranger installs it: the envelope, the presets, the disclosure, distress, integrity, the suite over the presets, and the budget's degradation. None of it needs a stranger to be built; all of it needs the suite to be trusted before one is.
