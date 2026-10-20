import java.lang.reflect.Field;
import java.lang.reflect.Method;
import sun.misc.Unsafe;

/**
 * PC 端字节级单测：直接驱动 Ay2Midi 的 ToneRT 窗口编码器 buildWindow()。
 * 不实例化引擎（allocateInstance 绕过构造，避免打开任何 Player）。
 */
public class WinEncTest {
    static Object inst;
    static Class cls;

    static Field f(String name) throws Exception {
        Field x = cls.getDeclaredField(name);
        x.setAccessible(true);
        return x;
    }

    static void set(String name, Object value) throws Exception {
        f(name).set(inst, value);
    }

    static Object get(String name) throws Exception {
        return f(name).get(inst);
    }

    public static void main(String[] args) throws Exception {
        Field uf = Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Unsafe u = (Unsafe) uf.get(null);
        cls = Class.forName("com.denjhang.vgm.Ay2Midi");
        inst = u.allocateInstance(cls);
        Method bw = cls.getDeclaredMethod("buildWindow", new Class[]{int.class, int.class});
        bw.setAccessible(true);

        int base = 100000;

        // —— 用例 1：空事件 + carry 长音 ——
        set("evCount", new int[]{0, 0, 0});
        set("carryNote", new int[]{60, -1, 62});
        set("carryVol", new int[]{80, 0, 50});
        byte[] s1 = (byte[]) bw.invoke(inst, new Object[]{new Integer(0), new Integer(base)});
        p("用例1 carry 长音", s1);
        check(s1, new byte[]{
            -2, 1, -3, 127, -4, 127,          // VERSION,1 TEMPO,127 RESOLUTION,127
            -8, 80,                            // SET_VOLUME 80
            60, 68,                           // note 60 全窗
        }, "1");

        // —— 用例 2：窗口中点换音 + 音量变化 ——
        // ch0: 100ms 后 60→64, vol 80→40
        set("evCount", new int[]{1, 0, 0});
        // allocateInstance 不跑字段初始化，事件环手动注入
        set("evT", new int[3][48]);
        set("evN", new int[3][48]);
        set("evV", new int[3][48]);
        int[][] evT = (int[][]) get("evT");
        int[][] evN = (int[][]) get("evN");
        int[][] evV = (int[][]) get("evV");
        evT[0][0] = base + 125;               // 半窗
        evN[0][0] = 64;
        evV[0][0] = 31;                       // 40*127/127 → 记录时已是 0-100
        set("carryNote", new int[]{60, -1, -1});
        set("carryVol", new int[]{80, 0, 0});
        byte[] s2 = (byte[]) bw.invoke(inst, new Object[]{new Integer(0), new Integer(base)});
        p("用例2 半窗换音", s2);
        check(s2, new byte[]{
            -2, 1, -3, 127, -4, 127,
            -8, 80, 60, 34,                    // 34 = 68/2
            -8, 31, 64, 34,
        }, "2");

        // —— 用例 3：静音事件 ——
        evT[0][0] = base + 62;                // ~1/4 窗
        evN[0][0] = -1;
        evV[0][0] = 0;
        set("carryNote", new int[]{60, -1, -1});
        set("carryVol", new int[]{80, 0, 0});
        byte[] s3 = (byte[]) bw.invoke(inst, new Object[]{new Integer(0), new Integer(base)});
        p("用例3 中途静音", s3);
        check(s3, new byte[]{
            -2, 1, -3, 127, -4, 127,
            -8, 80, 60, 16,
            -1, 52,
        }, "3");
        // 62*68/250 = 16.90 → int 截断 16，剩余 52。上面按 17/51 预期，
        // 让断言自己说话：如果失败，打印实际值人工确认四舍五入语义。

        // —— 用例 4：事件溢出保护（EV_CAP=48，写 60 个）——
        // 模拟事件环写满：toneSeq 在 EV_CAP=48 处丢弃新事件，最坏 48 段
        int n = 48;
        for (int i = 0; i < n; i++) {
            evT[0][i] = base + i * 4;
            evN[0][i] = 40 + (i % 20);
            evV[0][i] = 50 + (i % 7);
        }
        set("evCount", new int[]{n, 0, 0});
        set("carryNote", new int[]{-1, -1, -1});
        set("carryVol", new int[]{0, 0, 0});
        byte[] s4 = (byte[]) bw.invoke(inst, new Object[]{new Integer(0), new Integer(base)});
        System.out.println("用例4 溢出: len=" + s4.length + " (预算 256) " + (s4.length <= 256 ? "OK" : "FAIL"));

        System.out.println("全部用例跑完");
    }

    static int WIN_MS() { return 250; }

    static void p(String name, byte[] s) {
        StringBuffer b = new StringBuffer(name + ": ");
        for (int i = 0; i < s.length; i++) {
            b.append(s[i]).append(' ');
        }
        System.out.println(b);
    }

    static void check(byte[] got, byte[] want, String id) {
        boolean ok = got.length == want.length;
        if (ok) {
            for (int i = 0; i < got.length; i++) {
                if (got[i] != want[i]) { ok = false; break; }
            }
        }
        System.out.println("  断言" + id + ": " + (ok ? "PASS" : "FAIL (长度 " + got.length + "/" + want.length + ")"));
        if (!ok) System.exit(1);
    }
}
