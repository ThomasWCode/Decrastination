# Scripts and local helpers

Fifteen findings, all standing; three narrower than written (T-02, T-12, T-15), one smaller in effect than stated (T-10). Three scripts are committed (`scripts/`); the twelve helpers are the git-ignored ones on this PC (`private/tools/`, `private/eval-chat/`, `private/felix-speech-review/`, `private/eval/`), reviewed from byte-identical copies. Ids: max PT-/EV-, ultra WT-. The two probes of the committed scripts are in [evidence/](evidence/) and assert the behaviour seen; the probes of the private helpers, and the copies they run, are in `evidence/private/`, git-ignored, on this PC only.

The rubric was applied to helpers as to the app: P1 destroys a backup, a fixture or data; P2 gives a wrong result you'd act on, or crashes instead of one; P3 is docs. Several P2s here are low in practice, and say so.

### T-01 · P1 · `keep_before.sh` truncates an existing recovery archive before adb has read anything

Max PT-01. Source-traced. `private/tools/keep_before.sh:12` checks only that the APK name is new; `:16` redirects adb's tar into `$OUT/data.tar`, which the shell truncates first. `keep_data.sh:7` has the guard this lacks. The likely trigger is a retry: a first run that fails at the data step has written the APK, a rerun with the same names exits 3, so a new APK name is picked and the backup name kept, which empties the old archive. Refuse an existing destination, write to a temporary name, move into place.

### T-02 · P1 · `clean_test_chat.py --apply` reverts every note and reminder changed during the test

Max PT-02. Reproduced: `test_private_helpers.py` "cleanup keeps real messages and notes" fails (`messages=0, notes=0`). `:50` selects messages by id and time alone; `:64` pushes the whole of `felix.json` back from the snapshot, so a note or reminder added, or a reminder marked sent, since the snapshot is reverted, and a sent reminder fires again. Narrower than written: the dry run prints every message it will remove (`:51-52`), so a real message caught by the cutoff is visible before `--apply`; the silent part is `felix.json`, whose diff the dry run never shows (`:55-59`). Track the test's own ids and restore only their fields; show a `felix.json` diff; refuse when it can't separate the two.

### T-03 · P1 · `pull_events.py` overwrites the calendar fixture with an empty list when adb fails

Max EV-01. Reproduced: `test_private_eval_tools.py` "failed calendar read preserves the previous evaluation fixture" fails. `private/eval-chat/pull_events.py:29-32` ignores adb's exit status and stderr; `:90` writes `events.json` unconditionally; a disconnected phone gives `[]`. The last line does say "0 events kept of 0 rows", and the file can be pulled again, though not as the calendar was. Require a successful read before replacing the file.

### T-04 · P2 · `scripts/gmail_probe.py` carries a real account address as its default

Ultra WT-03; max's documentation pass had rejected it as grandfathered by D-80. `scripts/gmail_probe.py:43` sets `DEFAULT_ADDRESS` to an address outside the example domains, used at `:170` whenever the app password comes from the environment. Added on 8 Oct (e44873a7), unchanged since; public in the history since then. D-80 (17 Oct) grandfathers an enumerated list (fixtures' teacher and class names, a class link, real email and homework text, a first name in named files) that names no email address and no script, so D-79 and `development.md:9` ("no email addresses") apply and max's exemption is wider than D-80's words. `scan-public.sh` scans added lines only, so it never flagged this. The address is quoted nowhere in this folder. Your call (29 Oct): kept as pre-existing material, D-80 amended to name it; a history rewrite would break every clone, need the author-check ruleset bypassed and leave GitHub's caches, for an address public since 8 Oct.

### T-05 · P2 · `watch_claude.py` dies when the comments need a second page

Max PT-03 (P1). Reproduced: `test_private_helpers.py` "claude watcher reads more than one page" fails. `private/tools/watch_claude.py:26,31` pass `gh api --paginate` output, one JSON array per page, to `json.loads`; `watch_pr.py:29-39` decodes consecutive arrays. At the REST default of 30 a page, a PR past 30 issue comments kills the watcher on every poll, before Claude has finished. Also `gh()` (`:18-20`) returns "" on any failure, so an API error reads as "no answer yet". Use `--slurp` or share `watch_pr`'s decoder. P2: a helper exit wakes the session without the review; nothing lost.

### T-06 · P2 · `reply_resolve.py` resolves a thread whose reply failed

Max PT-04. Reproduced: `test_private_helpers.py` "failed reply does not resolve its thread" fails. `:17-19` print the error and return the output anyway; `:23-24` print "replied" unconditionally; `:30` resolves every thread in the input; `:33` takes `"isResolved":false` as success. Resolve only what replied; read the GraphQL `errors` field.

### T-07 · P2 · `pr_status.py --ready` sees only the first hundred review threads

Max PT-05. Source-traced. `:36` `reviewThreads(first:100)` with no paging (`:37` comments, `:35` reactions likewise); `:106` calls the subset complete; `merge_stack.py:47,56` merges on it. No PR here has come near a hundred threads and the merge rules are checked by hand too, so low in practice; still fail closed on `hasNextPage`.

### T-08 · P2 · `kmove.py`'s code-only rename changes string literals too

Max PT-06. Reproduced: `test_private_helpers.py` "dot-only rename preserves string contents" fails. `:299-308` apply the regex to every non-import line with no lexical filter, so a `"` before the name passes both lookbehinds; its `code_only()` (`:57`) is unused here. `README.md` line 22 promises "only its uses as code … never the word in prose". A git diff shows it and the README requires compiling and testing after, so low in practice. Tokenise, or at least skip quoted strings and comments.

### T-09 · P2 · `compare.py` approves a cut with no baseline answers

Max EV-03. Reproduced: `test_private_eval_tools.py` "comparison cannot approve without any baseline answers" fails. `private/eval-chat/compare.py:37` scores an empty baseline 0 with 0 rounds (`max(1, len(b))`), so any two after-runs with no extra round pass `:38`; `:45` prints "THE CUT STANDS"; the exit status is always 0. The table does show "-" in the before column. Require a baseline answer for every case and exit non-zero otherwise.

### T-10 · P2 · `pull_events.py` fixes London at UTC+1

Max EV-02. Reproduced: `test_private_eval_tools.py` "winter calendar window uses london winter time" fails. `:16-17` `LONDON = timezone(timedelta(hours=1))` beside a comment that names 25 Oct as the change; `:23` forces the end to UTC midnight; the app ends at the zone's start of day (`learn/CalendarTime.kt:29`). In winter the start is an hour early, in summer the end an hour late: the window is one hour too wide at one edge, so an event is added, never dropped. Low. Use `zoneinfo.ZoneInfo("Europe/London")` (on Windows, `pip install tzdata` if the zone database is missing).

### T-11 · P2 · `run_stats.py` crashes on a run of one kind of message

Max EV-04 (P1). Reproduced: `test_private_eval_tools.py` "a valid felix only run can be summarised" fails with `StatisticsError: no median for empty data`. `private/felix-speech-review/run_stats.py:30-31,36-37` take the median and mean of both the Felix and the chat subsets unconditionally; `:64` divides by zero when two texts share no word. The review it served is done; it matters only if reused. Print a no-samples row and go on.

### T-12 · P2 · `scripts/phone-check.sh` says "All well" when the saved crash records couldn't be read

Ultra WT-01. Reproduced: `tooling_probe.py` passes (exit 0, "All well", "Permission denied" on stderr). `:111` pipes the `run-as` listing of `files/crashes` through `grep -c .`, so a failed read is "0"; `:113` passes. Narrower than written: a general adb or `run-as` failure is caught by the settings read (`:61-63`) and the store listing (`:121-125`), so the gap is a failure confined to that one directory, which the app creates only when a crash is written (`data/Crashes.kt:22`). Low. `adb exec-out` doesn't return the remote command's status (the script's own comments at `:60,120`), so the fix is an in-band marker (`[ -d files/crashes ] && echo DIR`, an OK sentinel), with an absent directory still a pass.

### T-13 · P2 · `scripts/powerplanner_probe.py` reports a missing agenda list as an empty agenda

Ultra WT-02. Reproduced: `tooling_contract_probes.py` passes (exit 0, "0 agenda item(s)"). `:134` `agenda.get("Items") or []` (the comment at `:131` chose it for null values); exit 2 comes only from an `Error` field (`:157-159`). The app's `PowerPlannerApi.kt` throws on the same answer ("the answer had no list of items"). `:132` prints the response's keys, so a careful reader sees it. Require a list; accept an empty one.

### T-14 · P3 · `private/tools/README.md` says two helpers print store contents; three do

Max PT-07. `:4` names `chat_check.py` and `clean_test_chat.py`; `felix_feedback.py:62-67` prints his words, his note, the alternatives and yours, as its own entry at `:30` says. Fix the opening sentence.

### T-15 · P3 · `private/eval/make_cases.py` still writes Info and Event labels

Max EV-05. `:38,44,53` expect Info and `:50` Event; the Email contract is Admin only (`ReadingAnswers.kt:77` maps Event to Admin, `readings.md:58`), and the script isn't marked historical. Narrower than written: it wouldn't replace the maintained set with new contradictions, as `private/eval/cases.json` already carries the same labels, and the Info cases are retired on purpose (`EvalSetTest.kt:73`, `readings.md:216`). The one live problem is the single Event case in `cases.json`: `EvalSetTest.kt:160` compares the kind's name, Event now parses as Admin, so that case always fails its kind check. The labels are yours to change (`readings.md:216`).
