# The mobile ecosystem image: Node, npm, a warm cache, and nothing native.
#
# §45 asks for this and asks for honesty about what it can prove. There is no Android SDK and no
# Xcode here, on purpose: a cell in this image bundles the JavaScript and runs the component tests.
# It cannot tell you the app launches on a device, and a badge claiming otherwise would be a lie
# the matrix cannot back.
#
# What that boundary buys is a cell that finishes in under a minute on an ordinary runner. An
# emulator-backed cell would need a KVM-capable host, several gigabytes and minutes per run, for a
# signal the nightly cannot act on anyway — nobody fixes a layout bug from a matrix.
FROM node:24-bookworm-slim

# unzip to unpack the generated archive, git because a generated project arrives as a repository.
# Nothing else: an ecosystem image that accumulates tools stops resembling a user's machine.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# npm rather than pnpm, unlike the Node image.
#
# React Native resolves native modules by walking up from a package's real location, and pnpm's
# symlinked store breaks that walk for a whole class of libraries. Expo's own tooling assumes npm
# or yarn, and a generated project that needs `node-linker=hoisted` to work is a project whose
# first `npm install` — the command every Expo document tells you to run — does something
# different from what CI did.
ENV NPM_CONFIG_CACHE=/opt/npm-cache
ENV NPM_CONFIG_FUND=false
ENV NPM_CONFIG_AUDIT=false
# Expo phones home for update checks and telemetry. A cell has nobody to tell.
ENV EXPO_NO_TELEMETRY=1
ENV CI=1

# Warm the cache from the reference project's lockfile, then keep only the cache.
#
# `npm ci --ignore-scripts` into a throwaway tree rather than `npm install`: it populates the cache
# from the lockfile exactly, and the tree it builds is discarded because a node_modules baked into
# an image is stale the moment a recipe changes a version.
#
# Warm, not offline. Installs still resolve against the registry, so a package that disappears
# upstream still breaks the cell — which is the point (§12).
COPY reference/react-native-expo/mobile/package.json reference/react-native-expo/mobile/package-lock.json /warmup/
RUN cd /warmup \
    && npm ci --ignore-scripts \
    && rm -rf /warmup

RUN useradd --create-home --shell /usr/sbin/nologin builder \
    && mkdir -p /work "$NPM_CONFIG_CACHE" \
    && chown -R builder:builder /work "$NPM_CONFIG_CACHE"

COPY verification/images/mobile/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER builder
WORKDIR /work
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
