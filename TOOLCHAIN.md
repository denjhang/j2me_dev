# TOOLCHAIN — 编译链与验证闭环

本文记录 **2026-10-19 实测打通**的完整链路，含每个坑的原因和结论。

---

## 一、链路总览

```
源码 (.java)
   ↓  tools/jdk8/bin/javac.exe  -source 1.4 -target 1.4
   ↓  -bootclasspath = cldc_1.1.jar + midp_2.0.jar + jsr135.jar
字节码 (major version 48 = Java 1.4)
   ↓  tools/jdk8/bin/jar.exe
Brick.jar + Brick.jad
   ↓  KEmulator nnmod 2.22 x64（Java 8 运行）
模拟器窗口 ← Windows PrintWindow 截图
```

没有真机参与，整条链路在 PC 上闭环。

---

## 二、为什么必须是 JDK 8

本机默认 Java 26（Temurin）。实测：

```
$ javac --release 8 T.java   → 通过（有 obsolete 警告）
$ javac --release 7 T.java   → 错误: 不支持发行版本 7
```

`javac 26` 最低只支持 release 8，而 J2ME 的标准目标是 **class 版本 48（Java 1.4）**。
MIDP 2.0 / CLDC 1.1 设备普遍只认 1.3–1.4 级别的 class，所以编译必须用 JDK 8。

### 安装方式（免安装、无广告）

不用 Oracle 的 JRE/JDK，也不用安装器。Adoptium（Eclipse 基金会）提供的
Temurin 8 是 OpenJDK 的官方构建，完全免费，直接下 zip 解压即可：

```bash
curl -L -o jdk8.zip \
  "https://api.adoptium.net/v3/binary/latest/8/ga/windows/x64/jdk/hotspot/normal/eclipse"
unzip -q jdk8.zip -d tools/
mv tools/jdk8u504-b01 tools/jdk8
tools/jdk8/bin/java.exe -version   # openjdk version "1.8.0_504" (Temurin)
```

不需要管理员权限，不写注册表，不装服务。要卸载直接删目录。

> 也可以装 `jdk` 而不只是 `jre` —— J2ME 编译需要 `javac`，JRE 里没有。

---

## 三、编译类库：为什么不能直接用 KEmulator.jar

`emulators/kemnnx64/KEmulator.jar` 内部含 269 个 `javax.microedition.*` 类，
看起来可以当 API 用，但它**没有 `java.lang` 等 CLDC 基础类**。

实测 `-bootclasspath KEmulator.jar` 的结果：

```
致命错误: 在类路径或引导类路径中找不到程序包 java.lang
```

正确做法是用成套的 CLDC + MIDP 类库：

| 文件 | 来源 | 用途 |
|---|---|---|
| `cldc_1.1.jar` | `references/midplay/MIDPlay-main/lib/` | `java.lang`、`java.io`、`java.util` 等 |
| `midp_2.0.jar` | 同上 | `javax.microedition.lcdui`、`.rms`、`.io`、`ToneControl`、`VolumeControl` |
| `jsr135.jar` | `emulators/kemnnx64/uei/` | MMAPI：`Manager`、`Player`、**`MIDIControl`** |

注意 `midp_2.0.jar` 里只有 `ToneControl` 和 `VolumeControl`，**没有 `MIDIControl`**。
要播 MIDI 必须把 `jsr135.jar` 加进 bootclasspath，否则编译期就找不到。

### 为什么要用 bootclasspath 而不是 classpath

用 `-classpath` 也能编译通过，但那是把 J2ME 库当作**附加库**，
完整 Java SE 的类仍然可见。这样很容易写出 `String.format()`、`ArrayList<Foo>`
这类真机不存在的东西，直到部署到手机上才炸。

`-bootclasspath` 会把 Java SE 的标准库整个替换掉，编译器只能在 CLDC/MIDP 的
范围内找类 —— 写错立刻编译报错。这是唯一能保证“模拟器能跑 = 真机也能跑”的配置。

---

## 四、Windows / Git Bash 的路径坑

`javac.exe` 和 `jar.exe` 是 Windows 原生程序，**不认 Git Bash 的 `/e/...` 路径**：

```
javac: file not found: \e\working\...\BrickCanvas.java
```

而 `-bootclasspath` 是分号分隔的 Windows 路径。`build.sh` 里的处理：

```bash
# 源文件：先 cd 进工程目录，用相对路径
cd "$PROJ"
find src -name '*.java' > .sources
"$JAVAC" ... @.sources

# 类库：Git Bash 路径转盘符路径
win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
LIB_W="$(win "$LIB")"
BOOTCP="$LIB_W\\cldc_1.1.jar;$LIB_W\\midp_2.0.jar;$MMAPI_W"
```

另一个坑：**中文 locale 下 javac 的警告输出经管道会被 grep 当成二进制**
（`Binary file (standard input) matches`）。所以脚本里强制英文 locale：

```bash
"$JAVAC" -J-Duser.language=en -J-Duser.country=US ...
```

同时 `javac` 的退出码要单独判断。早期版本按“有没有生成 class”判断成功，
结果编译器报错但已经产出了部分 class，脚本误判成功并打出一个不完整的 jar。

---

## 五、JAD 文件

KEmulator 直接打开 jar 就能跑，但真机、以及部分模拟器需要 JAD 描述符。
`build.sh` 每次构建都会重写这两项，保证和实际 jar 一致：

```
MIDlet-Jar-Size: <字节数>
MIDlet-Jar-URL: Brick.jar
```

其他字段（`MIDlet-1`、`MicroEdition-Profile` 等）来自工程里的 `Brick.jad` 模板，
脚本只替换 size/URL 两行。jar 的 `MANIFEST.MF` 里也写了同样的 MIDlet 属性，
因为一部分真机认 manifest 而不读 JAD。

---

## 六、模拟器

### KEmulator nnmod 2.22

两个版本，发布包**区分 32/64 位**：

| 包 | 大小 | 说明 |
|---|---|---|
| `kemnnmod.v2.22.nojre.zip` | 10.7MB | **32 位**。在 x64 Java 8 上会弹 amd64 不匹配的对话框 |
| `kemnnx64.v2.22.zip` | 33MB | **x64**，用这个 |

配套要求（README 自述）：

```
JRE:
- Java 8 by Oracle
- OpenJDK 17-21（22+ 可以跑，但调试工具不保证）
```

实测 Java 8（Temurin 1.8.0_504）+ kemnnx64 2.22 正常。

### 首次运行弹出的 "Application Settings"

这是**给单个 MIDlet 保存的机型配置**，不是全局设置：

| 字段 | 作用 | 建议 |
|---|---|---|
| Device preset | 屏幕分辨率 + 键盘布局 | 240x320 竖屏（默认即可） |
| Platform | 对 MIDlet 报告的机型字符串 | 默认 |
| Encoding | `microedition.encoding` 系统属性 | ISO-8859-1（默认）；显示中文用 UTF-8 |
| Locale | `microedition.locale` | en-US |
| Screen size | 实际画布尺寸 | 跟随 preset |
| Max FPS | 帧率上限 | 30（默认） |

**Save as default** 会让以后新游戏都沿用这套；**Done** 只对当前 MIDlet 生效。

### 键盘

方向键 = 移动，数字键 5 / 回车 = 发射，`*` = 重开 BGM。

---

## 七、自动截图验证

`shot.ps1` 用 Win32 `PrintWindow` 抓窗口位图，不依赖窗口处于前台
（`CopyFromScreen` 抓到的是屏幕上那块区域，窗口被遮挡时会抓错）。

```bash
powershell -NoProfile -File ./shot.ps1 -Out shot_game.png
```

原理：`EnumWindows` 找到进程的可见窗口 → `GetWindowRect` → `PrintWindow(handle, hdc, 2)`。
参数 `2` 是 `PW_RENDERFULLCONTENT`，对 SWT/OpenGL 渲染的窗口比参数 `0` 可靠。

---

## 八、重新搭建（换机器时）

```bash
# 1. JDK 8（见第二节）
curl -L -o jdk8.zip "https://api.adoptium.net/v3/binary/latest/8/ga/windows/x64/jdk/hotspot/normal/eclipse"
unzip -q jdk8.zip -d tools/ && mv tools/jdk8u* tools/jdk8

# 2. KEmulator x64 发布版
curl -L -o kem.zip "https://github.com/shinovon/KEmulator/releases/download/v2.22/kemnnx64.v2.22.zip"
unzip -q kem.zip -d emulators/

# 3. CLDC/MIDP 类库 + MMAPI 存根（来自本地资料，不入库）
#    references/midplay/MIDPlay-main/lib/cldc_1.1.jar
#    references/midplay/MIDPlay-main/lib/midp_2.0.jar
#    emulators/kemnnx64/uei/jsr135.jar   ← 随 KEmulator 发布包自带

# 4. 验证
./build.sh && ./run-kemulator.sh "E:/working/zcode-projects/j2me_dev/games/brick/Brick.jar"
```

---

## 九、待验证

- [ ] 真机（Nokia E5）部署：`.jar` 拷进存储卡直接安装
- [ ] `device://midi` 在各代 S40/S60 真机上是否可用（不支持的机型需降级到 `Manager.playTone`）
- [ ] 触摸操作在真机上的手感（当前只验证了鼠标拖动）
