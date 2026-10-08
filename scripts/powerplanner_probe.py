#!/usr/bin/env python3
"""Phase 0 check: can we read your Power Planner agenda through the web API?

Prompts for your powerplanner.net username and password, logs in, fetches the
selected semester and its agenda, and prints the items. Credentials are held in
memory for the duration of the run only.

With --save, the semester and agenda responses are also written to
private/powerplanner_agenda.json (git-ignored), with anything that looks like a
login or session removed, so they can be read back without pasting them.

The username and password are read from DECRASTINATION_PP_USERNAME and
DECRASTINATION_PP_PASSWORD when set, and asked for otherwise.

Run:  python scripts/powerplanner_probe.py [--save]

Endpoints and request shapes come from the open-source web app
(github.com/powerplanner/powerplannerwebapp, src/src/api/util.ts and index.ts)
and the shared models (github.com/powerplanner/shared, PowerPlannerSending/WebRequests.cs).
"""

import argparse
import getpass
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

BASE_URL = "https://web.api.powerplanner.net/api"
# The "rate-limited development key" the official web app ships with.
HASHED_KEY = "3a4d3d842fd5c63e8c8ba5677c18abcc59affe2f3a8179081180d56a67376a74"
SAVE_PATH = Path(__file__).resolve().parent.parent / "private" / "powerplanner_agenda.json"
SECRET_KEYS = {"login", "session", "password", "token", "username"}


def post(path: str, body: dict) -> dict:
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        data=data,
        method="POST",
        headers={"Content-Type": "application/json", "HashedKey": HASHED_KEY},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} from {path}: {e.read()[:300]!r}")
        sys.exit(1)


def time_option(item: dict) -> str:
    """Power Planner keeps a task's time option in the seconds of its local date
    (viewItems.ts, timeOption): for homework (ItemType 5) 0 start of class, 1 before class,
    2 during class, 3 end of class, 4 a custom time, anything else all day; for other tasks,
    4 a custom time, anything else all day. Events (ItemType 6) use their end time instead."""
    date = item.get("Date") or ""
    if item.get("ItemType") != 5 or len(date) < 19:
        return "see EndTime" if item.get("ItemType") == 6 else "?"
    return {0: "start of class", 1: "before class", 2: "during class", 3: "end of class", 4: "custom time"}.get(
        int(date[17:19]), "all day"
    )


def scrub(value):
    """Drops any key that could hold a credential, at any depth."""
    if isinstance(value, dict):
        return {k: scrub(v) for k, v in value.items() if k.lower() not in SECRET_KEYS}
    if isinstance(value, list):
        return [scrub(v) for v in value]
    return value


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--save", action="store_true", help=f"also write the responses to {SAVE_PATH}")
    args = parser.parse_args()

    username = os.environ.get("DECRASTINATION_PP_USERNAME") or input("Power Planner username: ").strip()
    password = os.environ.get("DECRASTINATION_PP_PASSWORD") or getpass.getpass("Power Planner password: ")

    login = post("/LoginWeb", {"Username": username, "Password": password})
    if login.get("Error"):
        print("Login failed:", login["Error"])
        sys.exit(1)
    creds = {"AccountId": login["AccountId"], "Username": username, "Password": login["Session"]}
    print(f"Logged in. AccountId={login['AccountId']}")
    print("LoginWeb response keys:", sorted(login.keys()))

    sem = post("/GetSelectedSemesterId", {"Login": creds})
    print("GetSelectedSemesterId ->", json.dumps(scrub(sem))[:300])
    semester_id = sem.get("SelectedSemesterId") or sem.get("SemesterIdentifier") or sem.get("Identifier")
    years = None
    if not semester_id:
        years = post("/GetYearsAndSemesters", {"Login": creds})
        print("GetYearsAndSemesters ->", json.dumps(scrub(years), indent=1)[:1500])
        print("Couldn't find a selected semester id automatically; see the raw response above.")

    agenda = None
    timetable = None
    if semester_id:
        # GetAgenda leaves Classes null: names and timetables come from GetClassesAndSchedules.
        timetable = post("/GetClassesAndSchedules", {"Login": creds, "SemesterIdentifier": semester_id})
        class_list = timetable.get("Classes") or []
        print(f"GetClassesAndSchedules: {len(class_list)} class(es)")
        for c in class_list:
            print(f"- {c.get('Name')!r}: {len(c.get('Schedules') or [])} timetable slot(s)")
        with_slots = next((c for c in class_list if c.get("Schedules")), None)
        if with_slots:
            print("Raw first class with a timetable:")
            print(json.dumps(with_slots, indent=1)[:1500])

        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        agenda = post("/GetAgenda", {"Login": creds, "SemesterIdentifier": semester_id, "CurrentTime": now})
        if agenda.get("Error"):
            print("GetAgenda error:", agenda["Error"])
        else:
            # Keys can be present with a null value, so `or []` rather than a .get default.
            print("GetAgenda response:", {k: type(v).__name__ + (f"[{len(v)}]" if isinstance(v, list) else "") for k, v in agenda.items()})
            classes = {c["Identifier"]: c.get("Name", "?") for c in class_list}
            items = agenda.get("Items") or []
            print(f"\n{len(items)} agenda item(s):")
            for it in items:
                print(
                    f"- {it.get('Name')!r} | type={it.get('ItemType')} | class={classes.get(it.get('ClassIdentifier'), '?')} "
                    f"| date={it.get('Date')} ({time_option(it)}) | complete={it.get('PercentComplete')}"
                )
            print("\nRaw first item (for the field names the Kotlin model needs):")
            print(json.dumps(items[0] if items else {}, indent=1))

    if args.save:
        SAVE_PATH.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "capturedAt": datetime.now(timezone.utc).isoformat(),
            "loginResponseKeys": sorted(login.keys()),
            "selectedSemester": scrub(sem),
            "yearsAndSemesters": scrub(years),
            "classesAndSchedules": scrub(timetable),
            "agenda": scrub(agenda),
        }
        SAVE_PATH.write_text(json.dumps(payload, indent=1, ensure_ascii=False), encoding="utf-8")
        print(f"\nSaved to {SAVE_PATH} (git-ignored).")

    if not semester_id or agenda is None or agenda.get("Error"):
        sys.exit(2)


if __name__ == "__main__":
    main()
