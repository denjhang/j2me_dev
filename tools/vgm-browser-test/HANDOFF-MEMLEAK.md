# 交接：内存泄漏持续检查（vgm-test-harness 分支）

**建立**：2026-10-28。**任务**：在新窗口继续用测试器分支，检查当前状态下有没有内存泄漏。
这是**持续性监控任务**，不是修功能。P3（超堆曲目报错跳过）用户明确：**可实现，但没必要做**。

## 当前状态（接手时的事实，勿重查）

- 分支 `vgm-test-harness`，HEAD=`0e17192`（已 push）。P1 文件引擎、P2 跳转均已验证入库。
- 文件引擎基线（2026-10-26 jmap，DEVELOPMENT.md「文件引擎」章节有表）：
  - `javax.sound.midi.MidiEvent`/`FastShortMessage` ≈ **50,227**（仅当前一首展开）
  - old gen 常驻 ≈ **10.7MB** = 当前曲 regF + 当前曲 SMF 展开（设计内，非泄漏）
  - `[PERF]` 每秒 +7MB 锯齿 = 年轻代（重放/绘制垃圾），Young GC 全额回落（-51MB）→ 正常
- 下一步路线是 P4 融合主线，泄漏检查在融合前做完、留下干净基线。

## 怎么跑

```bash
# 1) 构建（build.sh 的管道会吞 javac 退出码，编译失败也会打出"成功"字样并
#    打出缺类的半套 jar，模拟器起不来。稳妥做法=手动，且验证 jar 里有 PlayScreen）：
cd tools/vgm-browser-test && find src -name '*.java' > .sources
/e/working/zcode-projects/j2me_dev/tools/jdk8/bin/javac.exe -J-Duser.language=en \
  -source 1.4 -target 1.4 -encoding UTF-8 \
  -bootclasspath "E:\\working\\zcode-projects\\j2me_dev\\references\\midplay\\MIDPlay-main\\lib\\cldc_1.1.jar;E:\\working\\zcode-projects\\j2me_dev\\references\\midplay\\MIDPlay-main\\lib\\midp_2.0.jar;E:\\working\\zcode-projects\\j2me_dev\\emulators\\kemnnx64\\uei\\jsr135.jar;E:\\working\\zcode-projects\\j2me_dev\\emulators\\kemnnx64\\uei\\jsr75_file.jar;E:\\working\\zcode-projects\\j2me_dev\\tools\\nokia-ui-api\\nokia-ui-api.jar" \
  -d classes @.sources
/e/working/zcode-projects/j2me_dev/tools/jdk8/bin/jar.exe cfm VgmBrowserTest.jar manifest.mf -C classes . -C res .

# 2) 内存曲线采样（它自己起 KEmulator，连播 17 首 2025-Turbosound 自动播放）：
bash tools/vgm-browser-test/test/memcurve.sh 24 30 memleak-<date>
#   → test-out/memcurve-<date>-*.hist / *.heap / .log
#   24 次 × 30s ≈ 12 分钟，覆盖多轮切曲。

# 3) 人工按键/看界面用 ./run-test.sh（stdout 重定向自定）。
```

禁忌：**禁开 JMX 端口**（实测让 KEmulator 起不来）；jmap 走 Attach API，PID 用
`tools/jdk8/bin/jps.exe -l | grep KEmulator` 找。

## 判定标准（什么算泄漏）

每轮切曲后对比直方图：

| 对象 | 合格 | 泄漏信号 |
|---|---|---|
| MidiEvent/FastShortMessage | 稳定在 ~50k 量级 | 随曲目数单调上涨 |
| `byte[]`（SMF/文件缓冲） | 每首转换后回落 | 逐首抬地板 |
| `long[]`（regF） | 保当前一首，release 后回到上一首水平 | 多首同驻 |
| old gen used | 切曲后回收，长期平坦 | 每轮切曲 +N MB 不回落 |
| PlayScreen 实例 | `PlayScreen.release #N` 计数=N，无残留 | release 数 < 切曲数 |

每首必打的合格日志：`[FILE] pipeline released (regF+file kept for vis/paint)`、
`[TEST] PlayScreen.release #N afterGc used=...`。**regF+file 保留一首给可视化是设计**，
不是泄漏；泄漏=保留两首以上。

## 泄漏嫌疑点（若曲线爬，按序查）

1. 新 PlayScreen 的 `gen.regF`/`file` 重建时旧引用是否断干净（release() 四连+System.gc）。
2. `layersWithoutFile` 重建路径（切曲空白修复引入）是否堆积图层对象。
3. SettingsStore/RMS 记录是否每曲复制字符串。
4. `pendingSmf`/`prepPlayer` 预备块（块模式遗产）在 FILE_MODE 下是否被 new 出来没释放。
5. `vgm2mid/*.mid` 在**磁盘**（文件系统），不在堆里，别算进泄漏。

## 收尾要求

结论（干净/泄漏点）写进 DEVELOPMENT.md「文件引擎」章节 + 更新"最后更新"行，
`test-out/memcurve-*` 保留。git add 对 `tools/` 需 `-f`（.gitignore 忽略 tools/）。
push：7892 代理（SpeedCat），节点偶发 TLS 挂——先
`curl -x http://127.0.0.1:7892 https://api.github.com/meta` 测隧道，挂了让用户换节点。
