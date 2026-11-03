#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "$0")/common.sh"
need curl; need python3
log 'Preflight: Tools, Konfiguration, OIDC Discovery und Erreichbarkeit'
[[ -f .env ]] || fail '.env fehlt (cp .env.example .env)'
for k in CORPORATE_CLIENT_SECRET TRAVEL_GRANT_CLIENT_SECRET INSECURE_CLIENT_SECRET GOOGLE_API_KEY; do
  [[ -n "$(getenv "$k")" ]] || fail "$k fehlt in .env"
done
python3 - <<'PY'
from pathlib import Path
p=Path('.env')
for line in p.read_text().splitlines():
 if line.startswith(('CORPORATE_CLIENT_SECRET=','TRAVEL_GRANT_CLIENT_SECRET=','INSECURE_CLIENT_SECRET=')) and ('example' in line.lower() or 'changeme' in line.lower()):
  raise SystemExit('FEHLER: Beispiel-Secret in .env')
PY
for k in CORPORATE_ISSUER TRAVEL_ISSUER; do
  issuer="$(getenv "$k")"
  [[ "$issuer" == https://* ]] || fail "$k muss HTTPS verwenden"
  status="$(curl --silent --show-error --location --max-time 10 --output /dev/null --write-out '%{http_code}' "$issuer/.well-known/openid-configuration" || true)"
  [[ "$status" == 200 ]] || fail "$k Discovery HTTP $status"
  log "$k: Discovery OK"
done
for k in TRAVEL_MCP_URL EXPENSE_MCP_URL; do
  url="$(getenv "$k")"
  [[ "$url" == https://* ]] || fail "$k muss HTTPS verwenden"
  # 401/403/405/406 are plausible for unauthenticated MCP transport.
  status="$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' "$url" || true)"
  case "$status" in 200|400|401|403|405|406) log "$k erreichbar (HTTP $status)";; *) fail "$k nicht plausibel erreichbar (HTTP $status)";; esac
done
log 'Preflight bestanden. Dies prüft NICHT Token-Chaining, Tool-Schemas oder Autorisierung.'
