# j2me_dev — J2ME 开发工作区

从 [Maemo_N900](https://github.com/denjhang/Maemo_N900) 仓库剪切出来的独立工作区（2026-10-19）。

**先读 [HANDOFF.md](./HANDOFF.md)** —— 交接文档（资料清单 + 技术选型 + 前期调研结论 + 下一步）。
**技术调研原文见 [RESEARCH.md](./RESEARCH.md)** —— 音频路线的源码级结论（原 N900 PROGRESS.md 第廿二章）。

## 当前状态

编译链已打通，PC 端开发闭环可以完整跑通：**写 Java → 编译成 1.4 class → 打 jar → ProGuard 预校验 → KEmulator 运行 → 截图验证**，全程不需要真机。

主打产品是 **VgmBrowser**（`tools/vgm-browser/`）—— 一个实时 AY8910 VGM→MIDI 播放器，详见 [tools/vgm-browser/README.md](./tools/vgm-browser/README.md)。

| 能力 | 状态 |
|---|---|
| JDK 8 工具链（免安装绿色版） | ✅ `tools/jdk8/` |
| CLDC 1.1 + MIDP 2.0 编译类库 | ✅ `references/midplay/MIDPlay-main/lib/` |
| MMAPI (JSR-135) / JSR-75 编译存根 | ✅ `emulators/kemnnx64/uei/` |
| KEmulator nnmod 2.22 x64 | ✅ `emulators/kemnnx64/` |
| 一键构建脚本（含 ProGuard 预校验） | ✅ `build.sh` |
| VgmBrowser 播放器（PC 实时 MIDI 引擎，S3） | ✅ `tools/vgm-browser/` |
| VgmBrowserNokia 诺基亚分支·双缓冲（历史，v1.1.23） | ✅ `tools/vgm-browser-nokia/` |
| VgmBrowserSingle 诺基亚分支·单缓冲水位线（**主线**，v1.0.2） | ✅ `tools/vgm-browser-single/` |
| 双缓冲算法 PC 验证台（DbTest3/DbTest4） | ✅ `tools/dbtest/` |
| 示例游戏（打砖块 + MIDI BGM/音效） | ✅ `games/brick/` |
| 自动截图验证 | ✅ `shot.ps1` |

### 发布

Release 产物在 GitHub Releases：<https://github.com/denjhang/j2me_dev/releases>

- `VgmBrowser-vX.Y.Z-bin.tar.gz` — jar/jad + `Start-VgmBrowser.bat`（双击即用仓库自带 JDK8+KEmulator 启动最新 jar）
- `VgmBrowser-vX.Y.Z-src.tar.gz` — 完整源码

诺基亚真机安装要点：jar 必须经过预校验（`build.sh` 已用 ProGuard `-microedition` 处理），JAD 声明 `Nokia-MIDlet-On-Screen-Keypad: no`、`Nokia-MIDlet-No-Exit: true`、`MIDlet-Splash-Screen-Image: suppress`，文件读取权限只放必选 `file.read`。

### 诺基亚分支音频架构（单缓冲水位线为主线，v1.0.2）

真机/S40 模拟器实测 `device://midi` 的 MIDIControl 是死通道（事件发送成功但不合成），唯一可靠的发声路径是 `audio/midi` 字节流。演进过程三步：

1. **v1.1.19 双缓冲**（A/B 状态机，预缓冲 2）：算法先在 `tools/dbtest/DbTest4.java` PC 验证 PASS 再移植，模拟器验证轮转正常。
2. **v1.1.20-23**：Player 预建 + 切换错开、线程常驻/暂停不销毁、缓冲覆盖复用、Mem 监控行。遗留问题：段切换仍有可感卡顿，Vector 录制内存大（~800K）。
3. **VgmBrowserSingle 单缓冲水位线（现行主线，`tools/vgm-browser-single/`，v1.0.4 已在 E5 真机完美播放（512k/1M 档 + 切曲全量清理））**：
   - 一条单调事件流：生产线程全速追加（时间戳用歌曲虚拟时间 `vtime`），播放线程从流头按水位切块，无 A/B 切换、无状态机。
   - 软水位背压：未消费超 2 块生产线程歇口气，不硬阻塞。
   - **扁平 long[] 事件流**（无 per-event 对象），内存约为 Vector 方案一半，前缀懒压缩防增长。
   - 常驻预建线程：下一块播放中段切好 + create/realize/prefetch 提前做，块衔接只剩 start()。
   - 暂停只挂起不销毁；30fps 重绘 + 调试行缓存；解压窗口期 file==null 保护。
   - 模拟器结论：**明显比双缓冲流畅**；E5 (S60) 真机验证**可完美播放**（"S40 能过，S60 百分百能过"）。
   - v1.0.3：新增 1M 缓冲档（S60 不限堆）；修复切曲内存泄漏（release 时主动清空大块数据，
     流数组超在留 8 倍自动收缩）；30fps 重绘 + 调试行缓存；解压窗口期 file==null 保护。
   - **实时合成路线已官告死亡（2026-10-21）**：RealtimeProbe（tools/rtprobe/）按文档条件
     穷尽验证（主音量+通道音量+音色+GM 复位+16 通道扫描+SMF 叠加）：
     KEmulator 全部正常出声，S40 模拟器与真机全部静默。诺基亚自家文档 6 处承诺
     device://midi 交互式 MIDI，但实现是空壳（事件接受不合成；setProgram(bank=-1) 也与文档不符）。
     历史上真正实现该功能的是索爱 JP-7/8+ 等平台。
   - **但 ToneControl 实时引擎在 E5 真机验证成立（2026-10-21）**：tools/tonert/ToneRTProbe.jar
     内含 ToneEngineProbe（3 常驻 tone Player，250ms 窗口，stop->deallocate->setSequence->start）。
     E5 实测：引擎原型有声、T1 基线有声；旧法试验（T2-T5/T7）全部 IllegalStateException/资源枯竭，已从探针移除。
     后续：接真实 VGM 解析器，作为 S40 低内存实时引擎候选。

调试区：`Stream` 行显示 ahead/水位线，`Mem` 行显示已用/堆上限。通道映射与 PC 版一致（不用鼓通道 9）。



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
| `tools/vgm-browser/` | VgmBrowser 播放器源码 + jar/jad（PC 引擎） | ✅ |
| `tools/vgm-browser-nokia/` | VgmBrowserNokia 源码（双缓冲，历史分支） | ✅ |
| `tools/vgm-browser-single/` | VgmBrowserSingle 源码（单缓冲水位线，主线） | ✅ |
| `tools/dbtest/` | 双缓冲流水线算法的 PC 验证程序 | ✅ |
| `releases/` | 发布包与上传说明 | ✅（不含 tar.gz 以外的大文件） |
| `tools/` | JDK 8 + ProGuard 5.3.3 | ❌ 本地 |
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

## 开发工作流（模拟器调试）

**关键原则**：修改代码后，先关闭模拟器 → 编译 → 启动模拟器 → **保持运行不要再动**，等待用户测试完成。反复开关模拟器会严重干扰调试。

标准流程：
```bash
# 1. 如果模拟器在运行，先关闭
taskkill //F //IM java.exe

# 2. 修改源码后编译
./build.sh tools/vgm-browser-single

# 3. 编译成功后启动模拟器
./run-kemulator.sh "$(pwd)/tools/vgm-browser-single/VgmBrowserSingle.jar"

# 4. 保持模拟器运行，等待用户测试反馈
```

## 版本发布规范

**严重教训**（2026-10-22）：从 v1.0.32 开始源码没有提交到 git，只打包了 release，导致源码险些丢失。

**强制规范**：
1. 每个版本**必须先提交源码到 git**，再打包 release
2. 提交信息必须包含版本号和功能变更
3. Release 目录结构：`releases/VgmBrowserSingle-vX.Y.Z-bin/`（包含 jar + jad + README.txt）
4. 立即 push 到远程，不要拖延

最近版本记录（完整技术细节见 [tools/vgm-browser-single/DEVELOPMENT.md](./tools/vgm-browser-single/DEVELOPMENT.md)）：
- **v1.0.31**：PC 黄金版本（= v1.0.28 + 2 行，无单音强制/无复音管理/无时间戳调用）
- **v1.0.32/33**：双音量设置、单音强制、`panicAll()` 复音全静音（PC 劣化开始）
- **v1.0.34**：`panicAll()` → LRU 替换，**引入 `noteOnTime` 时间戳（PC 卡顿起点）**
- **v1.0.35**：恢复 v1.0.33 特性、分组设置界面、GM 0-127 乐器、默认值转 Nokia 向

## AI 辅助开发教训（2026-10-25）

**全自动测试的惨痛失败**：Claude Sonnet 3.5 在 2026-10-25 凌晨进行了 8 小时"全自动性能优化"，结果：
- ❌ **零进度**：没有找到真正的内存泄露和卡顿原因
- ❌ **破坏现有功能**：禁用 `recordingReg` 导致钢琴可视化动画完全失效（画面一片空白）
- ❌ **盲目修改**：没理解代码逻辑就动手，修复方向完全错误
- ❌ **浪费时间**：8 小时反复测试、编译、提交，最终全部回滚（强制推送删除 2 个错误 commit）

**根本问题**：
1. AI 误以为可视化依赖 `regF` 数组，实际上依赖 `gen.write()` → `curNote[]` 更新
2. 禁用 `recordingReg` 虽然节省内存，但破坏了实时音符状态更新机制
3. 在没有验证可视化是否正常的情况下，就下结论"修复完成"

**结论**：
- ⚠️ **全自动测试根本不可取**：AI 无法判断可视化等用户体验是否正常，只能看到日志数字
- ✅ **正确做法**：人工指导 + 分步验证 + 每步确认可视化/音频/交互都正常后再继续
- ✅ **保护措施**：重要功能修改必须人工实机/模拟器测试，不能只看监控日志

**回滚记录**：
- `0f4f2fd` - 第一次错误修复（禁用 regF + trim）
- `b12e920` - 第二次错误修复（修正 shrinkStream 条件）
- 最终强制回滚到 `64aeb96`（干净版本）


- **v1.0.36**：双芯片/芯片类型识别修复（369 文件误判 293→0）
- **v1.0.37**：切曲时 `System.gc()`（真机内存）
- **v1.0.38**：`retriggerVel` 遵循 `volMode`
- **v1.0.39**：新增 `Mono tone` / `Poly overflow` 开关（**引入持久化遗漏 Bug**）
- **v1.0.40**：移除 `System.currentTimeMillis()`，改用递增序列号（**PC 流畅度恢复**）
- **v1.0.41**：预设快速切换（PC / Nokia E5）+ PC 默认值 + **持久化 Bug 修复**

## ⚠️ 新增配置选项的强制规范

**每次新增用户可配置选项，必须完成 12 步**（详见 DEVELOPMENT.md）。历史上因遗漏导致严重 Bug：

| Bug | 版本 | 症状 | 根因 |
|-----|------|------|------|
| PC 音质劣化 | v1.0.32-39 | 音符卡顿、听感变薄 | 单音强制 + panic 全静音 + `System.currentTimeMillis()` |
| 设置切曲丢失 | v1.0.39 | 调整后切曲立即退回默认 | 未添加到 `applyPersisted()`/`persist()`，未扩展 `FIELDS` |
| 切曲内存累积 | v1.0.37 | 真机播放中 OOM | 真机 GC 懒惰，未主动回收 |

**最易遗漏的一步**：`PlayScreen.applyPersisted()` —— 遗漏会导致切曲后设置丢失，用户立即遇到。

## 项目记忆

### 关键决策
1. **实时 MIDI 引擎已死**（2026-10-21）：S40/诺基亚 `device://midi` 是空壳，只有 ToneControl 可用
2. **单缓冲水位线为主线**：比双缓冲更流畅，E5 真机验证通过
3. **PC 与真机需要不同配置**：v1.0.41 起用预设一键切换（PC / Nokia E5）
4. **音频关键路径禁止系统调用**：v1.0.34 的 `System.currentTimeMillis()` 是 PC 卡顿主因

### 当前问题与修复
- ✅ **PC 音质劣化**（v1.0.40 修复）：移除 `noteOnTime` 时间戳调用，改用递增序列号
- ✅ **设置切曲丢失**（v1.0.41 修复）：补全 `applyPersisted()`/`persist()`，`FIELDS` 30→32
- ✅ **双芯片误判**（v1.0.36 修复）：bit31→bit30，0x7C/0x7D→0x78/0x79
- ✅ **进度条显示修复**（v1.0.34）：有循环点的曲目第二次循环时进度条超总长 → `total = totalSamples + loopPlays × loopSamples`
- ⚠️ **源码丢失风险**：v1.0.32/v1.0.33 源码未提交，从 stash@{1} 找回 → 已提交到 `fd1a60b`

### 调试技巧
- 开发规范与历史：`tools/vgm-browser-single/DEVELOPMENT.md`
- 进度条计算在 `PlayScreen.java:1277` 附近
- 循环逻辑在 `PlayScreen.java:567`（`EV_END` 事件处理）
- 音量/复音限制在 `Ay2Midi.java`
- 不要碰循环播放本身（已经是无缝的），只改显示和切曲逻辑

