package com.denjhang.vgmbrowser;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;

import com.denjhang.vgm.VgmFile;

/**
 * VGM 浏览器 —— 第一阶段只做解析与显示，不做任何音频转换。
 */
public class BrowserMIDlet extends MIDlet {

    private FileListScreen list;
    private PlayScreen play;

    public void startApp() {
        if (list == null) {
            list = new FileListScreen(this);
        }
        Display.getDisplay(this).setCurrent(list);
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

    void openPlay(VgmFile file, int listIndex) {
        releasePlay();
        play = new PlayScreen(this, file, listIndex);
        Display.getDisplay(this).setCurrent(play);
    }

    /** 释放上一个播放屏（切曲时必须关掉旧 MIDI Player，防资源泄漏）。 */
    void releasePlay() {
        if (play != null) {
            play.release();
            play = null;
        }
    }

    /** 播放器切曲（上一曲/下一曲），越界忽略。 */
    void switchTrack(int idx) {
        releasePlay();
        list.openByIndex(idx);
    }

    /** 播放列表条目数（随机播放用）。 */
    int trackCount() {
        return list.entryCount();
    }

    /** 后台播放是否存活。 */
    boolean playerAlive() {
        return play != null;
    }

    void refreshListNowPlaying() {
        list.refreshNowPlaying();
    }

    /** 回到正在播放的界面。 */
    void showPlayer() {
        if (play != null) {
            Display.getDisplay(this).setCurrent(play);
        }
    }
}
