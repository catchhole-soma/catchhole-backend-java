# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle

RUN chmod +x gradlew
RUN ./gradlew --no-daemon help

COPY monitoring/java-agent ./monitoring/java-agent
RUN ./gradlew --no-daemon -p monitoring/java-agent test jar prepareAgent

COPY src ./src

RUN ./gradlew --no-daemon clean bootJar \
    && cp "$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" app.jar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system catchhole \
    && useradd --system --gid catchhole --uid 10001 --create-home catchhole

COPY --from=build /workspace/app.jar /app/app.jar
COPY --from=build /workspace/monitoring/java-agent/build/agent/opentelemetry-javaagent.jar /app/otel/
COPY --from=build /workspace/monitoring/java-agent/build/libs/catchhole-tracing-privacy.jar /app/otel/
COPY monitoring/java-agent/agent-prod.properties /app/otel/
COPY --chmod=755 deploy/backend-entrypoint.sh /app/backend-entrypoint.sh

USER catchhole:catchhole

EXPOSE 8080

ENV JAVA_OPTS=""

ENTRYPOINT ["/app/backend-entrypoint.sh"]
