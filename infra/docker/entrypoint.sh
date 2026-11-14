#!/bin/sh
# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
# Starts the Python server and nginx; if either stops, the container stops (and Docker restarts it).
set -eu

if [ ! -s "${TVBRO_KEY_FILE:-/run/secrets/orbit_key}" ]; then
  echo "orbit: the encryption key file ${TVBRO_KEY_FILE:-/run/secrets/orbit_key} is missing or empty; see docs/orbit/DEPLOY.md" >&2
  exit 1
fi

mkdir -p /tmp/nginx   # read-only root: nginx temp files live in the /tmp tmpfs
cd /opt/orbit/server
python run.py &
PY=$!
nginx -e stderr -c /etc/nginx/orbit.conf -g 'daemon off;' &
NG=$!

trap 'kill -TERM "$PY" "$NG" 2>/dev/null; wait' TERM INT
# whichever ends first ends the container
while kill -0 "$PY" 2>/dev/null && kill -0 "$NG" 2>/dev/null; do sleep 2; done
kill -TERM "$PY" "$NG" 2>/dev/null || true
wait || true
exit 1
