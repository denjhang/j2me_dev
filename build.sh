#!/bin/bash
# 一键构建 J2ME MIDlet：javac(JDK8, target 1.4) -> jar -> 补齐 JAD 的 size/URL
#
# 用法:
#   ./build.sh              # 构建 games/brick
#   ./build.sh games/brick  # 指定工程目录（需含 src/ 和唯一的 *.jad 模板）
#
# 关键点:
#   1. 必须用 JDK 8 的 javac —— javac 26 最低只支持 --release 8，
#      而 MIDP 2.0 需要 class 版本 48（Java 1.4）。
#   2. -bootclasspath 只指向 CLDC + MIDP，防止误用桌面 Java SE 的类
#      （比如 String.format、泛型集合），否则真机会 NoClassDefFoundError。
#   3. Git Bash 的 /e/... 路径 Windows 版 javac 不认，全部转成盘符路径。

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJ="${1:-$ROOT/games/brick}"
PROJ="$(cd "$PROJ" && pwd)"

JDK8="$ROOT/tools/jdk8"
LIB="$ROOT/references/midplay/MIDPlay-main/lib"
# MMAPI(JSR-135) 的 MIDIControl 不在 midp_2.0.jar 里，用 KEmulator 自带的存根补齐
MMAPI="$ROOT/emulators/kemnnx64/uei/jsr135.jar"
JAVAC="$JDK8/bin/javac.exe"
JAR="$JDK8/bin/jar.exe"

[ -x "$JAVAC" ] || { echo "缺少 JDK 8: $JAVAC" >&2; exit 1; }
[ -f "$LIB/cldc_1.1.jar" ] || { echo "缺少 CLDC 库: $LIB/cldc_1.1.jar" >&2; exit 1; }
[ -f "$LIB/midp_2.0.jar" ] || { echo "缺少 MIDP 库: $LIB/midp_2.0.jar" >&2; exit 1; }
[ -f "$MMAPI" ] || { echo "缺少 MMAPI 库: $MMAPI" >&2; exit 1; }

# javac 是 Windows 程序，路径必须转成盘符形式
win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
LIB_W="$(win "$LIB")"
MMAPI_W="$(win "$MMAPI")"
BOOTCP="$LIB_W\\cldc_1.1.jar;$LIB_W\\midp_2.0.jar;$MMAPI_W"

# 工程里应只有一个 .jad 模板，产物名跟它走（Brick.jad -> Brick.jar）
cd "$PROJ"
JAD_TMPL=$(ls *.jad 2>/dev/null | head -1)
[ -n "$JAD_TMPL" ] || { echo "工程里没有 .jad 模板" >&2; exit 1; }
JAD="$PROJ/$JAD_TMPL"
BASE="${JAD_TMPL%.jad}"
JAR_NAME="$BASE.jar"

echo "== 工程: $PROJ"
echo "== 产物: $JAR_NAME / $JAD_TMPL"

# --- 1. 清理并编译 ---
rm -rf classes "$JAR_NAME" META-INF
mkdir -p classes

find src -name '*.java' > .sources
[ -s .sources ] || { echo "src/ 下没有 .java 文件" >&2; exit 1; }
echo "== 编译 $(wc -l < .sources) 个源文件"

# 英文 locale：中文警告经管道会被当成二进制，干扰过滤
# javac 的退出码要单独判断——若只按"有没有生成 class"判断，编译器报错但产出部分
# class 时会被误认为成功，打出一个不完整的 jar。
JAVAC_LOG="$(mktemp)"
"$JAVAC" -J-Duser.language=en -J-Duser.country=US \
  -source 1.4 -target 1.4 -encoding UTF-8 \
  -bootclasspath "$BOOTCP" \
  -d classes \
  @.sources > "$JAVAC_LOG" 2>&1
JAVAC_RC=$?
grep -v -E 'obsolete|Xlint|^[0-9]+ warnings?$' "$JAVAC_LOG" || true
rm -f "$JAVAC_LOG"
[ "$JAVAC_RC" -eq 0 ] || { echo "编译失败 (javac exit $JAVAC_RC)" >&2; exit 1; }

CLASS_COUNT=$(find classes -name '*.class' | wc -l)
[ "$CLASS_COUNT" -gt 0 ] || { echo "编译失败：没有生成 class" >&2; exit 1; }
echo "== 生成 $CLASS_COUNT 个 class"

# --- 2. 打包（manifest 里也带 MIDlet 属性，部分真机认这个） ---
cat > MANIFEST.MF <<EOF
Manifest-Version: 1.0
MIDlet-Name: $BASE
MIDlet-Version: 1.0.0
MIDlet-Vendor: Denjhang
MIDlet-1: $BASE, , $(sed -n 's/^MIDlet-1: [^,]*, *, *//p' "$JAD")
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1

EOF
"$JAR" cfm "$JAR_NAME" MANIFEST.MF -C classes .
rm -f MANIFEST.MF .sources

# --- 3. 补 JAD 的 MIDlet-Jar-Size / URL ---
SIZE=$(stat -c %s "$JAR_NAME")
grep -v -E '^MIDlet-Jar-(Size|URL):' "$JAD" > "$JAD.tmp"
echo "MIDlet-Jar-Size: $SIZE" >> "$JAD.tmp"
echo "MIDlet-Jar-URL: $JAR_NAME" >> "$JAD.tmp"
mv "$JAD.tmp" "$JAD"

echo "== 产物:"
ls -lh "$JAR_NAME" "$JAD_TMPL" | sed 's/^/   /'
echo
echo "== 运行: ./run-kemulator.sh \"$PROJ/$JAR_NAME\""
