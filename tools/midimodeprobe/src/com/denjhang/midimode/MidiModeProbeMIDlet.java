package com.denjhang.midimode;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

public class MidiModeProbeMIDlet extends MIDlet {
    private static MidiModeProbeMIDlet inst;
    private MidiModeProbe probe;

    public MidiModeProbeMIDlet() { inst = this; }

    protected void startApp() {
        if (probe == null) probe = new MidiModeProbe();
        Display.getDisplay(this).setCurrent(probe);
    }

    protected void pauseApp() {}
    protected void destroyApp(boolean u) {}

    static void quit() {
        if (inst != null) inst.notifyDestroyed();
    }
}
