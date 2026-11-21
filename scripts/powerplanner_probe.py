#!/usr/bin/env python3
"""A check from the PC: can we read your Power Planner agenda through the web API?

Prompts for your powerplanner.net username and password, logs in, fetches the
selected semester (the years and semesters first, where no semester id is known),
then its classes, their timetables and its items in the one call the web app makes
(GetClassesSchedulesAndMegaItems: GetAgenda and GetClassesAndSchedules, read by till
6.10.2, answer 404 since 19 Nov 2026), and prints them, the agenda's items marked.
Credentials are held in memory for the duration of the run only. Exits 2 when the
read isn't proven.

With --save, those responses are also written to private/powerplanner_agenda.json
(git-ignored), with anything that looks like a login or session removed, so they
can be read back without pasting them.

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
from datetime import date, datetime, timezone
from pathlib import Path

BASE_URL = "https://web.api.powerplanner.net/api"
# The "rate-limited development key" the official web app ships with.
HASHED_KEY = "3a4d3d842fd5c63e8c8ba5677c18abcc59affe2f3a8179081180d56a67376a74"
SAVE_PATH = Path(__file__).resolve().parent.parent / "private" / "powerplanner_agenda.json"
SECRET_KEYS = {"login", "session", "password", "token", "username"}
# MegaItemType (the web app's enums.ts): a class's task and the semester's own; a class's exam and the semester's own event.
TASKS = {0, 3}
EVENTS = {1, 4}


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


def time_option(item: dict, semester_id: str) -> str:
    """Power Planner keeps a task's time option in the seconds of its local date
    (viewItems.ts, timeOption). A task (MegaItemType 0, or 3 for the semester's own) with a class:
    0 start of class, 1 before class, 2 during class, 3 end of class, 4 a custom time, anything else
    all day. A task with no class, whose UpperIdentifier is the semester's id: 4 a custom time,
    anything else all day. Events (MegaItemType 1 and 4) use their end time instead."""
    date = item.get("Date") or ""
    if item.get("MegaItemType") not in TASKS or len(date) < 19:
        return "see EndTime" if item.get("MegaItemType") in EVENTS else "?"
    second = int(date[17:19])
    if item.get("UpperIdentifier") == semester_id:
        return "custom time" if second == 4 else "all day"
    return {0: "start of class", 1: "before class", 2: "during class", 3: "end of class", 4: "custom time"}.get(
        second, "all day"
    )


def on_agenda(item: dict, today: date) -> bool:
    """As the app makes the agenda of the semester's items (PowerPlannerItems.onAgenda, the web app's isOld):
    a task until it's complete and its day has passed, an event until its day has."""
    day = (item.get("Date") or "")[:10]
    past = bool(day) and day < today.isoformat()
    if item.get("MegaItemType") in EVENTS:
        return not past
    return not (past and (item.get("PercentComplete") or 0) >= 1)


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

    semester = None
    failed = not semester_id
    if semester_id:
        # One call for the classes, the timetable's slots (a list of their own, each naming its class) and every
        # item of the semester: the agenda is made of them here, as the app makes it.
        semester = post("/GetClassesSchedulesAndMegaItems", {"Login": creds, "SemesterIdentifier": semester_id})
        if semester.get("Error"):
            print("GetClassesSchedulesAndMegaItems error:", semester["Error"])
            failed = True
        else:
            print("GetClassesSchedulesAndMegaItems response:", {k: type(v).__name__ + (f"[{len(v)}]" if isinstance(v, list) else "") for k, v in semester.items()})
            class_list = semester.get("Classes") or []
            slots = semester.get("Schedules") or []
            print(f"{len(class_list)} class(es), {len(slots)} timetable slot(s)")
            for c in class_list:
                print(f"- {c.get('Name')!r}: {sum(s.get('UpperIdentifier') == c.get('Identifier') for s in slots)} timetable slot(s)")
            if slots:
                print("Raw first timetable slot:")
                print(json.dumps(slots[0], indent=1)[:1500])

            classes = {c["Identifier"]: c.get("Name", "?") for c in class_list}
            # A list, empty or not: one missing or null is an answer the app refuses ("the answer had no list of
            # items", PowerPlannerApi), not a semester with none.
            items = semester.get("MegaItems")
            if not isinstance(items, list):
                print("GetClassesSchedulesAndMegaItems error: the answer had no list of items")
                failed = True
                items = []
            agenda = [it for it in items if on_agenda(it, date.today())]
            print(f"\n{len(items)} item(s) in the semester, {len(agenda)} on the agenda:")
            for it in agenda:
                print(
                    f"- {it.get('Name')!r} | type={it.get('MegaItemType')} | class={classes.get(it.get('UpperIdentifier'), '?')} "
                    f"| date={it.get('Date')} ({time_option(it, semester_id)}) | complete={it.get('PercentComplete')}"
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
            "semester": scrub(semester),
        }
        SAVE_PATH.write_text(json.dumps(payload, indent=1, ensure_ascii=False), encoding="utf-8")
        print(f"\nSaved to {SAVE_PATH} (git-ignored).")

    if failed:
        print("\nNot proven: see the error above.")
        sys.exit(2)


if __name__ == "__main__":
    main()
