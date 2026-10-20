package com.denjhang.vgm;

import java.io.ByteArrayOutputStream;

/**
 * 双缓冲段构建：把一段录制事件（[0, segMs]）打成自包含 SMF。
 * 头部重发全部音色/声像/音量（每块是新 Player，合成器状态会复位）。
 */
public final class SmfSeg {
    private static final int PPQ = 480;
    private static final int MS_PER_Q = 500;

    private SmfSeg() {
    }

    public static byte[] build(java.util.Vector rec, java.util.Vector rec2,
                               long segMs, long[][] init, long[][] init2) {
        ByteArrayOutputStream tr = new ByteArrayOutputStream(1024);
        tr.write(0x00); tr.write(0xFF); tr.write(0x51); tr.write(0x03);
        tr.write(0x07); tr.write(0xA1); tr.write(0x20);   // 120bpm
        long lastTick = 0;
        // 头部：chip1 + chip2 初始化事件（tick 0）
        lastTick = emit(tr, init, 0, lastTick);
        if (init2 != null) {
            lastTick = emit(tr, init2, 0, lastTick);
        }
        // 两路归并（按时间）
        int i = 0, j = 0;
        while (true) {
            long[] e1 = i < rec.size() ? (long[]) rec.elementAt(i) : null;
            long[] e2 = rec2 != null && j < rec2.size() ? (long[]) rec2.elementAt(j) : null;
            long[] e;
            if (e1 == null && e2 == null) break;
            if (e1 == null) { e = e2; j++; }
            else if (e2 == null || e1[0] <= e2[0]) { e = e1; i++; }
            else { e = e2; j++; }
            if (e[0] > segMs) break;
            long relTick = e[0] * PPQ / MS_PER_Q;
            vlq(tr, (int) (relTick - lastTick));
            lastTick = relTick;
            int st = (int) e[1];
            tr.write(st);
            tr.write((int) e[2]);
            if (st < 0xC0 || st > 0xDF) {
                tr.write((int) e[3]);
            }
        }
        // 段尾静音补齐 + 全音符关
        long endTick = segMs * PPQ / MS_PER_Q;
        vlq(tr, (int) Math.max(0, endTick - lastTick - 1));
        tr.write(0xFF); tr.write(0x2F); tr.write(0x00);
        byte[] track = tr.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream(track.length + 32);
        o.write('M'); o.write('T'); o.write('h'); o.write('d');
        w32(o, 6); w16(o, 0); w16(o, 1); w16(o, PPQ);
        o.write('M'); o.write('T'); o.write('r'); o.write('k');
        w32(o, track.length);
        o.write(track, 0, track.length);
        return o.toByteArray();
    }

    private static long emit(ByteArrayOutputStream tr, long[][] ev, long atTick, long lastTick) {
        for (int k = 0; k < ev.length; k++) {
            vlq(tr, (int) (atTick - lastTick));
            lastTick = atTick;
            writeMsg(tr, ev[k]);
        }
        return lastTick;
    }

    /** MIDI 消息长度：0xC0-0xDF（Program Change/通道压力）两字节，其余三字节。 */
    private static void writeMsg(ByteArrayOutputStream tr, long[] e) {
        int st = (int) e[0];
        tr.write(st);
        tr.write((int) e[1]);
        if (st < 0xC0 || st > 0xDF) {
            tr.write((int) e[2]);
        }
    }

    private static void vlq(ByteArrayOutputStream o, int value) {
        if (value < 0) value = 0;
        long buffer = value & 0x7F;
        while ((value >>= 7) > 0) {
            buffer <<= 8;
            buffer |= ((value & 0x7F) | 0x80);
        }
        while (true) {
            o.write((int) (buffer & 0xFF));
            if (buffer < 0x80) return;
            buffer >>>= 8;
        }
    }

    private static void w32(ByteArrayOutputStream o, int v) {
        o.write(v >> 24); o.write(v >> 16); o.write(v >> 8); o.write(v);
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write(v >> 8); o.write(v);
    }

    /** 流式段构建器：init(...) 头部 + event(...) 序列 + build(段时长)。 */
    public static class Builder {
        private final ByteArrayOutputStream tr = new ByteArrayOutputStream(1024);
        private long lastTick = 0;

        public Builder() {
            tr.write(0x00); tr.write(0xFF); tr.write(0x51); tr.write(0x03);
            tr.write(0x07); tr.write(0xA1); tr.write(0x20);   // 120bpm
        }

        public void init(long[][] ev) {
            for (int k = 0; k < ev.length; k++) {
                vlq(tr, 0);
                writeMsg(tr, ev[k]);
            }
        }

        /** 复用内部缓冲（跨段覆盖填充，避免每段重建数组的 GC 开销）。 */
        public void reset() {
            tr.reset();
            lastTick = 0;
            tr.write(0x00); tr.write(0xFF); tr.write(0x51); tr.write(0x03);
            tr.write(0x07); tr.write(0xA1); tr.write(0x20);   // 120bpm
        }

        /** relTick 相对段起点（非减）。 */
        public void event(long relTick, int status, int d1, int d2) {
            vlq(tr, (int) Math.max(0, relTick * PPQ / MS_PER_Q - lastTick));
            lastTick = relTick * PPQ / MS_PER_Q;
            tr.write(status);
            tr.write(d1);
            if (status < 0xC0 || status > 0xDF) {
                tr.write(d2);
            }
        }

        public byte[] build(long segMs) {
            long endTick = segMs * PPQ / MS_PER_Q;
            vlq(tr, (int) Math.max(0, endTick - lastTick - 1));
            tr.write(0xFF); tr.write(0x2F); tr.write(0x00);
            byte[] track = tr.toByteArray();
            ByteArrayOutputStream o = new ByteArrayOutputStream(track.length + 32);
            o.write('M'); o.write('T'); o.write('h'); o.write('d');
            w32(o, 6); w16(o, 0); w16(o, 1); w16(o, PPQ);
            o.write('M'); o.write('T'); o.write('r'); o.write('k');
            w32(o, track.length);
            o.write(track, 0, track.length);
            return o.toByteArray();
        }
    }
}
