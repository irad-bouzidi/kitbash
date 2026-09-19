#!/usr/bin/env bash
#
# Run one verification cell: generate a project, unpack it, and build it inside the
# ecosystem container.
#
#   usage: run-cell.sh <selection.json>
#
# The rule that holds from this, the very first cell: a generated build never runs
# on the API host (§12). Everything after the unzip happens inside the container.
set -euo pipefail

selection="${1:?usage: run-cell.sh <selection.json>}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cell="$(basename "$selection" .json)"

# §13 limits. A generated build is untrusted input the moment recipes are
# contributed, and a cell that can eat the runner is a cell that takes the matrix
# with it when one recipe misbehaves.
IMAGE="${KITBASH_JVM_IMAGE:-kitbash/verify-jvm:latest}"
CPUS="${KITBASH_CELL_CPUS:-2}"
MEMORY="${KITBASH_CELL_MEMORY:-4g}"
TIMEOUT_SECONDS="${KITBASH_CELL_TIMEOUT:-900}"
BUDGET_SECONDS="${KITBASH_CELL_BUDGET:-600}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

reproduce="./verification/run-cell.sh $(realpath --relative-to="$repo_root" "$selection")"

fail() {
  echo
  echo "================================================================"
  echo " CELL FAILED: $cell"
  echo "================================================================"
  echo
  echo "The exact selection this cell generated from:"
  echo
  sed 's/^/    /' "$selection"
  echo
  echo "Reproduce it locally with one command, from the repository root:"
  echo
  echo "    $reproduce"
  echo
  echo "================================================================"
  exit 1
}

echo "[cell:$cell] generating"
"$repo_root/verification/generate.sh" "$selection" "$work/project.zip" || fail

echo "[cell:$cell] unpacking"
unzip -q "$work/project.zip" -d "$work/unpacked" || fail
project="$(find "$work/unpacked" -mindepth 1 -maxdepth 1 -type d | head -1)"
[ -n "$project" ] || fail

# Handed to the container so Testcontainers negotiates the version this daemon
# actually serves rather than docker-java's 1.32 default, which Docker 29 refuses.
docker_api_version="$(docker version --format '{{.Server.APIVersion}}' 2>/dev/null || true)"

echo "[cell:$cell] building in $IMAGE (cpus=$CPUS memory=$MEMORY timeout=${TIMEOUT_SECONDS}s docker-api=${docker_api_version:-default})"
started=$(date +%s)

# Testcontainers reaches the host daemon through the mounted socket and talks to
# the containers it starts through the host gateway. The alternative — no Docker at
# all — would mean skipping the integration tests, and a cell that does not run the
# generated project's own tests is not verifying very much.
#
# The project is mounted read-only and copied inside by the image's entrypoint, so
# a generated build cannot write to the runner's filesystem and the container's uid
# does not have to match the host's.
#
# The network is a dedicated bridge rather than the host's: see the gap noted in
# README.md about the dependency proxy this should eventually resolve through.
set +e
timeout --signal=KILL "$TIMEOUT_SECONDS" \
  docker run --rm \
    --cpus="$CPUS" \
    --memory="$MEMORY" \
    --memory-swap="$MEMORY" \
    --pids-limit=2048 \
    --security-opt=no-new-privileges \
    --add-host=host.docker.internal:host-gateway \
    -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    -e KITBASH_DOCKER_API_VERSION="$docker_api_version" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$project:/input:ro" \
    "$IMAGE" \
    ./gradlew build --no-daemon
status=$?
set -e

elapsed=$(( $(date +%s) - started ))
echo "[cell:$cell] build finished in ${elapsed}s (status $status)"

if [ "$status" -ne 0 ]; then
  if [ "$status" -eq 137 ]; then
    echo "[cell:$cell] killed after the ${TIMEOUT_SECONDS}s hard timeout"
  fi
  fail
fi

# Logged every run, not only when it is breached, so the trend is visible before it
# becomes a problem.
echo "[cell:$cell] wall clock ${elapsed}s against a ${BUDGET_SECONDS}s budget"
if [ "$elapsed" -gt "$BUDGET_SECONDS" ]; then
  echo "[cell:$cell] OVER BUDGET: ${elapsed}s > ${BUDGET_SECONDS}s"
  echo "[cell:$cell] the matrix multiplies this number; fix it here or it scales in kitbash-35"
  exit 1
fi

echo "[cell:$cell] PASSED"
