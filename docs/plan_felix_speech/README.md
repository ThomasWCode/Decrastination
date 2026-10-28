# Felix's speech: how to make him sound like a person (a review, 28 October 2026)

The owner's ask, 28 Oct 2026, after signing off 4.7.0's voice ("not perfect, accept this voice"): ideas for a substantial improvement in how Felix speaks, from prompts, models, code or the information he's given, researched and evaluated, with any earlier decision open to be overturned where a change has real potential. Nothing was built; this folder is the review. Written in a worktree of `main` (dcc041b7); the code read is the `v4.7.0` branch as it stood in the checkout, uncommitted feedback feature included.

- this README: the diagnosis, the changes ranked, the decisions they overturn, what stays, how to judge the result, and the stages;
- [ideas.md](ideas.md): every idea considered, I-01 to I-30, each with its evidence, cost, risk and verdict, the ones not worth doing included;
- [prompt-draft.md](prompt-draft.md): a draft of the rewritten notification prompt, the "story" user turn, the three-candidate answer, the diary, and the harness's judge, to run through the scenarios' set before anything is decided;
- [evidence.md](evidence.md): what each claim rests on: the code (file and line), the numbers measured from the runs and the chat backups, and the runs' quotes;
- [research.md](research.md): the literature looked up, what each finding supports and how far it can be trusted.

Measurements with real data ran on the owner's chat backups; only aggregates are quoted. The scripts and samples sit in the git-ignored `private/felix-speech-review/`.

## The short version

1. **He's briefed like an app and he answers like one.** His system prompt opens "You write the short messages the planner app sends", and his user turn is a form (`Reason: SlotEnd`, `Piece: …, 30 min, slot 17:00–17:30; it earns its free time with a photo`, `Today: 1 kept, 2 missed, 1 to come`, `How you feel, at most: let down, firmly (2 slots missed today)`). Sixteen rounds of tuning the register changed the words he's banned from, not the thing he's asked to be. The biggest single change is to brief him as a person: a short account of what's happened tonight, in order, with what they said, and the details after it for when he needs them (I-01, I-15).
2. **The voice is specified as about forty prohibitions,** 830 words of "never", "no", "not" in `REGISTER`, `FEELING` and `NOTE`. Current models follow instructions literally, so each ban removes a move and the style collapses onto the few moves left, which is why every warm message now opens with the clock ("done right on 18:00") and every day-done ends "N min of free time's yours" (7 of 8). Each run found new stock lines because the bans only know the lines they've been told. Replace them with a short positive description, in his own register, and the difference *shown* by contrasted pairs with the reason each is right, never words to reuse (I-02, I-04).
3. **He knows the slot grid and nothing else.** The notification prompt sees today's records, the plan, and his own last six notifications with one reply each. It never sees the chat (his answers there are filtered out), yesterday, the week's pattern, the weekly check-in's answers (how they felt, what got in the way, which the app stores and never sends), his own notes from earlier today, or anything the owner has rated. A friend's texts are made of shared history; his are made of the timetable. A diary of the last days and the evening's story, from stores the app already holds, costs no new call (I-14, I-15, I-16).
4. **His texts are the wrong shape.** The final runs' notifications average 2.7 sentences, 45 of 59 carry a clock time, 25 of 59 carry two or more numbers, 51 of 59 end with a full stop. The owner's own messages run a median of 14 words, 11 of 31 ending with a full stop, 8 of 31 holding a comma. A full stop on a text reads as cold (a measured effect), which is the one place he should use it. One thought a text, a second text where there's a second thing, numbers only where they're the point (I-06, I-07, I-18).
5. **Nothing makes him vary.** Opus 5.5 takes no temperature, "put it differently each time" has no memory of the other times, and the same inputs give the same line (word overlap between attempts of one case averages 45%, three attempts of m09 near-identical). Ask for three different texts in one answer and have him send one: the measured way to get diversity out of a model that's collapsed onto its typical line (I-10).
6. **The owner's thumbs are the best training signal there is,** and the design keeps them from him. Right: raw feedback must stay honest and unseen. But a weekly digest of what was marked down and why, as a few lines in his profile block, and the marked-up messages as his examples, is the personalisation technique with the strongest evidence (explanations carry most of the gain). It needs a fortnight of real use first (I-08).
7. **Model and effort: keep Opus 5.5, but test what was never tested.** Felix runs at medium effort; low and high were never tried on the voice, nor Fable 5.1 at low effort, where the model guide says it's often competitive on cost. A model change is a cheap experiment in the harness, not a decision to make from this desk (I-13).
8. **Judge it on real use, not on a seventeenth scenario round.** The 27 cases have been tuned against for sixteen rounds; the flags know what they were taught. Build the variants, run the set twice for regressions and a cheap "app or person?" judge, let the owner mark two variants blind, then a fortnight on the phone with the thumbs. The thumbs rate and the feedback text decide (I-17, I-19).

## Why he still sounds like an app

Each point names where it was seen; the lines quoted are from the made-up scenarios' final runs (`private/eval-felix-mood/report-final.md`, runs 15 and 16) and the code read on 28 Oct (file and line in [evidence.md](evidence.md)).

### 1. The frame: an app's writer, fed a form

`FelixPrompts.system` (line 91) starts: "You are Felix: your best mate, the one who keeps you on it. You write the short messages the planner app sends a UK sixth-form student (Year 12) about their work. You get the reason for the message, the piece of work it's about…". The chat's opens the same way and then lists nine capabilities for 2,700 words before the voice is mentioned. The user turn (`describe`, lines 157–233) is a labelled form: `Reason:`, `Now:`, `Piece:`, `First action:`, `Its task's due:`, `Today: N kept, N missed, N to come`, `How you feel, at most: …`, `Left today:`, `Recent, oldest first:` with his own past messages labelled by the app's kinds, `(SlotEnd)`. He's told to answer in a schema whose first field is a note and whose second is `message`.

A model briefed with a form writes form-shaped prose. The persona research says a role in the system prompt steers tone (Anthropic's prompting guide), and that the prompt's own style bleeds into the output's ("match your prompt style to the output style you want"). Here the prompt's style is a specification.

### 2. The register as a list of bans

`REGISTER` is 296 words, `FEELING` 463, `NOTE` 67; together they hold about forty things not to do: four London words, ten worn-out lines, two customer-service lines, two coach's questions, "no sarcasm", "no praise for nothing", "no promise", "no emojis", "no capitals", "never in words", "never a task's id", "never a joke in place of", "don't announce it", "never a statement about your feelings", "no softeners or pleasantries", "no nickname", "no 'finally'", "don't bring up yesterday". The model guide for Sonnet 5 and Opus 5.5 says the current models "interpret prompts literally" and holdover style directives "over-apply"; for Fable 5.1 that over-prescriptive prompts "reduce output quality"; and for every model that positive examples beat "instructions telling the model what not to do".

The runs show the collapse. Told "picking out something particular about this time (how late it was, …)" and shown a record "kept at 18:00", seven of eight on-time answers pick the clock: "done right on 18:00", "finished bang on 19:30", "done bang on 18:00". Told the free time earned, seven of eight day-done answers end "N min of free time's yours", the exact ending of two prompt examples. "after the evening you'd had" appears in all three attempts of m22. Three-word phrases shared by three or more different cases: 28 kinds in the final runs ("on now till" in 8 cases, "it's in at tomorrow" in 6, "that's a good" in 5). Each round's fix banned the last round's phrase and the next round found another: "knew you had it in you" (run 2), "off your plate" (3), "chuffed with that" (6), "nice one" (12), "bang on" (15, unflagged).

### 3. What he's told, and what he isn't

| He's told (notifications) | He isn't told |
|---|---|
| The reason, the time, the piece with its minutes and slot, its first action, its task's text (1,500 chars), its due date, notes, the reminder | Anything from the chat: `ChatHistory.his` keeps only his unprompted messages (`Chat.kt:141`), so what they talked about an hour ago is gone |
| Today's records as counts, and the mood line | Yesterday or the week: `Looks.kt:361` filters today's records; `ActivityLog` keeps 120 days |
| His last six notifications, one reply each (`RECENT = 6`) | His own `feeling` notes from earlier today (`Chat.kt:106`: "never sent back to him") |
| What's done of today's tasks | The weekly check-in's answers (how they felt, what they avoided, what got in the way, their energy: `CheckIn`, `Days.kt:38`), the review, the week's numbers |
| The profile block: subjects, hours, memories, rules | The learned pace (the chat has it; `describe` has no parameter for it) |
| | What was rated, and why (`ChatMessage.rating`, `feedback`: "never sent to him") |

The chat sees more of the work (every task, the plan for a fortnight, the pace, the calendar) but only 12 turns of conversation and the last two nudges; the archive is never sent. Neither prompt knows the shape of the week as a person would ("third night running the essay's slipped"; "you've been ill since Tuesday"), and the level rule resets at midnight by design, so he can't even *know* yesterday while feeling neutral about it.

### 4. The shape of a text

The measures, final runs' notifications against the owner's own chat messages (the person he's texting; aggregates from the 19 Oct backup, samples in `private/`):

| | Felix, final runs (59) | The owner (31) |
|---|---|---|
| Length | 128 chars, 2.7 sentences | median 14 words |
| Ends with a full stop | 51 of 59 | 11 of 31 |
| Holds a comma | 53 of 59 | 8 of 31 |
| Carries a clock time | 45 of 59 | 10 of 31 hold any digit |
| Two or more numbers | 25 of 59 | |

The register asks for it: "Two or three short sentences at most, under 240 characters", "a full stop between thoughts", "Exact where it matters: dates, times and minutes written plainly", "be concrete: name the piece, with what's worth knowing about it (when it's due, what's left, what's next)". So a kept slot reads "circular motion worksheet done right on 18:00, jay, even starting 10 min late. you got through that quick." Three facts, two numbers, a full stop. A person texts "physics done. nice" and, a moment later, "that was quick". The full-stop finding (a one-word text reply ending in a full stop rated less sincere than without; no effect on paper) is small but direct, and it hands him a register tool: the full stop is what he does when he's cold.

The run-3 comparison shows the mood prompt made this worse: run 3's notifications averaged 96 characters with 16 of 36 carrying a clock time; the final prompt's 128 with 45 of 59, as "pick out something particular about this time" sent him to the one particular he's always given, the time.

### 5. Examples copied, then their skeletons

Run 2 copied the recovery example word for word in 3 of 3 attempts; the fix marked the examples "never words to reuse" and the flags caught five-word copies. So the examples were rewritten each round into lines that couldn't be reused, and what's copied now is their structure: "X done, and Y too. that's Z. N min of free time's yours." The morning example's "it's due mon 26 oct, so tonight's the one that matters" became "it's in tomorrow at 09:00 so this hour's the one that counts" (m19) and "tonight's the last go at it" (m18). Anthropic's guidance is to keep examples (the most reliable lever for tone) but make them diverse "so Claude doesn't pick up unintended patterns", and the Fable 5.1 guide's fix for a model reproducing source text is one complete example with a sentence saying *why* it's right. Contrast pairs (the app's line beside his, and why his is his) teach the difference rather than the words, and a pool drawn from at random each call stops any one skeleton setting.

### 6. The level is a count, so the feeling is about counts

`Mood.of` gives a level and its reasons in the app's words ("2 slots missed today", "every slot today kept, 4 of 4", "the missed piece of X made up at 20:05"), and the prompt tells him the reasons are "for you to know, not to say back". He mostly obeys the letter, and the counts come out anyway: "that's two tonight", "three today", "that's the whole evening done". A person's disappointment is about the story ("you said 19:00 and then went quiet"), which is why the strong example's "you said yes to 19:00" lines read best of the run. The counting rule stays (it's testable and the owner chose it, Q3); what changes is that he's given the story the count came from, and the count itself only where a person would say it.

### 7. No variation

`FelixCaller` sets no temperature (none exists on Opus 5.5; the 2026-10-21 API version rejects it), the system prompt is cached for an hour, and the user turn for a given case is the same each time. The prompt's "put it differently each time" asks him to vary from attempts he's never seen. The paper on verbalized sampling locates the problem in the model's typicality bias and measures 1.6–2.1× the diversity when several candidates are asked for in one answer, quality held; a structured one-field answer is the format that collapses diversity most. Three texts and a choice, in the schema, gives him the room (I-10). A changing briefing (the story, the diary) varies the inputs, which varies the outputs for free.

### 8. The register was set by decision, and doesn't match the person

The persona is lower case, "tbh", "ngl", "gonna", "mate". The owner's own chat messages are capitalised full sentences with hardly a textism, many of them dictated. Style matching between people predicts relationship continuation (instant messages, couples, three months on); whether a bot matching its reader does the same isn't known. The owner chose this voice and liked "bit annoying tbh", so this isn't a finding against it: it's a variant worth a blind test, Felix writing a little more like the person he's texting (I-09).

## What to change, ranked

**Tier 1: the briefing and the voice (prompts and inputs; no new call; about a day).**

| | Change | Idea | What it fixes |
|---|---|---|---|
| 1 | Brief him as a person: the system prompt a page, in his register, who he is and what he's for, the voice described positively and shown by contrast pairs with reasons | I-01, I-02, I-04 | §1, §2, §5 |
| 2 | The user turn a story: what's happened tonight in order (his nudges, their replies, the chat's last exchange, what got done, what slipped, what they'd said they'd do), then the mood ceiling in a person's words, then the details block as now | I-15 | §1, §6 |
| 3 | A diary he reads: a line a day for the last seven (from `ActivityLog`, no call), the check-in's answers, the week's memories; his own notes from earlier today; the chat's last two exchanges in the notification prompt | I-14, I-16, I-03 | §3 |
| 4 | The shape: one thought a text, a second text for a second thing (the outbox already shows several lines in one notification); the full stop as his cold register; a number only where it's the point | I-06, I-07, I-18 | §4 |
| 5 | The morning: the app's list as the card it already is, his one line about the day on top | I-20 | the most app-shaped message of all |

**Tier 2: mechanics (small code, cents; a day).**

| | Change | Idea | What it fixes |
|---|---|---|---|
| 6 | Three candidate texts in one answer, he sends one (`texts[3]`, `send`); the two unsent kept for the feedback dialog, where the owner can pick the one they'd rather have had | I-10, I-19 | §7, and a preference signal richer than a thumb |
| 7 | Feedback into the prompt: a weekly digest of marked-down messages and reasons into the profile block as rules; marked-up messages as the pool his contrast pairs are drawn from | I-08 | the owner's taste, learned |
| 8 | The harness: a Haiku 5.5 judge with one anchored question ("sent by a mate, or by an app?") and automatic measures (numbers a message, final full stops, shared n-grams across cases, overlap between attempts) in place of the hand-kept ban lists; a blind two-variant report for the owner | I-17 | the whack-a-mole |

**Tier 3: experiments, not decisions (an afternoon in the harness, about $5).**

| | Experiment | Idea |
|---|---|---|
| 9 | Effort low, medium, high on the same prompt | I-13 |
| 10 | Fable 5.1 at low effort beside Opus 5.5 at medium | I-13 |
| 11 | A register variant mirroring the owner's own writing (capitals, full sentences, fewer textisms) | I-09 |
| 12 | Examples: none, fixed pairs, rotated pairs | I-02 |
| 13 | The voice reminder in the user turn rather than the system prompt | I-05 |

**Tier 4: behaviour, the owner's call after the fortnight.**

| | Change | Idea |
|---|---|---|
| 14 | Light teasing and a joke beside the answer when he's warm; never when let down | I-21 |
| 15 | A non-work line now and then, from what he knows (the match on the calendar, the cold) | I-22 |
| 16 | Replying in kind: a one-line answer to a reply that needs nothing ("ok starting") | I-23 |

## Decisions this would overturn

Each stands until the owner says otherwise; the proposal and why, so the answer can be given in one go.

| Decision | Today | Proposed | Why |
|---|---|---|---|
| D-57: "exact where it matters (dates, times, minutes)", carried into `REGISTER` | 45 of 59 notifications hold a clock time, 25 of 59 two or more numbers | A number only where it's the point ("in at 9 tomorrow"); the Day's cards carry the rest; never minutes, a time and a date in one text | Numbers are the app's voice; the owner's own texts hold a digit in a third |
| `REGISTER`: "two or three short sentences", "a full stop between thoughts" | 2.7 sentences, 51 of 59 ending in a full stop | One thought a text, up to two texts; no final full stop unless he's cold | The owner's texts; the full-stop finding |
| `REGISTER` and `FEELING`: about forty bans | | A short positive description, in his register, and contrast pairs with reasons | Literal instruction-following; each round's new stock line |
| D-57: "no sarcasm at them"; `REGISTER`: "never a joke in place of the answer" | | No sarcasm when let down; teasing and a joke beside the answer allowed when warm | A mate; affiliative humour is what friends converge on |
| 4.7.0 plan, decided without asking, 10: "misses across days aren't told him" | Today's records only | He reads a week's diary; the level still resets at midnight | A friend knows; recall of facts, not of feelings about them, is what readers took well |
| 4.7.0: `feeling`, `rating`, `feedback` "never sent to him" | | His own notes from today go back to him; the feedback goes in as a weekly digest and as the example pool, never raw | Continuity of mood within an evening; explanations carry most of personalisation's gain |
| Q3: the app decides the level, told in one line with its reasons | "How you feel, at most: let down, firmly (2 slots missed today)" | The ceiling stays, in a person's words ("not impressed, at most"); the reasons replaced by the story | The counts leak ("that's two tonight") |
| D-57: the morning "is the whole briefing, with no list of the day beside it" | A 480-character list in his voice | The app's list (the card `FelixWords.morningList` already makes when Claude's off), his one line on top | A list is the most app-shaped message |
| D-57: lower case, a little slang | | Kept as the default; a mirror-the-owner variant tested blind | Style matching |
| One message a look | | Up to two texts a look, sent as one notification | Texting's shape |
| 4.7.0's harness: the hand-kept ban lists as what decides a run | | Kept for regressions; the judge and the counted measures decide | The lists only know what they were told |
| 4.7.0 plan, answers §The age: "an older close friend is someone who'd naturally be the one in charge, so the age suits the imbalance" | `REGISTER`'s "early twenties"; both prompts name the student's year | He stays 22, and the two of them are about the same age; the imbalance is the relationship's (he's the one in charge, and they want it that way), not his seniority | Decided, the owner's word, 28 Oct: an older friend of a sixth-former reads as an older brother, and the dynamic wanted is between two of an age |

## What stays

- The app decides the facts and the ceiling: the planner, the records, `Mood.of`'s level (Q3), Q5's answered recovery, Q6's level kept on his message. Persona research finds persona-assigned models less consistent in judgement, so judgement stays out of the persona.
- The limits on the let-down levels: about the choice, never the person; no insult, no threat; over the moment work starts; a reason, an illness or distress brings him down (D-119's draft, the shame research).
- The cost rule: no new call a message. The diary and the story come from stores; the candidates cost output tokens (a message is about a cent with the cache; three texts add a fraction); the judge is Haiku 5.5 at a few thousandths of a cent a message; the feedback digest is one weekly call like memory-tidy's.
- D-95 (the chat his one voice; two texts are two chat messages, one notification), D-61 (no blocker), D-4 (their minutes as given), D-79 (nothing personal in the repository: the diary is phone-local, the pairs made up until the owner's own rated lines replace them, which stay in `private/` and the phone).
- "No praise for nothing", "no promise the app can't keep", the nickname via a memory, no emojis (the owner's taste; worth knowing it's a mild tell, as people his age use some).
- The goldens, `FelixInputsDocTest`, `SchemaCompileTest` live after the schema changes, the harness as the regression set.

## How to judge it

The last sixteen rounds judged the prompt on the 27 made-up cases by eye and by the flags, and the prompt is now tuned to them. The next round shouldn't be a seventeenth of the same.

1. **Regression:** the new prompt on the 27 cases twice (`EVAL_TIMES=2`, about $1.50): nothing cut, nothing unreadable, the levels respected, the old flags quiet.
2. **The measures:** `run_stats.py`'s numbers on the new run beside runs 15 and 16: numbers a message, final full stops, shared n-grams across cases, overlap between attempts (targets in [prompt-draft.md](prompt-draft.md) §The harness).
3. **The judge:** Haiku 5.5 asked one question of each message, blind to which prompt wrote it, with a four-line anchored rubric; the share judged "an app" by prompt. Judges drift to vibes, so it's one question and it sits beside the counts, never over them.
4. **The owner, blind:** ten cases, two variants each, shuffled, marked "which is the person" and why. Half an hour.
5. **Real use:** a fortnight on the phone with the thumbs; `felix_feedback.py` read weekly; the down-rate and the reasons decide what the digest teaches him. The scenarios can't fake a week.

## Stages, if the owner says yes

After 4.7.0 ships as signed off (its thumbs collect what stage 6 needs), as 4.8.0:

1. The briefing (system prompt, story turn, diary, chat exchanges, his notes, the check-in; `FelixPrompts`, `ChatPrompts`, `Looks`, `Conversation`; goldens; tests): ~2 h.
2. The shape and the candidates (`texts`, `send`, two bubbles through the outbox; `FelixAnswers`, `Outbox`; `SchemaCompileTest` live): ~1.5 h.
3. The harness (judge, measures, blind report; `EvalRunTest`): ~1.5 h.
4. The runs: tiers 1–2 against runs 15–16, then tier 3's five experiments: ~1 h and about $5.
5. The owner's blind read: 30 min of theirs.
6. After the fortnight: the feedback digest call and the example pool (`MemoryTidy`-shaped; `Profile`): ~1.5 h.
7. Docs (D-120, `docs/spec/felix.md`, `felix-inputs.md`, the answers from Q99), version, PR: ~30 min.

Estimates are the old figures; the project's measured durations say code runs at a third to a half of them.

## Risks

- **Shame.** A week's diary could make a bad week heavier. The level still resets daily; the diary is facts; the prompt says he knows and doesn't bring yesterday up unless it helps them tonight. Watch the thumbs on firm and strong messages first.
- **Privacy of the story and the diary.** Both are phone-local and go to the API as the profile does (AI-05). Nothing new leaves the phone that the chat prompt doesn't already send. The repository never holds a real line (D-79).
- **The cache.** The diary and the story go in the user turn, after the cached system block; the profile block is uncached already. The system prompt shrinks, so the cached prefix gets cheaper.
- **The grammar.** `texts` as a plain list of strings and `send` as an integer add nothing nullable; `SchemaCompileTest` live before the PR all the same.
- **Two texts and the notification's room.** 360 characters, lines newest first; two texts of one thought each fit. The chat shows them as two bubbles.
- **Overfitting again.** The harness's judge can be gamed by the same tuning loop. That's why real use decides.
