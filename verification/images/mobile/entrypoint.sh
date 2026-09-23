#!/bin/bash
#
# Copies the read-only project into a writable workspace and runs the build.
#
# The same shape as the Node image's, and for the same reason: a full-stack project's mobile app
# lives under mobile/, a standalone one's is at the root, and the cell says which.
set -euo pipefail

# Copied only into an empty workspace, so a cell whose steps share a volume does not have this
# step overwrite what the previous one produced.
if [ -z "$(ls -A /work 2>/dev/null)" ]; then
  cp -a /input/. /work/
fi

if [ -n "${KITBASH_WORKING_DIRECTORY:-}" ]; then
  cd "/work/${KITBASH_WORKING_DIRECTORY}"
fi

exec "$@"
