# Simplified Dockerfile using pre-built JAR to avoid line ending issues
FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r circleguard && useradd -r -g circleguard circleguard
USER circleguard
WORKDIR /app

# Copy the JAR built on the host
COPY services/circleguard-form-service/build/libs/*.jar app.jar

EXPOSE 8086
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
