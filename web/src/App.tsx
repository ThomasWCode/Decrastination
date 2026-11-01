import { useEffect, useState, type MouseEvent, type ReactNode } from "react";
import { api } from "./api";
import { DayPage } from "./Day";
import { PhonePage } from "./Phone";
import { SettingsPageView } from "./Settings";
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
export function Link({ to, go, current, children }: { to: string; go: Go; current?: boolean; children: ReactNode }) {
  const follow = (event: MouseEvent) => {
    if (event.metaKey || event.ctrlKey) return;
    event.preventDefault();
    go(to);
  };
  return (
    <a href={to} onClick={follow} aria-current={current ? "page" : undefined}>
      {children}
    </a>
  );
}

const PAGES = [
  { path: "/", name: "Day" },
  { path: "/work", name: "Work" },
  { path: "/settings", name: "Settings" },
  { path: "/phone", name: "Phone" },
];

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
    api.signOut().then(() => setSignedIn(false));
  };
  return (
    <div className="site">
      <header className="bar">
        <span className="name">Decrastination</span>
        <nav aria-label="Pages">
          {PAGES.map((page) => (
            <Link key={page.path} to={page.path} go={go} current={url.pathname === page.path}>
              {page.name}
            </Link>
          ))}
        </nav>
        <button className="quiet" onClick={signOut}>
          Sign out
        </button>
      </header>
      {url.pathname === "/work" ? (
        <WorkPage />
      ) : url.pathname === "/settings" ? (
        <SettingsPageView />
      ) : url.pathname === "/phone" ? (
        <PhonePage />
      ) : (
        <DayPage date={url.searchParams.get("date")} go={go} />
      )}
    </div>
  );
}
