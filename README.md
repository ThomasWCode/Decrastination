# Decrastination

An Android app that gathers everything outstanding (Teams assignments, Power Planner, starred Gmail conversations, AnkiDroid decks and tasks of your own), plans it into the days before each is due as timed slots, writes them to your calendar, shows the next thing to do on a home-screen widget, and, while work is due, replaces distracting apps with that next thing until it's done. Claude reads the work into steps and estimates, and Felix, a persona you chat with, makes changes and messages you about the day. The app opens on that chat, its home; Day, Work and Stats sit behind its Plan button.

A personal, sideloaded app for one phone, version 4.9.6.

## Documents

| | |
|---|---|
| [`docs/spec/`](docs/spec/) | How the app works now, an area a file: sources, Claude's readings, the planner, slots and the calendar, the Day, blocking and protection, Felix (and everything he's sent), learning, storage and backups, privacy; and the requirement ids the code cites |
| [`docs/decisions.md`](docs/decisions.md) | The owner's decisions that govern how the app is developed, numbered |
| [`docs/development.md`](docs/development.md) | How changes are made, reviewed, checked on the phone and shipped; the public repository's rules |
| [`docs/plan_v4/`](docs/plan_v4/README.md) | The 4.x plan's record (done, Oct 2026): what was built release by release and what's left to check; the structure the code has and its conventions; the stores and their migrations; the owner's answers to the plan's questions and to those asked since, from the timer (4.4.0) to Felix's moods (4.7.0), his speech (4.8.0, the speech review's plan folded in) and the chat as the app's home (4.9.0, its plan folded in) |
| [`docs/plan_web/`](docs/plan_web/README.md) | The website's plan (31 Oct 2026, not yet built): a second full client on shared state held by a Kotlin server, the phone keeping its device duties and working offline; the decisions, the architecture, the data and sync, the hosting with its evidence, and the stages |
| [`docs/astra-review-4.3.13/`](docs/astra-review-4.3.13/README.md) | An outside review of 4.3.13 (23 Oct 2026), each finding with what became of it |
| [`fixtures/README.md`](fixtures/README.md) | The test fixtures and where they came from |

## Build and install

Same toolchain as the [Teams Assignments widget](https://github.com/ThomasWCode/TeamsAssignmentsWidget): Gradle 9.3.1, AGP 9.0.1 with built-in Kotlin 2.2.10, JDK 17, `minSdk` 26, `targetSdk` 36.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install over the app with `-r`, never uninstalling it, and keep a backup first ([development.md](docs/development.md#the-phone)).

Every prompt and schema Claude is sent has a golden copy in `app/src/test/resources/prompts/`, but the web search's prompt, which `FelixInputsDocTest` holds in [`docs/spec/felix-inputs.md`](docs/spec/felix-inputs.md); the tests fail on any difference, with its diff. When a change is meant, write them again and commit them with it; where a reading's prompt or the profile changed, raise `ReadingPrompts.VERSION` too (a test asks for it), so the readings made by the old ones are made again:

```bash
./gradlew testDebugUnitTest -PupdateGolden=true --tests '*GoldenPromptsTest*'
```

The API compiles every schema into a grammar and refuses one too large, which no test can tell from the schema alone. After any change to a schema, have it compile them all before merging (the week's summary's, one plain string, isn't among them; one call for each schema, about a cent each; a refused one costs nothing; the key from the environment):

```bash
./gradlew testDebugUnitTest --tests '*SchemaCompileTest*' -PcheckSchemas=true
```

Debug builds are signed with a copy of the widget's committed `app/debug.keystore`. Sharing its key is what grants this app the widget's signature-level permission to read the assignments, so don't change it.

## Credentials and commands from a PC

The Power Planner login and the Gmail app password can be typed into Setup, or loaded from this PC's user environment (`DECRASTINATION_PP_USERNAME`, `DECRASTINATION_PP_PASSWORD`, `DECRASTINATION_GMAIL_APP_PASSWORD`) without being shown:

```bash
python scripts/load_credentials.py --gmail-address <the account's address>
```

It pipes them into the app's private storage with `adb exec-in run-as` (debug builds only), and the app moves them into its encrypted store and deletes the file. Other commands, also adb-only, log to `adb logcat -s Decrastination`:

```bash
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd sync     # optionally --es sources gmail,anki
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd state    # each source's status, every task
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd enrich   # Claude reads what's new now (real calls)
```

[`docs/spec/storage-and-backups.md`](docs/spec/storage-and-backups.md) lists the rest. On a debug build the menu's *Debug* page shows the model's recent calls, a task's prompt as it would be sent, the chat's context, Felix's state, his notes and reminders, the stores, each source's last read and the watchdog's findings, each with Copy.

## Backups

Every night the app saves all it keeps to `Documents/Decrastination` on the phone as `decrastination-<date>.json`, the newest fortnight kept (one too big for Import to read back isn't written, nor older ones let go for it); Settings → Backup says when it last did, and Import reads one back. A Reset saves `reset-<date>-<time>.json` there first, never pruned (and, where the chat goes and that copy has no room for its earliest messages, `chat-archive-<date>-<time>.json` beside it). Keeping a copy off the phone is yours to do.

## Setting up the blocker

The focus service is an accessibility service; a sideloaded app's can only be switched on in Settings after "Allow restricted settings", or from a PC:

```bash
adb shell settings put secure enabled_accessibility_services "$(adb shell settings get secure enabled_accessibility_services):com.thomaswcode.decrastination/com.thomaswcode.decrastination.block.FocusService"
adb shell pm grant com.thomaswcode.decrastination android.permission.WRITE_SECURE_SETTINGS   # self-repair, once armed
adb shell pm grant com.thomaswcode.decrastination android.permission.POST_NOTIFICATIONS
```

## Probes

Power Planner and Gmail can be checked from the PC with `scripts/powerplanner_probe.py` and `scripts/gmail_probe.py`, and the Teams widget app's store pulled with `scripts/pull_teams_state.ps1`; their output goes to the git-ignored `private/`.
