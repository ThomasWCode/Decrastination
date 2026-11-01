// The website's service worker, there only to show what the server pushes (W-9): it caches nothing, as a page that's offline
// has nothing to say.

self.addEventListener("install", () => self.skipWaiting());

self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

// Every one is shown, a page in front or not: a browser can stop sending to a site whose pushes show nothing.
self.addEventListener("push", (event) => {
  const message = event.data.json();
  event.waitUntil(self.registration.showNotification(message.title, { body: message.body, tag: message.tag, data: { url: message.url } }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const { url } = event.notification.data;
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((windows) => {
      const page = windows[0];
      if (!page) return self.clients.openWindow(url);
      // Focus first, while the tap still counts for it. A page this worker doesn't control can't be sent anywhere: a new one, then.
      return page
        .focus()
        .then(() => page.navigate(url))
        .catch(() => self.clients.openWindow(url));
    }),
  );
});
