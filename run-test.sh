#!/bin/bash
# 测试器启动器 —— 在 single 启动器基础上开启 JMX（端口 1099，无认证，仅本机）
# 用法: ./run-test.sh [midlet.jar]
# 不带参数则启动空模拟器
#
# 开 JMX 后可用 jconsole / jmap -histo <pid> 实时观察堆，定位内存泄漏。
# 默认调起 tools/vgm-browser-test/VgmBrowserTest.jar（自动播放版）。

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

echo "== JMX 端口 1099（无认证，仅本机），可用："
echo "   jconsole      → 连本地进程 PID（无需端口）"
echo "   jmap -histo <PID>            # 对象直方图"
echo "   jmap -dump:format=b,file=heap.hprof <PID>  # 完整堆转储"

# jmap 自动发现本机进程（Attach API），不依赖 JMX 端口；
# jconsole 用 JMX 端口 1099 连接。两套互补。
exec "$JDK8/bin/java.exe" \
  -Xmx512M \
  -Djava.library.path="$KEM_DIR" \
  -Dfile.encoding=UTF-8 \
  -Dswt.autoScale=false \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=1099 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.host=127.0.0.1 \
  -jar "$KEM_DIR/KEmulator.jar" "$JAR"
