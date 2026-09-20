# The Node ecosystem image: a Node runtime, pnpm, a warm store, nothing else.
#
# Generated builds run in here and never on the API host (§12). Built from the
# repository root so the warm-up can use the reference project — whose dependency
# set is, by construction, the dependency set of everything the frontend recipe
# generates.
FROM node:24-bookworm-slim

# unzip to unpack the generated archive, git because a generated project arrives as
# a repository and a build may read it. Nothing else: an ecosystem image that
# accumulates tools stops resembling a user's machine, which is what it exists to be.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV PNPM_HOME=/opt/pnpm
ENV PNPM_STORE_DIR=/opt/pnpm-store
ENV PATH=$PNPM_HOME:$PATH

# Corepack caches the pnpm version a project pins in `packageManager`, and it caches it
# under $COREPACK_HOME — which defaults to the *current user's* home. Warming as root and
# then running the cell as `builder` leaves that cache unreadable, so every cell
# re-downloaded pnpm before it could install anything. A shared location, handed over
# below, is the fix.
ENV COREPACK_HOME=/opt/corepack
# And never prompt: a cell has no terminal to answer on.
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable

# Warm the store from the reference project's lockfile, then throw the project away
# and keep the store. `pnpm fetch` is the right verb: it populates the store from the
# lockfile without needing the sources, so the image carries the packages without
# carrying a node_modules tree that would be stale the moment a recipe changed.
#
# A warm store is a speed optimisation, not a correctness one. Installs still resolve
# against the registry, so a package that disappears upstream still breaks the cell —
# which is the point. An offline cache would quietly hide that.
COPY reference/react-vite-ts/frontend/package.json reference/react-vite-ts/frontend/pnpm-lock.yaml /warmup/
RUN cd /warmup \
    && pnpm config set store-dir "$PNPM_STORE_DIR" \
    && pnpm fetch \
    && rm -rf /warmup

# The generated project is mounted read-only and copied in, so a build cannot write
# to the runner's filesystem and the container's uid need not match the host's.
# PNPM_HOME is created lazily by corepack on first use, so it has to be made here
# before it can be handed over — otherwise the chown fails on a directory that does
# not exist yet, and the image never builds.
RUN useradd --create-home --shell /usr/sbin/nologin builder \
    && mkdir -p /work "$PNPM_STORE_DIR" "$PNPM_HOME" "$COREPACK_HOME" \
    && chown -R builder:builder /work "$PNPM_STORE_DIR" "$PNPM_HOME" "$COREPACK_HOME"

COPY verification/images/node/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER builder
WORKDIR /work
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
