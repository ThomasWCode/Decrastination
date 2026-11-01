import { expect, test, type Locator, type Page } from "@playwright/test";

// What the website changes, against a server in commands mode with a database of its own (playwright.config.ts): the tests share
// it, so each makes what it needs, under a name of its own.

async function newTask(page: Page, title: string, minutes = "45") {
  await page.goto("/work");
  await page.getByRole("button", { name: "New task" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill(title);
  await form.getByLabel("Minutes").fill(minutes);
  await form.getByRole("button", { name: "Add" }).click();
  await expect(form).toBeHidden();
  await expect(page.getByRole("button", { name: title })).toBeVisible();
}

async function sheetOf(page: Page, title: string): Promise<Locator> {
  await page.getByRole("button", { name: title }).first().click();
  const sheet = page.getByTestId("task-sheet");
  await expect(sheet.getByRole("heading", { name: title })).toBeVisible();
  return sheet;
}

const tomorrow = () => {
  const day = new Date();
  day.setDate(day.getDate() + 1);
  return day;
};

/** [day] as a date field writes it. */
const dated = (day: Date) => `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, "0")}-${String(day.getDate()).padStart(2, "0")}`;

test("a new task goes on the list", async ({ page }) => {
  await newTask(page, "Read the chapter");
});

test("the form says what the server won't take", async ({ page }) => {
  await page.goto("/work");
  await page.getByRole("button", { name: "New task" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill("Too long");
  await form.getByLabel("Minutes").fill("100000");
  await form.getByRole("button", { name: "Add" }).click();
  await expect(form.getByRole("status")).toContainText("Its minutes must be");
  await expect(form).toBeVisible();
});

test("an event needs its time, and takes no steps", async ({ page }) => {
  await page.goto("/work");
  await page.getByRole("button", { name: "New task" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill("A concert");
  await form.getByLabel("Kind").selectOption({ label: "Event" });
  await expect(form.getByRole("button", { name: "Add a step" })).toBeHidden();
  await form.getByRole("button", { name: "Add" }).click();
  await expect(form.getByRole("status")).toContainText("An event needs its date and time");
});

test("Done says what it came to, and Undo takes it back", async ({ page }) => {
  await newTask(page, "Practise scales", "20");
  const sheet = await sheetOf(page, "Practise scales");
  await sheet.getByRole("button", { name: "Done", exact: true }).click();
  await expect(sheet.getByRole("status")).toContainText("Done");
  await sheet.getByRole("status").getByRole("button", { name: "Undo" }).click();
  await expect(sheet.getByRole("status")).toBeHidden();
});

test("steps made in the form are ticked off, and the last finishes it", async ({ page }) => {
  await page.goto("/work");
  await page.getByRole("button", { name: "New task" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill("Lab report");
  await form.getByRole("button", { name: "Add a step" }).click();
  await form.getByRole("group", { name: "Step 1" }).getByLabel("Step").fill("Plot the graph");
  await form.getByRole("button", { name: "Add" }).click();
  await expect(form).toBeHidden();
  const sheet = await sheetOf(page, "Lab report");
  const steps = sheet.getByRole("listitem").filter({ hasText: "Plot the graph" });
  await steps.getByRole("button", { name: "Done" }).click();
  await expect(steps).toHaveClass("done");
});

test("a task of your own is edited, keeping what wasn't changed", async ({ page }) => {
  await newTask(page, "Essay plan");
  const sheet = await sheetOf(page, "Essay plan");
  await sheet.getByRole("button", { name: "Edit" }).click();
  const form = sheet.getByRole("form", { name: "Edit task" });
  await expect(form.getByLabel("Minutes")).toHaveValue("45");
  await form.getByLabel("What").fill("Essay outline");
  await form.getByRole("button", { name: "Save" }).click();
  await expect(sheet.getByRole("heading", { name: "Essay outline" })).toBeVisible();
});

test("a due date of your own is given, then cleared", async ({ page }) => {
  await newTask(page, "Hand in the form");
  const sheet = await sheetOf(page, "Hand in the form");
  await sheet.getByRole("button", { name: "Change Due" }).click();
  await sheet.getByLabel("Due", { exact: true }).fill(`${dated(tomorrow())}T09:00`);
  await sheet.getByRole("button", { name: "Set" }).click();
  await expect(sheet.locator(".field").first()).not.toContainText("No due date");
  await sheet.getByRole("button", { name: "Clear Due" }).click();
  await expect(sheet.locator(".field").first()).toContainText("No due date");
});

test("a source's task takes minutes of yours, and Undo gives them back", async ({ page }) => {
  await page.goto("/work");
  await page.getByRole("button", { name: "Teams" }).click();
  await page.getByTestId("task").first().getByRole("button").first().click();
  const sheet = page.getByTestId("task-sheet");
  await expect(sheet.getByRole("heading")).toBeVisible();
  const takes = sheet.locator(".field", { hasText: "Takes" });
  await sheet.getByRole("button", { name: "Change Takes" }).click();
  await sheet.getByLabel("Takes", { exact: true }).fill("77");
  await sheet.getByRole("button", { name: "Set" }).click();
  await expect(takes).toContainText("1 h 17");
  await sheet.getByRole("button", { name: "Undo Takes" }).click();
  await expect(takes).not.toContainText("1 h 17");
});

test("all of a task moves to a day, said in the phone's words", async ({ page }) => {
  await newTask(page, "Revise vectors");
  const sheet = await sheetOf(page, "Revise vectors");
  await sheet.getByRole("button", { name: "Move" }).click();
  const move = sheet.getByRole("group", { name: /^Move/ });
  await move.getByRole("button").nth(3).click();
  await move.getByRole("button", { name: "All of it, any time that day" }).click();
  await expect(sheet.getByRole("status")).toContainText("All of it on");
});

test("a piece's time that's gone is refused, saying why", async ({ page }) => {
  await newTask(page, "Learn the quotes");
  const sheet = await sheetOf(page, "Learn the quotes");
  await sheet.getByRole("button", { name: "Move" }).click();
  const move = sheet.getByRole("group", { name: /^Move/ });
  await move.getByRole("button").first().click();
  await move.getByLabel("At").fill("00:00");
  await move.getByRole("button", { name: "This piece, at that time" }).click();
  await expect(sheet.getByRole("status")).toContainText("That time's gone");
});

test("Not a task asks first, then it's hidden", async ({ page }) => {
  await newTask(page, "Maybe nothing");
  const sheet = await sheetOf(page, "Maybe nothing");
  await sheet.getByRole("button", { name: "Not a task" }).click();
  await sheet.getByRole("alertdialog").getByRole("button", { name: "Cancel" }).click();
  await expect(sheet.getByRole("alertdialog")).toBeHidden();
  await sheet.getByRole("button", { name: "Not a task" }).click();
  await sheet.getByRole("alertdialog").getByRole("button", { name: "Not a task" }).click();
  await expect(sheet.getByRole("button", { name: "Not a task" })).toBeHidden();
});

test("Delete asks first, and it's gone", async ({ page }) => {
  await newTask(page, "Throw this away");
  const sheet = await sheetOf(page, "Throw this away");
  await sheet.getByRole("button", { name: "Delete", exact: true }).click();
  await sheet.getByRole("alertdialog").getByRole("button", { name: "Delete" }).click();
  await expect(sheet.getByRole("paragraph").filter({ hasText: "That task has gone" })).toBeVisible();
});

test("a note is kept on a task, and deleted", async ({ page }) => {
  await newTask(page, "Bring the book");
  const sheet = await sheetOf(page, "Bring the book");
  await sheet.getByLabel("A note on it").fill("The blue one");
  await sheet.getByRole("button", { name: "Keep" }).click();
  const note = sheet.getByRole("listitem").filter({ hasText: "The blue one" });
  await expect(note).toBeVisible();
  await note.getByRole("button", { name: "Delete" }).click();
  await expect(note).toBeHidden();
});

test("your own can be home only, and back", async ({ page }) => {
  await newTask(page, "Practise piano");
  const sheet = await sheetOf(page, "Practise piano");
  await sheet.getByRole("button", { name: "Home only" }).click();
  await expect(sheet.getByText("Planned at home")).toBeVisible();
  await sheet.getByRole("button", { name: "Anywhere" }).click();
  await expect(sheet.getByText("Planned anywhere")).toBeVisible();
});
