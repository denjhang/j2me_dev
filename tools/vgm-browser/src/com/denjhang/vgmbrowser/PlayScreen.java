package com.denjhang.vgmbrowser;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import com.denjhang.vgm.Ay2Midi;
import com.denjhang.vgm.VgmFile;
import com.denjhang.vgm.VgmParser;

/**
 * 播放器 HUD。
 *
 * 普通模式：◀▶ 切曲（顺序/随机）  ▲▼ 主音量  ●/5 播放暂停  # 进设置
 * 设置模式：▲▼ 移动光标  ◀▶ 调整  # 返回
 *
 * 后台播放：返回列表不停播；打开新曲时由 MIDlet 释放旧实例（单实例）。
 */
public class PlayScreen extends Canvas implements CommandListener, Runnable {

    private static final String[] CH_NAMES = {
        "方波A", "方波B", "方波C",
        "噪声A", "噪声B", "噪声C",
        "包络-锯齿", "包络-衰减", "包络-三角", "包络-交替"
    };
    private static final int[] PROG_CHOICES = {
        80, 81, 83, 87, 38, 48, 33, 56, 73, 24, 19, 11, 4, 0, 52, 127
    };
    private static final String[] UNI_NAMES = {"关", "上行1八度", "上行2八度"};
    private static final int[] LOOP_CHOICES = {1, 2, 3, 5, 99};
    private static final String[] LOOP_NAMES = {"1", "2", "3", "5", "无限"};
    private static final String[] ORDER_NAMES = {"顺序", "随机"};

    // v16 配色（高饱和版）：ChA 绿 / ChB 蓝 / ChC 红；二芯片 紫/黄/青
    private static final int COL_A = 0x4CD84C;
    private static final int COL_B = 0x4C6CF0;
    private static final int COL_C = 0xF04C4C;
    private static final int COL_A2 = 0xC84CE0;
    private static final int COL_B2 = 0xE8E01C;
    private static final int COL_C2 = 0x18CCE0;
    private static final int[] CH_COLORS = {COL_A, COL_B, COL_C};
    private static final int[] CH2_COLORS = {COL_A2, COL_B2, COL_C2};
    // 噪声 = 石板灰 / 玫瑰；包络 = 橙 / 琥珀（v16 语义，高对比）
    private static final int COL_NOISE = 0x5A6478;
    private static final int COL_NOISE2 = 0xB84868;
    private static final int COL_ENV = 0xFF8C1A;
    private static final int COL_ENV2 = 0xD06808;

    private static final int MODE_HUD = 0;
    private static final int MODE_SETTINGS = 1;
    private int mode = MODE_HUD;

    private static final int ROW_STEREO = 0;
    private static final int ROW_OCT = 1;
    private static final int ROW_NOISEMAP = 2;
    private static final int ROW_ENVSAW_OCT = 3;
    private static final int ROW_ENVTRI_OCT = 4;
    private static final int ROW_ENVSAW_UNI = 5;
    private static final int ROW_ENVTRI_UNI = 6;
    private static final int ROW_BUZZ_VOL = 7;
    private static final int ROW_LOOP = 8;
    private static final int ROW_ORDER = 9;
    private static final int ROW_CH0 = 10;
    private static final int TOTAL_ROWS = 10 + Ay2Midi.SLOTS;
    private static final int ROW_H = 16;
    private int cursor;
    private int scroll;

    private final BrowserMIDlet midlet;
    private final VgmFile file;
    final Ay2Midi ay;
    private Ay2Midi ay2;
    private final VgmParser parser;
    private final int listIndex;

    private final Command cmdSettings = new Command("设置", Command.SCREEN, 1);
    private final Command cmdBack = new Command("列表", Command.BACK, 2);

    private volatile boolean playing;
    private volatile boolean uiAlive = true;
    private Thread play;
    private String err;

    private int loopIdx = 1;          // 默认循环 2 次
    private int loopPlays;
    private boolean shuffle;
    private int masterVol = 100;

    private final java.util.Random rng = new java.util.Random();
    private final Font small;
    private final Font titleFont;
    private final Font tiny;

    public PlayScreen(BrowserMIDlet midlet, VgmFile file, int listIndex) {
        this.midlet = midlet;
        this.file = file;
        this.listIndex = listIndex;
        this.parser = file.getParser();
        this.ay = new Ay2Midi();
        setFullScreenMode(true);
        addCommand(cmdSettings);
        addCommand(cmdBack);
        setCommandListener(this);
        small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        titleFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        tiny = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        if (file.header.chipClock[0x12] != 0) {
            ay.setClock(file.header.chipClock[0x12] & 0x3FFFFFFF);
        }
        if (!ay.ready) {
            err = ay.initError;
        } else {
            ay.forceRefreshAll();
            ay.setMasterVol(masterVol);
        }
        if (file.header.ayClock2 != 0) {
            attachChip2();
        }
        startPlay(true);
        Thread ui = new Thread(this);
        ui.start();
    }

    private void attachChip2() {
        if (ay2 != null) {
            return;
        }
        ay2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);
        ay2.setClock(file.header.chipClock[0x12] & 0x3FFFFFFF);
        if (ay2.ready) {
            ay2.copyConfigFrom(ay);
            ay2.setMasterVol(masterVol);
        } else {
            ay2 = null;
        }
    }

    /** 由 MIDlet 释放资源（切曲/退出）。返回列表不调用——后台继续播。 */
    public void release() {
        uiAlive = false;
        playing = false;
        ay.close();
        if (ay2 != null) {
            ay2.close();
        }
    }

    public void run() {
        while (uiAlive) {
            if (isShown()) {
                repaint();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ------------------------------------------------------------ 播放

    private void startPlay(boolean reset) {
        if (playing || !ay.ready) {
            return;
        }
        if (reset) {
            parser.reset();
            loopPlays = 0;
            ay.allSoundOff();
            if (ay2 != null) {
                ay2.allSoundOff();
            }
        }
        playing = true;
        play = new Thread(new Runnable() {
            public void run() {
                playLoop();
            }
        });
        play.start();
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
                if (file.header.hasLoop() && loopPlays < LOOP_CHOICES[loopIdx]) {
                    parser.seek(file.header.loopOffset);
                    loopPlays++;
                    continue;
                }
                break;
            }

            if (t == VgmParser.EV_REG_WRITE && parser.evCmd == 0xA0) {
                if ((parser.evReg & 0x80) != 0) {
                    attachChip2();
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
            if (ay2 != null) {
                ay2.allSoundOff();
            }
            autoNext();
        }
    }

    /** 切曲：先淡出再切。 */
    private void switchTrackFade(final int target) {
        final int from = masterVol;
        new Thread(new Runnable() {
            public void run() {
                for (int i = 10; i >= 0; i--) {
                    int v = from * i / 10;
                    ay.setMasterVol(v);
                    if (ay2 != null) {
                        ay2.setMasterVol(v);
                    }
                    try {
                        Thread.sleep(24);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                playing = false;
                midlet.switchTrack(target);
            }
        }).start();
    }

    private void autoNext() {
        int next = nextIndex();
        if (next >= 0) {
            switchTrackFade(next);
        }
    }

    private int nextIndex() {
        int n = midlet.trackCount();
        if (n <= 1) {
            return -1;
        }
        if (shuffle) {
            int r = listIndex;
            while (r == listIndex) {
                int v = rng.nextInt();
                if (v < 0) {
                    v = -v;
                }
                r = v % n;
            }
            return r;
        }
        int r = listIndex + 1;
        return r < n ? r : 0;
    }

    private int prevIndex() {
        int n = midlet.trackCount();
        if (n <= 1) {
            return -1;
        }
        if (shuffle) {
            return nextIndex();
        }
        int r = listIndex - 1;
        return r >= 0 ? r : n - 1;
    }

    // ------------------------------------------------------------ HUD 绘制

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(0x0C1118);
        g.fillRect(0, 0, w, h);

        if (mode == MODE_SETTINGS) {
            paintSettings(g, w, h);
            return;
        }
        paintHud(g, w, h);
    }

    private void paintHud(Graphics g, int w, int h) {
        // GD3 标签
        String title = pick(file.header.gd3Title, file.header.gd3TitleEn);
        if (title.length() == 0) {
            title = file.name;
        }
        g.setFont(titleFont);
        g.setColor(0xF0EDE4);
        g.drawString(clip(title, 22), 6, 4, Graphics.TOP | Graphics.LEFT);

        g.setFont(tiny);
        int y = 26;
        g.setColor(0x8894A8);
        String game = pick(file.header.gd3Game, file.header.gd3GameEn);
        String artist = pick(file.header.gd3Artist, file.header.gd3ArtistEn);
        if (game.length() > 0) {
            g.drawString(clip(game, 30), 6, y, Graphics.TOP | Graphics.LEFT);
            y += 13;
        }
        if (artist.length() > 0) {
            g.drawString(clip("by " + artist, 30), 6, y, Graphics.TOP | Graphics.LEFT);
            y += 13;
        }
        if (file.header.gd3Date.length() > 0) {
            g.drawString(clip(file.header.gd3Date, 34), 6, y, Graphics.TOP | Graphics.LEFT);
            y += 13;
        }
        if (file.header.gd3Notes.length() > 0) {
            g.drawString(clip(file.header.gd3Notes, 36), 6, y, Graphics.TOP | Graphics.LEFT);
            y += 13;
        }

        // 进度条
        y += 6;
        int barW = w - 40;
        int barY = y;
        int barH = 10;
        long total = file.header.totalSamples;
        long cur = parser.getSamples();
        int filled = total > 0 ? (int) (barW * cur / total) : 0;
        if (filled > barW) {
            filled = barW;
        }
        g.setColor(0x1B2430);
        g.fillRoundRect(6, barY, barW, barH, barH, barH);
        if (filled > 2) {
            g.setColor(0x3E7BA8);
            g.fillRoundRect(6, barY, filled, barH, barH, barH);
            g.setColor(0x6FB1DE);
            g.fillRoundRect(6, barY, filled, barH / 2, barH, barH);
        }
        g.setColor(0xE8E8E8);
        g.fillArc(6 + filled - 3, barY - 2, barH + 4, barH + 4, 0, 360);
        g.setFont(tiny);
        g.setColor(0x8894A8);
        g.drawString(fmt(cur / 44100), 6, barY + 12, Graphics.TOP | Graphics.LEFT);
        g.drawString(fmt(total / 44100), 6 + barW, barY + 12, Graphics.TOP | Graphics.RIGHT);

        // 状态行
        int sy = barY + 27;
        g.setColor(playing ? 0x7CFC7C : 0x707A88);
        g.setFont(small);
        StringBuffer st = new StringBuffer();
        st.append(playing ? "▶" : "‖");
        if (file.header.hasLoop()) {
            st.append(" ⟳").append(loopPlays).append('/').append(LOOP_NAMES[loopIdx]);
        }
        st.append(shuffle ? " 随机" : " 曲目 ").append(listIndex + 1);
        st.append("  音量").append(masterVol);
        g.drawString(st.toString(), 6, sy, Graphics.TOP | Graphics.LEFT);

        // 三条像素风 88 键横条
        int py = sy + 16;
        for (int ch = 0; ch < 3; ch++) {
            drawKeyStrip(g, 4, py + ch * 17, w - 8, 15, ch);
        }

        g.setColor(0x4A5568);
        g.setFont(tiny);
        g.drawString("◀▶曲目 ▲▼音量 ●播放暂停 #设置", 6, h - 18,
                Graphics.TOP | Graphics.LEFT);

        if (err != null) {
            g.setColor(0xFF7C7C);
            g.drawString(clip(err, 26), 6, h - 32, Graphics.TOP | Graphics.LEFT);
        }
    }

    // ---------------- 像素风 88 键横条 ----------------

    private static final int FIRST_NOTE = 21;    // A0
    private static final int LAST_NOTE = 108;    // C8
    private static final int WHITE_COUNT = 52;
    private static final boolean[] BLACK_PC = {
        false, true, false, true, false, false,
        true, false, true, false, true, false
    };
    /** note → 白键序号（0..51），黑键为 -1。 */
    private static final int[] WHITE_IDX = new int[128];
    static {
        int wi = 0;
        for (int n = FIRST_NOTE; n <= LAST_NOTE; n++) {
            if (BLACK_PC[n % 12]) {
                WHITE_IDX[n] = -1;
            } else {
                WHITE_IDX[n] = wi++;
            }
        }
    }

    /**
     * 单声道 88 键横条：52 白键满宽（约 4px/键），黑键为顶部短凹槽。
     * 活动键点通道色（电平调亮度）；双芯片同键左右各半。
     */
    private void drawKeyStrip(Graphics g, int x, int y, int w, int h, int ch) {
        int note1 = ay.visNote(ch);
        int note2 = ay2 != null ? ay2.visNote(ch) : -1;

        // 左缘通道色标（4px）
        g.setColor(CH_COLORS[ch]);
        g.fillRect(x, y, 3, h);
        int kx = x + 5;
        int kw = w - 5 - 22;      // 右侧 3 字符位（含字距）
        // 浮点步进：52 键精确铺满可用宽度，无最小值回退（避免右侧空白）
        float step = (float) kw / WHITE_COUNT;
        int keyW = (int) step;    // 绘制宽度（留 1px 缝）
        if (keyW < 1) {
            keyW = 1;
        }
        int bh = h * 5 / 8;       // 黑键凹槽高度
        int bw = keyW / 2;
        if (bw < 2) {
            bw = 2;
        }

        // 白键底色：绘制宽 = 到下一键起点的距离 -1，保证每条缝严格 1px
        for (int i = 0; i < WHITE_COUNT; i++) {
            int sx = kx + (int) (i * step);
            int ex = kx + (int) ((i + 1) * step);
            int wpx = ex - sx - 1;
            if (wpx < 1) {
                wpx = 1;
            }
            g.setColor(0xBEC2CC);
            g.fillRect(sx, y, wpx, h);
        }
        // 活动白键点亮
        int w1 = inRange(note1) ? WHITE_IDX[note1] : -1;
        int w2 = inRange(note2) ? WHITE_IDX[note2] : -1;
        if (w1 >= 0 && w2 >= 0 && w1 == w2) {
            int c1 = keyColor(note1, note1, ay.visSmoothLevel(ch), CH_COLORS[ch]);
            int c2 = keyColor(note2, note2, ay2.visSmoothLevel(ch), CH2_COLORS[ch]);
            int px = kx + (int) (w1 * step);
            g.setColor(c1);
            g.fillRect(px, y, keyW / 2, h);
            g.setColor(c2);
            g.fillRect(px + keyW / 2, y, keyW - keyW / 2, h);
        } else {
            if (w1 >= 0) {
                int sx = kx + (int) (w1 * step);
                int ex = kx + (int) ((w1 + 1) * step);
                g.setColor(keyColor(note1, note1, ay.visSmoothLevel(ch), CH_COLORS[ch]));
                g.fillRect(sx, y, ex - sx - 1, h);
            }
            if (w2 >= 0) {
                int sx = kx + (int) (w2 * step);
                int ex = kx + (int) ((w2 + 1) * step);
                g.setColor(keyColor(note2, note2, ay2.visSmoothLevel(ch), CH2_COLORS[ch]));
                g.fillRect(sx, y, ex - sx - 1, h);
            }
        }
        // 黑键凹槽
        for (int n = FIRST_NOTE; n <= LAST_NOTE; n++) {
            if (WHITE_IDX[n] >= 0) {
                continue;
            }
            int wi = WHITE_IDX[n - 1] + 1;
            int bx = kx + (int) (wi * step) - bw + 1;
            if (n == note1 && n == note2) {
                g.setColor(CH_COLORS[ch]);
                g.fillRect(bx, y, bw / 2, bh);
                g.setColor(CH2_COLORS[ch]);
                g.fillRect(bx + bw / 2, y, bw - bw / 2, bh);
            } else if (n == note1) {
                g.setColor(CH_COLORS[ch]);
                g.fillRect(bx, y, bw, bh);
            } else if (n == note2) {
                g.setColor(CH2_COLORS[ch]);
                g.fillRect(bx, y, bw, bh);
            } else {
                g.setColor(0x14161C);
                g.fillRect(bx, y, bw, bh);
            }
        }

        // 噪声键（v16 规则：clock/(512*per) 的可视音高；本声道噪声门开着才亮）
        if (ay.visNoise(ch)) {
            int nn = ay.visNoiseNote();
            markKey(g, kx, y, step, keyW, bw, bh, h, nn, COL_NOISE);
        }
        if (ay2 != null && ay2.visNoise(ch)) {
            int nn = ay2.visNoiseNote();
            markKey(g, kx, y, step, keyW, bw, bh, h, nn, COL_NOISE2);
        }
        // 包络键（亮在发声源声道的横条上）
        if (ay.visBuzzSrc() == ch) {
            markKey(g, kx, y, step, keyW, bw, bh, h, ay.visEnvNote(), COL_ENV);
        }
        if (ay2 != null && ay2.visBuzzSrc() == ch) {
            markKey(g, kx, y, step, keyW, bw, bh, h, ay2.visEnvNote(), COL_ENV2);
        }

        // T/N/E 等距像素字符：点亮条件 = 门开且实际发声（否则一律灰色）
        for (int i = 0; i < 3; i++) {
            boolean on1, on2;
            int col1, col2;
            if (i == 0) {
                on1 = ay.visToneActive(ch);
                col1 = CH_COLORS[ch];
                on2 = ay2 != null && ay2.visToneActive(ch);
                col2 = CH2_COLORS[ch];
            } else if (i == 1) {
                on1 = ay.visNoiseActive(ch);
                col1 = COL_NOISE;
                on2 = ay2 != null && ay2.visNoiseActive(ch);
                col2 = COL_NOISE2;
            } else {
                on1 = ay.visEnvActive(ch);
                col1 = COL_ENV;
                on2 = ay2 != null && ay2.visEnvActive(ch);
                col2 = COL_ENV2;
            }
            gateChar(g, x + w - 24 + i * 8, y, h, GATE_LETTERS[i],
                    on1, col1, on2, col2, ay.visSmoothLevel(ch));
        }
    }

    private static final String[] GATE_LETTERS = {"T", "N", "E"};

    /** 8px 槽内居中的一个字符：开=电平渐变色，关=亮中灰。 */
    private void gateChar(Graphics g, int slotX, int y, int h, String letter,
                          boolean on1, int col1, boolean on2, int col2, int level) {
        int col = 0x6A7488;      // 熄灭=亮中灰，暗背景下高对比
        if (on1) {
            col = levelColor(col1, level);
        } else if (on2) {
            col = levelColor(col2, level);
        }
        g.setFont(tiny);
        g.setColor(col);
        g.drawString(letter, slotX + 4, y + h / 2,
                Graphics.BASELINE | Graphics.HCENTER);
    }

    /** 在横条上标记一个键：白键整高，黑键凹槽整块。 */
    private void markKey(Graphics g, int kx, int y, float step, int keyW,
                         int bw, int bh, int h, int note, int color) {
        if (note < FIRST_NOTE || note > LAST_NOTE) {
            return;
        }
        g.setColor(color);
        int wi = WHITE_IDX[note];
        if (wi >= 0) {
            int sx = kx + (int) (wi * step);
            int ex = kx + (int) ((wi + 1) * step);
            g.fillRect(sx, y, ex - sx - 1, h);
        } else {
            int bwi = WHITE_IDX[note - 1] + 1;
            g.fillRect(kx + (int) (bwi * step) - bw + 1, y, bw, bh);
        }
    }

    private static boolean inRange(int note) {
        return note >= FIRST_NOTE && note <= LAST_NOTE;
    }

    /** v16 深浅机制：45% 基色 + 55% 白 × 电平(0-127)。低电平保色相且不贴近背景。 */
    private static int levelColor(int base, int level) {
        int r = (base >> 16) & 0xFF;
        int gr = (base >> 8) & 0xFF;
        int b = base & 0xFF;
        int w = 55 * level / 127;      // 白色占比 0-55%
        r = r * (100 - w) / 100 + 255 * w / 100;
        gr = gr * (100 - w) / 100 + 255 * w / 100;
        b = b * (100 - w) / 100 + 255 * w / 100;
        return (r << 16) | (gr << 8) | b;
    }

    /** 键位高亮色（平滑电平版）。 */
    private static int keyColor(int note, int active, int level, int base) {
        return levelColor(base, level);
    }

    // ------------------------------------------------------------ 设置浮层

    private void paintSettings(Graphics g, int w, int h) {
        g.setFont(titleFont);
        g.setColor(0xF0EDE4);
        g.drawString("设置", 6, 4, Graphics.TOP | Graphics.LEFT);
        g.setFont(tiny);
        g.setColor(0x4A5568);
        g.drawString("▲▼选 ◀▶调 #返回", w - 6, 4, Graphics.TOP | Graphics.RIGHT);

        int y0 = 28;
        int listH = h - y0 - 8;
        int visible = listH / ROW_H;
        if (visible < 1) {
            visible = 1;
        }
        if (cursor < scroll) {
            scroll = cursor;
        }
        if (cursor >= scroll + visible) {
            scroll = cursor - visible + 1;
        }
        if (scroll + visible > TOTAL_ROWS) {
            scroll = TOTAL_ROWS - visible;
        }
        if (scroll < 0) {
            scroll = 0;
        }
        for (int i = 0; i < visible; i++) {
            int r = scroll + i;
            if (r >= TOTAL_ROWS) {
                break;
            }
            boolean sel = cursor == r;
            if (sel) {
                g.setColor(0x1C2E44);
                g.fillRect(4, y0 + i * ROW_H, w - 12, ROW_H - 1);
            }
            g.setColor(sel ? 0x6FB1DE : 0xC8CDD6);
            g.setFont(tiny);
            g.drawString(rowName(r), 10, y0 + i * ROW_H + 1, Graphics.TOP | Graphics.LEFT);
            g.setColor(0xE8B44C);
            g.drawString(rowValue(r), w - 10, y0 + i * ROW_H + 1, Graphics.TOP | Graphics.RIGHT);
        }
        if (TOTAL_ROWS > visible) {
            int trackH = visible * ROW_H;
            g.setColor(0x1B2430);
            g.fillRect(w - 5, y0, 3, trackH);
            int thumbH = trackH * visible / TOTAL_ROWS;
            if (thumbH < 8) {
                thumbH = 8;
            }
            int thumbY = y0 + (trackH - thumbH) * scroll / (TOTAL_ROWS - visible);
            g.setColor(0x6FB1DE);
            g.fillRect(w - 5, thumbY, 3, thumbH);
        }
    }

    private String rowName(int r) {
        switch (r) {
            case ROW_STEREO: return "声像";
            case ROW_OCT: return "八度";
            case ROW_NOISEMAP: return "噪声映射";
            case ROW_ENVSAW_OCT: return "包络锯齿八度";
            case ROW_ENVTRI_OCT: return "包络三角八度";
            case ROW_ENVSAW_UNI: return "锯齿上行复音";
            case ROW_ENVTRI_UNI: return "三角上行复音";
            case ROW_BUZZ_VOL: return "蜂鸣器音量";
            case ROW_LOOP: return "循环次数";
            case ROW_ORDER: return "播放顺序";
            default: return CH_NAMES[r - ROW_CH0];
        }
    }

    private String rowValue(int r) {
        switch (r) {
            case ROW_STEREO: return Ay2Midi.STEREO_MODES[ay.getStereoMode()];
            case ROW_OCT: return (ay.getOct() >= 0 ? "+" : "") + ay.getOct();
            case ROW_NOISEMAP: return Ay2Midi.NOISE_MAP_NAMES[ay.getNoiseMapMode()];
            case ROW_ENVSAW_OCT:
                return (ay.getEnvOctSaw() >= 0 ? "+" : "") + ay.getEnvOctSaw();
            case ROW_ENVTRI_OCT:
                return (ay.getEnvOctTri() >= 0 ? "+" : "") + ay.getEnvOctTri();
            case ROW_ENVSAW_UNI: return UNI_NAMES[ay.getEnvUni(false)];
            case ROW_ENVTRI_UNI: return UNI_NAMES[ay.getEnvUni(true)];
            case ROW_BUZZ_VOL: return String.valueOf(ay.getBuzzVol());
            case ROW_LOOP: return LOOP_NAMES[loopIdx];
            case ROW_ORDER: return ORDER_NAMES[shuffle ? 1 : 0];
            default: return String.valueOf(ay.getProg(r - ROW_CH0));
        }
    }

    private void adjust(int dir) {
        switch (cursor) {
            case ROW_STEREO:
                ay.setStereoMode(ay.getStereoMode() + dir);
                if (ay2 != null) ay2.setStereoMode(ay2.getStereoMode() + dir);
                break;
            case ROW_OCT: {
                int v = ay.getOct() + dir;
                ay.setOct(v);
                if (ay2 != null) ay2.setOct(v);
                break;
            }
            case ROW_NOISEMAP: {
                int v = ay.getNoiseMapMode() + dir;
                ay.setNoiseMapMode(v);
                if (ay2 != null) ay2.setNoiseMapMode(v);
                break;
            }
            case ROW_ENVSAW_OCT: {
                int v = ay.getEnvOctSaw() + dir;
                ay.setEnvOct(false, v);
                if (ay2 != null) ay2.setEnvOct(false, v);
                break;
            }
            case ROW_ENVTRI_OCT: {
                int v = ay.getEnvOctTri() + dir;
                ay.setEnvOct(true, v);
                if (ay2 != null) ay2.setEnvOct(true, v);
                break;
            }
            case ROW_ENVSAW_UNI: {
                int u = (ay.getEnvUni(false) + dir + 3) % 3;
                ay.setEnvUni(false, u);
                if (ay2 != null) ay2.setEnvUni(false, u);
                break;
            }
            case ROW_ENVTRI_UNI: {
                int u = (ay.getEnvUni(true) + dir + 3) % 3;
                ay.setEnvUni(true, u);
                if (ay2 != null) ay2.setEnvUni(true, u);
                break;
            }
            case ROW_BUZZ_VOL: {
                int v = ay.getBuzzVol() + dir * 10;
                ay.setBuzzVol(v);
                if (ay2 != null) ay2.setBuzzVol(v);
                break;
            }
            case ROW_LOOP:
                loopIdx = (loopIdx + dir + LOOP_CHOICES.length) % LOOP_CHOICES.length;
                loopPlays = 0;
                break;
            case ROW_ORDER:
                shuffle = !shuffle;
                break;
            default: {
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
                if (ay2 != null) ay2.setProg(ch, PROG_CHOICES[next]);
                break;
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

    // ------------------------------------------------------------ 工具

    private static String pick(String a, String b) {
        return a != null && a.length() > 0 ? a : (b == null ? "" : b);
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + ".." : s;
    }

    private static String fmt(long sec) {
        if (sec < 0) {
            sec = 0;
        }
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    // ------------------------------------------------------------ 输入

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);

        if (mode == MODE_SETTINGS) {
            if (keyCode == KEY_POUND) {
                mode = MODE_HUD;
            } else if (action == UP) {
                cursor = cursor > 0 ? cursor - 1 : TOTAL_ROWS - 1;
            } else if (action == DOWN) {
                cursor = cursor < TOTAL_ROWS - 1 ? cursor + 1 : 0;
            } else if (action == LEFT) {
                adjust(-1);
            } else if (action == RIGHT) {
                adjust(1);
            }
            repaint();
            return;
        }

        if (action == LEFT) {
            int p = prevIndex();
            if (p >= 0) {
                switchTrackFade(p);
            }
        } else if (action == RIGHT) {
            int n = nextIndex();
            if (n >= 0) {
                switchTrackFade(n);
            }
        } else if (action == UP) {
            setMaster(masterVol + 5);
        } else if (action == DOWN) {
            setMaster(masterVol - 5);
        } else if (action == FIRE || keyCode == KEY_NUM5) {
            if (playing) {
                playing = false;
                ay.allSoundOff();
                if (ay2 != null) {
                    ay2.allSoundOff();
                }
            } else {
                startPlay(false);
            }
        } else if (keyCode == KEY_POUND) {
            mode = MODE_SETTINGS;
        }
        repaint();
    }

    protected void keyRepeated(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == UP || action == DOWN || action == LEFT || action == RIGHT) {
            keyPressed(keyCode);
        }
    }

    private void setMaster(int v) {
        if (v < 0) v = 0;
        if (v > 127) v = 127;
        masterVol = v;
        ay.setMasterVol(v);
        if (ay2 != null) {
            ay2.setMasterVol(v);
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdSettings) {
            mode = MODE_SETTINGS;
            repaint();
        } else if (c == cmdBack) {
            // 返回列表但保持后台播放（单实例由 MIDlet 管理）
            midlet.backToList();
        }
    }
}
