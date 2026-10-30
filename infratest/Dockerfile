# Cloud Run image for the infratest module.
#
# Build from the module directory after `mvn package`:
#   docker build -t <image> .
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S infratest && adduser -S infratest -G infratest

WORKDIR /app

# Fast-jar layout produced by: mvn -pl infratest package -DskipTests
COPY target/quarkus-app /app

# Cloud Run profile: listen on 0.0.0.0:${PORT} with an in-memory H2 database.
ENV QUARKUS_PROFILE=cloudrun
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

USER infratest

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
