package com.denjhang.vgm;

import java.io.ByteArrayOutputStream;
import java.util.Vector;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VolumeControl;

/**
 * SMF 流引擎：实时捕获 Ay2Midi 的事件，按窗口打成小 SMF，
 * audio/midi Player 流水线背靠背播放（City Bloxx 每次播放现建 Player 的路径）。
 *
 * 时间线：窗口 W = CHUNK_MS。消费者线程每到窗口终点，取走 [已发, 窗口尾) 的事件，
 * 生成 SMF（尾部空事件补齐窗口长度），预建 Player（realize+prefetch），
 * 前一个 END_OF_MEDIA 时无缝 start 下一个。
 */
public class SmfStreamEngine implements PlayerListener {
    private static final int CHUNK_MS = 3000;
    private static final int PPQ = 480;
    private static final int MS_PER_Q = 500;

    private final Ay2Midi ay;
    private final Ay2Midi ay2;      // 第二芯片（无则 null）
    private Thread feeder;
    private volatile boolean quit;
    private Player current;      // 播放中
    private Player next;         // 预备
    private long windowStart;    // 已消费到的 ms（相对 recBase）
    private int consumedIdx;
    private int consumed2Idx;
    public int volume = 75;      // VolumeControl 0-100

    public SmfStreamEngine(Ay2Midi ay, Ay2Midi ay2) {
        this.ay = ay;
        this.ay2 = ay2;
    }

    public void start() {
        quit = false;
        consumedIdx = 0;
        consumed2Idx = 0;
        windowStart = 0;
        feeder = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        });
        feeder.setPriority(Thread.NORM_PRIORITY + 1);
        feeder.start();
    }

    public void stop() {
        quit = true;
        if (feeder != null) {
            try { feeder.join(); } catch (InterruptedException ignored) {}
            feeder = null;
        }
        closePlayer(current); current = null;
        closePlayer(next); next = null;
    }

    private void loop() {
        long t0 = System.currentTimeMillis();
        while (!quit) {
            long wall = System.currentTimeMillis() - t0;
            long target = windowStart + CHUNK_MS;
            // 先把下一块预备好（realize+prefetch 重，提前做）
            if (next == null) {
                byte[] smf = buildChunk(windowStart, target);
                next = makePlayer(smf);
                drainConsumed();          // 立即裁剪已消费事件，内存有界
            }
            // 窗口到期 且 下一块已就绪 才滚动；否则等（避免接力空档）
            if (wall < target || next == null) {
                try { Thread.sleep(40); } catch (InterruptedException e) { break; }
                continue;
            }
            Player startMe = next;
            next = null;
            windowStart = target;
            if (current == null) {
                playNow(startMe);
            } else {
                pending = startMe;   // END_OF_MEDIA 时接力
            }
        }
    }

    /** 裁掉两路录制流中已消费的事件（OOM 根治：录制缓冲有界）。 */
    private void drainConsumed() {
        for (int i = 0; i < consumedIdx; i++) {
            ay.rec.removeElementAt(0);
        }
        consumedIdx = 0;
        if (ay2 != null) {
            for (int i = 0; i < consumed2Idx; i++) {
                ay2.rec.removeElementAt(0);
            }
            consumed2Idx = 0;
        }
    }

    private Player pending;

    private void playNow(Player p) {
        if (p == null) {
            return;
        }
        current = p;
        try {
            p.addPlayerListener(this);
            p.start();
        } catch (Throwable t) {
            closePlayer(p);
            current = null;
        }
    }

    public void playerUpdate(Player p, String event, Object data) {
        if (event == PlayerListener.END_OF_MEDIA && p == current) {
            closePlayer(current);
            current = null;
            if (pending != null) {
                Player np = pending;
                pending = null;
                playNow(np);
            }
        }
    }

    private Player makePlayer(byte[] smf) {
        if (smf == null) {
            return null;
        }
        try {
            Player p = Manager.createPlayer(new java.io.ByteArrayInputStream(smf), "audio/midi");
            p.realize();
            p.prefetch();
            VolumeControl vc = (VolumeControl) p.getControl("VolumeControl");
            if (vc != null) {
                vc.setLevel(volume);
                vc.setMute(false);
            }
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private void closePlayer(Player p) {
        if (p != null) {
            try { p.close(); } catch (Throwable ignored) {}
        }
    }

    /** [fromMs, toMs) 窗口内的事件 → 自包含 SMF（相对 tick，尾部补窗口长度）。 */
    private byte[] buildChunk(long fromMs, long toMs) {
        Vector rec = ay.rec;
        // 快进 consumedIdx 到 >= fromMs
        while (consumedIdx < rec.size()
                && ((long[]) rec.elementAt(consumedIdx))[0] < fromMs) {
            consumedIdx++;
        }
        ByteArrayOutputStream tr = new ByteArrayOutputStream(512);
        tr.write(0x00); tr.write(0xFF); tr.write(0x51); tr.write(0x03);
        tr.write(0x07); tr.write(0xA1); tr.write(0x20);
        long lastTick = 0;
        int i = consumedIdx;
        while (i < rec.size()) {
            long[] e = (long[]) rec.elementAt(i);
            if (e[0] >= toMs) {
                break;
            }
            long relTick = (e[0] - fromMs) * PPQ / MS_PER_Q;
            writeVarLen(tr, (int) (relTick - lastTick));
            lastTick = relTick;
            tr.write((int) e[1]);
            tr.write((int) e[2]);
            tr.write((int) e[3]);
            i++;
        }
        long endTick = (toMs - fromMs) * PPQ / MS_PER_Q;
        writeVarLen(tr, (int) Math.max(0, endTick - lastTick));
        tr.write(0xFF); tr.write(0x2F); tr.write(0x00);
        byte[] track = tr.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(track.length + 32);
        out.write('M'); out.write('T'); out.write('h'); out.write('d');
        w32(out, 6); w16(out, 0); w16(out, 1); w16(out, PPQ);
        out.write('M'); out.write('T'); out.write('r'); out.write('k');
        w32(out, track.length);
        out.write(track, 0, track.length);
        return out.toByteArray();
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
