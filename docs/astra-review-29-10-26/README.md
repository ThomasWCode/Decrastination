# The review of 4.9.1, and what became of it

On 29 Oct 2026 two review agents (Astra, run at two effort levels) each read `main` at adfe71dc, version 4.9.1, in an isolated worktree: every tracked file, the decisions and specs, with throwaway probe tests on temporary stores and local fakes, no paid call and no phone input. One reported 43 findings, the other 24: 55 distinct ones, 12 found by both. The same day every finding was checked against the code and the decisions, with the probes re-run and six verification agents reading the code by area, and collated here (#154): **53 stood** (15 P1, 28 P2, 10 P3), 2 were rejected, and one candidate the reviewer had withdrawn. They were fixed the same evening, one release an area, in 4.9.2 to 4.9.6 (#155 to #159), and the documents and the committed scripts in #160; the local helpers in `private/` were fixed on this PC. This file keeps each finding and what became of it. The area files with each finding's full trace, fix and side effects, and the probes (each fix brought its own regression test), are in the history at 150bca50 (#154); the reviews' originals and the private helpers' probes are on the owner's PC only (`private/backup/astra-review-29-10-26-originals/`, `private/review-29-10-26/`).

## The scale

The reviews were asked to rate data loss, crashes and UI bugs P1, other wrong behaviour P2, documentation P3; "UI bug" stretched to a stale caption, so the record re-rated thirteen findings on one scale:
- **P1**: stored data lost or changed to what you didn't set; a crash; your typed input lost; a control that reports done what the app then doesn't do.
- **P2**: other wrong behaviour: a promised action that doesn't happen or happens when it shouldn't; a wrong figure, alert or caption; a helper's wrong result.
- **P3**: documents and comments.

## The findings, and their outcomes

| Id | P | Finding | Outcome |
|---|---|---|---|
| S-01 | P1 | A store update cancelled during its write left disk and memory apart; the next update wrote over what had reached disk | 4.9.2: a store's write and its state's change are one section a cancel can't split (`JsonStore.update`, `SecretStore.put`) |
| S-02 | P1 | A reset erased a chat message that arrived while its copy was being saved | 4.9.2: a reset takes what its copy holds and keeps what came after, record by record (by id; a day's record and a week's totals by day and week) |
| S-03 | P1 | A move to a time already gone today was accepted, then dropped by the plan | 4.9.3: `Planner.pinProblem` refuses a time whose piece and grace are over; one under way is kept; the chat shares the check |
| S-04 | P1 | Your total shared over uneven steps came to more than you typed | 4.9.3: shares in proportion, each at least a minute, the minutes that takes taken back from the largest |
| S-05 | P2 | A cancelled held notification came back at the morning briefing | 4.9.2: a cancel lets go of the held one too, ordered with a hold still being written |
| S-06 | P2 | Settings' margin line put a morning deadline's day a day earlier than the planner | Your call: the line follows the planner's rule (4.9.3, D-123) |
| S-07 | P2 | An applied instruction older than two years made every new backup unimportable | 4.9.2: its times held to its own making; the nightly isn't written or pruned for a backup Import would refuse; Export saves it with a warning (your call) |
| S-08 | P2 | The release rehearsal never read the chat archive's months or the Gmail journal | 4.9.2: both read, and the rehearsal fails on a JSON file it doesn't read, its list held to `Stores` |
| S-09 | P2 | A midweek review counted the week before's subjects | 4.9.3: its own days; your call (D-123): a midweek review is the week so far, learns nothing, and Sunday's still runs |
| S-10 | P2 | A newly learned Anki pace changed the task's total but not its steps' minutes | 4.9.3: the same steps take the source's new minutes, keeping their ids and ticks |
| S-11 | P2 | Felix couldn't forget a standing rule longer than sixty characters | 4.9.3: a rule is forgotten by its whole words |
| F-01 | P1 | A step action, or its Undo, landed on whatever step now held the index | 4.9.4: an action names its step (title, id), found where it is now or refused, said; `chat.json` 12, the archive's months 11. A moved step's piece-kept tick isn't undone (said in the spec) |
| F-02 | P2 | Pending messages dropped by the input budget were marked answered | 4.9.4: only those the answered request held are marked (`ChatPrompts.sent`) |
| F-03 | P2 | A photo of seven or more tasks kept six and said nothing of the rest | 4.9.4: the rest said under *Left out* |
| F-04 | P2 | A slot-start nudge written during a call was sent after you'd done the piece | 4.9.4: dropped when its work began meanwhile, his next look settled (`FelixRules.stillDue`) |
| F-05 | P2 | The mood evaluation's comparison judged an older run outside the budget | 4.9.4: judged within it, the report written after |
| F-06 | P2 | One instruction that extends a deadline and dates a step inside the extension was refused | 4.9.4: steps checked against the deadline the same answer gives (`InstructionAnswers.projected`) |
| I-01 | P1 | An unchanged Gmail poll stored your rename as the email's own subject | 4.9.5: rebuilt under the email's own subject |
| I-02 | P1 | The first sync after a quoted deadline passed dropped the hard deadline, the reading's steps and their ticks | 4.9.5: the rules read an email's dates as of the day it came, so an unchanged email reads the same at every sync, a deadline gone by included; no hash change, so no migration |
| I-03 | P2 | AnkiDroid dying between two queries counted as no cards left | 4.9.5: counted through an unstable client, a provider's death a failed read |
| I-04 | P2 | A vocabulary assignment marked "not a task" still made the Anki day reviews only | 4.9.5: only decks an unmarked assignment asks for feed the quota (`AnkiRules.asked`); the deck task stays listed |
| I-05 | P3 | Five comments described replaced behaviour | 4.9.5 |
| I-06 | P3 | D-9 still gave a fixed twenty new cards and a reviews-only switch on the due day | 4.9.5: D-9 amended to the rule built |
| U-01 | P1 | A subject named "No subject" crashed the Work list | 4.9.6: the no-subject group has a key of its own |
| U-02 | P1 | Turning the phone during a task's save left the form open with Save disabled | 4.9.6: the save runs in the app's scope (`FormSaves`); the form made again closes or gives Save back |
| U-03 | P1 | The rating box's words and pick were lost when the phone turned | 4.9.6: held by the chat screen as saveable state; checked on the phone, where the first fix, saveable state in the message's row, lost the box all the same |
| U-04 | P1 | Protection went on saying its notification channel was off after you'd turned it on | 4.9.6: looked at again on resume |
| U-05 | P1 | A chat message whose first write failed was gone from both the box and the chat | 4.9.6: back in the box |
| U-06 | P2 | Day's Undo refused the swipe that finished a task of your own | 4.9.6: `Focus.undoKeep`, with the chat's guard (`ChatActs.finishedBy`) |
| U-07 | P2 | "Done: 5 min free" for a keep that earned nothing | 4.9.6: the line says what the keep's record earned |
| U-08 | P2 | Re-entering a blocked app with under a minute left got no last-minute warning | 4.9.6: warned at once, once a period, with the seconds left |
| U-09 | P2 | The instruction dialog promised you'd check Claude's reading before anything changed | 4.9.6: says changes are made at once, with their Undo |
| U-10 | P3 | Four sentences described replaced rules | 4.9.6 (the service description's second sentence; its first, which the settings guard reads, unchanged) |
| U-11 | P3 | The widget's navigation row sent task taps to Day | 4.9.6: the row split in `spec/felix.md` |
| D-01 | P3 | The storage reference's store versions were a release behind | 4.9.4, with its own versions |
| D-02 | P3 | Version, fixture and requirement summaries omitted the current state | 4.9.4 (fixtures), 4.9.6 (the README's version), #160 (AR-04, PL-12, the plan's next free number) |
| D-03 | P3 | The architecture guide's "one test class a file" had five exceptions it didn't name | #160: named |
| D-04 | P3 | The Felix input reference and the privacy page described the payload before 4.8.0 | #160 |
| T-01 | P1 | `keep_before.sh` truncated an existing data backup before adb read anything | Fixed locally, as `keep_data.sh`: neither writes over, and the data is moved into place once it reads whole |
| T-02 | P1 | `clean_test_chat.py --apply` reverted every note and reminder changed during a test | Fixed locally: only what the test's messages' acts name is taken back |
| T-03 | P1 | `pull_events.py` overwrote the calendar fixture with an empty list when adb failed | Fixed locally |
| T-04 | P2 | `scripts/gmail_probe.py` carries a real account address as its default | Your call: kept, D-80 amended to name it |
| T-05 | P2 | `watch_claude.py` died when the comments needed a second page | Fixed locally, and a failed read is no longer "no answer yet" |
| T-06 | P2 | `reply_resolve.py` resolved a thread whose reply failed | Fixed locally, every thread read page by page |
| T-07 | P2 | `pr_status.py --ready` saw only the first hundred threads | Fixed locally: fails closed on a list read in part |
| T-08 | P2 | `kmove.py`'s code-only rename changed string literals | Fixed locally |
| T-09 | P2 | `compare.py` approved a cut with no baseline answers | Fixed locally, exiting 1 when the cut falls short |
| T-10 | P2 | `pull_events.py` fixed London at UTC+1 | Fixed locally, by the UK's rule (no tz database on this PC) |
| T-11 | P2 | `run_stats.py` crashed on a run of one kind of message | Fixed locally |
| T-12 | P2 | `scripts/phone-check.sh` said "All well" when the crash records couldn't be read | #160 |
| T-13 | P2 | `scripts/powerplanner_probe.py` reported a missing agenda list as an empty agenda | #160 |
| T-14 | P3 | `private/tools/README.md` said two helpers print store contents; three do | Fixed locally |
| T-15 | P3 | `private/eval/make_cases.py` still wrote Info and Event labels | Your call: the live case relabelled Admin; the generator's labels wait for its next use, as its docstring says |

Rejected after checking: **DOC-03** (D-112's timer-stop sentence, misread: "30 minutes after its zero or a Stop by hand, whichever came first" applies the 30 minutes to the earlier, as `FelixRules.liftAt` does) and **INT-05** (D-110's "not read again" is about the steps, which the code honours). Withdrawn by its reviewer: **R09** (a `/..` path to `rehearse.sh`; GNU `rm` refuses it).

## What was checked, and wasn't

- **Checked**: every finding against the code at adfe71dc and the decisions; the 28 Kotlin and 5 Python probes re-run; the fixes against the probes' scenarios, each in a regression test; 4.9.4's migration rehearsed on a backup of the phone's data; each release's reviews (Codex until a round with at most one P2, then Claude's, every finding answered).
- **Left out as settled** (your earlier calls): a process death between two stores' writes; the forecast across days and the rebalance refused for an earlier gap (Q61, Q62); a restore leaving out messages still waiting (D-111); the backup not carrying a source task's local ticks and photos (P01 of the 4.3.13 review); the fixtures' text under D-80; existing pins kept where you put them (D-114).
- **On the phone** (4.9.6 installed over 4.9.1, 29 Oct): the checks after an install all well, `chat.json` migrated to 12, Work by subject with its no-subject group (U-01), and the rating box through a turn (U-03). Not driven there: U-02 (a save's milliseconds), U-04 (it needs a channel switched in Android's settings), U-05 (a full disk), and U-06 to U-08 (they'd make records of keeps and free time), each held by its test.
- **Not checked by the review**: real Gmail, Teams, Power Planner or AnkiDroid behaviour; paid calls and prompt quality; a full disk.

## What it taught

- **Two reviewers at two efforts overlapped on 12 of 55.** Each found what the other missed, and checking them dropped 2, narrowed 10 and re-rated 13: worth running both, and checking every finding before fixing it.
- **Rate on a scale written down first.** "UI bug is P1" put a stale caption beside a crash; the scale above keeps P1 for lost data, crashes, lost input and controls that lie.
- **Choose the fix that needs no data release where one exists.** I-02's suggested fix changed the reading's hash, which five stores keep copies of; reading the email's dates as of the day it came made the hash stable with no migration. A stored field (F-01) still means a version raised and a fixture folder, though nothing migrates.
- **Write and publish together; reset only what the copy holds.** A cancel between a file's write and the state's change (S-01), and writers landing during a reset's copy (S-02), were each a few milliseconds wide; the fixes make the window impossible rather than unlikely. Ordering a cancel against a pending write needs both decided under one lock and numbered (S-05), and the write that removes must come even when nothing's held yet (Claude's review of 4.9.2).
- **A fix that makes a rare state normal needs its readers checked.** Letting Sunday's review run after a midweek one (D-123) made two reviews a week ordinary, and three places still took "the week's review" as the first (Claude's review of 4.9.3).
- **State kept through a turn of the phone belongs above a lazy list's rows.** `rememberSaveable` inside a chat message's row passed every test and still lost the rating box when the activity was made again; held by the screen, it held. Screen fixes are checked on the phone, not by reading.
- **Building the next release in a second worktree while CI runs on the first** kept five stacked releases moving; the full local run belongs on the stack's top before each push.
