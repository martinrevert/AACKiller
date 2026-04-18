# JVM build & run (local/server)

Make scripts executable
Before running shipped helper scripts make them executable:

```bash
chmod +x scripts/*.sh
```

This document explains how to build and run the application on the JVM.

Prerequisites
- Java 25 installed and on `PATH` (or use an SDK manager to select Java 25).
- `ffmpeg` and `ffprobe` available on `PATH` (these are invoked by the application).
- Gradle 9.1+ available to build the project (or use the Gradle Wrapper provided/generated in the repo).

Build (recommended)

```bash
./gradlew assemble -x test
```

Run (recommended)

Use the helper script `scripts/run-jvm.sh` (make executable first):

```bash
chmod +x scripts/run-jvm.sh
scripts/run-jvm.sh
```

Environment variables recognized by the script (or via `-D` system props):
- `INDEX_SCANPATH` / `-Dindex.scanPath` — path to scan for MKV files (default `samples`).
- `FFMPEG_THREADS` / `-Dffmpeg.threads` — threads to request per ffmpeg process.
- `WORKER_MAXCONCURRENCY` / `-Dworker.maxConcurrency` — number of simultaneous conversions.
- `JAVA_OPTS` — extra Java options (heap, GC tuning, etc.).

Direct `java -jar` example:

```bash
# set scan path and ffmpeg threads
INDEX_SCANPATH=/mnt/media FFMPEG_THREADS=1 WORKER_MAXCONCURRENCY=2 \
java -Dindex.scanPath=/mnt/media -Dffmpeg.threads=1 -Dworker.maxConcurrency=2 -jar build/libs/aac2ac3-service-0.1.0-SNAPSHOT.jar
```

Notes
- The application uses a file-backed H2 database by default (configured in `src/main/resources/application.properties`). The DB file is created under `data/` by default.
- Logs are written to `logs/` (per-job stdout/stderr).
- Ensure the user running the process has write permission to the data and logs folders and read access to the media mount.