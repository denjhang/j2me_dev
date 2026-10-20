package com.denjhang.smfprobe;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VolumeControl;
import java.io.ByteArrayOutputStream;

/**
 * SMF 流机制探针：同一旋律，六种播放策略对比——找出哪种能连续不断。
 * 自动循环逐个跑 12 秒，也可按 1-6 手动选。听感对比：断/连/稳。
 */
public class SmfProbe extends Canvas implements PlayerListener, Runnable {
    private static final String[] MODES = {
        "1 whole 20s SMF (ref)",     // 整曲一次播：连续性基准
        "2 relay 3s chunks",         // END_OF_MEDIA 接力 3 秒块
        "3 relay 1.5s chunks",       // 接力 1.5 秒块
        "4 wall-clock 1.5s",         // 按墙钟起块（重叠 300ms）
        "5 relay 6s chunks",         // 接力 6 秒块
        "6 two overlap players",     // 双播放器交叠
    };
    private int mode = -1;
    private String status = "auto cycling...";
    private int chunks;
    private String lastErr = "";
    private volatile boolean quit;
    private Player cur;
    private Player nxt;
    private Thread autoT;
    private Player pending;

    public SmfProbe() {
        autoT = new Thread(this);
        autoT.start();
    }

    /* ---------------- 旋律（纯函数：任意时间窗生成事件） ---------------- */

    private static final int[] SCALE = {60, 62, 64, 65, 67, 69, 71, 72};

    /** [fromMs,toMs) 窗口：每 NOTE_MS 一个音，音高上下行循环。 */
    private byte[] buildChunk(long fromMs, long toMs, int noteMs) {
        ByteArrayOutputStream tr = new ByteArrayOutputStream(256);
        tr.write(0x00); tr.write(0xFF); tr.write(0x51); tr.write(0x03);
        tr.write(0x07); tr.write(0xA1); tr.write(0x20);   // 120bpm
        tr.write(0x00); tr.write(0xC0); tr.write(80);     // square lead
        long lastTick = 0;
        long t = (fromMs / noteMs) * noteMs;
        if (t < fromMs) t += noteMs;
        while (t < toMs) {
            long idx = (t / noteMs) % (SCALE.length * 2 - 2);
            int up = (int) idx;
            int note = up < SCALE.length ? SCALE[up] : SCALE[SCALE.length * 2 - 2 - up];
            long relTick = (t - fromMs) * 480 / 500;
            vlq(tr, (int) (relTick - lastTick));
            lastTick = relTick;
            tr.write(0x90); tr.write(note); tr.write(110);
            vlq(tr, (int) Math.max(0, (noteMs - 40) * 480 / 500 - 0));
            tr.write(0x80); tr.write(note); tr.write(0);
            t += noteMs;
        }
        long endTick = (toMs - fromMs) * 480 / 500;
        vlq(tr, (int) Math.max(0, endTick - lastTick));
        tr.write(0xFF); tr.write(0x2F); tr.write(0x00);
        byte[] track = tr.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream(track.length + 32);
        o.write('M'); o.write('T'); o.write('h'); o.write('d');
        w32(o, 6); w16(o, 0); w16(o, 1); w16(o, 480);
        o.write('M'); o.write('T'); o.write('r'); o.write('k');
        w32(o, track.length);
        o.write(track, 0, track.length);
        return o.toByteArray();
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

    /* ---------------- 播放器 ---------------- */

    private Player make(long from, long to, int noteMs) {
        try {
            Player p = Manager.createPlayer(
                    new java.io.ByteArrayInputStream(buildChunk(from, to, noteMs)), "audio/midi");
            p.realize();
            p.prefetch();
            VolumeControl vc = (VolumeControl) p.getControl("VolumeControl");
            if (vc != null) {
                vc.setLevel(80);
                vc.setMute(false);
            }
            return p;
        } catch (Throwable t) {
            lastErr = "mk:" + t;
            return null;
        }
    }

    private void closeQuiet(Player p) {
        if (p != null) {
            try { p.close(); } catch (Throwable ignored) {}
        }
    }

    public void playerUpdate(Player p, String e, Object d) {
        if (e == PlayerListener.END_OF_MEDIA && p == cur) {
            synchronized (this) {
                closeQuiet(cur);
                cur = null;
                if (pending != null) {
                    Player np = pending;
                    pending = null;
                    startPlayer(np);
                }
            }
        }
    }

    private void startPlayer(Player p) {
        if (p == null) return;
        cur = p;
        try {
            p.addPlayerListener(this);
            p.start();
            chunks++;
        } catch (Throwable t) {
            lastErr = "st:" + t;
            closeQuiet(p);
            cur = null;
        }
    }

    /* ---------------- 六种策略 ---------------- */

    private void runMode(int m) {
        mode = m;
        chunks = 0;
        lastErr = "";
        closeQuiet(cur); cur = null;
        closeQuiet(nxt); nxt = null;
        pending = null;
        status = "running";
        repaint();
        long t0 = System.currentTimeMillis();
        try {
            switch (m) {
                case 0: {   // 整曲
                    Player p = make(0, 20000, 300);
                    startPlayer(p);
                    while (System.currentTimeMillis() - t0 < 12000 && !quit) sleep(100);
                    break;
                }
                case 1: relayChunks(t0, 3000, 300); break;
                case 2: relayChunks(t0, 1500, 300); break;
                case 4: relayChunks(t0, 6000, 300); break;
                case 3: {   // 墙钟起块：每窗口到点起新块，旧块延迟 300ms 停（交叠）
                    long w = 1500;
                    long win = 0;
                    while (System.currentTimeMillis() - t0 < 12000 && !quit) {
                        Player p = make(win, win + w, 300);
                        final Player old = cur;
                        startPlayer(p);
                        if (old != null) {
                            final Player f = old;
                            new Thread(new Runnable() {
                                public void run() {
                                    sleep(300);
                                    try { f.stop(); } catch (Throwable ignored) {}
                                    closeQuiet(f);
                                }
                            }).start();
                        }
                        win += w;
                        long until = t0 + win;
                        while (System.currentTimeMillis() < until && !quit) sleep(50);
                    }
                    break;
                }
                case 5: {   // 双播放器交叠接力：A/B 交替，各 3s，重叠 400ms
                    long w = 3000;
                    long win = 0;
                    boolean flip = false;
                    while (System.currentTimeMillis() - t0 < 12000 && !quit) {
                        Player p = make(win, win + w, 300);
                        startPlayer(p);
                        final Player prev = flip ? a : b;
                        if (prev != null) {
                            final Player f = prev;
                            new Thread(new Runnable() {
                                public void run() {
                                    sleep(400);
                                    try { f.stop(); } catch (Throwable ignored) {}
                                    closeQuiet(f);
                                }
                            }).start();
                        }
                        if (flip) b = p; else a = p;
                        flip = !flip;
                        win += w;
                        long until = t0 + win;
                        while (System.currentTimeMillis() < until && !quit) sleep(50);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            lastErr = "" + t;
        }
        closeQuiet(cur); cur = null;
        closeQuiet(nxt); nxt = null;
        closeQuiet(a); a = null;
        closeQuiet(b); b = null;
        status = "done " + chunks + " chunks";
        repaint();
    }

    private Player a, b;

    /** END_OF_MEDIA 接力：块长 chunkMs，预备下一块在窗口到期后立即建。 */
    private void relayChunks(long t0, int chunkMs, int noteMs) {
        long win = 0;
        while (System.currentTimeMillis() - t0 < 12000 && !quit) {
            Player p = make(win, win + chunkMs, noteMs);
            if (cur == null) {
                startPlayer(p);
            } else {
                pending = p;
                // 等 END 接力（最多块长 + 500ms 兜底强制滚）
                long limit = System.currentTimeMillis() + chunkMs + 500;
                while (cur != null && pending != null && System.currentTimeMillis() < limit && !quit) {
                    sleep(30);
                }
                if (pending != null) {   // END 没来，强制接力
                    Player f = pending;
                    pending = null;
                    closeQuiet(cur);
                    cur = null;
                    startPlayer(f);
                }
            }
            win += chunkMs;
            // 对齐墙钟：整体进度不应超前曲目时间太多
            long until = t0 + win - 200;
            while (System.currentTimeMillis() < until && !quit) sleep(30);
        }
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
        g.drawString(mode >= 0 ? MODES[mode] : "SmfProbe", 4, 4, Graphics.TOP | Graphics.LEFT);
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
        g.drawString("chunks: " + chunks, 4, y, Graphics.TOP | Graphics.LEFT); y += sm.getHeight();
        g.setColor(lastErr.length() > 0 ? 0xFF8A8A : 0x667788);
        g.drawString("err: " + (lastErr.length() > 0 ? lastErr : "-"), 4, y, Graphics.TOP | Graphics.LEFT);
        g.setColor(0x667788);
        g.drawString("1-6=mode 0=exit (auto cycles 12s each)", 4, h - sm.getHeight() - 2, Graphics.TOP | Graphics.LEFT);
    }

    protected void keyPressed(int k) {
        if (k >= KEY_NUM1 && k <= KEY_NUM6) {
            final int m = k - KEY_NUM1;
            new Thread(new Runnable() {
                public void run() { runMode(m); }
            }).start();
        } else if (k == KEY_NUM0) {
            quit = true;
            SmfProbeMIDlet.quit();
        }
    }
}
