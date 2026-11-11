#!/usr/bin/env bash
# One-command local OIDC stack:
#   Keycloak (Docker) -> realm/client bootstrap -> QOC image -> QOC container
#
# The Keycloak realm and its volume survive re-runs. The QOC image is rebuilt
# unless SKIP_BUILD=true is set.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ ! -f "$ROOT/deploy/.env" ]; then
  echo "deploy/.env is missing. Copy deploy/.env.example to deploy/.env and set OPENAI_API_KEY (and the DB settings) first." >&2
  exit 1
fi

# The old deploy/oidc.md used a standalone `docker run` container. Replace it
# with the compose-managed one so the hostname/network settings are reproducible.
if docker ps -a --format '{{.Names}}' | grep -qx keycloak-local; then
  project="$(docker inspect keycloak-local --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
  if [ "$project" != "keycloak" ]; then
    echo "Replacing standalone keycloak-local container (data volume is preserved)..."
    docker rm -f keycloak-local >/dev/null
  fi
fi

cd "$ROOT/deploy/keycloak"
docker compose up -d
./setup-keycloak.sh

cd "$ROOT"
if [ "${SKIP_BUILD:-false}" != "true" ]; then
  ./deploy/build-image-oidc.sh
fi

cd "$ROOT/deploy"
if [ ! -f .env.oidc ]; then
  cp .env.oidc.example .env.oidc
  echo "Created deploy/.env.oidc from deploy/.env.oidc.example"
fi
docker compose -f docker-compose.yml -f docker-compose.oidc.yml up -d qoc-local

echo
echo "QOC:     http://localhost:8071/"
echo "Keycloak: http://localhost:8180/admin (admin / admin)"
echo "Login:   alice / alice-password"
