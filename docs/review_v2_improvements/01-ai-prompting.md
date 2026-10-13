# 1. AI prompting

What Claude is asked, how its answers are checked, and where better wording or a little more context would give better readings. Covers the three enrichment jobs (`enrich/Prompts.kt`), the chat (`enrich/ChatReader.kt`), instructions (`enrich/InstructionReader.kt`), the photo check (`enrich/PhotoChecker.kt`) and the weekly review (`learn/ReviewInput.kt`).

How to read each item: **Now** is what the code does, with where; **Why** is what it costs; **Proposal** is the change; **Final state** is what you'd see; **Caveats** are the honest downsides; **Effort** is S (an hour or two), M (a day), L (several days). A **Decision** line points at [QUESTIONS.md](QUESTIONS.md).

The model-side settings (effort, model per job, caching, retries) are in [02-ai-architecture-and-cost.md](02-ai-architecture-and-cost.md).

## Summary

| ID | Improvement | Priority | Effort | Decision |
|---|---|---|---|---|
| AI-01 | Salvage a split with one bad date instead of dropping it all | P1 | S | Q-01 |
| AI-02 | A note to self is never "only to read" | P1 | S | – |
| AI-03 | Tell the model the hard limits the checks enforce | P1 | S | – |
| AI-04 | Tell the model it sees only the newest message of a thread, and what's attached | P1 | S | – |
| AI-05 | A stable "about the student" block: subjects, class codes, year group | P1 | M | Q-02 |
| AI-06 | Stop hard-coding "Year 12" | P1 | S | – |
| AI-07 | Worked examples in each system prompt | P2 | M | – |
| AI-08 | Keep step titles stable when a task is enriched again | P2 | S | – |
| AI-09 | Ask for a one-line reason with each reading, and show it | P2 | M | – |
| AI-10 | Let the model say it isn't sure, and surface that | P1 | M | Q-03 |
| AI-11 | A way to say "this email is a task" when Claude hid it | P1 | M | Q-04 |
| AI-12 | Soft deadlines for the "quick actions" the email prompt invents | P2 | S | Q-05 |
| AI-13 | Time-of-day awareness in the chat | P1 | S | – |
| AI-14 | More kinds of card in the chat | P2 | M | Q-06 |
| AI-15 | "Help me start": a micro-plan for the next piece | P2 | M | Q-07 |
| AI-16 | The photo check should see the instructions, not just the piece's name | P1 | S | – |
| AI-17 | Several photos for one piece, and partial credit | P3 | M | Q-08 |
| AI-18 | The weekly review should see last week's review and the day records | P2 | S | – |
| AI-19 | A "Steps" change, so Claude can split or re-split a task of your own | P2 | M | Q-06 |
| AI-20 | Schema descriptions as a steering channel | P2 | S | – |
| AI-21 | Remembered facts about the student | P3 | M | Q-09 |

---

## AI-01 Salvage a split with one bad date instead of dropping it all

**Now.** `Answers.steps` (`enrich/Prompts.kt:311`) rejects the whole list of steps when any one step has a `from` after the task's due date (`Prompts.kt:321`, `a step starts after the task is due`), a date that can't be read, or dates out of order. The assignment is then planned as one block at its whole estimate, and a "Claude's plan was dropped" warning is posted. The first eval run hit exactly this (`docs/needs-you.md`, 1.8.0, "The German assessment's plan is thrown away"): nine sensible steps, one of them "revise using the marked sheet" dated after the Teams hand-in but before the assessment on 12 Oct, and 3½ hours landed as one overdue lump.

**Why.** The failure mode is the most expensive one: the better the model's reading (dated steps for a multi-stage assignment), the likelier one date trips the check. The student sees a worse plan than the rules would have given.

**Proposal.** Two changes, prompt and check.

- Prompt (`Prompts.system`, Assignment): add one sentence: *"No step may start after the assignment's due time. Work that comes after the hand-in (revising for a test the work prepares for) gets no dates of its own; give the test's day in testDate instead."* The same for Effort: *"No block may start after the item's date."*
- Check (`Answers.steps`): for a step whose `from` or `due` falls after the task's deadline, keep the step and drop only its dates, recording what was changed in a new `Enrichment.adjusted: List<String>` ("'Revise using the marked sheet': its start was after the hand-in, so it's planned by the task's date"). Keep rejecting the cases that can't be repaired: no title, no minutes, a date that can't be read at all, out-of-order dates between two dated steps.

**Final state.** The assessment is planned as nine steps across the days before 8 Oct, with the one odd step undated. The task's sheet says "Claude's dates for one step were adjusted (why)" instead of "Claude's plan was dropped". The dropped-plan notification fires only for the unrepairable cases.

**Caveats.** Salvaging changes the contract "the model's plan is taken whole or not at all", which the 1.2.0 alert was built on; the tests in `EnrichTest` that assert a drop will need the new outcome. Keeping the drop for unreadable dates is deliberate: those are misreadings, not edge cases.

**Effort.** S. **Decision.** Q-01: salvage (recommended) or prompt-only.

## AI-02 A note to self is never "only to read"

**Now.** The Email prompt says a self-sent email "is a note of something they mean to do", but `kind` may still be `Info`, and the eval run shows a note holding a link to a recorded talk read as Info, 2 minutes (`docs/needs-you.md`, 1.8.0). An Info email is hidden from the plan and the list (1.3.0), so the note vanishes.

**Why.** The rules treat self-sent notes as Admin, 10 minutes (`sources/gmail/EmailRules.kt:79`). With Claude on, its reading replaces the rules', so switching Claude on makes this case worse.

**Proposal.** In the Email system prompt, replace the sentence with: *"An email the student sent themselves is a note to self. It's Admin: something to do, go back to, or decide about (listen to the recording, read the link, buy the thing). It's Info only if it's empty or plainly already dealt with. Its nextStep says what to do with it."* Keep the rules' 10 minutes as the floor by clamping a self-sent email's `effortMin` to at least 5 in `Answers.parse`.

**Final state.** Notes to self stay on the list with a concrete next step. The eval case passes.

**Caveats.** Some notes really are just notes ("Zip card"). The student can still archive them, and the "Not a task" quick change hides one in a tap. Better to show one too many than hide one obligation.

**Effort.** S.

## AI-03 Tell the model the hard limits the checks enforce

**Now.** The checks drop an assignment split whose steps run past 240 minutes each (`MAX_STEP`), a total past 600 (`MAX_EFFORT`, `Prompts.kt:185`), blocks past 30 or 20 hours, and steps that don't add up to the total within a tenth. The prompts say "10 to 60 minutes where the work allows" and name the 30-block limit, but never the 10-hour total, the 240-minute step, or the 20-hour block ceiling, and only the Effort prompt says the blocks must add up.

**Why.** A limit the model doesn't know about is a drop waiting to happen (the 1.1.1 history: four tries at 12–14 hours, all of which 1.1.0 would have dropped). Stating limits in the prompt is free.

**Proposal.** Add a short "Limits" paragraph to each system prompt, generated from the same constants so they can't drift: *"Limits the app enforces: at most 12 steps; no step longer than 240 minutes; a total of at most 10 hours; the steps must add up to effortMin exactly."* For emails and planner items: *"at most 30 blocks, 20 hours in all, adding up to effortMin exactly."* Move the constants that are now private in `Answers` to where `Prompts` can read them.

**Final state.** Fewer dropped plans; the prompt and the checks can't disagree.

**Caveats.** None of substance. A model told "exactly" will round; the tenth's slack stays as it is.

**Effort.** S.

## AI-04 Tell the model it sees only the newest message of a thread, and what's attached

**Now.** `Prompts.describe` (`Prompts.kt:136`) sends Received, From, Subject and the newest message's text. The thread's message count is stored (`GmailThreads.EXTRA_MESSAGES`) but not sent. Attachments are dropped at `Mime.kt:48` and never named. The model therefore reads "Sounds good, see attached" with no idea there was an earlier request or an attached form.

**Why.** Two of the commonest obligations in a school inbox are "fill in the attached form" and a reply in a thread. Both are invisible to the model now. The v1 review proposed sending earlier messages (IMP-P2-003) and you left that as is; this is the cheaper half: telling the model what it can't see.

**Proposal.**

- In `describe`, after From: `Messages in this conversation: 3 (only the newest is shown; earlier ones may hold the request)`, and `Attachments: consent-form.pdf (110 KB), itinerary.docx` once DX-01 ([03-data-extraction.md](03-data-extraction.md)) records attachment names.
- In the Email system prompt: *"You see the newest message only. If it refers to an earlier message or an attachment you can't see, don't guess what it asked: keep the task as Admin with nextStep 'Read the earlier message/attachment and decide', and effortMin 10."*

**Final state.** A short reply in a long thread becomes a 10-minute "read it and decide" task rather than Info; a form email names the form.

**Caveats.** Slightly more Admin tasks from courtesy replies. That's the right side to err on, and the Not-a-task tap fixes the rest.

**Effort.** S (the attachment list needs DX-01 first).

## AI-05 A stable "about the student" block: subjects, class codes, year group

**Now.** The model knows "a UK sixth-form student (Year 12)" and the task's class code as the source has it (`12.2-PH3`, `German PETERS`). It doesn't know that `12.2-PH3` is Physics, which subjects the student takes, that German uses Textbook 1 sections, or that school ends at 15:30 and work starts at 16:45.

**Why.** Splitting "Statics Prep, page 7–11 of the booklet" into realistic steps depends on knowing it's A-level Physics. Email triage depends on knowing which senders are school and which subjects matter. All of this is stable across calls, so it's cheap to send and (see AC-01) cacheable.

**Proposal.** A `StudentProfile` the app builds and you can edit in Settings:

- subjects and their class codes, seeded from Power Planner's class names and the Teams class codes seen so far ("12.2-PH3 = Physics"); Claude can be asked once, in the chat, to propose the mapping;
- year group, derived from the date (AI-06);
- the working hours and school day end from Settings;
- the current German textbook (Settings);
- three or four lines you write yourself ("Target grades…", "Finds German vocab slow", "Does football Tuesdays").

Sent as a second system text block after the job's instructions, in every job and in the chat. Kept short (under 600 characters) so it can't crowd the email.

**Final state.** Assignment steps name the subject's real work ("Draw free-body diagrams for Q1–4"); the chat knows "physics" means `12.2-PH3`; triage knows the school's domain.

**Caveats.** More personal context goes to the API each call (it already gets emails and assignments, so the marginal exposure is small, but it's your call). A wrong mapping misleads every call: show the profile and let it be edited.

**Effort.** M. **Decision.** Q-02: what goes in the profile.

## AI-06 Stop hard-coding "Year 12"

**Now.** `Prompts.kt` (`STUDENT`), `ChatPrompts.SYSTEM`, `InstructionPrompts.SYSTEM`, `PhotoChecker.SYSTEM` and `ReviewInput.SYSTEM` all say "Year 12". In September 2027 the student is in Year 13, with A-level exams, coursework deadlines and UCAS; the prompts would quietly describe the wrong year.

**Proposal.** One `Student.yearGroup(now)` function from a `yearGroupStart` setting (the year the student entered Year 12), rolling over on 1 September; every prompt reads it. Fold it into AI-05's profile.

**Effort.** S.

## AI-07 Worked examples in each system prompt

**Now.** The prompts are instructions only. The eval history (`docs/data-sources.md` §5, "What that showed") is a list of cases where wording had to be tightened one by one: appointments estimated as work, hand-in steps guessed, vocabulary steps repeated.

**Why.** A few compact examples settle boundary cases better than more rules, and they're stable text (cacheable, AC-01). The eval set gives a ready source of shapes to abstract from.

**Proposal.** Two or three synthetic examples per job in the system prompt, each a short input and the exact JSON expected: a newsletter (Info), a form from a no-reply address with a date (Admin, deadline, 15 min), a parent's email with three dated items (blocks), a two-part homework with a test date (steps, testDate, ankiSections), an appointment in Power Planner (effort 5, no blocks). No real names or addresses in the examples; they live in the repository.

**Final state.** The prompts carry their own precedent. New boundary cases are added as examples rather than as another clause.

**Caveats.** Examples anchor: a model shown "15 min" for forms may say 15 for every form. Keep examples varied, and re-run the eval set after adding them (that's what it's for). The system prompt grows by a few hundred tokens a call; with caching that's a one-off cost.

**Effort.** M.

## AI-08 Keep step titles stable when a task is enriched again

**Now.** A task is enriched afresh whenever its content hash changes (`Enrichments.inputHash`, `core/Enrichment.kt:142`): a new email in the thread, edited instructions. `withEnrichment` (`Enrichment.kt:100`) carries a step's done mark to the new step of the same title at the same position. The model is given nothing about the old split, so a reworded step loses its tick.

**Proposal.** When a task has a current or previous enrichment with steps, append to the user message: *"Steps as planned before (keep the exact title of any step whose work is unchanged; mark none as done): 1. …, 2. …"*. Nothing else changes: the check still matches by title.

**Final state.** A reply in a thread, or a teacher's edit, re-plans the task without losing what's been ticked.

**Caveats.** Anchoring is the point here, but it could keep a step the new text has removed; the instruction "whose work is unchanged" covers it. Worth pairing with AI-09 so the sheet can show why a step was kept or dropped.

**Effort.** S.

## AI-09 Ask for a one-line reason with each reading, and show it

**Now.** The task sheet says "Steps and estimate by Claude" and, for an email, the next step. It doesn't say *why* Claude read the deadline as Friday or the kind as Info.

**Why.** The student and you are the check on Claude. "Deadline Fri 17 Oct, from 'by the end of the week'" can be checked in two seconds; a bare date can't. The v1 review asked for provenance (IMP-P2-017) and you deferred it as a version number; this is the half that helps the student.

**Proposal.** Add `reason: string` (under 160 characters) to the Email and Assignment schemas: *"the phrase in the text that gave the deadline, kind or test date, quoted"*. Store it on `Enrichment.reason`; show it on the task sheet under the due date ("Claude: 'by Friday 17th' in the email"). The chat's `reply` already serves this role there.

**Caveats.** A quoted phrase is evidence, not proof; the model can quote something that doesn't support the reading. It still makes the wrong readings visible, which is the aim. Schema change means re-enrichment under a new prompt version (AC-06).

**Effort.** M.

## AI-10 Let the model say it isn't sure, and surface that

**Now.** The Email schema forces a `kind`; there is no way for the model to say "this might be for the student or might not" (a parent's forward, an email to the family, a reply that may close the matter). An uncertain Info reading hides the email from everything (1.3.0: "One Claude misjudges as only to read is only seen in Gmail").

**Proposal.** Add `unsure: boolean` and `question: string | null` to the Email schema: *"true when the email could be a task or not, with the one question that would settle it ('Is the Warwick booking for you?')"*. In the app: an unsure email is kept visible (Admin, 5 minutes) with a "Check" tag; its sheet shows the question with two taps, *It's a task* and *Not a task* (the latter the existing quick change; the former AI-11). Optionally a notification per unsure email (your call).

**Final state.** Nothing uncertain disappears. The student answers a one-line question instead of discovering a missed form.

**Caveats.** Over-use: a model told it may be unsure will be unsure more often. Cap it in the prompt ("rarely: only when the text itself can't settle it") and watch the rate in the eval set.

**Effort.** M. **Decision.** Q-03: tag only, or tag and notify.

## AI-11 A way to say "this email is a task" when Claude hid it

**Now.** `ChangeType.NotATask` exists; its opposite doesn't. An email Claude read as Info or Event is off every list (`TaskItem.hidden`), so there's nowhere to write an instruction about it (`docs/needs-you.md`, 1.5.0, "Not done"). The only recovery is to reply to the thread so the content hash changes.

**Proposal.** A `ChangeType.IsATask` override (`userKind = Admin`, laid over the enrichment's kind in `enriched()`), and a folded section at the foot of Tasks, "Only to read, as Claude read them" (count in the heading), listing hidden emails with a one-tap *It's a task*. The same list is where AI-10's unsure emails would live if you'd rather they stay hidden.

**Final state.** A misread email can be brought back in two taps, and the hidden list can be skimmed once a week.

**Caveats.** The list shows emails you chose not to see (1.3.0). Folded by default it costs nothing until opened.

**Effort.** M. **Decision.** Q-04.

## AI-12 Soft deadlines for the "quick actions" the email prompt invents

**Now.** The Email prompt says both *"deadline: … Never invent one"* and, for blocks, *"A quick action with no date of its own that's best done soon … is due within the next two weeks"*. The app then treats that invented due date as a hard one: it shows "Overdue" in red when it passes and ranks ahead of real work.

**Proposal.** Let the model mark a block's date as `softDue: true` (or give blocks a `window: "soon" | "dated"` field). The planner already has soft deadlines for undated work; a soft block keeps its date for ordering but shows "Waiting" rather than "Overdue" and never outranks a real deadline.

**Caveats.** Two kinds of due date on one task is one more thing to explain on the sheet. The alternative is to drop the two-week rule and let undated blocks fall under the task's own soft deadline, which is simpler but spreads "reply to X" over a week.

**Effort.** S. **Decision.** Q-05.

## AI-13 Time-of-day awareness in the chat

**Now.** The chat eval (`docs/eval.md`, "With the plan") notes that at 06:47 Claude said a piece "was due" at 08:30 the same morning. The context opens with "Today: Tuesday 13 October 2026, 06:47" but nothing tells the model to compare times. Also, `ChatPrompts.SYSTEM` (`ChatReader.kt:71`) has a missing space ("a line a task).Nothing"), harmless but untidy.

**Proposal.** Add to the system prompt: *"The context gives the time now. A deadline later today is still ahead ('due at 08:30 this morning'), not past. Say 'overdue' only where the plan marks it so."* And in `ChatPrompts.plan`, write each day's deadlines relative to now where it helps ("due in 1 h 43 min"). Fix the space.

**Effort.** S.

## AI-14 More kinds of card in the chat

**Now.** Claude can propose a new task or an instruction's changes (`ChatAnswers.parse`). It can't propose what the app can also do: tick a step of an email or own task, start a focus session on a piece, mark an own task done, or open a task.

**Proposal.** Extend the answer schema with `actions: [{type: "tickStep"|"startSession"|"markDone"|"open", taskId, stepIndex?}]`, each a card (*Start 25 min*, *Tick "Draft intro"*, *Mark done*), applied through the same code the Plan's buttons use (`PieceActions`, `Focus.tickBlock`, `AppGraph.finishOwnTask`). The system prompt lists them beside the change types.

**Final state.** "I've done the first two steps of the UCAS statement" becomes two tick cards; "start me on the physics" starts a session.

**Caveats.** Every new action is a new way for a wrong reading to do something, so each stays behind a tap, as now. "Mark done" for a source's task must stay excluded (Q27: only the source says).

**Effort.** M. **Decision.** Q-06 (which actions).

## AI-15 "Help me start": a micro-plan for the next piece

**Now.** The block screen and the Plan say what to do ("Statics Prep: part 1 of 3, 45 min") and offer Start. For a student who procrastinates, the gap between "Statics Prep" and the first pen stroke is the problem the app exists to close, and nothing bridges it.

**Proposal.** A *Help me start* button on the DO NOW card and the block screen. One call: the piece, the task's instructions, the step title and the minutes in; out, three lines: the very first action (under two minutes), the next two, and what "done for this piece" looks like. Shown on the card; a tap on the first line starts the session. Cached per piece until the task changes (so it's one call per piece, a few cents). This is the implementation-intention pattern ("when I sit down, I will open the booklet at page 7 and read Q1"), which has a large meta-analytic effect on goal attainment (Gollwitzer and Sheeran, 2006, *Advances in Experimental Social Psychology* 38, 69–119).

**Final state.** The card reads: "Start: open the booklet at p.7 and read Q1 aloud. Then: Q1–2 (15 min), Q3–4. Done when: answers to Q1–4 written up." Then *Start 45 min*.

**Caveats.** The model only knows the instructions the source gave; for "see the sheet" homework it will be generic. Cost is small, but it's a call made while the student waits, so it needs the lock change (AC-05) or it can queue behind enrichment.

**Effort.** M. **Decision.** Q-07.

## AI-16 The photo check should see the instructions, not just the piece's name

**Now.** `PhotoChecks.check` sends the piece's label alone ("Prep 30/09: Chapter 17 review, part 2 of 3", `PhotoChecker.kt:43`). The model is asked whether the photo shows "the piece of work described done" without the assignment's instructions, the questions set, or what a "part 2 of 3" is.

**Proposal.** Send the task's title, class, instructions (first 1 500 characters), the step's title and minutes, and, where the task has steps, which steps are already ticked. Ask the model to name what it can see ("answers to Q5–9 of the booklet") and to judge only against the described piece. Add to the system prompt: *"Blank or crossed-out pages, a photo of a screen, printed answers, or work for a different task are not done."*

**Final state.** The verdict's reason reads "Shows worked answers to Q5–8; Q9 isn't visible" rather than "Looks like homework".

**Caveats.** Instructions can be long (up to 2 000 characters from Teams); cap them. The model can't verify correctness, only completion, and the prompt should keep saying so.

**Effort.** S.

## AI-17 Several photos for one piece, and partial credit

**Now.** One photo per check; a "done" at confidence ≥ 0.7 ticks the whole piece; "not yet" ticks nothing.

**Proposal.** Allow up to three photos in one check (three image blocks in the user message) for multi-page pieces, and let the schema return `fraction: number` (0–1) of the piece visible as done. Credit `fraction × piece.minutes` as photo minutes (the plumbing already credits minutes, `Focus.finishPhoto`), ticking the step only at ≥ 0.9.

**Caveats.** Partial credit is easier to game with a few lines on a page. Keep the tick threshold high and the free time proportional. The v1 review's preview and crop ideas were dropped as not worth the screens; this is cheaper than those because it's the same screen.

**Effort.** M. **Decision.** Q-08.

## AI-18 The weekly review should see last week's review and the day records

**Now.** `ReviewInput.describe` (`learn/ReviewInput.kt:79`) sends the week's completions, sessions by hour, blocks, the check-in, the settings and the calibration. It doesn't send the previous review's note and changes, the day records (`DayRecord.full`: which days' plans were done in full), the free time earned and spent, or what the plan left unplanned.

**Why.** Without last week's note the review can't say whether its own change helped ("margin 2 days for Physics: three on-time finishes this week, keep it"); without the day records it can't see that Tuesdays never get done.

**Proposal.** Add three short sections to the input: *Last week's review and changes* (its lines, and each change's setting then and now), *Days* (date, planned minutes, done minutes, full or not), *Free time* (earned, spent). Ask the note to open with one line on last week's change. Keep `MAX_LISTED` bounds as they are.

**Caveats.** A few hundred more tokens once a week. The bounds on what the model may change stay exactly as they are (box, margin, undated minutes, ratio).

**Effort.** S.

## AI-19 A "Steps" change, so Claude can split or re-split a task of your own

**Now.** `Enrichments.jobFor` returns null for `Source.Own` (`core/Enrichment.kt:125`): a task of your own is never split by Claude after it's made. The chat can give steps only when it creates the task. "Split the UCAS statement into four evenings" has no change type to land on; the form can't edit steps either (UI-15).

**Proposal.** A `ChangeType.Steps` carrying a list of `{title, minutes, from?, due?}`, valid only for own tasks, made on the task as the form would (`OwnTasks.edited`), keeping ticks by title. In the chat prompt: *"Steps: replace a task of the student's own's steps with these (adding up to its minutes)."* And a *Split with Claude* button on an own task's sheet that opens the chat with "split this into steps" prefilled.

**Caveats.** Replacing steps on a task with timed minutes needs the same care `withEnrichment` takes; reuse it.

**Effort.** M. **Decision.** Q-06.

## AI-20 Schema descriptions as a steering channel

**Now.** Only `LOCAL_TIME` carries a `description` in the schemas (`Prompts.schema`). The model reads field descriptions as instructions, and they sit next to the field, where a rule in the system prompt can be forgotten by the time the field is written.

**Proposal.** Give every field a one-line description that restates its rule: `minutes: "10 to 60 where the work allows; never over 240"`, `from: "The date before which this step can't be done, only if the instructions give one; never after the task's due date"`, `ankiSections: "Only for a step that is nothing but learning numbered vocabulary sections"`, `effortMin: "The sum of the steps"`. Generated from the same constants as AI-03.

**Caveats.** Structured outputs honour the schema's shape, not its descriptions; the descriptions are prompt text by another route. Keep them short.

**Effort.** S.

## AI-21 Remembered facts about the student

**Now.** The chat sends the last twelve messages and the current state. Something the student says once ("I'm always slow at German", "I can't work on the bus") is gone after twelve messages unless it became an instruction.

**Proposal.** A `remember` card type: Claude proposes a one-line fact to keep ("Prefers maths before physics"); applied, it joins the profile block (AI-05) sent with every call, listed and deletable in Settings. Cap at ten lines. Facts that are really instructions (busy times, limits) should stay instructions, and the prompt should say so.

**Caveats.** A memory the student fills with excuses ("I can't do homework on Fridays") would steer the chat; keep the planner deterministic (memories never change the plan directly) and keep the list visible to you.

**Effort.** M. **Decision.** Q-09.

---

## What I looked at and left alone

- The email, assignment and effort prompts are already careful about the cases found on real data (self-notes, appointments, hand-in steps, relative dates); the changes above are additions, not rewrites.
- The instruction reader's change types and checks are sound; the chat reuses them cleanly.
- The chat's context budgeting (`ChatPrompts.turns`) is well thought out; nothing there needs changing.
- Sending earlier messages of a thread (IMP-P2-003) was your call to leave; AI-04 is the cheaper alternative and I haven't re-proposed the full version.
