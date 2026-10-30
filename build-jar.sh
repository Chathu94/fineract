#!/usr/bin/env bash
# Builds the Fineract jar the same way the Jenkinsfile 'Build' stage does:
#   fineract-provider/./gradlew clean jar copyDeps
# Output: build/libs/*.jar plus runtime dependencies in build/libs/lib
#
# Usage: ./build-jar.sh            (JAVA_HOME is auto-detected; override by exporting it)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROVIDER_DIR="$ROOT_DIR/fineract-provider"

# Java 8 JDK: honour JAVA_HOME if it has javac, else try the Jenkins (Corretto) path, then OpenJDK 8.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    JAVA_HOME=""
    for candidate in /usr/lib/jvm/java-1.8.0-amazon-corretto \
                     /usr/lib/jvm/java-8-openjdk-amd64 \
                     /usr/lib/jvm/java-1.8.0-openjdk-amd64; do
        if [ -x "$candidate/bin/javac" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "$JAVA_HOME" ]; then
    echo "ERROR: no Java 8 JDK (javac) found. Install one, e.g. 'sudo apt install openjdk-8-jdk-headless'," >&2
    echo "       or export JAVA_HOME to a Java 8 JDK." >&2
    exit 1
fi
export JAVA_HOME

# build.gradle uses Gradle 3.x syntax and will not configure under newer Gradle.
WRAPPER_PROPS="$PROVIDER_DIR/gradle/wrapper/gradle-wrapper.properties"
if ! grep -q 'gradle-3\.0-bin\.zip' "$WRAPPER_PROPS"; then
    echo "ERROR: $WRAPPER_PROPS does not point to gradle-3.0." >&2
    echo "       Restore it with: git checkout fineract-provider/gradle/wrapper/gradle-wrapper.properties" >&2
    exit 1
fi

echo "Using JAVA_HOME=$JAVA_HOME"
cd "$PROVIDER_DIR"
./gradlew clean jar copyDeps

echo
echo "Build succeeded:"
ls -1 "$ROOT_DIR"/build/libs/*.jar
echo "Dependencies: $ROOT_DIR/build/libs/lib ($(ls -1 "$ROOT_DIR"/build/libs/lib | wc -l) jars)"
