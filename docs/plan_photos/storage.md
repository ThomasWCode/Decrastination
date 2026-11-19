# What the server stores: the photos, the snapshots, the sizes in sight, and why the database grows

Asked for with the photos: how much they'll take, and whether R2 passes its free 10 GB. The photos take next to nothing, and none of it in R2. The database is what's on course to pass it, by itself.

## Where each thing is

| What | Where | Off the box |
|---|---|---|
| The database (`decrastination.db`): the stores, each one's history, the commands | the box's disk | Litestream carries it to R2; the nightly Backup-format file goes to B2 |
| A task's photo | a file on the box's disk by its hash, and on the phone | nowhere |
| A chat photo that's kept (6.8.0), a check's pages kept (6.5.0) | the same | nowhere |
| A check's pages, a chat photo with the box ticked | nowhere, once used | nowhere |

A photo is never a row: the database holds its hash. So no photo reaches R2 or B2, and a box that's lost loses the pictures it held, the messages and Felix's notes of them coming back with the database.

## What the photos will take (estimates)

- **A page**: the sample pages measured were 80 to 180 KB (scans, 1 240 by 1 754). A page from the phone's camera at 1 568 pixels isn't measured yet; 150 to 500 KB is planned for.
- **Kept by default**: nothing. A check's pages and a chat photo are deleted once used, the box ticked; what's kept is a line of text.
- **Kept when unticked**, a chat photo for 14 days from when it's sent (README, *Still to answer* 4): two a day, about 28 held at once, 4 to 14 MB; ten a day, about 140, 20 to 70 MB. There's no fixed most: it's whatever is sent unticked in a fortnight (forty a day, at half a megabyte each, would be 280 MB). The photos' folder is among the sizes in Settings (6.10.0).
- **The box's disk**: 91 GB free of 96 (18 Nov 2026), its photos' folder empty.

## What R2 holds (measured, 18 Nov 2026)

Counted in the bucket, seven and a half days after the replica's fresh path began (11 Nov):

| Litestream's | Files | Size | A day |
|---|---|---|---|
| snapshots, one a day | 8 | 1 033 MB | about 129 MB each, growing with the database |
| L1 (every 15 minutes) | 719 | 716 MB | about 95 MB |
| L2 (hourly) | 179 | 674 MB | about 90 MB |
| L3 (every 6 hours) | 29 | 372 MB | about 50 MB |
| in all | 978 | 2 795 MB | about 365 MB |

- L1 to L3 keep every file till it's behind the oldest snapshot kept ([hosting.md](../plan_web/hosting.md) §Litestream), so with 30 days' snapshots all of it is kept 30 days.
- At that rate, by estimate: 10 GB about the 27th day, 8 or 9 December, and about 11 GB once the 30 days are full; more if the database goes on growing, as each snapshot is the whole of it.
- The database was 344 MB that day, with 143 MB in its write-ahead log.

## The snapshots kept 14 days

The website's plan foresaw it ([plan_web/decisions.md](../plan_web/decisions.md): "near the limit, the snapshots go to 7 days, as the nightly file covers the long term"). The owner's word is 14.

- `deploy/litestream.yml`: `snapshot.retention` from `720h` to `336h`, its comment with it; `hosting.md` (§Litestream, and the day's requests, as L1 then holds 14 days' files, not 30), `spec/server.md` §Deployment, `plan_web/data-and-sync.md`.
- By estimate, at the database's size now: 5 to 7 GB, 14 days of L1 to L3 at about 235 MB a day (3.3 GB) and 14 snapshots (129 MB each so far, on average; the newest are bigger). It doesn't stay there: the database is still growing (§What trims it), and with it about 8 GB in mid-December and 9 to 11 GB by February, unless 6.11.0 lessens it first (README, *Still to answer* 5).
- What it costs: a restore to a moment reaches a fortnight back, not a month. The nightly file in B2 is the long term, as it was to be.
- Nothing's lost by it today: the bucket holds eight days.
- On the box: the file put there after the merge (the timer pulls images alone), Litestream restarted, `box_files.py` showing the box's file is main's, and the bucket counted again a few days on. A step of its own, at the owner's word.
- A docs-and-deployment PR, no version.

## What trims it, and what it comes to

Nothing here grows without end; each part has a rule that lets go of the old. What they add up to is the question.

**In R2**, Litestream's own rules ([hosting.md](../plan_web/hosting.md) §Litestream):

- a snapshot older than `snapshot.retention` is deleted;
- L1 to L3's files are deleted once they're behind the oldest snapshot kept, so they last as long as the snapshots do;
- L0's files go minutes after they're compacted.

So R2 holds about the days kept, times a snapshot and a day's changes: 14 × (a snapshot + about 235 MB). A snapshot is the whole database, so it's the database's size that decides it.

**In the database** (`ServerGraph`'s daily trim, and at each start):

- a store's row is replaced by each write, never added to: all the stores together are about a megabyte;
- `store_history` keeps every row for a day, then each store's last of each hour, for 30 days (D-151): about 135 MB for the day at the present rate, and by estimate up to 13 MB for each older day, some 500 MB once the 30 days are full, in mid-December;
- the record of commands is kept 90 days: 69 MB in its first fortnight, so by estimate some 430 MB once the 90 days are full, in early February;
- the file itself never shrinks (the server never vacuums it): room let go of is used again, so the file stays at the most it has needed.

So the database comes, by estimate, to about 650 MB in mid-December and about 1 GB by February, and then stays. A snapshot has been about half the database's size, so 14 of them are about 4.5 GB in mid-December and 7 GB or more by February, with 3.3 GB of L1 to L3 beside them. These rest on a week's figures and on the snapshots staying half the database's size, neither of which is sure.

What would lessen it, in the order it helps: fewer and smaller writes (6.11.0: a day's changes and the history both); the commands kept less long, or without what they needn't hold; fewer days of snapshots.

## 6.10.0: the sizes in Settings, and an alert

**Can it be measured?** Yes.

- **The box's own**, with no credential: the database's file and its write-ahead log, the photos' folder, the disk's free room.
- **R2's**: Cloudflare's analytics API gives a bucket's stored size and count (`r2StorageAdaptiveGroups`) and its requests by kind (`r2OperationsAdaptiveGroups`), over at most 31 days. One question a day, with a token that can read analytics and nothing else. The server holds no R2 credential now (Litestream's container has the bucket's key, the server B2's alone), and this one can't read or write the bucket. Which requests are Class A isn't given: it's worked out from each one's kind, by R2's own pricing page.
- **B2's**: the server's key for the nightly file may not be able to list its bucket. If it can't, the figure is what the server knows it has written and kept, said to be that.

**What's shown**, in Settings on the phone and the website, from one view the server makes (`SettingsViews`): each figure beside its free allowance (R2: 10 GB stored, a million Class A requests and ten million Class B a month; B2: 10 GB; the disk), when it was last read, and the date an allowance would be passed at the last week's rate. A figure that couldn't be read says so, with why.

**The alert.** A notification of its own (its own id and channel, so it replaces no other) and a line among the website's notices, at 80% of an allowance and again at 95%, once each a month for each allowance (README, *Still to answer* 2). November's bill was for requests, not size: the requests are watched as the size is.

**Needs.** A new credential on the box, made by the owner in Cloudflare; its exact permission is read from Cloudflare's own page before the steps are sent. A daily job of the server's, in its jobs' list, which fails quietly and says so in Settings.

**Tests.** The view from made-up figures (each line, a figure missing, the projected date); the alert's lines, once each; Cloudflare's answer parsed from a recorded one, never asked in a test.

**Docs.** `spec/server.md` (the job, the credential), `spec/website.md` and the phone's Settings in the specs, `plan_web/hosting.md` (the token), `spec/privacy.md` (what's asked of Cloudflare: sizes and counts).

## 6.11.0: why the database grows

**Measured on the box, the last 24 hours to 18 Nov 2026**: each store's size, and the rows and megabytes its history gained.

| Store | Its size | Rows of history | Their size |
|---|---|---|---|
| `tasks.json` | 179 KB | 433 | 75 MB |
| `chat.json` | 334 KB | 129 | 39 MB |
| `model.json` | 29 KB | 330 | 10 MB |
| `calendar-read.json` | 49 KB | 143 | 7 MB |
| the rest together | | about 120 | about 3 MB |

- A store is written whole, and each write adds its whole text to `store_history` (D-151 thins it after a day, which frees the room later but not the writing). About 135 MB of history a day, and the store's own row written again each time: what Litestream carries.
- The record of commands: 10 812 rows and 69 MB in the fortnight since the switch, kept 90 days.
- `session.json`, where 6.4.0's notes go, is 4 KB and gained 52 rows: the notes cost it nothing to speak of.

**To find first**, before anything's changed: why `tasks.json` is written 433 times a day. A guess, not checked: a read of a source writing it again when nothing but a time it was last seen has changed. And what the commands' 69 MB is made of.

**Then the least that mends it**, by what's found: a write that changes nothing not made; a time that changes each read kept apart from what's kept in history; or the history's rows kept smaller. Each is weighed against "no data is ever lost": the history is what a store that can't be read comes back from.

**Its measure**: `private/tools/box_churn.py` before and after, and the bucket's daily growth.

**Docs.** `spec/server.md`, `plan_web/data-and-sync.md`, `decisions.md` if D-151 changes.
