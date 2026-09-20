#!/bin/bash
#
# Copies the read-only project into a writable workspace and runs the build.
#
# The working directory matters here in a way it does not for the JVM image: a
# full-stack project's frontend lives under frontend/, and a standalone one's does
# too, so the cell says where to run and this obeys it.
set -euo pipefail

cp -a /input/. /work/

if [ -n "${KITBASH_WORKING_DIRECTORY:-}" ]; then
  cd "/work/${KITBASH_WORKING_DIRECTORY}"
fi

# Installs resolve against the registry; the store is warm, not offline. A package
# that disappears upstream still breaks the cell, which is the point (§12).
pnpm config set store-dir "$PNPM_STORE_DIR"

exec "$@"
