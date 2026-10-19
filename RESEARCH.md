# J2ME 音频路线研究（源码级结论）

**来源**: 从 `Maemo_N900/PROGRESS.md` 第廿二章剪切（2026-10-19）
**状态**: 调研已完成，尚未开始写代码
**配套**: 平台选型与资料清单见 [HANDOFF.md](./HANDOFF.md)

> 本文保留原始调研的行文顺序，只把路径前缀从 `j2me/references/` 改成 `references/`
> （相对本工作区根目录）。

### 廿二补充：J2ME 参考项目落实（MIDPlay）
- **MIDPlay**（phd051199, ★80, J2ME 在线音乐播放器）已收至 references/midplay/
- 关键实证：`Manager.createPlayer(InputStream, contentType)` 流式播放范式，
  生产级细节全有：content-type 候选协商、创建重试+瞬态失败标记、
  分片创建防卡死、自定义 BufferedInputStream、URL/InputStream 双模式
- **J2ME PSG 播放器 = STC 计数器范式 PSG 核 + MIDPlay MediaResolver 骨架**：
  contentType 换 audio/x-wav，喂料换 PSG render()；content-type 协商降级
  探测照抄它的候选表思路
- MeBoy-Asha 源码实证 = MIDI 作弊（MIDI_DEVICE_LOCATOR+shortMidiEvent），
  反面教材，与我们无关；GitHub 无 J2ME 真软件合成先例 → 我们写即参考实现

### 廿二再补充：FPlay J2ME（carlosrafaelgn）——J2ME 播放器工程完整体
- 已收至 references/fplay/（86 个 java 文件，目标机型 Nokia 3120c 弱 S40，MIT）
- 播放=URL locator 三件套：file:///（本地）、**capture://radio?f=...M（把手机
  FM 调谐器硬件包成 Player 源，白嫖收音机功能）**、getEncodedURL（转义）
- EQ=JSR-234 amms EqualizerControl 全局控制+能力探测降级；
  可视化=getMediaTime 轮询；触屏+键盘双输入完整 UI
- J2ME 输出路线图齐了：FPlay 的 URL 壳+JSR-234 EQ + MIDPlay 的
  InputStream 流式骨架 + 我们的 PSG 核（伪 WAV 流/文件轮换降级矩阵）

### 廿二再补充2：加载器世界判决（FreeJ2ME 源码实锤）
- 证据：flashgame/j2me_ref/freej2me-web-main LibmediaPlayer.doPrefetch() =
  `MediaBridge.playerLoad(handle, readBytesAndClose(stream), type)`
  —— **InputStream 被一次性全读，无流式**（j2me-web 同源码）
- 因此伪 WAV 无限流方案在 FreeJ2ME/J2ME Loader 上死路；真机三档矩阵照旧
- **加载器模式**：PSG 离线渲染完整 WAV（3min@22050 单声道≈8MB，安卓堆无压力，
  渲染 1s 音频≈0.1-0.3s CPU）→ createPlayer(ByteArrayInputStream,"audio/x-wav")
  —— 1 秒内渲完全曲，体验=加载曲子
- 附：FreeJ2ME 模拟了 Nokia Sound/Samsung AudioClip/Vodafone SoundPlayer 等
  厂商私有 API，MIDI 作弊路径也兼容；真 PCM x-wav 通道反而是最干净的路线
- 双世界策略：同一 PSG 核，真机走实时流，加载器走离线渲染，配置项切换
- API 依赖定案（JSR-172 页面查证后）：PSG 服务器只用 **MIDP2.0 核心 GCF**
  （ServerSocketConnection/HttpConnection，2002 年起 mandatory，全系必带）
  + MMAPI(JSR-135)。不依赖任何可选包（JSR-172/234 之类机型随机发货的
  一律不碰）——FPlay 用 JSR-234 EQ 都得写降级探测，我们零可选包零探测

### 廿二再补充3：J2ME Loader 判决 + 空子（源码级，nikita36078/J2ME-Loader）
已收至 references/j2meloader/（2688★ 官方仓）
- **实时流式必死**：createPlayer(InputStream) 同步把流灌进临时文件到 EOF
  才返回（InternalDataSource 构造函数 while 循环）——无限流=永久阻塞
- **空子**：`stream.available()` 用于预分配临时文件长度（raf.setLength）。
  PSG=确定性合成，总字节数精确已知 → available() 报真实总长，
  高速合成灌满（1s渲3min曲）→ MediaPlayer 播临时文件，体验无感
- audio/x-wav 在 supportedTypes 白名单；自带 ffmpeg（ADPCM→PCM u8@16k 自动转换）
- 其他部件：ToneManager(playTone 原生)、MMFConverter(SMAF)、RecordPlayer、
  MidiInterface；发现 JL-Mod fork(woesss, 318★) 待后续对比媒体实现
- **双世界策略定稿**：真机=三档降级流式；加载器=离线渲染+available()空子。
  同一 PSG 核，配置开关切换投放方式

### 廿二再补充4：伪装在线流判决（J2ME Loader Manager.java 源码）
- createPlayer(locator) 分支表只有 midi/tone/file/capture://audio|image|video；
  **http:// 落入 BasePlayer() 静默空操作**——媒体层无 http 协议实现
  （org.microemu.cldc.http.Connection 只服务 MIDlet 自己的 GCF 网络调用）
- 服务器半边：ServerSocketConnection 有完整实现（MicroEmulator 系，
  包真 java.net.ServerSocket），Android 不限 loopback——服务器立得起来，
  甚至可反向利用：MIDlet 当服务器让 PC 浏览器遥控
- 结论：加载器世界=离线渲染唯一解；伪装在线流仅 S60 真机可行
  （MMAPI 原生 http locator，酷狗实证；代价=签名+权限+代理三笔税）

### 廿二终章：堆预算公式 + 双形态定稿（J2ME 音频收官）
- PCM 堆占用：8kHz/16bit/单声道 ≈ 16KB/秒（含开销 ~17KB/秒）
- 平台判定：S40v3(~1MB 堆) 整曲必爆 → **3 段×0.3-0.5s 环形链**，
  接缝是生存代价，三件套缓解（过零对齐/交叉淡化/Player 对象池复用，
  块长恒定因 WAV 头长度固化）；S40v5/v6(4MB) → **整曲单 WAV 单 Player
  零接缝**（8kHz 单声道 2.9MB 勉强入堆）；Asha → 22kHz 时退回分段
- 公式：堆(MB)÷0.016÷曲长(s) = 可用采样率上限
- 设计定稿双形态：堆小=链式（S40v3），堆大=整曲单块（v5+）——
  堆够的机器不配受链式的罪；Player 常驻复用，GC 压力最小化
- 全谱系输出矩阵终版：真机流式(S60)/文件轮换(S40)/碎片链(S40v3 极端)；
  加载器离线渲染；MIDI 转换器(S40 实时备胎)；整曲单块(4MB+ 堆)

### 廿二终章补：VGM→MIDI 实时转译器路线（材料全部到位）
- 重大发现：**denjhang/vgm_ws_to_mid_full_version（用户本人旧作）**
  ——WonderSwan VGM→MIDI 转换器 v1.6→v2.55 五版迭代（research/midi-converters/ws2mid/），
  精确音高/时序、动态音量声像、颤音、无缝循环、instruments.ini 乐器系统；
  靠逆向 libvgm/modizer 完成，中英双语文档齐全
- jvgmtrans（dksrphm, Java VGM→MIDI，17★）：7 芯片映射类
  （V2mPSG=SN76489 明注 / YM2612 / YM2151 / YM2203 / C140 / OKIM6295 / SegaPCM），
  纯 Java 可移植；绑死桌面的只有 javax.sound.midi 输出层
- 其他参考：vampirefrog/vgm2x（vgm2mid/vgm2opm）、JamesDunne/vgm2midi（higan 系）
- **J2ME 实时转译器架构**：VGM 解析(现成) → 寄存器追踪(jvgmtrans 映射逻辑)
  → MIDI 事件(ws2mid 的转换智慧) → MIDIControl.shortMidiEvent(MIDPlay 管道)
  → 手机音源。零合成开销、1MB 堆无压力、S40v3 起全系实时
- 保真度分层：YM2413→OPLL 直译最优 / SN76489 良好 / YM2612 已知弱项(天花板)
- 三输出路线终局：PCM 合成(N900/S60 原汁) + MIDI 转译(S40 全系实时) +
  离线渲染(加载器) —— 三条路同一份 VGM 解析器喂料
