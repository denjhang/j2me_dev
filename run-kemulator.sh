#!/bin/bash
# KEmulator nnmod 启动器 —— 强制使用工作区自带的 Temurin JDK 8 (x64)
# 用法: ./run-kemulator.sh [midlet.jar]
# 不带参数则启动空模拟器（文件→打开 MIDlet）

ROOT="$(cd "$(dirname "$0")" && pwd)"
KEM_DIR="$ROOT/emulators/kemnnx64"
JDK8="$ROOT/tools/jdk8"

if [ ! -x "$JDK8/bin/java.exe" ]; then
  echo "缺少 JDK 8: $JDK8" >&2
  exit 1
fi
if [ ! -f "$KEM_DIR/KEmulator.jar" ]; then
  echo "缺少 KEmulator.jar: $KEM_DIR" >&2
  exit 1
fi

cd "$KEM_DIR" || exit 1

exec "$JDK8/bin/java.exe" \
  -Xmx512M \
  -Djava.library.path="$KEM_DIR" \
  -Dfile.encoding=UTF-8 \
  -Dswt.autoScale=false \
  -jar "$KEM_DIR/KEmulator.jar" "$@"
