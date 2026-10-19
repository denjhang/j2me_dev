package com.denjhang.vgmbrowser;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import com.denjhang.vgm.Ay2Midi;
import com.denjhang.vgm.VgmCommandTable;
import com.denjhang.vgm.VgmFile;
import com.denjhang.vgm.VgmParser;

/**
 * VGM → MIDI 实时播放器 HUD。
 *
 * 打开即自动播放。键位：
 *   4 / 6        上一曲 / 下一曲（播放列表）
 *   5            暂停 / 继续
 *   0            停止
 *   ↑ / ↓        移动设置光标
 *   ← / →        修改光标所在项（声像 / 八度 / 各通道音色）
 *   返回         停止并退出
 */
public class PlayScreen extends Canvas implements CommandListener, Runnable {

    private static final String[] CH_NAMES = {
        "方波A", "方波B", "方波C",
        "噪声A", "噪声B", "噪声C",
        "包络-锯齿", "包络-衰减", "包络-三角", "包络-交替"
    };
    /** 可选音色（常用 GM 值）。 */
    private static final int[] PROG_CHOICES = {
        80, 81, 83, 87, 38, 48, 33, 56, 73, 24, 19, 11, 4, 0, 52, 127
    };

    private final BrowserMIDlet midlet;
    private final VgmFile file;
    private final Ay2Midi ay;
    /** 第二片 PSG（双 AY 曲目），遇到 0xA0 寄存器 bit7 时惰性创建。 */
    private Ay2Midi ay2;
    private final VgmParser parser;
    private final int listIndex;

    private final Command cmdBack = new Command("返回", Command.BACK, 1);

    private volatile boolean playing;
    private volatile boolean uiAlive = true;
    private volatile boolean loop = true;
    private Thread play;
    private String err;

    private int cursor;          // HUD 行光标
    private static final int ROW_STEREO = 0;
    private static final int ROW_OCT = 1;
    private static final int ROW_NOISEMAP = 2;
    private static final int ROW_CH0 = 3;

    private final Font small;
    private final Font big;

    public PlayScreen(BrowserMIDlet midlet, VgmFile file, int listIndex) {
        this.midlet = midlet;
        this.file = file;
        this.listIndex = listIndex;
        this.parser = file.getParser();
        this.ay = new Ay2Midi();
        setFullScreenMode(true);
        addCommand(cmdBack);
        setCommandListener(this);
        small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        big = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        // VGM 时钟字段高两位是标志（bit31=双芯片，bit30=YM2149），取低 30 位
        if (file.header.chipClock[0x12] != 0) {
            ay.setClock(file.header.chipClock[0x12] & 0x3FFFFFFF);
        }
        if (file.header.ayClock2 != 0) {
            ay2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);
            ay2.setClock(file.header.ayClock2 & 0x3FFFFFFF);
            if (ay2.ready) {
                ay2.copyConfigFrom(ay);
            } else {
                ay2 = null;
            }
        }
        if (!ay.ready) {
            err = ay.initError;
        } else {
            ay.forceRefreshAll();
        }
        startPlay(true);
        Thread ui = new Thread(this);
        ui.start();
    }

    public void run() {
        while (uiAlive) {
            repaint();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ------------------------------------------------------------ 播放控制

    private void startPlay(boolean reset) {
        if (playing || !ay.ready) {
            return;
        }
        if (reset) {
            parser.reset();
            ay.allSoundOff();
        }
        playing = true;
        play = new Thread(new Runnable() {
            public void run() {
                playLoop();
            }
        });
        play.start();
    }

    private void pausePlay() {
        playing = false;
        ay.allSoundOff();
        if (ay2 != null) {
            ay2.allSoundOff();
        }
    }

    private void stopPlay() {
        playing = false;
        ay.allSoundOff();
        if (ay2 != null) {
            ay2.allSoundOff();
        }
    }

    private void playLoop() {
        long startAt = System.currentTimeMillis();
        long lastSamples = 0;

        while (playing) {
            ay.tick();
            if (ay2 != null) {
                ay2.tick();
            }
            int t = parser.next();

            if (t == VgmParser.EV_END) {
                if (loop && file.header.hasLoop()) {
                    parser.seek(file.header.loopOffset);
                    continue;
                }
                break;
            }

            if (t == VgmParser.EV_REG_WRITE && parser.evCmd == 0xA0) {
                if ((parser.evReg & 0x80) != 0) {
                    // 第二片 PSG：寄存器 bit7 标志
                    if (ay2 == null) {
                        ay2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);
                        ay2.setClock(file.header.chipClock[0x12] & 0x3FFFFFFF);
                        if (ay2.ready) {
                            ay2.copyConfigFrom(ay);
                        } else {
                            ay2 = null;
                        }
                    }
                    if (ay2 != null) {
                        ay2.write(parser.evReg & 0x7F, parser.evData);
                    }
                } else {
                    ay.write(parser.evReg, parser.evData);
                }
            }

            long nowSamples = parser.getSamples();
            if (nowSamples != lastSamples) {
                long songMs = nowSamples / 44;
                long wallMs = System.currentTimeMillis() - startAt;
                long ahead = songMs - wallMs;
                if (ahead > 2) {
                    try {
                        Thread.sleep(ahead);
                    } catch (InterruptedException e) {
                        break;
                    }
                } else if (ahead < -200) {
                    startAt = System.currentTimeMillis() - songMs;
                }
                lastSamples = nowSamples;
            }
        }
        if (playing) {
            playing = false;
            ay.allSoundOff();
        }
    }

    // ------------------------------------------------------------ 绘制

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(0x101820);
        g.fillRect(0, 0, w, h);

        String title = file.header.gd3Title;
        if (title.length() == 0) title = file.header.gd3TitleEn;
        if (title.length() == 0) title = file.name;
        g.setFont(big);
        g.setColor(0xE0E0E0);
        g.drawString(clip(title, 20), 4, 4, Graphics.TOP | Graphics.LEFT);

        g.setFont(small);
        int y = 30;
        g.setColor(playing ? 0x7CFC7C : 0xA0A0A0);
        String st = playing ? "▶" : "‖";
        g.drawString(st + " " + (parser.getSamples() / 44100) + "s / "
                + (int) file.header.durationSeconds() + "s"
                + (loop && file.header.hasLoop() ? "  ⟳" : ""), 4, y,
                Graphics.TOP | Graphics.LEFT);
        y += 18;
        g.setColor(0x808080);
        g.drawString(ay.statusLine(), 4, y, Graphics.TOP | Graphics.LEFT);
        y += 22;

        // 设置区
        g.setColor(0xE0E0E0);
        drawRow(g, cursor == 0, y, "声像",
                Ay2Midi.STEREO_MODES[ay.getStereoMode()]);
        y += 18;
        drawRow(g, cursor == ROW_OCT, y, "八度",
                (ay.getOct() >= 0 ? "+" : "") + ay.getOct());
        y += 18;
        drawRow(g, cursor == ROW_NOISEMAP, y, "噪声映射",
                Ay2Midi.NOISE_MAP_NAMES[ay.getNoiseMapMode()]);
        y += 18;
        for (int i = 0; i < Ay2Midi.SLOTS; i++) {
            drawRow(g, cursor == ROW_CH0 + i, y, CH_NAMES[i],
                    String.valueOf(ay.getProg(i)));
            y += 16;
        }

        if (err != null) {
            y += 4;
            g.setColor(0xFF7C7C);
            g.drawString(clip("错误: " + err, 24), 4, y, Graphics.TOP | Graphics.LEFT);
        }

        g.setColor(0x606870);
        g.drawString("4◀曲目 曲目▶6 5暂停 0停 ←→调 ↑↓选", 4, h - 34,
                Graphics.TOP | Graphics.LEFT);
        g.drawString("返回=退出", 4, h - 20, Graphics.TOP | Graphics.LEFT);
    }

    private void drawRow(Graphics g, boolean selected, int y, String name, String val) {
        g.setColor(selected ? 0x64B4FF : 0xE0E0E0);
        g.drawString((selected ? ">" : " ") + name, 4, y, Graphics.TOP | Graphics.LEFT);
        g.drawString(val, getWidth() - 8, y, Graphics.TOP | Graphics.RIGHT);
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    // ------------------------------------------------------------ 输入

    private void adjust(int dir) {
        if (cursor == ROW_STEREO) {
            ay.setStereoMode(ay.getStereoMode() + dir);
            if (ay2 != null) {
                ay2.setStereoMode(ay2.getStereoMode() + dir);
            }
        } else if (cursor == ROW_OCT) {
            ay.setOct(ay.getOct() + dir);
            if (ay2 != null) {
                ay2.setOct(ay2.getOct() + dir);
            }
        } else if (cursor == ROW_NOISEMAP) {
            ay.setNoiseMapMode(ay.getNoiseMapMode() + dir);
            if (ay2 != null) {
                ay2.setNoiseMapMode(ay2.getNoiseMapMode() + dir);
            }
        } else {
            int ch = cursor - ROW_CH0;
            int cur = ay.getProg(ch);
            int idx = findProg(cur);
            int next = idx;
            for (int step = 0; step < PROG_CHOICES.length; step++) {
                next += dir;
                if (next < 0) next = PROG_CHOICES.length - 1;
                if (next >= PROG_CHOICES.length) next = 0;
                if (PROG_CHOICES[next] != cur) break;
            }
            ay.setProg(ch, PROG_CHOICES[next]);
            if (ay2 != null) {
                ay2.setProg(ch, PROG_CHOICES[next]);
            }
        }
    }

    private static int findProg(int prog) {
        for (int i = 0; i < PROG_CHOICES.length; i++) {
            if (PROG_CHOICES[i] == prog) {
                return i;
            }
        }
        return 0;
    }

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);
        if (keyCode == KEY_NUM4) {
            midlet.switchTrack(listIndex - 1);
        } else if (keyCode == KEY_NUM6) {
            midlet.switchTrack(listIndex + 1);
        } else if (keyCode == KEY_NUM5) {
            if (playing) {
                pausePlay();
            } else {
                startPlay(false);
            }
        } else if (keyCode == KEY_NUM0) {
            stopPlay();
        } else if (action == UP) {
            cursor--;
            if (cursor < 0) cursor = ROW_CH0 + Ay2Midi.SLOTS - 1;
        } else if (action == DOWN) {
            cursor++;
            if (cursor > ROW_CH0 + Ay2Midi.SLOTS - 1) cursor = 0;
        } else if (action == LEFT) {
            adjust(-1);
        } else if (action == RIGHT) {
            adjust(1);
        }
        repaint();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdBack) {
            uiAlive = false;
            playing = false;
            ay.close();
            if (ay2 != null) {
                ay2.close();
            }
            midlet.backToList();
        }
    }
}
