package com.denjhang.vgm;

import java.io.IOException;

/**
 * 纯 Java 的 gzip (VGZ) 解码器。
 *
 * MIDP/CLDC 没有 java.util.zip，所以 DEFLATE 从头实现（puff.c 式）：
 *   - stored / fixed / dynamic 三种块
 *   - 规范哈夫曼码逐位解码
 *   - LZ77 长度/距离回拷
 * gzip 包装：10 字节头 + 可选字段（FEXTRA/FNAME/FCOMMENT/FHCRC）+ 数据 + CRC32/ISIZE（跳过不校验）。
 */
public final class Gzip {

    private Gzip() {
    }

    public static boolean isGzip(byte[] d) {
        return d != null && d.length > 18
                && (d[0] & 0xFF) == 0x1F && (d[1] & 0xFF) == 0x8B
                && (d[2] & 0xFF) == 8;
    }

    public static byte[] decode(byte[] d) throws IOException {
        int p = parseHeader(d);
        Inflater inf = new Inflater(d, p);
        byte[] out = inf.inflate();
        return out;
    }

    /** 解析 gzip 头，返回 deflate 数据起始偏移。 */
    private static int parseHeader(byte[] d) throws IOException {
        int flg = d[3] & 0xFF;
        int p = 10;
        if ((flg & 4) != 0) {                    // FEXTRA
            int xlen = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
            p += 2 + xlen;
        }
        if ((flg & 8) != 0) {                    // FNAME
            while (d[p] != 0) p++;
            p++;
        }
        if ((flg & 16) != 0) {                   // FCOMMENT
            while (d[p] != 0) p++;
            p++;
        }
        if ((flg & 2) != 0) {                    // FHCRC
            p += 2;
        }
        if (p >= d.length) {
            throw new IOException("gzip 头越界");
        }
        return p;
    }

    // ---------------------------------------------------------------- inflate

    private static final class Inflater {
        private final byte[] in;
        private int inPos;
        private int bitBuf;
        private int bitCnt;

        Inflater(byte[] in, int off) {
            this.in = in;
            this.inPos = off;
        }

        private int bits(int need) {
            int val = bitBuf;
            while (bitCnt < need) {
                if (inPos >= in.length) {
                    throw new RuntimeException("deflate 流意外结束");
                }
                val |= (in[inPos++] & 0xFF) << bitCnt;
                bitCnt += 8;
            }
            bitBuf = val >> need;
            bitCnt -= need;
            return val & ((1 << need) - 1);
        }

        private void alignByte() {
            bitBuf = 0;
            bitCnt = 0;
        }

        /** 规范哈夫曼解码：count[len]=该长度码数，symbol 按序排列。 */
        private int decode(int[] count, int[] symbol) {
            int code = 0, first = 0, index = 0;
            for (int len = 1; len <= 15; len++) {
                code |= bits(1);
                int c = count[len];
                if (code - first < c) {
                    return symbol[index + (code - first)];
                }
                index += c;
                first = (first + c) << 1;
                code <<= 1;
            }
            throw new RuntimeException("无效哈夫曼码");
        }

        private static final short[] LEN_BASE = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
        };
        private static final short[] LEN_EXTRA = {
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
        };
        private static final short[] DIST_BASE = {
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
            8193, 12289, 16385, 24577
        };
        private static final short[] DIST_EXTRA = {
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
        };
        private static final short[] CLC_ORDER = {
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
        };

        byte[] inflate() {
            // 输出缓冲即 LZ77 历史（回拷直接从 buf 取）
            GrowBuf g = new GrowBuf();
            boolean last;
            do {
                last = bits(1) != 0;
                int type = bits(2);
                if (type == 0) {
                    // stored
                    alignByte();
                    int len = bits(16);
                    int nlen = bits(16);
                    if ((len ^ 0xFFFF) != nlen) {
                        throw new RuntimeException("stored 块长度校验失败");
                    }
                    for (int i = 0; i < len; i++) {
                        g.write(bits(8));
                    }
                } else if (type == 1 || type == 2) {
                    int[] litCount, litSymbol, distCount, distSymbol;
                    if (type == 1) {
                        litCount = new int[16];
                        litSymbol = new int[288];
                        int[] lens = new int[288];
                        for (int i = 0; i < 144; i++) lens[i] = 8;
                        for (int i = 144; i < 256; i++) lens[i] = 9;
                        for (int i = 256; i < 280; i++) lens[i] = 7;
                        for (int i = 280; i < 288; i++) lens[i] = 8;
                        construct(lens, 288, litCount, litSymbol);
                        distCount = new int[16];
                        distSymbol = new int[30];
                        int[] dl = new int[30];
                        for (int i = 0; i < 30; i++) dl[i] = 5;
                        construct(dl, 30, distCount, distSymbol);
                    } else {
                        int hlit = bits(5) + 257;
                        int hdist = bits(5) + 1;
                        int hclen = bits(4) + 4;
                        int[] clLens = new int[19];
                        for (int i = 0; i < hclen; i++) {
                            clLens[CLC_ORDER[i]] = bits(3);
                        }
                        int[] clCount = new int[16];
                        int[] clSymbol = new int[19];
                        construct(clLens, 19, clCount, clSymbol);

                        int[] lens = new int[hlit + hdist];
                        int i = 0;
                        while (i < hlit + hdist) {
                            int sym = decode(clCount, clSymbol);
                            if (sym < 16) {
                                lens[i++] = sym;
                            } else if (sym == 16) {
                                if (i == 0) throw new RuntimeException("重复码无前值");
                                int prev = lens[i - 1];
                                int rep = 3 + bits(2);
                                while (rep-- > 0 && i < hlit + hdist) lens[i++] = prev;
                            } else if (sym == 17) {
                                int rep = 3 + bits(3);
                                while (rep-- > 0 && i < hlit + hdist) lens[i++] = 0;
                            } else {
                                int rep = 11 + bits(7);
                                while (rep-- > 0 && i < hlit + hdist) lens[i++] = 0;
                            }
                        }
                        litCount = new int[16];
                        litSymbol = new int[hlit];
                        construct(lens, hlit, litCount, litSymbol);
                        distCount = new int[16];
                        distSymbol = new int[hdist];
                        construct(copyOf(lens, hlit, hlit + hdist), hdist,
                                distCount, distSymbol);
                    }

                    // 解码循环
                    int symbol;
                    while ((symbol = decode(litCount, litSymbol)) != 256) {
                        if (symbol < 256) {
                            g.write(symbol);
                        } else {
                            symbol -= 257;
                            if (symbol >= 29) throw new RuntimeException("长度码越界");
                            int len = LEN_BASE[symbol] + bits(LEN_EXTRA[symbol]);
                            int dsym = decode(distCount, distSymbol);
                            if (dsym >= 30) throw new RuntimeException("距离码越界");
                            int dist = DIST_BASE[dsym] + bits(DIST_EXTRA[dsym]);
                            if (dist > g.len) throw new RuntimeException("距离超出历史");
                            int from = g.len - dist;
                            for (int k = 0; k < len; k++) {
                                g.write(g.buf[from + k]);
                            }
                        }
                    }
                } else {
                    throw new RuntimeException("非法块类型 3");
                }
            } while (!last);

            return g.toByteArray();
        }

        /**
         * 由码长构造规范哈夫曼表（puff.c 同款）。
         * count[len] 必须保持"该长度码的数量"——decode() 按此累加判断，
         * 符号散射用独立的 offs 表，不能覆盖 count。
         */
        private static void construct(int[] lens, int n, int[] count, int[] symbol) {
            for (int i = 0; i < 16; i++) count[i] = 0;
            for (int i = 0; i < n; i++) count[lens[i]]++;
            int[] offs = new int[16];
            for (int len = 1; len < 15; len++) {
                offs[len + 1] = offs[len] + count[len];
            }
            for (int i = 0; i < n; i++) {
                if (lens[i] != 0) {
                    symbol[offs[lens[i]]++] = i;
                }
            }
        }

        private static int[] copyOf(int[] src, int from, int to) {
            int[] r = new int[to - from];
            for (int i = 0; i < r.length; i++) {
                r[i] = src[from + i];
            }
            return r;
        }
    }

    /** 可增长输出缓冲（即 LZ77 历史）。 */
    private static final class GrowBuf {
        byte[] buf = new byte[1 << 16];
        int len;

        void write(int b) {
            if (len == buf.length) {
                byte[] nb = new byte[buf.length * 2];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            buf[len++] = (byte) b;
        }

        byte[] toByteArray() {
            byte[] r = new byte[len];
            System.arraycopy(buf, 0, r, 0, len);
            return r;
        }
    }
}
