#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

APP_NAME="MaraFlow"
APP_VERSION="1.0.0"
BUNDLE_ID="cz.maramat.maraflow"
MAIN_CLASS="cz.maramat.Main"
JAR_NAME="Maraflow-1.0-SNAPSHOT.jar"
ICON_PATH="$SCRIPT_DIR/src/main/resources/icons/MaraFlow.icns"
DIST_DIR="$SCRIPT_DIR/dist/mac"
INPUT_DIR="$SCRIPT_DIR/target/jpackage-input"

MVN_BIN="$(command -v mvn || true)"
if [ -z "$MVN_BIN" ]; then
    for candidate in \
        "/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn" \
        "/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn" \
        "$HOME/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn"; do
        if [ -x "$candidate" ]; then
            MVN_BIN="$candidate"
            break
        fi
    done
fi
if [ -z "$MVN_BIN" ]; then
    echo "Error: Maven (mvn) not found on PATH or in IntelliJ IDEA." >&2
    exit 1
fi

echo "==> 1/3: Building shaded JAR with Maven..."
"$MVN_BIN" clean package -DskipTests

rm -rf "$INPUT_DIR"
mkdir -p "$INPUT_DIR"
cp "$SCRIPT_DIR/target/$JAR_NAME" "$INPUT_DIR/"

echo "==> 2/3: Packaging $APP_NAME.app with jpackage..."
rm -rf "$DIST_DIR/$APP_NAME.app"
mkdir -p "$DIST_DIR"

jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --vendor "Maramat" \
  --mac-package-name "$APP_NAME" \
  --mac-package-identifier "$BUNDLE_ID" \
  --input "$INPUT_DIR" \
  --main-jar "$JAR_NAME" \
  --main-class "$MAIN_CLASS" \
  --icon "$ICON_PATH" \
  --dest "$DIST_DIR"

if command -v codesign >/dev/null 2>&1; then
    echo "==> 3/3: Ad-hoc signing $APP_NAME.app..."
    codesign --force --deep --sign - "$DIST_DIR/$APP_NAME.app"
fi

if [[ "${1:-}" == "--install" ]]; then
    echo "==> Installing $APP_NAME.app to /Applications..."
    rm -rf "/Applications/$APP_NAME.app"
    cp -R "$DIST_DIR/$APP_NAME.app" "/Applications/$APP_NAME.app"
    echo "Installed: /Applications/$APP_NAME.app"
fi

echo "=========================================================="
echo "SUCCESS! macOS app bundle created:"
echo "  $DIST_DIR/$APP_NAME.app"
echo "Tip: Run '$0 --install' to copy to /Applications so you can pin it to the Dock."
echo "=========================================================="
