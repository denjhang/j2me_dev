package com.denjhang.soundprobe;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

public class SoundProbeMIDlet extends MIDlet {
    private static SoundProbeMIDlet inst;
    private SoundProbe probe;

    public SoundProbeMIDlet() {
        inst = this;
    }

    protected void startApp() {
        if (probe == null) {
            probe = new SoundProbe();
        }
        Display.getDisplay(this).setCurrent(probe);
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean u) {}

    static void quit() {
        if (inst != null) {
            inst.notifyDestroyed();
        }
    }
}
