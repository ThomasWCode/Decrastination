# Decrastination

An Android app that gathers everything outstanding from Teams assignments, Power Planner, the Gmail inbox and AnkiDroid, shows the single next thing to do on a home-screen widget, and, while work is due today or tomorrow, replaces procrastination apps with that instruction until the work's own source says it's done.

A personal, sideloaded app for one phone (a Samsung Galaxy S24 on Android 16).

## Status

**Phase 0, proving each data path, is done** (8 Oct 2026): all four sources can be read, a blocklisted app can be covered, and the app's own Settings pages can be guarded. What it found is in [`docs/phase0-findings.md`](docs/phase0-findings.md).

**Phase 1, sources, storage and sync** (8–9 Oct): the app reads all four sources into one task list every 15 minutes, and shows every task and how each source's last read went, with a setup checklist. **Phase 2, the planner and widget** (9 Oct): the work is planned backwards from each deadline into day buckets; the app opens on the plan, and the *Next task* widget shows the single next thing to do, at any size from 2×1 up. [`PLAN.md`](PLAN.md) has the whole plan; [`docs/needs-you.md`](docs/needs-you.md) lists what's waiting for you.

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
```

## The Phase 0 probes

Setup → *Phase 0 probes* opens them; each probe also runs from a PC, logging to `adb logcat -s Decrastination`:

```bash
adb shell am start -n com.thomaswcode.decrastination/.probe.ProbeCommand --es probe teams   # or anki, sync, open, status
```

`ProbeCommand` is an alias of the probe screen that only the adb shell can start (it needs `android.permission.DUMP`), so no other app can make this one sync or open Teams; a probe sent to the launcher's entry is ignored.

The focus service, a spike of the blocker, blocks YouTube whenever it's switched on, so it's off except while testing. A test that opens YouTube ends with `adb shell am force-stop com.google.android.youtube`, and with `adb shell dumpsys window windows | grep -c "mWindowingMode=pinned"` printing 0: nothing may be left playing in a picture-in-picture window. Power Planner and Gmail are checked from the PC, with `scripts/powerplanner_probe.py` and `scripts/gmail_probe.py`; their `--save` output goes to the git-ignored `private/`.
