#!/usr/bin/env bash
# Build the QOC native runner and package it as a Docker runtime image.
#
# The native build runs in the Mandrel 25 dev image (deploy/docker-dev-native)
# with the *host* Maven repository mounted read-write. That avoids downloading
# the whole dependency tree again inside a throw-away builder stage, which is
# painfully slow on low-bandwidth links.
#
# For a fully self-contained build see deploy/Dockerfile.native:
#   cd deploy && docker compose --profile native build qoc-native
#
# Usage:
#   ./deploy/build-native-image.sh            # build + image
#   SKIP_BUILD=1 ./deploy/build-native-image.sh   # image from existing runner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BUILDER_IMAGE="${BUILDER_IMAGE:-qoc-engine:dev-native}"
IMAGE="${IMAGE:-qoc-engine:native}"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
    if ! docker image inspect "$BUILDER_IMAGE" >/dev/null 2>&1; then
        echo "Builder image $BUILDER_IMAGE not found; building deploy/docker-dev-native ..."
        (cd deploy && docker compose build qoc-dev-native)
    fi

    echo "Building native runner in $BUILDER_IMAGE (host ~/.m2 is reused) ..."
    docker run --rm \
        -u "$(id -u):$(id -g)" \
        -v "$ROOT":/app \
        -v "${HOME}/.m2":/home/qoc/.m2 \
        -w /app \
        -e MAVEN_OPTS="-Duser.home=/home/qoc" \
        -e MAVEN_CONFIG=/home/qoc/.m2 \
        --entrypoint /opt/maven/bin/mvn \
        "$BUILDER_IMAGE" \
        -B -pl quarkus -am \
        -DskipTests -Djacoco.skip=true \
        -Dmaven.repo.local=/home/qoc/.m2/repository \
        -Dquarkus.package.type=native \
        -Dquarkus.native.container-build=false \
        -Dquarkus.arc.exclude-types=org.a2aproject.sdk.** \
        package
fi

RUNNER=$(ls "$ROOT"/quarkus/target/*-runner 2>/dev/null | head -n 1 || true)
if [ -z "$RUNNER" ]; then
    echo "No native runner found under quarkus/target/" >&2
    echo "Run without SKIP_BUILD=1 or check the build output." >&2
    exit 1
fi
echo "Native runner: $RUNNER"

echo "Building runtime image $IMAGE ..."
docker build -f deploy/Dockerfile.native-runtime -t "$IMAGE" .

echo
echo "Done. Start the stack with:"
echo "  cd deploy && docker compose --profile native up -d"
