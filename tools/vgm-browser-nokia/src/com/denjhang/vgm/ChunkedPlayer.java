package com.denjhang.vgm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

/**
 * 分块链播放器：把连续合成切成有限 WAV 块，播完一块立即接下一块。
 *
 * 为什么存在：KEmulator / J2ME Loader / FreeJ2ME 的 WAV 路径都要求
 * 已知长度全量缓冲（Clip / 临时文件），无限流必死（mark/reset 失败、
 * available() 预分配、prefetch 到 EOF）。有限块是这些环境下唯一能出声的
 * 实时合成方式 —— 也就是 S40v3 短块环形链策略的原型。
 *
 * 抗间隙手段：播放当前块的同时预渲染下一块，END_OF_MEDIA 时立即 start。
 * 统计 gap（块间实际延迟）用于评估该策略的可用性。
 */
public class ChunkedPlayer implements PlayerListener, Runnable {

    public static final int RATE = 8000;
    /** 可选块长（毫秒）：KEmulator 切换开销 ~1.4s，块越长开销占比越低。 */
    private static final int[] CHUNK_CHOICES = {500, 2000, 8000};
    private int chunkIdx = 0;

    private volatile boolean running;
    private Thread feed;

    private volatile int freq = 440;
    private volatile int volume = 60;

    private Player cur;
    private volatile boolean chunkEnded;
    private int phase;
    private long playedChunks;
    private long totalGapMs;
    private long maxGapMs;
    private long lastEndAt;
    private long lastEventDelayMs;   // END 事件从真实结束到我们收到的延迟
    private long lastStartCostMs;    // start() 调用本身耗时
    private String lastError;

    // ------------------------------------------------------------ 生命周期

    public String start() {
        if (running) {
            return null;
        }
        phase = 0;
        playedChunks = 0;
        totalGapMs = 0;
        maxGapMs = 0;
        lastEndAt = 0;
        lastError = null;
        running = true;
        feed = new Thread(this);
        feed.start();
        return null;
    }

    public void stop() {
        running = false;
        Thread t = feed;
        feed = null;
        if (t != null) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
        synchronized (this) {
            closeQuietly(cur);
            cur = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------ 参数

    public void setFreq(int hz) {
        if (hz < 20) hz = 20;
        if (hz > 4000) hz = 4000;
        freq = hz;
    }

    public int getFreq() {
        return freq;
    }

    public void setVolume(int v) {
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        volume = v;
    }

    public int getVolume() {
        return volume;
    }

    public int getChunkMs() {
        return CHUNK_CHOICES[chunkIdx];
    }

    public void cycleChunk() {
        chunkIdx = (chunkIdx + 1) % CHUNK_CHOICES.length;
        // 块长变化只影响后续块，相位连续性不受影响
    }

    public long getPlayedChunks() {
        return playedChunks;
    }

    public long getMaxGapMs() {
        return maxGapMs;
    }

    public long getAvgGapMs() {
        return playedChunks > 1 ? totalGapMs / (playedChunks - 1) : 0;
    }

    public String getLastError() {
        return lastError;
    }

    public long getEventDelayMs() {
        return lastEventDelayMs;
    }

    public long getStartCostMs() {
        return lastStartCostMs;
    }

    public String getChunkLabel() {
        return CHUNK_CHOICES[chunkIdx] >= 1000
                ? (CHUNK_CHOICES[chunkIdx] / 1000) + "s" : "0.5s";
    }

    // ------------------------------------------------------------ 主循环

    public void run() {
        // 流水线：cur 在播，next 已 realize 待命。
        // END_OF_MEDIA 一到立刻 start(next)，间隙只剩一次 start() 的开销；
        // 然后趁 next 在播，再创建"下下块"的 Player。
        Player next;
        synchronized (this) {
            cur = buildPlayer(renderChunk());
            next = buildPlayer(renderChunk());
        }
        if (cur == null || next == null) {
            running = false;
            return;
        }
        try {
            cur.start();
            playedChunks++;
        } catch (Throwable t) {
            lastError = t.toString();
            running = false;
            return;
        }

        while (running) {
            // 等 cur 播完（2s 兜底：实现不发事件时也能推进，gap 会暴露在统计里）
            chunkEnded = false;
            long deadline = System.currentTimeMillis() + 2000;
            while (running && !chunkEnded && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(3);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (!running) {
                break;
            }
            long now = System.currentTimeMillis();
            if (chunkEnded) {
                lastEventDelayMs = now - lastEndAt;
            } else {
                lastEventDelayMs = -1;   // 走了 2s 兜底，说明事件没来
            }
            if (lastEndAt > 0) {
                long gap = now - lastEndAt;
                totalGapMs += gap;
                if (gap > maxGapMs) {
                    maxGapMs = gap;
                }
            }

            // 无缝切换：只做一次 start()
            synchronized (this) {
                closeQuietly(cur);
                cur = next;
            }
            long t0 = System.currentTimeMillis();
            try {
                cur.start();
                playedChunks++;
            } catch (Throwable t) {
                lastError = t.toString();
                break;
            }
            lastStartCostMs = System.currentTimeMillis() - t0;

            // 趁这块在播，创建再下一块的 Player
            Player following = buildPlayer(renderChunk());
            if (following == null) {
                break;
            }
            synchronized (this) {
                next = following;
            }
        }
        running = false;
    }

    /** createPlayer + realize + 挂 listener，不 start。失败返回 null。 */
    private Player buildPlayer(byte[] wav) {
        try {
            Player p = Manager.createPlayer(new ByteArrayInputStream(wav), "audio/x-wav");
            p.realize();
            p.addPlayerListener(this);
            return p;
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    public void playerUpdate(Player player, String event, Object eventData) {
        if (PlayerListener.END_OF_MEDIA.equals(event)) {
            lastEndAt = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------ 合成

    /** 渲染一块完整 WAV（含头），相位跨块连续。 */
    private byte[] renderChunk() {
        int CHUNK_SAMPLES = RATE * CHUNK_CHOICES[chunkIdx] / 1000;
        ByteArrayOutputStream out = new ByteArrayOutputStream(CHUNK_SAMPLES + 44);
        byte[] header = chunkHeader(CHUNK_SAMPLES);
        out.write(header, 0, header.length);

        int amp = volume * 60;
        int step = freq * 65536 / RATE;
        for (int i = 0; i < CHUNK_SAMPLES; i++) {
            phase += step;
            int s = (phase & 0x8000) != 0 ? amp : -amp;
            out.write(128 + (s >> 6));
        }
        return out.toByteArray();
    }

    /**
     * 块专用 WAV 头：数据长度必须写真实值。
     * 不能复用 StreamPlayer 的头——那边为无限流填了 9 小时的大值，
     * KEmulator 的 Clip 会按声明长度分配缓冲，直接 "Audio data is too big"。
     */
    private static byte[] chunkHeader(int CHUNK_SAMPLES) {
        int dataSize = CHUNK_SAMPLES;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        put32(h, 4, 36 + dataSize);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        put32(h, 16, 16);
        put16(h, 20, 1);
        put16(h, 22, 1);
        put32(h, 24, RATE);
        put32(h, 28, RATE);
        put16(h, 32, 1);
        put16(h, 34, 8);
        put(h, 36, "data");
        put32(h, 40, dataSize);
        return h;
    }

    private static void put(byte[] d, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            d[off + i] = (byte) s.charAt(i);
        }
    }

    private static void put32(byte[] d, int off, int v) {
        d[off] = (byte) v;
        d[off + 1] = (byte) (v >> 8);
        d[off + 2] = (byte) (v >> 16);
        d[off + 3] = (byte) (v >> 24);
    }

    private static void put16(byte[] d, int off, int v) {
        d[off] = (byte) v;
        d[off + 1] = (byte) (v >> 8);
    }

    private static void closeQuietly(Player p) {
        if (p != null) {
            // close() 会顺带解除 listener，无需单独 remove
            try {
                p.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
