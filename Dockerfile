# ---------------------------------------------------------------------------
# Stage 1 — Build the Spring Boot fat JAR
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

# Copy Gradle wrapper and build files first for layer caching.
# These change rarely, so Docker can reuse the cached dependency layer.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon

# Copy source and build
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ---------------------------------------------------------------------------
# Stage 2 — Runtime image (no JDK, no source, no Gradle)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

# Create directories for volume-mounted config and report output
RUN mkdir -p /app/config /var/log/email-filter \
    && chown -R appuser:appgroup /app /var/log/email-filter

USER appuser

EXPOSE 8080 8081

# External config is volume-mounted into /app/config/.
# spring.config.additional-location lets it override the bundled application.yaml.
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.additional-location=optional:file:/app/config/"]
