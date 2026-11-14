# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
#
# The Orbit home server in one container: nginx (frontend + proxy, port 8080) in front of the Python server
# (127.0.0.1:8750), as in tvBro's tested production setup. One container so the server sees each client's real
# address (it trusts X-Real-IP only from 127.0.0.1) for its sign-in throttling. Runs as uid 1000, no root.
# Build from the repository root:  docker build -f infra/docker/Dockerfile --build-arg ORBIT_VERSION=$(git rev-parse --short HEAD) .

FROM python:3.13-slim@sha256:e48f59cdba69a3cbd9ed2cf0f348d90d45a8d350d98134cf1070bc106ee9ed90

ARG ORBIT_VERSION=dev
# the browser players, pinned by SHA-256 (same pins as web/deploy/lxc_common.sh); never fetched at runtime
ARG HLS_URL=https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js
ARG HLS_SHA256=484054e8cd03d3f6d1781fb7f402bdc318d8a4c527f933a95c624e27cc9a9470
ARG MPEGTS_URL=https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.min.js
ARG MPEGTS_SHA256=453d5591d09b281a7862e56659aca8e4d0e470d86fe0fcb85684d986475e632e

RUN apt-get update \
 && apt-get install -y --no-install-recommends nginx ffmpeg ca-certificates curl \
 && rm -rf /var/lib/apt/lists/* \
 && pip install --no-cache-dir "cryptography==50.0.2"

WORKDIR /opt/orbit
COPY web/server ./server
COPY web/frontend ./frontend
COPY infra/docker/nginx.conf /etc/nginx/orbit.conf
COPY infra/docker/openssl.cnf /etc/ssl/orbit-openssl.cnf
COPY infra/docker/entrypoint.sh /usr/local/bin/orbit-entrypoint

# version-stamp the frontend (like web/deploy/stage.ps1): long browser caching, fresh files after each update
RUN set -eu; \
    find frontend -name '*.js' -exec sed -i -E "s#(from[[:space:]]+'\./[A-Za-z0-9_-]+\.js)'#\1?v=${ORBIT_VERSION}'#g" {} +; \
    sed -i "s/__V__/${ORBIT_VERSION}/g" frontend/index.html; \
    mkdir -p frontend/vendor; \
    curl -fsSL "$HLS_URL" -o frontend/vendor/hls.min.js; \
    curl -fsSL "$MPEGTS_URL" -o frontend/vendor/mpegts.min.js; \
    echo "$HLS_SHA256  frontend/vendor/hls.min.js" | sha256sum -c -; \
    echo "$MPEGTS_SHA256  frontend/vendor/mpegts.min.js" | sha256sum -c -; \
    apt-get purge -y curl >/dev/null 2>&1 || true; \
    find server -name '__pycache__' -prune -exec rm -rf {} +; \
    chmod 755 /usr/local/bin/orbit-entrypoint; \
    mkdir -p /data /tmp/nginx && chown -R 1000:1000 /data /tmp/nginx

ENV TVBRO_DATA=/data \
    TVBRO_BIND=127.0.0.1 \
    TVBRO_PORT=8750 \
    TVBRO_KEY_FILE=/run/secrets/orbit_key \
    OPENSSL_CONF=/etc/ssl/orbit-openssl.cnf \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    ORBIT_VERSION=${ORBIT_VERSION}

USER 1000:1000
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD python -c "import urllib.request as u; u.urlopen(u.Request('http://127.0.0.1:8080/api/health', headers={'Host': 'localhost'}), timeout=4)" || exit 1
ENTRYPOINT ["/usr/local/bin/orbit-entrypoint"]
