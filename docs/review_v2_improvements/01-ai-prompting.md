# 1. AI prompting

What Claude is asked, how its answers are checked, and where better wording or a little more context would give better readings. Covers the enrichment jobs (`enrich/Prompts.kt`), the chat (`enrich/ChatReader.kt`), instructions (`enrich/InstructionReader.kt`), the photo check (`enrich/PhotoChecker.kt`), the weekly review (`learn/ReviewInput.kt`), and two prompts that don't exist yet: Felix's messages (AI-22) and the photo-as-context reading (AI-23).

Revised after your answers in [QUESTIONS.md](QUESTIONS.md). Items marked **Changed** differ from the first version; **Withdrawn** items are kept as a line so the record shows why.

How to read each item: **Now** is what the code does, with where; **Why** is what it costs; **Proposal** is the change; **Final state** is what you'd see; **Caveats** are the honest downsides; **Effort** is S (an hour or two), M (a day), L (several days). A **Decision** line points at a question.

The model-side settings (effort, model per job, caching, retries) are in [02-ai-architecture-and-cost.md](02-ai-architecture-and-cost.md).

## Summary

| ID | Improvement | Priority | Effort | Status |
|---|---|---|---|---|
| AI-01 | One repair round, then salvage a split with one bad date | P1 | M | Changed (Q-01); Implemented (v3 phase 7) |
| AI-02 | The Email job after starred-only reading: no Info, always a next step | P1 | S | Changed (Q-04); Implemented (v3 phase 1) |
| AI-03 | Tell the model the hard limits the checks enforce | P1 | S | –; Implemented (v3 phase 7) |
| AI-04 | Tell the model it sees only the newest message of a thread, and what's attached | P1 | S | Implemented (v3 phase 1, as the whole conversation) |
| AI-05 | A stable "about me" block: subjects, class codes, year, timetable, your lines, memories | P1 | M | Changed (Q-02); Implemented (v3 phase 5) |
| AI-06 | Stop hard-coding "Year 12" | P1 | S | –; Implemented (v3 phase 5) |
| AI-07 | Worked examples in each system prompt | P2 | M | –; Implemented (v3 phase 7) |
| AI-08 | Keep step titles stable when a task is enriched again | P2 | S | –; Implemented (v3 phase 7) |
| AI-09 | Ask for a one-line reason with each reading, and show it | P2 | M | –; Implemented (v3 phase 7) |
| AI-10 | Let the model ask one question when it isn't sure, and notify | P1 | M | Changed (Q-03); Implemented (v3 phase 5) |
| AI-11 | A way to say "this email is a task" when Claude hid it | – | – | Withdrawn (Q-04) |
| AI-12 | Every date the model sets is soft | P1 | S | Changed (Q-05, note 4); Implemented (v3 phase 2) |
| AI-13 | Time-of-day awareness in the chat | P1 | S | –; Implemented (v3 phase 7) |
| AI-14 | More kinds of card in the chat: tick, steps, open, move | P2 | M | Changed (Q-06); Implemented (v3 phase 7) |
| AI-15 | "Help me start": a micro-plan for every slot, ready before it starts | P1 | M | Changed (Q-07); Implemented (v3 phase 7) |
| AI-16 | The photo check should see the instructions, not just the piece's name | P1 | S | –; Implemented (v3 phase 7) |
| AI-17 | Several photos for one piece; no partial credit | P3 | S | Changed (Q-08); Implemented (v3 phase 7) |
| AI-18 | The weekly review should see last week's review, the day records and the slots kept | P2 | S | Changed |
| AI-19 | A "Steps" change, so Claude can split or re-split a task of your own | P2 | M | –; Implemented (v3 phase 7) |
| AI-20 | Schema descriptions as a steering channel | P2 | S | –; Implemented (v3 phase 7) |
| AI-21 | Remembered facts and standing instructions, with a budget instead of a cap | P1 | M | Changed (Q-09, Q-36, Q-46); Implemented (v3 phase 5) |
| AI-22 | The voice: Felix, a strict friend, everywhere | P1 | M | New (note 6, Q-31, Q-44); Implemented (v3 phase 5) |
| AI-23 | A photo as context for an existing task | P2 | M | New (Q-22); Implemented (v3 phase 7) |
| AI-24 | Replies continue in the chat; changes to existing tasks apply at once | P1 | M | New (note 6, Q-31, Q-42); Implemented (v3 phase 5) |

---

## AI-01 One repair round, then salvage a split with one bad date

**Now.** `Answers.steps` (`enrich/Prompts.kt:311`) rejects the whole list of steps when any one step has a `from` after the task's due date (`Prompts.kt:321`), a date that can't be read, or dates out of order. The assignment is then planned as one block at its whole estimate, and a "Claude's plan was dropped" warning is posted. The first eval run hit exactly this (`docs/needs-you.md`, 1.8.0): nine sensible steps for the German assessment, one dated after the Teams hand-in, and 3½ hours landed as one lump.

**Why.** The failure mode is the most expensive one: the better the reading (dated steps for a multi-stage assignment), the likelier one date trips the check.

**Proposal.** Three layers, in order (your answer to Q-01):

1. Prompt: *"No step may start after the assignment's due time. Work that comes after the hand-in (revising for a test the work prepares for) gets no dates of its own; give the test's day in testDate instead."* The same for Effort: *"No block may start after the item's date."*
2. Repair: when a check fails, one more call with the same messages plus the model's own answer and the exact complaint ("Step 7, 'Revise using the marked sheet', starts on 10 Oct, after the due time 8 Oct 08:30. Return the whole answer again with only that put right."). Structured output as before; the checks run again.
3. Salvage: if the repair still fails a date check, keep the split and drop only the offending step's dates, recording what was changed in `Enrichment.adjusted` ("'Revise using the marked sheet': its start was after the hand-in, so it's planned by the task's date"). Still rejected outright: no title, no minutes, dates that can't be read at all, sums that don't add up after repair.

**Final state.** The assessment is planned as nine steps; the one odd step is undated and the sheet says why. The dropped-plan notification fires only for the unrepairable cases, after a repair was tried.

**Caveats.** The repair round is a second call while the task waits (a minute at HIGH effort; cost is not a concern, Q-10). The tests in `EnrichTest` that assert a drop need the new outcomes: repaired, salvaged, dropped.

**Effort.** M.

## AI-02 The Email job after starred-only reading: no Info, always a next step

**Now.** The Email prompt has four kinds including Info ("only to read"), and Info emails are hidden from the plan. With starred-only reading (DX-03, your answer to Q-04) every email that reaches the model is one you starred, and you've said a starred email can never be dropped as Info.

**Proposal.** Remove Info from the Email schema's `kind` (Admin, Homework, Revision, Event stay). Replace the Info guidance with: *"Every email you see was starred by the student: it is something to do. Say what, in nextStep, even when the email itself is only information (then the task is to read it and decide, 5 minutes)."* The self-note rule stays (a note to self is Admin with a concrete next step; the eval's recorded-talk case). Keep the rules' 10 minutes as the floor for a self-note by clamping `effortMin` in `Answers.parse`.

**Final state.** Nothing starred vanishes. The Email job's hardest judgement ("is this a task at all?") is yours, made with a star in Gmail, and the model's job is reading what, when and how long.

**Caveats.** You must star in Gmail, including notes to yourself (a Gmail filter "from:me to:me → star" does that with no code). The eval set's Info cases become "not sent" cases and should be moved out of the Email set.

**Effort.** S.

## AI-03 Tell the model the hard limits the checks enforce

**Now.** The checks drop an assignment split whose steps run past 240 minutes each (`MAX_STEP`), a total past 600 (`MAX_EFFORT`, `Prompts.kt:185`), blocks past 30 or 20 hours, and steps that don't add up to the total within a tenth. The prompts say "10 to 60 minutes where the work allows" and name the 30-block limit, but never the 10-hour total, the 240-minute step, or the 20-hour block ceiling, and only the Effort prompt says the blocks must add up.

**Proposal.** A short "Limits" paragraph in each system prompt, generated from the same constants so they can't drift: *"Limits the app enforces: at most 12 steps; no step longer than 240 minutes; a total of at most 10 hours; the steps must add up to effortMin exactly."* For emails and planner items: *"at most 30 blocks, 20 hours in all, adding up to effortMin exactly."* Move the constants that are private in `Answers` to where `Prompts` can read them. With AI-01's repair round, a limit broken is also named in the repair message.

**Effort.** S.

## AI-04 Tell the model it sees only the newest message of a thread, and what's attached

**Now.** `Prompts.describe` (`Prompts.kt:136`) sends Received, From, Subject and the newest message's text. The thread's message count is stored (`GmailThreads.EXTRA_MESSAGES`) but not sent. Attachments are dropped at `Mime.kt:48` and never named.

**Proposal.** In `describe`, after From: `Messages in this conversation: 3 (only the newest is shown; earlier ones may hold the request)`, and `Attachments: consent-form.pdf (110 KB), itinerary.docx` once DX-01 records attachment names. In the system prompt: *"You see the newest message only. If it refers to an earlier message or an attachment you can't see, don't guess what it asked: make nextStep 'Read the earlier message/attachment and decide', effortMin 10."*

**Effort.** S (the attachment list needs DX-01 first).

## AI-05 A stable "about me" block: subjects, class codes, year, timetable, your lines, memories

**Now.** The model knows "a UK sixth-form student (Year 12)" and the task's class code as the source has it (`12.2-PH3`, `German PETERS`). It doesn't know that `12.2-PH3` is Physics, which subjects you take, your timetable, or anything you've told it before.

**Proposal.** A profile block sent as a second system text after every job's instructions and in the chat and nags, built from (your answer to Q-02: all three):

- subjects and their class codes, seeded from Power Planner's class names and the Teams codes seen so far, confirmed once in the chat;
- year group from the date (AI-06);
- the week's timetable in one compact form (DX-09): lessons by day, free periods, school end;
- the working hours and the German textbook from Settings;
- lines you write in Settings ("target grades…", "I'm slow at German vocab");
- the remembered facts (AI-21).

Budget: 2,000 characters (about 500 tokens), the memories cut oldest-first when it's full, so the block can never crowd the email or the plan.

**Final state.** Assignment steps name the subject's real work; the chat knows "physics" means `12.2-PH3`; the nags know football is Tuesday without being told again.

**Caveats.** A wrong class mapping misleads every call: show the mapping and let it be edited. The block changes whenever a memory changes, which breaks the prompt cache (AC-01); that's fine, cost isn't the constraint.

**Effort.** M.

## AI-06 Stop hard-coding "Year 12"

**Now.** `Prompts.kt` (`STUDENT`), `ChatPrompts.SYSTEM`, `InstructionPrompts.SYSTEM`, `PhotoChecker.SYSTEM` and `ReviewInput.SYSTEM` all say "Year 12". In September 2027 the prompts would describe the wrong year.

**Proposal.** One `Student.yearGroup(now)` from a `yearGroupStart` setting, rolling over on 1 September; every prompt reads it through the profile block (AI-05).

**Effort.** S.

## AI-07 Worked examples in each system prompt

**Now.** The prompts are instructions only. The eval history (`docs/data-sources.md` §5) is a list of cases where wording was tightened one by one.

**Proposal.** Two or three synthetic examples per job in the system prompt, each a short input and the exact JSON expected: a form with a date (Admin, deadline, 15 min), a parent's email with three dated items (blocks), a two-part homework with a test date (steps, testDate, ankiSections), a self-note with a link (Admin, nextStep), an appointment in Power Planner (effort 5, no blocks). No Info example (AI-02). No real names or addresses.

**Caveats.** Examples anchor: a model shown "15 min" for forms may say 15 for every form. Keep them varied and re-run the eval set after adding them.

**Effort.** M.

## AI-08 Keep step titles stable when a task is enriched again

**Now.** A task is enriched afresh whenever its content hash changes (`Enrichments.inputHash`, `core/Enrichment.kt:142`). `withEnrichment` (`Enrichment.kt:100`) carries a step's done mark to the new step of the same title at the same position. The model is given nothing about the old split, so a reworded step loses its tick.

**Proposal.** When a task has a current or previous enrichment with steps, append to the user message: *"Steps as planned before (keep the exact title of any step whose work is unchanged; mark none as done): 1. …, 2. …"*. Also used by AI-23 (a photo re-read).

**Effort.** S.

## AI-09 Ask for a one-line reason with each reading, and show it

**Now.** The task sheet says "Steps and estimate by Claude" and, for an email, the next step. It doesn't say why Claude read the deadline as Friday.

**Proposal.** Add `reason: string` (under 160 characters) to the Email and Assignment schemas: *"the phrase in the text that gave the deadline, kind or test date, quoted"*. Store it on `Enrichment.reason`; show it on the task sheet under the due date.

**Effort.** M.

## AI-10 Let the model ask one question when it isn't sure, and notify

**Now.** The schemas force an answer; the model can't say "I can't tell the deadline from this" or "the sheet in the photo may be the wrong one".

**Proposal.** Add `question: string | null` to the Email, Assignment and photo-to-task schemas: *"rarely: the one question that would settle a reading the text can't (which Friday; whether the test is the 12th or the 19th)"*. A question is put to you by Felix (AI-22) as a notification with a reply field (AI-24), and sits on the task's sheet until answered; the answer is fed back as an instruction on the task, which re-enriches it with the answer in the text ("The student says: the test is the 19th"). With starred-only reading the question is never "is this a task?", which you've answered with the star.

**Final state.** A reading the model can't make is a question to you, in Felix's words, not a guess.

**Caveats.** Over-use: cap it in the prompt ("rarely") and watch the rate in the eval set.

**Effort.** M.

## AI-11 Withdrawn

A "this email is a task" override is unnecessary once only starred emails are read (DX-03): a star is that override, made in Gmail.

## AI-12 Every date the model sets is soft

**Now.** The Email prompt says both *"deadline: … Never invent one"* and, for blocks, *"A quick action with no date of its own that's best done soon … is due within the next two weeks"*. The app shows that invented date as hard ("Overdue"). Steps' `from` and `due` dates are hard too.

**Proposal.** Your answers to Q-05 and note 4 settle it more simply than a flag: every date that comes from the model or the app (a step's `from`/`due`, a quick action's two weeks, the undated week) is **soft** in the planner (PL-14): it orders and places the work, it never shows "Overdue", and when it passes the work is re-fitted. Only a date from a source (Teams due, Power Planner date, an email's stated deadline the model quotes in `reason`) is hard. The schema gets `deadlineQuoted: boolean` so the app knows which emails' deadlines are the sender's words and which are the model's guess; the prompt says: *"deadline: only a date the email itself gives; deadlineQuoted true then. A quick action with no date: deadline null, and the app plans it within a fortnight."*

**Final state.** "Reply to Mr Peters" is planned this week and slides if the week is full; "Form by Friday" is overdue on Saturday.

**Caveats.** An email that gives a date loosely ("by the end of the week") is quoted by the model and so hard; that's right.

**Effort.** S.

## AI-13 Time-of-day awareness in the chat

**Now.** The chat eval (`docs/eval.md`) notes that at 06:47 Claude said a piece "was due" at 08:30 the same morning. `ChatPrompts.SYSTEM` (`ChatReader.kt:71`) also has a missing space ("a line a task).Nothing").

**Proposal.** Add: *"The context gives the time now. A deadline later today is still ahead, not past. Say 'overdue' only where the plan marks it so."* In `ChatPrompts.plan`, each slot (PL-12) is given with its time and whether it's past, now, or ahead. Fix the space.

**Effort.** S.

## AI-14 More kinds of card in the chat: tick, steps, open, move

**Now.** Claude can propose a new task or an instruction's changes (`ChatAnswers.parse`). It can't tick a step, re-split your own task, open a task, or move a piece to a time.

**Proposal.** Per your answer to Q-06: `actions: [{type: "tickStep"|"open", taskId, stepIndex?}]` and the `Steps` change (AI-19); not "start session" (sessions go, note 1) and not "mark done" for a source's task. Add the `AtTime` change (PL-12: "put the physics at 19:30") beside `OnDay`. Each is a card tapped on purpose, as now.

**Effort.** M.

## AI-15 "Help me start": a micro-plan for every slot, ready before it starts

**Now.** The DO NOW card says what ("Statics Prep: part 1 of 3, 45 min"). Nothing bridges the gap between the name and the first pen stroke.

**Proposal.** For each slot in today's and tomorrow's plan (PL-12), one call ahead of time: the piece, the task's instructions, the step title and the minutes in; out, three lines: the first action (under two minutes), the next two, and what "done for this piece" looks like. Shown on the slot's card, in the slot-start notification and in the Day timeline; cached per piece until the task or the step changes. The implementation-intention form ("when I sit down, I will open the booklet at page 7 and read Q1") has a large effect on getting started (Gollwitzer and Sheeran, 2006, *Advances in Experimental Social Psychology* 38, 69–119). Generated ahead rather than on tap because cost isn't the constraint and waiting is.

**Final state.** The 17:00 notification reads: "Statics Prep until 17:45. Start: open the booklet at p.7 and read Q1 aloud. Then Q1–2, Q3–4. Done when: Q1–4 written up."

**Caveats.** For "see the sheet" homework the lines are generic until a photo of the sheet is added (AI-23).

**Effort.** M.

## AI-16 The photo check should see the instructions, not just the piece's name

**Now.** `PhotoChecks.check` sends the piece's label alone (`PhotoChecker.kt:43`).

**Proposal.** Send the task's title, class, instructions (first 1,500 characters), the step's title and minutes, and which steps are already ticked; ask the model to name what it sees and judge only against the described piece. Add: *"Blank or crossed-out pages, a photo of a screen, printed answers, or work for a different task are not done."*

**Effort.** S.

## AI-17 Several photos for one piece; no partial credit

**Now.** One photo per check; done at confidence ≥ 0.7 ticks the piece.

**Proposal.** Up to three photos in one check (three image blocks) for multi-page pieces. No fraction, no partial credit (your answer to Q-08).

**Effort.** S.

## AI-18 The weekly review should see last week's review, the day records and the slots kept

**Now.** `ReviewInput.describe` (`learn/ReviewInput.kt:79`) sends the week's completions, sessions by hour, blocks, the check-in, the settings and the calibration. It doesn't send the previous review's note and changes, the day records, or the free time earned and spent.

**Proposal.** Add *Last week's review and changes*, *Days* (date, planned, done, full or not), *Free time*, and, once PL-12 exists, *Slots* (kept, moved, missed, by hour and day) in place of sessions by hour. Ask the note to open with one line on last week's change.

**Effort.** S.

## AI-19 A "Steps" change, so Claude can split or re-split a task of your own

**Now.** `Enrichments.jobFor` returns null for `Source.Own` (`core/Enrichment.kt:125`): a task of your own is never split by Claude after it's made.

**Proposal.** `ChangeType.Steps` carrying `{title, minutes, from?, due?}` for own tasks, made as the form would (`OwnTasks.edited`), ticks kept by title; a *Split with Claude* button on an own task's sheet that opens the chat prefilled.

**Effort.** M.

## AI-20 Schema descriptions as a steering channel

**Now.** Only `LOCAL_TIME` carries a `description` (`Prompts.schema`).

**Proposal.** A one-line description per field restating its rule, generated from the same constants as AI-03.

**Effort.** S.

## AI-21 Remembered facts and standing instructions, with a budget instead of a cap

**Now.** The chat sends the last twelve messages and the current state; what you said once is gone unless it became an instruction.

**Proposal.** Two kinds of memory, both written by Claude on his own (Q-36) from the chat and from replies to Felix, each shown as a "Remembered: …" line when made (Q-46), listed and editable in Settings → Felix, reset in LR-08:

- **Facts** `{topic, text, at}`: things about you and your week ("football Tuesdays", "slow at German vocab", "prefers maths before physics"). A new fact on a topic replaces the old one.
- **Standing instructions** `{text, at}` (your addition in Q-46): how Felix should behave ("don't message me before 8", "be blunter", "ask for the photo once, not twice", "call it the stats booklet"). These go into Felix's system prompt as rules that override his defaults (his quiet times, his tone, his wording) and the chat's. They're never merged away without being shown.

Both go into the profile block (AI-05) on every call. Things that are really plan inputs (busy times, limits, holidays and half-days) are routed to instructions or the school calendar (PL-16), not to memory; the prompt says which is which.

**On the cap** (your question in Q-46). Cost isn't the reason for one; the bill is fine either way. The reasons are these. Every memory is 15–30 tokens the model has to weigh on every call, and a long list of stale, overlapping or contradictory lines makes its readings worse, not better: it will act on a fact you no longer hold, or on the older of two that disagree. A list of 300 lines in Settings is a list nobody audits, so a wrong fact lives forever. And any change to the list invalidates the prompt cache (AC-01), which is minor. A very large cap is therefore safe for the bill and bad for the quality of what Felix knows. So: no hard count; a budget of about 1,500 tokens (roughly 80 lines) for facts and 20 lines for standing instructions; and a weekly tidy with the review (confirmed in Q-51: no cap, and the pruning is his, not yours): Felix rewrites the facts into fewer, current lines (merging, dropping what's stale, keeping the newest on each topic), the before-and-after shown on the Reviews page (LR-01) with undo.

**Caveats.** Silent updates mean a misheard reply can become a fact; the "Remembered:" line and the list are the check. The tidy is a model rewriting its own memory, which can lose a nuance; that's why it's shown as a diff rather than done silently.

**Effort.** M.

## AI-22 The voice: Felix, a strict friend

**Now.** Notifications are computed strings (the briefing's list, "N min left"). Your note: they should feel like a person nagging you, and you should be able to answer. Your answer to Q-31: a strict friend, named Felix, who decides for himself when to check again.

**Proposal.** A `FelixPrompts` job used for every message in the voice: nags, the morning message (LR-04), the slot-end question (LR-09), the day-done line, questions (AI-10), the Sunday reminder (NF-02). Input: the reason (a slot started with no activity; a slot ended without a tick; a kept slot; the day done; the morning; a question to put), the slot and its task with its micro-plan (AI-15), today's plan and what's been done, the profile and memories, the last few messages and your replies (so he doesn't repeat himself and can hold you to what you said), the time. Output as structured JSON: `message` (one or two sentences), `replies` (up to three short quick replies), `nextCheckMin` (when he wants to check again; 20 when he gives none, Q-31), `memories` (AI-21). System prompt: *"You are Felix: a strict friend who knows the student's week and wants the work done. Be concrete: name the piece and its first action. Two sentences at most. No guilt, no sarcasm, no emojis, no promise the app can't keep. When you want a reply, offer the replies you'd accept. Say when you'll check again, and mean it."* No tone setting: Felix is the voice; his name and the one line that describes him are editable in Settings for the day you want him different.

**Final state.** 17:12: "You put the statics at five. It's twelve past; p.7, Q1 is two minutes. Starting?" with *Starting now* / *Give me 15* / *Not tonight, because…*. He checks again when he said he would.

**Felix everywhere** (Q-44). The chat's system prompt (`ChatPrompts.SYSTEM`) takes the same persona, so questions and instructions in the chat are answered as Felix; the instruction reader's reply text too; the button is "Felix", not "Ask Claude" (UI-16). "Claude" stays the name of the model in Setup and Settings (the key, the cost, the effort table); Felix is the one who talks to you (confirmed in Q-55: Felix everywhere except Settings).

**Caveats.** A model writing to you several times a day will sometimes misjudge; the history in the prompt, your standing instructions (AI-21) and the state check before sending (LR-03) are the controls, and AC-07's log shows every message.

**Effort.** M.

## AI-23 A photo as context for an existing task

**Now.** The Assignment job reads Teams' instructions only. Your note: recent Physics tasks got two hours when the sheet, seen, would have been thirty minutes.

**Proposal.** *Add a photo* on any task's sheet (and the camera flow of NF-01 can attach to an existing task instead of creating one). The task is re-enriched by the Assignment job with the photo as an image block and the instruction *"The student photographed the work itself; estimate and split from what the photo shows, and say in reason what you saw."* AI-08 keeps the step titles stable where the work is unchanged. The photo is kept with the task and shown on the sheet; the estimate line says "from your photo".

**Final state.** A 30-minute sheet is planned as 30 minutes; the calibration (which multiplies the estimate) learns from the better estimate rather than compensating for a bad one.

**Caveats.** A photo of the wrong sheet gives a confident wrong reading; the reason line and the question field (AI-10) are the checks.

**Effort.** M.

## AI-24 Replies continue in the chat; changes to existing tasks apply at once

**Now.** A notification's actions are fixed buttons; the chat is a screen.

**Proposal.** Felix's messages, questions (AI-10) and the morning message carry an inline reply (`RemoteInput`). A reply is a chat turn: Felix's message is the previous turn, your reply the next, and `ChatReader` answers with the normal schema (reply, changes, actions, memories). Your answer to Q-42 draws the line:

- **Applied at once:** anything that alters an existing task or piece (moving it within its due date, later today, its minutes, its deadline, its steps, not-a-task, a tick you report, "took longer by N"), a busy time for today, and memories.
- **A button first:** anything new (a task, an event, a busy time beyond today, a holiday or half-day for the school calendar PL-16), because new things carry descriptions and timings to get wrong.

The answer comes back as a notification in Felix's words saying what he did ("Moved it to 17:27 and the reply to Mr Peters to 18:15") or offering the button. The chat screen shows the whole thread, so a conversation begun from a notification continues there.

**Caveats.** Applied-at-once means a misread reply moves something; every applied change is an instruction, listed and undoable from the chat ("undo that"). The model lock (AC-05) matters here: a reply must not wait behind a background enrichment. What a move does to today's block is BL-11 (Q-48).

**Effort.** M.

---

## What I looked at and left alone

- The assignment and effort prompts are careful about the cases found on real data; the changes above are additions, not rewrites.
- The instruction reader's change types and checks are sound; the chat reuses them cleanly, and the new change types (`AtTime`, `Steps`) fit the same shape.
- The chat's context budgeting (`ChatPrompts.turns`) is well thought out.
- Sending earlier messages of a thread (v1's IMP-P2-003) was your call to leave; AI-04 is the cheaper alternative.
