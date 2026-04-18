Building this project with Gradle
================================

Quick steps
-----------

1. Install Gradle 9.1.0 (or use the Gradle Wrapper included/generated in the project).
2. (Optional) Generate the wrapper in the project root:

  ```bash
  gradle wrapper --gradle-version 9.1.0
  ```

3. Build the project:

  ```bash
  ./gradlew build    # if you created the wrapper
  # or
  gradle build
  ```

Notes and compatibility
-----------------------
- The root `build.gradle` uses pure Gradle mechanisms (Gradle platform BOM, toolchains, and tasks).
- Java toolchain is configured for Java 25; ensure you have a JDK that supports Java 25.
- For AOT or native images, use Gradle tasks:

  ```bash
  # run AOT (if configured) and build the jar
  ./gradlew processAot bootJar

  # build container image using Boot's buildpack integration
  BP_NATIVE_IMAGE=true ./gradlew -x test bootBuildImage
  ```

AOT / native builds
--------------------
- Gradle support for Spring AOT may require adding
  the Spring AOT Gradle plugin (experimental) or using the Spring Boot AOT integration. If you
  need native image support or AOT processing, I can add the relevant Gradle plugin and
  tasks once you confirm whether you want native images (GraalVM) or just the AOT-generated
  classes/resources.

CI and wrapper
--------------
- I did not add the Gradle wrapper files to the repo to avoid large binary changes. Run
  `gradle wrapper --gradle-version 8.6` to generate `gradlew`, `gradlew.bat`, and the wrapper
  directory, then commit them to your repo for consistent CI builds.
