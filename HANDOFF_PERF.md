# VgmBrowserSingle 性能调试交接文档

**建立日期**: 2026-10-25 20:15
**任务**: 定位并修复 VgmBrowserSingle（J2ME AY8910 VGM 播放器）的内存膨胀与双芯片卡顿
**建议**: 新窗口务必先读完本文档 + [tools/vgm-browser-single/DEVELOPMENT.md](./tools/vgm-browser-single/DEVELOPMENT.md) 的「性能分析进度」章节，再动手。

---

## 一、务必遵守的工作铁律（前一轮血泪教训）

1. **禁止全自动测试 + 仅看日志数字下结论**。前一轮 8 小时全自动跑、只看 fps/内存数字，零进度反而破坏了可视化。内存数字只能告诉你"在涨"，**必须用 jmap 抓对象级证据**。
2. **禁止 `reset --hard` + force push**。改坏了用 `git revert`，追加反向 commit；不要改写已推送历史。
3. **测试器在独立分支 `vgm-test-harness`**，主线 `midi-rt-player` 不动，除非验证完成才移植。
4. **可视化/音频/交互是否正常，必须由用户肉眼确认**——AI 看不见 KEmulator 屏幕、听不到声音。AI 不要自作主张说"已修复"。
5. **2MB 缓存是设计核心，禁止降档**。单遍转换 + 大缓存 = 中间零卡顿的根本，"修内存"不能靠缩小缓存。
6. **`tools/` 在 .gitignore 中**，新文件要 `git add -f`；已跟踪文件不受影响。

---

## 二、当前分支与产物状态

| 分支 | 最新 commit | 作用 |
|---|---|---|
| `midi-rt-player`（主线） | `afb878f` docs: 分配热点+架构计划 | 产品分支，代码 = v1.0.41 干净版，未做任何性能改动 |
| `vgm-test-harness`（测试器） | `5bf4bbe` test: buildChunk 分配消除 | 自动播放测试器，源码对齐 single v1.0.41 + 探针 + chunk.txt + buildChunk 优化（**未验证**） |

**主线代码状态**：干净，与 v1.0.41 发版一致。`tools/vgm-browser-single/src/` 22 文件已跟踪。坏改动（前一轮的 `recordingReg=false` 等）已全部清除，commit `0f4f2fd`/`b12e920` 已不可达。

**测试器分支状态**：自动播放线程 + 每秒 `[PERF]` 探针 + 块级探针 + chunk.txt 块档覆盖 + buildChunk 分配消除（**buildChunk 优化已编译通过但未 jmap 验证效果**）。

**测试器如何用**：
```bash
# 1. 起测试器（自动播 2025-Turbosound 双芯片目录）
./run-test.sh

# 2. 找 PID
tasklist /FI "IMAGENAME eq java.exe"

# 3. 抓堆（无需 JMX，jmap 用 Attach API 自动发现本机进程）
"tools/jdk8/bin/jmap.exe" -histo <PID>          # 全量
"tools/jdk8/bin/jmap.exe" -histo:live <PID>     # 强制 GC 后

# 4. 块档对比实验（无需重编）
echo 6 > emulators/kemnnx64/file/root/chunk.txt   # 128k
echo 9 > emulators/kemnnx64/file/root/chunk.txt   # 2M（默认）
rm emulators/kemnnx64/file/root/chunk.txt          # 恢复默认
```

**坑警示**：
- **JMX 端口会让 KEmulator 秒退**（实测 `-Dcom.sun.management.jmxremote.port=1099` 启动失败）。不要给 run-test.sh 加 JMX 参数，jmap Attach API 不依赖它。
- `-histo:live` 触发 GC，会让"稳态水位"看起来像"泄漏恒定"——必须配多时刻 + 强制 GC 后跌落一起看，**稳态不变 ≠ 泄漏**。

---

## 三、已确认的事实（jmap 三组实验交叉验证）

### 1. 每秒 +3~5MB 的分配热点（全部对应到代码行号）

| # | 位置 | 问题 | 估算 | 状态 |
|---|---|---|---|---|
| 1 | `PlayScreen.java:800-806` buildChunk 归并循环 | 每事件 `new long[]{ms,status,d1,d2}` 立即丢 | ~2-3 MB/s | **测试器已改**（commit `5bf4bbe`），未 jmap 验证 |
| 2 | `Ay2Midi.java:706-723` initEvents | 每块新建 `long[][]` | ~0.5-1 MB/s | 未动 |
| 3 | `SmfSeg.java:143-150` Builder.build | 双复制 byte[]（toByteArray 两次） | 峰值 ~4 MB/s | 未动 |
| 4 | `PlayScreen.java:1035-1046` playSeg 寄存器回放 | regF 常驻 7-15 MB 死水位（非分配，架构包袱） | 间接放大 GC | 未动 |

**约 2.5-4 MB/s 是我方可消除分配**，其余是 KEmulator 解析 SMF 时建 `javax.sound.midi.MidiEvent` 的固有成本。

### 2. 双芯片卡死机制（推理完整，待 -verbose:gc 抓 Full GC 时机佐证）

- 2MB 块单芯片≈42 万 MidiEvent/块，双芯片≈84 万（归并两路事件）
- 三代同驻稳态（当前+预备+上一块待 GC）≈ 3×单块 → 双芯片 2M 档稳态 ~120 万对象、~120 MB 死水位
- 大块事件存活时间长于 Survivor 晋升阈值 → 晋升老年代 → 等 Full GC 才收
- **Full GC STW 标记 120 万对象 = UI 冻结数秒 = 双芯片卡死**
- 单芯片 63 万对象/60MB → 偶发卡顿；双芯片翻倍 → 冻结

**KEmulator 源码佐证**（`emulators/KEmulator-nnmod/src/media/`）：
- `PlayerImpl.midi()` 行 230：`sequence = MidiSystem.getSequence(...)` 每块解析建 MidiEvent
- `PlayerImpl.start()` 行 790-796：全局路径 `EmulatorMIDI.start(this, (Sequence) sequence, ...)`，静态 `sequencer.setSequence(sequence)` 换不删旧
- `PlayerImpl.close()` 行 316-349：`players.remove(this)` + `sequence = null`，但旧 Sequence 要等下下次 GC
- `EmulatorMIDI` 静态单例 sequencer/synthesizer/receiver，跨 PlayerImpl 复用，**不是泄漏源**

### 3. 自我修正记录（前一轮初判错了）

- **初判**（上午实验 A，2M 档）：MidiEvent 42 万恒定 50 秒 → 误判为"永不释放 = 泄漏"
- **推翻**（下午实验 B，128k 档）：14.5 万→38 万→稳住，若真泄漏该无限涨
- **证伪**（B + 强制 GC）：38 万→15.7 万 → 证明可回收，无死引用
- **最终模型**：三代同驻 + 大块推进老年代 + Full GC STW = 真正的卡顿机制。**不是泄漏，是堆压力**。

完整数据表见 [DEVELOPMENT.md](./tools/vgm-browser-single/DEVELOPMENT.md) 「性能分析进度」章节。

---

## 四、下一步执行计划（按顺序）

### 阶段 A：验证 buildChunk 分配消除效果（最先做，门槛最低）

1. 切到 `vgm-test-harness` 分支（buildChunk 优化已在 commit `5bf4bbe`）
2. `./run-test.sh` 起测试器，自动播 2025-Turbosound
3. 每 30 秒抓一次 `jmap -histo`，对比改动前（基线在 DEVELOPMENT.md 实验表）
4. **预期**：`long[]` 和 `[Ljava.lang.Object;` 实例数明显下降，MidiEvent 不变（SMF 内容没变）
5. **若 `long[]` 没降**：检查是否真的没 `new long[]` 了（grep `new long\[\]` 应无 buildChunk 相关命中）
6. **请用户肉眼确认**：钢琴动画、双芯片颜色、音频正常 —— 改动是零行为变化，但必须确认

### 阶段 B：消除 #2 initEvents 分配

- `Ay2Midi.java:706-723` `initEvents()` 改为：常驻一个 `long[][]` 字段，每块 `reset` 后覆盖填充，不 new
- 编译验证 → jmap 看 `long[][]` 和 `[J` 下降 → 用户肉眼确认

### 阶段 C：消除 #3 SmfSeg 双复制

- `SmfSeg.java:143-150` `Builder.build()`：`tr.toByteArray()` + `new ByteArrayOutputStream` + `o.toByteArray()` 双复制
- 改为：Builder 内部维护单个 `byte[]` 缓冲，build 时一次输出
- 注意：这是热路径，要保证不破坏 SMF 字节正确性（用单芯片小曲对比生成字节是否一致）

### 阶段 D：架构级修复 —— VGM 单遍转换 + 可视化改读 MIDI（核心，解决切曲台阶）

> 受用户启发：缓存是为了单遍播放零卡顿，可视化不该再回放寄存器。

**当前架构（错）**：
```
VGM字节 ──解析──> gen.write() ──录制──> regF[]（寄存器历史，7~15MB，仅可视用）
                       │
                       └──录制──> recF[]（MIDI事件流，→ buildChunk → SMF → Player）
playSeg:  播 SMF 同时，把 regF[] 回放给 ay.write() 驱动 curNote[] 可视化
          ↑ regF 存在的唯一理由
```

**目标架构**：
```
VGM字节 ──单遍转换──> recF[]（MIDI事件流）──> buildChunk → SMF → Player
                          │
                          └──> 可视化渲染器直接读 recF[]（按通道号反推 curNote/curVol/noiseNote）
转换完成 → 释放 VGM字节 / 解析器 / regF（regF 整个删除）
```

**内存对比**（双芯片 3 分钟曲）：常驻从 ~20MB 降到 ~5MB。

**6 步实施**（测试器先做）：
1. 删 `regF[]` 录制（`Ay2Midi.write` 行 966-975 的 recordingReg 分支）
2. 删 `playSeg` 寄存器回放（`PlayScreen.java:1035-1046`）
3. 实现"可视化读 recF"渲染器：按 toneCh/noiseCh/buzzerCh 通道号解析 noteOn/noteOff 维护 curNote[]，双芯片用 chip1/chip2 通道映射区分颜色
4. 转换完成后 `file=null; parser=null; gen.regF=null`（释放 VGM）
5. jmap 对比常驻内存是否降到 ~5MB，"每秒涨"是否趋平
6. **用户肉眼确认**：钢琴动画、双芯片颜色、噪声/包络标记、**重点看同步蜂鸣器/包络动画**（R13 重触发率→buzzNote 是从寄存器语义来的，改 MIDI 语义可能丢失）

**风险与回退**：
- 可视化从"硬件寄存器语义"改"MIDI 事件语义"，可能丢失 R13 同步蜂鸣器等细节
- 回退方案：保留 regF 但只缓存可视相关寄存器子集（R8/R13 等），或只缓存到曲目结束不全程
- **任何回退都不要 `reset --hard`，用 `git revert`**

---

## 五、关键文件索引（行号以 single v1.0.41 为准）

| 文件 | 关键行 | 内容 |
|---|---|---|
| `tools/vgm-browser-single/src/com/denjhang/vgmbrowser/PlayScreen.java` | 800-806 | buildChunk 归并循环（#1 分配热点） |
| 同上 | 944-1014 | playSeg（块播放 + 寄存器回放驱动可视） |
| 同上 | 1035-1046 | playSeg 内 regF 回放（#4 架构包袱） |
| 同上 | 527-530 | BUF_BYTES 块档表（默认 idx=9=2MB） |
| 同上 | 389-439 | release()（切曲清理，Bug #3） |
| `tools/vgm-browser-single/src/com/denjhang/vgm/Ay2Midi.java` | 706-723 | initEvents()（#2 分配热点） |
| 同上 | 962-985 | write()（regF 录制入口，阶段 D 要删） |
| 同上 | 1534-1609 | visNote/visNoise/visBuzz（可视化读字段） |
| `tools/vgm-browser-single/src/com/denjhang/vgm/SmfSeg.java` | 143-150 | Builder.build()（#3 分配热点） |
| `tools/vgm-browser-single/DEVELOPMENT.md` | 895 起 | 完整性能分析进度 + 数据表 |

---

## 六、用户工作习惯与偏好（务必尊重）

- **看不见屏幕的能力，AI 必须靠用户肉眼确认 GUI 状态**：用户会文字描述动画在/不在。
- **要求"代码级理解优先于优化"**：动手前先读懂代码数据流，不要拍脑袋改。
- **要求每步小提交 + push**：不要攒大改一次推。
- **git 身份**：主线提交用 `676420807@qq.com`，前一轮坏 commit 误用了 `61811583+denjhang@users.noreply.github.com`（已不可达）。
- **中文沟通**，关键结论要写进 DEVELOPMENT.md 而非只在对话里。
- **禁止删功能式"优化"**：前一轮禁用 recordingReg 破坏可视化，用户严厉批评"自残式修复"。

---

## 七、环境与工具链速查

- 工作区：`E:\working\zcode-projects\j2me_dev`（Windows + Git Bash）
- JDK 8 绿色版：`tools/jdk8/`（含 jmap.exe / jhat.exe / jstack.exe / tools.jar，jvisualvm 无）
- KEmulator nnmod：`emulators/kemnnx64/`，虚拟 FS 根在 `emulators/kemnnx64/file/root/`
- 测试曲目：`emulators/kemnnx64/file/root/vgm/2025-Turbosound/`（17 个双芯片 vgm）
- KEmulator 源码：`emulators/KEmulator-nnmod/src/`（MMAPI 实现在 `media/javax/microedition/media/`）
- 构建：`./build.sh tools/vgm-browser-single`（主线）或 `./build.sh tools/vgm-browser-test`（测试器）
- 启动测试器：`./run-test.sh`（不带 JMX，配 jmap 提示）
- 启动主线：`./run-kemulator.sh "<jar 路径>"`

---

## 八、本次窗口已完成的事

- ✅ 修正前一轮"全自动测试破坏可视化"的失败，回滚 + 文档化（commit `f49baa8`）
- ✅ 建独立测试器分支 `vgm-test-harness`，源码对齐 v1.0.41 + 自动播放 + 探针
- ✅ 验证 jmap Attach API 可连 KEmulator 进程（不用 JMX）
- ✅ 三组 jmap 实验定位"每秒+3~5MB"分配热点（全部对应行号）
- ✅ 推翻上午"泄漏"初判，修正为"堆压力 + Full GC STW"模型
- ✅ buildChunk 分配消除（commit `5bf4bbe`，**未验证效果**）
- ✅ 完整分析写入 DEVELOPMENT.md（commit `afb878f`）

## 九、本次窗口没做完、交给下一个窗口

- ⬜ 阶段 A：jmap 验证 buildChunk 分配消除的实际效果（改前 vs 改后 long[] 实例数）
- ⬜ 阶段 B：initEvents 分配消除
- ⬜ 阶段 C：SmfSeg 双复制消除
- ⬜ 阶段 D：架构级 —— 删 regF + 可视化读 recF + 释放 VGM
- ⬜ 用 `-XX:+PrintGCDetails` 抓 Full GC 时机，定量佐证双芯片卡死
- ⬜ 任何主线修复前的人工肉眼确认（钢琴/双芯片颜色/蜂鸣器包络）

**第一件事就该做的**：阶段 A 的 jmap 验证。门槛最低、风险最小、能立刻看到分配消除是否有效。做完再决定 B/C/D 的优先级。
