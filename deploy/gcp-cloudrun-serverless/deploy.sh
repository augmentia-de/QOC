#!/usr/bin/env bash
# deploy/gcp-cloudrun-serverless/deploy.sh
#
# Deploy QOC as a GCP Cloud Run service (serverless containers).
# The service fetches agent configurations from a remote server
# (GCP VM) and runs prompts statelessly.
#
# Prerequisites:
#   - gcloud CLI configured (gcloud auth login, gcloud config set project)
#   - Docker installed
#   - Native image built: cd qoc && mvn -Dnative package -DskipTests
#   - Artifact Registry repository created (or set CREATE_REPO=true)
#
# Usage:
#   export PROJECT_ID=my-gcp-project
#   export ENGINE_AGENT_SOURCE_URL=https://gcp-vm.example.com
#   export ENGINE_OIDC_ISSUER=https://dev-xxx.us.auth0.com/
#   ./deploy.sh
#
# Configurable via environment variables (see below).

set -euo pipefail

PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${REGION:-us-central1}"
SERVICE_NAME="${SERVICE_NAME:-qoc-serverless}"
REPO_NAME="${REPO_NAME:-qoc-serverless}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
MEMORY="${MEMORY:-512Mi}"
CPU="${CPU:-1}"
MIN_INSTANCES="${MIN_INSTANCES:-0}"
MAX_INSTANCES="${MAX_INSTANCES:-1}"
CREATE_REPO="${CREATE_REPO:-false}"

ENGINE_AGENT_SOURCE_URL="${ENGINE_AGENT_SOURCE_URL:-}"
ENGINE_AGENT_AUTHORING_ENABLED="${ENGINE_AGENT_AUTHORING_ENABLED:-false}"

ENGINE_SECURITY_MODE="${ENGINE_SECURITY_MODE:-oidc}"
ENGINE_OIDC_ISSUER="${ENGINE_OIDC_ISSUER:-}"
ENGINE_OIDC_AUDIENCE="${ENGINE_OIDC_AUDIENCE:-qoc-engine}"

HOME_DIR="${HOME_DIR:-/tmp/home}"

if [ -z "$PROJECT_ID" ]; then
    echo "ERROR: PROJECT_ID not set. Run: gcloud config set project <project-id>"
    exit 1
fi

REGISTRY="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}"
IMAGE="${REGISTRY}/qoc-serverless:${IMAGE_TAG}"

echo "=== QOC Cloud Run Serverless Deployment ==="
echo "Project:      ${PROJECT_ID}"
echo "Region:       ${REGION}"
echo "Service:      ${SERVICE_NAME}"
echo "Memory:       ${MEMORY}"
echo "CPU:          ${CPU}"
echo "Min/Max:      ${MIN_INSTANCES}/${MAX_INSTANCES}"
echo "Agent source: ${ENGINE_AGENT_SOURCE_URL:-<not set>}"
echo ""

NATIVE_BINARY="quarkus/target/*-runner"
if ! ls $NATIVE_BINARY 1>/dev/null 2>&1; then
    echo "Native binary not found. Building..."
    cd "$(dirname "$0")/../.."
    mvn -o -pl quarkus -am clean package -Dnative -DskipTests -Dquarkus.profile=prod,native -Dquarkus.oidc.enabled=true
    cd - >/dev/null
fi

if [ "$CREATE_REPO" = "true" ]; then
    echo "Creating Artifact Registry repository ${REPO_NAME}..."
    gcloud artifacts repositories create "$REPO_NAME" \
        --repository-format=docker \
        --location="$REGION" \
        --project="$PROJECT_ID" 2>/dev/null || true
fi

echo "Configuring Docker auth..."
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

echo "Building container image..."
docker build -f "$(dirname "$0")/Dockerfile.cloudrun" \
    -t "$IMAGE" \
    "$(dirname "$0")/../.."

echo "Pushing to Artifact Registry..."
docker push "$IMAGE"

ENV_VARS="ENGINE_MODE=serverless"
ENV_VARS="${ENV_VARS},ENGINE_SECURITY_MODE=${ENGINE_SECURITY_MODE}"
ENV_VARS="${ENV_VARS},HOME=${HOME_DIR}"
ENV_VARS="${ENV_VARS},ENGINE_WORKSPACE=/tmp/workspace"
ENV_VARS="${ENV_VARS},ENGINE_SHELL_SANDBOX_ENABLED=false"
ENV_VARS="${ENV_VARS},ENGINE_MAX_CONCURRENT_PROMPTS=1"
ENV_VARS="${ENV_VARS},ENGINE_TURN_MAX_WALL_MINUTES=10"
ENV_VARS="${ENV_VARS},ENGINE_AGENT_AUTHORING_ENABLED=${ENGINE_AGENT_AUTHORING_ENABLED}"

if [ -n "$ENGINE_AGENT_SOURCE_URL" ]; then
    ENV_VARS="${ENV_VARS},ENGINE_AGENT_SOURCE_URL=${ENGINE_AGENT_SOURCE_URL}"
fi
if [ -n "$ENGINE_OIDC_ISSUER" ]; then
    ENV_VARS="${ENV_VARS},QUARKUS_OIDC_AUTH_SERVER_URL=${ENGINE_OIDC_ISSUER}"
    ENV_VARS="${ENV_VARS},QUARKUS_OIDC_TOKEN_ISSUER=${ENGINE_OIDC_ISSUER}"
    ENV_VARS="${ENV_VARS},QUARKUS_OIDC_TOKEN_AUDIENCE=${ENGINE_OIDC_AUDIENCE}"
fi

echo "Deploying to Cloud Run..."
gcloud run deploy "$SERVICE_NAME" \
    --image "$IMAGE" \
    --region "$REGION" \
    --project "$PROJECT_ID" \
    --memory "$MEMORY" \
    --cpu "$CPU" \
    --min-instances "$MIN_INSTANCES" \
    --max-instances "$MAX_INSTANCES" \
    --port 8080 \
    --allow-unauthenticated \
    --set-env-vars "$ENV_VARS" \
    --quiet

SERVICE_URL=$(gcloud run services describe "$SERVICE_NAME" \
    --region "$REGION" \
    --project "$PROJECT_ID" \
    --format 'value(status.url)')

echo ""
echo "=== Deployment complete ==="
echo "Service URL:  ${SERVICE_URL}"
echo "MCP endpoint: ${SERVICE_URL}/mcp"
echo ""
echo "Free Tier note: Cloud Run free tier = 2M requests/month + 360K vCPU-seconds + 180K GiB-seconds."
echo "At ${MEMORY} + ${CPU} CPU: ~$((180000 / ${MEMORY%Mi})) seconds/month free (memory-bound)."
