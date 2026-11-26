#!/usr/bin/env bash
cd /workspaces/coreplugins || exit 1
echo "[post-start] $(date -u +%FT%TZ)" >> /tmp/start-demo.log
setsid -f bash start-demo.sh >> /tmp/start-demo.log 2>&1 < /dev/null
