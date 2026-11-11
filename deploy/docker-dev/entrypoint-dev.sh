#!/bin/sh
# Dev entrypoint: ensure the engine jar exists in the (host-mounted) Maven
# repository, then start Quarkus dev mode with hot reload.
set -e
umask 000

M2_REPO="${MAVEN_REPO:-$HOME/.m2}"
ENGINE_JAR="$M2_REPO/repository/de/augmentia/qoc/engine/1.0.0-SNAPSHOT/engine-1.0.0-SNAPSHOT.jar"

cd /app

if [ ! -f "$ENGINE_JAR" ] || [ -n "$(find engine/src/main/java -newer "$ENGINE_JAR" -print -quit)" ]; then
    echo "Building engine into local Maven repository..."
    mvn -q -pl engine -am install -DskipTests -Djacoco.skip=true
fi

cd quarkus
exec mvn quarkus:dev \
    -Dquarkus.http.host=0.0.0.0 \
    -Dquarkus.test.continuous-testing=disabled \
    -Dmaven.test.skip=true
