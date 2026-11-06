#!/usr/bin/env sh
# Poll a URL until it answers 2xx (and, optionally, its body contains a string). CI helper.
#   sh scripts/ci/wait_for_url.sh <url> [timeout-seconds=180] [must-contain]
set -eu
url="$1"
timeout="${2:-180}"
needle="${3:-}"
end=$(( $(date +%s) + timeout ))
while :; do
  if body=$(curl -fsS --max-time 5 "$url" 2>/dev/null); then
    if [ -z "$needle" ] || printf '%s' "$body" | grep -q "$needle"; then
      echo "up: $url"
      exit 0
    fi
  fi
  if [ "$(date +%s)" -ge "$end" ]; then
    echo "timed out after ${timeout}s waiting for $url" >&2
    exit 1
  fi
  sleep 3
done
