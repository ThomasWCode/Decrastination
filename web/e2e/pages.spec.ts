import { expect, test } from "@playwright/test";

test("signed in, the Day shows the plan's grid and what to do now", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Do now" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Week" })).toBeVisible();
  await expect(page.getByTestId("grid")).toBeVisible();
});

test("a day of the week can be chosen", async ({ page }) => {
  await page.goto("/");
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

test("the Phone page gives a code and lists what's paired", async ({ page }) => {
  await page.goto("/phone");
  await expect(page.getByText("Test phone")).toBeVisible();
  await page.getByRole("button", { name: "Show a code" }).click();
  await expect(page.getByTestId("pairing-code")).toHaveText(/^\d{6}$/);
});

test("signed out, the site asks you to sign in", async ({ browser }) => {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await page.goto("/");
  await expect(page.getByRole("link", { name: "Sign in with GitHub" })).toBeVisible();
  await context.close();
});
