package com.denjhang.vgmbrowser;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;

import com.denjhang.vgm.VgmFile;

/**
 * VGM 浏览器 —— 第一阶段只做解析与显示，不做任何音频转换。
 */
public class BrowserMIDlet extends MIDlet {

    private FileListScreen list;

    public void startApp() {
        if (list == null) {
            list = new FileListScreen(this);
        }
        Display.getDisplay(this).setCurrent(list);
    }

    public void pauseApp() {
    }

    public void destroyApp(boolean unconditional) {
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
        Display.getDisplay(this).setCurrent(new PlayScreen(this, file, listIndex));
    }

    /** 播放器切曲（上一曲/下一曲），越界忽略。 */
    void switchTrack(int idx) {
        list.openByIndex(idx);
    }
}
