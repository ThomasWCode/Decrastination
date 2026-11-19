# Photos in more places, Day's menus on the website, and the server's storage in sight

The plan for nine pieces of work, a deployment change and eight releases (6.4.0 to 6.11.0), written before any is built (18 Nov 2026). Nothing here is built yet; the specs say how the app works now.

- [photo-checks.md](photo-checks.md): the photo check itself, its doors on the phone, and its door on the website (6.4.0, 6.5.0, 6.7.0).
- [website-day.md](website-day.md): the website's Day, each item opening its own menu (6.6.0).
- [chat-photos.md](chat-photos.md): photos in the chat with Felix, and one sent as proof handed to the photo check (6.8.0, 6.9.0).
- [storage.md](storage.md): what the server stores and where, the snapshots kept 14 days, the sizes in Settings with an alert, and why the database grows (the deployment change, 6.10.0, 6.11.0).

## What was asked

1. More pages in a photo check.
2. A check remembers what earlier ones saw, so pages 8 to 10, then 11 and 12, pass a task of pages 8 to 12.
3. Work's camera button asks what the photo is for.
4. *Check with a photo* on Day's pop-ups.
5. Photos in the chat with Felix.
6. On the website's Day, an item clicked opens its menu, as on the app, with the photo option.
7. Photos picked from the gallery on the phone.
8. A tick box for whether a photo used or sent is deleted or kept, ticked by default.
9. How much the server stores shown in Settings, with a notification as it nears what's billed for.
10. The database's snapshots kept 14 days, not 30.

## What's there now

- **Three pages a check** (`PhotoChecker.MAX_PAGES`, AI-17), each shrunk to 1 568 pixels on its long side. The API takes far more: a page that size is about 2 350 tokens when it's a page's shape and about 3 140 at the most, a square, so a cent or a little over on the model the check uses.
- **A check knows nothing of the one before.** Its pages are deleted whatever it says, and its answer's `seen` goes nowhere. Pages 8 to 10 are refused for want of 11 and 12, and 11 and 12 for want of 8 to 10.
- **Day's pop-up for a piece to do already has *Photo check*** (`PieceActions`), shown while Claude is on and the piece is written homework that isn't Anki's (`Chunk.photoCheckable`). A missed slot's sheet has none. A slot ticked *Done* can't be tapped at all, and a photo earns for a tick only if it's taken within half a minute of it (`Focus.photoEarns`, `AGAIN_MS`): a longer piece ticked first has no way to its photo afterwards, though Felix is told it "earns its free time only with a photo of it".
- **Work's camera button** goes straight to a photo of homework read into tasks (NF-01).
- **The chat is text throughout.** Each turn is one string (`ChatReader`), a message with no words is dropped (`Conversation.send`, and both boxes' Send), and one message of yours can be answered in up to three calls, each sending the conversation again.
- **The website's Day opens a piece's task, not the piece.** A click opens the task's sheet, whose *Done*, *Start* and *Later today* are for the task's next piece (`TaskSheetModel.next`), whichever piece was clicked. A missed slot, a kept one and a calendar event open nothing: `BlockView` carries no actions, and for those no task, piece or event to act on.
- **Photos are files, never rows.** A task's photo is a file on the phone and one on the server's disk by its hash (`Photos.hashOf`), sent ahead of the command that names it (`AttachPhoto`). The database, which is what Litestream carries to R2, holds the hash alone.
- **R2 held 2.8 GB on 18 Nov 2026**, seven and a half days after its fresh path, about 365 MB more each day: on course for its free 10 GB in early December, with no photo in it ([storage.md](storage.md)).

## The owner's answers (18 Nov 2026)

These go into `docs/plan_v4/answers.md` from Q151 on, and what they decide into `docs/decisions.md` from D-154 on, as each release is built.

1. **Which of Day's pop-ups get the check?** A missed slot's sheet, and a piece ticked *Done*, which gets a pop-up of its own.
2. **What does Work's camera button offer?** All three: new tasks from a photo, a check of work with a photo, a photo added to a task.
3. **How many pages a check?** Ten. Offered too: six, twenty.
4. **What's kept from an earlier check?** Claude's note of what it saw; the photos deleted as now. Offered too: the photos themselves, sent again; both, the photos for a day.
5. **Whose checks count?** The whole task's, each note with its piece and whether it passed. Offered too: the same piece's alone.
6. **What may Felix do with a photo in the chat?** See it and answer; make or change tasks from it; keep it with a task; and count it as proof of work.
7. **Who decides whether a photo sent as proof passes?** The photo check, given the picture itself, with Felix's summary of whatever else in the message bears on it. Offered too: Felix himself; no proof in the chat, Felix pointing to the check.
8. **What does the website's Day gain?** A clicked piece's own menu, a missed slot's sheet, a calendar event's sheet, *Ask Felix about it*, and the photo option, as on the phone.
9. **How does a photo check work on the website, where any saved picture can be chosen?** Any picture, the same judge, the same earning. Offered too: any picture but earning nothing; checks kept to the phone.
10. **How long does Felix go on seeing a chat photo?** With any message sent within ten minutes of it, by itself; after that he's told it's there, for as long as its message is in what he's sent, and asks to see it. Offered: your next few messages; once only; as long as it's in his history.
11. **How long is a chat photo's file kept?** While its message is in the chat's window, then deleted; not in backups. Offered too: as long as the message; a day. The window was put to the owner as "the newest 200, or 14 days", which is wrong: it keeps whichever of the two is more (D-96, `Outbox.past`), so a message can stay in it for months in a quiet chat (*Still to answer* 4).
12. **Which photos does the tick box delete?** The app's and the server's copy, once it's used, Felix's note of it staying; the gallery is never touched. Offered too: the original in the gallery; a camera photo saved to the gallery when unticked.
13. **Is the box ticked by default in the chat too?** Yes, everywhere. Put to the owner with what it means: a chat photo is gone once Felix has answered it, so answers 10 and 11 hold only for a photo sent with the box unticked. Offered too: the chat's box unticked by default, a check's ticked, as a chat photo's file is small and on the server's disk, not in R2.
14. **Said without being asked**: every recommendation below is built; the gallery can be picked from on the phone; the storage is shown in Settings if it can be measured, with a notification near what's billed for; the snapshots are kept 14 days.

## The work, in order

| | What | Where |
|---|---|---|
| 1 | The snapshots kept 14 days, not 30: `deploy/` and its docs, then the box | [storage.md](storage.md) |
| 2 | **6.4.0**: ten pages, earlier checks remembered, each page named, a page retaken, *Shown so far* | [photo-checks.md](photo-checks.md) |
| 3 | **6.5.0**: the check from a missed slot and a slot ticked *Done*, Work's camera menu, the gallery, the tick box | [photo-checks.md](photo-checks.md) |
| 4 | **6.6.0**: the website's Day, each item its own menu | [website-day.md](website-day.md) |
| 5 | **6.7.0**: the photo check on the website | [photo-checks.md](photo-checks.md) |
| 6 | **6.8.0**: photos in the chat | [chat-photos.md](chat-photos.md) |
| 7 | **6.9.0**: a chat photo as proof, handed to the photo check | [chat-photos.md](chat-photos.md) |
| 8 | **6.10.0**: the storage in Settings, and its alert | [storage.md](storage.md) |
| 9 | **6.11.0**: why the database grows, and less of it | [storage.md](storage.md) |

The snapshots come first for their date. 6.4.0 is what the owner opened with. 6.6.0 shares no code with the photos and can be built beside them. Each is a PR of its own, stacked no more than two deep. A release that adds a field to a command or a store goes to the server before the phone.

## Still to answer

Each has what's built meanwhile, so none holds the work up.

1. ***Check it's done* on the website.** The app's button asks the task's source; the server's own read of a source is reached only through Felix, with no address the website can call. Recommended: left out of 6.6.0, as *Open* and *Refresh Teams* are, and added later if it's missed. Built meanwhile: left out.
   ANSWER:
2. **The alert's lines.** Recommended: a notification at 80% of a free allowance and again at 95%, once each a month for each allowance, with the date it'd be passed at the present rate. Built meanwhile: those.
   ANSWER:
3. **What a kept check's pages are for.** Unticked, a check's pages aren't deleted. Recommended: kept with their task, shown on its sheet, and sent again with a later check of the task where its ten pages have room, the new ones first; deleted when the task's done or gone, at a reset, or 14 days after they were taken, whichever is first, as a kept chat photo is (4). Built meanwhile: that.
   ANSWER:

4. **How long a kept chat photo's file lasts, as the window isn't what the owner was told.** The chat's window keeps a message while it's among the newest 200 or within 14 days, whichever keeps more, so "while it's in the window" has no limit in days. Recommended: a kept photo's file is deleted 14 days after it's sent, or at a chat reset, whichever is first: what the owner was told, and a limit that holds however quiet the chat. Its message stays where it is, with Felix's note. Built meanwhile: that.
   ANSWER:
5. **Whether 6.11.0 comes sooner.** Fourteen days of snapshots hold R2 under its 10 GB only while the database stays near its size now, and it's still growing ([storage.md](storage.md) §What trims it): by estimate, 14 days come to 7 to 8 GB in mid-December and about 10 GB by February, and the week's own growth is quicker than that reckons on. Recommended: 6.11.0 second, straight after the snapshots, as it mends the cause, lessens both what a snapshot holds and what changes each day, and is a few hours; or the snapshots at 7 days, as the website's plan first had it. Past its 10 GB R2 charges $0.015 a gigabyte a month (its pricing page, 30 Oct 2026), so a gigabyte or two over is a few cents a month either way. Built meanwhile: the order above.
   ANSWER:

## Decided without asking

1. **The notes are kept in `session.json`**, beside the photo checks found done, written by `Focus`: a small store, written about 50 times a day on the server. Not on the task: every sync's merge would have to carry them, and every store that holds a task would change shape.
2. **Eight notes a task at most**, the newest, gone with the task.
3. **A check from a slot ticked *Done* earns that day only**, as a kept slot's earning already does (`Focus.earnKept`).
4. **Four photos a chat message.** A check takes ten; proof of more than four pages is the check's own door.
5. **The cap's dearest call stays $1.35** (`Pricing.WORST_CALL_USD`). A check's text is cut to a bound of its own so ten pages fit inside it, and a chat photo counts against the chat's 40 000 characters, each as the characters its tokens would be; a picture is costed at its dearest shape, a square, as one from the gallery or the website can be any shape.
6. **A page stays 1 568 pixels.** The model reads up to 2 576; handwriting is read at the smaller size, at half the tokens.
7. **What the owner said to Felix reaches the check as the student's word**: it can say what was set, not stand in for the work being seen.
8. **A picture chosen from the gallery is taken as of when it's chosen.** Nothing rests on when it was taken once a kept slot's check names its slot (6.5.0).
9. **Nothing earns twice from the notes.** The whole task's notes count as shown (answer 5), but a check must show work no check that passed has shown, so a later sitting can't pass on an earlier one's pages ([photo-checks.md](photo-checks.md)).
10. **The notes are in the backups**, as with the pages deleted they're the only record of what was shown.
11. **A kept photo lasts 14 days at most**, a check's pages as a chat's photo (*Still to answer* 3 and 4), and a sweep lets go of any photo file nothing names.

## Not checked yet

- How big a real page from the phone's camera is once shrunk: the sample pages measured were scans, 80 to 180 KB each.
- Whether anything in front of the server limits a request's size, and the box's room for ten pages held at once.
- The chat's second round, in which Felix is given what he asked for, read in outline and not line by line.
- How a build from before a new field treats it, on the phone and the server.
- The permission a Cloudflare token needs to read R2's figures.

`CLAUDE.md`'s *Where things are said* names this folder while it's in use; each file is folded into `answers.md` and the specs, and deleted, as its releases are built.
