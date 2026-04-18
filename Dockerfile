FROM openjdk:25-slim

# Install ffmpeg in the runtime image
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/*

ARG JAR_FILE=build/libs/aac2ac3-service-0.1.0-SNAPSHOT.jar
COPY ${JAR_FILE} /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
