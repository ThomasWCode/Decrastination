# Prompt for the implementing session

Paste what follows the rule into the new session. Edit the bracketed parts first.

---

You are implementing review v2 of Decrastination, an Android app (Kotlin, Compose, Material 3) that plans a sixth-form student's homework and blocks distracting apps until it's done. I'm the student and the developer. You have the repository checked out locally with every branch, and full control of my phone over adb.

## Where everything is

- The plan: `docs/review_v2_improvements/impl/README.md` on branch `ccr-ddd35a13-8tl04l`. Read it first, then `impl/00-conventions.md`, then the phase file you're on. The phase files state each change, the files and types to touch, the signatures, the prompt text, the tests and when the item is done. Follow them; they're written for you.
- The reasons behind every item: `docs/review_v2_improvements/01-…09-*.md`, the walkthrough `PLAN-OVERVIEW.md`, and `QUESTIONS.md`, where every decision was made over five rounds. Don't reopen a decision recorded there; don't implement anything marked Withdrawn or Deferred.
- The app's own docs: `README.md`, `docs/scheduler.md`, `docs/data-sources.md`, `docs/eval.md`, `PLAN.md`. They describe the app as it is today; the conventions file says which sections you must update as you go.
- `private/` is git-ignored real data (credentials, the eval set). Use it; never commit it; never print a credential.

## Git

1. Branch `v3` from `main`, merge `ccr-ddd35a13-8tl04l` into it (it carries only docs), and work on `v3`.
2. One commit per item or small group, message in the imperative with the item ids first (`Spread later work evenly to its deadline (PL-01, PL-07)`). No attribution footers, co-author trailers or session links, ever.
3. At the end of each phase: `./gradlew testDebugUnitTest lintDebug assembleDebug` green, the phase's "Done when" checks passed on the phone, the docs updated, then push `v3` and open a pull request for the phase against `main` titled `v3 phase N: <what it delivers>`. [Merge it yourself when CI is green and the phone checks pass / Wait for me to merge.]
4. Keep `PROGRESS.md` at the repository root current (done, current, outstanding, decisions made); delete it with the last phase.

## Order

Phases 1 to 8 in order; 6 and 7 may be swapped. Don't start a phase before the one before it is green and on the phone. Within a phase, the order in the file.

## The phone

- Samsung Galaxy S24, One UI, my daily phone with my real accounts in the app: the Teams widget (`com.teamsassignments.widget`, same debug signature as this app), Power Planner, AnkiDroid, my Gmail with an app password, and my Claude API key, all stored in the app's encrypted secret store. The app is installed from a debug build signed with the committed `app/debug.keystore`; never regenerate that keystore.
- Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk` so the data stays. Before the first install of each phase, keep the previous APK (`cp` it to `private/apk/<date>-<phase>.apk`) so you can roll back, and export a backup through the app's own export (Settings/Setup → Backup, or the `state` debug command; see `debug/CommandActivity.kt` for how debug commands are sent with `adb shell am start`). Check `data/JsonStore.kt`'s JSON configuration before relying on a rollback: an old build must tolerate fields a new one wrote.
- Protection: the app is a device admin with an accessibility service and `WRITE_SECURE_SETTINGS` (granted by `adb shell pm grant`). A reinstall over the top keeps them; a fresh install doesn't. Never uninstall. If protection ends up disarmed by your work, re-arm it before you stop (Settings → Blocking and protection, or the `test-arm` command) and tell me.
- You can drive the screen (`adb shell input`, `uiautomator dump`, `adb exec-out screencap -p`), read notifications (`adb shell dumpsys notification --noredact`), and read the log (`adb logcat -s <the tag in AppGraph.TAG>`). Use them to do each item's "Done when" checks for real, not just the unit tests. Take a screenshot of each new screen in light and dark and put them in `private/screens/`.
- I use the phone every day. Don't leave a half-done phase installed; install only a build whose tests pass; do installs and anything that changes the plan on the phone [in the evening after 22:30 / whenever]. Blocking is live: if a change of yours blocks something it shouldn't, fix it the same session.
- Model calls on the phone cost real money from my key. That's fine for testing; don't loop. The eval task (AR-02) needs the key as an environment variable on your machine: ask me for it when you get there.
- Phase 3 writes to a Google Calendar that I must create myself at calendar.google.com (an app-created calendar doesn't sync). [I've created "Decrastination" / Tell me when you reach NF-07 and I'll create it.] The app then lets me pick it in Settings.

## How to work

- Read the whole file you're changing before you change it, and the tests that cover it. The code's style and comments are deliberate; match them (the conventions file says how).
- Pure code first, tested on the JVM; Android glue thin. Keep the planner pure.
- Where a phase file says "decide", decide, do it, and write the decision in `PROGRESS.md`. Ask me only when a decision would change what I see or could lose my data, and then ask one specific question with your recommendation.
- If something in the plan is wrong against the code as it is (a function that doesn't exist, a type that changed), fix the plan in place in the `impl/` file in the same commit, with a one-line note of what you found, and carry on. Don't drift silently.
- Don't widen the work: no refactors the plan doesn't ask for, no new dependencies the plan doesn't name, no features from the withdrawn list.
- Report after each phase: what's in, what you verified on the phone and how, what you decided, what you left for me. Short, factual, no spin.

Start by reading `impl/README.md`, `impl/00-conventions.md` and `impl/phase-1-tests-and-email.md`, create `v3`, write the first `PROGRESS.md`, and begin phase 1.
