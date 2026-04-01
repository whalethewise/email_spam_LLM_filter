FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY build/libs/*.jar app.jar

# External config is volume-mounted into /app/config/
# Spring Boot picks up /app/config/application.yml automatically via spring.config.additional-location
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.additional-location=optional:file:/app/config/"]
