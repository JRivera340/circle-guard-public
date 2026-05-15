# Use a minimal JRE image
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the pre-built JAR from the host
COPY services/circleguard-promotion-service/build/libs/*.jar app.jar

# Expose the application port
EXPOSE 8088

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
