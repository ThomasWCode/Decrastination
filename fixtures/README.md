# Fixtures

Real snapshots taken from the phone on 7 October 2026 at about 22:00 BST. They are the inputs for the planner and source-parser unit tests, so the tests exercise the actual shapes and the actual edge cases (five overdue assignments, a deadline at 23:59 tonight, decks with only new cards).

| File | What | How it was captured |
|---|---|---|
| `teams_widget_state.json` | The Teams widget's full store: 11 open assignments, handed-in memory, class colours | `adb shell run-as com.teamsassignments.widget cat files/widget_state.json`. Since 1.7.0 `scripts/pull_teams_state.ps1` pulls a fresh one into the git-ignored `private/` and checks the widget's signing key; copy it here by hand if a test needs the new shape |
| `anki_decks.json` | Deck names and new/learn/review counts visible in the AnkiDroid deck picker | `uiautomator dump` of the deck picker, parsed |
| `powerplanner_agenda_ui.xml` | UI tree of Power Planner's Agenda screen (two items) | `uiautomator dump`; superseded by `powerplanner_agenda.json` |
| `powerplanner_agenda.json` | The web API's agenda (both items), the selected semester, and the German class's timetable, from 8 Oct 2026 | `scripts/powerplanner_probe.py --save`, then redacted for this public repo: the personal task renamed, 17 of the 18 classes and every room left out |
| `home_page2_ui.xml` | UI tree of the second home page with the four widgets | `uiautomator dump`; reference only |

There is deliberately no Gmail fixture: the inbox holds other people's messages, and this repository is public. `scripts/gmail_probe.py --save` writes the real one to the git-ignored `private/`; tests use made-up messages in the shapes `docs/phase0-findings.md` §4 records.

Times in `teams_widget_state.json` are epoch milliseconds; `lastSuccessAt` 1791351744439 is 06:42 BST on 7 Oct 2026. Tests should pin the clock to `2026-10-07T21:00:00+01:00` so "overdue", "due tonight" and "due tomorrow" buckets are stable.
