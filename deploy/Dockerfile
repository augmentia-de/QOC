# Minimal runtime image for the QOC engine (e2-micro friendly).
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Fast-jar layout produced by: mvn -pl quarkus package -DskipTests
COPY quarkus/target/quarkus-app /app

# Writable data volume: SQLite DB, workspace, logs.
VOLUME ["/data"]

ENV JAVA_OPTS="-Xms64m -Xmx384m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"
ENV QUARKUS_HTTP_HOST=0.0.0.0
ENV QUARKUS_HTTP_PORT=8080
ENV ENGINE_DB_KIND=sqlite
ENV ENGINE_DB_PATH=/data/qoc.db
ENV ENGINE_WORKSPACE=/data/workspace
ENV LOG_DIR=/data/log
ENV QUARKUS_OTEL_ENABLED=false
ENV QUARKUS_LOG_FILE_ENABLE=false

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "mkdir -p /data/workspace /data/log && exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
