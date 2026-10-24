package com.denjhang.vgmbrowser;

import javax.microedition.rms.RecordStore;

/**
 * 设置持久化（RMS）。单记录、逗号分隔整数串。
 * 存：Ay2Midi 全部参数 + PlayScreen 的 masterVol/loopIdx/shuffle。
 * 读取失败（首次运行/损坏）返回 null，调用方用默认值。
 */
public final class SettingsStore {
    private static final String RS_NAME = "vgmbrowser_cfg";
    static final int VERSION = 4;   // 4: PC 默认值 + monoTone + polyOverflow 持久化
    /** 版本 + 13 项 + SLOTS 个音色 + 音量行为。 */
    public static final int FIELDS = 20 + 12;

    private SettingsStore() {
    }

    public static int[] load() {
        try {
            RecordStore rs = RecordStore.openRecordStore(RS_NAME, true);
            try {
                if (rs.getNumRecords() < 1) {
                    return null;
                }
                byte[] d = rs.getRecord(1);
                if (d == null || d.length == 0) {
                    return null;
                }
                String s = new String(d, "UTF-8");
                int[] v = new int[FIELDS];
                int idx = 0;
                int pos = 0;
                while (idx < FIELDS) {
                    int comma = s.indexOf(',', pos);
                    if (comma < 0) {
                        if (pos < s.length()) {
                            v[idx++] = parseInt(s.substring(pos));
                        }
                        break;
                    }
                    v[idx++] = parseInt(s.substring(pos, comma));
                    pos = comma + 1;
                }
                if (idx < FIELDS || v[0] != VERSION) {
                    return null;
                }
                return v;
            } finally {
                rs.closeRecordStore();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static void save(int[] v) {
        if (v == null || v.length < FIELDS) {
            return;
        }
        StringBuffer sb = new StringBuffer(96);
        for (int i = 0; i < FIELDS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        try {
            byte[] d = sb.toString().getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore(RS_NAME, true);
            try {
                if (rs.getNumRecords() >= 1) {
                    rs.setRecord(1, d, 0, d.length);
                } else {
                    rs.addRecord(d, 0, d.length);
                }
            } finally {
                rs.closeRecordStore();
            }
        } catch (Throwable t) {
            // RMS 写失败不影响播放
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
