FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S rollbackshield && adduser -S rollbackshield -G rollbackshield
USER rollbackshield

WORKDIR /app
COPY target/rollbackshield-backend-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
