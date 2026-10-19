# Privacy

What leaves the phone, what's kept on it and for how long, how secrets are kept, what the activity log holds, and the rules for the public repository.
The app has no server of its own: it runs on the phone, talks only to Claude and to the sources' own services, and the PC is used only to build and test it.

## What leaves the phone

**Claude.** The Anthropic API, through its Java SDK, is called only while Claude is on and its key in use (`ModelGateway.claudeKey`), every call through `ModelGateway.call` ([readings.md](readings.md)).
Every call sends its job's instructions and, but for the web search, your profile (AI-05, AI-06, `enrich/Profile.kt`):
- your year group, and your subjects (Settings' map of your class codes to subjects, or the codes as they are);
- the school day's times (lessons, study periods, break, lunch), your working hours, the next days off with their names, and the Anki textbook;
- the lines you wrote about yourself, Felix's standing instructions, and what he remembers.

Each call's text is cut to 40 000 characters, the profile to 16 000. What else each job sends:

| Job | What it sends besides |
|---|---|
| An email's reading | The starred conversation, oldest first: who sent each message and when, the subject, its text (up to 16 000 characters), attachments by name; your answers to its question; its steps as planned |
| An assignment's or an item's reading | The task's title, class, due date and instructions (an assignment's with its attachments' names, its links and the decks' topics); your answers to its question |
| A photo with a task (AI-23) | The photo of the work, with its reading |
| A photo of homework (NF-01) | The photo, and today's date |
| A photo check | Up to three photos of the work; the piece, its task's title, subject, steps done and instructions (1 500 characters) |
| A piece's first steps (AI-15) | The task's title and subject, the piece and its minutes, attachments by name, its instructions (1 500 characters) |
| The chat | The conversation's last 12 messages and his last two nudges with your replies; the open tasks with their steps, the fortnight's calendar events (titles and times), your changes, your own tasks finished lately, Felix's settings, your pace, the plan's next 14 days; each open task's own text (instructions, an email's conversation, notes: 600 characters each), with its attachments' names, links and an email's sender; the whole text (8 000) of the task a message is about, and of up to three he asks to read; an event's place and description, for a message about it |
| Felix's messages | The reason and the time, the piece and today's slots, the free time, work its source confirmed, his last six messages of his own and your replies, a reading's question; the piece's task's own text (1 500 characters), with its attachments' names and links |
| An instruction | Your words; what they're about (a task's title, sender, class, due date, minutes and details); the open tasks and the fortnight's events |
| The weekly review | The week's record: finished work (titles, kinds, classes, estimates, minutes), slots by hour and day, blocks, days, Felix's counts, your check-in answers, the planning settings, what's learned |
| The week's summary (NF-02) | The week's numbers, finished and missed work, the review's note, Felix's counts, the changes you asked for |
| The memory tidy | The facts Felix remembers, with the day of each, beside a profile without them |

**The web search** (`enrich/WebResearcher.kt`): what to look up, your message's words and today's date go to Claude, with no profile. Claude's web search tool then searches the web (at most three searches, located in the UK) and reads the pages it finds.
It runs only while Felix may search (`felixSearches`), and only when a message needs facts neither you nor the app have given ([felix.md](felix.md)).

**The sources** ([sources.md](sources.md)):
- Power Planner's web API, with your login (`sources/powerplanner/PowerPlannerApi.kt`);
- Gmail over IMAP with an app password, read-only (`EXAMINE` and `BODY.PEEK`, `sources/gmail/Imap.kt`): nothing in the mailbox changes;
- Teams (through the Teams widget app's provider), AnkiDroid and the calendar are read on the phone.

**The calendar written** (NF-07): the plan's slots go into the calendar you pick, each with its piece's name, its source, why it's on its day and its first action. A Google calendar syncs them to your Google account.

**What you send yourself**: the week's summary opens in your email app to send, to the address in Settings → Notifications; the record (Stats' export, CSV files, PL-11) and a backup (Export) go wherever you share or save them.

Nothing else: no analytics, no crash reports (crashes are written down on the phone), and no Android cloud backup. From a PC, `ai-endpoint` sends Claude's calls to a stand-in server instead, till the app restarts ([storage-and-backups.md](storage-and-backups.md)).

## What's kept on the phone

| What | Where | How long |
|---|---|---|
| Tasks, their text and readings | `tasks.json` | A finished task while its source still lists it, then a fortnight; one its source stops listing is finished then; your own, a fortnight once done |
| The activity log | `log.json` | 120 days; each week's totals two years |
| The chat | `chat.json`, `chat-archive-<yyyy-MM>.json` | Every message, Felix's among them, till you clear it: the newest 200 or 14 days' in `chat.json`, the rest in the archive, a file a month |
| What Felix remembers | `memory.json` | Facts within 6 000 characters, the oldest out first; up to 20 standing instructions; till you delete them |
| Your changes | `instructions.json` | Till you take them back |
| Claude's calls | `runtime.json` | The last 100: job, what it's about, tokens, time, outcome, cost |
| Photos of work | `files/photos/` | While a task holds one |
| A photo check's pages | the app's cache | Deleted once checked |
| Crashes | `files/crashes/` | The last three |
| Daily backups | `Documents/Decrastination` | The newest 14; a reset's copy till you delete it |
| Copies of unreadable files | `files/` | The newest three of each |
| The file a migration came from | `files/` (`<name>.v<n>`) | A week from the migration, then let go at the next start |

A backup is plain JSON in the phone's shared `Documents` folder, holding the chat, what Felix remembers, your changes, your own tasks, the log and the settings (the summary's address among them).
Apps with access to all files (a file manager, a sync app) can read it, and so can a PC over USB.

## Secrets

`data/SecretStore.kt` keeps the credentials and keys (`Secret`) in `secrets.bin`, encrypted with AES-256-GCM under a key that never leaves the Android Keystore (`KeystoreCipher`).
- It holds the Power Planner login and its session, the Gmail address and app password, Claude's API key, and the parent code's authenticator secret.
- Values are never logged, shown or exported: screens and the `state` command show only which are set, and backups never hold them.
- They're typed into Setup, or loaded from the PC's user environment by `scripts/load_credentials.py` through a file the app imports and deletes (`import-credentials`).
- The authenticator secret is shown once, as a QR code, at arming.

Two keys are in the code, neither of them yours: Power Planner's web API is called with the development key its open-source web app uses, and every build is signed with the committed debug keystore that the Teams widget app shares, which grants its signature-level permission.

## The activity log

`log.json` (`ActivityLog`) is the record the stats, the calibration, the weekly review and Felix read:
- each slot kept, missed or moved; each finished task (title, kind, class, estimate, minutes worked, due and done times, your answer to how it went);
- each block (when, what, why); each protection lapse; each day's plan and how much got done;
- the check-ins, in your words; the weekly reviews, with their summary; each week's totals.

It's trimmed at each change: 120 days. It goes in every backup; Stats' export shares it as CSV, with Felix's own messages from the chat and your replies; Settings → Reset → Stats clears it, and Reset → "The chat and Felix's history" clears the chat ([storage-and-backups.md](storage-and-backups.md)).
Android's own log (`adb logcat -s Decrastination`) gets the app's notes too: the debug commands log task titles, never a secret's value.

## The public repository

The repository is public: code, tests, docs, commit messages and pull requests are world-readable. [development.md](../development.md) has the working rules.
- No personal data in anything committed: no email addresses, names of people, calendar event names, real task or email titles, addresses, phone numbers, personal dates, school details or device identifiers. Such things are described generically.
- Test fixtures are made up. A test that needs the shape of real data uses a copy in `private/`, never committed.
- `private/` is git-ignored and holds the real data: the evaluation set (`private/eval/`), the phone's data copied ahead of each install, the probes' output, the debug commands' files, earlier APKs, and the phone's helper scripts.
- The diff is scanned for addresses, names, event names and dates ahead of every push.
- Credentials for the PC's tools live in the user environment, never in the repository.
