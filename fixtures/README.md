# Fixtures

Snapshots in the shapes the phone gave on 7 October 2026 at about 22:00 BST, with everything personal made up or left out, as this repository is public (D-79). They are the inputs for the planner and source-parser unit tests, so the tests exercise the actual shapes and the actual edge cases (five overdue assignments, a deadline at 23:59 tonight, decks with only new cards).

| File | What | How it was captured |
|---|---|---|
| `teams_widget_state.json` | The Teams widget's full store: 11 open assignments, handed-in memory, class colours; its titles, class names, descriptions and ids made up, its times as they were | `adb shell run-as com.teamsassignments.widget cat files/widget_state.json`. `scripts/pull_teams_state.ps1` pulls a fresh one into the git-ignored `private/` and checks the widget's signing key; copy it here by hand if a test needs the new shape, its titles, class names, descriptions and ids made up first |
| `anki_decks.json` | Deck names and new/learn/review counts visible in the AnkiDroid deck picker | `uiautomator dump` of the deck picker, parsed |
| `powerplanner_agenda.json` | The web API's agenda (both items), the selected semester, and the German class's timetable, from 8 Oct 2026 | `scripts/powerplanner_probe.py --save`, then redacted for this public repo: the personal task renamed, 17 of the 18 classes and every room left out |

There is deliberately no Gmail fixture: the inbox holds other people's messages, and this repository is public. `scripts/gmail_probe.py --save` writes the real one to the git-ignored `private/`; tests use made-up messages in the shapes [`docs/spec/sources.md`](../docs/spec/sources.md) describes. New fixtures are made up, never taken from the phone (`uiautomator dump`, used for the decks' one, is no longer run on the phone: it unbinds its accessibility services).

Times in `teams_widget_state.json` are epoch milliseconds; `lastSuccessAt` 1791351744439 is 06:42 BST on 7 Oct 2026. Tests should pin the clock to `2026-10-07T21:00:00+01:00` so "overdue", "due tonight" and "due tomorrow" buckets are stable.

## Made up: the stores and the plan's snapshot

| Folder | What | Tested by |
|---|---|---|
| `stores/<date>/` | Every store the app keeps (`tasks.json`, `settings.json`, `runtime.json`, `log.json`, `instructions.json`, `chat.json`, `chat-archive.json` (from 2026-10-19, the one file 4.0.4 splits into months at start), `school.json`, `memory.json`, `calendar.json`, and from 2026-10-20 the runtime's split: `session.json`, `protection.json`, `model.json`, `anki.json`, `learning.json`) in its shape on that date, every field of every type filled somewhere, the fields kept only so older files read among them. A folder a shape: every migration is tested from every one (docs/plan_v4/04-data-and-migrations.md) | `StoresDecodeTest` |
| `snapshots/plan.txt` | A fixed state's plan as text, to the piece and the minute: any change to how the app plans shows there. Written again only on purpose, `./gradlew testDebugUnitTest --tests '*PlanSnapshotTest*' -PupdateSnapshots=true` | `PlanSnapshotTest` |

Both are made up: no task, event, person or address from the phone. The phone's own data is rehearsed in the git-ignored `private/` (`scripts/rehearse.sh`), never committed.
