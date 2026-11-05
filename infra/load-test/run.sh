#!/bin/bash
# Runs the k6 load test in a container (nothing to install except Docker).
#   python infra/load-test/prepare-users.py          # once, creates users (tokens last 15 minutes!)
#   bash infra/load-test/run.sh baseline             # 1 pair: how fast is an idle system
#   bash infra/load-test/run.sh load                 # default: 20 pairs + 20 browsing users
#   PAIRS=30 BROWSERS=40 bash infra/load-test/run.sh load
#   bash infra/load-test/run.sh stress               # twice the users
#   SEND_MS=300 bash infra/load-test/run.sh stress   # and a message every 0.3-0.6 s per sender
# Every virtual user needs its own prepared pair: (PAIRS + BROWSERS) x 2 for stress. The script tells you if too few.
# The summary is printed and also written to infra/load-test/results/<profile>-<time>.json
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")"

PROFILE="${1:-load}"
BASE_URL="${BASE_URL:-http://host.docker.internal:8200}"
mkdir -p results
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

[ -f data/users.json ] || { echo "Run  python infra/load-test/prepare-users.py  first."; exit 1; }
AGE=$(( $(date +%s) - $(python -c "import json;print(json.load(open('data/users.json'))['createdAt'])") ))
if [ "$AGE" -gt 780 ]; then
  echo "The access tokens in data/users.json are $((AGE / 60)) minutes old (they last 15). Run prepare-users.py again."; exit 1
fi

docker run --rm -i \
  -v "$(pwd -W 2>/dev/null || pwd)/data:/data:ro" \
  -v "$(pwd -W 2>/dev/null || pwd):/scripts:ro" \
  -v "$(pwd -W 2>/dev/null || pwd)/results:/results" \
  -e BASE_URL="$BASE_URL" -e PROFILE="$PROFILE" -e PAIRS="${PAIRS:-20}" -e BROWSERS="${BROWSERS:-20}" -e SEND_MS="${SEND_MS:-2000}" \
  grafana/k6:0.58.0 run --summary-export="/results/$PROFILE-$STAMP.json" /scripts/chat-load.js
