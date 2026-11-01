import { useCallback, useEffect, useState, type MouseEvent, type ReactNode } from "react";
import { api } from "./api";
import { BackupPage } from "./Backup";
import { ChatPage } from "./Chat";
import { DayPage } from "./Day";
import { useLive } from "./live";
import { PhonePage } from "./Phone";
import { SettingsPageView } from "./Settings";
import { SetupPage } from "./Setup";
import { StatsPage } from "./Stats";
import { ReviewsPage, WeekPage } from "./Week";
import { WorkPage } from "./Work";

/** Where you are, kept in the address so a reload and the back button work. */
function useLocation(): [URL, (to: string) => void] {
  const [url, setUrl] = useState(() => new URL(location.href));
  useEffect(() => {
    const moved = () => setUrl(new URL(location.href));
    addEventListener("popstate", moved);
    return () => removeEventListener("popstate", moved);
  }, []);
  const go = (to: string) => {
    history.pushState(null, "", to);
    setUrl(new URL(location.href));
  };
  return [url, go];
}

export type Go = (to: string) => void;

/** A link inside the site: the address changes, nothing reloads. */
export function Link({ to, go, current, date, children }: { to: string; go: Go; current?: boolean; date?: string; children: ReactNode }) {
  const follow = (event: MouseEvent) => {
    if (event.metaKey || event.ctrlKey) return;
    event.preventDefault();
    go(to);
  };
  return (
    <a href={to} onClick={follow} aria-current={current ? "page" : undefined} data-date={date}>
      {children}
    </a>
  );
}

const PAGES = [
  { path: "/", name: "Chat" },
  { path: "/day", name: "Day" },
  { path: "/work", name: "Work" },
  { path: "/stats", name: "Stats" },
  { path: "/week", name: "This week" },
  { path: "/reviews", name: "Reviews" },
  { path: "/settings", name: "Settings" },
  { path: "/backup", name: "Backup" },
  { path: "/setup", name: "Setup" },
  { path: "/phone", name: "Phone" },
];

/** What waits for you, wherever you are (W-9): his unread, by the bell and in the tab's title, but while you're [reading] them. */
function Bell({ go, reading, waiting }: { go: Go; reading: boolean; waiting: number }) {
  const [hidden, setHidden] = useState(document.hidden);
  useEffect(() => {
    const moved = () => setHidden(document.hidden);
    document.addEventListener("visibilitychange", moved);
    return () => document.removeEventListener("visibilitychange", moved);
  }, []);
  const unread = reading && !hidden ? 0 : waiting;
  useEffect(() => {
    document.title = unread > 0 ? `(${unread}) Decrastination` : "Decrastination";
  }, [unread]);
  return (
    <Link to="/" go={go}>
      <span className="bell" role="img" aria-label={unread > 0 ? `${unread} unread` : "Nothing unread"}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 3a6 6 0 0 0-6 6v4l-2 3v1h16v-1l-2-3V9a6 6 0 0 0-6-6Zm-2 15a2 2 0 0 0 4 0" />
        </svg>
        {unread > 0 ? <span className="count">{unread}</span> : null}
      </span>
    </Link>
  );
}

/** The site's bar: its pages, Setup marked while it needs you (UI-18), the bell and signing out. */
function Bar({ url, go, signOut }: { url: URL; go: Go; signOut: () => void }) {
  const load = useCallback(() => api.notices(), []);
  const { value } = useLive(load);
  return (
    <header className="bar">
      <span className="name">Decrastination</span>
      <nav aria-label="Pages">
        {PAGES.map((page) => (
          <Link key={page.path} to={page.path} go={go} current={url.pathname === page.path}>
            {page.name}
            {page.path === "/setup" && value?.setup ? <span className="needs-you" role="img" aria-label="needs you" /> : null}
          </Link>
        ))}
      </nav>
      <Bell go={go} reading={url.pathname === "/"} waiting={value?.unread ?? 0} />
      <button className="quiet" onClick={signOut}>
        Sign out
      </button>
    </header>
  );
}

export function App() {
  const [signedIn, setSignedIn] = useState<boolean | null>(null);
  const [url, go] = useLocation();
  useEffect(() => {
    api
      .me()
      .then((me) => setSignedIn(me.signedIn))
      .catch(() => setSignedIn(false));
  }, []);
  if (signedIn === null) return null;
  if (!signedIn) {
    return (
      <main className="signin">
        <h1>Decrastination</h1>
        <a className="button" href="/api/auth/github">
          Sign in with GitHub
        </a>
      </main>
    );
  }
  const signOut = () => {
    // What the chat's box held goes with the session.
    sessionStorage.clear();
    api.signOut().then(() => setSignedIn(false));
  };
  return (
    <div className="site">
      <Bar url={url} go={go} signOut={signOut} />
      {url.pathname === "/work" ? (
        <WorkPage />
      ) : url.pathname === "/stats" ? (
        <StatsPage />
      ) : url.pathname === "/week" ? (
        <WeekPage />
      ) : url.pathname === "/reviews" ? (
        <ReviewsPage />
      ) : url.pathname === "/settings" ? (
        <SettingsPageView />
      ) : url.pathname === "/backup" ? (
        <BackupPage />
      ) : url.pathname === "/setup" ? (
        <SetupPage />
      ) : url.pathname === "/phone" ? (
        <PhonePage />
      ) : url.pathname === "/day" ? (
        <DayPage date={url.searchParams.get("date")} go={go} />
      ) : (
        <ChatPage go={go} />
      )}
    </div>
  );
}
