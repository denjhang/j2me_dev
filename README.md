# j2me_dev — J2ME 开发工作区

从 [Maemo_N900](https://github.com/denjhang/Maemo_N900) 仓库剪切出来的独立工作区（2026-10-19）。

**先读 [HANDOFF.md](./HANDOFF.md)** —— 交接文档（资料清单 + 技术选型 + 前期调研结论 + 下一步）。
**技术调研原文见 [RESEARCH.md](./RESEARCH.md)** —— 音频路线的源码级结论（原 N900 PROGRESS.md 第廿二章）。

## 当前状态

编译链已打通，PC 端开发闭环可以完整跑通：**写 Java → 编译成 1.4 class → 打 jar → KEmulator 运行 → 截图验证**，全程不需要真机。

| 能力 | 状态 |
|---|---|
| JDK 8 工具链（免安装绿色版） | ✅ `tools/jdk8/` |
| CLDC 1.1 + MIDP 2.0 编译类库 | ✅ `references/midplay/MIDPlay-main/lib/` |
| MMAPI (JSR-135) 编译存根 | ✅ `emulators/kemnnx64/uei/jsr135.jar` |
| KEmulator nnmod 2.22 x64 | ✅ `emulators/kemnnx64/` |
| 一键构建脚本 | ✅ `build.sh` |
| 示例游戏（打砖块 + MIDI BGM/音效） | ✅ `games/brick/` |
| 自动截图验证 | ✅ `shot.ps1` |

## 快速开始

```bash
# 构建示例游戏（默认 games/brick）
./build.sh

# 构建指定工程（目录里要有 src/ 和一个 *.jad 模板）
./build.sh games/brick

# 在模拟器里运行
./run-kemulator.sh "E:/working/zcode-projects/j2me_dev/games/brick/Brick.jar"

# 不带参数则启动空模拟器，自己从菜单打开 jar
./run-kemulator.sh
```

截图（窗口存在时抓当前 KEmulator 窗口）：

```bash
powershell -NoProfile -File ./shot.ps1 -Out shot_game.png
```

## 目录

| 路径 | 内容 | 入库 |
|---|---|---|
| `HANDOFF.md` / `README.md` / `RESEARCH.md` / `TOOLCHAIN.md` | 文档 | ✅ |
| `build.sh` / `run-kemulator.sh` / `shot.ps1` | 构建与验证脚本 | ✅ |
| `games/brick/` | 示例游戏源码（打砖块，MIDI 音频） | ✅ |
| `tools/` | JDK 8 + WTK 2.5.2 | ❌ 本地 |
| `emulators/` | KEmulator-nnmod 源码+发布版 / FreeJ2ME / FreeJ2ME-Plus / SquirrelJME | ❌ 本地 |
| `references/` | fplay / midplay / j2meloader / awesome-j2me / huayuechenfeng / psg-core | ❌ 本地 |
| `assets/` | nnproject 两份抓取（595MB） | ❌ 本地 |

> `emulators/` 和 `references/` 不入库，但 `build.sh` 依赖其中的 CLDC/MIDP 类库和 MMAPI 存根。
> 换机器时按 [TOOLCHAIN.md](./TOOLCHAIN.md) 的“重新搭建”章节补齐即可。

## 环境提醒

- 编译**必须用 JDK 8**：`javac 26` 最低只支持 `--release 8`，而 MIDP 2.0 需要 class 版本 48（Java 1.4）
- `-bootclasspath` 只指向 CLDC + MIDP + MMAPI，**不能**混入桌面 Java SE 的类，否则真机上 `NoClassDefFoundError`
- KEmulator 2.22 实测在 Java 8 下正常；其 README 也支持 OpenJDK 17-21（22+ 可用但调试工具不保证）
- 部署到 E5：`.jar` 拷进去即可，**无需签名**
