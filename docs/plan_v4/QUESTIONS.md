# Questions and actions raised while the stages are built

Everything implementing the plan has needed from you, from 18 Oct 2026: questions with my recommendation and a blank `ANSWER:` line each, and actions only you can take. What was decided meanwhile, so the work didn't wait, is said with it and in the PR. [ANSWERS.md](ANSWERS.md) stays closed.

## Actions for you

- **A1. The PRs are stacked.** Stage 0's PR is on `main`; 3.3.0's (Stage 1) on Stage 0's branch, 3.3.1's on 3.3.0's, and so on to 3.3.4's, each showing only its own stage. Merge them in order; before merging one with `--delete-branch`, retarget the next to `main` (`gh pr edit <next> --base main`), or deleting the branch closes it. Reviews and merges are yours: none was requested.
  - UNDERSTOOD
  - Done (18 Oct): merged in order, #57 to #62.
- **A2. Words the privacy scan should refuse.** `scripts/scan-public.sh` refuses, beside email addresses and phone numbers, every word in the git-ignored `private/scan-words.txt`. It holds only the phone's serial number so far: add, a line each, any names you want kept out of the public repository (family, teachers, the school, the Gmail account's name before the @).
  - DONE

## Questions

### I1. A debug file left on the phone

`files/chat-context.txt` (16 KB, from 15 Oct) is on the phone: the `chat-context` command's output, which holds your tasks' text. The spec says such files are pulled into `private/` and then deleted from the phone; this one wasn't. Nothing reads it.

- **A (recommended).** Delete it (`run-as … rm files/chat-context.txt`); I'd pull it into `private/` first.
- **B.** Leave it.
- ANSWER: A
- Done (18 Oct): pulled into `private/phone-files/`, then deleted from the phone.

### I2. A test that can fail on this PC now and then

`PowerPlannerSourceTest` failed once in Stage 3's runs (and passed three times out of three again): Windows refused to rename `secrets.bin.tmp` over `secrets.bin` for a moment, as a virus scanner reading a file just written does. `JsonStore`'s write tries a few times more when that happens; `SecretStore`'s doesn't. CI runs on Linux, where it can't happen, and the app on Android neither: only the tests on this PC are touched. Not fixed here: the plan keeps finds out of the moves.

- **A (recommended).** Give `SecretStore.write` the same few tries, in a small PR of its own (or in Stage 12's sweep).
- **B.** Leave it: rerun the tests when it happens.
- ANSWER: A
- Done in 3.3.4, a PR of its own on 3.3.3's: the secrets' write goes through the stores' replace (`JsonStore.moveOver`), with its few tries.

### I3. A piece under way leaves today a minute after its start

Found while writing Stage 4's test of his looks; not changed there, as it's a move. On a Monday evening with nothing else planned, a 30-minute homework due Wednesday (or Friday) is planned at 16:45 today when the plan is made at 16:30, but every plan made from 16:46 on puts it on Tuesday, even with its start kept as the plan's watcher keeps it; one due the next morning stays. The spec says a kept start holds "one under way or in its grace included" (`docs/spec/slots-and-calendar.md`, PL-13). The planner chooses each piece's day first and lays today's slots after, and the kept starts reach only the laying (`previousSlots` goes to `Slotter.slot` alone). So, with nothing done on it, a slot of work with days to spare isn't under way at its tenth minute, and his "ten minutes in: starting?" (SlotStart) can't be written for it.

- **A (recommended).** Find why the day chosen changes once the evening's window has begun, and keep a piece under way on today while its slot and grace last: a test for it first, in a small PR of its own after 3.3.4.
- **B.** Leave it: work not begun moves to the next day.
- ANSWER: A
  - Done (18 Oct): 3.3.5, the planner keeps a piece kept today on today (`Planner.keptToday`), with a test.
