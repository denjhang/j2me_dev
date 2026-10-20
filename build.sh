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
# JSR-75 文件系统：仅文件浏览器用，真机支持度不一，不影响游戏
FILEAPI="$ROOT/emulators/kemnnx64/uei/jsr75_file.jar"
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
FILEAPI_W="$(win "$FILEAPI")"
BOOTCP="$LIB_W\\cldc_1.1.jar;$LIB_W\\midp_2.0.jar;$MMAPI_W"
if [ -f "$FILEAPI" ]; then
  BOOTCP="$BOOTCP;$FILEAPI_W"
fi
# Nokia UI API 编译期 stub（真机由固件提供；不打包进 jar）
NOKIAUI="$ROOT/tools/nokia-ui-api/nokia-ui-api.jar"
if [ -f "$NOKIAUI" ]; then
  BOOTCP="$BOOTCP;$(win "$NOKIAUI")"
fi

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

# --- 1b. 版本同步：JAD 模板为准，强制刷 manifest（真机对同名属性逐项比对） ---
JAD_VER=$(grep -m1 '^MIDlet-Version:' "$JAD" | tr -d '
' | cut -d' ' -f2)
JAD_NAME=$(grep -m1 '^MIDlet-Name:' "$JAD" | tr -d '
' | cut -d' ' -f2)
if [ -n "$JAD_VER" ] && [ -f manifest.mf ]; then
  sed -i "s/^MIDlet-Version:.*/MIDlet-Version: $JAD_VER/" manifest.mf
  sed -i "s/^MIDlet-Name:.*/MIDlet-Name: $JAD_NAME/" manifest.mf
fi

# --- 2. 打包（manifest.mf 独立文件，含诺基亚兼容字段） ---
if [ -f manifest.mf ]; then
  "$JAR" cfm "$JAR_NAME" manifest.mf -C classes . -C res .
else
  echo "警告: 缺少 manifest.mf，使用内联 manifest" >&2
  printf 'Manifest-Version: 1.0\nMIDlet-Name: %s\nMIDlet-Version: 1.0.0\nMIDlet-Vendor: Denjhang\nMIDlet-1: %s,,com.denjhang.vgmbrowser.BrowserMIDlet\nMicroEdition-Profile: MIDP-2.0\nMicroEdition-Configuration: CLDC-1.1\n\n' "$BASE" "$BASE" > MANIFEST.MF
  "$JAR" cfm "$JAR_NAME" MANIFEST.MF -C classes .
  rm -f MANIFEST.MF
fi
rm -f .sources

# --- 2b. ProGuard 预校验 + 优化（诺基亚真机安装必需） ---
PROGUARD="$ROOT/tools/proguard/proguard.jar"
PG_CONFIG="$PROJ/proguard.pro"
if [ -f "$PROGUARD" ] && [ -f "$PG_CONFIG" ]; then
  echo "== ProGuard 预校验"
  LIB_W2="$(win "$LIB")"
  MMAPI_W2="$(win "$MMAPI")"
  "$JDK8/bin/java.exe" -cp "$(win "$PROGUARD")" proguard.ProGuard \
    -injars "$JAR_NAME" \
    -outjars "$JAR_NAME-pg.jar" \
    -libraryjars "$LIB_W2\\cldc_1.1.jar;$LIB_W2\\midp_2.0.jar;$MMAPI_W2;$FILEAPI_W" \
    @"$PG_CONFIG" 2>&1 | tail -5
  if [ -f "$JAR_NAME-pg.jar" ]; then
    mv "$JAR_NAME-pg.jar" "$JAR_NAME"
    echo "== ProGuard 完成: $(stat -c %s "$JAR_NAME") bytes"
  else
    echo "警告: ProGuard 失败，使用未校验的 JAR" >&2
  fi
else
  echo "提示: 无 ProGuard，跳过预校验（诺基亚 S40 可能无法安装）" >&2
fi

# --- 3. 补 JAD 的 MIDlet-Jar-Size / URL ---
SIZE=$(stat -c %s "$JAR_NAME")
grep -v -E '^MIDlet-Jar-(Size|URL):' "$JAD" > "$JAD.tmp"
echo "MIDlet-Jar-Size: $SIZE" >> "$JAD.tmp"
echo "MIDlet-Jar-URL: $JAR_NAME" >> "$JAD.tmp"
# JAD 统一 CRLF（部分诺基亚固件对 LF 解析异常）
sed -i 's/$/
/' "$JAD.tmp" 2>/dev/null || unix2dos "$JAD.tmp" 2>/dev/null
mv "$JAD.tmp" "$JAD"

# --- 4. 一致性自检：jar/jad 属性逐项比对，不一致直接失败 ---
SIZE=$(stat -c %s "$JAR_NAME")
MF_VER=$(unzip -p "$JAR_NAME" META-INF/MANIFEST.MF | grep -m1 '^MIDlet-Version:' | tr -d '
' | cut -d' ' -f2)
MF_NAME=$(unzip -p "$JAR_NAME" META-INF/MANIFEST.MF | grep -m1 '^MIDlet-Name:' | tr -d '
' | cut -d' ' -f2)
JAD_SZ=$(grep -m1 '^MIDlet-Jar-Size:' "$JAD" | tr -d '
' | cut -d' ' -f2)
FAIL=0
[ "$MF_VER" = "$JAD_VER" ] || { echo "错误: manifest 版本 $MF_VER != jad $JAD_VER" >&2; FAIL=1; }
[ "$MF_NAME" = "$JAD_NAME" ] || { echo "错误: manifest 名字 $MF_NAME != jad $JAD_NAME" >&2; FAIL=1; }
[ "$JAD_SZ" = "$SIZE" ] || { echo "错误: jad 大小 $JAD_SZ != jar $SIZE" >&2; FAIL=1; }
[ "$FAIL" = "0" ] && echo "== 一致性自检通过 (ver=$MF_VER size=$SIZE)" || exit 1

echo "== 产物:"
ls -lh "$JAR_NAME" "$JAD_TMPL" | sed 's/^/   /'
echo
echo "== 运行: ./run-kemulator.sh \"$PROJ/$JAR_NAME\""
