# Decrastination

An Android app that gathers everything outstanding from Teams assignments, Power Planner, the Gmail inbox and AnkiDroid, shows the single next thing to do on a home-screen widget, and, while work is due today or tomorrow, replaces procrastination apps with that instruction until the work's own source says it's done.

A personal, sideloaded app for one phone (a Samsung Galaxy S24 on Android 16).

## Status

**Phase 0, proving each data path, is done** (8 Oct 2026): all four sources can be read, a blocklisted app can be covered, and the app's own Settings pages can be guarded. What it found is in [`docs/phase0-findings.md`](docs/phase0-findings.md). The app in `app/` is so far only the Phase 0 probes; Phase 1 (sources, storage, sync) builds on it. [`PLAN.md`](PLAN.md) has the whole plan.

## Documents

| | |
|---|---|
| [`PLAN.md`](PLAN.md) | What the app does, the decisions and why, architecture, phases |
| [`docs/data-sources.md`](docs/data-sources.md) | How each source is read: endpoints, providers, columns, verified facts |
| [`docs/scheduler.md`](docs/scheduler.md) | Day buckets, the block policy, anti-tamper, learning |
| [`docs/phase0-findings.md`](docs/phase0-findings.md) | What proving each path found |
| [`docs/open-questions.md`](docs/open-questions.md) | Decisions made, and the defaults still open |

## Build and install

Same toolchain as the [Teams Assignments widget](https://github.com/ThomasWCode/TeamsAssignmentsWidget): Gradle 9.3.1, AGP 9.0.1 with built-in Kotlin 2.2.10, JDK 17, `minSdk` 26, `targetSdk` 36.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew installDebug
```

Debug builds are signed with a copy of the widget's committed `app/debug.keystore`. Sharing its key is what grants this app the widget's signature-level permission to read the assignments, so don't change it.

## The Phase 0 probes

The app opens on a probe screen; each probe also runs from a PC, logging to `adb logcat -s Decrastination`:

```bash
adb shell am start -n com.thomaswcode.decrastination/.probe.ProbeActivity --es probe teams   # or anki, sync, open, status
```

The focus service, a spike of the blocker, blocks YouTube whenever it's switched on, so it's off except while testing. Power Planner and Gmail are checked from the PC, with `scripts/powerplanner_probe.py` and `scripts/gmail_probe.py`; their `--save` output goes to the git-ignored `private/`.
