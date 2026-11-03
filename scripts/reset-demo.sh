#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "$0")/common.sh"
need curl
# No invented reset endpoints or silent destruction of persistent expense data.
[[ "${1:-}" == '--confirm' ]] || fail 'Aufruf: scripts/reset-demo.sh --confirm (nur Browser-/Agent-Session, keine MCP-Daten)'
log 'Ein echter Reset von BOOK-100/EXP-8000 benötigt explizite Reset-APIs in den MCP-Servern.'
log 'Die vorhandene Agent-App speichert Sessions im Prozessspeicher.'
need docker
compose restart travel-agent
log 'Agent-Sessions durch Container-Neustart gelöscht; MCP-Daten NICHT verändert.'
log 'Für vollständigen Reset: Fixtures in Travel/Expense MCP gezielt neu initialisieren.'
