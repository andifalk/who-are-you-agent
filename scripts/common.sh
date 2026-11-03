#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"
log(){ printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail(){ printf 'FEHLER: %s\n' "$*" >&2; exit 1; }
need(){ command -v "$1" >/dev/null 2>&1 || fail "Benötigt: $1"; }
# Do not source .env: untrusted shell syntax and whitespace in scopes.
getenv(){ local key="$1" default="${2:-}" value; value="$(grep -E "^${key}=" "$PROJECT_DIR/.env" 2>/dev/null | tail -1 | cut -d= -f2- || true)"; printf '%s' "${value:-$default}"; }
compose(){ docker compose --project-directory "$PROJECT_DIR" -f "$PROJECT_DIR/compose.yaml" "$@"; }
