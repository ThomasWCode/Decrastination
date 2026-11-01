import { createHmac } from "node:crypto";
import { expect, test, type Locator, type Page } from "@playwright/test";

// What the website changes, against a server in commands mode with a database of its own (playwright.config.ts): the tests share
// it, so each makes what it needs, under a name of its own.

async function newTask(page: Page, title: string, minutes = "45") {
  await page.goto("/work");
  await page.getByRole("button", { name: "New task" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill(title);
  await form.getByLabel("Minutes").fill(minutes);
  await form.getByRole("button", { name: "Add", exact: true }).click();
  await expect(form).toBeHidden();
  await expect(page.getByRole("button", { name: title, exact: true })).toBeVisible();
}

async function sheetOf(page: Page, title: string): Promise<Locator> {
  await page.getByRole("button", { name: title, exact: true }).first().click();
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
  await form.getByRole("button", { name: "Add", exact: true }).click();
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
  await form.getByRole("button", { name: "Add", exact: true }).click();
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

test("steps made in the form are ticked off", async ({ page }) => {
  await page.goto("/work");
  await page.getByRole("button", { name: "New task" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill("Lab report");
  await form.getByRole("button", { name: "Add a step" }).click();
  await form.getByRole("group", { name: "Step 1" }).getByLabel("Step").fill("Plot the graph");
  await form.getByRole("button", { name: "Add", exact: true }).click();
  await expect(form).toBeHidden();
  const sheet = await sheetOf(page, "Lab report");
  const steps = sheet.getByRole("listitem").filter({ hasText: "Plot the graph" }).filter({ has: page.getByRole("button") });
  await steps.getByRole("button", { name: "Done" }).click();
  await expect(sheet.getByRole("listitem").filter({ hasText: "Plot the graph" }).first()).toHaveClass("done");
});

test("a step's place stays as it was given, through an edit of something else", async ({ page, request }) => {
  await page.goto("/work");
  await page.getByRole("button", { name: "New task" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill("Placed steps");
  await form.getByRole("button", { name: "Add a step" }).click();
  const step = form.getByRole("group", { name: "Step 1" });
  await step.getByLabel("Step").fill("Anywhere at all");
  await step.getByLabel("Home only").check();
  await step.getByLabel("Home only").uncheck();
  await form.getByRole("button", { name: "Add", exact: true }).click();
  await expect(form).toBeHidden();
  const sheet = await sheetOf(page, "Placed steps");
  await sheet.getByRole("button", { name: "Edit" }).click();
  const editing = sheet.getByRole("form", { name: "Edit task" });
  await editing.getByLabel("What").fill("Placed steps again");
  await editing.getByRole("button", { name: "Save" }).click();
  await expect(sheet.getByRole("heading", { name: "Placed steps again" })).toBeVisible();
  const work = await (await request.get("/api/views/work")).json();
  const tile = work.byDue.flatMap((group: { tasks: { id: string; title: string }[] }) => group.tasks).find((task: { title: string }) => task.title === "Placed steps again");
  const task = await (await request.get(`/api/views/task/${encodeURIComponent(tile.id)}`)).json();
  expect(task.edit.task.steps[0].where).toBe("anywhere");
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
  await expect(sheet.getByRole("heading").first()).toBeVisible();
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
  await expect(sheet.getByText("That task has gone")).toBeVisible();
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

// A pixel: the photo's what the browser does with it, and reading it takes Claude, whose answer's made up here.
const PHOTO = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", "base64");

test("tasks read from a photo are checked one at a time, and kept only if they're added", async ({ page }) => {
  await page.route("**/api/views/work", async (route) => {
    const response = await route.fetch();
    await route.fulfill({ response, json: { ...(await response.json()), photoSide: 1568 } });
  });
  let sent = "";
  await page.route("**/api/photos/tasks", (route) => {
    sent = route.request().headers()["content-type"] ?? "";
    const task = (title: string) => ({ title, notes: "", kind: "Homework", minutes: 30, steps: [], where: "anywhere" });
    return route.fulfill({ json: { tasks: [{ sourceId: "photo-a", task: task("A sheet to leave") }, { sourceId: "photo-b", task: task("A sheet to keep") }], leftOut: ["The last line"], question: null } });
  });
  await page.goto("/work");
  await page.locator('input[type="file"]').setInputFiles({ name: "work.png", mimeType: "image/png", buffer: PHOTO });
  const form = page.getByRole("form", { name: "New task" });
  await expect(form.getByLabel("What")).toHaveValue("A sheet to leave");
  await expect(page.getByText("Left out: The last line")).toBeVisible();
  await form.getByRole("button", { name: "Cancel" }).click();
  await expect(form.getByLabel("What")).toHaveValue("A sheet to keep");
  await form.getByRole("button", { name: "Add", exact: true }).click();
  await expect(page.getByRole("button", { name: "A sheet to keep", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "A sheet to leave", exact: true })).toBeHidden();
  expect(sent).toBe("image/jpeg");
});

/** Settings' page [name], from the side. */
async function settingsPage(page: Page, name: string) {
  await page.goto("/settings");
  await page.getByRole("navigation", { name: "Settings" }).getByRole("button", { name }).click();
}

test("a setting's saved, and comes back as saved", async ({ page }) => {
  await settingsPage(page, /^Planning/);
  const box = page.getByLabel("Work cut into pieces of (minutes)");
  const next = String(Number(await box.inputValue()) + 5);
  await box.fill(next);
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("Saved.")).toBeVisible();
  await settingsPage(page, /^Planning/);
  await expect(page.getByLabel("Work cut into pieces of (minutes)")).toHaveValue(next);
});

test("Save has nothing to send till something's changed", async ({ page }) => {
  await settingsPage(page, /^Felix/);
  const save = page.getByRole("button", { name: "Save", exact: true });
  await expect(save).toBeDisabled();
  const name = page.getByLabel("His name");
  const was = await name.inputValue();
  await name.fill(`${was} the second`);
  await expect(save).toBeEnabled();
  await name.fill(was);
  await expect(save).toBeDisabled();
});

test("a window's hours are saved", async ({ page }) => {
  await settingsPage(page, /^Hours/);
  const hours = page.getByRole("group", { name: "Weekends: work" });
  await hours.getByLabel("From").fill("09:30");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("Saved.")).toBeVisible();
  await settingsPage(page, /^Hours/);
  await expect(page.getByRole("group", { name: "Weekends: work" }).getByLabel("From")).toHaveValue("09:30");
});

test("days off are added and taken away, at once", async ({ page }) => {
  await settingsPage(page, /^School/);
  const form = page.getByRole("form", { name: "Days off" });
  await form.getByLabel("First day off").fill("2030-02-18");
  await form.getByLabel("Last day off").fill("2030-02-22");
  await form.getByLabel("What it is").fill("a made-up week off");
  await form.getByRole("button", { name: "Add days off" }).click();
  const line = page.getByRole("listitem").filter({ hasText: "a made-up week off" });
  await expect(line).toBeVisible();
  await line.getByRole("button", { name: /^Delete/ }).click();
  await expect(line).toBeHidden();
});

test("a standing instruction for Felix is added, and deleted", async ({ page }) => {
  await settingsPage(page, /^Felix/);
  const form = page.getByRole("form", { name: "A standing instruction" });
  await form.getByLabel("A standing instruction for Felix").fill("No messages before eight");
  await form.getByRole("button", { name: "Add", exact: true }).click();
  const line = page.getByRole("listitem").filter({ hasText: "No messages before eight" });
  await expect(line).toBeVisible();
  await line.getByRole("button", { name: /^Delete/ }).click();
  await expect(line).toBeHidden();
});

test("a sign-in is held, and what it is isn't shown", async ({ page }) => {
  await settingsPage(page, /^Sources/);
  const form = page.getByRole("form", { name: "Sign-ins" });
  const address = form.getByLabel("Address");
  await expect(address).toHaveAttribute("placeholder", "Not set");
  await address.fill("someone@example.org");
  await form.getByRole("button", { name: "Save sign-ins" }).click();
  await expect(address).toHaveValue("");
  await expect(address).toHaveAttribute("placeholder", "Held");
  await settingsPage(page, /^Sources/);
  await expect(page.getByRole("form", { name: "Sign-ins" }).getByLabel("Address")).toHaveAttribute("placeholder", "Held");
  await expect(page.getByText("someone@example.org")).toBeHidden();
});

/** The code the secret global-setup gave the server makes now: RFC 6238's own made-up one. */
function parentCode(): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)));
  const hash = createHmac("sha1", "12345678901234567890").update(counter).digest();
  const offset = (hash[hash.length - 1] ?? 0) & 0x0f;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}

// Last, as arming stays: a command does it, as the phone's the one that arms.
test("armed, a loosening waits, with its Cancel and a parent's code", async ({ page, request }) => {
  const arm = await request.post("/api/commands", {
    headers: { "X-Requested-With": "test" },
    data: { commands: [{ id: "arm", at: Date.now(), action: { type: "ChangeSettings", fields: { armed: true } } }] },
  });
  expect(arm.ok()).toBe(true);
  await settingsPage(page, /^Planning/);
  await expect(page.getByText("Protection is armed")).toBeVisible();
  const field = page.getByLabel("Minutes of work for one of free time †");
  await field.fill("2");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  const waiting = page.getByRole("listitem").filter({ hasText: "applies about" });
  await expect(waiting).toBeVisible();
  await waiting.getByRole("button", { name: "Cancel" }).click();
  await expect(waiting).toBeHidden();
  await page.getByLabel("Minutes of work for one of free time †").fill("2");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(waiting).toBeVisible();
  await waiting.getByRole("button", { name: "Parent code" }).click();
  const code = waiting.getByRole("form", { name: "Parent code" });
  await code.getByLabel("Code").fill(parentCode() === "000000" ? "000001" : "000000");
  await code.getByRole("button", { name: "Apply" }).click();
  await expect(waiting.getByRole("status")).toContainText("Wrong code");
  await code.getByLabel("Code").fill(parentCode());
  await code.getByRole("button", { name: "Apply" }).click();
  await expect(waiting).toBeHidden();
  await expect(page.getByLabel("Minutes of work for one of free time †")).toHaveValue("2");
});
