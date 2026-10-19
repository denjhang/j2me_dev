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
    private final Command cmdInfo = new Command("About", Command.SCREEN, 5);
    private final Command cmdNow = new Command("Now playing", Command.SCREEN, 0);

    private final Vector entries = new Vector();  // Entry

    private static class Entry {
        String name;
        String url;
        boolean dir;
        int size;
    }

    public FileListScreen(BrowserMIDlet midlet) {
        super("VGM 浏览器", List.IMPLICIT);
        this.midlet = midlet;
        addCommand(cmdOpen);
        addCommand(cmdRefresh);
        addCommand(cmdTone);
        addCommand(cmdInfo);
        setCommandListener(this);
        refreshNowPlaying();
        // 直达模拟器文件系统里的 vgm 目录，省两次点击；失败再回根列表
        loadDir("file:///root/vgm/");
        if (entries.size() == 0) {
            loadRoots();
        }
    }

    // ------------------------------------------------------------ 目录读取

    /** 有后台播放时显示 Now playing 入口。 */
    void refreshNowPlaying() {
        if (midlet.playerAlive()) {
            addCommand(cmdNow);
        }
    }

    private void loadRoots() {
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
        setTitle("VGM 浏览器");
    }

    private void addRoot(String r) {
        Entry en = new Entry();
        en.name = r;
        en.url = "file:///" + r;
        en.dir = true;
        entries.addElement(en);
        append(r, null);
    }

    private void loadDir(String url) {
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
                showAlert("目录不存在: " + url);
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
            showAlert("读取失败: " + t);
        } finally {
            close(fc);
        }

        setTitle(currentDir == null ? "VGM 浏览器"
                : "VGM  " + dirs + " 目录 / " + files + " 文件");
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
            VgmFile vf = VgmFile.fromBytes(en.name, data);
            // 打开 VGM 直接进播放（用户定案），不走详情屏
            if (idx >= 0) {
                midlet.openPlay(vf, idx);
            } else {
                // 从列表双击打开：也直接播放，传当前索引
                int currentIdx = -1;
                for (int j = 0; j < entries.size(); j++) {
                    if (entries.elementAt(j) == en) {
                        currentIdx = j;
                        break;
                    }
                }
                midlet.openPlay(vf, currentIdx);
            }
        } catch (Throwable t) {
            showAlert("解析失败: " + t);
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
        int slash = u.lastIndexOf('/');
        if (slash <= 6) {          // "file://" 之后没有更上级
            loadRoots();
        } else {
            loadDir(u.substring(0, slash + 1));
        }
    }

    private void showAlert(String msg) {
        Alert a = new Alert("提示", msg, null, AlertType.INFO);
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
        } else if (c == cmdInfo) {
            Alert a = new Alert("关于",
                    "VGM 浏览器 v0.1 (解析/显示)\n"
                            + "支持 VGM 1.5x，完整 256 项命令表。\n"
                            + "第一阶段不做音频转换。",
                    null, AlertType.INFO);
            a.setTimeout(4000);
            javax.microedition.lcdui.Display.getDisplay(midlet).setCurrent(a, this);
        }
    }
}
