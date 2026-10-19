# CLAUDE.md

Decrastination is a personal Android app, sideloaded onto one phone. It gathers the owner's school work (Teams assignments, Power Planner, starred Gmail, AnkiDroid decks, tasks of their own), plans it into timed slots, writes them to the calendar, blocks distracting apps while work is due (an accessibility service), and talks through Felix, a persona Claude writes as, in a chat and in notifications. Kotlin, Jetpack Compose (Material 3), Glance for the widget; the app's state is JSON files; Claude's API through the Anthropic Java SDK. `README.md` has more.

Every Claude session in this repository reads this file: the reviewer GitHub Actions runs on a pull request, and Claude Code working here. The last two sections are each for one of them, *Working in a local session* for Claude Code and *Reviewing a pull request in GitHub Actions* for the reviewer; the rest holds for every session.

## Where things are said

- `docs/decisions.md`: the owner's decisions, numbered (D-1 on). The code follows them; something that goes against one is a question for the owner, not a bug to fix.
- `docs/development.md`: how changes are made, tested, reviewed, checked on the phone and shipped; the public repository's rules.
- `docs/spec/`: how each area works now, naming its code. A change in behaviour updates its file in the same PR.
- `docs/plan_v4/`: the plan being carried out, a stage a PR. `03-stages.md` says what each stage's PR does, tests and leaves; `02-target-architecture.md` the structure aimed at and its rules; `04-data-and-migrations.md` how stored data changes; `11-conventions-and-docs.md` the conventions.

## What matters most here

- **No data is ever lost.** The app's state is JSON files, written whole and atomically (`data/JsonStore.kt`). A change to a stored type's shape must still read every file an older build wrote: from 3.3.2 by raising the store's version and adding a migration, tested from every earlier shape (`docs/plan_v4/04-data-and-migrations.md`). A new store goes into the backups and the resets.
- **What the owner or Felix set is kept as set (D-4).** The app's limits and its learned pace apply to its own values, never to a task's minutes, steps, times or days that the owner or Felix gave.
- **The repository is public (D-79).** Nothing personal in code, tests, fixtures, docs, commit messages or a PR's text: no email address, person's name, real task or email title, calendar event, address, phone number, personal date, school detail or device identifier. Fixtures and examples are made up; real data lives in the git-ignored `private/`.
- **Calls to Claude cost money.** Each goes through one place, under the monthly cap and its rests, and is put on record. No new call without need, none round the cap, none in a loop. A JSON schema sent to the API keeps its list items' fields plain (empty, or -1 for none, never nullable): a large one can be refused even when every test passes.
- **Prompts are held by goldens.** A change to a system prompt or a schema changes its copy in `app/src/test/resources/prompts/` in the same PR; one to what a reading is made by also raises `Prompts.VERSION`.
- **Android.** Work that has to outlive a screen or a receiver runs as WorkManager work (Android 15 cuts an app's network seconds after it leaves the screen). A notification's id and its request codes are its own (`notify/NoticeId.kt` from 3.3.0), so none replaces another. The accessibility service must never crash as it connects: Android then drops it from the enabled list without a word. A `PendingIntent` names a component that exists.
- **One writer a store** is where the code is heading (`docs/plan_v4/02-target-architecture.md`): new code changes a store through its owner, and other code reads its flow or calls the owner. Older code moves over to it stage by stage.
- **Tests.** New behaviour comes with tests; a service is tested on temporary stores, as `block/FocusTest.kt` does. Golden copies and the plan's snapshot change only on purpose, their diff in the PR.
- **Docs move with the code.** Behaviour changed: its `docs/spec/` file in the same PR. A new decision of the owner's: `docs/decisions.md`.

## Conventions (`docs/plan_v4/11-conventions-and-docs.md`)

- Comments say what the code does and why, in the present tense, in plain British English. Requirement ids (PL-12, AI-19) stay; the owner's decisions are referred to ("D-14"), not retold; no review history in comments.
- Imports, not inline fully qualified names, in the code a change moves or writes.
- A change matches the code around it. What's already there isn't renamed or reworded without a reason.

## Working in a local session

This section is for Claude Code working here; the reviewer can leave it aside. It keeps the work quick: most of a stage's time went on many small steps, so the steps are made bigger.

- **Fewer, bigger steps.** Chain the commands that depend on each other (`&&`), make an edit across several files with one script, send independent reads and checks together, and don't read a file back after editing it. A check before anything hard to undo (a push, an install, a reply, a merge) stays a step of its own, so a surprise still stops the work.
- **The helpers first.** The git-ignored `private/tools/` holds what each PR needs, listed in its `README.md`: `pr_status.py` (each PR's state; `--ready` the merge checklist), `watch_pr.py` and `watch_ci.py` (Codex and CI waited for in the background), `merge_up.sh` (a fix merged up a stack), `reply_resolve.py` (review threads answered and resolved), `held_by_name.py` (before a build that moves a class Android holds by name). A new script is written only for what they don't do, and kept there if it'll be wanted again.
- **Reviews only when asked.** No Codex or Claude review ever starts by itself: only a comment `@codex review` or `@claude review` starts one (opening or pushing to a PR starts none, whatever Codex's boilerplate says). Who asks, and how many rounds, is the owner's call per PR ([development.md](docs/development.md) §Changes, pull requests and reviews).
- **Short stacks.** A PR's reviews are done, or it's merged, before more than one or two are stacked on it: a fix low in a long stack has to be merged up through every branch above it, with its conflicts in each.
- **One full CI run a push.** While changing code, compile, then run the tests the change touches. Before a push, one run of CI's command (`./gradlew testDebugUnitTest lintDebug assembleDebug`), on a stack's top branch alone: CI runs it on every branch in parallel.
- **Phone sessions prepared.** A stage that needs the owner at the phone sends them its list first: every step in order, what to look for, and what the scripts have checked already, so their time goes only on what needs them (`docs/plan_v4/14-your-part.md`).
- **Broad reading in a subagent.** A sweep across many files goes to an Explore subagent, keeping this session's context small and its compactions few.

## Reviewing a pull request in GitHub Actions

This section is for the reviewer that `.github/workflows/claude.yml` runs on an `@claude` comment; other sessions can leave it aside.

- **What the PR is.** Every PR is a release (`X.Y.Z: what it does`, the version raised in `app/build.gradle.kts`) or, now and then, docs or tooling alone. Its description says what and why and, for the plan's work, which stage and whether it's a cleanup or a feature. Check that it does what it says, and nothing it doesn't.
- **Only its own changes.** PRs are often stacked, each on the one before, with another release's branch as its base. Review this PR's own diff: `gh pr diff <n>`, or `git diff <base>...HEAD` with the base `gh pr view <n> --json baseRefName` names.
- **Whole files.** Read every changed file in full, with the code that calls what changed, not only the diff's hunks.
- **Moves.** A cleanup moves code first and improves it after, a commit each. A moved piece must do what it did: compare it with where it came from, a commit at a time. A behaviour change inside a move is a finding.
- **Found, not in this PR.** Something wrong that the PR doesn't touch goes under its own heading: the plan fixes such finds in a PR of their own, never inside a move.
- **What you can do here.** Read the repository and its history; `gh pr`, `git diff`, `git log`, `git blame`. You don't build the app or run its tests: the workflow's tools don't include Gradle, and CI's `build` check already runs `./gradlew testDebugUnitTest lintDebug assembleDebug` on every push. Read its result (`gh pr checks <n>`, or the CI tools the workflow gives you) rather than guess.
- **What you don't do.** Push, approve, request changes or merge: the owner does those. Run anything that calls Claude's API.
- **How you report.** Findings ranked, the most serious first, each **Major** (a bug, data lost, a privacy leak, a build or test broken) or **Minor**: the file and line, what's wrong, a concrete case where it goes wrong, and a fix. Each line-specific finding is an inline comment of its own, as each is answered and resolved on its own; then one summary comment listing them all, what you read, and what you didn't check. Say how sure you are of a finding you aren't certain of, and what would settle it. Leave alone style the code already uses throughout.
