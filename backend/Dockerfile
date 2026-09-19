FROM eclipse-temurin:21-jre-alpine

# The ECS task health check shells out to curl; the JRE-alpine base does not
# ship it, so install it explicitly or every task fails its health check.
RUN apk add --no-cache curl \
    && addgroup -S rollbackshield && adduser -S rollbackshield -G rollbackshield
USER rollbackshield

WORKDIR /app
COPY target/rollbackshield-backend-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
