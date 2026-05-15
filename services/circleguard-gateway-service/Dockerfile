# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./
COPY services/circleguard-gateway-service/ services/circleguard-gateway-service/
RUN chmod +x gradlew && ./gradlew :services:circleguard-gateway-service:bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r circleguard && useradd -r -g circleguard circleguard
USER circleguard
WORKDIR /app
COPY --from=builder /workspace/services/circleguard-gateway-service/build/libs/*.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
