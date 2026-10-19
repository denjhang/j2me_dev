# VGM 播放器 — 设计

**版本**: v0.1 草案
**日期**: 2026-10-19
**第一目标**: AY8910 单芯片，VGM 实时转 MIDI 播放

---

## 一、为什么是 VGM→MIDI 而不是 PCM 合成

| 方案 | CPU | 堆 | 音质 | 结论 |
|---|---|---|---|---|
| 纯 Java PSG 合成 | 高（S40 勉强） | 小 | 最准 | 第二形态，需要时再做 |
| **VGM→MIDI 实时转译** | **极低** | **小** | 受手机音源限制 | **第一版走这条** |
| 离线渲染 WAV | 低（一次性） | 大（8MB/3min） | 准 | 加载器世界 / 堆大的机器 |

选 MIDI 的理由：零合成开销、1MB 堆无压力、S40v3 起全系实时。
代价是音色由手机 MIDI 音源决定 —— AY8910 的方波会被映射成某种 lead 音色，
听感不是原汁原味，但旋律、和声、节奏完全准确。

参考实现：**Valley Bell 的 vgm2mid** 已经证明这条路可行，它把
AY8910 寄存器状态映射成 MIDI Note On/Off + Volume + Pitch Bend。

---

## 二、整体架构

```
VGM 文件（jar 内资源）
    ↓ VgmParser：逐命令解析，产出寄存器写事件 + 时间戳
事件流（节奏：采样数）
    ↓ Ay8910ToMidi：寄存器状态机，比较前后状态，只在变化时产 MIDI 事件
MIDI 事件流
    ↓ MidiSink：8KB 缓冲 + 定时器
Player / shortMidiEvent
    ↓
手机 MIDI 音源
```

### 为什么要有 8KB 缓冲

J2ME 真机上 `shortMidiEvent` 是 JNI 调用，开销不低。如果 VGM 里每个寄存器写
都直接调一次，171 秒的曲子有 3 万多次调用（Digital Espresso 实测 32,891 次），
在 200MHz 的 ARM9 上会明显卡顿。

缓冲的作用是**批量提交**：解析线程往缓冲区里塞 MIDI 字节，播放线程按时间片
一次提交一批。8KB 是平衡点 —— 够放约 2000 个 3 字节事件，覆盖几秒的密集段落，
又不会在 1MB 堆上占太多。

```
缓冲区结构：环形，单个 slot = (deltaSamples, status, data1, data2) 4 字节
8KB / 4B = 2048 个事件槽
```

> 注意：**不能简单用 `shortMidiEvent(status, d1, d2)` 而丢掉 delta**。
> 真机上 MIDI 事件没有时间戳概念，只有「现在发」。所以播放线程必须自己按
> 时间片推进，缓冲区里的事件必须在正确的时间点才提交。
> 缓冲的意义是**预解析**（把 VGM 解析和 MIDI 提交解耦），不是「提前发声」。

---

## 三、模块划分

| 模块 | 职责 | 依赖 |
|---|---|---|
| `VgmHeader` | 解析文件头（版本、芯片时钟、循环点、总采样） | 无 |
| `VgmParser` | 命令循环，产出 `(waitSamples, cmd, reg, data)` | `VgmHeader` |
| `Ay8910State` | 16 个寄存器的影子状态 + 分频值拼装 | 无 |
| `Ay8910ToMidi` | 状态比较，产出 MIDI 事件 | `Ay8910State` |
| `MidiEventBuffer` | 8KB 环形缓冲 | 无 |
| `VgmPlayer` | 主循环：解析 → 转译 → 按时间提交 | 以上全部 |
| `MidiSink` | 封装 `device://midi` 和降级路径 | MMAPI |

### 数据流细节

```
VgmParser.next()
  返回值：{ waitSamples, type, reg, data }
    type = WAIT | REG_WRITE | END | LOOP

VgmPlayer.run()
  while (playing):
    ev = parser.next()
    if ev.type == REG_WRITE:
        Ay8910ToMidi.onRegisterWrite(ev.reg, ev.data)  → 可能产出 0..N 个 MIDI 事件
        写入 MidiEventBuffer（带当前累计采样数）
    else if ev.type == WAIT:
        currentSamples += ev.waitSamples
        flushEventsUpTo(currentSamples)   ' 提交到期的事件
    else if ev.type == END:
        if (hasLoop && loopEnabled) seek(loopOffset)
        else stop
```

---

## 四、AY8910 → MIDI 映射规则

照搬 vgm2mid 的**状态比较**思路，但做 J2ME 适配。

### 4.1 声道分配

| AY 声道 | MIDI 通道 | 音色 |
|---|---|---|
| 0 (A) | 10（或 0） | Square Lead |
| 1 (B) | 11（或 1） | Square Lead |
| 2 (C) | 12（或 2） | Square Lead |

> 为什么默认放 10-12：MIDI 通道 10 在 GM 里是鼓通道，但很多手机音源对
> 通道 10 的处理不一致。真机验证后再定，先做成可配置。
> **一条铁律**：一开始就用 `ChipID*3` 加上偏移的算法（vgm2mid 的做法），
> 支持双 AY 芯片而不改结构。

### 4.2 音高

```
FNum = (regs[coarse] & 0x0F) << 8 | regs[fine]     ' 12-bit
Hz   = FNum == 0 ? 0 : (clock / 16) / FNum
note = round(69 + 12 * log2(Hz / 440))
```

J2ME 没有 `Math.log`？—— 有，CLDC 1.1 的 `java.lang.Math` 包含 `log()`。
但为了省 CPU，**预计算**一张查找表更划算：

```
FNum 范围 1..4095（12 位）
建一张 4096 项的 short[4096]，存 MIDI 音符号（0-127）+ 微调分（-50..+50 音分）
启动时算一次，之后 O(1) 查表
```

4096 × 2B = 8KB —— 和缓冲区一样大。如果堆紧张，可以改成粗查表
（只对 1..1023 建表，高 3 位线性推算）。

### 4.3 音高微调（弯音轮）

vgm2mid 用 Pitch Bend 处理音分偏差。MIDI 弯音范围默认 ±2 半音 = ±200 音分，
14 位精度下 1 音分 ≈ 41 个弯音单位。

```
cents  = (noteFractional) * 100
bend   = 8192 + round(cents / 200 * 8192)
```

**第一版可以不做弯音**，只发整数音符 —— AY8910 的音准偏差通常在几个音分内，
听感上可以接受。留接口，第二版再填。

### 4.4 音量 ★（vgm2mid 这里有 bug，别照抄）

vgm2mid 的 `AY8910.bas` 这样写：

```vb
Attenuation_2(CurCH) = Data And &HF
MIDIVolume = Attenuation_2(CurCH) * &H8
MIDI_Event_Write MIDI_CONTROLLER_CHANGE, MIDIChannel, MIDI_VOLUME, MIDIVolume
```

**勘误（2026-10-20）：这段判断是错的。** AY8910 的音量寄存器语义是
数值越大越响（0=静音，15=最响），与 SN76489 的衰减方向相反。
vgm2mid 的 `Attenuation * 8` 对 AY 而言方向正确，不是 bug；
我们第一版照 SN 的习惯做了反相，反而引入了真 bug。
当前实现用 yasp11 VOL_TO_TTL 取反的非线性表。

对比同一份源码里其他芯片的写法，可以确认这是 AY 模块独有的疏漏：

- `SN76489.bas:240`：`MIDIVolume = MIDI_VOLUME_MAX + 1 - 8 - Attenuation * 8` —— 做了反相
- `SN76489.bas:323`：`PSG_Vol2DB(TL) = -TL * 2` —— **负号**，衰减值转成负 dB
- `YM2413.bas:258`：`Volume = MIDI_VOLUME_MAX - (Data * 8) + 1` —— 做了反相
- `AY8910.bas`：直接用 `Attenuation * 8`，**没有反相**

而且 `AY_Vol2DB()` 函数虽然定义了（`TL - 15`，正确方向），却**从未被调用**。

**我们采用**：
```
atten   = regs[volReg] & 0x0F          ' 0 = 最响
useEnv  = (regs[volReg] & 0x10) != 0
midiVol = useEnv ? 127 : ((15 - atten) * 8 + 7)
```

`(15 - atten) * 8 + 7` 让 atten=0 → 127（最响），atten=15 → 7（接近静音，
但不完全静音 —— 保留 7 是因为 AY 的 15 档仍有微弱输出）。

> 真机验证时用 `vgm2mid.exe` 转一个已知音量变化的片段对照，
> 会看到它的输出是反的。**不要**为了「和参考实现一致」而照抄这个 bug。

### 4.5 开关（ENABLE 寄存器）

```
bit0-2 = 音调通道 A/B/C 使能（0 = 开）
bit3-5 = 噪声通道 A/B/C 使能（0 = 开）
```

注意寄存器是**低有效**（0 = 使能）。vgm2mid 里 `TempByt = Not Data` 取反后判断。

噪声通道：第一版**直接忽略**（MIDI 没有噪声源）。损失是鼓组/效果音，
后续可以用 GM 鼓通道（channel 10）的鼓音色近似映射。

### 4.6 硬件包络

AY8910 的包络（寄存器 0xB-0xD）是硬件自动运行的。vgm2mid 的处理是
**忽略包络细节，固定给最大音量**。

更好的做法（第二版）：把包络波形（0xD 的高 4 位选 16 种，低 4 位选
持续/重触发）近似成 MIDI 的 Volume 包络或 Expression 控制器。第一版不做。

---

## 五、时间与循环

### 时间基准

```
累计采样数 → 毫秒：ms = samples * 1000 / 44100
```

**不要**把 MIDI 的 tick 概念带进来。手机 MMAPI 的 MIDI 事件没有 tick，
所有事件都是「立即执行」，节奏完全由我们自己的定时器控制。

### 定时器精度

J2ME 的 `Thread.sleep(1)` 实测精度在 10-20ms 左右（取决于平台）。
735 采样 = 16.67ms，正好在一帧的量级。

策略：**每 10ms 检查一次**，把「已经到期」的事件一次性全部提交。
这样即使 sleep 有抖动，事件时间误差也控制在 10ms 内，听感无影响。

### 循环

```
if (hasLoop && loopEnabled && reachedEnd):
    currentSamples = loopSamples      ' 回到循环点
    parser.seek(loopOffset)           ' 文件指针回跳
```

循环点偏移是相对 `0x1C` 的。实测 6 个文件里 3 个有循环。

---

## 六、降级策略

| 环境 | 策略 |
|---|---|
| KEmulator / J2ME Loader | `device://midi` + `shortMidiEvent`（已验证） |
| 真机支持 `device://midi` | 同上 |
| 真机不支持 | 降级到 `Manager.playTone(note, duration, volume)`，单音、无和弦 |
| 连 playTone 都没有 | 静音播放，UI 显示「无音频」 |

`MidiSink` 在初始化时探测，选最高可用的路径。游戏逻辑不感知。

---

## 七、第一版范围（MVP）

**做**：
- VGM 1.5x 头部解析（只读本项目需要的字段）
- 命令：`0x61` `0x62` `0x63` `0x66` `0x67` `0xA0`，其余安全跳过
- AY8910 单芯片，3 个音调通道
- 音高 + 音量 + 开关
- 循环播放
- `device://midi` 输出

**不做**：
- 噪声通道
- 硬件包络
- 弯音轮微调
- 双 AY 芯片
- PCM / data block 内容
- 其他芯片（SN76489 / YM2413）

**验收标准**：
1. 6 个测试文件全部能播完不崩
2. Digital Espresso（171s，32891 次寄存器写）播放流畅无卡顿
3. 旋律能听出来是对的（与 `vgm2mid.exe` 转出的 MID 对照）
4. 在 KEmulator 里内存占用 < 512KB

---

## 八、待验证问题

| 问题 | 怎么验 |
|---|---|
| **音量映射方向** | 对照 `vgm2mid.exe` 的输出 MID，看 AY 衰减 0 对应什么 MIDI 音量 |
| FNum → 音符的分界点 | 用几个已知频率点对照，确认 round 还是 floor |
| 手机 MIDI 通道 10 是否特殊 | 真机实测；模拟器上和真机可能不同 |
| `device://midi` 的 S40 支持度 | 真机实测，不支持的机型记录在案 |
| 8KB 缓冲是否够 | 压力测试 Digital Espresso，看丢事件数 |
| Thread.sleep 精度 | 模拟器 vs 真机分别测 |
