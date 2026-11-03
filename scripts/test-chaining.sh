#!/usr/bin/env bash
set -Eeuo pipefail
# RFC 8693 corporate -> RFC 7523 travel. Never print bearer tokens or secrets.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
need curl; need jq; need base64
umask 077
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

CORPORATE_ISSUER="${CORPORATE_ISSUER:-$(getenv CORPORATE_ISSUER 'https://keycloak.agile-dev.de/realms/corporate')}"
TRAVEL_ISSUER="${TRAVEL_ISSUER:-$(getenv TRAVEL_ISSUER 'https://keycloak.agile-dev.de/realms/travel')}"
CORPORATE_CLIENT_ID="${CORPORATE_CLIENT_ID:-$(getenv CORPORATE_CLIENT_ID 'travel-agent-bff')}"
CORPORATE_CLIENT_SECRET="${CORPORATE_CLIENT_SECRET:-$(getenv CORPORATE_CLIENT_SECRET)}"
TRAVEL_GRANT_CLIENT_ID="${TRAVEL_GRANT_CLIENT_ID:-$(getenv TRAVEL_GRANT_CLIENT_ID 'travel-agent-grant')}"
TRAVEL_GRANT_CLIENT_SECRET="${TRAVEL_GRANT_CLIENT_SECRET:-$(getenv TRAVEL_GRANT_CLIENT_SECRET)}"
EXCHANGE_SCOPE="${EXCHANGE_SCOPE:-$(getenv EXCHANGE_SCOPE 'access-travel-domain')}"
EXCHANGE_AUDIENCE="${EXCHANGE_AUDIENCE:-$(getenv EXCHANGE_AUDIENCE "$TRAVEL_ISSUER")}"
TARGET="${TARGET:-travel}"
case "$TARGET" in
 travel) TARGET_AUDIENCE="${TRAVEL_AUDIENCE:-$(getenv TRAVEL_AUDIENCE 'travel-mcp')}"; TARGET_SCOPES="${TRAVEL_SCOPES:-$(getenv TRAVEL_SCOPES 'travel.read travel.book')}";;
 expense) TARGET_AUDIENCE="${EXPENSE_AUDIENCE:-$(getenv EXPENSE_AUDIENCE 'expense-mcp')}"; TARGET_SCOPES="${EXPENSE_SCOPES:-$(getenv EXPENSE_SCOPES 'expenses.create expenses.read')}";;
 *) fail 'TARGET muss travel oder expense sein';;
esac

[ -n "$CORPORATE_CLIENT_SECRET" ] || fail 'CORPORATE_CLIENT_SECRET fehlt'
[ -n "$TRAVEL_GRANT_CLIENT_SECRET" ] || fail 'TRAVEL_GRANT_CLIENT_SECRET fehlt'
[ -n "${TOKEN1_FILE:-}" ] || fail 'TOKEN1_FILE muss auf eine Datei mit einem Alice Corporate Access Token zeigen (nicht ID Token)'
[ -r "$TOKEN1_FILE" ] || fail 'TOKEN1_FILE ist nicht lesbar'
TOKEN1="$(tr -d '\r\n' < "$TOKEN1_FILE")"
[ -n "$TOKEN1" ] || fail 'Token1 ist leer'

# Decode JWT claims locally for diagnostics; NOT a cryptographic signature validation.
claims() {
 local token="$1" payload
 payload="$(printf '%s' "$token" | cut -d. -f2 | tr '_-' '/+')"
 [ -n "$payload" ] || return 1
 while ((${#payload}%4)); do payload+='='; done
 printf '%s' "$payload" | base64 -d 2>/dev/null | jq -c '{iss,sub,aud,scope,azp,exp,iat}'
}
validate_claims() {
 local token="$1" expected_issuer="$2" expected_audience="$3" expected_scopes="$4" name="$5" data
 data="$(claims "$token")" || fail "$name ist kein lesbarer JWT"
 printf '%s' "$data" | jq -e --arg iss "$expected_issuer" '.iss == $iss' >/dev/null || fail "$name issuer stimmt nicht"
 printf '%s' "$data" | jq -e --arg aud "$expected_audience" '(.aud | if type=="array" then index($aud)!=null else .==$aud end)' >/dev/null || fail "$name audience stimmt nicht: $expected_audience"
 printf '%s' "$data" | jq -e '(.exp | type=="number") and (.exp > now)' >/dev/null || fail "$name ist abgelaufen"
 for scope in $expected_scopes; do
   printf '%s' "$data" | jq -e --arg scope "$scope" '(.scope // "" | split(" ") | index($scope)) != null' >/dev/null || fail "$name scope fehlt: $scope"
 done
 printf '%s\n' "$data" | jq '{iss,sub,aud,scope,azp,exp}'
}
post_token() {
 local label="$1" url="$2" outfile="$3"; shift 3
 local code
 code="$(curl --silent --show-error --max-time 20 --output "$outfile" --write-out '%{http_code}' --request POST "$url" --header 'Content-Type: application/x-www-form-urlencoded' "$@")" || fail "$label: HTTP-Verbindung fehlgeschlagen"
 if [ "$code" != 200 ]; then
  # Print only OAuth error code, never raw response (could contain sensitive data).
  local err; err="$(jq -r '.error // "unknown"' "$outfile" 2>/dev/null || true)"
  fail "$label: HTTP $code, OAuth error: $err"
 fi
 jq -e '.access_token | type=="string" and length>0' "$outfile" >/dev/null || fail "$label: access_token fehlt"
}

log 'Schritt 1: Corporate Token1 aus geschützter Datei (Authorization Code + PKCE separat testen)'
log 'Token1 Claims (informativ; aud kann von Client-ID abweichen):'
claims "$TOKEN1" | jq '{iss,sub,aud,scope,azp,exp}'
log 'Schritt 2: RFC 8693 Corporate Token Exchange'
post_token 'Token Exchange' "$CORPORATE_ISSUER/protocol/openid-connect/token" "$TMP/exchange.json" \
 --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:token-exchange' \
 --data-urlencode "client_id=$CORPORATE_CLIENT_ID" --data-urlencode "client_secret=$CORPORATE_CLIENT_SECRET" \
 --data-urlencode "subject_token=$TOKEN1" \
 --data-urlencode 'subject_token_type=urn:ietf:params:oauth:token-type:access_token' \
 --data-urlencode "scope=$EXCHANGE_SCOPE" --data-urlencode "audience=$EXCHANGE_AUDIENCE"
TOKEN2="$(jq -r '.access_token' "$TMP/exchange.json")"
validate_claims "$TOKEN2" "$CORPORATE_ISSUER" "$EXCHANGE_AUDIENCE" '' 'Token2'
log 'Schritt 3: RFC 7523 JWT Authorization Grant im Travel-Realm'
post_token 'JWT Grant' "$TRAVEL_ISSUER/protocol/openid-connect/token" "$TMP/grant.json" \
 --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
 --data-urlencode "assertion=$TOKEN2" --data-urlencode "client_id=$TRAVEL_GRANT_CLIENT_ID" \
 --data-urlencode "client_secret=$TRAVEL_GRANT_CLIENT_SECRET" \
 --data-urlencode "scope=$TARGET_SCOPES"
TOKEN3="$(jq -r '.access_token' "$TMP/grant.json")"
log "Schritt 4: Token3 Claims prüfen (Ziel: $TARGET)"
validate_claims "$TOKEN3" "$TRAVEL_ISSUER" "$TARGET_AUDIENCE" "$TARGET_SCOPES" 'Token3'
if [ "$TARGET" = expense ]; then
  if printf '%s' "$(claims "$TOKEN3")" | jq -e '(.scope // "" | split(" ") | index("expenses.approve")) != null' >/dev/null; then
   fail 'UNSICHER: Delegated Expense Token hat expenses.approve!'
  fi
  log 'OK: expenses.approve ist NICHT im delegierten Expense Token'
fi
log 'OK: Token1 -> Token2 -> Token3; Claims lokal dekodiert (keine Signaturprüfung)'
log 'Für echten E2E-Test: Token3 durch MCP-Server mit JWT-Signatur, Issuer, Audience und Scopes validieren lassen.'
