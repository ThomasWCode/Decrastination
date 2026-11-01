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
