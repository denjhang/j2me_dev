package com.denjhang.vgm;

import java.io.IOException;
import java.io.InputStream;

import javax.microedition.media.Manager;
import javax.microedition.media.Player;

/**
 * 实时 PCM 流式合成验证器。
 *
 * 合成线程产生 8bit/8kHz 单声道方波，写入环形缓冲；
 * 自定义 InputStream 从环形缓冲读出，包上 WAV 头喂给 MMAPI。
 * Player 以拉模式从流里取数据 —— 这就是"实时合成"的完整链路。
 *
 * 专门用来回答：这台机器的 MMAPI 是否支持无限流（不 prefetch 到 EOF）。
 * 关键指标：是否立即出声、underrun 次数、延迟。
 */
public class StreamPlayer implements Runnable {

    public static final int RATE = 8000;          // 采样率
    private static final int BUF_SIZE = 8192;     // 8KB 环形缓冲 ≈ 1 秒

    private final byte[] ring = new byte[BUF_SIZE];
    private int rp;                 // 读位置
    private int wp;                 // 写位置

    private Player player;
    private Thread synth;
    private volatile boolean running;
    private volatile boolean streamOpen;

    private volatile int freq = 440;       // Hz
    private volatile int volume = 60;      // 0-100
    private long phase = 0;                // 固定点相位累加器

    // 统计
    private long generated;
    private long consumed;
    private int underruns;

    private final SynthStream stream = new SynthStream();

    // ---------------------------------------------------------------- 生命周期

    /** 启动播放。返回 null 成功，否则返回错误信息。 */
    public String start() {
        if (running) {
            return null;
        }
        try {
            synchronized (this) {
                rp = wp = 0;
                phase = 0;
                generated = consumed = 0;
                underruns = 0;
            }
            streamOpen = true;
            running = true;

            // 头必须先于一切数据进入缓冲，Player 第一次 read 拿到的就是它
            primeHeader();

            player = Manager.createPlayer(stream, "audio/x-wav");
            player.realize();
            player.start();

            synth = new Thread(this);
            synth.start();
            return null;
        } catch (Throwable t) {
            stop();
            return t.toString();
        }
    }

    public void stop() {
        running = false;
        streamOpen = false;
        Thread t = synth;
        synth = null;
        if (t != null) {
            // CLDC 的 join() 没有超时版本，靠 running 标志自然退出
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
        if (player != null) {
            try {
                player.close();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        synchronized (this) {
            notifyAll();     // 唤醒可能阻塞在 read() 上的线程
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ---------------------------------------------------------------- 参数

    public void setFreq(int hz) {
        if (hz < 20) {
            hz = 20;
        }
        if (hz > 4000) {
            hz = 4000;
        }
        freq = hz;
    }

    public int getFreq() {
        return freq;
    }

    public void setVolume(int v) {
        if (v < 0) {
            v = 0;
        }
        if (v > 100) {
            v = 100;
        }
        volume = v;
    }

    public int getVolume() {
        return volume;
    }

    public synchronized int getBuffered() {
        return (wp - rp + BUF_SIZE) % BUF_SIZE;
    }

    public synchronized int getUnderruns() {
        return underruns;
    }

    public synchronized int getConsumedMs() {
        return (int) (consumed * 1000L / RATE);
    }

    public synchronized int getLatencyMs() {
        return getBuffered() * 1000 / RATE;
    }

    // ---------------------------------------------------------------- 合成线程

    public void run() {
        int localPhase = 0;
        while (running) {
            int free = freeSpace();
            if (free < 256) {
                // 缓冲快满，歇一会
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            // 一次最多产 256 字节（32ms），保证音量/音高变化尽快生效
            int n = Math.min(free, 256);
            byte[] chunk = new byte[n];
            int amp = volume * 60;                     // 0-6000 → 最大幅度约 ±47
            int step = freq * 65536 / RATE;
            for (int i = 0; i < n; i++) {
                localPhase += step;
                int s = (localPhase & 0x8000) != 0 ? amp : -amp;
                chunk[i] = (byte) (128 + (s >> 6));    // 8bit unsigned
            }
            synchronized (this) {
                writeRing(chunk, 0, n);
                generated += n;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private int freeSpace() {
        return (rp - wp - 1 + BUF_SIZE) % BUF_SIZE;
    }

    private void writeRing(byte[] src, int off, int len) {
        for (int i = 0; i < len; i++) {
            ring[wp] = src[off + i];
            wp = (wp + 1) % BUF_SIZE;
        }
    }

    // ---------------------------------------------------------------- 输入流

    private class SynthStream extends InputStream {

        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        public int read(byte[] b, int off, int len) throws IOException {
            if (!streamOpen) {
                return -1;
            }
            // 阻塞直到有数据（这就是"拉模式实时流"的核心）
            while (streamOpen && running) {
                synchronized (StreamPlayer.this) {
                    int avail = (wp - rp + BUF_SIZE) % BUF_SIZE;
                    if (avail > 0) {
                        int n = Math.min(len, avail);
                        for (int i = 0; i < n; i++) {
                            b[off + i] = ring[rp];
                            rp = (rp + 1) % BUF_SIZE;
                        }
                        consumed += n;
                        return n;
                    }
                    underruns++;
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    break;
                }
            }
            return streamOpen ? 0 : -1;
        }

        public int available() {
            // 故意返回 0：无限流不能报总长度。
            // 如果某实现依赖 available() 决定缓冲策略，这里就会暴露问题。
            return 0;
        }
    }

    // ---------------------------------------------------------------- WAV 头

    /**
     * 标准 44 字节 PCM WAV 头。数据长度填一个大值（约 9 小时），
     * 因为流没有确定的结尾。流的消费方解析完头之后就一直往后读。
     */
    public static byte[] wavHeader() {
        int dataSize = 0x0F000000;
        int riffSize = 36 + dataSize;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        put32(h, 4, riffSize);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        put32(h, 16, 16);
        put16(h, 20, 1);            // PCM
        put16(h, 22, 1);            // mono
        put32(h, 24, RATE);
        put32(h, 28, RATE);         // byte rate = rate * 1 * 1
        put16(h, 32, 1);            // block align
        put16(h, 34, 8);            // bits
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

    /**
     * 把 WAV 头写进流的最前面（由 start() 之后立即调用，
     * 在这里单独拿出来是为了让时序清晰：头先进缓冲，PCM 跟在后面）。
     */
    void primeHeader() {
        byte[] h = wavHeader();
        synchronized (this) {
            writeRing(h, 0, h.length);
        }
    }
}
