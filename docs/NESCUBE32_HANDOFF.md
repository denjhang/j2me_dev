# 交接文档：nescube 3.2 反编译攻坚（VgmBrowser 真机无声问题）

> 写给新窗口：你的唯一任务是反编译 nescube V3.2 发布版 jar，把它在诺基亚真机上
> 出声的**完整音频链路**逐字节搞清楚，产出可直接照抄的 Java 代码。不要做别的。

## 背景（30 秒）

- 项目：`E:\working\zcode-projects\j2me_dev`，J2ME VGM 播放器（VgmBrowser），
  实时 AY8910 → MIDI（`device://midi` + `MIDIControl.shortMidiEvent`）。
- **故障**：诺基亚 E5（S60v3 FP2）真机上，MIDIControl 拿得到、事件发送无异常、
  但**永远无声**。ToneControl / playTone 正常出声（音频硬件没问题）。
- KEmulator 模拟器上一切正常（MIDI 有声）。
- **唯一已知的"在诺基亚真机上 MIDI 出声"的参照物：nescube V3.2（NES 模拟器）。**
- 用户明确要求：只修 MIDI 路线；禁止提 WAV/PCM 软件合成方案（已试过并被否决）。

## 材料

- nescube V3.2 jar（已解压到 class 文件）：
  `E:\working\zcode-projects\j2me_dev\references\_x\n3\`
  关键类：`p.class`（引擎主类，含 createPlayer）、`y.class`（MIDI 事件层）
- nescube 1.2 源码（**注意：与 3.2 发布版不一致，只作参考**）：
  `E:\working\zcode-projects\j2me_dev\references\_x\nescube-src\src\engine.java`
- MeBoy 源码（第二参照）：
  `E:\working\zcode-projects\j2me_dev\references\_x\meboy-master\MeBoy\src\meboy\Dmgcpu.java`
- 反汇编工具：`E:\working\zcode-projects\j2me_dev\tools\jdk8\bin\javap.exe -p -c <class>`
  （本机没有专用反编译器；可以下载 CFR/procyron 到 tools/ 下用 JDK8 跑，
  CFR 对 Java 1.4 字节码支持良好：`java -jar cfr.jar p.class`）

## 已确认的 nescube 3.2 事实（我已反汇编验证过的，不要重查，往前推）

1. **打开序列**（p.class 方法 k()）：
   `Manager.createPlayer("device://midi")` → `prefetch()` →
   `getControl("javax.microedition.media.control.MIDIControl")`。
   **没有 realize()，没有 start()**。异常时把 MIDIControl 置 null 静默放弃。
2. **使用通道 6/7/8**（三个声部），**不是 0/1/2**：
   - 0x86/0x96/0xB6 = NOTE_OFF/NOTE_ON/CC ch6
   - 0x87/0x97/0xB7 = ch7；0x88/0x98/0xB8 = ch8
   - 还有 0xA3/0xA4（通道触后）等事件出现，用途待查
3. **音色**走专用 API：`midi.setProgram(ch, -1, patch)`（ch=6,7,8，bank=-1）
4. **音量**走专用 API：`midi.setChannelVolume(ch, vol)`（ch=6,7,8,9）
5. 初始化有个 b() 方法：对**全部 16 通道**发 `shortMidiEvent(0xB0|ch, 120, 0)`
   （CC120 All Sound Off）
6. **NOTE_ON 力度恒 127**，音量从不进力度
7. y.class 的 `a()` 在 setProgram(6/7/8) 后调 `setChannelVolume(6/7/8, x)`
   和 `setChannelVolume(9, 95)`（ch9=打击乐，固定 95）

## 你要做的

1. **完整反编译 y.class 和 p.class**（优先用 CFR 拿可读 Java，javap 做交叉验证），
   重点回答：
   a. `y.a()` 里 setChannelVolume 的音量值从哪张表来（b:[B 字节表？具体数值范围）
   b. 音符事件的完整状态机：何时 NOTE_OFF、何时只发 CC7（0xB6 d1=7）、
      通道触后 0xA3/0xA4 到底是什么用途
   c. `y.a(MIDIControl, byte[], int)` 的三个参数各是什么（第二个 byte[] 像是
      音符/音量查找表，第三个是通道数？）
   d. WAV 路径（`a()[B` + audio/x-wav + VolumeControl 75）是什么时候启用的——
      是 MIDI 失败的 fallback 还是独立功能（音效？）——**搞清触发条件**，
      但按用户要求不要把它当 VgmBrowser 的方案
   e. 通道 6/7/8 是配置死的还是可配的？设置存储里有没有通道号？
2. **产出**：
   - 一份 `docs/NESCUBE32_AUDIO.md`：完整音频链路图 + 每个事件类型的触发条件
   - 与我们 `tools/vgm-browser/src/com/denjhang/vgm/Ay2Midi.java` 的逐项差异表
     （当前实现已含：S3 打开序列、CC120 重置、setProgram/setChannelVolume、
     力度恒 127；**通道映射仍是 0-6，还没改成 6/7/8**）
   - 明确结论：把我们的 MAP_CHIP0 从 {0,1,2,3,4,5,6} 改成什么映射，
     以及还有什么没抄到的行为
3. 不要改 VgmBrowser 代码（除非差异表结论明确到只有一处改动）；先出文档。

## 我们的当前实现（对照用）

`tools/vgm-browser/src/com/denjhang/vgm/Ay2Midi.java`：
- `openMidiNescube()` = 事实 1 的序列
- `sendInitProgs()` = nescubeReset(CC120) + setProgram + setChannelVolume
- `noteOn()` = setChannelVolume + NOTE_ON(vel 127)
- 通道映射 `MAP_CHIP0 = {0,1,2,3,4,5,6}`、`MAP_CHIP1 = {10,11,12,13,14,15,7}`
- 真机测试工具：`MidiTestScreen`（文件列表菜单 "MIDI test"），6 号键全通道扫
  0-15（每通道 setProgram+setChannelVolume+CC120+音符 0.35s）
  **关键待办：用户还没跑这个测试**——如果只有 6/7/8 响，改映射即终结

## 构建/测试

- 构建：`./build.sh tools/vgm-browser`（JDK8 + ProGuard 预校验，产物 jar/jad）
- 模拟器：`./run-kemulator.sh <jar>`（KEmulator nnx64，MIDI 在模拟器上正常）
- 发布包：`releases/VgmBrowser-v1.1.13-bin/`
