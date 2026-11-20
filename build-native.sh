#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
F407_DIR="${F407_DIR:-/Users/matoussmekal/CLionProjects/F407PLC}"

if [ ! -f "$F407_DIR/Core/Src/mara_vm.c" ]; then
    echo "Error: F407PLC mara_vm.c not found at $F407_DIR/Core/Src/mara_vm.c" >&2
    exit 1
fi

JDK_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home)}"
OS_NAME="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH_NAME="$(uname -m)"
if [ "$ARCH_NAME" = "arm64" ]; then ARCH_NAME="aarch64"; fi

if [ "$OS_NAME" = "darwin" ]; then
    LIB_NAME="libmaravm.dylib"
    PLAT_INC="$JDK_HOME/include/darwin"
    SHARED_FLAGS="-dynamiclib"
else
    LIB_NAME="libmaravm.so"
    PLAT_INC="$JDK_HOME/include/linux"
    SHARED_FLAGS="-shared -fPIC"
fi

OUT_DIR="$SCRIPT_DIR/src/main/resources/native/${OS_NAME}-${ARCH_NAME}"
mkdir -p "$OUT_DIR"

cc -O2 -Wall -Wextra $SHARED_FLAGS \
    -I"$JDK_HOME/include" \
    -I"$PLAT_INC" \
    -I"$F407_DIR/Core/Inc" \
    "$F407_DIR/Core/Src/mara_vm.c" \
    "$SCRIPT_DIR/src/main/native/mara_vm_jni.c" \
    -lm \
    -o "$OUT_DIR/$LIB_NAME"

echo "Built native C MaraVM library: $OUT_DIR/$LIB_NAME"
