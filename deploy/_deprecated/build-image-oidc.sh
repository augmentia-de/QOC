#!/usr/bin/env bash
# Build the QOC image with the Quarkus OIDC extension enabled.
#
# quarkus.oidc.enabled is a build-time property, so the runtime env var alone
# is not enough. This wrapper keeps the normal build script as the single
# place for the PostgreSQL fast-jar build and image build.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export QUARKUS_OIDC_ENABLED=true
exec "$ROOT/deploy/build-image.sh"
