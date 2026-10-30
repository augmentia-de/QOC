#!/usr/bin/env bash
# Build, push and deploy the infratest module to Google Cloud Run.
#
# Free-tier defaults:
#   - region us-central1
#   - scale to zero (min-instances=0)
#   - max-instances=1 (the in-memory H2 database is per instance)
#   - ephemeral in-memory database, data is lost on cold start/revision changes
#
# Required: authenticated gcloud, Docker, Java 21, Maven.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJECT="${GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${GCP_REGION:-us-central1}"
SERVICE="${SERVICE_NAME:-infratest}"
REPO="${ARTIFACT_REPO:-infratest}"
TAG="$(date '+%Y%m%d-%H%M%S')"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT}/${REPO}/${SERVICE}:${TAG}"

if [[ -z "$PROJECT" || "$PROJECT" == "(unset)" ]]; then
  echo "No GCP project configured. Set GCP_PROJECT or run 'gcloud config set project <id>'." >&2
  exit 1
fi

cd "$ROOT"

echo "==> Building fast-jar"
mvn -q package -DskipTests

echo "==> Ensuring Artifact Registry repository ${REPO} in ${REGION}"
if ! gcloud artifacts repositories describe "$REPO" --location="$REGION" >/dev/null 2>&1; then
  gcloud artifacts repositories create "$REPO" \
    --repository-format=docker \
    --location="$REGION" \
    --description="infratest Quarkus images"
fi

echo "==> Configuring Docker authentication"
# Suppress the command output: it prints the local Docker config, which may
# contain credentials for other registries.
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet >/dev/null

echo "==> Building and pushing ${IMAGE}"
docker build -t "$IMAGE" .
docker push "$IMAGE"

echo "==> Deploying Cloud Run service ${SERVICE}"
gcloud run deploy "$SERVICE" \
  --image="$IMAGE" \
  --region="$REGION" \
  --platform=managed \
  --allow-unauthenticated \
  --port=8080 \
  --cpu=1 \
  --memory=512Mi \
  --concurrency=10 \
  --min-instances=0 \
  --max-instances=1 \
  --timeout=60

echo "==> Service URL"
gcloud run services describe "$SERVICE" \
  --region="$REGION" \
  --format='value(status.url)'
