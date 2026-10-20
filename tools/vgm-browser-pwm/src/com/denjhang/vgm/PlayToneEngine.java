package com.denjhang.vgm;

import javax.microedition.media.Manager;

/**
 * 诺基亚引擎：Manager.playTone 多声部轮询调度器。
 * 依据：诺基亚原厂/第三方应用（City Bloxx、JPianinho）在真机与 S40 模拟器上
 * 均能出声的路径只有 playTone 与 audio/midi 文件播放；device://midi 是死通道。
 *
 * 每个方波声部一个线程，用 playTone(note, ms, vol) 阻塞播放当前"愿望"音符；
 * 音符不变就继续续奏（长音靠分段续接），变了就等当前段结束再换。
 */
public class PlayToneEngine {
    private final int[] wantNote;
    private final int[] wantVol;
    private final boolean[] active = new boolean[3];
    private Thread[] threads = new Thread[3];
    private volatile boolean quit;
    private static final int SLICE_MS = 180;   // 每段时长：兼顾换音延迟与调用开销

    public PlayToneEngine(int[] wantNoteRef, int[] wantVolRef) {
        this.wantNote = wantNoteRef;
        this.wantVol = wantVolRef;
    }

    public void start() {
        quit = false;
        for (int ch = 0; ch < 3; ch++) {
            final int c = ch;
            active[ch] = true;
            threads[ch] = new Thread(new Runnable() {
                public void run() {
                    loop(c);
                }
            });
            threads[ch].setPriority(Thread.NORM_PRIORITY - 1);
            threads[ch].start();
        }
    }

    private void loop(int ch) {
        int lastNote = -2;
        int lastVol = -1;
        while (!quit && active[ch]) {
            int n = wantNote[ch];
            if (n < 0 || wantNote[ch] < 0) {
                // 静音：短暂休眠，等新音符
                try { Thread.sleep(SLICE_MS); } catch (InterruptedException e) { return; }
                lastNote = -2;
                continue;
            }
            if (n > 126) n = 126;
            if (n < 0) n = 0;
            int v = wantVol != null ? wantVol[ch] : 100;
            if (v < 1) v = 1;
            if (v > 100) v = 100;
            int dur = SLICE_MS;
            // 音高跨度大时缩短音段，减小换音延迟感知
            if (lastNote >= 0 && Math.abs(n - lastNote) > 2) dur = 90;
            lastNote = n;
            lastVol = v;
            try {
                Manager.playTone(n, dur, v);
            } catch (Throwable t) {
                try { Thread.sleep(SLICE_MS); } catch (InterruptedException e) { return; }
            }
            if (quit) {
                break;
            }
        }
    }

    public void stop() {
        quit = true;
        for (int i = 0; i < 3; i++) {
            active[i] = false;
            if (threads[i] != null) {
                try { threads[i].join(); } catch (InterruptedException ignored) {}
                threads[i] = null;
            }
        }
    }
}
