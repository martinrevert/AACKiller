#!/usr/bin/env bash
set -euo pipefail

JAR=build/libs/aac2ac3-service-0.1.0-SNAPSHOT.jar
BINARY_NAME=aac2ac3-service
TARGET_BIN=build/${BINARY_NAME}

echo "Packaging application, generating Spring AOT assets, and copying runtime dependencies..."
if [ -x "./gradlew" ]; then
  echo "Gradle wrapper found; running ./gradlew bootJar and copyRuntimeDependencies (skip tests)"
  ./gradlew --no-daemon -x test bootJar copyRuntimeDependencies || true
else
  if command -v gradle >/dev/null 2>&1; then
    echo "Gradle CLI found; running gradle bootJar and copyRuntimeDependencies (skip tests)"
    gradle --no-daemon -x test bootJar copyRuntimeDependencies || true
  else
    echo "No Gradle available: please install Gradle or generate the wrapper with 'gradle wrapper --gradle-version 9.1.0'"
    exit 1
  fi
fi

if command -v pack >/dev/null 2>&1; then
  echo "pack CLI found; building native image with Paketo buildpacks (requires Docker)."
  PACK_BUILDER=${PACK_BUILDER:-paketobuildpacks/builder:tiny}
  IMAGE_NAME=${IMAGE_NAME:-aac2ac3-service-native:latest}

  pack build "${IMAGE_NAME}" --path "${JAR}" --builder "${PACK_BUILDER}" \
    --buildpack paketo-buildpacks/java \
    --buildpack paketo-buildpacks/spring-boot \
    --buildpack paketo-buildpacks/native-image \
    --env BP_NATIVE_IMAGE=true

  echo "Paketo build completed; image ${IMAGE_NAME} created locally."
  echo "To extract the native binary from the image:"
  echo "  docker create --name tmp ${IMAGE_NAME}"
  echo "  docker cp tmp:/workspace/${BINARY_NAME} ${TARGET_BIN}"
  echo "  docker rm tmp"
  exit 0
fi

if command -v native-image >/dev/null 2>&1; then
  echo "native-image found in PATH; building native binary directly (classpath mode)"
  native-image ${NATIVE_IMAGE_FLAGS:---no-fallback} \
    -cp "build/classes/java/main:build/spring-aot/main/classes:build/spring-aot/main/resources:build/dependency/*" \
    ${MAIN_CLASS:-ar.com.martinrevert.aac2ac3.Application} \
    -o "${BINARY_NAME}"
  mv "${BINARY_NAME}" build/
  echo "Native binary created at ${TARGET_BIN}"
  exit 0
fi

if [ -n "${GRAALVM_HOME:-}" ] && [ -x "${GRAALVM_HOME}/bin/native-image" ]; then
  echo "Using native-image from GRAALVM_HOME ($GRAALVM_HOME) in classpath mode"
  "${GRAALVM_HOME}/bin/native-image" ${NATIVE_IMAGE_FLAGS:---no-fallback} \
    -cp "build/classes/java/main:build/spring-aot/main/classes:build/spring-aot/main/resources:build/dependency/*" \
    ${MAIN_CLASS:-ar.com.martinrevert.aac2ac3.Application} \
    -o "${BINARY_NAME}"
  mv "${BINARY_NAME}" build/
  echo "Native binary created at ${TARGET_BIN}"
  exit 0
fi

cat <<'EOF'
Neither 'pack' nor 'native-image' found / usable.

Install one of the following and re-run this script:
- pack + Docker to use Paketo buildpacks
- GraalVM with the `native-image` component (add to PATH or set GRAALVM_HOME)
EOF

exit 1
