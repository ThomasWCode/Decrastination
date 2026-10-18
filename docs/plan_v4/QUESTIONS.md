# Questions and actions raised while the stages are built

Everything implementing the plan has needed from you, from 18 Oct 2026: questions with my recommendation and a blank `ANSWER:` line each, and actions only you can take. What was decided meanwhile, so the work didn't wait, is said with it and in the PR. [ANSWERS.md](ANSWERS.md) stays closed.

## Actions for you

- **A1. The PRs are stacked.** Stage 0's PR is on `main`; 3.3.0's (Stage 1) on Stage 0's branch, 3.3.1's on 3.3.0's, and so on to 3.3.3's, each showing only its own stage. Merge them in order; before merging one with `--delete-branch`, retarget the next to `main` (`gh pr edit <next> --base main`), or deleting the branch closes it. Reviews and merges are yours: none was requested.
- **A2. Words the privacy scan should refuse.** `scripts/scan-public.sh` refuses, beside email addresses and phone numbers, every word in the git-ignored `private/scan-words.txt`. It holds only the phone's serial number so far: add, a line each, any names you want kept out of the public repository (family, teachers, the school, the Gmail account's name before the @).

## Questions

### I1. A debug file left on the phone

`files/chat-context.txt` (16 KB, from 15 Oct) is on the phone: the `chat-context` command's output, which holds your tasks' text. The spec says such files are pulled into `private/` and then deleted from the phone; this one wasn't. Nothing reads it.

- **A (recommended).** Delete it (`run-as … rm files/chat-context.txt`); I'd pull it into `private/` first.
- **B.** Leave it.
- ANSWER:
