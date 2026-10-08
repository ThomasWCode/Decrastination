#!/usr/bin/env python3
"""Phase 0 check: can we read your Power Planner agenda through the web API?

Prompts for your powerplanner.net username and password, logs in, fetches the
selected semester and its agenda, and prints the items. Nothing is written to
disk. Credentials are held in memory for the duration of the run only.

Run:  python scripts/powerplanner_probe.py

Endpoints and request shapes come from the open-source web app
(github.com/powerplanner/powerplannerwebapp, src/src/api/util.ts and index.ts)
and the shared models (github.com/powerplanner/shared, PowerPlannerSending/WebRequests.cs).
"""

import getpass
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

BASE_URL = "https://web.api.powerplanner.net/api"
# The "rate-limited development key" the official web app ships with.
HASHED_KEY = "3a4d3d842fd5c63e8c8ba5677c18abcc59affe2f3a8179081180d56a67376a74"


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


def main() -> None:
    username = input("Power Planner username: ").strip()
    password = getpass.getpass("Power Planner password: ")

    login = post("/LoginWeb", {"Username": username, "Password": password})
    if login.get("Error"):
        print("Login failed:", login["Error"])
        sys.exit(1)
    creds = {"AccountId": login["AccountId"], "Username": username, "Password": login["Session"]}
    print(f"Logged in. AccountId={login['AccountId']}")

    sem = post("/GetSelectedSemesterId", {"Login": creds})
    print("GetSelectedSemesterId ->", json.dumps(sem)[:300])
    semester_id = sem.get("SelectedSemesterId") or sem.get("SemesterIdentifier") or sem.get("Identifier")
    if not semester_id:
        years = post("/GetYearsAndSemesters", {"Login": creds})
        print("GetYearsAndSemesters ->", json.dumps(years, indent=1)[:1500])
        print("Couldn't find a selected semester id automatically; see the raw response above.")
        sys.exit(2)

    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    agenda = post("/GetAgenda", {"Login": creds, "SemesterIdentifier": semester_id, "CurrentTime": now})
    if agenda.get("Error"):
        print("GetAgenda error:", agenda["Error"])
        sys.exit(1)

    classes = {c["Identifier"]: c.get("Name", "?") for c in agenda.get("Classes", [])}
    items = agenda.get("Items", [])
    print(f"\n{len(items)} agenda item(s):")
    for it in items:
        print(
            f"- {it.get('Name')!r} | class={classes.get(it.get('ClassIdentifier'), '?')} "
            f"| date={it.get('Date')} | complete={it.get('PercentComplete')} "
            f"| extra keys={[k for k in it.keys() if k not in ('Name','ClassIdentifier','Date','PercentComplete','Identifier','DateCreated','ShortDetails')]}"
        )
    print("\nRaw first item (for the field names the Kotlin model needs):")
    print(json.dumps(items[0] if items else {}, indent=1))


if __name__ == "__main__":
    main()
