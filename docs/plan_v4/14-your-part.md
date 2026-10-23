# Your part: when the phone is needed, and when you are

Stage by stage, what each PR needs from your phone and from you, for what and for how long, and how to batch it. Everything else runs unattended: the code, the tests, CI, the reviews, and the waits ([development.md](../development.md) §Working unattended).

## The rules this follows

- **Every release is installed and checked on the phone before it merges** ([development.md](../development.md) §The phone): over adb from the PC, the app never uninstalled, a backup of the APK and the data first (`keep_before.sh`), the focus service checked after. So every stage but Stage 0 needs the phone connected, plugged in and unlocked, as in every session so far: evenings and weekends, since it's away on school days.
- **What only you can do**: anything in the notification shade (tap his notification, reply from it, a chip in it, Snooze); a gesture (a swipe); another app (Gmail, Teams); aeroplane mode; the phone off and on; a judgement of taste (his voice's examples); your mailbox. These are marked **needed**.
- **What I can do with the guarded scripts, slower**: a tap inside Decrastination (`private/tools/tap.sh`, which refuses unless the app is in front), reading the screen from screenshots, when you're not using the phone. Where a check is only that, you're marked **helpful**: five minutes of yours, or a slower quarter-hour of mine.
- **Replies to his messages from the PC**: Stage 5b gives the `chat-ask` command `--es replyTo <id>`, so a routed reply (a check-in answer, "leave me alone tonight", *Done* on a reminder) can be checked without a tap. The notification's own reply box still needs you, once, in 5a.
- **Pushing and merging**: a PR is pushed, reviewed and merged with your go-ahead; give it per stage in advance ("push and merge Stages 1–4 as each passes") and nothing waits on you.
- **Paid calls** are listed per stage; nothing costs over a pound but the chat's eval in 8c (before and after, the cut's cases twice each: about $6), the evals in 10b (about $2) and the re-read in 10a (about 60 cents).
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
| 6c | 4.0.7 | **needed**: install; `instruction`, `instructions`, `instruction-discard`, `instruction-delete` (new in 6c: takes one applied back, as the instructions' *Take back* does), `quick-minutes` (0 takes it back), `own-add`, `own-done`, `own-reopen`, `own-delete` | no |
| 6d | 4.0.8 | **needed**: install; `sync`, `enrich-task` (paid, cents) | no |
| 6e | 4.0.9 | **needed**: install; `review` (paid: the review's call and the summary's, cents); the check-in answered from the PC | no |
| — | 4.0.10 | **needed**: install; the chat's store read before and after | **needed**, 5 min: the box on his newest nudge, a reply that answers it, *Mark answered*, *Mark all answered* and its Undo |
| 6f | 4.0.11 | **needed**: install and the script | no |
| 9 | 4.0.12 | **needed**: install and the script | no |
| 7 | 4.0.13 | **needed**: the latest backup pulled for the rehearsal; a backup before the install; install; the counts compared; a sync; `backup-now` | **helpful**, 5 min: a look at Work and one task's sheet after the migration (your values, the ticks), a tick on Day |
| 8a | 4.1.0 | **needed**: install; three `chat-ask`s (paid) and their drafts (paid, cents); a three-minute wait; `dumpsys alarm`; the next day's first slot for a SlotStart reminder | **needed**, 15 min across an evening: Snooze and then *Done* from the reminder's notification; aeroplane mode on through a draft's window, the reminder set at least 15 minutes ahead and the phone switched straight after his answer, as one set inside the window's ten minutes is written for at once, online (the reminder comes as your words); "Add a note" on a sheet (a tap in the app: guarded if you'd rather); the phone off through a reminder's time, if you like |
| 8b | 4.1.1 | **needed**: install; six `chat-ask`s (paid); "open it in Teams" brings Teams up (the phone unlocked, no Teams sync in front) | **needed**, 5 min, before the `chat-ask`s: each class's subject in Settings → About you, Teams' codes and Power Planner's names alike (the list you offered on 19 Oct), so each Power Planner class finds its code; on the phone, never in the repository |
| — | 4.1.2 | **needed**: install; a reply from his notification, then the chat opened: no *New messages* line over the message replied to | no |
| — | 4.1.3 | **needed**: install; a slot missed then its task finished: blocking as the day's other slots have it; `state`'s cost of a call | no |
| 8c | 4.1.4 | **needed**: install; four `chat-ask`s (paid): last week, December, an email's text, an email's attachment | no: the chat's eval runs on the PC (paid, about $6, the cut's cases run twice before and after), and its figures decide by the rule in [15](15-sent-or-asked-for.md) |
| 10a | 4.2.0 | **needed**: install; `chat-ask` (paid); a fact with an until an hour ahead; past it, `memory` once Settings → Felix has opened (what's run out goes only as it's next read); `felix-say` and a reply from the PC once 8a's SlotStart reminder has been seen (such a rule could quiet his words for it); the next morning; the version bump re-reads the open tasks (about 40 calls, 60 cents, online) | no |
| — | 4.2.1 | **needed**: install and the script; the archive's months' copies listed (`run-as … ls files`), each to go at the first start a week after it was made | no |
| 10b | 4.2.2 | **needed**: install; four `chat-ask`s and a `felix-say` (paid); the next morning's briefing; `review` for the summary | **needed**, twice, at your own pace: before the PR, read the register paragraph and the examples (taste: 10 min, by message, no phone); after the install, read a day of his messages and say what's off |
| 11 | 4.3.0 | **needed**, online: install; `sync --es sources gmail`; `state`; a minute's wait for the write; logcat's IMAP lines | **needed**, 20 min with Gmail open: star a note-to-self email in Gmail; tick its piece on Day; Undo within the grace (again, with a second email); aeroplane mode on and off; look in Gmail (unstarred, out of the inbox); the first-time sheet; the switch off in Settings and a tick that leaves Gmail alone |
| — | 4.3.1 | **needed**: install and the script; two `chat-ask`s (paid) with Gmail's finish switch off (`gmail-finishes --ez on false`, then `true` again) | no |
| 12 | 4.3.2 | **needed**: install and the script; the baselines | **helpful**: the done-when list read together, by message |
| — | 4.3.3 | **needed**: install and the script (a reset, a reviews-only day and a missed slot's block screen aren't made for a check: the tests hold them) | no |
| — | 4.3.4 | **needed**: install and the script; the day's slots read back (`calendar.json`), each task's steps in their order | no |
| — | 4.3.5 | **needed**: install and the script; the version bump re-reads the open tasks (about 40 calls, 60 cents, online), and a step marked "in any order" on its task's sheet | no |
| — | 4.3.6 | **needed**: install and the script; a school day's briefing, its rebalance seen in the chat with its Undo | **helpful**: the eval's splits read, by message |
| — | 4.3.7 | **needed**: install and the script; a task's form with its steps' "Any order" chips, the row's layout seen on the phone (a proposed task opened in its form isn't made for a check: the tests hold it) | no |
| — | 4.3.8 | **needed**: install and the script; a school day's briefing that asks about a task of the reading's, its rebalance seen in the chat with its Undo, the task's minutes as they were laid | no |
| — | 4.3.9 | **needed**: install and the script; his next messages in the new voice, checked against the day's slots | **helpful**: a day of his messages read again, by message |
| — | 4.3.10 | **needed**: install and the script; a school day's morning with a rebalance, its task's later steps kept at the times his message names (a dry run on a copy of the phone's data stood in on 23 Oct, your call) | no |
| — | 4.3.11 | **needed**: install and the script; his next messages, a greeting only on the first after three hours with nothing said, and nothing asked for that's ticked (23 Oct's 07:00 replayed on a copy of the phone's data: neither said the question wasn't picked) | no |

## In sum

- **The phone** is needed for 41 of the 42 PRs, for the install and its check: a plugged-in, unlocked phone on the desk for an evening does several PRs if their reviews are done by then. Two stages want it free of use for an hour (6b, on a weekend) or online for a minute's write (11).
- **You** are needed for nine moments, about an hour and a half at the phone in all: 5a, 5b, 5c (ten, ten and five minutes; done, in one evening with the three PRs stacked), 4.0.10 (five minutes, 19 Oct), 8a (fifteen minutes spread over an evening), 8b (five minutes: your classes' subjects), 11 (twenty minutes with Gmail), and 10b twice by message (a read before, a read after): with Stage 5's, 4.0.10's, 8a's, 8b's, 11's and 10b's read before done (11's on 21 Oct, an hour with its two finds), 10b's read after is left. Four more are helpful but not needed (3, 6b, 7, 12): five minutes each, or slower guarded taps.
- **No decision waits on you** during the work: every question a stage needs is answered, and the four things that could go wrong mid-PR have their fallback chosen.

## Still to check

Everything else in Stage 5 was checked on the phone with you on 18 Oct, or by the scripts after its last install, and two more of its checks on 19 Oct: the chat left on *Your changes* with a fold open, his next notification tapped, and the *Chat* tab showed at his message, under the *New messages* line, every fold closed. 8a's reminder and 10a's rule were checked on 21 and 22 Oct: the reminder came at its time in his words, and its *Done* chip, tapped in the chat (the same entrance as the notification's), marked it done with no call; the reply to a `felix-say` made a rule till 07:00, `felix-check` wrote nothing, and his next look's message was empty, as were the night's three nudges, none sent; at 07:00 the rule was gone (`memory`), let go of with the briefing. 10b's read was on 22 Oct, of that day's 15 messages: their number is right while you aren't using the app yet, as nothing gets ticked; he's to sound more like a teenager, with a quick greeting now and then, and to leave out how to do the work, where a due date stays (4.3.9). These want your taps, or a day of their own, whenever they come up; none needs a session of its own:

1. With more of his messages unread than a screen holds, open the chat (his button, not his notification): it opens at the *New messages* line, not at its foot.
2. Once the chat has messages past its window (a fortnight old and past the newest 200, days away), scroll back past them: the older rows show under *Earlier messages* with no Undo or buttons of their own, read a month at a time (4.0.4).
3. The week's check-in answered on a Sunday (in the chat or on its form): kept once in the log, and the week's review asked for, now through `Learning` (6e). 6e's check couldn't answer one from the PC on 19 Oct, as that week had its check-in already.
4. A slot missed today, then its task finished: blocking as the day's other slots have it (4.1.3; not seen live, as blocking applies only within the day's hours).

## When the phone is away

PRs built while the phone is away wait ready and unmerged, each stacked on the one before, with its APK kept in `private/apk/` (as every release is installed and checked before it merges, unless you say otherwise for a PR). When it's back, the build on `main` goes on first if the phone hasn't got it; then each PR in order is installed, given its check from the table above, and merged before the next is installed. A problem one shows is fixed on its branch and merged up the stack, and every branch above it rebuilt, its CI run and its kept APK replaced before it's installed, so what's checked is what merges.

So it went for Stage 6: `main`'s 4.0.4 went on first, then Stage 6's five PRs (#76 to #80, 6a to 6e), each checked and merged in turn. 6b's check ran with you nearby: protection disarmed from the PC and armed again, the device admin given back over adb.

## How to batch it

1. **Stages 0–4** (six PRs, the ground; done): three phone evenings with no need of you; the five minutes of taps in Stage 3 whenever you're passing.
2. **Stage 5** (the chat; done, in one evening with you): three short sessions with you, or one evening for all three.
3. **Stages 6, 9 and 7** (eight PRs, the services, the names and the data release; done): three or four phone evenings; a five-minute look after 7.
4. **Stage 8** (reminders, the gaps and what he's sent; done): one evening with you for 8a's fifteen minutes, a glance the next day for the SlotStart reminder; 8b your classes' subjects first, five minutes; 8c only the phone.
5. **Stage 10** (done; 10b's session with 4.2.1 and 11's on 21 Oct): 10a needed only the phone; the voice's text was read by you before 10b started. Left: your read of a day of his messages (§Still to check); 10b's briefing and 10a's rule gone the next morning were checked on 22 Oct.
6. **Stage 11** (done, 21 Oct): an evening with Gmail open, about an hour with its two finds (a star's kind that Gmail's website still draws, and a lost connection's first retry).
7. **Stage 12** (done, 22 Oct): only the phone; the done-when list ([README](README.md)), ticked by 4.3.2 with what holds, is yours to read together whenever you like.

On any evening the phone is connected and you're not using it, I can run a stage's install and its scripted checks, wait for your taps where they're needed, and carry on with the next PR's code meanwhile.

Each session that needs you is prepared first: its list reaches you before it, in the stage's PR, with every step in order, what to look for, and what the scripts have already checked, so your time at the phone goes only on what needs you.
