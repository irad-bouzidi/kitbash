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

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -q -O- http://localhost/ >/dev/null || exit 1
