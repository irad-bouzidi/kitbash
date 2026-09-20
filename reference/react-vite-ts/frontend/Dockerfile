# Multi-stage: Node, the dependency tree and the sources never reach the runtime image,
# which serves static files and nothing else.
FROM node:24-alpine AS build
WORKDIR /build

ENV PNPM_HOME=/pnpm
ENV PATH=$PNPM_HOME:$PATH
RUN corepack enable

# Copied first so a source-only change does not re-resolve dependencies.
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

FROM nginx:1.27-alpine AS runtime

# Non-root. nginx's own user exists in the image; what it lacks is write access to the
# places the default config wants, so those are handed over explicitly rather than by
# running the whole server as root.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

RUN touch /var/run/nginx.pid && \
    chown -R nginx:nginx /var/run/nginx.pid /var/cache/nginx /usr/share/nginx/html

USER nginx
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -q -O- http://localhost:8080/healthz | grep -q ok || exit 1
