package com.denjhang.vgmbrowser;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.MIDIControl;
import javax.microedition.media.control.ToneControl;

/**
 * 独立 MIDI 诊断屏（不播放曲目）。
 * 按数字键逐项测试 device://midi 的各种打开方式与发声方式，
 * 哪一项出声就说明那条路可用；0/* 是参照组（ToneControl / playTone 必响）。
 */
public class MidiTestScreen extends Canvas implements CommandListener {
    private final BrowserMIDlet midlet;
    private final Command cmdBack = new Command("Back", Command.BACK, 1);

    private String[] results = new String[12];
    private String lastLine = "Press 1-9,0,* to run tests";

    private static final String[] TESTS = {
        "1 nescube order + CC7 + note",      // createPlayer→prefetch→getControl→CC7→note
        "2 meboy order + CC7 + note",        // createPlayer→realize→start→getControl→CC7→note
        "3 pure velocity note (no CC)",      // 最朴素：只有 PROG + NOTE_ON vel127
        "4 per-note CC7 (v1.1.7 style)",
        "5 program 0 piano note",
        "6 channel sweep 0-6",
        "7 setChannelVolume API + note",
        "8 longMidiEvent note",
        "9 pitch bend slide up",
        "0 ToneControl beep (reference)",    // 必响：不响就是整机音频问题
        "* playTone static (reference)",     // 必响
        "# close player",
    };

    public MidiTestScreen(BrowserMIDlet midlet) {
        this.midlet = midlet;
        addCommand(cmdBack);
        setCommandListener(this);
        setTitle("MIDI Test");
    }

    private Player player;
    private MIDIControl midi;

    /** 按指定顺序打开 device://midi，返回 null 表示失败（err 记原因）。 */
    private String[] openMidi(boolean nescubeOrder) {
        String[] r = new String[2];
        try {
            closePlayer();
            player = Manager.createPlayer("device://midi");
            if (nescubeOrder) {
                player.prefetch();
                midi = (MIDIControl) player.getControl("javax.microedition.media.control.MIDIControl");
                if (midi == null) {
                    midi = (MIDIControl) player.getControl("MIDIControl");
                }
                r[0] = "prefetch+ctrl";
            } else {
                player.realize();
                player.start();
                midi = (MIDIControl) player.getControl("javax.microedition.media.control.MIDIControl");
                if (midi == null) {
                    midi = (MIDIControl) player.getControl("MIDIControl");
                }
                r[0] = "realize+start+ctrl";
            }
            if (midi == null) {
                r[1] = "no MIDIControl";
                closePlayer();
                return r;
            }
            return r;
        } catch (Throwable t) {
            r[1] = t.toString();
            closePlayer();
            return r;
        }
    }

    private void closePlayer() {
        if (player != null) {
            try { player.close(); } catch (Throwable ignored) {}
            player = null;
        }
        midi = null;
    }

    private void ev(int status, int d1, int d2) {
        midi.shortMidiEvent(status, d1, d2);
    }

    private void delay(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    protected void keyPressed(int keyCode) {
        try {
            switch (keyCode) {
                case KEY_NUM1: test1(true); break;
                case KEY_NUM2: test1(false); break;
                case KEY_NUM3: test3(); break;
                case KEY_NUM4: test4(); break;
                case KEY_NUM5: test5(); break;
                case KEY_NUM6: test6(); break;
                case KEY_NUM7: test7(); break;
                case KEY_NUM8: test8(); break;
                case KEY_NUM9: test9(); break;
                case KEY_NUM0: testTone(); break;
                case KEY_STAR: testPlayTone(); break;
                case KEY_POUND: closePlayer(); lastLine = "player closed"; break;
                default: return;
            }
        } catch (Throwable t) {
            lastLine = "EX: " + t;
        }
        repaint();
    }

    private void test1(boolean nescubeOrder) throws Exception {
        String[] r = openMidi(nescubeOrder);
        if (midi == null) { results[0] = "FAIL " + r[0] + " " + r[1]; return; }
        ev(0xC0, 80, 0);
        ev(0xB0, 7, 127);
        ev(0x90, 60, 127);
        delay(700);
        ev(0x80, 60, 0);
        results[0] = "OK " + r[0] + " (heard?)";
    }

    private void test3() throws Exception {
        String[] r = openMidi(true);
        if (midi == null) { results[2] = "FAIL " + r[1]; return; }
        ev(0xC0, 80, 0);
        ev(0x90, 60, 127);
        delay(700);
        ev(0x80, 60, 0);
        results[2] = "OK pure velocity (heard?)";
    }

    private void test4() throws Exception {
        String[] r = openMidi(true);
        if (midi == null) { results[3] = "FAIL " + r[1]; return; }
        ev(0xC0, 80, 0);
        ev(0xB0, 7, 100);
        ev(0x90, 60, 127);
        delay(300);
        ev(0xB0, 7, 60);
        ev(0x90, 60, 100);
        delay(400);
        ev(0x80, 60, 0);
        results[3] = "OK per-note CC7 (heard?)";
    }

    private void test5() throws Exception {
        String[] r = openMidi(true);
        if (midi == null) { results[4] = "FAIL " + r[1]; return; }
        ev(0xC0, 0, 0);
        ev(0x90, 60, 127);
        delay(700);
        ev(0x80, 60, 0);
        results[4] = "OK piano prog 0 (heard?)";
    }

    private void test6() throws Exception {
        String[] r = openMidi(true);
        if (midi == null) { results[5] = "FAIL " + r[1]; return; }
        for (int ch = 0; ch <= 6; ch++) {
            if (ch == 9) continue;
            ev(0xC0 | ch, 80, 0);
            ev(0x90 | ch, 60, 127);
            delay(350);
            ev(0x80 | ch, 60, 0);
        }
        results[5] = "OK sweep ch0-6 (which sounded?)";
    }

    private void test7() throws Exception {
        String[] r = openMidi(true);
        if (midi == null) { results[6] = "FAIL " + r[1]; return; }
        ev(0xC0, 80, 0);
        try {
            midi.setChannelVolume(0, 100);
            ev(0x90, 60, 127);
            delay(700);
            ev(0x80, 60, 0);
            results[6] = "OK setChannelVolume (heard?)";
        } catch (Throwable t) {
            results[6] = "setChannelVolume EX: " + t;
        }
    }

    private void test8() throws Exception {
        String[] r = openMidi(true);
        if (midi == null) { results[7] = "FAIL " + r[1]; return; }
        try {
            // longMidiEvent: [status, d1, d2] 不带 running status
            midi.longMidiEvent(new byte[]{(byte) 0xC0, 80, 0}, 0, 3);
            midi.longMidiEvent(new byte[]{(byte) 0x90, 60, 127}, 0, 3);
            delay(700);
            midi.longMidiEvent(new byte[]{(byte) 0x80, 60, 0}, 0, 3);
            results[7] = "OK longMidiEvent (heard?)";
        } catch (Throwable t) {
            results[7] = "longMidiEvent EX: " + t;
        }
    }

    private void test9() throws Exception {
        String[] r = openMidi(true);
        if (midi == null) { results[8] = "FAIL " + r[1]; return; }
        ev(0xC0, 80, 0);
        ev(0x90, 60, 110);
        for (int b = 8192; b < 16383; b += 256) {
            ev(0xE0, b & 0x7F, (b >> 7) & 0x7F);
            delay(25);
        }
        ev(0xE0, 0, 64);
        ev(0x80, 60, 0);
        results[8] = "OK bend slide (heard?)";
    }

    private void testTone() {
        try {
            closePlayer();
            player = Manager.createPlayer("device://tone");
            player.realize();
            ToneControl tc = (ToneControl) player.getControl("ToneControl");
            if (tc == null) {
                results[9] = "no ToneControl";
                return;
            }
            tc.setSequence(new byte[]{
                ToneControl.VERSION, 1, ToneControl.TEMPO, 30,
                ToneControl.BLOCK_START, 0, 60, 32, ToneControl.BLOCK_END, 0,
                ToneControl.PLAY_BLOCK, 0,
            });
            player.prefetch();
            player.start();
            results[9] = "OK tone (heard? must sound!)";
        } catch (Throwable t) {
            results[9] = "EX: " + t;
        }
    }

    private void testPlayTone() {
        try {
            Manager.playTone(72, 500, 100);
            results[10] = "OK playTone (heard? must sound!)";
        } catch (Throwable t) {
            results[10] = "EX: " + t;
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(0x0C1118);
        g.fillRect(0, 0, w, h);
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(f);
        g.setColor(0xF0EDE4);
        g.drawString("MIDI Test - press key to run", 4, 2, Graphics.TOP | Graphics.LEFT);
        int y = 22;
        for (int i = 0; i < TESTS.length; i++) {
            g.setColor(0x8FA3C8);
            g.drawString(TESTS[i], 4, y, Graphics.TOP | Graphics.LEFT);
            if (results[i] != null) {
                g.setColor(results[i].startsWith("OK") || results[i].startsWith("EX") == false
                        && results[i].indexOf("FAIL") < 0 && results[i].indexOf("EX") < 0
                        ? 0x7FE08A : 0xFF8A8A);
                g.drawString(results[i], w - 4 - f.stringWidth(results[i]), y,
                        Graphics.TOP | Graphics.LEFT);
            }
            y += 16;
        }
        g.setColor(0xC8D4E4);
        if (y < h - 16) {
            g.drawString(lastLine, 4, h - 18, Graphics.TOP | Graphics.LEFT);
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdBack) {
            closePlayer();
            midlet.startApp();
        }
    }
}
