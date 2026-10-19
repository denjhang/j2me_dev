package com.denjhang.brick;

import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.MIDIControl;

/**
 * MIDI 音频输出：内置步进音序器播 BGM + 即时音效。
 *
 * 用 device://midi + shortMidiEvent 实时发声，不依赖 MIDI 文件资源，
 * 循环节奏自己掌握，所以没有序列文件循环的接缝问题。
 * 只用到 MMAPI 的 MIDIControl，不碰任何可选包。
 *
 * 若设备不支持 device://midi，init() 返回 false，全部接口变成空操作，
 * 游戏照常可玩。
 */
public class MidiAudio implements Runnable {

    /** 16 分音符时长（BPM 140）。 */
    private static final int STEP_MS = 107;
    private static final int STEPS = 64;

    private static final int CH_LEAD = 0;
    private static final int CH_BASS = 1;
    private static final int CH_FX = 2;
    private static final int CH_DRUM = 9;

    private static final int NOTE_ON = 0x90;
    private static final int NOTE_OFF = 0x80;
    private static final int CONTROL_CHANGE = 0xB0;
    private static final int PROGRAM_CHANGE = 0xC0;

    /** A 小调主题，和弦进行 Am - F - C - G，每和弦 16 步。 */
    private static final int[] LEAD = {
        69, 0, 72, 0, 76, 0, 72, 0,
        69, 0, 72, 0, 74, 0, 76, 0,
        65, 0, 69, 0, 72, 0, 69, 0,
        65, 0, 69, 0, 71, 0, 69, 0,
        72, 0, 76, 0, 79, 0, 76, 0,
        72, 0, 76, 0, 77, 0, 79, 0,
        67, 0, 71, 0, 74, 0, 71, 0,
        67, 0, 71, 0, 72, 0, 71, 0
    };

    private static final int[] BASS = {
        45, 0, 0, 0, 45, 0, 0, 0,
        45, 0, 0, 0, 45, 0, 0, 0,
        41, 0, 0, 0, 41, 0, 0, 0,
        41, 0, 0, 0, 41, 0, 0, 0,
        48, 0, 0, 0, 48, 0, 0, 0,
        48, 0, 0, 0, 48, 0, 0, 0,
        43, 0, 0, 0, 43, 0, 0, 0,
        43, 0, 0, 0, 43, 0, 0, 0
    };

    private static final int MAX_PENDING = 24;
    private final int[] pendCh = new int[MAX_PENDING];
    private final int[] pendNote = new int[MAX_PENDING];
    private final long[] pendAt = new long[MAX_PENDING];

    private Player player;
    private MIDIControl midi;
    private boolean ready;

    private Thread thread;
    private volatile boolean running;
    private volatile boolean musicOn;
    private boolean disposed;

    private int step;
    private long nextStepAt;

    /** 标准 MMAPI 里没有这个常量（KEmulator 等实现自带），按规范写字符串。 */
    private static final String MIDI_DEVICE = "device://midi";

    public MidiAudio() {
        for (int i = 0; i < MAX_PENDING; i++) {
            pendCh[i] = -1;
        }
        ready = init();
    }

    private boolean init() {
        try {
            player = Manager.createPlayer(MIDI_DEVICE);
            if (player == null) {
                return false;
            }
            player.realize();
            midi = (MIDIControl) player.getControl("MIDIControl");
            if (midi == null) {
                midi = (MIDIControl) player.getControl(
                        "javax.microedition.media.control.MIDIControl");
            }
            if (midi == null) {
                player.close();
                player = null;
                return false;
            }
            player.prefetch();
            player.start();

            programChange(CH_LEAD, 80);   // Square Lead，8-bit 味
            programChange(CH_BASS, 38);   // Synth Bass 1
            programChange(CH_FX, 81);     // Saw Lead
            setChannelVolume(CH_LEAD, 58);
            setChannelVolume(CH_BASS, 46);
            setChannelVolume(CH_FX, 100);
            return true;
        } catch (Throwable t) {
            player = null;
            midi = null;
            return false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    // ------------------------------------------------------------- 音符原语

    private synchronized void send(int type, int data1, int data2) {
        if (!ready) {
            return;
        }
        try {
            midi.shortMidiEvent(type, data1, data2);
        } catch (Throwable ignored) {
            // 单个事件失败不影响游戏
        }
    }

    private void noteOn(int channel, int note, int velocity) {
        send(NOTE_ON | channel, note, velocity);
    }

    private void noteOff(int channel, int note) {
        send(NOTE_OFF | channel, note, 0);
    }

    private void programChange(int channel, int program) {
        send(PROGRAM_CHANGE | channel, program, 0);
    }

    private void setChannelVolume(int channel, int volume) {
        send(CONTROL_CHANGE | channel, 7, volume);
    }

    private synchronized void scheduleOff(int channel, int note, long at) {
        int slot = -1;
        for (int i = 0; i < MAX_PENDING; i++) {
            if (pendCh[i] < 0) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            long earliest = Long.MAX_VALUE;
            for (int i = 0; i < MAX_PENDING; i++) {
                if (pendAt[i] < earliest) {
                    earliest = pendAt[i];
                    slot = i;
                }
            }
            send(NOTE_OFF | pendCh[slot], pendNote[slot], 0);
        }
        pendCh[slot] = channel;
        pendNote[slot] = note;
        pendAt[slot] = at;
    }

    private synchronized void flushDue(long now) {
        for (int i = 0; i < MAX_PENDING; i++) {
            if (pendCh[i] >= 0 && now >= pendAt[i]) {
                send(NOTE_OFF | pendCh[i], pendNote[i], 0);
                pendCh[i] = -1;
            }
        }
    }

    // ------------------------------------------------------------- 音效

    public void fxBrick() {
        playFx(84, 70, 55);
    }

    public void fxPaddle() {
        playFx(60, 80, 40);
    }

    public void fxLost() {
        playFx(48, 95, 260);
        playFx(43, 80, 320);
    }

    public void fxWin() {
        playFx(72, 90, 220);
        playFx(76, 90, 220);
        playFx(79, 90, 220);
        playFx(84, 95, 420);
    }

    public void fxGameOver() {
        playFx(48, 90, 300);
        playFx(45, 90, 300);
        playFx(41, 85, 520);
    }

    private void playFx(int note, int velocity, int durationMs) {
        if (!ready) {
            return;
        }
        noteOn(CH_FX, note, velocity);
        scheduleOff(CH_FX, note, System.currentTimeMillis() + durationMs);
    }

    // ------------------------------------------------------------- BGM

    public void startMusic() {
        if (!ready || disposed) {
            return;
        }
        musicOn = true;
        if (thread == null) {
            running = true;
            nextStepAt = System.currentTimeMillis();
            thread = new Thread(this);
            thread.start();
        }
    }

    public void stopMusic() {
        musicOn = false;
        allNotesOff();
    }

    public void close() {
        disposed = true;
        musicOn = false;
        running = false;
        thread = null;
        allNotesOff();
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

    private synchronized void allNotesOff() {
        for (int ch = 0; ch < 16; ch++) {
            if (ch == CH_DRUM || ch == CH_LEAD || ch == CH_BASS || ch == CH_FX) {
                send(CONTROL_CHANGE | ch, 123, 0);
                send(CONTROL_CHANGE | ch, 120, 0);
            }
        }
        for (int i = 0; i < MAX_PENDING; i++) {
            pendCh[i] = -1;
        }
    }

    public void run() {
        while (running) {
            long now = System.currentTimeMillis();
            if (now >= nextStepAt) {
                if (musicOn) {
                    tickStep(now);
                }
                step = (step + 1) % STEPS;
                nextStepAt += STEP_MS;
                // 机器卡顿时不追帧，避免一次性倾泻一堆音符
                if (nextStepAt < now - STEP_MS) {
                    nextStepAt = now + STEP_MS;
                }
            }
            flushDue(now);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                // 退出时忽略
            }
        }
    }

    private void tickStep(long now) {
        int i = step;

        int lead = LEAD[i];
        if (lead > 0) {
            noteOn(CH_LEAD, lead, 88);
            scheduleOff(CH_LEAD, lead, now + 90);
        }

        int bass = BASS[i];
        if (bass > 0) {
            noteOn(CH_BASS, bass, 80);
            scheduleOff(CH_BASS, bass, now + 190);
        }

        if (i % 8 == 0) {
            noteOn(CH_DRUM, 36, 70);            // kick
            scheduleOff(CH_DRUM, 36, now + 60);
        }
        if (i % 8 == 4) {
            noteOn(CH_DRUM, 38, 55);            // snare
            scheduleOff(CH_DRUM, 38, now + 60);
        }
        if (i % 2 == 0) {
            noteOn(CH_DRUM, 42, 32);            // hi-hat
            scheduleOff(CH_DRUM, 42, now + 30);
        }
    }
}
