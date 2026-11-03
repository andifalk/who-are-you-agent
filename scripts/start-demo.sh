#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "$0")/common.sh"
mode="${1:-}"
case "$mode" in overprivileged|delegated|google-agent-identity) ;; *) fail 'Aufruf: scripts/start-demo.sh {overprivileged|delegated|google-agent-identity}' ;; esac
need docker
[[ -f .env ]] || fail '.env fehlt'
if [[ "$mode" == google-agent-identity ]]; then
  fail 'Demo 3 ist im aktuellen Java-Projekt nicht implementiert (fail-closed). Kein automatischer Start.'
fi
log "Starte BFF. Modus '$mode' muss anschließend pro Browser-Session in der UI gewählt werden."
compose up -d --build
log 'Browser: http://localhost:8085 (oder dein HTTPS Reverse Proxy).'
log 'MCP-Container werden nicht umkonfiguriert oder neugestartet.'
