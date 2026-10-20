package com.denjhang.vgmbrowser;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import com.denjhang.vgm.ChunkedPlayer;
import com.denjhang.vgm.StreamPlayer;

/**
 * 实时方波测试屏（双引擎）。
 *
 * 模式 1 分块链：0.5s WAV 块接龙 —— 模拟器/加载器能出声，测接缝 gap。
 * 模式 2 无限流：阻塞 InputStream —— KEmulator 必败（mark/reset），
 *              留给真机 E5 验证 MMAPI 原生流式。
 *
 * 按键：↑↓ 音高 ×2÷2   ←→ 音量 ±10   5 启停   7 切模式   返回退出
 */
public class ToneTestScreen extends Canvas implements CommandListener, Runnable {

    private final BrowserMIDlet midlet;
    private final ChunkedPlayer chunked = new ChunkedPlayer();
    private final StreamPlayer streaming = new StreamPlayer();

    private boolean chunkMode = true;   // 默认分块：模拟器唯一能出声的模式
    private volatile boolean uiAlive = true;

    private final Command cmdBack = new Command("Back", Command.BACK, 1);
    private final Command cmdToggle = new Command("Start/Stop", Command.OK, 1);

    private final Font big;
    private final Font small;

    public ToneTestScreen(BrowserMIDlet midlet) {
        this.midlet = midlet;
        setFullScreenMode(true);
        addCommand(cmdBack);
        addCommand(cmdToggle);
        setCommandListener(this);
        big = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
        small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        Thread t = new Thread(this);
        t.start();
    }

    public void run() {
        while (uiAlive) {
            repaint();
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private boolean isRunning() {
        return chunkMode ? chunked.isRunning() : streaming.isRunning();
    }

    private void stopAll() {
        chunked.stop();
        streaming.stop();
    }

    private void toggle() {
        if (isRunning()) {
            stopAll();
            return;
        }
        if (chunkMode) {
            chunked.start();
        } else {
            streaming.start();
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        g.setColor(0x101820);
        g.fillRect(0, 0, w, h);

        g.setColor(0xE0E0E0);
        g.setFont(big);
        g.drawString("Live square wave test", w / 2, 4, Graphics.TOP | Graphics.HCENTER);

        g.setFont(small);
        int y = h / 4;

        g.setColor(0x64B4FF);
        g.drawString(chunkMode ? ("Mode: chunked chain (chunk=" + chunked.getChunkLabel() + ")")
                               : "Mode: infinite stream (device only)", 8, y, Graphics.TOP | Graphics.LEFT);
        y += 20;

        g.setColor(isRunning() ? 0x7CFC7C : 0xA0A0A0);
        g.drawString(isRunning() ? "Status: playing" : "Status: stopped", 8, y,
                Graphics.TOP | Graphics.LEFT);
        y += 20;

        g.setColor(0xE0E0E0);
        g.drawString("Pitch: " + freq() + " Hz", 8, y, Graphics.TOP | Graphics.LEFT);
        y += 18;
        g.drawString("Volume: " + vol() + "%", 8, y, Graphics.TOP | Graphics.LEFT);

        y += 24;
        g.setColor(0xA0C0E0);
        if (chunkMode) {
            g.drawString("Chunks played: " + chunked.getPlayedChunks(), 8, y,
                    Graphics.TOP | Graphics.LEFT);
            y += 18;
            g.drawString("Avg gap: " + chunked.getAvgGapMs() + " ms", 8, y,
                    Graphics.TOP | Graphics.LEFT);
            y += 18;
            g.drawString("Max gap: " + chunked.getMaxGapMs() + " ms", 8, y,
                    Graphics.TOP | Graphics.LEFT);
            y += 18;
            g.drawString("Event delay: " + chunked.getEventDelayMs()
                    + " ms  start cost: " + chunked.getStartCostMs() + " ms", 8, y,
                    Graphics.TOP | Graphics.LEFT);
        } else {
            g.drawString("Buffer: " + streaming.getBuffered() + " B ("
                    + streaming.getLatencyMs() + " ms)", 8, y, Graphics.TOP | Graphics.LEFT);
            y += 18;
            g.drawString("Underruns: " + streaming.getUnderruns(), 8, y,
                    Graphics.TOP | Graphics.LEFT);
        }

        String err = chunkMode ? chunked.getLastError()
                : (streaming.isRunning() || streaming.getUnderruns() >= 0 ? null : null);
        if (chunkMode && chunked.getLastError() != null) {
            y += 24;
            g.setColor(0xFF7C7C);
            g.drawString("Error: " + chunked.getLastError(), 8, y,
                    Graphics.TOP | Graphics.LEFT);
        }

        g.setColor(0x808080);
        g.drawString("Up/Dn pitch  L/R volume  5 start/stop  7 mode  8 chunk len", 8, h - 20,
                Graphics.TOP | Graphics.LEFT);
    }

    private int freq() {
        return chunkMode ? chunked.getFreq() : streaming.getFreq();
    }

    private int vol() {
        return chunkMode ? chunked.getVolume() : streaming.getVolume();
    }

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == UP) {
            setFreq(freq() * 2);
        } else if (action == DOWN) {
            setFreq(freq() / 2);
        } else if (action == LEFT) {
            setVol(vol() - 10);
        } else if (action == RIGHT) {
            setVol(vol() + 10);
        } else if (action == FIRE || keyCode == KEY_NUM5) {
            toggle();
        } else if (keyCode == KEY_NUM8) {
            boolean was = chunked.isRunning();
            chunked.stop();
            chunked.cycleChunk();
            if (was) {
                chunked.start();
            }
        } else if (keyCode == KEY_NUM7 || keyCode == KEY_POUND) {
            boolean wasRunning = isRunning();
            stopAll();
            chunkMode = !chunkMode;
            if (wasRunning) {
                toggle();
            }
        }
        repaint();
    }

    protected void keyRepeated(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == UP) {
            setFreq(freq() * 2);
        } else if (action == DOWN) {
            setFreq(freq() / 2);
        }
    }

    private void setFreq(int hz) {
        chunked.setFreq(hz);
        streaming.setFreq(hz);
    }

    private void setVol(int v) {
        chunked.setVolume(v);
        streaming.setVolume(v);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdBack) {
            uiAlive = false;
            stopAll();
            midlet.backToList();
        } else if (c == cmdToggle) {
            toggle();
            repaint();
        }
    }
}
