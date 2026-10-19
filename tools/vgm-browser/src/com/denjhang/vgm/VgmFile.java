package com.denjhang.vgm;

import java.io.IOException;

/**
 * 一个已加载的 VGM 文件：原始字节 + 头部 + 解析器。
 *
 * 第一阶段只负责读入和解析，不产生任何音频。
 */
public class VgmFile {

    public final String name;
    public final int byteSize;
    private final byte[] data;
    public final VgmHeader header;
    public final VgmParser parser;

    private VgmFile(String name, byte[] data, VgmHeader header) {
        this.name = name;
        this.data = data;
        this.byteSize = data.length;
        this.header = header;
        this.parser = new VgmParser(data, header);
    }

    public static VgmFile fromBytes(String name, byte[] data) {
        // VGZ = gzip 包装的 VGM（MIDP 无 zip 库，用自带 inflate 解开）
        if (Gzip.isGzip(data)) {
            try {
                data = Gzip.decode(data);
            } catch (Throwable t) {
                throw new IllegalArgumentException("VGZ 解压失败: " + t);
            }
        }
        VgmHeader h = VgmHeader.parse(data);
        return new VgmFile(name, data, h);
    }

    /** 跑一遍全曲统计（分段调用，避免长时间占用 UI 线程）。 */
    public void scan() {
        parser.scanAll();
    }

    /** 解析器可用位置，供"命令流"页逐条查看。 */
    public VgmParser getParser() {
        return parser;
    }

    public byte[] getData() {
        return data;
    }

    /** 从原始字节里取某段做十六进制显示。 */
    public String hexAt(int offset, int length, int maxBytes) {
        if (data == null || offset < 0 || offset >= data.length) {
            return "";
        }
        int n = length;
        if (n > maxBytes) {
            n = maxBytes;
        }
        if (offset + n > data.length) {
            n = data.length - offset;
        }
        StringBuffer sb = new StringBuffer(n * 3);
        for (int i = 0; i < n; i++) {
            int b = data[offset + i] & 0xFF;
            if (i > 0) {
                sb.append(' ');
            }
            if (b < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(b).toUpperCase());
        }
        return sb.toString();
    }

    public static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(65536);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
