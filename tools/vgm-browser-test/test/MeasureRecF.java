/**
 * 离线无头测量：VGM → MIDI 转换后，recF / regF / VGM 原始字节各占多少。
 *
 * 用法（JDK8 桌面，不启动 KEmulator）：
 *   cd E:\working\zcode-projects\j2me_dev
 *   tools/jdk8/bin/javac -encoding UTF-8 \
 *     -cp tools/vgm-browser-test/classes \
 *     -d tools/vgm-browser-test/test-classes \
 *     tools/vgm-browser-test/test/MeasureRecF.java
 *   tools/jdk8/bin/java -cp tools/vgm-browser-test/classes;tools/vgm-browser-test/test-classes \
 *     com.denjhang.vgm.test.MeasureRecF
 *
 * 原理：
 *   Ay2Midi.RECORD_ONLY=true → init() 不开音频，send() 只录 recF
 *   recordingReg=true          → write() 同时录 regF
 *   VgmFile.fromBytes()        → 解压+解析 VGM 头
 *   VgmParser.next()           → 逐事件驱动转换
 */
package com.denjhang.vgm.test;

import com.denjhang.vgm.Ay2Midi;
import com.denjhang.vgm.VgmFile;
import com.denjhang.vgm.VgmParser;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;

public class MeasureRecF {

    public static void main(String[] args) throws Exception {
        // 强制纯录制模式——不开任何音频设备
        Ay2Midi.RECORD_ONLY = true;

        File dir = new File(
            "E:\\working\\zcode-projects\\j2me_dev\\emulators\\kemnnx64\\file\\root\\vgm\\2025-Turbosound");
        if (!dir.isDirectory()) {
            System.err.println("目录不存在: " + dir.getAbsolutePath());
            return;
        }

        File[] files = dir.listFiles((d, name) ->
            name.toLowerCase().endsWith(".vgm") || name.toLowerCase().endsWith(".vgz"));
        if (files == null || files.length == 0) {
            System.err.println("目录下没有 .vgm/.vgz 文件");
            return;
        }
        Arrays.sort(files);

        System.out.println("================================================================");
        System.out.printf("%-55s %8s %10s %10s %8s %8s %6s%n",
            "文件", "VGM_KB", "recF_KB", "regF_KB", "recN", "regN", "芯片");
        System.out.println("================================================================");

        long totalVgm = 0, totalRecF = 0, totalRegF = 0;
        int dualChip = 0;

        for (File f : files) {
            byte[] raw = readAll(f);
            totalVgm += raw.length;

            // 加载并解析 VGM（不解压 VGZ 在这里——VgmFile.fromBytes 内部处理）
            VgmFile vf = VgmFile.fromBytes(f.getName(), raw);

            // chip0：主 AY8910
            Ay2Midi gen = new Ay2Midi();
            gen.RECORD_ONLY = true;  // 确保构造后也是录制模式
            gen.recording = true;
            gen.recordingReg = true;
            gen.recBaseMs = 0;
            gen.vtime = 0;

            // chip1：第二片 AY8910（双芯片曲才用到）
            Ay2Midi gen2 = null;
            boolean hasChip2 = false;

            VgmParser parser = vf.parser;
            while (true) {
                int t = parser.next();
                if (t == VgmParser.EV_END) {
                    if (vf.header.hasLoop()) {
                        // 只跑一遍，不循环（测单遍转换量）
                        break;
                    }
                    break;
                }
                if (t == VgmParser.EV_REG_WRITE && parser.evCmd == 0xA0) {
                    long ms = parser.getSamples() / 44;
                    gen.vtime = ms;
                    if ((parser.evReg & 0x80) != 0) {
                        // 第二片芯片
                        if (!hasChip2) {
                            hasChip2 = true;
                            gen2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);
                            gen2.RECORD_ONLY = true;
                            gen2.recording = true;
                            gen2.recordingReg = true;
                            gen2.recBaseMs = 0;
                            gen2.vtime = ms;
                        }
                        if (gen2 != null) {
                            gen2.write(parser.evReg & 0x7F, parser.evData);
                        }
                    } else {
                        gen.write(parser.evReg, parser.evData);
                    }
                }
                gen.tick();
                if (gen2 != null) {
                    gen2.tick();
                }
            }

            // --- 测量 ---
            int recFBytes = gen.recN * 4 * 8;       // recF 是 long[]，每元素 8 字节
            int regFBytes = gen.regN * 3 * 8;        // regF 是 long[]，每元素 8 字节
            // 数组实际分配含扩容尾巴
            int recFAlloc = gen.recF.length * 8;
            int regFAlloc = gen.regF.length * 8;

            int recF2Bytes = 0, regF2Bytes = 0, recF2Alloc = 0, regF2Alloc = 0;
            if (gen2 != null) {
                recF2Bytes = gen2.recN * 4 * 8;
                regF2Bytes = gen2.regN * 3 * 8;
                recF2Alloc = gen2.recF.length * 8;
                regF2Alloc = gen2.regF.length * 8;
                dualChip++;
            }

            String tag = hasChip2 ? "双" : "单";
            System.out.printf("%-55s %8d %10d %10d %8d %8d %6s%n",
                f.getName(),
                raw.length / 1024,
                (recFBytes + recF2Bytes) / 1024,
                (regFBytes + regF2Bytes) / 1024,
                gen.recN + (gen2 != null ? gen2.recN : 0),
                gen.regN + (gen2 != null ? gen2.regN : 0),
                tag);

            totalRecF += recFBytes + recF2Bytes;
            totalRegF += regFBytes + regF2Bytes;
        }

        System.out.println("================================================================");
        System.out.printf("%-55s %8d %10d %10d         %d 曲 (%d 双芯)%n",
            "合计",
            totalVgm / 1024,
            totalRecF / 1024,
            totalRegF / 1024,
            files.length,
            dualChip);
        System.out.println();
        System.out.println("--- 含扩容尾巴的数组实际堆占用 ---");
        System.out.printf("  recF 总分配 ≈ %d KB  (有效数据 %d KB)%n",
            totalRecF * 2 / 1024, totalRecF / 1024);  // 尾巴通常 ≤ 有效数据
        System.out.printf("  regF 总分配 ≈ %d KB  (有效数据 %d KB)%n",
            totalRegF * 2 / 1024, totalRegF / 1024);
        System.out.println();
        System.out.println("--- 阶段 D 预期收益 ---");
        System.out.printf("  删 regF 录制 + 释放 VGM 字节 + 释放解析器");
        System.out.printf("  → 每曲省 ~%d KB (regF) + %d KB (VGM原始) = ~%d KB%n",
            totalRegF / 1024, totalVgm / 1024,
            (totalRegF + totalVgm) / 1024);
        System.out.printf("  recF 保留（播放用），常驻 ~%d KB%n", totalRecF / 1024);
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) throw new java.io.IOException("unexpected EOF");
                off += n;
            }
            return buf;
        } finally {
            in.close();
        }
    }
}
