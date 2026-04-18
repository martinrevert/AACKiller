#!/usr/bin/env bash
set -euo pipefail
# Gradle bootstrapper: prefer system `gradle`; otherwise download and run distribution
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPERTIES_FILE="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.properties"

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

if [ ! -f "$PROPERTIES_FILE" ]; then
  echo "Missing $PROPERTIES_FILE" >&2
  exit 1
fi

DIST_URL=$(grep '^distributionUrl=' "$PROPERTIES_FILE" | cut -d'=' -f2- | tr -d '\r' | sed 's/\\:/:/g')
if [ -z "$DIST_URL" ]; then
  echo "distributionUrl not found in $PROPERTIES_FILE" >&2
  exit 1
fi

CACHE_DIR="$SCRIPT_DIR/.gradle-dist"
ZIP_NAME=$(basename "$DIST_URL")
DIST_DIR="$CACHE_DIR/${ZIP_NAME%.*}"

if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  mkdir -p "$CACHE_DIR"
  TMP_ZIP="$CACHE_DIR/$ZIP_NAME"
  echo "Downloading Gradle from $DIST_URL"
  if command -v curl >/dev/null 2>&1; then
    curl -fSL "$DIST_URL" -o "$TMP_ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$TMP_ZIP" "$DIST_URL"
  else
    echo "Neither curl nor wget available to download Gradle distribution" >&2
    exit 1
  fi

  echo "Extracting Gradle distribution to $DIST_DIR"
  mkdir -p "$DIST_DIR"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$TMP_ZIP" -d "$CACHE_DIR"
    # move extracted dir (gradle-9.1.0) into expected path
    EXTRACTED_DIR=$(unzip -Z -1 "$TMP_ZIP" | head -n1 | cut -d'/' -f1)
    if [ -d "$CACHE_DIR/$EXTRACTED_DIR" ]; then
      mv "$CACHE_DIR/$EXTRACTED_DIR"/* "$DIST_DIR" || true
      rm -rf "$CACHE_DIR/$EXTRACTED_DIR"
    else
      # fallback: try jar
      (cd "$CACHE_DIR" && jar xf "$TMP_ZIP") || true
    fi
  else
    # try jar
    (cd "$CACHE_DIR" && jar xf "$TMP_ZIP") || true
  fi
  rm -f "$TMP_ZIP"
fi

GRADLE_BIN="$DIST_DIR/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  # try find under DIST_DIR
  GRADLE_BIN=$(find "$DIST_DIR" -type f -path "*/bin/gradle" | head -n1 || true)
fi

if [ -z "$GRADLE_BIN" ] || [ ! -x "$GRADLE_BIN" ]; then
  echo "Gradle binary not found after extraction in $DIST_DIR" >&2
  exit 1
fi

exec "$GRADLE_BIN" "$@"
