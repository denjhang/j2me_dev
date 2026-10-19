package com.denjhang.brick;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;

/**
 * 打砖块 —— 最小可玩的 J2ME MIDlet。
 * 只依赖 MIDP 2.0 + CLDC 1.1 + MMAPI(MIDI)，不碰任何可选包。
 */
public class BrickMIDlet extends MIDlet {

    private MidiAudio audio;
    private BrickCanvas canvas;

    public void startApp() {
        if (audio == null) {
            audio = new MidiAudio();
        }
        if (canvas == null) {
            canvas = new BrickCanvas(this, audio);
        }
        Display.getDisplay(this).setCurrent(canvas);
        canvas.startGameLoop();
    }

    public void pauseApp() {
        if (canvas != null) {
            canvas.pauseGameLoop();
        }
    }

    public void destroyApp(boolean unconditional) {
        if (canvas != null) {
            canvas.stopGameLoop();
        }
        if (audio != null) {
            audio.close();
        }
        notifyDestroyed();
    }

    void exitApp() {
        destroyApp(true);
    }
}
