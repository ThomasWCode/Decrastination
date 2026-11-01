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

/** A task of your own due tomorrow night, so the plan finds it room, however full its days are. */
async function dueTask(page: Page, title: string) {
  await page.goto("/work");
  await page.getByRole("button", { name: "New task" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill(title);
  await form.getByLabel("Due").fill(`${dated(tomorrow())}T23:30`);
  await form.getByLabel("Minutes").fill("30");
  await form.getByRole("button", { name: "Add", exact: true }).click();
  await expect(form).toBeHidden();
}

/** The week's days from today on, each with its grid opened, till [title]'s piece is on one: its block. */
async function pieceOf(page: Page, title: string): Promise<Locator> {
  const piece = page.getByTestId("piece").filter({ hasText: title });
  for (let ahead = 0; ahead < 8; ahead++) {
    const day = new Date();
    day.setDate(day.getDate() + ahead);
    await page.goto(`/day?date=${dated(day)}`);
    await expect(page.getByTestId("grid")).toBeVisible();
    await page.waitForTimeout(200);
    if ((await piece.count()) > 0) return piece.first();
  }
  throw new Error(`No piece of ${title} in the week`);
}

/** Holds [block] and drags it [dy] down, or onto what's at [to], and lets go. */
async function drag(page: Page, block: Locator, to: { dy: number } | Locator) {
  await block.scrollIntoViewIfNeeded();
  const box = await block.boundingBox();
  if (!box) throw new Error("No block to drag");
  const x = box.x + 20;
  const y = box.y + 8;
  await page.mouse.move(x, y);
  await page.mouse.down();
  if ("dy" in to) {
    await page.mouse.move(x, y + to.dy, { steps: 8 });
  } else {
    const onto = await to.boundingBox();
    if (!onto) throw new Error("Nowhere to drag it");
    await page.mouse.move(onto.x + onto.width / 2, onto.y + onto.height / 2, { steps: 8 });
  }
}

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

test("a piece dragged on the grid shows where it'll land, and goes there, the server saying what came of it", async ({ page }) => {
  await dueTask(page, "Dragged to a time");
  const block = await pieceOf(page, "Dragged to a time");
  await drag(page, block, { dy: 120 });
  const landing = page.getByTestId("landing");
  await expect(landing).toHaveText(/^\d\d:\d[05]$/);
  const shown = (await landing.textContent()) ?? "";
  const asked = page.waitForRequest("**/api/day/drop");
  await page.mouse.up();
  const drop = (await asked).postDataJSON() as { key: string; date: string; onto: string | null; minute: number };
  expect(drop.onto).toBeNull();
  // Where the page showed it would land, as the server's own rule makes of what's sent: to the nearest five.
  const [hours, minutes] = shown.split(":").map(Number);
  expect(Math.abs(drop.minute - ((hours ?? 0) * 60 + (minutes ?? 0)))).toBeLessThanOrEqual(3);
  await expect(page.getByRole("status")).not.toBeEmpty();
  await expect(landing).toBeHidden();
});

test("a piece dragged onto a day of the strip is asked to go there, with no time", async ({ page }) => {
  await dueTask(page, "Dragged to a day");
  const block = await pieceOf(page, "Dragged to a day");
  await page.route("**/api/day/drop", (route) => route.fulfill({ json: { done: true, said: "All of it on that day: undone in the chat's Your changes" } }));
  const other = page.getByRole("navigation", { name: "Week" }).locator("a:not([aria-current=page])").last();
  const date = await other.getAttribute("data-date");
  const asked = page.waitForRequest("**/api/day/drop");
  await drag(page, block, other);
  await expect(page.getByTestId("landing")).toBeHidden();
  await page.mouse.up();
  const drop = (await asked).postDataJSON() as { onto: string | null; minute: number | null };
  expect(drop.onto).toBe(date);
  expect(drop.minute).toBeNull();
  await expect(page.getByRole("status")).toContainText("All of it on that day");
});

test("Escape puts a block back, and a tap opens its sheet", async ({ page }) => {
  await dueTask(page, "Lifted and let go");
  const block = await pieceOf(page, "Lifted and let go");
  await drag(page, block, { dy: 90 });
  await expect(page.getByTestId("landing")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("landing")).toBeHidden();
  await page.mouse.up();
  await expect(page.getByRole("status")).toHaveCount(0);
  await block.click();
  await expect(page.getByTestId("task-sheet").getByRole("heading", { name: "Lifted and let go" })).toBeVisible();
});

test("the wheel with Ctrl held zooms the grid, and it stays so", async ({ page }) => {
  await page.goto("/day");
  const height = () => page.getByTestId("grid").locator(".grid").evaluate((grid) => grid.getBoundingClientRect().height);
  const before = await height();
  const box = await page.getByTestId("grid").boundingBox();
  if (!box) throw new Error("No grid");
  await page.mouse.move(box.x + 100, box.y + 100);
  await page.mouse.wheel(0, -100);
  await page.waitForTimeout(200);
  expect(await height(), "alone, the wheel scrolls").toBe(before);
  await page.keyboard.down("Control");
  await page.mouse.wheel(0, -100);
  await page.keyboard.up("Control");
  await expect.poll(height).toBeGreaterThan(before);
  const zoomed = await height();
  await page.reload();
  await expect(page.getByTestId("grid")).toBeVisible();
  await expect.poll(height).toBe(zoomed);
  await page.evaluate(() => localStorage.removeItem("day-hour"));
});

test("a timer's started on a piece and stopped, with its time said", async ({ page }) => {
  await newTask(page, "A piece to time", "30");
  const sheet = await sheetOf(page, "A piece to time");
  await sheet.getByRole("button", { name: "Start", exact: true }).click();
  const stop = sheet.getByRole("button", { name: /^Stop \(/ });
  await expect(stop).toBeVisible();
  await expect(stop).toContainText("left");
  await page.goto("/day");
  const timer = page.getByRole("region", { name: "Timer" });
  await expect(timer).toContainText("A piece to time");
  await expect(timer).toContainText("Timer:");
  await timer.getByRole("button", { name: "Stop" }).click();
  await expect(timer).toBeHidden();
});

test("a timer's words are read again when they turn, not at the page's next minute", async ({ page }) => {
  await page.clock.install();
  let asked = 0;
  await page.route("**/api/views/day**", async (route) => {
    asked++;
    const response = await route.fetch();
    await route.fulfill({ response, json: { ...(await response.json()), timer: { label: "A piece", says: `Timer: ${30 - asked} min left`, nextAt: (await page.evaluate(() => Date.now())) + 5_000 } } });
  });
  await page.goto("/day");
  const timer = page.getByRole("region", { name: "Timer" });
  await expect(timer).toBeVisible();
  const before = await timer.textContent();
  await page.clock.runFor(6_000);
  await expect(timer).not.toHaveText(before ?? "");
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("a step takes a timer of its own", async ({ page }) => {
  await page.goto("/work");
  await page.getByRole("button", { name: "New task" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill("Timed by its steps");
  await form.getByRole("button", { name: "Add a step" }).click();
  await form.getByRole("group", { name: "Step 1" }).getByLabel("Step").fill("A step to time");
  await form.getByRole("button", { name: "Add", exact: true }).click();
  const sheet = await sheetOf(page, "Timed by its steps");
  await sheet.getByRole("listitem").filter({ hasText: "A step to time" }).getByRole("button", { name: "Start", exact: true }).click();
  const stop = sheet.getByRole("listitem").filter({ hasText: "A step to time" }).getByRole("button", { name: /^Stop \(/ });
  await expect(stop).toBeVisible();
  await stop.click();
  await expect(stop).toBeHidden();
});

test("what's next can be done from the Day, with its Undo", async ({ page }) => {
  await page.goto("/day");
  const buttons = page.getByRole("group", { name: "Do" });
  await expect(buttons.getByRole("button", { name: "Done", exact: true })).toBeVisible();
  await buttons.getByRole("button", { name: "Done", exact: true }).click();
  const said = page.getByRole("status").filter({ hasText: "Done" });
  await expect(said).toBeVisible();
  await said.getByRole("button", { name: "Undo" }).click();
  await expect(said).toBeHidden();
});

test("a block let go off the grid stays where it was, and nothing's asked", async ({ page }) => {
  await dueTask(page, "Let go off the grid");
  const block = await pieceOf(page, "Let go off the grid");
  const drops: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/day/drop")) drops.push(request.url());
  });
  await drag(page, block, page.locator(".now-panel"));
  await expect(page.getByTestId("landing")).toBeHidden();
  await page.mouse.up();
  await page.waitForTimeout(300);
  expect(drops).toEqual([]);
});

test("Done on the last piece keeps what it came to, and its Undo, though the piece goes", async ({ page }) => {
  let gone = false;
  await page.route("**/api/views/day**", async (route) => {
    const response = await route.fetch();
    const json = await response.json();
    await route.fulfill({ response, json: gone ? { ...json, next: null } : json });
  });
  await page.route("**/api/tasks/**", async (route) => {
    gone = true;
    await route.continue();
  });
  await page.goto("/day");
  const done = page.getByRole("group", { name: "Do" }).getByRole("button", { name: "Done", exact: true });
  await done.click();
  await expect(page.getByText("Nothing to do")).toBeVisible();
  const said = page.getByRole("status").filter({ hasText: "Done" });
  await expect(said).toBeVisible();
  await said.getByRole("button", { name: "Undo" }).click();
  await expect(said).toBeHidden();
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("what a button came to stays with its day, and what a drop did doesn't follow you", async ({ page }) => {
  await dueTask(page, "Said of its own day");
  const block = await pieceOf(page, "Said of its own day");
  await page.route("**/api/day/drop", (route) => route.fulfill({ json: { done: false, said: "Refused here" } }));
  await drag(page, block, { dy: 60 });
  await page.mouse.up();
  const said = page.getByRole("status").filter({ hasText: "Refused here" });
  await expect(said).toBeVisible();
  const week = page.getByRole("navigation", { name: "Week" });
  await week.locator("a:not([aria-current=page])").first().click();
  await expect(said).toBeHidden();
});

test("Done's words go when you move to another day", async ({ page }) => {
  await page.goto("/day");
  await page.getByRole("group", { name: "Do" }).getByRole("button", { name: "Done", exact: true }).click();
  const said = page.getByRole("status").filter({ hasText: "Done" });
  await expect(said).toBeVisible();
  await page.getByRole("navigation", { name: "Week" }).locator("a:not([aria-current=page])").first().click();
  await expect(said).toBeHidden();
});

test("how the Day looks with something to press, to be seen", async ({ page }) => {
  // Run alone, with LOOK=<a folder>, as look.spec.ts is.
  const folder = process.env.LOOK;
  test.skip(!folder, "Only when asked: LOOK=<a folder>");
  await dueTask(page, "A piece to look at");
  const block = await pieceOf(page, "A piece to look at");
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await drag(page, block, { dy: 75 });
    await page.screenshot({ path: `${folder}/day-drag-${scheme}.png` });
    await page.keyboard.press("Escape");
    await page.mouse.up();
  }
  await page.goto("/day");
  await page.getByRole("group", { name: "Do" }).getByRole("button", { name: "Start" }).click();
  await page.getByRole("region", { name: "Timer" }).waitFor();
  await page.screenshot({ path: `${folder}/day-timer.png` });
  await page.getByRole("region", { name: "Timer" }).getByRole("button", { name: "Stop" }).click();
});

test("a check-in that isn't kept says why, with what's typed still there", async ({ page }) => {
  await page.route("**/api/week/check-in", (route) => route.fulfill({ json: { done: false, said: "A made-up reason" } }));
  await page.goto("/week");
  const form = page.getByRole("form", { name: "This week's check-in" });
  await form.getByRole("group", { name: "How did the week feel?" }).getByRole("button", { name: "2" }).click();
  await form.getByLabel("What got in the way?").fill("Made-up things");
  await form.getByRole("button", { name: "Save, and review the week" }).click();
  await expect(form.getByRole("status")).toHaveText("A made-up reason");
  await expect(form.getByLabel("What got in the way?")).toHaveValue("Made-up things");
  await expect(form.getByRole("button", { name: "Save, and review the week" })).toBeEnabled();
});

test("what can't be forgotten says so", async ({ page }) => {
  await page.route("**/api/commands", (route) => route.fulfill({ json: [{ id: "made-up", state: "dropped", why: "A made-up reason", revision: 1 }] }));
  await page.goto("/stats");
  await page.getByRole("button", { name: "Forget" }).first().click();
  await page.getByRole("alertdialog", { name: "Forget this?" }).getByRole("button", { name: "Forget" }).click();
  await expect(page.getByRole("status")).toHaveText("A made-up reason");
});

// Before the check-in: its review learns afresh, which can take a class's figure with it.
test("what's learned can be forgotten, asking first", async ({ page }) => {
  await page.goto("/stats");
  const forget = page.getByRole("button", { name: "Forget" }).first();
  await expect(forget).toBeVisible();
  const before = await page.getByRole("button", { name: "Forget" }).count();
  await forget.click();
  const asking = page.getByRole("alertdialog", { name: "Forget this?" });
  await asking.getByRole("button", { name: "Keep" }).click();
  await expect(asking).toBeHidden();
  await expect(page.getByRole("button", { name: "Forget" })).toHaveCount(before);
  await forget.click();
  await asking.getByRole("button", { name: "Forget" }).click();
  await expect(page.getByRole("button", { name: "Forget" })).toHaveCount(before - 1);
});

test("the week's check-in is kept, and says so", async ({ page }) => {
  await page.goto("/week");
  const form = page.getByRole("form", { name: "This week's check-in" });
  await expect(form.getByRole("button", { name: "Save, and review the week" })).toBeDisabled();
  await form.getByRole("group", { name: "How did the week feel?" }).getByRole("button", { name: "4" }).click();
  await form.getByLabel("What got in the way?").fill("Made-up things");
  const sent = page.waitForRequest("**/api/week/check-in");
  await form.getByRole("button", { name: "Save, and review the week" }).click();
  expect((await sent).postDataJSON()).toMatchObject({ feel: 4, inTheWay: "Made-up things" });
  await expect(page.getByText("This week's check-in is done.")).toBeVisible();
  await expect(form).toBeHidden();
});

test("a review's change can be undone, once for a double click, saying what came of it", async ({ page }) => {
  let sent = 0;
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().endsWith("/api/commands")) sent += 1;
  });
  await page.goto("/reviews");
  await page.getByRole("button", { name: "Undo" }).first().dblclick();
  await expect(page.getByRole("status")).toContainText(/back to|waits/);
  expect(sent).toBe(1);
});

test("the record comes down as a file", async ({ page }) => {
  await page.goto("/stats");
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "Export the record as CSV" }).click();
  expect((await download).suggestedFilename()).toBe("decrastination-record.zip");
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
  // A field typed in and cleared isn't a sign-in taken away.
  await address.fill("made up");
  await address.fill("");
  await expect(form.getByRole("button", { name: "Save sign-ins" })).toBeDisabled();
  await settingsPage(page, /^Sources/);
  await expect(page.getByRole("form", { name: "Sign-ins" }).getByLabel("Address")).toHaveAttribute("placeholder", "Held");
  await expect(page.getByText("someone@example.org")).toBeHidden();
});

/** A browser that can be asked, with no push service behind it: Chromium's own needs one to be reached. */
async function pushable(page: Page, answer: NotificationPermission = "granted") {
  await page.addInitScript((given) => {
    const subscription = {
      endpoint: "https://fcm.googleapis.com/fcm/send/made-up",
      toJSON: () => ({ keys: { p256dh: "made-up", auth: "made-up" } }),
      unsubscribe: () => {
        held = null;
        return Promise.resolve(true);
      },
    };
    let held: typeof subscription | null = null;
    const registered = {
      pushManager: {
        getSubscription: () => Promise.resolve(held),
        subscribe: () => {
          held = subscription;
          return Promise.resolve(subscription);
        },
      },
    };
    Object.defineProperty(navigator, "serviceWorker", {
      value: { register: () => Promise.resolve(registered), getRegistration: () => Promise.resolve(registered), ready: Promise.resolve(registered) },
    });
    Object.assign(window, { PushManager: {}, Notification: { requestPermission: () => Promise.resolve(given) } });
  }, answer);
}

test("a browser asks to be told, and stops", async ({ page }) => {
  await pushable(page);
  await settingsPage(page, /^Notifications/);
  const here = page.getByRole("region", { name: "This browser" });
  const felix = here.getByRole("checkbox", { name: "Felix's messages" });
  await expect(felix).not.toBeChecked();
  const asked = page.waitForRequest("**/api/push/subscription");
  await felix.click();
  expect((await asked).postDataJSON()).toEqual({ endpoint: "https://fcm.googleapis.com/fcm/send/made-up", p256dh: "made-up", auth: "made-up", channels: ["felix"] });
  await expect(felix).toBeChecked();
  const again = page.waitForRequest("**/api/push/subscription");
  await here.getByRole("checkbox", { name: "When a timer ends" }).click();
  expect((await again).postDataJSON()).toMatchObject({ channels: ["felix", "timer"] });
  await here.getByRole("checkbox", { name: "When a timer ends" }).click();
  await expect(here.getByRole("checkbox", { name: "When a timer ends" })).not.toBeChecked();
  const stopped = page.waitForRequest("**/api/push/forget");
  await felix.click();
  expect((await stopped).postDataJSON()).toEqual({ endpoint: "https://fcm.googleapis.com/fcm/send/made-up" });
  await expect(felix).not.toBeChecked();
  const left = await page.evaluate(async () => (await navigator.serviceWorker.ready).pushManager.getSubscription());
  expect(left).toBeNull();
});

test("signing out stops this browser being told", async ({ browser, baseURL }) => {
  // A session of its own, as this one ends it.
  const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  expect((await context.request.post("/api/test/signin")).ok()).toBeTruthy();
  const page = await context.newPage();
  await pushable(page);
  await settingsPage(page, /^Notifications/);
  const felix = page.getByRole("region", { name: "This browser" }).getByRole("checkbox", { name: "Felix's messages" });
  await felix.click();
  await expect(felix).toBeChecked();
  const stopped = page.waitForRequest("**/api/push/forget");
  await page.getByRole("button", { name: "Sign out" }).click();
  await stopped;
  await expect(page.getByRole("link", { name: "Sign in with GitHub" })).toBeVisible();
  const left = await page.evaluate(async () => (await navigator.serviceWorker.ready).pushManager.getSubscription());
  expect(left).toBeNull();
  await context.close();
});

test("signing out goes ahead even if the browser can't be stopped", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  expect((await context.request.post("/api/test/signin")).ok()).toBeTruthy();
  const page = await context.newPage();
  await pushable(page);
  await settingsPage(page, /^Notifications/);
  const felix = page.getByRole("region", { name: "This browser" }).getByRole("checkbox", { name: "Felix's messages" });
  await felix.click();
  await expect(felix).toBeChecked();
  await page.route("**/api/push/forget", (route) => route.abort());
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("link", { name: "Sign in with GitHub" })).toBeVisible();
  // Nothing can reach it now: the server hears of that from its push service, at the next send.
  const left = await page.evaluate(async () => (await navigator.serviceWorker.ready).pushManager.getSubscription());
  expect(left).toBeNull();
  await context.close();
});

test("a browser's changes go one at a time, as each sends the whole list", async ({ page }) => {
  await pushable(page);
  let letGo = (): void => undefined;
  const held = new Promise<void>((resolve) => {
    letGo = resolve;
  });
  await page.route("**/api/push/subscription", async (route) => {
    await held;
    await route.continue();
  });
  await settingsPage(page, /^Notifications/);
  const here = page.getByRole("region", { name: "This browser" });
  await here.getByRole("checkbox", { name: "Felix's messages" }).click();
  await expect(here.getByRole("checkbox", { name: "When a timer ends" })).toBeDisabled();
  letGo();
  await expect(here.getByRole("checkbox", { name: "When a timer ends" })).toBeEnabled();
  await here.getByRole("checkbox", { name: "Felix's messages" }).click();
  await expect(here.getByRole("checkbox", { name: "Felix's messages" })).not.toBeChecked();
});

test("a browser that won't show notifications says so, with nothing chosen", async ({ page }) => {
  await pushable(page, "denied");
  await settingsPage(page, /^Notifications/);
  const here = page.getByRole("region", { name: "This browser" });
  await here.getByRole("checkbox", { name: "Felix's messages" }).click();
  await expect(here.getByRole("status")).toContainText("won't show notifications");
  await expect(here.getByRole("checkbox", { name: "Felix's messages" })).not.toBeChecked();
});

test("what a browser asked for that can't be read isn't taken for nothing", async ({ page }) => {
  await pushable(page);
  await page.addInitScript(() => {
    void navigator.serviceWorker.ready.then((registered) => registered.pushManager.subscribe());
  });
  await page.route("**/api/push/channels", (route) => route.abort());
  await settingsPage(page, /^Notifications/);
  const here = page.getByRole("region", { name: "This browser" });
  await expect(here.getByText("couldn't be read")).toBeVisible();
  await expect(here.getByRole("checkbox")).toHaveCount(0);
  await page.unroute("**/api/push/channels");
  await page.route("**/api/push/channels", (route) => route.fulfill({ json: { channels: ["slots"] } }));
  await here.getByRole("button", { name: "Try again" }).click();
  await expect(here.getByRole("checkbox", { name: "When a slot starts" })).toBeChecked();
});

test("what a browser asked for is shown when it comes back", async ({ page }) => {
  await pushable(page);
  await page.route("**/api/push/channels", (route) => route.fulfill({ json: { channels: ["slots"] } }));
  await page.addInitScript(() => {
    void navigator.serviceWorker.ready.then((registered) => registered.pushManager.subscribe());
  });
  await settingsPage(page, /^Notifications/);
  await expect(page.getByRole("region", { name: "This browser" }).getByRole("checkbox", { name: "When a slot starts" })).toBeChecked();
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

test("a backup comes down and goes back in, saying what came of it", async ({ page }) => {
  await page.goto("/backup");
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download a backup" }).click();
  await page.getByLabel("Import a backup").setInputFiles(await (await download).path());
  await expect(page.getByRole("status")).toContainText("Restored the settings");
});

test("a file that isn't a backup says so", async ({ page }) => {
  await page.goto("/backup");
  await page.getByLabel("Import a backup").setInputFiles({ name: "made-up.json", mimeType: "application/json", buffer: Buffer.from("not a backup") });
  await expect(page.getByRole("status")).toContainText("That isn't a Decrastination backup");
});

test("a file past what Import reads says so", async ({ page }) => {
  await page.route("**/api/backup/import", (route) => route.fulfill({ status: 413, json: { error: "made up" } }));
  await page.goto("/backup");
  await page.getByLabel("Import a backup").setInputFiles({ name: "made-up.json", mimeType: "application/json", buffer: Buffer.from("{}") });
  await expect(page.getByRole("status")).toHaveText("That file is too big to be a backup.");
});

test("a reset asks first, says what it did, and its copy brings it back", async ({ page }) => {
  await page.goto("/backup");
  const row = page.getByRole("listitem").filter({ hasText: "What Felix remembers" });
  await row.getByRole("button", { name: "Reset" }).click();
  const asking = page.getByRole("alertdialog", { name: "Reset what Felix remembers?" });
  await asking.getByRole("button", { name: "Keep it" }).click();
  await expect(asking).toBeHidden();
  await expect(page.getByRole("status")).toHaveCount(0);
  await row.getByRole("button", { name: "Reset" }).click();
  await asking.getByRole("button", { name: "Reset" }).click();
  await expect(page.getByRole("status")).toContainText("What Felix remembers: reset. A copy of everything from before is in the app's own files");
  await page.getByRole("listitem").filter({ hasText: /^reset-/ }).first().getByRole("button", { name: "Restore" }).click();
  await expect(page.getByRole("status")).toContainText("Restored the settings");
});
