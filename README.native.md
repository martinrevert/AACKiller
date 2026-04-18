
# Native amd64 build (GraalVM / native-image)

Make scripts executable
Before using helper scripts, make them executable:

```bash
chmod +x scripts/*.sh
```

This document explains how to build a native amd64 binary using GraalVM's `native-image`, targeting Java 25.

Prerequisites
- GraalVM distribution that supports Java 25. Get the official GraalVM distributions from the GraalVM website: https://www.graalvm.org/downloads/ (choose the Community or Enterprise build that matches Java 25).
- Set `GRAALVM_HOME` to the extracted GraalVM folder and add `$GRAALVM_HOME/bin` to `PATH`.
- Install the `native-image` component with the GraalVM `gu` tool: `$GRAALVM_HOME/bin/gu install native-image`.
- Gradle 9.1+ installed locally (or use the Gradle Wrapper provided/generated in the repo).

Important: use GraalVM Java 25 for native builds. Do not use Java versions older than 25.

Build steps

Quick build (recommended):

```bash
./gradlew assemble -x test
```

1. Build the JVM jar first:

```bash
./gradlew -x test bootJar
```

2. Recommended (Spring/AOT-aware): use the Gradle AOT/native tasks or Boot build-image:

```bash
# build image with native support via Paketo/Boot buildpacks (set BP_NATIVE_IMAGE=true)
BP_NATIVE_IMAGE=true ./gradlew -x test bootBuildImage
```

3. Alternative (manual `native-image`): if you prefer to run `native-image` directly, and you have prepared reflection/resource configurations, you can run:

```bash
$GRAALVM_HOME/bin/native-image --no-fallback -jar build/libs/aac2ac3-service-0.1.0-SNAPSHOT.jar -H:Name=aac2ac3-service
```

Running the native binary

```bash
INDEX_SCANPATH=/mnt/media ./build/aac2ac3-service --index.scanPath=/mnt/media --ffmpeg.threads=1
```

Notes & caveats
- Spring Boot: prefer Spring AOT and the Maven native profile; trying to native-image a fat spring jar directly usually requires extra reflection/resource configuration.
- Native images require explicit configuration for reflection, resource access and some dynamic features; consult GraalVM and Spring AOT docs for `reflect-config.json`, resource includes and initialization flags.
- Ensure `GRAALVM_HOME`/`PATH`/`JAVA_HOME` refer to a GraalVM Java 25 installation before building.

Paketo buildpacks (alternative)

Paketo buildpacks (via `spring-boot:build-image` or the `pack` CLI) can be used as an alternative to produce a native image artifact or native container image. This approach uses a buildpack-based builder and typically requires a container runtime (Docker) or a remote builder.

Example (Gradle + Spring Boot build-image, produces a Paketo image which can include a native binary):

```bash
# build an image using Paketo buildpacks + native-image
BP_NATIVE_IMAGE=true ./gradlew -x test -Dspring-boot.build-image.imageName=local/aac2ac3-native-amd64 bootBuildImage
```

You can extract the produced native binary from the image (if the buildpack produced it in a known path) with `docker create` + `docker cp`, or use the Paketo conventions to locate the binary. If you prefer not to use Docker locally, execute the Paketo flow on a host that has Docker available (CI or a build host).

`pack` CLI alternative

If you prefer the `pack` CLI instead of the Maven build-image, you can select a Paketo native-image capable builder and run:

```bash
pack build myrepo/aac2ac3-native-amd64 --builder paketobuildpacks/builder:base --env BP_NATIVE_IMAGE=true
```

Notes
- The Paketo flow is convenient when you want a reproducible, builder-driven native image, but it typically relies on a container builder (Docker) or an equivalent remote builder. Use GraalVM local/native-image when you want to strictly avoid container-based build steps.




