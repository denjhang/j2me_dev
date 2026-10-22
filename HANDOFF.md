# J2ME 开发交接文档

**建立日期**: 2026-10-17
**独立日期**: 2026-10-19（从 `Maemo_N900` 仓库整体剪切为独立工作区）
**状态**: 资料已收集整理，尚未开始实际开发
**建议**: 在新会话窗口中从本文档开始

---

## 一、为什么选 J2ME（相对塞班原生/PyS60 的优势）

| 维度 | J2ME | 塞班原生 C++ | PyS60 |
|---|---|---|---|
| 开发闭环 | **100% 在 PC 完成** | 需交叉编译 + 签名 | 需真机跑 |
| 桌面模拟器 | **成熟（4 个可选）** | 只有 EKA2L1（实验性） | 无 |
| 部署到真机 | .jar 拷进去即可，**无需签名** | .sis 需签名，每次更新手点确认 | .py 拷进去 |
| 调试 | 模拟器断点调试 | 需真机 + 日志 | 需真机 |
| 目标机型 | S40/S60/Asha **全系通吃** | 按 S60 版本分 | S60/S^3 |

**结论**：J2ME 是唯一能做到"不开真机就能开发+验证"的路线。

---

## 二、已收集的资料（工作区根目录，878MB）

```
（本仓库）/
├── tools/                     Sun 官方开发工具
│   └── wtk252_win.exe         39MB — Wireless Toolkit 2.5.2（自带编译器+模拟器）
├── emulators/                 4 个 PC 模拟器
│   ├── KEmulator-nnmod/       76MB — ★首选：功能最强，官宣支持 NokiaUI 1.4
│   ├── FreeJ2ME/              4MB  — 轻量，AWT/SDL/libretro 三前端
│   ├── FreeJ2ME-Plus/         11MB — FreeJ2ME 增强版
│   └── SquirrelJME/           118MB — 跨平台 JVM 实现（实验性）
├── references/                参考实现（源码级研究过）
│   ├── fplay/                 FPlayJ2ME — 完整 J2ME 播放器工程（86 个 java，MIT）
│   ├── midplay/               MIDPlay — 在线音乐播放器（流式播放范式）
│   ├── j2meloader/            J2ME-Loader（Android，2688★，源码级研究）
│   ├── awesome-j2me/          J2ME 全景资源清单（必读）
│   ├── huayuechenfeng/        2 个 zip（j2me-llm / symbian-j2me-im-gateway）
│   └── psg-core/              N900 的 AY8910 系 C 源码副本（移植 Java 用，见第六节）
└── assets/                    应用/游戏资源
    └── nnproject/             两份 nnproject 抓取（塞班+J2ME 混合，595MB）
```

---

## 三、环境现状（2026-10-19 已打通）

```
系统 Java:  OpenJDK 26.0.1 (Temurin)   ← 保持全局不动
项目 JDK8:  tools/jdk8/ (Temurin 1.8.0_504)  ← 编译和跑模拟器都用它
```

**已解决**，不需要装 Oracle 版、不需要改 `JAVA_HOME`：
JDK 8 是免安装的绿色版，放在工作区 `tools/jdk8/`，只由 `build.sh` /
`run-kemulator.sh` 显式指向它，不污染系统环境。

必须用 JDK 8 的原因是 **`javac 26` 最低只支持 `--release 8`**，
而 MIDP 2.0 要的是 class 版本 48（Java 1.4）。

完整链路和踩坑记录见 [TOOLCHAIN.md](./TOOLCHAIN.md)。

---

## 四、技术选型建议

### 模拟器选 KEmulator nnmod

理由（其 README 自述）：
- 跨平台、硬件加速 3D
- **完整 lcdui 实现**
- **NokiaUI 1.4**（塞班游戏最常用的扩展：全屏/震动/声音）
- M3G View、Mascot Capsule、SoftBank MEXA
- 内存查看、Class Watcher、Profiler 等调试工具
- 支持 Eclipse MTJ / NetBeans / IntelliJ IDEA 集成（可断点调试）

### 开发工具链

```
IntelliJ IDEA / NetBeans + KEmulator nnmod
    ↓ javac (target CLDC 1.1 / MIDP 2.0)
验证：KEmulator 跑 + 自动截图
部署：.jar 拷到 E5 → 直接运行
```

或者用 `tools/wtk252_win.exe`（Sun 官方 WTK，自带 javac + 模拟器，最经典但老）。

---

## 五、已有深度调研结论（来自原 PROGRESS.md 第廿二章，务必先读）

> 这些是前期源码级调研的结论，**避免重复踩坑**。完整原文已剪切到本工作区
> [RESEARCH.md](./RESEARCH.md)，不再是 N900 项目文档的一部分。

### 5.1 平台性能矩阵（J2ME 相关部分）

| 平台 | CPU | 结论 |
|---|---|---|
| S40 全系/Asha | ARM9 200-460MHz，Java 堆 1-5MB | **只能 J2ME** |
| S60v3（E5/E71/N95） | ARM9 220-369MHz | J2ME 是 v3 游戏王者（MIDP2） |

**S40/Asha 分两档**：
1. **实时 PSG 合成播放器** —— 参照 vNes/MeBoy 在 S40 全速跑模拟器的实证，
   SN76489 的 Java 合成比 NES 整机模拟便宜一个量级，**必然实时**
2. **FM 类** —— Java 合成太慢，写离线转换器（VGM→WAV）再 MMAPI 播放，
   保住"格式支持"，放弃实时性

### 5.2 音频播放的三条路（源码级实证）

| 路径 | 可行性 | 证据 |
|---|---|---|
| **真机实时流式** | ✅ S60 可行 | MMAPI 原生支持 `createPlayer(InputStream, "audio/x-wav")` 流式；酷狗实证 |
| **模拟器实时流式** | ❌ 死路 | FreeJ2ME 的 `doPrefetch()` 一次性读完流；J2ME Loader 的 `InternalDataSource` 构造函数 while 循环读到 EOF 才返回 → 无限流 = 永久阻塞 |
| **模拟器离线渲染** | ✅ 推荐 | PSG 离线渲染完整 WAV（3min@22050 单声道≈8MB）→ `createPlayer(ByteArrayInputStream, "audio/x-wav")`，1 秒内渲完，体验=加载曲子 |

### 5.3 J2ME Loader 的"空子"（重要技巧）

J2ME Loader 用 `stream.available()` 预分配临时文件长度（`raf.setLength`）。
**PSG 是确定性合成，总字节数精确已知** → `available()` 报真实总长，
高速合成灌满（1 秒渲 3 分钟曲）→ MediaPlayer 播临时文件，用户体验无感。

### 5.4 双世界策略（定稿）

```
同一 PSG 核，配置开关切换投放方式：
  真机    → 三档降级流式（MMAPI 原生）
  加载器  → 离线渲染 + available() 空子
  S40v3   → 3 段 × 0.3-0.5s 环形链（堆只有 ~1MB）
```

### 5.5 堆预算公式

```
PCM 堆占用 ≈ 16KB/秒（8kHz/16bit/单声道）
平台判定：堆(MB) ÷ 0.016 ÷ 曲长(s) = 可用采样率上限
```

| 平台 | 堆 | 策略 |
|---|---|---|
| S40v3 | ~1MB | 3 段环形链（接缝是生存代价，过零对齐/交叉淡化缓解） |
| S40v5/v6 | 4MB | 整曲单 WAV 单 Player（零接缝，8kHz 2.9MB 勉强入堆） |
| Asha | 128MB+ | 22kHz 时退回分段 |

### 5.6 API 依赖（定案）

只用 **MIDP 2.0 核心 GCF**（ServerSocketConnection / HttpConnection，2002 年起
mandatory，全系必带）+ **MMAPI (JSR-135)**。
**不依赖任何可选包**（JSR-172/234 等机型随机发货的一律不碰）。

### 5.7 参考实现的关键价值

| 项目 | 价值 |
|---|---|
| **FPlay**（carlosrafaelgn，目标 Nokia 3120c 弱 S40，MIT） | URL locator 三件套、JSR-234 EQ 全局控制+降级探测 |
| **MIDPlay**（phd051199，★80） | `Manager.createPlayer(InputStream, contentType)` 流式范式，含 content-type 候选协商、创建重试、分片创建防卡死 |
| **MeBoy-Asha** | 反面教材：走 MIDI 作弊路径（MIDI_DEVICE_LOCATOR + shortMidiEvent） |

**结论**：J2ME PSG 播放器 = STC 计数器范式 PSG 核 + MIDPlay 的 MediaResolver 骨架。
GitHub 上**没有 J2ME 真软件合成的先例** → 我们写就是参考实现。

---

## 六、与 N900 项目的复用关系

N900 的 VGM 播放器有 **AY8910 / YM2612 的 C 源码**。为避免跨仓库引用，
已把相关文件复制到本工作区的 `references/psg-core/`
（原件仍在 `Maemo_N900/research/apps/vgmplayer/`，N900 项目继续使用）：

| 文件 | 说明 |
|---|---|
| `ay8910.c` / `ay8910.h` | 精简版 AY8910 核 |
| `ay8910_v3.c` | 第三版实现 |
| `ay8910_full.c` | 完整版（带包络/噪声细节） |
| `ay8910_libvgm.c` / `.h` | libvgm 兼容层版本 |
| `vgm.cpp` / `libvgm_compat.h` | VGM 解析与播放器主循环 |

| 复用方式 | 说明 |
|---|---|
| 移植成 Java | 纯计算，算法不改；PSG 合成在 S40 上必然实时 |
| 写成 C 扩展（.pyd） | 塞班 PyS60 路线可用，.pyd 拷进去即用 |
| PC 端离线转换 | 先把 VGM 转 WAV，MIDlet 只负责播（最快验证链路） |

---

## 七、进度与下一步

### 第 1 步：打通开发闭环（✅ 2026-10-19 完成）
- [x] JDK 8 绿色版就位（`tools/jdk8/`，无需安装、不动系统环境）
- [x] KEmulator nnmod 2.22 x64 跑通（Java 8，无 amd64 报错）
- [x] 编译链固定：`javac 8 -source 1.4 -target 1.4` + CLDC/MIDP bootclasspath
- [x] 一键构建 `build.sh`（javac → jar → 补 JAD）
- [x] 自动截图 `shot.ps1`（Win32 PrintWindow，不要求窗口在前台）
- [x] 首个可玩样例：`games/brick/` 打砖块

### 第 2 步：音频（✅ MIDI 路线已通，VGM 播放器已可玩）
- [x] `device://midi` + `MIDIControl.shortMidiEvent` 在 KEmulator 上实测可用
- [x] VGM→MIDI 实时播放器（`tools/vgm-browser/`）：完整 256 项命令表解析、
      AY8910 七通道转译（3方波+3噪声+1包络）、播放列表 HUD、
      声像(7种排列)/八度/逐通道音色实时可调
- [x] 包络三分支：快包络波形（音高=clock/16/period/steps，锯齿32/三角64步）、
      同步蜂鸣器（R13 高速重写）、慢包络软件仿真（emu2149 状态机→CC11）
- [x] VGZ 支持（纯 Java inflate，字节级验证）
- [x] 音量映射修正：AY 语义 0=静音 15=最响，yasp11 非线性表
- [x] PCM 实时合成实验：KEmulator WAV 路径不支持无限流（mark/reset 失败），
      块链 gap ~1.4s 为模拟器包装层开销（裸 Clip 实测切换 0ms）
- [ ] 真机（E5）验证：device://midi、块链 gap、VGZ 内存占用
- [ ] WAV/离线渲染路线（堆预算与流式策略见 [RESEARCH.md](./RESEARCH.md)）

### 第 3 步：VGM 播放器
- [ ] 决定：纯 Java 合成 vs 离线转 WAV
- [ ] 移植 AY8910 核到 Java（C 源码副本在 `references/psg-core/`）
- [ ] 双世界策略落地（真机流式 / 模拟器离线）

### 第 4 步：真机部署（E5）
- [ ] .jar 拷到 E5（U 盘模式，无需签名）
- [ ] 验证实际性能

---

## 八、关键提醒

1. **只能用 JDK 8 编译**：`javac 26` 最低 `--release 8`，做不出 class 48。
   别去改系统 `JAVA_HOME`，脚本里显式指向 `tools/jdk8` 就够了。
2. **不要用 `-classpath` 编译 J2ME**：那会放行整个 Java SE，写出真机上不存在的 API。
   必须用 `-bootclasspath` 只挂 CLDC/MIDP/MMAPI。
3. **模拟器实时流式必死**：不要尝试在模拟器里做无限流式播放，会永久阻塞。
   开发期用"离线渲染"模式，真机才用流式。
4. **`device://midi` 的 `setLoopCount` 是空实现**（KEmulator 的 `MIDIImpl`）。
   BGM 循环不要指望它，自己用步进音序器控时钟。
5. **堆预算**：S40v3 只有 1MB 堆，整曲 WAV 必然爆。写代码时按平台分档。
6. **不要碰可选包**：JSR-172/234 等机型支持不一，只用 MIDP 2.0 + MMAPI。
7. **.jar 无需签名**：这是 J2ME 相对塞班原生的巨大优势，别走签名那条弯路。
8. **模拟器调试流程**（避免干扰用户测试）：
   - 修改代码后，如果模拟器在运行 → 先关闭
   - 编译成功后 → 启动模拟器
   - **保持模拟器运行**，不要反复开关
   - 等待用户测试完成后再进行下一轮修改

---

## 九、参考资料索引

| 文档 | 位置 |
|---|---|
| **编译链与验证闭环（实测记录）** | [TOOLCHAIN.md](./TOOLCHAIN.md) |
| 音频方案源码级调研（原文） | [RESEARCH.md](./RESEARCH.md) |
| 示例游戏（打砖块 + MIDI） | `games/brick/` |
| J2ME 全景清单 | `references/awesome-j2me/readme.md` |
| 可复用的 PSG 核（N900 C 源码副本） | `references/psg-core/` |
| N900 的自动化验证方法（可移植到模拟器） | `Maemo_N900/docs/N900_AUTO_VISUAL_TEST.md` |
| KEmulator 使用文档 | `emulators/KEmulator-nnmod/README.md` |
| FreeJ2ME 构建说明 | `emulators/FreeJ2ME/README.md` |

---

**交接人备注**：资料已全部归位到本工作区（2026-10-19 从 `Maemo_N900/j2me/`
整体剪切），J2ME 相关内容在 N900 仓库中已清空（仅 N900 自用的 PSG 源码原件保留）。
新窗口开工时直接读本文档 + [RESEARCH.md](./RESEARCH.md) 即可。
