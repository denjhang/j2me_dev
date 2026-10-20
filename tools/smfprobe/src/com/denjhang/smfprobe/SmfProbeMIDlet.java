package com.denjhang.smfprobe;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

public class SmfProbeMIDlet extends MIDlet {
    private static SmfProbeMIDlet inst;
    private SmfProbe probe;

    public SmfProbeMIDlet() { inst = this; }

    protected void startApp() {
        if (probe == null) probe = new SmfProbe();
        Display.getDisplay(this).setCurrent(probe);
    }

    protected void pauseApp() {}
    protected void destroyApp(boolean u) {}

    static void quit() {
        if (inst != null) inst.notifyDestroyed();
    }
}
