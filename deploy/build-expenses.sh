#!/usr/bin/env bash
# Baut das Image für den Expense-MCP-Service (expense-domain) und pusht es nach Docker Hub. Optionen siehe docker-build-common.sh.
# Beispiel: DOCKERHUB_NAMESPACE=meinuser IMAGE_TAG=1.0.0 deploy/build-expenses.sh
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/docker-build-common.sh"
build_and_push "expense-domain" "${IMAGE_NAME:-expense-domain}"
