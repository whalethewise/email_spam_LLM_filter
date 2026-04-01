# ---------------------------------------------------------------------------
# Multi-stage build — compiles from source, no pre-built JAR required.
# Use this when building from the repo on the server.
#
#   docker compose up -d --build
#
# To deploy a pre-built JAR instead, see Dockerfile.runtime.
# ---------------------------------------------------------------------------

# --- Stage 1: Build ---
FROM amazoncorretto:21-alpine AS build

RUN apk add --no-cache bash

WORKDIR /workspace

# Copy Gradle wrapper and build files first for dependency layer caching.
# These change rarely — Docker reuses this layer until build files change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Copy source and build the fat JAR (tests run in CI, not here)
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# --- Stage 2: Runtime ---
FROM amazoncorretto:21-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

RUN mkdir -p /app/config /var/log/email-filter \
    && chown -R appuser:appgroup /app /var/log/email-filter

USER appuser

EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.additional-location=optional:file:/app/config/"]
