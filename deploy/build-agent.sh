#!/usr/bin/env bash
# Baut das Image für den Travel-MCP-Service (travel-agent) und pusht es nach Docker Hub. Optionen siehe docker-build-common.sh.
# Beispiel: DOCKERHUB_NAMESPACE=meinuser IMAGE_TAG=1.0.0 deploy/build-travel.sh
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/docker-build-common.sh"
build_and_push "travel-agent" "${IMAGE_NAME:-travel-agent}"
