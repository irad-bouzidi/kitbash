#!/usr/bin/env bash
#
# Produce a zip for one selection.
#
# §12 requires every cell to call the generator through `cli` rather than over HTTP. That is
# not a convenience: it keeps verification independent of the API, its auth and its
# persistence, so a red cell means the generator is broken rather than the deployment. It is
# also the cheapest check that the §6 module boundary holds — `cli` depends on core, catalog
# and render, and a Spring dependency there is a build failure.
#
#   usage: generate.sh <selection.json> <output.zip>
set -euo pipefail

selection="$1"
output="$2"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { printf '[generate] %s\n' "$*" >&2; }

log "building the cli"
(cd "$repo_root/server" && ./gradlew :cli:installDist --quiet)

kitbash="$repo_root/server/cli/build/install/kitbash/bin/kitbash"
[ -x "$kitbash" ] || { log "no cli was produced at $kitbash"; exit 1; }

log "generating $(basename "$output")"
# Non-zero exits carry the §14 envelope as JSON on stderr, so a job can report the code, the
# stage and the recipe rather than a line somebody has to grep.
"$kitbash" generate \
  --selection "$selection" \
  --out "$output" \
  --zip \
  --catalog "$repo_root/recipes"

log "$(wc -c <"$output") bytes"
