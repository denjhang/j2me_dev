package com.denjhang.vgmbrowser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;

import com.denjhang.vgm.VgmFile;

/**
 * 文件浏览器：从文件系统根开始逐级进入，列出目录和 .vgm 文件。
 *
 * 只读，不写任何东西。
 */
public class FileListScreen extends List implements CommandListener {

    private static final String UP = "..";

    private final BrowserMIDlet midlet;
    private String currentDir = null;   // null = 根列表

    private final Command cmdOpen = new Command("Open", Command.OK, 1);
    private final Command cmdUp = new Command("Up", Command.BACK, 2);
    private final Command cmdRefresh = new Command("Refresh", Command.SCREEN, 3);
    private final Command cmdTone = new Command("Square wave test", Command.SCREEN, 4);
    private final Command cmdMidi = new Command("MIDI test", Command.SCREEN, 5);
    private final Command cmdInfo = new Command("About", Command.SCREEN, 5);
    private final Command cmdNow = new Command("Now playing", Command.OK, 1);
    private boolean advancedShown;

    private final Vector entries = new Vector();  // Entry

    private static class Entry {
        String name;
        String url;
        boolean dir;
        int size;
    }

    public FileListScreen(BrowserMIDlet midlet) {
        super("VGM Browser", List.IMPLICIT);
        this.midlet = midlet;
        setCommandListener(this);
        // 常驻命令只有右软键 Up；左软键随播放状态切换（命令数恰好 1 时
        // KEmulator 才把命令直接放到左软键，所以平时不挂 Refresh/About 等杂项）
        addCommand(cmdUp);
        addCommand(midlet.playerAlive() ? cmdNow : cmdOpen);
        // 高级命令常驻：多条命令时左软键自动变成菜单（KEmulator/S60 行为）
        addCommand(cmdRefresh);
        addCommand(cmdTone);
        addCommand(cmdMidi);
        addCommand(cmdInfo);
        refreshNowPlaying();
        // 模拟器直达 file:///root/vgm/；真机没有 root，静默失败回根列表
        if (!tryLoadDir("file:///root/vgm/")) {
            loadRoots();
        }
    }

    // ------------------------------------------------------------ 目录读取

    /** 播放状态变化后调用：左软键 = Now playing（播着）/ Open（没播）。 */
    void refreshNowPlaying() {
        boolean alive = midlet.playerAlive();
        removeCommand(cmdNow);
        removeCommand(cmdOpen);
        addCommand(alive ? cmdNow : cmdOpen);
    }

    /** 高级命令进 Menu（Refresh/About/Test），仅在需要时挂一次。 */
    private void showAdvanced() {
        if (!advancedShown) {
            advancedShown = true;
            addCommand(cmdRefresh);
            addCommand(cmdTone);
            addCommand(cmdInfo);
        }
    }

    private void loadRoots() {
        refreshNowPlaying();
        currentDir = null;
        entries.removeAllElements();
        deleteAll();
        removeCommand(cmdUp);

        int n = 0;
        try {
            java.util.Enumeration e = FileSystemRegistry.listRoots();
            while (e.hasMoreElements()) {
                String root = (String) e.nextElement();
                Entry en = new Entry();
                en.name = root;
                en.url = "file:///" + root;
                en.dir = true;
                entries.addElement(en);
                append(root, null);
                n++;
            }
        } catch (Throwable t) {
            // 某些模拟器/真机没有 JSR-75
        }

        if (n == 0) {
            // 常见根，兜底
            addRoot("C:/");
            addRoot("D:/");
            addRoot("E:/");
        }
        setTitle("VGM Browser");
    }

    private void addRoot(String r) {
        Entry en = new Entry();
        en.name = r;
        en.url = "file:///" + r;
        en.dir = true;
        entries.addElement(en);
        append(r, null);
    }

    /** 静默尝试打开目录：成功返回 true。仅供预设路径探测，不给用户弹错。 */
    private boolean tryLoadDir(String url) {
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(url, Connector.READ);
            if (!fc.exists() || !fc.isOpen()) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        } finally {
            close(fc);
        }
        loadDir(url);
        return entries.size() >= 0 && currentDir != null;
    }

    private void loadDir(String url) {
        refreshNowPlaying();
        entries.removeAllElements();
        deleteAll();
        currentDir = url;
        addCommand(cmdUp);

        int dirs = 0;
        int files = 0;
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(url, Connector.READ);
            if (!fc.exists()) {
                showAlert("Dir not found: " + url);
                return;
            }
            // 收集后排序：文件夹 → VGZ → VGM，组内字母序（忽略大小写）
            Vector dirsV = new Vector();
            Vector vgzV = new Vector();
            Vector vgmV = new Vector();
            java.util.Enumeration e = fc.list();
            while (e.hasMoreElements()) {
                String name = (String) e.nextElement();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                String childUrl = url.endsWith("/") ? url + name : url + "/" + name;
                boolean isDir = name.endsWith("/");
                Object[] pair = {name, childUrl};
                if (isDir) {
                    dirsV.addElement(pair);
                } else if (name.toLowerCase().endsWith(".vgz")) {
                    vgzV.addElement(pair);
                } else if (isVgm(name)) {
                    vgmV.addElement(pair);
                }
            }
            sortByName(dirsV);
            sortByName(vgzV);
            sortByName(vgmV);

            for (int i = 0; i < dirsV.size(); i++) {
                Object[] pair = (Object[]) dirsV.elementAt(i);
                String name = (String) pair[0];
                Entry en = new Entry();
                en.name = name;
                en.url = (String) pair[1];
                en.dir = true;
                append("[D] " + name, null);
                entries.addElement(en);
                dirs++;
            }
            for (int i = 0; i < vgzV.size(); i++) {
                Object[] pair = (Object[]) vgzV.elementAt(i);
                String name = (String) pair[0];
                Entry en = new Entry();
                en.name = name;
                en.url = (String) pair[1];
                en.dir = false;
                try {
                    en.size = (int) fcSize(en.url);
                } catch (Throwable t) {
                    en.size = 0;
                }
                append(formatSize(en.size) + "  " + name, null);
                entries.addElement(en);
                files++;
            }
            for (int i = 0; i < vgmV.size(); i++) {
                Object[] pair = (Object[]) vgmV.elementAt(i);
                String name = (String) pair[0];
                Entry en = new Entry();
                en.name = name;
                en.url = (String) pair[1];
                en.dir = false;
                try {
                    en.size = (int) fcSize(en.url);
                } catch (Throwable t) {
                    en.size = 0;
                }
                append(formatSize(en.size) + "  " + name, null);
                entries.addElement(en);
                files++;
            }
        } catch (Throwable t) {
            showAlert("Read failed: " + t);
        } finally {
            close(fc);
        }

        setTitle(currentDir == null ? "VGM Browser"
                : "VGM  " + dirs + " dirs / " + files + " files");
    }

    private static long fcSize(String url) {
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(url, Connector.READ);
            return fc.fileSize();
        } catch (Throwable t) {
            return -1;
        } finally {
            close(fc);
        }
    }

    /** Vector of Object[]{name,url} 按名字排序（忽略大小写，插入排序）。 */
    private static void sortByName(Vector v) {
        for (int i = 1; i < v.size(); i++) {
            Object[] key = (Object[]) v.elementAt(i);
            String kn = ((String) key[0]).toLowerCase();
            int j = i - 1;
            while (j >= 0) {
                String jn = ((String) ((Object[]) v.elementAt(j))[0]).toLowerCase();
                if (jn.compareTo(kn) <= 0) {
                    break;
                }
                v.setElementAt(v.elementAt(j), j + 1);
                j--;
            }
            v.setElementAt(key, j + 1);
        }
    }

    private static boolean isVgm(String name) {
        String l = name.toLowerCase();
        return l.endsWith(".vgm") || l.endsWith(".vgz");
    }

    private static void close(FileConnection fc) {
        if (fc != null) {
            try {
                fc.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String formatSize(int bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + "K";
        }
        return (bytes / (1024 * 1024)) + "M";
    }

    // ------------------------------------------------------------ 交互

    private void openSelected() {
        int idx = getSelectedIndex();
        if (idx < 0 || idx >= entries.size()) {
            return;
        }
        Entry en = (Entry) entries.elementAt(idx);
        if (en.dir) {
            loadDir(en.url);
        } else {
            loadFile(en);
        }
    }

    /** 播放器切曲用：按 entries 索引加载文件（只认 .vgm）。 */
    void openByIndex(int idx) {
        if (idx < 0 || idx >= entries.size()) {
            return;
        }
        Entry en = (Entry) entries.elementAt(idx);
        if (!en.dir) {
            loadFile(en, idx);
        }
    }

    int entryCount() {
        return entries.size();
    }

    private void loadFile(Entry en) {
        loadFile(en, -1);
    }

    private void loadFile(Entry en, int idx) {
        FileConnection fc = null;
        InputStream in = null;
        try {
            fc = (FileConnection) Connector.open(en.url, Connector.READ);
            in = fc.openInputStream();
            byte[] data = VgmFile.readAll(in);
            // 直接传原始字节进播放器：VGZ 解压在播放器内做（进度条兼做解压进度）
            if (idx >= 0) {
                midlet.openPlayRaw(en.name, data, idx);
            } else {
                int currentIdx = -1;
                for (int j = 0; j < entries.size(); j++) {
                    if (entries.elementAt(j) == en) {
                        currentIdx = j;
                        break;
                    }
                }
                midlet.openPlayRaw(en.name, data, currentIdx);
            }
        } catch (Throwable t) {
            showAlert("Parse failed: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            close(fc);
        }
    }

    private void goUp() {
        if (currentDir == null) {
            return;
        }
        String u = currentDir;
        if (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        // 去掉 "file:///" 后看剩余部分：根（C:、MemoryCard 等）直接回根列表；
        // 更深则截到上一级并保留尾斜杠（目录 URL 无尾斜杠在诺基亚上 invalid）
        String path = u.startsWith("file:///") ? u.substring(8) : u;
        int slash = path.indexOf('/');
        if (slash < 0 || slash == path.length() - 1) {
            loadRoots();
            return;
        }
        int cut = path.lastIndexOf('/');
        if (cut <= 0) {
            loadRoots();
        } else {
            loadDir("file:///" + path.substring(0, cut + 1));
        }
    }

    /** About: English info + CN/EN signature images (bundled in res). */
    private static final String NL = System.getProperty("line.separator");

    private void showAbout() {
        javax.microedition.lcdui.Form f = new javax.microedition.lcdui.Form("About");
        f.append("VgmBrowser v1.1.5" + NL);
        f.append("Real-time AY8910 VGM to MIDI converter with 88-key visualization." + NL);
        f.append("Dual-chip, VGZ support, full 256-entry VGM command table." + NL);
        f.append("References: libvgm, vgm2mid (Valley Bell), yasp11, v16, MeBoy, nescube." + NL + NL);
        try {
            f.append(javax.microedition.lcdui.Image.createImage("/sig_en.png"));
            f.append(NL);
            f.append(javax.microedition.lcdui.Image.createImage("/sig_cn.png"));
        } catch (Throwable t) {
            f.append("Denjhang / Dianjiang Open-Source Digital");
        }
        final FileListScreen self = this;
        Command back = new Command("Back", Command.BACK, 1);
        f.addCommand(back);
        f.setCommandListener(new javax.microedition.lcdui.CommandListener() {
            public void commandAction(Command c, javax.microedition.lcdui.Displayable d) {
                javax.microedition.lcdui.Display.getDisplay(midlet).setCurrent(self);
            }
        });
        javax.microedition.lcdui.Display.getDisplay(midlet).setCurrent(f);
    }

    private void showAlert(String msg) {
        Alert a = new Alert("Info", msg, null, AlertType.INFO);
        a.setTimeout(3000);
        javax.microedition.lcdui.Display.getDisplay(midlet).setCurrent(a, this);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdOpen || c == List.SELECT_COMMAND) {
            openSelected();
        } else if (c == cmdUp) {
            goUp();
        } else if (c == cmdRefresh) {
            if (currentDir == null) {
                loadRoots();
            } else {
                loadDir(currentDir);
            }
        } else if (c == cmdNow) {
            midlet.showPlayer();
        } else if (c == cmdTone) {
            midlet.openToneTest();
        } else if (c == cmdMidi) {
            midlet.openMidiTest();
        } else if (c == cmdInfo) {
            showAbout();
        }
    }
}
