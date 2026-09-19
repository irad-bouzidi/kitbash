#!/usr/bin/env bash
#
# Produce a zip for one selection. THIS IS THE ONE REPLACEABLE STEP.
#
# kitbash-18 swaps the body of this script for a `cli` invocation and nothing else
# about the verification job changes. Keeping it behind a script with a fixed
# contract — selection in, zip out — is what makes that a one-file change.
#
#   usage: generate.sh <selection.json> <output.zip>
set -euo pipefail

selection="$1"
output="$2"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { printf '[generate] %s\n' "$*" >&2; }

# Phase 0 has no CLI, so the server is booted and asked over HTTP. §8's generation
# endpoint is synchronous, so a single curl is the whole interaction.
log "building the api jar"
(cd "$repo_root/server" && ./gradlew :api:bootJar --quiet)

jar="$(find "$repo_root/server/api/build/libs" -name 'api-*.jar' ! -name '*-plain.jar' | head -1)"
[ -n "$jar" ] || { log "no api jar was produced"; exit 1; }

log "starting the api"
java -jar "$jar" --server.port=18080 >"$repo_root/verification/.generate-server.log" 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  if curl -sf -o /dev/null "http://localhost:18080/actuator/health"; then break; fi
  sleep 1
done
curl -sf -o /dev/null "http://localhost:18080/actuator/health" || {
  log "the api did not become healthy"
  tail -40 "$repo_root/verification/.generate-server.log" >&2
  exit 1
}

log "generating $(basename "$output")"
curl -sf -X POST "http://localhost:18080/api/v1/generate" \
  -H 'Content-Type: application/json' \
  --data-binary "@$selection" \
  -o "$output"

log "$(wc -c <"$output") bytes"
