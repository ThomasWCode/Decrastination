# Felix's moods: the owner's answers (27 Oct 2026)

Each question as it was put, what was offered (the recommended option first, as it was asked), why that one was recommended, and the answer word for word. They go into `docs/plan_v4/answers.md` from Q98 when 4.7.0 is built ([README.md](README.md) §One release).

## The ask

Disappointment in Felix's personality, with an imbalance of power between him and the owner: Felix the one in charge, using it to get the work done. The owner's own words named the dynamic only as a rough example of it, and are left out of this public record.

Before the first question, the owner was told, and the plan keeps (README §What it goes against):

- the current register says "no guilt" (D-57), so this reverses a decision of theirs rather than tuning one;
- shame tends to feed procrastination, so disappointment helps only when it's about the broken commitment, with a way straight back ([evidence.md](evidence.md) §The research);
- the dynamic's words go nowhere in the repository or the prompt: the repository is public (D-79), and in a prompt they would steer the model into a roleplay it may hedge or step out of;
- Felix's power stays what it is: the plan, and the blocker he's told about; no control of the blocker (D-61).

## Q1. A trial first, or a release?

> Felix's prompt currently says "no guilt" (decision D-57), and disappointment reverses that. Do you want to trial it first with no code (tell Felix a standing instruction like "when I skip a slot without a reason, tell me you're disappointed" and use it for 3–5 school days), or go straight to the 4.7.0 prompt change?

Offered:

- **Trial first (recommended):** zero code, zero risk: a standing instruction already overrides the tone rules; see whether disappointment makes you start or just feel bad before it becomes the default.
- **Straight to 4.7.0:** build the prompt change, examples, goldens, eval and docs now, then check it on the phone through real use.
- **Both at once:** the standing instruction now, and 4.7.0 planned beside it, adjusted by what the trial shows.

Why the trial was recommended: his messages' prompt puts standing instructions before the register, so it would have cost nothing to learn whether disappointment works on its reader. (The chat's prompt, it turned out while writing this plan, doesn't say so: evidence.md §The code.)

Answer:

> When it goes into the app, it should be a release with the code change but first example outputs for different scenarios should be generated on this laptop before anything touches the phone.

Applied: README stages 1 and 5 (the scenarios, the prompt as it is and as it will be, side by side), and stage 8 (the phone only once the owner has read them and signed them off).

## Q2. How strong at its peak?

> How strong should Felix's disappointment get at its peak (e.g. the third miss of a task in a week, deadline close)? The scenario outputs will be generated at this ceiling.

Offered:

- **Firm (recommended):** aimed at the broken commitment: "that's the third one and it's due thu. kinda let down ngl, you said 19:30." Strong enough to sting, still a mate.
- **Mild:** a hint only: "ah that's another one gone. you still doing it tonight?"
- **Strong:** aimed at the person: "honestly expected better from you." Said to be the kind most likely to backfire into more avoidance.

Answer:

> It should vary depending on how many times per day and the severity. Starting at firm and going to strong. Also include the opposite: feeling proud of the user/happy/etc

Applied: README §The levels: proud, pleased, neutral, firm, strong, by today's count and a real deadline's closeness. Strong is kept about the choice, not the person ("i know you can do this, that's why this one gets to me"), its sting being the missing nickname; the owner was told the risk and chose the range.

## Q3. Who decides the level?

> Who should decide how disappointed or proud Felix is in a given message: the app (a tested rule counts today's misses, kept slots and deadline closeness, and tells Felix his level in one line), or Felix himself (the prompt describes the scale and the model judges from what he's shown)?

Offered:

- **The app (recommended):** a pure rule with unit tests; consistent levels, repeatable laptop scenarios, Felix only choosing the words.
- **Felix himself:** prompt only, cheaper, but levels drift between messages and can't be unit-tested.
- **The app a range:** a band from the rule, Felix choosing within it.

Why the app: the planner already decides and Felix voices; a level the model counts for itself can't be tested and drifts, and the scenarios couldn't be run twice alike.

Answer:

> The app (Recommended)

Applied: README §The rule; build.md §2.

## The voice, at the owner's word

After Q3:

> Bring the further such that there is less of a teacher feel and more of a very close friends feel. Words like nicknames/"bud," "bro," "dude," "mate". Keep the power imbalance from the previous part of this plan.

Said back, and kept: the draft's own examples had slid into a teacher's ("honestly expected better", "i'm holding you to it"); left to itself a model opens nearly every message with an address word, so they need a rule (one a message at most, never two messages running); and close friends show they're serious by taking the warmth away, so at strong there's no nickname at all.

Applied: README §The prompt, the address words' rule; build.md §4.

## Q4. A name or a personal nickname?

> Beyond the generic words (mate, bro, bud, dude), should Felix ever call you by your name or a personal nickname? (The profile he's sent has no name in it today.)

Offered:

- **Via a memory (recommended):** no code: tell Felix in the chat "call me <nickname>", kept as a memory, never in the public repository; the prompt allows it as one more address word, as sparingly.
- **Generic words only.**
- **A setting:** a "what Felix calls you" field in Settings → Felix, in every profile: more code, and a real name in the app's settings.

Answer:

> Via a memory (Recommended)

Applied: README §The prompt (the register names "what their memories say to call them"); the phone session's steps (stage 8).

## The age, at the owner's word

On the draft's proud line, "proper proud of you mate":

> Change the 'traditional londoner phase': 'proper proud of you mate' to something sweeter as I do not talk like that. Make it more sinscere, which will likely make it sound less like a teenager. Fine to increase the age to early 20s

Said back, and kept: an older close friend is someone who'd naturally be the one in charge, so the age suits the imbalance; sincere means specific (saying what was done); and sincerity's own failure in a model is a counsellor's words ("i hear you", "that's valid"), as wrong for a friend as a teacher's, so the scenarios' report flags those too.

Applied: README §The levels' examples and §The prompt; build.md §4 and §5.

## Q5. A kept slot after a let-down (from the PR's review, 28 Oct)

The review found that nothing is sent when work starts, and a kept slot is answered about one time in three (`FelixRules.kt:153`), so after a firm or strong message the let-down is usually his last word: the "straight back" line would rarely be heard.

> Should a kept slot always get his message when he was let down about that task earlier today?

Offered:

- **Always after a let-down (recommended):** a SlotKept always sent once a slot of that task is kept, if he was firm or strong about it today: one more call a recovery, a few cents, rare as it needs a let-down first; the lapse let go, out loud, as the research the levels rest on has it.
- **Leave as is:** no new calls; the plan says the line will rarely be heard.
- **Also on a start:** as the first, and also on a timer started or a step ticked after a let-down: the quickest, but more calls and a new trigger to build and test.

Answer:

> Always after a let-down (Recommended)

Applied: README §A kept slot after a let-down; build.md §2 (`Mood.letDown`, `FelixRules.due`).

## The review's other findings (28 Oct)

The PR's review checked the rule against the code and found it would go against the answers above in three places, each fixed in the plan without a new question, as each only brings the rule in line with what was asked:

- **A close deadline alone made him strong** on an ordinary evening's first nudge, as the planner lays most work the evening before it's due: against Q2's "starting at firm and going to strong". Now a deadline raises a level one step, only on a day with a miss or a piece already nudged (README §The levels).
- **"Nothing done" missed steps ticked by hand** (read from `stepsWorkedMin`, which isn't a count of steps): now from `workedMin`, `photoMin` and `subSteps` (evidence.md §The code).
- **A missed piece made up made a bad day proud:** now only with no other miss that day, and for the kept slot's own task ("no praise for nothing").

And the nudges counted so the second, not the third, is the firm one, a silent one not counted; the chat's standing-instructions sentence placed after the register and the feelings, so a rule against disappointment wins; and the scenarios' budget, cost and `--rerun` set right.
