package com.denjhang.midimode;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.MIDIControl;

/**
 * nescube / MeBoy 模式忠实复刻探针。逐行对照源码：
 * - MeBoy Dmgcpu.initSound/updateSound（原版=Asha 版，两版一致）
 * - nescube v1.2 engine.OpenMidi/PlayTone/SelectInstrument
 * - nescube v3.2（反编译）：通道 6/7/8 + setProgram/setChannelVolume
 * 关键细节：全部用 Manager.MIDI_DEVICE_LOCATOR 常量（不是字面量）；
 * v1.2 完全不发 CC（音量=velocity 查表）。
 */
public class MidiModeProbe extends Canvas implements Runnable {
    private static final String[] MODES = {
        "1 MeBoy advanced (realize+start)",
        "2 MeBoy simple (velocity vol)",
        "3 nescube v1.2 (prefetch, no CC)",
        "4 nescube v3.2 (ch6-8+setProgram)",
    };
    private static final int MASTER_VOLUME = 8;   // MeBoy 原值
    private static final int[] MIDI_VOL = {       // nescube v1.2 原表
        0x00, 0x20, 0x2E, 0x38, 0x41, 0x49, 0x50, 0x56,
        0x5C, 0x62, 0x67, 0x6C, 0x71, 0x76, 0x7A, 0x7F,
    };
    private static final int[] SCALE = {60, 62, 64, 65, 67, 69, 71, 72};

    private int mode = -1;
    private String status = "auto cycling";
    private String lastErr = "";
    private int sent;
    private int failed;
    private volatile boolean quit;
    private MIDIControl synth;
    private Player player;
    private Thread autoT;

    public MidiModeProbe() {
        autoT = new Thread(this);
        autoT.start();
    }

    private void ev(int s, int d1, int d2) {
        try {
            synth.shortMidiEvent(s, d1, d2);
            sent++;
        } catch (Throwable t) {
            failed++;
            if (lastErr.length() == 0) lastErr = "" + t;
        }
    }

    /* ---------------- 四种模式（逐行对照原作） ---------------- */

    private void runMode(int m) {
        mode = m;
        sent = 0; failed = 0; lastErr = "";
        releasePlayer();
        status = "opening";
        repaint();
        long t0 = System.currentTimeMillis();
        try {
            switch (m) {
                case 0: meboy(true, t0); break;
                case 1: meboy(false, t0); break;
                case 2: nescube12(t0); break;
                case 3: nescube32(t0); break;
            }
        } catch (Throwable t) {
            lastErr = "open: " + t;
        }
        releasePlayer();
        status = "done";
        repaint();
    }

    /** MeBoy initSound + updateSound（advanced=true/false 与原作分支一致）。 */
    private void meboy(boolean advanced, long t0) throws Exception {
        player = Manager.createPlayer("device://midi");   // = Manager.MIDI_DEVICE_LOCATOR 常量值
        player.realize();
        player.start();
        synth = (MIDIControl) player.getControl("MIDIControl");   // 短名
        if (synth == null) { status = "no MIDIControl"; return; }
        if (advanced) {
            synth.shortMidiEvent(0xC0, 81, 0);
            synth.shortMidiEvent(0xC1, 81, 0);
            synth.shortMidiEvent(0xC2, 81, 0);
        }
        status = "playing";
        repaint();
        int[] freq = new int[3];          // soundFrequency[]
        long[] lc = new long[3];
        int[] vol = new int[3];           // soundVolume[]
        long[] lastChange = new long[3];
        int[] notes = {SCALE[0], SCALE[2], SCALE[4]};
        int ni = 0;
        while (System.currentTimeMillis() - t0 < 12000 && !quit) {
            for (int ch = 0; ch < 3; ch++) {
                if (System.currentTimeMillis() - lastChange[ch] < 250) continue;
                lastChange[ch] = System.currentTimeMillis();
                int n = notes[(ni + ch) % SCALE.length];
                int volume = (int) (System.currentTimeMillis() / 500) % 16;
                // —— Dmgcpu.updateSound 原序 ——
                if (advanced) {
                    if (freq[ch] > 0) {
                        ev(0x80 + ch, freq[ch], 127);
                    }
                    freq[ch] = n;
                    ev(0xB0 + ch, 7, volume * MASTER_VOLUME);
                    if (ch == 0) {
                        ev(0xE0, 0x2000 & 127, 0x2000 >> 7);
                    }
                    ev(0x90 + ch, n, 127);
                } else {
                    if (freq[ch] > 0) {
                        ev(0x80 + ch, freq[ch], vol[ch] * MASTER_VOLUME);
                    }
                    freq[ch] = n;
                    ev(0x90 + ch, n, volume * MASTER_VOLUME);
                }
                vol[ch] = volume;
            }
            ni++;
            sleep(20);                    // 60Hz 事件泵（原作每帧调 updateSound）
        }
    }

    /** nescube v1.2：OpenMidi + SelectInstrument + PlayTone（无 CC、无 realize/start）。 */
    private void nescube12(long t0) throws Exception {
        player = Manager.createPlayer("device://midi");   // = Manager.MIDI_DEVICE_LOCATOR 常量值
        player.prefetch();
        synth = (MIDIControl) player.getControl(
                "javax.microedition.media.control.MIDIControl");   // 全名
        if (synth == null) { status = "no MIDIControl"; return; }
        ev(0xC0 | 0, 80, 0);             // SelectInstrument(SQUARE1, 80)
        ev(0xC0 | 1, 80, 0);             // SQUARE2
        ev(0xC0 | 2, 73, 0);             // TRIANGLE, 73
        status = "playing";
        repaint();
        int[] tones = new int[3];
        int[] volum = new int[3];
        long[] lastChange = new long[3];
        int ni = 0;
        while (System.currentTimeMillis() - t0 < 12000 && !quit) {
            for (int ch = 0; ch < 3; ch++) {
                if (System.currentTimeMillis() - lastChange[ch] < 200) continue;
                lastChange[ch] = System.currentTimeMillis();
                int tone = SCALE[(ni + ch) % SCALE.length];
                int volume = (int) (System.currentTimeMillis() / 400) % 16;
                // —— engine.PlayTone 原序 ——
                if (tone != tones[ch] && tones[ch] != 0) {
                    ev(0x80 + ch, tones[ch], 0);
                }
                if (tone != tones[ch] || volume != volum[ch]) {
                    ev(0x90 + ch, tone, MIDI_VOL[volume]);
                    volum[ch] = volume;
                    tones[ch] = tone;
                }
            }
            ni++;
            sleep(15);                    // APU 帧率事件泵
        }
        for (int ch = 0; ch < 3; ch++) {
            if (tones[ch] != 0) ev(0x80 + ch, tones[ch], 0);
        }
    }

    /** nescube v3.2（反编译）：CC120 全通道 + setProgram(6/7/8) + setChannelVolume + 通道 6/7/8。 */
    private void nescube32(long t0) throws Exception {
        player = Manager.createPlayer("device://midi");   // = Manager.MIDI_DEVICE_LOCATOR 常量值
        player.prefetch();
        synth = (MIDIControl) player.getControl(
                "javax.microedition.media.control.MIDIControl");
        if (synth == null) { status = "no MIDIControl"; return; }
        for (int ch = 0; ch < 16; ch++) ev(0xB0 | ch, 120, 0);     // y.b()
        try { synth.setProgram(6, -1, 80); } catch (Throwable ignored) {}
        try { synth.setProgram(7, -1, 80); } catch (Throwable ignored) {}
        try { synth.setProgram(8, -1, 73); } catch (Throwable ignored) {}
        try { synth.setChannelVolume(6, 95); } catch (Throwable ignored) {}
        try { synth.setChannelVolume(7, 95); } catch (Throwable ignored) {}
        try { synth.setChannelVolume(8, 95); } catch (Throwable ignored) {}
        status = "playing";
        repaint();
        int[] tones = new int[3];
        long[] lastChange = new long[3];
        int ni = 0;
        while (System.currentTimeMillis() - t0 < 12000 && !quit) {
            for (int c = 0; c < 3; c++) {
                if (System.currentTimeMillis() - lastChange[c] < 200) continue;
                lastChange[c] = System.currentTimeMillis();
                int ch = 6 + c;                                    // 通道 6/7/8
                int tone = SCALE[(ni + c) % SCALE.length];
                int volIdx = (int) (System.currentTimeMillis() / 400) % 16;
                if (tone != tones[c] && tones[c] != 0) {
                    ev(0x80 + ch, tones[c], 0);
                }
                if (tone != tones[c]) {
                    ev(0xB0 + ch, 7, MIDI_VOL[volIdx]);            // y: CC7 音量
                    ev(0x90 + ch, tone, 127);                      // 力度恒 127
                    tones[c] = tone;
                }
            }
            ni++;
            sleep(15);
        }
        for (int c = 0; c < 3; c++) {
            if (tones[c] != 0) ev(0x80 + (6 + c), tones[c], 0);
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.close(); } catch (Throwable ignored) {}
            player = null;
        }
        synth = null;
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }

    /* ---------------- 自动循环 + UI ---------------- */

    public void run() {
        while (!quit) {
            for (int m = 0; m < MODES.length; m++) {
                if (quit) break;
                runMode(m);
                sleep(800);
            }
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0); g.fillRect(0, 0, w, h);
        Font big = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
        Font sm = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(big);
        g.setColor(0xFFE08A);
        g.drawString(mode >= 0 ? MODES[mode] : "MidiModeProbe", 4, 4, Graphics.TOP | Graphics.LEFT);
        g.setFont(sm);
        int y = big.getHeight() + 12;
        for (int i = 0; i < MODES.length; i++) {
            g.setColor(i == mode ? 0x7FE08A : 0x8FA3C8);
            g.drawString(MODES[i], 4, y, Graphics.TOP | Graphics.LEFT);
            y += sm.getHeight() + 2;
        }
        y += 6;
        g.setColor(0xC8D4E4);
        g.drawString("status: " + status, 4, y, Graphics.TOP | Graphics.LEFT); y += sm.getHeight();
        g.drawString("sent: " + sent + "  failed: " + failed, 4, y, Graphics.TOP | Graphics.LEFT); y += sm.getHeight();
        g.setColor(lastErr.length() > 0 ? 0xFF8A8A : 0x667788);
        g.drawString("err: " + (lastErr.length() > 0 ? lastErr : "-"), 4, y, Graphics.TOP | Graphics.LEFT);
        g.setColor(0x667788);
        g.drawString("1-4=mode 0=exit (auto 12s each)", 4, h - sm.getHeight() - 2, Graphics.TOP | Graphics.LEFT);
    }

    protected void keyPressed(int k) {
        if (k >= KEY_NUM1 && k <= KEY_NUM4) {
            final int m = k - KEY_NUM1;
            new Thread(new Runnable() {
                public void run() { runMode(m); }
            }).start();
        } else if (k == KEY_NUM0) {
            quit = true;
            MidiModeProbeMIDlet.quit();
        }
    }
}
