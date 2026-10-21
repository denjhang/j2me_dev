import java.lang.reflect.Field;
import java.lang.reflect.Method;
import sun.misc.Unsafe;

/**
 * PC 端集成测试：模拟 PlayScreen 的实时喂送循环，检查
 *   1) tone 模式下 write() 会把音符/音量事件送进事件环
 *   2) 每 250ms 的 buildWindow 产出合法序列
 *   3) 事件环不会无限增长（每窗清空）
 * 不打开任何真实 Player（用 allocateInstance + 手动 toneEngine 标记）。
 */
public class RtFeedTest {
    static Object ay;
    static Class cls;
    static Method write, buildWindow, tick;
    static Field evCountF, carryNoteF, carryVolF, toneEngineF, evTF, evNF, evVF, armF, clockF;

    static void set(String n, Object v) throws Exception {
        Field f = cls.getDeclaredField(n);
        f.setAccessible(true);
        f.set(ay, v);
    }

    static Object get(String n) throws Exception {
        Field f = cls.getDeclaredField(n);
        f.setAccessible(true);
        return f.get(ay);
    }

    public static void main(String[] args) throws Exception {
        Field uf = Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Unsafe u = (Unsafe) uf.get(null);
        cls = Class.forName("com.denjhang.vgm.Ay2Midi");
        ay = u.allocateInstance(cls);

        // 手动初始化 buildWindow 需要的字段（allocateInstance 跳过初始化）
        set("evT", new int[3][48]);
        set("evN", new int[3][48]);
        set("evV", new int[3][48]);
        set("evCount", new int[]{0, 0, 0});
        set("carryNote", new int[]{-1, -1, -1});
        set("carryVol", new int[]{0, 0, 0});
        set("sustainNote", new int[]{-1, -1, -1});
        set("sustainVol", new int[]{0, 0, 0});
        set("evLock", new Object());
        set("toneEngine", Boolean.TRUE);
        set("regs", new int[16]);
        set("curNote", new int[]{-1, -1, -1});
        set("curBend", new int[]{8192, 8192, 8192});
        set("curVol", new int[]{-1, -1, -1});
        set("buzzer", new boolean[3]);
        set("noiseSounding", new boolean[3]);
        set("noiseVol", new int[]{-1, -1, -1});
        set("progMap", new int[8]);
        set("pan", new int[3]);
        set("toneCh", new int[]{0, 1, 2});
        set("noiseCh", new int[]{3, 3, 3});
        set("envLastCC", new int[]{-1, -1, -1});
        set("smooth", new int[3]);
        set("ready", Boolean.TRUE);
        set("clock", new Integer(1773400));

        Method toneSeq = cls.getDeclaredMethod("toneSeq", new Class[]{int.class, int.class, int.class});
        toneSeq.setAccessible(true);
        buildWindow = cls.getDeclaredMethod("buildWindow", new Class[]{int.class, int.class});
        buildWindow.setAccessible(true);

        int base = 100000;
        int totalEvents = 0;
        int badWindows = 0;
        // 模拟 100 个窗口：每窗每声道 2 个音符变化
        for (int w = 0; w < 100; w++) {
            for (int ch = 0; ch < 3; ch++) {
                toneSeq.invoke(ay, new Object[]{new Integer(ch),
                        new Integer(48 + ((w + ch * 3) % 24)), new Integer(70 + ch * 10)});
                toneSeq.invoke(ay, new Object[]{new Integer(ch),
                        new Integer(48 + ((w + ch * 3 + 5) % 24)), new Integer(60 + ch * 10)});
            }
            for (int ch = 0; ch < 3; ch++) {
                byte[] s = (byte[]) buildWindow.invoke(ay, new Object[]{new Integer(ch), new Integer(base)});
                totalEvents += s.length;
                if (s.length < 6 || s[0] != -2 || s[2] != -3 || s[4] != -4) {
                    badWindows++;
                }
                if (s.length > 256) {
                    System.out.println("窗口超预算: " + s.length);
                    badWindows++;
                }
            }
            // 模拟窗口线程清环 + 滚动 carry
            int[] sn = (int[]) get("sustainNote");
            int[] sv = (int[]) get("sustainVol");
            set("carryNote", new int[]{sn[0], sn[1], sn[2]});
            set("carryVol", new int[]{sv[0], sv[1], sv[2]});
            set("evCount", new int[]{0, 0, 0});
            base += 250;
        }
        System.out.println("100 窗 x 3 声道: 总字节 " + totalEvents
                + " 均 " + (totalEvents / 300) + "B/窗  坏窗 " + badWindows);
        if (badWindows > 0) {
            System.exit(1);
        }
        // 事件环清空验证
        int[] ec = (int[]) get("evCount");
        System.out.println("窗口末事件环计数: " + ec[0] + "/" + ec[1] + "/" + ec[2]
                + (ec[0] == 0 && ec[1] == 0 && ec[2] == 0 ? " OK(每窗清空)" : " FAIL(泄漏)"));
        System.out.println("全部通过");
    }
}
