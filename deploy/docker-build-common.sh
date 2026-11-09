#!/usr/bin/env bash
# Gemeinsame Logik für deploy/build-*.sh: Docker-Image bauen und nach Docker Hub pushen.
# Wird gesourct, nicht direkt ausgeführt.
#
# Umgebungsvariablen:
#   DOCKERHUB_NAMESPACE  (Pflicht) Docker-Hub-Benutzer oder -Organisation, z. B. "andifalk"
#   IMAGE_TAG            Tag (Default: kurzer Git-Commit-Hash, bei uncommitted Changes mit "-dirty")
#   TAG_LATEST           zusätzlich ":latest" taggen/pushen (Default: true)
#   PUSH                 nach Docker Hub pushen (Default: true; false = nur lokal bauen)
#   PLATFORMS            Zielplattform(en) für buildx (Default: linux/amd64;
#                        mehrere nur mit PUSH=true, z. B. linux/amd64,linux/arm64)
set -Eeuo pipefail

DEPLOY_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$DEPLOY_DIR/.." && pwd)"

log(){ printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail(){ printf 'FEHLER: %s\n' "$*" >&2; exit 1; }

# build_and_push <Projektverzeichnis relativ zum Repo> <Image-Name>
build_and_push() {
  local project="$1" name="$2"
  local context="$REPO_DIR/$project"

  command -v docker >/dev/null 2>&1 || fail 'Benötigt: docker'
  docker buildx version >/dev/null 2>&1 || fail 'Benötigt: docker buildx'
  [[ -f "$context/Dockerfile" ]] || fail "Dockerfile fehlt: $context/Dockerfile"
  : "${DOCKERHUB_NAMESPACE:?DOCKERHUB_NAMESPACE erforderlich (Docker-Hub-Benutzer oder -Organisation)}"

  local tag="${IMAGE_TAG:-}"
  if [[ -z "$tag" ]]; then
    tag="$(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)"
    if [[ -n "$(git -C "$REPO_DIR" status --porcelain -- "$project" 2>/dev/null)" ]]; then
      tag="${tag}-dirty"
    fi
  fi

  local image="docker.io/${DOCKERHUB_NAMESPACE}/${name}"
  local platforms="${PLATFORMS:-linux/amd64}"
  local push="${PUSH:-true}"
  local -a tags=(--tag "${image}:${tag}")
  [[ "${TAG_LATEST:-true}" == true ]] && tags+=(--tag "${image}:latest")

  local -a output
  if [[ "$push" == true ]]; then
    output=(--push)
  else
    [[ "$platforms" != *,* ]] || fail 'Mehrere PLATFORMS nur mit PUSH=true möglich (lokales Laden unterstützt nur eine Plattform)'
    output=(--load)
  fi

  log "Build ${image}:${tag} (${platforms}) aus ${project}/"
  docker buildx build --platform "$platforms" "${tags[@]}" "${output[@]}" "$context"

  if [[ "$push" == true ]]; then
    log "Gepusht: ${image}:${tag}$([[ "${TAG_LATEST:-true}" == true ]] && printf ' und :latest')"
  else
    log "Nur lokal gebaut (PUSH=false): ${image}:${tag}"
  fi
}
