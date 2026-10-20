# ToneRT 实时诺基亚模式 — 交接文档

> 2026-10-21 · 分支 `midi-rt-player`（git）/ `tools/vgm-browser-tonert/`（工程）
> 目标：用 ToneControl 给诺基亚设备做**零缓冲实时** VGM 播放引擎，替代 SMF 分块流。

## 1. 背景与结论（必读）

诺基亚平台上三条音频路线的最终判定：

| 路线 | 结论 | 证据 |
|---|---|---|
| `device://midi` + MIDIControl 实时 | **死刑（空壳）** | RealtimeProbe（`tools/rtprobe/`）按文档条件穷尽验证：KEmulator 全部出声，S40 模拟器与 E5 真机全部静默；事件不报错但不合成。诺基亚自家文档 6 处承诺该功能，实现缺失 |
| SMF 分块流（audio/midi 字节流） | **主线方案**（VgmBrowserSingle v1.0.24） | E5 完美播放；缺点：每块一个 Player、内存随档位增长、块间有接缝 |
| **ToneControl 实时** | **本任务，已验证可行** | ToneRTProbe/ToneEngineProbe（`tools/tonert/`）E5 实测：**ToneEngineProbe 有声音**，T6 原语 12 秒连续重排全对 |

关键认知：诺基亚文档明说 **tone player 底层用内部 MIDI 合成器实现**——这是 S40/S60 上唯一真正接通合成器的实时通道。

## 2. 已验证的原语（不要重新发明）

引擎三定律，全部 E5 实测：

1. **常驻 Player**：每声道一个 `Manager.createPlayer(Manager.TONE_DEVICE_LOCATOR)`，
   终身复用。**禁止每窗口新建**——S40 连建十几个后 `MediaException` 资源枯竭（T7 实测）。
2. **T6 切换法**：换序列必须
   `stop() → deallocate() → setSequence() → start()`。
   `deallocate()` 是灵魂：`stop()` 后 Player 停在 PREFETCHED 态，
   该态 setSequence 抛 `IllegalStateException`（T2/T3/T4/T5 实测全是这个）。
3. **窗口节奏**：250ms 窗口实测可用（ToneEngineProbe 三声道连续出声）。
   120ms 及以下未验证；接缝听感待细调。

ToneControl 能力边界（文档 + 实测）：
- 每轨**单音**（monotonic）；3 个 AY 方波声道 = 3 个并行 Player。
- 音域 0-127 半音；`TEMPO 127`（=508bpm）+ `RESOLUTION 127` → 最短音符 ≈3.7ms。
- `SET_VOLUME` 逐音符变音量（0-100%），可表达 AY 幅度包络。
- 序列格式只用裸 `VERSION/TEMPO(,RESOLUTION)/[SET_VOLUME]note,dur...`；
  **BLOCK/REPEAT 慎用**（部分实现拒收 `Illegal sequence`）。
- 每窗口序列只几十字节——这就是"零内存"的来源。

## 3. 分支与代码起点

- git 分支：`midi-rt-player`（已基于 main 3bd62e9 建好并切出）
- 工程目录：`tools/vgm-browser-tonert/`（VgmBrowserToneRT v0.9.0，
  从 VgmBrowserSingle v1.0.21 复制，构建/打包链全通）
- 可运行原型：`tools/tonert/ToneRTProbe.jar`
  - MIDlet 2 = **ToneEngineProbe**：3 常驻 Player + 250ms 窗口 + 合成旋律，E5 已出声。
    `startEngine()/buildWindow()` 就是引擎骨架，把它接上真实数据即可。
  - MIDlet 1 = ToneRTProbe（只剩 T6 试验，其它已删——都是已知死路，别测了）

## 4. 架构设计（建议）

```
生产线程（1 个）                    播放线程（1 个）
  VgmParser.next()                   每 250ms：
    → vtime = samples/44               ① 取 [winStart, winStart+250ms) 的各声道音符/音量
    → Ay2Midi 式转换但输出改为           ② 每声道 buildWindow()（byte[] 序列）
      "声道→(音符,音量,时刻)" 事件      ③ 3 路 T6 切换（stop/deallocate/set/start）
    → 攒进当前窗口缓冲
```

要点：
- **复用 Ay2Midi 的音高换算**（note144、包络频率、噪声映射思想），
  但输出层不复用（没有 MIDI 通道概念，只有 note+volume+duration）。
- 每声道状态：当前音符、当前音量。窗口内音高变化 → 多个 note 段；
  音量变化 → SET_VOLUME 对（+2 字节）。密集变化要**量化**到 res 单位（3.7ms）防溢出。
- 噪声：tone 序列表达不了噪声——先用"R6 映射音高 + 短音"近似（沿用 NOISE_MAPS），
  或第 4 个 Player 做打击乐近似。这是音质妥协点，先响再像。
- 包络声道：envMode 时音符=音调周期、音量=env 电平（envLevelCC），
  注意 v1.0.22 的 isBuzzer 教训（tone 混合器开着就不是 buzzer）。
- 暂停：Player.stop() 即可，恢复 start()（比 SMF 方案还简单）。
- 可视化：窗口数据本身就是音符/音量，直接驱动 vis，无需寄存器重放。

## 5. 工程注意（前人踩过的坑）

- **必须 ProGuard 预校验**：工程要有 `proguard.pro`（keep 自己的 MIDlet），
  否则 S40 拒装（rtprobe 栽过）。
- **MIDlet-Version 纯数字点分**；jad CRLF；jar/jad 属性一致（build.sh 自检会拦）。
- 窗口线程里 `deallocate()` 后立即 `setSequence` 没有延迟问题（E5 实测）。
- 三个 Player 的窗口切换尽量在同一线程顺序做（原型如此），避免并发切换。
- 事件密度上限：每窗口字节数要设上限（建议 256B/声道），
  超限丢弃细分段（保音高变化、丢音量微变）。

## 6. 验收路径

1. ToneEngineProbe 接真实 VGM（先单声道方波，验证音高跟随）。
2. 三声道 + 音量包络，对比 SMF 主线的听感。
3. 接缝打磨：窗口长度（250/300/400ms）、窗口内音符衔接（末音不写满整窗，留 1 单位接缝）。
4. 并入 VgmBrowserToneRT：Engine 设置项加 "Tone RT"，与 SMF 流可切换。
5. S40 模拟器 + 真机测试（内存优势的主战场）。

## 7. 相关文件速查

| 内容 | 位置 |
|---|---|
| 引擎骨架原型（E5 出声） | `tools/tonert/src/com/denjhang/tonert/ToneEngineProbe.java` |
| T6 原语试验 | 同目录 `ToneRTProbe.java` |
| 父本（SMF 主线，所有音高换算逻辑） | `tools/vgm-browser-single/src/com/denjhang/vgm/Ay2Midi.java` |
| 分支工程 | `tools/vgm-browser-tonert/` |
| device://midi 死刑档案 | `tools/rtprobe/`、README"实时合成路线已宣告死亡"节 |
| PC 端管线复现器（查"某曲怪异"专用） | `tools/dbtest/DbUcka.java` |
| Nokia 文档库 | `tools/nokia-docs/Java_ME_Docs_Web/`（ToneControl javadoc：GUID-8F9BE454 包） |
