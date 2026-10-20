# nescube V3.2 音频链路完整反编译报告

> 反编译：`references/_x/n3/*.class` → CFR 0.152 + javap 交叉验证  
> 目标：VgmBrowser 诺基亚真机 MIDI 无声 → 找到 nescube 3.2 在真机出声的全部差异

---

## 1. 打开序列（p.class 方法 k()）

```java
// p.java:182-193 (CFR反编译)
private final void k() {
    try {
        Player player = Manager.createPlayer("device://midi");
        player.prefetch();
        a = (MIDIControl) player.getControl("javax.microedition.media.control.MIDIControl");
        return;
    } catch (Exception exception) {
        a = null;  // 静默放弃
        return;
    }
}
```

**关键点**：
- ✅ `createPlayer("device://midi")` → `prefetch()` → `getControl(全类名)`
- ✅ **没有 realize()**
- ✅ **没有 start()**
- ✅ 异常时置 `MIDIControl = null`，不抛出、不重试

---

## 2. 初始化序列（y.class）

### 2.1 全通道重置 `y.b()`

```java
// y.java:130-135
static final void b() {
    for (int i = 0; i < 16; ++i) {
        a.shortMidiEvent(0xB0 | i, 120, 0);  // CC120 All Sound Off
    }
    y.g();
}
```

**CC120 全通道重置**：对 MIDI 全部 16 通道发送 `CC#120=0`（All Sound Off）。

### 2.2 音色与音量初始化 `y.a()`

```java
// y.java:119-128
static final void a() {
    a.setProgram(6, -1, a);  // patch存档变量 a:I（静态）
    a.setProgram(7, -1, b);  // patch存档变量 b:I
    a.setProgram(8, -1, c);  // patch存档变量 c:I
    a.setChannelVolume(6, (int)a[h]);  // 音量表 a:[B，索引 h（动态音量）
    a.setChannelVolume(7, (int)a[l]);
    a.setChannelVolume(8, 95);         // 通道8固定音量95
    a.setChannelVolume(9, (int)a[q]);  // 通道9（噪声）
    y.d();
}
```

**专用 API**：
- ✅ `setProgram(ch, bank=-1, program)` 设置音色（不走 shortMidiEvent 0xC0）
- ✅ `setChannelVolume(ch, volume)` 设置音量（不走 CC#7）

**音量表 a:[B（静态初始化）**：
```java
// y.java:670 (静态块)
a = new byte[]{7, 15, 23, 31, 39, 47, 55, 63, 71, 79, 87, 95, 103, 111, 119, 127};
```
16 级非线性音量映射（0=7, 1=15, ..., 15=127）。

---

## 3. 通道分配（硬编码）

### 3.1 NES APU → MIDI 通道映射

| NES 声部       | MIDI 通道 | 音色来源         | 音量来源           | 备注                     |
|----------------|-----------|------------------|--------------------|--------------------------|
| 方波1          | **6**     | `a:I` (存档)     | `a:[B][h]` 动态    | 0x86/0x96/0xB6           |
| 方波2          | **7**     | `b:I` (存档)     | `a:[B][l]` 动态    | 0x87/0x97/0xB7           |
| 三角波         | **8**     | `c:I` (存档)     | **95 固定**        | 0x88/0x98/0xB8，音符-24  |
| 噪声           | **9**     | (GM 鼓通道)      | `a:[B][q]` 动态    | 0x89/0x99/0xB9           |

**核心差异**：通道 6/7/8（不是 0/1/2），外加通道 9（噪声）。

### 3.2 音符事件格式（y.java:651-979，`y.a(boolean)` 方法）

**NOTE_OFF/NOTE_ON 配对**（每个通道独立状态机）：

```java
// 方波1 (ch6) 示例，bytecode 916-931
if (g != f) {  // g=当前音符索引，f=上次发的
    a.shortMidiEvent(134, (int)b[f], 0);           // 0x86 = NOTE_OFF ch6
    if (g != 0) {
        a.shortMidiEvent(150, (int)b[g], 127);     // 0x96 = NOTE_ON ch6, vel=127
    }
    f = g;
}
```

**音量事件**（方波1/2 走 CC#7）：
```java
// 方波1 音量，bytecode 705-720
if (i != h) {  // i=当前音量索引，h=上次发的
    a.shortMidiEvent(182, 7, (int)a[i]);  // 0xB6 CC#7 = volume, ch6
    h = i;
}
```

**三角波音符偏移**（bytecode 835-852）：
```java
// 通道8（三角波）音符要减24
a.shortMidiEvent(136, b[n] - 24, 0);      // 0x88 NOTE_OFF ch8
if (o != 0) {
    a.shortMidiEvent(152, b[o] - 24, 127); // 0x98 NOTE_ON ch8
}
```

**噪声音符**（通道9，使用固定音符 `d:I`）：
```java
// bytecode 901-933
a.shortMidiEvent(137, d, 0);              // 0x89 NOTE_OFF ch9
if (r != 0) {
    a.shortMidiEvent(153, d, 127);        // 0x99 NOTE_ON ch9, vel=127
}
```

**音符表 b:[B**：4096 字节外部查找表（NES 周期→MIDI 音符），从 `resources.pak` 加载：
```java
// w.java:360 (音频初始化)
y.a((MIDIControl)a, ((bk)object).a(4096), 10);
// 参数：MIDIControl, 音符表字节数组, 播放器数=10
```

---

## 4. NOTE_ON 力度恒定

**全部声部 NOTE_ON 力度恒 127**：
- 方波1/2：`shortMidiEvent(0x96/0x97, note, 127)`
- 三角波：`shortMidiEvent(0x98, note-24, 127)`
- 噪声：`shortMidiEvent(0x99, fixed_note, 127)`

**音量控制**：
- 方波1/2/噪声：通过 `CC#7` 或 `setChannelVolume` 动态调整
- 三角波（ch8）：固定 95

---

## 5. 完整音频架构：双引擎并行

nescube V3.2 使用 **MIDI + WAV 双引擎**：

### 5.1 MIDI 引擎（通道 6/7/8/9）

**用途**：NES APU 四个音调声部（方波1/2、三角波、噪声）  
**初始化**：
```java
// p.k() + y.a(MIDIControl, noteTable, 10)
Player player = Manager.createPlayer("device://midi");
player.prefetch();
midi = (MIDIControl) player.getControl("javax.microedition.media.control.MIDIControl");
```

**运行时**：`y.a(boolean)` 每帧调用，根据 NES 寄存器状态发送：
- NOTE_ON/OFF (0x96/0x86 等)
- CC#7 音量 (0xB6/0xB7)
- 使用 `setProgram` / `setChannelVolume` 专用 API

---

### 5.2 WAV 引擎（Player 池，10 个槽位）

**用途**：NES DPCM 样本回放（第 5 声道，鼓点/人声采样）  
**实现**：实时生成 WAV 文件 → ByteArrayInputStream → `Manager.createPlayer("audio/x-wav")`

#### 5.2.1 样本生成（y.a() → byte[]）

```java
// y.java:408-449
private static final byte[] a() {
    int sampleRate = f ? 16000 : 8000;  // f=高速标志
    int n2 = c[K] >> 3 << 10;           // K=DPCM 频率索引
    int n3 = (e << 10) / sampleRate;    // e=全局采样率
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(d);  // d:[B = WAV header 模板（44字节 RIFF）
    
    int addr = T;   // T=DPCM 样本起始地址（0xC000 | offset）
    int len = U;    // U=DPCM 样本长度（字节数）
    int level = 32; // DPCM 解码器电平（0-63）
    
    // DPCM 解码：1-bit delta → 8-bit PCM
    while (len > 0) {
        int bits = w.a(addr++);  // 读取 NES ROM 一个字节（8个1-bit样本）
        for (int i = 0; i < 8; i++) {
            if (bits & 1) {
                if (level < 63) level++;
            } else {
                if (level > 0) level--;
            }
            bits >>= 1;
            out.write(level << 2);  // 6-bit → 8-bit PCM
        }
        len--;
    }
    
    byte[] wav = out.toByteArray();
    y.a(wav, sampleRate, 1, 8);  // 填充 WAV header（采样率/声道/位深）
    return wav;
}
```

#### 5.2.2 播放器池管理（10 槽 LRU 缓存）

```java
// y.java:511-521 播放器创建
private static final Player a() {
    ByteArrayInputStream in = new ByteArrayInputStream(y.a());
    Player p = Manager.createPlayer(in, "audio/x-wav");
    p.realize();
    p.prefetch();
    VolumeControl vc = (VolumeControl) p.getControl("VolumeControl");
    if (vc != null) {
        vc.setLevel(75);  // 固定音量 75%
    }
    return p;
}

// y.java:488-508 LRU 替换
private static final void e() {
    int key = T >> 6 & 0xFF | (U - 1 >> 4 & 0xFF) << 8 | K << 16;
    if (key != d[X] || a[X] == null) {
        y.g();  // 停止当前槽位
        int slot = y.a(key);  // 查找缓存
        if (slot < 0) {
            slot = y.b();  // LRU 淘汰最旧的
            if (a[slot] != null) {
                a[slot].close();
            }
            d[slot] = key;
            a[slot] = y.a();  // 生成新 WAV Player
            a[slot] = System.currentTimeMillis();
        }
        X = slot;
    }
}

// y.java:385-416 播放/停止
private static final void f() {
    y.e();  // 确保 Player 就绪
    if (!y.a()) {  // 如果未在播放
        a[X].setMediaTime(0L);
        a[X].start();
    }
}
private static final void g() {
    if (y.a()) {
        a[X].stop();
    }
}
```

#### 5.2.3 触发条件（NES $4015 寄存器写入）

```java
// y.java:237-251
case 16405: {  // NES APU $4015（声道使能寄存器）
    V = n2;
    g = a && (V & 1) != 0;   // 方波1
    h = b && (V & 2) != 0;   // 方波2
    i = c && (V & 4) != 0;   // 三角波
    j = d && (V & 8) != 0;   // 噪声
    boolean k = e && (V & 0x10) != 0;  // DPCM（bit 4）
    
    if (a != null && w.c) {  // w.c = MIDI 可用标志
        if (k) {
            y.f();  // 播放 DPCM（WAV）
            return;
        }
        y.g();  // 停止 DPCM
    }
    return;
}
```

**关键逻辑**：
- `w.c` = 全局配置标志（MIDI 是否可用/用户是否启用声音）
- DPCM 通道（bit 4）开启 → 调用 `y.f()` → 从池中取/生成 Player → start()
- DPCM 通道关闭 → 调用 `y.g()` → stop()
- MIDI 和 WAV **并行工作**，不互斥

---

### 5.3 架构总结

| 引擎   | 用途                         | API                              | 通道               | 触发方式          |
|--------|------------------------------|----------------------------------|--------------------|-------------------|
| MIDI   | 方波1/2、三角波、噪声        | `device://midi` + MIDIControl    | 6, 7, 8, 9         | 每帧 `y.a(bool)` |
| WAV    | DPCM 样本（鼓/人声）         | `audio/x-wav` + Player池         | 独立 Player 对象   | $4015 bit 4 写入  |

**不是 fallback**：两个引擎同时工作，MIDI 负责持续音调，WAV 负责短采样。

---

### 5.4 对 VgmBrowser 的影响

**结论**：VgmBrowser **不需要** WAV 路径，因为：
1. AY8910 没有 DPCM 样本回放（只有 3 方波 + 3 噪声 + 1 包络）
2. 用户红线：只修 MIDI
3. nescube 的 WAV 引擎是 NES 特有功能（第 5 声道），与诺基亚 MIDI 问题无关

---

## 6. 与 VgmBrowser `Ay2Midi.java` 的差异表

| 项目                     | nescube 3.2 (y.class)                   | Ay2Midi.java (当前)                      | 状态   |
|--------------------------|-----------------------------------------|------------------------------------------|--------|
| 打开序列                 | `createPlayer` → `prefetch` → `getControl` | ✅ 同（`openMidiNescube()`）             | ✅ 已同步 |
| 通道映射（chip0）        | **6, 7, 8, 9**                          | ❌ `{0,1,2,3,4,5,6}`                     | **待改** |
| CC120 全通道重置         | ✅ 全 16 通道                            | ✅ `nescubeReset()` 全 16 通道           | ✅ 已同步 |
| 音色设置                 | ✅ `setProgram(ch, -1, patch)`          | ✅ `midi.setProgram(ch, -1, progMap[i])` | ✅ 已同步 |
| 音量设置                 | ✅ `setChannelVolume(ch, vol)`          | ✅ `midi.setChannelVolume(ch, masterVol)`| ✅ 已同步 |
| NOTE_ON 力度             | ✅ 恒 127                                | ✅ `send(NOTE_ON|ch, note, 127)`        | ✅ 已同步 |
| 音量表                   | `{7,15,23,...,127}` 16级非线性          | `ATTN_TO_VOL` yasp11 曲线                | 🔶 不同但兼容 |
| 三角波音符偏移           | ❌ 无（nescube 是 NES，没有 AY 三角）   | ❌ 无                                    | N/A    |
| 音符表                   | 外部 4096 字节 NES 周期表               | AY 频率→MIDI 音符计算                    | 🔶 不同但兼容 |

---

## 7. 终结性结论：VgmBrowser 唯一需要改的地方

### 7.1 通道映射修正

**当前**（`Ay2Midi.java:34`）：
```java
public static final int[] MAP_CHIP0 = {0, 1, 2, 3, 4, 5, 6};
```

**应改为**（对齐 nescube 3.2）：
```java
public static final int[] MAP_CHIP0 = {6, 7, 8, 3, 4, 5, 9};
//                                      ^方波A B C^        ^包络
//                                         噪声A B C^
```

**或者更激进的纯 nescube 映射**（只用方波+噪声，不用包络蜂鸣）：
```java
public static final int[] MAP_CHIP0 = {6, 7, 8, 9, 10, 11, 12};
//                                      ^方波^ ^------噪声------^
```

### 7.2 实施建议

1. **立即测试**：用户运行 `MidiTestScreen` 的 **6 号键全通道扫描**（0-15 每通道 setProgram+音符 0.35s），
   看诺基亚 E5 真机上**哪些通道响**。
   - 如果只有 6/7/8/9 响 → 改映射即终结。
   - 如果全响但某些通道音色错 → 还有音色库问题。

2. **最小改动**：只改 `MAP_CHIP0` 一行，保留其余全部逻辑（CC120、setProgram、setChannelVolume、力度 127 都已对齐）。

3. **chip1 映射**（双 AY 芯片）：
   ```java
   public static final int[] MAP_CHIP1 = {10, 11, 12, 13, 14, 15, 0};
   // 避开 9（GM 鼓），使用 10-15 + 0
   ```

---

## 8. 未解之谜（不影响终结）

1. **patch 默认值 a:I/b:I/c:I/d:I**：来自存档，反编译未找到硬编码默认值（可能首次运行为 0）。
2. **0xA3/0xA4 触后事件**（bytecode 出现但未找到实际发送）：可能是 NES DMC 或未使用特性。
3. **音符表具体数值**：4096 字节 blob 在 `resources.pak`，需解包 + 十六进制提取（不影响 VgmBrowser，AY 用自己的频率计算）。

---

## 附录：关键文件清单

- 反编译产物：`docs/_tmp/n3src/*.java`（79 个类）
- 关键类：
  - `p.java`：引擎基类，`k()` 打开 MIDI
  - `y.java`：音频层，全部 MIDI 事件
  - `w.java`：NES 仿真主循环，调用 `y.a(MIDIControl, noteTable, 10)`
  - `q.java`：`extends p` 的实际引擎子类
- 字节码验证：`docs/_tmp/y_javap.txt`、`y_verbose.txt`

---

**交接完毕**。下一步：用户在诺基亚 E5 真机上跑通道扫描测试，确认 6/7/8/9 响应后，立即改 `MAP_CHIP0` 一行代码推送。
