#!/usr/bin/env python3
"""Loads the app's credentials from this PC's environment into the phone, without showing them.

Reads, from the process environment or (on Windows) your user environment in the registry:

  DECRASTINATION_PP_USERNAME, DECRASTINATION_PP_PASSWORD   Power Planner login
  DECRASTINATION_GMAIL_APP_PASSWORD                        Gmail app password (with --gmail-address)
  DECRASTINATION_ANTHROPIC_API_KEY                         Claude API key (optional; the AI stays off
                                                           until you switch it on in Settings)

and pipes them as JSON into the app's private storage with `adb exec-in run-as` (debug builds
only), then asks the app to move them into its encrypted store and delete the file
(`.debug.Command`, `import-credentials`). Nothing is written to this PC's disk or printed: only
the names of the credentials sent.

Run:  python scripts/load_credentials.py --gmail-address you@gmail.com [--serial SERIAL]
"""

import argparse
import json
import os
import subprocess
import sys

PACKAGE = "com.thomaswcode.decrastination"
VARIABLES = {
    "PowerPlannerUsername": "DECRASTINATION_PP_USERNAME",
    "PowerPlannerPassword": "DECRASTINATION_PP_PASSWORD",
    "GmailAppPassword": "DECRASTINATION_GMAIL_APP_PASSWORD",
    "AnthropicApiKey": "DECRASTINATION_ANTHROPIC_API_KEY",
}


def environment(name: str):
    """The variable from this process, else from the Windows user environment (set since this shell started)."""
    value = os.environ.get(name)
    if value:
        return value
    if sys.platform == "win32":
        import winreg

        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
                return winreg.QueryValueEx(key, name)[0] or None
        except OSError:
            return None
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--gmail-address", help="the Gmail address the app password belongs to")
    parser.add_argument("--serial", help="adb device serial, if more than one is connected")
    args = parser.parse_args()

    values = {key: environment(var) for key, var in VARIABLES.items()}
    values = {key: value for key, value in values.items() if value}
    if "GmailAppPassword" in values:
        if not args.gmail_address:
            sys.exit("--gmail-address is needed with a Gmail app password")
        values["GmailAddress"] = args.gmail_address
    if not values:
        sys.exit("None of the DECRASTINATION_* variables is set")

    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    payload = json.dumps(values).encode("utf-8")
    write = subprocess.run(
        adb + ["exec-in", "run-as", PACKAGE, "sh", "-c", "mkdir -p files && cat > files/credentials.import"],
        input=payload,
        capture_output=True,
    )
    if write.returncode != 0:
        sys.exit(f"Couldn't write into the app's storage: {write.stderr.decode(errors='replace').strip()}")
    start = subprocess.run(
        adb + ["shell", "am", "start", "-n", f"{PACKAGE}/.debug.Command", "--es", "cmd", "import-credentials"],
        capture_output=True,
        text=True,
    )
    if start.returncode != 0 or "Error" in start.stdout:
        sys.exit(f"Couldn't start the import: {start.stdout.strip()} {start.stderr.strip()}")
    print("Sent:", ", ".join(sorted(values)))
    print("Check with: adb logcat -d -s Decrastination | findstr Imported")


if __name__ == "__main__":
    main()
