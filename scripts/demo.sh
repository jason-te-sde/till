#!/usr/bin/env bash
# Brings up the stack and walks through the interesting cases, so that "it works" is something you
# watch rather than something you are told.
#
#   scripts/demo.sh
#
# Needs Docker. Leaves the stack running; `docker compose down -v` when you are done.
set -euo pipefail

cd "$(dirname "$0")/.."

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
run() { printf '$ tillctl %s\n' "$*"; docker compose run --rm tillctl "$@"; }

say "bringing up postgres and till"
docker compose up -d --wait

say "stock in"
run adjust widget 100
run stock widget

say "a hold: on-hand does not move, available does"
HELD="$(docker compose run --rm tillctl reserve widget:2 --key=checkout-8123 | awk '{print $1}' | tr -d '\r')"
echo "$ tillctl reserve widget:2 --key=checkout-8123   ->  $HELD"
run stock widget

say "the same key again, from a client that never saw the answer"
run reserve widget:2 --key=checkout-8123
run stock widget
echo "# the same reservation, and two units held rather than four"

say "committing: the goods leave, and the hold leaves with them"
run commit "$HELD"
run stock widget

say "asking for more than there is: every short line is reported"
run reserve widget:500 || true

say "a hold that runs out of time"
STALE="$(docker compose run --rm tillctl reserve widget:1 --ttl=2 --key=abandoned | awk '{print $1}' | tr -d '\r')"
echo "$ tillctl reserve widget:1 --ttl=2   ->  $STALE"
sleep 3
run get "$STALE"
echo "# EXPIRED, with the stored state still saying HELD: the deadline decides, not the row"
run commit "$STALE" || true

say "the stock came back, without a sweep having had to run"
run stock widget

say "metrics"
curl -sS http://127.0.0.1:9101/actuator/prometheus | grep -E '^till_(outcome|outbox)' | head -12

say "done. docker compose down -v to clean up"
