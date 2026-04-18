#!/usr/bin/env bash
set -euo pipefail

# Helper to run the built jar with recommended defaults
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$ROOT_DIR/build/libs/aac2ac3-service-0.1.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "Jar not found: $JAR"
  echo "Build first: ./gradlew bootJar"
  exit 1
fi

INDEX_SCANPATH="${INDEX_SCANPATH:-samples}"
FFMPEG_THREADS="${FFMPEG_THREADS:-1}"
WORKER_MAXCONCURRENCY="${WORKER_MAXCONCURRENCY:-2}"
JAVA_OPTS="${JAVA_OPTS:-}"

echo "Running aac2ac3-service"
echo "  index.scanPath=$INDEX_SCANPATH"
echo "  ffmpeg.threads=$FFMPEG_THREADS"
echo "  worker.maxConcurrency=$WORKER_MAXCONCURRENCY"

exec java $JAVA_OPTS \
  -Dindex.scanPath="$INDEX_SCANPATH" \
  -Dffmpeg.threads="$FFMPEG_THREADS" \
  -Dworker.maxConcurrency="$WORKER_MAXCONCURRENCY" \
  -jar "$JAR"
