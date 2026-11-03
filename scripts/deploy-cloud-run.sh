#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "$0")/common.sh"
need gcloud
need docker
: "${GCP_PROJECT:?GCP_PROJECT erforderlich}"
: "${GCP_REGION:?GCP_REGION erforderlich}"
: "${GCP_REPOSITORY:?GCP_REPOSITORY (Artifact Registry) erforderlich}"
service="${GCP_SERVICE:-travel-agent}"
image="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT}/${GCP_REPOSITORY}/${service}:$(date +%Y%m%d%H%M%S)"
[[ "${CONFIRM_CLOUD_DEPLOY:-}" == YES ]] || fail 'Cloud-Deployment nur mit CONFIRM_CLOUD_DEPLOY=YES. Secrets und Cloud Run Access müssen vorab konfiguriert sein.'
log "Build & Push: $image"
gcloud builds submit --project "$GCP_PROJECT" --tag "$image" .
log 'Deploy (keine automatische Freigabe für unauthentifizierte Nutzer)'
gcloud run deploy "$service" --project "$GCP_PROJECT" --region "$GCP_REGION" --image "$image" --port 8080 --no-allow-unauthenticated
log 'WICHTIG: Browser-OIDC-Login benötigt passende Cloud Run IAM/Ingress-Freigabe, Callback URL, Secret Manager, Session Store und Google-Agent-Identity-Integration.'
