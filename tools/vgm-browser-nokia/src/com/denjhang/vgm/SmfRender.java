package com.denjhang.vgm;

import java.io.ByteArrayOutputStream;

/**
 * 离线渲染器：把 VGM 曲目用 Ay2Midi 的全部转换逻辑渲染成 SMF（.mid 字节流），
 * 供 audio/midi Player 播放（City Bloxx / 4 个钢琴应用验证过的诺基亚唯一可靠 MIDI 路径）。
 *
 * 原理：虚拟时钟驱动的逐事件回放——与实时 playLoop 同一套逻辑，
 * 只是时间不等待、Ay2Midi 处于 recording 模式捕获每个 MIDI 事件。
 */
public final class SmfRender {
    private static final int PPQ = 480;
    private static final int MS_PER_QUARTER = 500;   // 120bpm：480 tick = 500ms

    private SmfRender() {
    }

    /**
     * 渲染整曲（含循环）。
     * @param ay  模板实例（配置从这里复制；渲染用全新实例，不动播放状态）
     */
    public static byte[] render(VgmFile file, Ay2Midi template, int loops) {
        Ay2Midi ay = new Ay2Midi();
        ay.copyConfigFrom(template);
        ay.vtime = 0;
        ay.recording = true;
        ay.recTick = 0;
        if (!ay.ready) {
            // MIDI 打不开也能录（send 只记录）
        }

        Ay2Midi ay2 = null;
        if (file.header.ayClock2 != 0 || ((file.header.chipClock[0x12] & 0x80000000) != 0)) {
            ay2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);
            ay2.copyConfigFrom(ay);
            ay2.vtime = 0;
            ay2.recording = true;
            ay2.recTick = 0;
        }

        VgmParser p = file.getParser();
        p.reset();
        long lastMs = 0;
        int loopPlays = 0;
        while (true) {
            int t = p.next();
            if (t == VgmParser.EV_END) {
                if (file.header.hasLoop() && loopPlays < loops) {
                    p.seek(file.header.loopOffset);
                    loopPlays++;
                    continue;
                }
                break;
            }
            if (t == VgmParser.EV_REG_WRITE && p.evCmd == 0xA0) {
                long songMs = p.getSamples() / 44;
                advance(ay, ay2, lastMs, songMs);
                lastMs = songMs;
                long tick = songMs * PPQ / MS_PER_QUARTER;
                if ((p.evReg & 0x80) != 0) {
                    if (ay2 != null) {
                        ay2.recTick = tick;
                        ay2.write(p.evReg & 0x7F, p.evData);
                    }
                } else {
                    ay.recTick = tick;
                    ay.write(p.evReg, p.evData);
                }
            }
        }
        // 尾部推进让最后音符的包络走完
        advance(ay, ay2, lastMs, lastMs + 2000);
        ay.allSoundOff();
        ay.recTick = (lastMs + 2000) * PPQ / MS_PER_QUARTER;
        if (ay2 != null) {
            ay2.allSoundOff();
            ay2.recTick = ay.recTick;
        }
        ay.recording = false;
        if (ay2 != null) {
            ay2.recording = false;
        }
        return build(ay, ay2);
    }

    /** 虚拟时间从 fromMs 推到 toMs，每 10ms 一拍驱动 tick()（包络/平滑状态机）。 */
    private static void advance(Ay2Midi ay, Ay2Midi ay2, long fromMs, long toMs) {
        for (long ms = fromMs; ms < toMs; ms += 10) {
            ay.vtime = ms;
            ay.recTick = ms * PPQ / MS_PER_QUARTER;
            ay.tick();
            if (ay2 != null) {
                ay2.vtime = ms;
                ay2.recTick = ay.recTick;
                ay2.tick();
            }
        }
    }

    /** 合并两芯片事件流 → SMF format 0。 */
    private static byte[] build(Ay2Midi ay, Ay2Midi ay2) {
        java.util.Vector ev = new java.util.Vector();
        append(ev, ay);
        if (ay2 != null) {
            append(ev, ay2);
        }
        // 按时间排序（稳定：同 tick 保持插入序）
        for (int i = 1; i < ev.size(); i++) {
            long[] e = (long[]) ev.elementAt(i);
            int j = i - 1;
            while (j >= 0 && ((long[]) ev.elementAt(j))[0] > e[0]) {
                ev.setElementAt(ev.elementAt(j), j + 1);
                j--;
            }
            ev.setElementAt(e, j + 1);
        }
        ByteArrayOutputStream tr = new ByteArrayOutputStream(4096);
        // tempo: 120bpm
        tr.write(0x00); tr.write(0xFF); tr.write(0x51); tr.write(0x03);
        tr.write(0x07); tr.write(0xA1); tr.write(0x20);
        long lastTick = 0;
        for (int i = 0; i < ev.size(); i++) {
            long[] e = (long[]) ev.elementAt(i);
            writeVarLen(tr, (int) (e[0] - lastTick));
            lastTick = e[0];
            tr.write((int) e[1]);
            tr.write((int) e[2]);
            tr.write((int) e[3]);
        }
        tr.write(0x00); tr.write(0xFF); tr.write(0x2F); tr.write(0x00);
        byte[] track = tr.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream(track.length + 32);
        out.write('M'); out.write('T'); out.write('h'); out.write('d');
        w32(out, 6);
        w16(out, 0);          // format 0
        w16(out, 1);          // 1 track
        w16(out, PPQ);
        out.write('M'); out.write('T'); out.write('r'); out.write('k');
        w32(out, track.length);
        out.write(track, 0, track.length);
        return out.toByteArray();
    }

    private static void append(java.util.Vector ev, Ay2Midi ay) {
        for (int i = 0; i < ay.rec.size(); i++) {
            ev.addElement(ay.rec.elementAt(i));
        }
    }

    private static void writeVarLen(ByteArrayOutputStream o, int value) {
        if (value < 0) value = 0;
        long buffer = value & 0x7F;
        while ((value >>= 7) > 0) {
            buffer <<= 8;
            buffer |= ((value & 0x7F) | 0x80);
        }
        while (true) {
            o.write((int) (buffer & 0xFF));
            if (buffer < 0x80) {
                break;
            }
            buffer >>>= 8;
        }
    }

    private static void w32(ByteArrayOutputStream o, int v) {
        o.write(v >> 24); o.write(v >> 16); o.write(v >> 8); o.write(v);
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write(v >> 8); o.write(v);
    }

}
