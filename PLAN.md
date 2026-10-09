# Decrastination: Implementation Plan

*Drafted 7 October 2026 from a live survey of the phone, the Teams widget code, and the Power Planner and AnkiDroid sources; revised the same evening after the first round of answers. Anything marked **ASSUMED** is a default I chose; `docs/open-questions.md` lists what is still yours to decide.*

## 1. What the app is

A sideloaded Android app that gathers every outstanding obligation from four places, builds a day-by-day plan, shows the **single next thing to do** on a home-screen widget, and, while anything is due today or tomorrow, **intercepts procrastination apps** and replaces them with that instruction until the source of truth confirms the work is done. It learns from what you actually complete and tightens or loosens its own plan accordingly.

The four sources, and what "done" means for each:

| Source | What it contributes | Verifiable completion |
|---|---|---|
| Teams Assignments widget (`com.teamsassignments.widget`) | School homework: title, class, instructions, exact due time | Assignment leaves the not-handed-in list (handed in) |
| Power Planner (`com.barebonesdev.powerplanner`) | Personal tasks and events with dates | Item marked complete, or gone |
| Gmail inbox (`thomasawhite321@gmail.com`) | Everything in the inbox is outstanding; some now, some from a date | Message no longer in INBOX (archived, snoozed or deleted) |
| AnkiDroid (`com.ichi2.anki`) | German vocabulary: due reviews, new cards, decks tied to homework | Deck due count reaches zero / daily quota met |

## 2. Decisions, and why

**Read the Teams widget's data directly, not the widget.** Yes. The widget is a Glance rendering of `files/widget_state.json`, which holds strictly more than the widget shows: the Teams GUID key, full instructions (up to 2 000 chars), the due time as epoch millis, and the handed-in memory. The file is app-private, so a second app cannot read it. Fix: add a ~80-line read-only `ContentProvider` to the Teams widget app, protected by a `signature`-level permission. The Teams widget signs every debug build (CI included) with a keystore committed in its repo, and the installed APK carries that certificate; Decrastination signs with a copy of the same keystore, so the permission is granted silently and the widget is never reinstalled. Details in `docs/data-sources.md` §1. (Merging the two apps was rejected: the Teams widget is finished and tested, and coupling would make both harder to change.)

**Power Planner: its web API** (confirmed). The Android app is a Xamarin build with nothing readable, but Power Planner is open source and its web app talks to `https://web.api.powerplanner.net/api/` with plain JSON: `LoginWeb` returns a session, `GetAgenda` returns every open item with name, due date, class id and percent complete, and `GetClassesAndSchedules` the class names and timetable. Verified with your account on 8 Oct. Your username and password are stored encrypted on the phone. `docs/data-sources.md` §2 and `scripts/powerplanner_probe.py`.

**AnkiDroid: its official content-provider API.** `content://com.ichi2.anki.flashcards/decks` gives every deck with its learn/review/new counts behind a one-time runtime permission. Your decks are named after textbook sections (`1.1`, `1.2`, `2.2`…) and your German homework says "Learn vocabulary column 1.2" and "p46-47/ 2.2/2.3", so a regex links assignment to deck and Anki's own counts track the vocab part of homework.

**Gmail: IMAP with an app password** (confirmed). Simplest route that gives the inbox list, bodies for triage, and the "still in INBOX?" completion test. Gmail's snooze already implements "wait until date" natively; the app treats snoozing as a legitimate deferral.

**AI: an Anthropic API key, Claude Opus 5.5, high effort** (confirmed; you have $200 of prepaid credit and no card on the account, so it stops when that runs out). The core stays deterministic: ranking, deadlines, bucket allocation, quotas and completion checks never depend on the model, or the app becomes unpredictable. With the budget no longer a constraint, the model takes on five bounded jobs, each cached so nothing is sent twice: (1) inbox triage into actionable-now / from-a-date / informational, with effort; (2) splitting an assignment's instructions into ordered sub-steps with estimates, so the planner has real chunks ("Q1–8 ~25 min") rather than "45 minutes of"; (3) effort estimates where there is no better signal; (4) a weekly review of your completion log that proposes calibration changes within bounds and writes a short note; (5) optionally, vision checks of photographed written work to confirm a chunk is done. Estimated spend at high effort: under £5 a month; a monthly cap in Settings stops surprises. Subscriptions cannot be used by a third-party app; both vendors restrict subscription auth to their own clients. `docs/data-sources.md` §5.

**Blocking: an accessibility service, a full-screen block activity, and no bypass** (confirmed). The service receives a window-state event the instant a blocklisted app comes to the foreground and starts `BlockedActivity` over it. The policy is strict while anything sits in today's or tomorrow's bucket and switches to earned free time only when those buckets are empty. "Cannot be bypassed" is engineered as six layers (no bypass button, settings guard, device admin, 24-hour delayed loosening, watchdog, and a parent-held override: each pending change emails a one-time code to richard.white@lshtm.ac.uk from a mailbox you cannot read, and typing that code applies that one change immediately) with the residual holes stated honestly; `docs/scheduler.md` §6.

**Splitting large work** (confirmed). The planner allocates every task's remaining effort into day buckets backwards from its deadline with a safety margin, one chunk per day where possible; whatever lands in today's or tomorrow's bucket is what counts as due. `docs/scheduler.md` §3.

**Learning** (confirmed). Per-kind effort multipliers, safety margins, box-length experiments, a capacity reality check, a one-tap self-assessment after each completion, a five-question Sunday check-in, and the weekly LLM review that reads all of it. `docs/scheduler.md` §5.

**Hours and quota** (confirmed). Capacity Mon–Fri 16:45–22:00 and Sat–Sun 08:30–22:30; quiet (no blocking) 22:30–07:00. Anki: every day, all due reviews plus 20 new cards from the lowest-numbered deck that still has new cards, plus any deck a German assignment names.

**No PC or server at runtime.** Everything runs on the phone. adb was for discovery only.

## 3. Facts gathered tonight

- **Phone:** Samsung Galaxy S24 (`SM-S921B`), Android 16, One UI, serial `R3CX20C33EF`, en-GB. Second home page holds the Teams widget, the Power Planner Agenda and Schedule widgets, and a Google Calendar widget.
- **Teams store:** 11 open assignments at 06:42 today; 5 already overdue (German ×3, Statics Prep, Binomial Expansion), Chapter 17 review due 23:59 tonight, two due tomorrow morning. Snapshot in `fixtures/teams_widget_state.json`.
- **Power Planner:** two items (overdue "Pg 60&61", German, 25 Sep; "Call with Jags after school" 14 Oct 16:00). App version 2609.30.191.0.
- **AnkiDroid 2.25.1:** decks Extras, GCSE Vocab, Textbook 1, 1.1–5.1 and more; every visible deck 20 new / 0 learn / 0 review; header "100 cards due"; nothing studied today. Snapshot in `fixtures/anki_decks.json`.
- **Gmail:** 24 messages in INBOX, roughly a third self-sent notes.
- **Blocklist candidates installed:** YouTube, YouTube Music, Instagram, Snapchat, Discord, Twitch, Netflix, Chrome.
- **Enabled accessibility services:** your dictation app, your WhatsApp scheduler, AnyDesk, Teams Assignments sync.
- **Also present:** a sideloaded app labelled "Digital Wellbeing" (`com.screentime` 1.6, installed 7 Sep) with usage-access permission (Q9).
- **Toolchain on this PC:** JDK 17, Gradle 9.3.1 wrapper cached, AGP 9.0.1 cached, SDK platforms 35–37, build-tools 36. Same stack as the Teams widget.
- **Signing:** the installed Teams widget (0.2.1) is signed by the repo's committed `app/debug.keystore` (SHA-256 `9A:48:B9:F9…7B:F6`), not by this PC's `~/.android/debug.keystore` (`35:17:80:81…`). Decrastination will sign with the same committed key.

## 4. Architecture

```
┌──────────────────────────────── phone ─────────────────────────────────┐
│  Teams widget app ──ContentProvider──┐                                  │
│  Power Planner web API ──HTTPS───────┤                                  │
│  AnkiDroid provider ─────────────────┼──► SyncWorker ──► Room DB        │
│  Gmail IMAP ─────────────────────────┤      (every 15 min + on demand)  │
│  Device calendar (optional) ─────────┘            │                     │
│                                                   ▼                     │
│                     Enricher (Claude, once per new item, cached;        │
│                     rules fallback): triage, sub-steps, effort          │
│                                                   │                     │
│                                                   ▼                     │
│                     Planner: day buckets, chunks, NextAction            │
│                            │                         │                  │
│            ┌───────────────┘                         └──────────┐       │
│            ▼                                                    ▼       │
│     NextWidget (Glance)                                 BlockPolicy     │
│                                                              │          │
│     FocusAccessibilityService: foreground window changed ────┤          │
│       + settings guard (anti-tamper)                         ▼          │
│                                                      BlockedActivity    │
│                                                      "Do: … [Open]      │
│                                                       [Start 25 min]    │
│                                                       [Check it's done]"│
│                                                                         │
│     Calibration: multipliers, margins, box lengths ◄── completion log   │
│     Weekly review (Claude) ──► bounded parameter changes + note         │
└─────────────────────────────────────────────────────────────────────────┘
```

**Package:** `com.thomaswcode.decrastination`. **Stack:** Kotlin, AGP 9.0.1, Gradle 9.3.1, `minSdk 26`, `targetSdk 36`, Jetpack Compose + Material 3, Glance 1.2, WorkManager, kotlinx.serialization. *As built (Phase 1):* state lives in JSON files written atomically (`data/JsonStore.kt`, the Teams widget's pattern) rather than Room: it's tens of tasks and a few thousand log rows a year, all of it fits in memory, and the planner's inputs stay plain data classes the tests build directly. Secrets are one file encrypted with AES-GCM under an Android Keystore key (`data/SecretStore.kt`) rather than the now-deprecated EncryptedSharedPreferences, and HTTP and IMAP use the platform (`HttpURLConnection`, a small IMAP client) rather than OkHttp, JavaMail or an SDK. Distribution: `./gradlew installDebug`, debug APK signed with the Teams widget's committed debug key.

**Unified task model** (`TaskItem`):

| Field | Meaning |
|---|---|
| `id`, `source`, `sourceId` | Stable identity: Teams GUID, Power Planner GUID, Anki deck id, Gmail Message-ID |
| `title`, `detail`, `className` | What it is |
| `dueAt: Instant?` | Hard deadline, if any |
| `availableFrom: Instant` | "Wait until date" tasks are hidden before this |
| `effortMin: Int` | Estimate (source, rule, or LLM); you can override |
| `subSteps: List<SubStep>` | Ordered chunks with minutes (LLM), each with `done` |
| `progress: Float` | Power Planner percent complete, Anki cards done / total, sub-steps done / total |
| `kind` | `Homework`, `Revision`, `Admin`, `Event`, `Info` |
| `openIntent` | Deep link: Teams card, Power Planner, Anki deck, Gmail |
| `status` | `Open`, `DonePendingVerify`, `Done`, `Dismissed` |
| `firstSeenAt`, `lastSeenAt`, `doneAt`, `sessions` | Bookkeeping for calibration and stats |

**Completion verification:** tapping **Check it's done** re-reads only that task's source (one provider query, one IMAP search, one `GetAgenda` call, or a request to the Teams widget to sync). A task is `Done` only when the source agrees; a chunk is done by a completed focus session or a photo check. No honour-system button.

## 5. Phases

### Phase 0: Prove each data path (done 8 Oct 2026)

Every path works; the results, measurements and what they change are in `docs/phase0-findings.md`.

1. **Teams provider.** Built in the widget (0.3.0, TeamsAssignmentsWidget #13): the `signature` permission `READ_ASSIGNMENTS`, `AssignmentsProvider` with `assignments` and `state` cursors and change notifications, and `call("requestSync")` / `call("open", key)`, unit-tested. Read from the probe app on the phone; `adb shell` refused. Found on the way: the widget's sync and open failed once Teams started paging a Past due list of seven cards; widget 0.3.1 (#14) fixes that.
2. **Power Planner.** `scripts/powerplanner_probe.py` logged in with your account and read both items and all 18 classes. Class names and the two-week timetable come from `GetClassesAndSchedules`; an item's time option is in its date's seconds.
3. **AnkiDroid.** The probe app asked for the permission and read all 57 decks; `deck_count` is `[learn, review, new]`. Section numbers repeat across Textbook 1 and 2, so a current-textbook setting picks one, starting at Textbook 1 (Q18).
4. **Gmail.** App password created; `scripts/gmail_probe.py` read the inbox read-only over IMAP. Snoozed mail isn't visible over IMAP; your own notes carry `\Sent`.
5. **Blocking and guard.** The probe's service covered YouTube in 0.21 s and backed out of its own accessibility page, its App info and its uninstall prompt. Found: it can be put on an accessibility key shortcut without visiting its page, so Phase 3 guards that too, and the watchdog gets `WRITE_SECURE_SETTINGS` (granted over adb at setup) to switch it back on (Q19).

Phase 0 also built what Phase 1 would have scaffolded: the Gradle setup, the shared signing key, CI, and readers for both providers (`app/`, package `probe`).

### Phase 1: Skeleton, sources, storage, sync

*Done 8–9 Oct 2026: all four sources read on the phone; see `docs/data-sources.md` ("Built in Phase 1" under each source).*


- Build on Phase 0's skeleton (Gradle, signing, CI and the provider readers are in place); keep the probe screen as a debug view until Phase 3 replaces the spike.
- `sources/`: `TeamsSource`, `PowerPlannerSource` (with `GetClassesAndSchedules` and the time-option rules), `AnkiSource`, `GmailSource`, each with `fetch()` and `isDone(sourceId)`.
- Room: `TaskItem`, `SubStep`, `Enrichment` (keyed by content hash), `Session`, `Completion`, `PendingChange`, `Settings`.
- `SyncWorker` (periodic 15 min + expedited on demand); sources isolated so one failure never blocks the rest.
- Plain Compose list of every open task with source badge and raw detail (stays as the debug view).
- Setup checklist: Teams provider reachable, Power Planner login, AnkiDroid permission, Gmail app password, accessibility service, device admin, battery exemption, widget added, API key, and the parent-override sender mailbox (a screen your dad completes; the password is never shown again).

### Phase 2: Planner and widget

*Done 9 Oct 2026: the planner (with a daily cap on undated work and one Anki step a day, `docs/scheduler.md` §3 item 7), a Plan screen, and the Next task widget, placed on the 4th home page and tried at 2×1, 4×1, 2×2, 4×2, 4×3 and 4×5. ↻ reads every source and asks the Teams widget to sync; the Teams widget's change notifications bring the result back.*


- `Planner` as pure Kotlin, tested against `fixtures/` with the clock pinned to tonight.
- Availability template, box length and margins in Settings (defaults in Q6).
- `NextWidget` (Glance): "Do:" line, due/behind badge, minutes, "then:" line, ↻; tap opens the task. Midnight and deadline redraws.

### Phase 3: Blocker and anti-tamper

*Built 9 Oct 2026 and left as decided (Q20): blocking on, protection unarmed. Tested on the phone: blocking apps, sites and browsers; focus sessions; the Teams-sync banner; and, armed for the test, the guard on App info and the uninstall prompt, the watchdog switching the service back on, the device admin, and a parent code from a TOTP computed on the PC. `docs/scheduler.md` §4 and §6 ("As built").*

- `FocusAccessibilityService`: window-state and windows-changed events, blocklist, quiet hours, every-window check (split screen, pop-up, PiP).
- `BlockedActivity`: full screen, `excludeFromRecents`, Back → Home. Shows the top chunk, **Open**, **Start N min** (focus session), **Check it's done**, **Refresh Teams**, **Why am I blocked?**.
- `BlockPolicy`: strict while today/tomorrow buckets are non-empty, earned time otherwise; unit-tested.
- Anti-tamper layers 1–6 (`docs/scheduler.md` §6): settings guard, `DeviceAdminReceiver`, 24-hour delayed loosening with persisted `PendingChange`s, watchdog job + boot receiver, "PROTECTION OFF" widget state, and the parent-held override: HMAC-derived one-time codes, SMTP sender from the dedicated mailbox, code-entry screen with lockout, protection-off alert after an hour.
- Chrome URL check for YouTube in the browser (Q8).

### Phase 4: Enrichment

*Built 9 Oct 2026; Claude off until you switch it on (no paid calls yet). The rules split listed assignments into steps; Claude's client was checked against stand-in servers in the tests and on the phone, and its prompts on real items by Opus through the subscription. `docs/data-sources.md` §5 ("As built").*

- `Enricher` interface with `RuleEnricher` (always) and `ClaudeEnricher` (Opus 5.5, `output_config.effort = "high"`, JSON schema output, server-side fallbacks on, usage logged against an optional monthly cap).
- Email triage → `{kind, actionableFrom, deadline?, effortMin, nextStep}`. Assignment → `{subSteps[], effortMin, ankiDecks[], testDate?}` (the deck regex always wins when it matches). Power Planner → effort.
- Settings screen: blocklist, hours, quota, box length, margins, credentials, API key, cap; loosening changes shown as pending with their apply time.

### Phase 5: Learning and review

*Built 9 Oct 2026. The calibration, capacity check, self-assessments, Sunday check-in and the rules' review, the morning briefing, and the calendar as busy time all run now. The model's review and the photo check are built and wait for Claude (no paid calls yet). Tried on the phone: the check-in screen and a review by the rules, and the calendar's judgement of your events. `docs/scheduler.md` §5 ("As built").*

- Session and completion logging; calibration (`docs/scheduler.md` §5 items 1–4) as pure Kotlin with tests.
- One-tap self-assessment after each verified completion; the Sunday five-question check-in.
- Weekly review call (Sunday 20:00, after the check-in): proposes bounded changes, writes the note; changes applied through the same pending-change path.
- Photo check for written chunks (vision).
- Device calendar as busy time (`CalendarContract`, `READ_CALENDAR`).
- Morning notification (Q13) with the day's bucket, and the Teams sync requested then.

### Phase 6: Polish

*Built 9 Oct 2026, version 1.0.0. A Stats tab (the last fortnight's finished work, focus sessions, blocks and protection findings, day by day; today's free time; what the app has learned, in words; Claude's month) and Back up / Restore in Setup (the settings, the log, the calibration and your calendar answers, never passwords or keys). Tried on the phone: the Stats tab, and a backup saved through Android's file picker.*

- Stats screen: completions per day, sessions, blocks triggered, protection-off events, calibration values.
- Export/import of settings and the log.

### After v1: blocks (1.1.0)

*Built 9 Oct 2026 (Q12): the model can split any task (an email, a Power Planner item, an assignment) into blocks with their own dates; the planner places each in its own window; an email archived with dated blocks to do stays as a follow-up until they're done, and an email's blocks can be ticked off in Tasks.*

### 1.1.1: long emails read to their end

*Built 9 Oct 2026: the app kept only the first 4 000 characters of an email, which cut the dated timeline off an 8 500-character one before the model could see it; it now keeps 20 000, of which the model reads 16 000 (told when there's more), and reads again, once, the emails it had cut. Read whole, that email's plan came to 12 to 14 hours in 17 to 20 blocks, past the ten hours and twenty blocks 1.1.0 trusted from one email, so an email's or planner item's blocks may now come to twenty hours and thirty blocks.*

### 1.2.0: alerts

*Built 9 Oct 2026: a plan of Claude's that the checks drop is said on the task and in a notification, with why; a key the API refuses (rejected, not allowed, or out of credit) is alerted once a stretch, in a notification, a banner on the Plan and Setup; Claude's monthly cap is optional and in dollars, none by default, as the account is prepaid; and work due today or tomorrow comes before overdue work, taking the evening's time first.*

## 6. Verification

- **Unit tests (JVM):** `Planner` bucket allocation and ordering against `fixtures/` (expected buckets for tonight are written out in `docs/scheduler.md` §3); `BlockPolicy` strict/earned/quiet transitions; pending-change timing across midnight and reboot; source parsers against recorded JSON; deck-regex mapping against the real assignment texts; calibration maths; enrichment cache.
- **Install:** `.\gradlew.bat installDebug`; logs via `adb logcat -s Decrastination`.
- **Device checks:** each source populates; the widget shows the right chunk after a sync; opening YouTube while Ch17 review is pending shows the block screen; a focus session blocks throughout and marks the chunk; handing in in Teams then **Check it's done** clears the task; with buckets empty, credit is spent only while YouTube is in front; the settings guard backs out of the app's own accessibility page and App info; uninstall is refused while the admin is active; a loosening change waits 24 h; the override email arrives at the parent address from the dedicated mailbox and nowhere in your own Gmail, its code applies only that change and only once, three wrong codes lock entry; the watchdog notices the service being turned off within 15 minutes and the alert email goes after an hour; everything survives a reboot and a day of One UI battery management.

## 7. Risks and limits accepted

- **Teams data is only as fresh as the Teams widget's last sync** (manual, takes over the screen). The app requests a sync only at deliberate moments (block screen, morning). The widget's read-along observer catches hand-ins whenever Teams is open, which is when you hand in.
- **Power Planner's web API key is the rate-limited development key** from its open-source web app; 15-minute polling is modest; fallback is screen reading.
- **An app password grants full mailbox access**; encrypted on a phone you control; revocable.
- **Anti-tamper is friction, not security**: adb, safe mode, a factory reset or a new user profile defeat it. Layer 2 depends on Settings screen text, like the Teams scraper depends on Teams. The parent override is only as strong as the sender mailbox staying his; the setup makes him enter its password, and the app never displays it.
- **Chunk completion via a timer is self-reported in effect**; the deadline, the source check and the calibration layer are the backstops; photo checks tighten it for written work.
- **One UI can switch accessibility services off** under battery management; same mitigations as the Teams widget, plus the watchdog.
- **LLM output is advisory only**: it never decides what is done, only how work is described, split and estimated, and it proposes calibration changes within fixed bounds.

## 8. Project layout

```
Decrastination/
  PLAN.md                       this file
  docs/data-sources.md          per-source access methods, endpoints, columns, verified facts
  docs/scheduler.md             buckets, block policy, anti-tamper, learning
  docs/phase0-findings.md       what proving each data path found on 8 Oct 2026
  docs/open-questions.md        decisions: answered so far, and what is still open
  fixtures/                     real snapshots from 7–8 Oct 2026 for unit tests (redacted where personal)
  scripts/powerplanner_probe.py Power Planner web API check (credentials from the environment or asked for)
  scripts/gmail_probe.py        read-only Gmail IMAP check (app password from the environment or asked for)
  scripts/pull_teams_state.ps1  refresh fixtures/teams_widget_state.json and check the signing key
  app/                          the Android app; Phase 0's probes in package probe
  private/                      git-ignored probe output (real inbox and agenda)
```
