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

# Avoid H2 file-lock collisions: stop any previously running instance of this jar.
EXISTING_PIDS="$(pgrep -f -- "$JAR" || true)"
if [ -n "$EXISTING_PIDS" ]; then
  echo "Stopping existing instance(s): $EXISTING_PIDS"
  # shellcheck disable=SC2086
  kill $EXISTING_PIDS || true
  for _ in $(seq 1 20); do
    sleep 0.25
    if ! pgrep -f -- "$JAR" >/dev/null 2>&1; then
      break
    fi
  done
  if pgrep -f -- "$JAR" >/dev/null 2>&1; then
    echo "Existing instance still running; forcing stop"
    # shellcheck disable=SC2086
    kill -9 $EXISTING_PIDS || true
  fi
fi

echo "Running aac2ac3-service"
echo "  index.scanPath=$INDEX_SCANPATH"
echo "  ffmpeg.threads=$FFMPEG_THREADS"
echo "  worker.maxConcurrency=$WORKER_MAXCONCURRENCY"

exec java $JAVA_OPTS \
  -Dindex.scanPath="$INDEX_SCANPATH" \
  -Dffmpeg.threads="$FFMPEG_THREADS" \
  -Dworker.maxConcurrency="$WORKER_MAXCONCURRENCY" \
  -jar "$JAR"
