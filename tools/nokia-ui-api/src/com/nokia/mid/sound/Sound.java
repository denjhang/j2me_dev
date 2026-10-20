package com.nokia.mid.sound;

/** 编译期 stub：真机由固件提供（Nokia UI API 1.0）。不打包进 jar。 */
public class Sound {
    public static final int SOUND_FORMAT_TONE = 1;
    public static final int SOUND_FORMAT_WAV = 5;
    public static final int SOUND_FORMAT_TONESEQ = 8;

    public Sound(byte[] data, int type) {}
    public Sound(String name) {}
    public void init() {}
    public void play(int loop) {}
    public void stop() {}
    public void resume() {}
    public void setGain(int gain) {}
    public int getGain() { return 255; }
    public void release() {}
    public static void playSound(byte[] data, int type) {}
}
