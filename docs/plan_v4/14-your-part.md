# Your part: when the phone is needed, and when you are

Stage by stage, what each PR needs from your phone and from you, for what and for how long, and how to batch it. Everything else runs unattended: the code, the tests, CI, the reviews, and the waits ([development.md](../development.md) §Working unattended).

## The rules this follows

- **Every release is installed and checked on the phone before it merges** ([development.md](../development.md) §The phone): over adb from the PC, the app never uninstalled, a backup of the APK and the data first (`keep_before.sh`), the focus service checked after. So every stage but Stage 0 needs the phone connected, plugged in and unlocked, as in every session so far: evenings and weekends, since it's away on school days.
- **What only you can do**: anything in the notification shade (tap his notification, reply from it, a chip in it, Snooze); a gesture (a swipe); another app (Gmail, Teams); aeroplane mode; the phone off and on; a judgement of taste (his voice's examples); your mailbox. These are marked **needed**.
- **What I can do with the guarded scripts, slower**: a tap inside Decrastination (`private/tools/tap.sh`, which refuses unless the app is in front), reading the screen from screenshots, when you're not using the phone. Where a check is only that, you're marked **helpful**: five minutes of yours, or a slower quarter-hour of mine.
- **Replies to his messages from the PC**: Stage 5b gives the `chat-ask` command `--es replyTo <id>`, so a routed reply (a check-in answer, "leave me alone tonight", *Done* on a reminder) can be checked without a tap. The notification's own reply box still needs you, once, in 5a.
- **Pushing and merging**: a PR is pushed, reviewed and merged with your go-ahead; give it per stage in advance ("push and merge Stages 1–4 as each passes") and nothing waits on you.
- **Paid calls** are listed per stage; nothing costs over a pound but the chat's eval in 8c (two runs, about $4), the evals in 10b (about $2) and the re-read in 10a (about 60 cents).
- **Decisions made in advance**, so none stops the work: the chat's schema refused by the API → the change-type fallback in the same PR (Q13); the one notification misbehaving on One UI → one a message, grouped (C-Q4's B; not needed: when One UI hid the Reply in Stage 5's check, your lines as a speaker of their own fixed it); the voice costing a change or a date in the evals → the register tightened and run again (Q16); something 8c moves scoring lower in the chat's eval → sent always again, in the same PR.

## Stage by stage

| Stage | PR | The phone | You |
|---|---|---|---|
| 0 | none | **helpful**, 2 min: the latest nightly backup pulled from `Documents/Decrastination` for the rehearsal; the check script's first run | no |
| 1 | 3.3.0 | **needed**: install and check; a briefing and a free-time notice by debug command | no |
| 2 | 3.3.1 | **needed**: install and check; `ai-check` against the stub (free); one `chat-ask` and one `enrich-task` (paid, cents) | no |
| 3 | 3.3.2 | **needed**: install; `backup-now`; an Import and a Reset of Stats with its copy restored | **helpful**, 5 min: the Import and the Reset are taps in Settings (else guarded taps with screenshots) |
| 4 | 3.3.3 | **needed**: install; `chat-ask` and `felix-say` (paid, cents), `check-in`, `briefing`, `chat-card --es do apply`, then `--es do undo`; the plan snapshot | no |
| 5a | 4.0.0 | **needed**: the latest backup pulled for the migration's rehearsal first; install; `felix-say` twice (paid); `briefing`, `check-in`, `assess` | **needed**, 10 min: tap his notification (the chat opens at the message), reply from its box with a chip, see the old records in the chat at their times; the check-in's chips |
| 5b | 4.0.1 | **needed**: install | **needed**, 10 min: a swipe-reply, a long-press, scroll up past the top for earlier messages, the badge on his button |
| 5c | 4.0.2 | **needed**: install; one `chat-ask` that makes a change (paid) | **needed**, 5 min: the chevron, the label sliding out and back, Undo inside the fold, leave and return |
| 6a | 4.0.5 | **needed**: install; `calendar-written`, `calendar-sync` | no |
| 6b | 4.0.6 | **needed**: install on a weekend, the phone not in use for an hour; `test-arm`, `test-disarm`, `force-block` | **helpful**: nearby, in case blocking misfires (undone from the PC with `test-disarm`) |
| 6c | 4.0.7 | **needed**: install; `instruction`, `instructions`, `instruction-discard`, `instruction-delete` (new in 6c: deletes one applied, as the screen's Delete does), `quick-minutes` (0 takes it back), `own-add`, `own-done`, `own-reopen`, `own-delete` | no |
| 6d | 4.0.8 | **needed**: install; `sync`, `enrich-task` (paid, cents) | no |
| 6e | 4.0.9 | **needed**: install; `review` (paid: the review's call and the summary's, cents); the check-in answered from the PC | no |
| 6f | 4.0.10 | **needed**: install and the script | no |
| 7 | 4.0.11 | **needed**: the latest backup pulled for the rehearsal; a backup before the install; install; the counts compared; a sync; `backup-now` | **helpful**, 5 min: a look at Work and one task's sheet after the migration (your values, the ticks), a tick on Day |
| 8a | 4.1.0 | **needed**: install; three `chat-ask`s (paid) and their drafts (paid, cents); a three-minute wait; `dumpsys alarm`; the next day's first slot for a SlotStart reminder | **needed**, 15 min across an evening: Snooze and then *Done* from the reminder's notification; aeroplane mode on through a draft's window (the reminder comes as your words); "Add a note" on a sheet (a tap in the app: guarded if you'd rather); the phone off through a reminder's time, if you like |
| 8b | 4.1.1 | **needed**: install; six `chat-ask`s (paid); "open it in Teams" brings Teams up (the phone unlocked, no Teams sync in front) | **needed**, 5 min, before the `chat-ask`s: each class's subject in Settings → About you, Teams' codes and Power Planner's names alike (the list you offered on 19 Oct), so each Power Planner class finds its code; on the phone, never in the repository |
| 8c | 4.1.2 | **needed**: install; four `chat-ask`s (paid): last week, December, an email's text, an email's attachment | no: the chat's eval runs on the PC (paid, about $4), and its figures decide by the rule in [15](15-sent-or-asked-for.md) |
| 9 | 4.1.3 | **needed**: install and the script | no |
| 10a | 4.2.0 | **needed**: install; `chat-ask` (paid); `memory` an hour apart; `felix-say` and a reply from the PC; the next morning; the version bump re-reads the open tasks (about 40 calls, 60 cents, online) | no |
| 10b | 4.2.1 | **needed**: install; four `chat-ask`s and a `felix-say` (paid); the next morning's briefing; `review` for the summary | **needed**, twice, at your own pace: before the PR, read the register paragraph and the examples (taste: 10 min, by message, no phone); after the install, read a day of his messages and say what's off |
| 11 | 4.3.0 | **needed**, online: install; `sync --es sources gmail`; `state`; a minute's wait for the write; logcat's IMAP lines | **needed**, 20 min with Gmail open: star a note-to-self email in Gmail; tick its piece on Day; Undo within the grace (again, with a second email); aeroplane mode on and off; look in Gmail (unstarred, out of the inbox); the first-time sheet; the switch off in Settings and a tick that leaves Gmail alone |
| 12 | 4.3.1 | **needed**: install and the script; the baselines | **helpful**: the done-when list read together, by message |

## In sum

- **The phone** is needed for 27 of the 28 PRs, for the install and its check: a plugged-in, unlocked phone on the desk for an evening does several PRs if their reviews are done by then. Two stages want it free of use for an hour (6b, on a weekend) or online for a minute's write (11).
- **You** are needed for eight moments, about an hour and a half at the phone in all: 5a, 5b, 5c (ten, ten and five minutes; done, in one evening with the three PRs stacked), 8a (fifteen minutes spread over an evening), 8b (five minutes: your classes' subjects), 11 (twenty minutes with Gmail), and 10b twice by message (a read before, a read after): with Stage 5's done, 8a's, 8b's, 11's and 10b's are left. Four more are helpful but not needed (3, 6b, 7, 12): five minutes each, or slower guarded taps.
- **No decision waits on you** during the work: every question is answered, and the three things that could go wrong mid-PR have their fallback chosen.

## Still to check from Stage 5

Everything else in Stage 5 was checked on the phone with you on 18 Oct, or by the scripts after its last install. These four want your taps, whenever they come up; none needs a session of its own:

1. With more of his messages unread than a screen holds, open the chat (his button, not his notification): it opens at the *New messages* line, not at its foot.
2. Leave the chat on *Your changes*, then tap his notification: the *Chat* tab shows, at the message.
3. Open a message's fold, go to *Your changes* and back: every fold is closed, and what came meanwhile is under the *New messages* line.
4. Once the chat has messages past its window (a fortnight old and past the newest 200, days away), scroll back past them: the older rows show under *Earlier messages* with no Undo or buttons of their own, read a month at a time (4.0.4).

## Waiting for the phone

The phone was disconnected on 19 Oct before 4.0.3's and 4.0.4's last fixes reached it, and Stage 6 is built while it's away. When it's connected again, in this order:

1. **The build on `main` installed and checked**: 4.0.4 as merged (`main` at 7c46fe9, its APK kept as `private/apk/2026-10-19-4.0.4-final-built.apk`), with `keep_before.sh` first and `scripts/phone-check.sh` after. The phone has #74 as it was at 07:44 on 19 Oct (the archive a file a month, the live window a fortnight; the app and the chat opened and scrolled back then), without the review fixes of 4.0.3 (a photo taken within 30 seconds of a Done earns that Done's slot; the "stays planned" line for a task as the plan takes it, your instructions' values over it or work credited from a linked one, what's left rounded as the planner rounds it) or of 4.0.4 (an archive file that can't be read set aside and said; *Clear the chat* lets its months go unread; a reply restored from the archive). None of them needs your taps.
2. **Stage 6's PRs, in order**: each, built and reviewed while the phone is away, waits ready and unmerged, stacked on the one before, with its APK kept in `private/apk/` (as every release is installed and checked before it merges, unless you say otherwise for a PR). Each is then installed and given its check from the table above, and merged, before the next is installed; a problem one shows is fixed on its branch and merged up the stack, and every branch above it then rebuilt, its CI run and its kept APK replaced before it's installed, so what's checked is what merges. 6b's check wants a weekend and you nearby, as the table says.
3. **Stage 5's four checks** above, whenever they come up.

## How to batch it

1. **Stages 0–4** (six PRs, the ground; done): three phone evenings with no need of you; the five minutes of taps in Stage 3 whenever you're passing.
2. **Stage 5** (the chat; done, in one evening with you): three short sessions with you, or one evening for all three.
3. **Stages 6 and 7** (seven PRs, the services and the data release): three or four phone evenings; 6b on a weekend with you nearby; a five-minute look after 7.
4. **Stage 8** (reminders, the gaps and what he's sent): one evening with you for 8a's fifteen minutes, a glance the next day for the SlotStart reminder; 8b your classes' subjects first, five minutes; 8c needs only the phone.
5. **Stages 9 and 10**: the voice's text read by you before 10b starts (no phone), a session after it lands; 9 and 10a need only the phone.
6. **Stage 11**: one evening with Gmail open, twenty minutes.
7. **Stage 12**: the phone, and the done-when list read together.

On any evening the phone is connected and you're not using it, I can run a stage's install and its scripted checks, wait for your taps where they're needed, and carry on with the next PR's code meanwhile.

Each session that needs you is prepared first: its list reaches you before it, in the stage's PR, with every step in order, what to look for, and what the scripts have already checked, so your time at the phone goes only on what needs you.
