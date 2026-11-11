#!/bin/sh
# Native dev entrypoint.
#
# - Rebuilds the engine jar into the (host-mounted) Maven repository if needed.
# - Rebuilds the native runner when no runner exists or sources are newer.
# - Starts the native runner.
#
# DEV_NATIVE_REBUILD:
#   auto   (default) build when the runner is missing or sources are newer
#   always          always rebuild on container start
#   never           never rebuild; fail if no runner exists
#
# DEV_NATIVE_OIDC:
#   true/false (default false). OIDC is a build-time setting in native mode;
#   changing it requires a rebuild.
set -eu

umask 000

APP_DIR="${APP_DIR:-/app}"
M2_REPO="${MAVEN_REPO:-/home/qoc/.m2}"
M2_LOCAL="${M2_REPO}/repository"
ENGINE_VERSION="${ENGINE_VERSION:-1.0.0-SNAPSHOT}"
ENGINE_JAR="${M2_LOCAL}/de/augmentia/qoc/engine/${ENGINE_VERSION}/engine-${ENGINE_VERSION}.jar"
REBUILD="${DEV_NATIVE_REBUILD:-auto}"
OIDC="${DEV_NATIVE_OIDC:-false}"

cd "$APP_DIR"

find_runner() {
    ls "$APP_DIR"/quarkus/target/*-runner 2>/dev/null | head -n 1 || true
}

sources_newer_than() {
    reference="$1"
    [ -n "$reference" ] || return 1
    [ -n "$(find \
        engine/src/main/java \
        quarkus/src/main/java \
        quarkus/src/main/resources \
        pom.xml engine/pom.xml quarkus/pom.xml \
        -newer "$reference" -print -quit 2>/dev/null)" ]
}

build_engine() {
    echo "[dev-native] building engine into local Maven repository..."
    mvn -q -B -pl engine -am install \
        -DskipTests \
        -Djacoco.skip=true \
        -Dmaven.repo.local="$M2_LOCAL"
}

build_native() {
    echo "[dev-native] building native image (this can take several minutes)..."
    export ENGINE_DB_KIND="${ENGINE_DB_KIND:-postgresql}"
    export ENGINE_DB_DRIVER="${ENGINE_DB_DRIVER:-org.postgresql.Driver}"
    export ENGINE_FLYWAY_LOCATIONS="${ENGINE_FLYWAY_LOCATIONS:-classpath:db/migration}"
    export QUARKUS_OIDC_ENABLED="$OIDC"

    mvn -B -pl quarkus -am package \
        -DskipTests \
        -Djacoco.skip=true \
        -Dmaven.repo.local="$M2_LOCAL" \
        -Dquarkus.package.type=native \
        -Dquarkus.native.container-build=false \
        -Dquarkus.arc.exclude-types=org.a2aproject.sdk.**
}

if [ ! -f "$ENGINE_JAR" ] || [ -n "$(find engine/src/main/java -newer "$ENGINE_JAR" -print -quit 2>/dev/null)" ]; then
    build_engine
fi

RUNNER="$(find_runner)"

case "$REBUILD" in
    always)
        build_native
        RUNNER="$(find_runner)"
        ;;
    auto)
        if [ -z "$RUNNER" ] || sources_newer_than "$RUNNER"; then
            build_native
            RUNNER="$(find_runner)"
        fi
        ;;
    never)
        ;;
    *)
        echo "[dev-native] invalid DEV_NATIVE_REBUILD='$REBUILD' (use auto|always|never)" >&2
        exit 2
        ;;
esac

if [ -z "$RUNNER" ] || [ ! -x "$RUNNER" ]; then
    echo "[dev-native] no native runner found under $APP_DIR/quarkus/target/" >&2
    echo "[dev-native] set DEV_NATIVE_REBUILD=always or check the build output" >&2
    exit 1
fi

mkdir -p "${HOME}/.opencode/plan"

echo "[dev-native] starting $RUNNER"
exec "$RUNNER"
