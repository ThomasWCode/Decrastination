import { test } from "@playwright/test";

// Screenshots to look at, in both schemes: run alone with LOOK=<a folder>.
const folder = process.env.LOOK;

test.skip(!folder, "Only when asked: LOOK=<a folder>");

for (const scheme of ["light", "dark"] as const) {
  test(`how the pages look, ${scheme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto("/");
    await page.getByTestId("grid").waitFor();
    await page.waitForTimeout(600);
    await page.screenshot({ path: `${folder}/day-${scheme}.png` });
    await page.goto("/work");
    await page.getByTestId("work-column").first().waitFor();
    await page.screenshot({ path: `${folder}/work-${scheme}.png` });
    await page.goto("/phone");
    await page.getByRole("button", { name: "Show a code" }).click();
    await page.getByTestId("pairing-code").waitFor();
    await page.screenshot({ path: `${folder}/phone-${scheme}.png` });
  });
}
