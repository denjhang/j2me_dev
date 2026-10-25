package com.denjhang.vgmbrowser;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import com.denjhang.vgm.VgmFile;

/**
 * VGM 浏览器 —— 测试版：自动加载固定 VGM 并输出帧率日志。
 */
public class BrowserMIDlet extends MIDlet {

    private FileListScreen list;
    private PlayScreen play;
    
    // ---- 测试模式：自动播放整个文件夹 ----
    private static final String AUTO_LOAD_DIR = "file:///root/vgm/2025-Turbosound/";
    private static final boolean AUTO_PLAY_FULL = true;  // true=播放完整，false=限时切曲
    private static final int AUTO_PLAY_SECONDS = 30;  // 仅当 AUTO_PLAY_FULL=false 时生效
    private Thread autoPlayThread;
    /** chunk.txt 读到的块档（-1=不覆盖），在每个 PlayScreen 创建后应用。 */
    private int pendingChunkLevel = -1;

    public void startApp() {
        System.out.println("[TEST] === VgmBrowserSingle 测试版启动 ===");
        System.out.println("[TEST] 自动播放目录: " + AUTO_LOAD_DIR);
        System.out.println("[TEST] 每首播放 " + AUTO_PLAY_SECONDS + " 秒后自动切曲");
        
        // 先初始化 list（PlayScreen 构造函数会调用 refreshListNowPlaying）
        if (list == null) {
            list = new FileListScreen(this);
        }
        
        // 启动自动播放线程
        autoPlayThread = new Thread(new Runnable() {
            public void run() {
                autoPlayLoop();
            }
        });
        autoPlayThread.start();
    }
    
    private void autoPlayLoop() {
        try {
            // ---- 探针：读 /root/chunk.txt 覆盖块档（对比实验用，无需改码重编）----
            try {
                FileConnection cf = (FileConnection) Connector.open("file:///root/chunk.txt", Connector.READ);
                try {
                    java.io.InputStream cin = cf.openInputStream();
                    java.io.ByteArrayOutputStream cb = new java.io.ByteArrayOutputStream();
                    byte[] t = new byte[64];
                    int n;
                    while ((n = cin.read(t)) > 0) {
                        cb.write(t, 0, n);
                    }
                    cin.close();
                    String s = new String(cb.toByteArray()).trim();
                    int lvl = Integer.parseInt(s);
                    pendingChunkLevel = lvl;
                    System.out.println("[TEST] chunk.txt 覆盖块档 = " + lvl);
                } finally {
                    cf.close();
                }
            } catch (Throwable t) {
                System.out.println("[TEST] 无 chunk.txt，用默认块档");
            }
            // 扫描目录获取所有 VGM 文件
            java.util.Vector files = new java.util.Vector();
            FileConnection dir = (FileConnection) Connector.open(AUTO_LOAD_DIR, Connector.READ);
            try {
                java.util.Enumeration e = dir.list();
                while (e.hasMoreElements()) {
                    String name = (String) e.nextElement();
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".vgm") || lower.endsWith(".vgz")) {
                        files.addElement(name);
                    }
                }
            } finally {
                dir.close();
            }
            
            System.out.println("[TEST] 找到 " + files.size() + " 个 VGM 文件");
            
            if (files.size() == 0) {
                System.out.println("[TEST] 目录为空，退出");
                return;
            }
            
            // 循环播放所有文件
            int idx = 0;
            while (true) {
                String name = (String) files.elementAt(idx);
                System.out.println("[TEST] (" + (idx + 1) + "/" + files.size() + ") 加载: " + name);
                
                // 加载文件
                FileConnection fc = (FileConnection) Connector.open(AUTO_LOAD_DIR + name, Connector.READ);
                try {
                    long size = fc.fileSize();
                    java.io.InputStream in = fc.openInputStream();
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream((int) size);
                    byte[] tmp = new byte[8192];
                    int r;
                    while ((r = in.read(tmp)) > 0) {
                        buf.write(tmp, 0, r);
                    }
                    in.close();
                    byte[] raw = buf.toByteArray();
                    
                    System.out.println("[TEST] 文件已加载: " + raw.length + " bytes");
                    
                    // 关闭旧的播放
                    releasePlay();
                    
                    // 创建新的 PlayScreen
                    play = new PlayScreen(BrowserMIDlet.this, name, raw, idx);
                    play.setSrcFile(AUTO_LOAD_DIR + name);
                    Display.getDisplay(BrowserMIDlet.this).setCurrent(play);
                    
                } finally {
                    fc.close();
                }
                
                if (AUTO_PLAY_FULL) {
                    // 等待播放线程启动
                    System.out.println("[TEST] 等待播放启动...");
                    long waitStart = System.currentTimeMillis();
                    while (play != null && !play.playing && (System.currentTimeMillis() - waitStart) < 5000) {
                        Thread.sleep(100);
                    }
                    
                    if (play == null || !play.playing) {
                        System.out.println("[TEST] 播放未启动，跳过");
                    } else {
                        System.out.println("[TEST] 播放已启动，等待完成...");
                        long startMs = System.currentTimeMillis();
                        while (play != null && play.playing) {
                            Thread.sleep(1000);
                            long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
                            if (elapsedSec % 10 == 0 && elapsedSec > 0) {
                                System.out.println("[TEST] 播放中... " + elapsedSec + " 秒");
                            }
                        }
                        System.out.println("[TEST] 播放完成，总时长: " + ((System.currentTimeMillis() - startMs) / 1000) + " 秒");
                    }
                    Thread.sleep(2000);  // 等 2 秒再切曲
                } else {
                    // 定时切曲
                    Thread.sleep(AUTO_PLAY_SECONDS * 1000);
                }
                
                // 切换到下一首
                idx = (idx + 1) % files.size();
            }
            
        } catch (Throwable t) {
            System.out.println("[TEST] 自动播放失败: " + t);
            t.printStackTrace();
        }
    }

    public void pauseApp() {
    }

    public void destroyApp(boolean unconditional) {
        releasePlay();
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

    void openMidiTest() {
        Display.getDisplay(this).setCurrent(new MidiTestScreen(this));
    }

    void openPlayRaw(String name, byte[] rawBytes, int listIndex) {
        openPlayRaw(name, rawBytes, listIndex, null);
    }

    /** srcUrl=VGM 完整 URL；.mid 落到源目录的 vgm2mid/ 子目录。 */
    void openPlayRaw(String name, byte[] rawBytes, int listIndex, String srcUrl) {
        releasePlay();
        play = new PlayScreen(this, name, rawBytes, listIndex);
        play.setSrcFile(srcUrl);
        applyChunkLevel();
        Display.getDisplay(this).setCurrent(play);
    }

    /** chunk.txt 探针：把覆盖档位应用到当前 PlayScreen。 */
    private void applyChunkLevel() {
        if (pendingChunkLevel >= 0 && play != null) {
            play.setChunkLevel(pendingChunkLevel);
            System.out.println("[TEST] 块档已应用 = " + pendingChunkLevel);
        }
    }

    void openPlay(VgmFile file, int listIndex) {
        releasePlay();
        play = new PlayScreen(this, file, listIndex);
        Display.getDisplay(this).setCurrent(play);
    }

    /** 释放上一个播放屏（切曲时必须关掉旧 MIDI Player，防资源泄漏）。 */
    void releasePlay() {
        if (play != null) {
            play.release();
            play = null;
        }
    }

    /** 播放器切曲（上一曲/下一曲），越界忽略。 */
    void switchTrack(int idx) {
        releasePlay();
        list.openByIndex(idx);
    }

    /** 播放列表条目数（随机播放用）。 */
    int trackCount() {
        return list.entryCount();
    }

    /** 后台播放是否存活。 */
    boolean playerAlive() {
        return play != null;
    }

    void refreshListNowPlaying() {
        list.refreshNowPlaying();
    }

    /** 回到正在播放的界面。 */
    void showPlayer() {
        if (play != null) {
            Display.getDisplay(this).setCurrent(play);
        }
    }
}
