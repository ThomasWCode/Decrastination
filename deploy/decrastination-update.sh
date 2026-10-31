#!/usr/bin/env bash
# Brings the box to the images GitHub Actions last pushed. `up -d` recreates only what's changed, and
# restarts what's stopped, so a run with nothing new does nothing. Run by decrastination-update.timer.
set -euo pipefail
umask 077
cd "${DECRASTINATION_DIR:-/opt/decrastination}"

# One at a time: a slow pull outlasts the timer.
exec 9>.update.lock
flock -n 9 || exit 0

# Held by hand: a rollback, or a restore in hand.
if [ -f .hold ]; then exit 0; fi

timeout 300 docker compose pull --quiet
timeout 180 docker compose up -d --remove-orphans --wait --wait-timeout 120
docker image prune --force --filter "until=72h" >/dev/null
