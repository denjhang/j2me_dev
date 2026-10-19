package com.denjhang.vgm;

import javax.microedition.media.Manager;
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

    private static final int[] TONE_CH = {0, 1, 2};
    private static final int[] NOISE_CH = {3, 4, 5};
    private static final int BUZZER = 6;
    public static final int CHANNELS = 7;

    // MIDI 状态字节（低 4 位 OR 通道号）
    private static final int NOTE_ON = 0x90;
    private static final int NOTE_OFF = 0x80;
    private static final int CC = 0xB0;
    private static final int CC_VOLUME = 7;
    private static final int CC_PAN = 10;
    private static final int CC_EXPRESSION = 11;
    private static final int PROG = 0xC0;
    private static final int PITCH = 0xE0;

    /** 弯音范围（半音）：±12 覆盖 VGM 大范围滑音；KEmulator 默认 ±2。 */
    private static final int BEND_RANGE_SEMITONES = 12;

    public static final int PROG_SQUARE = 80;
    public static final int PROG_BUZZER = 83;
    public static final int PROG_NOISE = 127;

    /** 默认音色映射（可被用户逐通道覆盖）。 */
    public static final int[] DEFAULT_PROG = {
        PROG_SQUARE, PROG_SQUARE, PROG_SQUARE,
        PROG_NOISE, PROG_NOISE, PROG_NOISE,
        PROG_BUZZER
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
    private final int[] progMap = new int[CHANNELS];
    private final int[] pan = new int[3];

    private final int[] curNote = new int[3];
    private final int[] curBend = new int[3];
    private final int[] curVol = new int[3];
    private final boolean[] buzzer = new boolean[3];

    private final boolean[] noiseSounding = new boolean[3];
    private final int[] noiseVol = new int[3];

    private int buzzSrc = -1;
    private int buzzNote = -1;

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
        for (int i = 0; i < 3; i++) {
            curNote[i] = -1;
            curBend[i] = 8192;
            curVol[i] = -1;
            noiseVol[i] = -1;
        }
        for (int i = 0; i < CHANNELS; i++) {
            progMap[i] = DEFAULT_PROG[i];
        }
        applyStereoMode(0);
        ready = init();
    }

    private boolean init() {
        try {
            player = Manager.createPlayer("device://midi");
            if (player == null) {
                initError = "device://midi 不可用";
                return false;
            }
            player.realize();
            midi = (MIDIControl) player.getControl("MIDIControl");
            if (midi == null) {
                player.close();
                player = null;
                initError = "无 MIDIControl";
                return false;
            }
            player.prefetch();
            player.start();
            for (int i = 0; i < CHANNELS; i++) {
                send(PROG | i, progMap[i], 0);
            }
            sendPans();
            // 初始化所有音量为 0（防止残留），播放时再按寄存器推上去
            for (int i = 0; i < CHANNELS; i++) {
                send(CC | i, 7, 0);
            }
            return true;
        } catch (Throwable t) {
            initError = t.toString();
            return false;
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
            send(CC | TONE_CH[i], CC_PAN, pan[i]);
            send(CC | NOISE_CH[i], CC_PAN, pan[i]);
        }
        send(CC | BUZZER, CC_PAN, PAN_C);
    }

    /** 发 RPN 0x00/0x00 设置弯音范围（半音）。 */
    private void setPitchBendRange(int ch, int semitones) {
        send(CC | ch, 101, 0);         // RPN MSB = 0
        send(CC | ch, 100, 0);         // RPN LSB = 0 (Pitch Bend Sensitivity)
        send(CC | ch, 6, semitones);   // Data Entry MSB = 半音数
        send(CC | ch, 38, 0);          // Data Entry LSB = 0（音分，忽略）
        send(CC | ch, 101, 127);       // RPN reset
        send(CC | ch, 100, 127);
    }

    public int getProg(int midiCh) {
        return progMap[midiCh];
    }

    /** 用户改音色：关掉该通道音符再换音色。 */
    public void setProg(int midiCh, int prog) {
        if (midiCh < 0 || midiCh >= CHANNELS || prog < 0 || prog > 127) {
            return;
        }
        if (prog == progMap[midiCh]) {
            return;
        }
        switch (midiCh) {
            case 0: case 1: case 2:
                noteOff(midiCh);
                break;
            case 3: case 4: case 5:
                int i = midiCh - 3;
                if (noiseSounding[i]) {
                    send(NOTE_OFF | midiCh, 60, 0);
                    noiseSounding[i] = false;
                }
                break;
            default:
                if (buzzNote >= 0) {
                    send(NOTE_OFF | BUZZER, buzzNote, 0);
                    buzzNote = -1;
                    buzzSrc = -1;
                }
                break;
        }
        progMap[midiCh] = prog;
        send(PROG | midiCh, prog, 0);
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
        allSoundOff();
        if (player != null) {
            try {
                player.stop();
                player.close();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        midi = null;
        ready = false;
    }

    /** 强制重发所有音色和 CC（每次打开新文件时调用，确保音源状态正确）。 */
    public void forceRefreshAll() {
        for (int i = 0; i < CHANNELS; i++) {
            send(PROG | i, progMap[i], 0);
        }
        sendPans();
        // 设置所有通道弯音范围为 ±12 半音（VGM 大范围滑音常见）
        for (int i = 0; i < CHANNELS; i++) {
            setPitchBendRange(i, BEND_RANGE_SEMITONES);
        }
        // CC7 (Main Volume) 固定 127，CC11 (Expression) 做动态音量
        for (int i = 0; i < CHANNELS; i++) {
            send(CC | i, CC_VOLUME, 127);
            send(CC | i, CC_EXPRESSION, i == BUZZER ? 127 : 0);
            send(CC | i, 123, 0);   // All notes off
        }
        for (int i = 0; i < 3; i++) {
            curVol[i] = -1;
            noiseVol[i] = -1;
        }
    }

    public void allSoundOff() {
        for (int ch = 0; ch < 3; ch++) {
            noteOff(ch);
            buzzer[ch] = false;
            curVol[ch] = -1;
            if (noiseSounding[ch]) {
                send(NOTE_OFF | NOISE_CH[ch], 60, 0);
                noiseSounding[ch] = false;
            }
            noiseVol[ch] = -1;
        }
        if (buzzNote >= 0) {
            send(NOTE_OFF | BUZZER, buzzNote, 0);
        }
        buzzSrc = -1;
        buzzNote = -1;
        stopSyncBuzz();
        syncBuzz = false;
        r13Interval = 0;
        lastR13At = 0;
    }

    private void send(int status, int d1, int d2) {
        if (!ready) {
            return;
        }
        try {
            midi.shortMidiEvent(status, d1, d2);
        } catch (Throwable ignored) {
        }
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

        if (syncNote >= 0 && syncNote != note) {
            send(NOTE_OFF | BUZZER, syncNote, 0);
        }
        syncNote = note;
        send(PITCH | BUZZER, bend & 0x7F, (bend >> 7) & 0x7F);
        send(CC | BUZZER, CC_EXPRESSION, 127);
        send(NOTE_ON | BUZZER, note, 127);
    }

    private void stopSyncBuzz() {
        if (syncNote >= 0) {
            send(NOTE_OFF | BUZZER, syncNote, 0);
            syncNote = -1;
        }
    }

    /**
     * 播放线程周期调用：重触发停止后关掉同步蜂鸣音，防止卡音符。
     */
    public void tick() {
        if (syncBuzz && lastR13At != 0
                && System.currentTimeMillis() - lastR13At > 100) {
            stopSyncBuzz();
            syncBuzz = false;
            r13Interval = 0;
        }
        tickEnvelope();
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
                send(CC | TONE_CH[ch], CC_EXPRESSION, v);
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

    private void updateChannelCtl(int ch) {
        boolean buzz = isBuzzer(ch);
        if (buzz != buzzer[ch]) {
            buzzer[ch] = buzz;
            noteOff(ch);
            if (buzzSrc == ch && buzzNote >= 0) {
                send(NOTE_OFF | BUZZER, buzzNote, 0);
                buzzNote = -1;
                buzzSrc = -1;
            }
        }

        int atten = regs[8 + ch] & 0x0F;

        // 方波音量：蜂鸣器走独立通道；慢包络近似固定 100
        int v;
        if (buzz) {
            v = -1;
        } else if (envMode(ch)) {
            v = envLevelCC();          // 慢包络：软件仿真电平
        } else {
            v = ATTN_TO_VOL[atten];
        }
        if (v >= 0 && v != curVol[ch]) {
            curVol[ch] = v;
            send(CC | TONE_CH[ch], CC_EXPRESSION, v);
        }

        // 噪声独立声道
        boolean noiseOn = (regs[7] & (0x8 << ch)) == 0 && atten > 0;
        int nv = noiseOn ? N_ATT_TO_VOL[atten] : 0;
        if (nv > 0 && nv != noiseVol[ch]) {
            noiseVol[ch] = nv;
            send(CC | NOISE_CH[ch], CC_EXPRESSION, nv);
        }
        if (noiseOn && !noiseSounding[ch]) {
            noiseSounding[ch] = true;
            send(NOTE_ON | NOISE_CH[ch], 60, 100);
        } else if (!noiseOn && noiseSounding[ch]) {
            noiseSounding[ch] = false;
            send(NOTE_OFF | NOISE_CH[ch], 60, 0);
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
            send(NOTE_OFF | TONE_CH[ch], curNote[ch], 0);
            curNote[ch] = -1;
            curBend[ch] = 8192;
        }
    }

    private long note144(int hz) {
        int lg = log2fx(hz) - LOG2_440 + octShift * 12 * 65536;
        return 69L * 65536 + 12L * (long) lg;
    }

    /** 音分偏差 → 弯音轮值（14 位，8192 居中）。
     *  弯音范围 ±BEND_RANGE_SEMITONES 半音 = ±(BEND_RANGE*100) 音分。
     */
    private int centsToBend(int cents144) {
        // cents144 = 偏差 * 65536，单位音分
        // bend = 8192 + cents * 8192 / (BEND_RANGE*100)
        long cents = (long) cents144 * 100 / 65536;
        long bend = 8192 + cents * 8192 / (BEND_RANGE_SEMITONES * 100);
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
            send(PITCH | TONE_CH[ch], bend & 0x7F, (bend >> 7) & 0x7F);
            send(NOTE_ON | TONE_CH[ch], note, 100);
        } else if (Math.abs(bend - curBend[ch]) > 4) {
            curBend[ch] = bend;
            send(PITCH | TONE_CH[ch], bend & 0x7F, (bend >> 7) & 0x7F);
        }
    }

    private void updateBuzzerPitch(int ch) {
        int hz = envWaveHz();
        if (!buzzer[ch] || hz <= 0) {
            if (buzzSrc == ch && buzzNote >= 0) {
                send(NOTE_OFF | BUZZER, buzzNote, 0);
                buzzNote = -1;
                buzzSrc = -1;
            }
            return;
        }
        // 音高来自包络自身频率（updateBuzzerPitch 的 hz 参数即 envWaveHz），
        // 32/64 步的八度差已包含在公式分母里
        long n144 = note144(hz);
        if (n144 < 0 || n144 > 127L * 65536) {
            return;
        }
        int note = (int) ((n144 + 32768) / 65536);
        int cents144 = (int) (n144 - note * 65536);
        int bend = centsToBend(cents144);

        if (buzzSrc != ch || buzzNote != note) {
            // 无条件关掉旧音符（防止通道切换时复音泄漏）
            if (buzzNote >= 0) {
                send(NOTE_OFF | BUZZER, buzzNote, 0);
            }
            buzzSrc = ch;
            buzzNote = note;
            send(PITCH | BUZZER, bend & 0x7F, (bend >> 7) & 0x7F);
            send(CC | BUZZER, CC_EXPRESSION, 127);  // 蜂鸣器固定最大音量
            send(NOTE_ON | BUZZER, note, 127);
        } else if (Math.abs(bend - curBend[ch]) > 4) {
            send(PITCH | BUZZER, bend & 0x7F, (bend >> 7) & 0x7F);
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
