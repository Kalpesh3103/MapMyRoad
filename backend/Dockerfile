# syntax=docker/dockerfile:1
#
# MapMyRoad API runtime image (M4-1, DEPLOYMENT.md sections 4 and 6.1).
#
# This Dockerfile does NOT compile the code. Build the Spring Boot executable jar first:
#   cd backend && ./gradlew bootJar          (gradlew.bat bootJar on Windows)
# then build the image from the backend/ directory:
#   docker build -t mmr-api:dev .
# CI does the same (.github/workflows/backend-ci.yml, job "image").
#
# Gradle writes two jars to build/libs/: the executable boot jar and a "-plain.jar" (classes only).
# .dockerignore excludes "*-plain.jar", so the glob below matches exactly one file. If it ever matches
# more than one (for example stale jars from an older version), `docker build` fails loudly instead of
# silently picking the wrong jar: run `./gradlew clean bootJar`.
#
# Base image: Eclipse Temurin 21 JRE on Ubuntu 24.04 (noble). Multi-arch (amd64 and arm64), which the
# Oracle Ampere VM needs (TECH_STACK.md section 6).
# TODO(M4-4): pin the full version tag (for example 21.0.x_y-jre-noble) and, for production, the digest
# (eclipse-temurin:<tag>@sha256:...). Dependabot (docker ecosystem, /backend) proposes updates.
# Look the values up on Docker Hub; do not invent them.

# ---------------------------------------------------------------------------------------------------
# Stage 1: unpack the boot jar into layers.
# Runs on the build machine's own platform ($BUILDPLATFORM) because the output is plain files that are
# identical for every architecture. This avoids running the JVM under QEMU in multi-arch CI builds.
# ---------------------------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jre-noble AS extract
WORKDIR /workspace
COPY build/libs/*.jar app.jar
# Spring Boot's jarmode "tools" splits the jar into directories that change at different rates:
#   dependencies/            third-party jars (change rarely)
#   spring-boot-loader/      the launcher classes
#   snapshot-dependencies/   SNAPSHOT jars (normally empty)
#   application/             our classes and resources (change on every commit)
# --launcher keeps the launcher layout so the app starts with JarLauncher (see ENTRYPOINT).
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------------------------------
# Stage 2: runtime image.
# ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

LABEL org.opencontainers.image.title="mmr-api" \
      org.opencontainers.image.description="MapMyRoad backend API" \
      org.opencontainers.image.licenses="NOASSERTION"

# Dedicated non-root user with a fixed uid/gid (SECURITY.md section 9: containers run as non-root).
# A fixed id makes file ownership predictable for mounted secrets (for example the Play Integrity key
# must be readable by uid 10001).
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app

WORKDIR /app

# Copy layers from least to most frequently changing, so a code-only change re-uploads only the small
# "application" layer. Files are owned by root and only readable by the app user: the process cannot
# modify its own code, which also suits a read-only root filesystem.
COPY --from=extract /workspace/extracted/dependencies/ ./
COPY --from=extract /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract /workspace/extracted/application/ ./

USER 10001:10001

# Public API port only. The Actuator management port (8081, ADR-0015) is deliberately NOT exposed:
# it is reachable only on the internal Docker network and is never published or proxied.
EXPOSE 8080

# Runtime expectations (set in infra/docker-compose*.yml, not here):
# - read_only: true with a tmpfs on /tmp (Tomcat work files and JVM perf data go to /tmp).
# - No HEALTHCHECK in this image: neither curl nor wget is reliably present in the Temurin JRE image.
#   Compose defines a bash /dev/tcp probe against the management port instead, and Caddy actively
#   health-checks /v1/config.
# - No secrets in the image (SECURITY.md section 10): all configuration comes from environment variables.

# MaxRAMPercentage sizes the heap from the container memory limit (mem_limit in compose) rather than
# from host memory. JarLauncher is the Spring Boot 3.2+/4.x launcher class for the --launcher layout.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
