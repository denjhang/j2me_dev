package com.denjhang.vgm;

import javax.microedition.media.Manager;
import javax.microedition.media.control.ToneControl;
import javax.microedition.media.control.VolumeControl;
import javax.microedition.media.Player;
import javax.microedition.media.control.MIDIControl;

/**
 * AY8910 寄存器流 → MIDI 实时转译（7 通道，用户定案架构）：
 *
 *   MIDI 0/1/2 = 方波 A/B/C（默认音色 80）
 *   MIDI 3/4/5 = 噪声 A/B/C（默认音色 127，独立声道各带声像）
 *   MIDI 6     = 包络波形/蜂鸣器（默认音色 83）
 *
 * 每个通道的音色、整体声像模式（ABC 等排列）、八度移位均可调。
 * 转换语义以用户源码为准（yasp11 ay_to_opm.c + Denjhang v16）。
 */
public class Ay2Midi {

    public static final int CHANNELS = 7;
    /** 配置槽数：0-2 方波，3-5 噪声，6-9 包络四形状类（锯齿/衰减/三角/交替）。 */
    public static final int SLOTS = 10;
    public static final int SLOT_ENV_SAW = 6;      // R13 8,12  循环锯齿
    public static final int SLOT_ENV_DECAY = 7;    // R13 9,15  衰减锯齿
    public static final int SLOT_ENV_TRI = 8;      // R13 10,14 三角
    public static final int SLOT_ENV_ALT = 9;      // R13 11,13 交替锯齿
    public static final String[] SLOT_NAMES = {
        "Env-Saw", "Env-Decay", "Env-Tri", "Env-Alt"
    };
    public static final int[] ENV_SHAPE_DEFAULT = {81, 87, 82, 80};

    /** chip0：0-6 直排。 */
    // nescube 3.2 通道映射（反编译定案）：方波 6/7/8、噪声 9-11（9=GM鼓）、包络 12
    public static final int[] MAP_CHIP0 = {6, 7, 8, 9, 10, 11, 12};
    /** chip1：避开 9 号 GM 鼓通道。方波 10-12，噪声 13-15，包络 7。 */
    public static final int[] MAP_CHIP1 = {0, 1, 2, 3, 4, 5, 13};

    private final int[] toneCh = new int[3];
    private final int[] noiseCh = new int[3];
    private int buzzerCh;

    // MIDI 状态字节（低 4 位 OR 通道号）
    private static final int NOTE_ON = 0x90;
    private static final int NOTE_OFF = 0x80;
    private static final int CC = 0xB0;
    private static final int CC_VOLUME = 7;
    private static final int CC_PAN = 10;
    private static final int CC_EXPRESSION = 11;
    private static final int PROG = 0xC0;
    private static final int PITCH = 0xE0;

    /** RPN 固定 ±2 半音；灵敏度只改微分刻度。 */
    private static final int BEND_STEPS_PER_SEMI = 4096;

    /** 弯音灵敏度（百分比，100=默认），左右键 0.01 步进连续可调。 */
    private int bendSensPct = 100;   // 默认 x1.0
    private static final int BEND_SENS_MIN = 25;
    private static final int BEND_SENS_MAX = 400;
    /** 弯音范围比例（百分比，100=不缩放）。只按比例缩放弯音偏移，
     *  不做最大最小钳制 —— 减小即压缩弯音行程（颤音/滑音幅度）。 */
    private int bendRangePct = 100;

    public static final int PROG_SQUARE = 80;
    public static final int PROG_BUZZER = 83;
    public static final int PROG_NOISE = 127;

    /** 默认音色映射（可被用户逐槽覆盖）。6-9 按包络形状类给不同乐器。 */
    public static final int[] DEFAULT_PROG = {
        PROG_SQUARE, PROG_SQUARE, PROG_SQUARE,
        PROG_NOISE, PROG_NOISE, PROG_NOISE,
        ENV_SHAPE_DEFAULT[0], ENV_SHAPE_DEFAULT[1],
        ENV_SHAPE_DEFAULT[2], ENV_SHAPE_DEFAULT[3]
    };

    private static final int PAN_L = 1;
    private static final int PAN_C = 64;
    private static final int PAN_R = 127;

    /** 立体声模式名与 A/B/C 声像（左中右），用户左右键循环选择。 */
    public static final String[] STEREO_MODES = {
        "ABC", "ACB", "BAC", "BCA", "CAB", "CBA", "MONO"
    };
    private static final int[][] STEREO_PANS = {
        {PAN_L, PAN_C, PAN_R},   // ABC
        {PAN_L, PAN_R, PAN_C},   // ACB
        {PAN_C, PAN_L, PAN_R},   // BAC
        {PAN_R, PAN_L, PAN_C},   // BCA
        {PAN_C, PAN_R, PAN_L},   // CAB
        {PAN_R, PAN_C, PAN_L},   // CBA
        {PAN_C, PAN_C, PAN_C},   // MONO
    };

    /**
     * AY 音量值（0=静音，15=最响，注意与 SN76489 相反）→ CC7/CC11。
     * 由 yasp11 VOL_TO_TL 取反（cc = 127 - TL），非线性响度曲线。
     * 首项必须是 0：音量 0 = 静音。
     */
    static final int[] ATTN_TO_VOL = {
        0, 65, 71, 75, 81, 85, 91, 95, 99, 103, 107, 111, 115, 119, 123, 127
    };
    /** yasp11 N_VOL_TO_TL 取反 → 噪声衰减到 CC7。 */
    static final int[] N_ATT_TO_VOL = {
        0, 1, 2, 3, 4, 5, 6, 7, 11, 15, 22, 31, 45, 63, 90, 127
    };

    private Player player;
    private MIDIControl midi;
    public boolean ready;
    public String initError;

    private final int[] regs = new int[16];
    private int clock = 1773400;
    private int octShift;
    private int stereoMode;
    private final int[] progMap = new int[SLOTS];
    private final int[] pan = new int[3];

    private final int[] curNote = new int[3];
    private final int[] curBend = new int[3];
    private final int[] curVol = new int[3];
    private final boolean[] buzzer = new boolean[3];

    private final boolean[] noiseSounding = new boolean[3];
    private final int[] noiseVol = new int[3];
    /** 噪声发生器全局共享（一个 R6），三个噪声声道共用同一音高。 */
    private int noiseNote = 60;

    private int buzzSrc = -1;
    private int buzzNote = -1;       // 基准音
    private int buzzVoices = 1;      // 当前堆叠数（1-3）
    private int buzzProgSent = -1;   // 已发到蜂鸣器通道的音色

    /** 关掉蜂鸣通道上全部堆叠音。 */
    private void buzzOffAll() {
        if (buzzNote >= 0) {
            for (int i = 0; i < buzzVoices; i++) {
                int n = buzzNote + i * 12;
                if (n <= 127) {
                    send(NOTE_OFF | buzzerCh, n, 0);
                }
            }
        }
        buzzNote = -1;
        buzzSrc = -1;
        buzzVoices = 1;
    }

    private int familyVoices(int slot) {
        return (slot == SLOT_ENV_TRI ? envUniTri : envUniSaw) + 1;
    }

    // 软件包络仿真（慢包络的音量模拟），状态机照抄 emu2149 的 env 单元
    private int envPtr = 31;           // 0-31 电平（6 位计数器，0x20 为进/借位标志）
    private boolean envFaceUp;         // true=上升
    private boolean envPause;
    private long envNextStepAt;
    private int envStepMs = -1;        // 每步毫秒 = 16*period*1000/clock
    private final int[] envLastCC = new int[3];

    // 同步蜂鸣器：软件高速重写 R13（一次性形状）时，重触发率即音高
    private long lastR13At;
    private long r13Interval;      // 平滑后的重写间隔 ms
    private boolean syncBuzz;
    private int syncNote = -1;

    public Ay2Midi() {
        this(MAP_CHIP0);
    }

    /** map[7]：0-2 方波，3-5 噪声，6 包络；必须避开 MIDI 9（GM 鼓）。 */
    public Ay2Midi(int[] map) {
        for (int i = 0; i < 3; i++) {
            toneCh[i] = map[i];
            noiseCh[i] = map[3 + i];
        }
        buzzerCh = map[6];
        for (int i = 0; i < 3; i++) {
            curNote[i] = -1;
            curBend[i] = 8192;
            curVol[i] = -1;
            noiseVol[i] = -1;
        }
        for (int i = 0; i < SLOTS; i++) {
            progMap[i] = DEFAULT_PROG[i];
        }
        applyStereoMode(0);
        ready = init();
    }

    private String midiPath = "?";

    // ToneControl 兜底引擎：MIDIControl 不可用/哑巴的真机上保底出声（3 个方波声部）
    public boolean toneEngine = false;
    private final Player[] tonePlayer = new Player[3];
    private final ToneControl[] toneCtrl = new ToneControl[3];

    /** 分支版：强制 tone（诺基亚）引擎，禁用一切 MIDI 尝试。 */
    public static boolean FORCE_TONE = false;
    /** 声音引擎选择：0=MIDI(S3/MB 自动降级 tone)，1=Tone(诺基亚兼容，默认)。 */
    public static boolean preferTone = false;

    private boolean init() {
        if ((FORCE_TONE || preferTone) && openToneEngine()) {
            midiPath = "TONE";
            toneEngine = true;
            return true;
        }
        // nescube 精确复刻：createPlayer → prefetch() → getControl(全类名)。
        // 没有 realize、没有 start、初始化零 CC（音色照 nescube 打开后按需发）。
        // 任一步失败再走 MeBoy 兜底链（realize → start → getControl）。
        if (openMidiNescube()) {
            midiPath = "S3";
            sendInitProgs();
            return true;
        }
        if (openMidiMeboy()) {
            midiPath = "MB";
            sendInitProgs();
            return true;
        }
        if (openToneEngine()) {
            midiPath = "TONE";
            toneEngine = true;
            return true;
        }
        return false;
    }

    /** device://tone + 专职线程（ToneControl 兜底）。声部级容错：
     *  个别声部拿不到 ToneControl 不整体失败（5300 模拟器实测），全坏才返回 false。 */
    private boolean openToneEngine() {
        int okCount = 0;
        for (int i = 0; i < 3; i++) {
            try {
                tonePlayer[i] = Manager.createPlayer("device://tone");
                tonePlayer[i].realize();
                toneCtrl[i] = (ToneControl) tonePlayer[i].getControl("ToneControl");
                if (toneCtrl[i] == null) {
                    try { tonePlayer[i].close(); } catch (Throwable ignored) {}
                    tonePlayer[i] = null;
                    continue;                  // 这个声部废了，其他继续
                }
                tonePlayer[i].prefetch();
                okCount++;
            } catch (Throwable t) {
                tonePlayer[i] = null;
                toneCtrl[i] = null;
            }
        }
        if (okCount == 0) {
            initError = "tone: all voices failed";
            return false;
        }
        startToneThread();
        return true;
    }
    // 播放线程只写愿望（want*），专职 tone 线程低频消化——
    // 高频 stop/setSequence/start 会拖死 KEmulator 音频（实测 2 秒卡死）
    private final int[] wantNote = {-1, -1, -1};
    private final int[] toneLastNote = {-1, -1, -1};
    private final int[] toneFail = new int[3];
    private Thread toneThread;
    private boolean toneQuit;

    /** tone 引擎：给第 ch 个方波声部设连续音（vol 0-127 → 0-100）。
     *  音符变化立即更新；只有音量变化时按 12 级量化 + 100ms 节流。 */
    private void toneSeq(int ch, int note, int vol) {
        if (ch < 0 || ch > 2) {
            return;
        }
        if (note < 0) note = 60;
        if (note > 126) note = 126;
        wantNote[ch] = note;      // wave 引擎线程自己消化
    }

    private void toneSilence(int ch) {
        if (ch < 0 || ch > 2) {
            return;
        }
        wantNote[ch] = -1;
    }


    private boolean openMidiNescube() {
        try {
            player = Manager.createPlayer("device://midi");
            if (player == null) {
                initError = "device://midi unavailable";
                return false;
            }
            player.prefetch();
            midi = (MIDIControl) player.getControl("javax.microedition.media.control.MIDIControl");
            if (midi == null) {
                midi = (MIDIControl) player.getControl("MIDIControl");
            }
            setPlayerVolume();
            return midi != null;
        } catch (Throwable t) {
            initError = t.toString();
            safeClose();
            return false;
        }
    }

    private boolean openMidiMeboy() {
        try {
            player = Manager.createPlayer("device://midi");
            if (player == null) {
                return false;
            }
            player.realize();
            player.start();
            midi = (MIDIControl) player.getControl("javax.microedition.media.control.MIDIControl");
            if (midi == null) {
                midi = (MIDIControl) player.getControl("MIDIControl");
            }
            setPlayerVolume();
            return midi != null;
        } catch (Throwable t) {
            initError = t.toString();
            safeClose();
            return false;
        }
    }

    private void closeAll() {
        stopToneThread();
        safeClose();
        for (int i = 0; i < 3; i++) {
            if (tonePlayer[i] != null) {
                try { tonePlayer[i].close(); } catch (Throwable ignored) {}
                tonePlayer[i] = null;
                toneCtrl[i] = null;
            }
        }
    }

    private void stopToneThread() {
        toneQuit = true;
        toneThread = null;
    }

    private void startToneThread() {
        if (toneThread != null) {
            return;
        }
        toneQuit = false;
        toneThread = new Thread(new Runnable() {
            public void run() {
                while (!toneQuit) {
                    for (int ch = 0; ch < 3; ch++) {
                        int want = wantNote[ch];
                        if (want == toneLastNote[ch]) {
                            continue;
                        }
                        try {
                            Player tp = tonePlayer[ch];
                            if (tp == null) {
                                continue;
                            }
                            if (tp.getState() >= Player.STARTED) {
                                tp.stop();
                            }
                            if (want < 0) {
                                toneLastNote[ch] = -1;
                                continue;
                            }
                            byte n = (byte) want;
                            toneCtrl[ch].setSequence(new byte[]{
                                ToneControl.VERSION, 1,
                                ToneControl.TEMPO, 30,
                                n, 127, n, 127, n, 127, n, 127, n, 127, n, 127,
                            });
                            tp.start();
                            toneLastNote[ch] = want;
                        } catch (Throwable t) {
                            if (++toneFail[ch] > 32) {
                                toneCtrl[ch] = null;
                            }
                        }
                    }
                    try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                }
            }
        });
        toneThread.setPriority(Thread.NORM_PRIORITY - 1);
        toneThread.start();
    }

    /** Tower Bloxx（诺基亚原厂）做法：显式把 Player 音量推到最大，默认可能是 0。 */
    private void setPlayerVolume() {
        try {
            VolumeControl vc = (VolumeControl) player.getControl("VolumeControl");
            if (vc != null) {
                vc.setLevel(100);
                vc.setMute(false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void safeClose() {
        if (player != null) {
            try {
                player.close();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    /** 打开后只发音色 + 声像，其余 CC 一律不发（nescube 同款）。 */
    /** nescube 3.2 b()：初始化给全部 16 通道发 CC120（All Sound Off）。 */
    private void nescubeReset() {
        for (int ch = 0; ch < 16; ch++) {
            send(0xB0 | ch, 120, 0);
        }
    }

    private void sendInitProgs() {
        nescubeReset();
        // nescube 3.2 发布版用专用 API（不是 CC）：setProgram(ch,-1,patch) + setChannelVolume(ch,vol)。
        // 诺基亚实现里 shortMidiEvent 的 PC/CC 可能被忽略，而 setChannelVolume 是通道路径。
        for (int i = 0; i < SLOTS; i++) {
            int ch = channelOf(i);
            if (ch == 9) {
                continue;
            }
            try {
                midi.setProgram(ch, -1, progMap[i]);
            } catch (Throwable t) {
                send(PROG | ch, progMap[i], 0);   // 老实现没有 setProgram 时退回 CC
            }
            try {
                midi.setChannelVolume(ch, masterVol);
            } catch (Throwable t) {
                send(CC | ch, CC_VOLUME, masterVol);
            }
        }
        sendPans();
    }

    /** MeBoy advanced 模式：每个 note-on 前重发 CC7，不依赖设备记住任何 CC 状态。 */
    private void noteOn(int ch, int note, int velocity) {
        if (toneEngine) {
            if (ch >= 0 && ch <= 2) {
                toneSeq(ch, note, velocity);
            }
            return;
        }
        // nescube 3.2：NOTE_ON 力度恒 127，音量只走 setChannelVolume/CC7
        try {
            midi.setChannelVolume(ch, masterVol);
        } catch (Throwable t) {
            send(CC | ch, CC_VOLUME, masterVol);
        }
        send(NOTE_ON | ch, note, 127);
    }

    /** 设置菜单切换引擎：完全拆掉旧引擎再建新的，避免两个引擎状态互相污染。 */
    public void setEngineTone(boolean tone) {
        if (FORCE_TONE) {
            return;                       // 固定版：不许切走
        }
        if (tone == toneEngine) {
            return;
        }
        // 1) 全停 + 全拆
        stopToneThread();
        allSoundOff();
        if (midi != null) {
            safeClose();          // 关 MIDI player，midi 置空
        }
        // 2) 建新引擎（失败保留 initError 供 HUD 显示）
        if (tone) {
            if (openToneEngine()) {
                toneEngine = true;
                midiPath = "TONE";
                return;
            }
            // tone 打不开：尽力恢复 MIDI
            if (openMidiNescube() || openMidiMeboy()) {
                toneEngine = false;
                midiPath = "S3";
                sendInitProgs();
            } else {
                ready = false;
            }
        } else {
            if (openMidiNescube() || openMidiMeboy()) {
                toneEngine = false;
                midiPath = "S3";
                sendInitProgs();
                return;
            }
            if (openToneEngine()) {
                toneEngine = true;
                midiPath = "TONE";
            } else {
                ready = false;
            }
        }
    }

    private void allMidiSoundOff() {
        for (int ch = 0; ch < CHANNELS; ch++) {
            send(NOTE_OFF | ch, 60, 0);
        }
    }

    /** nescube 音量模型：电平直接做 note-on 力度（最小 1，0 等于 note-off）。 */
    private static int vel(int level) {
        return level > 0 ? level : 1;
    }

    /** 电平变化时力度重发（参数为 MIDI 通道号）。 */
    private void retriggerVel(int midiCh, int note, int level) {
        if (note >= 0) {
            send(CC | midiCh, CC_VOLUME, masterVol);
            send(NOTE_ON | midiCh, note, vel(level));
        }
    }

    public void setClock(int hz) {
        if (hz > 100000 && hz < 100000000) {
            clock = hz;
            updateEnvStepMs();
        }
    }

    public int getStereoMode() {
        return stereoMode;
    }

    public void setStereoMode(int idx) {
        applyStereoMode(((idx % STEREO_MODES.length) + STEREO_MODES.length)
                % STEREO_MODES.length);
        sendPans();
    }

    private void applyStereoMode(int idx) {
        stereoMode = idx;
        int[] p = STEREO_PANS[idx];
        for (int i = 0; i < 3; i++) {
            pan[i] = p[i];
        }
    }

    private void sendPans() {
        for (int i = 0; i < 3; i++) {
            send(CC | toneCh[i], CC_PAN, pan[i]);
            send(CC | noiseCh[i], CC_PAN, pan[i]);
        }
        send(CC | buzzerCh, CC_PAN, PAN_C);
    }

    /** 发 RPN 0x00/0x00 设置弯音范围（半音）。 */
    /** RPN 固定 ±2 半音（MIDI 默认，vgm2mid 同款校准）。 */
    private void setPitchBendRange(int ch) {
        send(CC | ch, 101, 0);         // RPN MSB = 0
        send(CC | ch, 100, 0);         // RPN LSB = 0 (Pitch Bend Sensitivity)
        send(CC | ch, 6, 2);           // Data Entry MSB = 2 半音
        send(CC | ch, 38, 0);          // Data Entry LSB = 0
        send(CC | ch, 101, 127);       // RPN reset
        send(CC | ch, 100, 127);
    }

    public int getProg(int idx) {
        return progMap[idx];
    }

    /** 用户改音色：idx 是槽位（0-2 方波，3-5 噪声，6-9 包络形状类）。 */
    public void setProg(int idx, int prog) {
        if (idx < 0 || idx >= SLOTS || prog < 0 || prog > 127) {
            return;
        }
        int midiCh = channelOf(idx);
        if (prog == progMap[idx]) {
            return;
        }
        if (idx < 3) {
            noteOff(idx);
        } else if (idx < 6) {
            int i = idx - 3;
            if (noiseSounding[i]) {
                send(NOTE_OFF | midiCh, 60, 0);
                noiseSounding[i] = false;
            }
        } else {
            // 包络四形状类共用蜂鸣器 MIDI 通道：换音色先关音
            buzzOffAll();
            buzzProgSent = -1;
        }
        progMap[idx] = prog;
        if (idx < 6) {
            send(PROG | midiCh, prog, 0);
        }
    }

    private int channelOf(int idx) {
        return idx < 3 ? toneCh[idx] : (idx < 6 ? noiseCh[idx - 3] : buzzerCh);
    }

    public int getOct() {
        return octShift;
    }

    public void setOct(int oct) {
        if (oct < -3) oct = -3;
        if (oct > 3) oct = 3;
        if (oct == octShift) return;
        allSoundOff();
        octShift = oct;
    }

    public void close() {
        stopToneThread();
        allSoundOff();
        if (player != null) {
            try {
                player.stop();
                player.close();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        stopToneThread();
        for (int i = 0; i < 3; i++) {
            if (tonePlayer[i] != null) {
                try { tonePlayer[i].close(); } catch (Throwable ignored) {}
                tonePlayer[i] = null;
                toneCtrl[i] = null;
            }
        }
        midi = null;
        ready = false;
    }

    /** 强制重发所有音色和 CC（每次打开新文件时调用，确保音源状态正确）。 */
    /** 完整复制另一片的配置（乐器/声像/八度）并全量重发。双 PSG 用。 */
    public void copyConfigFrom(Ay2Midi src) {
        for (int i = 0; i < SLOTS; i++) {
            progMap[i] = src.progMap[i];
        }
        applyStereoMode(src.stereoMode);
        octShift = src.octShift;
        noiseMapMode = src.noiseMapMode;
        envOctSaw = src.envOctSaw;
        envOctTri = src.envOctTri;
        envUniSaw = src.envUniSaw;
        envUniTri = src.envUniTri;
        buzzVol = src.buzzVol;
        masterVol = src.masterVol;
        bendSensPct = src.bendSensPct;
        bendRangePct = src.bendRangePct;
        forceRefreshAll();
    }

    public void forceRefreshAll() {
        for (int i = 0; i < 6; i++) {
            send(PROG | channelOf(i), progMap[i], 0);
        }
        buzzProgSent = -1;
        sendPans();
        for (int i = 0; i < CHANNELS; i++) {
            setPitchBendRange(channelOf(i));
        }
        // CC7 (Main Volume) 固定 127，CC11 (Expression) 做动态音量
        for (int i = 0; i < CHANNELS; i++) {
            int ch = channelOf(i);
            send(CC | ch, CC_VOLUME, masterVol);
            send(CC | ch, CC_EXPRESSION, i == 6 ? 127 : 0);
            send(CC | ch, 123, 0);   // All notes off
        }
        for (int i = 0; i < 3; i++) {
            curVol[i] = -1;
            noiseVol[i] = -1;
        }
    }

    public void allSoundOff() {
        for (int ch = 0; ch < 3; ch++) {
            if (toneEngine) {
                toneSilence(ch);
            }
            noteOff(ch);
            buzzer[ch] = false;
            curVol[ch] = -1;
            if (noiseSounding[ch]) {
                send(NOTE_OFF | noiseCh[ch], 60, 0);
                noiseSounding[ch] = false;
            }
            noiseVol[ch] = -1;
        }
        buzzOffAll();
        stopSyncBuzz();
        syncBuzz = false;
        r13Interval = 0;
        lastR13At = 0;
    }

    private int sendOk;
    private int sendFail;
    private String sendErr = "";

    private void send(int status, int d1, int d2) {
        if (!ready || toneEngine || midi == null) {
            return;
        }
        try {
            midi.shortMidiEvent(status, d1, d2);
            sendOk++;
        } catch (Throwable t) {
            // SecurityException 等不能吞掉不报——真机诊断关键
            sendFail++;
            if (sendErr.length() == 0) {
                sendErr = t.toString();
            }
        }
    }

    /** HUD 诊断：引擎与首个异常。 */
    public String midiStatus() {
        if (!ready) {
            return "FAIL: " + initError;
        }
        if (toneEngine) {
            return "Tone";
        }
        StringBuffer sb = new StringBuffer(midiPath);
        if (sendErr.length() > 0) {
            sb.append(" E:").append(clip(sendErr, 40));
        }
        return sb.toString();
    }

    /** 事件发送统计（成功/失败 + 异常摘要）。 */
    public String eventStats() {
        StringBuffer sb = new StringBuffer();
        sb.append(sendOk).append(" ok / ").append(sendFail).append(" fail");
        if (sendErr.length() > 0) {
            sb.append(" - ").append(clip(sendErr, 60));
        }
        return sb.toString();
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    // ------------------------------------------------------------ 寄存器入口

    public void write(int reg, int data) {
        if (reg < 0 || reg > 15) {
            return;
        }
        regs[reg] = data & 0xFF;
        switch (reg) {
            case 0: case 1:
                updateChannel(0);
                break;
            case 2: case 3:
                updateChannel(1);
                break;
            case 4: case 5:
                updateChannel(2);
                break;
            case 6:
                updateNoisePitch();
                break;
            case 7:
                updateAllCtl();
                break;
            case 8:
                updateChannelCtl(0);
                break;
            case 9:
                updateChannelCtl(1);
                break;
            case 10:
                updateChannelCtl(2);
                break;
            case 11: case 12:
                // 包络周期变化会改变蜂鸣器判定与八度，全部声道重估
                updateEnvStepMs();
                updateAllCtl();
                break;
            case 13:
                onShapeWrite();
                updateAllCtl();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------ 声道

    private int channelFreq(int ch) {
        int fnum = ((regs[ch * 2 + 1] & 0x0F) << 8) | regs[ch * 2];
        if (fnum == 0) {
            return 0;
        }
        return (int) (clock / 16L / fnum);
    }

    private boolean envMode(int ch) {
        return (regs[8 + ch] & 0x10) != 0;
    }

    /**
     * 包络形状（R13）→ 每周期步数（yasp11 ay_to_opm.c 同款）：
     *   8,9,11,12,13,15 = 锯齿类，32 步（基准八度）
     *   10,14           = 三角类，64 步（低一个八度）
     *   0-7             = 一次性形状，非连续波形，返回 0
     */
    /** R13 → 包络形状类槽位（6-9）；一次性形状返回 -1。 */
    private int shapeSlot() {
        switch (regs[13] & 0x0F) {
            case 8: case 12: return SLOT_ENV_SAW;
            case 9: case 15: return SLOT_ENV_DECAY;
            case 10: case 14: return SLOT_ENV_TRI;
            case 11: case 13: return SLOT_ENV_ALT;
            default: return -1;
        }
    }

    private int shapeSteps() {
        switch (regs[13] & 0x0F) {
            case 8: case 9: case 11: case 12: case 13: case 15:
                return 32;
            case 10: case 14:
                return 64;
            default:
                return 0;
        }
    }

    /**
     * R13 写入处理。一次性形状（0-7）被高速重写时是"同步蜂鸣器"玩法：
     * 每次写都重启包络，重触发率成为可听音高（>20Hz）。
     * 重复形状（8-15）的重写无此含义，交给常规蜂鸣器分支。
     */
    private void onShapeWrite() {
        long now = System.currentTimeMillis();
        // 硬件语义：写 R13 重启包络发生器（attack 位决定起始方向）
        boolean attack = (regs[13] & 0x04) != 0;
        envPtr = attack ? 0 : 31;
        envFaceUp = attack;
        envPause = false;
        envNextStepAt = now;
        updateEnvStepMs();
        if (shapeSteps() == 0 && anyEnvMode()) {
            if (lastR13At != 0) {
                long dt = now - lastR13At;
                if (dt > 0 && dt < 400) {
                    // 指数平滑间隔，抗抖动
                    r13Interval = r13Interval == 0 ? dt : (r13Interval * 3 + dt) / 4;
                    if (r13Interval > 0 && r13Interval <= 50) {   // ≥20Hz
                        syncBuzz = true;
                    }
                } else if (dt >= 400) {
                    r13Interval = 0;
                    syncBuzz = false;
                }
            }
            if (syncBuzz) {
                triggerSyncBuzz();
            }
        } else {
            // 重复形状接管：同步音让位给常规蜂鸣器
            stopSyncBuzz();
            r13Interval = 0;
            syncBuzz = false;
        }
        lastR13At = now;
    }

    private boolean anyEnvMode() {
        for (int ch = 0; ch < 3; ch++) {
            if ((regs[8 + ch] & 0x10) != 0) {
                return true;
            }
        }
        return false;
    }

    /** 按重触发率发/更新同步蜂鸣音；每次写入做 re-articulation（模拟包络重启）。 */
    private void triggerSyncBuzz() {
        if (r13Interval <= 0) {
            return;
        }
        int hz = (int) (1000 / r13Interval);
        if (hz <= 0) {
            return;
        }
        long n144 = note144(hz);
        if (n144 < 0 || n144 > 127L * 65536) {
            return;
        }
        int note = (int) ((n144 + 32768) / 65536);
        int cents144 = (int) (n144 - note * 65536);
        int bend = centsToBend(cents144);

        int voices = envUniSaw + 1;      // 同步蜂鸣属锯齿族
        if (syncNote >= 0 && (syncNote != note || syncVoices != voices)) {
            for (int i = 0; i < syncVoices; i++) {
                int n = syncNote + i * 12;
                if (n <= 127) {
                    send(NOTE_OFF | buzzerCh, n, 0);
                }
            }
        }
        syncNote = note;
        syncVoices = voices;
        send(PITCH | buzzerCh, bend & 0x7F, (bend >> 7) & 0x7F);
        send(CC | buzzerCh, CC_EXPRESSION, buzzVol);
        for (int i = 0; i < voices; i++) {
            int n = note + i * 12;
            if (n <= 127) {
                noteOn(buzzerCh, n, vel(buzzVol));
            }
        }
    }

    private int syncVoices = 1;

    private void stopSyncBuzz() {
        if (syncNote >= 0) {
            for (int i = 0; i < syncVoices; i++) {
                int n = syncNote + i * 12;
                if (n <= 127) {
                    send(NOTE_OFF | buzzerCh, n, 0);
                }
            }
            syncNote = -1;
        }
        syncVoices = 1;
    }

    /**
     * 播放线程周期调用：重触发停止后关掉同步蜂鸣音，防止卡音符。
     */
    /** v16 式平滑电平（level += (target-level)*0.3 的整数版），供可视亮度用。 */
    private final int[] smooth = new int[3];

    public int visSmoothLevel(int ch) {
        return smooth[ch];
    }

    private void tickSmooth() {
        for (int ch = 0; ch < 3; ch++) {
            int target = buzzer[ch] ? buzzVol : visToneLevel(ch);
            smooth[ch] += (target - smooth[ch]) * 3 / 10;
        }
    }

    public void tick() {
        if (syncBuzz && lastR13At != 0
                && System.currentTimeMillis() - lastR13At > 100) {
            stopSyncBuzz();
            syncBuzz = false;
            r13Interval = 0;
        }
        tickEnvelope();
        tickSmooth();
    }

    private void updateEnvStepMs() {
        if (clock <= 0) {
            envStepMs = -1;
            return;
        }
        // 包络每步 = 16 * period 个时钟（emu2149 同款换算）
        envStepMs = (int) (16L * envelopePeriod() * 1000 / clock);
        if (envStepMs < 1) {
            envStepMs = 1;
        }
    }

    /** 撫放线程节拍：推进软件包络，把电平推给慢包络声道的 CC11。 */
    private void tickEnvelope() {
        if (envStepMs <= 0 || envPause) {
            return;
        }
        long now = System.currentTimeMillis();
        if (envNextStepAt == 0) {
            envNextStepAt = now;
        }
        int guard = 64;   // 防止长时间无人调 tick 后的补偿风暴
        while (now >= envNextStepAt && guard-- > 0) {
            stepEnvelope();
            envNextStepAt += envStepMs;
        }
        if (envNextStepAt < now - 500) {
            envNextStepAt = now;
        }
        pushEnvVolume();
    }

    /** emu2149 update_output() 包络单元的整数化移植。 */
    private void stepEnvelope() {
        if (envPause) {
            return;
        }
        envPtr += envFaceUp ? 1 : -1;
        if ((envPtr & 0x20) != 0) {          // 进位/借位
            boolean cont = (regs[13] & 0x08) != 0;
            boolean alt = (regs[13] & 0x02) != 0;
            boolean hold = (regs[13] & 0x01) != 0;
            if (cont) {
                if (alt != hold) {
                    envFaceUp = !envFaceUp;
                }
                if (hold) {
                    envPause = true;
                }
                envPtr = envFaceUp ? 0 : 31;
            } else {
                envPause = true;
                envPtr = 0;
            }
        }
    }

    /** 当前电平 → CC11（线性近似；AY 的 voltbl 是对数曲线，后续可换表）。 */
    private int envLevelCC() {
        int p = envPtr & 0x1F;
        return p * 127 / 31;
    }

    private void pushEnvVolume() {
        int v = envLevelCC();
        for (int ch = 0; ch < 3; ch++) {
            if (envMode(ch) && !isBuzzer(ch) && v != envLastCC[ch]) {
                envLastCC[ch] = v;
                curVol[ch] = v;
                send(CC | toneCh[ch], CC_EXPRESSION, v);
                // 噪声门共用同一幅度源：mixer 开着噪声时同步复制
                if ((regs[7] & (0x8 << ch)) == 0) {
                    noiseVol[ch] = v;
                    send(CC | noiseCh[ch], CC_EXPRESSION, v);
                }
            }
        }
    }

    /** 慢/快包络分界：包络波形频率低于此值按音量模拟（约 20Hz 听感分界）。 */
    private static final int ENV_TONE_MIN_HZ = 20;

    /**
     * 包络作为波形的输出频率 = clock / (16 × period × steps)（yasp11 同款）。
     * 64 步三角在分母里自然比 32 步锯齿低一个八度。
     * period=0 按硬件语义视为 1。
     */
    private int envWaveHz() {
        int steps = shapeSteps();
        if (steps == 0) {
            return 0;
        }
        int period = envelopePeriod();
        if (period == 0) {
            period = 1;
        }
        return (int) (clock / 16L / period / steps);
    }

    private boolean isBuzzer(int ch) {
        return envMode(ch) && envWaveHz() >= ENV_TONE_MIN_HZ;
    }

    private int envelopePeriod() {
        return ((regs[12] & 0xFF) << 8) | (regs[11] & 0xFF);
    }

    private void updateChannel(int ch) {
        if (buzzer[ch]) {
            updateBuzzerPitch(ch);
        } else {
            updateTonePitch(ch);
        }
    }

    private void updateAllCtl() {
        for (int ch = 0; ch < 3; ch++) {
            updateChannelCtl(ch);
        }
    }

    /**
     * 噪声音高映射：R6 的 32 级均匀摊到钢琴键位，
     * 最高频端(R6=0)=C8(108)，最低频端(R6=31)=A0(21)。
     * 只做映射这一件事，其余噪声行为保持旧版。
     */
    /** 噪声映射三模式：{低,高}。0=C4中央 1=C4最高(定案默认) 2=88键平均。 */
    public static final int[][] NOISE_MAPS = {
        {48, 72},    // C3-C5，C4 居中
        {36, 60},    // C2-C4，C4 封顶
        {21, 108},   // A0-C8 全键盘
    };
    public static final String[] NOISE_MAP_NAMES = {
        "C4 center", "C4 top", "88 keys"
    };
    private int noiseMapMode = 1;
    /** 包络八度：锯齿族（含衰减/交替，32 步）与三角（64 步）分开调。 */
    private int envOctSaw;
    private int envOctTri;
    /** 上行八度复音：0=关 1=+1八度(2复音) 2=+2八度(3复音)。锯齿/三角独立。 */
    private int envUniSaw = 1;   // 定案默认：上行 1 八度（2 复音）
    private int envUniTri = 1;   // 定案默认：上行 1 八度（2 复音）
    /** 蜂鸣器音量（CC11），默认 100。 */
    private int buzzVol = 100;
    /** 主音量（CC7），所有通道统一。 */
    private int masterVol = 100;

    public int getNoiseMapMode() {
        return noiseMapMode;
    }

    public int getEnvOctSaw() {
        return envOctSaw;
    }

    public int getEnvOctTri() {
        return envOctTri;
    }

    public int getEnvUni(boolean tri) {
        return tri ? envUniTri : envUniSaw;
    }

    public int getBuzzVol() {
        return buzzVol;
    }

    public int getMasterVol() {
        return masterVol;
    }



    public int getBendSensPct() {
        return bendSensPct;
    }

    /** 左右键 ±1%（=0.01 倍率）连续调节，RPN 范围固定不动。 */
    public void addBendSens(int dir) {
        int v = bendSensPct + dir;
        if (v < BEND_SENS_MIN) v = BEND_SENS_MIN;
        if (v > BEND_SENS_MAX) v = BEND_SENS_MAX;
        bendSensPct = v;
    }

    /** 范围比例 ±1% 连续调节。 */
    public void addBendRange(int dir) {
        int v = bendRangePct + dir;
        if (v < 1) v = 1;
        if (v > 400) v = 400;
        bendRangePct = v;
    }

    public int getBendRangePct() {
        return bendRangePct;
    }

    public void setBendSensPct(int v) {
        if (v < BEND_SENS_MIN) v = BEND_SENS_MIN;
        if (v > BEND_SENS_MAX) v = BEND_SENS_MAX;
        bendSensPct = v;
    }

    public void setBendRangePct(int v) {
        if (v < 1) v = 1;
        if (v > 400) v = 400;
        bendRangePct = v;
    }

    /** x1.50 样式的显示值。 */
    public String getBendSensText() {
        int whole = bendSensPct / 100;
        int frac = bendSensPct % 100;
        return "x" + whole + "." + (frac < 10 ? "0" : "") + frac;
    }

    /** 主音量 0-127，立即推到全部通道（用于整体音量调节和淡出）。 */
    public void setMasterVol(int v) {
        if (v < 0) v = 0;
        if (v > 127) v = 127;
        if (v == masterVol) {
            return;
        }
        masterVol = v;
        for (int i = 0; i < CHANNELS; i++) {
            int chm = channelOf(i);
            try {
                midi.setChannelVolume(chm, v);
            } catch (Throwable t) {
                send(CC | chm, CC_VOLUME, v);
            }
        }
    }

    // ---------------- 可视化接口（钢琴键盘用） ----------------

    /** 声道当前发声音符（方波或该声道触发的蜂鸣音），-1 无。 */
    public int visNote(int ch) {
        if (buzzSrc == ch && buzzNote >= 0) {
            return buzzNote;
        }
        return curNote[ch];
    }

    /** 该声道噪声门是否开着。 */
    public boolean visNoise(int ch) {
        return noiseSounding[ch];
    }

    /** v16 规则：噪声可视音高 = clock/(512×per)，per=(R6&31)<<1（0 按 2）。 */
    public int visNoiseNote() {
        int per = (regs[6] & 0x1F) << 1;
        if (per == 0) {
            per = 2;
        }
        int hz = (int) (clock / 512L / per);
        long n144 = note144(hz);
        if (n144 < 0 || n144 > 127L * 65536) {
            return -1;
        }
        return (int) ((n144 + 32768) / 65536);
    }

    /** 包络可视音符：正在响的蜂鸣基准音（含同步蜂鸣），-1 无。 */
    public int visEnvNote() {
        if (buzzNote >= 0) {
            return buzzNote;
        }
        return syncNote;
    }

    /** 包络发声的源声道（同步蜂鸣归 A 声道显示），-1 无。 */
    public int visBuzzSrc() {
        if (buzzSrc >= 0) {
            return buzzSrc;
        }
        return syncNote >= 0 ? 0 : -1;
    }

    /** 声道门位（mixer 低有效）：T=方波门开。 */
    public boolean visToneGate(int ch) {
        return (regs[7] & (1 << ch)) == 0;
    }

    /** N=噪声门开。 */
    public boolean visNoiseGate(int ch) {
        return (regs[7] & (0x8 << ch)) == 0;
    }

    /** E=包络位（音量寄存器 bit4）。 */
    public boolean visEnvGate(int ch) {
        return (regs[8 + ch] & 0x10) != 0;
    }

    /** 方波实际电平：蜂鸣器模式下方波静音为 0；慢包络用仿真电平。 */
    public int visToneLevel(int ch) {
        if (buzzer[ch]) {
            return 0;
        }
        if (envMode(ch)) {
            return envLevelCC();
        }
        return ATTN_TO_VOL[regs[8 + ch] & 0x0F];
    }

    /** T 灯：方波门开且实际电平 > 0。 */
    public boolean visToneActive(int ch) {
        return visToneGate(ch) && visToneLevel(ch) > 0;
    }

    /** N 灯：噪声门开且声道电平 > 0（噪声无独立音量计算器）。 */
    public boolean visNoiseActive(int ch) {
        return visNoiseGate(ch) && visToneLevel(ch) > 0;
    }

    /** E 灯：包络位且包络确实在出声（快包络看蜂鸣音量，慢包络看电平）。 */
    public boolean visEnvActive(int ch) {
        return visEnvGate(ch) && (isBuzzer(ch) ? buzzVol > 0 : envLevelCC() > 0);
    }

    /** 该声道当前电平 0-127（键盘按键亮度参考）。 */
    public int visLevel(int ch) {
        int v = curVol[ch];
        if (buzzSrc == ch) {
            v = buzzVol;
        }
        return v < 0 ? 0 : v;
    }

    /** 蜂鸣器音量 0-127，立即推送到通道。 */
    public void setBuzzVol(int v) {
        if (v < 0) v = 0;
        if (v > 127) v = 127;
        buzzVol = v;
        send(CC | buzzerCh, CC_EXPRESSION, v);
    }

    /** 上行八度复音设置（0/1/2 循环）。生效中的蜂鸣音立即重算。 */
    public void setEnvUni(boolean tri, int u) {
        if (u < 0) u = 0;
        if (u > 2) u = 2;
        if (tri) {
            if (u == envUniTri) return;
            envUniTri = u;
        } else {
            if (u == envUniSaw) return;
            envUniSaw = u;
        }
        if (buzzSrc >= 0) {
            updateBuzzerPitch(buzzSrc);
        }
    }

    /** tri=true 调三角八度，否则锯齿族。生效中的蜂鸣音立即重算。 */
    public void setEnvOct(boolean tri, int oct) {
        if (oct < -3) oct = -3;
        if (oct > 3) oct = 3;
        if (tri) {
            if (oct == envOctTri) return;
            envOctTri = oct;
        } else {
            if (oct == envOctSaw) return;
            envOctSaw = oct;
        }
        if (buzzSrc >= 0) {
            updateBuzzerPitch(buzzSrc);
        }
    }

    public void setNoiseMapMode(int mode) {
        mode = ((mode % 3) + 3) % 3;
        if (mode == noiseMapMode) {
            return;
        }
        noiseMapMode = mode;
        // 映射变了，当前 R6 对应的键位也变，立即重算
        updateNoisePitch();
    }

    private void updateNoisePitch() {
        int r6 = regs[6] & 0x1F;
        int low = NOISE_MAPS[noiseMapMode][0];
        int high = NOISE_MAPS[noiseMapMode][1];
        int note = high - r6 * (high - low) / 31;
        if (note == noiseNote) {
            return;
        }
        int oldNote = noiseNote;
        noiseNote = note;
        for (int ch = 0; ch < 3; ch++) {
            if (noiseSounding[ch]) {
                send(NOTE_OFF | noiseCh[ch], oldNote, 0);
                noteOn(noiseCh[ch], noiseNote, noiseVol[ch] > 0 ? vel(noiseVol[ch]) : 100);
            }
        }
    }

    private void updateChannelCtl(int ch) {
        boolean buzz = isBuzzer(ch);
        if (buzz != buzzer[ch]) {
            buzzer[ch] = buzz;
            noteOff(ch);
            if (buzzSrc == ch) {
                buzzOffAll();
            }
        }

        int atten = regs[8 + ch] & 0x0F;

        // 声道唯一电平 v：固定音量 / 慢包络仿真 / 快包络波形近似。
        // 方波门和噪声门都乘它（AY 硬件：一个幅度寄存器管两者）。
        int v;
        if (buzz) {
            v = 100;                   // 波形近似电平（噪声门共用）
        } else if (envMode(ch)) {
            v = envLevelCC();          // 慢包络：软件仿真电平
        } else {
            v = ATTN_TO_VOL[atten];
        }

        // 方波音量（蜂鸣器模式下声音走包络通道，方波通道静音）
        int toneV = buzz ? 0 : v;
        if (toneV != curVol[ch]) {
            int old = curVol[ch];
            curVol[ch] = toneV;
            send(CC | toneCh[ch], CC_EXPRESSION, toneV);
            // nescube 模型：力度重发，CC11 不被支持的真机上音量仍然生效
            if (toneEngine) {
                if (curNote[ch] >= 0 && toneV > 0) {
                    toneSeq(ch, curNote[ch], toneV);
                }
            } else if (old >= 0 && toneV > 0 && Math.abs(toneV - old) >= 16) {
                retriggerVel(toneCh[ch], curNote[ch], toneV);
            }
        }

        // 噪声：复制对应方波通道的电平（无独立音量计算器）
        boolean noiseOn = (regs[7] & (0x8 << ch)) == 0 && v > 0;
        if (noiseOn && v != noiseVol[ch]) {
            noiseVol[ch] = v;
            send(CC | noiseCh[ch], CC_EXPRESSION, v);
        }
        if (noiseOn && !noiseSounding[ch]) {
            noiseSounding[ch] = true;
            noteOn(noiseCh[ch], noiseNote, noiseVol[ch] > 0 ? vel(noiseVol[ch]) : 100);
        } else if (!noiseOn && noiseSounding[ch]) {
            noiseSounding[ch] = false;
            send(NOTE_OFF | noiseCh[ch], noiseNote, 0);
        }

        updateToneSounding(ch);
        if (buzz) {
            updateBuzzerPitch(ch);
        }
    }

    private void updateToneSounding(int ch) {
        boolean mixerOn = (regs[7] & (1 << ch)) == 0;
        boolean audible = (mixerOn || buzzer[ch]) && !buzzer[ch] && curVol[ch] > 0;
        if (!audible) {
            noteOff(ch);
        } else if (curNote[ch] < 0) {
            updateTonePitch(ch);
        }
    }

    private void noteOff(int ch) {
        if (curNote[ch] >= 0) {
            if (toneEngine) {
                toneSilence(ch);
            } else {
                send(NOTE_OFF | toneCh[ch], curNote[ch], 0);
            }
            curNote[ch] = -1;
            curBend[ch] = 8192;
        }
    }

    private long note144(int hz) {
        int lg = log2fx(hz) - LOG2_440 + octShift * 12 * 65536;
        return 69L * 65536 + 12L * (long) lg;
    }

    /**
     * 音分偏差 → 弯音轮值（14 位，8192 居中）。
     * 全程 64 位定点直算，不做整数音分的中间截断：
     * 1/65536 半音粒度直达 14 位弯音轮，分辨率约 0.0002 半音（0.02 音分），
     * 远超 0.01 半音要求。灵敏度只改变固定 ±8192 步内的微分刻度，
     * 不扩大弯音范围（14 位轮物理上限）。
     */
    private int centsToBend(int cents144) {
        long pct = getBendSensPct();
        long bend = 8192 + (long) cents144 * BEND_STEPS_PER_SEMI * pct
                / (100L * 65536L) * bendRangePct / 100;
        if (bend < 0) return 0;
        if (bend > 16383) return 16383;
        return (int) bend;
    }

    private void updateTonePitch(int ch) {
        int hz = channelFreq(ch);
        if (hz <= 0 || buzzer[ch] || curVol[ch] <= 0) {
            noteOff(ch);
            return;
        }
        long n144 = note144(hz);
        if (n144 < 0 || n144 > 127L * 65536) {
            noteOff(ch);
            return;
        }
        int note = (int) ((n144 + 32768) / 65536);
        int cents144 = (int) (n144 - note * 65536);
        int bend = centsToBend(cents144);

        if (curNote[ch] != note) {
            noteOff(ch);
            curNote[ch] = note;
            curBend[ch] = bend;
            send(PITCH | toneCh[ch], bend & 0x7F, (bend >> 7) & 0x7F);
            noteOn(toneCh[ch], note, curVol[ch] > 0 ? vel(curVol[ch]) : 100);
        } else if (Math.abs(bend - curBend[ch]) > 40) {
            curBend[ch] = bend;
            send(PITCH | toneCh[ch], bend & 0x7F, (bend >> 7) & 0x7F);
        }
    }

    private void updateBuzzerPitch(int ch) {
        int hz = envWaveHz();
        if (!buzzer[ch] || hz <= 0) {
            if (buzzSrc == ch) {
                buzzOffAll();
            }
            return;
        }
        // 音高来自包络自身频率，32/64 步的八度差已含在公式分母里；
        // 用户八度偏移按形状族叠加（三角族独立于锯齿族）
        long n144 = note144(hz);
        int octSlot = shapeSlot();
        if (octSlot == SLOT_ENV_TRI) {
            n144 += envOctTri * 12L * 65536;
        } else {
            n144 += envOctSaw * 12L * 65536;
        }
        if (n144 < 0 || n144 > 127L * 65536) {
            return;
        }
        int note = (int) ((n144 + 32768) / 65536);
        int cents144 = (int) (n144 - note * 65536);
        int bend = centsToBend(cents144);

        int slot = shapeSlot();
        int prog = slot >= 0 ? progMap[slot] : progMap[SLOT_ENV_SAW];
        if (prog != buzzProgSent) {
            // 形状类音色变化：先关音再换音色（音源不允许演奏中换）
            buzzOffAll();
            buzzProgSent = prog;
            send(PROG | buzzerCh, prog, 0);
        }
        int voices = familyVoices(slot);
        if (buzzSrc != ch || buzzNote != note || buzzVoices != voices) {
            // 无条件关掉旧堆叠（防止通道切换/复音数变化时泄漏）
            buzzOffAll();
            buzzSrc = ch;
            buzzNote = note;
            buzzVoices = voices;
            send(PITCH | buzzerCh, bend & 0x7F, (bend >> 7) & 0x7F);
            send(CC | buzzerCh, CC_EXPRESSION, buzzVol);
            for (int i = 0; i < voices; i++) {
                int n = note + i * 12;               // 基准 + 上行八度堆叠
                if (n <= 127) {
                    noteOn(buzzerCh, n, vel(buzzVol));
                }
            }
        } else if (Math.abs(bend - curBend[ch]) > 40) {
            send(PITCH | buzzerCh, bend & 0x7F, (bend >> 7) & 0x7F);
        }
    }

    // ------------------------------------------------------------ 定点 log2

    /**
     * LOG2TAB[i] = round(log2(1 + i/256) * 65536)。
     * 用 2^y 的位搜索法在整数域求解，无需浮点（CLDC 没有 Math.log）。
     */
    /** LOG2TAB[i] = round(log2(1 + i/256) * 65536)，Python 预生成。 */
    private static final int[] LOG2TAB = {
        0, 369, 736, 1102, 1466, 1829, 2190, 2551,
        2909, 3267, 3623, 3978, 4331, 4683, 5034, 5384,
        5732, 6079, 6425, 6769, 7112, 7454, 7795, 8134,
        8473, 8810, 9146, 9480, 9814, 10146, 10477, 10807,
        11136, 11464, 11791, 12116, 12440, 12764, 13086, 13407,
        13727, 14046, 14363, 14680, 14996, 15310, 15624, 15937,
        16248, 16559, 16868, 17177, 17484, 17791, 18096, 18401,
        18704, 19007, 19308, 19609, 19909, 20207, 20505, 20802,
        21098, 21393, 21687, 21980, 22272, 22564, 22854, 23144,
        23433, 23720, 24007, 24293, 24579, 24863, 25146, 25429,
        25711, 25992, 26272, 26551, 26830, 27108, 27384, 27660,
        27936, 28210, 28484, 28757, 29029, 29300, 29571, 29840,
        30109, 30378, 30645, 30912, 31178, 31443, 31707, 31971,
        32234, 32496, 32758, 33019, 33279, 33538, 33797, 34055,
        34312, 34569, 34825, 35080, 35334, 35588, 35841, 36094,
        36346, 36597, 36847, 37097, 37346, 37595, 37842, 38090,
        38336, 38582, 38827, 39072, 39316, 39559, 39802, 40044,
        40286, 40527, 40767, 41006, 41246, 41484, 41722, 41959,
        42196, 42432, 42667, 42902, 43137, 43370, 43603, 43836,
        44068, 44300, 44530, 44761, 44990, 45220, 45448, 45676,
        45904, 46131, 46357, 46583, 46809, 47034, 47258, 47482,
        47705, 47928, 48150, 48372, 48593, 48813, 49034, 49253,
        49472, 49691, 49909, 50127, 50344, 50560, 50776, 50992,
        51207, 51422, 51636, 51850, 52063, 52276, 52488, 52700,
        52911, 53122, 53332, 53542, 53751, 53960, 54169, 54377,
        54584, 54791, 54998, 55204, 55410, 55615, 55820, 56025,
        56229, 56432, 56635, 56838, 57040, 57242, 57443, 57644,
        57845, 58045, 58245, 58444, 58643, 58841, 59039, 59237,
        59434, 59631, 59827, 60023, 60219, 60414, 60609, 60803,
        60997, 61190, 61384, 61576, 61769, 61961, 62152, 62343,
        62534, 62725, 62915, 63104, 63294, 63483, 63671, 63859,
        64047, 64234, 64421, 64608, 64794, 64980, 65166, 65351
    };

    /** 440Hz 的定点 log2。必须晚于 LOG2TAB 初始化（Java 按声明顺序执行）。 */
    public static final int LOG2_440 = log2fx(440);

    /** log2(vx) * 65536。最大误差 ~7 音分。 */
    public static int log2fx(int vx) {
        if (vx <= 0) {
            return -0x7fffffff;
        }
        int e = 0;
        while (vx > 0xFFFF) {
            vx >>>= 1;
            e += 65536;
        }
        while (vx <= 0x7FFF) {
            vx <<= 1;
            e -= 65536;
        }
        // vx ∈ [0x8000, 0xFFFF] 表示 [2^15, 2^16)，基数 15 必须加上
        return e + 15 * 65536 + LOG2TAB[(vx - 0x8000) >> 7];
    }

    // ------------------------------------------------------------ 调试

    public String statusLine() {
        return "T[" + dg(curNote[0]) + dg(curNote[1]) + dg(curNote[2])
                + "] N[" + (noiseSounding[0] ? '1' : '0')
                + (noiseSounding[1] ? '1' : '0')
                + (noiseSounding[2] ? '1' : '0')
                + "] B[" + (buzzNote < 0 ? '-' : '1') + "]";
    }

    private static char dg(int v) {
        return v < 0 ? '-' : Integer.toString(v).charAt(0);
    }
}
