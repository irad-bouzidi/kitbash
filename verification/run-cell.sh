#!/usr/bin/env bash
#
# Run one verification cell.
#
#   usage: run-cell.sh <cell-id>          e.g. run-cell.sh full-stack
#
# This is what a red pipeline tells you to run, and it is deliberately thin: it hands
# the one cell to the same runner CI uses. An earlier version of this script owned its
# own `docker run` and built the project with a hardcoded `./gradlew build`, which was
# fine while every cell was a JVM cell and wrong the moment one was not — it would have
# run the frontend-only project in the JDK image. One runner, one set of §13 limits,
# one definition of what a step is.
set -euo pipefail

cell="${1:?usage: run-cell.sh <cell-id>   (ids: $(ls "$(dirname "${BASH_SOURCE[0]}")/cells" | sed 's/\.json$//' | tr '\n' ' '))}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repo_root/server"
exec ./gradlew --quiet --console=plain :verify:runMatrix -Pkitbash.cell="$cell"
