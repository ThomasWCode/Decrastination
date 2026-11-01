import { expect, test } from "@playwright/test";

test("the chat's the home, and while the phone writes it's there to read, with nothing to press", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByTestId("message").filter({ hasText: "A new word for you" })).toBeVisible();
  await expect(page.getByText("To write to Fe, use the phone for now.")).toBeVisible();
  await expect(page.getByRole("form", { name: /^Write to/ })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Clear the chat" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Mark all answered" })).toHaveCount(0);
  await expect(page.getByRole("group", { name: "His replies" })).toHaveCount(0);
  const his = page.getByTestId("message").filter({ hasText: "Busy, but it fits." });
  await expect(his.getByRole("button", { name: "Thumbs up" })).toHaveCount(0);
  await expect(page.getByRole("complementary", { name: "Your changes" }).getByRole("button", { name: "Take back" })).toHaveCount(0);
});

test("while the phone writes, the Day has nothing to press, and a block can't be lifted", async ({ page }) => {
  await page.goto("/day");
  await expect(page.getByTestId("grid")).toBeVisible();
  await expect(page.getByRole("group", { name: "Do" })).toHaveCount(0);
  await expect(page.locator(".block.movable")).toHaveCount(0);
});

test("Stats shows the record, by period, with nothing to press while the phone writes", async ({ page }) => {
  await page.goto("/stats");
  await expect(page.getByRole("heading", { name: "The last 14 days" })).toBeVisible();
  await expect(page.getByTestId("stat-tile")).toHaveCount(6);
  const days = page.getByRole("region", { name: /^Day by day/ }).getByRole("listitem");
  await expect(days).toHaveCount(7);
  await page.getByRole("button", { name: "Show all 14 days" }).click();
  await expect(days).toHaveCount(14);
  await page.getByRole("button", { name: "Show the last week only" }).click();
  await expect(days).toHaveCount(7);
  const period = page.getByRole("group", { name: "Period" });
  await expect(period.getByRole("button", { name: "2 weeks" })).toHaveAttribute("aria-pressed", "true");
  await period.getByRole("button", { name: "6 weeks" }).click();
  await expect(page.getByRole("heading", { name: "The last 42 days" })).toBeVisible();
  await expect(period.getByRole("button", { name: "6 weeks" })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("table")).toBeVisible();
  await expect(page.getByRole("cell", { name: "Mon 07:00: 0 kept, 0 missed" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Forget" })).toHaveCount(0);
});

test("This week says the check-in's the phone's while it writes, and shows the latest review", async ({ page }) => {
  await page.goto("/week");
  await expect(page.getByRole("heading", { name: "This week" })).toBeVisible();
  await expect(page.getByRole("form", { name: "This week's check-in" })).toHaveCount(0);
  await expect(page.getByRole("region", { name: /^Review, / })).toBeVisible();
});

test("the reviews are there to read, with no Undo while the phone writes", async ({ page }) => {
  await page.goto("/reviews");
  const review = page.getByTestId("review").first();
  await expect(review.getByRole("heading", { name: /^Week of / })).toBeVisible();
  await expect(page.getByRole("button", { name: "Undo" })).toHaveCount(0);
});

test("a review's summary goes by the mail app, nothing being sent from here", async ({ page }) => {
  const card = { id: 1, week: "Week of 5 Oct", by: "By the rules.", lines: ["A made-up note."], changes: [], summary: "A made-up week", mailto: "mailto:?subject=Made%20up&body=A%20made-up%20week" };
  await page.route("**/api/views/reviews", (route) => route.fulfill({ json: { cards: [card] } }));
  await page.goto("/reviews");
  await expect(page.getByRole("link", { name: "Send it" })).toHaveAttribute("href", card.mailto);
  await expect(page.getByText("A made-up week")).toHaveCSS("white-space", "pre-wrap");
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.getByRole("button", { name: "Copy" }).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(card.summary);
});

test("a backup can be had while the phone writes, with nothing else to press", async ({ page }) => {
  await page.goto("/backup");
  await expect(page.getByRole("heading", { name: "Back up" })).toBeVisible();
  await expect(page.getByText("Restoring and resetting are on the phone for now.")).toBeVisible();
  await expect(page.getByLabel("Import a backup")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Reset" })).toHaveCount(0);
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download a backup" }).click();
  expect((await download).suggestedFilename()).toMatch(/^decrastination-\d{4}-\d{2}-\d{2}\.json$/);
});

test("Setup says how each source reads, and Claude", async ({ page }) => {
  await page.goto("/setup");
  const sources = page.getByRole("region", { name: "Sources" }).getByRole("listitem");
  await expect(sources).toHaveCount(4);
  await expect(sources.first()).toContainText("Teams Assignments widget");
  await expect(page.getByRole("region", { name: "Claude" }).getByRole("listitem")).toHaveCount(1);
});

test("Setup's marked in the bar while it needs you", async ({ page }) => {
  await page.route("**/api/views/notices", (route) => route.fulfill({ json: { unread: 0, setup: true } }));
  await page.goto("/day");
  await expect(page.getByRole("link", { name: "Setup" }).getByRole("img", { name: "needs you" })).toBeVisible();
});

test("Setup's not marked when it doesn't", async ({ page }) => {
  await page.route("**/api/views/notices", (route) => route.fulfill({ json: { unread: 0, setup: false } }));
  await page.goto("/day");
  await expect(page.getByRole("link", { name: "Setup" })).toBeVisible();
  await expect(page.getByRole("img", { name: "needs you" })).toHaveCount(0);
});

test("Settings says what's the phone's alone, and Claude's page how it's worked", async ({ page }) => {
  await page.goto("/settings");
  await expect(page.getByText("On the phone only: Blocking and protection, Calendar, This phone's pairing, The last crash.")).toBeVisible();
  await page.getByRole("button", { name: /^Claude/ }).click();
  await expect(page.getByRole("heading", { name: "How hard it thinks" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Recent calls" })).toBeVisible();
});

test("a browser's offered nothing while the phone writes", async ({ page }) => {
  await page.goto("/settings");
  await page.getByRole("button", { name: /^Notifications/ }).click();
  await expect(page.getByRole("heading", { name: "Notifications" })).toBeVisible();
  await expect(page.getByRole("region", { name: "This browser" })).toHaveCount(0);
});

test("a phone's revoked only once it's been asked", async ({ page, request }) => {
  const { code } = await (await request.post("/api/devices/pair", { headers: { "X-Requested-With": "test" } })).json();
  await request.post("/api/devices/claim", { data: { code, name: "A made-up phone" } });
  await page.goto("/phone");
  const row = page.getByRole("listitem").filter({ hasText: "A made-up phone" });
  await row.getByRole("button", { name: "Revoke" }).click();
  const asking = page.getByRole("alertdialog", { name: "Revoke A made-up phone?" });
  await asking.getByRole("button", { name: "Keep it" }).click();
  await expect(asking).toBeHidden();
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "Revoke" }).click();
  await asking.getByRole("button", { name: "Revoke" }).click();
  await expect(row).toHaveCount(0);
});

test("signed in, the Day shows the plan's grid and what to do now", async ({ page }) => {
  await page.goto("/day");
  await expect(page.getByRole("heading", { name: "Do now" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Week" })).toBeVisible();
  await expect(page.getByTestId("grid")).toBeVisible();
});

test("the grid keeps your place when the page is asked for again", async ({ page }) => {
  await page.clock.install();
  // Each answer a minute on from the last, as the server's would be: the mocked clock doesn't reach it.
  let minute = 600;
  await page.route("**/api/views/day**", async (route) => {
    const response = await route.fetch();
    await route.fulfill({ response, json: { ...(await response.json()), nowMin: minute++ } });
  });
  await page.goto("/day");
  const grid = page.getByTestId("grid");
  await expect(grid).toBeVisible();
  const line = grid.locator("i.now");
  const before = await line.evaluate((element) => (element as HTMLElement).style.top);
  // At once: the grid scrolls smoothly, and a test reading it mid-way reads neither end.
  await grid.evaluate((element) => element.scrollTo({ top: 7, behavior: "instant" }));
  await page.clock.runFor(61_000);
  // The new minute drawn, so the page has taken the new answer in.
  await expect.poll(() => line.evaluate((element) => (element as HTMLElement).style.top)).not.toBe(before);
  expect(await grid.evaluate((element) => element.scrollTop)).toBe(7);
});

test("a day of the week can be chosen", async ({ page }) => {
  await page.goto("/day");
  const week = page.getByRole("navigation", { name: "Week" });
  await week.getByRole("link").first().click();
  await expect(page).toHaveURL(/date=/);
  await expect(page.getByTestId("grid")).toBeVisible();
});

test("Work shows the tasks by when they're due, and by subject", async ({ page }) => {
  await page.goto("/work");
  await expect(page.getByRole("heading", { name: "Work" })).toBeVisible();
  const columns = page.getByTestId("work-column");
  await expect(columns.first()).toBeVisible();
  await page.getByRole("button", { name: "By subject" }).click();
  await expect(columns.first()).toBeVisible();
});

test("Work filters by source", async ({ page }) => {
  await page.goto("/work");
  await page.getByRole("button", { name: "Teams" }).click();
  await expect(page.getByTestId("task").first()).toBeVisible();
  for (const line of await page.getByTestId("task-line").allTextContents()) expect(line).toContain("Teams");
});

test("mirroring the phone, a task's sheet is there to read, with nothing to press", async ({ page }) => {
  await page.goto("/work");
  await expect(page.getByTestId("work-column").first()).toBeVisible();
  await expect(page.getByRole("button", { name: "New task" })).toBeHidden();
  await page.getByTestId("task").first().getByRole("button").first().click();
  const sheet = page.getByTestId("task-sheet");
  await expect(sheet.getByRole("heading").first()).toBeVisible();
  await expect(sheet.locator(".field").first()).toBeVisible();
  await expect(sheet.getByRole("button", { name: "Done", exact: true })).toBeHidden();
  await expect(sheet.getByRole("button", { name: /^Change/ })).toHaveCount(0);
  await expect(sheet.getByRole("button", { name: "Move" })).toBeHidden();
  await expect(sheet.getByLabel("A note on it")).toBeHidden();
});

test("the Phone page gives a code and lists what's paired", async ({ page }) => {
  await page.goto("/phone");
  await expect(page.getByText("Test phone")).toBeVisible();
  await page.getByRole("button", { name: "Show a code" }).click();
  await expect(page.getByTestId("pairing-code")).toHaveText(/^\d{6}$/);
});

test("signed out, the site asks you to sign in", async ({ browser }) => {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await page.goto("/day");
  await expect(page.getByRole("link", { name: "Sign in with GitHub" })).toBeVisible();
  await context.close();
});
