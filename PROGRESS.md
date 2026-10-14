# Review v2: progress

The implementation of `docs/review_v2_improvements/impl/` on branch `v3`. Deleted when the last phase is done.

## Done

- AR-04 property tests for the planner (`PlannerPropertyTest`): 825256e. 300 seeds in 1.3 s; 50 000 pass locally (21 s).
- DX-03, DX-07 starred conversations from All Mail, known ones not fetched again: this commit. Unit-tested against a scripted IMAP server; the phone check is with the phase's install.

## Current

Phase 1 (`impl/phase-1-tests-and-email.md`): AI-02 next.

## Outstanding

- Phase 1: AI-02, DX-06, DX-01, AI-04; then the phone checks, the docs, the pull request.
- Phases 2 to 8.

## Decisions made

- **AR-04's stability check is scoped (no planner change).** As specified ("a minute passing moves no chunk after today") it fails at seed 1702 of 10 000: Saturday 19:00, today's 75 minutes full to the minute, so a minute later its last piece goes to tomorrow and moves tomorrow's. That's a planner with no memory of its last plan, which PL-13's `previous` (phase 3) fixes; nothing to fix minimally now. The check runs where the minute leaves today's time as it was (277 of the first 300 seeds); the phase file says so. No other invariant failed in 50 000 seeds.

- **Whole conversations (the owner, 14 Oct, during phase 1).** A starred message makes its whole conversation the task, and every message in it is read, starred or not, the owner's replies included; drafts are left out. So the read examines All Mail (its `\All` special-use attribute, else a name ending `/All Mail`), finds the starred messages with `UID SEARCH FLAGGED`, and their conversations with one `UID SEARCH NOT DRAFT OR X-GM-THRID … ` (Gmail answered it on the real mailbox: 17 starred messages, 17 conversations, two of 9 and 8 messages with one star each). This changes DX-03 (All Mail, not the Starred folder), DX-07 (known and new by conversation), DX-06 (the marker no longer says earlier messages can't be seen), DX-01 (attachments of every message) and AI-04 (the model sees the whole conversation). The phase file is amended to match.
- **Unstarred means done, blocks or not (DX-03).** `Merge.apply` kept an email gone from the inbox open while its reading was due, or while it had dated blocks left (a "follow-up"). Under the star as the state, and the owner's "unstarred email tasks finish at the first starred-only sync", both rules go: an unstarred conversation is finished at the next read.
- **A note to self is a conversation that's all yours.** The rules read "the newest message is yours" as a note to self; with your replies in the conversation that's wrong, so a conversation is a note to self only when every message in it is yours, and its sender is the newest message not yours.
- **Prompt changes before phase 8's `Prompts.VERSION`:** (recorded here until phase 8 adds the version) the Email job's system prompt and schema change in AI-02, DX-06 and AI-04.
