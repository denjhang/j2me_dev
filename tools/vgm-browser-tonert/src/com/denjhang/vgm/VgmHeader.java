package com.denjhang.vgm;

/**
 * VGM 文件头（0x00-0x100）。
 *
 * 偏移量以 VGM 1.5x 规范为准。注意几个"相对偏移"：
 *   - EOF 偏移是相对 0x04
 *   - GD3 偏移是相对 0x14
 *   - 循环点偏移是相对 0x1C
 *   - 数据区偏移是相对 0x34
 * 这些不是笔误，是 VGM 规范本身的设计。
 */
public class VgmHeader {

    public static final int MIN_SIZE = 0x40;

    /** 声明的芯片时钟，索引 = libvgm 的芯片 ID。0 表示未使用。 */
    public final int[] chipClock = new int[0x2A];
    /** 双芯片时的第二个时钟。 */
    public final int[] chipClock2 = new int[0x2A];
    /** AY8910 芯片类型（0=AY8910，1=YM2149 等）。 */
    public int ayChipType;
    public int ayFlags;
    /** 第二片 AY8910 的时钟（0=单芯片）。 */
    public int ayClock2;

    public int version;
    public int eofOffset;
    public int gd3Offset;
    public int totalSamples;
    public int loopOffset;
    public int loopSamples;
    public int dataOffset;
    /** 数据区结束位置（绝对偏移）。 */
    public int dataEnd;

    public int headerSize;

    /** GD3 标签（可能为空）。 */
    public String gd3Title = "";
    public String gd3TitleEn = "";
    public String gd3Game = "";
    public String gd3GameEn = "";
    public String gd3Artist = "";
    public String gd3ArtistEn = "";
    public String gd3Date = "";
    public String gd3Ripper = "";
    public String gd3Notes = "";

    public boolean hasLoop() {
        return loopOffset > 0 && loopSamples > 0;
    }

    public double durationSeconds() {
        return totalSamples / 44100.0;
    }

    public double loopSeconds() {
        return loopSamples / 44100.0;
    }

    public String versionString() {
        return (version >> 8) + "." + ((version & 0xFF) < 0x10 ? "0" : "")
                + Integer.toHexString(version & 0xFF);
    }

    public static VgmHeader parse(byte[] d) {
        if (d == null || d.length < 0x20) {
            throw new IllegalArgumentException("file too small, not a VGM");
        }
        if (d[0] != 'V' || d[1] != 'g' || d[2] != 'm' || d[3] != ' ') {
            throw new IllegalArgumentException("missing Vgm magic");
        }

        VgmHeader h = new VgmHeader();
        h.version = u32(d, 0x08);
        h.eofOffset = u32(d, 0x04);
        h.gd3Offset = u32(d, 0x14);
        h.totalSamples = u32(d, 0x18);
        h.loopOffset = u32(d, 0x1C);
        h.loopSamples = u32(d, 0x20);
        h.dataOffset = u32(d, 0x34);

        // 相对偏移还原成绝对偏移
        if (h.eofOffset != 0) {
            h.eofOffset += 0x04;
        }
        if (h.gd3Offset != 0) {
            h.gd3Offset += 0x14;
        }
        if (h.loopOffset != 0) {
            h.loopOffset += 0x1C;
        }
        if (h.dataOffset != 0) {
            h.dataOffset += 0x34;
        } else {
            h.dataOffset = 0x40;
        }

        h.dataEnd = h.eofOffset != 0 ? h.eofOffset : d.length;
        if (h.dataEnd > d.length) {
            h.dataEnd = d.length;
        }

        h.headerSize = h.dataOffset < 0x40 ? 0x40 : h.dataOffset;

        // 芯片时钟：逐字段按 libvgm 的 SoundDevs 偏移表取
        readClocks(d, h);

        // AY8910 附加字段：0x78=第二片时钟，0x7C=芯片类型，0x7D=标志
        if (d.length > 0x78) {
            h.ayClock2 = u32(d, 0x78);
        }
        if (d.length > 0x7D) {
            h.ayChipType = d[0x7C] & 0xFF;
            h.ayFlags = d[0x7D] & 0xFF;
        }

        if (h.gd3Offset > 0 && h.gd3Offset + 12 <= d.length) {
            parseGd3(d, h);
        }
        return h;
    }

    /**
     * 芯片时钟读取表，照抄 libvgm 的 _CHIPCLK_OFS 与 _DEV_LIST：
     * 两个数组按下标一一对应（注意 RF5C68 出现两次、顺序与 DEVID 不同）。
     */
    private static final int[] CLK_OFS = {
        0x0C, 0x10, 0x2C, 0x30, 0x38, 0x40, 0x44, 0x48,
        0x4C, 0x50, 0x54, 0x58, 0x5C, 0x60, 0x64, 0x68,
        0x6C, 0x70, 0x74, 0x80, 0x84, 0x88, 0x8C, 0x90,
        0x98, 0x9C, 0xA0, 0xA4, 0xA8, 0xAC, 0xB0, 0xB4,
        0xB8, 0xC0, 0xC4, 0xC8, 0xCC, 0xD0, 0xD8, 0xDC,
        0xE0, 0xE4
    };

    private static final int[] CLK_DEV = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x05, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
        0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
        0x28, 0x29
    };

    private static void readClocks(byte[] d, VgmHeader h) {
        // 关键：头部扩展字段只存在于数据区之前。
        // 数据区从 0x40/0x80 开始时，0x80 之后的"时钟字段"其实是 VGM 命令字节，
        // 读出来全是无意义的噪声，所以必须在 dataOffset 处截断。
        int limit = h.dataOffset;
        if (limit > d.length) {
            limit = d.length;
        }
        for (int i = 0; i < CLK_OFS.length; i++) {
            int id = CLK_DEV[i];
            if (id >= h.chipClock.length) {
                continue;
            }
            if (CLK_OFS[i] + 4 <= limit) {
                int c = u32(d, CLK_OFS[i]);
                // RF5C68 在表里出现两次（0x40 和 0x6C），只取第一个非零值
                if (h.chipClock[id] == 0) {
                    h.chipClock[id] = c;
                }
            }
        }
    }

    private static void parseGd3(byte[] d, VgmHeader h) {
        int p = h.gd3Offset;
        if (d[p] != 'G' || d[p + 1] != 'd' || d[p + 2] != '3' || d[p + 3] != ' ') {
            return;
        }
        int size = u32(d, p + 8);
        int end = p + 12 + size;
        if (end > d.length) {
            end = d.length;
        }
        p += 12;
        String[] fields = new String[11];
        for (int i = 0; i < fields.length && p < end; i++) {
            fields[i] = readUtf16z(d, p, end);
            p += (fields[i].length() + 1) * 2;
        }
        h.gd3Title = nz(fields[0]);
        h.gd3TitleEn = nz(fields[1]);
        h.gd3Game = nz(fields[2]);
        h.gd3GameEn = nz(fields[3]);
        h.gd3Artist = nz(fields[6]);
        h.gd3ArtistEn = nz(fields[7]);
        h.gd3Date = nz(fields[8]);
        h.gd3Ripper = nz(fields[9]);
        h.gd3Notes = nz(fields[10]);
    }

    private static String readUtf16z(byte[] d, int p, int end) {
        StringBuffer sb = new StringBuffer();
        while (p + 1 < end) {
            int c = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
            if (c == 0) {
                break;
            }
            sb.append((char) c);
            p += 2;
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static int u32(byte[] d, int off) {
        if (off + 4 > d.length) {
            return 0;
        }
        return (d[off] & 0xFF)
                | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16)
                | ((d[off + 3] & 0xFF) << 24);
    }
}
