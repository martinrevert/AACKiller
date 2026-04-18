# ARM64 / Raspberry Pi build & deployment

Make scripts executable
Before running the consolidated ARM64 helper, make scripts executable:

```bash
chmod +x scripts/*.sh
```

This consolidated guide covers building and publishing `linux/arm64` runtime artifacts for the project. It includes two main flows:

- Docker image build (multi-arch via Docker Buildx) — for running the JVM-based jar inside a container.
- Paketo / buildpacks native flow — produce a native executable (via GraalVM/native-image) and package it into a runtime image (recommended for constrained devices).

Quick build (recommended):

```bash
./gradlew assemble -x test
```

Single helper script

Use the consolidated helper script `scripts/build-arm64.sh` for all ARM64/Pi workflows. The script supports:

- `--mode registry` — build a linux/arm64 Docker image locally with `docker buildx`.
- `--mode paketo` — run a Paketo/Spring Boot `build-image` native flow (produces an image from which a native binary can be extracted and packaged into a runtime image).
- `--remote-host user@host` — if provided, the script rsyncs the repository to the remote host and runs the same script on that host (single SSH entrypoint).

Examples

Local Docker (build & push):

```bash
./scripts/build-arm64.sh --mode registry --image myrepo/aac2ac3 --tag arm64-latest --push
```

Run Paketo native build on a remote Raspberry Pi (single SSH step):

```bash
./scripts/build-arm64.sh --mode paketo --image myrepo/aac2ac3 --tag arm64-paketo --remote-host pi@raspberrypi.local --remote-path /home/pi/aackiller --push
```

Direct on-device (run on the Pi):

```bash
# on Pi
./scripts/build-arm64.sh --mode paketo --image myrepo/aac2ac3 --tag arm64-paketo --push
```

Notes

- The paketo/native flow requires Docker on the host (the buildpack stages run using container tooling and may need a lot of RAM/disk). The helper will create a small swap file when RAM is low to help the native build and will cleanup after itself.
- For paketo flows, if extraction of the native binary fails automatically, inspect the image that the buildpack produced and extract the binary manually using `docker create` + `docker cp`.
- To push images in the script, set `DOCKER_USER` and `DOCKER_PASS` environment variables on the host; the script will attempt to log in when `--push` is used.
- The helper uses a single SSH entrypoint (`--remote-host`) so you don't need separate scripts or commands for Pi vs generic ARM64 remote builds.

Where this replaces earlier files

- This file consolidates the previous `README.docker-arm64.md` and `README.pi.md` content and keeps the important usage notes in one place.
