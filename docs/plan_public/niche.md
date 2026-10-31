# The niche: what this app is that the others aren't

Read from the code and its documents on 31 Oct 2026 (4.10.0). This file says what the app does that no shipping product does, which of its parts carry that, and the rules a public version must keep so that it never turns into one more app blocker or one more study planner. [market.md](market.md) has the competitors it's measured against, with the evidence.

## The problem it was built for

A student who procrastinates doesn't keep a to-do list. Every planner on the market but the few that read an American LMS fails at its first step, entry: the work has to be typed in, and the person the planner is for is the person least likely to type it. Every blocker fails at its second step, the reason: it blocks by a schedule or a limit the student set in a good moment and loosens in a bad one, and it doesn't know whether the essay is done. The two categories are each saturated ([market.md](market.md) §Competitors), and neither solves the problem, because each solves half of it and leaves the other half to the student.

This app was built by its owner for themselves as the other half of both: it reads the work from where the school puts it, plans it into the day, and lets the phone go only when that work is done. Then it was given a voice that behaves like a person who cares whether the work got done.

## The six parts, and which carry the niche

| | Part | What it does today | Where | Who else does it |
|---|---|---|---|---|
| 1 | **Ingestion** | Reads assignments from Teams (through the widget app), Power Planner, starred Gmail and AnkiDroid; the student types nothing; a photo of homework becomes tasks (NF-01) | `sources/`, the widget, `enrich/PhotoTasks.kt` | Shovel (Canvas, Brightspace, Moodle, Google Classroom, read-only, about a day behind) and Studyo Go (Classroom) read an American LMS; nobody reads Teams, and none of them plans into the day, blocks or speaks. MyStudyLife, Power Planner and the rest are typed in |
| 2 | **Reading** | Claude reads each task into steps, an estimate, a real deadline (quoted or not, AI-12), where it can be done; one question where the text can't settle it (AI-10); the rules stand in when Claude is off (D-14) | `enrich/` | AI schedulers (Motion, Reclaim) take tasks as typed; none reads an assignment's instructions |
| 3 | **The planner** | Pure rules (D-2, D-32): work spread to deadlines by each day's free time around the timetable and the calendar, pace and yield learned, slots written to Google Calendar, a week ahead | `core/Planner.kt`, `core/Slotter.kt`, `calendar/` | Motion and Reclaim auto-schedule for adults' task lists; neither knows a school day, a study period or a term |
| 4 | **Contingent blocking** | Distracting apps and sites are blocked while today's planned work isn't done (BL-01); free time is earned by doing it, about a minute for three (D-51), and checked: a photo read by Claude, a source confirming it, Anki's counts (BL-08). Nothing to do, nothing blocked | `block/` | Every blocker on the market blocks by schedule, usage limit or a session the user starts. FocusFlow (iOS, thin evidence it's still live) imports Canvas and earns screen time by tasks ticked; Study Lock earns it by quizzes. Neither schedules, checks the work or speaks |
| 5 | **A voice with a stake** | Felix writes everything the app says, in one chat that is the app's home (D-95, D-121): the morning, the nudge at a slot's start, the question, the review; he's let down when work is skipped and pleased when it's done (D-119); he remembers (D-64), acts at once with an Undo (D-60), and never touches blocking's settings (D-61) | `felix/` | "AI coach" features elsewhere are a chat window beside the product. Companions (Replika, Character.ai) have a relationship and no work |
| 6 | **The parent as a stakeholder** | An authenticator code a parent holds applies a waiting change or unblocks an hour (D-47); loosening the blocker waits 24 hours (D-46); a weekly summary goes home in the third person, by the student's hand (D-66). The parent sees no message, no task, no screen | `protect/`, `learn/Summary.kt` | Parental control (Bark, Qustodio, Family Link) is surveillance the child doesn't choose. Here the student arms it and the parent holds the key |

Parts 1, 4 and 6 together are the niche. Nobody ships the combination of **work read from the school, enforcement contingent on that work, and a commitment device a parent holds**; the research in [market.md](market.md) found no product with three of the four pillars (ingestion, planning, contingent blocking, a coach with a relationship), two with two (Shovel: ingestion and planning; FocusFlow: ingestion and task-gated screen time, thinly evidenced), and the rest with one. Parts 2, 3 and 5 are what make 1, 4 and 6 bearable to live with: a plan that's wrong, or a blocker with no voice, is deleted within a week.

Two more things carry weight with the people who would pay for it:

- **Privacy by construction.** There's no server, no account, no analytics (spec/privacy.md); the data is JSON on the phone, backed up to the phone's own Documents folder. For a product used by under-18s this is a strength the incumbents can't claim, and the Children's Code ([compliance.md](compliance.md)) makes it a requirement rather than a boast.
- **The core is deterministic.** Ranking, deadlines, allocation and "done" never depend on Claude (D-2). When the model is wrong, slow, refused or unpaid-for, the app still plans and still blocks. This is also what makes the LLM cost controllable: the model is advice, not the engine.

## What's distinctive and what's merely particular

Not everything the app does is part of the niche. Some of it is the owner's taste, right for one person and wrong as a product's default. Telling the two apart is the whole of the public plan.

**Distinctive (keep, make it the product):**

- Entry by ingestion, never by typing, with typing and photos as the fallback.
- Blocking as a function of the plan's state, never of the clock alone.
- Earned free time, real and spent, in place of points and streaks (D-38, D-90).
- Checked completion: a source's word, a photo, a count; a tick keeps a slot, it doesn't earn (D-52).
- Loosening waits; tightening and every change to the work is instant (D-1, D-46).
- No silent bypass: where the platform lets the student switch the blocker off (Play forbids stopping them, [compliance.md](compliance.md) §Google Play), the app notices and the parent hears of it.
- One voice for everything the app says, which acts with an Undo and can't touch the blocker.
- The parent holds a key, not a window.
- A pure planner with every input through one door (D-32), learning bounded and shown.

**Particular (the owner's, to become settings, presets or a tier):**

- The UK sixth-form shape: Study Periods, 16:45 and 10:00, 22:30 to 07:00, A-level subjects, Anki textbooks (D-8, D-9, D-50).
- The four sources as they are: Teams by driving the Teams app, Power Planner through its web app's key, Gmail by app password, AnkiDroid ([what-changes.md](what-changes.md) §Ingestion).
- Felix as "a stern boyfriend with the romance taken out" and "the closest person you've got" (D-120): one register of several, and for a public product used by minors not the default one ([felix-public.md](felix-public.md)).
- Opus 5.5 at high effort for every reading and medium for every message (D-5, D-15), on the owner's own key.
- adb as part of setup: credentials over `run-as`, `WRITE_SECURE_SETTINGS` for self-repair (D-48), the accessibility service enabled from a PC.
- The settings guard reading Samsung's Device Care page (`com.samsung.android.lool`).
- The summary email's address set on the phone, the parent's only channel.

## The rules a public version keeps

These are the lines that stop the product sliding into the saturated categories. Each is a decision the owner already made, carried over; a public version that breaks one has become something else.

1. **Nothing is blocked when nothing is due.** Blocking is a function of today's work (BL-01). No schedule-only mode, no usage limits, no "focus session" the user starts (D-55). A student with the day's work done has their phone.
2. **The student never has to type the work in.** Ingestion is the primary path; a photo, the chat and the form are the fallbacks. A release that makes manual entry the onboarding has lost the moat.
3. **Free time is the only reward.** No streaks, badges, trees or leagues (D-38). The reward is the thing the student wanted, given back.
4. **Done is checked, not declared.** A source says when its task is done (D-54); a long piece earns by its photo or its source (D-52). The product's honesty about completion is what makes the parent's trust and the student's free time worth anything.
5. **Felix is the app, not a feature.** Every word in the app's name is his, in one chat (D-95). He acts at once with an Undo (D-60). He never changes the blocker, protection or the model's settings (D-61). The day the app grows a second voice, or a "chat with AI" button beside its screens, it has become a planner with a chatbot.
6. **The parent holds a key, never a window.** The parent sees the weekly summary the student sends, holds the override code, and is told when the blocker is switched off or a loosening is waiting: the state of the commitment, never its content. Never the chat, the tasks, the blocks or the phone's use. Surveillance is the other category's product, and the student's consent is what makes this one work.
7. **The core stays deterministic (D-2).** The model reads, writes and advises; the rules decide what's due, what's planned and what's done. The app works with the model off.
8. **Everything the owner or Felix set is kept as set (D-4).** The app's limits apply to its own values. A product that silently rewrites the student's deadlines is one they stop trusting.
9. **No data leaves without a reason the student can read** (spec/privacy.md). No analytics by default, no advertising, no sale of anything but the subscription.

## What the niche is worth, in a line

A student (or their parent) isn't buying a blocker or a planner. They're buying the one thing neither sells: *the phone comes back when the homework is actually done, and someone noticed.* Everything in [stages.md](stages.md) is ordered to keep that sentence true at every release.
