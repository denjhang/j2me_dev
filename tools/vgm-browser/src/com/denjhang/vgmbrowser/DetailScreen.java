package com.denjhang.vgmbrowser;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;

import com.denjhang.vgm.VgmCommandTable;
import com.denjhang.vgm.VgmFile;
import com.denjhang.vgm.VgmHeader;

/**
 * 文件详情：头部字段 + 芯片 + 统计。
 */
public class DetailScreen extends Form implements CommandListener {

    private final BrowserMIDlet midlet;
    private final VgmFile file;

    private final Command cmdBack = new Command("返回", Command.BACK, 1);
    private final Command cmdCmd = new Command("命令流", Command.SCREEN, 2);
    private final Command cmdHex = new Command("头部十六进制", Command.SCREEN, 3);
    private final Command cmdPlay = new Command("实时播放(MIDI)", Command.OK, 2);

    public DetailScreen(BrowserMIDlet midlet, VgmFile file) {
        super("详情");
        this.midlet = midlet;
        this.file = file;

        addCommand(cmdBack);
        addCommand(cmdPlay);
        addCommand(cmdCmd);
        addCommand(cmdHex);
        setCommandListener(this);

        build();
    }

    private void build() {
        VgmHeader h = file.header;

        String title = h.gd3Title.length() > 0 ? h.gd3Title : h.gd3TitleEn;
        if (title.length() > 0) {
            append(title + "\n");
        }
        append("文件: " + file.name + "\n");
        append("大小: " + file.byteSize + " 字节\n");
        append("版本: VGM " + h.versionString() + "\n\n");

        append("时长: " + fmt(h.durationSeconds()) + "\n");
        if (h.hasLoop()) {
            double loopStart = (h.totalSamples - h.loopSamples) / 44100.0;
            append("循环: " + fmt(loopStart) + "s 起, 长 "
                    + fmt(h.loopSeconds()) + "s (0x" + hex(h.loopOffset) + ")\n");
        } else {
            append("循环: 无\n");
        }
        append("总采样: " + h.totalSamples + "\n\n");

        append("数据区: 0x" + hex(h.dataOffset)
                + " - 0x" + hex(h.dataEnd) + "\n");
        append("头部长度: 0x" + hex(h.headerSize) + "\n\n");

        // 芯片
        append("芯片:\n");
        int[] used = usedChips(h);
        if (used.length == 0) {
            append("  (头部未声明)\n");
        } else {
            for (int i = 0; i < used.length; i++) {
                int id = used[i];
                int clk = h.chipClock[id];
                double mhz = clk / 1000000.0;
                append("  " + VgmCommandTable.chipName(id)
                        + "  " + clk + " Hz");
                if (mhz >= 1.0) {
                    append(" (" + fmt(mhz) + " MHz)");
                }
                append("\n");
            }
        }
        if (h.chipClock[0x12] != 0) {
            append("  AY 类型: " + h.ayChipType
                    + ", flags: " + h.ayFlags + "\n");
        }
        append("\n");

        // GD3 其他字段
        if (h.gd3Game.length() > 0 || h.gd3GameEn.length() > 0) {
            append("游戏: " + pick(h.gd3Game, h.gd3GameEn) + "\n");
        }
        if (h.gd3Artist.length() > 0 || h.gd3ArtistEn.length() > 0) {
            append("作者: " + pick(h.gd3Artist, h.gd3ArtistEn) + "\n");
        }
        if (h.gd3Date.length() > 0) {
            append("日期: " + h.gd3Date + "\n");
        }
        if (h.gd3Ripper.length() > 0) {
            append("抓取: " + h.gd3Ripper + "\n");
        }

        append("\n(点\"命令流\"查看解析结果)");
    }

    private static String pick(String a, String b) {
        return a != null && a.length() > 0 ? a : b;
    }

    private static int[] usedChips(VgmHeader h) {
        int n = 0;
        for (int i = 0; i < h.chipClock.length; i++) {
            if (h.chipClock[i] != 0) {
                n++;
            }
        }
        int[] r = new int[n];
        int k = 0;
        for (int i = 0; i < h.chipClock.length; i++) {
            if (h.chipClock[i] != 0) {
                r[k++] = i;
            }
        }
        return r;
    }

    private static String fmt(double v) {
        int scaled = (int) (v * 10 + 0.5);
        return (scaled / 10) + "." + (scaled % 10);
    }

    private static String hex(int v) {
        String s = Integer.toHexString(v).toUpperCase();
        return s;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdBack) {
            midlet.backToList();
        } else if (c == cmdPlay) {
            midlet.openPlay(file, -1);
        } else if (c == cmdCmd) {
            midlet.openCommands(file);
        } else if (c == cmdHex) {
            showHex();
        }
    }

    private void showHex() {
        Form f = new Form("头部 hex");
        int n = file.header.headerSize;
        if (n > 0x100) {
            n = 0x100;
        }
        for (int off = 0; off < n; off += 32) {
            int len = Math.min(32, n - off);
            String line = pad(hex(off), 4) + "  " + file.hexAt(off, len, len);
            f.append(line + "\n");
        }
        f.append("");
        f.addCommand(new Command("返回", Command.BACK, 1));
        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                midlet.openDetail(file);
            }
        });
        javax.microedition.lcdui.Display.getDisplay(midlet).setCurrent(f);
    }

    private static String pad(String s, int n) {
        StringBuffer sb = new StringBuffer(s);
        while (sb.length() < n) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }
}
