package com.denjhang.vgmbrowser;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;

import com.denjhang.vgm.VgmCommandTable;
import com.denjhang.vgm.VgmFile;
import com.denjhang.vgm.VgmParser;

/**
 * 命令流查看器：逐条显示解析出的 VGM 命令。
 *
 * 用 Form 的分页思想 —— MIDP 的 Form 天然支持滚动和字段追加，
 * 一次性渲染太多 Item 会慢，所以每屏只列 100 条，滚动按钮翻页。
 */
public class CommandScreen extends Form implements CommandListener {

    private static final int PAGE = 100;

    private final BrowserMIDlet midlet;
    private final VgmFile file;

    private final Command cmdBack = new Command("Back", Command.BACK, 1);
    private final Command cmdNext = new Command("Next page", Command.SCREEN, 2);
    private final Command cmdPrev = new Command("Prev page", Command.SCREEN, 3);
    private final Command cmdScan = new Command("Statistics", Command.SCREEN, 4);

    private int pageIndex = 0;
    private int totalCommands;

    public CommandScreen(BrowserMIDlet midlet, VgmFile file) {
        super("Command stream");
        this.midlet = midlet;
        this.file = file;
        addCommand(cmdBack);
        addCommand(cmdNext);
        addCommand(cmdPrev);
        addCommand(cmdScan);
        setCommandListener(this);
        refreshPage();
    }

    private void refreshPage() {
        deleteAll();
        append("Command stream (parser)\n");
        append("----\n");

        VgmParser p = file.getParser();
        p.reset();

        int start = pageIndex * PAGE;
        int shown = 0;
        int skipped = 0;

        // 跳过到本页起点
        while (skipped < start && !p.isEnded()) {
            p.next();
            skipped++;
        }

        StringBuffer pageText = new StringBuffer();
        int n = 0;
        while (n < PAGE && !p.isEnded()) {
            int t = p.next();
            pageText.append(fmtEvent(p, t));
            n++;
        }
        totalCommands = countTotal();
        pageText.append("\n--- Page " + (pageIndex + 1) + " of "
                + ((totalCommands + PAGE - 1) / PAGE) + " pages ---\n");

        // Form 一次 append 一个长 String 比 append 很多 StringItem 快
        append(pageText.toString());
    }

    private int countTotal() {
        VgmParser p = file.getParser();
        p.reset();
        int n = 0;
        while (!p.isEnded()) {
            p.next();
            n++;
        }
        return n;
    }

    private String fmtEvent(VgmParser p, int t) {
        StringBuffer sb = new StringBuffer();
        int cmd = p.evCmd;
        String nm = VgmCommandTable.name(cmd);
        sb.append(pad(hex4(p.evOffset), 6));
        sb.append(' ');
        sb.append(pad(hex2(cmd), 2));
        sb.append(' ');
        sb.append(nm);

        switch (t) {
            case VgmParser.EV_REG_WRITE:
                sb.append("  reg=").append(pad(hex2(p.evReg), 2))
                        .append(" data=").append(pad(hex2(p.evData), 2));
                if (VgmCommandTable.chip(cmd) != VgmCommandTable.NONE) {
                    sb.append("  chip=")
                            .append(VgmCommandTable.chipName(VgmCommandTable.chip(cmd)));
                }
                break;
            case VgmParser.EV_WAIT:
                sb.append("  +").append(p.evWait).append(" smp");
                break;
            case VgmParser.EV_DATA_BLOCK:
                VgmParser.DataBlock b = (VgmParser.DataBlock) file.getParser().dataBlocks
                        .elementAt(file.getParser().dataBlocks.size() - 1);
                sb.append("  type=").append(hex2(b.type))
                        .append(" size=").append(b.size);
                break;
            case VgmParser.EV_END:
                sb.append("  <<END>>");
                break;
            case VgmParser.EV_UNKNOWN:
                sb.append("  (invalid/unknown)");
                break;
            default:
                break;
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String pad(String s, int n) {
        StringBuffer sb = new StringBuffer(s);
        while (sb.length() < n) {
            sb.insert(0, ' ');
        }
        return sb.toString();
    }

    private static String hex2(int v) {
        String s = Integer.toHexString(v & 0xFF).toUpperCase();
        if (s.length() < 2) {
            s = "0" + s;
        }
        return s;
    }

    private static String hex4(int v) {
        return Integer.toHexString(v).toUpperCase();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdBack) {
            midlet.openDetail(file);
        } else if (c == cmdNext) {
            pageIndex++;
            refreshPage();
        } else if (c == cmdPrev) {
            if (pageIndex > 0) {
                pageIndex--;
            }
            refreshPage();
        } else if (c == cmdScan) {
            showScan();
        }
    }

    private void showScan() {
        Form f = new Form("Statistics");
        VgmParser p = file.getParser();
        p.scanAll();

        f.append("Total commands: " + p.getCommandCount() + "\n");
        f.append("Parsed length: " + fmt(p.getSeconds()) + "s\n");
        f.append("Unknown/invalid commands: " + p.badCmdCount + "\n");
        f.append("Data blocks: " + p.dataBlocks.size() + "\n\n");

        f.append("Command counts:\n");
        for (int i = 0; i < 256; i++) {
            if (p.cmdCount[i] > 0) {
                f.append("  " + hex2(i) + " " + VgmCommandTable.name(i)
                        + ": " + p.cmdCount[i] + "\n");
            }
        }

        f.append("\nAY8910 register writes:\n");
        boolean any = false;
        for (int i = 0; i < p.ayRegCount.length; i++) {
            if (p.ayRegCount[i] > 0) {
                f.append("  R" + hex2(i) + ": " + p.ayRegCount[i] + "\n");
                any = true;
            }
        }
        if (!any) {
            f.append("  (none)\n");
        }

        f.addCommand(new Command("Back", Command.BACK, 1));
        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                javax.microedition.lcdui.Display.getDisplay(midlet)
                        .setCurrent(CommandScreen.this);
            }
        });
        javax.microedition.lcdui.Display.getDisplay(midlet).setCurrent(f);
    }

    private static String fmt(double v) {
        int scaled = (int) (v * 10 + 0.5);
        return (scaled / 10) + "." + (scaled % 10);
    }
}
