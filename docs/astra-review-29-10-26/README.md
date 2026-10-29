# The review of 4.9.1, and what became of it

On 29 Oct 2026 two separate review agents (Astra, run at two effort levels) each read `main` at adfe71dc, version 4.9.1, in an isolated worktree: every tracked file, the decisions and specs, with throwaway probe tests on temporary stores and local fakes, no paid call, no phone input. One reported 43 findings, the other 24; between them 55 distinct ones, 12 found by both. The same day both were cross-referenced and every finding checked against the code and the decisions: the probes re-run on this checkout (all 28 Kotlin cases and all 5 Python probes behave as reported), six verification agents reading the code by area (three on the races, merges and date arithmetic, three on the screens, the documents and the scripts), and the P1s and every narrowed claim re-read by hand. This folder keeps one version of each finding with what checking it changed; the two originals, their coverage ledgers, logs and manifests are backed up on the owner's PC and deleted here.

The outcome: **53 findings stand** (15 P1, 28 P2, 10 P3), 31 of them reproduced by a probe, 12 traced in the source, 10 in documents alone; **2 are rejected** (a sentence misread, a decision misread); one candidate the reviewer had already withdrawn. No production source was changed by the reviews or by this check.

## Priorities

Both reviews were asked to rate data loss, crashes and UI bugs P1, other wrong behaviour P2, documentation P3. "UI bug" stretched: a stale caption and a crash both came out P1, so the two reviews rated the same defect differently five times. The ratings here apply one scale and say where the originals differ:

- **P1**: stored data lost or changed to what you didn't set; a crash; your typed input lost; a control that reports done what the app then doesn't do.
- **P2**: other wrong behaviour: a promised action that doesn't happen or happens when it shouldn't; a wrong figure, alert or caption; a helper's wrong result.
- **P3**: documents and comments.

"Reproduced" means a probe showed it on temporary stores or a fake transport; "traced" that the code path was shown without running it; nothing was run on the phone.

## Start with these

S-01 (a cancelled save overwrites what reached disk) and S-02 (a reset erases a message landing during its copy) lose data in the store layer every screen uses. I-01 and I-02 silently rewrite a Gmail task's source record on an ordinary sync, one after a rename, the other the morning after a deadline, and each costs a paid re-read and the ticks on the reading's steps. F-01 ticks whichever step now holds the index you named. S-04 saves a total you didn't type. T-01 and T-02 are the local helpers that can destroy a pre-install backup or a real note. The rest of the P1s are screens: one crash (U-01) and four states a turn of the phone or a settings page leaves wrong.

## The findings

Each area file has every finding in full: where, what happens, what it goes against, what checking it changed, the fix and its side effects. The outcome column is for the fixing releases, as [the 4.3.13 record](../astra-review-4.3.13/README.md) kept them.

| Id | P | Finding | Reviewers' ids | Shown | Outcome |
|---|---|---|---|---|---|
| [S-01](storage-planning.md#s-01--p1--a-store-update-cancelled-during-its-write-leaves-disk-and-memory-apart-and-the-next-update-overwrites-what-reached-disk) | P1 | A store update cancelled during its write leaves disk and memory apart; the next update overwrites what reached disk | R01, CD-01 | reproduced | |
| [S-02](storage-planning.md#s-02--p1--reset-erases-a-chat-message-that-arrives-while-its-recovery-copy-is-being-written) | P1 | Reset erases a chat message that arrives while its recovery copy is being written | R02 | reproduced | |
| [S-03](storage-planning.md#s-03--p1--moving-a-piece-to-a-time-already-gone-today-is-accepted-then-dropped-by-the-plan) | P1 | Moving a piece to a time already gone today is accepted, then dropped by the plan | R05 | reproduced | |
| [S-04](storage-planning.md#s-04--p1--scaling-uneven-steps-to-a-total-you-gave-saves-more-minutes-than-you-typed) | P1 | Scaling uneven steps to a total you gave saves more minutes than you typed | R03 (P1), CD-02 (P2) | reproduced | |
| [S-05](storage-planning.md#s-05--p2--a-cancelled-held-notification-comes-back-at-the-morning-briefing) | P2 | A cancelled held notification comes back at the morning briefing | R04, UB-02 (both P1) | reproduced | |
| [S-06](storage-planning.md#s-06--p2--settings-margin-preview-puts-a-morning-deadlines-day-a-day-earlier-than-the-planner) | P2 | Settings' margin preview puts a morning deadline's day a day earlier than the planner | R06 (P1) | reproduced | Your call (29 Oct): the preview follows the planner's rule, which matches the field's label; to be built |
| [S-07](storage-planning.md#s-07--p2--an-applied-dated-instruction-older-than-two-years-makes-every-new-backup-unimportable) | P2 | An applied dated instruction older than two years makes every new backup unimportable | R07, CD-06 | reproduced | |
| [S-08](storage-planning.md#s-08--p2--the-release-rehearsal-never-reads-the-monthly-chat-archives-or-the-gmail-journal) | P2 | The release rehearsal never reads the monthly chat archives or the Gmail journal | R08, CD-07 | reproduced | |
| [S-09](storage-planning.md#s-09--p2--a-midweek-review-counts-the-previous-weeks-subjects) | P2 | A midweek review counts the previous week's subjects | R10 | reproduced | |
| [S-10](storage-planning.md#s-10--p2--a-newly-learned-anki-pace-changes-the-tasks-total-but-not-its-steps-minutes) | P2 | A newly learned Anki pace changes the task's total but not its steps' minutes | CD-03 | reproduced | |
| [S-11](storage-planning.md#s-11--p2--felix-cant-forget-a-standing-rule-longer-than-sixty-characters) | P2 | Felix can't forget a standing rule longer than sixty characters | CD-04 | reproduced | |
| [F-01](felix-chat.md#f-01--p1--a-step-action-or-its-undo-lands-on-whatever-step-now-holds-the-index-not-the-step-you-named) | P1 | A step action, or its Undo, lands on whatever step now holds the index, not the step you named | FX-01, AS-02 | reproduced | |
| [F-02](felix-chat.md#f-02--p2--pending-messages-dropped-by-the-input-budget-are-marked-answered) | P2 | Pending messages dropped by the input budget are marked answered | FX-02 (P1) | reproduced | |
| [F-03](felix-chat.md#f-03--p2--a-photo-of-seven-or-more-tasks-keeps-six-and-says-nothing-of-the-rest) | P2 | A photo of seven or more tasks keeps six and says nothing of the rest | AS-03 (P1) | reproduced | |
| [F-04](felix-chat.md#f-04--p2--a-slot-start-nudge-written-during-a-call-is-sent-after-youve-finished-the-piece) | P2 | A slot-start nudge written during a call is sent after you've finished the piece | AS-04 (P1) | reproduced | |
| [F-05](felix-chat.md#f-05--p2--the-mood-evaluations-comparison-with-an-older-run-judges-it-outside-the-budget) | P2 | The mood evaluation's comparison with an older run judges it outside the budget | AS-05 | traced | |
| [F-06](felix-chat.md#f-06--p2--one-instruction-that-extends-a-deadline-and-dates-a-step-inside-the-extension-is-refused) | P2 | One instruction that extends a deadline and dates a step inside the extension is refused (the chat keeps the deadline and drops the step, not the whole answer) | INT-03 | reproduced | |
| [I-01](sources.md#i-01--p1--an-unchanged-gmail-poll-stores-your-rename-as-the-emails-own-subject) | P1 | An unchanged Gmail poll stores your rename as the email's own subject | INT-01 | reproduced | |
| [I-02](sources.md#i-02--p1--the-first-sync-after-a-quoted-deadline-passes-drops-the-hard-deadline-the-readings-steps-and-their-ticks) | P1 | The first sync after a quoted deadline passes drops the hard deadline, the reading's steps and their ticks | AS-01 | reproduced | |
| [I-03](sources.md#i-03--p2--ankidroid-dying-between-two-queries-counts-as-no-cards-left-and-finishes-the-homework-for-good) | P2 | AnkiDroid dying between two queries counts as no cards left and finishes the homework for good (a narrow race; a healthy null means none) | INT-02 (P1) | traced | |
| [I-04](sources.md#i-04--p2--a-vocabulary-assignment-marked-not-a-task-still-switches-the-anki-day-to-reviews-only) | P2 | A vocabulary assignment marked "not a task" still switches the Anki day to reviews only | INT-04 | reproduced | |
| [I-05](sources.md#i-05--p3--five-comments-describe-behaviour-the-code-replaced) | P3 | Five comments describe behaviour the code replaced (of seven listed) | INT-07 | docs | |
| [I-06](sources.md#i-06--p3--d-9-still-gives-a-fixed-twenty-new-cards-and-a-reviews-only-switch-on-the-due-day) | P3 | D-9 still gives a fixed twenty new cards and a reviews-only switch on the due day | DH-01 | docs | |
| [U-01](ui-blocking.md#u-01--p1--a-subject-named-no-subject-crashes-the-work-list) | P1 | A subject named "No subject" crashes the Work list | UI-01 | reproduced | |
| [U-02](ui-blocking.md#u-02--p1--turning-the-phone-during-a-tasks-save-leaves-the-form-open-with-save-disabled-for-good) | P1 | Turning the phone during a task's save leaves the form open with Save disabled for good (not the chat's proposal form) | UI-02, UB-04 | traced | |
| [U-03](ui-blocking.md#u-03--p1--the-rating-dialogs-typed-feedback-and-chosen-text-are-lost-when-the-phone-turns) | P1 | The rating dialog's typed feedback and chosen text are lost when the phone turns | UI-03 | traced | |
| [U-04](ui-blocking.md#u-04--p1--protection-goes-on-saying-its-notification-channel-is-off-after-youve-turned-it-on) | P1 | Protection goes on saying its notification channel is off after you've turned it on | UI-04 | traced | |
| [U-05](ui-blocking.md#u-05--p1--a-chat-message-whose-first-write-fails-is-gone-from-both-the-box-and-the-chat) | P1 | A chat message whose first write fails is gone from both the box and the chat (a full disk) | UI-05 | traced | |
| [U-06](ui-blocking.md#u-06--p2--days-undo-refuses-the-swipe-that-finished-a-task-of-your-own) | P2 | Day's Undo refuses the swipe that finished a task of your own (one without steps) | UB-01 (P1) | reproduced | |
| [U-07](ui-blocking.md#u-07--p2--done-5-min-free-for-a-keep-that-earned-nothing) | P2 | "Done: 5 min free" for a keep that earned nothing | UB-03 (P1) | traced | |
| [U-08](ui-blocking.md#u-08--p2--re-entering-a-blocked-app-with-under-a-minute-of-free-time-gets-no-last-minute-warning) | P2 | Re-entering a blocked app with under a minute of free time gets no last-minute warning | UI-07 (P1) | traced | |
| [U-09](ui-blocking.md#u-09--p2--the-instruction-dialog-promises-youll-check-claudes-reading-before-anything-changes) | P2 | The instruction dialog promises you'll check Claude's reading before anything changes | UI-06, UB-05 (both P1) | traced | |
| [U-10](ui-blocking.md#u-10--p3--four-sentences-describe-rules-that-were-replaced) | P3 | Four sentences describe rules that were replaced (the service description, two comments, one spec line) | UI-08 (P1), UB-06 | docs | |
| [U-11](ui-blocking.md#u-11--p3--the-widgets-navigation-row-sends-task-taps-to-day) | P3 | The widget's navigation row sends task taps to Day | UI-09 | docs | |
| [D-01](docs.md#d-01--the-storage-references-store-versions-are-a-release-behind) | P3 | The storage reference's store versions are a release behind | DOC-01, CD-05 | docs | |
| [D-02](docs.md#d-02--version-fixture-and-requirement-summaries-omit-the-current-state) | P3 | Version, fixture and requirement summaries omit the current state | DOC-02, WT-04 | docs | |
| [D-03](docs.md#d-03--the-architecture-guides-one-test-class-a-file-has-five-exceptions-it-doesnt-name) | P3 | The architecture guide's "one test class a file" has five exceptions it doesn't name | DOC-04 | docs | |
| [D-04](docs.md#d-04--the-felix-input-reference-and-the-privacy-page-describe-the-payload-before-480) | P3 | The Felix input reference and the privacy page describe the payload before 4.8.0 | INT-06, AS-06 | docs | |
| [T-01](tools.md#t-01--p1--keep_beforesh-truncates-an-existing-recovery-archive-before-adb-has-read-anything) | P1 | `keep_before.sh` truncates an existing recovery archive before adb has read anything | PT-01 | traced | |
| [T-02](tools.md#t-02--p1--clean_test_chatpy---apply-reverts-every-note-and-reminder-changed-during-the-test) | P1 | `clean_test_chat.py --apply` reverts every note and reminder changed during the test | PT-02 | reproduced | |
| [T-03](tools.md#t-03--p1--pull_eventspy-overwrites-the-calendar-fixture-with-an-empty-list-when-adb-fails) | P1 | `pull_events.py` overwrites the calendar fixture with an empty list when adb fails | EV-01 | reproduced | |
| [T-04](tools.md#t-04--p2--scriptsgmail_probepy-carries-a-real-account-address-as-its-default) | P2 | `scripts/gmail_probe.py` carries a real account address as its default (D-79; not within D-80's list) | WT-03 | traced | Your call (29 Oct): kept, D-80 amended to name it |
| [T-05](tools.md#t-05--p2--watch_claudepy-dies-when-the-comments-need-a-second-page) | P2 | `watch_claude.py` dies when the comments need a second page | PT-03 (P1) | reproduced | |
| [T-06](tools.md#t-06--p2--reply_resolvepy-resolves-a-thread-whose-reply-failed) | P2 | `reply_resolve.py` resolves a thread whose reply failed | PT-04 | reproduced | |
| [T-07](tools.md#t-07--p2--pr_statuspy---ready-sees-only-the-first-hundred-review-threads) | P2 | `pr_status.py --ready` sees only the first hundred review threads | PT-05 | traced | |
| [T-08](tools.md#t-08--p2--kmovepys-code-only-rename-changes-string-literals-too) | P2 | `kmove.py`'s code-only rename changes string literals too | PT-06 | reproduced | |
| [T-09](tools.md#t-09--p2--comparepy-approves-a-cut-with-no-baseline-answers) | P2 | `compare.py` approves a cut with no baseline answers | EV-03 | reproduced | |
| [T-10](tools.md#t-10--p2--pull_eventspy-fixes-london-at-utc1) | P2 | `pull_events.py` fixes London at UTC+1 | EV-02 | reproduced | |
| [T-11](tools.md#t-11--p2--run_statspy-crashes-on-a-run-of-one-kind-of-message) | P2 | `run_stats.py` crashes on a run of one kind of message | EV-04 (P1) | reproduced | |
| [T-12](tools.md#t-12--p2--scriptsphone-checksh-says-all-well-when-the-saved-crash-records-couldnt-be-read) | P2 | `scripts/phone-check.sh` says "All well" when the saved crash records couldn't be read | WT-01 | reproduced | |
| [T-13](tools.md#t-13--p2--scriptspowerplanner_probepy-reports-a-missing-agenda-list-as-an-empty-agenda) | P2 | `scripts/powerplanner_probe.py` reports a missing agenda list as an empty agenda | WT-02 | reproduced | |
| [T-14](tools.md#t-14--p3--privatetoolsreadmemd-says-two-helpers-print-store-contents-three-do) | P3 | `private/tools/README.md` says two helpers print store contents; three do | PT-07 | docs | |
| [T-15](tools.md#t-15--p3--privateevalmake_casespy-still-writes-info-and-event-labels) | P3 | `private/eval/make_cases.py` still writes Info and Event labels (the live case set's one Event label is the real item) | EV-05 | docs | Your call (29 Oct): the live case's expected kind relabelled Admin, its input left as captured; the generator's labels wait for its next use |

Rejected after checking: **DOC-03** (the timer-stop sentence, misread; [docs.md](docs.md#rejected-doc-03-the-timer-stop-wording)) and **INT-05** (D-110's "not read again" is about the steps, which the code honours; [sources.md](sources.md#rejected-int-05-d-110s-not-read-again)). Withdrawn by its own reviewer, and agreed: **R09** (a `/..` path to `rehearse.sh`; GNU `rm` refuses it; [storage-planning.md](storage-planning.md#s-08--p2--the-release-rehearsal-never-reads-the-monthly-chat-archives-or-the-gmail-journal)).

## What checking changed

- **Two findings fell**: DOC-03 and INT-05, above.
- **Six are narrower than written**: U-06 holds for a task without steps only; F-06's chat path makes the deadline and drops the step rather than refusing the answer; I-03's null is also a healthy "none", so the defect is the one query API that can't tell the two apart; T-02's dry run does show the messages it will remove (the notes and reminders are the silent part); T-12's gap is one directory, the other reads being guarded; D-04's `Reason:` line is still sent. S-08's blame on `rehearse.sh` was wrong (the test's lists, not the script).
- **One exemption fell**: the max review's documentation pass had excused T-04 under D-80; D-80's list names no address and no script.
- **Priorities moved on 17**: ten P1s to P2 under the one scale above (the table shows each); S-04 stays P1 against ultra's P2 because the saved total isn't the one typed (D-4).
- **Found beside the findings**: U-04's shortcut row is stale the same way; S-09's midweek review marks the week as reviewed, so Sunday's is skipped; I-02 also stales the micro-plans and the paced-total check; U-07's photo and source lines over-promise alike; the privacy page's chat row (D-04) has the same gap as his messages' row. Each is noted in its finding.
- **Fixes with side effects**, noted in their findings: I-02 (every Gmail task's hash changes once: a paid re-read each unless migrated), I-04 (filtering the derived task itself marks it Missed and resets its ticks on Undo), F-01 (a new field on a stored action: store version and migration), U-06 (mirror the chat's guard, not a bare `reopen`), S-03 (the guard belongs in `pinProblem`, shared with the chat, and must allow a pin under way), S-06 (the planner's own rule may be what's wrong), F-04 (settle the next look when dropping the nudge, or it repeats).

## What was checked, and wasn't

- **Checked**: every finding of both reviews against the code at adfe71dc and against `docs/decisions.md`, `docs/plan_v4/answers.md` and the specs; the 28 Kotlin probe cases re-run on this checkout (16 of the max review's fail as it said, the ultra review's 6 pass and 6 fail as it said); the five Python probes re-run (as reported); the copies of the private helpers compared with the live ones (identical); both folders scanned for personal data (none: the only addresses are `example.org` fixtures).
- **Left out as settled** (the owner's earlier calls, not reported again by either review): a process death between two stores' writes; the forecast across days and the rebalance refused for an earlier gap (Q61, Q62); a restore leaving out messages still waiting for an answer (D-111); the backup not yet carrying a source task's local ticks and photos (P01 of the 4.3.13 review); the pre-existing fixture text under D-80; existing pins kept where you put them (D-114).
- **Not checked**: anything on the phone (the five traced screen findings, U-02 to U-05 and U-07 to U-09, want a phone check with the fix; U-02's window needs a barrier to show); real Gmail, Teams, Power Planner or AnkiDroid behaviour (I-03's provider death in particular); paid calls, prompt quality, schema compiles; a full disk; the dependencies.

## Evidence

[evidence/](evidence/) holds the probes, one file a reviewer class, unchanged from the reviews. The Kotlin ones go into the test tree at their package's path to run (`felix/`, `sources/gmail/`, `data/`, `ui/`, `block/`), for a run of `./gradlew :app:testDebugUnitTest --tests '*Astra*' --tests '*ReviewUiBlockTest*'`, and come out again; the max review's assert the behaviour wanted and fail, the ultra review's assert the behaviour seen and pass (its `AstraCoreDataProbeTest`) or fail (`ReviewUiBlockTest`, `AstraAiReviewTest`). `tooling_probe.py` and `tooling_contract_probes.py` run the two committed scripts offline with `adb` and the HTTP post replaced; they pass while the defects stand. The probes of the private helpers, and the copies of the helpers they run, are in `evidence/private/`, git-ignored, on the owner's PC only. As with the 4.3.13 record, the probes can go once the fixes land, each fix bringing its own regression test.
