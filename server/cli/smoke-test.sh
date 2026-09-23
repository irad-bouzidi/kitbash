#!/usr/bin/env bash
#
# §42's exit criterion, as a script: a developer with no JDK installs the published binary,
# generates a project offline, and builds it.
#
#   usage: server/cli/smoke-test.sh [path/to/kitbash-<version>.tgz]
#
# Two containers, because one would prove the wrong thing.
#
#   1. A bare base image with **no JDK and no network**. If the distribution is self-contained,
#      this is where that is true or false. Running it on a developer machine proves nothing:
#      every developer machine has a JDK, and it would pass with a launcher that called `java`.
#
#   2. The JVM verification image, to build what came out. "Generates a project" is half the
#      claim; §12's whole argument is that only a real build proves the output.
#
# The two share a Docker volume rather than a host bind mount. A bind mount on some hosts does not
# carry the executable bit, and the first draft of this test reported a `gradlew` that was 0644 —
# which was the mount, not the generator. A volume is the same filesystem both containers see.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

archive="${1:-}"
if [ -z "$archive" ]; then
  archive="$(ls -1 "$repo_root"/server/cli/build/distributions/kitbash-*.tgz 2>/dev/null | head -1 || true)"
fi
[ -n "$archive" ] && [ -f "$archive" ] || {
  echo "No distribution archive. Build one with: (cd server && ./gradlew :cli:distributionArchive)" >&2
  exit 2
}

base_image="${KITBASH_SMOKE_BASE:-ubuntu:24.04}"
jvm_image="${KITBASH_JVM_IMAGE:-kitbash/verify-jvm:latest}"

workdir="$(mktemp -d)"
volume="kitbash-smoke-$$"
cleanup() {
  rm -rf "$workdir"
  docker volume rm "$volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> unpacking $(basename "$archive")"
tar -xzf "$archive" -C "$workdir"
installed="$workdir/kitbash"
[ -x "$installed/bin/kitbash" ] || { echo "The archive has no executable bin/kitbash" >&2; exit 1; }

cat > "$workdir/selection.json" <<'JSON'
{"schemaVersion":1,"projectName":"smoke",
 "options":{"backend":"backend-spring-java","buildTool":"build-gradle-kts","database":"db-postgres-flyway"},
 "variables":{"groupId":"com.example","packageName":"com.example.smoke","javaVersion":"21",
              "entityName":"Widget","entityTable":"widgets","envPrefix":"SMOKE"}}
JSON

docker volume create "$volume" >/dev/null

echo "==> generating in $base_image, with no JDK and no network"
docker run --rm --network none \
  -v "$installed:/opt/kitbash:ro" \
  -v "$workdir/selection.json:/selection.json:ro" \
  -v "$volume:/out" \
  "$base_image" sh -euc '
    if command -v java >/dev/null 2>&1; then
      echo "This base image has a JDK, so it cannot prove the distribution is self-contained." >&2
      exit 1
    fi
    /opt/kitbash/bin/kitbash --version
    /opt/kitbash/bin/kitbash generate --selection /selection.json --out /out
  '

echo "==> building what it produced, in $jvm_image"
docker run --rm --entrypoint sh \
  -v "$volume:/input:ro" \
  "$jvm_image" -euc '
    cp -a /input/. /workspace/
    cd /workspace
    ./gradlew build -x test --no-daemon -q
  '

echo "==> smoke test passed: no JDK, no network, generated and built"
