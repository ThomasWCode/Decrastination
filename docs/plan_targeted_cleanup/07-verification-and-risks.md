# Verification, and the risks

How each phase is shown to keep the app working, and what could go wrong.

## "Nothing changed": how it's shown

A phase that claims to keep behaviour shows it four ways:

1. **Every test passes**, the 874 of today and the new ones, with none removed but by a stated reason.
2. **The plan snapshot is unchanged** (Phase 0): a fixed state planned the same, to the piece and the minute.
3. **The goldens are unchanged**: no prompt or schema differs, unless the PR says why.
4. **The phone check passes** after the install, and the phase's own checks with it.

A phase that changes behaviour on purpose (only 1b, and 5b's trimmed text for long-finished tasks) says what changes, and its tests show it.

## The tests added

| Kind | What | Phase |
|---|---|---|
| Store fixtures and decoding | every store's shape as it is, read by the app's serializers | 0 |
| Plan snapshot | a fixed state's plan, as text | 0 |
| Notice registry | no two ids or request-code ranges overlap | 1a |
| Outbox and conversation | a message in the chat before its notification; none with the chat on screen; replies under the message they answer; his rules counting the same | 1b |
| Gateway | holds, the cap, the record, a failure's class, one client for many calls | 2 |
| Services | each service's flows on temporary stores, many of them untested today (the chat's undo, instructions applied and refused, a backup restored, a reset) | 3 |
| Who set what | the flags of 3.1.2–3.1.4 over `mine`; ticks following an id; origins | 4 |
| Migrations | from every fixture shape, twice (idempotent); the rehearsal's invariants | 4, 5 |
| Architecture | `core` pure; one writer a store; only the gateway builds clients; only the notifier posts | 1a onwards |

## The phone check

After every install, read-only, by `scripts/phone-check.sh` (Phase 0):

- the version and the time it was installed;
- the six accessibility services enabled and bound, the focus service among them;
- the calendar written to: its slots, the longest of the app's own steps an hour or less, no stray copies;
- no crash in the log since the install;
- what's in front (the home screen, not a screen left open);
- the stores' sizes, against the last run.

The agent never taps the phone: where a check needs a tap (a notification opening the chat at a message), you make it, and say what you saw. A backup of the APK and the data comes before every install (`keep_before.sh`), and the app is never uninstalled.

## Reviews

- **Moves** (Phase 3's first commits, Phase 6): one Codex round, the PR saying it's a move, so its findings are about the move.
- **Phases that change data or behaviour** (1b, 4, 5): up to three rounds, stopping at a round with at most one P2, as for the releases of the last week.
- **One `@claude review`** on every PR that gets up to three Codex rounds (Q2): it answers in one comment, not a comment a point; each point is handled, then its comment gets the reply "Resolved". Whether this goes into `docs/development.md` as every PR's rule is Q12.
- Both as you decided (Q2: A, with the Claude review).

## Risks

| Risk | Where | Likelihood | Harm | How it's met |
|---|---|---|---|---|
| Blocking or protection behaves differently | 3c | low | high: blocked when it shouldn't be, or not when it should | moved as it is; `ProtectTest` and new service tests of armed and unarmed paths; `test-arm`/`test-disarm` and `force-block` on the phone; released on a weekend; the previous APK kept |
| Data lost or changed in a migration | 4, 5 | low | high | the rehearsal on your latest backup first; migrations tested from every shape and run twice; the old file kept a week; the backup before the install |
| Felix's messages missing or doubled | 1b | medium | medium | the chat written before the notification; tests of every producer; what his rules count compared before and after |
| Notifications behave differently | 1a, 1b | low | low | ids kept where they don't move; the registry's test; the ceiling's tests |
| A move changes behaviour unseen | 3 | medium | medium | moves verbatim, with delegates; the plan snapshot; the phone check |
| Clashes with the coming feature work | all | medium | low | features land between phases; small PRs; Felix's service moved after his features |
| Paid calls in checks | all | low | low | `ai-check` against a local stub; a paid call only where the phase needs one, said in the PR |
| The PC's memory during builds | all | low | low | one warm Gradle daemon and Kotlin's beside it, each capped, giving back memory it isn't using and stopping after an idle hour (`gradle.properties`); `./gradlew --stop` after each session's builds |
| The phone's away (school days, 07:40–18:00) | all | certain | time | installs and checks in the evening or at the weekend |
| Features wait too long | all | medium | medium | the lighter route: Phases 0–2 first (the chat work's ground and the gateway), the rest between features |

## Rolling back

Each phase's PR can be reverted on its own. A phase that migrated data is undone as [04](04-data-and-migrations.md#rolling-back) says: the previous APK installed over the new one, the data from before restored with `run-as`, never an uninstall.
