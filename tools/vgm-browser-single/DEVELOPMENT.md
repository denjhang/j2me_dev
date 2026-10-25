# VgmBrowserSingle 开发文档

## 目录
1. [版本演进历史（v1.0.28 → v1.0.41）](#版本演进历史v1028--v1041)
2. [三个重大 Bug 的完整分析](#三个重大-bug-的完整分析)
3. [新增配置选项的完整清单](#新增配置选项的完整清单)
4. [代码审查清单](#代码审查清单)
5. [性能优化指南](#性能优化指南)
6. [当前槽位分配](#当前槽位分配)
7. [Git 提交索引](#git-提交索引)
8. [AI 辅助开发失败案例](#ai-辅助开发失败案例)

---

## AI 辅助开发失败案例

### 2026-10-25 凌晨：全自动性能优化的惨败

**背景**：用户报告 PC 播放时 GUI 卡顿、内存膨胀到 300-400MB，双芯片曲目经常崩溃。

**AI 操作**（**Claude Sonnet 5.5**，持续 8 小时）：
1. 创建自动测试版本（`tools/vgm-browser-test`）
2. 完整播放双芯片曲目，监控 fps、内存、regN、recN
3. 分析日志，得出"regF 和 recF 无限增长导致内存膨胀"
4. 实施"修复"：
   - 禁用 `gen.recordingReg = false`（节省 50% 内存）
   - 添加 `trimRec()` 定期清理 recF
   - 修正 `shrinkStream()` 条件
   - 提交 2 个 commit（`0f4f2fd`, `b12e920`）

**灾难性后果**：
- ❌ **钢琴可视化动画完全消失**（画面一片空白）
- ❌ **没有找到真正的内存泄露原因**
- ❌ **没有找到真正的卡顿原因**
- ❌ **8 小时零进度，最终强制回滚**

### 技术根因分析

**AI 的错误假设**：
```
可视化读取 regF 数组 → 禁用 recordingReg 节省内存 → 可视化应该不受影响
```

**实际机制**（正确理解）：
```java
// PlayScreen.java 行 1569-1570
int note1 = ay.visNote(ch);
int note2 = ay2 != null ? ay2.visNote(ch) : -1;

// Ay2Midi.java 行 1534-1539
public int visNote(int ch) {
    if (buzzSrc == ch && buzzNote >= 0) {
        return buzzNote;
    }
    return curNote[ch];  // ← 关键：返回实时状态
}

// Ay2Midi.java 行 962-985
public void write(int reg, int data) {
    if (recordingReg) {
        // 记录到 regF（用于录制流，不影响可视化）
    }
    regs[reg] = data & 0xFF;  // ← 更新硬件寄存器状态
    switch (reg) {
        case 0: case 1:
            updateChannel(0);  // ← 更新 curNote[0]
            break;
        ...
    }
}

// Ay2Midi.java 行 1840-1865
private void updateTonePitch(int ch) {
    int hz = channelFreq(ch);
    // ... 计算音符
    if (curNote[ch] != note) {
        noteOff(ch);
        curNote[ch] = note;  // ← 可视化数据源
        noteOn(toneCh[ch], note, vel(curVol[ch]));
    }
}
```

**真相**：
1. **可视化依赖 `curNote[]`**，不是 `regF[]`
2. **`curNote[]` 由 `write()` → `updateChannel()` → `updateTonePitch()` 更新**
3. **`recordingReg` 只控制是否记录到 `regF[]`**（录制流）
4. **但 AI 误以为禁用 `recordingReg` 不影响 `write()` 调用**

**为什么可视化失效？**
- 可能原因 1：`gen.recording = false` 时，`write()` 根本不被调用
- 可能原因 2：禁用 regF 后，某个关键初始化路径被破坏
- **AI 没有验证可视化是否正常就下结论"修复完成"**

### 监控数据对比

**旧版（64aeb96，regF 启用）**：
```
第 1 首（双芯片，48KB）：
- fps=22（不是 30！本来就卡）
- fps=2（GC 时严重卡顿）
- gen.regN=6, gen2.regN=9（regF 很小，不是问题）
- gen.recN=0, gen2.recN=0（cutChunk 已在清理）
- 内存：24MB → 84MB（20秒增长 60MB，每秒 3MB）
- 可视化：✅ 正常
```

**"修复"版（b12e920，regF 禁用）**：
```
第 1 首（双芯片，48KB）：
- fps=30-32（改善！）
- gen.regN=0, gen2.regN=0（regF 已禁用）
- gen.recN=0, gen2.recN=0（trim 工作）
- 内存：44MB → 73MB（约 6 秒，每秒 5MB，更快！）
- 可视化：❌ **钢琴动画完全消失，一片空白**
```

**结论**：
- fps 提升是因为禁用了 regF 的记录开销
- 但内存膨胀更快（3MB/s → 5MB/s）
- **最致命**：可视化完全失效，功能被摧毁

### 根本问题清单

1. **盲目假设**：
   - 没理解 `visNote()` 的数据流
   - 没理解 `recordingReg` 的作用域
   - 没理解 `write()` → `updateChannel()` → `curNote[]` 的更新链

2. **验证缺失**：
   - 只看日志数字（fps、内存、regN），**没有肉眼验证可视化**
   - 测试版在后台运行，AI 看不到画面
   - 修复完成后立刻下结论，没有要求用户确认

3. **方向错误**：
   - 认为 regF/recF 是内存泄露主因（实际只有个位数）
   - 真正的泄露源（SMF 缓冲、图像缓冲、MIDI Player 内部）没找到
   - 卡顿真因（可能是 SMF 生成、GC、MIDI 合成）没找到

4. **全自动模式失效**：
   - AI 无法判断可视化、音频、交互是否正常
   - 只能通过日志数字"优化"，看不到用户体验
   - 8 小时反复测试、编译、分析，全是无效功

### 教训与规范

**❌ 全自动测试根本不可取**：
- 性能优化必须人工验证可视化/音频/交互
- 日志数字改善 ≠ 用户体验改善
- 后台测试看不到画面 = 盲目修改

**✅ 正确的 AI 辅助流程**：
1. **分析阶段**：AI 读代码、分析日志、提出假设
2. **验证阶段**：**人工**在模拟器/真机上确认假设
3. **修改阶段**：AI 实施修改，**每改一处都要人工验证**
4. **测试阶段**：**人工**完整测试所有功能（可视化/音频/设置/切曲）
5. **提交前**：**人工**最终确认，AI 只负责写 commit message

**✅ 性能优化保护措施**：
- 禁止禁用任何 `recording*` 字段，除非完整理解其影响
- 禁止修改 `write()`、`updateChannel()`、`curNote[]` 相关逻辑
- 内存优化必须先定位泄露源（profiler/heap dump），不能靠猜
- 每次修改后必须肉眼验证：钢琴动画、音频、设置持久化、切曲

**✅ 回滚记录**（警示后人）：
```bash
# 错误的修复
0f4f2fd - 禁用 regF + 添加 trim（破坏可视化）
b12e920 - 修正 shrinkStream 条件（仍然破坏可视化）

# 强制回滚
git reset --hard 64aeb96
git push -f origin midi-rt-player
```

**最终结论**：8 小时全自动优化 = **零价值输出 + 功能破坏 + 时间浪费**。

---

## 版本演进历史（v1.0.28 → v1.0.41）

> 所有事实均从 git 历史核实，行号以当时版本为准。

### 时间线总览

| 版本 | 提交 | 日期 | 关键改动 | PC 音质 |
|------|------|------|---------|---------|
| v1.0.28 | `52f6307` | 10-21 03:39 | 设置持久化修复；立体声默认 ABC | ✅ 好 |
| v1.0.29/30 | — | — | 无源码提交（仅 release 目录） | — |
| **v1.0.31** | `7a50d72`→`65c813d` | 10-21 10:07 | **= v1.0.28 + 2 行**（立体声 MONO + 蜂鸣器音量对齐） | ✅ **最佳** |
| v1.0.32/33 | `fd1a60b`（10-22 补提交） | 10-22 09:34 | 双音量设置 + 单音强制 + panicAll + 分组设置 + 127 乐器 | ⚠️ 劣化开始 |
| v1.0.34 | `56f1460` | 10-22 10:16 | panicAll → LRU 替换，**引入 noteOnTime 时间戳** | ❌ 卡顿开始 |
| v1.0.35 | `daed2d5`→`ffdd381` | 10-22 10:33 | 恢复 v1.0.33 特性；默认值改 Nokia 向（volMode=1, volFollow=0） | ❌ 卡顿 |
| v1.0.36 | `a143e96` | 10-24 21:59 | 双芯片/芯片类型识别修复（293→0 误判） | ❌ 卡顿 |
| v1.0.37 | `aa27459` | 10-24 23:03 | 切曲时 System.gc() 防内存累积 | ❌ 卡顿 |
| v1.0.38 | `7814ca9` | 10-24 23:17 | retriggerVel 遵循 volMode | ❌ 卡顿 |
| v1.0.39 | `0d6e283` | 10-24 23:31 | 新增 Mono tone / Poly overflow 开关 | ❌ 卡顿 + 设置丢失 |
| v1.0.40 | `4377102` | 10-24 23:40 | **移除 currentTimeMillis()**，改用递增序列号 | ✅ 恢复流畅 |
| v1.0.41 | `66dc8ef` 等 5 个 | 10-24 23:48→10-25 00:12 | 预设切换 + PC 默认值 + **持久化修复** + 本文档 | ✅ 流畅 |

---

### v1.0.28（`52f6307`，10-21 03:39）

**改动**：修复设置持久化 —— `persist()` 硬编码 record v1，而 `load()` 要求 v2，两者统一为 `SettingsStore.VERSION`。

**关键状态**：
- 立体声默认：`applyStereoMode(0)` = **ABC**（行 194）
- 音量跟随默认：`volFollowMode = 2` = **CC7**（行 ~1242）
- **没有** `toneChFlag`（单音强制）
- **没有** `panicAll()`
- **没有** `noteOnTime` / `System.currentTimeMillis()` in noteOn
- `noteOn()` 实现（行 483-497）：
  ```java
  private void noteOn(int ch, int note, int velocity) {
      if (toneEngine) { if (ch >= 0 && ch <= 2) toneSeq(ch, note, velocity); return; }
      try {
          midi.setChannelVolume(ch, masterVol);   // 音量走 API
      } catch (Throwable t) {
          send(CC | ch, CC_VOLUME, masterVol);    // 失败降级 CC7
      }
      send(NOTE_ON | ch, note, 127);              // 力度恒 127
  }
  ```

---

### v1.0.31（`7a50d72`，10-21 10:07）—— PC 黄金版本

**与 v1.0.28 的差异：只有 2 行**（`git diff 52f6307 7a50d72` 验证）：

```diff
# Ay2Midi.java 行 194
- applyStereoMode(0);              // ABC
+ applyStereoMode(6);              // MONO：手机单声道上侧声像会衰减，主旋律被压低

# Ay2Midi.java 行 ~1027
- send(CC | buzzerCh, CC_EXPRESSION, buzzLevel());
+ send(CC | buzzerCh, CC_EXPRESSION, buzzVol);   // 对齐 PC 版
```

**为什么 PC 音质最佳**：
1. **无单音强制** —— 方波通道允许自由复音，MIDI 合成器自行智能混音
2. **无复音管理** —— 不存在 `panicAll()` 全静音，也不会提前 noteOff
3. **无时间戳系统调用** —— `noteOn()` 里没有任何 `System.currentTimeMillis()`
4. **代码路径最短** —— `noteOn()` 只做 3 件事（设音量、发 note-on、返回）

**提交信息原话**：`v1.0.31 = 1.0.30 + sync-level PC alignment`
即 v1.0.31 的唯一目的就是**把蜂鸣器音量对齐 PC 版**。

**真机代价**：方波通道挂音（AY-3-8910 硬件是单音，但 MIDI 允许复音）——这正是 v1.0.32 试图解决的问题，也是 PC 劣化的起点。

---

### v1.0.32 / v1.0.33（源码未提交，`fd1a60b` 于 10-22 09:34 补提交）

> **重要**：v1.0.32/33 的源码当时**没有提交到 git**，只打包了 release。
> 险些永久丢失，从 `stash@{1}` 找回后于 `fd1a60b` 补提交（jad 标记为 1.0.34）。

**v1.0.32 引入的"真机优化"**（提交信息）：
- 双音量设置：`Note-on vol`（`VOL_MODES`）+ `Vol follow`
- 蜂鸣器隔离

**v1.0.33 引入**：
- 分组设置界面（7 个 section header）
- 完整 GM 0-127 乐器选择
- Nokia 优化默认值

**v1.0.33 的 `noteOn()`（行 484-527）新增了两块逻辑**：

```java
private void noteOn(int ch, int note, int velocity) {
    if (toneEngine) { ... return; }

    // ★ 新增 1：单音强制（AY 硬件语义）
    if (toneChFlag[ch] && lastNoteOn[ch] >= 0) {   // 行 493
        send(NOTE_OFF | ch, lastNoteOn[ch], 0);
        if (activeNotes > 0) activeNotes--;
    }
    lastNoteOn[ch] = note;
    activeNotes++;

    // ★ 新增 2：复音超限 → 全静音
    if (activeNotes > polyLimit) {
        panicAll();                                 // 行 503
    }

    // ★ 新增 3：三种音量模式
    if (volMode == 0) {          // 默认 0 = API（兼容性最好）
        try { midi.setChannelVolume(ch, masterVol); }
        catch (Throwable t) { send(CC | ch, CC_VOLUME, masterVol); }
        send(NOTE_ON | ch, note, 127);
    } else if (volMode == 1) {
        send(CC | ch, CC_VOLUME, velocity);
        send(NOTE_ON | ch, note, 127);
    } else {
        send(CC | ch, CC_VOLUME, velocity);
        send(CC | ch, CC_EXPRESSION, velocity);
        send(NOTE_ON | ch, note, velocity);
    }
}
```

**v1.0.33 的默认值**（核实）：
- `volMode = 0`（API，行 1223）
- `volFollowMode = 2`（CC7，行 1298）
- `polyLimit = 64`

**此时还没有性能问题** —— `noteOn()` 里**没有** `System.currentTimeMillis()`。

**PC 劣化的第一个原因（本版本引入）**：
- `panicAll()`：复音超 64 就**全部静音**。PC 合成器能处理 128+ 复音，这个限制造成音乐"空洞"。
- 单音强制：方波通道被迫单音，PC 上听感变薄。

---

### v1.0.34（`56f1460`，10-22 10:16）—— 卡顿的真正起点

**提交信息**：`null check fix + LRU poly replacement`

**改动**（`git show 56f1460` 核实）：
```diff
# 1. 进度条空指针修复
+ if (file.header != null) { ... }

# 2. panicAll → LRU 替换
-         panicAll();                     // 泄露兼底
+         // LRU 替换：关掉最旧的音符，不是全部静音
+         int oldestCh = -1;
+         long oldestTime = Long.MAX_VALUE;
+         for (int i = 0; i < 16; i++) {
+             if (lastNoteOn[i] >= 0 && noteOnTime[i] < oldestTime) {
+                 oldestTime = noteOnTime[i];
+                 oldestCh = i;
+             }
+         }
+         if (oldestCh >= 0) {
+             send(NOTE_OFF | oldestCh, lastNoteOn[oldestCh], 0);
+             lastNoteOn[oldestCh] = -1;
+         }

# 3. ★ 新增时间戳数组（性能 Bug 根源）
+ private final long[] noteOnTime = new long[16];  // LRU 时间戳
+ noteOnTime[ch] = System.currentTimeMillis();      // ← 每个音符都调用！
```

**为什么这是卡顿的真正起点**：
- `noteOn()` 是**音频关键路径**，每个音符触发都执行
- `System.currentTimeMillis()` 是**系统调用**（用户态→内核态切换）
- J2ME 环境下 JNI 边界开销极大
- 高频调用（每秒可能数十次）累积成可感知卡顿
- v1.0.31 完全没有此调用

**注意**：v1.0.34 的 `noteOnTime` 是为了实现 LRU 替换才引入的 —— **为了真机防挂音，牺牲了 PC 性能**。

---

### v1.0.35（`daed2d5`→`ffdd381`，10-22 10:33-10:42）

**提交信息**：`recover v1.0.33 features (grouped settings + 0-127 instruments + Nokia defaults)`

**关键改动 —— 默认值转向 Nokia**（核实）：

| 参数 | v1.0.33 | v1.0.35 | 影响 |
|------|---------|---------|------|
| `volMode` | 0 (API) | **1 (CC7)** | 音量控制方式变 |
| `volFollowMode` | 2 (CC7) | **0 (retrig)** | 音量变化时重发 note-on |
| `polyLimit` | 64 | 64 | 不变 |

**新增**：
- 7 个分组标题（Playback / Level balance / Volume behavior / Pitch / Envelope voices / Routing / Instruments）
- 完整 GM 0-127 乐器名（`GM_NAMES[128]`）
- `522439f` 标题行加粗亮白高亮

**PC 劣化的第二个原因（本版本引入）**：
- `volFollowMode = 0`（retrig）会在音量变化时**重发 note-on**
- 而 `retriggerVel()` 强制使用 `vel(level)` 作为力度，**忽略 volMode 设置**
- 导致 PC 上音量跟随行为异常

---

### v1.0.36（`a143e96`，10-24 21:59）

**改动**：修复 VGM 头部解析两处偏移错误
- 双芯片标志：bit31 → **bit30**（`0x40000000`）
- 芯片类型：`0x7C/0x7D` → **`0x78/0x79`**

**效果**：369 个测试文件从 293 个误判 → 0 个误判

**与音质无关**，仅修复显示/识别。

---

### v1.0.37（`aa27459`，10-24 23:03）

**改动**：`PlayScreen.release()` 末尾加 `System.gc()`

**原因**：Nokia E5 真机切曲时内存累积不释放

**与音质无关**（GC 只在切曲时触发）。

---

### v1.0.38（`7814ca9`，10-24 23:17）

**改动**：`retriggerVel()` 遵循 `volMode` 设置

```java
private void retriggerVel(int midiCh, int note, int level) {
    if (note >= 0) {
        send(CC | midiCh, CC_VOLUME, masterVol);
        int velocity = (volMode == 2) ? vel(level) : 127;   // ← 新增判断
        send(NOTE_ON | midiCh, note, velocity);
    }
}
```

**效果**：用户设 `volMode=0/1` 时，retrig 也用力度 127，接近 v1.0.31 行为。
**但仍未解决卡顿**（`System.currentTimeMillis()` 还在）。

---

### v1.0.39（`0d6e283`，10-24 23:31）—— 引入持久化 Bug

**改动**：新增两个开关，让用户可禁用真机优化

```java
/** 单音强制：true=方波通道强制单音(真机)  false=允许复音(PC,v1.0.31行为) */
private boolean monoTone = true;
public boolean getMonoTone() { return monoTone; }
public void setMonoTone(boolean m) { monoTone = m; }
public static final String[] MONO_NAMES = {"off", "on"};

/** 复音超限策略：0=LRU替换  1=panic全静音(v1.0.32) */
private int polyOverflow = 0;
public int getPolyOverflow() { return polyOverflow; }
public void setPolyOverflow(int p) { if (p >= 0 && p < 2) polyOverflow = p; }
public static final String[] OVERFLOW_NAMES = {"LRU", "panic"};
```

**`noteOn()` 改为可配置**：
```java
if (monoTone && toneChFlag[ch] && lastNoteOn[ch] >= 0) {  // ← 可关闭
    send(NOTE_OFF | ch, lastNoteOn[ch], 0);
    ...
}
if (activeNotes > polyLimit) {
    if (polyOverflow == 1) {
        panicAll();                    // panic 模式
    } else {
        // LRU 替换（同 v1.0.34）
    }
}
```

**已正确完成**：
- ✓ `copyConfigFrom()` 添加两字段
- ✓ `copyConfigQuiet()` 添加两字段
- ✓ UI 行号 / 标签 / 值显示 / 键盘处理

**致命遗漏**：
- ✗ `PlayScreen.applyPersisted()` 未添加恢复逻辑
- ✗ `PlayScreen.persist()` 未添加保存逻辑
- ✗ `SettingsStore.FIELDS` 未扩展（仍为 30）

**后果**：用户调整 `Mono tone = off` 后切曲，立即退回 `on`；每首曲目都要重设。

---

### v1.0.40（`4377102`，10-24 23:40）—— 性能修复

**问题定位**：
```bash
# v1.0.31：noteOn() 中无任何时间调用
$ grep -n "System.currentTimeMillis" v1.0.31/Ay2Midi.java
216:  return vtime >= 0 ? vtime : System.currentTimeMillis();   # nowMs()
797:  : System.currentTimeMillis() - recBaseMs;                 # 录制
866:  regF[regN++] = ... System.currentTimeMillis() ...          # 录制

# v1.0.39：noteOn() 中每次调用
$ grep -n "System.currentTimeMillis" v1.0.39/Ay2Midi.java
538:  noteOnTime[ch] = System.currentTimeMillis();   # ← 音频关键路径！
```

**修复**：用轻量级递增序列号代替时间戳

```java
// 字段（行 1283）
- private final long[] noteOnTime = new long[16];  // LRU 时间戳
+ private long noteSeq;
+ private final long[] noteOnSeq = new long[16];

// noteOn()（行 538）
- noteOnTime[ch] = System.currentTimeMillis();
+ noteOnSeq[ch] = ++noteSeq;              // 纯内存操作

// LRU 查找（行 545-555）
- long oldestTime = Long.MAX_VALUE;
- if (lastNoteOn[i] >= 0 && noteOnTime[i] < oldestTime) {
-     oldestTime = noteOnTime[i];
+ long oldestSeq = Long.MAX_VALUE;
+ if (lastNoteOn[i] >= 0 && noteOnSeq[i] < oldestSeq) {
+     oldestSeq = noteOnSeq[i];

// panicAll()（行 590）
- noteOnTime[c] = 0;
+ noteOnSeq[c] = 0;
```

**效果**：零系统调用，恢复 v1.0.31 音频流畅度。LRU 逻辑功能完全相同（只需比较大小）。

---

### v1.0.41（5 个提交，10-24 23:48 → 10-25 00:12）

| 提交 | 内容 |
|------|------|
| `66dc8ef` | 新增预设快速切换（PC / Nokia E5） |
| `183c0e0` | 预设名称显示（临时方案 `L=.. R=..`） |
| `0dc564e` | 简化预设 UI，改为标准显示（`presetIdx` 字段 + 循环切换） |
| `7b3cbc0` | 默认值改为 PC 向：`monoTone=false`, `polyLimit=128` |
| `34725d6` | **持久化 Bug 修复**（最关键） |

**`66dc8ef` 新增预设**：
```java
public static final String[] PRESET_NAMES = {"PC", "Nokia E5"};
public void applyPreset(int preset) {
    switch (preset) {
        case 0:  // PC 预设
            volMode = 0; volFollowMode = 2; monoTone = false;
            polyOverflow = 0; polyLimit = 128;
            sqVolPct = 90; noiseVolPct = 80; envVolPct = 90;
            setEnvUni(false, 2); setEnvUni(true, 1);
            stereoMode = 0; noiseMapMode = 1;
            forceRefreshAll(); break;
        case 1:  // Nokia E5 预设
            volMode = 1; volFollowMode = 0; monoTone = true;
            polyOverflow = 0; polyLimit = 64;
            sqVolPct = 100; noiseVolPct = 100; envVolPct = 100;
            setEnvUni(false, 2); setEnvUni(true, 0);
            stereoMode = 6; noiseMapMode = 1;
            forceRefreshAll(); break;
    }
}
```

**`34725d6` 持久化修复**：
```java
// SettingsStore.java 行 12, 14
- static final int VERSION = 3;
+ static final int VERSION = 4;
- public static final int FIELDS = 20 + 10;   // 30
+ public static final int FIELDS = 20 + 12;   // 32

// PlayScreen.applyPersisted()（约 307-313 行）
+ if (v.length > 30) ay.setMonoTone(v[30] != 0);
+ if (v.length > 31 && v[31] >= 0 && v[31] < 2) ay.setPolyOverflow(v[31]);

// PlayScreen.persist()（约 360-361 行）
+ v[30] = ay.getMonoTone() ? 1 : 0;
+ v[31] = ay.getPolyOverflow();
```

---

## 三个重大 Bug 的完整分析

### Bug #1：PC 音质劣化（v1.0.32 → v1.0.39，v1.0.40 修复）

**现象**：v1.0.31 之后 PC 音质变差、音符卡顿；真机反而正常。

**三个叠加原因**：

| # | 原因 | 引入版本 | 代码位置 | 后果 |
|---|------|---------|---------|------|
| 1 | 单音强制 | v1.0.32/33 | `toneChFlag[ch]` 判断 | 方波通道被迫单音，PC 听感变薄 |
| 2 | 复音超限全静音 | v1.0.32/33 | `panicAll()` | PC 能处理 128+ 复音，限制 64 造成空洞 |
| 3 | **时间戳系统调用** | **v1.0.34** | `noteOnTime[ch]=System.currentTimeMillis()` | **音频线程卡顿（主因）** |

**为什么时间戳是主因**：
- 位于 `noteOn()` 音频关键路径
- 系统调用开销（用户态↔内核态）
- J2ME JNI 边界开销被放大
- 每秒数十次高频调用

**修复**：
- v1.0.38：`retriggerVel` 遵循 volMode（解决原因 2 的衍生问题）
- v1.0.39：提供开关（但被 Bug #2 挡住）
- v1.0.40：**移除 `System.currentTimeMillis()`** ← 关键修复
- v1.0.41：PC 预设一键配置 + 默认值 PC 向

---

### Bug #2：新增选项持久化遗漏（v1.0.39 → v1.0.41 修复）

**现象**：调整 `Mono tone = off` 后切曲，立即退回 `on`。

**根因**：v1.0.39 添加字段时**只完成一半工作**

| 步骤 | 位置 | v1.0.39 状态 |
|------|------|-------------|
| 字段声明 + getter/setter | `Ay2Midi.java` | ✓ |
| `copyConfigFrom()` | `Ay2Midi.java` ~797 | ✓ |
| `copyConfigQuiet()` | `Ay2Midi.java` ~824 | ✓ |
| `FIELDS` 扩展 | `SettingsStore.java` 行 14 | ✗ **遗漏** |
| `applyPersisted()` 恢复 | `PlayScreen.java` ~282 | ✗ **遗漏** |
| `persist()` 保存 | `PlayScreen.java` ~331 | ✗ **遗漏** |

**为什么 `applyPersisted()` 遗漏最致命**：
- 切曲时 `BrowserMIDlet.openPlay()` 创建新 `PlayScreen`
- 新 `PlayScreen` 构造时调用 `applyPersisted()` 恢复用户配置
- 未恢复 → 使用字段默认值 → 用户设置丢失

**修复**（`34725d6`）：`FIELDS` 30→32，`VERSION` 3→4，补全恢复/保存逻辑。

---

### Bug #3：切曲内存累积（v1.0.37 缓解）

**现象**：Nokia E5 真机播放到一半 OOM。

**原因**：真机 GC 懒惰，切曲后旧资源未及时回收。

**修复**（`aa27459`）：`release()` 末尾 `System.gc()`。

**注意**：GC 只在切曲时触发，**不影响播放性能**。

---

## 新增配置选项的完整清单

**每次添加新的用户可配置选项，必须完成以下全部 12 步，缺一不可。**

### 步骤 1-3：Ay2Midi.java

**位置**：`tools/vgm-browser-single/src/com/denjhang/vgm/Ay2Midi.java`

**步骤 1：声明字段和方法**（约 1280-1350 行区域）
```java
private boolean monoTone = false;
public boolean getMonoTone() { return monoTone; }
public void setMonoTone(boolean m) { monoTone = m; }
public static final String[] MONO_NAMES = {"off", "on"};
```

**步骤 2：`copyConfigFrom()`**（约 797-821 行）
```java
public void copyConfigFrom(Ay2Midi src) {
    // ... 现有字段 ...
    monoTone = src.monoTone;          // ← 必须
    polyOverflow = src.polyOverflow;  // ← 必须
    forceRefreshAll();
}
```
> 用途：双芯片时 ay2 从 ay 复制配置。

**步骤 3：`copyConfigQuiet()`**（约 824-847 行）
```java
public void copyConfigQuiet(Ay2Midi src) {
    // ... 现有字段 ...
    monoTone = src.monoTone;          // ← 必须
    polyOverflow = src.polyOverflow;  // ← 必须
}
```
> 用途：录制 SMF/寄存器时 gen 从 ay 静默同步。

### 步骤 4-5：SettingsStore.java

**位置**：`tools/vgm-browser-single/src/com/denjhang/vgmbrowser/SettingsStore.java`

**步骤 4：递增 VERSION**（行 12）
```java
static final int VERSION = 4;   // 每次改槽位布局必须递增
```
> 用途：版本不匹配触发配置重置，防读取错位数据。

**步骤 5：扩展 FIELDS**（行 14）
```java
public static final int FIELDS = 20 + 12;   // 每个新字段 +1
```
> 用途：槽位不足会数组越界或数据截断。

### 步骤 6-12：PlayScreen.java

**位置**：`tools/vgm-browser-single/src/com/denjhang/vgmbrowser/PlayScreen.java`

**步骤 6：添加行号常量**（约 60-96 行）
```java
private static final int ROW_MONOTONE = 16;
private static final int ROW_OVERFLOW = 17;
// 后续所有行号 +2
```

**步骤 7：更新 TOTAL_ROWS**（约 96 行）
```java
private static final int TOTAL_ROWS = 31 + Ay2Midi.SLOTS;
```

**步骤 8：`rowLabel()`**（约 1930-1955 行）
```java
case ROW_MONOTONE: return "Mono tone";
case ROW_OVERFLOW: return "Poly overflow";
```

**步骤 9：`rowValue()`**（约 1975-2000 行）
```java
case ROW_MONOTONE: return Ay2Midi.MONO_NAMES[ay.getMonoTone() ? 1 : 0];
case ROW_OVERFLOW: return Ay2Midi.OVERFLOW_NAMES[ay.getPolyOverflow()];
```

**步骤 10：`applyPersisted()`**（约 282-328 行）← **最易遗漏**
```java
if (v.length > 30) ay.setMonoTone(v[30] != 0);
if (v.length > 31 && v[31] >= 0 && v[31] < 2) ay.setPolyOverflow(v[31]);
```

**步骤 11：`persist()`**（约 331-365 行）
```java
v[30] = ay.getMonoTone() ? 1 : 0;
v[31] = ay.getPolyOverflow();
```

**步骤 12：`handleLR()`**（约 2014-2150 行）
```java
case ROW_MONOTONE: {
    ay.setMonoTone(!ay.getMonoTone());
    if (ay2 != null) ay2.setMonoTone(ay.getMonoTone());
    break;
}
case ROW_OVERFLOW: {
    int p = (ay.getPolyOverflow() + dir + 2) % 2;
    ay.setPolyOverflow(p);
    if (ay2 != null) ay2.setPolyOverflow(p);
    break;
}
```

### 遗漏后果对照表

| 遗漏 | 文件 | 行号区域 | 症状 |
|------|------|---------|------|
| 步骤 2 | Ay2Midi.java | ~797 | 双芯片曲目设置不一致 |
| 步骤 3 | Ay2Midi.java | ~824 | SMF/寄存器导出错误 |
| 步骤 5 | SettingsStore.java | ~14 | 数组越界或数据截断 |
| **步骤 10** | **PlayScreen.java** | **~282** | **切曲后设置丢失（用户立即遇到）** |
| 步骤 11 | PlayScreen.java | ~331 | 重启后设置丢失 |
| 步骤 12 | PlayScreen.java | ~2014 | 左右键无响应 |

---

## 代码审查清单

**提交前逐项确认：**

### Ay2Midi.java
- [ ] 字段声明（private + 默认值）
- [ ] getter / setter（public + 参数校验）
- [ ] 选项名称数组（`public static final String[]`）
- [ ] `copyConfigFrom()` 包含新字段（~797-821）
- [ ] `copyConfigQuiet()` 包含新字段（~824-847）

### SettingsStore.java
- [ ] `VERSION` 已递增（行 12）
- [ ] `FIELDS` 已扩展（行 14），新增数 = 新字段数

### PlayScreen.java
- [ ] 行号常量已添加（~60-96）
- [ ] 后续行号已全部更新
- [ ] `TOTAL_ROWS` 已更新（~96）
- [ ] `rowLabel()` 已添加（~1930）
- [ ] `rowValue()` 已添加（~1975）
- [ ] `applyPersisted()` 已添加（~282）← **最关键**
- [ ] `persist()` 已添加（~331）
- [ ] `handleLR()` 已添加（~2014）

### 功能测试
- [ ] 编译通过
- [ ] 设置界面选项显示正常
- [ ] 左右键可调整
- [ ] **调整后切曲，设置保持不变**
- [ ] **退出重启，设置保持**
- [ ] 双芯片曲目，两芯片设置一致

---

## 性能优化指南

### 音频关键路径禁忌操作

**在 `noteOn()`, `noteOff()`, `updateTone()`, `tick()` 等高频方法中，禁止：**

| 类别 | 禁止项 | 原因 |
|------|--------|------|
| 系统调用 | `System.currentTimeMillis()` | 用户态↔内核态切换（v1.0.34 的教训） |
| 系统调用 | `System.nanoTime()` | 同上 |
| I/O | 文件读写、网络 | 阻塞不可预测 |
| 同步 | 大段 `synchronized` | 锁竞争 |
| 内存 | `new Object()`、字符串拼接、数组扩容 | GC 压力 |

### 时间戳 → 序列号（v1.0.40 案例）

**错误**（v1.0.34-v1.0.39）：
```java
noteOnTime[ch] = System.currentTimeMillis();   // 系统调用
// LRU 查找
if (noteOnTime[i] < oldestTime) { ... }
```

**正确**（v1.0.40+）：
```java
noteOnSeq[ch] = ++noteSeq;                     // 纯内存操作
// LRU 查找（逻辑相同）
if (noteOnSeq[i] < oldestSeq) { ... }
```

**要点**：LRU 只需要"相对新旧"，不需要真实时间。递增计数器完全等价且零开销。

---

## 当前槽位分配（v1.0.41，VERSION=4，FIELDS=32）

```
v[0]      = VERSION (4)
v[1]      = stereoMode
v[2]      = octShift
v[3]      = noiseMapMode
v[4]      = envOctSaw
v[5]      = envOctTri
v[6]      = envUniSaw
v[7]      = envUniTri
v[8]      = buzzVol
v[9]      = bendSensPct
v[10]     = bendRangePct
v[11]     = masterVol
v[12]     = loopIdx
v[13]     = shuffle
v[14]     = engineIdx
v[15]     = chunkIdx
v[16..23] = progMap[0..7]        (8 个乐器槽位)
v[24]     = sqVolPct
v[25]     = noiseVolPct
v[26]     = envVolPct
v[27]     = volFollowMode         (0=retrig, 1=chop, 2=CC7, 3=off)
v[28]     = volMode               (0=API, 1=CC7, 2=CC7+CC11+vel)
v[29]     = polyLimit             (16/32/64/128)
v[30]     = monoTone              (0=off, 1=on)
v[31]     = polyOverflow          (0=LRU, 1=panic)
```

**下次新增从 `v[32]` 开始**：
1. `SettingsStore.VERSION` → 5
2. `SettingsStore.FIELDS` → 33
3. 完成 12 步清单

---

## Git 提交索引

```
52f6307  v1.0.28  设置持久化修复
7a50d72  v1.0.31  = v1.0.28 + 2 行（立体声 MONO + 蜂鸣器音量）★ PC 黄金版
65c813d  v1.0.31  版本号 bump
fd1a60b  v1.0.34  补提交 v1.0.32/33 源码（双音量+单音强制+panicAll）
56f1460  v1.0.34  panicAll→LRU，引入 noteOnTime 时间戳 ★ 卡顿起点
daed2d5  v1.0.35  恢复 v1.0.33 特性，默认值转 Nokia 向
522439f  v1.0.35  分组标题视觉高亮
ffdd381  v1.0.35  版本号 bump
2445641  v1.0.35  发布二进制
0aec1d1  —       KEmulator 启动脚本修复
a143e96  v1.0.36  双芯片/芯片类型识别修复
aa27459  v1.0.37  切曲 System.gc()
7814ca9  v1.0.38  retriggerVel 遵循 volMode
0d6e283  v1.0.39  Mono tone / Poly overflow 开关（持久化 Bug 引入）
4377102  v1.0.40  移除 currentTimeMillis() ★ 性能修复
66dc8ef  v1.0.41  预设快速切换
183c0e0  v1.0.41  预设名称显示
0dc564e  v1.0.41  简化预设 UI
7b3cbc0  v1.0.41  PC 默认值（monoTone=false, polyLimit=128）
34725d6  v1.0.41  持久化 Bug 修复 ★
3aeebca  v1.0.41  本文档
```

---

## 文档维护

**必须同步更新本文档的场景**：
- 新增配置选项 → 更新「槽位分配」+ 完成 12 步清单
- 发现新 bug → 添加到「重大 Bug 分析」
- 新增性能问题 → 添加到「性能优化指南」
- 每次发版 → 更新「版本演进历史」时间线

**最后更新**：2026-10-25，v1.0.41 持久化修复
