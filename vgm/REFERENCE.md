# VGM 播放器 — 参考资料索引

**建立日期**: 2026-10-19
**目标**: J2ME（MIDP 2.0 + MMAPI）上的 VGM 播放器，第一版只做 AY8910
**路线**: VGM 解析 → 寄存器事件流 → 实时转 MIDI 事件 → 手机 MIDI 音源

本文只记录参考资料的**位置和用途**。具体设计决策见 [DESIGN.md](./DESIGN.md)，
进度见 [PROGRESS.md](./PROGRESS.md)。

---

## 一、参考资料目录（外部，不入库）

### 1. libvgm（完整 VGM 播放器参考实现）

```
E:\working\vscode-projects\Reference_Project\vgm_libs\
  modizer-master-20260408\modizer-master\libs\libvgm\libvgm
```

| 路径 | 大小 | 用途 |
|---|---|---|
| `player/vgmplayer.cpp` | 56KB | VGM 播放主循环、头部解析、等待/循环处理 |
| `player/vgmplayer_cmdhandler.cpp` | 46KB | **命令分发表** `_CMD_INFO[0x100]`，每个命令的长度和芯片归属 |
| `player/vgmplayer.hpp` | — | `COMMAND_INFO` 结构定义 |
| `emu/cores/ay8910.c` | 52KB | AY8910 完整实现（MAME 血统） |
| `emu/cores/emu2149.c` | 18KB | 另一版 AY8910（emu2149，更紧凑） |
| `emu/cores/ayintf.c` | 336B | libvgm 的设备接口适配层 |
| `emu/cores/ayintf.h` | 1.1KB | `AY8910_CFG` 配置结构 |
| `emu/cores/sn76489.c` / `sn76496.c` | — | SN76489，第二阶段用 |
| `emu/cores/ym2413.c` / `nukedopll.c` | — | YM2413/OPLL，FM 路线用 |
| `emu/SoundEmu.c` / `EmuStructs.h` | — | 设备抽象与配置 |

**怎么用**：
- 查命令表长度和芯片映射 → `vgmplayer_cmdhandler.cpp` 的 `_CMD_INFO`
- 查某个命令的实际语义（比如 `0x67` data block 有哪些类型）→ `vgmplayer.cpp`
- 查 AY8910 寄存器语义和包络实现 → `emu/cores/ay8910.c`
- **不要**直接移植 libvgm 的 resampler/音频输出层，J2ME 用不上

### 2. Valley Bell 的 vgm2mid（VGM → MIDI 转换逻辑）

```
E:\working\vscode-projects\Reference_Project\vgm_libs\
  vgm2mid_2013-09-09_src Valley Bell
```

| 路径 | 大小 | 用途 |
|---|---|---|
| `Modules\AY8910.bas` | 5.6KB | **★核心参考**：AY8910 寄存器 → MIDI 事件的完整映射 |
| `Modules\VGM.bas` | 4.6KB | VGM 解析主循环（命令长度、等待累加、loop 点） |
| `Modules\Midi_f0.bas` | 30KB | MIDI 事件写入、音高换算 `Note(Hz)`、弯音轮 |
| `Modules\SN76489.bas` | 12.8KB | SN76489 → MIDI 映射（第二阶段） |
| `Modules\YM2413.bas` / `YM2612.bas` / `YM2151.bas` / `YM3812.bas` | 20KB/20KB/10KB/11KB | 各 FM 芯片映射（后期） |
| `Modules\basNESAPU.bas` / `GameBoy.bas` | 11KB/8KB | 其他芯片（后期） |
| `Modules\PCM.bas` | 5.3KB | PCM 采样处理 |
| `Prog\VGM2MID.bas` | — | 主程序、命令行参数 |
| `Prog\Readme.txt` | — | 版本说明（0.5 版，Paul Jensen 原作） |
| `Prog\vgm2mid.exe` | — | 编译好的二进制，可当**参考输出**做对照 |

**★ AY8910.bas 的价值**（这是本站最关键的一份参考）：

它把 AY8910 的 16 个寄存器直接映射成 MIDI 事件，核心逻辑：

```
寄存器 0-5（AFINE/ACOARSE/BFINE/BCOARSE/CFINE/CCOARSE）
  → 拼出 12 位分频值 FNum
  → Hz = (ClockAY / 16) / FNum          ' 注意 ÷16
  → 转成 MIDI 音符号
  → 音符变化时发 Note On/Off（带弯音轮处理微分音）

寄存器 8-10（AVOL/BVOL/CVOL）
  → 低 4 位是衰减值（0=最响，15=最轻）
  → MIDI Volume = 衰减值 × 8
  → 第 4 位（0x10）置位 = 使用硬件包络 → 固定给 0x7F

寄存器 7（ENABLE）
  → 每 bit 是一个声道的开/关（bit0-2 音调，bit3-5 噪声）
  → 状态翻转时发 Note On / Note Off
```

**关键细节**：它用的是**状态比较**而不是逐事件翻译 —— 每次寄存器写入后
重新计算音符/音量，跟上次比较，只有变化才发 MIDI 事件。这样寄存器被反复
写相同值不会产生冗余 MIDI 事件，正好符合 MIDI 事件流的特性。

### 3. 本项目已有的 AY8910 C 源码

```
E:\working\zcode-projects\j2me_dev\references\psg-core\
```

| 文件 | 说明 |
|---|---|
| `ay8910.c` / `ay8910.h` | 精简版核（N900 项目在用） |
| `ay8910_v3.c` | 第三版 |
| `ay8910_full.c` | 完整版，带包络/噪声细节 |
| `ay8910_libvgm.c` / `.h` | libvgm 兼容层 |
| `vgm.cpp` | N900 的 VGM 播放器主循环（**解析逻辑可直接对照**） |
| `libvgm_compat.h` | 兼容层头文件 |

`vgm.cpp` 已经跑通 N900 真机，它的 VGM 头部解析和命令分发是最贴近我们需求的
起点 —— 比 libvgm 轻得多，没有 resampler 和音频设备层。

### 4. 测试素材

```
E:\working\zcode-projects\j2me_dev\vgm\ay8910\
```

六个 VGM 1.51 文件，**全部是纯 AY8910**（已逐个解析验证）：

| 文件 | 时长 | 循环 | AY 写入次数 | 备注 |
|---|---|---|---|---|
| `Beforeny2.vgm` | 20s | 无 | 2,299 | 带 NES_APU/MultiPCM 头但数据只用了 AY |
| `Digital-Espresso-Revision-2023-12-.vgm` | 171s | 2s 循环点 | 32,891 | 最长的，适合压力测试 |
| `Reko7-Xenium-2022-.vgm` | 126s | 15s 循环点 | 16,695 | 有 data block（PCM） |
| `She-Stole-My-Cat.vgm` | 151s | 151s 全曲循环 | 29,711 | 全曲循环 |
| `Solitude-Lost-Party-2024-1-.vgm` | 173s | 无 | 26,906 | 最长时长 |
| `bubbled-sKetch-.vgm` | 51s | 38s | 4,930 | 带 NES_APU/MultiPCM 头 |

共同特征：
- 版本全部 VGM 1.51
- AY8910 时钟全部 **1,773,400 Hz**
- 只用 `0xA0`（AY8910 寄存器写）和 `0x61/0x63`（等待）两个命令
- 写入的寄存器覆盖全部 14 个已定义寄存器（0x0-0xD），说明**
  必须支持完整寄存器集**，不能只做 0-10

> 两个文件头里声明了 NES_APU / MultiPCM 的非零时钟，但实际数据流里没有
> 对应命令 —— 解析器遇到未知命令要能安全跳过，不能崩。

---

## 二、VGM 格式要点（已核实）

### 文件头（偏移量以 VGM 1.51 为准）

| 偏移 | 长度 | 字段 |
|---|---|---|
| 0x00 | 4 | `"Vgm "` 魔数 |
| 0x04 | 4 | EOF 偏移（相对 0x04；0 表示 = 文件尾） |
| 0x08 | 4 | 版本号（`0x00000151` = 1.51） |
| 0x14 | 4 | GD3 标签偏移（相对 0x14） |
| 0x18 | 4 | 总采样数（44100Hz 计） |
| 0x1C | 4 | 循环点偏移（相对 0x1C；0 = 不循环） |
| 0x20 | 4 | 循环采样数 |
| 0x34 | 4 | 数据区偏移（**相对 0x34**，不是 0x30） |
| 0x74 | 4 | **AY8910 时钟** |
| 0x78 | 1 | AY8910 芯片类型（0=AY8910，1=YM2149 等） |
| 0x79 | 1 | AY8910 标志位 |

> 踩坑记录：数据区偏移在 `0x34` 且是**相对 0x34** 的。最初按 0x30 解析，
> 导致所有字节都错位一格，把 `0x63` 等待命令读成 `0x0`。

### 本项目用到的命令

| 命令 | 长度 | 含义 | 已核实 |
|---|---|---|---|
| `0x61 nn nn` | 3 | 等待 nn nn 采样 | ✅ 6 个文件都有 |
| `0x62` | 1 | 等待 735 采样 | — |
| `0x63` | 1 | 等待 882 采样 | ✅ 主要等待方式 |
| `0x66` | 1 | 数据结束 | ✅ |
| `0x67 0x66 tt ss ss ss ss ...` | 7+size | 数据块（PCM ROM 等） | ✅ Reko7 有 |
| `0xA0 aa dd` | 3 | **AY8910 写寄存器 aa = dd** | ✅ 全部文件的核心命令 |

> `0xA0` 在 libvgm 里走 `Cmd_Reg8_Data8`：chipID 由命令值是否 ≥0xA0 决定，
> 然后 `SendYMCommand(dev, 0, reg, data)`。

### 时间基准

```
44100 采样 = 1 秒
DeltaTime（MIDI tick） = 累计采样数 / 735
```

vgm2mid 用的是 735 采样/tick —— 因为 44100/735 = 60，即标准 MIDI 的
**60 tick/秒**。这个换算关系在 `VGM.bas` 里写死。

---

## 三、AY8910 寄存器速查

| 寄存器 | 名称 | 位宽 | 含义 |
|---|---|---|---|
| 0x0 | AFINE | 8 | 声道 A 分频低 8 位 |
| 0x1 | ACOARSE | 4 | 声道 A 分频高 4 位 |
| 0x2 | BFINE | 8 | 声道 B 分频低 8 位 |
| 0x3 | BCOARSE | 4 | 声道 B 分频高 4 位 |
| 0x4 | CFINE | 8 | 声道 C 分频低 8 位 |
| 0x5 | CCOARSE | 4 | 声道 C 分频高 4 位 |
| 0x6 | NOISEPER | 5 | 噪声分频（低 5 位） |
| 0x7 | ENABLE | 6 | bit0-2 音调开关，bit3-5 噪声开关 |
| 0x8 | AVOL | 5 | bit0-3 音量衰减，bit4 = 用包络 |
| 0x9 | BVOL | 5 | 同上 |
| 0xA | CVOL | 5 | 同上 |
| 0xB | EFINE | 8 | 包络分频低 8 位 |
| 0xC | ECOARSE | 8 | 包络分频高 8 位 |
| 0xD | ESHAPE | 4 | 包络波形选择（0-15） |

**音高公式**（vgm2mid `AY8910.bas`）：
```
Hz = (ClockAY / 16) / FNum      ' FNum = 12 位分频值，0 时无输出
```
1,773,400 Hz / 16 = 110,837.5，所以 FNum 每变 1，频率变化约 0.1Hz@1kHz 级。

**音量公式**：
```
衰减值 0-15（0 最响）
MIDI Volume = 衰减值 × 8   ' 0→0, 15→120
若 bit4 置位（用硬件包络）→ 固定 0x7F
```

**包络**：AY8910 的硬件包络在 vgm2mid 里被**简化为固定最大音量** ——
真实包络是硬件自动运行的 16 种波形，MIDI 无法逐采样跟随。这是已知保真度损失，
但实际听感损失有限（包络主要影响音符起音/衰减）。

---

## 四、待补充的参考

| 参考 | 用途 | 状态 |
|---|---|---|
| VGM 官方规范（vgmrips.net） | 完整命令表、扩展命令 | 待下载 |
| YM2149 数据手册 | 与 AY8910 的细微差异（音量曲线） | 待查 |
| 真机 MMAPI MIDI 行为 | `device://midi` 在 S40/S60 的支持度 | 待真机验证 |
| SN76489 映射 | 第二阶段 | `vgm2mid/Modules/SN76489.bas` 已有 |
| YM2413 映射 | 第三阶段（OPLL） | `vgm2mid/Modules/YM2413.bas` 已有 |
