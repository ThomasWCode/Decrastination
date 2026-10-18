#!/bin/sh
# The privacy scan before a push or a commit (docs/development.md §The repository is public; D-79):
# the lines a diff adds, searched for email addresses, phone numbers and the words listed, one a
# line, in the git-ignored private/scan-words.txt (names, places and identifiers from your data,
# which this public script can't hold). Prints each hit with its file; exits 1 if there's one.
#
#   scripts/scan-public.sh            # what this branch adds beyond origin/main
#   scripts/scan-public.sh --cached   # what's staged
#   scripts/scan-public.sh <range>    # any range git diff takes
#
# Made-up addresses at example.com, example.org and example.net, and GitHub's no-reply ones, pass;
# so does Kotlin's label on this, super, return, break and continue (this@AppGraph.member).
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT" || exit 2
RANGE=${1:-origin/main...HEAD}
WORDS="$ROOT/private/scan-words.txt"
ADDED=$(mktemp)
# Each added line as file:text, the file from the diff's own headers.
git diff --unified=0 --no-color $RANGE | awk '
  /^\+\+\+ b\// { file = substr($0, 7); next }
  /^\+\+\+ / { file = ""; next }
  /^\+/ && file != "" { print file ":" substr($0, 2) }' > "$ADDED"
HITS=$(awk -v words="$WORDS" '
  BEGIN {
    while ((getline w < words) > 0) if (w != "" && w !~ /^#/) listed[tolower(w)] = 1
  }
  {
    file = $0; sub(/:.*/, "", file); text = substr($0, length(file) + 2)
    rest = text
    while (match(rest, /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z][A-Za-z]+/)) {
      a = substr(rest, RSTART, RLENGTH); rest = substr(rest, RSTART + RLENGTH)
      local = a; sub(/@.*/, "", local)
      if (local ~ /^(this|super|return|break|continue)$/) continue
      if (tolower(a) !~ /@(example\.(com|org|net)|users\.noreply\.github\.com)$/) print "  an email address: " file ": " a
    }
    rest = text
    # UK numbers, mobile and landline, with or without the country code and spaces.
    while (match(rest, /(\+44 ?|0)(7[0-9][0-9][0-9]|1[0-9][0-9][0-9]?[0-9]?|2[0-9])[ -]?[0-9][0-9][0-9][0-9]?[ -]?[0-9][0-9][0-9][0-9]?/)) {
      p = substr(rest, RSTART, RLENGTH); before = RSTART > 1 ? substr(rest, RSTART - 1, 1) : ""; rest = substr(rest, RSTART + RLENGTH)
      # Not the middle of a longer number (a timestamp, an id).
      if (before !~ /[0-9A-Za-z]/ && rest !~ /^[0-9]/) print "  a phone number: " file ": " p
    }
    low = tolower(text)
    for (w in listed) if (index(low, w) > 0) print "  \"" w "\": " file
  }' "$ADDED")
rm -f "$ADDED"
[ -f "$WORDS" ] || echo "  (no private/scan-words.txt: only addresses and numbers were looked for)"
if [ -n "$HITS" ]; then
  echo "$HITS"
  echo "$(echo "$HITS" | grep -c .) hit(s) in what $RANGE adds: take them out, or make them up"
  exit 1
fi
echo "Nothing personal found in what $RANGE adds."
