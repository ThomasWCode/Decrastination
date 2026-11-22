# Decrastination

An Android app that gathers everything outstanding (Teams assignments, Power Planner, starred Gmail conversations, AnkiDroid decks and tasks of your own), plans it into the days before each is due as timed slots, writes them to your calendar, shows the next thing to do on a home-screen widget, and, while work is due, replaces distracting apps with that next thing until it's done. Claude reads the work into steps and estimates, and Felix, a persona you chat with, makes changes and messages you about the day. The app opens on that chat, its home; Day, Work and Stats sit behind its Plan button.

Since 4 Nov 2026 the state is held by a Kotlin server (`server/`, Ktor and SQLite, on one box). The phone and the website (`web/`, React) send each change as a command and take back what came of it. The server reads Power Planner and Gmail, asks Claude and writes Felix's messages; the phone reads Teams and AnkiDroid, writes the calendar, blocks, and goes on working offline from its own copy ([`server.md`](docs/spec/server.md), [`sync.md`](docs/spec/sync.md), [`website.md`](docs/spec/website.md), [`hosting.md`](docs/spec/hosting.md)).

A personal, sideloaded app for one phone, with its own server and website; its version is `versionName` in `app/build.gradle.kts`.

## Documents

| | |
|---|---|
| [`docs/spec/`](docs/spec/) | How the app works now, an area a file: sources, Claude's readings, the planner, slots and the calendar, the Day, blocking and protection, Felix (and everything he's sent), learning, storage and backups, privacy, the server, how the phone and the server talk, the website, the hosting; and the requirement ids the code cites |
| [`docs/decisions.md`](docs/decisions.md) | The owner's decisions that govern how the app is developed, numbered |
| [`docs/development.md`](docs/development.md) | How changes are made, reviewed, checked on the phone and shipped; the public repository's rules |
| [`docs/plan_v4/`](docs/plan_v4/README.md) | The 4.x plan's record (done, Oct 2026): what was built release by release and what's left to check; the structure the code has and its conventions; the stores and their migrations; every question put to the owner, with their answer and the choices made in building, a section a release (4.4.0 to 4.11.0, 6.3.0 to 6.12.0) |
| [`docs/plan_web/`](docs/plan_web/README.md) | The website's plan's record (31 Oct 2026; built 4.12.0 to 5.11.0, switched on 4 Nov 2026): what it set out to do, what was built otherwise than planned, its W-numbers and questions (Q129 to Q145), and what's still open. How it works is in `docs/spec/` |
| [`docs/astra-review-4.3.13/`](docs/astra-review-4.3.13/README.md) | An outside review of 4.3.13 (23 Oct 2026), each finding with what became of it |
| [`docs/astra-review-29-10-26/`](docs/astra-review-29-10-26/README.md) | The review of 4.9.1 (29 Oct 2026), its 53 findings with what became of each |
| [`docs/felix-feedback/`](docs/felix-feedback/README.md) | Felix's rated messages, read to tune his prompts |
| [`fixtures/README.md`](fixtures/README.md) | The test fixtures and where they came from |

## Build and install

Same toolchain as the [Teams Assignments widget](https://github.com/ThomasWCode/TeamsAssignmentsWidget): Gradle 9.3.1, AGP 9.0.1 with built-in Kotlin 2.2.10, JDK 17, `minSdk` 26, `targetSdk` 36.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug :server:buildFatJar
(cd web && npm ci && npx playwright install chromium && npm run lint && npm run test:unit && npm run build && npm run test:e2e)   # Node 24
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install over the app with `-r`, never uninstalling it, and keep a backup first ([development.md](docs/development.md#the-phone)). A build for the phone is made with the Firebase project's `app/google-services.json`, which is kept out of git (D-145): without it the app builds all the same, and hears of the server's changes at its 15-minute sync.

Every prompt and schema Claude is sent has a golden copy in `engine/src/test/resources/prompts/`, but the web search's prompt, which `FelixInputsDocTest` holds in [`docs/spec/felix-inputs.md`](docs/spec/felix-inputs.md); the tests fail on any difference, with its diff. When a change is meant, write them again and commit them with it; where a reading's prompt or the profile changed, raise `ReadingPrompts.VERSION` too (a test asks for it), so the readings made by the old ones are made again:

```bash
./gradlew :engine:test -PupdateGolden=true --tests '*GoldenPromptsTest*'
```

The API compiles every schema into a grammar and refuses one too large, which no test can tell from the schema alone. After any change to a schema, have it compile them all before merging (the week's summary's, one plain string, isn't among them; one call for each schema, about a cent each; a refused one costs nothing; the key from the environment):

```bash
./gradlew :engine:test --tests '*SchemaCompileTest*' -PcheckSchemas=true
```

Debug builds are signed with a copy of the widget's committed `app/debug.keystore`. Sharing its key is what grants this app the widget's signature-level permission to read the assignments, so don't change it.

## Credentials and commands from a PC

Since the switch the server reads Power Planner and Gmail and asks Claude, with sign-ins entered on the website's Settings and its own key (D-133, D-142). On a phone that isn't paired, or still mirrors, they're the phone's own: the Power Planner login and the Gmail app password can be typed into Setup, or loaded from this PC's user environment (`DECRASTINATION_PP_USERNAME`, `DECRASTINATION_PP_PASSWORD`, `DECRASTINATION_GMAIL_APP_PASSWORD`) without being shown:

```bash
python scripts/load_credentials.py --gmail-address <the account's address>
```

It pipes them into the app's private storage with `adb exec-in run-as` (debug builds only), and the app moves them into its encrypted store and deletes the file. Other commands, also adb-only, log to `adb logcat -s Decrastination`:

```bash
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd sync     # optionally --es sources teams,anki
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd state    # each source's status, every task
adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd enrich   # Claude reads what's new now (real calls; nothing once the phone sends commands, as the server reads)
```

[`docs/spec/storage-and-backups.md`](docs/spec/storage-and-backups.md) lists the rest. On a debug build the menu's *Debug* page shows the model's recent calls, a task's prompt as it would be sent, the chat's context, Felix's state, his notes and reminders, the stores, each source's last read and the watchdog's findings, each with Copy.

## Backups

Every night the app saves all it keeps to `Documents/Decrastination` on the phone as `decrastination-<date>.json`, the newest fortnight kept (one too big for Import to read back isn't written, nor older ones let go for it); Settings → Backup says when it last did, and Import reads one back. A Reset saves `reset-<date>-<time>.json` there first, never pruned (and, where the chat goes and that copy has no room for its earliest messages, `chat-archive-<date>-<time>.json` beside it). Keeping a copy of these files off the phone is yours to do; the server keeps its own copies ([`hosting.md`](docs/spec/hosting.md) §The copies).

## Setting up the blocker

The focus service is an accessibility service; a sideloaded app's can only be switched on in Settings after "Allow restricted settings", or from a PC:

```bash
adb shell settings put secure enabled_accessibility_services "$(adb shell settings get secure enabled_accessibility_services):com.thomaswcode.decrastination/com.thomaswcode.decrastination.block.FocusService"
adb shell pm grant com.thomaswcode.decrastination android.permission.WRITE_SECURE_SETTINGS   # self-repair, once armed
adb shell pm grant com.thomaswcode.decrastination android.permission.POST_NOTIFICATIONS
```

## Probes

Power Planner and Gmail can be checked from the PC with `scripts/powerplanner_probe.py` and `scripts/gmail_probe.py`, and the Teams widget app's store pulled with `scripts/pull_teams_state.ps1`; their output goes to the git-ignored `private/`.
