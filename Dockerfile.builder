FROM openjdk:25-slim AS builder

WORKDIR /workspace
COPY . /workspace

# This builder image can be used on machines that want to build the project in-container.
# Prefer Gradle-based build: use wrapper if present, otherwise require Gradle in the image.
RUN if [ -x ./gradlew ]; then \
			./gradlew --no-daemon -x test bootJar; \
		elif command -v gradle >/dev/null 2>&1; then \
			gradle --no-daemon -x test bootJar; \
		else \
			echo "No Gradle or wrapper found; please generate the Gradle wrapper or use an image with Gradle installed."; exit 1; \
		fi
