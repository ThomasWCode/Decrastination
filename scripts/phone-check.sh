#!/bin/sh
# The phone check after every install (docs/plan_v4/12-verification-and-risks.md §The phone check),
# read-only: nothing on the phone is changed, nothing is tapped, and nothing it prints is a task's,
# an event's or a person's words.
#
#   scripts/phone-check.sh
#
# The version and when it was installed; the accessibility services enabled and bound, the focus
# service among them; the calendar written to (the app's slots ahead, the longest of them, and
# stray copies in its words); crashes since the install; what's in front; the stores' sizes beside
# the last run's (kept in the git-ignored private/phone-check-last.txt). adb's one device, or
# ANDROID_SERIAL's. Exits 1 if the focus service isn't enabled and bound, the app crashed since its
# install, or the crash log, the calendar or the stores couldn't be read: a check that didn't happen
# isn't a clean one.
export MSYS_NO_PATHCONV=1
PKG=com.thomaswcode.decrastination
ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
FAILED=0
adb get-state >/dev/null 2>&1 || { echo "No phone: adb sees no device"; exit 2; }

echo "== Version"
adb shell dumpsys package $PKG | grep -m2 -E "versionName|lastUpdateTime" | tr -d '\r' | sed 's/^ */  /'
INSTALLED=$(adb shell dumpsys package $PKG | grep -m1 lastUpdateTime | tr -d '\r' | sed 's/.*lastUpdateTime=//')

echo "== Accessibility"
# Each as package/package.Class: either list may name one as package/.Class.
full() { sed -E 's#^([^/]+)/\.#\1/\1.#'; }
ENABLED=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r' | tr ':' '\n' | grep . | full)
# The bound list runs over several lines, from "Bound services:" to "Enabled services:", each entry
# Service[label=..., id=package/.Class, ...] (Android 16's dump).
BOUNDLIST=$(adb shell dumpsys accessibility | tr -d '\r' | awk '/Bound services:/ { p = 1 } p && /Enabled services:/ { exit } p')
BOUND=$(echo "$BOUNDLIST" | grep -o "id=[^,]*" | sed 's/^id=//' | full)
echo "  enabled $(echo "$ENABLED" | grep -c .), bound $(echo "$BOUND" | grep -c .)"
echo "$ENABLED" | grep -qx "$PKG/$PKG.block.FocusService" && echo "  the focus service is enabled" || { echo "  THE FOCUS SERVICE ISN'T ENABLED"; FAILED=1; }
if [ -z "$BOUND" ] && echo "$BOUNDLIST" | grep -q "Service\["; then
  # Services bound, but a dump that doesn't name them: said as such, not as each unbound.
  echo "  CAN'T READ WHICH SERVICES ARE BOUND: this build's dump doesn't name them"
  FAILED=1
else
  for s in $ENABLED; do
    if echo "$BOUND" | grep -qx "$s"; then echo "  bound    $s"; else echo "  UNBOUND  $s"; fi
  done
  echo "$BOUND" | grep -qx "$PKG/$PKG.block.FocusService" && echo "  the focus service is bound" || { echo "  THE FOCUS SERVICE ISN'T BOUND"; FAILED=1; }
fi

echo "== Calendar"
# exec-out passes run-as's error on as if it were the file: settings that aren't JSON weren't read.
SETTINGS=$(adb exec-out run-as $PKG cat files/settings.json 2>/dev/null)
CAL=$(echo "$SETTINGS" | grep -o '"calendarWriteId":[0-9]*' | cut -d: -f2)
case "$SETTINGS" in "{"*) READ=1 ;; *) READ=0 ;; esac
# The query's rows, read only once it's known to have run: the content command prints a provider's
# error to stderr alone and still exits 0, and it prints "No result found." when there are no rows, so
# anything on stderr, or nothing at all, is a query that failed.
QERR=$(mktemp)
NOW=$(($(date +%s) * 1000))
if [ "$READ" = 0 ]; then
  echo "  CAN'T READ THE SETTINGS: the calendar wasn't checked"
  FAILED=1
elif [ -z "$CAL" ]; then
  echo "  no calendar written to"
elif ! ROWS=$(adb shell content query --uri content://com.android.calendar/events --projection dtstart:dtend:customAppPackage:description --where "\"calendar_id=$CAL AND deleted=0 AND dtend>$NOW\"" 2>"$QERR") || [ -s "$QERR" ] || [ -z "$ROWS" ]; then
  echo "  CAN'T READ THE CALENDAR (its query failed): the slots weren't checked"
  FAILED=1
else
  echo "$ROWS" | tr -d '\r' | awk -v pkg="$PKG" '
    /^Row:/ {
      start = $0; sub(/.*dtstart=/, "", start); sub(/,.*/, "", start)
      end = $0; sub(/.*dtend=/, "", end); sub(/,.*/, "", end)
      app = ($0 ~ ("customAppPackage=" pkg ","))
      desc = $0; sub(/.*description=/, "", desc)
      mins = (end - start) / 60000
      if (app) { ours++; if (mins > longest) longest = mins; if (mins > 60) over++ }
      else if (desc ~ /^(Teams|Power Planner|Anki|Gmail|Yours)\. / || desc == "A break outside.") copies++
      else others++
    }
    END {
      printf "  the app'"'"'s slots ahead %d, the longest %d min (%d over an hour: fine only for steps you or Felix gave, D-34)\n", ours, longest, over
      printf "  copies in its words without its package %d; other events %d\n", copies, others
    }'
fi
rm -f "$QERR"

echo "== Crashes since the install ($INSTALLED)"
# The crash buffer from the install on: one an earlier build left there isn't this build's. A crash
# log that can't be read is a check that didn't happen, not a clean one.
if [ -n "$INSTALLED" ]; then
  CRASHLOG=$(adb logcat -d -b crash -t "$INSTALLED.000" 2>&1) || { echo "  CAN'T READ THE CRASH LOG (logcat failed)"; FAILED=1; }
else
  CRASHLOG=$(adb logcat -d -b crash 2>&1) || { echo "  CAN'T READ THE CRASH LOG (logcat failed)"; FAILED=1; }
fi
CRASHES=$(echo "$CRASHLOG" | grep -A1 "FATAL EXCEPTION" | grep -c "Process: $PKG")
echo "  in the crash log: $CRASHES"
adb exec-out run-as $PKG sh -c 'ls -l files/crashes 2>/dev/null' | tr -d '\r' | awk 'NR > 1 { print "  kept by the app: " $6 " " $7 " " $8 }'
[ "$CRASHES" = "0" ] || FAILED=1

echo "== In front"
adb shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r' | sed 's/.* u0 /  /; s/}$//'

echo "== Stores"
LAST="$ROOT/private/phone-check-last.txt"
# ls's lines for the stores alone: run-as's or ls's error, passed on by exec-out as output, isn't one.
SIZES=$(adb exec-out run-as $PKG sh -c 'ls -l files/*.json' | tr -d '\r' | awk '$NF ~ /\.json$/ && $5 ~ /^[0-9]+$/ { n = $NF; sub(/.*\//, "", n); print n, $5 }')
if [ -z "$SIZES" ]; then
  # The last run's sizes are kept, for the next run to compare with.
  echo "  CAN'T LIST THE STORES: their sizes weren't checked"
  FAILED=1
else
  echo "$SIZES" | while read -r name size; do
    was=$( [ -f "$LAST" ] && grep "^$name " "$LAST" | cut -d' ' -f2 )
    if [ -n "$was" ]; then echo "  $name $size bytes (was $was)"; else echo "  $name $size bytes"; fi
  done
  [ -d "$ROOT/private" ] && echo "$SIZES" > "$LAST"
fi

[ "$FAILED" = "0" ] && echo "== All well" || echo "== SOMETHING'S WRONG: see above"
exit $FAILED
