#!/bin/bash
#
# Copies the read-only project into a writable workspace and runs the linter.
set -euo pipefail

# Copied only into an empty workspace, for the same reason the other images do it: a cell whose
# steps share a volume must not have a later step overwrite what an earlier one produced.
if [ -z "$(ls -A /work 2>/dev/null)" ]; then
  cp -a /input/. /work/
fi

if [ -n "${KITBASH_WORKING_DIRECTORY:-}" ]; then
  cd "/work/${KITBASH_WORKING_DIRECTORY}"
fi

exec "$@"
