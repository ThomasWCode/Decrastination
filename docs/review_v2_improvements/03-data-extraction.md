# 3. Data extraction and sources

What each source gives the app, what it leaves on the table, and how a sync could cost less. Sources: Gmail over IMAP (`sources/gmail`), the Teams widget's provider (`sources/teams`), Power Planner's web API (`sources/powerplanner`), AnkiDroid's provider (`sources/anki`), and the device calendar (`learn/CalendarTime.kt`, `learn/EventJudge.kt`).

## Summary

| ID | Improvement | Priority | Effort | Decision |
|---|---|---|---|---|
| DX-01 | Attachment names and types, from the structure already fetched | P1 | S | – |
| DX-02 | Calendar invites in emails (`text/calendar`) as events | P3 | M | – |
| DX-03 | Starred and Important as signals | P2 | S | – |
| DX-04 | Bulk-mail headers as a hint | P2 | S | – |
| DX-05 | Sender rules the student sets | P2 | M | Q-11 |
| DX-06 | Strip quoted replies and signatures before the model sees them | P2 | S | – |
| DX-07 | Read only the inbox's recent part each sync | P1 | M | – |
| DX-08 | Teams: the instructions' links and attachments | P2 | S | – |
| DX-09 | Power Planner: the timetable, for tomorrow's lessons | P2 | M | Q-12 |
| DX-10 | Anki: deck descriptions, so topic-named homework finds its deck | P2 | S | – |
| DX-11 | Anki: seconds per card from real sessions | P2 | M | – |
| DX-12 | Calendar: location and description for the event judge | P2 | S | – |
| DX-13 | Calendar: choose which calendars count | P1 | S | – |
| DX-14 | Calendar: look as far ahead as the planner plans | P1 | S | – |
| DX-15 | The same work from two sources | P2 | M | Q-13 |

---

## DX-01 Attachment names and types, from the structure already fetched

**Now.** To find the text part, `text()` fetches each message's BODYSTRUCTURE (`GmailSource.kt:258`) and `Mime.collect` walks it, skipping any part with an `attachment` disposition (`Mime.kt:48`). The names of those parts are in the same structure (the disposition's `filename` parameter, or the body's `name` parameter) and are thrown away.

**Proposal.** Have `Mime.collect` also return `Attachment(name, type, size)` for the skipped parts and for non-text parts (a PDF, an image, a `.docx`). Store them on the thread (`EXTRA_ATTACHMENTS`), send them to the model (AI-04), and show them on the task sheet as a line ("2 attachments: consent-form.pdf, itinerary.docx") that opens the thread in Gmail (the sheet's existing open action).

**Final state.** "Please return the attached form" reads as a form task, and the student can see which file it means without opening Gmail first.

**Caveats.** No extra round trips: the structure is already fetched. Inline images (`inline` disposition, `image/*`) should be counted but not named, or every signature logo becomes an "attachment". Sending the attachment itself to the model (a PDF as a document block) is possible and would let Claude read the form, at a cost per page and a privacy cost; I'd leave that until a case shows it's needed.

**Effort.** S.

## DX-02 Calendar invites in emails (`text/calendar`) as events

**Now.** An invitation (`text/calendar` part, or an `.ics` attachment) is just a message with a text part; the model reads "You're invited to the sixth-form parents' evening" and guesses the time from the prose.

**Proposal.** When the structure has a `text/calendar` part, fetch it (it's small) and parse `SUMMARY`, `DTSTART`, `DTEND`, `LOCATION`. Give the task the exact time as its due (`EventTime` already exists as a change type) and mark it an Event, so the planner treats it as busy time via the same path the calendar uses; or, simpler, show "Invite: Thu 16 Oct 18:00–20:00, Main Hall" on the sheet and let the student add it to the calendar.

**Caveats.** School emails rarely carry invites; parents' forwards sometimes do. Priority P3 for that reason; the parsing is small (four fields, one date format) but it's a new path to test.

**Effort.** M.

## DX-03 Starred and Important as signals

**Now.** `X-GM-LABELS` is fetched for every message (`GmailSource.kt:216`) and used only to spot `\Sent` (`GmailSource.kt:177`). `\Starred` (the student's own flag) and `\Important` (Gmail's ranking, which knows the student's reply history) are dropped.

**Proposal.** Keep both on the thread. Starred: never Info, never hidden; shown with a star on the list; sent to the model as "The student starred this". Important: sent to the model as a hint ("Gmail ranks this as important") and used by the rules as a tie-break for effort. A thread the student stars after Claude hid it comes back (that's a second route to AI-11).

**Final state.** The star in Gmail becomes the student's "this one matters" without opening the app.

**Caveats.** `\Important` is noisy (Gmail marks anything from a frequent correspondent). Treat it as a hint only.

**Effort.** S.

## DX-04 Bulk-mail headers as a hint

**Now.** Only the envelope is fetched. `List-Unsubscribe`, `Precedence: bulk` and `Auto-Submitted` headers, which mark newsletters and automated mail, aren't read. The rules use the sender's address (`NO_REPLY`, `EmailRules.kt:55`).

**Proposal.** Add `BODY.PEEK[HEADER.FIELDS (LIST-UNSUBSCRIBE PRECEDENCE AUTO-SUBMITTED)]` to the envelope fetch (one more item in the same command, a few bytes a message). Store `bulk: Boolean`. Send it to the model as "Sent as a mailing-list or automated message", and let the rules lower a bulk email's default effort to 2 minutes.

**Caveats.** As AC-09 says: school mailers (ParentMail, SchoolComms, Arbor) use these headers for forms and trip letters too, so bulk never means Info by itself. It's a hint.

**Effort.** S.

## DX-05 Sender rules the student sets

**Now.** Instructions are per task. "Everything from this sender is just reading" has to be said again for each email, and the model is asked each time (AC-09).

**Proposal.** A `SenderRule(address or domain, kind: Info | Admin, note)` list in Settings and a `ChangeType.Sender` the chat and instructions can propose ("From now on, newsletters@ucas.com is only to read"). Applied before enrichment: a matching thread gets the rule's kind from the rules without a model call, with `by = "sender rule"` shown on its sheet. Suggested automatically after three Not-a-task marks from one address, as a card the student confirms.

**Final state.** The inbox's recurring noise is settled once. The hidden list (AI-11) shows which rule hid what.

**Caveats.** A rule on a mixed sender hides real tasks. Suggest, never apply silently; rules listed and deletable; domain-wide rules only for obvious newsletter domains.

**Effort.** M. **Decision.** Q-11.

## DX-06 Strip quoted replies and signatures before the model sees them

**Now.** The newest message's text goes to the model whole, up to 16,000 characters (`Prompts.MAX_TEXT`). A reply carries the whole thread below "On Tue, 7 Oct 2026, X wrote:" (or Gmail's `gmail_quote` div), plus signatures and legal footers. `Mime.htmlToText` (`Mime.kt:122`) flattens HTML but doesn't cut quotes.

**Why.** Tokens, and anchoring: the model may answer the quoted request rather than the newest line ("Thanks, done" above a 500-word request reads as the request).

**Proposal.** In `htmlToText`, drop `blockquote` and `div.gmail_quote` subtrees; in plain text, cut at the first line matching `^On .+ wrote:$` or `^-----Original Message-----`, and at `^-- $` (the signature delimiter). Replace the cut with "[Earlier messages in this thread were left out: N characters.]" so the model knows (AI-04). Keep the uncut text in the store for the sheet.

**Final state.** The model reads the new words and knows there were old ones. Input tokens on replies drop by most of their size.

**Caveats.** Forwarded emails ("FW: please see below") have their content in the quoted part; "Forwarded message" markers should keep the text and only cut after a second quote level. A regex that cuts too much is worse than none: test it on the eval set's threads.

**Effort.** S.

## DX-07 Read only the inbox's recent part each sync

**Now.** Each sync lists every UID in the inbox (`uidSearch("ALL")`, `GmailSource.kt:214`) and fetches the envelope and labels of all of them in batches, then texts for up to 60 new messages (`MAX_BODIES`) within the sync's time budget. An inbox of 3,000 messages means 3,000 envelopes every sync, on the phone's data, before anything new is seen.

**Proposal.** Two reads instead of one:

- the listing limited by date: `UID SEARCH SINCE <date 60 days ago>` (the merge keeps finished tasks for `KEEP_FINISHED_MS` anyway; older threads can't become tasks);
- and, for threads the app already knows, a flags-only fetch (`UID FETCH <known uids> (X-GM-LABELS FLAGS)`) to catch archiving, starring and deletion, instead of the full envelope.

Keep `UIDVALIDITY` and fall back to the full read when it changes (a rebuilt mailbox).

**Final state.** A sync that moves a few kilobytes instead of a few hundred; faster syncs on mobile data; the 90-second budget (`sync/Syncer.kt:62`) spent on bodies, not listings.

**Caveats.** `SINCE` is by the message's internal date; a thread whose newest message is old but which the student replies to today has a new message with a new date, so it's caught. A message moved from another folder into the inbox with an old date would be missed; that's rare and the student can star it (DX-03). The watchdog on "listed N but described M" (`GmailSource.kt:218`) stays.

**Effort.** M.

## DX-08 Teams: the instructions' links and attachments

**Now.** The widget's provider gives title, description (instructions), class, due time, tab and due text (`sources/teams/TeamsSource.kt:76`). Whether the provider exposes an assignment's attached files and links depends on the widget (it's your code too); the app doesn't ask for them.

**Proposal.** If the widget can carry them, add `attachments` (names) and `links` (URLs) columns, and show them on the task sheet as tappable lines; send names to the model with the instructions (a "Statics booklet.pdf" attachment tells the model what "the booklet" is). The Teams open action already takes the student to the assignment.

**Caveats.** Depends on the widget's data model; if its provider doesn't have them, it's a change there first. Reading the attachments' content (Teams' Graph API) is out of scope for the widget's permission model.

**Effort.** S here, unknown in the widget.

## DX-09 Power Planner: the timetable, for tomorrow's lessons

**Now.** `GetClassesAndSchedules` is read (`PowerPlannerApi.kt:67`) and used to turn an item's date into a due time at the lesson's start (`PowerPlannerItems.slot`). The timetable itself (which subjects have lessons on which days) isn't used anywhere else.

**Proposal.** Two uses:

- for the planner's ordering (PL-03): among work due the same day, the subject with the earlier lesson first; and, with the student's say-so, a task with no due date but a subject with a lesson tomorrow gets a soft nudge forward;
- for the briefing: "Tomorrow: Physics P1, German P3; Physics prep is planned tonight".

Also use the timetable's end-of-day for "school ends at" in the student profile (AI-05) instead of a typed setting.

**Caveats.** Only Power Planner has the timetable, and only if the student keeps it up to date. Two-week timetables are handled already (`scheduleWeek`). This is a nice-to-have that depends on the student's data hygiene.

**Effort.** M. **Decision.** Q-12.

## DX-10 Anki: deck descriptions, so topic-named homework finds its deck

**Now.** The decks query reads `deck_id, deck_name, deck_count, options` (`AnkiSource.kt:39`). Section matching (`AnkiRules.sectionsOf`, `AnkiRules.kt:144`) works on numbered sections ("1.3") in the deck name. Homework that names a topic ("learn the holidays vocab") can't be matched unless the deck's name has the number.

**Proposal.** Read `deck_desc` as well (AnkiDroid's `FlashCardsContract.Deck.DECK_DESC`). A deck whose description holds the topic ("Holidays and travel") is matched by the topic words in the homework's instructions, after the numbered match fails; the model's `ankiSections` field can be given the deck list with descriptions ("1.3 Holidays and travel") so it can name sections by topic too.

**Caveats.** Depends on the decks' descriptions being filled in, which is a one-off job in AnkiDroid. A loose word match ("school" matches everything) must be whole-phrase, and a single candidate.

**Effort.** S.

## DX-11 Anki: seconds per card from real sessions

**Now.** A deck task's minutes come from `REVIEW_SECONDS` per card (`AnkiRules.kt:63`), one constant for every deck and every student. The focus session knows how long the student spent in AnkiDroid on a deck piece, and the deck's counts before and after.

**Proposal.** After a session on a deck piece, record `(cards cleared, minutes)` from the counts' difference; keep a per-deck median of seconds per card over the last ten sessions and use it instead of the constant when there are three or more. Show it on the Anki settings line ("German 1.3: 9 s a card from 6 sessions").

**Caveats.** Counts change for reasons other than the session (cards becoming due at midnight, a desktop session). Only count a session that started and ended on the same day with the deck's counts read on both sides. This is a calibration like the kind multipliers (`Calibrator`), and could live there.

**Effort.** M.

## DX-12 Calendar: location and description for the event judge

**Now.** `CalendarTime.read` (`CalendarTime.kt:67`) reads title, times, all-day, availability, calendar name and attendance; `EventJudge` decides free, busy, ask from the title (travel words, `EventJudge.kt:49`), the calendar name (holidays, birthdays) and length.

**Proposal.** Read `EVENT_LOCATION` and `DESCRIPTION` too. Location: an event with a location far from home or school is a travel day (ask); an event with no location and no attendees is likelier a reminder than a commitment. Description: "optional", "tentative", "if you can" push toward Ask or Free; a Teams/Zoom link makes a timed event busy. Both are also useful on the Calendar page's rows and in the chat's context (the model can then answer "is the Warwick day all day?").

**Caveats.** Descriptions can be long (a school newsletter pasted in); cap at 300 characters in the context. Judging by location needs a "home" and "school" you'd type; a simpler first step is just showing it.

**Effort.** S.

## DX-13 Calendar: choose which calendars count

**Now.** Every calendar on the device is read; a regex on the calendar's name drops holidays, birthdays and week numbers (`NOT_YOURS`, `EventJudge.kt:50`). A shared family calendar, a sports-club feed or a parent's work calendar synced to the phone all count as the student's busy time.

**Proposal.** A list of the device's calendars in Settings → Calendar (name, account, colour), each with a tick; default all ticked except the ones the regex names. Store the excluded `CALENDAR_ID`s; filter in `read`. Show on the Calendar page which calendar each event came from (already read as `calendar`).

**Caveats.** New calendars appear ticked by default (safer: busy time counted rather than missed). The regex stays as the default for the first run.

**Effort.** S.

## DX-14 Calendar: look as far ahead as the planner plans

**Now.** Events are read 14 days ahead (`LOOK_AHEAD_MS`, `CalendarTime.kt:43`); the planner places dated work up to 90 days out (`Planner.MAX_HORIZON_DAYS`, `Planner.kt:71`). A revision block placed on a day three weeks away lands on a school trip the planner can't see, and gets re-planned when the trip comes into view, moving work later rather than earlier. Your v1 verdict on this (IMP-P2-005) was "worth a look once exam season shows it".

**Proposal.** Read busy time to the planner's horizon (one query with a wider range; the device calendar is a local database, so it's cheap), keep the questions ("All of it / A few hours / None") to the current 7 days (`ASK_AHEAD_MS`) so the student isn't asked about things six weeks away, and treat unanswered all-day events beyond that as "a few hours" rather than nothing.

**Final state.** Revision for a mock in six weeks is laid around the half-term trip from the start.

**Caveats.** More events in the context the chat sends; cap the chat's list at the fortnight as now. Exam season (May) is when this matters; it's small enough to do before then.

**Effort.** S.

## DX-15 The same work from two sources

**Now.** Tasks are keyed by source and id (`core/Merge.kt`). The same homework can arrive from Teams and from Power Planner (if the student copies it there), and an email about an assignment is a third task; the student sees two or three and works one.

**Proposal.** A light duplicate detector run after merge: same class (via the class map, AI-05), titles that share most of their words, due within a day. A match becomes a card in the chat or a line on the sheet ("Looks like the same work as 'Statics Prep' from Teams"), with one tap that links them: the linked task is hidden, its worked minutes credited to the other, and its source's done state still ends it. Stored as `ChangeType.SameAs`. The model could be asked in the chat to confirm uncertain pairs.

**Caveats.** False links hide real work; so never automatic, always a tap, and listed in the hidden section (AI-11). The title match needs care with recurring homework ("Prep 30/09", "Prep 07/10").

**Effort.** M. **Decision.** Q-13.

---

## What I looked at and left alone

- **IMAP over the Gmail API.** A hand-rolled IMAP client reading `BODY.PEEK` is the right call for a read-only app with an app password; the Gmail API would need OAuth and Google's verification. Stay.
- **Reading attachment contents** (PDF forms as document blocks): see DX-01's caveat; possible, not yet justified.
- **Power Planner's `HashedKey`**: the web app's own dev key, rate-limited; the app's volume (one agenda read a sync) is far below it. Nothing to do unless it stops working.
- **Teams via Graph** rather than the widget: the widget's signature-permission provider is the point (no Graph consent for a school tenant). Stay.
- **Anki `review_info`**: it gives the next card, not timings; DX-11 gets timing from the sessions instead.
