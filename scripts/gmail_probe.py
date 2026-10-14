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

With --starred, instead of the inbox it reads what the app reads since review v2 (DX-03): All
Mail (found by its \\All special-use attribute), the starred messages in it (SEARCH FLAGGED), and
each of their whole conversations (X-GM-THRID), unstarred messages and your replies included,
drafts left out; one block per conversation, with how many of its messages are starred. It also
counts the Starred folder (\\Flagged), which should hold the same starred messages.

With --save, the same goes to private/gmail_probe.json (git-ignored), so the results can be read
back without pasting them. The password is never written anywhere.

The app password is read from DECRASTINATION_GMAIL_APP_PASSWORD when set, and asked for
otherwise; --address picks the account (default: the one below).

Run:  python scripts/gmail_probe.py [--save] [--starred] [--address you@gmail.com]
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


class ProbeFailed(Exception):
    """A command the probe depends on was refused: nothing it would report can be trusted."""


def check(typ: str, data, what: str) -> None:
    if typ != "OK":
        raise ProbeFailed(f"{what} returned {typ}: {data!r:.300}")


def examine_count(imap: imaplib.IMAP4_SSL, folder: str) -> int:
    typ, data = imap.select(f'"{folder}"', readonly=True)  # EXAMINE
    check(typ, data, f"EXAMINE {folder}")
    return int(data[0])


def fetch_inbox(imap: imaplib.IMAP4_SSL) -> list[dict]:
    """Every message in the folder last examined. A refused SEARCH or FETCH, or a FETCH that
    returns fewer messages than SEARCH found, fails the probe rather than passing for an
    emptier inbox."""
    return fetch_messages(imap, search(imap, "ALL"))


def search(imap: imaplib.IMAP4_SSL, *criteria: str) -> list[bytes]:
    """The UIDs matching criteria in the folder last examined."""
    typ, data = imap.uid("SEARCH", None, *criteria)
    check(typ, data, "UID SEARCH " + " ".join(criteria)[:80])
    return data[0].split() if data and data[0] else []


def any_of(keys: list[str]) -> list[str]:
    """IMAP's OR takes two keys, so several are nested: OR a OR b c."""
    if len(keys) == 1:
        return keys[0].split()
    return ["OR", *keys[0].split(), *any_of(keys[1:])]


def starred_conversations(imap: imaplib.IMAP4_SSL, folders: list[dict]) -> dict:
    """The starred messages' whole conversations, as the app reads them since review v2 (DX-03)."""
    def special(attribute: str) -> str | None:
        return next((f["name"] for f in folders if attribute in [a.lower() for a in f.get("flags", [])]), None)

    all_mail, starred_folder = special("\\all"), special("\\flagged")
    if all_mail is None:
        raise ProbeFailed("no folder has the \\All attribute")
    starred_count = examine_count(imap, starred_folder) if starred_folder else None
    typ, data = imap.select(f'"{all_mail}"', readonly=True)  # EXAMINE
    check(typ, data, f"EXAMINE {all_mail}")
    validity = imap.response("UIDVALIDITY")[1]
    starred = search(imap, "FLAGGED")
    threads = sorted({m["gmThrId"] for m in fetch_messages(imap, starred)})
    uids = []
    for batch in [threads[i:i + 40] for i in range(0, len(threads), 40)]:
        uids += search(imap, "NOT", "DRAFT", *any_of([f"X-GM-THRID {t}" for t in batch]))
    messages = fetch_messages(imap, sorted(set(uids), key=int))
    conversations = {}
    for m in messages:
        conversations.setdefault(m["gmThrId"], []).append(m)
    return {
        "allMail": all_mail,
        "uidValidity": validity[0].decode() if validity and validity[0] else None,
        "starredFolder": starred_folder,
        "starredFolderCount": starred_count,
        "starredInAllMail": len(starred),
        "conversations": conversations,
    }


def fetch_messages(imap: imaplib.IMAP4_SSL, uids: list[bytes]) -> list[dict]:
    """The messages with these UIDs in the folder last examined, each once."""
    if not uids:
        return []
    typ, data = imap.uid("FETCH", b",".join(uids).decode(), FETCH_ITEMS)
    check(typ, data, "UID FETCH")
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
    if len(messages) != len(uids):
        raise ProbeFailed(f"UID FETCH returned {len(messages)} of the {len(uids)} messages SEARCH found")
    return messages


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--save", action="store_true", help=f"also write the results to {SAVE_PATH}")
    parser.add_argument("--address", help=f"the Gmail address (default {DEFAULT_ADDRESS}, or asked for)")
    parser.add_argument("--starred", action="store_true", help="the starred messages' whole conversations from All Mail, as the app reads them, instead of the inbox")
    args = parser.parse_args()
    # Subjects carry emoji, which a Windows console's code page can't print.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

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

    try:
        typ, data = imap.list()
        check(typ, data, "LIST")
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

        if args.starred:
            starred = starred_conversations(imap, folders)
        else:
            inbox_count = examine_count(imap, "INBOX")
            messages = fetch_inbox(imap)
    except ProbeFailed as e:
        print("\nNot proven:", e)
        imap.logout()
        sys.exit(2)
    imap.logout()

    if args.starred:
        conversations = starred["conversations"]
        print(f"\n{starred['allMail']} (UIDVALIDITY {starred['uidValidity']}): {starred['starredInAllMail']} starred message(s)"
              f" in {len(conversations)} conversation(s); {starred['starredFolder']} holds {starred['starredFolderCount']}")
        for thread, msgs in conversations.items():
            flagged = sum("\\Flagged" in (m["flags"] or "") for m in msgs)
            print(f"\n- conversation {thread}: {len(msgs)} message(s), {flagged} starred")
            for m in msgs:
                print(f"  - {m['internalDate']} | uid {m['uid']} | flags=({m['flags']}) | {m['from']} | {m['subject']}")
        if args.save:
            path = SAVE_PATH.with_name("gmail_probe_starred.json")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"capturedAt": datetime.now(timezone.utc).isoformat(), **starred}, indent=1, ensure_ascii=False), encoding="utf-8")
            print(f"\nSaved to {path} (git-ignored).")
        return

    print(f"\nINBOX: {inbox_count} message(s)")
    for msg in messages:
        print(f"- {msg['internalDate']} | labels=({msg['labels']}) | {msg['from']} | {msg['subject']}")

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
