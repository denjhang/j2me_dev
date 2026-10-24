package com.denjhang.vgmbrowser;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import com.denjhang.vgm.Ay2Midi;
import com.denjhang.vgm.VgmFile;
import com.denjhang.vgm.VgmParser;

/**
 * 播放器 HUD。
 *
 * 普通模式：◀▶ 切曲（顺序/随机）  ▲▼ 主音量  ●/5 播放暂停  # 进设置
 * 设置模式：▲▼ 移动光标  ◀▶ 调整  # 返回
 *
 * 后台播放：返回列表不停播；打开新曲时由 MIDlet 释放旧实例（单实例）。
 */
public class PlayScreen extends Canvas implements CommandListener, Runnable {

    private static final String[] CH_NAMES = {
        "Sq A", "Sq B", "Sq C", "Noise",
        "Env Saw", "Env Decay", "Env Tri", "Env Alt"
    };
    private static final int[] PROG_CHOICES = {
        80, 81, 83, 87, 38, 48, 33, 56, 73, 24, 19, 11, 4, 0, 52, 127
    };
    private static final String[] UNI_NAMES = {"Off", "+1 oct (2v)", "+2 oct (3v)"};
    private static final int[] LOOP_CHOICES = {1, 2, 3, 5, 99};
    private static final String[] LOOP_NAMES = {"1", "2", "3", "5", "inf"};
    private static final String[] ORDER_NAMES = {"Seq", "Shuffle"};

    // v16 配色（高饱和版）：ChA 绿 / ChB 蓝 / ChC 红；二芯片 紫/黄/青
    private static final int COL_A = 0x4CD84C;
    private static final int COL_B = 0x4C6CF0;
    private static final int COL_C = 0xF04C4C;
    private static final int COL_A2 = 0xC84CE0;
    private static final int COL_B2 = 0xE8E01C;
    private static final int COL_C2 = 0x18CCE0;
    private static final int[] CH_COLORS = {COL_A, COL_B, COL_C};
    private static final int[] CH2_COLORS = {COL_A2, COL_B2, COL_C2};
    // 噪声 = 石板灰 / 玫瑰；包络 = 橙 / 琥珀（v16 语义，高对比）
    private static final int COL_NOISE = 0x5A6478;
    private static final int COL_NOISE2 = 0xB84868;
    private static final int COL_ENV = 0xFF8C1A;
    private static final int COL_ENV2 = 0xD06808;

    private static final int MODE_HUD = 0;
    private static final int MODE_SETTINGS = 1;
    private int mode = MODE_HUD;

    // 设置行布局（同类项靠近）：
    //  播放:    Master / Engine / Gen size / Loop / Order
    //  通道平衡: Sq vol / Noise vol / Env vol / Buzzer vol
    //  音准:    Octave / Bend sens / Bend range
    //  定位:    Stereo / Noise map
    //  包络:    Env saw oct / Env tri oct / Saw unison / Tri unison
    //  乐器:    CH0..
    // 设置界面行号（含分组标题）
    private static final int ROW_HEADER_PLAYBACK = 0;
    private static final int ROW_PRESET = 1;
    private static final int ROW_MASTER = 2;
    private static final int ROW_ENGINE = 3;
    private static final int ROW_CHUNK = 4;
    private static final int ROW_LOOP = 5;
    private static final int ROW_ORDER = 6;
    private static final int ROW_HEADER_LEVEL = 7;
    private static final int ROW_SQVOL = 8;
    private static final int ROW_NOISEVOL = 9;
    private static final int ROW_ENVVOL = 10;
    private static final int ROW_BUZZ_VOL = 11;
    private static final int ROW_HEADER_VOLBEHAVIOR = 12;
    private static final int ROW_VOLMODE = 13;
    private static final int ROW_VOLFOLLOW = 14;
    private static final int ROW_POLY = 15;
    private static final int ROW_MONOTONE = 16;
    private static final int ROW_OVERFLOW = 17;
    private static final int ROW_HEADER_PITCH = 18;
    private static final int ROW_OCT = 19;
    private static final int ROW_BEND_SENS = 20;
    private static final int ROW_BEND_RANGE = 21;
    private static final int ROW_HEADER_ENVELOPE = 22;
    private static final int ROW_ENVSAW_OCT = 23;
    private static final int ROW_ENVTRI_OCT = 24;
    private static final int ROW_ENVSAW_UNI = 25;
    private static final int ROW_ENVTRI_UNI = 26;
    private static final int ROW_HEADER_ROUTING = 27;
    private static final int ROW_STEREO = 28;
    private static final int ROW_NOISEMAP = 29;
    private static final int ROW_HEADER_INSTRUMENTS = 30;
    private static final int ROW_CH0 = 31;
    private static final int TOTAL_ROWS = 31 + Ay2Midi.SLOTS;
    private static final String[] CHUNK_NAMES = {"4k", "8k", "16k", "32k", "64k", "128k", "256k", "512k", "1M", "2M"};
    private int chunkIdx = 9;                 // Nokia 默认 2M（真机测试最优）
    private static final String[] ENGINE_NAMES = {"MIDI (S3)", "Tone (Nokia)"};
    private int presetIdx = 0;                // 当前预设：0=PC, 1=Nokia E5
    private static final int ROW_H = 16;
    private int cursor;
    private int scroll;

    private final BrowserMIDlet midlet;
    private VgmFile file;
    private VgmParser parser;
    private String pendingName;
    private byte[] pendingBytes;
    final Ay2Midi ay;
    private Ay2Midi ay2;
    /** 生产者转换器：录制 SMF 事件/寄存器（与可视实例 ay 分离，杜绝串扰）。 */
    private final Ay2Midi gen;
    private Ay2Midi gen2;
    private final int listIndex;

    private final Command cmdSettings = new Command("Settings", Command.SCREEN, 1);
    private final Command cmdBack = new Command("List", Command.BACK, 2);
    private final Command cmdSetBack = new Command("Back", Command.OK, 1);

    private volatile boolean playing;
    private volatile boolean uiAlive = true;
    private Thread play;
    private String err;

    private int loopIdx = 1;          // 默认循环 2 次
    private int loopPlays;
    private boolean shuffle;
    private int masterVol = 100;
    // 双缓冲：生成大小档位
    static {
        com.denjhang.vgm.Ay2Midi.RECORD_ONLY = true;   // 诺基亚分支：SMF 流引擎
    }
    private int engineIdx = 0;          // 0=MIDI：默认自动（MIDI 可用就用 MIDI，失败落 Tone）

    private final java.util.Random rng = new java.util.Random();
    private long marqueeBaseMs;   // 跑马灯时间基准（与重绘频率解耦）

    // ---- 性能分层：静态内容烘焙进离屏图，每帧只画动态部分 ----
    private Image kbImg;          // 三条键盘底图（白键/黑键/通道标/灭灯字符）
    private Image gd3Img;         // GD3 表格整块（含换行结果）
    private static int gd3Scroll; // 静态：切曲新建 PlayScreen 实例后位置保留（盯健康度用）
    private int colX;             // 信息表键值分列 x（烘焙/动态调试行共用）
    private int builtW = -1;      // 构建时的屏宽（变了才重建）
    private int kbStep;           // 键盘布局缓存（动态高亮要与底图对齐）
    private int kbKx;
    private int kbKeyW;
    private int kbBw;
    private int kbBh;
    private int kbTneLx;          // TNE 字符 x 起点（底图/动态共用，一个公式）
    private int kbH = 15;
    private int kbPitch = 17;
    private Font small;
    private Font titleFont;
    private Font tiny;

    /** 解压状态：>0 时进度条兼做解压进度，-1 完成，-2 错误。 */
    private volatile int decompPct = -1;
    private String decompStatus = "";

    public PlayScreen(BrowserMIDlet midlet, String name, byte[] rawBytes,
                      int listIndex) {
        this.midlet = midlet;
        this.file = null;
        this.listIndex = listIndex;
        this.parser = null;
        this.ay = new Ay2Midi();
        this.gen = new Ay2Midi();
        applyPersisted();
        engineIdx = ay.toneEngine ? 1 : 0;
        pendingName = name;
        pendingBytes = rawBytes;
        initCommon();
        // 后台线程：解压 + 解析，进度回调直接更新 decompPct → repaint
        decompPct = 0;
        decompStatus = "Decompressing...";
        new Thread(new Runnable() {
            public void run() {
                try {
                    final long startMs = System.currentTimeMillis();
                    final byte[] raw = pendingBytes;
                    VgmFile vf = VgmFile.fromBytes(pendingName, raw,
                            new com.denjhang.vgm.Gzip.Progress() {
                                private long lastUpdate = 0;
                                public void onProgress(int inPos, int inTotal, int outLen) {
                                    if (inTotal > 0) {
                                        long now = System.currentTimeMillis();
                                        // 节流：至少 30ms 更新一次（避免回调风暴）
                                        if (now - lastUpdate >= 30) {
                                            decompPct = inPos * 100 / inTotal;
                                            repaint();
                                            lastUpdate = now;
                                        }
                                    }
                                }
                            });
                    file = vf;
                    parser = vf.getParser();
                    pendingBytes = null;   // vgz 原始压缩字节立即丢弃（解压产物已就位）
                    // 最小展示 300ms：保持 100% 显示，让用户看到完整动画
                    decompPct = 100;
                    repaint();
                    long elapsed = System.currentTimeMillis() - startMs;
                    if (elapsed < 300) {
                        try { Thread.sleep(300 - elapsed); } catch (Exception e) {}
                    }
                    decompPct = -1;  // 切换到播放状态
                    if (file.header.chipClock[0x12] != 0) {
                        ay.setClock(file.header.chipClock[0x12] & 0x3FFFFFFF);
                    }
                    if (ay.ready) {
                        ay.forceRefreshAll();
                        ay.setMasterVol(masterVol);
                    }
                    if (file.header.ayClock2 != 0) {
                        attachChip2();
                    }
                    startPlay(true);
                    marqueeBaseMs = System.currentTimeMillis();
                    repaint();
                } catch (Throwable t) {
                    decompPct = -2;
                    decompStatus = t.toString();
                    repaint();
                }
            }
        }).start();
        // UI 线程（repaint 循环 + 按键）必须立即启动，否则界面卡死
        midlet.refreshListNowPlaying();
        Thread ui = new Thread(this);
        ui.start();
    }

    public PlayScreen(BrowserMIDlet midlet, VgmFile file, int listIndex) {
        this.midlet = midlet;
        this.file = file;
        this.listIndex = listIndex;
        this.parser = file.getParser();
        this.ay = new Ay2Midi();
        this.gen = new Ay2Midi();
        applyPersisted();
        engineIdx = ay.toneEngine ? 1 : 0;
        initCommon();
        if (file.header.chipClock[0x12] != 0) {
            ay.setClock(file.header.chipClock[0x12] & 0x3FFFFFFF);
        }
        if (!ay.ready) {
            err = ay.initError;
            try {
                javax.microedition.media.Manager.playTone(72, 150, 100);
                Thread.sleep(200);
                javax.microedition.media.Manager.playTone(72, 150, 100);
                Thread.sleep(200);
                javax.microedition.media.Manager.playTone(72, 150, 100);
            } catch (Throwable ignored) {
            }
        } else {
            ay.forceRefreshAll();
            ay.setMasterVol(masterVol);
        }
        if (file.header.ayClock2 != 0) {
            attachChip2();
        }
        startPlay(true);
        marqueeBaseMs = System.currentTimeMillis();
        midlet.refreshListNowPlaying();
        Thread ui = new Thread(this);
        ui.start();
    }

    private void initCommon() {
        setFullScreenMode(true);
        addCommand(cmdSettings);
        addCommand(cmdBack);
        setCommandListener(this);
        small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        titleFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        tiny = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    }

    /** 启动时从 RMS 恢复设置（失败保持默认）。 */
    private void applyPersisted() {
        int[] v = SettingsStore.load();
        if (v == null) {
            return;
        }
        Ay2Midi.preferTone = (v[14] == 1);
        engineIdx = v[14] == 1 ? 1 : 0;
        if (v[15] >= 0 && v[15] < CHUNK_NAMES.length) {
            chunkIdx = v[15];
        }
        // 百分比放槽位区之外（v[24..26]），避免被乐器槽覆盖
        if (v.length > 24) {
            if (v[24] > 0) ay.setSqVolPct(v[24]);
            if (v[25] > 0) ay.setNoiseVolPct(v[25]);
            if (v[26] > 0) ay.setEnvVolPct(v[26]);
        }
        if (v.length > 27 && v[27] >= 0 && v[27] < Ay2Midi.VOL_FOLLOW_NAMES.length) {
            ay.setVolFollowMode(v[27]);
        }
        if (v.length > 28 && v[28] >= 0 && v[28] < Ay2Midi.VOL_MODES.length) {
            ay.setVolMode(v[28]);
        }
        if (v.length > 29 && v[29] > 0) {
            ay.setPolyLimit(v[29]);
        }
        if (v.length > 30) {
            ay.setMonoTone(v[30] != 0);
        }
        if (v.length > 31 && v[31] >= 0 && v[31] < 2) {
            ay.setPolyOverflow(v[31]);
        }
        ay.setEngineTone(engineIdx == 1);
        try {
            ay.setStereoMode(v[1]);
            ay.setOct(v[2]);
            ay.setNoiseMapMode(v[3]);
            ay.setEnvOct(false, v[4]);
            ay.setEnvOct(true, v[5]);
            ay.setEnvUni(false, v[6]);
            ay.setEnvUni(true, v[7]);
            ay.setBuzzVol(v[8]);
            ay.setBendSensPct(v[9]);
            ay.setBendRangePct(v[10]);
            masterVol = v[11];
            loopIdx = v[12];
            shuffle = v[13] != 0;
            for (int i = 0; i < Ay2Midi.SLOTS && 16 + i < v.length; i++) {
                ay.setProg(i, v[16 + i]);
            }
        } catch (Throwable t) {
            // 单项越界忽略，保其余
        }
    }

    /** 当前设置写入 RMS（每次调整后调用）。 */
    private void persist() {
        int[] v = new int[SettingsStore.FIELDS];
        v[0] = SettingsStore.VERSION;   // 与 load 校验同源，别再硬编码
        v[1] = ay.getStereoMode();
        v[2] = ay.getOct();
        v[3] = ay.getNoiseMapMode();
        v[4] = ay.getEnvOctSaw();
        v[5] = ay.getEnvOctTri();
        v[6] = ay.getEnvUni(false);
        v[7] = ay.getEnvUni(true);
        v[8] = ay.getBuzzVol();
        v[9] = ay.getBendSensPct();
        v[10] = ay.getBendRangePct();
        v[11] = masterVol;
        v[12] = loopIdx;
        v[13] = shuffle ? 1 : 0;
        v[14] = engineIdx;
        v[15] = chunkIdx;
        v[24] = ay.getSqVolPct();
        v[25] = ay.getNoiseVolPct();
        v[26] = ay.getEnvVolPct();
        v[27] = ay.getVolFollowMode();
        v[28] = ay.getVolMode();
        v[29] = ay.getPolyLimit();
        v[30] = ay.getMonoTone() ? 1 : 0;
        v[31] = ay.getPolyOverflow();
        for (int i = 0; i < Ay2Midi.SLOTS; i++) {
            v[16 + i] = ay.getProg(i);
        }
        SettingsStore.save(v);
    }

    private void attachChip2() {
        int clk = file.header.chipClock[0x12] & 0x3FFFFFFF;
        if (ay2 == null) {
            ay2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);
            ay2.setClock(clk);
            if (ay2.ready) {
                ay2.copyConfigFrom(ay);
                ay2.setMasterVol(masterVol);
            } else {
                ay2 = null;
            }
        }
        if (gen2 == null && ay2 != null) {
            gen2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);
            gen2.setClock(clk);
            gen2.copyConfigFrom(ay2);
        }
    }

    /** 由 MIDlet 释放资源（切曲/退出）。返回列表不调用——后台继续播。 */
    public void release() {
        uiAlive = false;
        playing = false;
        prepAlive = false;
        synchronized (streamLock) {
            streamLock.notifyAll();
        }
        synchronized (prepLock) {
            prepWant = null;
            if (prepPlayer != null) {
                try { prepPlayer.close(); } catch (Throwable ignored) {}
                prepPlayer = null;
                prepSmf = null;
            }
        }
        ay.close();
        gen.close();
        if (ay2 != null) {
            ay2.close();
        }
        if (gen2 != null) {
            gen2.close();
        }
        // 切曲防泄漏：主动清空所有大块数据，不等 GC（S60 上 GC 懒，会越滚越大）
        gen.recF = null;
        gen.regF = null;
        gen.recN = 0;
        gen.regN = 0;
        if (gen2 != null) {
            gen2.recF = null;
            gen2.regF = null;
            gen2.recN = 0;
            gen2.regN = 0;
        }
        chunkSmf = null;
        chunkRegs = null;
        chunkRegs2 = null;
        pendingSmf = null;
        pendingRegs = null;
        pendingRegs2 = null;
        regPool = null;
        reg2Pool = null;
        kbImg = null;
        gd3Img = null;
        dbgCache = null;
        parser = null;
        file = null;
        pendingBytes = null;
        // 切曲强制 GC：Nokia E5 等真机 GC 懒惰，不主动回收导致内存累积
        System.gc();
    }

    public void run() {
        while (uiAlive) {
            if (isShown()) {
                repaint();
            }
            try {
                Thread.sleep(33);      // ~30fps：动画流畅度的主开关
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ------------------------------------------------------------ 播放

    private void startPlay(boolean reset) {
        if (playing || !ay.ready) {
            return;
        }
        if (reset) {
            parser.reset();
            loopPlays = 0;
            ay.allSoundOff();
            if (ay2 != null) {
                ay2.allSoundOff();
            }
        }
        playing = true;
        paused = false;
        ensurePrepThread();
        play = new Thread(new Runnable() {
            public void run() {
                playLoop();
            }
        });
        play.start();
    }

    // ---- 单缓冲水位线（一条单调事件流，无 A/B 切换）----
    //  1) 生产线程全速往流尾部追加（事件时间戳用歌曲虚拟时间 vtime）。
    //  2) 播放线程从流头按水位切块：够 target 字节切一块，头指针前移，前缀懒压缩。
    //  3) 软水位背压：未消费超 2 块，生产线程歇口气（不硬阻塞、无状态切换）。
    //  4) 下一块在播放中段就切好并交给常驻线程预建 Player，块衔接只剩 start()。
    //  5) 事件流是扁平 long[]（无 per-event 对象），内存约为 Vector 方案一半。
    public static final int PHASE_FILL = 0;
    public static final int PHASE_PLAY = 1;
    public volatile int playPhase = PHASE_FILL;
    public volatile int playPct;
    public volatile long progressBase;        // 样本
    private volatile long playStartWall;      // 当前块播放起始墙钟（进度条用）
    public String playErr = "";
    private volatile boolean feedEnded;
    /** 暂停：只挂起线程与音频，不销毁不重建。 */
    private volatile boolean paused;

    private final Object streamLock = new Object();
    private long consumedTotal;               // 已消费（播放）累计字节
    private int headEvents;                   // gen 已切走事件数（前缀懒压缩）
    private int head2Events;
    private long streamHeadMs;                // 已切走到的时间
    private Thread bufThread;

    /** 常驻预建线程：为下一块提前 create+realize+prefetch。 */
    private final Object prepLock = new Object();
    private javax.microedition.media.Player prepPlayer;
    private byte[] prepSmf;                   // 身份校验：prepPlayer 对应的字节
    private byte[] prepWant;                  // 请求预建的 smf
    private volatile boolean prepAlive;
    private Thread prepThread;

    /** 当前块 / 预备下一块。 */
    private byte[] chunkSmf;
    private long chunkFrom, chunkTo;
    private long[] chunkRegs, chunkRegs2;
    private javax.microedition.media.Player chunkPrep;   // 随块带出的预建 Player
    private byte[] pendingSmf;
    private long pendingFrom, pendingTo;
    private long[] pendingRegs, pendingRegs2;

    /** 复用池：块构建器 + 寄存器数组（覆盖填充，不销毁重建）。 */
    private final com.denjhang.vgm.SmfSeg.Builder segBuilder
            = new com.denjhang.vgm.SmfSeg.Builder();
    private long[] regPool;      // 当前块专用
    private long[] reg2Pool;
    private long[] regPoolPend;  // 预备块专用（与当前块隔离，防别名覆盖正在重放的数组）
    private long[] reg2PoolPend;
    private static final int[] BUF_BYTES = {4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576, 2097152};

    private int targetBytes() {
        return BUF_BYTES[chunkIdx < BUF_BYTES.length ? chunkIdx : BUF_BYTES.length - 1];
    }

    /** 流中未消费字节（按估算 5 字节/事件）。 */
    private int availBytes() {
        return (gen.recN / 4 - headEvents) * 5
                + (gen2 != null ? (gen2.recN / 4 - head2Events) * 5 : 0);
    }

    /** 生产线程：全速转换；水位线软背压（未消费超 2 块歇口气）。 */
    private void playLoop() {
        int clk = file.header.chipClock[0x12] & 0x3FFFFFFF;
        if (clk != 0) {
            gen.setClock(clk);
        }
        gen.copyConfigFrom(ay);
        gen.recording = true;
        gen.recordingReg = true;
        gen.recBaseMs = 0;
        gen.vtime = 0;
        gen.recN = 0;
        gen.regN = 0;
        gen.recBytes = 0;
        if (gen2 != null) {
            gen2.setClock(clk);
            gen2.copyConfigFrom(ay2);
            gen2.recording = true;
            gen2.recordingReg = true;
            gen2.recBaseMs = 0;
            gen2.vtime = 0;
            gen2.recN = 0;
            gen2.regN = 0;
            gen2.recBytes = 0;
        }
        headEvents = 0;
        head2Events = 0;
        consumedTotal = 0;
        streamHeadMs = parser.getSamples() / 44;   // 恢复播放时接着时间线走
        playPhase = PHASE_FILL;
        progressBase = streamHeadMs * 44;
        feedEnded = false;
        paused = false;
        pendingSmf = null;

        bufThread = new Thread(new Runnable() {
            public void run() {
                bufferPlayer();
            }
        });
        bufThread.start();

        int pumpCount = 0;
        fillLoop:
        while (playing) {
            while (paused && playing) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
            int t = parser.next();
            if (t == VgmParser.EV_END) {
                if (file.header.hasLoop() && loopPlays < LOOP_CHOICES[loopIdx]) {
                    parser.seek(file.header.loopOffset);
                    loopPlays++;
                    continue;
                }
                feedEnded = true;
                break fillLoop;
            }
            if (t == VgmParser.EV_REG_WRITE && parser.evCmd == 0xA0) {
                long ms = parser.getSamples() / 44;
                gen.vtime = ms;
                if (gen2 != null) {
                    gen2.vtime = ms;
                }
                if ((parser.evReg & 0x80) != 0) {
                    attachChip2();
                    if (gen2 != null) {
                        if (!gen2.recording) {
                            gen2.recording = true;
                            gen2.recordingReg = true;
                            gen2.recBaseMs = 0;
                            gen2.recBytes = 0;
                            gen2.vtime = ms;
                        }
                        gen2.write(parser.evReg & 0x7F, parser.evData);
                    }
                } else {
                    gen.write(parser.evReg, parser.evData);
                }
            }
            gen.tick();
            if (gen2 != null) {
                gen2.tick();
            }
            // 周期性让出 CPU：大缓冲填充期长，不让出会饿死 UI 重统（动画/文字冻结）
            if ((++pumpCount & 0xFF) == 0) {
                try { Thread.sleep(2); } catch (InterruptedException e) { break fillLoop; }
            }
            // 水位线：未消费超过 2 块，歇口气（软背压，内存有界）
            while (playing && availBytes() > targetBytes() * 2) {
                if (paused) {
                    break;
                }
                try { Thread.sleep(20); } catch (InterruptedException e) { break fillLoop; }
            }
        }
        feedEnded = true;
        if (bufThread != null) {
            try { bufThread.join(); } catch (InterruptedException ignored) {}
            bufThread = null;
        }
        if (playing) {
            playing = false;
            gen.recording = false;
            gen.recordingReg = false;
            gen.vtime = -1;
            if (gen2 != null) {
                gen2.recording = false;
                gen2.recordingReg = false;
                gen2.vtime = -1;
            }
            ay.allSoundOff();
            if (ay2 != null) {
                ay2.allSoundOff();
            }
            autoNext();
        }
    }

    /** 消费者线程：首块到水位即播；块衔接用预备好的 Player。 */
    private void bufferPlayer() {
        try {
            synchronized (streamLock) {
                while (playing && !feedEnded && availBytes() < targetBytes()) {
                    try { streamLock.wait(30); } catch (InterruptedException e) { return; }
                }
            }
            playPhase = PHASE_PLAY;
            while (playing) {
                while (paused && playing) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
                if (!playing) {
                    break;
                }
                if (!takeNextChunk()) {
                    if (feedEnded) {
                        break;
                    }
                    try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                    continue;
                }
                progressBase = chunkFrom * 44;
                playStartWall = System.currentTimeMillis();
                playSeg(chunkSmf, chunkFrom, chunkTo, chunkRegs, chunkRegs2);
                progressBase = chunkTo * 44;
            }
        } catch (Throwable t) {
            playErr = "player: " + t;
        }
        playPhase = PHASE_FILL;
    }

    /** 取下一块：优先用已切好并预建 Player 的预备块。 */
    private boolean takeNextChunk() {
        synchronized (prepLock) {
            if (pendingSmf != null) {
                chunkSmf = pendingSmf;
                chunkFrom = pendingFrom;
                chunkTo = pendingTo;
                chunkRegs = pendingRegs;
                chunkRegs2 = pendingRegs2;
                pendingSmf = null;
                if (prepPlayer != null && prepSmf == chunkSmf) {
                    chunkPrep = prepPlayer;
                    prepPlayer = null;
                    prepSmf = null;
                }
                return true;
            }
        }
        chunkPrep = null;
        return cutChunk(false);
    }

    /** 从流头切一块（够 target 字节），构建 SMF+寄存器并推进头指针。 */
    private boolean cutChunk(boolean toPending) {
        int tb = targetBytes();
        int i0 = headEvents, j0 = head2Events;
        int i = i0, j = j0;
        long endMs = -1;
        int bytes = 0;
        while (bytes < tb) {
            boolean has1 = i * 4 < gen.recN;
            boolean has2 = gen2 != null && j * 4 < gen2.recN;
            if (!has1 && !has2) {
                break;
            }
            long t1 = has1 ? gen.recF[i * 4] : Long.MAX_VALUE;
            long t2 = has2 ? gen2.recF[j * 4] : Long.MAX_VALUE;
            if (t1 <= t2) {
                endMs = t1;
                i++;
            } else {
                endMs = t2;
                j++;
            }
            bytes += 5;
        }
        if (endMs < 0) {
            return false;
        }
        consumedTotal += bytes;
        if (endMs <= streamHeadMs) {
            endMs = streamHeadMs + 1;
        }
        long from = streamHeadMs;
        byte[] smf = buildChunk(i0, i - i0, j0, j - j0, from, endMs);
        long[] regs;
        long[] regs2;
        if (toPending) {
            regs = drainRegs(gen, from, endMs, regPoolPend);
            regs2 = gen2 != null ? drainRegs(gen2, from, endMs, reg2PoolPend) : null;
            regPoolPend = regs;
            reg2PoolPend = regs2;
        } else {
            regs = drainRegs(gen, from, endMs, regPool);
            regs2 = gen2 != null ? drainRegs(gen2, from, endMs, reg2Pool) : null;
            regPool = regs;
            reg2Pool = regs2;
        }
        synchronized (streamLock) {
            headEvents = i;
            head2Events = j;
            streamHeadMs = endMs;
            lazyTrim();
        }
        if (toPending) {
            pendingSmf = smf;
            pendingFrom = from;
            pendingTo = endMs;
            pendingRegs = regs;
            pendingRegs2 = regs2;
        } else {
            chunkSmf = smf;
            chunkFrom = from;
            chunkTo = endMs;
            chunkRegs = regs;
            chunkRegs2 = regs2;
        }
        return true;
    }

    /** 构建一块自包含 SMF（构建器跨块复用，配置静默同步）。 */
    private byte[] buildChunk(int i0, int n1, int j0, int n2, long from, long to) {
        gen.copyConfigQuiet(ay);
        if (gen2 != null) {
            gen2.copyConfigQuiet(ay2);
        }
        segBuilder.reset();
        segBuilder.init(gen.initEvents());
        if (gen2 != null) {
            segBuilder.init(gen2.initEvents());
        }
        int i = i0, j = j0;
        while (i < i0 + n1 || j < j0 + n2) {
            boolean has1 = i < i0 + n1;
            long t1 = has1 ? gen.recF[i * 4] : Long.MAX_VALUE;
            long t2 = j < j0 + n2 && gen2 != null ? gen2.recF[j * 4] : Long.MAX_VALUE;
            long[] e;
            if (t1 <= t2) {
                e = new long[]{gen.recF[i * 4], gen.recF[i * 4 + 1],
                        gen.recF[i * 4 + 2], gen.recF[i * 4 + 3]};
                i++;
            } else {
                e = new long[]{gen2.recF[j * 4], gen2.recF[j * 4 + 1],
                        gen2.recF[j * 4 + 2], gen2.recF[j * 4 + 3]};
                j++;
            }
            segBuilder.event(e[0] - from, (int) e[1], (int) e[2], (int) e[3]);
        }
        return segBuilder.build(to - from);
    }

    /** 前缀懒压缩：已消费事件超阈值才批量前移（摊销开销，流数组不无限增长）。 */
    private void lazyTrim() {
        if (headEvents > 1024) {
            int keep = gen.recN / 4 - headEvents;
            System.arraycopy(gen.recF, headEvents * 4, gen.recF, 0, keep * 4);
            gen.recN = keep * 4;
            headEvents = 0;
        }
        if (gen2 != null && head2Events > 1024) {
            int keep = gen2.recN / 4 - head2Events;
            System.arraycopy(gen2.recF, head2Events * 4, gen2.recF, 0, keep * 4);
            gen2.recN = keep * 4;
            head2Events = 0;
        }
        shrinkStream(gen);
        if (gen2 != null) {
            shrinkStream(gen2);
        }
        int rKeep = trimRegs(gen, streamHeadMs);
        gen.regN = rKeep;
        if (gen2 != null) {
            gen2.regN = trimRegs(gen2, streamHeadMs);
        }
    }

    /** 数组收缩：容量超过在留数据 8 倍且大于 32K long 时砍半，防止峰值容量永久占用。 */
    private static void shrinkStream(Ay2Midi g) {
        if (g.recF != null && g.recF.length > 8192 && g.recF.length > g.recN * 8) {
            long[] n = new long[Math.max(8192, g.recN * 2)];
            System.arraycopy(g.recF, 0, n, 0, g.recN);
            g.recF = n;
        }
        if (g.regF != null && g.regF.length > 6144 && g.regF.length > g.regN * 8) {
            long[] n = new long[Math.max(6144, g.regN * 2)];
            System.arraycopy(g.regF, 0, n, 0, g.regN);
            g.regF = n;
        }
    }

    /** 寄存器流按时间压缩，返回新长度。 */
    private static int trimRegs(Ay2Midi g, long headMs) {
        int k = 0;
        for (int p = 0; p < g.regN; p += 3) {
            if (g.regF[p] >= headMs) {
                break;
            }
            k = p + 3;
        }
        if (k > 0) {
            System.arraycopy(g.regF, k, g.regF, 0, g.regN - k);
        }
        return g.regN - k;
    }

    /** 创建一块的 Player：create+realize+prefetch（开销大，尽量由常驻线程提前做）。 */
    private javax.microedition.media.Player createSegPlayer(byte[] smf) throws Exception {
        javax.microedition.media.Player p = javax.microedition.media.Manager.createPlayer(
                new java.io.ByteArrayInputStream(smf), "audio/midi");
        p.realize();
        p.prefetch();
        return p;
    }

    /** 常驻预建线程：等到活才干活，活干完继续等。 */
    private void prepLoop() {
        while (prepAlive) {
            byte[] smf;
            synchronized (prepLock) {
                while (prepAlive && prepWant == null) {
                    try {
                        prepLock.wait(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!prepAlive) {
                    return;
                }
                smf = prepWant;
                prepWant = null;
            }
            javax.microedition.media.Player p = null;
            try {
                p = createSegPlayer(smf);
            } catch (Throwable t) {
                p = null;
            }
            synchronized (prepLock) {
                if (p != null && smf == pendingSmf) {
                    if (prepPlayer != null) {
                        try { prepPlayer.close(); } catch (Throwable ignored) {}
                    }
                    prepPlayer = p;
                    prepSmf = smf;
                } else if (p != null) {
                    try { p.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void ensurePrepThread() {
        if (prepThread != null) {
            return;
        }
        prepAlive = true;
        prepThread = new Thread(new Runnable() {
            public void run() {
                prepLoop();
            }
        });
        prepThread.start();
    }

    /** 播放中段：切好下一块并请求预建（错开块衔接瞬间的负载）。 */
    private void buildPending() {
        if (pendingSmf != null) {
            return;
        }
        int tb = targetBytes();
        if (availBytes() >= tb || (feedEnded && availBytes() > 0)) {
            if (cutChunk(true)) {
                synchronized (prepLock) {
                    prepWant = pendingSmf;
                    prepLock.notifyAll();
                }
            }
        }
    }

    /** 播放一块：audio/midi 流 + 寄存器重放驱动可视化（vis 实例独立于生产状态）。 */
    private void playSeg(byte[] smf, long startMs, long endMs,
                         long[] regs, long[] regs2) {
        javax.microedition.media.Player p = chunkPrep;
        chunkPrep = null;
        try {
            if (p == null) {
                p = createSegPlayer(smf);
            }
            javax.microedition.media.control.VolumeControl vc =
                    (javax.microedition.media.control.VolumeControl) p.getControl("VolumeControl");
            if (vc != null) {
                vc.setLevel(masterVol * 75 / 100 + 20);
                vc.setMute(false);
            }
            p.start();
        } catch (Throwable t) {
            playErr = "" + t;
            if (p != null) {
                try { p.close(); } catch (Throwable ignored) {}
            }
            return;
        }
        long segMs = endMs - startMs;
        long t0 = System.currentTimeMillis();
        int ri = 0, ri2 = 0;
        while (playing) {
            // 暂停：停住音频，线程挂起等待（不销毁），恢复后从停止位置续播
            if (paused) {
                try { p.stop(); } catch (Throwable ignored) {}
                long parkT0 = System.currentTimeMillis();
                while (paused && playing) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
                t0 += System.currentTimeMillis() - parkT0;
                if (!playing) {
                    break;
                }
                try { p.start(); } catch (Throwable ignored) {}
            }
            long el = System.currentTimeMillis() - t0;
            if (el >= segMs) {
                break;
            }
            playPct = (int) (el * 100 / Math.max(1, segMs));
            // 中段切好并预建下一块（无 A/B 切换，只是流头指针前移）
            if (el >= 400) {
                buildPending();
            }
            // 可视化重放（绝对时间轴）
            while (ri < regs.length && regs[ri] - startMs <= el) {
                ay.vtime = regs[ri];
                ay.write((int) regs[ri + 1], (int) regs[ri + 2]);
                ri += 3;
            }
            if (regs2 != null) {
                while (ri2 < regs2.length && regs2[ri2] - startMs <= el) {
                    ay2.vtime = regs2[ri2];
                    ay2.write((int) regs2[ri2 + 1], (int) regs2[ri2 + 2]);
                    ri2 += 3;
                }
            }
            ay.vtime = startMs + el;
            ay.tick();
            if (ay2 != null) {
                ay2.vtime = startMs + el;
                ay2.tick();
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }
        try { p.stop(); p.close(); } catch (Throwable ignored) {}
    }

    /** 块寄存器事件打包成扁平数组 {ms,reg,data,...}（数组跨块复用，覆盖填充）。 */
    private static long[] drainRegs(Ay2Midi g, long from, long to, long[] reuse) {
        int n = 0;
        for (int p = 0; p < g.regN; p += 3) {
            if (g.regF[p] > from && g.regF[p] <= to) {
                n++;
            }
        }
        long[] out = (reuse != null && reuse.length >= n * 3) ? reuse : new long[n * 3];
        int k = 0;
        for (int p = 0; p < g.regN; p += 3) {
            if (g.regF[p] > from && g.regF[p] <= to) {
                out[k++] = g.regF[p];
                out[k++] = g.regF[p + 1];
                out[k++] = g.regF[p + 2];
            }
        }
        return out;
    }

    /** 切曲：先淡出再切。 */
    private void switchTrackFade(final int target) {
        final int from = masterVol;
        new Thread(new Runnable() {
            public void run() {
                for (int i = 10; i >= 0; i--) {
                    int v = from * i / 10;
                    ay.setMasterVol(v);
                    if (ay2 != null) {
                        ay2.setMasterVol(v);
                    }
                    try {
                        Thread.sleep(24);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                playing = false;
                persist();  // 切曲前保存当前设置，防止新 PlayScreen 的 applyPersisted 冲掉
                midlet.switchTrack(target);
            }
        }).start();
    }

    private void autoNext() {
        int next = nextIndex();
        if (next >= 0) {
            switchTrackFade(next);
        }
    }

    private int nextIndex() {
        int n = midlet.trackCount();
        if (n <= 1) {
            return -1;
        }
        if (shuffle) {
            int r = listIndex;
            while (r == listIndex) {
                int v = rng.nextInt();
                if (v < 0) {
                    v = -v;
                }
                r = v % n;
            }
            return r;
        }
        int r = listIndex + 1;
        return r < n ? r : 0;
    }

    private int prevIndex() {
        int n = midlet.trackCount();
        if (n <= 1) {
            return -1;
        }
        if (shuffle) {
            return nextIndex();
        }
        int r = listIndex - 1;
        return r >= 0 ? r : n - 1;
    }

    // ------------------------------------------------------------ HUD 绘制

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(0x0C1118);
        g.fillRect(0, 0, w, h);

        if (file == null) {
            // 解压尚未完成：file 还没赋值，任何 header 访问都会 NPE
            g.setFont(small);
            g.setColor(0xAAB4C4);
            String s = pendingName != null ? pendingName : "Loading";
            g.drawString(s, 6, 4, Graphics.TOP | Graphics.LEFT);
            if (decompPct >= 0) {
                g.setColor(0x3E7BA8);
                g.fillRoundRect(6, 30, (w - 16) * Math.max(0, decompPct) / 100, 10, 10, 10);
            }
            return;
        }
        if (mode == MODE_SETTINGS) {
            paintSettings(g, w, h);
            return;
        }
        paintHud(g, w, h);
    }

    /** 烘焙静态层：键盘底图 + GD3 表格。仅曲目打开/屏宽变化时调用。 */
    private void buildLayers(int w) {
        builtW = w;
        // 不复位滚动条：切曲后用户可盯着同一位置看运行健康度（paint 会夹紧）

        // ---- 键盘底图 ----
        // 先算布局：键盘宽 + TNE 字符 x（底图/动态层共用）
        int kx = 4 + 5;
        int kw = w - 8 - 5 - 26;
        float step = (float) kw / WHITE_COUNT;
        int keyW = (int) step;
        if (keyW < 1) {
            keyW = 1;
        }
        int bh = kbH * 5 / 8;
        int bw = keyW / 2;
        if (bw < 2) {
            bw = 2;
        }
        kbKx = kx;
        kbStep = (int) (step * 256);
        kbKeyW = keyW;
        kbBw = bw;
        kbBh = bh;
        kbTneLx = kx + kw + 2;        // 键盘终点 + 2px，底图/动态共用

        kbImg = Image.createImage(w, 3 * kbPitch);
        Graphics kg = kbImg.getGraphics();
        kg.setColor(0x0C1118);
        kg.fillRect(0, 0, w, 3 * kbPitch);
        for (int ch = 0; ch < 3; ch++) {
            drawKeyStripBase(kg, ch * kbPitch, ch);
        }

        // ---- 信息表（换行只算这一次）----
        colX = w * 30 / 100;
        java.util.Vector flat = new java.util.Vector();
        com.denjhang.vgm.VgmHeader hd = file.header;
        // 芯片/时钟信息置顶（无表头行，直接内容）
        long clk1 = hd.chipClock[0x12] & 0x3FFFFFFF;
        boolean ym = hd.ayChipType != 0;  // 0x78: 0x00=AY8910, 0x10=YM2149
        boolean dual = (hd.chipClock[0x12] & 0x40000000) != 0;  // bit30 = dual-chip flag
        java.util.Vector top = new java.util.Vector();
        if (clk1 > 0) {
            top.addElement("Chip");
            top.addElement(ym ? "YM2149 x1" : "AY8910 x1");
            top.addElement("Clock");
            top.addElement(fmtHz(clk1));
        }
        if (dual) {
            // 第二片时钟暂不支持（需解析扩展头 0xBC），回退用同一时钟
            top.addElement("Chip2");
            top.addElement(ym ? "YM2149 x2" : "AY8910 x2");
            top.addElement("Clock2");
            top.addElement(fmtHz(clk1));
        }
        top.addElement("VGM");
        top.addElement("v" + (hd.version >> 8) + "." + (hd.version & 0xFF));
        for (int i = 0; i + 1 < top.size(); i += 2) {
            flat.addElement(top.elementAt(i));
            flat.addElement(top.elementAt(i + 1));
        }
        String[][] rows = {
            {"Title", hd.gd3Title},
            {"Title (en)", hd.gd3TitleEn},
            {"Game", hd.gd3Game},
            {"Game (en)", hd.gd3GameEn},
            {"Artist", hd.gd3Artist},
            {"Artist (en)", hd.gd3ArtistEn},
            {"Date", hd.gd3Date},
            {"Ripper", hd.gd3Ripper},
            {"Notes", hd.gd3Notes},
        };
        for (int i = 0; i < rows.length; i++) {
            String val = rows[i][1];
            if (val == null || val.length() == 0) {
                continue;
            }
            String[] lines = wrap(val, tiny, w - colX - 8);
            for (int li = 0; li < lines.length; li++) {
                flat.addElement(rows[i][0]);
                flat.addElement(lines[li]);
            }
        }
        int gd3H = flat.size() / 2 * 13 + 4;
        gd3Img = Image.createImage(w, gd3H);
        Graphics gg = gd3Img.getGraphics();
        gg.setColor(0x0C1118);
        gg.fillRect(0, 0, w, gd3H);
        gg.setFont(tiny);
        gg.setColor(0x3A4656);
        gg.drawLine(4, 0, w - 4, 0);
        int yy = 2;
        gg.setColor(0xAAB4C4);
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            gg.drawString((String) flat.elementAt(i), 6, yy,
                    Graphics.TOP | Graphics.LEFT);
            gg.drawString((String) flat.elementAt(i + 1), colX, yy,
                    Graphics.TOP | Graphics.LEFT);
            yy += 13;
        }
    }

    /** 时钟格式化：1.79 MHz / 1789 Hz / 0（CLDC 无格式化，手工拼）。 */
    private static String fmtHz(long hz) {
        if (hz <= 0) {
            return "0";
        }
        if (hz >= 1000000) {
            return (hz / 1000000) + "." + ((hz / 10000) % 100) + " MHz";
        }
        if (hz >= 1000) {
            return (hz / 1000) + "." + ((hz / 10) % 100) + " kHz";
        }
        return hz + " Hz";
    }

    private void paintHud(Graphics g, int w, int h) {
        // ---- 标题跑马灯（GD3 标题/文件名，超宽往返滚动）----
        String title = pick(file.header.gd3Title, file.header.gd3TitleEn);
        if (title.length() == 0) {
            title = file.name;
        }
        g.setFont(titleFont);
        g.setColor(0xF0EDE4);
        // MIDP clipRect 是相交语义：先存原裁剪区，画完标题后完整恢复，
        // 否则后续所有绘制都被裁在标题条里
        int cw = g.getClipWidth();
        int chh = g.getClipHeight();
        g.clipRect(4, 2, w - 8, 22);
        int mx = 0;
        int tw = titleFont.stringWidth(title);
        int avail = w - 12;
        if (tw > avail) {
            // 按墙钟时间驱动：40ms 走 1px（约 25px/秒），到头折返，头部停 1.5s
            int span = tw - avail + 8;
            int holdMs = 1500;
            int travelMs = span * 40;
            int cycle = holdMs * 2 + travelMs * 2;
            long t = (System.currentTimeMillis() - marqueeBaseMs) % cycle;
            int pos;
            if (t < holdMs) {
                pos = 0;
            } else if (t < holdMs + travelMs) {
                pos = (int) ((t - holdMs) / 40);
            } else if (t < holdMs * 2 + travelMs) {
                pos = span;
            } else {
                pos = span - (int) ((t - holdMs * 2 - travelMs) / 40);
            }
            if (pos < 0) pos = 0;
            if (pos > span) pos = span;
            mx = -pos;
        }
        g.drawString(title, 6 + mx, 4, Graphics.TOP | Graphics.LEFT);
        g.setClip(0, 0, cw, chh);

        // ---- 进度条（解压中=解压进度，播放中=播放进度）----
        g.setFont(tiny);
        int barY = 26;
        int barW = w - 40;
        int barH = 10;
        
        boolean decompressing = (decompPct >= 0);
        int filled = 0;
        long total = 0;
        long cur = 0;
        
        if (decompressing) {
            // 解压进度
            filled = barW * Math.max(0, decompPct) / 100;
        } else {
            // 播放进度（libvgm 同款：总长 = 第一遍 + 已循环次数 × 循环段）
            if (file != null && file.header != null) {
                boolean hasLoop = file.header.hasLoop();
                total = file.header.totalSamples;
                if (hasLoop && loopPlays > 0) {
                    total += loopPlays * file.header.loopSamples;
                }
            } else {
                total = 0;
            }
            if (playPhase == PHASE_PLAY) {
                cur = progressBase + (System.currentTimeMillis() - playStartWall) * 44;
            } else {
                cur = progressBase;
            }
            filled = total > 0 ? (int) (barW * cur / total) : 0;
            if (filled > barW) filled = barW;
        }
        
        g.setColor(0x1B2430);
        g.fillRoundRect(6, barY, barW, barH, barH, barH);
        if (filled > 2) {
            g.setColor(0x3E7BA8);
            g.fillRoundRect(6, barY, filled, barH, barH, barH);
            g.setColor(0x6FB1DE);
            g.fillRoundRect(6, barY, filled, barH / 2, barH, barH);
        }
        g.setColor(0xE8E8E8);
        g.fillArc(6 + filled - 3, barY - 2, barH + 4, barH + 4, 0, 360);
        
        // 右上角标签：解压中 or 时间
        g.setColor(0x8894A8);
        if (decompressing) {
            String label = "Decompressing " + decompPct + "%";
            int lw = tiny.stringWidth(label);
            g.drawString(label, 6 + barW - lw, barY - 2, Graphics.TOP | Graphics.LEFT);
        } else {
            g.drawString(fmt(cur / 44100), 6, barY + 12, Graphics.TOP | Graphics.LEFT);
            g.drawString(fmt(total / 44100), 6 + barW, barY + 12,
                    Graphics.TOP | Graphics.RIGHT);
        }

        // ---- 状态行 ----
        int sy = barY + 27;
        g.setColor(playing ? (paused ? 0xE8B44C : 0x7CFC7C) : 0x707A88);
        g.setFont(small);
        StringBuffer st = new StringBuffer();
        st.append(playing ? (paused ? "PAUSE" : "PLAY") : "PAUSE");
        if (file.header.hasLoop()) {
            st.append("  loop ").append(loopPlays).append('/').append(LOOP_NAMES[loopIdx]);
        }
        st.append(shuffle ? "  shuf #" : "  #").append(listIndex + 1);
        st.append("  vol ").append(masterVol);
        g.drawString(st.toString(), 6, sy, Graphics.TOP | Graphics.LEFT);
        if (err != null) {
            g.setColor(0xFF7C7C);
            g.drawString(clip(err, 26), 6, sy + 14, Graphics.TOP | Graphics.LEFT);
        }

        // ---- 键盘（底图烘焙）+ 动态高亮 ----
        if (kbImg == null || builtW != w) {
            buildLayers(w);
        }
        int py = sy + 16;
        g.drawImage(kbImg, 0, py, Graphics.TOP | Graphics.LEFT);
        if (playPhase != PHASE_FILL) {          // 填充期可视化冻结（保持上一帧）
            for (int ch = 0; ch < 3; ch++) {
                drawKeyStripDyn(g, py + ch * kbPitch, ch);
            }
        }

        // ---- GD3 表格（整块贴图，超出屏幕可滚动：2/8 键，右侧滚动条） ----
        int gy = py + 3 * kbPitch + 4;
        int gh = h - gy;
        if (gh < 0) {
            gh = 0;
        }
        // 调试区：标签下面多行（Engine/事件计数/首个异常/时钟等）
        String[][] dbg = debugRows();
        int dbgH = dbg.length * 13 + 4;
        int contentH = gd3Img.getHeight() + dbgH + 16;   // 底部预留一行空行
        int gd3Max2 = contentH - gh;
        if (gd3Max2 < 0) {
            gd3Max2 = 0;
        }
        if (gd3Scroll > gd3Max2) {
            gd3Scroll = gd3Max2;
        }
        if (gd3Scroll < 0) {
            gd3Scroll = 0;
        }
        int cw2 = g.getClipWidth();
        int ch2 = g.getClipHeight();
        g.clipRect(0, gy, w, gh);
        g.drawImage(gd3Img, 0, gy - gd3Scroll, Graphics.TOP | Graphics.LEFT);
        g.setFont(tiny);
        int dy = gy - gd3Scroll + gd3Img.getHeight() + 2;
        g.setColor(0x3A4656);
        g.drawLine(4, dy, w - 4, dy);
        dy += 2;
        for (int i = 0; i < dbg.length; i++) {
            g.setColor(0x7FA0C8);
            g.drawString(dbg[i][0], 6, dy, Graphics.TOP | Graphics.LEFT);
            g.setColor(0xC8D4E4);
            g.drawString(dbg[i][1], colX, dy, Graphics.TOP | Graphics.LEFT);
            dy += 13;
        }
        g.setClip(0, 0, cw2, ch2);
        if (gd3Max2 > 0) {
            // 滚动条（真机屏幕矮，标签+调试经常放不下）
            int thumbH = Math.max(14, gh * gh / contentH);
            int thumbY = gy + (gh - thumbH) * gd3Scroll / gd3Max2;
            g.setColor(0x2A3242);
            g.fillRect(w - 5, gy, 3, gh);
            g.setColor(0x8FA3C8);
            g.fillRect(w - 5, thumbY, 3, thumbH);
        }
    }

    /** 单缓冲流状态：ahead = 未消费字节 / 水位线（2 块）。 */
    private String streamInfo() {
        // 按水位线封顶：填充期 0→max，播放期钉在 max，只有切曲/歪路才回 0
        int wm = targetBytes() * 2;
        int avail = availBytes();
        int fill = avail < wm ? avail : wm;
        return "level " + (fill / 1024) + "k  " + streamState();
    }

    private String streamTotal() {
        int tb = targetBytes();
        return "total " + (availBytes() / 1024) + "k  max " + (tb * 2 / 1024) + "k";
    }

    private String streamState() {
        if (playPhase == PHASE_FILL) {
            return feedEnded ? "end" : "filling";
        }
        if (pendingSmf != null) {
            return prepPlayer != null ? "next prep" : "next wait";
        }
        return "filling";
    }

    /** JVM 内存：已用/总量（自动 K/M 单位）。total 是堆上限而非物理占用，
     *  模拟器堆大、真机堆小；要盯的是已用曲线是否平稳。 */
    private static String memInfo() {
        Runtime rt = Runtime.getRuntime();
        return fmtMem(rt.totalMemory() - rt.freeMemory()) + " / " + fmtMem(rt.totalMemory());
    }

    private static String fmtMem(long bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / 1024 / 1024) + "." + ((bytes / 1024 * 10 / 1024) % 10) + "M";
        }
        return (bytes / 1024) + "K";
    }

    /** 调试行缓存：拼字符串开销不小，半秒重建一次足够（paint 每帧只是读）。 */
    private String[][] dbgCache;
    private long dbgCacheAt;

    /** 调试信息行（信息表底部，随标签区一起滚动）。 */
    private String[][] debugRows() {
        long now = System.currentTimeMillis();
        if (dbgCache != null && now - dbgCacheAt < 500) {
            return dbgCache;
        }
        dbgCacheAt = now;
        com.denjhang.vgm.VgmHeader hd = file.header;
        long clk = hd.chipClock[0x12] & 0x3FFFFFFF;
        boolean dual = (hd.chipClock[0x12] & 0x40000000) != 0;  // bit30 = dual-chip flag
        dbgCache = new String[][]{
            {"Engine", ENGINE_NAMES[engineIdx] + " - " + ay.midiStatus()},
            {"Stream", streamInfo()},
            {"Buffer", streamTotal()},
            {"Mem", memInfo()},
            {"PlayErr", playErr.length() > 0 ? playErr : "-"},
            {"Clock", clk + " Hz" + (dual ? " x2" : "")},
            {"Events", ay.eventStats()},
            {"Bend", ay.getBendSensText() + " / " + ay.getBendRangePct() + "%"},
            {"Master", String.valueOf(masterVol)},
            {"Track", (listIndex + 1) + " " + (shuffle ? "shuffle" : "seq")
                + " loop " + LOOP_NAMES[loopIdx]},
        };
        return dbgCache;
    }

    /** 键盘底图（静态，烘焙一次）：白键、黑键凹槽、通道色标、灭灯 TNE 字符。 */
    private void drawKeyStripBase(Graphics g, int y, int ch) {
        int kx = kbKx;
        int step = kbStep;
        int keyW = kbKeyW;
        int bw = kbBw;
        int bh = kbBh;
        int h = kbH;

        g.setColor(CH_COLORS[ch]);
        g.fillRect(4, y, 3, h);

        for (int i = 0; i < WHITE_COUNT; i++) {
            int sx = kx + ((i * step) >> 8);
            int ex = kx + (((i + 1) * step) >> 8);
            g.setColor(0xBEC2CC);
            g.fillRect(sx, y, ex - sx - 1, h);
        }
        g.setColor(0x14161C);
        for (int n = FIRST_NOTE; n <= LAST_NOTE; n++) {
            if (WHITE_IDX[n] >= 0) {
                continue;
            }
            int wi = WHITE_IDX[n - 1] + 1;
            g.fillRect(kx + ((wi * step) >> 8) - bw + 1, y, bw, bh);
        }
        int lx = kbTneLx;
        g.setFont(tiny);
        g.setColor(0x6A7488);
        g.drawString("T", lx, y + h, Graphics.BASELINE | Graphics.LEFT);
        g.drawString("N", lx + 8, y + h, Graphics.BASELINE | Graphics.LEFT);
        g.drawString("E", lx + 16, y + h, Graphics.BASELINE | Graphics.LEFT);
    }

    /** 键盘动态层（每帧）：活动键高亮、噪声/包络标记、点亮 TNE 字符。 */
    private void drawKeyStripDyn(Graphics g, int y, int ch) {
        int kx = kbKx;
        int step = kbStep;
        int keyW = kbKeyW;
        int bw = kbBw;
        int bh = kbBh;
        int h = kbH;

        int note1 = ay.visNote(ch);
        int note2 = ay2 != null ? ay2.visNote(ch) : -1;

        int w1 = inRange(note1) ? WHITE_IDX[note1] : -1;
        int w2 = inRange(note2) ? WHITE_IDX[note2] : -1;
        if (w1 >= 0) {
            int sx = kx + ((w1 * step) >> 8);
            g.setColor(levelColor(CH_COLORS[ch], ay.visSmoothLevel(ch)));
            g.fillRect(sx, y, keyW, h);
            g.setColor(0x0C1118);
            g.fillRect(sx + keyW - 1, y, 1, h);
        }
        if (w2 >= 0) {
            int sx = kx + ((w2 * step) >> 8);
            g.setColor(levelColor(CH2_COLORS[ch], ay2.visSmoothLevel(ch)));
            g.fillRect(sx, y, keyW, h);
            g.setColor(0x0C1118);
            g.fillRect(sx + keyW - 1, y, 1, h);
        }
        if (inRange(note1) && WHITE_IDX[note1] < 0) {
            int wi = WHITE_IDX[note1 - 1] + 1;
            g.setColor(CH_COLORS[ch]);
            g.fillRect(kx + ((wi * step) >> 8) - bw + 1, y, bw, bh);
        }
        if (inRange(note2) && WHITE_IDX[note2] < 0) {
            int wi = WHITE_IDX[note2 - 1] + 1;
            g.setColor(CH2_COLORS[ch]);
            g.fillRect(kx + ((wi * step) >> 8) - bw + 1, y, bw, bh);
        }

        if (ay.visNoise(ch)) {
            markKeyDyn(g, kx, step, keyW, bw, bh, y, h, ay.visNoiseNote(), COL_NOISE);
        }
        if (ay2 != null && ay2.visNoise(ch)) {
            markKeyDyn(g, kx, step, keyW, bw, bh, y, h, ay2.visNoiseNote(), COL_NOISE2);
        }
        if (ay.visBuzzSrc() == ch) {
            markKeyDyn(g, kx, step, keyW, bw, bh, y, h, ay.visEnvNote(), COL_ENV);
        }
        if (ay2 != null && ay2.visBuzzSrc() == ch) {
            markKeyDyn(g, kx, step, keyW, bw, bh, y, h, ay2.visEnvNote(), COL_ENV2);
        }

        g.setFont(tiny);
        int lx = kbTneLx;
        if (ay.visToneActive(ch)) {
            g.setColor(levelColor(CH_COLORS[ch], ay.visSmoothLevel(ch)));
            g.drawString("T", lx, y + h, Graphics.BASELINE | Graphics.LEFT);
        }
        if (ay.visNoiseActive(ch)) {
            g.setColor(levelColor(COL_NOISE, ay.visSmoothLevel(ch)));
            g.drawString("N", lx + 8, y + h, Graphics.BASELINE | Graphics.LEFT);
        }
        if (ay.visEnvActive(ch)) {
            g.setColor(COL_ENV);
            g.drawString("E", lx + 16, y + h, Graphics.BASELINE | Graphics.LEFT);
        }
        if (ay2 != null) {
            if (ay2.visToneActive(ch)) {
                g.setColor(levelColor(CH2_COLORS[ch], ay2.visSmoothLevel(ch)));
                g.drawString("T", lx + 1, y + h, Graphics.BASELINE | Graphics.LEFT);
            }
            if (ay2.visNoiseActive(ch)) {
                g.setColor(levelColor(COL_NOISE2, ay2.visSmoothLevel(ch)));
                g.drawString("N", lx + 9, y + h, Graphics.BASELINE | Graphics.LEFT);
            }
            if (ay2.visEnvActive(ch)) {
                g.setColor(COL_ENV2);
                g.drawString("E", lx + 17, y + h, Graphics.BASELINE | Graphics.LEFT);
            }
        }
    }

    /** 动态标记一个键（与底图同布局）。 */
    private void markKeyDyn(Graphics g, int kx, int step, int keyW,
                            int bw, int bh, int y, int h, int note, int color) {
        if (note < FIRST_NOTE || note > LAST_NOTE) {
            return;
        }
        g.setColor(color);
        int wi = WHITE_IDX[note];
        if (wi >= 0) {
            int sx = kx + ((wi * step) >> 8);
            g.fillRect(sx, y, keyW, h);
            g.setColor(0x0C1118);
            g.fillRect(sx + keyW - 1, y, 1, h);
        } else {
            int bwi = WHITE_IDX[note - 1] + 1;
            g.fillRect(kx + ((bwi * step) >> 8) - bw + 1, y, bw, bh);
        }
    }

    /** 按像素宽换行（按空格分词；单词超宽时硬切）。 */
    private static String[] wrap(String text, Font f, int maxW) {
        java.util.Vector lines = new java.util.Vector();
        StringBuffer cur = new StringBuffer();
        int pos = 0;
        while (pos < text.length()) {
            int sp = text.indexOf(' ', pos);
            String word;
            if (sp < 0) {
                word = text.substring(pos);
                pos = text.length();
            } else {
                word = text.substring(pos, sp + 1);
                pos = sp + 1;
            }
            if (f.stringWidth(word) > maxW && word.length() > 2) {
                for (int i = 0; i < word.length(); i++) {
                    cur.append(word.charAt(i));
                    if (f.stringWidth(cur.toString()) > maxW) {
                        lines.addElement(cur.toString());
                        cur.setLength(0);
                    }
                }
                continue;
            }
            if (f.stringWidth(cur.toString() + word) > maxW && cur.length() > 0) {
                lines.addElement(cur.toString());
                cur.setLength(0);
            }
            cur.append(word);
        }
        if (cur.length() > 0 || lines.size() == 0) {
            lines.addElement(cur.toString());
        }
        String[] r = new String[lines.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = (String) lines.elementAt(i);
        }
        return r;
    }

    // ---------------- 像素风 88 键横条 ----------------

    private static final int FIRST_NOTE = 21;    // A0
    private static final int LAST_NOTE = 108;    // C8
    private static final int WHITE_COUNT = 52;
    private static final boolean[] BLACK_PC = {
        false, true, false, true, false, false,
        true, false, true, false, true, false
    };
    /** note → 白键序号（0..51），黑键为 -1。 */
    private static final int[] WHITE_IDX = new int[128];
    static {
        int wi = 0;
        for (int n = FIRST_NOTE; n <= LAST_NOTE; n++) {
            if (BLACK_PC[n % 12]) {
                WHITE_IDX[n] = -1;
            } else {
                WHITE_IDX[n] = wi++;
            }
        }
    }

    /**
     * 单声道 88 键横条：52 白键满宽（约 4px/键），黑键为顶部短凹槽。
     * 活动键点通道色（电平调亮度）；双芯片同键左右各半。
     */
    private void drawKeyStrip(Graphics g, int x, int y, int w, int h, int ch) {
        int note1 = ay.visNote(ch);
        int note2 = ay2 != null ? ay2.visNote(ch) : -1;

        // 左缘通道色标（4px）
        g.setColor(CH_COLORS[ch]);
        g.fillRect(x, y, 3, h);
        int kx = x + 5;
        int kw = w - 5 - 22;      // 右侧 3 字符位（含字距）
        // 浮点步进：52 键精确铺满可用宽度，无最小值回退（避免右侧空白）
        float step = (float) kw / WHITE_COUNT;
        int keyW = (int) step;    // 绘制宽度（留 1px 缝）
        if (keyW < 1) {
            keyW = 1;
        }
        int bh = h * 5 / 8;       // 黑键凹槽高度
        int bw = keyW / 2;
        if (bw < 2) {
            bw = 2;
        }

        // 白键底色：绘制宽 = 到下一键起点的距离 -1，保证每条缝严格 1px
        for (int i = 0; i < WHITE_COUNT; i++) {
            int sx = kx + (int) (i * step);
            int ex = kx + (int) ((i + 1) * step);
            int wpx = ex - sx - 1;
            if (wpx < 1) {
                wpx = 1;
            }
            g.setColor(0xBEC2CC);
            g.fillRect(sx, y, wpx, h);
        }
        // 活动白键点亮
        int w1 = inRange(note1) ? WHITE_IDX[note1] : -1;
        int w2 = inRange(note2) ? WHITE_IDX[note2] : -1;
        if (w1 >= 0 && w2 >= 0 && w1 == w2) {
            int c1 = keyColor(note1, note1, ay.visSmoothLevel(ch), CH_COLORS[ch]);
            int c2 = keyColor(note2, note2, ay2.visSmoothLevel(ch), CH2_COLORS[ch]);
            int px = kx + (int) (w1 * step);
            g.setColor(c1);
            g.fillRect(px, y, keyW / 2, h);
            g.setColor(c2);
            g.fillRect(px + keyW / 2, y, keyW - keyW / 2, h);
        } else {
            if (w1 >= 0) {
                int sx = kx + (int) (w1 * step);
                int ex = kx + (int) ((w1 + 1) * step);
                g.setColor(keyColor(note1, note1, ay.visSmoothLevel(ch), CH_COLORS[ch]));
                g.fillRect(sx, y, ex - sx - 1, h);
            }
            if (w2 >= 0) {
                int sx = kx + (int) (w2 * step);
                int ex = kx + (int) ((w2 + 1) * step);
                g.setColor(keyColor(note2, note2, ay2.visSmoothLevel(ch), CH2_COLORS[ch]));
                g.fillRect(sx, y, ex - sx - 1, h);
            }
        }
        // 黑键凹槽
        for (int n = FIRST_NOTE; n <= LAST_NOTE; n++) {
            if (WHITE_IDX[n] >= 0) {
                continue;
            }
            int wi = WHITE_IDX[n - 1] + 1;
            int bx = kx + (int) (wi * step) - bw + 1;
            if (n == note1 && n == note2) {
                g.setColor(CH_COLORS[ch]);
                g.fillRect(bx, y, bw / 2, bh);
                g.setColor(CH2_COLORS[ch]);
                g.fillRect(bx + bw / 2, y, bw - bw / 2, bh);
            } else if (n == note1) {
                g.setColor(CH_COLORS[ch]);
                g.fillRect(bx, y, bw, bh);
            } else if (n == note2) {
                g.setColor(CH2_COLORS[ch]);
                g.fillRect(bx, y, bw, bh);
            } else {
                g.setColor(0x14161C);
                g.fillRect(bx, y, bw, bh);
            }
        }

        // 噪声键（v16 规则：clock/(512*per) 的可视音高；本声道噪声门开着才亮）
        if (ay.visNoise(ch)) {
            int nn = ay.visNoiseNote();
            markKey(g, kx, y, step, keyW, bw, bh, h, nn, COL_NOISE);
        }
        if (ay2 != null && ay2.visNoise(ch)) {
            int nn = ay2.visNoiseNote();
            markKey(g, kx, y, step, keyW, bw, bh, h, nn, COL_NOISE2);
        }
        // 包络键（亮在发声源声道的横条上）
        if (ay.visBuzzSrc() == ch) {
            markKey(g, kx, y, step, keyW, bw, bh, h, ay.visEnvNote(), COL_ENV);
        }
        if (ay2 != null && ay2.visBuzzSrc() == ch) {
            markKey(g, kx, y, step, keyW, bw, bh, h, ay2.visEnvNote(), COL_ENV2);
        }

        // T/N/E 等距像素字符：点亮条件 = 门开且实际发声（否则一律灰色）
        for (int i = 0; i < 3; i++) {
            boolean on1, on2;
            int col1, col2;
            if (i == 0) {
                on1 = ay.visToneActive(ch);
                col1 = CH_COLORS[ch];
                on2 = ay2 != null && ay2.visToneActive(ch);
                col2 = CH2_COLORS[ch];
            } else if (i == 1) {
                on1 = ay.visNoiseActive(ch);
                col1 = COL_NOISE;
                on2 = ay2 != null && ay2.visNoiseActive(ch);
                col2 = COL_NOISE2;
            } else {
                on1 = ay.visEnvActive(ch);
                col1 = COL_ENV;
                on2 = ay2 != null && ay2.visEnvActive(ch);
                col2 = COL_ENV2;
            }
            gateChar(g, x + w - 24 + i * 8, y, h, GATE_LETTERS[i],
                    on1, col1, on2, col2, ay.visSmoothLevel(ch));
        }
    }

    private static final String[] GATE_LETTERS = {"T", "N", "E"};

    /** 8px 槽内居中的一个字符：开=电平渐变色，关=亮中灰。 */
    private void gateChar(Graphics g, int slotX, int y, int h, String letter,
                          boolean on1, int col1, boolean on2, int col2, int level) {
        int col = 0x6A7488;      // 熄灭=亮中灰，暗背景下高对比
        if (on1) {
            col = levelColor(col1, level);
        } else if (on2) {
            col = levelColor(col2, level);
        }
        g.setFont(tiny);
        g.setColor(col);
        g.drawString(letter, slotX + 4, y + h,
                Graphics.BASELINE | Graphics.HCENTER);
    }

    /** 在横条上标记一个键：白键整高，黑键凹槽整块。 */
    private void markKey(Graphics g, int kx, int y, float step, int keyW,
                         int bw, int bh, int h, int note, int color) {
        if (note < FIRST_NOTE || note > LAST_NOTE) {
            return;
        }
        g.setColor(color);
        int wi = WHITE_IDX[note];
        if (wi >= 0) {
            int sx = kx + (int) (wi * step);
            int ex = kx + (int) ((wi + 1) * step);
            g.fillRect(sx, y, ex - sx - 1, h);
        } else {
            int bwi = WHITE_IDX[note - 1] + 1;
            g.fillRect(kx + (int) (bwi * step) - bw + 1, y, bw, bh);
        }
    }

    private static boolean inRange(int note) {
        return note >= FIRST_NOTE && note <= LAST_NOTE;
    }

    /** v16 深浅机制：45% 基色 + 55% 白 × 电平(0-127)。低电平保色相且不贴近背景。 */
    private static int levelColor(int base, int level) {
        int r = (base >> 16) & 0xFF;
        int gr = (base >> 8) & 0xFF;
        int b = base & 0xFF;
        int w = 55 * level / 127;      // 白色占比 0-55%
        r = r * (100 - w) / 100 + 255 * w / 100;
        gr = gr * (100 - w) / 100 + 255 * w / 100;
        b = b * (100 - w) / 100 + 255 * w / 100;
        return (r << 16) | (gr << 8) | b;
    }

    /** 键位高亮色（平滑电平版）。 */
    private static int keyColor(int note, int active, int level, int base) {
        return levelColor(base, level);
    }

    // ------------------------------------------------------------ 设置浮层

    private void paintSettings(Graphics g, int w, int h) {
        g.setFont(titleFont);
        g.setColor(0xF0EDE4);
        g.drawString("Settings", 6, 4, Graphics.TOP | Graphics.LEFT);
        g.setFont(tiny);
        g.setColor(0x4A5568);
        g.drawString("^v sel  <> change  # back", w - 6, 4,
                Graphics.TOP | Graphics.RIGHT);

        int y0 = 28;
        int listH = h - y0 - 8;
        int visible = listH / ROW_H;
        if (visible < 1) {
            visible = 1;
        }
        if (cursor < scroll) {
            scroll = cursor;
        }
        if (cursor >= scroll + visible) {
            scroll = cursor - visible + 1;
        }
        if (scroll + visible > TOTAL_ROWS) {
            scroll = TOTAL_ROWS - visible;
        }
        if (scroll < 0) {
            scroll = 0;
        }
        for (int i = 0; i < visible; i++) {
            int r = scroll + i;
            if (r >= TOTAL_ROWS) {
                break;
            }
            boolean sel = cursor == r;
            boolean isHeader = isHeaderRow(r);
            
            if (sel) {
                g.setColor(0x1C2E44);
                g.fillRect(4, y0 + i * ROW_H, w - 12, ROW_H - 1);
            }
            
            // 标题行：加粗字体，高亮颜色
            if (isHeader) {
                g.setFont(titleFont);
                g.setColor(0xF0EDE4);  // 亮白色（与顶部 Settings 标题同色）
                g.drawString(rowName(r), 10, y0 + i * ROW_H + 1, Graphics.TOP | Graphics.LEFT);
            } else {
                g.setFont(tiny);
                g.setColor(sel ? 0x6FB1DE : 0xC8CDD6);
                g.drawString(rowName(r), 10, y0 + i * ROW_H + 1, Graphics.TOP | Graphics.LEFT);
                g.setColor(0xE8B44C);
                g.drawString(rowValue(r), w - 10, y0 + i * ROW_H + 1, Graphics.TOP | Graphics.RIGHT);
            }
        }
        if (TOTAL_ROWS > visible) {
            int trackH = visible * ROW_H;
            g.setColor(0x1B2430);
            g.fillRect(w - 5, y0, 3, trackH);
            int thumbH = trackH * visible / TOTAL_ROWS;
            if (thumbH < 8) {
                thumbH = 8;
            }
            int thumbY = y0 + (trackH - thumbH) * scroll / (TOTAL_ROWS - visible);
            g.setColor(0x6FB1DE);
            g.fillRect(w - 5, thumbY, 3, thumbH);
        }
    }

    private String rowName(int r) {
        switch (r) {
            case ROW_HEADER_PLAYBACK: return "-- Playback --";
            case ROW_PRESET: return "Preset";
            case ROW_MASTER: return "Master vol";
            case ROW_ENGINE: return "Engine";
            case ROW_CHUNK: return "Gen size";
            case ROW_LOOP: return "Loop count";
            case ROW_ORDER: return "Play order";
            case ROW_HEADER_LEVEL: return "-- Level balance --";
            case ROW_SQVOL: return "Square vol";
            case ROW_NOISEVOL: return "Noise vol";
            case ROW_ENVVOL: return "Envelope vol";
            case ROW_BUZZ_VOL: return "Buzzer vol";
            case ROW_HEADER_VOLBEHAVIOR: return "-- Volume behavior --";
            case ROW_VOLMODE: return "Note-on vol";
            case ROW_VOLFOLLOW: return "Vol follow";
            case ROW_POLY: return "Max poly";
            case ROW_MONOTONE: return "Mono tone";
            case ROW_OVERFLOW: return "Poly overflow";
            case ROW_HEADER_PITCH: return "-- Pitch --";
            case ROW_OCT: return "Octave";
            case ROW_BEND_SENS: return "Bend sens";
            case ROW_BEND_RANGE: return "Bend range";
            case ROW_HEADER_ENVELOPE: return "-- Envelope voices --";
            case ROW_ENVSAW_OCT: return "Env saw octave";
            case ROW_ENVTRI_OCT: return "Env tri octave";
            case ROW_ENVSAW_UNI: return "Saw unison";
            case ROW_ENVTRI_UNI: return "Tri unison";
            case ROW_HEADER_ROUTING: return "-- Routing --";
            case ROW_STEREO: return "Stereo";
            case ROW_NOISEMAP: return "Noise map";
            case ROW_HEADER_INSTRUMENTS: return "-- Instruments --";
            default: return CH_NAMES[r - ROW_CH0];
        }
    }

    private String rowValue(int r) {
        switch (r) {
            case ROW_HEADER_PLAYBACK:
            case ROW_HEADER_LEVEL:
            case ROW_HEADER_VOLBEHAVIOR:
            case ROW_HEADER_PITCH:
            case ROW_HEADER_ENVELOPE:
            case ROW_HEADER_ROUTING:
            case ROW_HEADER_INSTRUMENTS:
                return "";  // 标题行无值
            case ROW_PRESET: return Ay2Midi.PRESET_NAMES[presetIdx];
            case ROW_MASTER: return String.valueOf(masterVol);
            case ROW_ENGINE: return ENGINE_NAMES[engineIdx];
            case ROW_CHUNK: return CHUNK_NAMES[chunkIdx];
            case ROW_LOOP: return LOOP_NAMES[loopIdx];
            case ROW_ORDER: return ORDER_NAMES[shuffle ? 1 : 0];
            case ROW_SQVOL: return ay.getSqVolPct() + "%";
            case ROW_NOISEVOL: return ay.getNoiseVolPct() + "%";
            case ROW_ENVVOL: return ay.getEnvVolPct() + "%";
            case ROW_BUZZ_VOL: return String.valueOf(ay.getBuzzVol());
            case ROW_VOLFOLLOW: return Ay2Midi.VOL_FOLLOW_NAMES[ay.getVolFollowMode()];
            case ROW_VOLMODE: return Ay2Midi.VOL_MODES[ay.getVolMode()];
            case ROW_POLY: return String.valueOf(ay.getPolyLimit());
            case ROW_MONOTONE: return Ay2Midi.MONO_NAMES[ay.getMonoTone() ? 1 : 0];
            case ROW_OVERFLOW: return Ay2Midi.OVERFLOW_NAMES[ay.getPolyOverflow()];
            case ROW_OCT: return (ay.getOct() >= 0 ? "+" : "") + ay.getOct();
            case ROW_BEND_SENS: return ay.getBendSensText();
            case ROW_BEND_RANGE: return ay.getBendRangePct() + "%";
            case ROW_STEREO: return Ay2Midi.STEREO_MODES[ay.getStereoMode()];
            case ROW_NOISEMAP: return Ay2Midi.NOISE_MAP_NAMES[ay.getNoiseMapMode()];
            case ROW_ENVSAW_OCT:
                return (ay.getEnvOctSaw() >= 0 ? "+" : "") + ay.getEnvOctSaw();
            case ROW_ENVTRI_OCT:
                return (ay.getEnvOctTri() >= 0 ? "+" : "") + ay.getEnvOctTri();
            case ROW_ENVSAW_UNI: return UNI_NAMES[ay.getEnvUni(false)];
            case ROW_ENVTRI_UNI: return UNI_NAMES[ay.getEnvUni(true)];
            default:
                // 乐器显示：数字 名称
                int prog = ay.getProg(r - ROW_CH0);
                return prog + " " + Ay2Midi.GM_NAMES[prog];
        }
    }

    private void adjust(int dir) {
        // 标题行不可调节
        if (cursor == ROW_HEADER_PLAYBACK || cursor == ROW_HEADER_LEVEL ||
            cursor == ROW_HEADER_VOLBEHAVIOR || cursor == ROW_HEADER_PITCH ||
            cursor == ROW_HEADER_ENVELOPE || cursor == ROW_HEADER_ROUTING ||
            cursor == ROW_HEADER_INSTRUMENTS) {
            return;
        }
        switch (cursor) {
            case ROW_PRESET: {
                presetIdx = (presetIdx + dir + Ay2Midi.PRESET_NAMES.length) % Ay2Midi.PRESET_NAMES.length;
                ay.applyPreset(presetIdx);
                if (ay2 != null) ay2.applyPreset(presetIdx);
                break;
            }
            case ROW_MASTER:
                setMaster(masterVol + dir * 10);
                break;
            case ROW_ENGINE: {
                int e = (engineIdx + dir + 2) % 2;
                try {
                    ay.setEngineTone(e == 1);
                    if (ay2 != null) {
                        ay2.setEngineTone(e == 1);
                    }
                } catch (Throwable ignored) {
                }
                engineIdx = ay.toneEngine ? 1 : 0;
                Ay2Midi.preferTone = (engineIdx == 1);
                break;
            }
            case ROW_CHUNK: {
                chunkIdx = (chunkIdx + dir + CHUNK_NAMES.length) % CHUNK_NAMES.length;
                break;
            }
            case ROW_LOOP:
                loopIdx = (loopIdx + dir + LOOP_CHOICES.length) % LOOP_CHOICES.length;
                loopPlays = 0;
                break;
            case ROW_ORDER:
                shuffle = !shuffle;
                break;
            case ROW_SQVOL: {
                int v = ay.getSqVolPct() + dir * 10;
                if (v < 0) v = 0;
                if (v > 200) v = 200;
                ay.setSqVolPct(v);
                if (ay2 != null) ay2.setSqVolPct(v);
                break;
            }
            case ROW_NOISEVOL: {
                int v = ay.getNoiseVolPct() + dir * 10;
                if (v < 0) v = 0;
                if (v > 300) v = 300;      // 噪声可扩到 300%
                ay.setNoiseVolPct(v);
                if (ay2 != null) ay2.setNoiseVolPct(v);
                break;
            }
            case ROW_ENVVOL: {
                int v = ay.getEnvVolPct() + dir * 10;
                if (v < 0) v = 0;
                if (v > 200) v = 200;
                ay.setEnvVolPct(v);
                if (ay2 != null) ay2.setEnvVolPct(v);
                break;
            }
            case ROW_BUZZ_VOL: {
                int v = ay.getBuzzVol() + dir * 10;
                ay.setBuzzVol(v);
                if (ay2 != null) ay2.setBuzzVol(v);
                break;
            }
            case ROW_VOLFOLLOW: {
                int m = (ay.getVolFollowMode() + dir + Ay2Midi.VOL_FOLLOW_NAMES.length)
                        % Ay2Midi.VOL_FOLLOW_NAMES.length;
                ay.setVolFollowMode(m);
                if (ay2 != null) ay2.setVolFollowMode(m);
                break;
            }
            case ROW_VOLMODE: {
                int m = (ay.getVolMode() + dir + Ay2Midi.VOL_MODES.length)
                        % Ay2Midi.VOL_MODES.length;
                ay.setVolMode(m);
                if (ay2 != null) ay2.setVolMode(m);
                break;
            }
            case ROW_POLY: {
                int cur = ay.getPolyLimit();
                int idx = 0;
                for (int i = 0; i < Ay2Midi.POLY_CHOICES.length; i++) {
                    if (Ay2Midi.POLY_CHOICES[i] == cur) {
                        idx = i;
                        break;
                    }
                }
                idx = (idx + dir + Ay2Midi.POLY_CHOICES.length) % Ay2Midi.POLY_CHOICES.length;
                ay.setPolyLimit(Ay2Midi.POLY_CHOICES[idx]);
                if (ay2 != null) ay2.setPolyLimit(Ay2Midi.POLY_CHOICES[idx]);
                break;
            }
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
            case ROW_OCT: {
                int v = ay.getOct() + dir;
                ay.setOct(v);
                if (ay2 != null) ay2.setOct(v);
                break;
            }
            case ROW_BEND_SENS:
                ay.addBendSens(dir);
                if (ay2 != null) ay2.addBendSens(dir);
                break;
            case ROW_BEND_RANGE:
                ay.addBendRange(dir * 5);
                if (ay2 != null) ay2.addBendRange(dir * 5);
                break;
            case ROW_STEREO:
                ay.setStereoMode(ay.getStereoMode() + dir);
                if (ay2 != null) ay2.setStereoMode(ay2.getStereoMode() + dir);
                break;
            case ROW_NOISEMAP: {
                int v = ay.getNoiseMapMode() + dir;
                ay.setNoiseMapMode(v);
                if (ay2 != null) ay2.setNoiseMapMode(v);
                break;
            }
            case ROW_ENVSAW_OCT: {
                int v = ay.getEnvOctSaw() + dir;
                ay.setEnvOct(false, v);
                if (ay2 != null) ay2.setEnvOct(false, v);
                break;
            }
            case ROW_ENVTRI_OCT: {
                int v = ay.getEnvOctTri() + dir;
                ay.setEnvOct(true, v);
                if (ay2 != null) ay2.setEnvOct(true, v);
                break;
            }
            case ROW_ENVSAW_UNI: {
                int u = (ay.getEnvUni(false) + dir + 3) % 3;
                ay.setEnvUni(false, u);
                if (ay2 != null) ay2.setEnvUni(false, u);
                break;
            }
            case ROW_ENVTRI_UNI: {
                int u = (ay.getEnvUni(true) + dir + 3) % 3;
                ay.setEnvUni(true, u);
                if (ay2 != null) ay2.setEnvUni(true, u);
                break;
            }
            default: {
                // 乐器 0-127（GM 全范围）
                int ch = cursor - ROW_CH0;
                int cur = ay.getProg(ch);
                int next = cur + dir;
                if (next < 0) next = 127;
                if (next > 127) next = 0;
                ay.setProg(ch, next);
                if (ay2 != null) ay2.setProg(ch, next);
                break;
            }
        }
        persist();
    }

    private static int findProg(int prog) {
        for (int i = 0; i < PROG_CHOICES.length; i++) {
            if (PROG_CHOICES[i] == prog) {
                return i;
            }
        }
        return 0;
    }

    /** 判断是否为标题行（不可选中） */
    private boolean isHeaderRow(int r) {
        return r == ROW_HEADER_PLAYBACK || r == ROW_HEADER_LEVEL ||
               r == ROW_HEADER_VOLBEHAVIOR || r == ROW_HEADER_PITCH ||
               r == ROW_HEADER_ENVELOPE || r == ROW_HEADER_ROUTING ||
               r == ROW_HEADER_INSTRUMENTS;
    }

    // ------------------------------------------------------------ 工具

    private static String pick(String a, String b) {
        return a != null && a.length() > 0 ? a : (b == null ? "" : b);
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + ".." : s;
    }

    private static String fmt(long sec) {
        if (sec < 0) {
            sec = 0;
        }
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    // ------------------------------------------------------------ 输入

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);

        if (mode == MODE_SETTINGS) {
            if (keyCode == KEY_POUND) {
                setMode(MODE_HUD);
            } else if (action == UP) {
                do {
                    cursor = cursor > 0 ? cursor - 1 : TOTAL_ROWS - 1;
                } while (isHeaderRow(cursor));
            } else if (action == DOWN) {
                do {
                    cursor = cursor < TOTAL_ROWS - 1 ? cursor + 1 : 0;
                } while (isHeaderRow(cursor));
            } else if (action == LEFT) {
                adjust(-1);
            } else if (action == RIGHT) {
                adjust(1);
            }
            repaint();
            return;
        }

        if (action == LEFT) {
            int p = prevIndex();
            if (p >= 0) {
                switchTrackFade(p);
            }
        } else if (action == RIGHT) {
            int n = nextIndex();
            if (n >= 0) {
                switchTrackFade(n);
            }
        } else if (action == UP) {
            gd3Scroll -= 24;
            if (gd3Scroll < 0) {
                gd3Scroll = 0;
            }
        } else if (action == DOWN) {
            gd3Scroll += 24;
        } else if (action == FIRE || keyCode == KEY_NUM5) {
            if (playing) {
                paused = !paused;      // 挂起/恢复，不销毁线程
            } else {
                startPlay(false);
            }
        } else if (keyCode == KEY_NUM2) {
            gd3Scroll -= 24;
            if (gd3Scroll < 0) {
                gd3Scroll = 0;
            }
        } else if (keyCode == KEY_NUM8) {
            gd3Scroll += 24;
        } else if (keyCode == KEY_POUND) {
            setMode(MODE_SETTINGS);
        }
        repaint();
    }

    /** 模式切换同时更新左软键：HUD=Settings，Settings=Back。 */
    private void setMode(int m) {
        if (mode == m) {
            return;
        }
        mode = m;
        if (m == MODE_SETTINGS) {
            // 确保光标不在标题行
            if (isHeaderRow(cursor)) {
                cursor = ROW_MASTER;  // 跳到第一个可选项
            }
            removeCommand(cmdSettings);
            addCommand(cmdSetBack);
        } else {
            removeCommand(cmdSetBack);
            addCommand(cmdSettings);
        }
    }

    protected void keyRepeated(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == UP || action == DOWN || action == LEFT || action == RIGHT) {
            keyPressed(keyCode);
        }
    }

    private void setMaster(int v) {
        if (v < 0) v = 0;
        if (v > 127) v = 127;
        masterVol = v;
        ay.setMasterVol(v);
        if (ay2 != null) {
            ay2.setMasterVol(v);
        }
        persist();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdSettings) {
            setMode(MODE_SETTINGS);
            repaint();
        } else if (c == cmdSetBack) {
            setMode(MODE_HUD);
            repaint();
        } else if (c == cmdBack) {
            // 返回列表但保持后台播放（单实例由 MIDlet 管理）
            midlet.backToList();
            midlet.refreshListNowPlaying();
        }
    }
}
