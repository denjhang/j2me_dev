# VgmBrowserSingle 开发文档

## 目录
1. [版本演进历史与重大 Bug 分析](#版本演进历史与重大-bug-分析)
2. [新增配置选项的完整清单](#新增配置选项的完整清单)
3. [代码审查清单](#代码审查清单)
4. [当前槽位分配](#当前槽位分配)

---

## 版本演进历史与重大 Bug 分析

### 为什么 v1.0.31 在 PC 上音质最好？

**v1.0.31 (2024-10-21) - 黄金版本**
- **特性**：完全没有单音强制逻辑，没有复音管理
- **行为**：方波通道允许自由复音，MIDI 合成器自己处理复音
- **PC 表现**：音质流畅、饱满，合成器智能混音
- **真机问题**：音符泄露（挂音），因为 AY-3-8910 硬件只支持单音

**关键代码** (v1.0.31 `Ay2Midi.java`):
```java
// 完全没有单音强制，noteOn() 直接发送
private void noteOn(int ch, int note, int velocity) {
    send(NOTE_ON | ch, note, velocity);
    // 没有任何 noteOff 或 panic 逻辑
}
```

---

### v1.0.32-v1.0.36: 真机优化导致 PC 音质劣化

**v1.0.32 引入的"真机优化"**

**问题发现场景**：
- Nokia E5 真机测试发现音符泄露（挂音）
- AY-3-8910 方波通道硬件只支持单音
- 但 MIDI 允许一个通道多个音符同时发声

**错误的修复方案** (v1.0.32):
```java
// Ay2Midi.java, 行号约 520-540
private void noteOn(int ch, int note, int velocity) {
    // 1. 强制单音：方波通道先 noteOff
    if (isToneChannel(ch)) {
        if (lastNoteOn[ch] >= 0) {
            send(NOTE_OFF | ch, lastNoteOn[ch], 0);
        }
    }
    
    // 2. 复音超限：全部 panic
    lastNoteOn[ch] = note;
    noteOnTime[ch] = System.currentTimeMillis();  // ← Bug #2 的根源
    activeNotes++;
    if (activeNotes > polyLimit) {
        panicAll();  // ← Bug #1 的根源：全部静音
    }
    
    send(NOTE_ON | ch, note, velocity);
}
```

**引入的两个严重 Bug**：

#### Bug #1: 复音超限时 panic 全静音
- **位置**：`Ay2Midi.java` 约 520-565 行
- **逻辑**：`if (activeNotes > polyLimit) { panicAll(); }`
- **后果**：
  - PC 合成器能处理 128+ 复音
  - 但代码限制 64 个，超过就全部静音
  - 导致音乐出现瞬间静音的"空洞"
  - 真机需要 panic（防泄露），但 PC 不需要

#### Bug #2: 每次 noteOn 调用 System.currentTimeMillis()
- **位置**：`Ay2Midi.java` 约 538 行
- **代码**：`noteOnTime[ch] = System.currentTimeMillis();`
- **性能影响**：
  - 系统调用需要进入内核（用户态→内核态切换）
  - J2ME 环境下 JNI 边界开销极大
  - 每个音符触发都调用（高频）
  - v1.0.31 完全没有此调用
- **后果**：
  - 音频线程卡顿
  - 即使 GUI 不卡，声音也会出现断续
  - PC 端尤其明显（模拟器开销更大）

**为什么 v1.0.31 更流畅**：
1. 没有强制单音逻辑
2. 没有 panic 全静音
3. **没有 System.currentTimeMillis() 系统调用**
4. 让 MIDI 合成器自由处理复音

---

### v1.0.37: 真机内存优化（不影响音质）

**改动**：
- 添加 `System.gc()` 优化真机内存释放
- 位置：解压、解析完成后
- **不影响音质**，纯内存管理

---

### v1.0.38: retriggerVel 遵循 volMode

**问题发现场景**：
- 用户设置 `volMode = API` 后，retriggered 音符音量仍然不对
- 原因：`retriggerVel()` 硬编码使用 CC7，没有遵循 volMode

**修复** (commit `7814ca9`):
```java
// Ay2Midi.java 约 570-580 行
private void retriggerVel(int ch, int vel8bit) {
    int velocity;
    if (volMode == 2) {
        // CC7+CC11+vel 三重
        velocity = vel8bit * 127 / 255;
        send(CC | ch, CC_VOLUME, velocity);
        send(CC | ch, CC_EXPRESSION, velocity);
    } else if (volMode == 1) {
        // CC7 only
        velocity = 127;
        send(CC | ch, CC_VOLUME, vel8bit * 127 / 255);
    } else {
        // API (volMode == 0)
        velocity = vel8bit * 127 / 255;
        // 不发送 CC7
    }
    send(NOTE_ON | ch, lastNoteOn[ch], velocity);
}
```

**行号**：`Ay2Midi.java:570-582`

---

### v1.0.39: 添加单音强制和复音策略开关（引入持久化 Bug）

**设计目标**：
- 让用户可以禁用 v1.0.32 的"真机优化"
- 恢复 v1.0.31 的 PC 友好行为

**新增字段** (commit `0d6e283`):

```java
// Ay2Midi.java 约 1287-1297 行
/** 单音强制：true=方波通道强制单音(真机)  false=允许复音(PC,v1.0.31行为) */
private boolean monoTone = true;   // 默认 true（真机优化）
public boolean getMonoTone() { return monoTone; }
public void setMonoTone(boolean m) { monoTone = m; }
public static final String[] MONO_NAMES = {"off", "on"};

/** 复音超限策略：0=LRU替换  1=panic全静音(v1.0.32) */
private int polyOverflow = 0;   // 默认 0=LRU（PC 友好）
public int getPolyOverflow() { return polyOverflow; }
public void setPolyOverflow(int p) { if (p >= 0 && p < 2) polyOverflow = p; }
public static final String[] OVERFLOW_NAMES = {"LRU", "panic"};
```

**修改后的 noteOn() 逻辑**：
```java
// Ay2Midi.java 约 520-565 行
private void noteOn(int ch, int note, int velocity) {
    // 1. 单音强制（可配置）
    if (monoTone && isToneChannel(ch)) {  // ← 现在可以关闭
        if (lastNoteOn[ch] >= 0) {
            send(NOTE_OFF | ch, lastNoteOn[ch], 0);
        }
    }
    
    // 2. 复音管理
    lastNoteOn[ch] = note;
    noteOnTime[ch] = System.currentTimeMillis();  // ← Bug #2 仍然存在！
    activeNotes++;
    if (activeNotes > polyLimit) {
        if (polyOverflow == 1) {  // ← 可配置
            panicAll();  // panic 模式
        } else {
            // LRU 替换：关掉最旧的音符
            int oldestCh = -1;
            long oldestTime = Long.MAX_VALUE;
            for (int i = 0; i < 16; i++) {
                if (lastNoteOn[i] >= 0 && noteOnTime[i] < oldestTime) {
                    oldestTime = noteOnTime[i];
                    oldestCh = i;
                }
            }
            if (oldestCh >= 0) {
                send(NOTE_OFF | oldestCh, lastNoteOn[oldestCh], 0);
                lastNoteOn[oldestCh] = -1;
                activeNotes--;
            }
        }
    }
    
    send(NOTE_ON | ch, note, velocity);
}
```

**UI 添加**：
- `PlayScreen.java` 行 79-80：`ROW_MONOTONE = 15`, `ROW_OVERFLOW = 16`
- 行 1943-1944：标签 `"Mono tone"`, `"Poly overflow"`
- 行 1986-1987：值显示
- 行 2118-2128：键盘处理

**配置复制已添加**：
```java
// Ay2Midi.java 约 818-819 行 (copyConfigFrom)
monoTone = src.monoTone;
polyOverflow = src.polyOverflow;

// 约 845-846 行 (copyConfigQuiet)
monoTone = src.monoTone;
polyOverflow = src.polyOverflow;
```

**致命遗漏**：
- ✗ **未添加到 `PlayScreen.applyPersisted()`**
- ✗ **未添加到 `PlayScreen.persist()`**
- ✗ **未扩展 `SettingsStore.FIELDS`**

**后果**：
- 用户调整 `monoTone = false` 后切曲，立即退回 `true`
- 每首曲目都要重新设置
- **这是用户最容易遇到的严重 bug**

---

### v1.0.40: 移除 System.currentTimeMillis() 系统调用（性能修复）

**问题发现**：
- 用户报告："为什么新版音符卡顿，旧版 GUI 卡顿但声音流畅？"
- 分析：音频线程应该完全独立，不受 GUI 影响

**根本原因定位**：
```bash
# v1.0.31 只在音量获取时调用
grep "System.currentTimeMillis" v1.0.31/Ay2Midi.java
# 216: return vtime >= 0 ? vtime : System.currentTimeMillis();
# 421: try { Thread.sleep(50); } ...
# ← noteOn() 中完全没有调用

# v1.0.32+ 在 noteOn() 中调用
grep "System.currentTimeMillis" v1.0.40/Ay2Midi.java
# 253: return vtime >= 0 ? vtime : System.currentTimeMillis();
# 538: noteOnTime[ch] = System.currentTimeMillis();  // ← 每个音符都调用！
```

**性能分析**：
- `System.currentTimeMillis()` 需要系统调用（用户态→内核态）
- J2ME 环境 JNI 开销极大
- 每个音符触发都调用（高频，可能每秒数十次）
- v1.0.31 完全没有这个开销

**修复方案** (commit `4377102`):
```java
// Ay2Midi.java 约 1283 行
// 替换：
// private final long[] noteOnTime = new long[16];  // LRU 时间戳
private long noteSeq;  // 轻量级递增序列号，代替 currentTimeMillis()
private final long[] noteOnSeq = new long[16];  // LRU 序列号（避免系统调用）

// 约 538 行，noteOn() 中：
// 替换：
// noteOnTime[ch] = System.currentTimeMillis();
noteOnSeq[ch] = ++noteSeq;  // 纯内存操作，零系统调用

// 约 545-555 行，LRU 替换逻辑：
int oldestCh = -1;
long oldestSeq = Long.MAX_VALUE;  // 改为比较序列号
for (int i = 0; i < 16; i++) {
    if (lastNoteOn[i] >= 0 && noteOnSeq[i] < oldestSeq) {
        oldestSeq = noteOnSeq[i];
        oldestCh = i;
    }
}

// 约 590 行，panicAll() 中：
// 替换：
// noteOnTime[c] = 0;
noteOnSeq[c] = 0;
```

**效果**：
- 恢复 v1.0.31 的音频流畅度
- LRU 替换逻辑仍然正常工作（只需比较大小）
- 零系统调用开销

---

### v1.0.41: 预设功能 + 持久化 Bug 修复

#### Part 1: 添加预设快速切换 (commit `66dc8ef`)

**设计目标**：
- 用户不想手动调 10+ 个选项
- 一键切换 PC/Nokia 优化配置

**新增代码**：
```java
// Ay2Midi.java 约 1299-1332 行
public static final String[] PRESET_NAMES = {"PC", "Nokia E5"};
public void applyPreset(int preset) {
    switch (preset) {
        case 0:  // PC 预设
            volMode = 0;           // API
            volFollowMode = 2;     // CC7
            monoTone = false;      // 允许复音
            polyOverflow = 0;      // LRU
            polyLimit = 128;       // 最大复音
            sqVolPct = 90;
            noiseVolPct = 80;
            envVolPct = 90;
            setEnvUni(false, 2);   // saw unison +2
            setEnvUni(true, 1);    // tri unison +1
            stereoMode = 0;        // ABC
            noiseMapMode = 1;      // C4 top
            forceRefreshAll();
            break;
        case 1:  // Nokia E5 预设
            volMode = 1;           // CC7
            volFollowMode = 0;     // retrig
            monoTone = true;       // 单音强制
            polyOverflow = 0;      // LRU
            polyLimit = 64;        // 真机限制
            sqVolPct = 100;
            noiseVolPct = 100;
            envVolPct = 100;
            setEnvUni(false, 2);   // saw unison +2
            setEnvUni(true, 0);    // tri unison off
            stereoMode = 6;        // MONO
            noiseMapMode = 1;      // C4 top
            forceRefreshAll();
            break;
    }
}
```

**UI 实现**：
```java
// PlayScreen.java 行 66: ROW_PRESET = 1
// 行 1931: case ROW_PRESET: return "Preset";
// 行 1975: case ROW_PRESET: return Ay2Midi.PRESET_NAMES[presetIdx];
// 行 2017-2022: 键盘处理
case ROW_PRESET: {
    presetIdx = (presetIdx + dir + Ay2Midi.PRESET_NAMES.length) 
                % Ay2Midi.PRESET_NAMES.length;
    ay.applyPreset(presetIdx);
    if (ay2 != null) ay2.applyPreset(presetIdx);
    break;
}
```

#### Part 2: 修复 PC 默认值 (commit `7b3cbc0`)

**问题**：
- 默认值是 Nokia 优化（`monoTone=true`, `polyLimit=64`）
- 但目标用户是 PC（模拟器）
- 切曲后退回 Nokia 默认值，PC 用户体验差

**修复**：
```java
// Ay2Midi.java 约 1277 行
// 改为：
private int polyLimit = 128;   // PC 默认 128

// 约 1287 行
// 改为：
private boolean monoTone = false;   // PC 默认 false（允许复音）
```

#### Part 3: 修复持久化 Bug (commit `34725d6`) - **最关键**

**问题发现场景**：
- 用户："切曲后 mono tone 无论如何都会变 on"
- 根本原因：**v1.0.39 添加字段时未持久化到 RMS**

**完整修复步骤**：

**1. 扩展 RMS 槽位**：
```java
// SettingsStore.java 行 12
// 改为：
static final int VERSION = 4;   // 4: PC 默认值 + monoTone + polyOverflow 持久化

// 行 14
// 改为：
public static final int FIELDS = 20 + 12;  // 30 → 32
```

**2. 添加恢复逻辑**：
```java
// PlayScreen.java applyPersisted() 约 307-313 行
if (v.length > 30) {
    ay.setMonoTone(v[30] != 0);
}
if (v.length > 31 && v[31] >= 0 && v[31] < 2) {
    ay.setPolyOverflow(v[31]);
}
```

**3. 添加保存逻辑**：
```java
// PlayScreen.java persist() 约 360-361 行
v[30] = ay.getMonoTone() ? 1 : 0;
v[31] = ay.getPolyOverflow();
```

**为什么这是最严重的 Bug**：
- 用户调整后切曲立即丢失
- 每首曲目都要重新设置
- 完全破坏用户体验
- **根本原因**：添加新字段时只做了一半工作

**完整的 6 步检查清单**：
1. ✓ `Ay2Midi.java`: 字段声明 + getter/setter
2. ✓ `Ay2Midi.java`: `copyConfigFrom()` 包含新字段
3. ✓ `Ay2Midi.java`: `copyConfigQuiet()` 包含新字段
4. ✗ `SettingsStore.java`: `FIELDS` 未扩展 ← **遗漏**
5. ✗ `PlayScreen.java`: `applyPersisted()` 未添加 ← **遗漏**
6. ✗ `PlayScreen.java`: `persist()` 未添加 ← **遗漏**

---

## 总结：为什么 v1.0.31 最好，v1.0.41 才修复？

### v1.0.31 优势
1. **没有单音强制**：让 MIDI 合成器自由处理复音
2. **没有 panic 全静音**：不会出现音乐"空洞"
3. **没有 System.currentTimeMillis()**：零系统调用，音频流畅
4. **代码简单**：没有复杂的复音管理逻辑

### v1.0.32-v1.0.36 劣化原因
1. **Bug #1**：复音超限 panic 全静音（PC 合成器能处理 128+ 复音）
2. **Bug #2**：每个音符调用 System.currentTimeMillis()（高频系统调用）
3. **强制单音**：方波通道不允许复音（PC 合成器能智能混音）

### v1.0.37-v1.0.39 部分修复
- v1.0.37：内存优化（不影响音质）
- v1.0.38：retriggerVel 遵循 volMode
- v1.0.39：添加开关，但**持久化 Bug 导致无法使用**

### v1.0.40-v1.0.41 完整修复
- v1.0.40：移除 System.currentTimeMillis()，恢复流畅度
- v1.0.41：修复持久化 Bug，用户调整才能生效
- **PC 推荐设置**：
  ```
  Preset = PC
  Mono tone = off
  Poly overflow = LRU
  Max poly = 128
  Note-on vol = API
  Vol follow = CC7
  ```

---

## 新增配置选项的完整清单

**每次添加新的用户可配置选项时，必须完成以下所有步骤，缺一不可。**

### 完整的 12 步流程

#### 步骤 1-3: Ay2Midi.java - 字段和配置复制

**位置**：`tools/vgm-browser-single/src/com/denjhang/vgm/Ay2Midi.java`

**步骤 1：声明字段和方法**（约 1280-1350 行区域）
```java
// 示例：添加 monoTone 选项
private boolean monoTone = false;   // PC 默认 false
public boolean getMonoTone() { return monoTone; }
public void setMonoTone(boolean m) { monoTone = m; }
public static final String[] MONO_NAMES = {"off", "on"};
```

**步骤 2：copyConfigFrom() 中添加**（约 797-821 行）
```java
public void copyConfigFrom(Ay2Midi src) {
    // ... 现有字段 ...
    monoTone = src.monoTone;        // ← 必须添加
    polyOverflow = src.polyOverflow; // ← 必须添加
    forceRefreshAll();
}
```

**为什么**：双芯片模式时，ay2 从 ay 复制配置。遗漏会导致 ay2 使用默认值。

**步骤 3：copyConfigQuiet() 中添加**（约 824-847 行）
```java
public void copyConfigQuiet(Ay2Midi src) {
    // ... 现有字段 ...
    monoTone = src.monoTone;        // ← 必须添加
    polyOverflow = src.polyOverflow; // ← 必须添加
}
```

**为什么**：录制 SMF/寄存器时，gen 从 ay 静默同步配置。遗漏会导致录制结果错误。

---

#### 步骤 4-5: SettingsStore.java - 扩展 RMS

**位置**：`tools/vgm-browser-single/src/com/denjhang/vgmbrowser/SettingsStore.java`

**步骤 4：递增 VERSION**（行 12）
```java
// 每次修改槽位布局必须递增版本号
static final int VERSION = 4;   // 3 → 4
```

**为什么**：版本不匹配会触发配置重置，防止读取错位数据。

**步骤 5：扩展 FIELDS**（行 14）
```java
// 每个新字段需要 1 个槽位
public static final int FIELDS = 20 + 12;  // 30 → 32（新增 2 个字段）
```

**为什么**：槽位不足会导致数组越界或数据截断。

---

#### 步骤 6-12: PlayScreen.java - UI 和持久化

**位置**：`tools/vgm-browser-single/src/com/denjhang/vgmbrowser/PlayScreen.java`

**步骤 6：添加行号常量**（约 60-96 行）
```java
private static final int ROW_MONOTONE = 16;
private static final int ROW_OVERFLOW = 17;
// 后续所有行号 +2
private static final int ROW_HEADER_PITCH = 18;  // 原 16 → 18
// ... 依次类推
```

**步骤 7：更新 TOTAL_ROWS**（约 96 行）
```java
private static final int TOTAL_ROWS = 31 + Ay2Midi.SLOTS;  // 29 → 31
```

**步骤 8：rowLabel() 中添加标签**（约 1930-1955 行）
```java
private String rowLabel(int r) {
    switch (r) {
        // ... 现有选项 ...
        case ROW_MONOTONE: return "Mono tone";
        case ROW_OVERFLOW: return "Poly overflow";
```

**步骤 9：rowValue() 中添加值显示**（约 1975-2000 行）
```java
private String rowValue(int r) {
    switch (r) {
        // ... 现有选项 ...
        case ROW_MONOTONE: return Ay2Midi.MONO_NAMES[ay.getMonoTone() ? 1 : 0];
        case ROW_OVERFLOW: return Ay2Midi.OVERFLOW_NAMES[ay.getPolyOverflow()];
```

**步骤 10：applyPersisted() 中添加恢复逻辑**（约 282-328 行）
```java
private void applyPersisted() {
    int[] v = SettingsStore.load();
    if (v == null) return;
    
    // ... 现有字段 ...
    
    if (v.length > 30) {
        ay.setMonoTone(v[30] != 0);
    }
    if (v.length > 31 && v[31] >= 0 && v[31] < 2) {
        ay.setPolyOverflow(v[31]);
    }
```

**为什么这是最容易遗漏的步骤**：
- 切曲时创建新 PlayScreen，调用 `applyPersisted()` 恢复配置
- 遗漏会导致新字段使用默认值，用户调整丢失
- **这是 v1.0.39 的致命 Bug**

**步骤 11：persist() 中添加保存逻辑**（约 331-365 行）
```java
private void persist() {
    int[] v = new int[SettingsStore.FIELDS];
    v[0] = SettingsStore.VERSION;
    
    // ... 现有字段 ...
    
    v[30] = ay.getMonoTone() ? 1 : 0;
    v[31] = ay.getPolyOverflow();
    
    SettingsStore.save(v);
}
```

**为什么**：用户调整后切曲/退出时保存。遗漏会导致重启后配置丢失。

**步骤 12：handleLR() 中添加键盘处理**（约 2014-2150 行）
```java
private void handleLR(int dir) {
    switch (cursor) {
        // ... 现有选项 ...
        
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

---

### 常见错误和后果对照表

| 遗漏步骤 | 文件 | 行号区域 | 后果 | 症状 |
|---------|------|---------|------|------|
| 步骤 2 | Ay2Midi.java | ~797-821 | ay2 配置错误 | 双芯片曲目设置不一致 |
| 步骤 3 | Ay2Midi.java | ~824-847 | 录制配置错误 | SMF/寄存器导出错误 |
| 步骤 5 | SettingsStore.java | ~14 | 数组越界/截断 | 崩溃或数据损坏 |
| **步骤 10** | **PlayScreen.java** | **~282-328** | **切曲后设置丢失** | **用户每曲都要重新调整** |
| 步骤 11 | PlayScreen.java | ~331-365 | 重启后设置丢失 | 配置无法持久化 |
| 步骤 12 | PlayScreen.java | ~2014-2150 | 无法调整选项 | 左右键无响应 |

**最严重的是步骤 10**：用户立即遇到，体验极差。

---

## 代码审查清单

**提交前必须逐项检查，打勾确认：**

### Ay2Midi.java
- [ ] 字段声明（private + 默认值）
- [ ] getter 方法（public）
- [ ] setter 方法（public + 参数校验）
- [ ] 选项名称数组（public static final String[]）
- [ ] `copyConfigFrom()` 包含新字段（~797-821 行）
- [ ] `copyConfigQuiet()` 包含新字段（~824-847 行）

### SettingsStore.java
- [ ] `VERSION` 已递增（行 12）
- [ ] `FIELDS` 已扩展（行 14）
- [ ] 新增槽位数 = 新增字段数

### PlayScreen.java
- [ ] 行号常量已添加（~60-96 行）
- [ ] 后续行号已全部更新
- [ ] `TOTAL_ROWS` 已更新（~96 行）
- [ ] `rowLabel()` 已添加（~1930-1955 行）
- [ ] `rowValue()` 已添加（~1975-2000 行）
- [ ] `applyPersisted()` 已添加（~282-328 行）← **最关键**
- [ ] `persist()` 已添加（~331-365 行）
- [ ] `handleLR()` 已添加（~2014-2150 行）

### 功能测试
- [ ] 编译通过
- [ ] 进入设置，选项显示正常
- [ ] 左右键可以调整选项
- [ ] 调整后切曲，设置保持不变
- [ ] 退出应用，重启后设置保持
- [ ] 双芯片曲目，两个芯片设置一致

---

## 当前槽位分配 (v1.0.41, VERSION=4, FIELDS=32)

```
v[0]  = VERSION (4)
v[1]  = stereoMode           // 立体声模式
v[2]  = octShift             // 八度偏移
v[3]  = noiseMapMode         // 噪音映射
v[4]  = envOctSaw            // 锯齿波包络八度
v[5]  = envOctTri            // 三角波包络八度
v[6]  = envUniSaw            // 锯齿波 unison
v[7]  = envUniTri            // 三角波 unison
v[8]  = buzzVol              // 蜂鸣器音量
v[9]  = bendSensPct          // 弯音灵敏度
v[10] = bendRangePct         // 弯音范围
v[11] = masterVol            // 主音量
v[12] = loopIdx              // 循环次数
v[13] = shuffle             // 随机播放 (0/1)
v[14] = engineIdx            // 引擎 (0=MIDI, 1=Tone)
v[15] = chunkIdx             // 生成器缓冲区大小
v[16..23] = progMap[0..7]    // 乐器槽位（8 个）
v[24] = sqVolPct             // 方波音量百分比
v[25] = noiseVolPct          // 噪音音量百分比
v[26] = envVolPct            // 包络音量百分比
v[27] = volFollowMode        // 音量跟随模式 (0=retrig, 1=CC11, 2=CC7)
v[28] = volMode              // Note-on 音量模式 (0=API, 1=CC7, 2=CC7+CC11+vel)
v[29] = polyLimit            // 最大复音数 (16/32/64/128)
v[30] = monoTone             // 单音强制 (0=off, 1=on)
v[31] = polyOverflow         // 复音超限策略 (0=LRU, 1=panic)
```

**下次新增字段从 v[32] 开始，记得：**
1. `SettingsStore.VERSION` 递增为 5
2. `SettingsStore.FIELDS` 改为 33
3. 完成 12 步清单

---

## 性能优化指南

### 音频关键路径的禁忌操作

**在 `noteOn()`, `noteOff()`, `updateTone()` 等高频调用的方法中，绝对禁止：**

1. **系统调用**：
   - `System.currentTimeMillis()` ← **v1.0.32-v1.0.39 的致命错误**
   - `System.nanoTime()`
   - 文件 I/O
   - 网络操作

2. **重量级同步**：
   - `synchronized` 大段代码
   - `wait()` / `notify()`
   - 复杂的锁竞争

3. **内存分配**：
   - `new Object()`
   - 字符串拼接（`+`）
   - 数组扩容

**推荐做法**：
- 用递增计数器代替时间戳（v1.0.40 的修复）
- 预分配数组，避免动态分配
- 用整数比较代替对象比较

### v1.0.40 性能修复案例

**错误做法** (v1.0.32-v1.0.39):
```java
// 每个音符都调用系统 API
noteOnTime[ch] = System.currentTimeMillis();  // 用户态→内核态切换

// LRU 查找
for (int i = 0; i < 16; i++) {
    if (noteOnTime[i] < oldestTime) {  // 比较时间戳
        oldestTime = noteOnTime[i];
        oldestCh = i;
    }
}
```

**正确做法** (v1.0.40+):
```java
// 纯内存操作
noteOnSeq[ch] = ++noteSeq;  // 递增计数器，零系统调用

// LRU 查找（逻辑相同）
for (int i = 0; i < 16; i++) {
    if (noteOnSeq[i] < oldestSeq) {  // 比较序列号
        oldestSeq = noteOnSeq[i];
        oldestCh = i;
    }
}
```

**性能提升**：
- 消除系统调用开销（J2ME 环境下可达数十微秒）
- 恢复 v1.0.31 的流畅度
- LRU 逻辑功能完全相同

---

## 附录：Git 提交历史

### v1.0.37 - v1.0.41 完整提交记录

```
7a50d72  v1.0.31 - 黄金版本（基准）
         无单音强制，无 panic，无 currentTimeMillis()

[v1.0.32-v1.0.36 引入性能问题]
         添加单音强制 + panic + currentTimeMillis()

b8e3d5a  v1.0.37 - 真机内存优化
         添加 System.gc()，不影响音质

7814ca9  v1.0.38 - retriggerVel 遵循 volMode
         修复 retriggerVel() 未遵循 volMode 的问题
         行号：Ay2Midi.java:570-582

0d6e283  v1.0.39 - 添加单音强制和复音策略开关
         新增 monoTone, polyOverflow 字段
         ✓ 添加到 copyConfigFrom/copyConfigQuiet
         ✗ 未添加到 applyPersisted/persist
         ✗ 未扩展 SettingsStore.FIELDS
         → 引入持久化 Bug

4377102  v1.0.40 - 移除 System.currentTimeMillis()
         noteOnTime[] → noteOnSeq[]
         currentTimeMillis() → ++noteSeq
         恢复 v1.0.31 流畅度

66dc8ef  v1.0.41 - 添加预设功能
         applyPreset() 一键切换 PC/Nokia 配置
         
7b3cbc0  v1.0.41 - 修改默认值为 PC 优化
         monoTone: true → false
         polyLimit: 64 → 128

183c0e0  v1.0.41 - 简化预设 UI
         显示当前预设名称，和其他选项一致

0dc564e  v1.0.41 - 预设 UI 标准化
         添加 presetIdx 字段，循环切换

34725d6  v1.0.41 - 修复持久化 Bug（最终修复）
         SettingsStore.FIELDS: 30 → 32
         SettingsStore.VERSION: 3 → 4
         applyPersisted() 添加 v[30]/v[31] 恢复
         persist() 添加 v[30]/v[31] 保存
         + 新增 DEVELOPMENT.md 开发文档
```

---

## 文档维护

**本文档必须保持同步更新：**
- 每次添加新配置选项时，更新"槽位分配"表
- 遇到新的 bug 时，添加到"历史 bug 案例"
- 新的性能问题，添加到"性能优化指南"

**最后更新**：2024-10-25, v1.0.41 持久化 Bug 修复
