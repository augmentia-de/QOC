#!/usr/bin/env bash
# Baut die native QOC-Binary im Memory-Modus (keine DB, kein Flyway).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$REPO_ROOT"

export ENGINE_STORAGE="${ENGINE_STORAGE:-memory}"
export QUARKUS_FLYWAY_ENABLED="${QUARKUS_FLYWAY_ENABLED:-false}"

# JAVA_HOME / Maven ggf. anpassen:
# export JAVA_HOME=/tmp/graalvm25/graalvm-community-25.4.4.1.1+1.1
# export PATH="$JAVA_HOME/bin:/opt/maven/apache-maven-3.9.10/bin:$PATH"

mvn -o -pl quarkus -am clean package \
  -Dquarkus.package.type=native \
  -Dquarkus.profile=prod,native \
  -DskipTests -Djacoco.skip=true

echo "Native binary: $REPO_ROOT/quarkus/target/*-runner"
