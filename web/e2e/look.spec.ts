import { test } from "@playwright/test";

// Screenshots to look at, in both schemes: run alone with LOOK=<a folder>.
const folder = process.env.LOOK;

test.skip(!folder, "Only when asked: LOOK=<a folder>");

for (const scheme of ["light", "dark"] as const) {
  test(`how the pages look, ${scheme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto("/");
    await page.getByTestId("message").first().waitFor();
    await page.screenshot({ path: `${folder}/chat-${scheme}.png` });
    await page.goto("/day");
    await page.getByTestId("grid").waitFor();
    await page.waitForTimeout(600);
    await page.screenshot({ path: `${folder}/day-${scheme}.png` });
    await page.goto("/work");
    await page.getByTestId("work-column").first().waitFor();
    await page.screenshot({ path: `${folder}/work-${scheme}.png` });
    await page.getByTestId("task").first().getByRole("button").first().click();
    await page.getByTestId("task-sheet").getByRole("heading").first().waitFor();
    await page.screenshot({ path: `${folder}/sheet-${scheme}.png` });
    await page.goto("/settings");
    await page.getByRole("navigation", { name: "Settings" }).getByRole("button", { name: /^Hours/ }).click();
    await page.getByRole("heading", { name: "Hours and windows" }).waitFor();
    await page.screenshot({ path: `${folder}/settings-${scheme}.png` });
    await page.goto("/stats");
    await page.getByTestId("stat-tile").first().waitFor();
    await page.screenshot({ path: `${folder}/stats-${scheme}.png`, fullPage: false });
    await page.goto("/reviews");
    await page.getByTestId("review").first().waitFor();
    await page.screenshot({ path: `${folder}/reviews-${scheme}.png` });
    await page.goto("/phone");
    await page.getByRole("button", { name: "Show a code" }).click();
    await page.getByTestId("pairing-code").waitFor();
    await page.screenshot({ path: `${folder}/phone-${scheme}.png` });
  });
}
