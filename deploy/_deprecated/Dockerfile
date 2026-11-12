# Minimal runtime image for the QOC engine (e2-micro friendly).
FROM eclipse-temurin:21-jre-alpine

# script(1) provides the optional one-off PTY for the shell tool; git backs the
# worktree tools.
RUN apk add --no-cache util-linux git bubblewrap

# Create dedicated user
RUN addgroup -S qoc && adduser -S qoc -G qoc -h /var/lib/qoc -s /sbin/nologin

WORKDIR /app

# Fast-jar layout produced by: mvn -pl quarkus package -DskipTests
COPY quarkus/target/quarkus-app /app

# Writable data volume: workspace, logs, and HOME.
VOLUME ["/data"]

# Create data directories and set ownership.
# /data/home is the process HOME: plan files (~/.opencode/plan) land in the
# persistent volume instead of the container root or the workspace.
RUN mkdir -p /data/workspace /data/wf /data/log /data/home/.opencode/plan /var/lib/qoc && \
    chown -R qoc:qoc /data /var/lib/qoc && \
    chmod 750 /data /data/workspace /data/wf /data/log /var/lib/qoc && \
    chmod 700 /data/home

ENV HOME=/data/home
ENV JAVA_OPTS="-Xms64m -Xmx384m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
ENV QUARKUS_HTTP_HOST=0.0.0.0
ENV QUARKUS_HTTP_PORT=8080
EXPOSE 5005
ARG ENGINE_DB_KIND=postgresql
ENV ENGINE_DB_KIND=${ENGINE_DB_KIND}
ENV ENGINE_WORKSPACE=/data/workspace
ENV LOG_DIR=/data/log
ENV QUARKUS_OTEL_ENABLED=false
ENV QUARKUS_LOG_FILE_ENABLED=true
ENV QUARKUS_LOG_LEVEL=DEBUG
ENV QUARKUS_LOG_CONSOLE_LEVEL=DEBUG
ENV QUARKUS_LOG_FILE_LEVEL=DEBUG
ENV QUARKUS_LOG_FILE_PATH=/data/log/qoc.log

EXPOSE 8080

# Run as dedicated user (not root)
USER qoc

# The bind mount on /data hides the build-time /data/home, so create it again
# at startup. HOME must be writable by the runtime user (see compose user:).
ENTRYPOINT ["sh", "-c", "mkdir -p \"$ENGINE_WORKSPACE/plans\" && exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]