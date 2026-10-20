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
        "Sq A", "Sq B", "Sq C",
        "Noi A", "Noi B", "Noi C",
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

    private static final int ROW_STEREO = 0;
    private static final int ROW_OCT = 1;
    private static final int ROW_NOISEMAP = 2;
    private static final int ROW_ENVSAW_OCT = 3;
    private static final int ROW_ENVTRI_OCT = 4;
    private static final int ROW_ENVSAW_UNI = 5;
    private static final int ROW_ENVTRI_UNI = 6;
    private static final int ROW_BUZZ_VOL = 7;
    private static final int ROW_BEND_SENS = 8;
    private static final int ROW_LOOP = 9;
    private static final int ROW_ORDER = 10;
    private static final int ROW_MASTER = 11;
    private static final int ROW_ENGINE = 12;
    private static final int ROW_BEND_RANGE = 13;
    private static final int ROW_CHUNK = 14;
    private static final int ROW_CH0 = 15;
    private static final int TOTAL_ROWS = 15 + Ay2Midi.SLOTS;
    private static final String[] CHUNK_NAMES = {"4k", "8k", "16k", "32k", "64k", "128k", "256k"};
    private int chunkIdx = 0;                 // 默认 4k（首段填充最快出声）
    private static final String[] ENGINE_NAMES = {"MIDI (S3)", "Tone (Nokia)"};
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
    private int gd3Scroll;        // GD3 表格纵向滚动（2/8 键）
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
        v[0] = 1;
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
        for (int i = 0; i < Ay2Midi.SLOTS; i++) {
            v[16 + i] = ay.getProg(i);
        }
        SettingsStore.save(v);
    }

    private void attachChip2() {
        if (ay2 != null) {
            return;
        }
        ay2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);
        ay2.setClock(file.header.chipClock[0x12] & 0x3FFFFFFF);
        if (ay2.ready) {
            ay2.copyConfigFrom(ay);
            ay2.setMasterVol(masterVol);
        } else {
            ay2 = null;
        }
    }

    /** 由 MIDlet 释放资源（切曲/退出）。返回列表不调用——后台继续播。 */
    public void release() {
        uiAlive = false;
        playing = false;
        ay.close();
        if (ay2 != null) {
            ay2.close();
        }
    }

    public void run() {
        while (uiAlive) {
            if (isShown()) {
                repaint();
            }
            try {
                Thread.sleep(50);
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
        play = new Thread(new Runnable() {
            public void run() {
                playLoop();
            }
        });
        play.start();
    }

    // ---- 双缓冲播放模型 ----
    // FILL：正常喂 VGM（录制开），可视化跟随寄存器实时走，进度冻结；
    //       填到目标字节数为止。
    // PLAY：SMF 段播放，寄存器录制按时间重放（可视化与音频同步走），进度跟音频。
    public static final int PHASE_FILL = 0;
    public static final int PHASE_PLAY = 1;
    public int playPhase = PHASE_FILL;
    public int bufNo;                       // 0=A 1=B
    public int fillPct;                     // 填充进度（0-100，按字节）
    public int playPct;                     // 播放进度（0-100）
    public long progressBase;               // 进度基点（样本数，已播完部分）
    private long playStartWall;
    private static final int[] BUF_BYTES = {4096, 8192, 16384, 32768, 65536, 131072, 262144};

    private int targetBytes() {
        return BUF_BYTES[chunkIdx < BUF_BYTES.length ? chunkIdx : BUF_BYTES.length - 1];
    }

    private void playLoop() {
        long startAt = System.currentTimeMillis();
        long lastSamples = 0;
        progressBase = 0;
        bufNo = 1;

        while (playing) {
            // ================= FILL =================
            bufNo ^= 1;
            playPhase = PHASE_FILL;
            fillPct = 0;
            playPct = 0;
            ay.recording = true;
            ay.recordingReg = true;
            ay.rec.removeAllElements();
            ay.regRec.removeAllElements();
            ay.recBytes = 0;
            ay.recBaseMs = System.currentTimeMillis();
            if (ay2 != null) {
                ay2.recording = true;
                ay2.recordingReg = true;
                ay2.rec.removeAllElements();
                ay2.regRec.removeAllElements();
                ay2.recBytes = 0;
                ay2.recBaseMs = ay.recBaseMs;
            }
            long fillStart = System.currentTimeMillis();
            boolean trackEnd = false;
            long feedStartAt = System.currentTimeMillis();
            long feedLastSamples = 0;

            while (playing) {
                ay.tick();
                if (ay2 != null) {
                    ay2.tick();
                }
                int t = parser.next();

                if (t == VgmParser.EV_END) {
                    if (file.header.hasLoop() && loopPlays < LOOP_CHOICES[loopIdx]) {
                        parser.seek(file.header.loopOffset);
                        loopPlays++;
                        continue;
                    }
                    trackEnd = true;
                    break;
                }

                if (t == VgmParser.EV_REG_WRITE && parser.evCmd == 0xA0) {
                    if ((parser.evReg & 0x80) != 0) {
                        attachChip2();
                        if (ay2 != null) {
                            ay2.write(parser.evReg & 0x7F, parser.evData);
                        }
                    } else {
                        ay.write(parser.evReg, parser.evData);
                    }
                }

                // 实时对时（照原版）
                long nowSamples = parser.getSamples();
                if (nowSamples != feedLastSamples) {
                    long songMs = nowSamples / 44;
                    long wallMs = System.currentTimeMillis() - feedStartAt;
                    long ahead = songMs - wallMs;
                    if (ahead > 2) {
                        try {
                            Thread.sleep(ahead);
                        } catch (InterruptedException e) {
                            trackEnd = !playing ? trackEnd : trackEnd;
                            playing = false;
                        }
                    } else if (ahead < -200) {
                        feedStartAt = System.currentTimeMillis() - songMs;
                    }
                    feedLastSamples = nowSamples;
                }
                if (!playing) {
                    break;
                }
                int tb = targetBytes();
                int total = ay.recBytes + (ay2 != null ? ay2.recBytes : 0);
                fillPct = total * 100 / tb;
                if (total >= tb) {
                    break;                 // 段满
                }
            }
            long segMs = System.currentTimeMillis() - fillStart;
            ay.recording = false;
            ay.recordingReg = false;
            if (ay2 != null) {
                ay2.recording = false;
                ay2.recordingReg = false;
            }
            if (segMs < 200) {
                segMs = 200;               // 极短段也至少给一拍
            }
            if (!playing || segMs <= 0) {
                break;
            }

            // ================= BUILD + PLAY =================
            byte[] smf = com.denjhang.vgm.SmfSeg.build(
                    ay.rec, ay2 != null ? ay2.rec : null, segMs,
                    ay.initEvents(), ay2 != null ? ay2.initEvents() : null);
            playPhase = PHASE_PLAY;
            playStartWall = System.currentTimeMillis();
            playSeg(smf, segMs);
            progressBase = parser.getSamples();
            if (trackEnd) {
                break;
            }
        }
        if (playing) {
            playing = false;
            ay.allSoundOff();
            if (ay2 != null) {
                ay2.allSoundOff();
            }
            autoNext();
        }
    }

    /** 播放一段：音频走，寄存器录制按墙钟重放驱动可视化。 */
    private void playSeg(byte[] smf, long segMs) {
        javax.microedition.media.Player p = null;
        try {
            p = javax.microedition.media.Manager.createPlayer(
                    new java.io.ByteArrayInputStream(smf), "audio/midi");
            p.realize();
            p.prefetch();
            javax.microedition.media.control.VolumeControl vc =
                    (javax.microedition.media.control.VolumeControl) p.getControl("VolumeControl");
            if (vc != null) {
                vc.setLevel(masterVol * 75 / 100 + 20);
                vc.setMute(false);
            }
            p.start();
        } catch (Throwable t) {
            if (p != null) {
                try { p.close(); } catch (Throwable ignored) {}
            }
            return;
        }
        // 寄存器重放：按段内时间戳应用到 ay/ay2（可视化与音频同步）
        int ri = 0, r2i = 0;
        long t0 = System.currentTimeMillis();
        while (playing) {
            long el = System.currentTimeMillis() - t0;
            playPct = (int) (el * 100 / segMs);
            if (el >= segMs) {
                break;
            }
            while (ri < ay.regRec.size()) {
                long[] e = (long[]) ay.regRec.elementAt(ri);
                if (e[0] > el) break;
                ay.write((int) e[1], (int) e[2]);
                ri++;
            }
            if (ay2 != null) {
                while (r2i < ay2.regRec.size()) {
                    long[] e = (long[]) ay2.regRec.elementAt(r2i);
                    if (e[0] > el) break;
                    ay2.write((int) e[1], (int) e[2]);
                    r2i++;
                }
            }
            ay.tick();
            if (ay2 != null) {
                ay2.tick();
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }
        try { p.stop(); p.close(); } catch (Throwable ignored) {}
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

        if (mode == MODE_SETTINGS) {
            paintSettings(g, w, h);
            return;
        }
        paintHud(g, w, h);
    }

    /** 烘焙静态层：键盘底图 + GD3 表格。仅曲目打开/屏宽变化时调用。 */
    private void buildLayers(int w) {
        builtW = w;
        gd3Scroll = 0;

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
        boolean ym = (hd.chipClock[0x12] & 0x40000000) != 0 || hd.ayChipType != 0;
        boolean dual = (hd.chipClock[0x12] & 0x80000000) != 0 || hd.ayClock2 != 0;
        java.util.Vector top = new java.util.Vector();
        if (clk1 > 0) {
            top.addElement("Chip");
            top.addElement(ym ? "YM2149 x1" : "AY8910 x1");
            top.addElement("Clock");
            top.addElement(fmtHz(clk1));
        }
        if (dual) {
            long clk2 = hd.ayClock2 != 0 ? (hd.ayClock2 & 0x3FFFFFFF) : clk1;
            top.addElement("Chip2");
            top.addElement(ym ? "YM2149 x2" : "AY8910 x2");
            top.addElement("Clock2");
            top.addElement(fmtHz(clk2));
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
            // 播放进度
            total = file.header.totalSamples;
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
        g.setColor(playing ? 0x7CFC7C : 0x707A88);
        g.setFont(small);
        StringBuffer st = new StringBuffer();
        st.append(playing ? "PLAY" : "PAUSE");
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
        int contentH = gd3Img.getHeight() + dbgH;
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

    /** 双缓冲状态：fill xx% / play xx% / idle。 */
    private String bufInfo(int which) {
        if (which == bufNo) {
            return playPhase == PHASE_FILL
                    ? "fill " + fillPct + "%" : "play " + playPct + "%";
        }
        return "idle";
    }

    /** 调试信息行（信息表底部，随标签区一起滚动）。 */
    private String[][] debugRows() {
        com.denjhang.vgm.VgmHeader hd = file.header;
        long clk = hd.chipClock[0x12] & 0x3FFFFFFF;
        boolean dual = (hd.chipClock[0x12] & 0x80000000) != 0 || hd.ayClock2 != 0;
        return new String[][]{
            {"Engine", ENGINE_NAMES[engineIdx] + " - " + ay.midiStatus()},
            {"BufA", bufInfo(0)},
            {"BufB", bufInfo(1)},
            {"Clock", clk + " Hz" + (dual ? " x2" : "")},
            {"Events", ay.eventStats()},
            {"Bend", ay.getBendSensText() + " / " + ay.getBendRangePct() + "%"},
            {"Master", String.valueOf(masterVol)},
            {"Track", (listIndex + 1) + " " + (shuffle ? "shuffle" : "seq")
                + " loop " + LOOP_NAMES[loopIdx]},
        };
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
            if (sel) {
                g.setColor(0x1C2E44);
                g.fillRect(4, y0 + i * ROW_H, w - 12, ROW_H - 1);
            }
            g.setColor(sel ? 0x6FB1DE : 0xC8CDD6);
            g.setFont(tiny);
            g.drawString(rowName(r), 10, y0 + i * ROW_H + 1, Graphics.TOP | Graphics.LEFT);
            g.setColor(0xE8B44C);
            g.drawString(rowValue(r), w - 10, y0 + i * ROW_H + 1, Graphics.TOP | Graphics.RIGHT);
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
            case ROW_STEREO: return "Stereo";
            case ROW_OCT: return "Octave";
            case ROW_NOISEMAP: return "Noise map";
            case ROW_ENVSAW_OCT: return "Env saw octave";
            case ROW_ENVTRI_OCT: return "Env tri octave";
            case ROW_ENVSAW_UNI: return "Saw unison";
            case ROW_ENVTRI_UNI: return "Tri unison";
            case ROW_BUZZ_VOL: return "Buzzer vol";
            case ROW_BEND_SENS: return "Bend sens";
            case ROW_LOOP: return "Loop count";
            case ROW_ORDER: return "Play order";
            case ROW_MASTER: return "Master vol";
            case ROW_ENGINE: return "Engine";
            case ROW_CHUNK: return "Gen size";
            case ROW_BEND_RANGE: return "Bend range";
            default: return CH_NAMES[r - ROW_CH0];
        }
    }

    private String rowValue(int r) {
        switch (r) {
            case ROW_STEREO: return Ay2Midi.STEREO_MODES[ay.getStereoMode()];
            case ROW_OCT: return (ay.getOct() >= 0 ? "+" : "") + ay.getOct();
            case ROW_NOISEMAP: return Ay2Midi.NOISE_MAP_NAMES[ay.getNoiseMapMode()];
            case ROW_ENVSAW_OCT:
                return (ay.getEnvOctSaw() >= 0 ? "+" : "") + ay.getEnvOctSaw();
            case ROW_ENVTRI_OCT:
                return (ay.getEnvOctTri() >= 0 ? "+" : "") + ay.getEnvOctTri();
            case ROW_ENVSAW_UNI: return UNI_NAMES[ay.getEnvUni(false)];
            case ROW_ENVTRI_UNI: return UNI_NAMES[ay.getEnvUni(true)];
            case ROW_BUZZ_VOL: return String.valueOf(ay.getBuzzVol());
            case ROW_BEND_SENS: return ay.getBendSensText();
            case ROW_LOOP: return LOOP_NAMES[loopIdx];
            case ROW_ORDER: return ORDER_NAMES[shuffle ? 1 : 0];
            case ROW_MASTER: return String.valueOf(masterVol);
            case ROW_ENGINE: return ENGINE_NAMES[engineIdx];
            case ROW_CHUNK: return CHUNK_NAMES[chunkIdx];
            case ROW_BEND_RANGE: return ay.getBendRangePct() + "%";
            default: return String.valueOf(ay.getProg(r - ROW_CH0));
        }
    }

    private void adjust(int dir) {
        switch (cursor) {
            case ROW_STEREO:
                ay.setStereoMode(ay.getStereoMode() + dir);
                if (ay2 != null) ay2.setStereoMode(ay2.getStereoMode() + dir);
                break;
            case ROW_OCT: {
                int v = ay.getOct() + dir;
                ay.setOct(v);
                if (ay2 != null) ay2.setOct(v);
                break;
            }
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
            case ROW_BUZZ_VOL: {
                int v = ay.getBuzzVol() + dir * 10;
                ay.setBuzzVol(v);
                if (ay2 != null) ay2.setBuzzVol(v);
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
            case ROW_LOOP:
                loopIdx = (loopIdx + dir + LOOP_CHOICES.length) % LOOP_CHOICES.length;
                loopPlays = 0;
                break;
            case ROW_ORDER:
                shuffle = !shuffle;
                break;
            case ROW_MASTER:
                setMaster(masterVol + dir * 10);
                break;
            case ROW_CHUNK: {
                chunkIdx = (chunkIdx + dir + CHUNK_NAMES.length) % CHUNK_NAMES.length;
                break;                          // 下一段填充生效
            }
            case ROW_ENGINE: {
                int e = (engineIdx + dir + 2) % 2;
                try {
                    ay.setEngineTone(e == 1);
                    if (ay2 != null) {
                        ay2.setEngineTone(e == 1);
                    }
                } catch (Throwable ignored) {
                }
                // 以实际引擎状态为准：切换失败显示回弹 + Engine 行露出错误
                engineIdx = ay.toneEngine ? 1 : 0;
                Ay2Midi.preferTone = (engineIdx == 1);
                break;
            }
            default: {
                int ch = cursor - ROW_CH0;
                int cur = ay.getProg(ch);
                int idx = findProg(cur);
                int next = idx;
                for (int step = 0; step < PROG_CHOICES.length; step++) {
                    next += dir;
                    if (next < 0) next = PROG_CHOICES.length - 1;
                    if (next >= PROG_CHOICES.length) next = 0;
                    if (PROG_CHOICES[next] != cur) break;
                }
                ay.setProg(ch, PROG_CHOICES[next]);
                if (ay2 != null) ay2.setProg(ch, PROG_CHOICES[next]);
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
                cursor = cursor > 0 ? cursor - 1 : TOTAL_ROWS - 1;
            } else if (action == DOWN) {
                cursor = cursor < TOTAL_ROWS - 1 ? cursor + 1 : 0;
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
                playing = false;
                ay.recording = false;
                ay.recordingReg = false;
                if (ay2 != null) {
                    ay2.recording = false;
                    ay2.recordingReg = false;
                }
                ay.allSoundOff();
                if (ay2 != null) {
                    ay2.allSoundOff();
                }
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
