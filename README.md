# Decrastination

An Android app that gathers everything outstanding from Teams assignments, Power Planner, the Gmail inbox and AnkiDroid, shows the single next thing to do on a home-screen widget, and, while work is due today or tomorrow, replaces procrastination apps with that instruction until the work's own source says it's done.

A personal, sideloaded app for one phone (a Samsung Galaxy S24 on Android 16).

## Status

**Phase 0, proving each data path, is done** (8 Oct 2026): all four sources can be read, a blocklisted app can be covered, and the app's own Settings pages can be guarded. What it found is in [`docs/phase0-findings.md`](docs/phase0-findings.md).

**Phase 1, sources, storage and sync** (8–9 Oct): the app reads all four sources into one task list every 15 minutes, and shows every task and how each source's last read went, with a setup checklist. **Phase 2, the planner and widget** (9 Oct): the work is planned backwards from each deadline into day buckets; the app opens on the plan, and the *Next task* widget shows the single next thing to do, at any size from 2×1 up. **Phase 3, the blocker** (9 Oct): while anything is due today or tomorrow, from 16:45 on school days and 07:00 at weekends until 22:30, blocked apps and sites show the block screen instead; focus sessions, earned free time, automatic Teams syncs, and anti-tamper protection that's built but left for you to arm. **Phase 4, enrichment and settings** (9 Oct): the rules split listed assignments into steps; Claude for email triage, assignment steps and estimates is built but off until you give it a key and switch it on, under a monthly cap; and a Settings screen edits everything. **Phase 5, learning and review** (9 Oct): the app learns your estimates, margins and box lengths from what you finish, asks "how was it?" after each completion, briefs you each morning, counts your calendar's events as busy time (asking about the ones it can't judge), and reviews the week on Sunday evening after a five-question check-in; the model's review and the photo check wait for Claude. **Phase 6, polish** (9 Oct): a Stats tab, and backup and restore of the settings, the log and what the app has learned; this is **version 1.0.0**. **1.1.0** (9 Oct): the AI can split any task into blocks with their own dates (Q12): an email holding a calendar of deadlines becomes its pieces of work, each planned in its own window. **1.1.1** (9 Oct): long emails are read to their end (up to 20 000 characters, not 4 000), so a calendar of deadlines late in one isn't missed, and an email's blocks can come to 20 hours, as months of applications do. [`PLAN.md`](PLAN.md) has the whole plan; [`docs/needs-you.md`](docs/needs-you.md) lists what's waiting for you.

## Documents

| | |
|---|---|
| [`PLAN.md`](PLAN.md) | What the app does, the decisions and why, architecture, phases |
| [`docs/data-sources.md`](docs/data-sources.md) | How each source is read: endpoints, providers, columns, verified facts |
| [`docs/scheduler.md`](docs/scheduler.md) | Day buckets, the block policy, anti-tamper, learning |
| [`docs/phase0-findings.md`](docs/phase0-findings.md) | What proving each path found |
| [`docs/open-questions.md`](docs/open-questions.md) | Decisions made, and the defaults still open |
| [`docs/needs-you.md`](docs/needs-you.md) | What the unattended v1 build left for you to decide or do |

## Build and install

Same toolchain as the [Teams Assignments widget](https://github.com/ThomasWCode/TeamsAssignmentsWidget): Gradle 9.3.1, AGP 9.0.1 with built-in Kotlin 2.2.10, JDK 17, `minSdk` 26, `targetSdk` 36.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew installDebug
```

Debug builds are signed with a copy of the widget's committed `app/debug.keystore`. Sharing its key is what grants this app the widget's signature-level permission to read the assignments, so don't change it.

## Credentials and commands from a PC

The Power Planner login and the Gmail app password can be typed into Setup, or loaded from this PC's user environment (`DECRASTINATION_PP_USERNAME`, `DECRASTINATION_PP_PASSWORD`, `DECRASTINATION_GMAIL_APP_PASSWORD`) without being shown:

```bash
python scripts/load_credentials.py --gmail-address you@gmail.com
```

It pipes them into the app's private storage with `adb exec-in run-as` (debug builds only), and the app moves them into its encrypted store and deletes the file. Other commands, also adb-only, log to `adb logcat -s Decrastination`:

```bash
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd sync     # optionally --es sources gmail,anki
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd state    # each source's status, every task
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd force-block --ei minutes 10   # blocking hours now, for testing
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd protection   # run the watchdog
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd enrich       # enrich what's new now
```

`CommandActivity` lists the rest, including test hooks that arm and disarm at once and a `clean-up` after testing.

## Setting up the blocker

The focus service is an accessibility service; a sideloaded app's can only be switched on in Settings after "Allow restricted settings", or from a PC:

```bash
adb shell settings put secure enabled_accessibility_services "$(adb shell settings get secure enabled_accessibility_services):com.thomaswcode.decrastination/com.thomaswcode.decrastination.block.FocusService"
adb shell pm grant com.thomaswcode.decrastination android.permission.WRITE_SECURE_SETTINGS   # self-repair, once armed (Q19)
adb shell pm grant com.thomaswcode.decrastination android.permission.POST_NOTIFICATIONS
```

## Phase 0

The data-path probes of 8 Oct (`docs/phase0-findings.md`) were retired in Phase 3, when the real blocker replaced their spike. Power Planner and Gmail can still be checked from the PC with `scripts/powerplanner_probe.py` and `scripts/gmail_probe.py`; their `--save` output goes to the git-ignored `private/`. A test that opens YouTube or another blocked app ends with `adb shell am force-stop <package>`, and with `adb shell dumpsys window windows | grep -c "mWindowingMode=pinned"` printing 0.
