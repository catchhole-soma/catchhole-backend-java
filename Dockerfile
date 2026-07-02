# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle

RUN chmod +x gradlew
RUN ./gradlew --no-daemon help

COPY src ./src

RUN ./gradlew --no-daemon clean bootJar \
    && cp "$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" app.jar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system catchhole \
    && useradd --system --gid catchhole --uid 10001 --create-home catchhole

COPY --from=build /workspace/app.jar /app/app.jar

USER catchhole:catchhole

EXPOSE 8080

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
