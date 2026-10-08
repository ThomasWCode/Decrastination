#!/usr/bin/env python3
"""Phase 0 check: can Decrastination read your Gmail inbox over IMAP with an app password?

Read-only by construction. Every folder is opened with EXAMINE, so the server refuses any change
to flags or messages, and headers are fetched with BODY.PEEK, so nothing is marked as read. IMAP
cannot send mail, and this script never opens an SMTP connection.

Prompts for the address and a Google app password (myaccount.google.com/apppasswords; needs
2-Step Verification), then prints:
  - the server's capabilities (X-GM-EXT-1 means Gmail's message ids and labels are available),
  - every folder, and how many messages each snooze-like folder holds, to learn whether
    snoozed mail can be seen over IMAP,
  - one line per inbox message: date, Gmail labels, sender, subject.

With --save, the same goes to private/gmail_probe.json (git-ignored), so the results can be read
back without pasting them. The password is never written anywhere.

The app password is read from DECRASTINATION_GMAIL_APP_PASSWORD when set, and asked for
otherwise; --address picks the account (default: the one below).

Run:  python scripts/gmail_probe.py [--save] [--address you@gmail.com]
"""

import argparse
import email
import email.policy
import getpass
import imaplib
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

HOST = "imap.gmail.com"
DEFAULT_ADDRESS = "thomasawhite321@gmail.com"
SAVE_PATH = Path(__file__).resolve().parent.parent / "private" / "gmail_probe.json"
FETCH_ITEMS = "(X-GM-MSGID X-GM-THRID X-GM-LABELS INTERNALDATE FLAGS BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])"


def parse_list_line(line: bytes) -> dict:
    """b'(\\HasNoChildren \\All) "/" "[Gmail]/All Mail"' -> {"flags": [...], "name": "[Gmail]/All Mail"}"""
    m = re.match(rb'\((?P<flags>[^)]*)\) "(?P<sep>[^"]*)" (?P<name>.+)$', line)
    if not m:
        return {"raw": line.decode("utf-8", "replace")}
    name = m.group("name").decode("utf-8", "replace").strip()
    if name.startswith('"') and name.endswith('"'):
        name = name[1:-1]
    return {"flags": m.group("flags").decode().split(), "name": name}


def examine_count(imap: imaplib.IMAP4_SSL, folder: str) -> int | None:
    typ, data = imap.select(f'"{folder}"', readonly=True)  # EXAMINE
    return int(data[0]) if typ == "OK" else None


def fetch_inbox(imap: imaplib.IMAP4_SSL) -> list[dict]:
    typ, data = imap.uid("SEARCH", None, "ALL")
    uids = data[0].split() if typ == "OK" and data and data[0] else []
    if not uids:
        return []
    typ, data = imap.uid("FETCH", b",".join(uids).decode(), FETCH_ITEMS)
    messages = []
    for part in data:
        if not isinstance(part, tuple):
            continue
        meta, header_bytes = part
        meta_text = meta.decode("utf-8", "replace")

        def grab(pattern: str) -> str | None:
            m = re.search(pattern, meta_text)
            return m.group(1) if m else None

        headers = email.message_from_bytes(header_bytes, policy=email.policy.default)
        messages.append({
            "uid": grab(r"UID (\d+)"),
            "gmMsgId": grab(r"X-GM-MSGID (\d+)"),
            "gmThrId": grab(r"X-GM-THRID (\d+)"),
            "labels": grab(r"X-GM-LABELS \(([^)]*)\)"),
            "flags": grab(r"FLAGS \(([^)]*)\)"),
            "internalDate": grab(r'INTERNALDATE "([^"]+)"'),
            "from": str(headers.get("From", "")),
            "subject": str(headers.get("Subject", "")),
            "date": str(headers.get("Date", "")),
        })
    return messages


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--save", action="store_true", help=f"also write the results to {SAVE_PATH}")
    parser.add_argument("--address", help=f"the Gmail address (default {DEFAULT_ADDRESS}, or asked for)")
    args = parser.parse_args()

    from_env = os.environ.get("DECRASTINATION_GMAIL_APP_PASSWORD")
    address = args.address or (DEFAULT_ADDRESS if from_env else input(f"Gmail address [{DEFAULT_ADDRESS}]: ").strip() or DEFAULT_ADDRESS)
    password = (from_env or getpass.getpass("App password (16 letters; spaces are fine): ")).replace(" ", "")

    imap = imaplib.IMAP4_SSL(HOST, 993, timeout=30)
    try:
        imap.login(address, password)
    except imaplib.IMAP4.error as e:
        print("Login failed:", e)
        sys.exit(1)
    del password
    print("Logged in.")

    capabilities = sorted(c.decode() if isinstance(c, bytes) else c for c in imap.capabilities)
    print("Capabilities:", " ".join(capabilities))
    print("Gmail extensions (X-GM-EXT-1):", "X-GM-EXT-1" in capabilities)

    typ, data = imap.list()
    folders = [parse_list_line(line) for line in data if isinstance(line, bytes)]
    print(f"\n{len(folders)} folder(s):")
    for f in folders:
        print("-", f.get("name", f.get("raw")), f.get("flags", ""))

    snooze_counts = {}
    for f in folders:
        name = f.get("name", "")
        if "snooze" in name.lower():
            snooze_counts[name] = examine_count(imap, name)
    print("\nSnooze-like folders:", snooze_counts or "none visible over IMAP")

    inbox_count = examine_count(imap, "INBOX")
    messages = fetch_inbox(imap)
    print(f"\nINBOX: {inbox_count} message(s)")
    for msg in messages:
        print(f"- {msg['internalDate']} | labels=({msg['labels']}) | {msg['from']} | {msg['subject']}")

    imap.logout()

    if args.save:
        SAVE_PATH.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "capturedAt": datetime.now(timezone.utc).isoformat(),
            "capabilities": capabilities,
            "folders": folders,
            "snoozeFolders": snooze_counts,
            "inboxCount": inbox_count,
            "inbox": messages,
        }
        SAVE_PATH.write_text(json.dumps(payload, indent=1, ensure_ascii=False), encoding="utf-8")
        print(f"\nSaved to {SAVE_PATH} (git-ignored).")


if __name__ == "__main__":
    main()
