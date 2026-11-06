# syntax=docker/dockerfile:1
# Build stage. The pom is copied first so the dependency layer is cached between builds. The Maven repository is a
# BuildKit cache mount, so a rebuilt layer re-downloads only what changed.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/cfp.jar app.jar
# Scaleway's database needs TLS; the Compose Postgres doesn't, so application.properties defaults to off.
ENV PGSSLMODE=require
# GET /health reports this as its version: docker build --build-arg GIT_SHA=$(git rev-parse HEAD) .
ARG GIT_SHA=unknown
ENV GIT_SHA=$GIT_SHA
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
