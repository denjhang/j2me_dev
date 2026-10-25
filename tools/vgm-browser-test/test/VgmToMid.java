/**
 * 无头 VGM → MID 转换器（PC 桌面，JDK8，不启动 KEmulator）。
 *
 * 目的：用当前线上转换逻辑把 VGM 转成标准 .mid，量产出字节数，
 *       对比 Valley Bell vgm2mid，查 MIDI 为何被撑到几兆级。
 *
 * 配置：从 KEmulator 的 RMS 配置文件读 v[]（和 PlayScreen.applyPersisted 同源），
 *       全部参数（音色/声像/音量/单音/八度/包络/蜂鸣器等）按播放器同样顺序应用到 ay，
 *       再 copyConfigFrom(ay) 给 gen —— 与真实播放的配置链完全一致。
 *
 * 用法：
 *   tools/jdk8/bin/javac -encoding UTF-8 -source 8 -target 8 \
 *     -cp tools/vgm-browser-test/classes;emulators/kemnnx64/uei/jsr135.jar;references/midplay/MIDPlay-main/lib/cldc_1.1.jar;references/midplay/MIDPlay-main/lib/midp_2.0.jar \
 *     -d tools/vgm-browser-test/test-classes \
 *     tools/vgm-browser-test/test/VgmToMid.java
 *   tools/jdk8/bin/java -cp tools/vgm-browser-test/classes;tools/vgm-browser-test/test-classes;emulators/kemnnx64/uei/jsr135.jar;references/midplay/MIDPlay-main/lib/cldc_1.1.jar;references/midplay/MIDPlay-main/lib/midp_2.0.jar \
 *     com.denjhang.vgm.test.VgmToMid [输出目录] [输入vgm或目录]
 *
 * 不带参数：跑 emulators/kemnnx64/file/root/vgm/2025-Turbosound 全目录，输出到 tools/vgm-browser-test/test-out/mid。
 */
package com.denjhang.vgm.test;

import com.denjhang.vgm.Ay2Midi;
import com.denjhang.vgm.VgmFile;
import com.denjhang.vgm.VgmParser;
import com.denjhang.vgm.SmfSeg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;

public class VgmToMid {

    // RMS 配置路径（与 SettingsStore.RS_NAME 对应的 KEmulator 虚拟 FS）
    static final String RMS_CFG =
        "emulators/kemnnx64/rms/RGVuamhhbmdfVmdtQnJvd3NlclRlc3Q=/dmdtYnJvd3Nlcl9jZmc=";

    public static void main(String[] args) throws Exception {
        Ay2Midi.RECORD_ONLY = true;   // 纯录制，不开任何音频

        String inPath = args.length >= 2 ? args[1]
            : "emulators/kemnnx64/file/root/vgm/2025-Turbosound";
        File outDir = new File(args.length >= 1 ? args[0]
            : "tools/vgm-browser-test/test-out/mid");
        outDir.mkdirs();

        // 1. 加载 RMS 配置 → v[]
        int[] v = loadRmsConfig();
        System.out.println("[cfg] v[] = " + (v == null ? "(null→默认)" : Arrays.toString(v)));

        File inFile = new File(inPath);
        File[] files;
        if (inFile.isFile()) {
            files = new File[] { inFile };
        } else {
            files = inFile.listFiles((d, n) ->
                n.toLowerCase().endsWith(".vgm") || n.toLowerCase().endsWith(".vgz"));
            if (files == null) files = new File[0];
            Arrays.sort(files);
        }

        System.out.println("================================================================");
        System.out.printf("%-55s %8s %10s %10s %8s %6s%n",
            "文件", "VGM_KB", "MID_KB", "recF_KB", "事件数", "芯片");
        System.out.println("================================================================");

        long totVgm = 0, totMid = 0;
        int dual = 0;

        for (File f : files) {
            byte[] raw = readAll(f);
            totVgm += raw.length;
            VgmFile vf = VgmFile.fromBytes(f.getName(), raw);

            // 2. 配置源 ay（按 applyPersisted 全套应用）
            Ay2Midi ay = new Ay2Midi();           // chip0
            Ay2Midi ay2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);  // chip1
            applyConfig(ay, ay2, v);

            // 3. 录制 gen（copyConfigFrom 同真实播放链）
            Ay2Midi gen = new Ay2Midi();
            gen.RECORD_ONLY = true;
            int clk = vf.header.chipClock[0x12] & 0x3FFFFFFF;
            if (clk != 0) gen.setClock(clk);
            gen.copyConfigFrom(ay);
            gen.recording = true;
            gen.recordingReg = false;   // 出 .mid 不需要 regF
            gen.recBaseMs = 0;
            gen.vtime = 0;
            gen.recN = 0;
            gen.recBytes = 0;

            Ay2Midi gen2 = null;
            boolean hasChip2 = false;

            // 4. 单遍转换主循环（与 PlayScreen.playLoop 同逻辑）
            VgmParser parser = vf.parser;
            long loopPlays = 0;
            long lastMs = 0;
            while (true) {
                int t = parser.next();
                if (t == VgmParser.EV_END) {
                    if (vf.header.hasLoop() && loopPlays < 1) {  // 只跑 1 遍（含 1 次循环）
                        parser.seek(vf.header.loopOffset);
                        loopPlays++;
                        continue;
                    }
                    break;
                }
                if (t == VgmParser.EV_REG_WRITE && parser.evCmd == 0xA0) {
                    long ms = parser.getSamples() / 44;
                    gen.vtime = ms;
                    lastMs = ms;
                    if ((parser.evReg & 0x80) != 0) {
                        if (!hasChip2) {
                            hasChip2 = true;
                            gen2 = new Ay2Midi(Ay2Midi.MAP_CHIP1);
                            gen2.RECORD_ONLY = true;
                            if (clk != 0) gen2.setClock(clk);
                            gen2.copyConfigFrom(ay2);
                            gen2.recording = true;
                            gen2.recordingReg = false;
                            gen2.recBaseMs = 0;
                            gen2.vtime = ms;
                            gen2.recN = 0;
                            gen2.recBytes = 0;
                        }
                        if (gen2 != null) {
                            gen2.write(parser.evReg & 0x7F, parser.evData);
                        }
                    } else {
                        gen.write(parser.evReg, parser.evData);
                    }
                }
                gen.tick();
                if (gen2 != null) gen2.tick();
            }

            // 5. 整曲一次性打成 SMF（复用 SmfSeg.Builder，头部 init 双芯片）
            SmfSeg.Builder sb = new SmfSeg.Builder();
            sb.init(gen.initEvents());
            if (gen2 != null) {
                sb.init(gen2.initEvents());
            }
            // 归并两路 recF（按时间，与 buildChunk 同逻辑但整曲一次）
            int i = 0, j = 0;
            int n1 = gen.recN / 4;
            int n2 = gen2 != null ? gen2.recN / 4 : 0;
            long from = 0;
            long to = lastMs + 1;
            long totalEvents = 0;
            while (i < n1 || j < n2) {
                long t1 = i < n1 ? gen.recF[i * 4] : Long.MAX_VALUE;
                long t2 = j < n2 ? gen2.recF[j * 4] : Long.MAX_VALUE;
                if (t1 <= t2) {
                    long ms = gen.recF[i * 4] - from;
                    sb.event(ms, (int) gen.recF[i * 4 + 1],
                            (int) gen.recF[i * 4 + 2], (int) gen.recF[i * 4 + 3]);
                    i++;
                } else {
                    long ms = gen2.recF[j * 4] - from;
                    sb.event(ms, (int) gen2.recF[j * 4 + 1],
                            (int) gen2.recF[j * 4 + 2], (int) gen2.recF[j * 4 + 3]);
                    j++;
                }
                totalEvents++;
            }
            byte[] smf = sb.build(to - from);

            // 6. 写 .mid 到磁盘
            String outName = f.getName().replaceAll("(?i)\\.(vgm|vgz)$", ".mid");
            File outFile = new File(outDir, outName);
            FileOutputStream fos = new FileOutputStream(outFile);
            try { fos.write(smf); } finally { fos.close(); }

            long recFBytes = (gen.recN + (gen2 != null ? gen2.recN : 0)) * 4 * 8;
            System.out.printf("%-55s %8d %10d %10d %8d %6s%n",
                f.getName(),
                raw.length / 1024,
                smf.length / 1024,
                recFBytes / 1024,
                totalEvents,
                hasChip2 ? "双" : "单");
            totMid += smf.length;
            if (hasChip2) dual++;
        }

        System.out.println("================================================================");
        System.out.printf("%-55s %8d %10d %10s %8d 曲 (%d 双芯)%n",
            "合计",
            totVgm / 1024, totMid / 1024, "-", files.length, dual);
        System.out.println();
        System.out.println("产出 .mid 在: " + outDir.getAbsolutePath());
    }

    // ---- RMS 配置读取（纯逗号串，无二进制头） ----
    private static int[] loadRmsConfig() {
        try {
            File f = new File(RMS_CFG);
            if (!f.isFile()) return null;
            byte[] d = readAll(f);
            String s = new String(d, "UTF-8").trim();
            // RMS 文件可能含尾部 record 簿记字节，截到第一个非数字非逗号字符
            int cut = s.length();
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (!Character.isDigit(c) && c != ',' && c != '-') { cut = k; break; }
            }
            s = s.substring(0, cut);
            String[] parts = s.split(",");
            int[] v = new int[parts.length];
            for (int k = 0; k < parts.length; k++) {
                v[k] = Integer.parseInt(parts[k].trim());
            }
            return v;
        } catch (Throwable t) {
            System.err.println("[cfg] RMS 读取失败: " + t + "，用默认值");
            return null;
        }
    }

    // ---- 复刻 PlayScreen.applyPersisted 的配置应用（v[] → ay/ay2）----
    private static void applyConfig(Ay2Midi ay, Ay2Midi ay2, int[] v) {
        if (v == null) {
            // 默认值：与 Ay2Midi 构造器出厂一致，不再额外设置
            return;
        }
        try {
            Ay2Midi.preferTone = (v.length > 14 && v[14] == 1);
            // 百分比与音量行为（v[24..31]）
            if (v.length > 24 && v[24] > 0) ay.setSqVolPct(v[24]);
            if (v.length > 25 && v[25] > 0) ay.setNoiseVolPct(v[25]);
            if (v.length > 26 && v[26] > 0) ay.setEnvVolPct(v[26]);
            if (v.length > 27 && v[27] >= 0) ay.setVolFollowMode(v[27]);
            if (v.length > 28 && v[28] >= 0) ay.setVolMode(v[28]);
            if (v.length > 29 && v[29] > 0) ay.setPolyLimit(v[29]);
            if (v.length > 30) ay.setMonoTone(v[30] != 0);
            if (v.length > 31 && v[31] >= 0) ay.setPolyOverflow(v[31]);
            ay.setEngineTone(Ay2Midi.preferTone);
            ay.setStereoMode(v[1]);
            ay.setOct(v[2]);
            ay.setNoiseMapMode(v[3]);
            ay.setEnvOct(false, v[4]);
            ay.setEnvOct(true, v[5]);
            ay.setEnvUni(false, v[6]);
            ay.setEnvUni(true, v[7]);
            ay.setBuzzVol(v[8]);
            ay.setBendSensPct(v[9]);
            ay.setBendRangePct(v[10]);
            for (int i = 0; i < Ay2Midi.SLOTS && 16 + i < v.length; i++) {
                ay.setProg(i, v[16 + i]);
            }
            // chip2 同步配置（双芯片曲才用到，但提前配好）
            ay2.setStereoMode(v[1]);
            ay2.setOct(v[2]);
            ay2.setNoiseMapMode(v[3]);
            ay2.setEnvOct(false, v[4]);
            ay2.setEnvOct(true, v[5]);
            ay2.setEnvUni(false, v[6]);
            ay2.setEnvUni(true, v[7]);
            ay2.setBuzzVol(v[8]);
            ay2.setBendSensPct(v[9]);
            ay2.setBendRangePct(v[10]);
            for (int i = 0; i < Ay2Midi.SLOTS && 16 + i < v.length; i++) {
                ay2.setProg(i, v[16 + i]);
            }
        } catch (Throwable t) {
            System.err.println("[cfg] 应用失败: " + t);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) throw new java.io.IOException("EOF");
                off += n;
            }
            return buf;
        } finally { in.close(); }
    }
}
