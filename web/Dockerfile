FROM node:24-alpine AS build
WORKDIR /build

RUN corepack enable

# Copied first so a source change does not re-resolve the dependency tree.
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY web/ ./

# Vite resolves `import.meta.env` at build time, so where the identity provider lives is baked in
# here rather than handed to the container at startup. A build for a different environment is a
# different build — which is the honest description of a static bundle.
ARG VITE_OIDC_ISSUER
ARG VITE_OIDC_CLIENT_ID
RUN pnpm build

FROM nginx:1.29-alpine AS runtime

COPY web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

# 127.0.0.1 rather than localhost, and the difference is the whole healthcheck.
#
# The image's /etc/hosts maps `localhost` to both 127.0.0.1 and ::1, wget tries the IPv6 address
# first, and nginx listens on `80` — IPv4 only. So this probe answered "connection refused" on
# every run since kitbash-4 and the container has never once reported healthy: `docker compose ps`
# has shown `unhealthy` for the whole life of this file, and anything that waited on
# `service_healthy` would have waited forever.
#
# Naming the family is the fix rather than adding `listen [::]:80`, because what this asks is "is
# the server I just started answering", and that question should not depend on a resolver's
# preference order.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -q -O- http://127.0.0.1/ >/dev/null || exit 1
