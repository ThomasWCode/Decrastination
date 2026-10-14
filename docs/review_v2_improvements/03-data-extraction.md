# 3. Data extraction and sources

What each source gives the app, what it leaves on the table, and how a sync could cost less. Sources: Gmail over IMAP (`sources/gmail`), the Teams widget's provider (`sources/teams`), Power Planner's web API (`sources/powerplanner`), AnkiDroid's provider (`sources/anki`), and the device calendar (`learn/CalendarTime.kt`, `learn/EventJudge.kt`).

Revised after your answers: Gmail becomes starred-only (Q-04), which removes three items and most of a fourth; the Power Planner timetable is used now (Q-12) and becomes the source of the school-day windows the planner fills (PL-15).

## Summary

| ID | Improvement | Priority | Effort | Status |
|---|---|---|---|---|
| DX-01 | Attachment names and types, from the structure already fetched | P1 | S | – |
| DX-02 | Calendar invites in emails (`text/calendar`) as events | P3 | M | – |
| DX-03 | Read only starred emails, and every starred email is a task | P1 | M | Changed (Q-04) |
| DX-04 | Bulk-mail headers as a hint | – | – | Withdrawn (Q-04) |
| DX-05 | Sender rules | – | – | Withdrawn (Q-11) |
| DX-06 | Strip quoted replies and signatures before the model sees them | P2 | S | – |
| DX-07 | The sync after starred-only: the Starred folder, flags for the known | P1 | S | Changed |
| DX-08 | Teams: the instructions' links and attachments | P2 | S | – |
| DX-09 | Power Planner: the timetable, for free periods, lessons and the school day | P1 | M | Changed (Q-12) |
| DX-10 | Anki: deck descriptions, so topic-named homework finds its deck | P2 | S | – |
| DX-11 | Anki: seconds per card from the counts around a slot | P2 | M | Changed (note 1) |
| DX-12 | Calendar: location and description for the event judge and the Day view | P2 | S | – |
| DX-13 | Calendar: choose which calendars count | P1 | S | – |
| DX-14 | Calendar: look as far ahead as the planner plans | P1 | S | Changed (now needed) |
| DX-15 | The same work from two sources | P2 | M | – |

---

## DX-01 Attachment names and types, from the structure already fetched

**Now.** To find the text part, `text()` fetches each message's BODYSTRUCTURE (`GmailSource.kt:258`) and `Mime.collect` walks it, skipping parts with an `attachment` disposition (`Mime.kt:48`). Their names are in the same structure and are thrown away.

**Proposal.** Return `Attachment(name, type, size)` for the skipped and non-text parts. Store them on the thread, send them to the model (AI-04), show them on the sheet ("2 attachments: consent-form.pdf, itinerary.docx") with a tap that opens the thread in Gmail.

**Caveats.** Inline images (`inline`, `image/*`) counted but not named, or every signature logo is an attachment. Sending the attachment itself to the model (a PDF as a document block) is possible and, with cost no concern, worth a try for forms once DX-01 is in; it's a privacy choice (the PDF goes to the API), so it's yours.

**Effort.** S.

## DX-02 Calendar invites in emails (`text/calendar`) as events

**Now.** An invitation is a message with a text part; the model guesses the time from the prose.

**Proposal.** When the structure has a `text/calendar` part, fetch it and parse `SUMMARY`, `DTSTART`, `DTEND`, `LOCATION`; give the task the exact time and mark it an Event, or show "Invite: Thu 16 Oct 18:00–20:00" on the sheet with *Add to calendar*.

**Effort.** M. P3: school emails rarely carry invites.

## DX-03 Read only starred emails, and every starred email is a task

**Now.** Every inbox thread is read (`uidSearch("ALL")`, `GmailSource.kt:214`) and Claude decides which are tasks; those it reads as Info are hidden; a star in Gmail is fetched as a label (`X-GM-LABELS`, `:216`) and ignored.

**Proposal.** Your answer to Q-04. The star is the decision:

- Read the Starred mailbox, not the inbox: Gmail exposes it over IMAP as a folder (`[Gmail]/Starred` in English accounts; found reliably by `LIST` and the `\Flagged` special-use attribute, RFC 6154, so the name's language doesn't matter). `EXAMINE` it, list its UIDs (tens, not thousands), fetch envelopes and texts as now.
- A starred message makes its thread a task; the thread's newest message is the text, as now. A thread with no starred message left (you unstarred it) is finished, like an archived one today. Archiving without unstarring keeps it open: the star is the state.
- The Email job can't say Info any more (AI-02): everything it sees is something to do.
- Setup says it plainly: "Star an email in Gmail to make it a task; unstar it when it's done."
- Notes to self: star them, or a Gmail filter (`from:me to:me` → star) does it for you; no code.

**Migration.** At the first starred-only sync, open email tasks that aren't starred would otherwise end. Better: keep them open for seven days with a line on the Day view ("12 email tasks aren't starred: star the ones you still want"), then finish the rest.

**Final state.** The inbox's noise never reaches the app or the model. The question "is this a task?" is answered by you in Gmail with one tap; the model's job is what, when and how long. The sync's heaviest part (DX-07) shrinks to almost nothing.

**Caveats.** An email you forget to star isn't a task; that's the deal you've chosen, and the one the old design (read everything, let the model hide the noise) couldn't make reliable. Gmail's star is per message, not per thread: starring any message in a thread is enough. The IMAP special-use lookup is one more command at login; `[Gmail]/Starred` as a fallback name.

**Effort.** M.

## DX-04 Withdrawn

Bulk-mail headers were a hint for telling newsletters from forms. With starred-only reading there's nothing to tell apart.

## DX-05 Withdrawn

Sender rules were for inbox noise (your answer to Q-11: no longer important).

## DX-06 Strip quoted replies and signatures before the model sees them

**Now.** The newest message's text goes to the model whole, up to 16,000 characters (`Prompts.MAX_TEXT`), including the quoted thread below "On … wrote:" and signatures. `Mime.htmlToText` (`Mime.kt:122`) flattens HTML but doesn't cut quotes.

**Why.** Anchoring more than tokens: the model may answer the quoted request rather than the newest line.

**Proposal.** Drop `blockquote` and `div.gmail_quote` subtrees in HTML; in plain text cut at `^On .+ wrote:$`, `^-----Original Message-----` and `^-- $`. Replace the cut with "[Earlier messages in this thread were left out: N characters.]" (AI-04). Forwards ("Forwarded message") keep their content and cut only at the second quote level. Keep the uncut text for the sheet.

**Effort.** S.

## DX-07 The sync after starred-only: the Starred folder, flags for the known

**Now.** Each sync lists every inbox UID and fetches all their envelopes before anything new is seen; texts for up to 60 new messages within the time budget (`MAX_BODIES`, `GmailSource.kt:298`).

**Proposal.** With DX-03 the listing is the Starred folder's UIDs (small). For threads already known, fetch `FLAGS` and `X-GM-LABELS` only, to catch unstarring; envelopes and texts only for new UIDs. Keep `UIDVALIDITY` and fall back to a full read of the folder when it changes. The 90-second budget (`sync/Syncer.kt:62`) is then spent on bodies.

**Effort.** S.

## DX-08 Teams: the instructions' links and attachments

**Now.** The widget's provider gives title, description, class, due time, tab and due text (`sources/teams/TeamsSource.kt:76`).

**Proposal.** If the widget can carry them, `attachments` (names) and `links` columns, shown on the sheet as tappable lines and sent to the model with the instructions ("Statics booklet.pdf" tells it what "the booklet" is).

**Effort.** S here, unknown in the widget.

## DX-09 Power Planner: the timetable, for free periods, lessons and the school day

**Now.** `GetClassesAndSchedules` is read (`PowerPlannerApi.kt:67`): classes with names and schedules (start, end, day of week, week). It's used only to turn an item's date into a due time at the lesson's start (`PowerPlannerItems.slot`). You keep it current, with Google Calendar, and it holds break, lunch and study periods as entries.

**Proposal.** Your answer to Q-12: now. Four uses:

- **Windows (PL-15).** The timetable's "Study Period" entries, with break (10:45–11:15) and lunch (13:15–14:35 less 13:40–13:55 for eating) at their fixed times on every school day (your answer to Q-30), minus calendar events, become planning windows the slots (PL-12) can fill, alongside the evening.
- **Order.** Among work due the same day, the subject with the earlier lesson first; work for tomorrow's lessons ahead of work for Thursday's.
- **The voice.** The briefing and the nags know "Physics P1 tomorrow".
- **The profile (AI-05).** Lessons by day and school end, so Claude's estimates and the chat know the shape of your week.

**Final state.** A Tuesday with a double study period in the morning has thirty minutes of German vocab in it, on the calendar, before the evening starts.

**Caveats.** Depends on the timetable staying right in Power Planner, which you say it does; the last good read is cached so a failed sync doesn't lose the windows. Days without school, and half-days, come from the school calendar (PL-16), not the timetable. Two-week timetables are handled already (`scheduleWeek`). Work that needs a laptop or home (a Teams upload, a long essay) shouldn't land in a 30-minute break: the model marks a step `where: "anywhere" | "home"` (confirmed in Q-43).

**Effort.** M.

## DX-10 Anki: deck descriptions, so topic-named homework finds its deck

**Now.** The decks query reads `deck_id, deck_name, deck_count, options` (`AnkiSource.kt:39`); section matching (`AnkiRules.sectionsOf`, `AnkiRules.kt:144`) works on numbered sections in the deck name.

**Proposal.** Read `deck_desc` too; match a topic-named homework ("holidays vocab") to a deck whose description names it, whole-phrase, single candidate; give the model the deck list with descriptions so `ankiSections` can name sections by topic.

**Effort.** S.

## DX-11 Anki: seconds per card from the counts around a slot

**Now.** A deck task's minutes come from `REVIEW_SECONDS` per card (`AnkiRules.kt:63`), one constant for every deck. The first version of this item learned the rate from focus sessions; sessions go (note 1).

**Proposal.** Read the deck's counts at the start and end of the deck piece's slot (PL-12) and at each sync. Cards cleared between two reads on the same day, divided by the minutes between them while AnkiDroid was in front (the accessibility service knows, `FocusService.onFront`), gives seconds per card; keep a per-deck median over the last ten readings and use it instead of the constant from three readings on. This is the same reading NF-03 uses to count the work done.

**Caveats.** Counts change for reasons other than studying (cards becoming due at midnight, a desktop session); only readings on the same day count, and only while AnkiDroid was in front.

**Effort.** M.

## DX-12 Calendar: location and description for the event judge and the Day view

**Now.** `CalendarTime.read` (`CalendarTime.kt:67`) reads title, times, all-day, availability, calendar name and attendance; `EventJudge` decides from the title and the calendar name.

**Proposal.** Read `EVENT_LOCATION` and `DESCRIPTION`. Location on the Day timeline (UI-17) and in the chat's context; "optional" or "tentative" in the description pushes toward Ask; a meeting link makes a timed event busy. Cap the description at 300 characters in the context.

**Effort.** S.

## DX-13 Calendar: choose which calendars count

**Now.** Every calendar on the device is read; a regex on the name drops holidays, birthdays and week numbers (`NOT_YOURS`, `EventJudge.kt:50`). A shared family calendar or a club feed counts as your busy time, and the calendar the app will write to (NF-07) must be excluded from reading.

**Proposal.** The device's calendars listed in Settings with a tick each; the app's own calendar always unticked; new calendars ticked by default. Filter by `CALENDAR_ID` in `read`.

**Effort.** S.

## DX-14 Calendar: look as far ahead as the planner plans

**Now.** Events are read 14 days ahead (`LOOK_AHEAD_MS`, `CalendarTime.kt:43`); the planner places work up to 90 days out (`Planner.MAX_HORIZON_DAYS`). Even spreading (PL-01) places a share of every task on every week to its deadline, so a trip in week three the planner can't see gets work put on it.

**Proposal.** Read busy time to the planner's horizon (the device calendar is a local database; one wider query). Keep the questions ("All of it / A few hours / None") to the next 7 days (`ASK_AHEAD_MS`), and treat unanswered all-day events beyond that as "a few hours".

**Effort.** S. Now P1: PL-01 needs it.

## DX-15 The same work from two sources

**Now.** Tasks are keyed by source and id (`core/Merge.kt`); the same homework can arrive from Teams and from Power Planner, and a starred email about it is a third.

**Proposal.** A light duplicate detector after merge (same class via the class map, titles sharing most words, due within a day) that proposes a link as a card; linked, the duplicate is hidden, its worked minutes credited to the other, its own source's done state still ends it (`ChangeType.SameAs`). Your answer to Q-13: by tap, never automatic.

**Effort.** M.

---

## What I looked at and left alone

- **IMAP over the Gmail API**: still right; the Starred folder is an IMAP feature too.
- **Power Planner's `HashedKey`**: the web app's own dev key; volume far below its limit.
- **Teams via Graph** rather than the widget: no; the widget's signature-permission provider is the point.
- **Anki `review_info`**: gives the next card, not timings; DX-11 gets timing from the counts.
