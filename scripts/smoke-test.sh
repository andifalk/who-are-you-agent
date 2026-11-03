#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "$0")/common.sh"
need curl
base="${AGENT_BASE_URL:-http://localhost:8085}"
log "Smoke-Test: $base"
status="$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' "$base/actuator/health" || true)"
[[ "$status" == 200 ]] || fail "Health-Endpoint HTTP $status"
log 'Health OK'
status="$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' "$base/api/session" || true)"
case "$status" in 302|303|401) log 'Geschützter Session-Endpunkt: HTTP '$status;; *) fail "Session-Endpunkt nicht geschützt oder unerwartet: HTTP $status";; esac
log 'Smoke-Test bestanden (keine echte OAuth- oder MCP-End-to-End-Prüfung).'
