# ArkVcs task environment
# Pre-fetches every dependency for BOTH Gradle build surfaces (root server
# project + nested standalone ArkClient project) at image-build time, so
# the run phase works fully offline.
#
#   1. /opt/gradle      -> shared GRADLE_USER_HOME (all Maven Central +
#                          plugin + Kotlin compiler artifacts)
#   2. root project's   -> ~/.gradle (wrapper dist + local caches; the root
#   .gradle cache           wrapper writes here, not to GRADLE_USER_HOME)
#   3. ArkClient's      -> same pattern for the nested project
#
# The root project's test suite is NOT run at build time: its two RPC
# verification tests require live services. compileTestKotlin is enough to
# pull test-implementation deps; the verifier runs tests at check time.

FROM eclipse-temurin:24-jdk

ENV GRADLE_USER_HOME=/opt/gradle

WORKDIR /workspace

COPY . /workspace

# --- Surface 1: root project (server) ---
# Invoke via `bash gradlew` (not `./gradlew`) so the wrapper's exec bit is
# irrelevant — ArkClient/gradlew ships as 444 in some checkouts.
RUN cd /workspace && bash gradlew --no-daemon \
        dependencies --configuration compileClasspath \
        dependencies --configuration runtimeClasspath \
        dependencies --configuration testCompileClasspath \
        dependencies --configuration testRuntimeClasspath \
        compileKotlin compileTestKotlin

# --- Surface 2: nested standalone project (ArkClient) ---
RUN cd /workspace/ArkClient && bash gradlew --no-daemon \
        dependencies --configuration compileClasspath \
        dependencies --configuration runtimeClasspath \
        dependencies --configuration testCompileClasspath \
        dependencies --configuration testRuntimeClasspath \
        compileKotlin compileTestKotlin

# --- Warm the wrapper-local caches ---
# The root wrapper resolves its distribution to ~/.gradle/wrapper/dists,
# NOT to GRADLE_USER_HOME. Same for ArkClient. Both wrapper jars are
# present in the copy above, so this materializes the dist into the image.
RUN cd /workspace && bash gradlew --version \
    && cd /workspace/ArkClient && bash gradlew --version

WORKDIR /workspace
