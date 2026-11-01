import { describe, expect, it, vi } from "vitest";
import source from "../public/sw.js?raw";

type Handler = (event: unknown) => void;

/** The service worker run against a made-up browser: what it listens for, and what it was given to wait for. */
function worker(windows: { focused?: boolean; focus?: () => Promise<unknown>; navigate?: (url: string) => Promise<unknown> }[]) {
  const handlers: Record<string, Handler> = {};
  const showNotification = vi.fn(() => Promise.resolve());
  const openWindow = vi.fn(() => Promise.resolve());
  const self = {
    addEventListener: (name: string, handler: Handler) => {
      handlers[name] = handler;
    },
    skipWaiting: vi.fn(),
    clients: { claim: vi.fn(() => Promise.resolve()), matchAll: vi.fn(() => Promise.resolve(windows)), openWindow },
    registration: { showNotification },
  };
  new Function("self", source)(self);
  const run = async (name: string, event: object) => {
    let waited: Promise<unknown> = Promise.resolve();
    handlers[name]?.({
      ...event,
      waitUntil: (work: Promise<unknown>) => {
        waited = work;
      },
    });
    await waited;
  };
  return { self, run, showNotification, openWindow };
}

const pushed = { data: { json: () => ({ title: "Felix", body: "A made-up word", url: "/", tag: "felix" }) } };

describe("the service worker", () => {
  it("shows what's pushed, its tag and where a tap goes with it", async () => {
    const { run, showNotification } = worker([{ focused: false }]);
    await run("push", pushed);
    expect(showNotification).toHaveBeenCalledWith("Felix", { body: "A made-up word", tag: "felix", data: { url: "/" } });
  });

  it("shows it with a page in front too, as a browser can stop sending to a site whose pushes show nothing", async () => {
    const { run, showNotification } = worker([{ focused: true }]);
    await run("push", pushed);
    expect(showNotification).toHaveBeenCalled();
  });

  it("takes a tap to an open page", async () => {
    const said: string[] = [];
    const focus = vi.fn(() => {
      said.push("focus");
      return Promise.resolve();
    });
    const navigate = vi.fn(() => {
      said.push("navigate");
      return Promise.resolve();
    });
    const { run, openWindow } = worker([{ focus, navigate }]);
    const close = vi.fn();
    await run("notificationclick", { notification: { close, data: { url: "/day" } } });
    expect(close).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith("/day");
    expect(said).toEqual(["focus", "navigate"]);
    expect(openWindow).not.toHaveBeenCalled();
  });

  it("opens a page where the one it found can't be sent there", async () => {
    const { run, openWindow } = worker([{ focus: () => Promise.resolve(), navigate: () => Promise.reject(new Error("made up")) }]);
    await run("notificationclick", { notification: { close: vi.fn(), data: { url: "/day" } } });
    expect(openWindow).toHaveBeenCalledWith("/day");
  });

  it("opens the site with none open", async () => {
    const { run, openWindow } = worker([]);
    await run("notificationclick", { notification: { close: vi.fn(), data: { url: "/" } } });
    expect(openWindow).toHaveBeenCalledWith("/");
  });

  it("takes over at once, so a tap can reach a page already open", async () => {
    const { self, run } = worker([]);
    await run("activate", {});
    expect(self.clients.claim).toHaveBeenCalled();
  });
});
