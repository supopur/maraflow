#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Detect Java 21 JDK for compiling bytecode matching the bundled Java 21 LTS runtime
if [ -d "/usr/lib/jvm/temurin-21-jdk-amd64" ]; then
    export JAVA_HOME="/usr/lib/jvm/temurin-21-jdk-amd64"
elif [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
else
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
fi
export PATH="$JAVA_HOME/bin:$PATH"

MVN_BIN="/home/mat/.local/share/JetBrains/Toolbox/apps/intellij-idea/plugins/maven/lib/maven3/bin/mvn"
if [ ! -x "$MVN_BIN" ]; then
    MVN_BIN="mvn"
fi

DIST_DIR="$SCRIPT_DIR/dist/Maraflow-Windows-x64"
WIN_JMODS_DIR="$SCRIPT_DIR/tools/win-jdk21/jdk-21.0.13+9/jmods"

echo "==> 1/4: Building shaded Fat JAR with Maven..."
"$MVN_BIN" clean package -DskipTests

echo "==> 2/4: Building native Windows Maraflow.exe..."
"$SCRIPT_DIR/tools/launch4j/launch4jc" "$SCRIPT_DIR/launch4j.xml"

echo "==> 3/4: Creating stripped, minimal Windows JRE using jlink..."
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

# Only link the exact modules needed by Maraflow: java.base, java.desktop, java.logging
/usr/bin/jlink \
  --module-path "$WIN_JMODS_DIR" \
  --add-modules java.base,java.desktop,java.logging \
  --strip-debug \
  --no-man-pages \
  --no-header-files \
  --compress=zip-6 \
  --output "$DIST_DIR/jre"

# Remove non-essential files from the bundled JRE to save space
rm -f "$DIST_DIR/jre/bin/keytool.exe"

echo "==> 4/4: Assembling self-contained distribution..."
cp "$SCRIPT_DIR/target/Maraflow.exe" "$DIST_DIR/"

# Package into a release zip using 7z
cd "$SCRIPT_DIR/dist"
rm -f Maraflow-Windows-x64.zip
7z a -tzip -mx=9 Maraflow-Windows-x64.zip Maraflow-Windows-x64 > /dev/null

echo "=========================================================="
echo "SUCCESS! Self-contained Windows package created:"
echo "Directory: $DIST_DIR"
du -sh "$DIST_DIR"
echo "Release ZIP:"
ls -lh "$SCRIPT_DIR/dist/Maraflow-Windows-x64.zip"
echo "=========================================================="
