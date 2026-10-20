package com.denjhang.soundprobe;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.MIDIControl;
import javax.microedition.media.control.ToneControl;
import javax.microedition.media.control.VolumeControl;

/**
 * 声音探针：打开即自动逐项测试所有发声方式，屏幕大字显示当前测试项，
 * 用户只需听——哪项出声一目了然。测完从头循环，按 5 立即重测，按 0 退出。
 */
public class SoundProbe extends Canvas implements Runnable {
    private static final String[] NAMES = {
        "1 playTone x1 (ref)",          // 必响基准
        "2 playTone x3 threads",        // 三声部并发
        "3 ToneControl 1 voice",        // 单音
        "4 ToneControl 3 voices",       // 三播放器并发
        "5 MIDI nescube order",         // device://midi prefetch+full-name
        "6 MIDI meboy order",           // device://midi realize+start
        "7 MIDI ch6 nescube style",     // nescube 3.2 通道/音色/音量全套
        "8 MIDI file stream (SMF)",     // City Bloxx 路径：audio/midi 字节流
        "9 SMF stream x3 chunks",       // 逐块接力（我们的流引擎形态）
        "A Nokia UI Sound (.ott)",      // Bounce 的 com.nokia.mid.sound.Sound
    };
    private String[] results = new String[NAMES.length];
    private int cur = -1;
    private boolean running = true;
    private Thread t;
    private Player holdPlayer;      // 保持引用防止被 GC

    public SoundProbe() {
        t = new Thread(this);
        t.start();
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(0);
        g.fillRect(0, 0, w, h);
        Font big = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
        Font small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(big);
        g.setColor(0xFFE08A);
        if (cur >= 0 && cur < NAMES.length) {
            g.drawString("TEST " + NAMES[cur], 4, 4, Graphics.TOP | Graphics.LEFT);
        } else {
            g.drawString("SoundProbe", 4, 4, Graphics.TOP | Graphics.LEFT);
        }
        g.setFont(small);
        int y = big.getHeight() + 12;
        for (int i = 0; i < NAMES.length; i++) {
            g.setColor(i == cur ? 0x7FE08A : 0x8FA3C8);
            g.drawString(NAMES[i] + (results[i] != null ? " -> " + results[i] : ""),
                    4, y, Graphics.TOP | Graphics.LEFT);
            y += small.getHeight() + 4;
        }
        g.setColor(0x667788);
        g.drawString("auto:1,2,5-10 | 3,4=ToneCtrl(manual,crash risk) | 5=reset 0=exit", 4, h - small.getHeight() - 2,
                Graphics.TOP | Graphics.LEFT);
    }

    private void show(int idx, String result) {
        cur = idx;
        if (result != null) {
            results[idx] = result;
        }
        repaint();
        serviceRepaints();
    }

    private void pause(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }

    public void run() {
        while (running) {
            try {
                test1(); pause(600);
                test2(); pause(600);
                // 3/4（ToneControl）S40 上会崩 VM，改为手动：按 3 / 按 4
                test5(); pause(600);
                test6(); pause(600);
                test7(); pause(600);
                test8(); pause(600);
                test9(); pause(600);
                testA(); pause(600);
            } catch (Throwable t) {
                if (cur >= 0) results[cur] = "EX " + t;
            }
            pause(1500);
        }
    }

    private void test1() {
        show(0, "playing");
        try {
            Manager.playTone(67, 1200, 100);
            show(0, "OK (heard?)");
        } catch (Throwable t) {
            show(0, "EX " + t);
        }
    }

    private void test2() {
        show(1, "playing");
        Thread[] ts = new Thread[3];
        final int[] notes = {60, 64, 67};
        for (int i = 0; i < 3; i++) {
            final int n = notes[i];
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        for (int k = 0; k < 6; k++) {
                            Manager.playTone(n, 200, 100);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            ts[i].start();
        }
        for (int i = 0; i < 3; i++) {
            try { ts[i].join(); } catch (InterruptedException ignored) {}
        }
        show(1, "OK (3 voices?)");
    }

    private void test3() {
        show(2, "playing");
        try {
            Player p = Manager.createPlayer("device://tone");
            p.realize();
            ToneControl tc = (ToneControl) p.getControl("ToneControl");
            if (tc == null) {
                show(2, "no ToneControl");
                p.close();
                return;
            }
            tc.setSequence(new byte[]{ToneControl.VERSION, 1, ToneControl.TEMPO, 30,
                60, 127, 64, 127, 67, 127, 64, 127, 60, 127});
            p.prefetch();
            p.start();
            holdPlayer = p;
            pause(2500);
            p.stop();
            p.close();
            show(2, "OK (heard?)");
        } catch (Throwable t) {
            show(2, "EX " + t);
        }
    }

    private void test4() {
        show(3, "playing");
        try {
            Player[] ps = new Player[3];
            ToneControl[] tcs = new ToneControl[3];
            int[] notes = {60, 63, 67};
            for (int i = 0; i < 3; i++) {
                ps[i] = Manager.createPlayer("device://tone");
                ps[i].realize();
                tcs[i] = (ToneControl) ps[i].getControl("ToneControl");
                if (tcs[i] == null) {
                    show(3, "no TC @" + i);
                    return;
                }
                byte n = (byte) notes[i];
                tcs[i].setSequence(new byte[]{ToneControl.VERSION, 1, ToneControl.TEMPO, 30,
                    n, 127, n, 127, n, 127, n, 127, n, 127, n, 127});
                ps[i].prefetch();
                ps[i].start();
            }
            holdPlayer = ps[0];
            pause(2500);
            for (int i = 0; i < 3; i++) {
                ps[i].stop();
                ps[i].close();
            }
            show(3, "OK (3 voices?)");
        } catch (Throwable t) {
            show(3, "EX " + t);
        }
    }

    private MIDIControl openMidi(boolean nescube) throws Exception {
        Player p = Manager.createPlayer("device://midi");
        if (nescube) {
            p.prefetch();
        } else {
            p.realize();
            p.start();
        }
        MIDIControl m = (MIDIControl) p.getControl("javax.microedition.media.control.MIDIControl");
        if (m == null) {
            m = (MIDIControl) p.getControl("MIDIControl");
        }
        holdPlayer = p;
        return m;
    }

    private void closeHeld() {
        if (holdPlayer != null) {
            try { holdPlayer.close(); } catch (Throwable ignored) {}
            holdPlayer = null;
        }
    }

    private void melody(MIDIControl m, int ch) {
        int[] ns = {60, 64, 67, 72, 67, 64, 60};
        for (int i = 0; i < ns.length; i++) {
            m.shortMidiEvent(0x90 | ch, ns[i], 127);
            pause(220);
            m.shortMidiEvent(0x80 | ch, ns[i], 0);
        }
    }

    private void test5() {
        show(4, "opening");
        try {
            MIDIControl m = openMidi(true);
            if (m == null) {
                show(4, "no MIDIControl");
                closeHeld();
                return;
            }
            m.shortMidiEvent(0xC0, 80, 0);
            m.shortMidiEvent(0xB0, 7, 127);
            melody(m, 0);
            closeHeld();
            show(4, "OK (heard?)");
        } catch (Throwable t) {
            show(4, "EX " + t);
            closeHeld();
        }
    }

    private void test6() {
        show(5, "opening");
        try {
            MIDIControl m = openMidi(false);
            if (m == null) {
                show(5, "no MIDIControl");
                closeHeld();
                return;
            }
            m.shortMidiEvent(0xC0, 80, 0);
            m.shortMidiEvent(0xB0, 7, 127);
            melody(m, 0);
            closeHeld();
            show(5, "OK (heard?)");
        } catch (Throwable t) {
            show(5, "EX " + t);
            closeHeld();
        }
    }

    private void test7() {
        show(6, "opening");
        try {
            MIDIControl m = openMidi(true);
            if (m == null) {
                show(6, "no MIDIControl");
                closeHeld();
                return;
            }
            // nescune 3.2 全套：CC120 全通道 + setProgram(6,-1,80) + setChannelVolume(6,95)
            for (int ch = 0; ch < 16; ch++) {
                m.shortMidiEvent(0xB0 | ch, 120, 0);
            }
            m.setProgram(6, -1, 80);
            m.setChannelVolume(6, 95);
            melody(m, 6);
            closeHeld();
            show(6, "OK (heard?)");
        } catch (Throwable t) {
            show(6, "EX " + t);
            closeHeld();
        }
    }

    /** 生成一个小 SMF（单轨，C 大调琶音），City Bloxx 路径播放。 */
    private static byte[] buildSmf() {
        int[] notes = {60, 64, 67, 72, 67, 64, 60};
        // MIDI 轨道数据：program change + note on/off，delta time 全 1（120tick=0.5s@PPQ480? 用 PPQ=480, tempo 默认）
        java.io.ByteArrayOutputStream tr = new java.io.ByteArrayOutputStream();
        tr.write(0x00); tr.write(0xC0); tr.write(80);            // prog
        for (int i = 0; i < notes.length; i++) {
            tr.write(0x00); tr.write(0x90); tr.write(notes[i]); tr.write(110);
            tr.write(0x81); tr.write(0x60);                       // delta=240
            tr.write(0x80); tr.write(notes[i]); tr.write(0);
        }
        tr.write(0x00); tr.write(0xFF); tr.write(0x2F); tr.write(0x00); // end of track
        byte[] track = tr.toByteArray();
        java.io.ByteArrayOutputStream s = new java.io.ByteArrayOutputStream();
        s.write('M'); s.write('T'); s.write('h'); s.write('d');
        write32(s, 6); write16(s, 0); write16(s, 1); write16(s, 480);
        s.write('M'); s.write('T'); s.write('r'); s.write('k');
        write32(s, track.length);
        s.write(track, 0, track.length);
        return s.toByteArray();
    }

    private static void write32(java.io.ByteArrayOutputStream o, int v) {
        o.write(v >> 24); o.write(v >> 16); o.write(v >> 8); o.write(v);
    }

    private static void write16(java.io.ByteArrayOutputStream o, int v) {
        o.write(v >> 8); o.write(v);
    }

    private void test8() {
        show(7, "building SMF");
        try {
            byte[] smf = buildSmf();
            Player p = Manager.createPlayer(new java.io.ByteArrayInputStream(smf), "audio/midi");
            p.realize();
            p.prefetch();
            VolumeControl vc = (VolumeControl) p.getControl("VolumeControl");
            if (vc != null) {
                vc.setLevel(75);      // City Bloxx 同款
                vc.setMute(false);
            }
            p.start();
            holdPlayer = p;
            pause(3500);              // 等旋律播完
            p.close();
            holdPlayer = null;
            show(7, "OK (heard?)");
        } catch (Throwable t) {
            show(7, "EX " + t);
            closeHeld();
        }
    }

    /** 三块 SMF 接力播放（模拟流引擎）。 */
    private void test9() {
        show(8, "playing 3 chunks");
        try {
            int[] starts = {60, 62, 64};
            for (int c = 0; c < 3; c++) {
                byte[] smf = buildSmfFrom(starts[c]);
                Player p = Manager.createPlayer(new java.io.ByteArrayInputStream(smf), "audio/midi");
                p.realize();
                p.prefetch();
                VolumeControl vc = (VolumeControl) p.getControl("VolumeControl");
                if (vc != null) vc.setLevel(75);
                p.start();
                pause(1800);
                p.close();
            }
            show(8, "OK (seamless?)");
        } catch (Throwable t) {
            show(8, "EX " + t);
        }
    }

    /** 指定起始音的 5 音上行 SMF。 */
    private static byte[] buildSmfFrom(int start) {
        int[] notes = {start, start + 4, start + 7, start + 12, start + 7};
        java.io.ByteArrayOutputStream tr = new java.io.ByteArrayOutputStream();
        tr.write(0x00); tr.write(0xC0); tr.write(80);
        for (int i = 0; i < notes.length; i++) {
            tr.write(0x00); tr.write(0x90); tr.write(notes[i]); tr.write(110);
            tr.write(0x81); tr.write(0x60);
            tr.write(0x80); tr.write(notes[i]); tr.write(0);
        }
        tr.write(0x00); tr.write(0xFF); tr.write(0x2F); tr.write(0x00);
        byte[] track = tr.toByteArray();
        java.io.ByteArrayOutputStream s = new java.io.ByteArrayOutputStream();
        s.write('M'); s.write('T'); s.write('h'); s.write('d');
        write32(s, 6); write16(s, 0); write16(s, 1); write16(s, 480);
        s.write('M'); s.write('T'); s.write('r'); s.write('k');
        write32(s, track.length);
        s.write(track, 0, track.length);
        return s.toByteArray();
    }

    /** Bounce 原版 pop.ott 字节（com.nokia.mid.sound.Sound 固件解析实证样本）。 */
    private static final byte[] POP_OTT = {
        2, 74, 58, 64, 4, 0, 15, 28, (byte) 197, 24, (byte) 161, (byte) 202, 36, (byte) 140, 0, 0
    };

    /** Nokia UI API 1.0：逐方法容错——各固件方法集不同（S40 模拟器无 init）。 */
    private void testA() {
        show(9, "creating");
        try {
            Class.forName("com.nokia.mid.sound.Sound");   // 存在性检查
            com.nokia.mid.sound.Sound snd =
                    new com.nokia.mid.sound.Sound(POP_OTT, com.nokia.mid.sound.Sound.SOUND_FORMAT_TONE);
            StringBuffer api = new StringBuffer("ctor");
            try { snd.setGain(255); api.append("+gain"); } catch (Throwable ignored) {}
            try { snd.init(); api.append("+init"); } catch (Throwable ignored) {}
            try {
                snd.play(3);
                api.append("+play");
            } catch (Throwable t) {
                show(9, api + " playEX " + t);
                return;
            }
            pause(2500);
            try { snd.stop(); } catch (Throwable ignored) {}
            try { snd.release(); api.append("+rel"); } catch (Throwable ignored) {}
            show(9, api + " (heard?)");
        } catch (Throwable t) {
            show(9, "EX " + t);
        }
    }

    protected void keyPressed(int keyCode) {
        if (keyCode == KEY_NUM3) {
            test3();
        } else if (keyCode == KEY_NUM4) {
            test4();
        } else if (keyCode == KEY_NUM5) {
            for (int i = 0; i < results.length; i++) {
                results[i] = null;
            }
            cur = -1;
            repaint();
        } else if (keyCode == KEY_NUM0) {
            running = false;
            closeHeld();
            notifyDestroyed();
        }
    }

    private void notifyDestroyed() {
        // Canvas 没有 notifyDestroyed；通过 MIDlet 引用退出
        SoundProbeMIDlet.quit();
    }
}
