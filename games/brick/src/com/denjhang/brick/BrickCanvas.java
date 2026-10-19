package com.denjhang.brick;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * 打砖块画面与主循环。
 * 代码保持 Java 1.4 语法（不用泛型/增强 for），以便 target 1.4 的 class 文件。
 */
public class BrickCanvas extends Canvas implements Runnable {

    private static final int COLS = 7;
    private static final int ROWS = 5;
    private static final int BRICK_TOP = 34;
    private static final int BRICK_GAP = 2;
    private static final int BRICK_H = 12;

    private static final int STATE_READY = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_WON = 2;
    private static final int STATE_LOST = 3;

    /** 按住方向键时每帧的移动像素（60fps 下约 360px/s）。 */
    private static final int PADDLE_SPEED = 6;

    private final BrickMIDlet midlet;
    private final MidiAudio audio;

    private int width;
    private int height;

    private int paddleX;
    private int paddleW;
    private int paddleH;

    private int ballX;
    private int ballY;
    private int ballDX;
    private int ballDY;
    private int ballR;

    private boolean[][] bricks;
    private int bricksLeft;

    private int score;
    private int lives;
    private int state;

    private volatile boolean running;
    private volatile boolean paused;
    private Thread loop;

    /** 按住状态自己记，不依赖模拟器的 key-repeat 事件。 */
    private boolean leftHeld;
    private boolean rightHeld;

    private final Font smallFont;
    private final Font bigFont;

    public BrickCanvas(BrickMIDlet midlet, MidiAudio audio) {
        this.midlet = midlet;
        this.audio = audio;
        this.smallFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        this.bigFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        width = getWidth();
        height = getHeight();
        if (width <= 0) {
            width = 240;
        }
        if (height <= 0) {
            height = 320;
        }
        resetGame();
    }

    // ---------------------------------------------------------------- 主循环

    void startGameLoop() {
        if (loop == null) {
            running = true;
            paused = false;
            loop = new Thread(this);
            loop.start();
        } else {
            paused = false;
        }
        audio.startMusic();
    }

    void pauseGameLoop() {
        paused = true;
        leftHeld = false;
        rightHeld = false;
        audio.stopMusic();
    }

    void stopGameLoop() {
        running = false;
        loop = null;
        audio.close();
    }

    public void run() {
        while (running) {
            long began = System.currentTimeMillis();
            if (!paused) {
                update();
                repaint();
            }
            long spent = System.currentTimeMillis() - began;
            long sleep = 16 - spent;
            if (sleep < 1) {
                sleep = 1;
            }
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                // 退出时忽略
            }
        }
    }

    // ---------------------------------------------------------------- 游戏状态

    private void resetGame() {
        score = 0;
        lives = 3;
        newRound();
    }

    private void newRound() {
        bricks = new boolean[ROWS][COLS];
        bricksLeft = ROWS * COLS;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                bricks[r][c] = true;
            }
        }

        paddleW = width / 4;
        if (paddleW < 28) {
            paddleW = 28;
        }
        paddleH = 8;
        paddleX = (width - paddleW) / 2;

        ballR = 4;
        resetBall();
        state = STATE_READY;
    }

    private void resetBall() {
        ballX = width / 2;
        ballY = height - paddleH - 30;
        ballDX = 3;
        ballDY = -3;
    }

    private void update() {
        movePaddleWithHeldKeys();

        if (state != STATE_PLAYING) {
            return;
        }

        ballX += ballDX;
        ballY += ballDY;

        // 左右墙
        if (ballX - ballR < 0) {
            ballX = ballR;
            ballDX = -ballDX;
        } else if (ballX + ballR > width) {
            ballX = width - ballR;
            ballDX = -ballDX;
        }

        // 顶部
        if (ballY - ballR < 0) {
            ballY = ballR;
            ballDY = -ballDY;
        }

        // 挡板
        int paddleY = height - paddleH - 6;
        if (ballDY > 0
                && ballY + ballR >= paddleY
                && ballY + ballR <= paddleY + paddleH + 4
                && ballX >= paddleX - ballR
                && ballX <= paddleX + paddleW + ballR) {
            ballY = paddleY - ballR;
            ballDY = -ballDY;
            int hit = (ballX - (paddleX + paddleW / 2)) * 5 / (paddleW / 2 + 1);
            ballDX = hit;
            if (ballDX == 0) {
                ballDX = 1;
            }
            if (ballDY > -3) {
                ballDY = -4;
            }
            audio.fxPaddle();
        }

        // 掉出底部
        if (ballY - ballR > height) {
            lives--;
            audio.fxLost();
            if (lives <= 0) {
                state = STATE_LOST;
                audio.fxGameOver();
            } else {
                resetBall();
                state = STATE_READY;
            }
            return;
        }

        checkBrickCollision();

        if (bricksLeft == 0) {
            state = STATE_WON;
            audio.fxWin();
        }
    }

    private void movePaddleWithHeldKeys() {
        if (leftHeld) {
            paddleX -= PADDLE_SPEED;
        }
        if (rightHeld) {
            paddleX += PADDLE_SPEED;
        }
        if (leftHeld || rightHeld) {
            clampPaddle();
        }
    }

    private void checkBrickCollision() {
        int brickW = brickWidth();

        if (ballY + ballR < BRICK_TOP || ballY - ballR > BRICK_TOP + ROWS * (BRICK_H + BRICK_GAP)) {
            return;
        }

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (!bricks[r][c]) {
                    continue;
                }
                int bx = 4 + c * (brickW + BRICK_GAP);
                int by = BRICK_TOP + r * (BRICK_H + BRICK_GAP);

                if (ballX + ballR >= bx && ballX - ballR <= bx + brickW
                        && ballY + ballR >= by && ballY - ballR <= by + BRICK_H) {
                    bricks[r][c] = false;
                    bricksLeft--;
                    score += 10;
                    ballDY = -ballDY;
                    audio.fxBrick();
                    return;
                }
            }
        }
    }

    private int brickWidth() {
        return (width - 8 - (COLS - 1) * BRICK_GAP) / COLS;
    }

    // ---------------------------------------------------------------- 绘制

    protected void paint(Graphics g) {
        g.setColor(0x101820);
        g.fillRect(0, 0, width, height);

        drawHud(g);
        drawBricks(g);
        drawPaddle(g);
        drawBall(g);

        if (state == STATE_READY) {
            drawCenterText(g, "点击 / 按 5 发球", 0xE0E0E0);
            drawCenterTextOffset(g, "← → 移动", 24, 0xBFBFBF);
        } else if (state == STATE_WON) {
            drawCenterText(g, "通关！得分 " + score, 0x7CFC7C);
            drawCenterTextOffset(g, "按 5 再来一局", 24, 0xBFBFBF);
        } else if (state == STATE_LOST) {
            drawCenterText(g, "游戏结束  得分 " + score, 0xFF7C7C);
            drawCenterTextOffset(g, "按 5 重新开始", 24, 0xBFBFBF);
        }
    }

    private void drawHud(Graphics g) {
        g.setFont(smallFont);
        g.setColor(0xA0C0E0);
        g.drawString("得分 " + score, 4, 4, Graphics.TOP | Graphics.LEFT);
        g.drawString("命 " + lives + "/3", width - 4, 4, Graphics.TOP | Graphics.RIGHT);
    }

    private void drawBricks(Graphics g) {
        int brickW = brickWidth();
        int[] palette = {
            0xE05A5A, 0xE0A040, 0xDCD44A, 0x5AC85A, 0x4AA8DC
        };

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (!bricks[r][c]) {
                    continue;
                }
                int bx = 4 + c * (brickW + BRICK_GAP);
                int by = BRICK_TOP + r * (BRICK_H + BRICK_GAP);
                g.setColor(palette[r % palette.length]);
                g.fillRect(bx, by, brickW, BRICK_H);
                g.setColor(0xFFFFFF);
                g.drawLine(bx, by, bx + brickW - 1, by);
            }
        }
    }

    private void drawPaddle(Graphics g) {
        int paddleY = height - paddleH - 6;
        g.setColor(0x64B4FF);
        g.fillRoundRect(paddleX, paddleY, paddleW, paddleH, 6, 6);
    }

    private void drawBall(Graphics g) {
        g.setColor(0xFFFFFF);
        g.fillArc(ballX - ballR, ballY - ballR, ballR * 2, ballR * 2, 0, 360);
    }

    private void drawCenterText(Graphics g, String text, int color) {
        drawCenterTextOffset(g, text, 0, color);
    }

    private void drawCenterTextOffset(Graphics g, String text, int dy, int color) {
        g.setFont(bigFont);
        g.setColor(color);
        int baseline = height / 2 + dy;
        g.drawString(text, width / 2, baseline, Graphics.TOP | Graphics.HCENTER);
    }

    // ---------------------------------------------------------------- 输入

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == LEFT) {
            leftHeld = true;
        } else if (action == RIGHT) {
            rightHeld = true;
        } else if (action == FIRE || keyCode == KEY_NUM5) {
            launchOrRestart();
        } else if (action == GAME_A || keyCode == KEY_STAR) {
            audio.startMusic();
        }
    }

    protected void keyReleased(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == LEFT) {
            leftHeld = false;
        } else if (action == RIGHT) {
            rightHeld = false;
        }
    }

    /**
     * 有些机器只发 keyRepeated 不发按住状态，这里也走一遍，
     * 保证长按一定有响应（短按由 keyPressed 处理）。
     */
    protected void keyRepeated(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == LEFT) {
            leftHeld = true;
        } else if (action == RIGHT) {
            rightHeld = true;
        }
    }

    protected void pointerPressed(int x, int y) {
        setPaddleCenter(x);
        if (state == STATE_READY) {
            launchOrRestart();
        } else if (state == STATE_WON || state == STATE_LOST) {
            launchOrRestart();
        }
    }

    protected void pointerDragged(int x, int y) {
        setPaddleCenter(x);
    }

    private void setPaddleCenter(int x) {
        paddleX = x - paddleW / 2;
        clampPaddle();
    }

    private void clampPaddle() {
        if (paddleX < 0) {
            paddleX = 0;
        }
        if (paddleX > width - paddleW) {
            paddleX = width - paddleW;
        }
    }

    private void launchOrRestart() {
        if (state == STATE_READY) {
            state = STATE_PLAYING;
        } else if (state == STATE_WON || state == STATE_LOST) {
            resetGame();
            state = STATE_PLAYING;
        }
    }
}
