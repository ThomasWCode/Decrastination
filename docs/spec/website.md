# The website

`https://decrastination.thomaswhite.me`, a React and TypeScript single-page app in `web/` (D-132), served by Caddy beside the API on one origin, so its session is an ordinary cookie and there's no CORS. A second client of the state the server holds (D-126). The server is in [server.md](server.md).

It holds no rule of its own (D-137). The server computes each page with the phone's own code (`engine/.../views/`: `DayViews`, `WorkViews`, from `DayModel` and `Groups`) and sends it in the phone's words; the TypeScript lays it out. A change to how a day or a task reads is made once, in Kotlin.

Read-only in 5.0.0: nothing can be changed here yet. Nothing is shown to the world: everything needs the owner's sign-in (D-128).

## The pages

- **Day** (`/`, `web/src/Day.tsx`). Beside each other: a navy slab with Do now, what comes after it, the day's load and what's been done; and the week's strip over the day's time grid, a pixel to the minute. Blocks overlap as they do on the phone: their columns are `DayLayout.columns`, sent with each block. `?date=` shows another day of the week.
- **Work** (`/work`, `web/src/Work.tsx`). Every open task in columns, by due date or by subject, with a filter by source. Below them, what isn't planned and why, what's been hidden, what's finished, and how each source was last read.
- **Phone** (`/phone`, `web/src/Phone.tsx`). A code to pair a phone with, what's paired, when each was last heard from, and revoking one.

Each page says how fresh it is: "From the phone 5 min ago". In mirror mode the phone is where the state comes from.

## Staying current

`web/src/live.ts` fetches a page's view, then listens to `/api/events`. The server says the revision it's at on connecting, and each new one; a page fetches again when the revision moves, as the page comes back into view, and so after a dropped connection.

## The look

The phone's navy and paper, in CSS variables with a dark scheme, Atkinson Hyperlegible Next served from the site itself (the content security policy allows nothing else). Wide-screen first: the grid and the columns use the width a phone doesn't have.

## Testing

`web/`: `npm ci`, then `npm run lint`, `npm run test:unit` (vitest), `npm run build` and `npm run test:e2e`. The last is Playwright in Chromium (`npx playwright install chromium` once) against the fat JAR in test mode (`DECRASTINATION_TEST=1`: its own database, sign-in stubbed, refused on a domain), with the newest `fixtures/stores/` seeded as a phone would, under the live site's content security policy. `LOOK=<a folder> npx playwright test look` takes screenshots of each page in both schemes.
