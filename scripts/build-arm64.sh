#!/usr/bin/env bash
set -euo pipefail

# build-arm64.sh
# Consolidated ARM64 / Raspberry Pi build helper.
# Supports building a linux/arm64 Docker image (local buildx), running a remote build via SSH,
# and running a Paketo (buildpacks) native-image flow locally or remotely.

usage() {
  cat <<EOF
Usage: $0 --mode MODE [options]

Modes:
  registry        Build a linux/arm64 Docker image (uses buildx). Use --push to push.
  paketo          Run Paketo/Spring Boot build-image to produce a native artifact and runtime image.

Options:
  --mode MODE         registry|paketo
  --image IMAGE       Image name (default: myrepo/aac2ac3)
  --tag TAG           Image tag (default: arm64-latest)
  --remote-host HOST  If set, rsync repo to remote and run the same script there (SSH e.g. user@host)
  --remote-path PATH  Remote path to sync the repo (default: /home/pi/aackiller)
  --push              Push the final runtime image to the registry (if applicable)
  --workdir DIR       Temporary workdir for paketo native build (default: /tmp/aac2ac3-build)
  --local             Internal flag: indicates the script is running on the build host (used when invoked remotely)
  --help              Show this help

Examples:
  # Build & push a linux/arm64 Docker image locally
  ./scripts/build-arm64.sh --mode registry --image myrepo/aac2ac3 --tag arm64-latest --push

  # Run Paketo native-image build on a remote Raspberry Pi (single SSH entrypoint)
  ./scripts/build-arm64.sh --mode paketo --image myrepo/aac2ac3 --tag arm64-paketo --remote-host pi@raspberrypi.local --remote-path /home/pi/aackiller --push

EOF
  exit 1
}

MODE=""
IMAGE="myrepo/aac2ac3"
TAG="arm64-latest"
REMOTE_HOST=""
REMOTE_PATH="/home/pi/aackiller"
PUSH=false
WORKDIR="/tmp/aac2ac3-build"
LOCAL_RUN=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode) MODE="$2"; shift 2;;
    --image) IMAGE="$2"; shift 2;;
    --tag) TAG="$2"; shift 2;;
    --remote-host) REMOTE_HOST="$2"; shift 2;;
    --remote-path) REMOTE_PATH="$2"; shift 2;;
    --push) PUSH=true; shift;;
    --workdir) WORKDIR="$2"; shift 2;;
    --local) LOCAL_RUN=true; shift;;
    --help) usage;;
    *) echo "Unknown argument: $1"; usage;;
  esac
done

if [ -z "$MODE" ]; then
  echo "--mode is required" >&2
  usage
fi

# If remote host is specified and we're not already running on the remote, sync & invoke remotely
if [ -n "$REMOTE_HOST" ] && [ "$LOCAL_RUN" = false ]; then
  echo "Syncing repository to $REMOTE_HOST:$REMOTE_PATH"
  rsync -av --delete --exclude build --exclude .git . "$REMOTE_HOST:$REMOTE_PATH"
  echo "Invoking remote build on $REMOTE_HOST"
  ssh "$REMOTE_HOST" bash -lc "cd $REMOTE_PATH && ./scripts/build-arm64.sh --mode $MODE --image '$IMAGE' --tag '$TAG' --local --workdir '$WORKDIR' $( [ "$PUSH" = true ] && echo '--push' || echo '' )"
  exit $?
fi

ensure_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

create_swap_if_needed() {
  # Create temporary 2GB swap if memory is low (<8GB)
  MEM_KB=$(grep MemTotal /proc/meminfo | awk '{print $2}')
  MEM_MB=$((MEM_KB/1024))
  if [ "$MEM_MB" -lt 8000 ]; then
    echo "Low memory ($MEM_MB MB). Creating 2GB swapfile to help the native build."
    sudo fallocate -l 2G /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    SWAP_CREATED=1
  fi
}

cleanup_swap() {
  if [ "${SWAP_CREATED:-0}" = "1" ]; then
    echo "Removing temporary swap"
    sudo swapoff /swapfile || true
    sudo rm -f /swapfile || true
  fi
}

build_registry() {
  ensure_cmd docker
  echo "Building linux/arm64 Docker image: $IMAGE:$TAG"
  docker buildx create --use || true
  if [ "$PUSH" = true ]; then
    docker buildx build --platform linux/arm64 -t "$IMAGE:$TAG" --push .
  else
    docker buildx build --platform linux/arm64 -t "$IMAGE:$TAG" --load .
  fi
}

build_paketo_local() {
  ensure_cmd docker
  ensure_cmd gradle

  echo "Preparing workdir: $WORKDIR"
  mkdir -p "$WORKDIR"
  rsync -a --exclude target --exclude .git . "$WORKDIR/"
  pushd "$WORKDIR" >/dev/null

  # If Gradle wrapper present, build jar and copy runtime dependencies
  if [ -x "./gradlew" ]; then
    echo "Gradle wrapper found; running ./gradlew bootJar copyRuntimeDependencies (skip tests)"
    ./gradlew --no-daemon -x test bootJar copyRuntimeDependencies || true
  fi

  create_swap_if_needed

  echo "Running Paketo/Spring Boot build-image to produce native artifact"
  BP_NATIVE_IMAGE=true ./gradlew -x test -Dspring-boot.build-image.imageName=local/build-image bootBuildImage

  echo "Attempting to extract native binary from produced image 'local/build-image'"
  docker create --name __tmp_build local/build-image || true
  mkdir -p native-binary
  set +e
  docker cp __tmp_build:/workspace/build/native/native-run/native-image/native-binary/aac2ac3 native-binary/ 2>/dev/null
  if [ $? -ne 0 ]; then
    docker cp __tmp_build:/workspace/build/native/native-binary native-binary 2>/dev/null || true
  fi
  set -e
  docker rm -f __tmp_build || true

  if [ -x native-binary/aac2ac3 ]; then
    echo "Native binary extracted: native-binary/aac2ac3"
    cat > Dockerfile.final <<'EOF'
FROM linuxserver/ffmpeg:latest
COPY native-binary/aac2ac3 /usr/local/bin/aac2ac3
RUN chmod +x /usr/local/bin/aac2ac3
ENTRYPOINT ["/usr/local/bin/aac2ac3"]
EOF
    docker build -t "$IMAGE:$TAG" -f Dockerfile.final .
    if [ "$PUSH" = true ]; then
      if [ -n "${DOCKER_USER:-}" ]; then
        echo "Pushing $IMAGE:$TAG"
        echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
        docker push "$IMAGE:$TAG"
      else
        echo "DOCKER_USER not set; skipping push. Set DOCKER_USER/DOCKER_PASS to push."
      fi
    fi
  else
    echo "Could not extract native binary automatically. Inspect the produced image 'local/build-image' manually."
  fi

  popd >/dev/null
  cleanup_swap
}

case "$MODE" in
  registry)
    build_registry
    ;;
  paketo)
    build_paketo_local
    ;;
  *)
    echo "Unknown mode: $MODE" >&2
    usage
    ;;
esac

echo "Done."
