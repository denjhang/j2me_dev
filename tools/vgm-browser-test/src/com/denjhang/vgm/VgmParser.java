package com.denjhang.vgm;

import java.util.Hashtable;

/**
 * VGM 命令解析器：按完整 256 项命令表逐条走，产出结构化事件。
 *
 * 第一阶段只做解析和统计，不做任何音频转换。
 * 未知/无效命令也要按表里的长度安全跳过，不能崩、不能错位。
 */
public class VgmParser {

    public static final int EV_REG_WRITE = 0;
    public static final int EV_WAIT = 1;
    public static final int EV_DATA_BLOCK = 2;
    public static final int EV_END = 3;
    public static final int EV_UNKNOWN = 4;
    public static final int EV_RAM_WRITE = 5;

    /** 单个数据块信息（只记录元数据，不保留内容）。 */
    public static class DataBlock {
        public int offset;
        public int type;
        public int chipId;
        public int size;
        public int romSize;
    }

    private byte[] data;
    private final VgmHeader header;

    private int pos;
    private long samples;
    private int commandCount;
    private boolean ended;

    /** 每条命令出现次数（按命令字节）。 */
    public final int[] cmdCount = new int[256];
    /** 每种芯片的寄存器写次数（按芯片 ID）。 */
    public final int[] chipWrites = new int[0x2A];
    /** AY8910 每个寄存器被写次数。 */
    public final int[] ayRegCount = new int[16];
    /** 数据块列表。 */
    public final java.util.Vector dataBlocks = new java.util.Vector();
    /** 未知命令统计：命令字节 -> 次数。 */
    public final Hashtable unknownCmds = new Hashtable();
    /** 因为长度表为 0 而跳过失败（可能错位）的次数。 */
    public int badCmdCount;

    public VgmParser(byte[] data, VgmHeader header) {
        this.data = data;
        this.header = header;
        this.pos = header.dataOffset;
        this.samples = 0;
    }

    /** 文件模式：转换完成/落盘后释放 VGM 原始字节（parser 不可再用）。 */
    public void release() {
        data = new byte[0];
    }

    public VgmHeader getHeader() {
        return header;
    }

    public long getSamples() {
        return samples;
    }

    public double getSeconds() {
        return samples / 44100.0;
    }

    public int getPosition() {
        return pos;
    }

    public boolean isEnded() {
        return ended;
    }

    public int getCommandCount() {
        return commandCount;
    }

    public void seek(int absoluteOffset) {
        pos = absoluteOffset;
        ended = false;
    }

    public void reset() {
        pos = header.dataOffset;
        samples = 0;
        ended = false;
    }

    // 事件输出的目标，允许外部每次取一条
    public int evType;
    public int evCmd;
    public int evChip;
    public int evReg;
    public int evData;
    public int evWait;
    public int evSeq;          // 命令在文件中的序号
    public int evOffset;       // 命令字节的文件偏移

    /**
     * 解析下一条命令。返回事件类型；文件结束时返回 EV_END 并置 ended。
     */
    public int next() {
        if (ended || pos >= header.dataEnd || pos >= data.length) {
            ended = true;
            evType = EV_END;
            return EV_END;
        }

        evOffset = pos;
        evSeq = commandCount;
        int cmd = data[pos] & 0xFF;
        evCmd = cmd;
        cmdCount[cmd]++;
        commandCount++;

        int len = VgmCommandTable.length(cmd);
        int chip = VgmCommandTable.chip(cmd);
        evChip = chip;

        // 变长命令先处理
        if (cmd == 0x67) {
            return parseDataBlock();
        }

        // 长度 0：无效或未实现，按 libvgm 的做法前进 1 字节
        if (len <= 0) {
            badCmdCount++;
            Integer c = (Integer) unknownCmds.get(new Integer(cmd));
            unknownCmds.put(new Integer(cmd), new Integer(c == null ? 1 : c.intValue() + 1));
            pos += 1;
            evType = EV_UNKNOWN;
            return EV_UNKNOWN;
        }

        // 命令长度超出数据区：截断保护
        if (pos + len > header.dataEnd) {
            ended = true;
            evType = EV_END;
            return EV_END;
        }

        evReg = len >= 2 ? (data[pos + 1] & 0xFF) : 0;
        evData = len >= 3 ? (data[pos + 2] & 0xFF) : 0;
        evWait = 0;

        // 等待类命令累加采样
        switch (cmd) {
            case 0x61:
                evWait = (data[pos + 1] & 0xFF) | ((data[pos + 2] & 0xFF) << 8);
                samples += evWait;
                pos += len;
                evType = EV_WAIT;
                return EV_WAIT;
            case 0x62:
                evWait = 735;
                samples += 735;
                pos += len;
                evType = EV_WAIT;
                return EV_WAIT;
            case 0x63:
                evWait = 882;
                samples += 882;
                pos += len;
                evType = EV_WAIT;
                return EV_WAIT;
            case 0x66:
                ended = true;
                pos += len;
                evType = EV_END;
                return EV_END;
            default:
                break;
        }

        // 0x70-0x7F：等待 n+1 采样
        if (cmd >= 0x70 && cmd <= 0x7F) {
            evWait = (cmd & 0x0F) + 1;
            samples += evWait;
            pos += len;
            evType = EV_WAIT;
            return EV_WAIT;
        }

        // 0x80-0x8F：YM2612 DAC + 等待
        if (cmd >= 0x80 && cmd <= 0x8F) {
            evWait = cmd & 0x0F;
            samples += evWait;
            pos += len;
            evType = EV_WAIT;
            return EV_WAIT;
        }

        // 0x68 PCM RAM write
        if (cmd == 0x68) {
            pos += len;
            evType = EV_RAM_WRITE;
            return EV_RAM_WRITE;
        }

        // 其余命令（含寄存器写）
        pos += len;

        if (chip != VgmCommandTable.NONE && chip < chipWrites.length) {
            chipWrites[chip]++;
        }

        // AY8910 寄存器统计（0xA0 = AY 写，0x51 = YM2413 等不混淆）
        if (cmd == 0xA0 && evReg < ayRegCount.length) {
            ayRegCount[evReg]++;
        }

        evType = EV_REG_WRITE;
        return EV_REG_WRITE;
    }

    private int parseDataBlock() {
        // 0x67 0x66 tt ss ss ss ss [data...]
        if (pos + 7 > data.length) {
            ended = true;
            evType = EV_END;
            return EV_END;
        }
        DataBlock b = new DataBlock();
        b.offset = pos;
        b.type = data[pos + 2] & 0xFF;
        b.size = VgmHeader.u32(data, pos + 3);

        // type 0xC0-0xDF：压缩 ROM，多 4 字节 ROM 大小
        boolean hasRom = (b.type >= 0x40 && b.type <= 0x5F) || (b.type >= 0xC0 && b.type <= 0xDF);
        int total = 7 + b.size + (hasRom ? 4 : 0);
        if (hasRom && pos + 11 <= data.length) {
            b.romSize = VgmHeader.u32(data, pos + 7);
        }
        if (pos + total > header.dataEnd) {
            ended = true;
            evType = EV_END;
            return EV_END;
        }

        dataBlocks.addElement(b);
        pos += total;
        evType = EV_DATA_BLOCK;
        return EV_DATA_BLOCK;
    }

    /**
     * 把整首曲子的命令全跑一遍，收集统计。用于浏览器的"概览"页。
     */
    public void scanAll() {
        reset();
        while (!ended) {
            next();
        }
    }

    /** 已用到哪些芯片（按时钟非零）。 */
    public int[] usedChips() {
        int n = 0;
        for (int i = 0; i < header.chipClock.length; i++) {
            if (header.chipClock[i] != 0) {
                n++;
            }
        }
        int[] r = new int[n];
        int k = 0;
        for (int i = 0; i < header.chipClock.length; i++) {
            if (header.chipClock[i] != 0) {
                r[k++] = i;
            }
        }
        return r;
    }
}
