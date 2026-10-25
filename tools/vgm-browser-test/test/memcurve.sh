#!/bin/bash
# P1-验证：文件模式连播期间的堆曲线采样。
# 用法: memcurve.sh <采样次数> <间隔秒> [标签]
# 原理: 控制台模式启动 KEmulator（stdout 重定向到日志），
#       用 jdk8 的 jmap（Attach API，不用 JMX——JMX 会让 KEmulator 起不来）定时抓直方图。
set -u
dir="$(cd "$(dirname "$0")/.." && pwd)"          # tools/vgm-browser-test
root="$(cd "$dir/../.." && pwd)"                  # 仓库根
jdk8="$root/tools/jdk8/bin"
emu="$root/emulators/kemnnx64"
N="${1:-16}"; GAP="${2:-20}"; TAG="${3:-file}"
out="$dir/test-out/memcurve-$TAG"
mkdir -p "$dir/test-out"

"$jdk8/java.exe" -Djava.library.path="$emu" -Xmx512M -Dfile.encoding=UTF-8 \
    -jar "$emu/KEmulator.jar" -jar "$dir/VgmBrowserTest.jar" \
    > "$out.log" 2>&1 &
kpid=$!
# MSYS 的 $! 不是 Windows PID，jmap 认不出——用 jps 按命令行解析真实 PID
winpid=""
for try in 1 2 3 4 5; do
    sleep 3
    winpid=$("$jdk8/jps.exe" -l 2>/dev/null | grep "KEmulator.jar" | awk '{print $1}' | head -1)
    [ -n "$winpid" ] && break
done
if [ -z "$winpid" ]; then echo "!! jps 找不到 KEmulator，放弃"; kill "$kpid" 2>/dev/null; exit 1; fi
echo "== KEmulator msys=$kpid winpid=$winpid 采样 $N 次 x ${GAP}s -> $out-*.hist"
sleep 15   # 等首曲转换+起播

snap() {
    local i=$1
    "$jdk8/jmap.exe" -histo "$winpid" 2>>"$out.err" | head -40 > "$out-$i.hist"
    "$jdk8/jmap.exe" -heap "$winpid" 2>>"$out.err" | grep -E "old|young|used|capacity" | head -12 > "$out-$i.heap"
    echo "-- snapshot $i $(date +%H:%M:%S)"
    grep -E "num  |Midi|ShortMessage|Sequence|Parser|long\[\]|byte\[\]|Vector" "$out-$i.hist" | head -14
    cat "$out-$i.heap"
    grep -E "^\[FILE\]|^\[TEST\] \(" "$out.log" | tail -3
}

snap 0
for i in $(seq 1 "$N"); do
    sleep "$GAP"
    if ! kill -0 "$kpid" 2>/dev/null; then echo "== KEmulator 退出，停止采样"; break; fi
    snap "$i"
done
kill "$kpid" 2>/dev/null
echo "== 完成。日志: $out.log"
