import { expect, test, type Locator, type Page } from "@playwright/test";

// The chat, against a server in commands mode, with a stand-in for Claude (claude-stand-in.mjs) and a made-up chat (global-setup.ts). The
// tests share it and run in order: the first two read the same new message, and the last clears it all.

// The first with the words: a reply that quotes them comes after.
const message = (page: Page, words: string): Locator => page.getByTestId("message").filter({ hasText: words }).first();

/** [words] sent as a turn of the chat, not a reply to what the box is offered. */
async function say(page: Page, words: string) {
  const box = page.getByRole("textbox", { name: /^Tell / });
  await expect(box).toBeVisible();
  const offered = page.getByRole("button", { name: "Not replying to this" });
  if (await offered.isVisible()) await offered.click();
  await box.fill(words);
  await page.getByRole("button", { name: "Send", exact: true }).click();
  await expect(message(page, words)).toBeVisible();
}

test("what's new is marked, and passing through leaves it new", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("New messages")).toBeVisible();
  await expect(page.getByTestId("message").filter({ hasText: "A new word for you" })).toBeInViewport();
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Work" }).click();
  await expect(page).toHaveTitle("(1) Decrastination");
  await expect(page.getByRole("img", { name: "1 unread" })).toBeVisible();
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Chat" }).click();
  await expect(page.getByText("New messages")).toBeVisible();
  await expect(page).toHaveTitle("Decrastination");
});

test("a look that's lasted reads it, and the mark's gone at the next visit", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("New messages")).toBeVisible();
  await page.waitForTimeout(3_400);
  // Still there this visit: it's over what was new when it opened.
  await expect(page.getByText("New messages")).toBeVisible();
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Work" }).click();
  await expect(page.getByRole("img", { name: "Nothing unread" })).toBeVisible();
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Chat" }).click();
  await expect(page.getByTestId("chat-list")).toBeVisible();
  await expect(page.getByText("New messages")).toBeHidden();
});

test("Enter that confirms what an input method's composing sends nothing", async ({ page }) => {
  await page.goto("/");
  const box = page.getByRole("textbox", { name: /^Tell / });
  await box.fill("Still being composed");
  await box.dispatchEvent("keydown", { key: "Enter", isComposing: true, bubbles: true });
  await expect(box).toHaveValue("Still being composed");
  await box.fill("");
});

test("his question's offered to the box, and turned down it stays so, its chips still there", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Replying to Fe")).toBeVisible();
  await expect(page.getByRole("form", { name: "Write to Fe" }).getByText("Can you work through the second made-up event?")).toBeVisible();
  await page.getByRole("button", { name: "Not replying to this" }).click();
  await expect(page.getByText("Replying to Fe")).toBeHidden();
  await expect(page.getByRole("group", { name: "His replies" }).getByRole("button", { name: "None" })).toBeVisible();
});

test("a message is answered, and the box is ready for the next", async ({ page }) => {
  await page.goto("/");
  await say(page, "How does tomorrow look?");
  await expect(message(page, "I hear you.")).toBeVisible();
  await expect(page.getByRole("textbox", { name: /^Tell / })).toHaveValue("");
});

test("Enter sends, and what's unsent stays through a move to another page", async ({ page }) => {
  await page.goto("/");
  const box = page.getByRole("textbox", { name: /^Tell / });
  await expect(box).toBeVisible();
  await box.fill("Half a thought");
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Work" }).click();
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Chat" }).click();
  await expect(box).toHaveValue("Half a thought");
  await box.press("Enter");
  await expect(message(page, "Half a thought")).toBeVisible();
  await expect(box).toHaveValue("");
});

test("once you've talked to him, a question from before says it waits, with nothing offered", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: /^Tell / })).toBeVisible();
  await expect(page.getByText("Replying to Fe")).toBeHidden();
  await expect(message(page, "Can you work through the second made-up event?").getByText("waiting for your answer")).toBeVisible();
});

test("Mark answered takes it off, and its Undo puts it back", async ({ page }) => {
  await page.goto("/");
  const question = message(page, "Can you work through the first made-up event?");
  await question.hover();
  await question.getByRole("button", { name: "Mark answered" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Marked answered" })).toBeVisible();
  await expect(question.getByText("waiting for your answer")).toBeHidden();
  await page.getByRole("button", { name: "Undo", exact: true }).click();
  await expect(question.getByText("waiting for your answer")).toBeVisible();
});

test("one of his chosen shows its own replies, and a chip pressed answers it, with no call", async ({ page }) => {
  await page.goto("/");
  const question = message(page, "Can you work through the second made-up event?");
  await question.hover();
  await question.getByRole("button", { name: "Reply" }).click();
  await expect(page.getByText("Replying to Fe")).toBeVisible();
  await page.getByRole("group", { name: "His replies" }).getByRole("button", { name: "None" }).click();
  await expect(question.getByText("waiting for your answer")).toBeHidden();
  await expect(page.getByTestId("message").filter({ has: page.locator(".words", { hasText: /^None$/ }) })).toHaveCount(1);
  await expect(page.getByText("Replying to Fe")).toBeHidden();
  await expect(page.getByRole("button", { name: "None" })).toBeHidden();
});

test("a rating's kept, with the text he didn't send you'd rather have had, and comes off", async ({ page }) => {
  await page.goto("/");
  const his = message(page, "Busy, but it fits.");
  await his.hover();
  await his.getByRole("button", { name: "Thumbs up" }).click();
  const box = his.getByRole("form", { name: "Thumbs up" });
  await expect(box.getByText("Glad to have good news")).toBeVisible();
  await box.getByRole("button", { name: "Full, but fine." }).click();
  await box.getByLabel("What was good?").fill("Straight to it");
  await box.getByRole("button", { name: "Save" }).click();
  await expect(box).toBeHidden();
  await his.getByRole("button", { name: "Rated thumbs up: change" }).click();
  await expect(box.getByLabel("What was good?")).toHaveValue("Straight to it");
  await expect(box.getByRole("button", { name: "Full, but fine." })).toHaveAttribute("aria-pressed", "true");
  await box.getByRole("button", { name: "Remove" }).click();
  await expect(his.getByRole("button", { name: "Rated thumbs up: change" })).toBeHidden();
});

test("what he looked up is folded under his words, each page a link, and one a browser shouldn't follow isn't", async ({ page }) => {
  await page.goto("/");
  const his = message(page, "Busy, but it fits.");
  await his.getByRole("button", { name: /Show what he did/ }).click();
  await expect(his.getByText("Searched the web for “made-up words”")).toBeVisible();
  await expect(his.getByRole("link", { name: "A made-up page" })).toHaveAttribute("href", "https://example.com/made-up");
  await expect(his.getByText("Not a page to follow")).toBeVisible();
  await expect(his.getByRole("link", { name: "Not a page to follow" })).toHaveCount(0);
});

test("an answer that couldn't be read says so, and Try again asks again", async ({ page }) => {
  await page.goto("/");
  await say(page, "please be unreadable");
  const yours = message(page, "please be unreadable");
  await expect(yours.getByText("Claude's answer couldn't be read: Try again")).toBeVisible();
  await yours.getByRole("button", { name: "Try again" }).click();
  await expect(message(page, "Read you this time.")).toBeVisible();
  await expect(yours.getByRole("button", { name: "Try again" })).toBeHidden();
});

test("Take back takes that one back", async ({ page }) => {
  await page.goto("/");
  await say(page, "please always fail");
  const yours = message(page, "please always fail");
  await expect(yours.getByText("Claude's answer couldn't be read: Try again")).toBeVisible();
  await yours.getByRole("button", { name: "Take back" }).click();
  await expect(page.getByTestId("message").filter({ hasText: "please always fail" })).toHaveCount(0);
});

test("he's shown typing, and a long wait can be stopped", async ({ page }) => {
  test.setTimeout(45_000);
  await page.goto("/");
  await say(page, "please take a while");
  await expect(page.getByRole("status", { name: "Fe is typing" })).toBeVisible();
  await page.getByRole("button", { name: "Cancel", exact: true }).click({ timeout: 15_000 });
  await expect(page.getByRole("status", { name: "Fe is typing" })).toBeHidden();
  const yours = message(page, "please take a while");
  await expect(yours.getByRole("button", { name: "Take back" })).toBeVisible();
  await yours.getByRole("button", { name: "Take back" }).click();
  await expect(page.getByTestId("message").filter({ hasText: "please take a while" })).toHaveCount(0);
});

test("a card he made can be undone, made again, and changed from the form", async ({ page }) => {
  await page.goto("/");
  await say(page, "please add the worksheet");
  const his = message(page, "Added it.");
  await his.getByRole("button", { name: /Show what he did/ }).click();
  const card = his.getByRole("region", { name: "A worksheet to add" });
  await expect(card.getByText("Added")).toBeVisible();
  await card.getByRole("button", { name: "Undo" }).click();
  await expect(card.getByText("Undone")).toBeVisible();
  await card.getByRole("button", { name: "Change first" }).click();
  const form = page.getByRole("form", { name: "New task" });
  await form.getByLabel("What").fill("A worksheet, changed");
  await form.getByRole("button", { name: "Add", exact: true }).click();
  const changed = his.getByRole("region", { name: "A worksheet, changed" });
  await expect(changed.getByText("Added")).toBeVisible();
  await page.goto("/work");
  await expect(page.getByRole("button", { name: "A worksheet, changed", exact: true })).toBeVisible();
});

test("your changes are beside the chat, and one can be taken back", async ({ page }) => {
  await page.goto("/");
  const changes = page.getByRole("complementary", { name: "Your changes" });
  await expect(changes.getByRole("heading", { name: "In use" })).toBeVisible();
  const takeBack = changes.getByRole("button", { name: "Take back" });
  const before = await takeBack.count();
  expect(before).toBeGreaterThan(0);
  await takeBack.first().click();
  await expect(takeBack).toHaveCount(before - 1);
});

test("a task he shows you opens its sheet", async ({ page }) => {
  await page.goto("/");
  const his = message(page, "Here's the one to look at.");
  await his.getByRole("button", { name: "Open" }).click();
  await expect(page).toHaveURL(/\/work\?task=/);
  await expect(page.getByTestId("task-sheet").getByRole("heading", { name: "A task he shows you" })).toBeVisible();
});

test("scrolling back reads the archive, and it's there to read but not to act on", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Earlier messages" }).click();
  const old = message(page, "An old word of his");
  await expect(old).toBeVisible();
  await old.hover();
  await expect(old.getByRole("button", { name: "Mark answered" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Earlier messages" })).toBeHidden();
});

test("a quote goes to what it quotes, in the archive too", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByTestId("message").filter({ hasText: "An old question of his" }).first()).toBeVisible();
  await page.getByRole("button", { name: /An old question of his/ }).click();
  await expect(page.locator("#message-seed-old-asked")).toBeVisible();
  await expect(page.locator("#message-seed-old-asked")).toBeInViewport();
});

test("what got there unheard goes again as itself, so it's the one message, a move to another page between", async ({ page }) => {
  await page.goto("/");
  let lost = false;
  await page.route("**/api/commands", async (route) => {
    if (lost) return route.continue();
    lost = true;
    await route.fetch();
    await route.abort();
  });
  const box = page.getByRole("textbox", { name: /^Tell / });
  await box.fill("Said once");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  await expect(page.getByText("That didn't reach the server. Try again.")).toBeVisible();
  await expect(box).toHaveValue("Said once");
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Work" }).click();
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Chat" }).click();
  await expect(box).toHaveValue("Said once");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  await expect(box).toHaveValue("");
  // One sent after it: a second copy of the first would have landed by the time this shows.
  await say(page, "Said after it");
  await expect(page.getByTestId("message").filter({ hasText: "Said once" })).toHaveCount(1);
});

test("leaving reads what the page showed, not what's been written since", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: /^Tell / })).toBeVisible();
  await page.waitForTimeout(3_400);
  // The page hears no more of the chat, and a message comes meanwhile.
  await page.route("**/api/views/chat**", (route) => route.abort());
  const unread = async () => (await (await page.request.get("/api/views/notices")).json()).unread as number;
  const before = await unread();
  const sent = await page.request.post("/api/commands", {
    headers: { "X-Requested-With": "test" },
    data: { commands: [{ id: crypto.randomUUID(), at: Date.now(), action: { type: "SendMessage", text: "Not shown on that page", id: crypto.randomUUID() } }] },
  });
  expect(sent.ok()).toBeTruthy();
  await expect.poll(unread).toBeGreaterThan(before);
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Work" }).click();
  await expect(page.getByRole("img", { name: "1 unread" })).toBeVisible();
  await page.unroute("**/api/views/chat**");
  await page.waitForTimeout(500);
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Chat" }).click();
  await expect(page.getByText("New messages")).toBeVisible();
  await page.waitForTimeout(3_400);
  await page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Work" }).click();
  await expect(page.getByRole("img", { name: "Nothing unread" })).toBeVisible();
});

test("how the chat looks, to be seen", async ({ page }) => {
  // Run alone, with LOOK=<a folder>, as look.spec.ts is: this one can be pressed.
  const folder = process.env.LOOK;
  test.skip(!folder, "Only when asked: LOOK=<a folder>");
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto("/");
    const his = message(page, "Busy, but it fits.");
    await his.getByRole("button", { name: /Show what he did/ }).click();
    await his.hover();
    await his.getByRole("button", { name: "Thumbs down" }).click();
    await page.getByRole("textbox", { name: /^Tell / }).fill("Half a thought");
    await page.screenshot({ path: `${folder}/chat-writing-${scheme}.png` });
  }
});

test("clearing the chat asks first, and takes all of it", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Clear the chat" }).click();
  const asking = page.getByRole("alertdialog", { name: "Clear the chat?" });
  await asking.getByRole("button", { name: "Cancel" }).click();
  await expect(page.getByTestId("message").first()).toBeVisible();
  await page.getByRole("button", { name: "Clear the chat" }).click();
  await asking.getByRole("button", { name: "Clear" }).click();
  await expect(page.getByTestId("message")).toHaveCount(0);
  await expect(page.getByText("Tell Fe about your work")).toBeVisible();
  await expect(page.getByRole("button", { name: "Earlier messages" })).toBeHidden();
  await expect(page.getByRole("button", { name: "Clear the chat" })).toBeHidden();
});
