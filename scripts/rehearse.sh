#!/bin/sh
# The migration rehearsal (docs/plan_v4, Stage 0): a phone backup's stores read, and migrated, by the
# code as it is, before a release is installed. Prints only counts and invariants, never what the
# stores hold.
#
#   scripts/rehearse.sh <backup>
#
# <backup> is a folder of the git-ignored private/backup/ (by name, or a path) holding a data.tar, as
# private/tools/keep_before.sh and keep_data.sh make them: the app's files/ is unpacked into
# private/rehearsal/<name>/, git-ignored too, with the newest nightly backup (decrastination-*.json)
# copied beside it where the folder has one; then RehearsalTest reads it all and writes
# private/rehearsal/<name>/rehearsal.txt, which is printed.
set -e
ROOT=$(git rev-parse --show-toplevel)
[ -n "$1" ] || { echo "usage: scripts/rehearse.sh <backup: a folder of private/backup/ with a data.tar>"; exit 2; }
SRC="$1"
[ -d "$SRC" ] || SRC="$ROOT/private/backup/$1"
[ -f "$SRC/data.tar" ] || { echo "no data.tar in $SRC"; exit 2; }
NAME=$(basename "$SRC")
OUT="$ROOT/private/rehearsal/$NAME"
# Afresh each time: what's there is a copy of the backup, nothing else.
rm -rf "$OUT"
mkdir -p "$OUT"
# Git Bash's tar takes C:/... as a host name without --force-local.
if tar --version 2>/dev/null | grep -q GNU; then LOCAL=--force-local; else LOCAL=; fi
tar $LOCAL -xf "$SRC/data.tar" -C "$OUT" files
NIGHTLY=$(ls "$SRC"/decrastination-*.json 2>/dev/null | sort | tail -1)
[ -n "$NIGHTLY" ] && cp "$NIGHTLY" "$OUT/"
# The JVM wants C:/... on Windows.
if command -v cygpath >/dev/null 2>&1; then DIR=$(cygpath -m "$OUT"); else DIR="$OUT"; fi
cd "$ROOT"
./gradlew testDebugUnitTest --tests '*RehearsalTest*' --rerun -PrehearseFrom="$DIR" --console=plain -q || STATUS=$?
cat "$OUT/rehearsal.txt" 2>/dev/null || echo "no rehearsal.txt: the test didn't get as far as writing it"
exit ${STATUS:-0}
