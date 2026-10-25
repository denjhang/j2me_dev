#!/bin/bash
# 测试器启动器 —— 与 single 启动器参数完全一致（保证 KEmulator 能正常起）
# 用法: ./run-test.sh [midlet.jar]
# 默认调起 tools/vgm-browser-test/VgmBrowserTest.jar（自动播放版）
#
# 不开 JMX 端口（实测会让 KEmulator 启动失败）。
# jmap 用 Attach API 自动发现本机进程，不依赖 JMX 端口：
#   jmap -histo <PID>                          # 对象直方图
#   jmap -histo:live <PID>                     # 仅存活对象（触发一次 GC）
#   jmap -dump:format=b,file=heap.hprof <PID>  # 完整堆转储
#   jmap -heap <PID>                           # 堆摘要（代/已用/上限）
# 探针日志走 System.out，在本进程 stdout 里。

ROOT="$(cd "$(dirname "$0")" && pwd)"
KEM_DIR="$ROOT/emulators/kemnnx64"
JDK8="$ROOT/tools/jdk8"
JAR="${1:-$ROOT/tools/vgm-browser-test/VgmBrowserTest.jar}"

if [ ! -x "$JDK8/bin/java.exe" ]; then
  echo "缺少 JDK 8: $JDK8" >&2
  exit 1
fi
if [ ! -f "$KEM_DIR/KEmulator.jar" ]; then
  echo "缺少 KEmulator.jar: $KEM_DIR" >&2
  exit 1
fi
if [ ! -f "$JAR" ]; then
  echo "缺少 MIDlet jar: $JAR" >&2
  exit 1
fi

cd "$KEM_DIR" || exit 1

echo "== 启动测试器: $JAR"
echo "== 探针日志在本进程 stdout；抓堆用 jmap（PID 见 tasklist java.exe）："
echo "   jmap -histo <PID>            # 对象直方图"
echo "   jmap -heap <PID>             # 堆摘要"
echo "   jmap -dump:format=b,file=heap.hprof <PID>  # 完整堆转储"

exec "$JDK8/bin/java.exe" \
  -Xmx512M \
  -Djava.library.path="$KEM_DIR" \
  -Dfile.encoding=UTF-8 \
  -Dswt.autoScale=false \
  -jar "$KEM_DIR/KEmulator.jar" "$JAR"
