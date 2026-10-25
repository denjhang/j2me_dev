package com.denjhang.vgmbrowser;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

import com.denjhang.vgm.VgmFile;

/**
 * VgmBrowserTest —— 自动播放测试器。
 *
 * 启动即扫 file:///root/vgm/ 下的 .vgm/.vgz，顺序播放一遍；
 * 主线 PlayScreen 的 autoNext 在曲终时调用 midlet.switchTrack(idx)，
 * 接力到下一首；列表放完后回到第 0 首。
 *
 * 与 single 的唯一区别：启动后自动播第 0 首（不需要按 ▶），
 * 以及自动切曲（single 默认 loopIdx=1，需手动切）。
 * 播放逻辑本身完全复用 single v1.0.41。
 */
public class BrowserMIDlet extends MIDlet {

    private FileListScreen list;
    private PlayScreen play;
    private boolean started;

    public void startApp() {
        // 探针声明：启动事件，用于对齐 jmap 时机
        System.out.println("[TEST] startApp");
        if (!started) {
            started = true;
            list = new FileListScreen(this);
            Display.getDisplay(this).setCurrent(list);
            // 异步触发：等 list 把目录读进来再播第 0 首
            // FileListScreen 构造里已 tryLoadDir("file:///root/vgm/")
        }
    }

    public void pauseApp() {
    }

    public void destroyApp(boolean unconditional) {
        releasePlay();
        notifyDestroyed();
    }

    void openDetail(VgmFile file) {
        Display.getDisplay(this).setCurrent(new DetailScreen(this, file));
    }

    void backToList() {
        Display.getDisplay(this).setCurrent(list);
    }

    void openCommands(VgmFile file) {
        Display.getDisplay(this).setCurrent(new CommandScreen(this, file));
    }

    void openToneTest() {
        Display.getDisplay(this).setCurrent(new ToneTestScreen(this));
    }

    void openMidiTest() {
        Display.getDisplay(this).setCurrent(new MidiTestScreen(this));
    }

    void openPlayRaw(String name, byte[] rawBytes, int listIndex) {
        releasePlay();
        play = new PlayScreen(this, name, rawBytes, listIndex);
        Display.getDisplay(this).setCurrent(play);
    }

    void openPlay(VgmFile file, int listIndex) {
        releasePlay();
        play = new PlayScreen(this, file, listIndex);
        Display.getDisplay(this).setCurrent(play);
    }

    /** 释放上一个播放屏（切曲时必须关掉旧 MIDI Player，防资源泄漏）。 */
    void releasePlay() {
        if (play != null) {
            play.release();
            markerRelease();
            play = null;
        }
    }

    /** 播放器切曲（上一曲/下一曲），越界忽略。 */
    void switchTrack(int idx) {
        releasePlay();
        list.openByIndex(idx);
    }

    int trackCount() {
        return list.entryCount();
    }

    boolean playerAlive() {
        return play != null;
    }

    void refreshListNowPlaying() {
        list.refreshNowPlaying();
    }

    void showPlayer() {
        if (play != null) {
            Display.getDisplay(this).setCurrent(play);
        }
    }

    // ---- 探针：在屏幕切换的契约事件上打标记，便于 jmap 对齐 ----

    /** 由 PlayScreen.release() 末尾调用：标记一次旧实例释放完毕。 */
    void markerRelease() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        // 旧实例已被 release() 清空大块引用；此处的 used 是"释放后、GC 前"的瞬时值
        System.out.println("[TEST] release done, used=" + (used / 1024) + "K"
                + " total=" + (rt.totalMemory() / 1024) + "K");
    }

    /** 由 PlayScreen 构造末尾调用：标记一次新实例就绪。 */
    void markerNewPlayer(int listIndex, int total) {
        System.out.println("[TEST] new PlayScreen idx=" + listIndex + "/" + total);
    }

    /** 启动后由 FileListScreen 完成扫描时回调。 */
    void markerScanned(int total) {
        System.out.println("[TEST] scanned vgm files: " + total);
    }
}