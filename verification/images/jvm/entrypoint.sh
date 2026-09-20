#!/bin/bash
#
# Copies the read-only project into a writable workspace and runs the build.
set -euo pipefail

cp -a /input/. /workspace/

# A cell names the directory its commands run in. For the JVM half that is always the
# project root today, and saying so explicitly keeps one cell shape covering both images.
if [ -n "${KITBASH_WORKING_DIRECTORY:-}" ]; then
  cd "/workspace/${KITBASH_WORKING_DIRECTORY}"
fi

# docker-java — which Testcontainers uses — defaults to Docker API v1.32, and
# daemons from Docker 29 onward refuse it outright ("Could not find a valid Docker
# environment"). The runner passes the daemon's own advertised version, which every
# daemon accepts by definition, so this works on old and new hosts alike.
#
# It goes in ~/.docker-java.properties rather than an environment variable because
# that file is the only one of the three sources docker-java reads that survives
# the fork into Gradle's test JVM.
if [ -n "${KITBASH_DOCKER_API_VERSION:-}" ]; then
  echo "api.version=${KITBASH_DOCKER_API_VERSION}" > "$HOME/.docker-java.properties"
fi

exec "$@"
