// The website's service worker, there only to show what the server pushes (W-9): it caches nothing, as a page that's offline
// has nothing to say.

self.addEventListener("install", () => self.skipWaiting());

self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  const message = event.data.json();
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((windows) => {
      // A page you're looking at says it itself.
      if (windows.some((page) => page.focused)) return undefined;
      return self.registration.showNotification(message.title, { body: message.body, tag: message.tag, data: { url: message.url } });
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const { url } = event.notification.data;
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((windows) => {
      const page = windows[0];
      return page ? page.navigate(url).then((there) => there?.focus()) : self.clients.openWindow(url);
    }),
  );
});
