# The website

`https://decrastination.thomaswhite.me`, a React and TypeScript single-page app in `web/` (D-132), served by Caddy beside the API on one origin, so its session is an ordinary cookie and there's no CORS. A second client of the state the server holds (D-126). The server is in [server.md](server.md).

It holds no rule of its own (D-137). The server computes each page with the phone's own code (`engine/.../views/`: `DayViews`, `WorkViews`, from `DayModel` and `Groups`) and sends it in the phone's words; the TypeScript lays it out. A change to how a day or a task reads is made once, in Kotlin.

Nothing is shown to the world: everything needs the owner's sign-in (D-128). It's read-only till the switch (D-141): while the server's mirroring the phone, each page says so by what it's sent, with nothing to press, as the phone's seeds are what change the state. Once it applies commands, the same pages can change things. A change is a command the page sends as the server gave it, or, where it takes the phone's rules to make one, a `TaskAsk`: what you gave, made into commands on the server (`TaskAsks`, `POST /api/tasks/{id}/ask`), so what a task allows and what's said of it are written once.

## The pages

- **Day** (`/`, `web/src/Day.tsx`). Beside each other: a navy slab with Do now, what comes after it, the day's load and what's been done; and the week's strip over the day's time grid, a pixel to the minute. Blocks overlap as they do on the phone: their columns are `DayLayout.columns`, sent with each block. `?date=` shows another day of the week.
- **Work** (`/work`, `web/src/Work.tsx`). Every open task in columns, by due date or by subject, with a filter by source. Below them, what isn't planned and why, what's been hidden, what's finished, and how each source was last read. Work that looks the same is said, with Same and Different. New task opens the form (`NewTask.tsx`), whose kinds and limits are the server's. From a photo reads homework into tasks (`PhotoTasks.tsx`, `POST /api/photos/tasks`): the browser turns it upright and shrinks it, the server reads it outside any command, as a call can take a minute, and each task's checked in the form and kept only if it's added. It's there only with Claude on and its key on the box.
- **A task's sheet** (`web/src/TaskSheet.tsx`, `TaskViews`). What it is, when it's due, how long it takes and when it can start, each with a Change where it can be given one and an Undo or a Clear where there's one to take back; its steps, what's in the plan and why, your changes on it, and its notes. Done says what it earned, as the phone's does (`PieceSays`), with an Undo. Later today, Tomorrow, Move (a day of the fortnight, then this piece at a time or all of it that day, `Moves`), and what's only for your own: All of it done, Edit, Home only, Delete. What can't be undone at a tap asks first. Not on the website: a source's Open, the timer, Read again, Gmail's finish, a photo of the work.
- **Settings** (`/settings`, `web/src/Settings.tsx`, `SettingsViews`). The pages that are shared state: About you, Hours and windows, School, Planning, Felix, Claude, Sources, Notifications. What's on each comes from the server, each field with its kind, range and what it does, and only what's changed is sent. Those that can loosen blocking are marked †; armed, one that does waits its hours as on the phone, shown under Waiting with its Cancel and, once the server holds the secret, a parent's code, which goes with the change it allows (`ApplyChangeNow`). Sources takes the sign-ins (D-143), and says which it holds, never what they are. Blocking and protection stay the phone's.
- **Phone** (`/phone`, `web/src/Phone.tsx`). A code to pair a phone with, what's paired, when each was last heard from, and revoking one.

Each page says how fresh it is: "From the phone 5 min ago". In mirror mode the phone is where the state comes from.

## Staying current

`web/src/live.ts` fetches a page's view, then listens to `/api/events`. The server says the revision it's at on connecting, and each new one; a page fetches again when the revision moves, as the page comes back into view, and so after a dropped connection.

## The look

The phone's navy and paper, in CSS variables with a dark scheme, Atkinson Hyperlegible Next served from the site itself (the content security policy allows nothing else). Wide-screen first: the grid and the columns use the width a phone doesn't have.

## Testing

`web/`: `npm ci`, then `npm run lint`, `npm run test:unit` (vitest), `npm run build` and `npm run test:e2e`. The last is Playwright in Chromium (`npx playwright install chromium` once) against the fat JAR in test mode (`DECRASTINATION_TEST=1`: its own database, sign-in stubbed, refused on a domain), with the newest `fixtures/stores/` seeded as a phone would, under the live site's content security policy. It runs two servers, each with a database of its own: `reading` against one that mirrors, as the site does till the switch, and `writing` (`e2e/writes.spec.ts`) against one taken into commands by `POST /api/test/commands`, with a made-up parent code. `LOOK=<a folder> npx playwright test look` takes screenshots of each page in both schemes.
