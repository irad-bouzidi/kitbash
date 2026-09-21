# The pipeline-linting image: two linters and nothing else.
#
# §34's whole point is that a CI recipe fails silently. A rendered workflow is valid YAML, gets
# committed, and nobody notices it never ran — so "it renders" is not evidence. These cells run
# each provider's own linter over the emitted file.
#
# A third ecosystem image rather than adding linters to the JVM or Node ones: those exist to look
# like a user's machine, and a user's machine does not have actionlint on it.
FROM python:3.12-slim-bookworm

# actionlint understands GitHub's schema *and* shellchecks every `run:` block, which is the half
# that catches real mistakes rather than typos.
ARG ACTIONLINT_VERSION=1.7.7
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl shellcheck \
    && curl -fsSL "https://github.com/rhysd/actionlint/releases/download/v${ACTIONLINT_VERSION}/actionlint_${ACTIONLINT_VERSION}_linux_amd64.tar.gz" \
        | tar -xz -C /usr/local/bin actionlint \
    && apt-get purge -y curl \
    && rm -rf /var/lib/apt/lists/*

# GitLab's own lint is an API on a GitLab instance, which a cell has neither a token for nor any
# business calling. check-jsonschema ships GitLab's published schema and runs offline, which
# catches the same class of thing: a key that does not exist, a value of the wrong shape.
RUN pip install --no-cache-dir check-jsonschema==0.31.2

# §13 attaches a second duty to the freshness job: run the generated dependency trees through a
# vulnerability scan, so this does not become an efficient distributor of known-vulnerable
# dependencies. osv-scanner reads a pom.xml or a pnpm-lock.yaml directly and needs no API key.
ARG OSV_SCANNER_VERSION=2.2.4
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && curl -fsSL -o /usr/local/bin/osv-scanner \
        "https://github.com/google/osv-scanner/releases/download/v${OSV_SCANNER_VERSION}/osv-scanner_linux_amd64" \
    && chmod +x /usr/local/bin/osv-scanner \
    && apt-get purge -y curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /bin/bash builder \
    && mkdir -p /work \
    && chown builder:builder /work
USER builder

WORKDIR /work

COPY --chown=builder:builder verification/images/ci/entrypoint.sh /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
